package org.wiicompiled.portlab;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.xmlpull.v1.XmlPullParser;
import android.util.Xml;

/** Android storage/profile half of WheelWizard's mod manager. */
final class AndroidModManager {
    private static final String ROOT = "wheelwizard/mods", META = "mod.properties";
    private static final String PATCH_META = "archive-patches.properties", COMPOSED = "wheelwizard/composed";
    private static final String RIIVO_CHOICES = "riivolution-choices.properties";
    private static final String BRSAR_TARGET = "sound/revo_kart.brsar";
    private static final int MAX_ENTRIES = 20_000;
    private static final long MAX_ENTRY_BYTES = 512L * 1024 * 1024;
    private static final long MAX_TOTAL_BYTES = 2L * 1024 * 1024 * 1024;
    // Official managed distributions can legitimately contain a larger virtual SD. These
    // remain finite and are available only to package-local distribution installers.
    static final long MAX_DISTRIBUTION_ENTRY_BYTES = 2L * 1024 * 1024 * 1024;
    static final long MAX_DISTRIBUTION_TOTAL_BYTES = 12L * 1024 * 1024 * 1024;

    static final class Mod {
        final File directory;
        final String id, title, author, overlayRoot, riivolutionRoot, remoteVersion;
        final String distributionId, distributionVersion;
        final int remoteId;
        final long priority;
        final int mappedFiles, skippedFiles, archivePatchFiles, unsupportedCodeFiles, unsupportedSoundPatches;
        final List<ArchivePatch> archivePatches;
        final CodePatchRegistry.Report codeReport;
        boolean enabled;
        Mod(File directory, String id, String title, String author, String overlayRoot, String riivolutionRoot,
            int remoteId, String remoteVersion, long priority,
            String distributionId, String distributionVersion,
            boolean enabled, int mappedFiles, int skippedFiles, int unsupportedCodeFiles,
            int unsupportedSoundPatches, List<ArchivePatch> archivePatches,
            CodePatchRegistry.Report codeReport) {
            this.directory = directory; this.id = id; this.title = title; this.author = author; this.overlayRoot = overlayRoot;
            this.riivolutionRoot = riivolutionRoot;
            this.remoteId = remoteId; this.remoteVersion = remoteVersion;
            this.distributionId = distributionId; this.distributionVersion = distributionVersion;
            this.priority = priority; this.enabled = enabled; this.mappedFiles = mappedFiles; this.skippedFiles = skippedFiles;
            this.unsupportedCodeFiles = unsupportedCodeFiles; this.unsupportedSoundPatches = unsupportedSoundPatches;
            this.archivePatches = archivePatches; this.archivePatchFiles = archivePatches.size();
            this.codeReport = codeReport;
        }
    }

    private static final class ArchivePatch {
        final File file; final String target, member;
        ArchivePatch(File file, String target, String member) {
            this.file = file; this.target = target; this.member = member;
        }
    }
    private static final class LoosePatch {
        final String target, member;
        LoosePatch(String target, String member) { this.target = target; this.member = member; }
    }
    private record WheelMetadata(String name, String author) { }
    private record RiivolutionAction(boolean folder, String disc, String external, boolean recursive,
                                     String xmlDirectory, String patchRoot, boolean supported,
                                     boolean codePatch, long offset, long fileOffset, long length,
                                     boolean resize, boolean create) { }
    private record SelectedRiivolutionPatch(String id, Map<String, String> parameters) { }

    static final class RiivolutionOption {
        final String id, name; final List<String> choices; final int selected;
        RiivolutionOption(String id, String name, List<String> choices, int selected) {
            this.id = id; this.name = name; this.choices = choices; this.selected = selected;
        }
    }

    static final class ImportResult {
        final Mod mod;
        final String message;
        ImportResult(Mod mod, String message) { this.mod = mod; this.message = message; }
    }

    static List<Mod> list(Context context) {
        List<Mod> mods = new ArrayList<>();
        File[] directories = root(context).listFiles(File::isDirectory);
        if (directories != null) for (File directory : directories) {
            Mod mod = load(directory); if (mod != null) mods.add(mod);
        }
        mods.sort(Comparator.comparingLong((Mod mod) -> mod.priority).reversed());
        return mods;
    }

    static ImportResult importZip(Context context, Uri uri, File discFiles) throws IOException {
        return importZip(context, uri, discFiles, MAX_ENTRY_BYTES, MAX_TOTAL_BYTES);
    }

    static ImportResult importDistributionZip(Context context, Uri uri, File discFiles) throws IOException {
        return importZip(context, uri, discFiles, MAX_DISTRIBUTION_ENTRY_BYTES, MAX_DISTRIBUTION_TOTAL_BYTES, false);
    }

    static ImportResult importRetroRewindDistribution(Context context, Uri uri, File discFiles) throws IOException {
        return importZip(context, uri, discFiles, MAX_DISTRIBUTION_ENTRY_BYTES, MAX_DISTRIBUTION_TOTAL_BYTES, true);
    }

    private static ImportResult importZip(Context context, Uri uri, File discFiles,
                                          long maximumEntryBytes, long maximumTotalBytes) throws IOException {
        return importZip(context, uri, discFiles, maximumEntryBytes, maximumTotalBytes, false);
    }

    private static ImportResult importZip(Context context, Uri uri, File discFiles,
                                          long maximumEntryBytes, long maximumTotalBytes,
                                          boolean preserveRetroRewindVirtualSd) throws IOException {
        if (!discFiles.isDirectory()) throw new IOException("Extracted game files are unavailable");
        String displayName = displayName(context, uri);
        String title = displayName.replaceFirst("(?i)\\.zip$", "").trim();
        if (title.isEmpty()) title = "Imported mod";
        String stem = title.toLowerCase(Locale.US).replaceAll("[^a-z0-9._-]+", "-")
            .replaceAll("^-+|-+$", "");
        if (stem.isEmpty()) stem = "mod";
        long priority = System.currentTimeMillis();
        String id = stem + "-" + priority;
        File destination = new File(root(context), id), extracted = new File(destination, "package");
        if (!extracted.mkdirs()) throw new IOException("Cannot create private mod directory");
        try {
            extractSafely(context, uri, extracted, maximumEntryBytes, maximumTotalBytes);
            WheelMetadata metadata = readWheelMetadata(extracted);
            if (!metadata.name().isEmpty()) title = metadata.name();
            File virtualSdRoot = findVirtualSdRoot(extracted);
            File overlay;
            int mapped, skipped, unsupportedCode = 0, unsupportedSound = 0;
            String kind;
            if (virtualSdRoot != null) {
                if (preserveRetroRewindVirtualSd) {
                    // The pinned Retro runtime loads RetroRewind6/xml/RetroRewind6.xml itself.
                    // Its parent is the virtual SD root, which also carries riivolution/config.
                    // Do not require generic riivolution/*.xml redirects before retaining that layout.
                    overlay = caseInsensitiveChild(virtualSdRoot, "RetroRewind6");
                    if (!overlay.isDirectory()) throw new IOException("Retro Rewind virtual SD lacks RetroRewind6");
                    mapped = countFiles(overlay); skipped = 0;
                    if (mapped == 0) throw new IOException("Retro Rewind virtual SD contains no pack files");
                    kind = "Retro Rewind virtual-SD pack";
                    CodePatchRegistry.inspectAndSave(destination, overlay);
                } else {
                    overlay = new File(destination, "overlay");
                    if (!overlay.mkdirs()) throw new IOException("Cannot create Riivolution overlay");
                    int[] counts = buildRiivolutionOverlay(virtualSdRoot, overlay, discFiles);
                    mapped = counts[0]; skipped = counts[1]; unsupportedCode = counts[2];
                    File patchDirectory = new File(destination, "archive-patches");
                    int convertedArchives = convertOverlaySzsToPatches(overlay, patchDirectory, discFiles);
                    if (mapped == 0) {
                        String reason = "No default Riivolution file or folder redirects could be applied";
                        if (unsupportedCode > 0) reason += ". " + unsupportedCode + " code patch(es) require a new Android recompilation";
                        throw new IOException(reason);
                    }
                    kind = convertedArchives == 0 ? "Riivolution pack" : "Riivolution/tagged archive pack";
                    CodePatchRegistry.inspectAndSave(destination, virtualSdRoot);
                }
            } else {
                overlay = new File(destination, "overlay");
                if (!overlay.mkdirs()) throw new IOException("Cannot create mod overlay");
                File patchDirectory = new File(destination, "archive-patches");
                int[] counts = buildDiscOverlay(extracted, overlay, patchDirectory, discFiles);
                mapped = counts[0]; skipped = counts[1]; unsupportedCode = counts[3]; unsupportedSound = counts[4];
                kind = counts[2] == 0
                    ? "file replacement mod" : "file replacement/tagged archive mod";
                if (mapped == 0) {
                    String reason = "No files or tagged SZS patches match this PAL disc";
                    if (counts[3] > 0) reason += ". " + counts[3] + " translated-code replacement(s) require a new Android recompilation";
                    if (counts[4] > 0) reason += ". " + counts[4] + " revo_kart sound patch(es) require the BRSAR patch stage";
                    throw new IOException(reason);
                }
                CodePatchRegistry.inspectAndSave(destination, extracted);
                deleteRecursively(extracted);
            }
            Properties values = new Properties();
            values.setProperty("id", id); values.setProperty("title", title);
            values.setProperty("author", metadata.author());
            values.setProperty("priority", Long.toString(priority)); values.setProperty("enabled", "true");
            values.setProperty("overlayRoot", overlay.getCanonicalPath());
            values.setProperty("riivolutionRoot", virtualSdRoot == null ? "" : virtualSdRoot.getCanonicalPath());
            values.setProperty("mappedFiles", Integer.toString(mapped));
            values.setProperty("skippedFiles", Integer.toString(skipped));
            values.setProperty("unsupportedCodeFiles", Integer.toString(unsupportedCode));
            values.setProperty("unsupportedSoundPatches", Integer.toString(unsupportedSound));
            save(destination, values);
            Mod mod = load(destination);
            if (mod == null) throw new IOException("Imported mod metadata could not be reopened");
            rebuildEnabledOverlays(context);
            String message = "Imported " + title + " as a " + kind + ": " + mapped + " usable file(s)";
            if (mod.archivePatchFiles > 0) message += " including " + mod.archivePatchFiles + " tagged archive patch file(s)";
            if (skipped > 0) message += ", " + skipped + " unsupported or unmatched file(s) skipped";
            if (unsupportedCode > 0) message += ". " + unsupportedCode + " code replacement(s) need recompilation";
            if (unsupportedSound > 0) message += ". " + unsupportedSound + " tagged sound patch(es) need BRSAR support";
            message += ". Restart the game to apply it.";
            return new ImportResult(mod, message);
        } catch (IOException error) {
            deleteRecursively(destination);
            try { rebuildEnabledOverlays(context); }
            catch (IOException recoveryError) { error.addSuppressed(recoveryError); }
            throw error;
        }
    }

    static void setEnabled(Context context, Mod mod, boolean enabled) throws IOException {
        Properties values = readProperties(mod.directory);
        String previous = values.getProperty("enabled", "true");
        values.setProperty("enabled", Boolean.toString(enabled)); save(mod.directory, values);
        try {
            rebuildEnabledOverlays(context); mod.enabled = enabled;
        } catch (IOException error) {
            values.setProperty("enabled", previous); save(mod.directory, values); throw error;
        }
    }

    static boolean movePriority(Context context, Mod mod, boolean higher) throws IOException {
        List<Mod> mods = list(context);
        int index = -1;
        for (int current = 0; current < mods.size(); current++)
            if (mods.get(current).id.equals(mod.id)) { index = current; break; }
        int otherIndex = higher ? index - 1 : index + 1;
        if (index < 0 || otherIndex < 0 || otherIndex >= mods.size()) return false;
        Mod other = mods.get(otherIndex);
        Properties modValues = readProperties(mod.directory), otherValues = readProperties(other.directory);
        modValues.setProperty("priority", Long.toString(other.priority));
        otherValues.setProperty("priority", Long.toString(mod.priority));
        save(mod.directory, modValues);
        try {
            save(other.directory, otherValues);
            rebuildEnabledOverlays(context);
        }
        catch (IOException error) {
            modValues.setProperty("priority", Long.toString(mod.priority));
            otherValues.setProperty("priority", Long.toString(other.priority));
            save(mod.directory, modValues);
            save(other.directory, otherValues);
            throw error;
        }
        return true;
    }

    static void delete(Context context, Mod mod) throws IOException {
        String allowed = mod.directory.getParentFile().getCanonicalPath() + File.separator;
        if (!mod.directory.getCanonicalPath().startsWith(allowed)) throw new IOException("Unsafe mod path");
        deleteRecursively(mod.directory);
        if (mod.directory.exists()) throw new IOException("Could not remove mod directory");
        rebuildEnabledOverlays(context);
    }

    static void prepareEnvironment(Context context) throws IOException {
        List<Mod> mods = list(context);
        File external = context.getExternalFilesDir(null);
        if (external == null) throw new IOException("External app storage unavailable");
        File composed = composeTaggedArchives(context, mods, new File(external, "game/disc/files"));
        StringBuilder roots = new StringBuilder();
        if (composed != null) roots.append(composed.getAbsolutePath());
        for (Mod mod : mods) if (mod.enabled) {
            File root = new File(mod.overlayRoot);
            if (!root.isDirectory()) continue;
            if (roots.length() != 0) roots.append(';');
            roots.append(root.getAbsolutePath());
        }
        NativeProbe.configureModOverlays(roots.length() == 0 ? null : roots.toString());
    }

    static String runtimeLibrary(Context context) throws IOException {
        return CodePatchRegistry.selectRuntime(list(context));
    }

    static void rebuildEnabledOverlays(Context context) throws IOException {
        File external = context.getExternalFilesDir(null);
        if (external == null) throw new IOException("External app storage unavailable");
        composeTaggedArchives(context, list(context), new File(external, "game/disc/files"));
    }

    static void attachRemoteMetadata(Mod mod, int remoteId, String version, String author) throws IOException {
        if (remoteId <= 0) throw new IOException("Invalid remote mod ID");
        Properties values = readProperties(mod.directory);
        values.setProperty("remoteId", Integer.toString(remoteId));
        values.setProperty("remoteVersion", cleanMetadataValue(version == null ? "" : version));
        if ((values.getProperty("author", "").isEmpty() || values.getProperty("author").equals("-1"))
            && author != null && !author.isBlank()) values.setProperty("author", cleanMetadataValue(author));
        save(mod.directory, values);
    }

    static void replaceRemoteProfile(Context context, Mod previous, Mod replacement) throws IOException {
        if (previous.remoteId <= 0 || replacement.remoteId != previous.remoteId)
            throw new IOException("Remote profile IDs do not match");
        replaceProfile(context, previous, replacement);
    }

    static void attachDistributionMetadata(Mod mod, String distributionId, String version) throws IOException {
        if (distributionId == null || !distributionId.matches("[a-z0-9-]{1,64}"))
            throw new IOException("Invalid distribution ID");
        Properties values = readProperties(mod.directory);
        values.setProperty("distributionId", distributionId);
        values.setProperty("distributionVersion", cleanMetadataValue(version == null ? "" : version));
        save(mod.directory, values);
    }

    static void replaceProfile(Context context, Mod previous, Mod replacement) throws IOException {
        Properties values = readProperties(replacement.directory);
        values.setProperty("priority", Long.toString(previous.priority));
        values.setProperty("enabled", Boolean.toString(previous.enabled));
        save(replacement.directory, values);
        // Keep the freshly imported profile if removal fails; losing both versions would be worse.
        delete(context, previous);
    }

    static List<RiivolutionOption> riivolutionOptions(Mod mod) throws IOException {
        if (mod.riivolutionRoot.isEmpty()) return Collections.emptyList();
        File sdRoot = new File(mod.riivolutionRoot), riivolution = new File(sdRoot, "riivolution");
        Map<String, Integer> configured = new HashMap<>(readRiivolutionConfig(riivolution));
        configured.putAll(readRiivolutionChoices(mod.directory));
        List<File> xmlFiles = allFiles(riivolution);
        xmlFiles.removeIf(file -> !file.getName().toLowerCase(Locale.US).endsWith(".xml")
            || relative(riivolution, file).replace('\\', '/').toLowerCase(Locale.US).startsWith("config/"));
        xmlFiles.sort(Comparator.comparing(file -> relative(riivolution, file)));
        List<RiivolutionOption> options = new ArrayList<>();
        for (File xml : xmlFiles) options.addAll(readRiivolutionOptionMetadata(xml, configured));
        return Collections.unmodifiableList(options);
    }

    static void setRiivolutionChoices(Context context, Mod mod, Map<String, Integer> choices) throws IOException {
        if (mod.riivolutionRoot.isEmpty()) throw new IOException("This profile is not a Riivolution pack");
        Map<String, RiivolutionOption> available = new HashMap<>();
        for (RiivolutionOption option : riivolutionOptions(mod)) available.put(option.id, option);
        for (Map.Entry<String, Integer> choice : choices.entrySet()) {
            RiivolutionOption option = available.get(choice.getKey());
            if (option == null || choice.getValue() < 0 || choice.getValue() >= option.choices.size())
                throw new IOException("Invalid Riivolution choice: " + choice.getKey());
        }

        File external = context.getExternalFilesDir(null);
        if (external == null) throw new IOException("External app storage unavailable");
        File discFiles = new File(external, "game/disc/files");
        File staging = new File(mod.directory, "rebuild-staging"), backup = new File(mod.directory, "rebuild-backup");
        deleteRecursively(staging); deleteRecursively(backup);
        File stageOverlay = new File(staging, "overlay"), stagePatches = new File(staging, "archive-patches");
        if (!stageOverlay.mkdirs()) throw new IOException("Cannot create Riivolution rebuild staging directory");
        int[] counts = buildRiivolutionOverlay(new File(mod.riivolutionRoot), stageOverlay, discFiles, choices);
        convertOverlaySzsToPatches(stageOverlay, stagePatches, discFiles);
        if (counts[0] == 0) { deleteRecursively(staging); throw new IOException("Selected options produce no usable file redirects"); }

        if (!backup.mkdirs()) { deleteRecursively(staging); throw new IOException("Cannot create Riivolution rollback directory"); }
        File overlay = new File(mod.directory, "overlay"), patches = new File(mod.directory, "archive-patches");
        File manifest = new File(mod.directory, PATCH_META), choiceFile = new File(mod.directory, RIIVO_CHOICES);
        File metadata = new File(mod.directory, META);
        try {
            moveIfExists(overlay, new File(backup, "overlay"));
            moveIfExists(patches, new File(backup, "archive-patches"));
            moveIfExists(manifest, new File(backup, PATCH_META));
            moveIfExists(choiceFile, new File(backup, RIIVO_CHOICES));
            Files.copy(metadata.toPath(), new File(backup, META).toPath(), StandardCopyOption.REPLACE_EXISTING);
            moveIfExists(stageOverlay, overlay); moveIfExists(stagePatches, patches);
            moveIfExists(new File(staging, PATCH_META), manifest);

            Properties values = readProperties(mod.directory);
            values.setProperty("mappedFiles", Integer.toString(counts[0]));
            values.setProperty("skippedFiles", Integer.toString(counts[1]));
            values.setProperty("unsupportedCodeFiles", Integer.toString(counts[2]));
            save(mod.directory, values); saveRiivolutionChoices(mod.directory, choices);
            rebuildEnabledOverlays(context);
            deleteRecursively(backup); deleteRecursively(staging);
        } catch (IOException | RuntimeException error) {
            deleteRecursively(overlay); deleteRecursively(patches); manifest.delete(); choiceFile.delete();
            moveIfExists(new File(backup, "overlay"), overlay);
            moveIfExists(new File(backup, "archive-patches"), patches);
            moveIfExists(new File(backup, PATCH_META), manifest);
            moveIfExists(new File(backup, RIIVO_CHOICES), choiceFile);
            File oldMetadata = new File(backup, META);
            if (oldMetadata.isFile()) Files.copy(oldMetadata.toPath(), metadata.toPath(), StandardCopyOption.REPLACE_EXISTING);
            try { rebuildEnabledOverlays(context); } catch (IOException recovery) { error.addSuppressed(recovery); }
            deleteRecursively(backup); deleteRecursively(staging); throw error;
        }
    }

    private static File root(Context context) {
        File root = new File(context.getFilesDir(), ROOT); root.mkdirs(); return root;
    }

    private static Mod load(File directory) {
        try {
            Properties values = readProperties(directory);
            String id = values.getProperty("id", directory.getName());
            String title = values.getProperty("title", id);
            String author = values.getProperty("author", "");
            String overlayRoot = values.getProperty("overlayRoot", "");
            String riivolutionRoot = values.getProperty("riivolutionRoot", "");
            File overlay = new File(overlayRoot);
            String allowed = directory.getCanonicalPath() + File.separator;
            if (!overlay.getCanonicalPath().startsWith(allowed) || !overlay.isDirectory()) return null;
            if (!riivolutionRoot.isEmpty()) {
                File source = new File(riivolutionRoot);
                if (!source.getCanonicalPath().startsWith(allowed) || !source.isDirectory()) return null;
                riivolutionRoot = source.getCanonicalPath();
            }
            CodePatchRegistry.Report codeReport = CodePatchRegistry.load(directory);
            if (!riivolutionRoot.isEmpty() && !new File(directory, CodePatchRegistry.MANIFEST).isFile())
                codeReport = CodePatchRegistry.inspectAndSave(directory, new File(riivolutionRoot));
            return new Mod(directory, id, title, author, overlay.getCanonicalPath(), riivolutionRoot,
                Integer.parseInt(values.getProperty("remoteId", "-1")), values.getProperty("remoteVersion", ""),
                Long.parseLong(values.getProperty("priority", "0")),
                values.getProperty("distributionId", ""), values.getProperty("distributionVersion", ""),
                Boolean.parseBoolean(values.getProperty("enabled", "true")),
                Integer.parseInt(values.getProperty("mappedFiles", "0")),
                Integer.parseInt(values.getProperty("skippedFiles", "0")),
                Integer.parseInt(values.getProperty("unsupportedCodeFiles", "0")),
                Integer.parseInt(values.getProperty("unsupportedSoundPatches", "0")), loadArchivePatches(directory),
                codeReport);
        } catch (Exception ignored) { return null; }
    }

    private static Properties readProperties(File directory) throws IOException {
        Properties values = new Properties();
        try (InputStream input = new FileInputStream(new File(directory, META))) { values.load(input); }
        return values;
    }

    private static void save(File directory, Properties values) throws IOException {
        File target = new File(directory, META), temporary = new File(directory, META + ".tmp");
        try (OutputStream output = new FileOutputStream(temporary)) {
            values.store(output, "WheelWizard-compatible Android mod profile");
        }
        try {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static int[] buildDiscOverlay(File extracted, File overlay, File discFiles) throws IOException {
        int[] counts = buildDiscOverlay(extracted, overlay,
            new File(overlay.getParentFile(), "archive-patches"), discFiles);
        return new int[]{counts[0], counts[1]};
    }

    private static int[] buildDiscOverlay(File extracted, File overlay, File patchDirectory,
                                          File discFiles) throws IOException {
        Map<String, List<File>> byName = new HashMap<>();
        indexDiscFiles(discFiles, byName);
        int mapped = 0, skipped = 0, patchCount = 0, unsupportedCode = 0, unsupportedSound = 0;
        Properties patches = new Properties();
        List<File> sources = allFiles(extracted);
        sources.sort(Comparator.comparing(file -> relative(extracted, file)));
        for (File source : sources) {
            String lowerName = source.getName().toLowerCase(Locale.US);
            if (lowerName.endsWith(".ini")) continue;
            if (lowerName.endsWith(".dol") || lowerName.endsWith(".rel")) {
                skipped++; unsupportedCode++; continue;
            }
            String packagePath = relative(extracted, source).replace('\\', '/');
            String candidate = stripDiscPrefix(packagePath);
            File baseCandidate = new File(discFiles, candidate);
            String discRelative = null;
            if (baseCandidate.isFile()) discRelative = relative(discFiles, baseCandidate);
            if (discRelative == null) {
                List<File> matches = byName.get(source.getName().toLowerCase(Locale.US));
                if (matches != null && matches.size() == 1) discRelative = relative(discFiles, matches.get(0));
            }
            if (discRelative == null) {
                if (lowerName.endsWith(".revo_kart.szs")) {
                    Map<String, byte[]> soundMembers = WheelArchive.decode(source);
                    WheelBrsar.normalizeOverrides(soundMembers);
                    if (!safeChild(discFiles, BRSAR_TARGET).isFile())
                        throw new IOException("PAL revo_kart.brsar is unavailable");
                    if (!patchDirectory.isDirectory() && !patchDirectory.mkdirs())
                        throw new IOException("Cannot create tagged sound patch directory");
                    String storedName = String.format(Locale.US, "%04d.szs", patchCount);
                    Files.copy(source.toPath(), new File(patchDirectory, storedName).toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
                    patches.setProperty("patch." + patchCount + ".file", "archive-patches/" + storedName);
                    patches.setProperty("patch." + patchCount + ".target", BRSAR_TARGET);
                    patchCount++; mapped++; continue;
                }
                LoosePatch looseSound = resolveLooseBrsarPatch(source);
                if (looseSound != null) {
                    WheelBrsar.normalizeOverrides(java.util.Map.of(looseSound.member, Files.readAllBytes(source.toPath())));
                    if (!patchDirectory.isDirectory() && !patchDirectory.mkdirs())
                        throw new IOException("Cannot create tagged sound patch directory");
                    String storedName = String.format(Locale.US, "%04d.bin", patchCount);
                    Files.copy(source.toPath(), new File(patchDirectory, storedName).toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
                    patches.setProperty("patch." + patchCount + ".file", "archive-patches/" + storedName);
                    patches.setProperty("patch." + patchCount + ".target", looseSound.target);
                    patches.setProperty("patch." + patchCount + ".member", looseSound.member);
                    patchCount++; mapped++; continue;
                }
                String patchTarget = resolveTaggedTarget(source, packagePath, discFiles, byName);
                if (patchTarget != null) {
                    // Decode at import so malformed archives never become an enabled profile.
                    WheelArchive.decode(source);
                    if (!patchDirectory.isDirectory() && !patchDirectory.mkdirs())
                        throw new IOException("Cannot create tagged archive patch directory");
                    String storedName = String.format(Locale.US, "%04d.szs", patchCount);
                    Files.copy(source.toPath(), new File(patchDirectory, storedName).toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
                    patches.setProperty("patch." + patchCount + ".file", "archive-patches/" + storedName);
                    patches.setProperty("patch." + patchCount + ".target", patchTarget.replace('\\', '/'));
                    patchCount++; mapped++; continue;
                }
                LoosePatch loose = resolveLoosePatch(source, packagePath, discFiles, byName);
                if (loose != null) {
                    if (!patchDirectory.isDirectory() && !patchDirectory.mkdirs())
                        throw new IOException("Cannot create tagged archive patch directory");
                    String storedName = String.format(Locale.US, "%04d.bin", patchCount);
                    Files.copy(source.toPath(), new File(patchDirectory, storedName).toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
                    patches.setProperty("patch." + patchCount + ".file", "archive-patches/" + storedName);
                    patches.setProperty("patch." + patchCount + ".target", loose.target);
                    patches.setProperty("patch." + patchCount + ".member", loose.member);
                    patchCount++; mapped++; continue;
                }
            }
            if (discRelative == null) { skipped++; continue; }
            if (lowerName.endsWith(".szs")) {
                Map<String, byte[]> difference = buildSzsDifference(new File(discFiles, discRelative), source);
                if (difference != null) {
                    if (difference.isEmpty()) { skipped++; continue; }
                    if (!patchDirectory.isDirectory() && !patchDirectory.mkdirs())
                        throw new IOException("Cannot create converted archive patch directory");
                    String storedName = String.format(Locale.US, "%04d.szs", patchCount);
                    Files.write(new File(patchDirectory, storedName).toPath(), WheelArchive.buildYaz0(difference));
                    patches.setProperty("patch." + patchCount + ".file", "archive-patches/" + storedName);
                    patches.setProperty("patch." + patchCount + ".target", discRelative.replace('\\', '/'));
                    patchCount++; mapped++; continue;
                }
            }
            File target = new File(overlay, discRelative);
            String root = overlay.getCanonicalPath() + File.separator;
            if (!target.getCanonicalPath().startsWith(root)) throw new IOException("Unsafe mapped mod path");
            File parent = target.getParentFile(); if (!parent.mkdirs() && !parent.isDirectory()) throw new IOException("Cannot create mod directory");
            Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            mapped++;
        }
        if (patchCount > 0) {
            patches.setProperty("count", Integer.toString(patchCount));
            File manifest = new File(patchDirectory.getParentFile(), PATCH_META);
            try (OutputStream output = new FileOutputStream(manifest)) {
                patches.store(output, "WheelWizard tagged SZS patch targets");
            }
        }
        return new int[]{mapped, skipped, patchCount, unsupportedCode, unsupportedSound};
    }

    private static Map<String, byte[]> buildSzsDifference(File base, File modded) {
        try {
            Map<String, byte[]> cleanMembers = WheelArchive.decode(base);
            Map<String, byte[]> moddedMembers = WheelArchive.decode(modded);
            Map<String, byte[]> difference = new LinkedHashMap<>();
            for (Map.Entry<String, byte[]> entry : moddedMembers.entrySet()) {
                String cleanPath = findArchiveMember(cleanMembers, entry.getKey());
                byte[] clean = cleanPath == null ? null : cleanMembers.get(cleanPath);
                if (clean == null || !java.util.Arrays.equals(clean, entry.getValue()))
                    difference.put(cleanPath == null ? entry.getKey() : cleanPath, entry.getValue());
            }
            for (String path : cleanMembers.keySet()) if (findArchiveMember(moddedMembers, path) == null)
                difference.put(path + ".delete", new byte[0]);
            return difference;
        } catch (IOException ignored) {
            // Some SZS-named resources are raw or use a different container. Keep those whole.
            return null;
        }
    }

    private static int[] buildRiivolutionOverlay(File sdRoot, File overlay, File discFiles) throws IOException {
        return buildRiivolutionOverlay(sdRoot, overlay, discFiles, Collections.emptyMap());
    }

    private static int[] buildRiivolutionOverlay(File sdRoot, File overlay, File discFiles,
                                                  Map<String, Integer> overrides) throws IOException {
        File riivolution = new File(sdRoot, "riivolution");
        Map<String, Integer> configuredChoices = new HashMap<>(readRiivolutionConfig(riivolution));
        configuredChoices.putAll(overrides);
        List<File> xmlFiles = allFiles(riivolution);
        xmlFiles.removeIf(file -> !file.getName().toLowerCase(Locale.US).endsWith(".xml")
            || relative(riivolution, file).replace('\\', '/').toLowerCase(Locale.US).startsWith("config/"));
        xmlFiles.sort(Comparator.comparing(file -> relative(riivolution, file)));
        if (xmlFiles.isEmpty()) throw new IOException("Riivolution folder contains no XML patch definition");

        int mapped = 0, skipped = 0, unsupportedCode = 0;
        Map<String, List<File>> discByName = new HashMap<>(); indexDiscFiles(discFiles, discByName);
        for (File xml : xmlFiles) for (RiivolutionAction action :
                parseRiivolutionActions(xml, sdRoot, configuredChoices)) {
            if (!action.supported()) {
                skipped++; if (action.codePatch()) unsupportedCode++; continue;
            }
            String externalPath = expandRiivolutionPath(action.external());
            String discPath = expandRiivolutionPath(action.disc());
            if (externalPath == null || discPath == null) { skipped++; continue; }
            File source = resolveRiivolutionExternal(sdRoot, action, externalPath);
            String targetRoot = action.folder()
                ? stripDiscPrefix(stripLeadingSlashes(discPath))
                : resolveRiivolutionDiscTarget(discPath, discFiles, discByName, action.create());
            if (targetRoot == null) { skipped++; continue; }
            if (action.folder() && !targetRoot.isEmpty()) {
                File existingRoot = caseInsensitiveChild(discFiles, targetRoot);
                if (existingRoot.isDirectory()) targetRoot = relative(discFiles, existingRoot).replace('\\', '/');
            }
            if (targetRoot.equalsIgnoreCase("main.dol") || targetRoot.toLowerCase(Locale.US).endsWith(".rel")) {
                unsupportedCode++; skipped++; continue;
            }
            if (action.folder()) {
                if (!source.isDirectory()) { skipped++; continue; }
                List<File> payload = allFiles(source);
                for (File file : payload) {
                    String relative = relative(source, file).replace('\\', '/');
                    if (!action.recursive() && relative.indexOf('/') >= 0) continue;
                    String target = joinDiscPath(targetRoot, relative);
                    if (target.toLowerCase(Locale.US).endsWith(".dol")
                        || target.toLowerCase(Locale.US).endsWith(".rel")) {
                        unsupportedCode++; skipped++; continue;
                    }
                    if (materializeRiivolutionFile(file, overlay, discFiles, target, action)) mapped++;
                    else skipped++;
                }
            } else {
                if (!source.isFile() || targetRoot.isEmpty()) { skipped++; continue; }
                if (materializeRiivolutionFile(source, overlay, discFiles, targetRoot, action)) mapped++;
                else skipped++;
            }
        }
        return new int[]{mapped, skipped, unsupportedCode};
    }

    private static int convertOverlaySzsToPatches(File overlay, File patchDirectory,
                                                   File discFiles) throws IOException {
        List<File> files = allFiles(overlay);
        files.sort(Comparator.comparing(file -> relative(overlay, file)));
        Properties patches = new Properties(); int patchCount = 0;
        for (File file : files) {
            if (!file.getName().toLowerCase(Locale.US).endsWith(".szs")) continue;
            String target = relative(overlay, file).replace('\\', '/');
            File clean = safeChild(discFiles, target);
            if (!clean.isFile()) continue;
            Map<String, byte[]> difference = buildSzsDifference(clean, file);
            if (difference == null) continue;
            if (difference.isEmpty()) { Files.deleteIfExists(file.toPath()); continue; }
            if (!patchDirectory.isDirectory() && !patchDirectory.mkdirs())
                throw new IOException("Cannot create converted Riivolution archive directory");
            String storedName = String.format(Locale.US, "%04d.szs", patchCount);
            Files.write(new File(patchDirectory, storedName).toPath(), WheelArchive.buildYaz0(difference));
            patches.setProperty("patch." + patchCount + ".file", "archive-patches/" + storedName);
            patches.setProperty("patch." + patchCount + ".target", target);
            Files.delete(file.toPath()); patchCount++;
        }
        if (patchCount > 0) {
            patches.setProperty("count", Integer.toString(patchCount));
            try (OutputStream output = new FileOutputStream(new File(patchDirectory.getParentFile(), PATCH_META))) {
                patches.store(output, "Converted Riivolution SZS patch targets");
            }
        }
        return patchCount;
    }

    private static List<RiivolutionAction> parseRiivolutionActions(
            File xmlFile, File sdRoot, Map<String, Integer> configuredChoices) throws IOException {
        byte[] bytes = readSafeXml(xmlFile);
        Map<String, List<RiivolutionAction>> definitions = new LinkedHashMap<>();
        List<SelectedRiivolutionPatch> selected = new ArrayList<>();
        boolean sawOption = false, selectedChoice = false;
        int sectionDepth = -1, optionDepth = -1, choiceDepth = -1, choiceIndex = 0, defaultChoice = 0;
        int referenceDepth = -1, idDepth = -1, definitionDepth = -1;
        boolean sawRegion = false, acceptsPal = false;
        String sectionName = "", definition = null, defaultRoot = "", definitionRoot = "";
        String referenceId = null;
        Map<String, String> optionParams = new LinkedHashMap<>(), choiceParams = new LinkedHashMap<>();
        Map<String, String> referenceParams = new LinkedHashMap<>();
        String xmlDirectory = relative(sdRoot, xmlFile.getParentFile()).replace('\\', '/');
        try {
            XmlPullParser parser = Xml.newPullParser();
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false);
            parser.setInput(new ByteArrayInputStream(bytes), null);
            for (int event = parser.getEventType(); event != XmlPullParser.END_DOCUMENT; event = parser.next()) {
                if (event == XmlPullParser.START_TAG) {
                    String name = parser.getName(); int depth = parser.getDepth();
                    if (name.equalsIgnoreCase("wiidisc")) {
                        String root = attribute(parser, "root"); if (root != null) defaultRoot = root;
                    } else if (name.equalsIgnoreCase("id")) {
                        idDepth = depth;
                        String game = attribute(parser, "game");
                        if (game != null && !"RMCP01".startsWith(game.toUpperCase(Locale.US)))
                            return Collections.emptyList();
                        String developer = attribute(parser, "developer");
                        if (developer != null && !developer.equals("01")) return Collections.emptyList();
                        String disc = attribute(parser, "disc");
                        if (disc != null && parseChoice(disc) != 0) return Collections.emptyList();
                    } else if (name.equalsIgnoreCase("region") && idDepth >= 0) {
                        sawRegion = true;
                        String type = attribute(parser, "type");
                        acceptsPal |= type != null && type.equalsIgnoreCase("P");
                    } else if (name.equalsIgnoreCase("section")) {
                        sectionDepth = depth; String value = attribute(parser, "name");
                        sectionName = value == null ? "" : value;
                    } else if (name.equalsIgnoreCase("option")) {
                        sawOption = true; optionDepth = depth; choiceIndex = 0; optionParams.clear();
                        String optionId = attribute(parser, "id"), optionName = attribute(parser, "name");
                        String configId = optionId == null || optionId.isEmpty()
                            ? sectionName + (optionName == null ? "" : optionName) : optionId;
                        defaultChoice = configuredChoices.getOrDefault(configId,
                            parseChoice(attribute(parser, "default")));
                    } else if (name.equalsIgnoreCase("choice") && optionDepth >= 0) {
                        choiceDepth = depth; selectedChoice = ++choiceIndex == defaultChoice;
                        choiceParams = new LinkedHashMap<>(optionParams);
                    } else if (name.equalsIgnoreCase("patch")) {
                        String id = attribute(parser, "id");
                        if (id == null || id.isBlank()) continue;
                        if (selectedChoice) {
                            referenceId = id; referenceDepth = depth;
                            referenceParams = new LinkedHashMap<>(choiceParams);
                        } else if (optionDepth < 0) {
                            definition = id; definitionDepth = depth;
                            String root = attribute(parser, "root");
                            definitionRoot = root == null ? defaultRoot : root;
                            definitions.computeIfAbsent(id, ignored -> new ArrayList<>());
                        }
                    } else if (name.equalsIgnoreCase("param") && optionDepth >= 0) {
                        String key = attribute(parser, "name"), value = attribute(parser, "value");
                        if (key != null && value != null) {
                            if (referenceId != null) referenceParams.put(key, value);
                            else if (choiceDepth >= 0) choiceParams.put(key, value);
                            else optionParams.put(key, value);
                        }
                    } else if (definition != null) {
                        boolean file = name.equalsIgnoreCase("file"), folder = name.equalsIgnoreCase("folder");
                        if (file || folder) {
                            String disc = attribute(parser, "disc"), external = attribute(parser, "external");
                            long offset = file ? unsignedAttribute(parser, "offset") : 0;
                            long fileOffset = file ? unsignedAttribute(parser, "fileoffset") : 0;
                            long length = unsignedAttribute(parser, "length");
                            boolean supported = disc != null && external != null
                                && offset >= 0 && fileOffset >= 0 && length >= 0;
                            definitions.get(definition).add(new RiivolutionAction(folder, disc, external,
                                !"false".equalsIgnoreCase(attribute(parser, "recursive")),
                                xmlDirectory, definitionRoot, supported, false, offset, fileOffset, length,
                                !"false".equalsIgnoreCase(attribute(parser, "resize")),
                                "true".equalsIgnoreCase(attribute(parser, "create"))));
                        } else if (name.equalsIgnoreCase("memory") || name.equalsIgnoreCase("savegame")
                                || name.equalsIgnoreCase("dolphin_sys_file")
                                || name.equalsIgnoreCase("dolphin_sys_folder")) {
                            definitions.get(definition).add(new RiivolutionAction(false, null, null, true,
                                xmlDirectory, definitionRoot, false, name.equalsIgnoreCase("memory"),
                                0, 0, 0, true, false));
                        }
                    }
                } else if (event == XmlPullParser.END_TAG) {
                    int depth = parser.getDepth(); String name = parser.getName();
                    if (name.equalsIgnoreCase("patch") && depth == referenceDepth) {
                        selected.add(new SelectedRiivolutionPatch(referenceId,
                            Collections.unmodifiableMap(new LinkedHashMap<>(referenceParams))));
                        referenceId = null; referenceDepth = -1; referenceParams.clear();
                    } else if (name.equalsIgnoreCase("choice") && depth == choiceDepth) {
                        choiceDepth = -1; selectedChoice = false; choiceParams.clear();
                    } else if (name.equalsIgnoreCase("option") && depth == optionDepth) {
                        optionDepth = -1; choiceIndex = 0; defaultChoice = 0; optionParams.clear();
                    } else if (name.equalsIgnoreCase("section") && depth == sectionDepth) {
                        sectionDepth = -1; sectionName = "";
                    } else if (name.equalsIgnoreCase("id") && depth == idDepth) {
                        idDepth = -1;
                        if (sawRegion && !acceptsPal) return Collections.emptyList();
                    } else if (name.equalsIgnoreCase("patch") && depth == definitionDepth) {
                        definition = null; definitionRoot = ""; definitionDepth = -1;
                    }
                }
            }
        } catch (org.xmlpull.v1.XmlPullParserException error) {
            throw new IOException("Invalid Riivolution XML " + xmlFile.getName() + ": " + error.getMessage(), error);
        }

        if (!sawOption) for (String id : definitions.keySet())
            selected.add(new SelectedRiivolutionPatch(id, Collections.emptyMap()));
        List<RiivolutionAction> actions = new ArrayList<>();
        for (SelectedRiivolutionPatch selection : selected) {
            List<RiivolutionAction> patch = definitions.get(selection.id());
            if (patch == null) continue;
            for (RiivolutionAction action : patch) actions.add(new RiivolutionAction(action.folder(),
                replaceRiivolutionParameters(action.disc(), selection.parameters()),
                replaceRiivolutionParameters(action.external(), selection.parameters()), action.recursive(),
                action.xmlDirectory(), replaceRiivolutionParameters(action.patchRoot(), selection.parameters()),
                action.supported(), action.codePatch(), action.offset(), action.fileOffset(), action.length(),
                action.resize(), action.create()));
        }
        return actions;
    }

    private static Map<String, Integer> readRiivolutionConfig(File riivolution) throws IOException {
        File config = caseInsensitiveChild(riivolution, "config/RMCP.xml");
        if (!config.isFile()) return Collections.emptyMap();
        byte[] bytes = readSafeXml(config); Map<String, Integer> choices = new HashMap<>();
        try {
            XmlPullParser parser = Xml.newPullParser();
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false);
            parser.setInput(new ByteArrayInputStream(bytes), null);
            for (int event = parser.getEventType(); event != XmlPullParser.END_DOCUMENT; event = parser.next())
                if (event == XmlPullParser.START_TAG && parser.getName().equalsIgnoreCase("option")) {
                    String id = attribute(parser, "id");
                    if (id != null && !id.isEmpty()) choices.put(id, parseChoice(attribute(parser, "default")));
                }
        } catch (org.xmlpull.v1.XmlPullParserException error) {
            throw new IOException("Invalid Riivolution config: " + error.getMessage(), error);
        }
        return choices;
    }

    private static List<RiivolutionOption> readRiivolutionOptionMetadata(
            File xml, Map<String, Integer> configured) throws IOException {
        byte[] bytes = readSafeXml(xml); List<RiivolutionOption> result = new ArrayList<>();
        String section = "", optionId = null, optionName = null; List<String> choices = null;
        int sectionDepth = -1, optionDepth = -1, idDepth = -1, defaultChoice = 0;
        boolean compatible = true, sawRegion = false, acceptsPal = false;
        try {
            XmlPullParser parser = Xml.newPullParser();
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false);
            parser.setInput(new ByteArrayInputStream(bytes), null);
            for (int event = parser.getEventType(); event != XmlPullParser.END_DOCUMENT; event = parser.next()) {
                if (event == XmlPullParser.START_TAG) {
                    String name = parser.getName(); int depth = parser.getDepth();
                    if (name.equalsIgnoreCase("id")) {
                        idDepth = depth; String game = attribute(parser, "game"), developer = attribute(parser, "developer");
                        compatible &= game == null || "RMCP01".startsWith(game.toUpperCase(Locale.US));
                        compatible &= developer == null || developer.equals("01");
                    } else if (name.equalsIgnoreCase("region") && idDepth >= 0) {
                        sawRegion = true; String type = attribute(parser, "type");
                        acceptsPal |= type != null && type.equalsIgnoreCase("P");
                    } else if (name.equalsIgnoreCase("section")) {
                        sectionDepth = depth; String value = attribute(parser, "name"); section = value == null ? "" : value;
                    } else if (name.equalsIgnoreCase("option")) {
                        optionDepth = depth; optionId = attribute(parser, "id"); optionName = attribute(parser, "name");
                        String stableId = optionId == null || optionId.isEmpty()
                            ? section + (optionName == null ? "" : optionName) : optionId;
                        defaultChoice = configured.getOrDefault(stableId, parseChoice(attribute(parser, "default")));
                        choices = new ArrayList<>(); choices.add("Disabled");
                    } else if (name.equalsIgnoreCase("choice") && optionDepth >= 0 && choices != null) {
                        String value = attribute(parser, "name");
                        choices.add(value == null || value.isBlank() ? "Choice " + choices.size() : value);
                    }
                } else if (event == XmlPullParser.END_TAG) {
                    String name = parser.getName(); int depth = parser.getDepth();
                    if (name.equalsIgnoreCase("option") && depth == optionDepth && choices != null) {
                        String stableId = optionId == null || optionId.isEmpty()
                            ? section + (optionName == null ? "" : optionName) : optionId;
                        if (!stableId.isEmpty() && choices.size() > 1) result.add(new RiivolutionOption(stableId,
                            optionName == null || optionName.isBlank() ? stableId : optionName,
                            Collections.unmodifiableList(new ArrayList<>(choices)),
                            Math.min(Math.max(defaultChoice, 0), choices.size() - 1)));
                        optionDepth = -1; optionId = null; optionName = null; choices = null;
                    } else if (name.equalsIgnoreCase("section") && depth == sectionDepth) {
                        sectionDepth = -1; section = "";
                    } else if (name.equalsIgnoreCase("id") && depth == idDepth) idDepth = -1;
                }
            }
        } catch (org.xmlpull.v1.XmlPullParserException error) {
            throw new IOException("Invalid Riivolution XML " + xml.getName() + ": " + error.getMessage(), error);
        }
        if (sawRegion && !acceptsPal) compatible = false;
        return compatible ? result : Collections.emptyList();
    }

    private static Map<String, Integer> readRiivolutionChoices(File directory) throws IOException {
        File file = new File(directory, RIIVO_CHOICES);
        if (!file.isFile()) return Collections.emptyMap();
        Properties values = new Properties();
        try (InputStream input = new FileInputStream(file)) { values.load(input); }
        Map<String, Integer> choices = new HashMap<>();
        for (String id : values.stringPropertyNames()) {
            try { choices.put(id, Integer.parseInt(values.getProperty(id))); }
            catch (NumberFormatException error) { throw new IOException("Invalid saved Riivolution choice", error); }
        }
        return choices;
    }

    private static void saveRiivolutionChoices(File directory, Map<String, Integer> choices) throws IOException {
        Properties values = new Properties();
        for (Map.Entry<String, Integer> choice : choices.entrySet())
            values.setProperty(choice.getKey(), Integer.toString(choice.getValue()));
        File target = new File(directory, RIIVO_CHOICES), temporary = new File(directory, RIIVO_CHOICES + ".tmp");
        try (OutputStream output = new FileOutputStream(temporary)) {
            values.store(output, "Selected Riivolution options");
        }
        try { Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE); }
        catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void moveIfExists(File source, File destination) throws IOException {
        if (!source.exists()) return;
        File parent = destination.getParentFile();
        if (!parent.mkdirs() && !parent.isDirectory()) throw new IOException("Cannot create profile rollback directory");
        Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    private static byte[] readSafeXml(File file) throws IOException {
        if (file.length() <= 0 || file.length() > 1024 * 1024)
            throw new IOException("Riivolution XML size is outside the supported range: " + file.getName());
        byte[] bytes = Files.readAllBytes(file.toPath());
        String probe = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1).toLowerCase(Locale.US);
        if (probe.contains("<!doctype") || probe.contains("<!entity"))
            throw new IOException("Riivolution XML declarations and entities are not allowed");
        return bytes;
    }

    private static long unsignedAttribute(XmlPullParser parser, String name) {
        String value = attribute(parser, name);
        if (value == null || value.isBlank()) return 0;
        try { long parsed = Long.decode(value.trim()); return parsed < 0 ? -1 : parsed; }
        catch (NumberFormatException ignored) { return -1; }
    }

    private static String replaceRiivolutionParameters(String value, Map<String, String> parameters) {
        if (value == null) return null;
        String result = value;
        for (Map.Entry<String, String> parameter : parameters.entrySet())
            result = result.replace("{$" + parameter.getKey() + "}", parameter.getValue());
        return result;
    }

    private static int parseChoice(String value) {
        if (value == null) return 0;
        try { int parsed = Integer.parseInt(value.trim()); return Math.max(parsed, 0); }
        catch (NumberFormatException ignored) { return 0; }
    }

    private static String attribute(XmlPullParser parser, String name) {
        for (int index = 0; index < parser.getAttributeCount(); index++)
            if (name.equalsIgnoreCase(parser.getAttributeName(index))) return parser.getAttributeValue(index);
        return null;
    }

    private static String expandRiivolutionPath(String path) {
        if (path == null) return null;
        String expanded = path.replace("{$__region}", "P").replace("{$__gameid}", "RMC")
            .replace("{$__maker}", "01").replace("{$__gameid6}", "RMCP01");
        return expanded.contains("{$") ? null : expanded;
    }

    private static File resolveRiivolutionExternal(File sdRoot, RiivolutionAction action,
                                                   String external) throws IOException {
        String relative;
        if (external.startsWith("/")) relative = stripLeadingSlashes(external);
        else {
            String patchRoot = action.patchRoot();
            String base;
            if (patchRoot == null || patchRoot.isEmpty()) base = action.xmlDirectory();
            else if (patchRoot.startsWith("/")) base = stripLeadingSlashes(patchRoot);
            else base = joinDiscPath(action.xmlDirectory(), patchRoot);
            relative = joinDiscPath(base, external);
        }
        return caseInsensitiveChild(sdRoot, relative);
    }

    private static File caseInsensitiveChild(File root, String path) throws IOException {
        String normalized = path.replace('\\', '/');
        if (normalized.matches("^[A-Za-z]:.*")) throw new IOException("Unsafe Riivolution external path: " + path);
        File current = root.getCanonicalFile(), canonicalRoot = current;
        for (String component : normalized.split("/+", -1)) {
            if (component.isEmpty() || component.equals(".")) continue;
            if (component.equals("..")) {
                if (current.equals(canonicalRoot)) throw new IOException("Riivolution path leaves the SD root");
                current = current.getParentFile(); continue;
            }
            if (component.chars().allMatch(value -> value == '.'))
                throw new IOException("Unsafe Riivolution external path: " + path);
            File exact = new File(current, component);
            if (exact.exists()) current = exact;
            else {
                File match = null; File[] children = current.listFiles();
                if (children != null) for (File child : children) if (child.getName().equalsIgnoreCase(component)) {
                    if (match != null) throw new IOException("Ambiguous Riivolution path casing: " + path);
                    match = child;
                }
                current = match == null ? exact : match;
            }
            String allowed = canonicalRoot.getPath() + File.separator;
            String candidate = current.getCanonicalPath();
            if (!candidate.equals(canonicalRoot.getPath()) && !candidate.startsWith(allowed))
                throw new IOException("Riivolution path leaves the SD root");
        }
        return current;
    }

    private static String joinDiscPath(String root, String child) {
        if (root == null || root.isEmpty() || root.equals("/")) return child;
        return root.endsWith("/") ? root + child : root + "/" + child;
    }

    private static String stripLeadingSlashes(String path) {
        int index = 0; while (index < path.length() && path.charAt(index) == '/') index++;
        return path.substring(index);
    }

    private static String resolveRiivolutionDiscTarget(String disc, File discFiles,
            Map<String, List<File>> byName, boolean create) throws IOException {
        boolean explicitPath = disc.startsWith("/") || disc.contains("\\") || stripLeadingSlashes(disc).contains("/");
        String candidate = stripDiscPrefix(stripLeadingSlashes(disc.replace('\\', '/')));
        if (candidate.isEmpty()) return null;
        if (explicitPath) {
            File existing = caseInsensitiveChild(discFiles, candidate);
            if (existing.isFile()) return relative(discFiles, existing).replace('\\', '/');
            return create ? candidate : null;
        }
        List<File> matches = byName.get(candidate.toLowerCase(Locale.US));
        if (matches != null && !matches.isEmpty()) {
            matches.sort(Comparator.comparing(file -> relative(discFiles, file), String.CASE_INSENSITIVE_ORDER));
            return relative(discFiles, matches.get(0)).replace('\\', '/');
        }
        return create ? candidate : null;
    }

    private static boolean materializeRiivolutionFile(File source, File overlay, File discFiles,
                                                       String relative, RiivolutionAction action) throws IOException {
        if (source.length() > MAX_ENTRY_BYTES) throw new IOException("Riivolution payload is too large");
        File target = safeChild(overlay, relative), base = target;
        if (!base.isFile()) {
            File clean = caseInsensitiveChild(discFiles, relative);
            base = clean.isFile() ? clean : null;
        }
        if (base == null && !action.create()) return false;

        long patchStartLong = action.offset() & ~3L;
        long rawSourceSize = source.length(), sourceOffsetLong = Math.min(action.fileOffset(), rawSourceSize);
        long sourceSizeLong = rawSourceSize - sourceOffsetLong;
        long patchSizeLong = action.length() == 0 ? sourceSizeLong : action.length();
        long patchEndLong;
        try { patchEndLong = Math.addExact(patchStartLong, patchSizeLong); }
        catch (ArithmeticException error) { throw new IOException("Riivolution patch range overflows", error); }
        long baseSizeLong = base == null ? 0 : base.length();
        long targetSizeLong = action.resize() ? patchEndLong : Math.max(baseSizeLong, patchEndLong);
        if (targetSizeLong > MAX_ENTRY_BYTES || targetSizeLong > Integer.MAX_VALUE)
            throw new IOException("Materialized Riivolution file exceeds the safety limit");

        File parent = target.getParentFile();
        if (!parent.mkdirs() && !parent.isDirectory()) throw new IOException("Cannot create Riivolution overlay directory");
        if (patchStartLong == 0 && sourceOffsetLong == 0 && action.length() == 0 && action.resize()) {
            Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING); return true;
        }

        byte[] output = new byte[(int)targetSizeLong];
        if (base != null) {
            byte[] clean = Files.readAllBytes(base.toPath());
            System.arraycopy(clean, 0, output, 0, Math.min(clean.length, output.length));
        }
        byte[] patch = Files.readAllBytes(source.toPath());
        int copy = (int)Math.min(patchSizeLong, sourceSizeLong);
        if (copy > 0) System.arraycopy(patch, (int)sourceOffsetLong, output, (int)patchStartLong, copy);
        Files.write(target.toPath(), output); return true;
    }

    private static String resolveTaggedTarget(File source, String packagePath, File discFiles,
                                              Map<String, List<File>> byName) {
        String lower = source.getName().toLowerCase(Locale.US);
        if (!lower.endsWith(".szs")) return null;
        String stem = source.getName().substring(0, source.getName().length() - 4);
        int separator = stem.lastIndexOf('.');
        if (separator <= 0 || separator == stem.length() - 1) return null;
        return resolveArchiveTarget(stem.substring(separator + 1), packagePath, discFiles, byName);
    }

    private static LoosePatch resolveLoosePatch(File source, String packagePath, File discFiles,
                                                Map<String, List<File>> byName) {
        String name = source.getName();
        int separator = name.lastIndexOf('.');
        if (separator <= 0 || separator == name.length() - 1) return null;
        String archiveTag = name.substring(separator + 1);
        String encodedMember = name.substring(0, separator);
        StringBuilder member = new StringBuilder();
        while (encodedMember.startsWith("[")) {
            int close = encodedMember.indexOf(']');
            if (close <= 1) return null;
            String directory = encodedMember.substring(1, close);
            if (directory.equals(".") || directory.equals("..") || directory.indexOf('/') >= 0
                || directory.indexOf('\\') >= 0) return null;
            if (member.length() != 0) member.append('/');
            member.append(directory);
            encodedMember = encodedMember.substring(close + 1);
        }
        if (encodedMember.isEmpty() || encodedMember.equals(".") || encodedMember.equals("..")
            || encodedMember.indexOf('/') >= 0 || encodedMember.indexOf('\\') >= 0) return null;
        if (member.length() != 0) member.append('/');
        member.append(encodedMember);
        String target = resolveArchiveTarget(archiveTag, packagePath, discFiles, byName);
        return target == null ? null : new LoosePatch(target, member.toString());
    }

    private static LoosePatch resolveLooseBrsarPatch(File source) {
        String name = source.getName();
        if (!name.startsWith("[")) return null;
        int close = name.indexOf(']');
        if (close <= 1) return null;
        String id = name.substring(1, close);
        if (!id.matches("[0-9]+")) return null;
        String lower = name.toLowerCase(Locale.US);
        String extension;
        if (lower.endsWith(".brbnk")) extension = ".brbnk";
        else if (lower.endsWith(".brseq")) extension = ".brseq";
        else if (lower.endsWith(".brwsd")) extension = ".brwsd";
        else return null;
        return new LoosePatch(BRSAR_TARGET, id + extension);
    }

    private static String resolveArchiveTarget(String archiveTag, String packagePath, File discFiles,
                                               Map<String, List<File>> byName) {
        String targetName = archiveTag + ".szs";
        List<File> matches = byName.get(targetName.toLowerCase(Locale.US));
        if (matches == null || matches.isEmpty()) return null;
        String parent = packagePath.contains("/") ? packagePath.substring(0, packagePath.lastIndexOf('/') + 1) : "";
        String hinted = stripDiscPrefix(parent + targetName).replace('\\', '/').toLowerCase(Locale.US);
        File selected = null;
        for (File match : matches) {
            String relative = relative(discFiles, match).replace('\\', '/');
            if (hinted.equals(relative.toLowerCase(Locale.US)) || hinted.endsWith("/" + relative.toLowerCase(Locale.US))) {
                if (selected != null) return null;
                selected = match;
            }
        }
        if (selected == null && matches.size() == 1) selected = matches.get(0);
        return selected == null ? null : relative(discFiles, selected).replace('\\', '/');
    }

    private static List<ArchivePatch> loadArchivePatches(File directory) throws IOException {
        File manifest = new File(directory, PATCH_META);
        if (!manifest.isFile()) return Collections.emptyList();
        Properties values = new Properties();
        try (InputStream input = new FileInputStream(manifest)) { values.load(input); }
        int count = Integer.parseInt(values.getProperty("count", "0"));
        if (count < 0 || count > MAX_ENTRIES) throw new IOException("Invalid tagged patch count");
        String allowed = directory.getCanonicalPath() + File.separator;
        List<ArchivePatch> patches = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            File file = new File(directory, values.getProperty("patch." + index + ".file", ""));
            String target = safeDiscPath(values.getProperty("patch." + index + ".target", ""));
            if (!file.getCanonicalPath().startsWith(allowed) || !file.isFile())
                throw new IOException("Tagged patch file is missing or unsafe");
            String member = values.getProperty("patch." + index + ".member");
            if (member != null) member = safeArchiveMember(member);
            patches.add(new ArchivePatch(file, target, member));
        }
        return Collections.unmodifiableList(patches);
    }

    private static File composeTaggedArchives(Context context, List<Mod> listedMods, File discFiles) throws IOException {
        List<Mod> enabled = new ArrayList<>();
        boolean hasPatches = false;
        for (Mod mod : listedMods) if (mod.enabled) {
            enabled.add(mod); hasPatches |= !mod.archivePatches.isEmpty();
        }
        File external = context.getExternalFilesDir(null);
        if (external == null) throw new IOException("External app storage unavailable");
        File composed = new File(external, COMPOSED);
        File signatureFile = new File(composed.getParentFile(), "composed.signature");
        File legacy = new File(context.getFilesDir(), COMPOSED);
        if (legacy.exists()) deleteRecursively(legacy);
        if (!hasPatches) {
            if (composed.exists()) deleteRecursively(composed);
            signatureFile.delete();
            return null;
        }
        if (!discFiles.isDirectory()) throw new IOException("Extracted PAL disc files are unavailable");

        enabled.sort(Comparator.comparingLong(mod -> mod.priority));
        Map<String, Boolean> targets = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Mod mod : enabled) for (ArchivePatch patch : mod.archivePatches) targets.put(patch.target, true);
        String signature = compositionSignature(enabled, targets.keySet(), discFiles);
        if (signatureFile.isFile()
            && new String(Files.readAllBytes(signatureFile.toPath()),
                java.nio.charset.StandardCharsets.UTF_8).equals(signature)) return composed;

        File staging = new File(composed.getParentFile(), composed.getName() + ".staging");
        deleteRecursively(staging);
        if (!staging.mkdirs()) throw new IOException("Cannot create tagged archive staging directory");
        try {
            String stagingRoot = staging.getCanonicalPath() + File.separator;
            for (String target : targets.keySet()) {
                File base = safeChild(discFiles, target);
                if (!base.isFile()) throw new IOException("Tagged patch target is missing from the PAL disc: " + target);
                File output = new File(staging, target);
                if (!output.getCanonicalPath().startsWith(stagingRoot)) throw new IOException("Unsafe composed archive path");
                if (target.toLowerCase(Locale.US).endsWith(".brsar")) {
                    composeBrsarTarget(base, output, target, enabled);
                    continue;
                }
                Map<String, byte[]> members = new LinkedHashMap<>(WheelArchive.decode(base));
                for (Mod mod : enabled) {
                    File fullReplacement = safeChild(new File(mod.overlayRoot), target);
                    if (fullReplacement.isFile()) members = new LinkedHashMap<>(WheelArchive.decode(fullReplacement));
                    for (ArchivePatch patch : mod.archivePatches) if (patch.target.equalsIgnoreCase(target)) {
                        if (patch.member == null) applyArchivePatch(members, WheelArchive.decode(patch.file));
                        else applyArchiveMember(members, patch.member, Files.readAllBytes(patch.file.toPath()));
                    }
                }
                File parent = output.getParentFile();
                if (!parent.mkdirs() && !parent.isDirectory()) throw new IOException("Cannot create composed archive directory");
                Files.write(output.toPath(), WheelArchive.buildYaz0(members));
            }
            deleteRecursively(composed);
            try {
                Files.move(staging.toPath(), composed.toPath(), StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(staging.toPath(), composed.toPath());
            }
            Files.write(signatureFile.toPath(), signature.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return composed;
        } catch (IOException | RuntimeException error) {
            deleteRecursively(staging);
            throw error;
        }
    }

    private static void composeBrsarTarget(File base, File output, String target, List<Mod> enabled) throws IOException {
        File source = base;
        Map<Integer, byte[]> overrides = new HashMap<>();
        for (Mod mod : enabled) {
            File fullReplacement = safeChild(new File(mod.overlayRoot), target);
            if (fullReplacement.isFile()) { source = fullReplacement; overrides.clear(); }
            for (ArchivePatch patch : mod.archivePatches) if (patch.target.equalsIgnoreCase(target)) {
                Map<String, byte[]> members;
                if (patch.member == null) members = WheelArchive.decode(patch.file);
                else members = java.util.Map.of(patch.member, Files.readAllBytes(patch.file.toPath()));
                overrides.putAll(WheelBrsar.normalizeOverrides(members));
            }
        }
        WheelBrsar.compose(source, output, overrides);
    }

    private static void applyArchivePatch(Map<String, byte[]> base, Map<String, byte[]> patch) {
        for (Map.Entry<String, byte[]> entry : patch.entrySet())
            applyArchiveMember(base, entry.getKey(), entry.getValue());
    }

    private static void applyArchiveMember(Map<String, byte[]> base, String path, byte[] bytes) {
        if (bytes.length == 0 && path.toLowerCase(Locale.US).endsWith(".delete")) {
            String requested = path.substring(0, path.length() - ".delete".length());
            String existing = findArchiveMember(base, requested);
            if (existing != null) base.remove(existing);
            return;
        }
        String existing = findArchiveMember(base, path);
        base.put(existing == null ? path : existing, bytes);
    }

    private static String findArchiveMember(Map<String, byte[]> members, String requested) {
        if (members.containsKey(requested)) return requested;
        String match = null;
        for (String path : members.keySet()) if (path.equalsIgnoreCase(requested)) {
            if (match != null) return null;
            match = path;
        }
        return match;
    }

    private static String compositionSignature(List<Mod> enabled, java.util.Set<String> targets,
                                               File discFiles) throws IOException {
        StringBuilder value = new StringBuilder("wheel-archive-v1\n");
        for (String target : targets) {
            File base = safeChild(discFiles, target);
            value.append("base:").append(target).append(':').append(base.length()).append(':')
                .append(base.lastModified()).append('\n');
        }
        for (Mod mod : enabled) {
            value.append(mod.id).append(':').append(mod.priority).append('\n');
            for (ArchivePatch patch : mod.archivePatches)
                value.append(patch.target).append(':').append(patch.file.length()).append(':')
                    .append(patch.file.lastModified()).append(':').append(patch.member).append('\n');
            for (String target : targets) {
                File direct = safeChild(new File(mod.overlayRoot), target);
                if (direct.isFile()) value.append("full:").append(target).append(':').append(direct.length())
                    .append(':').append(direct.lastModified()).append('\n');
            }
        }
        return value.toString();
    }

    private static File safeChild(File root, String relative) throws IOException {
        String safe = safeDiscPath(relative);
        File child = new File(root, safe);
        String canonicalRoot = root.getCanonicalPath() + File.separator;
        if (!child.getCanonicalPath().startsWith(canonicalRoot)) throw new IOException("Unsafe disc path");
        return child;
    }

    private static String safeDiscPath(String path) throws IOException {
        if (path == null) throw new IOException("Missing disc path");
        String normalized = path.replace('\\', '/');
        if (normalized.isEmpty() || normalized.startsWith("/") || normalized.matches("^[A-Za-z]:.*"))
            throw new IOException("Unsafe disc path: " + path);
        for (String component : normalized.split("/", -1))
            if (component.isEmpty() || component.equals(".") || component.equals(".."))
                throw new IOException("Unsafe disc path: " + path);
        return normalized;
    }

    private static String safeArchiveMember(String path) throws IOException {
        String normalized = path.replace('\\', '/');
        if (normalized.isEmpty() || normalized.startsWith("/"))
            throw new IOException("Unsafe archive member path: " + path);
        for (String component : normalized.split("/", -1))
            if (component.isEmpty() || component.equals(".") || component.equals(".."))
                throw new IOException("Unsafe archive member path: " + path);
        return normalized;
    }

    private static String stripDiscPrefix(String path) {
        String lower = path.toLowerCase(Locale.US);
        for (String marker : new String[]{"data/files/", "disc/files/", "files/"}) {
            int index = lower.indexOf(marker);
            if (index >= 0) return path.substring(index + marker.length());
        }
        return path;
    }

    private static void indexDiscFiles(File directory, Map<String, List<File>> index) {
        File[] children = directory.listFiles(); if (children == null) return;
        for (File child : children) {
            if (child.isDirectory()) indexDiscFiles(child, index);
            else index.computeIfAbsent(child.getName().toLowerCase(Locale.US), ignored -> new ArrayList<>()).add(child);
        }
    }

    private static List<File> allFiles(File directory) {
        List<File> files = new ArrayList<>(); collectFiles(directory, files); return files;
    }
    private static void collectFiles(File directory, List<File> files) {
        File[] children = directory.listFiles(); if (children == null) return;
        for (File child : children) { if (child.isDirectory()) collectFiles(child, files); else files.add(child); }
    }

    private static File findVirtualSdRoot(File directory) {
        if (new File(directory, "riivolution").isDirectory()) return directory;
        File[] children = directory.listFiles(File::isDirectory); if (children == null) return null;
        for (File child : children) { File found = findVirtualSdRoot(child); if (found != null) return found; }
        return null;
    }

    private static WheelMetadata readWheelMetadata(File extracted) {
        List<File> files = allFiles(extracted);
        files.sort(Comparator.comparing(file -> relative(extracted, file)));
        for (File file : files) {
            if (!file.getName().toLowerCase(Locale.US).endsWith(".ini") || file.length() > 64 * 1024) continue;
            String name = "", author = ""; boolean modSection = false;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    new FileInputStream(file), java.nio.charset.StandardCharsets.UTF_8))) {
                for (String line; (line = reader.readLine()) != null;) {
                    String value = line.trim();
                    if (value.startsWith("[") && value.endsWith("]")) {
                        modSection = value.substring(1, value.length() - 1).trim().equalsIgnoreCase("Mod");
                        continue;
                    }
                    if (!modSection || value.isEmpty() || value.startsWith(";") || value.startsWith("#")) continue;
                    int equals = value.indexOf('='); if (equals <= 0) continue;
                    String key = value.substring(0, equals).trim();
                    String field = cleanMetadataValue(value.substring(equals + 1));
                    if (key.equalsIgnoreCase("Name")) name = field;
                    else if (key.equalsIgnoreCase("Author")) author = field;
                }
            } catch (IOException ignored) { continue; }
            if (!name.isEmpty() || !author.isEmpty()) return new WheelMetadata(name, author);
        }
        return new WheelMetadata("", "");
    }

    private static String cleanMetadataValue(String value) {
        StringBuilder clean = new StringBuilder();
        for (int index = 0; index < value.length() && clean.length() < 120; index++) {
            char character = value.charAt(index);
            if (!Character.isISOControl(character)) clean.append(character);
        }
        return clean.toString().trim();
    }

    private static int countFiles(File directory) { return allFiles(directory).size(); }
    private static String relative(File root, File file) { return root.toPath().relativize(file.toPath()).toString(); }

    private static String displayName(Context context, Uri uri) {
        if ("file".equalsIgnoreCase(uri.getScheme()) && uri.getPath() != null) {
            String name = new File(uri.getPath()).getName(); if (!name.isBlank()) return name;
        }
        try (Cursor cursor = context.getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String name = cursor.getString(0); if (name != null && !name.isBlank()) return name;
            }
        } catch (RuntimeException ignored) { }
        return "imported-mod.zip";
    }

    private static void extractSafely(Context context, Uri uri, File destination,
                                      long maximumEntryBytes, long maximumTotalBytes) throws IOException {
        String root = destination.getCanonicalPath() + File.separator;
        int entries = 0; long total = 0; boolean any = false;
        try (InputStream raw = context.getContentResolver().openInputStream(uri);
             ZipInputStream zip = raw == null ? null : new ZipInputStream(raw)) {
            if (zip == null) throw new IOException("Document provider did not open the ZIP");
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; zip.closeEntry()) {
                any = true; if (++entries > MAX_ENTRIES) throw new IOException("Mod ZIP has too many entries");
                String name = safeEntryName(entry.getName()); File output = new File(destination, name);
                if (!output.getCanonicalPath().startsWith(root)) throw new IOException("Unsafe ZIP path: " + name);
                if (entry.isDirectory()) { if (!output.mkdirs() && !output.isDirectory()) throw new IOException("Cannot create ZIP directory"); continue; }
                if (entry.getSize() > maximumEntryBytes) throw new IOException("ZIP entry is too large: " + name);
                File parent = output.getParentFile(); if (!parent.mkdirs() && !parent.isDirectory()) throw new IOException("Cannot create ZIP directory");
                try (OutputStream file = new FileOutputStream(output)) {
                    byte[] buffer = new byte[64 * 1024]; long entryBytes = 0;
                    for (int read; (read = zip.read(buffer)) != -1;) {
                        entryBytes += read; total += read;
                        if (entryBytes > maximumEntryBytes || total > maximumTotalBytes)
                            throw new IOException("Mod ZIP expands beyond safety limit");
                        file.write(buffer, 0, read);
                    }
                }
            }
        }
        if (!any) throw new IOException("Mod ZIP is empty or invalid");
    }

    static String safeEntryName(String name) throws IOException {
        if (name == null) throw new IOException("ZIP entry has no name");
        String normalized = name.replace('\\', '/');
        while (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
        if (normalized.isEmpty() || normalized.startsWith("/") || normalized.matches("^[A-Za-z]:.*"))
            throw new IOException("Unsafe ZIP entry: " + name);
        for (String component : normalized.split("/"))
            if (component.isEmpty() || component.equals(".") || component.equals("..")) throw new IOException("Unsafe ZIP entry: " + name);
        return normalized;
    }

    private static void deleteRecursively(File file) {
        File[] children = file.listFiles(); if (children != null) for (File child : children) deleteRecursively(child);
        file.delete();
    }
}

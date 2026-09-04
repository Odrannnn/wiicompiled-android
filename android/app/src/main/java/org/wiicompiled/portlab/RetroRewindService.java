package org.wiicompiled.portlab;

import android.content.Context;
import android.net.Uri;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Staged Retro Rewind installer using WheelWizard's pinned update contract. */
final class RetroRewindService {
    private static final String ROOT = "https://update.rwfc.net/RetroRewind/";
    private static final String INSTALL = ROOT + "RetroRewindInstall.txt";
    private static final String VERSIONS = ROOT + "RetroRewindVersion.txt";
    private static final String DELETIONS = ROOT + "RetroRewindDelete.txt";
    // The official 6.12.4 full archive is about 1.72 GiB. Keep finite limits, but leave
    // room for current full releases and their expanded virtual-SD contents.
    private static final long GIB = 1024L * 1024 * 1024;
    private static final long MAX_DOWNLOAD = 3L * GIB;
    private static final long MAX_ENTRY = 2L * GIB, MAX_EXPANDED = 12L * GIB;
    private static final long MIN_STAGING_SPACE = 12L * GIB;
    private static final int MAX_ENTRIES = 20_000;

    interface Progress { boolean update(String stage, long done, long total); }
    record Status(String installedVersion, String latestVersion) {
        boolean installed() { return installedVersion != null; }
        boolean current() { return installed() && Version.parse(installedVersion).compareTo(Version.parse(latestVersion)) >= 0; }
    }
    private record Update(Version version, String url) { }
    private record Deletion(Version version, String path) { }

    static Status status(Context context) throws IOException {
        AndroidModManager.Mod installed = installed(context); String latest = latestVersion();
        return new Status(installed == null ? null : installed.distributionVersion, latest);
    }

    static AndroidModManager.Mod installOrUpdate(Context context, File discFiles, Progress progress) throws IOException {
        if (!discFiles.isDirectory()) throw new IOException("Extracted PAL disc files are unavailable");
        File work = new File(context.getCacheDir(), "retro-rewind-stage"); delete(work);
        File extracted = new File(work, "root"), downloads = new File(work, "downloads");
        if (!extracted.mkdirs() || !downloads.mkdirs()) throw new IOException("Cannot create Retro Rewind staging storage");
        try {
            String installUrl = firstHttps(GameBananaClient.fetchText(INSTALL, 64 * 1024));
            verifyStagingSpace(context, installUrl);
            String versionText = GameBananaClient.fetchText(VERSIONS, 2 * 1024 * 1024);
            List<Update> updates = parseUpdates(versionText);
            Version latest = Version.parse(latestVersion(versionText));
            File baseZip = new File(downloads, "base.zip");
            report(progress, "Downloading Retro Rewind base", 0, -1);
            GameBananaClient.downloadUrl(installUrl, baseZip, MAX_DOWNLOAD,
                (done, total) -> active(progress, "Downloading Retro Rewind base", done, total));
            report(progress, "Extracting Retro Rewind base", 0, -1);
            extract(baseZip, extracted, progress, "Extracting Retro Rewind base");

            Version baseVersion = readInstalledVersion(extracted); List<Update> needed = new ArrayList<>();
            for (Update update : updates) if (update.version().compareTo(baseVersion) > 0) needed.add(update);
            if (baseVersion.compareTo(latest) < 0
                && (needed.isEmpty() || needed.get(needed.size() - 1).version().compareTo(latest) < 0))
                throw new IOException("Retro Rewind update feed does not provide a complete path to " + latest.text());
            applyDeletions(extracted, parseDeletions(GameBananaClient.fetchText(DELETIONS, 2 * 1024 * 1024)),
                baseVersion, latest);
            for (int index = 0; index < needed.size(); index++) {
                Update update = needed.get(index); File zip = new File(downloads, "update-" + index + ".zip");
                String label = "Downloading Retro Rewind update " + (index + 1) + "/" + needed.size();
                GameBananaClient.downloadUrl(update.url(), zip, MAX_DOWNLOAD,
                    (done, total) -> active(progress, label, done, total));
                report(progress, "Applying Retro Rewind update " + (index + 1) + "/" + needed.size(), 0, -1);
                extract(zip, extracted, progress, "Applying Retro Rewind update " + (index + 1) + "/" + needed.size());
                zip.delete();
            }
            File rrFolder = caseInsensitiveChild(extracted, "RetroRewind6");
            if (!rrFolder.isDirectory() || !caseInsensitiveChild(extracted, "riivolution").isDirectory())
                throw new IOException("Retro Rewind archive lacks RetroRewind6 or riivolution content");
            Files.write(new File(rrFolder, "version.txt").toPath(), latest.text().getBytes(StandardCharsets.UTF_8));

            File combined = new File(work, "Retro-Rewind-" + latest.text() + ".zip");
            report(progress, "Preparing Android mod profile", 0, -1);
            createZip(extracted, combined, progress); report(progress, "Importing Android mod profile", 0, -1);
            AndroidModManager.Mod previous = installed(context);
            AndroidModManager.ImportResult imported = AndroidModManager.importRetroRewindDistribution(
                context, Uri.fromFile(combined), discFiles);
            AndroidModManager.attachDistributionMetadata(imported.mod, "retro-rewind", latest.text());
            AndroidModManager.Mod replacement = AndroidModManager.list(context).stream()
                .filter(mod -> mod.id.equals(imported.mod.id)).findFirst()
                .orElseThrow(() -> new IOException("Retro Rewind profile metadata could not be reopened"));
            if (previous != null) AndroidModManager.replaceProfile(context, previous, replacement);
            return AndroidModManager.list(context).stream().filter(mod -> mod.id.equals(replacement.id)).findFirst()
                .orElse(replacement);
        } finally { delete(work); }
    }

    private static AndroidModManager.Mod installed(Context context) {
        return AndroidModManager.list(context).stream()
            .filter(mod -> mod.distributionId.equals("retro-rewind")).findFirst().orElse(null);
    }
    private static String latestVersion() throws IOException {
        return latestVersion(GameBananaClient.fetchText(VERSIONS, 2 * 1024 * 1024));
    }
    private static String latestVersion(String text) throws IOException {
        List<Update> updates = parseUpdates(text);
        if (!updates.isEmpty()) return updates.get(updates.size() - 1).version().text();
        String last = null; for (String line : text.split("\\R")) if (!line.isBlank()) last = line.trim().split("\\s+", 2)[0];
        if (last == null) throw new IOException("Retro Rewind version list is empty");
        return Version.parse(last).text();
    }
    private static List<Update> parseUpdates(String text) {
        List<Update> result = new ArrayList<>();
        for (String line : text.split("\\R")) {
            String[] fields = line.trim().split("\\s+", 4); if (fields.length < 2) continue;
            try {
                String url = fields[1].replace("http://update.rwfc.net:8000/", "https://update.rwfc.net/");
                if (!url.toLowerCase(Locale.US).startsWith("https://")) continue;
                result.add(new Update(Version.parse(fields[0]), url));
            } catch (RuntimeException ignored) { }
        }
        result.sort(Comparator.comparing(Update::version)); return result;
    }
    private static List<Deletion> parseDeletions(String text) {
        List<Deletion> result = new ArrayList<>();
        for (String line : text.split("\\R")) {
            String[] fields = line.trim().split("\\s+", 2); if (fields.length < 2) continue;
            try { result.add(new Deletion(Version.parse(fields[0]), fields[1].trim())); }
            catch (RuntimeException ignored) { }
        }
        return result;
    }
    private static void applyDeletions(File root, List<Deletion> deletions, Version from, Version to) throws IOException {
        String allowed = root.getCanonicalPath() + File.separator;
        for (Deletion deletion : deletions) if (deletion.version().compareTo(from) > 0
                && deletion.version().compareTo(to) <= 0) {
            String path = deletion.path().replace('\\', '/'); while (path.startsWith("/")) path = path.substring(1);
            File target = new File(root, path);
            if (!target.getCanonicalPath().startsWith(allowed)) throw new IOException("Unsafe Retro Rewind deletion path");
            delete(target);
        }
    }
    private static Version readInstalledVersion(File root) throws IOException {
        File version = caseInsensitiveChild(root, "RetroRewind6/version.txt");
        if (!version.isFile()) return Version.ZERO;
        return Version.parse(new String(Files.readAllBytes(version.toPath()), StandardCharsets.UTF_8).trim());
    }
    private static String firstHttps(String text) throws IOException {
        for (String token : text.split("\\s+")) if (token.toLowerCase(Locale.US).startsWith("https://")) return token.trim();
        throw new IOException("Retro Rewind install service returned no HTTPS URL");
    }

    private static void verifyStagingSpace(Context context, String installUrl) throws IOException {
        long archiveSize = GameBananaClient.contentLength(installUrl);
        if (archiveSize > MAX_DOWNLOAD)
            throw new IOException("Retro Rewind archive is " + gibibytes(archiveSize)
                + " GiB, above this build's " + gibibytes(MAX_DOWNLOAD) + " GiB download limit");
        long required = MIN_STAGING_SPACE;
        if (archiveSize > 0) required = Math.max(required, archiveSize * 5L);
        long available = context.getCacheDir().getUsableSpace();
        if (available > 0 && available < required)
            throw new IOException("Retro Rewind needs about " + gibibytes(required)
                + " GiB free while staging; only " + gibibytes(available) + " GiB is available");
    }

    private static String gibibytes(long bytes) {
        return String.format(Locale.US, "%.1f", bytes / (double)GIB);
    }

    private static void extract(File zipFile, File destination, Progress progress, String stage) throws IOException {
        String allowed = destination.getCanonicalPath() + File.separator; int entries = 0; long total = 0;
        try (ZipInputStream zip = new ZipInputStream(new FileInputStream(zipFile))) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; zip.closeEntry()) {
                if (++entries > MAX_ENTRIES) throw new IOException("Retro Rewind archive has too many entries");
                String name = entry.getName().replace('\\', '/'); if (name.toLowerCase(Locale.US).endsWith("desktop.ini")) continue;
                File output = new File(destination, name);
                if (!output.getCanonicalPath().startsWith(allowed)) throw new IOException("Unsafe Retro Rewind ZIP path");
                if (entry.isDirectory()) { if (!output.mkdirs() && !output.isDirectory()) throw new IOException("Cannot create update directory"); continue; }
                File parent = output.getParentFile(); if (!parent.mkdirs() && !parent.isDirectory()) throw new IOException("Cannot create update directory");
                try (OutputStream file = new FileOutputStream(output)) {
                    byte[] buffer = new byte[64 * 1024]; long current = 0;
                    for (int read; (read = zip.read(buffer)) != -1;) {
                        current += read; total += read;
                        if (current > MAX_ENTRY || total > MAX_EXPANDED) throw new IOException("Retro Rewind archive exceeds safety limits");
                        file.write(buffer, 0, read);
                        if ((total & ((4L * 1024 * 1024) - 1)) < buffer.length)
                            report(progress, stage, total, -1);
                    }
                }
            }
        }
    }
    private static void createZip(File root, File destination, Progress progress) throws IOException {
        List<File> files = new ArrayList<>(); collect(root, files);
        files.sort(Comparator.comparing(file -> relative(root, file))); if (files.size() > MAX_ENTRIES)
            throw new IOException("Retro Rewind profile has too many files");
        long totalBytes = 0; for (File file : files) totalBytes += file.length();
        final long expected = totalBytes; long written = 0;
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(destination))) {
            byte[] buffer = new byte[64 * 1024];
            for (File file : files) {
                ZipEntry entry = new ZipEntry(relative(root, file).replace('\\', '/')); entry.setTime(0); zip.putNextEntry(entry);
                try (InputStream input = new FileInputStream(file)) {
                    for (int read; (read = input.read(buffer)) != -1;) {
                        zip.write(buffer, 0, read); written += read;
                        if ((written & ((4L * 1024 * 1024) - 1)) < buffer.length)
                            report(progress, "Preparing Android mod profile", written, expected);
                    }
                }
                zip.closeEntry();
            }
        }
    }
    private static File caseInsensitiveChild(File root, String path) throws IOException {
        File current = root;
        for (String part : path.replace('\\', '/').split("/")) {
            File exact = new File(current, part); if (exact.exists()) { current = exact; continue; }
            File match = null; File[] children = current.listFiles();
            if (children != null) for (File child : children) if (child.getName().equalsIgnoreCase(part)) { match = child; break; }
            current = match == null ? exact : match;
        }
        String allowed = root.getCanonicalPath() + File.separator;
        if (!current.getCanonicalPath().startsWith(allowed)) throw new IOException("Unsafe Retro Rewind path");
        return current;
    }
    private static void collect(File directory, List<File> files) {
        File[] children = directory.listFiles(); if (children == null) return;
        for (File child : children) { if (child.isDirectory()) collect(child, files); else files.add(child); }
    }
    private static String relative(File root, File file) { return root.toPath().relativize(file.toPath()).toString(); }
    private static void delete(File file) {
        File[] children = file.listFiles(); if (children != null) for (File child : children) delete(child); file.delete();
    }
    private static boolean active(Progress progress, String stage, long done, long total) {
        return progress == null || progress.update(stage, done, total);
    }
    private static void report(Progress progress, String stage, long done, long total) throws InterruptedIOException {
        if (!active(progress, stage, done, total)) throw new InterruptedIOException("Retro Rewind installation cancelled");
    }

    private record Version(int major, int minor, int patch, String text) implements Comparable<Version> {
        static final Version ZERO = new Version(0, 0, 0, "0.0.0");
        static Version parse(String value) {
            String clean = value.trim(); if (clean.startsWith("v") || clean.startsWith("V")) clean = clean.substring(1);
            String numeric = clean.split("[-+]", 2)[0]; String[] parts = numeric.split("\\.");
            if (parts.length < 2 || parts.length > 3) throw new IllegalArgumentException("Invalid version");
            int major = Integer.parseInt(parts[0]), minor = Integer.parseInt(parts[1]);
            int patch = parts.length == 3 ? Integer.parseInt(parts[2]) : 0;
            return new Version(major, minor, patch, clean);
        }
        @Override public int compareTo(Version other) {
            int value = Integer.compare(major, other.major); if (value == 0) value = Integer.compare(minor, other.minor);
            return value == 0 ? Integer.compare(patch, other.patch) : value;
        }
    }
}

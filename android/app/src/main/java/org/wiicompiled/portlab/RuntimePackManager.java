package org.wiicompiled.portlab;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.Comparator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Owns private, user-built ARM64 runtime packs. No translated code is shipped in the public APK. */
final class RuntimePackManager {
    private static final String MANIFEST = "runtime-pack.properties";
    private static final String ROOT = "runtime-packs", ACTIVE = "active";
    private static final int FORMAT = 1, MAX_ENTRIES = 20_000;
    private static final long MAX_ENTRY_BYTES = 1_100L * 1024 * 1024;
    private static final long MAX_TOTAL_BYTES = 2_200L * 1024 * 1024;
    private static final Set<String> LIBRARIES = Set.of(
        "libc++_shared.so", "libpng16.so", "libmain_hook.so", "libhook_impl.so",
        "libWiiCompiled.so", "libRetroRewind.so");
    private static final String[] LOAD_ORDER = {
        "libc++_shared.so", "libpng16.so", "libhook_impl.so", "libmain_hook.so"
    };

    static final class Pack {
        final File directory;
        final String label;
        final Map<String, String> hashes;
        Pack(File directory, String label, Map<String, String> hashes) {
            this.directory = directory; this.label = label; this.hashes = hashes;
        }
        File library(String runtimeName) {
            return new File(directory, "lib/arm64-v8a/lib" + runtimeName + ".so");
        }
        boolean has(String runtimeName) { return library(runtimeName).isFile(); }
    }

    static String status(Context context) {
        try {
            Pack pack = active(context);
            if (pack == null) return "No private ARM64 runtime installed.";
            String products = pack.has(CodePatchRegistry.RETRO_LIBRARY)
                ? "Base game + Retro Rewind" : "Base game";
            return "Installed: " + pack.label + "\n" + products + " · ARM64 · local only";
        } catch (IOException error) {
            return "Runtime pack is invalid: " + error.getMessage();
        }
    }

    static boolean canLaunch(Context context, String runtimeName) {
        try { Pack pack = active(context); return pack != null && pack.has(runtimeName); }
        catch (IOException ignored) { return false; }
    }

    static String importZip(Context context, Uri uri) throws IOException {
        File root = root(context), staging = new File(root, "importing-" + System.currentTimeMillis());
        if (!staging.mkdirs()) throw new IOException("Cannot create private runtime staging directory");
        try {
            extract(context, uri, staging);
            Pack pack = validate(staging);
            installResources(context, pack);
            activate(root, staging);
            return "Activated " + displayName(context, uri) + ".\n" + status(context);
        } catch (IOException | RuntimeException error) {
            deleteRecursively(staging);
            throw error;
        }
    }

    static String installBuilt(Context context, File gameLibrary, File sdkRoot) throws IOException {
        if (!gameLibrary.isFile()) throw new IOException("The Android build produced no game library");
        File root = root(context), staging = new File(root, "building-" + System.currentTimeMillis());
        if (!staging.mkdirs()) throw new IOException("Cannot create private runtime staging directory");
        try {
            File lib = new File(staging, "lib/arm64-v8a");
            if (!lib.mkdirs()) throw new IOException("Cannot create runtime library directory");
            Files.copy(gameLibrary.toPath(), new File(lib, "libWiiCompiled.so").toPath());
            File nativeLib = new File(context.getApplicationInfo().nativeLibraryDir);
            for (String name : LOAD_ORDER) {
                File source = new File(nativeLib, name);
                if (source.isFile()) Files.copy(source.toPath(), new File(lib, name).toPath());
            }
            File assets = new File(sdkRoot, "kit/runtime/assets");
            copyTree(new File(assets, "wii"), new File(staging, "resources/wii_bootstrap"));
            Files.copy(new File(assets, "dsp/dsp_coef.bin").toPath(),
                new File(staging, "resources/dsp_coef.bin").toPath(), StandardCopyOption.REPLACE_EXISTING);
            Files.copy(new File(assets, "pipeline/initial_pipeline_cache.db").toPath(),
                new File(staging, "resources/initial_pipeline_cache.db").toPath(), StandardCopyOption.REPLACE_EXISTING);
            writeManifest(staging, "Built on this Android device");
            Pack pack = validate(staging);
            installResources(context, pack);
            activate(root, staging);
            return "Private ARM64 runtime built and activated.\n" + status(context);
        } catch (IOException | RuntimeException error) {
            deleteRecursively(staging); throw error;
        }
    }

    static Pack active(Context context) throws IOException {
        File directory = new File(root(context), ACTIVE);
        return directory.isDirectory() ? validate(directory) : null;
    }

    static File prepareForLaunch(Context context, String runtimeName) throws IOException {
        Pack pack = active(context);
        if (pack == null) throw new IOException("Build or import a private ARM64 runtime first.");
        File runtime = pack.library(runtimeName);
        if (!runtime.isFile()) throw new IOException(pack.label + " does not contain lib" + runtimeName + ".so");
        installResources(context, pack);
        return runtime;
    }

    static void load(Pack pack, String runtimeName) throws IOException {
        File lib = new File(pack.directory, "lib/arm64-v8a");
        try {
            for (String name : LOAD_ORDER) {
                File dependency = new File(lib, name);
                if (dependency.isFile()) System.load(dependency.getAbsolutePath());
            }
            System.load(pack.library(runtimeName).getAbsolutePath());
        } catch (UnsatisfiedLinkError error) {
            throw new IOException("Android rejected the private runtime: " + error.getMessage(), error);
        }
    }

    private static Pack validate(File directory) throws IOException {
        File manifestFile = new File(directory, MANIFEST);
        if (!manifestFile.isFile()) throw new IOException("Missing " + MANIFEST);
        Properties values = new Properties();
        try (InputStream input = new FileInputStream(manifestFile)) { values.load(input); }
        if (!Integer.toString(FORMAT).equals(values.getProperty("format")))
            throw new IOException("Unsupported runtime pack format");
        if (!"RMCP01".equals(values.getProperty("gameId")) || !"0".equals(values.getProperty("discRevision")))
            throw new IOException("Runtime pack is not for clean PAL RMCP01 revision 0");
        if (!"arm64-v8a".equals(values.getProperty("abi"))) throw new IOException("Runtime pack is not Android ARM64");
        String label = values.getProperty("label", "Private tablet build").trim();
        int count;
        try { count = Integer.parseInt(values.getProperty("fileCount", "-1")); }
        catch (NumberFormatException error) { throw new IOException("Invalid runtime file count", error); }
        if (count < 1 || count > MAX_ENTRIES) throw new IOException("Invalid runtime file count");
        Map<String, String> expected = new HashMap<>();
        for (int index = 0; index < count; index++) {
            String path = safePath(values.getProperty("file." + index + ".path"));
            String hash = values.getProperty("file." + index + ".sha256", "").toLowerCase(Locale.US);
            if (!hash.matches("[0-9a-f]{64}") || expected.put(path, hash) != null)
                throw new IOException("Invalid or duplicate runtime manifest entry: " + path);
        }
        List<File> files = new ArrayList<>(); collectFiles(directory, files);
        int seen = 0;
        for (File file : files) {
            String path = relative(directory, file);
            if (MANIFEST.equals(path)) continue;
            String expectedHash = expected.get(path);
            if (expectedHash == null) throw new IOException("Unlisted runtime file: " + path);
            if (!expectedHash.equals(sha256(file))) throw new IOException("Runtime hash mismatch: " + path);
            if (path.startsWith("lib/")) validateLibraryPathAndElf(path, file);
            else validateResourcePath(path);
            seen++;
        }
        if (seen != expected.size()) throw new IOException("Runtime manifest lists missing files");
        if (!new File(directory, "lib/arm64-v8a/libWiiCompiled.so").isFile())
            throw new IOException("Runtime pack has no base game library");
        return new Pack(directory, label.isEmpty() ? "Private tablet build" : label, expected);
    }

    private static void extract(Context context, Uri uri, File destination) throws IOException {
        String root = destination.getCanonicalPath() + File.separator;
        int entries = 0; long total = 0;
        try (InputStream raw = context.getContentResolver().openInputStream(uri);
             ZipInputStream zip = raw == null ? null : new ZipInputStream(new BufferedInputStream(raw, 256 * 1024))) {
            if (zip == null) throw new IOException("Document provider did not open the runtime ZIP");
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; zip.closeEntry()) {
                if (++entries > MAX_ENTRIES) throw new IOException("Runtime pack has too many entries");
                String path = safePath(entry.getName());
                File output = new File(destination, path);
                if (!output.getCanonicalPath().startsWith(root)) throw new IOException("Unsafe runtime path");
                if (entry.isDirectory()) {
                    if (!output.mkdirs() && !output.isDirectory()) throw new IOException("Cannot create runtime directory");
                    continue;
                }
                if (entry.getSize() > MAX_ENTRY_BYTES) throw new IOException("Runtime entry is too large: " + path);
                File parent = output.getParentFile();
                if (!parent.mkdirs() && !parent.isDirectory()) throw new IOException("Cannot create runtime directory");
                long entryBytes = 0;
                try (OutputStream file = new FileOutputStream(output)) {
                    byte[] buffer = new byte[256 * 1024];
                    for (int read; (read = zip.read(buffer)) != -1;) {
                        entryBytes += read; total += read;
                        if (entryBytes > MAX_ENTRY_BYTES || total > MAX_TOTAL_BYTES)
                            throw new IOException("Runtime pack expands beyond the safety limit");
                        file.write(buffer, 0, read);
                    }
                }
            }
        }
        if (entries == 0) throw new IOException("Runtime ZIP is empty or invalid");
    }

    private static void installResources(Context context, Pack pack) throws IOException {
        File source = new File(pack.directory, "resources");
        if (!source.isDirectory()) return;
        File external = context.getExternalFilesDir(null);
        if (external == null) throw new IOException("External app storage unavailable");
        File game = new File(external, "game");
        copyTree(source, game);
    }

    private static void writeManifest(File directory, String label) throws IOException {
        List<File> files = new ArrayList<>(); collectFiles(directory, files);
        files.removeIf(file -> MANIFEST.equals(relative(directory, file)));
        files.sort(Comparator.comparing(file -> relative(directory, file)));
        Properties values = new Properties();
        values.setProperty("format", Integer.toString(FORMAT));
        values.setProperty("gameId", "RMCP01"); values.setProperty("discRevision", "0");
        values.setProperty("abi", "arm64-v8a"); values.setProperty("label", label);
        values.setProperty("fileCount", Integer.toString(files.size()));
        for (int index = 0; index < files.size(); index++) {
            String path = relative(directory, files.get(index));
            values.setProperty("file." + index + ".path", path);
            values.setProperty("file." + index + ".sha256", sha256(files.get(index)));
        }
        try (OutputStream output = new FileOutputStream(new File(directory, MANIFEST))) {
            values.store(output, "Private WiiCompiled runtime generated locally");
        }
    }

    private static void activate(File root, File staging) throws IOException {
        File active = new File(root, ACTIVE), old = new File(root, "previous");
        deleteRecursively(old);
        if (active.exists() && !active.renameTo(old)) throw new IOException("Cannot preserve the previous runtime pack");
        if (!staging.renameTo(active)) {
            if (old.exists()) old.renameTo(active);
            throw new IOException("Cannot activate the private runtime pack");
        }
        deleteRecursively(old);
    }

    private static void copyTree(File source, File destination) throws IOException {
        File[] children = source.listFiles();
        if (children == null) throw new IOException("Cannot read runtime resources");
        if (!destination.mkdirs() && !destination.isDirectory()) throw new IOException("Cannot create game resource directory");
        for (File child : children) {
            File target = new File(destination, child.getName());
            if (child.isDirectory()) copyTree(child, target);
            else Files.copy(child.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void validateLibraryPathAndElf(String path, File file) throws IOException {
        if (!path.startsWith("lib/arm64-v8a/") || !LIBRARIES.contains(file.getName()))
            throw new IOException("Unsupported runtime library: " + path);
        byte[] header = new byte[20]; int offset = 0;
        try (InputStream input = new FileInputStream(file)) {
            while (offset < header.length) { int read = input.read(header, offset, header.length - offset); if (read < 0) break; offset += read; }
        }
        int type = offset == header.length ? (header[16] & 255) | ((header[17] & 255) << 8) : -1;
        int machine = offset == header.length ? (header[18] & 255) | ((header[19] & 255) << 8) : -1;
        if (offset != header.length || header[0] != 0x7f || header[1] != 'E' || header[2] != 'L' || header[3] != 'F'
                || header[4] != 2 || header[5] != 1 || type != 3 || machine != 183)
            throw new IOException(path + " is not an ARM64 ELF shared library");
    }

    private static void validateResourcePath(String path) throws IOException {
        if (path.equals("resources/dsp_coef.bin") || path.equals("resources/initial_pipeline_cache.db")
                || path.startsWith("resources/wii_bootstrap/")) return;
        throw new IOException("Unsupported runtime resource: " + path);
    }

    private static String safePath(String raw) throws IOException {
        if (raw == null) throw new IOException("Runtime entry has no path");
        String path = raw.replace('\\', '/');
        while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        if (path.isEmpty() || path.startsWith("/") || path.matches("^[A-Za-z]:.*")) throw new IOException("Unsafe runtime path: " + raw);
        for (String part : path.split("/")) if (part.isEmpty() || part.equals(".") || part.equals(".."))
            throw new IOException("Unsafe runtime path: " + raw);
        return path;
    }

    private static String relative(File root, File file) {
        return root.toPath().toAbsolutePath().normalize().relativize(file.toPath().toAbsolutePath().normalize())
            .toString().replace('\\', '/');
    }
    private static void collectFiles(File directory, List<File> files) {
        File[] children = directory.listFiles(); if (children == null) return;
        for (File child : children) { if (child.isDirectory()) collectFiles(child, files); else files.add(child); }
    }
    private static String sha256(File file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = new FileInputStream(file)) {
                byte[] buffer = new byte[256 * 1024]; for (int read; (read = input.read(buffer)) != -1;) digest.update(buffer, 0, read);
            }
            StringBuilder result = new StringBuilder();
            for (byte value : digest.digest()) result.append(String.format(Locale.US, "%02x", value & 255));
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static File root(Context context) { File root = new File(context.getFilesDir(), ROOT); root.mkdirs(); return root; }
    private static String displayName(Context context, Uri uri) {
        try (Cursor cursor = context.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) { String name = cursor.getString(0); if (name != null) return name; }
        } catch (RuntimeException ignored) { }
        return "private runtime pack";
    }
    private static void deleteRecursively(File file) {
        File[] children = file.listFiles(); if (children != null) for (File child : children) deleteRecursively(child);
        file.delete();
    }
}

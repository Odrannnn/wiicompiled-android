package org.wiicompiled.portlab;

import android.content.Context;
import android.os.StatFs;
import android.system.Os;
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Runs the private RMCP01 translation and ARM64 compilation entirely inside the app sandbox. */
final class AndroidBuilderManager {
    interface Progress { void update(String message, int percent); }
    private static final AtomicBoolean CANCELLED = new AtomicBoolean();
    private static volatile Process activeProcess;
    private static final long MIN_FREE_BYTES = 4L * 1024 * 1024 * 1024;

    static boolean available(Context context) {
        if (!BuildConfig.WITH_BUILDER) return false;
        File nativeDir = new File(context.getApplicationInfo().nativeLibraryDir);
        return new File(nativeDir, "libwiicompiled_translator.so").isFile()
            && new File(nativeDir, "libwiicompiled_clang.so").isFile();
    }

    static String status(Context context) {
        if (!BuildConfig.WITH_BUILDER) return "Install the Builder edition to compile on this device.";
        if (!available(context)) return "Builder payload is incomplete; reinstall the Builder APK.";
        return context.getSharedPreferences("android-builder", Context.MODE_PRIVATE)
            .getString("status", "Ready to build from the extracted PAL disc.");
    }

    static void cancel() {
        CANCELLED.set(true);
        Process process = activeProcess;
        if (process != null) process.destroyForcibly();
    }

    static String build(Context context, Progress progress) throws IOException {
        if (!available(context)) throw new IOException("This APK does not contain the Android builder payload");
        CANCELLED.set(false);
        File external = context.getExternalFilesDir(null);
        if (external == null) throw new IOException("External app storage is unavailable");
        File dol = new File(external, "game/disc/sys/main.dol");
        File rel = new File(external, "game/disc/files/rel/StaticR.rel");
        if (!dol.isFile() || !rel.isFile()) throw new IOException("Extract a clean PAL RMCP01 disc in the Play page first");
        if (new StatFs(context.getFilesDir().getAbsolutePath()).getAvailableBytes() < MIN_FREE_BYTES)
            throw new IOException("At least 4 GiB of free internal storage is required for translation and compilation");

        File root = new File(context.getFilesDir(), "android-builder");
        File sdk = new File(root, "sdk-v1"), marker = new File(sdk, ".complete");
        if (!marker.isFile()) {
            progress.update("Preparing the compiler and reusable runtime SDK…", 2);
            deleteRecursively(sdk);
            if (!sdk.mkdirs()) throw new IOException("Cannot create the private builder SDK directory");
            extractAsset(context, "compiler-sdk.zip", sdk);
            extractAsset(context, "runtime-sdk.zip", sdk);
            if (!marker.createNewFile()) throw new IOException("Cannot finalize the private builder SDK");
        }
        checkCancelled();
        File workspace = new File(root, "workspace"), objects = new File(root, "objects");
        deleteRecursively(workspace); deleteRecursively(objects);
        if (!workspace.mkdirs() || !objects.mkdirs()) throw new IOException("Cannot create the private build workspace");
        copyTree(new File(sdk, "kit"), workspace);
        File assets = new File(workspace, "Assets");
        if (!assets.mkdirs()) throw new IOException("Cannot create the private translation input directory");
        Files.copy(dol.toPath(), new File(assets, "main.dol").toPath(), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(rel.toPath(), new File(assets, "StaticR.rel").toPath(), StandardCopyOption.REPLACE_EXISTING);

        File log = new File(root, "build.log");
        try (FileWriter ignored = new FileWriter(log, false)) { }
        File nativeDir = new File(context.getApplicationInfo().nativeLibraryDir);
        File bin = new File(root, "bin"); if (!bin.mkdirs() && !bin.isDirectory()) throw new IOException("Cannot create tool links");
        link(new File(nativeDir, "libwiicompiled_clang.so"), new File(bin, "clang++"));
        link(new File(nativeDir, "libwiicompiled_lld.so"), new File(bin, "ld.lld"));
        link(new File(nativeDir, "libwiicompiled_llvm_ar.so"), new File(bin, "llvm-ar"));
        File translator = new File(nativeDir, "libwiicompiled_translator.so");
        File project = new File(workspace, "projects/mkwii/recomp.yml");
        int threads = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 2));

        progress.update("Translating PowerPC game code…", 6);
        run(workspace, log, nativeDir, list(translator, "translate-recursive", "0x800060A4", "--project", project,
            "--outdir", new File(workspace, "generated/functions"), "--output-metadata",
            new File(workspace, "generated/base_translation_metadata.json"), "--production-source-bundle",
            new File(workspace, "generated/base_translation_sources.bin"), "--no-function-files", "--prune-stale",
            "--threads", Integer.toString(threads)));
        progress.update("Writing the private base manifest…", 27);
        run(workspace, log, nativeDir, list(translator, "emit-base-manifest", "--project", project, "--out",
            new File(workspace, "build/base"), "--functions-dir", new File(workspace, "generated/functions"),
            "--translation-output-metadata", new File(workspace, "generated/base_translation_metadata.json"), "--region", "P"));
        run(workspace, log, nativeDir, list(translator, "generate-data-init", "--project", project));
        run(workspace, log, nativeDir, list(translator, "emit-build-shards", "--project", project,
            "--base-metadata", new File(workspace, "generated/base_translation_metadata.json"), "--base-functions-dir",
            new File(workspace, "generated/functions"), "--native-source-dir", new File(workspace, "runtime/src"),
            "--out", new File(workspace, "generated/build_shards")));

        List<File> sources = buildSources(workspace);
        if (sources.size() < 10) throw new IOException("Translator produced an incomplete Android source graph");
        File clang = new File(bin, "clang++"), sysroot = new File(sdk, "toolchain/sysroot");
        File resource = new File(sdk, "toolchain/lib/clang/21");
        List<File> objectFiles = new ArrayList<>();
        for (int index = 0; index < sources.size(); index++) {
            checkCancelled();
            File source = sources.get(index), object = new File(objects, String.format("unit-%03d.o", index));
            List<Object> command = compilePrefix(clang, bin, sysroot, resource, workspace);
            command.add("-c"); command.add(source); command.add("-o"); command.add(object);
            int percent = 32 + (int)((index + 1L) * 55 / sources.size());
            progress.update("Compiling private ARM64 unit " + (index + 1) + " of " + sources.size() + "…", percent);
            run(workspace, log, nativeDir, command); objectFiles.add(object);
        }

        progress.update("Linking the private ARM64 runtime…", 90);
        File result = new File(root, "output/libWiiCompiled.so");
        if (!result.getParentFile().mkdirs() && !result.getParentFile().isDirectory()) throw new IOException("Cannot create build output directory");
        List<Object> link = compilePrefix(clang, bin, sysroot, resource, workspace);
        link.add("-shared"); link.add("-Wl,-z,max-page-size=16384"); link.add("-Wl,-z,defs");
        link.add("-Wl,--gc-sections"); link.add("-Wl,--strip-all"); link.add("-Wl,-soname,libWiiCompiled.so");
        link.add("-o"); link.add(result); link.addAll(objectFiles);
        for (String argument : Files.readAllLines(new File(sdk, "runtime-sdk/link-arguments.txt").toPath())) {
            if (argument.isBlank()) continue;
            if (argument.contains("libwiicompiled_runtime_support.a")) link.add("-Wl,--whole-archive");
            if (argument.startsWith("@native/")) link.add(new File(nativeDir, argument.substring(8)));
            else if (argument.startsWith("runtime-sdk/")) link.add(new File(sdk, argument));
            else link.add(argument);
            if (argument.contains("libwiicompiled_runtime_support.a")) link.add("-Wl,--no-whole-archive");
        }
        run(workspace, log, nativeDir, link);
        progress.update("Verifying and activating the private runtime…", 97);
        String finished = RuntimePackManager.installBuilt(context, result, sdk);
        context.getSharedPreferences("android-builder", Context.MODE_PRIVATE).edit().putString("status", finished).apply();
        progress.update(finished, 100); return finished;
    }

    private static List<Object> compilePrefix(File clang, File bin, File sysroot, File resource, File workspace) {
        List<Object> command = new ArrayList<>();
        command.add(clang); command.add("--target=aarch64-linux-android30"); command.add("--sysroot=" + sysroot);
        command.add("-resource-dir=" + resource); command.add("-B" + bin); command.add("-fuse-ld=lld");
        command.add("-std=gnu++20"); command.add("-fPIC"); command.add("-march=armv8-a");
        command.add("-O2"); command.add("-fno-fast-math"); command.add("-ffp-contract=off");
        command.add("-fno-slp-vectorize"); command.add("-w"); command.add("-DTARGET_PC"); command.add("-DNOMINMAX");
        command.add("-DSDL_MAIN_HANDLED"); command.add("-D_DISABLE_STRING_ANNOTATION");
        command.add("-D_DISABLE_VECTOR_ANNOTATION"); command.add("-I" + new File(workspace, "runtime/include"));
        command.add("-I" + new File(workspace, "runtime/src")); command.add("-I" + workspace);
        return command;
    }

    private static List<File> buildSources(File workspace) throws IOException {
        List<File> result = new ArrayList<>(); File generated = new File(workspace, "generated");
        for (String directory : new String[]{"base_common","base_portable_sensitive","base_dispatch","base_registration"})
            collect(new File(generated, "build_shards/" + directory), result, ".cpp");
        for (String name : new String[]{"data_sections_init.cpp","guest_symbol_table.cpp","data_sections_init_blobs.S"}) {
            File file = new File(generated, name); if (!file.isFile()) throw new IOException("Missing generated source " + name); result.add(file);
        }
        result.sort(Comparator.comparing(File::getAbsolutePath)); return result;
    }

    private static void collect(File directory, List<File> output, String suffix) {
        File[] children = directory.listFiles(); if (children == null) return;
        for (File child : children) { if (child.isDirectory()) collect(child, output, suffix); else if (child.getName().endsWith(suffix)) output.add(child); }
    }

    private static List<Object> list(Object... values) { return new ArrayList<>(List.of(values)); }
    private static void run(File directory, File log, File nativeDir, List<Object> values) throws IOException {
        checkCancelled(); List<String> command = new ArrayList<>(); for (Object value : values) command.add(value.toString());
        ProcessBuilder builder = new ProcessBuilder(command).directory(directory).redirectErrorStream(true);
        builder.environment().put("LD_LIBRARY_PATH", nativeDir.getAbsolutePath());
        builder.environment().put("TMPDIR", new File(directory, "tmp").getAbsolutePath());
        new File(directory, "tmp").mkdirs();
        try (FileWriter writer = new FileWriter(log, true)) {
            writer.write("\n$ " + String.join(" ", command) + "\n"); writer.flush();
            Process process = builder.start(); activeProcess = process;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                for (String line; (line = reader.readLine()) != null;) { writer.write(line); writer.write('\n'); }
            }
            try {
                int code = process.waitFor(); if (code != 0) throw new IOException("Build command failed (exit " + code + "). See " + log);
            } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException("Android build interrupted", error); }
            finally { activeProcess = null; }
        }
        checkCancelled();
    }

    private static void extractAsset(Context context, String name, File destination) throws IOException {
        String root = destination.getCanonicalPath() + File.separator; int entries = 0; long total = 0;
        try (InputStream raw = context.getAssets().open(name); ZipInputStream zip = new ZipInputStream(new BufferedInputStream(raw, 256 * 1024))) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; zip.closeEntry()) {
                if (++entries > 60_000) throw new IOException(name + " contains too many entries");
                String path = entry.getName().replace('\\','/');
                if (path.startsWith("/") || path.contains("../") || path.equals("..")) throw new IOException("Unsafe SDK path");
                File output = new File(destination, path);
                if (!output.getCanonicalPath().startsWith(root)) throw new IOException("Unsafe SDK path");
                if (entry.isDirectory()) { if (!output.mkdirs() && !output.isDirectory()) throw new IOException("Cannot create SDK directory"); continue; }
                File parent = output.getParentFile(); if (!parent.mkdirs() && !parent.isDirectory()) throw new IOException("Cannot create SDK directory");
                try (OutputStream file = new FileOutputStream(output)) {
                    byte[] buffer = new byte[256 * 1024]; for (int read; (read = zip.read(buffer)) != -1;) {
                        total += read; if (total > 2L * 1024 * 1024 * 1024) throw new IOException("SDK expands beyond 2 GiB"); file.write(buffer,0,read);
                    }
                }
            }
        }
    }

    private static void link(File target, File link) throws IOException {
        try { Files.deleteIfExists(link.toPath()); Os.symlink(target.getAbsolutePath(), link.getAbsolutePath()); }
        catch (Exception error) { throw new IOException("Cannot prepare signed native tool " + link.getName(), error); }
    }
    private static void copyTree(File source, File destination) throws IOException {
        File[] children = source.listFiles(); if (children == null) throw new IOException("Missing builder source kit: " + source);
        if (!destination.mkdirs() && !destination.isDirectory()) throw new IOException("Cannot create " + destination);
        for (File child : children) { File target = new File(destination, child.getName()); if (child.isDirectory()) copyTree(child,target); else Files.copy(child.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING); }
    }
    private static void checkCancelled() throws IOException { if (CANCELLED.get()) throw new IOException("Build cancelled"); }
    private static void deleteRecursively(File file) { File[] children=file.listFiles(); if(children!=null) for(File child:children) deleteRecursively(child); file.delete(); }
}

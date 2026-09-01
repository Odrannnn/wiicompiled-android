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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
        File sdk = new File(root, "sdk-v2"), marker = new File(sdk, ".complete");
        deleteRecursively(new File(root, "sdk-v1"));
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
        File retroRoot = enabledRetroRewindRoot(context);
        File stagedRetro = retroRoot == null ? null : stageRetroRewind(workspace, retroRoot);
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
        File retroOutput = new File(workspace, "build/mods/retro_rewind_full_cpp");
        if (retroRoot != null) {
            progress.update("Translating the pinned Retro Rewind code patch…", 30);
            run(workspace, log, nativeDir, list(translator, "translate-mod", "--project", project,
                "--profile", "retro-rewind", "--base-manifest", new File(workspace, "build/base/mkwii_base_manifest.json"),
                "--base-translation-output-metadata", new File(workspace, "generated/base_translation_metadata.json"),
                "--code-pul", new File(stagedRetro, "Binaries/Code.pul"), "--mod-root", retroRoot,
                "--mod-name", "Retro Rewind", "--region", "P", "--out", retroOutput,
                "--prefer-cached-inputs", "--emit-cpp", "--threads", Integer.toString(threads), "--skip-retro-wfc"));
        }
        List<Object> shardCommand = list(translator, "emit-build-shards", "--project", project,
            "--base-metadata", new File(workspace, "generated/base_translation_metadata.json"), "--base-functions-dir",
            new File(workspace, "generated/functions"), "--native-source-dir", new File(workspace, "runtime/src"),
            "--out", new File(workspace, "generated/build_shards"));
        if (retroRoot != null) {
            shardCommand.add("--resolved-profile");
            shardCommand.add(new File(retroOutput, "resolved_dispatch_profile.json"));
            shardCommand.add("--retro-cpp-dir");
            shardCommand.add(new File(retroOutput, "cpp"));
        }
        run(workspace, log, nativeDir, shardCommand);

        List<File> baseSources = buildSources(workspace, false, retroOutput);
        List<File> retroSources = retroRoot == null ? List.of() : buildSources(workspace, true, retroOutput);
        if (baseSources.size() < 10 || (retroRoot != null && retroSources.size() < 10))
            throw new IOException("Translator produced an incomplete Android source graph");
        Map<String, File> sources = new LinkedHashMap<>();
        for (File source : baseSources) sources.put(source.getAbsolutePath(), source);
        for (File source : retroSources) sources.put(source.getAbsolutePath(), source);
        File clang = new File(bin, "clang++"), sysroot = new File(sdk, "toolchain/sysroot");
        File resource = new File(sdk, "toolchain/lib/clang/21");
        Map<String, File> objectFiles = new LinkedHashMap<>(); int index = 0;
        for (File source : sources.values()) {
            checkCancelled();
            File object = new File(objects, String.format("unit-%03d.o", index));
            List<Object> command = compilePrefix(clang, bin, sysroot, resource, workspace);
            command.add("-c"); command.add(source); command.add("-o"); command.add(object);
            int percent = 36 + (int)((index + 1L) * 51 / sources.size());
            progress.update("Compiling private ARM64 unit " + (index + 1) + " of " + sources.size() + "…", percent);
            run(workspace, log, nativeDir, command); objectFiles.put(source.getAbsolutePath(), object); index++;
        }

        progress.update("Linking the private ARM64 base runtime…", 90);
        File result = new File(root, "output/libWiiCompiled.so"), retroResult = null;
        linkProduct(workspace, log, nativeDir, clang, bin, sysroot, resource, sdk,
            result, "libWiiCompiled.so", baseSources, objectFiles);
        if (retroRoot != null) {
            progress.update("Linking the private Retro Rewind runtime…", 94);
            retroResult = new File(root, "output/libRetroRewind.so");
            linkProduct(workspace, log, nativeDir, clang, bin, sysroot, resource, sdk,
                retroResult, "libRetroRewind.so", retroSources, objectFiles);
        }
        progress.update("Verifying and activating the private runtime…", 97);
        String finished = RuntimePackManager.installBuilt(context, result, retroResult, sdk);
        context.getSharedPreferences("android-builder", Context.MODE_PRIVATE).edit().putString("status", finished).apply();
        deleteRecursively(workspace);
        deleteRecursively(objects);
        deleteRecursively(new File(root, "output"));
        deleteRecursively(bin);
        progress.update(finished, 100); return finished;
    }

    private static void linkProduct(File workspace, File log, File nativeDir, File clang, File bin,
                                    File sysroot, File resource, File sdk, File result, String soname,
                                    List<File> sources, Map<String, File> objects) throws IOException {
        if (!result.getParentFile().mkdirs() && !result.getParentFile().isDirectory())
            throw new IOException("Cannot create build output directory");
        List<Object> link = compilePrefix(clang, bin, sysroot, resource, workspace);
        link.add("-shared"); link.add("-Wl,-z,max-page-size=16384"); link.add("-Wl,-z,defs");
        link.add("-Wl,--gc-sections"); link.add("-Wl,--strip-all"); link.add("-Wl,-soname," + soname);
        link.add("-o"); link.add(result);
        for (File source : sources) {
            File object = objects.get(source.getAbsolutePath());
            if (object == null || !object.isFile()) throw new IOException("Missing compiled object for " + source.getName());
            link.add(object);
        }
        for (String argument : Files.readAllLines(new File(sdk, "runtime-sdk/link-arguments.txt").toPath())) {
            if (argument.isBlank()) continue;
            if (argument.contains("libwiicompiled_runtime_support.a")) link.add("-Wl,--whole-archive");
            if (argument.startsWith("@native/")) link.add(new File(nativeDir, argument.substring(8)));
            else if (argument.startsWith("runtime-sdk/")) link.add(new File(sdk, argument));
            else link.add(argument);
            if (argument.contains("libwiicompiled_runtime_support.a")) link.add("-Wl,--no-whole-archive");
        }
        run(workspace, log, nativeDir, link);
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

    private static List<File> buildSources(File workspace, boolean retro, File retroOutput) throws IOException {
        List<File> result = new ArrayList<>(); File generated = new File(workspace, "generated");
        String[] directories = retro
            ? new String[]{"base_common","retro_portable_sensitive","retro_mod","retro_rewind_dispatch","retro_rewind_registration"}
            : new String[]{"base_common","base_portable_sensitive","base_dispatch","base_registration"};
        for (String directory : directories)
            collect(new File(generated, "build_shards/" + directory), result, ".cpp");
        if (retro) {
            for (String name : new String[]{"mod_data_patches.cpp", "mod_data_patches_blobs.S"}) {
                File file = new File(retroOutput, "cpp/" + name);
                if (!file.isFile()) throw new IOException("Missing generated Retro Rewind source " + name);
                result.add(file);
            }
        }
        for (String name : new String[]{"data_sections_init.cpp","guest_symbol_table.cpp","data_sections_init_blobs.S"}) {
            File file = new File(generated, name); if (!file.isFile()) throw new IOException("Missing generated source " + name); result.add(file);
        }
        result.sort(Comparator.comparing(File::getAbsolutePath)); return result;
    }

    private static File enabledRetroRewindRoot(Context context) throws IOException {
        File selected = null;
        for (AndroidModManager.Mod mod : AndroidModManager.list(context)) {
            if (!mod.enabled || !mod.codeReport.needsRetroRuntime()) continue;
            if (!mod.codeReport.ready()) throw new IOException(mod.title + " is not eligible for the pinned ARM64 build");
            if (selected != null) throw new IOException("More than one Retro Rewind code profile is enabled");
            selected = new File(mod.overlayRoot);
        }
        if (selected != null && !new File(selected, "Binaries/Code.pul").isFile())
            throw new IOException("The enabled Retro Rewind profile has no Code.pul");
        return selected;
    }

    private static File stageRetroRewind(File workspace, File source) throws IOException {
        File staged = new File(workspace, "PulsarPacks/completed/RetroRewind/RetroRewind6");
        File binaries = new File(staged, "Binaries"), xml = new File(staged, "xml");
        if (!binaries.mkdirs() || !xml.mkdirs()) throw new IOException("Cannot stage Retro Rewind build inputs");
        copyRequired(new File(source, "Binaries/Code.pul"), new File(binaries, "Code.pul"));
        copyRequired(new File(source, "Binaries/Loader.pul"), new File(binaries, "Loader.pul"));
        copyRequired(new File(source, "xml/RetroRewind6.xml"), new File(xml, "RetroRewind6.xml"));
        return staged;
    }

    private static void copyRequired(File source, File destination) throws IOException {
        if (!source.isFile()) throw new IOException("Missing Retro Rewind build input " + source.getName());
        Files.copy(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
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

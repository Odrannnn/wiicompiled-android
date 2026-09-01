package org.wiicompiled.portlab;

import android.util.Xml;
import java.io.ByteArrayInputStream;
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
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import org.xmlpull.v1.XmlPullParser;

/** Hash-gated registry for executable patches that have an ARM64 translation in this APK. */
final class CodePatchRegistry {
    static final String MANIFEST = "code-requirements.properties";
    static final String RETRO_LIBRARY = "RetroRewind";
    static final String BASE_LIBRARY = "WiiCompiled";

    // Pinned TeamWheelWizard/Retro Rewind Pulsar build translated into libRetroRewind.so.
    static final String RETRO_CODE_SHA256 =
        "ea93f9b8bf6d7696a807c1da5be724f1b0ec3eea563c1fdc1adfab10cb6c98e2";
    static final String PULSAR_LOADER_SHA256 =
        "ef396b3116219ef6c5a5c96e73ad138f40505fd958417de671d11a06049d7167";
    private static final int MAX_FILES = 20_000;
    private static final long MAX_XML = 4L * 1024 * 1024;

    static final class Requirement {
        final String kind, source, address, value, original, sha256, status, detail;
        final long length;
        Requirement(String kind, String source, String address, String value, String original,
                    String sha256, long length, String status, String detail) {
            this.kind = kind; this.source = source; this.address = address; this.value = value;
            this.original = original; this.sha256 = sha256; this.length = length;
            this.status = status; this.detail = detail;
        }
        boolean translated() { return status.equals("translated") || status.equals("pal-noop"); }
    }

    static final class Report {
        final List<Requirement> requirements;
        final String codePulSha256, runtimeLibrary;
        final int translated, blocked;
        Report(List<Requirement> requirements, String codePulSha256, String runtimeLibrary) {
            this.requirements = Collections.unmodifiableList(new ArrayList<>(requirements));
            this.codePulSha256 = codePulSha256; this.runtimeLibrary = runtimeLibrary;
            int good = 0, bad = 0;
            for (Requirement item : requirements) if (item.translated()) good++; else bad++;
            translated = good; blocked = bad;
        }
        boolean needsRetroRuntime() { return RETRO_LIBRARY.equals(runtimeLibrary); }
        boolean ready() { return needsRetroRuntime() && blocked == 0; }
        String summary() {
            if (requirements.isEmpty()) return "No executable patch requirements";
            String result = translated + "/" + requirements.size() + " executable requirement(s) translated";
            if (needsRetroRuntime()) result += " · ARM64 Retro Rewind runtime";
            if (blocked > 0) result += " · " + blocked + " blocked";
            return result;
        }
        String details() {
            StringBuilder out = new StringBuilder(summary());
            for (Requirement item : requirements) {
                out.append("\n\n").append(item.translated() ? "READY  " : "BLOCKED  ")
                    .append(item.kind).append(" · ").append(item.source);
                if (!item.address.isEmpty()) out.append(" @ ").append(item.address);
                if (!item.sha256.isEmpty()) out.append("\nSHA-256 ").append(item.sha256);
                out.append("\n").append(item.detail);
            }
            return out.toString();
        }
    }

    static Report inspectAndSave(File profileDirectory, File packageRoot) throws IOException {
        List<File> files = new ArrayList<>(); collect(packageRoot, files);
        files.sort(Comparator.comparing(file -> relative(packageRoot, file), String.CASE_INSENSITIVE_ORDER));
        List<Requirement> requirements = new ArrayList<>(); String codeHash = "";
        for (File file : files) {
            String path = relative(packageRoot, file).replace('\\', '/');
            String name = file.getName().toLowerCase(Locale.US);
            if (name.endsWith(".xml") && file.length() <= MAX_XML) inspectXml(file, path, requirements);
            else if (name.equals("code.pul")) {
                String hash = sha256(file); if (codeHash.isEmpty()) codeHash = hash;
                boolean supported = RETRO_CODE_SHA256.equals(hash);
                requirements.add(new Requirement("Kamek module", path, "0x81800000", "", "", hash,
                    file.length(), supported ? "translated" : "blocked",
                    supported ? "Matches the pinned Retro Rewind 6.12.4 ARM64 translation bundled in this APK."
                              : "This Code.pul revision has no matching ARM64 translation."));
            } else if (name.equals("loader.pul")) {
                String hash = sha256(file); boolean supported = PULSAR_LOADER_SHA256.equals(hash);
                requirements.add(new Requirement("Pulsar loader", path, "0x80004000", "", "", hash,
                    file.length(), supported ? "translated" : "blocked",
                    supported ? "Replaced by direct Android selection of the translated runtime."
                              : "Unknown PowerPC loader; it is never executed on Android."));
            } else if (name.endsWith(".dol") || name.endsWith(".rel")) {
                requirements.add(new Requirement(name.endsWith(".dol") ? "DOL replacement" : "REL replacement",
                    path, "", "", "", sha256(file), file.length(), "blocked",
                    "Arbitrary executable replacement is not part of the translated runtime."));
            }
        }
        String runtime = RETRO_CODE_SHA256.equals(codeHash) ? RETRO_LIBRARY : BASE_LIBRARY;
        Report report = new Report(requirements, codeHash, runtime);
        save(profileDirectory, report); return report;
    }

    static Report load(File profileDirectory) {
        File manifest = new File(profileDirectory, MANIFEST);
        if (!manifest.isFile()) return new Report(Collections.emptyList(), "", BASE_LIBRARY);
        try (InputStream input = new FileInputStream(manifest)) {
            Properties p = new Properties(); p.load(input);
            int count = Integer.parseInt(p.getProperty("count", "0"));
            if (count < 0 || count > MAX_FILES) throw new IOException("Invalid code requirement count");
            List<Requirement> items = new ArrayList<>(); boolean refreshed = false;
            for (int i = 0; i < count; i++) {
                String key = "requirement." + i + ".";
                Requirement saved = new Requirement(p.getProperty(key + "kind", "unknown"),
                    p.getProperty(key + "source", ""), p.getProperty(key + "address", ""),
                    p.getProperty(key + "value", ""), p.getProperty(key + "original", ""),
                    p.getProperty(key + "sha256", ""), Long.parseLong(p.getProperty(key + "length", "0")),
                    p.getProperty(key + "status", "blocked"), p.getProperty(key + "detail", "Unclassified requirement"));
                Requirement current = refreshClassification(saved); items.add(current);
                refreshed |= !saved.status.equals(current.status) || !saved.detail.equals(current.detail);
            }
            String codeHash = p.getProperty("codePulSha256", "");
            String runtime = RETRO_CODE_SHA256.equals(codeHash) ? RETRO_LIBRARY : BASE_LIBRARY;
            refreshed |= !runtime.equals(p.getProperty("runtimeLibrary", BASE_LIBRARY));
            Report report = new Report(items, codeHash, runtime);
            if (refreshed) save(profileDirectory, report);
            return report;
        } catch (Exception ignored) { return new Report(Collections.emptyList(), "", BASE_LIBRARY); }
    }

    private static Requirement refreshClassification(Requirement saved) {
        String status = saved.status, detail = saved.detail;
        if (saved.kind.equals("Kamek module")) {
            boolean supported = RETRO_CODE_SHA256.equals(saved.sha256);
            status = supported ? "translated" : "blocked";
            detail = supported ? "Matches the pinned Retro Rewind 6.12.4 ARM64 translation bundled in this APK."
                               : "This Code.pul revision has no matching ARM64 translation.";
        } else if (saved.kind.equals("Pulsar loader")) {
            boolean supported = PULSAR_LOADER_SHA256.equals(saved.sha256);
            status = supported ? "translated" : "blocked";
            detail = supported ? "Replaced by direct Android selection of the translated runtime."
                               : "Unknown PowerPC loader; it is never executed on Android.";
        } else if (saved.kind.equals("Riivolution memory")) {
            String address = saved.address.toLowerCase(Locale.US), value = saved.value.toLowerCase(Locale.US);
            if (isPalBootstrap(address, value, saved.original)) {
                status = "translated"; detail = "Pulsar PAL bootstrap branch is replaced by Android runtime selection.";
            } else if (isOtherRegionBootstrap(address, value, saved.original)) {
                status = "pal-noop"; detail = "Guarded branch belongs to another disc region and cannot match RMCP01.";
            } else if (address.equals("0x80004000") && saved.source.contains("Loader.pul")) {
                status = "translated"; detail = "Loader upload is replaced by the hash-gated ARM64 runtime.";
            } else if (isRuntimeLowMemoryWrite(address, value, saved.original, null)) {
                status = "translated";
                detail = "Selected low-memory data write is applied before the translated game entry point.";
            }
        }
        if (status.equals(saved.status) && detail.equals(saved.detail)) return saved;
        return new Requirement(saved.kind, saved.source, saved.address, saved.value, saved.original,
            saved.sha256, saved.length, status, detail);
    }

    static String selectRuntime(List<AndroidModManager.Mod> mods) throws IOException {
        String selectedHash = null;
        for (AndroidModManager.Mod mod : mods) if (mod.enabled && !mod.codeReport.codePulSha256.isEmpty()) {
            if (!mod.codeReport.ready())
                throw new IOException(mod.title + " has executable patches without a matching ARM64 translation");
            if (selectedHash != null && !selectedHash.equals(mod.codeReport.codePulSha256))
                throw new IOException("Enabled profiles require different executable runtimes");
            selectedHash = mod.codeReport.codePulSha256;
        }
        return selectedHash == null ? BASE_LIBRARY : RETRO_LIBRARY;
    }

    private static void inspectXml(File file, String path, List<Requirement> out) throws IOException {
        byte[] bytes = Files.readAllBytes(file.toPath());
        try {
            XmlPullParser parser = Xml.newPullParser();
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false);
            parser.setInput(new ByteArrayInputStream(bytes), null);
            for (int event = parser.getEventType(); event != XmlPullParser.END_DOCUMENT; event = parser.next()) {
                if (event != XmlPullParser.START_TAG || !parser.getName().equalsIgnoreCase("memory")) continue;
                String address = attr(parser, "offset"), value = attr(parser, "value");
                String original = attr(parser, "original"), valueFile = attr(parser, "valuefile");
                String status = "blocked", detail = "PowerPC memory write has no registered ARM64 implementation.";
                String normalizedAddress = address == null ? "" : address.toLowerCase(Locale.US);
                String normalizedValue = value == null ? "" : value.toLowerCase(Locale.US);
                if (isPalBootstrap(normalizedAddress, normalizedValue, original)) {
                    status = "translated"; detail = "Pulsar PAL bootstrap branch is replaced by Android runtime selection.";
                } else if (isOtherRegionBootstrap(normalizedAddress, normalizedValue, original)) {
                    status = "pal-noop"; detail = "Guarded branch belongs to another disc region and cannot match RMCP01.";
                } else if (normalizedAddress.equals("0x80004000") && valueFile != null) {
                    status = "translated"; detail = "Loader upload is replaced by the hash-gated ARM64 runtime.";
                } else if (isRuntimeLowMemoryWrite(normalizedAddress, normalizedValue, original, valueFile)) {
                    status = "translated";
                    detail = "Selected low-memory data write is applied before the translated game entry point.";
                }
                out.add(new Requirement("Riivolution memory", path + (valueFile == null ? "" : " -> " + valueFile),
                    address == null ? "" : address, value == null ? "" : value,
                    original == null ? "" : original, "", 0, status, detail));
            }
        } catch (org.xmlpull.v1.XmlPullParserException error) {
            throw new IOException("Invalid Riivolution XML " + path, error);
        }
    }

    private static boolean isPalBootstrap(String address, String value, String original) {
        if (!"4e800020".equalsIgnoreCase(original)) return false;
        return (address.equals("0x80242698") && value.equals("4bdc1968"))
            || (address.equals("0x8000a3f4") && value.equals("4bff9c0c"));
    }
    private static boolean isOtherRegionBootstrap(String address, String value, String original) {
        if (!"4e800020".equalsIgnoreCase(original)) return false;
        return address.equals("0x802417dc") || address.equals("0x8000a3b4")
            || address.equals("0x802425b8") || address.equals("0x8000a350")
            || address.equals("0x80242a0c") || address.equals("0x8000a4fc");
    }

    private static boolean isRuntimeLowMemoryWrite(String address, String value, String original,
                                                   String valueFile) {
        if (original != null && !original.isEmpty() || valueFile != null && !valueFile.isEmpty()) return false;
        if (value.isEmpty() || (value.length() & 1) != 0 || !value.matches("[0-9a-f]+")) return false;
        try {
            long parsed = Long.decode(address);
            long end = parsed + value.length() / 2L;
            return parsed >= 0x80001000L && end <= 0x80006000L;
        } catch (RuntimeException ignored) { return false; }
    }

    private static void save(File directory, Report report) throws IOException {
        Properties p = new Properties(); p.setProperty("count", Integer.toString(report.requirements.size()));
        p.setProperty("codePulSha256", report.codePulSha256); p.setProperty("runtimeLibrary", report.runtimeLibrary);
        for (int i = 0; i < report.requirements.size(); i++) {
            Requirement item = report.requirements.get(i); String key = "requirement." + i + ".";
            p.setProperty(key + "kind", item.kind); p.setProperty(key + "source", item.source);
            p.setProperty(key + "address", item.address); p.setProperty(key + "value", item.value);
            p.setProperty(key + "original", item.original); p.setProperty(key + "sha256", item.sha256);
            p.setProperty(key + "length", Long.toString(item.length)); p.setProperty(key + "status", item.status);
            p.setProperty(key + "detail", item.detail);
        }
        File target = new File(directory, MANIFEST), temporary = new File(directory, MANIFEST + ".tmp");
        try (OutputStream output = new FileOutputStream(temporary)) { p.store(output, "ARM64 code patch compatibility"); }
        try { Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE); }
        catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void collect(File directory, List<File> files) throws IOException {
        File[] children = directory.listFiles(); if (children == null) return;
        for (File child : children) {
            if (files.size() >= MAX_FILES) throw new IOException("Mod package has too many files for code inventory");
            if (child.isDirectory()) collect(child, files); else if (child.isFile()) files.add(child);
        }
    }
    private static String relative(File root, File file) {
        return root.toPath().toAbsolutePath().normalize().relativize(file.toPath().toAbsolutePath().normalize()).toString();
    }
    private static String attr(XmlPullParser parser, String name) {
        for (int i = 0; i < parser.getAttributeCount(); i++)
            if (name.equalsIgnoreCase(parser.getAttributeName(i))) return parser.getAttributeValue(i);
        return null;
    }
    private static String sha256(File file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = new FileInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                for (int read; (read = input.read(buffer)) != -1;) digest.update(buffer, 0, read);
            }
            StringBuilder text = new StringBuilder();
            for (byte value : digest.digest()) text.append(String.format(Locale.US, "%02x", value & 0xff));
            return text.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}

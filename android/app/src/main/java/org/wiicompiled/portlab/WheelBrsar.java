package org.wiicompiled.portlab;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Applies WheelWizard/Pulsar file-ID sound overrides to an on-disc BRSAR. */
final class WheelBrsar {
    private static final int MAX_ARCHIVE_BYTES = 512 * 1024 * 1024;
    private static final int MAX_TABLE_ENTRIES = 16_384;

    static Map<Integer, byte[]> normalizeOverrides(Map<String, byte[]> members) throws IOException {
        Map<Integer, byte[]> overrides = new HashMap<>();
        for (Map.Entry<String, byte[]> entry : members.entrySet()) {
            int fileId = memberFileId(entry.getKey());
            validateOverride(entry.getKey(), entry.getValue());
            overrides.put(fileId, entry.getValue());
        }
        if (overrides.isEmpty()) throw new IOException("Tagged revo_kart bundle contains no supported sound members");
        return overrides;
    }

    static int memberFileId(String member) throws IOException {
        String name = member.replace('\\', '/');
        if (name.indexOf('/') >= 0) throw new IOException("BRSAR patch members must be at the bundle root: " + member);
        int dot = name.lastIndexOf('.');
        if (dot <= 0) throw new IOException("Invalid BRSAR patch member: " + member);
        String extension = name.substring(dot).toLowerCase(Locale.US);
        if (!extension.equals(".brbnk") && !extension.equals(".brseq") && !extension.equals(".brwsd"))
            throw new IOException("Unsupported BRSAR patch member: " + member);
        String id = name.substring(0, dot);
        if (!id.matches("[0-9]+")) throw new IOException("BRSAR patch member has no numeric file ID: " + member);
        try {
            int value = Integer.parseInt(id);
            if (value < 0 || value >= MAX_TABLE_ENTRIES) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException error) {
            throw new IOException("BRSAR patch file ID is outside the supported range: " + member);
        }
    }

    static void compose(File source, File output, Map<Integer, byte[]> overrides) throws IOException {
        long sourceLength = source.length();
        if (sourceLength < 0x40 || sourceLength > MAX_ARCHIVE_BYTES)
            throw new IOException("BRSAR size is outside the supported range");
        try (RandomAccessFile input = new RandomAccessFile(source, "r")) {
            byte[] header = new byte[0x40]; input.readFully(header);
            if (!ascii(header, 0, "RSAR")) throw new IOException("Sound archive does not start with RSAR");
            int fileSectionOffset = checkedInt(u32(header, 0x20), "BRSAR FILE offset");
            if (fileSectionOffset < 0x40 || fileSectionOffset + 8L > sourceLength || fileSectionOffset > 32 * 1024 * 1024)
                throw new IOException("BRSAR FILE section is invalid");
            byte[] metadata = new byte[fileSectionOffset + 8];
            input.seek(0); input.readFully(metadata);
            if (!ascii(metadata, fileSectionOffset, "FILE")) throw new IOException("BRSAR FILE section is missing");

            int infoOffset = checkedInt(u32(metadata, 0x18), "BRSAR INFO offset");
            int infoBase = Math.addExact(infoOffset, 8);
            requireRange(metadata, infoBase, 0x28, "BRSAR INFO header");
            int fileTable = resolveRef(metadata, infoBase + 0x18, infoBase);
            int groupTable = resolveRef(metadata, infoBase + 0x20, infoBase);
            List<Integer> fileEntries = referenceTable(metadata, fileTable, infoBase);
            List<Integer> groupEntries = referenceTable(metadata, groupTable, infoBase);
            if (fileEntries.isEmpty() || groupEntries.isEmpty()) throw new IOException("BRSAR INFO tables are empty");
            for (int fileId : overrides.keySet())
                if (fileId >= fileEntries.size()) throw new IOException("BRSAR patch file ID does not exist: " + fileId);

            Map<Integer, OverrideParts> parts = new HashMap<>();
            for (Map.Entry<Integer, byte[]> entry : overrides.entrySet()) {
                OverrideParts parsed = parseOverride(entry.getKey(), entry.getValue());
                parts.put(entry.getKey(), parsed);
                int fileEntry = fileEntries.get(entry.getKey());
                requireRange(metadata, fileEntry, 8, "BRSAR file entry");
                write32(metadata, fileEntry, parsed.main.length);
                if (parsed.wave != null) write32(metadata, fileEntry + 4, parsed.wave.length);
            }

            List<Group> groups = new ArrayList<>();
            for (int entry : groupEntries) groups.add(parseGroup(metadata, entry, infoBase));
            File parent = output.getParentFile();
            if (!parent.mkdirs() && !parent.isDirectory()) throw new IOException("Cannot create BRSAR output directory");
            try (RandomAccessFile result = new RandomAccessFile(output, "rw")) {
                result.setLength(0);
                result.write(metadata);
                long cursor = align32(fileSectionOffset + 8L);
                for (Group group : groups) {
                    cursor = align32(cursor);
                    long mainStart = cursor;
                    List<Item> mainItems = new ArrayList<>(group.items);
                    mainItems.sort(Comparator.comparingInt(item -> item.offset));
                    for (Item item : mainItems) {
                        cursor = align32(cursor);
                        int relative = checkedInt(cursor - mainStart, "BRSAR group file offset");
                        OverrideParts replacement = parts.get(item.fileId);
                        int size;
                        if (replacement != null) {
                            result.seek(cursor); result.write(replacement.main); size = replacement.main.length;
                        } else {
                            result.seek(cursor);
                            copy(input, result, (long)group.originalOffset + item.offset, item.size, sourceLength);
                            size = item.size;
                        }
                        write32(metadata, item.entryOffset + 4, relative);
                        write32(metadata, item.entryOffset + 8, size);
                        cursor += size;
                    }
                    cursor = align32(cursor);
                    int mainSize = checkedInt(cursor - mainStart, "BRSAR group file size");
                    write32(metadata, group.entryOffset + 0x10, mainStart);
                    write32(metadata, group.entryOffset + 0x14, mainSize);

                    long waveStart = cursor;
                    List<Item> waveItems = new ArrayList<>(group.items);
                    waveItems.sort(Comparator.comparingInt(item -> item.waveOffset));
                    for (Item item : waveItems) {
                        OverrideParts replacement = parts.get(item.fileId);
                        byte[] wave = replacement == null ? null : replacement.wave;
                        int size = wave == null ? item.waveSize : wave.length;
                        if (size == 0) {
                            write32(metadata, item.entryOffset + 0x0c, 0);
                            write32(metadata, item.entryOffset + 0x10, 0);
                            continue;
                        }
                        cursor = align32(cursor);
                        int relative = checkedInt(cursor - waveStart, "BRSAR group wave offset");
                        if (wave != null) { result.seek(cursor); result.write(wave); }
                        else {
                            result.seek(cursor);
                            copy(input, result, (long)group.originalWaveOffset + item.waveOffset,
                                item.waveSize, sourceLength);
                        }
                        write32(metadata, item.entryOffset + 0x0c, relative);
                        write32(metadata, item.entryOffset + 0x10, size);
                        cursor += size;
                    }
                    cursor = align32(cursor);
                    int waveSize = checkedInt(cursor - waveStart, "BRSAR group wave size");
                    write32(metadata, group.entryOffset + 0x18, waveStart);
                    write32(metadata, group.entryOffset + 0x1c, waveSize);
                }
                cursor = align32(cursor);
                if (cursor > MAX_ARCHIVE_BYTES) throw new IOException("Composed BRSAR exceeds the safety limit");
                write32(metadata, 8, cursor);
                write32(metadata, 0x24, cursor - fileSectionOffset);
                write32(metadata, fileSectionOffset + 4, cursor - fileSectionOffset);
                result.seek(0); result.write(metadata); result.setLength(cursor);
            } catch (IOException | RuntimeException error) {
                output.delete(); throw error;
            }
        }
    }

    private static Group parseGroup(byte[] metadata, int entry, int infoBase) throws IOException {
        requireRange(metadata, entry, 0x28, "BRSAR group entry");
        int itemTable = resolveRef(metadata, entry + 0x20, infoBase);
        List<Integer> itemEntries = referenceTable(metadata, itemTable, infoBase);
        List<Item> items = new ArrayList<>();
        for (int itemEntry : itemEntries) {
            requireRange(metadata, itemEntry, 0x18, "BRSAR group item");
            items.add(new Item(itemEntry, checkedInt(u32(metadata, itemEntry), "BRSAR file ID"),
                checkedInt(u32(metadata, itemEntry + 4), "BRSAR item offset"),
                checkedInt(u32(metadata, itemEntry + 8), "BRSAR item size"),
                checkedInt(u32(metadata, itemEntry + 0x0c), "BRSAR wave offset"),
                checkedInt(u32(metadata, itemEntry + 0x10), "BRSAR wave size")));
        }
        return new Group(entry, checkedInt(u32(metadata, entry + 0x10), "BRSAR group offset"),
            checkedInt(u32(metadata, entry + 0x18), "BRSAR group wave offset"), items);
    }

    private static OverrideParts parseOverride(int fileId, byte[] bytes) throws IOException {
        validateOverride(Integer.toString(fileId), bytes);
        int mainSize = checkedInt(u32(bytes, 8), "BRSAR override size");
        byte[] main = java.util.Arrays.copyOfRange(bytes, 0, mainSize);
        int waveAt = align32(mainSize);
        byte[] wave = null;
        if (waveAt + 0x20 <= bytes.length && ascii(bytes, waveAt, "RWAR")) {
            int waveSize = checkedInt(u32(bytes, waveAt + 8), "RWAR override size");
            if (waveSize < 0x20 || waveAt + (long)waveSize > bytes.length)
                throw new IOException("BRSAR override has a truncated RWAR: " + fileId);
            wave = java.util.Arrays.copyOfRange(bytes, waveAt, waveAt + waveSize);
        }
        return new OverrideParts(main, wave);
    }

    private static void validateOverride(String member, byte[] bytes) throws IOException {
        if (bytes.length < 0x20) throw new IOException("BRSAR override is truncated: " + member);
        String magic = new String(bytes, 0, 4, StandardCharsets.US_ASCII);
        String lower = member.toLowerCase(Locale.US);
        boolean matches = lower.endsWith(".brbnk") && magic.equals("RBNK")
            || lower.endsWith(".brseq") && magic.equals("RSEQ")
            || lower.endsWith(".brwsd") && magic.equals("RWSD");
        if (!matches) throw new IOException("BRSAR override magic does not match its extension: " + member);
        int size = checkedInt(u32(bytes, 8), "BRSAR override size");
        if (size < 0x20 || size > bytes.length) throw new IOException("BRSAR override size is invalid: " + member);
    }

    private static List<Integer> referenceTable(byte[] bytes, int table, int base) throws IOException {
        requireRange(bytes, table, 4, "BRSAR reference table");
        int count = checkedInt(u32(bytes, table), "BRSAR table count");
        if (count < 0 || count > MAX_TABLE_ENTRIES) throw new IOException("BRSAR table has too many entries");
        requireRange(bytes, table + 4, Math.multiplyExact(count, 8), "BRSAR reference table");
        List<Integer> entries = new ArrayList<>(count);
        for (int index = 0; index < count; index++) entries.add(resolveRef(bytes, table + 4 + index * 8, base));
        return entries;
    }

    private static int resolveRef(byte[] bytes, int offset, int base) throws IOException {
        requireRange(bytes, offset, 8, "BRSAR data reference");
        int type = bytes[offset] & 0xff;
        int value = checkedInt(u32(bytes, offset + 4), "BRSAR data reference");
        if (value == 0) throw new IOException("BRSAR contains an empty required data reference");
        if (type == 0) return value;
        if (type == 1) return Math.addExact(base, value);
        throw new IOException("Unsupported BRSAR data reference type: " + type);
    }

    private static void copy(RandomAccessFile input, RandomAccessFile output, long offset,
                             int size, long sourceLength) throws IOException {
        if (offset < 0 || size < 0 || offset + size > sourceLength)
            throw new IOException("BRSAR group data is out of bounds");
        byte[] buffer = new byte[64 * 1024]; input.seek(offset);
        int remaining = size;
        while (remaining > 0) {
            int count = Math.min(remaining, buffer.length); input.readFully(buffer, 0, count); output.write(buffer, 0, count);
            remaining -= count;
        }
    }

    private static boolean ascii(byte[] bytes, int offset, String expected) {
        if (offset < 0 || offset + expected.length() > bytes.length) return false;
        for (int index = 0; index < expected.length(); index++)
            if (bytes[offset + index] != (byte)expected.charAt(index)) return false;
        return true;
    }
    private static void requireRange(byte[] bytes, int offset, int size, String label) throws IOException {
        if (offset < 0 || size < 0 || (long)offset + size > bytes.length) throw new IOException(label + " is truncated");
    }
    private static long u32(byte[] bytes, int offset) throws IOException {
        requireRange(bytes, offset, 4, "32-bit value");
        return (long)(bytes[offset] & 0xff) << 24 | (long)(bytes[offset + 1] & 0xff) << 16
            | (long)(bytes[offset + 2] & 0xff) << 8 | bytes[offset + 3] & 0xffL;
    }
    private static int checkedInt(long value, String label) throws IOException {
        if (value < 0 || value > Integer.MAX_VALUE) throw new IOException(label + " is too large"); return (int)value;
    }
    private static void write32(byte[] bytes, int offset, long value) throws IOException {
        if (value < 0 || value > 0xffffffffL) throw new IOException("BRSAR value exceeds 32 bits");
        requireRange(bytes, offset, 4, "BRSAR metadata write");
        bytes[offset]=(byte)(value>>>24); bytes[offset+1]=(byte)(value>>>16);
        bytes[offset+2]=(byte)(value>>>8); bytes[offset+3]=(byte)value;
    }
    private static int align32(int value) { return (value + 0x1f) & ~0x1f; }
    private static long align32(long value) { return (value + 0x1fL) & ~0x1fL; }

    private record OverrideParts(byte[] main, byte[] wave) { }
    private record Item(int entryOffset, int fileId, int offset, int size, int waveOffset, int waveSize) { }
    private record Group(int entryOffset, int originalOffset, int originalWaveOffset, List<Item> items) { }
}

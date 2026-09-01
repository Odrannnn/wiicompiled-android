package org.wiicompiled.portlab;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/** Yaz0 and U8 support for WheelWizard tagged SZS patch bundles. */
final class WheelArchive {
    private static final long U8_MAGIC = 0x55aa382dL;
    private static final int MAX_ARCHIVE_BYTES = 256 * 1024 * 1024;
    private static final int MAX_NODES = 200_000;

    static Map<String, byte[]> decode(File file) throws IOException {
        long length = file.length();
        if (length < 4 || length > MAX_ARCHIVE_BYTES)
            throw new IOException("SZS size is outside the supported range");
        return decode(Files.readAllBytes(file.toPath()));
    }

    static Map<String, byte[]> decode(byte[] encoded) throws IOException {
        byte[] bytes = decompressYaz0(encoded);
        if (bytes.length < 0x20 || uint32(bytes, 0) != U8_MAGIC)
            throw new IOException("File is not a Yaz0/U8 archive");

        int rootOffset = checkedInt(uint32(bytes, 4), "U8 root offset");
        Node root = readNode(bytes, rootOffset);
        if (!root.directory) throw new IOException("U8 root node is not a directory");
        int nodeCount = root.size;
        if (nodeCount < 1 || nodeCount > MAX_NODES)
            throw new IOException("U8 node count is outside the supported range");
        long nodeTableEnd = (long)rootOffset + (long)nodeCount * 12L;
        if (rootOffset < 0x20 || nodeTableEnd > bytes.length)
            throw new IOException("U8 node table is truncated");
        int stringsOffset = (int)nodeTableEnd;

        Map<String, byte[]> files = new LinkedHashMap<>();
        ArrayDeque<Directory> directories = new ArrayDeque<>();
        directories.push(new Directory(nodeCount, ""));
        for (int index = 1; index < nodeCount; index++) {
            while (!directories.isEmpty() && index >= directories.peek().endIndex) directories.pop();
            if (directories.isEmpty()) throw new IOException("U8 directory tree is malformed");
            Node node = readNode(bytes, rootOffset + index * 12);
            int nameAt = stringsOffset + node.nameOffset;
            if (nameAt < stringsOffset || nameAt >= bytes.length)
                throw new IOException("U8 member name is out of bounds");
            String name = readName(bytes, nameAt);
            validateSegment(name);
            String parent = directories.peek().path;
            String path = parent.isEmpty() ? name : parent + "/" + name;
            if (node.directory) {
                if (node.size <= index || node.size > directories.peek().endIndex)
                    throw new IOException("U8 directory range is malformed");
                directories.push(new Directory(node.size, path));
            } else {
                long end = (long)node.dataOffset + (long)node.size;
                if (node.dataOffset < 0 || node.size < 0 || end > bytes.length)
                    throw new IOException("U8 member data is out of bounds: " + path);
                files.put(path, Arrays.copyOfRange(bytes, node.dataOffset, (int)end));
            }
        }
        return files;
    }

    static byte[] buildYaz0(Map<String, byte[]> entries) throws IOException {
        return encodeYaz0(build(entries));
    }

    private static byte[] decompressYaz0(byte[] bytes) throws IOException {
        if (bytes.length < 4 || bytes[0] != 'Y' || bytes[1] != 'a' || bytes[2] != 'z' || bytes[3] != '0')
            return bytes;
        if (bytes.length < 0x10) throw new IOException("Yaz0 header is truncated");
        int outputSize = checkedInt(uint32(bytes, 4), "Yaz0 output size");
        if (outputSize > MAX_ARCHIVE_BYTES) throw new IOException("Yaz0 output exceeds the safety limit");
        byte[] output = new byte[outputSize];
        int source = 0x10, destination = 0, header = 0, bits = 0;
        while (destination < output.length) {
            if (bits == 0) {
                if (source >= bytes.length) throw new IOException("Yaz0 group header is truncated");
                header = bytes[source++] & 0xff;
                bits = 8;
            }
            if ((header & 0x80) != 0) {
                if (source >= bytes.length) throw new IOException("Yaz0 literal is truncated");
                output[destination++] = bytes[source++];
            } else {
                if (source + 1 >= bytes.length) throw new IOException("Yaz0 backreference is truncated");
                int first = bytes[source++] & 0xff, second = bytes[source++] & 0xff;
                int distance = ((first & 0x0f) << 8 | second) + 1;
                int count = first >>> 4;
                if (count == 0) {
                    if (source >= bytes.length) throw new IOException("Yaz0 length is truncated");
                    count = (bytes[source++] & 0xff) + 0x12;
                } else count += 2;
                if (distance > destination) throw new IOException("Yaz0 backreference is out of bounds");
                int copy = destination - distance;
                while (count-- > 0 && destination < output.length) output[destination++] = output[copy++];
            }
            header <<= 1;
            bits--;
        }
        return output;
    }

    private static byte[] build(Map<String, byte[]> entries) throws IOException {
        BuildDirectory root = new BuildDirectory("");
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            String normalized = entry.getKey().replace('\\', '/');
            if (normalized.startsWith("/") || normalized.endsWith("/"))
                throw new IOException("Invalid U8 member path: " + entry.getKey());
            String[] segments = normalized.split("/", -1);
            BuildDirectory directory = root;
            for (int index = 0; index < segments.length; index++) {
                validateSegment(segments[index]);
                boolean last = index == segments.length - 1;
                BuildEntry current = directory.children.get(segments[index]);
                if (last) {
                    if (current instanceof BuildDirectory)
                        throw new IOException("U8 member conflicts with a directory: " + normalized);
                    directory.children.put(segments[index], new BuildFile(segments[index], entry.getValue()));
                } else if (current == null) {
                    BuildDirectory child = new BuildDirectory(segments[index]);
                    directory.children.put(segments[index], child);
                    directory = child;
                } else if (current instanceof BuildDirectory) {
                    directory = (BuildDirectory)current;
                } else throw new IOException("U8 directory conflicts with a file: " + normalized);
            }
        }

        List<BuildNode> nodes = new ArrayList<>();
        ByteArrayOutputStream strings = new ByteArrayOutputStream();
        strings.write(0);
        emit(root, 0, nodes, strings);
        if (nodes.size() > MAX_NODES) throw new IOException("U8 archive has too many nodes");
        int tableSize = Math.multiplyExact(nodes.size(), 12);
        int combinedSize = Math.addExact(tableSize, strings.size());
        int writeOffset = align32(Math.addExact(0x20, combinedSize));
        for (BuildNode node : nodes) if (!node.directory) {
            node.dataOffset = writeOffset;
            writeOffset = Math.addExact(writeOffset, align32(node.size));
            if (writeOffset > MAX_ARCHIVE_BYTES) throw new IOException("Built U8 archive exceeds the safety limit");
        }
        byte[] output = new byte[writeOffset];
        write32(output, 0, U8_MAGIC); write32(output, 4, 0x20);
        write32(output, 8, combinedSize); write32(output, 12, align32(0x20 + combinedSize));
        int nodeAt = 0x20;
        for (BuildNode node : nodes) {
            output[nodeAt] = (byte)(node.directory ? 1 : 0);
            output[nodeAt + 1] = (byte)(node.nameOffset >>> 16);
            output[nodeAt + 2] = (byte)(node.nameOffset >>> 8);
            output[nodeAt + 3] = (byte)node.nameOffset;
            write32(output, nodeAt + 4, node.dataOffset); write32(output, nodeAt + 8, node.size);
            nodeAt += 12;
        }
        byte[] stringBytes = strings.toByteArray();
        System.arraycopy(stringBytes, 0, output, 0x20 + tableSize, stringBytes.length);
        for (BuildNode node : nodes) if (!node.directory && node.bytes != null)
            System.arraycopy(node.bytes, 0, output, node.dataOffset, node.bytes.length);
        return output;
    }

    private static int emit(BuildEntry entry, int parent, List<BuildNode> nodes,
                            ByteArrayOutputStream strings) throws IOException {
        int index = nodes.size();
        int nameOffset = 0;
        if (!entry.name.isEmpty()) {
            nameOffset = strings.size();
            byte[] name = entry.name.getBytes(StandardCharsets.UTF_8);
            if (nameOffset + name.length >= 0x1000000) throw new IOException("U8 string table is too large");
            strings.write(name); strings.write(0);
        }
        BuildNode node;
        if (entry instanceof BuildDirectory) node = new BuildNode(true, nameOffset, parent, 0, null);
        else {
            byte[] bytes = ((BuildFile)entry).bytes;
            node = new BuildNode(false, nameOffset, 0, bytes.length, bytes);
        }
        nodes.add(node);
        if (entry instanceof BuildDirectory) {
            for (BuildEntry child : ((BuildDirectory)entry).children.values()) emit(child, index, nodes, strings);
            node.size = nodes.size();
        }
        return index;
    }

    private static byte[] encodeYaz0(byte[] input) {
        ByteSink output = new ByteSink(input.length + 16);
        output.write(new byte[]{'Y','a','z','0',(byte)(input.length >>> 24),(byte)(input.length >>> 16),
            (byte)(input.length >>> 8),(byte)input.length,0,0,0,0,0,0,0,0});
        int[] head = new int[1 << 16], previous = new int[input.length];
        Arrays.fill(head, -1); Arrays.fill(previous, -1);
        int source = 0;
        while (source < input.length) {
            int codeAt = output.size; output.write(0); int code = 0;
            for (int bit = 0; bit < 8 && source < input.length; bit++) {
                int hash = hashAt(input, source), bestLength = 0, bestDistance = 0;
                int candidate = hash < 0 ? -1 : head[hash];
                int maxLength = Math.min(0x111, input.length - source);
                for (int searched = 0; candidate >= 0 && searched < 256; searched++) {
                    int distance = source - candidate;
                    if (distance > 0x1000) break;
                    int length = 0;
                    while (length < maxLength && input[candidate + length] == input[source + length]) length++;
                    if (length > bestLength && length >= 3) {
                        bestLength = length; bestDistance = distance;
                        if (length == maxLength) break;
                    }
                    candidate = previous[candidate];
                }
                if (bestLength >= 3) {
                    int distance = bestDistance - 1;
                    if (bestLength >= 0x12) {
                        output.write((distance >>> 8) & 0x0f); output.write(distance); output.write(bestLength - 0x12);
                    } else {
                        output.write((bestLength - 2) << 4 | (distance >>> 8) & 0x0f); output.write(distance);
                    }
                    for (int count = 0; count < bestLength; count++) insert(input, source + count, head, previous);
                    source += bestLength;
                } else {
                    code |= 0x80 >>> bit; output.write(input[source]); insert(input, source, head, previous); source++;
                }
            }
            output.bytes[codeAt] = (byte)code;
        }
        return output.toByteArray();
    }

    private static void insert(byte[] input, int offset, int[] head, int[] previous) {
        int hash = hashAt(input, offset); if (hash < 0) return;
        previous[offset] = head[hash]; head[hash] = offset;
    }
    private static int hashAt(byte[] input, int offset) {
        return offset + 2 < input.length
            ? (((input[offset] & 0xff) << 8) ^ ((input[offset + 1] & 0xff) << 4) ^ (input[offset + 2] & 0xff)) & 0xffff
            : -1;
    }
    private static void validateSegment(String value) throws IOException {
        if (value.isEmpty() || value.equals(".") || value.equals("..") || value.indexOf('/') >= 0 || value.indexOf('\\') >= 0)
            throw new IOException("Unsafe U8 member name: " + value);
    }
    private static String readName(byte[] bytes, int offset) throws IOException {
        int end = offset;
        while (end < bytes.length && bytes[end] != 0) end++;
        if (end == bytes.length) throw new IOException("U8 member name is unterminated");
        return new String(bytes, offset, end - offset, StandardCharsets.UTF_8);
    }
    private static Node readNode(byte[] bytes, int offset) throws IOException {
        if (offset < 0 || offset + 12 > bytes.length) throw new IOException("U8 node is truncated");
        int name = (bytes[offset + 1] & 0xff) << 16 | (bytes[offset + 2] & 0xff) << 8 | bytes[offset + 3] & 0xff;
        return new Node(bytes[offset] != 0, name, checkedInt(uint32(bytes, offset + 4), "U8 node offset"),
            checkedInt(uint32(bytes, offset + 8), "U8 node size"));
    }
    private static long uint32(byte[] bytes, int offset) throws IOException {
        if (offset < 0 || offset + 4 > bytes.length) throw new IOException("32-bit value is truncated");
        return (long)(bytes[offset] & 0xff) << 24 | (long)(bytes[offset + 1] & 0xff) << 16
            | (long)(bytes[offset + 2] & 0xff) << 8 | bytes[offset + 3] & 0xffL;
    }
    private static int checkedInt(long value, String label) throws IOException {
        if (value > Integer.MAX_VALUE) throw new IOException(label + " is too large"); return (int)value;
    }
    private static void write32(byte[] bytes, int offset, long value) {
        bytes[offset]=(byte)(value>>>24); bytes[offset+1]=(byte)(value>>>16); bytes[offset+2]=(byte)(value>>>8); bytes[offset+3]=(byte)value;
    }
    private static int align32(int value) { return (value + 0x1f) & ~0x1f; }

    private record Node(boolean directory, int nameOffset, int dataOffset, int size) { }
    private record Directory(int endIndex, String path) { }
    private abstract static class BuildEntry { final String name; BuildEntry(String name) { this.name = name; } }
    private static final class BuildDirectory extends BuildEntry {
        final SortedMap<String, BuildEntry> children = new TreeMap<>(); BuildDirectory(String name) { super(name); }
    }
    private static final class BuildFile extends BuildEntry {
        final byte[] bytes; BuildFile(String name, byte[] bytes) { super(name); this.bytes = bytes; }
    }
    private static final class BuildNode {
        final boolean directory; final int nameOffset; int dataOffset, size; final byte[] bytes;
        BuildNode(boolean directory, int nameOffset, int dataOffset, int size, byte[] bytes) {
            this.directory=directory; this.nameOffset=nameOffset; this.dataOffset=dataOffset; this.size=size; this.bytes=bytes;
        }
    }
    private static final class ByteSink {
        byte[] bytes; int size;
        ByteSink(int capacity) { bytes = new byte[Math.max(capacity, 32)]; }
        void write(int value) { ensure(1); bytes[size++] = (byte)value; }
        void write(byte[] value) { ensure(value.length); System.arraycopy(value, 0, bytes, size, value.length); size += value.length; }
        byte[] toByteArray() { return Arrays.copyOf(bytes, size); }
        private void ensure(int count) {
            int required = size + count;
            if (required > bytes.length) bytes = Arrays.copyOf(bytes, Math.max(required, bytes.length + (bytes.length >>> 1)));
        }
    }
}

package org.wiicompiled.portlab;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Bounded header inspection, not proof of an unmodified disc. */
public final class DiscHeader {
    public static String inspect(InputStream input) throws IOException {
        byte[] header = new byte[32];
        int count = 0;
        while (count < header.length) {
            int read = input.read(header, count, header.length - count);
            if (read < 0) break;
            if (read == 0) {
                int value = input.read();
                if (value < 0) break;
                header[count++] = (byte)value;
            } else count += read;
        }
        if (count < 4) return "Rejected: file is too short to contain a disc header.";
        String magic = new String(header, 0, 4, StandardCharsets.US_ASCII);
        if (magic.equals("WBFS") || magic.equals("RVZ\1") || magic.equals("WIA\1")
                || magic.equals("CISO") || (header[0] == 1 && header[1] == (byte)0xc0
                && header[2] == 0x0b && header[3] == (byte)0xb1)) {
            return "Container selected. WBFS/RVZ/WIA/CISO/GCZ extraction is not implemented on Android; "
                + "disc region and revision are not verified.";
        }
        if (count < 32) return "Rejected: incomplete raw disc header.";
        if ((header[24] & 255) != 0x5d || (header[25] & 255) != 0x1c
                || (header[26] & 255) != 0x9e || (header[27] & 255) != 0xa3)
            return "Rejected: no Wii raw-disc magic. Use your own unmodified disc dump.";
        String id = new String(header, 0, 6, StandardCharsets.US_ASCII);
        if (!id.equals("RMCP01")) return "Rejected: expected PAL RMCP01, found " + id + ".";
        if (header[6] != 0 || header[7] != 0)
            return "Rejected: expected disc 0, revision 0.";
        return "PAL RMCP01 revision 0 header found. Full disc integrity is NOT verified. "
            + "Extraction, translation and installation are not implemented yet.";
    }
    private DiscHeader() {}
}


package org.wiicompiled.portlab;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class DiscHeaderTest {
    private byte[] raw() {
        byte[] bytes = new byte[32];
        System.arraycopy("RMCP01".getBytes(StandardCharsets.US_ASCII), 0, bytes, 0, 6);
        bytes[24] = 0x5d; bytes[25] = 0x1c; bytes[26] = (byte)0x9e; bytes[27] = (byte)0xa3;
        return bytes;
    }
    private String inspect(byte[] bytes) throws Exception { return DiscHeader.inspect(new ByteArrayInputStream(bytes)); }
    @Test public void palHeaderDoesNotClaimFullVerification() throws Exception {
        String result = inspect(raw());
        assertTrue(result.contains("PAL RMCP01")); assertTrue(result.contains("NOT verified"));
    }
    @Test public void rejectsOtherRegions() throws Exception {
        byte[] bytes = raw(); bytes[3] = 'E';
        assertTrue(inspect(bytes).contains("Rejected: expected PAL"));
    }
    @Test public void rejectsRevisionAndDiscNumber() throws Exception {
        byte[] bytes = raw(); bytes[7] = 1;
        assertTrue(inspect(bytes).contains("Rejected: expected disc"));
        bytes[7] = 0; bytes[6] = 1;
        assertTrue(inspect(bytes).contains("Rejected: expected disc"));
    }
    @Test public void rejectsFalseGameIdWithoutWiiMagic() throws Exception {
        byte[] bytes = raw(); bytes[24] = 0;
        assertTrue(inspect(bytes).contains("Rejected: no Wii"));
    }
    @Test public void rejectsTruncatedInput() throws Exception {
        assertTrue(inspect(new byte[0]).contains("too short"));
        assertTrue(inspect(new byte[16]).contains("incomplete"));
    }
    @Test public void containersRemainUnverified() throws Exception {
        for (String magic : new String[]{"WBFS", "RVZ\1", "WIA\1", "CISO"}) {
            assertTrue(inspect(magic.getBytes(StandardCharsets.US_ASCII)).contains("not verified"));
        }
    }
    @Test public void neverReadsBeyondHeader() throws Exception {
        byte[] bytes = raw();
        InputStream stream = new InputStream() {
            int position;
            @Override public int read() {
                if (position == 32) throw new AssertionError("Read beyond bounded header");
                return bytes[position++] & 255;
            }
        };
        assertTrue(DiscHeader.inspect(stream).contains("PAL RMCP01"));
    }
    @Test public void handlesShortAndZeroLengthReads() throws Exception {
        byte[] bytes = raw();
        InputStream stream = new ByteArrayInputStream(bytes) {
            boolean zero = true;
            @Override public synchronized int read(byte[] target, int offset, int length) {
                zero = !zero;
                return zero ? 0 : super.read(target, offset, Math.min(3, length));
            }
        };
        assertTrue(DiscHeader.inspect(stream).contains("PAL RMCP01"));
    }
}


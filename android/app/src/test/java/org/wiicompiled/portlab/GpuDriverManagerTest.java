package org.wiicompiled.portlab;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class GpuDriverManagerTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private File driver(File parent, int elfMachine) throws Exception {
        File file = new File(parent, "libvulkan_freedreno.so");
        byte[] header = new byte[20];
        header[0] = 0x7f; header[1] = 'E'; header[2] = 'L'; header[3] = 'F';
        header[4] = 2; header[5] = 1;
        header[18] = (byte)(elfMachine & 0xff); header[19] = (byte)(elfMachine >>> 8);
        try (FileOutputStream output = new FileOutputStream(file)) { output.write(header); }
        return file;
    }

    @Test public void acceptsArm64DriverWithoutAbiFolder() throws Exception {
        File root = temporary.newFolder("turnip-package");
        File expected = driver(root, 183);
        assertEquals(expected.getCanonicalFile(), GpuDriverManager.findSupportedDriver(root).getCanonicalFile());
    }

    @Test public void acceptsArm64DriverInArbitraryNestedFolder() throws Exception {
        File root = temporary.newFolder("turnip-nested");
        File nested = new File(root, "vulkan/release"); assertTrue(nested.mkdirs());
        driver(nested, 183);
        assertNotNull(GpuDriverManager.findSupportedDriver(root));
    }

    @Test public void rejectsWrongArchitectureByElfHeader() throws Exception {
        File root = temporary.newFolder("wrong-architecture");
        driver(root, 62); // EM_X86_64
        try {
            GpuDriverManager.findSupportedDriver(root);
            fail("Expected wrong architecture to be rejected");
        } catch (IOException expected) { assertTrue(expected.getMessage().contains("not an ARM64 driver")); }
    }

    @Test public void rejectsZipTraversalNames() throws Exception {
        for (String name : new String[]{"../driver.so", "folder/../driver.so", "/driver.so", "C:/driver.so"}) {
            try { GpuDriverManager.safeEntryName(name); fail("Accepted unsafe path " + name); }
            catch (IOException expected) { }
        }
        assertEquals("vulkan/release/libvulkan_freedreno.so",
            GpuDriverManager.safeEntryName("vulkan/release/libvulkan_freedreno.so"));
    }
}

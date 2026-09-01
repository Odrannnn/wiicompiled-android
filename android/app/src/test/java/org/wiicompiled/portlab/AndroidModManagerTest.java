package org.wiicompiled.portlab;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class AndroidModManagerTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private static void write(File file, int value) throws Exception {
        File parent = file.getParentFile(); assertTrue(parent.mkdirs() || parent.isDirectory());
        try (FileOutputStream output = new FileOutputStream(file)) { output.write(value); }
    }

    @Test public void mapsFlatWheelWizardFileByUniqueDiscName() throws Exception {
        File disc = temporary.newFolder("disc");
        write(new File(disc, "Race/Kart/ma_kart-mr.szs"), 1);
        File extracted = temporary.newFolder("package");
        write(new File(extracted, "ma_kart-mr.szs"), 2);
        File overlay = temporary.newFolder("overlay");
        int[] counts = AndroidModManager.buildDiscOverlay(extracted, overlay, disc);
        assertArrayEquals(new int[]{1, 0}, counts);
        assertArrayEquals(new byte[]{2}, Files.readAllBytes(new File(overlay, "Race/Kart/ma_kart-mr.szs").toPath()));
    }

    @Test public void preservesFilesPrefixedDiscLayout() throws Exception {
        File disc = temporary.newFolder("disc-layout");
        write(new File(disc, "Scene/UI/Channel.szs"), 1);
        File extracted = temporary.newFolder("package-layout");
        write(new File(extracted, "DATA/files/Scene/UI/Channel.szs"), 3);
        File overlay = temporary.newFolder("overlay-layout");
        assertArrayEquals(new int[]{1, 0}, AndroidModManager.buildDiscOverlay(extracted, overlay, disc));
        assertTrue(new File(overlay, "Scene/UI/Channel.szs").isFile());
    }

    @Test public void skipsAmbiguousOrUnknownFiles() throws Exception {
        File disc = temporary.newFolder("disc-ambiguous");
        write(new File(disc, "A/shared.bin"), 1); write(new File(disc, "B/shared.bin"), 1);
        File extracted = temporary.newFolder("package-ambiguous");
        write(new File(extracted, "shared.bin"), 2); write(new File(extracted, "readme.txt"), 2);
        File overlay = temporary.newFolder("overlay-ambiguous");
        assertArrayEquals(new int[]{0, 2}, AndroidModManager.buildDiscOverlay(extracted, overlay, disc));
    }

    @Test public void rejectsZipTraversalNames() throws Exception {
        for (String name : new String[]{"../mod.szs", "folder/../mod.szs", "/mod.szs", "C:/mod.szs"}) {
            try { AndroidModManager.safeEntryName(name); fail("Accepted unsafe path " + name); }
            catch (IOException expected) { }
        }
    }
}

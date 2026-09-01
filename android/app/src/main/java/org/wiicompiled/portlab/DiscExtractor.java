package org.wiicompiled.portlab;

import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.Locale;

/** Extracts and verifies a user-selected PAL disc without copying the source image. */
final class DiscExtractor {
    private static final String MAIN_DOL_SHA256 =
        "80d18895b39c63bd80f457398bfcbb91b7d16ac116a41a88967e954080155b05";
    private static final String STATIC_R_SHA256 =
        "16d9d146112541fefea701ecb5bc1a496f9d50e4a752fbb5b6778e7c6399f67d";

    interface Progress {
        boolean update(String stage, long completedBytes, long totalBytes);
    }

    static { System.loadLibrary("wiicompiled_probe"); }

    static synchronized String extract(Context context, Uri source, Progress progress) throws IOException {
        File external = context.getExternalFilesDir(null);
        if (external == null) throw new IOException("External app storage unavailable");
        File game = new File(external, "game");
        if (!game.isDirectory() && !game.mkdirs()) throw new IOException("Cannot create private game directory");
        File destination = new File(game, "disc");
        File staging = new File(game, "disc.importing");
        File backup = new File(game, "disc.previous");
        recoverInterruptedActivation(destination, staging, backup);
        deleteRecursively(staging.toPath());
        if (!staging.mkdirs()) throw new IOException("Cannot create extraction staging directory");
        String nativeSummary;
        try (ParcelFileDescriptor descriptor = context.getContentResolver().openFileDescriptor(source, "r")) {
            if (descriptor == null) throw new IOException("Document provider did not open the disc image");
            nativeSummary = nativeExtract(descriptor.getFd(), descriptor.getStatSize(),
                staging.getAbsolutePath(), progress);
            verify(staging, progress);
            activate(destination, staging, backup, progress);
            try { writeReceipt(game, source, nativeSummary); }
            catch (IOException receiptError) {
                android.util.Log.w("WiiCompiled", "Could not write disc extraction receipt", receiptError);
            }
            return nativeSummary + "\nVerified the pinned main.dol and StaticR.rel, then activated the extraction.";
        } catch (IOException | RuntimeException error) {
            try { deleteRecursively(staging.toPath()); }
            catch (IOException cleanup) { error.addSuppressed(cleanup); }
            throw error;
        }
    }

    static String installedStatus(Context context) {
        File external = context.getExternalFilesDir(null);
        if (external == null) return "External app storage unavailable.";
        File disc = new File(external, "game/disc");
        File main = new File(disc, "sys/main.dol");
        File rel = new File(disc, "files/rel/StaticR.rel");
        return main.isFile() && rel.isFile()
            ? "A verified PAL RMCP01 extraction is installed in app-private storage."
            : "No extracted PAL disc is installed.";
    }

    private static void verify(File staging, Progress progress) throws IOException {
        if (progress != null && !progress.update("Checking pinned game binaries", 0, 0))
            throw new IOException("Extraction cancelled");
        verifyHash(new File(staging, "sys/main.dol"), MAIN_DOL_SHA256, "main.dol");
        verifyHash(new File(staging, "files/rel/StaticR.rel"), STATIC_R_SHA256, "StaticR.rel");
    }

    private static void verifyHash(File file, String expected, String label) throws IOException {
        if (!file.isFile()) throw new IOException("Extracted disc is missing " + label);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[1024 * 1024];
            try (FileInputStream input = new FileInputStream(file)) {
                int read;
                while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
            }
            StringBuilder actual = new StringBuilder(64);
            for (byte value : digest.digest()) actual.append(String.format(Locale.US, "%02x", value & 255));
            if (!expected.equals(actual.toString()))
                throw new IOException(label + " does not match the supported clean PAL revision");
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static void activate(File destination, File staging, File backup, Progress progress) throws IOException {
        if (progress != null && !progress.update("Activating extracted game", 0, 0))
            throw new IOException("Extraction cancelled");
        deleteRecursively(backup.toPath());
        boolean movedExisting = false;
        if (destination.exists()) {
            move(destination.toPath(), backup.toPath());
            movedExisting = true;
        }
        try {
            move(staging.toPath(), destination.toPath());
        } catch (IOException activationError) {
            if (movedExisting && !destination.exists() && backup.exists()) {
                try { move(backup.toPath(), destination.toPath()); }
                catch (IOException restoreError) { activationError.addSuppressed(restoreError); }
            }
            throw activationError;
        }
        try { deleteRecursively(backup.toPath()); }
        catch (IOException cleanupError) {
            android.util.Log.w("WiiCompiled", "Activated disc but could not remove previous extraction", cleanupError);
        }
    }

    private static void recoverInterruptedActivation(File destination, File staging, File backup) throws IOException {
        if (!destination.exists() && backup.exists()) move(backup.toPath(), destination.toPath());
        deleteRecursively(staging.toPath());
        if (destination.exists()) deleteRecursively(backup.toPath());
    }

    private static void move(Path source, Path destination) throws IOException {
        try { Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE); }
        catch (java.nio.file.AtomicMoveNotSupportedException unsupported) { Files.move(source, destination); }
    }

    private static void writeReceipt(File game, Uri source, String summary) throws IOException {
        String leaf = source.getLastPathSegment();
        if (leaf == null) leaf = "selected document";
        leaf = leaf.replaceAll("[\\r\\n\\p{Cntrl}]", "");
        String receipt = "source=" + leaf + "\ncompletedUtc=" + java.time.Instant.now() + "\n" + summary + "\n";
        try (FileOutputStream output = new FileOutputStream(new File(game, "disc-extraction.txt"))) {
            output.write(receipt.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) return;
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path directory, IOException error) throws IOException {
                if (error != null) throw error;
                Files.delete(directory); return FileVisitResult.CONTINUE;
            }
        });
    }

    private static native String nativeExtract(int descriptor, long length, String destination, Progress progress)
        throws IOException;

    private DiscExtractor() {}
}

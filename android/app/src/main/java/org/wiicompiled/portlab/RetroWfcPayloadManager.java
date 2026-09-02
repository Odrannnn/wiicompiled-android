package org.wiicompiled.portlab;

import android.content.Context;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.RSAPublicKeySpec;

/** Downloads and authenticates the production Retro-WFC payload used during translation. */
final class RetroWfcPayloadManager {
    private static final String ENDPOINT = "http://play.rwfc.net/payload?g=RMCPD00";
    private static final int MAX_BYTES = 16 * 1024 * 1024;
    private static final int SIGNED_OFFSET = 0x110;
    private static final int SIGNATURE_OFFSET = 0x10;
    private static final int SIGNATURE_BYTES = 0x100;
    private static final String MODULUS =
        "e6e6ce416f350422cbe26c36a67eba613dddcd27d79afd077dcc593e5319eaa6" +
        "080293400033876d3dbdfda12c15f46ac8e4f5b40c56e7b5f67e91647d618cb9" +
        "99c041581b86d103bd7723fceac03ad3ad5134bf611cd47dc527002596821e94" +
        "1c9470938fea07238a84767323e4a610bd996465e59d04dae4febd915c96fc07" +
        "39e4e818300829d78f3f2275e1f3fbd2507f1bde74f24a5285e61007b959a583" +
        "b4820d75eca76680866efe5d79590b82c3577b796155899530e305b94b4ceef4" +
        "428644b719df3d8540c9588f5bb02d83d3938255d1a1e073d3408163ff93a615" +
        "a2106a03923a397aad6a29ebb43031ed06de1575c8ee2b54678fa059e025f455";

    static File download(Context context) throws IOException {
        File directory = new File(context.getFilesDir(), "android-builder/retro-wfc/binary");
        if (!directory.mkdirs() && !directory.isDirectory())
            throw new IOException("Cannot create the private Retro-WFC payload directory");
        File destination = new File(directory, "payload.RMCPD00.bin");
        IOException failure = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                byte[] payload = downloadAttempt();
                validate(payload);
                File temporary = new File(directory, destination.getName() + ".tmp");
                try (FileOutputStream output = new FileOutputStream(temporary, false)) {
                    output.write(payload);
                    output.getFD().sync();
                }
                try {
                    Files.move(temporary.toPath(), destination.toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException error) {
                    Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
                return destination;
            } catch (SecurityException error) {
                throw new IOException(error.getMessage(), error);
            } catch (IOException error) {
                failure = error;
                android.util.Log.w("WiiCompiled", "Retro-WFC payload download attempt " + (attempt + 1) + " failed", error);
                if (attempt == 0) {
                    try {
                        Thread.sleep(1_000);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Retro-WFC payload download was interrupted", interrupted);
                    }
                }
            }
        }
        if (destination.isFile()) {
            try {
                byte[] cached = Files.readAllBytes(destination.toPath());
                validate(cached);
                android.util.Log.w("WiiCompiled", "Retro-WFC endpoint unavailable; using the last signed payload", failure);
                return destination;
            } catch (SecurityException | IOException cachedError) {
                if (failure != null) cachedError.addSuppressed(failure);
                throw new IOException("The Retro-WFC endpoint is unavailable and its cached payload is invalid", cachedError);
            }
        }
        throw new IOException("Could not download the signed Retro-WFC payload after two attempts",
            failure == null ? new IOException("Unknown download failure") : failure);
    }

    private static byte[] downloadAttempt() throws IOException {
        HttpURLConnection connection = (HttpURLConnection)new URL(ENDPOINT).openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(30_000);
        connection.setReadTimeout(30_000);
        connection.setRequestProperty("Accept", "application/octet-stream");
        connection.setRequestProperty("User-Agent", "WiiCompiled-Android/0.3");
        try {
            int status = connection.getResponseCode();
            if (status >= 300 && status < 400)
                throw new IOException("The fixed Retro-WFC endpoint redirected; refusing another host");
            if (status < 200 || status >= 300)
                throw new IOException("Retro-WFC endpoint returned HTTP " + status);
            int declared = connection.getContentLength();
            if (declared > MAX_BYTES) throw new IOException("Retro-WFC payload is unexpectedly large");
            try (InputStream input = new BufferedInputStream(connection.getInputStream());
                 ByteArrayOutputStream output = new ByteArrayOutputStream(declared > 0 ? declared : 64 * 1024)) {
                byte[] buffer = new byte[64 * 1024];
                int total = 0;
                for (int read; (read = input.read(buffer)) != -1;) {
                    total += read;
                    if (total > MAX_BYTES) throw new IOException("Retro-WFC payload is unexpectedly large");
                    output.write(buffer, 0, read);
                }
                return output.toByteArray();
            }
        } finally {
            connection.disconnect();
        }
    }

    private static void validate(byte[] image) throws IOException {
        if (image.length < 0x130 || image.length > MAX_BYTES)
            throw new IOException("Retro-WFC payload has an invalid size");
        byte[] magic = "WWFC/Payload".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        for (int i = 0; i < magic.length; i++)
            if (image[i] != magic[i]) throw new IOException("Retro-WFC payload has an invalid header");
        long declared = ((long)(image[0x0c] & 0xff) << 24) | ((long)(image[0x0d] & 0xff) << 16)
            | ((long)(image[0x0e] & 0xff) << 8) | (image[0x0f] & 0xffL);
        if (declared != image.length) throw new IOException("Retro-WFC payload size header is invalid");
        try {
            RSAPublicKeySpec keySpec = new RSAPublicKeySpec(new BigInteger(MODULUS, 16), BigInteger.valueOf(65537));
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(KeyFactory.getInstance("RSA").generatePublic(keySpec));
            verifier.update(image, SIGNED_OFFSET, image.length - SIGNED_OFFSET);
            if (!verifier.verify(image, SIGNATURE_OFFSET, SIGNATURE_BYTES))
                throw new SecurityException("Retro-WFC payload signature does not match the pinned production key");
        } catch (SecurityException error) {
            throw error;
        } catch (Exception error) {
            throw new IOException("Cannot verify the Retro-WFC payload signature", error);
        }
    }

    private RetroWfcPayloadManager() {}
}

package org.wiicompiled.portlab;

import android.app.Activity;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Bundle;
import android.widget.TextView;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** Debug-only, token-gated ADB stream receiver. Included only in local game APKs. */
public final class ImportActivity extends Activity {
    private TextView status;
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        status = new TextView(this); status.setTextSize(18); status.setPadding(40, 80, 40, 40);
        status.setText("Waiting for local ADB data stream…"); setContentView(status);
        String token = getIntent().getStringExtra("token");
        if (token == null || token.length() < 32) { fail("Missing deployment token"); return; }
        new Thread(() -> receive(token), "Game data importer").start();
    }
    private void receive(String expectedToken) {
        try (LocalServerSocket server = new LocalServerSocket("wiicompiled_import");
             LocalSocket socket = server.accept();
             DataInputStream input = new DataInputStream(new BufferedInputStream(socket.getInputStream(), 1024 * 1024))) {
            int tokenLength = input.readInt();
            if (tokenLength < 32 || tokenLength > 256) throw new SecurityException("Invalid token length");
            byte[] token = input.readNBytes(tokenLength);
            if (!expectedToken.equals(new String(token, StandardCharsets.UTF_8))) throw new SecurityException("Deployment token mismatch");
            File root = new File(getExternalFilesDir(null), "game").getCanonicalFile();
            if (!root.isDirectory() && !root.mkdirs()) throw new java.io.IOException("Cannot create game folder");
            byte[] buffer = new byte[1024 * 1024]; long total = 0; int files = 0;
            for (;;) {
                int nameLength = input.readInt(); long length = input.readLong();
                if (nameLength == 0 && length == 0) break;
                if (nameLength <= 0 || nameLength > 4096 || length < 0 || length > 8_000_000_000L)
                    throw new java.io.IOException("Invalid stream entry");
                String relative = new String(input.readNBytes(nameLength), StandardCharsets.UTF_8);
                File target = new File(root, relative).getCanonicalFile();
                if (!target.toPath().startsWith(root.toPath())) throw new SecurityException("Path traversal rejected");
                File parent = target.getParentFile();
                if (!parent.isDirectory() && !parent.mkdirs()) throw new java.io.IOException("Cannot create " + parent);
                try (FileOutputStream output = new FileOutputStream(target)) {
                    long remaining = length;
                    while (remaining > 0) {
                        int read = input.read(buffer, 0, (int)Math.min(buffer.length, remaining));
                        if (read < 0) throw new java.io.EOFException("Stream ended inside " + relative);
                        output.write(buffer, 0, read); remaining -= read; total += read;
                    }
                }
                files++;
                if ((files & 63) == 0) {
                    final String progress = "Imported " + files + " files · " + (total / 1048576) + " MiB";
                    runOnUiThread(() -> status.setText(progress));
                }
            }
            final String completed = "Import complete: " + files + " files · " + (total / 1048576) + " MiB";
            runOnUiThread(() -> {
                status.setText(completed); setResult(RESULT_OK);
                startActivity(new android.content.Intent(this, GameActivity.class));
                finish();
            });
        } catch (Exception error) { fail("Import failed: " + error.getMessage()); }
    }
    private void fail(String text) { runOnUiThread(() -> { status.setText(text); setResult(RESULT_CANCELED); }); }
}


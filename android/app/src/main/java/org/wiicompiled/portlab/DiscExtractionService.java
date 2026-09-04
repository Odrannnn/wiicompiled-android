package org.wiicompiled.portlab;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.IBinder;
import android.provider.OpenableColumns;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns long disc extraction independently of activity and display lifecycles. */
public final class DiscExtractionService extends Service {
    static final String ACTION_START = "org.wiicompiled.portlab.DISC_EXTRACT_START";
    static final String ACTION_CANCEL = "org.wiicompiled.portlab.DISC_EXTRACT_CANCEL";
    static final String ACTION_UPDATE = "org.wiicompiled.portlab.DISC_EXTRACT_UPDATE";
    static final String EXTRA_STATUS = "status", EXTRA_PERCENT = "percent", EXTRA_RUNNING = "running";
    private static final String CHANNEL = "disc-extraction";
    private static final String PREFERENCES = "disc-extraction";
    private static final String STATUS_KEY = "status", URI_KEY = "source-uri";
    private static final int NOTIFICATION_ID = 42;
    private static final AtomicBoolean CANCELLED = new AtomicBoolean();
    private static volatile boolean running;
    private static volatile String status = "No extracted PAL disc is installed.";
    private static volatile int percent;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    static boolean isRunning() { return running; }
    static String currentStatus(Context context) {
        return running ? status : context.getSharedPreferences(PREFERENCES, MODE_PRIVATE)
            .getString(STATUS_KEY, DiscExtractor.installedStatus(context));
    }
    static int currentPercent() { return percent; }

    @Override public void onCreate() {
        super.onCreate();
        NotificationChannel channel = new NotificationChannel(
            CHANNEL, "Disc extraction", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Progress while WiiCompiled verifies and extracts your selected disc");
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_CANCEL.equals(action)) {
            if (running) {
                CANCELLED.set(true);
                publish("Cancelling disc extraction…", percent, true);
            } else stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(action) || running || intent.getData() == null) return START_NOT_STICKY;
        if (BuilderService.isRunning() || RetroRewindInstallService.isRunning()) {
            publish("Wait for the private runtime build or Retro Rewind installation to finish before extracting a disc.", 0, false);
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        Uri source = intent.getData();
        int grantFlags = intent.getFlags() &
            (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        CANCELLED.set(false); running = true;
        String selectedName = displayName(source);
        publish(selectedName + "\nOpening selected disc image…", 1, true);
        startForeground(NOTIFICATION_ID, notification(status, percent));
        worker.execute(() -> extract(source, selectedName, grantFlags));
        return START_NOT_STICKY;
    }

    private void extract(Uri source, String selectedName, int grantFlags) {
        boolean persistent = false;
        try {
            if ((grantFlags & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0) {
                try {
                    getContentResolver().takePersistableUriPermission(
                        source, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    persistent = true;
                } catch (SecurityException ignored) {
                    // The service-specific temporary read grant remains valid for this run.
                }
            }
            final boolean retained = persistent;
            String summary = DiscExtractor.extract(this, source, (stage, done, total) -> {
                if (CANCELLED.get() || Thread.currentThread().isInterrupted()) return false;
                int value = stagePercent(stage, done, total);
                String message = selectedName + "\n" + stage;
                if (total > 0) message += ": " + Math.min(done * 100 / total, 100) + "% · "
                    + (done / 1048576) + " / " + (total / 1048576) + " MiB";
                publish(message, value, true);
                return true;
            });
            String finished = selectedName + "\n" + summary;
            if (!retained) finished += "\nThe provider did not grant persistent source access; the extracted files are unaffected.";
            rememberSuccessfulSource(source, retained);
            saveStatus(finished);
            publish(finished, 100, false);
        } catch (Exception error) {
            String message = CANCELLED.get() ? "Disc extraction cancelled. The previous extraction was left unchanged."
                : "Disc extraction failed: " + safeMessage(error) + "\nThe previous extraction was left unchanged.";
            saveStatus(message);
            publish(message, 0, false);
        } finally {
            running = false;
            stopForeground(STOP_FOREGROUND_DETACH);
            stopSelf();
        }
    }

    private int stagePercent(String stage, long done, long total) {
        if (stage != null && stage.startsWith("Checking")) return 94;
        if (stage != null && stage.startsWith("Activating")) return 98;
        if (total > 0) return 3 + (int)Math.min(done * 90 / total, 90);
        return Math.max(2, percent);
    }

    private void rememberSuccessfulSource(Uri source, boolean persistent) {
        SharedPreferences preferences = getSharedPreferences(PREFERENCES, MODE_PRIVATE);
        String old = preferences.getString(URI_KEY, null);
        preferences.edit().putString(URI_KEY, persistent ? source.toString() : "").apply();
        if (old != null && !old.isEmpty() && !old.equals(source.toString())) {
            try { getContentResolver().releasePersistableUriPermission(
                Uri.parse(old), Intent.FLAG_GRANT_READ_URI_PERMISSION); }
            catch (SecurityException ignored) { /* The provider may already have revoked it. */ }
        }
    }

    private String displayName(Uri source) {
        try (Cursor cursor = getContentResolver().query(
                source, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String value = cursor.getString(0);
                if (value != null && !value.isBlank()) return value;
            }
        } catch (RuntimeException ignored) { }
        String leaf = source.getLastPathSegment();
        return leaf == null || leaf.isBlank() ? "Selected document" : leaf;
    }

    private void saveStatus(String message) {
        getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit().putString(STATUS_KEY, message).apply();
    }

    private void publish(String message, int value, boolean active) {
        status = message; percent = Math.max(0, Math.min(100, value));
        if (active && (android.os.Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED)) {
            getSystemService(NotificationManager.class)
                .notify(NOTIFICATION_ID, notification(message, percent));
        }
        Intent update = new Intent(ACTION_UPDATE).setPackage(getPackageName());
        update.putExtra(EXTRA_STATUS, message).putExtra(EXTRA_PERCENT, percent)
            .putExtra(EXTRA_RUNNING, active);
        sendBroadcast(update);
    }

    private Notification notification(String message, int value) {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent cancelIntent = new Intent(this, DiscExtractionService.class).setAction(ACTION_CANCEL);
        PendingIntent cancel = PendingIntent.getService(this, 1, cancelIntent,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Extracting Mario Kart Wii disc").setContentText(firstLine(message))
            .setStyle(new Notification.BigTextStyle().bigText(message)).setContentIntent(open)
            .setOngoing(running).setOnlyAlertOnce(true).setProgress(100, value, value <= 1);
        if (running) builder.addAction(new Notification.Action.Builder(null, "Cancel", cancel).build());
        return builder.build();
    }

    private static String firstLine(String message) {
        if (message == null) return "Working…";
        int newline = message.indexOf('\n');
        return newline < 0 ? message : message.substring(0, newline);
    }

    private static String safeMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() {
        if (running) CANCELLED.set(true);
        worker.shutdownNow();
        super.onDestroy();
    }
}

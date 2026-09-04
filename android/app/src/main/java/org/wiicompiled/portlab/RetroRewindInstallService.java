package org.wiicompiled.portlab;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import java.io.File;
import java.io.InterruptedIOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Keeps the multi-gigabyte Retro Rewind install alive across activity and display changes. */
public final class RetroRewindInstallService extends Service {
    static final String ACTION_START = "org.wiicompiled.portlab.RETRO_REWIND_START";
    static final String ACTION_CANCEL = "org.wiicompiled.portlab.RETRO_REWIND_CANCEL";
    static final String ACTION_UPDATE = "org.wiicompiled.portlab.RETRO_REWIND_UPDATE";
    static final String EXTRA_STATUS = "status", EXTRA_PERCENT = "percent", EXTRA_RUNNING = "running";
    private static final String CHANNEL = "retro-rewind-install";
    private static final String PREFERENCES = "retro-rewind-install";
    private static final String STATUS_KEY = "status";
    private static final int NOTIFICATION_ID = 43;
    private static final AtomicBoolean CANCELLED = new AtomicBoolean();
    private static volatile boolean running;
    private static volatile String status = "Retro Rewind installation is idle.";
    private static volatile int percent;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    static boolean isRunning() { return running; }
    static String currentStatus(Context context) {
        return running ? status : context.getSharedPreferences(PREFERENCES, MODE_PRIVATE)
            .getString(STATUS_KEY, status);
    }
    static int currentPercent() { return percent; }

    @Override public void onCreate() {
        super.onCreate();
        NotificationChannel channel = new NotificationChannel(
            CHANNEL, "Retro Rewind installation", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Progress while the official Retro Rewind distribution is downloaded and installed");
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_CANCEL.equals(action)) {
            if (running) { CANCELLED.set(true); publish("Cancelling Retro Rewind installation…", percent, true); }
            else stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(action) || running) return START_NOT_STICKY;
        if (BuilderService.isRunning() || DiscExtractionService.isRunning()) {
            publish("Wait for disc extraction or the private runtime build to finish first.", 0, false);
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        File external = getExternalFilesDir(null);
        File discFiles = external == null ? null : new File(external, "game/disc/files");
        if (discFiles == null || !discFiles.isDirectory()) {
            publish("Extract a clean PAL disc before installing Retro Rewind.", 0, false);
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        CANCELLED.set(false); running = true;
        publish("Preparing Retro Rewind…", 1, true);
        startForeground(NOTIFICATION_ID, notification(status, percent));
        worker.execute(() -> install(discFiles));
        return START_NOT_STICKY;
    }

    private void install(File discFiles) {
        try {
            final long[] lastPublish = {0};
            AndroidModManager.Mod installed = RetroRewindService.installOrUpdate(this, discFiles,
                (stage, done, total) -> {
                    if (CANCELLED.get() || Thread.currentThread().isInterrupted()) return false;
                    long now = android.os.SystemClock.elapsedRealtime();
                    if (now - lastPublish[0] < 250) return true;
                    lastPublish[0] = now;
                    String message = stage;
                    if (done > 0) message += total > 0
                        ? ": " + Math.min(done * 100 / total, 100) + "%"
                        : ": " + done / (1024 * 1024) + " MiB";
                    publish(message, stagePercent(stage, done, total), true);
                    return !CANCELLED.get();
                });
            String message = "Installed Retro Rewind " + installed.distributionVersion
                + " as a managed profile. Build the combined runtime if it is missing, then restart the game.";
            saveStatus(message); publish(message, 100, false);
        } catch (Exception error) {
            boolean cancelled = CANCELLED.get() || error instanceof InterruptedIOException;
            String message = cancelled
                ? "Retro Rewind installation cancelled. Existing profiles were left unchanged."
                : "Retro Rewind installation failed: " + safeMessage(error);
            saveStatus(message); publish(message, 0, false);
        } finally {
            running = false;
            stopForeground(STOP_FOREGROUND_DETACH);
            stopSelf();
        }
    }

    private int stagePercent(String stage, long done, long total) {
        if (stage != null && stage.startsWith("Downloading"))
            return total > 0 ? 5 + (int)Math.min(done * 50 / total, 50) : Math.max(5, percent);
        if (stage != null && (stage.startsWith("Extracting") || stage.startsWith("Applying"))) return 60;
        if (stage != null && stage.startsWith("Preparing"))
            return total > 0 ? 70 + (int)Math.min(done * 20 / total, 20) : 70;
        if (stage != null && stage.startsWith("Importing")) return 92;
        return Math.max(1, percent);
    }

    private void saveStatus(String message) {
        getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit().putString(STATUS_KEY, message).apply();
    }

    private void publish(String message, int value, boolean active) {
        status = message; percent = Math.max(0, Math.min(100, value));
        if (active && (android.os.Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED)) {
            getSystemService(NotificationManager.class).notify(
                NOTIFICATION_ID, notification(message, percent));
        }
        Intent update = new Intent(ACTION_UPDATE).setPackage(getPackageName());
        update.putExtra(EXTRA_STATUS, message).putExtra(EXTRA_PERCENT, percent)
            .putExtra(EXTRA_RUNNING, active);
        sendBroadcast(update);
    }

    private Notification notification(String message, int value) {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent cancel = PendingIntent.getService(this, 1,
            new Intent(this, RetroRewindInstallService.class).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Installing Retro Rewind").setContentText(message)
            .setStyle(new Notification.BigTextStyle().bigText(message)).setContentIntent(open)
            .setOngoing(running).setOnlyAlertOnce(true).setProgress(100, value, value <= 1);
        if (running) builder.addAction(new Notification.Action.Builder(null, "Cancel", cancel).build());
        return builder.build();
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

package org.wiicompiled.portlab;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Keeps the long private translation/build alive when the activity is backgrounded. */
public final class BuilderService extends Service {
    static final String ACTION_START = "org.wiicompiled.portlab.BUILD_START";
    static final String ACTION_CANCEL = "org.wiicompiled.portlab.BUILD_CANCEL";
    static final String ACTION_UPDATE = "org.wiicompiled.portlab.BUILD_UPDATE";
    static final String EXTRA_STATUS = "status", EXTRA_PERCENT = "percent", EXTRA_RUNNING = "running";
    private static final String CHANNEL = "private-runtime-builder";
    private static volatile boolean running;
    private static volatile String status = "Builder is idle.";
    private static volatile int percent;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    static boolean isRunning() { return running; }
    static String currentStatus() { return status; }
    static int currentPercent() { return percent; }

    @Override public void onCreate() {
        super.onCreate();
        NotificationChannel channel = new NotificationChannel(CHANNEL, "Private runtime build", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Progress while WiiCompiled builds your private runtime on this device");
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_CANCEL.equals(action)) {
            if (running) { AndroidBuilderManager.cancel(); publish("Cancelling build…", percent, true); }
            else stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(action) || running) return START_NOT_STICKY;
        if (DiscExtractionService.isRunning()) {
            publish("Wait for disc extraction to finish before building.", 0, false);
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        running = true; publish("Starting private Android build…", 0, true);
        startForeground(41, notification(status, percent));
        worker.execute(() -> {
            try { AndroidBuilderManager.build(this, (message, value) -> publish(message, value, true)); }
            catch (Exception error) {
                String message = "Build failed: " + (error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
                getSharedPreferences("android-builder", MODE_PRIVATE).edit().putString("status", message).apply();
                publish(message, percent, false);
            } finally { running = false; publish(status, percent, false); stopForeground(STOP_FOREGROUND_DETACH); stopSelf(); }
        });
        return START_NOT_STICKY;
    }

    private void publish(String message, int value, boolean active) {
        status = message; percent = Math.max(0, Math.min(100, value));
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (active && (android.os.Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED)) {
            manager.notify(41, notification(message, percent));
        }
        Intent update = new Intent(ACTION_UPDATE).setPackage(getPackageName());
        update.putExtra(EXTRA_STATUS, message).putExtra(EXTRA_PERCENT, percent).putExtra(EXTRA_RUNNING, active);
        sendBroadcast(update);
    }

    private Notification notification(String message, int value) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent cancelIntent = new Intent(this, BuilderService.class).setAction(ACTION_CANCEL);
        PendingIntent cancel = PendingIntent.getService(this, 1, cancelIntent,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Building private WiiCompiled runtime").setContentText(message).setContentIntent(pending)
            .setStyle(new Notification.BigTextStyle().bigText(message))
            .setOngoing(running).setOnlyAlertOnce(true).setProgress(100, value, value <= 0);
        if (running) builder.addAction(new Notification.Action.Builder(null, "Cancel", cancel).build());
        return builder.build();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() {
        if (running) AndroidBuilderManager.cancel();
        worker.shutdownNow();
        super.onDestroy();
    }
}

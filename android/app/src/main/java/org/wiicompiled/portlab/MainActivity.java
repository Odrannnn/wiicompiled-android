package org.wiicompiled.portlab;

import android.app.Activity;
import android.content.Intent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Bundle;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.Button;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Android setup, diagnostics, and local game launcher. */
public final class MainActivity extends Activity {
    private static final int PICK_DISC = 1, EXPORT_REPORT = 2, PICK_GPU_DRIVER = 3, PICK_MOD = 4,
        PICK_RUNTIME_PACK = 5, EXPORT_BUILD_LOG = 6, EXPORT_GAME_LOG = 7;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private TextView diagnostics, discStatus, gpuDriverStatus, modStatus, retroRewindStatus, runtimeStatus, builderStatus;
    private LinearLayout modsContainer;
    private Button testButton, builderBuildButton, builderCancelButton, discExtractButton, discCancelButton;
    private Button retroCheckButton, retroCancelButton;
    private Button[] navigationButtons;
    private View[] pages;
    private int selectedPage;
    private String report = "No native diagnostics have run.";
    private boolean stopped;
    private boolean builderReceiverRegistered, discReceiverRegistered, retroReceiverRegistered;
    private final BroadcastReceiver builderReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            updateBuilderUi(intent.getStringExtra(BuilderService.EXTRA_STATUS),
                intent.getIntExtra(BuilderService.EXTRA_PERCENT, 0),
                intent.getBooleanExtra(BuilderService.EXTRA_RUNNING, false));
        }
    };
    private final BroadcastReceiver discReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            updateDiscUi(intent.getStringExtra(DiscExtractionService.EXTRA_STATUS),
                intent.getIntExtra(DiscExtractionService.EXTRA_PERCENT, 0),
                intent.getBooleanExtra(DiscExtractionService.EXTRA_RUNNING, false));
        }
    };
    private final BroadcastReceiver retroReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            updateRetroUi(intent.getStringExtra(RetroRewindInstallService.EXTRA_STATUS),
                intent.getIntExtra(RetroRewindInstallService.EXTRA_PERCENT, 0),
                intent.getBooleanExtra(RetroRewindInstallService.EXTRA_RUNNING, false));
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        List<String> requestedPermissions = new ArrayList<>();
        if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(
                android.Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestedPermissions.add(android.Manifest.permission.POST_NOTIFICATIONS);
        if (android.os.Build.VERSION.SDK_INT >= 31 && checkSelfPermission(
                android.Manifest.permission.BLUETOOTH_CONNECT) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestedPermissions.add(android.Manifest.permission.BLUETOOTH_CONNECT);
        if (!requestedPermissions.isEmpty())
            requestPermissions(requestedPermissions.toArray(new String[0]), 100);
        boolean wide = getResources().getConfiguration().screenWidthDp >= 840;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(10, 15, 26));
        // Android 16 enforces edge-to-edge: keep controls clear of system bars.
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(
                android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        setContentView(root);

        LinearLayout navigation = new LinearLayout(this);
        navigation.setOrientation(wide ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        navigation.setPadding(dp(wide ? 24 : 8), dp(12), dp(wide ? 24 : 8), dp(12));
        navigation.setBackgroundColor(Color.rgb(17, 24, 39));
        if (wide) {
            root.addView(navigation, new LinearLayout.LayoutParams(dp(280), -1));
            label(navigation, "WIICOMPILED", 13, Color.rgb(94, 234, 212));
            label(navigation, "Port Lab", 30, Color.WHITE);
            label(navigation, "Mario Kart Wii · Android", 15, Color.LTGRAY);
        } else {
            LinearLayout compactChrome = new LinearLayout(this);
            compactChrome.setOrientation(LinearLayout.VERTICAL);
            compactChrome.setBackgroundColor(Color.rgb(17, 24, 39));
            compactChrome.setPadding(dp(12), dp(8), dp(12), dp(4));
            root.addView(compactChrome, new LinearLayout.LayoutParams(-1, -2));
            label(compactChrome, "WIICOMPILED  ·  PORT LAB", 20, Color.WHITE);
            compactChrome.addView(navigation, new LinearLayout.LayoutParams(-1, -2));
        }

        FrameLayout pageHost = new FrameLayout(this);
        root.addView(pageHost, new LinearLayout.LayoutParams(wide ? 0 : -1, wide ? -1 : 0, 1f));
        pages = new View[]{buildPlayPage(), buildModsPage(wide), buildGraphicsPage(), buildToolsPage(wide)};
        for (View page : pages) pageHost.addView(page, new FrameLayout.LayoutParams(-1, -1));

        String[] destinations = {"Play", "Mods", "Graphics", "Tools"};
        navigationButtons = new Button[destinations.length];
        for (int index = 0; index < destinations.length; index++) {
            final int page = index;
            Button destination = new Button(this);
            destination.setText(destinations[index]); destination.setAllCaps(false);
            destination.setTextSize(16); destination.setOnClickListener(v -> showPage(page));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(wide ? -1 : 0, -2, wide ? 0f : 1f);
            params.topMargin = dp(4); params.bottomMargin = dp(4);
            navigation.addView(destination, params); navigationButtons[index] = destination;
        }

        selectedPage = state == null ? 0 : state.getInt("selectedPage", 0);
        showPage(Math.max(0, Math.min(selectedPage, pages.length - 1)));
        refreshModsUi(null);
        if (state != null) {
            report = state.getString("report", report);
            diagnostics.setText(report);
        }
        // Local ADB launch hook. GameActivity remains non-exported; MainActivity performs
        // the same explicit in-app transition as the visible experimental launch button.
        if (getIntent().getBooleanExtra("launchGame", false)) {
            Intent game = new Intent();
            game.setClassName(this, "org.wiicompiled.portlab.GameActivity");
            startActivity(game);
        }
    }

    private View buildPlayPage() {
        LinearLayout page = pageContent();
        LinearLayout overview = card("Ready to race",
            "Launch the local PAL build after the disc data and private ARM64 runtime are ready.");
        button(overview, "Launch Mario Kart Wii", v -> launchGame());
        addCard(page, overview);
        LinearLayout runtime = card("Private ARM64 runtime",
            "The runtime is generated from your own disc and stays on this device. The public APK contains no translated game code.");
        runtimeStatus = label(runtime, RuntimePackManager.status(this), 15, Color.rgb(209, 250, 229));
        button(runtime, "Import existing runtime pack", v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE); intent.setType("application/zip");
            intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/zip", "application/octet-stream", "application/x-zip-compressed"});
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION); startActivityForResult(intent, PICK_RUNTIME_PACK);
        });
        addCard(page, runtime);
        LinearLayout builder = card("Build on this Android device",
            "The Builder edition translates your extracted disc and compiles a private ARM64 runtime here. When a supported Retro Rewind profile is enabled, the same build produces both required libraries and securely downloads its signed WFC payload. The first online build needs Internet access. No PC or Termux is required. Keep at least 4 GiB free and connect power.");
        builderStatus = label(builder, AndroidBuilderManager.status(this), 15, Color.rgb(209, 250, 229));
        builderBuildButton = button(builder, "Build private runtime", v -> {
            if (!AndroidBuilderManager.available(this)) {
                new android.app.AlertDialog.Builder(this).setTitle("Builder edition required")
                    .setMessage(AndroidBuilderManager.status(this)).setPositiveButton("Close", null).show(); return;
            }
            Intent service = new Intent(this, BuilderService.class).setAction(BuilderService.ACTION_START);
            startForegroundService(service); updateBuilderUi("Starting private Android build…", 0, true);
        });
        builderBuildButton.setEnabled(AndroidBuilderManager.available(this));
        builderCancelButton = button(builder, "Cancel current build", v -> {
            Intent service = new Intent(this, BuilderService.class).setAction(BuilderService.ACTION_CANCEL);
            startService(service);
        });
        addCard(page, builder);
        LinearLayout disc = card("PAL disc reference",
            "Extract your own clean PAL RMCP01 image directly into app-private storage. Supports ISO, RVZ, WBFS, WIA, CISO, and GCZ.");
        discStatus = label(disc, DiscExtractionService.currentStatus(this),
            15, Color.rgb(209, 250, 229));
        discExtractButton = button(disc, "Select and extract disc image", v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE); intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/octet-stream", "application/x-iso9660-image", "application/x-wbfs"});
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(intent, PICK_DISC);
        });
        discCancelButton = button(disc, "Cancel current extraction", v -> {
            Intent service = new Intent(this, DiscExtractionService.class)
                .setAction(DiscExtractionService.ACTION_CANCEL);
            startService(service);
        });
        updateDiscUi(DiscExtractionService.currentStatus(this),
            DiscExtractionService.currentPercent(), DiscExtractionService.isRunning());
        addCard(page, disc); return pageScroll(page);
    }

    private View buildModsPage(boolean wide) {
        LinearLayout page = pageContent();
        LinearLayout actions = card("Mod library",
            "Import WheelWizard ZIPs or browse compatible releases. Profiles stay reversible and private.");
        modStatus = label(actions, "", 15, Color.rgb(209, 250, 229));
        button(actions, "Import WheelWizard mod ZIP", v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE); intent.setType("application/zip");
            intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/zip", "application/octet-stream", "application/x-zip-compressed"});
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION); startActivityForResult(intent, PICK_MOD);
        });
        button(actions, "Browse online mods", v -> showCatalogueSearch());
        LinearLayout retro = card("Retro Rewind",
            "Install or update the official distribution as a managed, hash-gated ARM64 profile.");
        retroRewindStatus = label(retro, RetroRewindInstallService.currentStatus(this),
            15, Color.rgb(209, 250, 229));
        retroCheckButton = button(retro, "Check installation", v -> checkRetroRewind());
        retroCancelButton = button(retro, "Cancel current installation", v -> startService(
            new Intent(this, RetroRewindInstallService.class).setAction(RetroRewindInstallService.ACTION_CANCEL)));
        retroCancelButton.setEnabled(RetroRewindInstallService.isRunning());
        addPair(page, actions, retro, wide);
        LinearLayout profiles = card("Installed profiles", "Enable, prioritize, configure, or remove local profiles.");
        modsContainer = new LinearLayout(this); modsContainer.setOrientation(LinearLayout.VERTICAL);
        profiles.addView(modsContainer, new LinearLayout.LayoutParams(-1, -2)); addCard(page, profiles);
        return pageScroll(page);
    }

    private View buildGraphicsPage() {
        LinearLayout page = pageContent();
        LinearLayout driver = card("Vulkan driver",
            "The system driver is the safe default. Compatible arm64 Turnip ZIPs remain private to WiiCompiled.");
        String driverText = GpuDriverManager.status(this), runtimeDriverText = GpuDriverManager.runtimeStatus(this);
        if (runtimeDriverText != null) driverText += "\n\nLast game start: " + runtimeDriverText;
        gpuDriverStatus = label(driver, driverText, 15, Color.rgb(209, 250, 229));
        button(driver, "Import custom driver ZIP", v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE); intent.setType("application/zip");
            intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/zip", "application/octet-stream", "application/x-zip-compressed"});
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION); startActivityForResult(intent, PICK_GPU_DRIVER);
        });
        button(driver, "Use system Vulkan driver", v -> worker.execute(() -> {
            String result;
            try { GpuDriverManager.reset(this); result = GpuDriverManager.status(this); }
            catch (Exception error) { result = "Could not reset driver: " + error.getMessage(); }
            final String finished = result;
            runOnUiThread(() -> { if (!stopped) gpuDriverStatus.setText(finished); });
        }));
        addCard(page, driver); return pageScroll(page);
    }

    private View buildToolsPage(boolean wide) {
        LinearLayout page = pageContent();
        LinearLayout checks = card("Device diagnostics", "Run native ARM64, Vulkan, storage, and runtime checks.");
        testButton = button(checks, "Run device checks", v -> runDiagnostics());
        diagnostics = label(checks, report, 15, Color.rgb(209, 250, 229)); diagnostics.setTextIsSelectable(true);
        button(checks, "Export diagnostic report", v -> {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE); intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_TITLE, "wiicompiled-android-diagnostics.txt");
            startActivityForResult(intent, EXPORT_REPORT);
        });
        button(checks, "Export latest build log", v -> beginLogExport(
            EXPORT_BUILD_LOG, "wiicompiled-build.log", latestBuildLog()));
        button(checks, "Export latest game log", v -> beginLogExport(
            EXPORT_GAME_LOG, "wiicompiled-game.log", latestGameLog()));
        LinearLayout about = card("About this build",
            "Version " + BuildConfig.VERSION_NAME + ". No copyrighted game image is bundled or downloaded by WiiCompiled.");
        button(about, "Check for app updates", v -> checkForAppUpdate());
        button(about, "View licenses and upstream sources", v -> {
            try (InputStream stream = getAssets().open("NOTICES.txt")) {
                new android.app.AlertDialog.Builder(this).setTitle("Licenses and upstream sources")
                    .setMessage(readNotice(stream)).setPositiveButton("Close", null).show();
            } catch (Exception error) {
                new android.app.AlertDialog.Builder(this).setTitle("Could not load notices")
                    .setMessage(error.getMessage()).setPositiveButton("Close", null).show();
            }
        });
        addPair(page, checks, about, wide); return pageScroll(page);
    }

    private void checkForAppUpdate() {
        diagnostics.setText("Checking official GitHub releases…");
        worker.execute(() -> {
            try {
                AppUpdateChecker.Result result = AppUpdateChecker.check();
                runOnUiThread(() -> {
                    if (stopped) return;
                    if (!result.updateAvailable()) {
                        diagnostics.setText("WiiCompiled Android " + result.currentVersion() + " is current.");
                        return;
                    }
                    diagnostics.setText("Update available: " + result.latestVersion());
                    new android.app.AlertDialog.Builder(this).setTitle("App update available")
                        .setMessage("Installed: " + result.currentVersion() + "\nAvailable: "
                            + result.latestVersion() + "\n\nOpen the signed GitHub release to review and download it?")
                        .setNegativeButton("Later", null)
                        .setPositiveButton("Open release", (dialog, which) -> {
                            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(result.releaseUrl()))); }
                            catch (RuntimeException error) { diagnostics.setText("Could not open release: " + error.getMessage()); }
                        }).show();
                });
            } catch (Exception error) {
                String message = "Update check failed: " + error.getMessage();
                runOnUiThread(() -> { if (!stopped) diagnostics.setText(message); });
            }
        });
    }

    private void beginLogExport(int request, String name, File source) {
        if (source == null || !source.isFile()) {
            diagnostics.setText(request == EXPORT_BUILD_LOG
                ? "No build log exists yet. Start an on-device build first."
                : "No game log exists yet. Launch the game once first.");
            return;
        }
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TITLE, name);
        startActivityForResult(intent, request);
    }

    private File latestBuildLog() {
        return new File(getFilesDir(), "android-builder/build.log");
    }

    private File latestGameLog() {
        File root = new File(getFilesDir(), "WiiCompiled/Logs");
        File[] runs = root.listFiles(File::isDirectory);
        if (runs == null) return null;
        File newest = null;
        for (File run : runs) {
            File candidate = new File(run, "console.log");
            if (candidate.isFile() && (newest == null || candidate.lastModified() > newest.lastModified()))
                newest = candidate;
        }
        return newest;
    }

    private void exportLog(File source, Uri destination, String label) {
        worker.execute(() -> {
            try (InputStream input = new java.io.FileInputStream(source);
                 OutputStream output = getContentResolver().openOutputStream(destination, "wt")) {
                if (output == null) throw new java.io.IOException("Document provider did not open the destination");
                byte[] buffer = new byte[64 * 1024];
                for (int read; (read = input.read(buffer)) != -1;) output.write(buffer, 0, read);
                runOnUiThread(() -> { if (!stopped) diagnostics.setText(label + " exported successfully."); });
            } catch (Exception error) {
                runOnUiThread(() -> { if (!stopped) diagnostics.setText(label + " export failed: " + error.getMessage()); });
            }
        });
    }

    private void launchGame() {
        try {
            String requested = AndroidModManager.runtimeLibrary(this);
            if (!BuildConfig.WITH_GAME && !RuntimePackManager.canLaunch(this, requested)) {
                new android.app.AlertDialog.Builder(this).setTitle("Private runtime required")
                    .setMessage("This profile needs lib" + requested + ".so. Build it on this tablet or import a matching runtime pack first.")
                    .setPositiveButton("Close", null).show();
                return;
            }
        } catch (Exception error) {
            new android.app.AlertDialog.Builder(this).setTitle("Cannot launch")
                .setMessage(error.getMessage()).setPositiveButton("Close", null).show();
            return;
        }
        Intent game = new Intent(); game.setClassName(this, "org.wiicompiled.portlab.GameActivity"); startActivity(game);
    }

    private void updateBuilderUi(String message, int percent, boolean running) {
        if (builderStatus == null) return;
        String text = message == null ? AndroidBuilderManager.status(this) : message;
        if (running && percent > 0) text += "\n" + percent + "% complete";
        builderStatus.setText(text);
        if (!running && percent >= 100 && runtimeStatus != null) runtimeStatus.setText(RuntimePackManager.status(this));
        if (builderBuildButton != null) builderBuildButton.setEnabled(
            AndroidBuilderManager.available(this) && !running && !DiscExtractionService.isRunning()
                && !RetroRewindInstallService.isRunning());
        if (builderCancelButton != null) builderCancelButton.setEnabled(running);
        if (discExtractButton != null) discExtractButton.setEnabled(!running && !DiscExtractionService.isRunning()
            && !RetroRewindInstallService.isRunning());
    }

    private void updateDiscUi(String message, int percent, boolean running) {
        if (discStatus == null) return;
        String text = message == null ? DiscExtractionService.currentStatus(this) : message;
        if (running && percent > 1) text += "\n" + percent + "% complete";
        discStatus.setText(text);
        if (discExtractButton != null) discExtractButton.setEnabled(!running && !BuilderService.isRunning()
            && !RetroRewindInstallService.isRunning());
        if (discCancelButton != null) discCancelButton.setEnabled(running);
        if (builderBuildButton != null) builderBuildButton.setEnabled(
            AndroidBuilderManager.available(this) && !running && !BuilderService.isRunning()
                && !RetroRewindInstallService.isRunning());
        if (!running && percent >= 100 && runtimeStatus != null)
            runtimeStatus.setText(RuntimePackManager.status(this));
    }

    private void updateRetroUi(String message, int percent, boolean running) {
        if (retroRewindStatus == null) return;
        String text = message == null ? RetroRewindInstallService.currentStatus(this) : message;
        if (running && percent > 1) text += "\n" + percent + "% complete";
        retroRewindStatus.setText(text);
        if (retroCheckButton != null) retroCheckButton.setEnabled(!running);
        if (retroCancelButton != null) retroCancelButton.setEnabled(running);
        if (builderBuildButton != null) builderBuildButton.setEnabled(AndroidBuilderManager.available(this)
            && !running && !BuilderService.isRunning() && !DiscExtractionService.isRunning());
        if (discExtractButton != null) discExtractButton.setEnabled(
            !running && !BuilderService.isRunning() && !DiscExtractionService.isRunning());
        if (!running && percent >= 100) refreshModsUi(text);
    }

    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Override protected void onStart() {
        super.onStart(); stopped = false;
        IntentFilter filter = new IntentFilter(BuilderService.ACTION_UPDATE);
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(builderReceiver, filter, RECEIVER_NOT_EXPORTED);
        else registerReceiver(builderReceiver, filter);
        builderReceiverRegistered = true;
        IntentFilter discFilter = new IntentFilter(DiscExtractionService.ACTION_UPDATE);
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(discReceiver, discFilter, RECEIVER_NOT_EXPORTED);
        else registerReceiver(discReceiver, discFilter);
        discReceiverRegistered = true;
        IntentFilter retroFilter = new IntentFilter(RetroRewindInstallService.ACTION_UPDATE);
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(retroReceiver, retroFilter, RECEIVER_NOT_EXPORTED);
        else registerReceiver(retroReceiver, retroFilter);
        retroReceiverRegistered = true;
        updateBuilderUi(BuilderService.isRunning() ? BuilderService.currentStatus() : AndroidBuilderManager.status(this),
            BuilderService.currentPercent(), BuilderService.isRunning());
        updateDiscUi(DiscExtractionService.currentStatus(this), DiscExtractionService.currentPercent(),
            DiscExtractionService.isRunning());
        updateRetroUi(RetroRewindInstallService.currentStatus(this), RetroRewindInstallService.currentPercent(),
            RetroRewindInstallService.isRunning());
    }

    @Override protected void onStop() {
        if (builderReceiverRegistered) { unregisterReceiver(builderReceiver); builderReceiverRegistered = false; }
        if (discReceiverRegistered) { unregisterReceiver(discReceiver); discReceiverRegistered = false; }
        if (retroReceiverRegistered) { unregisterReceiver(retroReceiver); retroReceiverRegistered = false; }
        super.onStop();
    }

    private void showPage(int index) {
        selectedPage = index;
        for (int i = 0; i < pages.length; i++) {
            boolean selected = i == index; pages[i].setVisibility(selected ? View.VISIBLE : View.GONE);
            navigationButtons[i].setTextColor(selected ? Color.rgb(8, 47, 43) : Color.WHITE);
            navigationButtons[i].setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                selected ? Color.rgb(94, 234, 212) : Color.rgb(31, 41, 55)));
        }
    }

    private LinearLayout pageContent() {
        LinearLayout page = new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(24), dp(20), dp(24), dp(28)); return page;
    }

    private ScrollView pageScroll(LinearLayout page) {
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.addView(page); return scroll;
    }

    private LinearLayout card(String title, String description) {
        LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(20), dp(16), dp(20), dp(18));
        GradientDrawable background = new GradientDrawable(); background.setColor(Color.rgb(24, 34, 49));
        background.setCornerRadius(dp(16)); background.setStroke(dp(1), Color.rgb(55, 70, 91));
        card.setBackground(background); label(card, title, 22, Color.WHITE);
        label(card, description, 15, Color.rgb(190, 200, 216)); return card;
    }

    private void addCard(LinearLayout page, LinearLayout card) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.bottomMargin = dp(16);
        page.addView(card, params);
    }

    private void addPair(LinearLayout page, LinearLayout first, LinearLayout second, boolean wide) {
        if (!wide) { addCard(page, first); addCard(page, second); return; }
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams firstParams = new LinearLayout.LayoutParams(0, -2, 1f);
        firstParams.rightMargin = dp(8); row.addView(first, firstParams);
        LinearLayout.LayoutParams secondParams = new LinearLayout.LayoutParams(0, -2, 1f);
        secondParams.leftMargin = dp(8); row.addView(second, secondParams);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2); rowParams.bottomMargin = dp(16);
        page.addView(row, rowParams);
    }

    private void runDiagnostics() {
        testButton.setEnabled(false);
        diagnostics.setText("Running ARM64 checks…");
        worker.execute(() -> {
            String result;
            try { result = NativeProbe.run(); }
            catch (LinkageError | RuntimeException e) { result = "FAIL: native checks unavailable: " + e; }
            String text = "Device: " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL
                + "\nAndroid " + android.os.Build.VERSION.RELEASE + " (API " + android.os.Build.VERSION.SDK_INT
                + ")\n\n" + result;
            // App-private file allows adb run-as to retrieve exactly what was displayed.
            try (OutputStream file = openFileOutput("diagnostics.txt", MODE_PRIVATE)) {
                file.write(text.getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) { text += "\nReport persistence failed: " + e.getMessage(); }
            final String completed = text;
            runOnUiThread(() -> {
                if (stopped) return;
                report = completed;
                diagnostics.setText(report);
                testButton.setEnabled(true);
            });
        });
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (request == EXPORT_REPORT) {
            final String snapshot = report;
            worker.execute(() -> {
                try (OutputStream output = getContentResolver().openOutputStream(uri, "wt")) {
                    if (output == null) throw new java.io.IOException("Document provider did not open the report");
                    output.write(snapshot.getBytes(StandardCharsets.UTF_8));
                } catch (Exception e) {
                    runOnUiThread(() -> { if (!stopped) diagnostics.setText(report + "\nExport failed: " + e.getMessage()); });
                }
            });
        } else if (request == EXPORT_BUILD_LOG) {
            File source = latestBuildLog();
            if (source.isFile()) exportLog(source, uri, "Build log");
            else diagnostics.setText("The build log is no longer available.");
        } else if (request == EXPORT_GAME_LOG) {
            File source = latestGameLog();
            if (source != null && source.isFile()) exportLog(source, uri, "Game log");
            else diagnostics.setText("The game log is no longer available.");
        } else if (request == PICK_DISC) {
            Intent service = new Intent(this, DiscExtractionService.class)
                .setAction(DiscExtractionService.ACTION_START).setData(uri)
                .addFlags(data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION |
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION));
            startForegroundService(service);
            updateDiscUi("Opening selected disc image…", 1, true);
        } else if (request == PICK_GPU_DRIVER) {
            gpuDriverStatus.setText("Importing and validating driver…");
            worker.execute(() -> {
                String message;
                try { message = GpuDriverManager.importZip(this, uri) + "\n\n" + GpuDriverManager.status(this); }
                catch (Exception error) { message = "Driver import rejected: " + error.getMessage(); }
                final String finished = message;
                runOnUiThread(() -> { if (!stopped) gpuDriverStatus.setText(finished); });
            });
        } else if (request == PICK_MOD) {
            modStatus.setText("Importing and matching mod files…");
            worker.execute(() -> {
                String message;
                try {
                    File external = getExternalFilesDir(null);
                    if (external == null) throw new java.io.IOException("External app storage unavailable");
                    File discFiles = new File(external, "game/disc/files");
                    message = AndroidModManager.importZip(this, uri, discFiles).message;
                } catch (Exception error) { message = "Mod import rejected: " + error.getMessage(); }
                final String finished = message;
                runOnUiThread(() -> { if (!stopped) refreshModsUi(finished); });
            });
        } else if (request == PICK_RUNTIME_PACK) {
            runtimeStatus.setText("Importing and verifying private ARM64 runtime…");
            worker.execute(() -> {
                String message;
                try { message = RuntimePackManager.importZip(this, uri); }
                catch (Exception error) { message = "Runtime import rejected: " + error.getMessage(); }
                final String finished = message;
                runOnUiThread(() -> { if (!stopped) runtimeStatus.setText(finished); });
            });
        }
    }

    private void refreshModsUi(String notice) {
        java.util.List<AndroidModManager.Mod> mods = AndroidModManager.list(this);
        String summary = mods.isEmpty() ? "No mods installed. Base game files remain unchanged."
            : mods.size() + " mod(s) installed. Enabled changes apply on the next game start.";
        modStatus.setText(notice == null ? summary : notice + "\n\n" + summary);
        modsContainer.removeAllViews();
        for (int modIndex = 0; modIndex < mods.size(); modIndex++) {
            AndroidModManager.Mod mod = mods.get(modIndex);
            String detail = (mod.enabled ? "ENABLED  " : "DISABLED  ") + mod.title
                + (mod.author.isEmpty() || mod.author.equals("-1") ? "" : "\nBy " + mod.author)
                + "\nPriority " + (mods.size() - modIndex) + " · " + mod.mappedFiles + " usable file(s)"
                + (mod.archivePatchFiles == 0 ? "" : ", " + mod.archivePatchFiles + " tagged archive patch file(s)")
                + (mod.skippedFiles == 0 ? "" : ", " + mod.skippedFiles + " skipped")
                + (mod.codeReport.requirements.isEmpty() ? "" : "\n" + mod.codeReport.summary())
                + (mod.codeReport.blocked == 0 ? "" : "\nExecutable blockers prevent this profile from launching.");
            label(modsContainer, detail, 15, mod.enabled ? Color.rgb(134, 239, 172) : Color.LTGRAY);
            if (!mod.codeReport.requirements.isEmpty()) button(modsContainer, "View code compatibility", v ->
                new android.app.AlertDialog.Builder(this).setTitle(mod.title + " code compatibility")
                    .setMessage(mod.codeReport.details()).setPositiveButton("Close", null).show());
            button(modsContainer, (mod.enabled ? "Disable " : "Enable ") + mod.title, v -> worker.execute(() -> {
                String result;
                try { AndroidModManager.setEnabled(this, mod, !mod.enabled); result = "Updated " + mod.title + "."; }
                catch (Exception error) { result = "Could not update mod: " + error.getMessage(); }
                final String finished = result;
                runOnUiThread(() -> { if (!stopped) refreshModsUi(finished); });
            }));
            if (modIndex > 0) button(modsContainer, "Raise priority", v -> worker.execute(() -> {
                String result;
                try { AndroidModManager.movePriority(this, mod, true); result = "Raised priority for " + mod.title + "."; }
                catch (Exception error) { result = "Could not change priority: " + error.getMessage(); }
                final String finished = result;
                runOnUiThread(() -> { if (!stopped) refreshModsUi(finished); });
            }));
            if (modIndex + 1 < mods.size()) button(modsContainer, "Lower priority", v -> worker.execute(() -> {
                String result;
                try { AndroidModManager.movePriority(this, mod, false); result = "Lowered priority for " + mod.title + "."; }
                catch (Exception error) { result = "Could not change priority: " + error.getMessage(); }
                final String finished = result;
                runOnUiThread(() -> { if (!stopped) refreshModsUi(finished); });
            }));
            if (!mod.riivolutionRoot.isEmpty()) button(modsContainer, "Configure Riivolution options", v -> {
                modStatus.setText("Reading Riivolution options for " + mod.title + "…");
                worker.execute(() -> {
                    try {
                        List<AndroidModManager.RiivolutionOption> options = AndroidModManager.riivolutionOptions(mod);
                        runOnUiThread(() -> { if (!stopped) showRiivolutionOptions(mod, options); });
                    } catch (Exception error) {
                        String result = "Could not read Riivolution options: " + error.getMessage();
                        runOnUiThread(() -> { if (!stopped) refreshModsUi(result); });
                    }
                });
            });
            button(modsContainer, "Remove " + mod.title, v -> worker.execute(() -> {
                String result;
                try { AndroidModManager.delete(this, mod); result = "Removed " + mod.title + "."; }
                catch (Exception error) { result = "Could not remove mod: " + error.getMessage(); }
                final String finished = result;
                runOnUiThread(() -> { if (!stopped) refreshModsUi(finished); });
            }));
        }
    }

    private void showRiivolutionOptions(AndroidModManager.Mod mod,
                                        List<AndroidModManager.RiivolutionOption> options) {
        if (options.isEmpty()) { refreshModsUi("This pack exposes no configurable PAL options."); return; }
        ScrollView scroll = new ScrollView(this); LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL); int padding = dp(16);
        content.setPadding(padding, padding, padding, padding); scroll.addView(content);
        Map<AndroidModManager.RiivolutionOption, Spinner> controls = new LinkedHashMap<>();
        for (AndroidModManager.RiivolutionOption option : options) {
            TextView title = new TextView(this); title.setText(option.name); title.setTextSize(16);
            title.setTextColor(Color.WHITE); title.setPadding(0, dp(10), 0, dp(4)); content.addView(title);
            Spinner spinner = new Spinner(this);
            ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, option.choices);
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            spinner.setAdapter(adapter); spinner.setSelection(option.selected); content.addView(spinner);
            controls.put(option, spinner);
        }
        new android.app.AlertDialog.Builder(this).setTitle("Riivolution options · " + mod.title)
            .setView(scroll).setNegativeButton("Cancel", null).setPositiveButton("Apply", (dialog, which) -> {
                Map<String, Integer> selected = new LinkedHashMap<>();
                for (Map.Entry<AndroidModManager.RiivolutionOption, Spinner> entry : controls.entrySet())
                    selected.put(entry.getKey().id, entry.getValue().getSelectedItemPosition());
                modStatus.setText("Rebuilding " + mod.title + " with the selected options…");
                worker.execute(() -> {
                    String result;
                    try {
                        AndroidModManager.setRiivolutionChoices(this, mod, selected);
                        result = "Applied Riivolution options to " + mod.title + ". Restart the game to use them.";
                    } catch (Exception error) { result = "Could not apply Riivolution options: " + error.getMessage(); }
                    final String finished = result;
                    runOnUiThread(() -> { if (!stopped) refreshModsUi(finished); });
                });
            }).show();
    }

    private void showCatalogueSearch() {
        EditText search = new EditText(this); search.setHint("Mod name or patches"); search.setSingleLine(true);
        int padding = dp(20); search.setPadding(padding, padding, padding, padding);
        new android.app.AlertDialog.Builder(this).setTitle("Search Mario Kart Wii mods")
            .setView(search).setNegativeButton("Cancel", null).setPositiveButton("Search", (dialog, which) ->
                loadCatalogue(search.getText().toString(), 1)).show();
    }

    private void loadCatalogue(String search, int page) {
        String term = search == null ? "" : search.trim();
        modStatus.setText("Loading online mod catalogue…");
        worker.execute(() -> {
            try {
                List<GameBananaClient.CatalogMod> mods = GameBananaClient.search(term, page);
                runOnUiThread(() -> { if (!stopped) showCatalogueResults(term, page, mods); });
            } catch (Exception error) {
                String result = "Could not load online mods: " + error.getMessage();
                runOnUiThread(() -> { if (!stopped) refreshModsUi(result); });
            }
        });
    }

    private void showCatalogueResults(String search, int page, List<GameBananaClient.CatalogMod> mods) {
        ScrollView scroll = new ScrollView(this); LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL); int padding = dp(14);
        content.setPadding(padding, padding, padding, padding); scroll.addView(content);
        if (mods.isEmpty()) {
            TextView empty = new TextView(this); empty.setText("No compatible results on this page.");
            empty.setTextColor(Color.LTGRAY); empty.setTextSize(16); content.addView(empty);
        }
        List<AndroidModManager.Mod> installed = AndroidModManager.list(this);
        for (GameBananaClient.CatalogMod mod : mods) {
            boolean present = installed.stream().anyMatch(local -> local.remoteId == mod.id());
            String text = (mod.usesPatches() ? "PATCH  " : "") + mod.name()
                + (mod.author().isEmpty() ? "" : "\nBy " + mod.author())
                + (mod.version().isEmpty() ? "" : " · v" + mod.version())
                + (present ? "\nINSTALLED" : "");
            button(content, text, v -> loadCatalogueDetails(mod.id()));
        }
        if (page > 1) button(content, "Previous page", v -> loadCatalogue(search, page - 1));
        if (!mods.isEmpty()) button(content, "Next page", v -> loadCatalogue(search, page + 1));
        button(content, "New search", v -> showCatalogueSearch());
        new android.app.AlertDialog.Builder(this).setTitle("Online mods · page " + page)
            .setView(scroll).setNegativeButton("Close", null).show();
        refreshModsUi("Loaded online mod catalogue page " + page + ".");
    }

    private void loadCatalogueDetails(int id) {
        modStatus.setText("Loading mod details…");
        worker.execute(() -> {
            try {
                GameBananaClient.Details details = GameBananaClient.details(id);
                runOnUiThread(() -> { if (!stopped) showCatalogueDetails(details); });
            } catch (Exception error) {
                String result = "Could not load mod details: " + error.getMessage();
                runOnUiThread(() -> { if (!stopped) refreshModsUi(result); });
            }
        });
    }

    private void showCatalogueDetails(GameBananaClient.Details details) {
        ScrollView scroll = new ScrollView(this); LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL); int padding = dp(16);
        content.setPadding(padding, padding, padding, padding); scroll.addView(content);
        String summary = (details.author().isEmpty() ? "" : "By " + details.author() + "\n")
            + (details.version().isEmpty() ? "" : "Version " + details.version() + "\n")
            + details.downloads() + " downloads"
            + (details.description().isEmpty() ? "" : "\n\n" + details.description());
        TextView info = new TextView(this); info.setText(summary); info.setTextColor(Color.LTGRAY);
        info.setTextSize(15); content.addView(info);
        AndroidModManager.Mod installed = AndroidModManager.list(this).stream()
            .filter(mod -> mod.remoteId == details.id()).findFirst().orElse(null);
        boolean current = installed != null && (details.version().isEmpty() || details.version().equals(installed.remoteVersion));
        if (current) {
            TextView present = new TextView(this); present.setText("This catalogue version is already installed.");
            present.setTextColor(Color.rgb(134, 239, 172)); present.setPadding(0, dp(14), 0, 0); content.addView(present);
        }
        if (details.files().isEmpty()) {
            TextView none = new TextView(this); none.setText("No ZIP download is available. RAR and 7z files are not accepted by this Android importer.");
            none.setTextColor(Color.rgb(253, 186, 116)); none.setPadding(0, dp(14), 0, 0); content.addView(none);
        } else if (!current) for (GameBananaClient.ModFile file : details.files()) {
            String size = file.size() > 0 ? " · " + (file.size() / (1024 * 1024)) + " MiB" : "";
            button(content, (installed == null ? "Download and install " : "Download and update ") + file.name() + size,
                v -> downloadCatalogueMod(details, file, installed));
        }
        new android.app.AlertDialog.Builder(this).setTitle(details.name()).setView(scroll)
            .setNegativeButton("Close", null).show();
    }

    private void downloadCatalogueMod(GameBananaClient.Details details, GameBananaClient.ModFile remote,
                                      AndroidModManager.Mod previous) {
        modStatus.setText("Downloading " + details.name() + "…");
        worker.execute(() -> {
            File temporary = new File(getCacheDir(), "wheel-downloads/" + safeDownloadName(remote.name()));
            try {
                final long[] lastUpdate = {0};
                GameBananaClient.download(remote, temporary, (received, total) -> {
                    long now = android.os.SystemClock.elapsedRealtime(); if (now - lastUpdate[0] < 250) return true;
                    lastUpdate[0] = now; int percent = total > 0 ? (int)Math.min(received * 100 / total, 100) : -1;
                    String progress = percent >= 0 ? "Downloading " + details.name() + ": " + percent + "%"
                        : "Downloading " + details.name() + ": " + (received / (1024 * 1024)) + " MiB";
                    runOnUiThread(() -> { if (!stopped) modStatus.setText(progress); });
                    return true;
                });
                File external = getExternalFilesDir(null);
                if (external == null) throw new java.io.IOException("External app storage unavailable");
                AndroidModManager.ImportResult imported = AndroidModManager.importZip(this,
                    Uri.fromFile(temporary), new File(external, "game/disc/files"));
                AndroidModManager.attachRemoteMetadata(imported.mod, details.id(), details.version(), details.author());
                if (previous != null) AndroidModManager.replaceRemoteProfile(this, previous,
                    AndroidModManager.list(this).stream().filter(mod -> mod.id.equals(imported.mod.id)).findFirst()
                        .orElseThrow(() -> new java.io.IOException("Updated profile metadata could not be reopened")));
                String result = "Downloaded and " + (previous == null ? "installed " : "updated ")
                    + details.name() + ". Restart the game to apply it.";
                runOnUiThread(() -> { if (!stopped) refreshModsUi(result); });
            } catch (Exception error) {
                String result = "Online mod installation failed: " + error.getMessage();
                runOnUiThread(() -> { if (!stopped) refreshModsUi(result); });
            } finally { temporary.delete(); }
        });
    }

    private static String safeDownloadName(String name) {
        String safe = name == null ? "download.zip" : name.replaceAll("[^A-Za-z0-9._-]+", "-");
        return safe.toLowerCase(java.util.Locale.US).endsWith(".zip") ? safe : safe + ".zip";
    }

    private void checkRetroRewind() {
        retroRewindStatus.setText("Checking the Retro Rewind update service…");
        worker.execute(() -> {
            try {
                RetroRewindService.Status status = RetroRewindService.status(this);
                runOnUiThread(() -> { if (!stopped) showRetroRewindStatus(status); });
            } catch (Exception error) {
                android.util.Log.e("WiiCompiled", "Retro Rewind status check failed", error);
                String result = "Retro Rewind check failed: " + error.getMessage();
                runOnUiThread(() -> { if (!stopped) retroRewindStatus.setText(result); });
            }
        });
    }

    private void showRetroRewindStatus(RetroRewindService.Status status) {
        if (status.current()) {
            retroRewindStatus.setText("Retro Rewind " + status.installedVersion() + " is current."); return;
        }
        String action = status.installed() ? "Update" : "Install";
        String message = status.installed()
            ? "Installed: " + status.installedVersion() + "\nAvailable: " + status.latestVersion()
            : "Available version: " + status.latestVersion();
        retroRewindStatus.setText(message);
        new android.app.AlertDialog.Builder(this).setTitle(action + " Retro Rewind")
            .setMessage(message + "\n\nThe distribution contains PowerPC patches that may still need translated Android implementations.")
            .setNegativeButton("Cancel", null).setPositiveButton(action, (dialog, which) -> installRetroRewind()).show();
    }

    private void installRetroRewind() {
        Intent service = new Intent(this, RetroRewindInstallService.class)
            .setAction(RetroRewindInstallService.ACTION_START);
        startForegroundService(service);
        updateRetroUi("Preparing Retro Rewind…", 1, true);
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("report", report);
        state.putInt("selectedPage", selectedPage);
        super.onSaveInstanceState(state);
    }
    @Override protected void onDestroy() {
        stopped = true;
        worker.shutdown();
        super.onDestroy();
    }
    private static String readNotice(InputStream stream) throws java.io.IOException {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = stream.read(buffer)) != -1) bytes.write(buffer, 0, read);
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private TextView label(LinearLayout parent, String text, int size, int color) {
        TextView view = new TextView(this);
        view.setText(text); view.setTextSize(size); view.setTextColor(color);
        view.setPadding(0, dp(10), 0, dp(10));
        parent.addView(view); return view;
    }
    private Button button(LinearLayout parent, String text, View.OnClickListener click) {
        Button button = new Button(this);
        button.setText(text); button.setAllCaps(false); button.setOnClickListener(click);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(8); params.bottomMargin = dp(8);
        parent.addView(button, params); return button;
    }
}



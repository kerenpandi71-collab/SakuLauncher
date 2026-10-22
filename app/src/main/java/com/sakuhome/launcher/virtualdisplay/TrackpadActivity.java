/* Copyright 2026 Taskbar fork contributors
 * Licensed under the Apache License, Version 2.0.
 */
package com.sakuhome.launcher.virtualdisplay;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.provider.Settings;
import android.view.Display;
import android.view.WindowManager;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.ArrayDeque;

/**
 * First usable trackpad controller for the virtual display.
 * UI cursor movement is local; discrete input is sent to Android's target display.
 */
public class TrackpadActivity extends Activity {
    private EditText displayInput, widthInput, heightInput;
    private TextView connectionStatus, coordinateStatus;
    private TrackpadSurface pad;
    private final ExecutorService inputQueue = Executors.newSingleThreadExecutor();
    private volatile int targetDisplayId = -1;
    private int targetWidth = 1920, targetHeight = 1080;
    private WindowManager remoteCursorWindowManager;
    private RemoteCursorView remoteCursorView;
    private WindowManager.LayoutParams remoteCursorParams;
    private int remoteCursorDisplayId = -1;
    private float cursorX = 960, cursorY = 540;
    private long lastTapTime;
    private float tapStartX, tapStartY;
    private long lastMoveEventTime;
    private boolean moved;
    private final AtomicReference<String> pendingMouseMove = new AtomicReference<>();
    private final AtomicBoolean mouseMoveWorkerRunning = new AtomicBoolean(false);
    private volatile Process bridgeProcess;
    private volatile BufferedWriter bridgeWriter;
    private volatile boolean bridgeReady;
    private volatile boolean bridgeNotReadyWarningLogged;
    private final ArrayDeque<String> diagnosticLog = new ArrayDeque<>();
    private final Object logLock = new Object();
    private static final int MAX_LOG_LINES = 250;
    private int dp(int n) { return (int)(n * getResources().getDisplayMetrics().density + .5f); }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        targetDisplayId = getIntent().getIntExtra("display_id", -1);
        buildUi();
        displayInput.setText(targetDisplayId >= 0 ? Integer.toString(targetDisplayId) : "");
        widthInput.setText(Integer.toString(targetWidth));
        heightInput.setText(Integer.toString(targetHeight));
        updateCoordinates();
        detectAndAutoConnect();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(16), dp(18), dp(14));
        root.setBackgroundColor(0xFF000000);

        TextView title = text("TRACKPAD", 23, 0xFFFFFFFF);
        title.setTypeface(null, 1);
        root.addView(title);
        TextView subtitle = text("VIRTUAL DESKTOP  /  INPUT CONTROL", 12, 0xFF55D6FF);
        root.addView(subtitle);

        LinearLayout config = new LinearLayout(this);
        config.setGravity(Gravity.CENTER_VERTICAL);
        displayInput = field("Display ID");
        widthInput = field("Lebar");
        heightInput = field("Tinggi");
        config.addView(displayInput, new LinearLayout.LayoutParams(0, dp(48), 1.0f));
        config.addView(widthInput, new LinearLayout.LayoutParams(0, dp(48), 1.2f));
        config.addView(heightInput, new LinearLayout.LayoutParams(0, dp(48), 1.2f));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
        cp.topMargin = dp(12);
        root.addView(config, cp);

        Button connect = button("Sambungkan / Muat Ulang Display");
        connect.setOnClickListener(v -> connectTarget());
        root.addView(connect);

        connectionStatus = text("BELUM TERSAMBUNG", 12, 0xFFFFC857);
        root.addView(connectionStatus);

        Button logs = button("Lihat / Salin Log Error");
        logs.setOnClickListener(v -> showDiagnosticLog());
        root.addView(logs);

        pad = new TrackpadSurface();
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-1, 0, 1.0f);
        pp.topMargin = dp(12);
        pp.bottomMargin = dp(10);
        root.addView(pad, pp);

        coordinateStatus = text("X 960  •  Y 540", 12, 0xFFB9C8DE);
        root.addView(coordinateStatus);

        LinearLayout actions = new LinearLayout(this);
        actions.addView(button("Klik Kiri", v -> injectTap(false)), new LinearLayout.LayoutParams(0, dp(48), 1));
        actions.addView(button("Klik Kanan", v -> injectTap(true)), new LinearLayout.LayoutParams(0, dp(48), 1));
        actions.addView(button("Pusatkan Kursor", v -> resetCursor()), new LinearLayout.LayoutParams(0, dp(48), 1));
        root.addView(actions);
        TextView help = text("1 jari: gerakkan kursor  •  ketuk: klik  •  kanan: klik kanan", 11, 0xFFB7C7D9);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, -2);
        hp.topMargin = dp(8);
        root.addView(help, hp);
        setContentView(root);
    }

    private TextView text(String value, int size, int color) {
        TextView t = new TextView(this);
        t.setText(value); t.setTextSize(size); t.setTextColor(color);
        t.setGravity(Gravity.CENTER_VERTICAL);
        return t;
    }
    private EditText field(String hint) {
        EditText e = new EditText(this); e.setSingleLine(true); e.setHint(hint);
        e.setTextColor(0xFFF4F7FC); e.setHintTextColor(0xFF8799B4);
        e.setInputType( android.text.InputType.TYPE_CLASS_NUMBER);
        e.setPadding(dp(8), 0, dp(8), 0);
        return e;
    }
    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label.toUpperCase(java.util.Locale.ROOT));
        b.setTextColor(0xFF65E5FF);
        b.setTextSize(12);
        b.setAllCaps(false);
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF101820));
        b.setStateListAnimator(null);
        return b;
    }
    private Button button(String label, View.OnClickListener listener) {
        Button b = button(label); b.setOnClickListener(listener); return b;
    }
    private void detectAndAutoConnect() {
        connectionStatus.setText("Mendeteksi virtual display otomatis…");
        connectionStatus.setTextColor(0xFFFFD27D);
        inputQueue.execute(() -> {
            String dump = runRoot("dumpsys display");
            int detectedId = findOverlayDisplayId(dump);
            int[] size = detectedId >= 0 ? findDisplaySize(dump, detectedId) : null;
            runOnUiThread(() -> {
                if (detectedId < 0) {
                    connectionStatus.setText("Virtual display belum ditemukan. Aktifkan overlay lalu tekan Sambungkan.");
                    connectionStatus.setTextColor(0xFFFF9A9A);
                    addLog("WARN", "Auto-detect tidak menemukan overlay display aktif");
                    return;
                }
                targetDisplayId = detectedId;
                if (size != null) { targetWidth = size[0]; targetHeight = size[1]; }
                displayInput.setText(Integer.toString(targetDisplayId));
                widthInput.setText(Integer.toString(targetWidth));
                heightInput.setText(Integer.toString(targetHeight));
                addLog("INFO", "Auto-detect menemukan display " + targetDisplayId + " (" + targetWidth + "x" + targetHeight + ")");
                connectTarget();
            });
        });
    }

    private int findOverlayDisplayId(String dump) {
        if (dump == null) return -1;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("DisplayViewport\\{([^}]*)\\}").matcher(dump);
        while (m.find()) {
            String block = m.group(1);
            if (!block.contains("uniqueId='overlay:") && !block.contains("type=VIRTUAL")) continue;
            java.util.regex.Matcher id = java.util.regex.Pattern.compile("\\bdisplayId=(\\d+)").matcher(block);
            if (id.find()) {
                try { return Integer.parseInt(id.group(1)); } catch (NumberFormatException ignored) { }
            }
        }
        java.util.regex.Matcher info = java.util.regex.Pattern.compile("(?s)DisplayInfo\\{([^}]*)\\}").matcher(dump);
        while (info.find()) {
            String block = info.group(1);
            if (!block.contains("uniqueId='overlay:")) continue;
            java.util.regex.Matcher id = java.util.regex.Pattern.compile("\\bdisplayId=(\\d+)").matcher(block);
            if (id.find()) {
                try { return Integer.parseInt(id.group(1)); } catch (NumberFormatException ignored) { }
            }
        }
        return -1;
    }

    private void connectTarget() {
        addLog("INFO", "Tombol sambungkan ditekan");
        try {
            String idText = displayInput.getText().toString().trim();
            String widthText = widthInput.getText().toString().trim();
            String heightText = heightInput.getText().toString().trim();
            if (idText.isEmpty() || widthText.isEmpty() || heightText.isEmpty()) {
                detectAndAutoConnect();
                return;
            }
            int id = Integer.parseInt(idText);
            int w = Integer.parseInt(widthText);
            int h = Integer.parseInt(heightText);
            if (id < 0 || w < 320 || w > 3840 || h < 320 || h > 2160) throw new NumberFormatException();
            targetDisplayId = id; targetWidth = w; targetHeight = h;
            addLog("INFO", "Target display=" + id + ", ukuran input=" + w + "x" + h);
            cursorX = w / 2f; cursorY = h / 2f;
            connectionStatus.setText("Memeriksa display " + id + " dan menyiapkan input mouse…");
            connectionStatus.setTextColor(0xFFFFD27D);
            updateCoordinates();
            inputQueue.execute(() -> {
                String out = runRoot("dumpsys display");
                addLog("INFO", out == null ? "dumpsys display gagal / timeout" : "dumpsys display berhasil, panjang=" + out.length());
                int[] size = findDisplaySize(out, id);
                boolean exists = size != null || containsExactDisplayId(out, id);
                if (size != null) {
                    targetWidth = size[0];
                    targetHeight = size[1];
                    cursorX = targetWidth / 2f;
                    cursorY = targetHeight / 2f;
                }
                addLog("INFO", "Display ditemukan=" + exists + (size == null ? "" : ", ukuran aktual=" + size[0] + "x" + size[1]));
                if (exists) {
                    launchDesktopOnTargetDisplay(id);
                    collectInputFocusDiagnostics(id);
                    startInputBridge();
                }
                runOnUiThread(() -> {
                    updateCoordinates();
                    if (exists) attachRemoteCursor(id);
                    if (!exists) {
                        connectionStatus.setText("Display " + id + " tidak ditemukan. Periksa ID overlay aktif.");
                        connectionStatus.setTextColor(0xFFFF9A9A);
                    } else {
                        String dimensions = targetWidth + "×" + targetHeight;
                        connectionStatus.setText("Display " + id + " ditemukan (" + dimensions
                            + ") • menyiapkan bridge mouse root…");
                        connectionStatus.setTextColor(0xFFFFD27D);
                    }
                });
            });
        } catch (NumberFormatException e) {
            addLog("ERROR", "Konfigurasi display tidak valid: " + e);
            new AlertDialog.Builder(this).setTitle("Konfigurasi tidak valid")
                .setMessage("Masukkan Display ID, lebar, dan tinggi yang valid.")
                .setPositiveButton("OK", null).show();
        }
    }

    /**
     * A virtual display can exist while Android still shows only the system
     * SecondaryDisplayLauncher. In that state InputDispatcher reports NO_WINDOW
     * for the target display. Launch our fullscreen list launcher before taking
     * diagnostics so the target has a real focusable app window.
     */
    private void launchDesktopOnTargetDisplay(int displayId) {
        String component = getPackageName() + "/com.sakuhome.launcher.virtualdisplay.DesktopActivity";
        String command = "am start --display " + displayId + " --windowingMode 1 -f 0x10008000 -n "
            + component + " --ei display_id " + displayId;
        String output = runAsShell(command);
        if (output == null || output.contains("Error:") || output.contains("Exception")
                || output.contains("Permission Denial") || output.contains("does not exist")) {
            addLog("ERROR", "Desktop fullscreen gagal diluncurkan pada display " + displayId
                + ": " + (output == null ? "root command gagal/timeout" : output));
            return;
        }
        addLog("INFO", "Desktop fullscreen diminta pada display " + displayId + ": " + output);
        try {
            // Let ActivityTaskManager create the target window before reading focus state.
            Thread.sleep(350);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Records focused-window and input-dispatcher state to help explain why an
     * injected hover can be accepted without a visible pointer on the target display.
     */
    private void collectInputFocusDiagnostics(int displayId) {
        String input = runRoot("dumpsys input | grep -iE 'focused|displayId|pointer|hover|dispatch|mouse' | head -n 60 || true");
        if (input == null || input.isEmpty()) {
            addLog("WARN", "Diagnostik dumpsys input tidak menghasilkan baris terfilter");
        } else {
            addLog("INFO", "=== INPUT FOCUS / POINTER DIAGNOSTICS (display " + displayId + ") ===");
            for (String line : input.split("\\n")) addLog("INPUT_STATE", line.trim());
        }

        String windows = runRoot("dumpsys window displays | grep -iE 'displayId|focused|mCurrentFocus|mFocusedApp|Window #' | head -n 50 || true");
        if (windows == null || windows.isEmpty()) {
            addLog("WARN", "Diagnostik window focus tidak menghasilkan baris terfilter");
        } else {
            addLog("INFO", "=== WINDOW / DISPLAY FOCUS DIAGNOSTICS ===");
            for (String line : windows.split("\\n")) addLog("WINDOW_STATE", line.trim());
        }
    }

    private boolean containsExactDisplayId(String dump, int id) {
        if (dump == null) return false;
        String value = Integer.toString(id);
        return java.util.regex.Pattern.compile("(?i)\\bmDisplayId\\s*=\\s*" + value + "\\b").matcher(dump).find()
            || java.util.regex.Pattern.compile("(?i)\\bdisplayId\\s*[=: ]\\s*" + value + "\\b").matcher(dump).find()
            || java.util.regex.Pattern.compile("(?i)\\bDisplay\\s+" + value + "\\s*:").matcher(dump).find();
    }

    private int[] findDisplaySize(String dump, int id) {
        if (dump == null) return null;
        java.util.regex.Matcher blocks = java.util.regex.Pattern
            .compile("DisplayViewport\\{([^}]*)\\}").matcher(dump);
        while (blocks.find()) {
            String block = blocks.group(1);
            java.util.regex.Matcher idMatcher = java.util.regex.Pattern
                .compile("(?i)\\bdisplayId\\s*[=: ]\\s*(\\d+)").matcher(block);
            if (!idMatcher.find() || Integer.parseInt(idMatcher.group(1)) != id) continue;
            java.util.regex.Matcher w = java.util.regex.Pattern.compile("\\bdeviceWidth=(\\d+)").matcher(block);
            java.util.regex.Matcher h = java.util.regex.Pattern.compile("\\bdeviceHeight=(\\d+)").matcher(block);
            if (w.find() && h.find()) {
                int[] size = validatedSize(w.group(1), h.group(1));
                if (size != null) return size;
            }
        }

        // Overlay displays usually have no touch DisplayViewport; use their DisplayInfo block.
        java.util.regex.Matcher infos = java.util.regex.Pattern
            .compile("DisplayInfo\\{([^}]*)\\}").matcher(dump);
        while (infos.find()) {
            String block = infos.group(1);
            java.util.regex.Matcher idMatcher = java.util.regex.Pattern
                .compile("(?i)\\bdisplayId\\s*[=: ]\\s*" + id + "\\b").matcher(block);
            if (!idMatcher.find()) continue;
            java.util.regex.Matcher size = java.util.regex.Pattern
                .compile("(?i)\\b(?:real|logical)\\s+(\\d+)\\s*x\\s*(\\d+)").matcher(block);
            if (size.find()) {
                int[] result = validatedSize(size.group(1), size.group(2));
                if (result != null) return result;
            }
        }
        return null;
    }

    private int[] validatedSize(String widthText, String heightText) {
        try {
            int width = Integer.parseInt(widthText);
            int height = Integer.parseInt(heightText);
            if (width >= 320 && width <= 3840 && height >= 320 && height <= 2160) {
                return new int[] { width, height };
            }
        } catch (NumberFormatException ignored) { }
        return null;
    }

    private String runAsShell(String command) {
        Process process = null;
        try {
            process = new ProcessBuilder("su", "2000", "-c", command).redirectErrorStream(true).start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null && output.length() < 6000) {
                    output.append(line).append('\n');
                }
            }
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return "Perintah shell timeout";
            }
            return output.toString().trim();
        } catch (Exception e) {
            return e.getClass().getSimpleName() + ": " + e.getMessage();
        } finally {
            if (process != null) process.destroy();
        }
    }

    private void startInputBridge() {
        if (bridgeProcess != null && bridgeProcess.isAlive() && bridgeWriter != null) {
            addLog("INFO", "Bridge sudah berjalan; menggunakan proses yang ada");
            return;
        }
        addLog("INFO", "Menjalankan root input bridge via su/app_process");
        try {
            String packageName = getPackageName();
            String shell = "APK=$(pm path " + packageName
                + " | head -n 1 | cut -d: -f2); "
                + "if [ -z \"$APK\" ]; then echo NO_APK_PATH; exit 20; fi; "
                + "CLASSPATH=\"$APK\" app_process /system/bin "
                + "com.sakuhome.launcher.virtualdisplay.RootInputBridge";
            Process process = new ProcessBuilder("su", "-c", shell).redirectErrorStream(true).start();
            bridgeProcess = process;
            bridgeWriter = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()));
            bridgeReady = false;
            Thread readerThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        final String outputLine = line;
                        addLog(outputLine.startsWith("BRIDGE_ERROR") || outputLine.startsWith("INPUT_ERROR") || outputLine.startsWith("NO_APK_PATH") ? "ERROR" : "BRIDGE", outputLine);
                        if ("READY".equals(outputLine)) {
                            bridgeReady = true;
                            bridgeNotReadyWarningLogged = false;
                            addLog("INFO", "Bridge mengirim READY");
                            runOnUiThread(() -> {
                                if (connectionStatus != null) {
                                    connectionStatus.setText("Display " + targetDisplayId + " • mouse bridge aktif");
                                    connectionStatus.setTextColor(0xFF8BE0B0);
                                }
                            });
                        } else if (outputLine.startsWith("BRIDGE_ERROR") || outputLine.startsWith("NO_APK_PATH")) {
                            bridgeReady = false;
                            runOnUiThread(() -> {
                                if (connectionStatus != null) {
                                    connectionStatus.setText("Input bridge gagal: " + outputLine);
                                    connectionStatus.setTextColor(0xFFFF9A9A);
                                }
                            });
                        } else if (outputLine.startsWith("INPUT_ERROR")) {
                            // A rejected event does not mean the bridge process/pipe died.
                            // Keep READY so subsequent movement can still be tested.
                            addLog("WARN", "Bridge masih hidup, tetapi event ditolak: " + outputLine);
                            runOnUiThread(() -> {
                                if (connectionStatus != null) {
                                    connectionStatus.setText("Bridge aktif, event input ditolak — lihat log");
                                    connectionStatus.setTextColor(0xFFFF9A9A);
                                }
                            });
                        }
                    }
                } catch (Exception ignored) { }
                finally {
                    addLog("ERROR", "Output bridge berhenti; exit=" + safeExitValue(process));
                    bridgeReady = false;
                    if (bridgeProcess == process) bridgeProcess = null;
                }
            }, "root-input-bridge-output");
            readerThread.setDaemon(true);
            readerThread.start();
        } catch (Exception e) {
            addLog("ERROR", "Gagal memulai input bridge: " + describeThrowable(e));
            bridgeReady = false;
            runOnUiThread(() -> {
                if (connectionStatus != null) {
                    connectionStatus.setText("Gagal memulai input bridge: " + e.getMessage());
                    connectionStatus.setTextColor(0xFFFF9A9A);
                }
            });
        }
    }

    private boolean sendBridgeCommand(String command) {
        BufferedWriter writer = bridgeWriter;
        if (!bridgeReady || writer == null) {
            if (!bridgeNotReadyWarningLogged) {
                bridgeNotReadyWarningLogged = true;
                addLog("WARN", "Perintah input ditahan: bridge belum READY; contoh=" + command);
            }
            return false;
        }
        try {
            synchronized (writer) {
                writer.write(command);
                writer.newLine();
                writer.flush();
            }
            return true;
        } catch (Exception e) {
            addLog("ERROR", "Gagal mengirim perintah bridge: " + describeThrowable(e));
            bridgeReady = false;
            return false;
        }
    }

    private void resetCursor() { cursorX = targetWidth / 2f; cursorY = targetHeight / 2f; updateCoordinates(); pad.invalidate(); }
    private void updateCoordinates() {
        if (coordinateStatus != null) coordinateStatus.setText("X " + (int)cursorX + "  •  Y " + (int)cursorY);
        if (pad != null) pad.invalidate();
        updateRemoteCursorPosition();
    }

    /**
     * Draw a software cursor on the target display. Injected MotionEvents can be
     * accepted by InputManager without Android creating a hardware mouse pointer
     * controller, so the trackpad must not rely on the phone-side dot as its cursor.
     */
    private void attachRemoteCursor(int displayId) {
        if (remoteCursorDisplayId == displayId && remoteCursorView != null) {
            updateRemoteCursorPosition();
            return;
        }
        removeRemoteCursor();
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            addLog("WARN", "Kursor virtual tidak dibuat: izin tampil di atas aplikasi (SYSTEM_ALERT_WINDOW) belum diberikan.");
            return;
        }
        try {
            DisplayManager manager = (DisplayManager) getSystemService(DISPLAY_SERVICE);
            Display display = manager == null ? null : manager.getDisplay(displayId);
            if (display == null) {
                addLog("WARN", "Kursor virtual gagal dibuat: display " + displayId + " tidak ditemukan oleh DisplayManager.");
                return;
            }
            android.content.Context displayContext = createDisplayContext(display);
            android.content.Context windowContext = Build.VERSION.SDK_INT >= 30
                ? displayContext.createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
                : displayContext;
            remoteCursorWindowManager = (WindowManager) windowContext.getSystemService(WINDOW_SERVICE);
            if (remoteCursorWindowManager == null) throw new IllegalStateException("WindowManager target display null");
            // One non-touchable full-display overlay avoids WindowManager relayout on
            // every touch sample. Only the cursor is redrawn at the next display frame.
            remoteCursorView = new RemoteCursorView(windowContext);
            remoteCursorParams = new WindowManager.LayoutParams(
                targetWidth, targetHeight,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
            remoteCursorParams.gravity = Gravity.TOP | Gravity.LEFT;
            remoteCursorParams.x = 0;
            remoteCursorParams.y = 0;
            remoteCursorParams.setTitle("Taskbar virtual-display cursor");
            remoteCursorWindowManager.addView(remoteCursorView, remoteCursorParams);
            remoteCursorDisplayId = displayId;
            updateRemoteCursorPosition();
            addLog("INFO", "Kursor software dibuat pada display " + displayId + " (overlay non-interaktif).");
        } catch (Throwable e) {
            addLog("ERROR", "Gagal membuat kursor pada display target: " + describeThrowable(e));
            removeRemoteCursor();
        }
    }

    private void updateRemoteCursorPosition() {
        if (remoteCursorWindowManager == null || remoteCursorView == null
                || remoteCursorParams == null || remoteCursorDisplayId != targetDisplayId) return;
        remoteCursorView.setCursorPosition(
            Math.max(0, Math.min(targetWidth - 1, cursorX)),
            Math.max(0, Math.min(targetHeight - 1, cursorY)));
    }

    private void removeRemoteCursor() {
        if (remoteCursorWindowManager != null && remoteCursorView != null) {
            try { remoteCursorWindowManager.removeView(remoteCursorView); }
            catch (Throwable ignored) { }
        }
        remoteCursorWindowManager = null;
        remoteCursorView = null;
        remoteCursorParams = null;
        remoteCursorDisplayId = -1;
    }

    private final class RemoteCursorView extends View {
        private final Paint cursorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float pointerX, pointerY;
        RemoteCursorView(android.content.Context context) {
            super(context);
            setLayerType(View.LAYER_TYPE_HARDWARE, null);
            setWillNotDraw(false);
        }
        void setCursorPosition(float x, float y) {
            pointerX = x;
            pointerY = y;
            // Coalesce many touch samples into one hardware-accelerated frame.
            postInvalidateOnAnimation();
        }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float d = getResources().getDisplayMetrics().density;
            Path arrow = new Path();
            arrow.moveTo(pointerX, pointerY);
            arrow.lineTo(pointerX, pointerY + 22 * d);
            arrow.lineTo(pointerX + 5 * d, pointerY + 17 * d);
            arrow.lineTo(pointerX + 10 * d, pointerY + 27 * d);
            arrow.lineTo(pointerX + 14 * d, pointerY + 25 * d);
            arrow.lineTo(pointerX + 10 * d, pointerY + 15 * d);
            arrow.lineTo(pointerX + 19 * d, pointerY + 15 * d);
            arrow.close();
            cursorPaint.setColor(0xFF0B1220);
            cursorPaint.setStyle(Paint.Style.STROKE);
            cursorPaint.setStrokeWidth(3 * d);
            canvas.drawPath(arrow, cursorPaint);
            cursorPaint.setColor(0xFFF5F8FF);
            cursorPaint.setStyle(Paint.Style.FILL);
            canvas.drawPath(arrow, cursorPaint);
        }
    }
    private void injectTap(boolean right) {
        if (targetDisplayId < 0) {
            Toast.makeText(this, "Pilih target display dulu.", Toast.LENGTH_SHORT).show();
            return;
        }
        int x = Math.max(0, Math.min(targetWidth - 1, Math.round(cursorX)));
        int y = Math.max(0, Math.min(targetHeight - 1, Math.round(cursorY)));
        String button = right ? "RIGHT" : "LEFT";
        inputQueue.execute(() -> {
            boolean sent = sendBridgeCommand("CLICK " + targetDisplayId + " " + x + " " + y + " " + button);
            runOnUiThread(() -> {
                if (!sent) Toast.makeText(this,
                    "Mouse bridge belum siap. Tekan Sambungkan / Muat Ulang Display.",
                    Toast.LENGTH_SHORT).show();
            });
        });
    }

    /** Send mouse-pointer movement to the target display, coalescing stale samples. */
    private void queueMouseMove() {
        int displayId = targetDisplayId;
        int x = Math.max(0, Math.min(targetWidth - 1, Math.round(cursorX)));
        int y = Math.max(0, Math.min(targetHeight - 1, Math.round(cursorY)));
        if (displayId < 0) return;
        pendingMouseMove.set("MOVE " + displayId + " " + x + " " + y);
        if (!mouseMoveWorkerRunning.compareAndSet(false, true)) return;
        try {
            inputQueue.execute(() -> {
                try {
                    String command;
                    while ((command = pendingMouseMove.getAndSet(null)) != null) sendBridgeCommand(command);
                } finally {
                    mouseMoveWorkerRunning.set(false);
                    if (pendingMouseMove.get() != null) queueMouseMove();
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            mouseMoveWorkerRunning.set(false);
        }
    }

    private String runRoot(String command) {
        Process p = null;
        addLog("INFO", "Menjalankan perintah root: " + command);
        try {
            p = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line; while ((line = r.readLine()) != null && out.length() < 4000) out.append(line).append('\n');
            }
            if (!p.waitFor(3, TimeUnit.SECONDS)) { addLog("ERROR", "Perintah root timeout: " + command); p.destroyForcibly(); return null; }
            addLog(p.exitValue() == 0 ? "INFO" : "ERROR", "Perintah root exit=" + p.exitValue());
            return p.exitValue() == 0 ? out.toString().trim() : null;
        } catch (Exception e) { addLog("ERROR", "Perintah root exception: " + describeThrowable(e)); return null; }
        finally { if (p != null) p.destroy(); }
    }

    private int safeExitValue(Process process) {
        try { return process.exitValue(); } catch (IllegalThreadStateException running) { return -1; }
    }

    private String describeThrowable(Throwable error) {
        StringBuilder result = new StringBuilder();
        Throwable current = error;
        int depth = 0;
        while (current != null && depth++ < 8) {
            if (result.length() > 0) result.append(" <- ");
            result.append(current.getClass().getSimpleName());
            String message = current.getMessage();
            if (message != null && !message.trim().isEmpty()) result.append(": ").append(message);
            current = current.getCause();
        }
        return result.toString();
    }

    private void addLog(String level, String message) {
        String entry = java.text.DateFormat.getTimeInstance(java.text.DateFormat.MEDIUM).format(new java.util.Date())
            + " [" + level + "] " + message;
        synchronized (logLock) {
            diagnosticLog.addLast(entry);
            while (diagnosticLog.size() > MAX_LOG_LINES) diagnosticLog.removeFirst();
        }
    }

    private String getDiagnosticText() {
        StringBuilder text = new StringBuilder();
        synchronized (logLock) {
            for (String line : diagnosticLog) text.append(line).append('\n');
        }
        if (text.length() == 0) text.append("Belum ada log. Tekan Sambungkan / Muat Ulang Display terlebih dahulu.\n");
        return text.toString();
    }

    private void showDiagnosticLog() {
        String content = getDiagnosticText();
        TextView logView = new TextView(this);
        logView.setText(content);
        logView.setTextIsSelectable(true);
        logView.setTextSize(12);
        logView.setPadding(dp(16), dp(12), dp(16), dp(12));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(logView);
        new AlertDialog.Builder(this)
            .setTitle("Log diagnostik trackpad")
            .setView(scroll)
            .setPositiveButton("Salin Log", (dialog, which) -> {
                ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboard != null) {
                    clipboard.setPrimaryClip(ClipData.newPlainText("Taskbar Trackpad Diagnostics", getDiagnosticText()));
                    Toast.makeText(this, "Log disalin. Tempelkan langsung ke chat.", Toast.LENGTH_LONG).show();
                }
            })
            .setNegativeButton("Tutup", null)
            .setNeutralButton("Salin Log Lengkap", (dialog, which) -> {
                ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboard != null) {
                    clipboard.setPrimaryClip(ClipData.newPlainText("Taskbar Trackpad Diagnostics", getDiagnosticText()));
                    Toast.makeText(this, "Log disalin.", Toast.LENGTH_SHORT).show();
                }
            })
            .show();
    }

    @Override protected void onDestroy() {
        removeRemoteCursor();
        super.onDestroy();
        try {
            BufferedWriter writer = bridgeWriter;
            if (writer != null) {
                synchronized (writer) {
                    writer.write("QUIT");
                    writer.newLine();
                    writer.flush();
                    writer.close();
                }
            }
        } catch (Exception ignored) { }
        Process process = bridgeProcess;
        if (process != null) process.destroy();
        bridgeReady = false;
        inputQueue.shutdownNow();
    }

    private final class TrackpadSurface extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float lastX, lastY;
        private float startX, startY;
        private boolean gestureMoved;
        TrackpadSurface() { super(TrackpadActivity.this); setBackgroundColor(0xFF000000); setLayerType(View.LAYER_TYPE_HARDWARE, null); }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float inset = dp(12);
            paint.setColor(0xFF123847); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(1));
            canvas.drawRoundRect(new RectF(inset, inset, getWidth()-inset, getHeight()-inset), dp(18), dp(18), paint);
            paint.setStyle(Paint.Style.FILL); paint.setColor(0xFF5CE1FF);
            float cx = inset + (cursorX / Math.max(1, targetWidth)) * (getWidth()-2*inset);
            float cy = inset + (cursorY / Math.max(1, targetHeight)) * (getHeight()-2*inset);
            canvas.drawCircle(cx, cy, dp(7), paint);
            paint.setColor(0x665CE1FF); canvas.drawCircle(cx, cy, dp(18), paint);
            paint.setColor(0xFF55D6FF); paint.setTextSize(dp(12)); paint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText("AREA TRACKPAD", getWidth()/2f, getHeight()/2f, paint);
        }
        @Override public boolean onTouchEvent(MotionEvent event) {
            float x = event.getX(), y = event.getY();
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    lastX = startX = x; lastY = startY = y; lastMoveEventTime = event.getEventTime(); gestureMoved = false; return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = x-lastX, dy = y-lastY;
                    if (Math.abs(x-startX)+Math.abs(y-startY) > dp(8)) gestureMoved = true;
                    long eventTime = event.getEventTime();
                    long previousTime = lastMoveEventTime;
                    float dt = previousTime > 0 ? Math.max(1f, Math.min(40f, eventTime - previousTime)) : 16f;
                    lastMoveEventTime = eventTime;
                    float scaleX = targetWidth / (float)Math.max(1, getWidth());
                    float scaleY = targetHeight / (float)Math.max(1, getHeight());
                    // Relative touchpad motion with velocity-based acceleration: slow
                    // movement stays precise, fast swipes cross the desktop quicker.
                    float speed = (float)Math.sqrt(dx * dx + dy * dy) / dt;
                    float acceleration = 1.0f + Math.min(0.85f, speed / 3.5f);
                    cursorX = Math.max(0, Math.min(targetWidth-1, cursorX + dx*scaleX*acceleration));
                    cursorY = Math.max(0, Math.min(targetHeight-1, cursorY + dy*scaleY*acceleration));
                    lastX=x; lastY=y; updateCoordinates(); queueMouseMove(); return true;
                case MotionEvent.ACTION_UP:
                    if (!gestureMoved) {
                        long now = android.os.SystemClock.uptimeMillis();
                        if (now-lastTapTime < 320) { injectTap(false); lastTapTime=0; }
                        else { injectTap(false); lastTapTime=now; }
                    }
                    performClick(); return true;
                case MotionEvent.ACTION_CANCEL: return true;
            }
            return true;
        }
        @Override public boolean performClick() { super.performClick(); return true; }
    }
}

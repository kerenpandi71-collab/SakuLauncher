/* Copyright 2026 Taskbar fork contributors
 * Licensed under the Apache License, Version 2.0.
 */
package com.sakuhome.launcher.virtualdisplay;

import android.app.Activity;
import android.content.Intent;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

public class VirtualDisplayActivity extends Activity {
    private EditText widthInput, heightInput, dpiInput;
    private TextView status;
    private SharedPreferences prefs;
    private static final String PREFS = "virtual_display_manager";
    private static final String ORIGINAL = "original_overlay_setting";
    private static final String SAVED = "has_saved_original";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        buildUi();
        refreshStatus();
    }

    private void buildUi() {
        int pad = dp(20);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(0xFF101827);
        TextView title = new TextView(this);
        title.setText("Virtual Display Manager");
        title.setTextColor(0xFFF3F6FC);
        title.setTextSize(24);
        title.setTypeface(null, 1);
        root.addView(title);
        TextView subtitle = new TextView(this);
        subtitle.setText("Atur resolusi dan DPI overlay display Android.\nDukungan bergantung pada ROM.");
        subtitle.setTextColor(0xFFB7C3D8);
        subtitle.setTextSize(14);
        LinearLayout.LayoutParams subParams = new LinearLayout.LayoutParams(-1, -2);
        subParams.topMargin = dp(8);
        root.addView(subtitle, subParams);
        root.addView(label("Resolusi lebar (px)"));
        widthInput = field("1920");
        root.addView(widthInput);
        root.addView(label("Resolusi tinggi (px)"));
        heightInput = field("1080");
        root.addView(heightInput);
        root.addView(label("DPI"));
        dpiInput = field("240");
        root.addView(dpiInput);
        LinearLayout presets = new LinearLayout(this);
        presets.setOrientation(LinearLayout.HORIZONTAL);
        presets.addView(button("900×900", v -> setValues(900, 900, 240)), weight());
        presets.addView(button("800×800", v -> setValues(800, 800, 240)), weight());
        presets.addView(button("Custom", v -> {
            widthInput.requestFocus();
            Toast.makeText(this, "Atur lebar, tinggi, dan DPI secara manual.", Toast.LENGTH_SHORT).show();
        }), weight());
        LinearLayout.LayoutParams presetParams = new LinearLayout.LayoutParams(-1, -2);
        presetParams.topMargin = dp(12);
        root.addView(presets, presetParams);
        root.addView(button("TERAPKAN KONFIGURASI", v -> applyDisplay()));
        root.addView(button("BACA STATUS KONFIGURASI", v -> refreshStatus()));
        root.addView(button("BUKA SAKU DESKTOP LAUNCHER", v -> openDesktopOnOverlay()));
        root.addView(button("BUKA TRACKPAD UNTUK DISPLAY VIRTUAL", v -> openTrackpad()));
        root.addView(button("PULIHKAN KONFIGURASI SEBELUMNYA", v -> restorePrevious()));
        root.addView(button("HAPUS OVERLAY DISPLAY VIRTUAL", v -> removeOverlayDisplay()));
        status = new TextView(this);
        status.setTextColor(0xFFE0E7F2);
        status.setTextSize(13);
        status.setTextIsSelectable(true);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-1, -2);
        statusParams.topMargin = dp(14);
        root.addView(status, statusParams);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(root);
        setContentView(scroll);
    }

    private TextView label(String text) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextColor(0xFFD5DEED);
        v.setTextSize(14);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(14);
        v.setLayoutParams(p);
        return v;
    }

    private EditText field(String value) {
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setText(value);
        e.setTextColor(0xFFF3F6FC);
        e.setHintTextColor(0xFF9AA8BC);
        e.setPadding(dp(12), dp(6), dp(12), dp(6));
        e.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF8EA4C5));
        return e;
    }

    private Button button(String text, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(text);
        b.setOnClickListener(listener);
        return b;
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1);
        p.setMargins(dp(2), 0, dp(2), 0);
        return p;
    }

    private void setValues(int width, int height, int dpi) {
        widthInput.setText(Integer.toString(width));
        heightInput.setText(Integer.toString(height));
        dpiInput.setText(Integer.toString(dpi));
    }

    private void applyDisplay() {
        final int width, height, dpi;
        try {
            width = Integer.parseInt(widthInput.getText().toString().trim());
            height = Integer.parseInt(heightInput.getText().toString().trim());
            dpi = Integer.parseInt(dpiInput.getText().toString().trim());
        } catch (NumberFormatException e) {
            showError("Isi lebar, tinggi, dan DPI dengan angka yang valid.");
            return;
        }
        if (width < 320 || width > 3840 || height < 320 || height > 2160 || dpi < 120 || dpi > 640) {
            showError("Batas: lebar 320–3840, tinggi 320–2160, DPI 120–640.");
            return;
        }
        new AlertDialog.Builder(this)
            .setTitle("Terapkan overlay display?")
            .setMessage(width + "×" + height + " / " + dpi + " DPI\n\nIni mengubah konfigurasi overlay display Android, bukan resolusi layar fisik.")
            .setNegativeButton("Batal", null)
            .setPositiveButton("Terapkan", (dialog, which) -> {
                String current = runRoot("settings get global overlay_display_devices");
                if (current == null) {
                    showError("Tidak bisa membaca konfigurasi. Pastikan Magisk memberikan akses root.");
                    return;
                }
                if (!prefs.getBoolean(SAVED, false)) {
                    prefs.edit().putString(ORIGINAL, "null".equals(current.trim()) ? "" : current.trim())
                        .putBoolean(SAVED, true).apply();
                }
                String value = width + "x" + height + "/" + dpi;
                String result = runRoot("settings put global overlay_display_devices " + value
                    + " && settings get global overlay_display_devices");
                if (result == null || !result.trim().endsWith(value)) {
                    showError("Konfigurasi belum terverifikasi. Output: " + (result == null ? "(tidak ada)" : result));
                    return;
                }
                status.setText("Nilai setting terbaca kembali: " + result.trim()
                    + "\n\nIni belum membuktikan overlay display aktif. Periksa daftar display Android dan uji pada ROM ini.");
                Toast.makeText(this, "Konfigurasi overlay tersimpan", Toast.LENGTH_LONG).show();
            }).show();
    }

    private void refreshStatus() {
        status.setText("Membaca konfigurasi root…");
        new Thread(() -> {
            String result = runRoot("settings get global overlay_display_devices; dumpsys display");
            runOnUiThread(() -> {
                if (result == null) {
                    status.setText("Gagal membaca status. Pastikan akses root tersedia.");
                    return;
                }
                String[] lines = result.split("\\r?\\n");
                StringBuilder summary = new StringBuilder("Konfigurasi overlay_display_devices: ")
                    .append(lines.length > 0 ? lines[0] : "tidak diketahui")
                    .append("\n\nStatus display: baris terkait dari dumpsys display ditampilkan di bawah.");
                for (String line : lines) {
                    if (line.contains("OverlayDisplay") || line.contains("overlay") || line.contains("mDisplayId")) {
                        if (summary.length() < 5000) summary.append("\n").append(line.trim());
                    }
                }
                status.setText(summary.toString());
            });
        }).start();
    }



    private void openTrackpad() {
        status.setText("Mencari overlay display untuk trackpad…");
        new Thread(() -> {
            String dump = runRoot("dumpsys display");
            int displayId = findOverlayDisplayId(dump);
            runOnUiThread(() -> {
                Intent intent = new Intent(this, TrackpadActivity.class);
                if (displayId >= 0) {
                    intent.putExtra("display_id", displayId);
                } else {
                    intent.putExtra("display_id", -1);
                }
                startActivity(intent);
                if (displayId >= 0) {
                    Toast.makeText(this, "Trackpad diarahkan ke overlay display " + displayId,
                        Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(this, "Trackpad dibuka. Masukkan Display ID overlay secara manual.",
                        Toast.LENGTH_LONG).show();
                }
            });
        }, "open-trackpad").start();
    }

    private void openDesktopOnOverlay() {
        status.setText("Mencari overlay display aktif…");
        new Thread(() -> {
            String dump = runRoot("dumpsys display");
            int displayId = findOverlayDisplayId(dump);
            if (displayId < 0) {
                runOnUiThread(() -> {
                    showError("Overlay display tidak ditemukan. Baca status konfigurasi dan pastikan ada blok DisplayViewport dengan uniqueId overlay.");
                    refreshStatus();
                });
                return;
            }

            String component = getPackageName() + "/com.sakuhome.launcher.virtualdisplay.DesktopActivity";
            String command = "am start --display " + displayId + " --windowingMode 1 -f 0x10008000 -n " + component
                + " --ei display_id " + displayId;
            RootResult launch = runAsShellDetailed(command);
            String output = launch.output;
            boolean failed = launch.timedOut || launch.exitCode != 0
                || output.contains("Error:") || output.contains("Exception")
                || output.contains("does not exist") || output.contains("Permission Denial");
            runOnUiThread(() -> {
                if (failed) {
                    showError("Gagal membuka Desktop pada overlay display " + displayId
                        + ". Exit code: " + launch.exitCode
                        + (launch.timedOut ? " (timeout)" : "")
                        + "\nPerintah: " + command
                        + "\nOutput: " + (output.isEmpty() ? "(kosong)" : output));
                    status.setText("Overlay ditemukan: display " + displayId
                        + "\nPerintah peluncuran gagal. Detail tersedia pada pesan error.");
                    return;
                }
                status.setText("Permintaan peluncuran Desktop dikirim. Target overlay display: "
                    + displayId + "\n" + output);
                Toast.makeText(this, "Membuka desktop di display " + displayId, Toast.LENGTH_LONG).show();
            });
        }, "launch-desktop-overlay").start();
    }

    private int findOverlayDisplayId(String dump) {
        if (dump == null || dump.isEmpty()) return -1;
        // Virtual displays are logical displays and commonly do not have a DisplayViewport.
        // Parse each logical-display block and match its overlay name/unique ID instead.
        java.util.regex.Matcher headers = java.util.regex.Pattern
            .compile("(?m)^\\s*Display\\s+(\\d+)\\s*:").matcher(dump);
        java.util.ArrayList<Integer> starts = new java.util.ArrayList<>();
        java.util.ArrayList<Integer> ids = new java.util.ArrayList<>();
        while (headers.find()) {
            try {
                ids.add(Integer.parseInt(headers.group(1)));
                starts.add(headers.start());
            } catch (NumberFormatException ignored) { }
        }
        for (int i = 0; i < starts.size(); i++) {
            int end = i + 1 < starts.size() ? starts.get(i + 1) : dump.length();
            String block = dump.substring(starts.get(i), end);
            String lower = block.toLowerCase(java.util.Locale.ROOT);
            if (!lower.contains("overlay:") && !lower.contains("overlay #")
                && !lower.contains("overlay display")) continue;
            java.util.regex.Matcher idMatcher = java.util.regex.Pattern
                .compile("(?i)\\bmDisplayId\\s*=\\s*(\\d+)").matcher(block);
            if (idMatcher.find()) {
                try { return Integer.parseInt(idMatcher.group(1)); }
                catch (NumberFormatException ignored) { }
            }
            return ids.get(i);
        }

        // Fallback for ROMs that print a single-line DisplayInfo record.
        java.util.regex.Matcher info = java.util.regex.Pattern
            .compile("(?i)DisplayInfo\\{[^}]*overlay[^}]*displayId\\s*[=: ]\\s*(\\d+)[^}]*\\}")
            .matcher(dump);
        if (info.find()) {
            try { return Integer.parseInt(info.group(1)); }
            catch (NumberFormatException ignored) { }
        }

        // Reliable fallback: Android reports overlay IDs in DisplayViewport on this ROM.
        java.util.regex.Matcher viewport = java.util.regex.Pattern
            .compile("DisplayViewport\\{([^}]*)\\}").matcher(dump);
        while (viewport.find()) {
            String block = viewport.group(1);
            if (!block.contains("uniqueId='overlay:") && !block.contains("type=VIRTUAL")) continue;
            java.util.regex.Matcher id = java.util.regex.Pattern
                .compile("(?i)\\bdisplayId\\s*=\\s*(\\d+)").matcher(block);
            if (id.find()) {
                try { return Integer.parseInt(id.group(1)); }
                catch (NumberFormatException ignored) { }
            }
        }
        return -1;
    }

    private void restorePrevious() {
        if (!prefs.getBoolean(SAVED, false)) {
            showError("Belum ada konfigurasi sebelumnya yang tersimpan oleh aplikasi.");
            return;
        }
        String original = prefs.getString(ORIGINAL, "");
        // The value may include named overlay flags (for example own_content_only or
        // show_system_decorations). Restrict it to a conservative grammar and quote it
        // as one shell argument instead of rejecting legitimate saved configurations.
        if (!original.matches("[A-Za-z0-9_xX/,:;= .+\\-]*")) {
            showError("Nilai konfigurasi sebelumnya mengandung karakter yang tidak didukung. Nilai tetap disimpan dan tidak dijalankan.");
            return;
        }
        String command = original.isEmpty()
            ? "settings delete global overlay_display_devices; settings get global overlay_display_devices"
            : "settings put global overlay_display_devices " + shellQuote(original)
                + " && settings get global overlay_display_devices";
        String result = runRoot(command);
        if (result == null) {
            showError("Gagal memulihkan konfigurasi. Periksa akses root. Nilai lama tetap disimpan agar bisa dicoba lagi.");
            return;
        }
        String restored = result.trim();
        boolean restoredAsExpected = original.isEmpty()
            ? restored.isEmpty() || "null".equalsIgnoreCase(restored)
            : original.equals(restored);
        if (!restoredAsExpected) {
            showError("Android belum mengembalikan nilai yang diharapkan. Nilai terbaca: "
                + (restored.isEmpty() ? "(kosong)" : restored)
                + "\nCadangan tetap disimpan agar pemulihan bisa dicoba lagi.");
            return;
        }
        prefs.edit().remove(ORIGINAL).putBoolean(SAVED, false).apply();
        status.setText("Konfigurasi sebelumnya berhasil diverifikasi: "
            + (restored.isEmpty() ? "(default / kosong)" : restored)
            + "\nPeriksa ulang daftar display karena dukungan overlay berbeda antar-ROM.");
    }

    private void removeOverlayDisplay() {
        new AlertDialog.Builder(this)
            .setTitle("Hapus overlay display virtual?")
            .setMessage("Ini menghapus konfigurasi overlay_display_devices Android dan menutup display virtual yang berasal dari konfigurasi itu. Layar fisik tidak diubah. Cadangan konfigurasi sebelumnya tetap disimpan jika tersedia.")
            .setNegativeButton("Batal", null)
            .setPositiveButton("Hapus overlay", (dialog, which) -> {
                status.setText("Menghapus konfigurasi overlay display…");
                new Thread(() -> {
                    RootResult result = runRootDetailed(
                        "settings delete global overlay_display_devices && settings get global overlay_display_devices");
                    runOnUiThread(() -> {
                        if (result.timedOut || result.exitCode != 0) {
                            showError("Gagal menghapus overlay display. Exit code: " + result.exitCode
                                + (result.timedOut ? " (timeout)" : "") + "\\n" + result.output);
                            return;
                        }
                        String value = result.output.trim();
                        if (!value.isEmpty() && !"null".equalsIgnoreCase(value)) {
                            showError("Perintah selesai, tetapi setting masih berisi: " + value);
                            return;
                        }
                        status.setText("Konfigurasi overlay display dihapus. Android akan memperbarui daftar display; cadangan lama tetap tersedia untuk pemulihan.");
                        Toast.makeText(this, "Overlay display dihapus", Toast.LENGTH_LONG).show();
                    });
                }, "remove-overlay-display").start();
            }).show();
    }

    private String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static final class RootResult {
        final String output;
        final int exitCode;
        final boolean timedOut;
        RootResult(String output, int exitCode, boolean timedOut) {
            this.output = output;
            this.exitCode = exitCode;
            this.timedOut = timedOut;
        }
    }

    private String runRoot(String command) {
        RootResult result = runRootDetailed(command);
        return result.timedOut || result.exitCode != 0 ? null : result.output;
    }

    private RootResult runAsShellDetailed(String command) {
        Process process = null;
        StringBuilder output = new StringBuilder();
        try {
            // --display launches must run as Android shell UID, not UID 0.
            process = new ProcessBuilder("su", "2000", "-c", command).redirectErrorStream(true).start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null && output.length() < 12000) {
                    output.append(line).append('\n');
                }
            }
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return new RootResult(output.toString().trim(), -1, true);
            }
            return new RootResult(output.toString().trim(), process.exitValue(), false);
        } catch (Exception e) {
            output.append(e.getClass().getSimpleName()).append(": ").append(e.getMessage());
            return new RootResult(output.toString().trim(), -1, false);
        } finally {
            if (process != null) process.destroy();
        }
    }

    private RootResult runRootDetailed(String command) {
        Process process = null;
        StringBuilder output = new StringBuilder();
        try {
            process = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null && output.length() < 12000) {
                    output.append(line).append('\n');
                }
            }
            if (!process.waitFor(8, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return new RootResult(output.toString().trim(), -1, true);
            }
            return new RootResult(output.toString().trim(), process.exitValue(), false);
        } catch (Exception e) {
            output.append(e.getClass().getSimpleName()).append(": ").append(e.getMessage());
            return new RootResult(output.toString().trim(), -1, false);
        } finally {
            if (process != null) process.destroy();
        }
    }

    private void showError(String message) {
        new AlertDialog.Builder(this).setTitle("Tidak dapat menerapkan")
            .setMessage(message).setPositiveButton("OK", null).show();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}

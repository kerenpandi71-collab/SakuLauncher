/* Copyright 2026 Taskbar fork contributors
 * Licensed under the Apache License, Version 2.0.
 */
package com.sakuhome.launcher.virtualdisplay;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public class DesktopActivity extends Activity {
    private int displayId = -1;
    private LinearLayout appList;
    private TextView clock, date;
    private int dp(int n) { return (int) (n * getResources().getDisplayMetrics().density + .5f); }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        displayId = getIntent().getIntExtra("display_id", -1);
        // A desktop surface must be focusable/touchable so InputDispatcher can route
        // mouse clicks and hover events to this display's application window.
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        buildUi();
        loadApps("");
        updateClock();
        clock.postDelayed(new Runnable() {
            @Override public void run() {
                if (clock != null) {
                    updateClock();
                    clock.postDelayed(this, 30000);
                }
            }
        }, 30000);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(16), dp(18), dp(10));
        root.setBackgroundColor(0xFF0B1220);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        clock = text("", 26, 0xFFF3F6FC);
        clock.setTypeface(null, 1);
        date = text("", 12, 0xFF9EACC2);
        info.addView(clock);
        info.addView(date);
        header.addView(info, new LinearLayout.LayoutParams(0, -2, 1));

        root.addView(header);

        TextView trackpad = text("Trackpad + Mouse + Log Diagnostik", 14, 0xFFF4F7FC);
        trackpad.setGravity(Gravity.CENTER_VERTICAL);
        trackpad.setPadding(dp(14), dp(12), dp(14), dp(12));
        trackpad.setBackgroundColor(0xFF24344D);
        trackpad.setOnClickListener(v -> openTrackpadOnPhone());
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, -2);
        tp.topMargin = dp(10);
        root.addView(trackpad, tp);

        TextView settings = text("Virtual Display • Resolusi / DPI / Pemulihan", 14, 0xFFF4F7FC);
        settings.setGravity(Gravity.CENTER_VERTICAL);
        settings.setPadding(dp(14), dp(12), dp(14), dp(12));
        settings.setBackgroundColor(0xFF24344D);
        settings.setOnClickListener(v -> openMiniDesktopSettings());
        LinearLayout.LayoutParams settingsParams = new LinearLayout.LayoutParams(-1, -2);
        settingsParams.topMargin = dp(8);
        root.addView(settings, settingsParams);

        TextView heading = text("APLIKASI", 12, 0xFF8294B3);
        heading.setLetterSpacing(.12f);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, -2);
        hp.topMargin = dp(14);
        root.addView(heading, hp);

        EditText search = new EditText(this);
        search.setSingleLine(true);
        search.setHint("Cari aplikasi…");
        search.setTextColor(0xFFF3F6FC);
        search.setHintTextColor(0xFF9EACC2);
        search.setTextSize(14);
        search.setPadding(dp(10), 0, dp(10), 0);
        search.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF687A98));
        root.addView(search, new LinearLayout.LayoutParams(-1, dp(46)));
        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                loadApps(s.toString());
            }
            @Override public void afterTextChanged(android.text.Editable e) { }
        });

        ScrollView scroll = new ScrollView(this);
        appList = new LinearLayout(this);
        appList.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(appList);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        TextView footer = text("Display " + displayId + " • Pilih aplikasi untuk membuka", 11, 0xFF8190A8);
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(-1, -2);
        fp.topMargin = dp(6);
        root.addView(footer, fp);
        setContentView(root);
    }

    private TextView text(String value, int size, int color) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER_VERTICAL);
        return t;
    }

    private void openMiniDesktopSettings() {
        // Settings must open on the phone display, not inherit the virtual desktop display.
        final String command = "am start --display 0 --windowingMode 1 -n " + getPackageName()
                + "/com.sakuhome.launcher.virtualdisplay.VirtualDisplayActivity";
        new Thread(() -> {
            String result = runAsShell(command);
            runOnUiThread(() -> {
                if (result == null || result.contains("Error:") || result.contains("Exception")) {
                    Toast.makeText(this, "Gagal membuka Pengaturan Mini Desktop: " + result,
                            Toast.LENGTH_LONG).show();
                }
            });
        }, "open-mini-desktop-settings").start();
    }

    private void openTrackpadOnPhone() {
        // DesktopActivity runs on the virtual display. A normal startActivity() inherits
        // that display and can put the trackpad UI on the desktop instead of the phone.
        final String command = "am start --display 0 --windowingMode 1 -n " + getPackageName()
                + "/com.sakuhome.launcher.virtualdisplay.TrackpadActivity --ei display_id " + displayId;
        new Thread(() -> {
            String result = runRoot(command);
            runOnUiThread(() -> {
                if (result == null || result.contains("Error:") || result.contains("Exception")) {
                    Toast.makeText(this, "Gagal membuka trackpad di layar HP: " + result,
                            Toast.LENGTH_LONG).show();
                }
            });
        }, "open-phone-trackpad").start();
    }

    private void loadApps(String query) {
        if (appList == null) return;
        appList.removeAllViews();
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> apps = getPackageManager().queryIntentActivities(intent, 0);
        Collections.sort(apps, new Comparator<ResolveInfo>() {
            @Override public int compare(ResolveInfo a, ResolveInfo b) {
                return a.loadLabel(getPackageManager()).toString()
                        .compareToIgnoreCase(b.loadLabel(getPackageManager()).toString());
            }
        });

        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        int count = 0;
        for (ResolveInfo app : apps) {
            String label = app.loadLabel(getPackageManager()).toString();
            String packageName = app.activityInfo.packageName;
            if (!label.toLowerCase(Locale.ROOT).contains(q)
                    && !packageName.toLowerCase(Locale.ROOT).contains(q)) continue;

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(10), dp(8), dp(10), dp(8));
            row.setBackgroundColor(count % 2 == 0 ? 0xFF111C2E : 0xFF0D1727);

            ImageView icon = new ImageView(this);
            try { icon.setImageDrawable(app.loadIcon(getPackageManager())); }
            catch (Exception ignored) { }
            row.addView(icon, new LinearLayout.LayoutParams(dp(42), dp(42)));

            LinearLayout labels = new LinearLayout(this);
            labels.setOrientation(LinearLayout.VERTICAL);
            labels.setPadding(dp(12), 0, 0, 0);
            TextView name = text(label, 14, 0xFFF0F4FB);
            TextView pkg = text(packageName, 11, 0xFF8FA2BE);
            labels.addView(name);
            labels.addView(pkg);
            row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));

            row.setOnClickListener(v -> launchApp(app));
            appList.addView(row, new LinearLayout.LayoutParams(-1, -2));
            View divider = new View(this);
            divider.setBackgroundColor(0xFF243149);
            appList.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
            count++;
        }

        if (count == 0) {
            TextView empty = text("Tidak ada aplikasi yang cocok.", 13, 0xFFB7C3D8);
            empty.setPadding(dp(10), dp(16), dp(10), dp(16));
            appList.addView(empty);
        }
    }

    private void launchApp(ResolveInfo app) {
        String component = app.activityInfo.packageName + "/" + app.activityInfo.name;
        if (!component.matches("[A-Za-z0-9_.$]+/[A-Za-z0-9_.$]+")) {
            Toast.makeText(this, "Komponen tidak valid", Toast.LENGTH_SHORT).show();
            return;
        }
        new Thread(() -> {
            String result = runAsShell("am start --display " + displayId + " --windowingMode 1 -n " + component);
            runOnUiThread(() -> {
                if (result == null || result.contains("Error:") || result.contains("Exception")) {
                    Toast.makeText(this, "Android gagal membuka aplikasi di display ini.",
                            Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(this, "Membuka " + app.loadLabel(getPackageManager()),
                            Toast.LENGTH_SHORT).show();
                }
            });
        }, "launch-desktop-app").start();
    }

    private void updateClock() {
        java.util.Date now = new java.util.Date();
        clock.setText(new java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).format(now));
        date.setText(new java.text.SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(now));
    }

    private String runAsShell(String command) {
        Process p = null;
        try {
            // ActivityTaskManager authorizes --display against shell UID (2000), not root UID.
            p = new ProcessBuilder("su", "2000", "-c", command).redirectErrorStream(true).start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null && out.length() < 6000) {
                    out.append(line).append('\n');
                }
            }
            if (!p.waitFor(10, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return "Perintah shell timeout";
            }
            return out.toString().trim();
        } catch (Exception e) {
            return e.getClass().getSimpleName() + ": " + e.getMessage();
        } finally {
            if (p != null) p.destroy();
        }
    }

    private String runRoot(String command) {
        Process p = null;
        try {
            p = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null && out.length() < 4000) {
                    out.append(line).append('\n');
                }
            }
            if (!p.waitFor(5, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            return p.exitValue() == 0 ? out.toString().trim() : out.toString().trim();
        } catch (Exception e) {
            return null;
        } finally {
            if (p != null) p.destroy();
        }
    }
}

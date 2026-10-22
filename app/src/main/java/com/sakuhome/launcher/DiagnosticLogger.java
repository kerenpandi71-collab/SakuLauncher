package com.sakuhome.launcher;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Process;
import android.provider.MediaStore;
import android.util.Log;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/** Writes fatal crash and main-thread stall diagnostics into the public Downloads folder. */
public final class DiagnosticLogger {
    private static final String TAG = "SakuDiagnostics";
    private static final AtomicBoolean WRITING = new AtomicBoolean(false);
    private static volatile Context appContext;

    private DiagnosticLogger() {}

    public static void install(Context context) {
        appContext = context.getApplicationContext();
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                write("CRASH", "Fatal exception on thread " + thread.getName(), error, thread);
            } catch (Throwable loggingError) {
                Log.e(TAG, "Unable to save crash report", loggingError);
            } finally {
                // Keep Android's normal crash handling and crash dialog.
                if (previous != null) previous.uncaughtException(thread, error);
                else {
                    android.os.Process.killProcess(android.os.Process.myPid());
                    System.exit(10);
                }
            }
        });
        startAnrWatchdog();
    }

    private static void startAnrWatchdog() {
        final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        final java.util.concurrent.atomic.AtomicLong heartbeat = new java.util.concurrent.atomic.AtomicLong();
        final Runnable beat = new Runnable() {
            @Override public void run() {
                heartbeat.set(android.os.SystemClock.elapsedRealtime());
                main.postDelayed(this, 1000);
            }
        };
        main.post(beat);
        Thread watchdog = new Thread(() -> {
            long lastReportAt = 0;
            while (!Thread.currentThread().isInterrupted()) {
                try { Thread.sleep(3000); } catch (InterruptedException e) { return; }
                long now = android.os.SystemClock.elapsedRealtime();
                long lastBeat = heartbeat.get();
                if (lastBeat > 0 && now - lastBeat >= 10000 && now - lastReportAt >= 30000) {
                    lastReportAt = now;
                    StackTraceElement[] stack = LooperStack.mainThreadStack();
                    writeText("ANR", "Main thread did not respond for about " + (now-lastBeat)
                            + " ms. This is a diagnostic warning; Android may or may not classify it as an ANR.\n"
                            + stackToString(stack));
                }
            }
        }, "saku-anr-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    private static final class LooperStack {
        static StackTraceElement[] mainThreadStack() {
            for (Thread t : Thread.getAllStackTraces().keySet()) {
                if ("main".equals(t.getName())) return t.getStackTrace();
            }
            return new StackTraceElement[0];
        }
    }

    private static String stackToString(StackTraceElement[] stack) {
        StringBuilder out = new StringBuilder();
        for (StackTraceElement e : stack) out.append("    at ").append(e).append('\n');
        return out.toString();
    }

    private static void write(String type, String message, Throwable error, Thread thread) {
        StringWriter sw = new StringWriter();
        if (error != null) error.printStackTrace(new PrintWriter(sw));
        writeText(type, message + "\nthread=" + (thread == null ? "unknown" : thread.getName())
                + "\n" + sw);
    }

    private static void writeText(String type, String body) {
        Context context = appContext;
        if (context == null || !WRITING.compareAndSet(false, true)) return;
        try {
            String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date());
            String filename = "SakuLauncher_" + type.toLowerCase(Locale.US) + "_" + stamp + ".txt";
            String report = "SakuLauncher diagnostic report\n"
                    + "type=" + type + "\n"
                    + "time=" + new Date() + "\n"
                    + "device=" + Build.MANUFACTURER + " " + Build.MODEL + "\n"
                    + "android=" + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")\n"
                    + "app=" + BuildConfig.APPLICATION_ID + " " + BuildConfig.VERSION_NAME + "\n\n"
                    + body + "\n";
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, filename);
            values.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.put(MediaStore.Downloads.RELATIVE_PATH, "Download/SakuLauncher-Logs");
                values.put(MediaStore.Downloads.IS_PENDING, 1);
            }
            Uri collection = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    ? MediaStore.Downloads.EXTERNAL_CONTENT_URI
                    : MediaStore.Files.getContentUri("external");
            Uri uri = context.getContentResolver().insert(collection, values);
            if (uri == null) throw new IllegalStateException("MediaStore returned no Downloads URI");
            try (OutputStream stream = context.getContentResolver().openOutputStream(uri)) {
                if (stream == null) throw new IllegalStateException("Cannot open diagnostic file");
                stream.write(report.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                stream.flush();
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues ready = new ContentValues();
                ready.put(MediaStore.Downloads.IS_PENDING, 0);
                context.getContentResolver().update(uri, ready, null, null);
            }
            Log.e(TAG, "Saved " + type + " report: " + filename);
        } catch (Throwable e) {
            Log.e(TAG, "Could not save diagnostic report", e);
        } finally {
            WRITING.set(false);
        }
    }
}

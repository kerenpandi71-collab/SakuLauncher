package com.sakuhome.launcher.virtualdisplay;

import android.hardware.input.InputManager;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;

/**
 * Small root-side input bridge. TrackpadActivity sends MOVE and CLICK lines over stdin.
 * This runs under app_process with root privileges so mouse events can target a virtual display.
 */
public final class RootInputBridge {
    private static final int INJECT_ASYNC = 0;
    private static Object inputManager;
    private static Method injectInputEvent;
    private static Method setDisplayId;

    private RootInputBridge() { }

    public static void main(String[] args) {
        try {
            Class<?> managerClass = Class.forName("android.hardware.input.InputManager");
            Method getInstance = managerClass.getDeclaredMethod("getInstance");
            getInstance.setAccessible(true);
            inputManager = getInstance.invoke(null);
            injectInputEvent = managerClass.getDeclaredMethod("injectInputEvent", InputEvent.class, int.class);
            injectInputEvent.setAccessible(true);
            setDisplayId = MotionEvent.class.getDeclaredMethod("setDisplayId", int.class);
            setDisplayId.setAccessible(true);
            System.out.println("READY");
            System.out.flush();

            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;
                try {
                    handle(trimmed);
                } catch (Throwable error) {
                    System.out.println("INPUT_ERROR " + trimmed + " :: "
                        + error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage()));
                    System.out.flush();
                }
            }
        } catch (Throwable error) {
            System.out.println("BRIDGE_ERROR " + error.getClass().getSimpleName() + ": "
                + String.valueOf(error.getMessage()));
            System.out.flush();
        }
    }

    private static void handle(String line) throws Exception {
        String[] parts = line.split("\\s+");
        if (parts.length < 4) throw new IllegalArgumentException("format perintah tidak lengkap");
        String action = parts[0];
        int displayId = Integer.parseInt(parts[1]);
        float x = Float.parseFloat(parts[2]);
        float y = Float.parseFloat(parts[3]);
        if (displayId < 0 || !Float.isFinite(x) || !Float.isFinite(y)) {
            throw new IllegalArgumentException("display/koordinat tidak valid");
        }

        if ("MOVE".equals(action)) {
            injectMouse(displayId, x, y, MotionEvent.ACTION_HOVER_MOVE, 0, 0L);
        } else if ("CLICK".equals(action)) {
            if (parts.length < 5) throw new IllegalArgumentException("jenis klik tidak ada");
            int button = "RIGHT".equalsIgnoreCase(parts[4])
                ? MotionEvent.BUTTON_SECONDARY : MotionEvent.BUTTON_PRIMARY;
            long downTime = SystemClock.uptimeMillis();
            injectMouse(displayId, x, y, MotionEvent.ACTION_DOWN, button, downTime);
            SystemClock.sleep(12L);
            injectMouse(displayId, x, y, MotionEvent.ACTION_UP, 0, downTime);
        } else {
            throw new IllegalArgumentException("aksi tidak dikenal: " + action);
        }
    }

    private static void injectMouse(int displayId, float x, float y, int action,
            int buttonState, long downTime) throws Exception {
        long now = SystemClock.uptimeMillis();
        long eventDown = downTime == 0L ? now : downTime;
        MotionEvent event = MotionEvent.obtain(eventDown, now, action, x, y, 0);
        try {
            event.setSource(InputDevice.SOURCE_MOUSE);
            event.setButtonState(buttonState);
            setDisplayId.invoke(event, displayId);
            Object result = injectInputEvent.invoke(inputManager, event, INJECT_ASYNC);
            if (!(result instanceof Boolean) || !((Boolean) result)) {
                throw new IllegalStateException("InputManager menolak event pada display " + displayId
                    + " action=" + action + " x=" + x + " y=" + y);
            }
        } finally {
            event.recycle();
        }
    }
}

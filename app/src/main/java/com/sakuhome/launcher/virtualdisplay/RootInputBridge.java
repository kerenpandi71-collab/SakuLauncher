package com.sakuhome.launcher.virtualdisplay;

import android.os.IBinder;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.LinkedHashMap;
import java.util.Map;
import java.lang.reflect.Method;

/**
 * Root-side input bridge. Resolve the input service directly through ServiceManager:
 * InputManager.getInstance() can throw when app_process has no framework singleton.
 */
public final class RootInputBridge {
    private static final int INJECT_ASYNC = 0;
    private static Object inputManager;
    private static Method injectInputEvent;
    private static Method setDisplayId;
    private static Method setButtonState;
    private static long dragDownTime;
    private static final LinkedHashMap<Integer, TouchPoint> activeTouches = new LinkedHashMap<>();
    private static final class TouchPoint {
        float x, y;
        TouchPoint(float x, float y) { this.x=x; this.y=y; }
    }

    private RootInputBridge() { }

    public static void main(String[] args) {
        try {
            System.out.println("INIT service lookup");
            Class<?> serviceManager = Class.forName("android.os.ServiceManager");
            Method getService = serviceManager.getDeclaredMethod("getService", String.class);
            getService.setAccessible(true);
            IBinder binder = (IBinder) getService.invoke(null, "input");
            if (binder == null) {
                throw new IllegalStateException("ServiceManager tidak menemukan service input");
            }

            Class<?> stubClass = Class.forName("android.hardware.input.IInputManager$Stub");
            Method asInterface = stubClass.getDeclaredMethod("asInterface", IBinder.class);
            asInterface.setAccessible(true);
            inputManager = asInterface.invoke(null, binder);
            if (inputManager == null) {
                throw new IllegalStateException("IInputManager.asInterface menghasilkan null");
            }

            Class<?> managerInterface = Class.forName("android.hardware.input.IInputManager");
            injectInputEvent = managerInterface.getDeclaredMethod("injectInputEvent", InputEvent.class, int.class);
            injectInputEvent.setAccessible(true);
            setDisplayId = MotionEvent.class.getDeclaredMethod("setDisplayId", int.class);
            setDisplayId.setAccessible(true);
            setButtonState = MotionEvent.class.getDeclaredMethod("setButtonState", int.class);
            setButtonState.setAccessible(true);
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
                    error.printStackTrace(System.out);
                    System.out.flush();
                }
            }
        } catch (Throwable error) {
            StringBuilder detail = new StringBuilder();
            Throwable current = error;
            int depth = 0;
            while (current != null && depth++ < 8) {
                if (detail.length() > 0) detail.append(" <- ");
                detail.append(current.getClass().getName()).append(": ")
                    .append(String.valueOf(current.getMessage()));
                if (current instanceof java.lang.reflect.InvocationTargetException) {
                    Throwable target = ((java.lang.reflect.InvocationTargetException) current).getTargetException();
                    if (target != null && target != current) { current = target; continue; }
                }
                current = current.getCause();
            }
            System.out.println("BRIDGE_ERROR " + detail);
            error.printStackTrace(System.out);
            System.out.flush();
        }
    }

    private static void handle(String line) throws Exception {
        String[] parts = line.split("\\s+");
        if (parts.length < 2) throw new IllegalArgumentException("format perintah tidak lengkap");
        String action = parts[0];
        int displayId = Integer.parseInt(parts[1]);
        if ("TOUCH".equals(action)) {
            if (parts.length < 6) throw new IllegalArgumentException("format TOUCH display pointerId DOWN|MOVE|UP x y");
            int pointerId = Integer.parseInt(parts[2]);
            String phase = parts[3].toUpperCase(java.util.Locale.ROOT);
            float tx = Float.parseFloat(parts[4]), ty = Float.parseFloat(parts[5]);
            injectTouch(displayId, pointerId, phase, tx, ty);
            return;
        }
        if ("KEY".equals(action)) {
            if (parts.length < 3) throw new IllegalArgumentException("format KEY display HOME|BACK");
            injectKey(displayId, "HOME".equalsIgnoreCase(parts[2]) ? KeyEvent.KEYCODE_HOME : KeyEvent.KEYCODE_BACK);
            return;
        }
        if (parts.length < 4) throw new IllegalArgumentException("format perintah tidak lengkap");
        float x = Float.parseFloat(parts[2]);
        float y = Float.parseFloat(parts[3]);
        if (displayId < 0 || !Float.isFinite(x) || !Float.isFinite(y)) {
            throw new IllegalArgumentException("display/koordinat tidak valid");
        }

        if ("MOVE".equals(action)) {
            if (dragDownTime > 0L) injectMouse(displayId, x, y, MotionEvent.ACTION_MOVE, MotionEvent.BUTTON_PRIMARY, dragDownTime);
            else injectMouse(displayId, x, y, MotionEvent.ACTION_HOVER_MOVE, 0, 0L);
        } else if ("BUTTON".equals(action)) {
            if (parts.length < 5) throw new IllegalArgumentException("format BUTTON display x y DOWN|UP");
            if ("DOWN".equalsIgnoreCase(parts[4])) {
                if (dragDownTime == 0L) dragDownTime = SystemClock.uptimeMillis();
                injectMouse(displayId, x, y, MotionEvent.ACTION_DOWN, MotionEvent.BUTTON_PRIMARY, dragDownTime);
            } else {
                long down = dragDownTime > 0L ? dragDownTime : SystemClock.uptimeMillis();
                injectMouse(displayId, x, y, MotionEvent.ACTION_UP, 0, down);
                dragDownTime = 0L;
            }
        } else if ("SCROLL".equals(action)) {
            if (parts.length < 6) throw new IllegalArgumentException("format SCROLL display x y h v");
            float horizontal = Float.parseFloat(parts[4]);
            float vertical = Float.parseFloat(parts[5]);
            injectScroll(displayId, x, y, horizontal, vertical);
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


    /** Injects a multi-pointer touchscreen stream to a specific display. */
    private static void injectTouch(int displayId, int pointerId, String phase, float x, float y) throws Exception {
        if (pointerId < 0 || pointerId > 31 || !Float.isFinite(x) || !Float.isFinite(y))
            throw new IllegalArgumentException("pointer/coordinates invalid");
        boolean exists = activeTouches.containsKey(pointerId);
        if ("DOWN".equals(phase)) {
            if (exists) throw new IllegalStateException("pointer already down: " + pointerId);
            activeTouches.put(pointerId, new TouchPoint(x, y));
        } else {
            if (!exists) throw new IllegalStateException("pointer not down: " + pointerId);
            activeTouches.get(pointerId).x=x; activeTouches.get(pointerId).y=y;
        }
        int index=0, targetIndex=-1;
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[activeTouches.size()];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[activeTouches.size()];
        for (Map.Entry<Integer,TouchPoint> entry : activeTouches.entrySet()) {
            MotionEvent.PointerProperties pp = new MotionEvent.PointerProperties();
            pp.id=entry.getKey(); pp.toolType=MotionEvent.TOOL_TYPE_FINGER; properties[index]=pp;
            MotionEvent.PointerCoords pc = new MotionEvent.PointerCoords();
            pc.x=entry.getValue().x; pc.y=entry.getValue().y; pc.pressure=1f; pc.size=1f; coords[index]=pc;
            if (entry.getKey()==pointerId) targetIndex=index;
            index++;
        }
        int action;
        if ("DOWN".equals(phase)) action = activeTouches.size()==1 ? MotionEvent.ACTION_DOWN :
            MotionEvent.ACTION_POINTER_DOWN | (targetIndex << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
        else if ("MOVE".equals(phase)) action=MotionEvent.ACTION_MOVE;
        else if ("UP".equals(phase)) action=activeTouches.size()==1 ? MotionEvent.ACTION_UP :
            MotionEvent.ACTION_POINTER_UP | (targetIndex << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
        else throw new IllegalArgumentException("phase harus DOWN/MOVE/UP");
        long now=SystemClock.uptimeMillis();
        MotionEvent event=MotionEvent.obtain(now,now,action,properties.length,properties,coords,
            0,0,1f,1f,0,0,InputDevice.SOURCE_TOUCHSCREEN,0);
        try {
            setDisplayId.invoke(event,displayId);
            Object result=injectInputEvent.invoke(inputManager,event,INJECT_ASYNC);
            if (!(result instanceof Boolean) || !((Boolean)result))
                throw new IllegalStateException("IInputManager menolak TOUCH display="+displayId+" action="+action);
        } finally { event.recycle(); }
        if ("UP".equals(phase)) activeTouches.remove(pointerId);
    }

    private static void injectKey(int displayId, int keyCode) throws Exception {
        long now = SystemClock.uptimeMillis();
        Method keyDisplaySetter = InputEvent.class.getDeclaredMethod("setDisplayId", int.class);
        keyDisplaySetter.setAccessible(true);
        KeyEvent down = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0);
        KeyEvent up = new KeyEvent(now, now + 12L, KeyEvent.ACTION_UP, keyCode, 0);
        keyDisplaySetter.invoke(down, displayId);
        keyDisplaySetter.invoke(up, displayId);
        Object downResult = injectInputEvent.invoke(inputManager, down, INJECT_ASYNC);
        Object upResult = injectInputEvent.invoke(inputManager, up, INJECT_ASYNC);
        if (!Boolean.TRUE.equals(downResult) || !Boolean.TRUE.equals(upResult)) {
            throw new IllegalStateException("IInputManager menolak tombol " + keyCode + " pada display " + displayId);
        }
    }

    private static void injectScroll(int displayId, float x, float y, float horizontal, float vertical) throws Exception {
        long now = SystemClock.uptimeMillis();
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[1];
        properties[0] = new MotionEvent.PointerProperties();
        properties[0].id = 0;
        properties[0].toolType = MotionEvent.TOOL_TYPE_MOUSE;

        MotionEvent.PointerCoords[] coordinates = new MotionEvent.PointerCoords[1];
        coordinates[0] = new MotionEvent.PointerCoords();
        coordinates[0].x = x;
        coordinates[0].y = y;
        coordinates[0].pressure = 1f;
        coordinates[0].size = 1f;
        coordinates[0].setAxisValue(MotionEvent.AXIS_HSCROLL, horizontal);
        coordinates[0].setAxisValue(MotionEvent.AXIS_VSCROLL, vertical);

        MotionEvent event = MotionEvent.obtain(
            now, now, MotionEvent.ACTION_SCROLL, 1, properties, coordinates,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0);
        try {
            setDisplayId.invoke(event, displayId);
            Object result = injectInputEvent.invoke(inputManager, event, INJECT_ASYNC);
            if (!(result instanceof Boolean) || !((Boolean) result)) {
                throw new IllegalStateException("IInputManager menolak scroll pada display " + displayId);
            }
        } finally { event.recycle(); }
    }

    private static void injectMouse(int displayId, float x, float y, int action,
            int buttonState, long downTime) throws Exception {
        long now = SystemClock.uptimeMillis();
        long eventDown = downTime == 0L ? now : downTime;
        MotionEvent event = MotionEvent.obtain(eventDown, now, action, x, y, 0);
        try {
            event.setSource(InputDevice.SOURCE_MOUSE);
            setButtonState.invoke(event, buttonState);
            setDisplayId.invoke(event, displayId);
            Object result = injectInputEvent.invoke(inputManager, event, INJECT_ASYNC);
            if (!(result instanceof Boolean) || !((Boolean) result)) {
                throw new IllegalStateException("IInputManager menolak event pada display " + displayId
                    + " action=" + action + " x=" + x + " y=" + y);
            }
        } finally {
            event.recycle();
        }
    }
}

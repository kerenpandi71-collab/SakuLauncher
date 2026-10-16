package com.sakuhome.launcher

import android.os.Binder
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import java.lang.reflect.Method

/**
 * Runs as Shizuku's shell user service. This mirrors the DroidOS input path:
 * inject real mouse hover/move/button/scroll events through InputManager.
 */
class ShellUserService : IShellService.Stub() {
    private var inputManager: Any? = null
    private var injectMethod: Method? = null

    init {
        try {
            val cls = Class.forName("android.hardware.input.InputManager")
            inputManager = cls.getMethod("getInstance").invoke(null)
            injectMethod = cls.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
        } catch (_: Exception) {
            inputManager = null
            injectMethod = null
        }
    }

    override fun injectMouse(action: Int, x: Float, y: Float, displayId: Int, source: Int, buttonState: Int, downTime: Long) {
        val now = SystemClock.uptimeMillis()
        val props = MotionEvent.PointerProperties().apply {
            id = 0
            toolType = if ((source and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE) MotionEvent.TOOL_TYPE_MOUSE else MotionEvent.TOOL_TYPE_FINGER
        }
        val coords = MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            pressure = if (action == MotionEvent.ACTION_UP) 0f else 1f
            size = 1f
        }
        injectMotion(action, props, coords, displayId, source, buttonState, downTime, now)
    }

    override fun injectScroll(x: Float, y: Float, vDistance: Float, hDistance: Float, displayId: Int) {
        val now = SystemClock.uptimeMillis()
        val props = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE }
        val coords = MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            pressure = 1f
            size = 1f
            setAxisValue(MotionEvent.AXIS_VSCROLL, vDistance)
            setAxisValue(MotionEvent.AXIS_HSCROLL, hDistance)
        }
        injectMotion(MotionEvent.ACTION_SCROLL, props, coords, displayId, InputDevice.SOURCE_MOUSE, 0, now, now)
    }

    override fun execClick(x: Float, y: Float, displayId: Int) {
        val now = SystemClock.uptimeMillis()
        injectMouse(MotionEvent.ACTION_DOWN, x, y, displayId, InputDevice.SOURCE_MOUSE, MotionEvent.BUTTON_PRIMARY, now)
        injectMouse(MotionEvent.ACTION_UP, x, y, displayId, InputDevice.SOURCE_MOUSE, 0, now)
    }

    override fun execRightClick(x: Float, y: Float, displayId: Int) {
        val now = SystemClock.uptimeMillis()
        injectMouse(MotionEvent.ACTION_DOWN, x, y, displayId, InputDevice.SOURCE_MOUSE, MotionEvent.BUTTON_SECONDARY, now)
        injectMouse(MotionEvent.ACTION_UP, x, y, displayId, InputDevice.SOURCE_MOUSE, 0, now)
    }

    override fun injectKey(keyCode: Int, action: Int, metaState: Int, displayId: Int) {
        val now = SystemClock.uptimeMillis()
        val event = KeyEvent(now, now, action, keyCode, 0, metaState, 0, 0, KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD)
        try {
            InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType).invoke(event, displayId)
            injectMethod?.invoke(inputManager, event, 0)
        } catch (_: Exception) {
            // Keep failures isolated; the UI falls back to the standard IME path.
        }
    }

    private fun injectMotion(
        action: Int,
        props: MotionEvent.PointerProperties,
        coords: MotionEvent.PointerCoords,
        displayId: Int,
        source: Int,
        buttonState: Int,
        downTime: Long,
        eventTime: Long
    ) {
        val manager = inputManager ?: return
        val method = injectMethod ?: return
        var event: MotionEvent? = null
        val token = Binder.clearCallingIdentity()
        try {
            event = MotionEvent.obtain(downTime, eventTime, action, 1, arrayOf(props), arrayOf(coords),
                0, buttonState, 1f, 1f, 0, 0, source, 0)
            InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType).invoke(event, displayId)
            method.invoke(manager, event, 0)
        } catch (_: Exception) {
        } finally {
            event?.recycle()
            Binder.restoreCallingIdentity(token)
        }
    }
}
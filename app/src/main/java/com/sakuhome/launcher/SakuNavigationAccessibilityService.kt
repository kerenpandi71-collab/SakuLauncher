package com.sakuhome.launcher

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

/**
 * Optional user-enabled bridge for the desktop navigation bar.
 * It only exposes Android's standard global navigation actions.
 */
class SakuNavigationAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        active = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (active === this) active = null
        super.onDestroy()
    }

    companion object {
        @Volatile private var active: SakuNavigationAccessibilityService? = null

        fun performGlobalAction(action: Int): Boolean =
            active?.performGlobalAction(action) ?: false

        fun performTap(x: Float, y: Float, durationMs: Long = 80L): Boolean {
            val service = active ?: return false
            val path = Path().apply { moveTo(x, y); lineTo(x + 1f, y + 1f) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(50L, 250L)))
                .build()
            return service.dispatchGesture(gesture, null, null)
        }

        fun performLongPress(x: Float, y: Float, durationMs: Long = 600L): Boolean {
            val service = active ?: return false
            val path = Path().apply { moveTo(x, y); lineTo(x + 1f, y + 1f) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(400L, 1200L)))
                .build()
            return service.dispatchGesture(gesture, null, null)
        }

    }
}

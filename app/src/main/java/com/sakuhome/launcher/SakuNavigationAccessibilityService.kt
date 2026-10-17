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

        fun performScroll(scrollDown: Boolean): Boolean {
            val service = active ?: return false
            val metrics = service.resources.displayMetrics
            val x = metrics.widthPixels * 0.5f
            val startY = if (scrollDown) metrics.heightPixels * 0.78f else metrics.heightPixels * 0.28f
            val endY = if (scrollDown) metrics.heightPixels * 0.28f else metrics.heightPixels * 0.78f
            val path = Path().apply { moveTo(x, startY); lineTo(x, endY) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 420))
                .build()
            return service.dispatchGesture(gesture, null, null)
        }
    }
}

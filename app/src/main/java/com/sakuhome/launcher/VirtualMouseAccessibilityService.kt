package com.sakuhome.launcher

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

class VirtualMouseAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    fun tapAt(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y); lineTo(x + 0.5f, y + 0.5f) }
        dispatchGesture(GestureDescription.Builder().addStroke(
            GestureDescription.StrokeDescription(path, 0, 55)
        ).build(), null, null)
    }

    fun longPressAt(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y); lineTo(x + 0.1f, y + 0.1f) }
        dispatchGesture(GestureDescription.Builder().addStroke(
            GestureDescription.StrokeDescription(path, 0, 650)
        ).build(), null, null)
    }

    fun swipe(fromX: Float, fromY: Float, toX: Float, toY: Float, durationMs: Long = 240L) {
        val path = Path().apply { moveTo(fromX, fromY); lineTo(toX, toY) }
        dispatchGesture(GestureDescription.Builder().addStroke(
            GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(80L, 900L))
        ).build(), null, null)
    }

    companion object {
        @Volatile var instance: VirtualMouseAccessibilityService? = null
            private set
    }
}

package com.sakuhome.launcher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

class VirtualControlService : Service() {
    private lateinit var wm: WindowManager
    private var panel: View? = null
    private var cursor: TextView? = null
    private var trackpadView: View? = null
    private var hintView: View? = null
    private var keyboardView: View? = null
    private var keyboardVisible = false
    private var pcShift = false
    private var pcCtrl = false
    private var pcAlt = false
    private var pcWin = false
    private var foreground = false
    private var cursorX = 0f
    private var cursorY = 0f
    private var padDownX = 0f
    private var padDownY = 0f
    private var moved = false
    private var dragMode = false
    private var multiTouch = false
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var lastScrollY = 0f
    private var lastMoveAt = 0L
    private var dragDownTime = 0L
    private lateinit var cursorParams: WindowManager.LayoutParams

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Izinkan Saku tampil di atas aplikasi terlebih dahulu", Toast.LENGTH_LONG).show()
            stopSelf()
            return START_NOT_STICKY
        }
        // Uses DroidOS-style privileged InputManager injection when Shizuku is available.
        // Accessibility gestures remain as a safe fallback.
        SakuInputBridge.ensureConnected(this)
        startAsForeground()
        if (panel == null) showControls()
        return START_STICKY
    }

    private fun startAsForeground() {
        if (foreground) return
        val channelId = "saku_virtual_controls"
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(channelId, "Saku Virtual Mouse", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
        val stopIntent = Intent(this, VirtualControlService::class.java).setAction(ACTION_STOP)
        val stopPending = PendingIntent.getService(this, 72, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, channelId) else Notification.Builder(this)
        val notification = builder.setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Saku Virtual Mouse aktif")
            .setContentText("Trackpad, klik mouse, scroll, dan keyboard")
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Matikan", stopPending)
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(72, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(72, notification)
        foreground = true
    }

    private fun showControls() {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val metrics = resources.displayMetrics
        cursorX = metrics.widthPixels * 0.5f
        cursorY = metrics.heightPixels * 0.38f

        val shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(9), dp(7), dp(9), dp(7))
            background = rounded(Color.rgb(25, 31, 45), 20)
            elevation = dp(14).toFloat()
        }
        val toolbar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        toolbar.addView(TextView(this).apply {
            text = "SAKU  •  TRACKPAD"
            textSize = 11f
            setTextColor(Color.rgb(190, 205, 235))
        }, LinearLayout.LayoutParams(0, dp(36), 1f))
        toolbar.addView(button("⌨", dp(38)) { toggleKeyboard() })
        toolbar.addView(button(if (dragMode) "Drag✓" else "Drag", dp(48)) { toggleDragMode() })
        toolbar.addView(button("Right", dp(43)) { rightClick() })
        toolbar.addView(button("×", dp(34)) { stopSelf() })
        shell.addView(toolbar)

        val pad = TextView(this).apply {
            text = "TRACKPAD\nSatu jari: gerak + klik • dua jari: scroll"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = rounded(Color.rgb(48, 59, 81), 17)
            isHapticFeedbackEnabled = true
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        padDownX = event.x
                        padDownY = event.y
                        lastRawX = event.rawX
                        lastRawY = event.rawY
                        lastMoveAt = SystemClock.uptimeMillis()
                        moved = false
                        multiTouch = false
                        lastScrollY = event.y
                        true
                    }
                    MotionEvent.ACTION_POINTER_DOWN -> {
                        multiTouch = true
                        moved = true
                        lastScrollY = averageY(event)
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (event.pointerCount >= 2 || multiTouch) {
                            multiTouch = true
                            val y = averageY(event)
                            val dy = y - lastScrollY
                            if (abs(dy) > 0.5f) {
                                val bridge = SakuInputBridge.shell
                                if (bridge != null) {
                                    try { bridge.injectScroll(cursorX, cursorY, -dy / dp(16).toFloat(), 0f, 0) } catch (_: Exception) {}
                                } else {
                                    // Fallback approximation: a short swipe at the cursor location.
                                    val scroll = VirtualMouseAccessibilityService.instance
                                    if (scroll != null && abs(dy) > dp(3)) {
                                        scroll.swipe(cursorX, cursorY, cursorX, cursorY - dy * 1.8f, 140L)
                                    }
                                }
                            }
                            lastScrollY = y
                            true
                        } else {
                            val dx = event.rawX - lastRawX
                            val dy = event.rawY - lastRawY
                            val distance = hypot(dx, dy)
                            if (abs(event.x - padDownX) + abs(event.y - padDownY) > dp(5)) moved = true
                            val now = SystemClock.uptimeMillis()
                            val elapsed = (now - lastMoveAt).coerceAtLeast(1L).toFloat()
                            val speed = distance / elapsed
                            val gain = (1.25f + min(speed * 1.8f, 1.15f)).coerceIn(1.25f, 2.4f)
                            cursorX = (cursorX + dx * gain).coerceIn(dp(4).toFloat(), metrics.widthPixels - dp(30).toFloat())
                            cursorY = (cursorY + dy * gain).coerceIn(dp(24).toFloat(), metrics.heightPixels - dp(160).toFloat())
                            lastRawX = event.rawX
                            lastRawY = event.rawY
                            lastMoveAt = now
                            moveCursor()
                            val bridge = SakuInputBridge.shell
                            if (bridge != null) {
                                try {
                                    if (dragMode) {
                                        bridge.injectMouse(MotionEvent.ACTION_MOVE, cursorX, cursorY, 0, InputDevice.SOURCE_MOUSE, MotionEvent.BUTTON_PRIMARY, dragDownTime)
                                    } else {
                                        bridge.injectMouse(MotionEvent.ACTION_HOVER_MOVE, cursorX, cursorY, 0, InputDevice.SOURCE_MOUSE, 0, now)
                                    }
                                } catch (_: Exception) {}
                            }
                            true
                        }
                    }
                    MotionEvent.ACTION_POINTER_UP -> {
                        multiTouch = event.pointerCount > 2
                        lastRawX = event.rawX
                        lastRawY = event.rawY
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (event.actionMasked == MotionEvent.ACTION_UP && !moved && !multiTouch) {
                            val bridge = SakuInputBridge.shell
                            if (bridge != null) {
                                try { bridge.execClick(cursorX, cursorY, 0) } catch (_: Exception) {
                                    VirtualMouseAccessibilityService.instance?.tapAt(cursorX, cursorY)
                                }
                            } else {
                                VirtualMouseAccessibilityService.instance?.tapAt(cursorX, cursorY)
                            }
                        }
                        multiTouch = false
                        true
                    }
                    else -> true
                }
            }
        }
        shell.addView(pad, LinearLayout.LayoutParams(-1, dp(118)).apply { topMargin = dp(5) })
        trackpadView = pad
        val hint = TextView(this).apply {
            text = "Mouse hardware via Shizuku (jika diaktifkan); Accessibility jadi fallback. Aktifkan Shizuku untuk hover dan scroll mouse asli."
            textSize = 10f
            setTextColor(Color.rgb(190, 198, 215))
            setPadding(dp(4), dp(5), dp(4), 0)
        }
        shell.addView(hint)
        hintView = hint
        val panelParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM; y = dp(8) }
        try {
            wm.addView(shell, panelParams)
            panel = shell
            cursor = TextView(this).apply {
                text = "➤"
                textSize = 24f
                setTextColor(Color.rgb(103, 178, 255))
                setShadowLayer(dp(3).toFloat(), 0f, 0f, Color.BLACK)
            }
            cursorParams = WindowManager.LayoutParams(
                dp(28), dp(28), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP or Gravity.START; x = cursorX.toInt(); y = cursorY.toInt() }
            wm.addView(cursor, cursorParams)
        } catch (_: Exception) {
            Toast.makeText(this, "Panel virtual tidak dapat ditampilkan", Toast.LENGTH_SHORT).show()
            stopSelf()
        }
    }

    private fun averageY(event: MotionEvent): Float {
        if (event.pointerCount < 2) return event.y
        return (event.getY(0) + event.getY(1)) / 2f
    }

    private fun toggleDragMode() {
        val bridge = SakuInputBridge.shell
        if (!dragMode) {
            dragMode = true
            dragDownTime = SystemClock.uptimeMillis()
            if (bridge != null) {
                try { bridge.injectMouse(MotionEvent.ACTION_DOWN, cursorX, cursorY, 0, InputDevice.SOURCE_MOUSE, MotionEvent.BUTTON_PRIMARY, dragDownTime) } catch (_: Exception) {}
            }
        } else {
            dragMode = false
            if (bridge != null) {
                try { bridge.injectMouse(MotionEvent.ACTION_UP, cursorX, cursorY, 0, InputDevice.SOURCE_MOUSE, 0, if (dragDownTime != 0L) dragDownTime else SystemClock.uptimeMillis()) } catch (_: Exception) {}
            }
            dragDownTime = 0L
        }
        panel?.let { view ->
            // Rebuild the toolbar label without restarting the input service.
            try {
                val row = (view as LinearLayout).getChildAt(0) as LinearLayout
                val dragButton = row.getChildAt(2) as TextView
                dragButton.text = if (dragMode) "Drag✓" else "Drag"
            } catch (_: Exception) {}
        }
    }

    private fun rightClick() {
        val bridge = SakuInputBridge.shell
        if (bridge != null) {
            try { bridge.execRightClick(cursorX, cursorY, 0); return } catch (_: Exception) {}
        }
        VirtualMouseAccessibilityService.instance?.longPressAt(cursorX, cursorY)
            ?: Toast.makeText(this, "Klik kanan butuh Shizuku aktif", Toast.LENGTH_SHORT).show()
    }


    private fun toggleKeyboard() {
        val shell = panel as? LinearLayout ?: return
        val pad = trackpadView ?: return
        val hint = hintView ?: return
        if (!keyboardVisible) {
            val keyboard = buildPcKeyboard()
            keyboardView = keyboard
            shell.removeView(pad)
            shell.removeView(hint)
            shell.addView(keyboard, LinearLayout.LayoutParams(-1, WindowManager.LayoutParams.WRAP_CONTENT))
            keyboardVisible = true
            if (SakuInputBridge.shell == null) {
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                Toast.makeText(this, "Keyboard PC tampil. Untuk input global, sambungkan Shizuku.", Toast.LENGTH_SHORT).show()
            }
        } else {
            keyboardView?.let { shell.removeView(it) }
            shell.addView(pad, LinearLayout.LayoutParams(-1, dp(118)).apply { topMargin = dp(5) })
            shell.addView(hint)
            keyboardView = null
            keyboardVisible = false
        }
    }

    private fun buildPcKeyboard(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(1), dp(4), dp(1), dp(1))
        }
        val rows = listOf(
            listOf("Esc","F1","F2","F3","F4","F5","F6","F7","F8","F9","F10","F11","F12"),
            listOf("`","1","2","3","4","5","6","7","8","9","0","-","=","⌫"),
            listOf("Tab","Q","W","E","R","T","Y","U","I","O","P","[","]"),
            listOf("Caps","A","S","D","F","G","H","J","K","L",";","'","Enter"),
            listOf("Shift","\\","Z","X","C","V","B","N","M",",",".","/","Shift"),
            listOf("Ctrl","Win","Alt","Space","Alt","←","↑","↓","→","Del")
        )
        rows.forEach { labels ->
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            labels.forEach { label ->
                val weight = when (label) {
                    "Space" -> 4f
                    "Enter", "Shift", "Backspace", "Caps", "Tab", "Ctrl", "Alt", "Win", "⌫", "Del" -> 1.5f
                    else -> 1f
                }
                val key = TextView(this).apply {
                    text = if (label == "Space") "Spasi" else label
                    textSize = if (label.length > 3) 8f else 10f
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    background = rounded(Color.rgb(43, 53, 73), 5)
                    isClickable = true
                    setOnClickListener { sendPcKey(label) }
                }
                line.addView(key, LinearLayout.LayoutParams(0, dp(31), weight).apply {
                    marginStart = dp(1); marginEnd = dp(1); bottomMargin = dp(3)
                })
            }
            root.addView(line)
        }
        return root
    }

    private fun sendPcKey(label: String) {
        when (label) {
            "Shift" -> { pcShift = !pcShift; return }
            "Ctrl" -> { pcCtrl = !pcCtrl; return }
            "Alt" -> { pcAlt = !pcAlt; return }
            "Win" -> { pcWin = !pcWin; return }
        }
        val bridge = SakuInputBridge.shell
        if (bridge == null) {
            Toast.makeText(this, "Aktifkan Shizuku dan izinkan Saku untuk mengetik.", Toast.LENGTH_SHORT).show()
            return
        }
        val code = when (label) {
            "Esc" -> android.view.KeyEvent.KEYCODE_ESCAPE
            "Tab" -> android.view.KeyEvent.KEYCODE_TAB
            "Caps" -> android.view.KeyEvent.KEYCODE_CAPS_LOCK
            "Enter" -> android.view.KeyEvent.KEYCODE_ENTER
            "⌫" -> android.view.KeyEvent.KEYCODE_DEL
            "Del" -> android.view.KeyEvent.KEYCODE_FORWARD_DEL
            "←" -> android.view.KeyEvent.KEYCODE_DPAD_LEFT
            "→" -> android.view.KeyEvent.KEYCODE_DPAD_RIGHT
            "↑" -> android.view.KeyEvent.KEYCODE_DPAD_UP
            "↓" -> android.view.KeyEvent.KEYCODE_DPAD_DOWN
            "Space" -> android.view.KeyEvent.KEYCODE_SPACE
            "F1" -> android.view.KeyEvent.KEYCODE_F1
            "F2" -> android.view.KeyEvent.KEYCODE_F2
            "F3" -> android.view.KeyEvent.KEYCODE_F3
            "F4" -> android.view.KeyEvent.KEYCODE_F4
            "F5" -> android.view.KeyEvent.KEYCODE_F5
            "F6" -> android.view.KeyEvent.KEYCODE_F6
            "F7" -> android.view.KeyEvent.KEYCODE_F7
            "F8" -> android.view.KeyEvent.KEYCODE_F8
            "F9" -> android.view.KeyEvent.KEYCODE_F9
            "F10" -> android.view.KeyEvent.KEYCODE_F10
            "F11" -> android.view.KeyEvent.KEYCODE_F11
            "F12" -> android.view.KeyEvent.KEYCODE_F12
            else -> keyCodeForLabel(label)
        }
        if (code == android.view.KeyEvent.KEYCODE_UNKNOWN) return
        val now = SystemClock.uptimeMillis()
        var meta = 0
        if (pcShift) meta = meta or android.view.KeyEvent.META_SHIFT_ON or android.view.KeyEvent.META_SHIFT_LEFT_ON
        if (pcCtrl) meta = meta or android.view.KeyEvent.META_CTRL_ON or android.view.KeyEvent.META_CTRL_LEFT_ON
        if (pcAlt) meta = meta or android.view.KeyEvent.META_ALT_ON or android.view.KeyEvent.META_ALT_LEFT_ON
        if (pcWin) meta = meta or android.view.KeyEvent.META_META_ON
        try {
            bridge.injectKey(code, android.view.KeyEvent.ACTION_DOWN, meta, 0)
            bridge.injectKey(code, android.view.KeyEvent.ACTION_UP, meta, 0)
            if (pcShift) pcShift = false
        } catch (_: Exception) {
            Toast.makeText(this, "Gagal mengirim tombol. Periksa koneksi Shizuku.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun keyCodeForLabel(label: String): Int {
        val c = label.firstOrNull()?.uppercaseChar() ?: return android.view.KeyEvent.KEYCODE_UNKNOWN
        return when {
            c in 'A'..'Z' -> android.view.KeyEvent.KEYCODE_A + (c - 'A')
            c in '1'..'9' -> android.view.KeyEvent.KEYCODE_1 + (c - '1')
            c == '0' -> android.view.KeyEvent.KEYCODE_0
            c == ',' -> android.view.KeyEvent.KEYCODE_COMMA
            c == '.' -> android.view.KeyEvent.KEYCODE_PERIOD
            c == '/' -> android.view.KeyEvent.KEYCODE_SLASH
            c == ';' -> android.view.KeyEvent.KEYCODE_SEMICOLON
            c == '\'' -> android.view.KeyEvent.KEYCODE_APOSTROPHE
            c == '[' -> android.view.KeyEvent.KEYCODE_LEFT_BRACKET
            c == ']' -> android.view.KeyEvent.KEYCODE_RIGHT_BRACKET
            c == '-' -> android.view.KeyEvent.KEYCODE_MINUS
            c == '=' -> android.view.KeyEvent.KEYCODE_EQUALS
            c == '\\' -> android.view.KeyEvent.KEYCODE_BACKSLASH
            c == '`' -> android.view.KeyEvent.KEYCODE_GRAVE
            else -> android.view.KeyEvent.KEYCODE_UNKNOWN
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun moveCursor() {
        val v = cursor ?: return
        cursorParams.x = cursorX.toInt()
        cursorParams.y = cursorY.toInt()
        try { wm.updateViewLayout(v, cursorParams) } catch (_: Exception) { }
    }

    private fun button(label: String, width: Int, action: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 11f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        background = rounded(Color.rgb(55, 67, 91), 12)
        isClickable = true
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(width, -1).apply { marginStart = dp(4) }
    }


    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius * resources.displayMetrics.density
    }

    override fun onDestroy() {
        if (dragMode) {
            try { SakuInputBridge.shell?.injectMouse(MotionEvent.ACTION_UP, cursorX, cursorY, 0, InputDevice.SOURCE_MOUSE, 0, if (dragDownTime != 0L) dragDownTime else SystemClock.uptimeMillis()) } catch (_: Exception) {}
        }
        listOfNotNull(panel, cursor).forEach { try { wm.removeView(it) } catch (_: Exception) { } }
        panel = null
        cursor = null
        if (foreground) {
            if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "com.sakuhome.launcher.STOP_VIRTUAL_CONTROLS"
    }
}

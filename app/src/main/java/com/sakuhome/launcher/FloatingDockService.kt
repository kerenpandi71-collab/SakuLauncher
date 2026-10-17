package com.sakuhome.launcher

import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.content.ClipData
import android.content.ClipboardManager
import android.view.inputmethod.InputMethodManager
import android.widget.PopupWindow
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class FloatingDockService : Service() {
    private lateinit var windowManager: WindowManager
    private var dockView: View? = null
    private var drawerView: View? = null
    private var navigationView: View? = null
    private var dockRevealHandle: View? = null
    private var assistiveBubble: View? = null
    private var gestureStartY = 0f
    private var isForeground = false
    private var lastLandscape: Boolean? = null
    private var quickMenuView: View? = null
    private var lastBubbleTapAt = 0L
    private var trackpadPanel: View? = null
    private var trackpadPointer: View? = null
    private var trackpadPointerX = 0f
    private var trackpadPointerY = 0f
    private var trackpadContext: Context? = null
    private var trackpadWindowManager: WindowManager? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_TRACKPAD_ON) {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Izinkan tampil di atas aplikasi lain untuk memakai trackpad", Toast.LENGTH_LONG).show()
                stopSelf()
                return START_NOT_STICKY
            }
            startAsForeground()
            showTrackpadOverlay()
            return START_STICKY
        }
        if (intent?.action == ACTION_TRACKPAD_OFF) {
            removeTrackpadOverlay()
            val overlayEnabled = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_OVERLAY_ENABLED, false)
            if (!overlayEnabled) {
                if (isForeground) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
                    isForeground = false
                }
                stopSelf()
                return START_NOT_STICKY
            }
        }
        if (intent?.action == ACTION_STOP) {
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_OVERLAY_ENABLED, false).apply()
            stopSelf()
            return START_NOT_STICKY
        }
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        startAsForeground()
        if (intent?.action == ACTION_REFRESH) removeDock()
        val landscapeNow = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        if (dockView != null && lastLandscape != landscapeNow) removeDock()
        if (dockView == null) showDock()
        showAssistiveBubble()
        return START_STICKY
    }

    private fun startAsForeground() {
        if (isForeground) return
        val channelId = "saku_floating_dock"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Dock mengambang Saku", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Menjaga dock Saku tetap tersedia di atas aplikasi"
                setShowBadge(false)
            }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
        val open = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_OPEN_START, true)
        }
        val openPending = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = Intent(this, FloatingDockService::class.java).setAction(ACTION_STOP)
        val stopPending = PendingIntent.getService(this, 1, stop, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, channelId) else Notification.Builder(this)
        val notification = builder
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Saku Dock aktif")
            .setContentText("Dock mengambang siap digunakan")
            .setContentIntent(openPending)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Matikan dock", stopPending)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        isForeground = true
    }

    /**
     * Persistent touchpad overlay. It covers the lower part of the screen while other
     * apps remain visible above it; accessibility gestures target the virtual pointer.
     * This is a compatibility fallback, not a claim that apps are running inside a
     * trusted Android VirtualDisplay.
     */
    private fun showTrackpadOverlay() {
        if (trackpadPanel != null || !Settings.canDrawOverlays(this)) return
        // Bind the trackpad overlay to the phone's default display even if the
        // launcher Activity was opened on a simulated/secondary display.
        val displayContext = if (Build.VERSION.SDK_INT >= 30) {
            val displayManager = getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
            displayManager.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.let { createDisplayContext(it) } ?: this
        } else this
        val overlayContext = if (Build.VERSION.SDK_INT >= 30) {
            displayContext.createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        } else displayContext
        val trackpadWm = overlayContext.getSystemService(WINDOW_SERVICE) as WindowManager
        trackpadContext = overlayContext
        trackpadWindowManager = trackpadWm
        val metrics = displayContext.resources.displayMetrics
        val screenW = metrics.widthPixels.toFloat()
        val screenH = metrics.heightPixels.toFloat()
        val padHeight = (screenH * 0.38f).toInt().coerceAtLeast(dp(190))
        trackpadPointerX = screenW * 0.5f
        trackpadPointerY = (screenH - padHeight) * 0.5f
        val dark = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_DARK_THEME, true)
        val textColor = if (dark) Color.WHITE else Color.rgb(24, 31, 48)
        val pad = LinearLayout(overlayContext).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(10))
            background = GradientDrawable().apply {
                setColor(if (dark) Color.rgb(25, 31, 43) else Color.rgb(226, 232, 242))
                cornerRadii = floatArrayOf(dp(22).toFloat(), dp(22).toFloat(), dp(22).toFloat(), dp(22).toFloat(), 0f, 0f, 0f, 0f)
                setStroke(dp(1), if (dark) Color.rgb(75, 91, 116) else Color.rgb(184, 198, 219))
            }
            elevation = dp(18).toFloat()
        }
        val label = TextView(overlayContext).apply {
            text = "SAKU TRACKPAD · satu jari gerakkan pointer · dua jari scroll"
            textSize = 10f; gravity = Gravity.CENTER; setTextColor(textColor); maxLines = 2
        }
        pad.addView(label, LinearLayout.LayoutParams(-1, dp(30)))
        val touchArea = TextView(overlayContext).apply {
            text = "AREA TRACKPAD\nGeser untuk menggerakkan pointer"
            textSize = 13f; gravity = Gravity.CENTER; setTextColor(textColor)
            background = GradientDrawable().apply {
                setColor(if (dark) Color.rgb(39, 48, 64) else Color.rgb(241, 245, 251))
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), if (dark) Color.rgb(83, 101, 130) else Color.rgb(190, 204, 224))
            }
        }
        pad.addView(touchArea, LinearLayout.LayoutParams(-1, 0, 1f))
        var lastX = 0f
        var lastY = 0f
        var moved = false
        var twoFinger = false
        var lastAverageY = 0f
        var scrollDistance = 0f
        fun movePointer(dx: Float, dy: Float) {
            trackpadPointerX = (trackpadPointerX + dx * 1.7f).coerceIn(dp(10).toFloat(), screenW - dp(10))
            trackpadPointerY = (trackpadPointerY + dy * 1.7f).coerceIn(dp(10).toFloat(), screenH - padHeight - dp(10))
            val pointer = trackpadPointer ?: return
            val lp = pointer.layoutParams as? WindowManager.LayoutParams ?: return
            lp.x = trackpadPointerX.toInt()
            lp.y = trackpadPointerY.toInt()
            try { trackpadWindowManager?.updateViewLayout(pointer, lp) } catch (_: Exception) { }
        }
        touchArea.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    lastX = event.x; lastY = event.y; moved = false; twoFinger = false; scrollDistance = 0f
                    true
                }
                android.view.MotionEvent.ACTION_POINTER_DOWN -> {
                    if (event.pointerCount >= 2) {
                        twoFinger = true
                        lastAverageY = (event.getY(0) + event.getY(1)) / 2f
                        scrollDistance = 0f
                        moved = true
                        label.text = "Dua jari: geser untuk menggulir"
                    }
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    if (twoFinger && event.pointerCount >= 2) {
                        val averageY = (event.getY(0) + event.getY(1)) / 2f
                        scrollDistance += averageY - lastAverageY
                        lastAverageY = averageY
                    } else {
                        val dx = event.x - lastX
                        val dy = event.y - lastY
                        if (kotlin.math.abs(dx) + kotlin.math.abs(dy) > dp(2)) moved = true
                        movePointer(dx, dy)
                        lastX = event.x; lastY = event.y
                    }
                    true
                }
                android.view.MotionEvent.ACTION_POINTER_UP -> {
                    if (twoFinger && kotlin.math.abs(scrollDistance) > dp(8)) {
                        val delta = (scrollDistance * 2.2f).toInt().coerceIn(-520, 520)
                        val targetId = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getInt(KEY_TARGET_DISPLAY_ID, 0)
                        val ok = if (targetId > 0) injectTargetDisplaySwipe(targetId, trackpadPointerX, trackpadPointerY, delta, screenW, screenH - padHeight)
                            else SakuNavigationAccessibilityService.performScrollAt(trackpadPointerX, trackpadPointerY, delta)
                        label.text = if (ok) "Scroll dua jari dikirim" else if (targetId > 0) "Input Root gagal pada display " + targetId else "Aktifkan Aksesibilitas Saku untuk scroll"
                    }
                    true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    if (twoFinger) {
                        twoFinger = false
                    } else if (!moved) {
                        val targetId = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getInt(KEY_TARGET_DISPLAY_ID, 0)
                        val ok = if (targetId > 0) injectTargetDisplayTap(targetId, trackpadPointerX, trackpadPointerY, screenW, screenH - padHeight)
                            else SakuNavigationAccessibilityService.performTap(trackpadPointerX, trackpadPointerY, 80L)
                        label.text = if (ok) "Klik dikirim" else if (targetId > 0) "Input Root gagal pada display " + targetId else "Aktifkan Aksesibilitas Saku untuk klik"
                    } else {
                        label.text = "Pointer dipindahkan"
                    }
                    view.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                    true
                }
                android.view.MotionEvent.ACTION_CANCEL -> { twoFinger = false; true }
                else -> true
            }
        }
        val panelParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, padHeight,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM or Gravity.START }
        val pointer = View(overlayContext).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.rgb(255, 214, 10)); setStroke(dp(2), Color.BLACK) }
            elevation = dp(30).toFloat()
            contentDescription = "Penunjuk virtual Saku"
        }
        val pointerParams = WindowManager.LayoutParams(
            dp(18), dp(18),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = trackpadPointerX.toInt()
            y = trackpadPointerY.toInt()
        }
        try {
            trackpadWm.addView(pad, panelParams)
            trackpadPanel = pad
            trackpadWm.addView(pointer, pointerParams)
            trackpadPointer = pointer
        } catch (_: Exception) {
            try { trackpadWm.removeView(pad) } catch (_: Exception) { }
            try { trackpadWm.removeView(pointer) } catch (_: Exception) { }
            trackpadPanel = null; trackpadPointer = null
            trackpadContext = null; trackpadWindowManager = null
            Toast.makeText(this, "Overlay trackpad gagal ditampilkan di layar HP", Toast.LENGTH_LONG).show()
        }
    }

    private fun removeTrackpadOverlay() {
        val wm = trackpadWindowManager ?: windowManager
        trackpadPanel?.let { try { wm.removeView(it) } catch (_: Exception) { } }
        trackpadPointer?.let { try { wm.removeView(it) } catch (_: Exception) { } }
        trackpadPanel = null
        trackpadPointer = null
        trackpadContext = null
        trackpadWindowManager = null
    }

    private fun showDock() {
        removeDockRevealHandle()
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val accentColor = prefs.getInt(KEY_ACCENT, Color.rgb(110, 151, 245))
        val darkTheme = prefs.getBoolean(KEY_DARK_THEME, true)
        val transparency = prefs.getInt(KEY_TRANSPARENCY, 88).coerceIn(0, 100)
        val iconSize = prefs.getInt(KEY_ICON_SIZE, 48).coerceIn(32, 64)
        val dockWidth = prefs.getInt(KEY_DOCK_WIDTH, 240).coerceIn(160, 360)
        val dockHeight = prefs.getInt(KEY_DOCK_HEIGHT, 64).coerceIn(48, 100)
        val landscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val dockPosition = prefs.getString(KEY_DOCK_POSITION, "bottom") ?: "bottom"
        val sideDock = dockPosition == "left" || dockPosition == "right"
        lastLandscape = landscape
        val hiddenPackages = prefs.getStringSet(KEY_HIDDEN_APPS, emptySet()) ?: emptySet()
        val pinnedPackages = if (prefs.contains(KEY_PINNED)) {
            prefs.getString(KEY_PINNED, "").orEmpty().split('\n').filter { it.isNotBlank() }
        } else loadApps().take(5).map { it.packageName }
        val apps = loadApps().associateBy { it.packageName }
        val dock = LinearLayout(this).apply {
            orientation = if (sideDock) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(7), dp(5), dp(7), dp(5))
            background = GradientDrawable().apply {
                setColor(Color.argb(transparency * 255 / 100, if (darkTheme) 31 else 247, if (darkTheme) 37 else 249, if (darkTheme) 52 else 253))
                setStroke(dp(1), Color.argb(150, 255, 255, 255))
                cornerRadius = dp(22).toFloat()
            }
            elevation = dp(12).toFloat()
        }
        fun dockActionButton(label: String, description: String, highlighted: Boolean = false, action: () -> Unit): TextView =
            TextView(this).apply {
                text = label
                textSize = if (label == "⚙") 20f else 21f
                gravity = Gravity.CENTER
                setTextColor(if (darkTheme) Color.WHITE else Color.rgb(24, 31, 48))
                contentDescription = description
                background = GradientDrawable().apply {
                    setColor(if (highlighted) accentColor else Color.argb(if (darkTheme) 28 else 20, if (darkTheme) 255 else 22, if (darkTheme) 255 else 30, if (darkTheme) 255 else 46))
                    cornerRadius = dp(15).toFloat()
                }
                isClickable = true
                isFocusable = true
                setOnClickListener { action() }
            }
        fun actionDivider() {
            dock.addView(View(this).apply {
                setBackgroundColor(Color.argb(if (darkTheme) 68 else 50, if (darkTheme) 255 else 25, if (darkTheme) 255 else 31, if (darkTheme) 255 else 48))
                isClickable = false
            }, if (sideDock) LinearLayout.LayoutParams(dp(28), dp(1)).apply {
                gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(4); bottomMargin = dp(4)
            } else LinearLayout.LayoutParams(dp(1), dp(28)).apply {
                gravity = Gravity.CENTER_VERTICAL; marginStart = dp(4); marginEnd = dp(4)
            })
        }
        val actionButtonWidth = dp(if (sideDock) 56 else 44)
        val actionButtonHeight = dp(if (sideDock) 44 else 48)
        val startButton = dockActionButton("▦", "Buka menu aplikasi", true) {
            if (drawerView == null) showFloatingDrawer() else hideFloatingDrawer()
        }
        dock.addView(startButton, LinearLayout.LayoutParams(actionButtonWidth, actionButtonHeight).apply {
            if (sideDock) gravity = Gravity.CENTER_HORIZONTAL else gravity = Gravity.CENTER_VERTICAL
        })
        actionDivider()
        val settingsButton = dockActionButton("⚙", "Pengaturan Saku Launcher") {
            val openSettings = Intent(this@FloatingDockService, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(MainActivity.EXTRA_OPEN_SETTINGS, true)
            }
            try { startActivity(openSettings) } catch (_: Exception) {
                Toast.makeText(this@FloatingDockService, "Pengaturan Saku Launcher tidak dapat dibuka", Toast.LENGTH_SHORT).show()
            }
        }
        dock.addView(settingsButton, LinearLayout.LayoutParams(actionButtonWidth, actionButtonHeight).apply {
            if (sideDock) gravity = Gravity.CENTER_HORIZONTAL else gravity = Gravity.CENTER_VERTICAL
        })
        // One UI-inspired navigation cluster: separate hit targets and subtle dividers
        // reduce accidental taps while keeping the dock compact.
        fun navDivider() {
            val divider = View(this).apply {
                setBackgroundColor(Color.argb(if (darkTheme) 72 else 55, if (darkTheme) 255 else 25, if (darkTheme) 255 else 31, if (darkTheme) 255 else 48))
                isClickable = false
                isFocusable = false
            }
            dock.addView(divider, if (sideDock) {
                LinearLayout.LayoutParams(dp(28), dp(1)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(3); bottomMargin = dp(3) }
            } else {
                LinearLayout.LayoutParams(dp(1), dp(28)).apply { gravity = Gravity.CENTER_VERTICAL; marginStart = dp(3); marginEnd = dp(3) }
            })
        }
        fun navButton(label: String, description: String, action: () -> Unit) {
            val button = TextView(this).apply {
                text = label
                contentDescription = description
                textSize = 23f
                gravity = Gravity.CENTER
                setTextColor(if (darkTheme) Color.WHITE else Color.rgb(24, 31, 48))
                background = GradientDrawable().apply {
                    setColor(Color.argb(if (darkTheme) 24 else 18, if (darkTheme) 255 else 20, if (darkTheme) 255 else 28, if (darkTheme) 255 else 42))
                    cornerRadius = dp(15).toFloat()
                }
                isClickable = true
                isFocusable = true
                setOnClickListener { action() }
            }
            dock.addView(button, if (sideDock) LinearLayout.LayoutParams(dp(56), dp(44)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(2); bottomMargin = dp(2) } else LinearLayout.LayoutParams(dp(44), dp(48)).apply { gravity = Gravity.CENTER_VERTICAL; marginStart = dp(2); marginEnd = dp(2) })
        }
        navDivider()
        navButton("‹", "Kembali") {
            if (!SakuNavigationAccessibilityService.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
                Toast.makeText(this, "Aktifkan aksesibilitas Navigasi desktop Saku", Toast.LENGTH_SHORT).show()
        }
        navDivider()
        navButton("⌂", "Beranda") {
            hideFloatingDrawer()
            try { startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { }
        }
        navDivider()
        navButton("▤", "Aplikasi terbaru") {
            if (!SakuNavigationAccessibilityService.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS))
                Toast.makeText(this, "Aktifkan aksesibilitas Navigasi desktop Saku", Toast.LENGTH_SHORT).show()
        }

        val params = WindowManager.LayoutParams(
            dp(if (sideDock) 76 else if (landscape) 300 else 280),
            dp(if (sideDock) 280 else dockHeight),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = when (dockPosition) {
                "top" -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
                "left" -> Gravity.LEFT or Gravity.CENTER_VERTICAL
                "right" -> Gravity.RIGHT or Gravity.CENTER_VERTICAL
                else -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            }
            when (dockPosition) {
                "top" -> y = dp(12)
                "left" -> x = dp(8)
                "right" -> x = dp(8)
                else -> y = dp(if (landscape) 8 else 28)
            }
        }
        try {
            windowManager.addView(dock, params)
            dockView = dock
        } catch (_: Exception) {
            Toast.makeText(this, "Tidak dapat menampilkan dock di atas aplikasi", Toast.LENGTH_SHORT).show()
            stopSelf()
        }
    }

    private fun showFloatingDrawer() {
        if (!Settings.canDrawOverlays(this)) return
        hideFloatingDrawer()
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val dark = prefs.getBoolean(KEY_DARK_THEME, true)
        val panelColor = if (dark) Color.argb(242, 31, 37, 52) else Color.argb(242, 248, 249, 253)
        val textColor = if (dark) Color.WHITE else Color.rgb(24, 31, 48)
        val accent = prefs.getInt(KEY_ACCENT, Color.rgb(110, 151, 245))
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(10), dp(10))
            background = GradientDrawable().apply {
                setColor(panelColor)
                setStroke(dp(1), Color.argb(150, 255, 255, 255))
                cornerRadius = dp(24).toFloat()
            }
            elevation = dp(16).toFloat()
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply {
            text = "Aplikasi"
            textSize = 20f
            setTextColor(textColor)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        header.addView(TextView(this).apply {
            text = "×"
            textSize = 26f
            gravity = Gravity.CENTER
            setTextColor(textColor)
            background = rounded(Color.argb(100, 110, 151, 245), 18)
            setOnClickListener { hideFloatingDrawer() }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        panel.addView(header)
        val search = android.widget.EditText(this).apply {
            hint = "Cari aplikasi"
            textSize = 14f
            setSingleLine(true)
            setTextColor(textColor)
            setHintTextColor(Color.GRAY)
            setPadding(dp(12), 0, dp(12), 0)
            background = GradientDrawable().apply {
                setColor(Color.argb(45, 255, 255, 255))
                setStroke(dp(1), Color.argb(120, 255, 255, 255))
                cornerRadius = dp(16).toFloat()
            }
        }
        panel.addView(search, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8); bottomMargin = dp(8) })
        val scroll = android.widget.ScrollView(this).apply { isFillViewport = true }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val allApps = loadApps().sortedBy { it.label.lowercase() }
        fun populate(query: String) {
            list.removeAllViews()
            allApps.filter { it.label.contains(query, ignoreCase = true) }.forEach { app ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(8), dp(4), dp(10), dp(4))
                    background = GradientDrawable().apply {
                        setColor(Color.argb(if (dark) 18 else 12, if (dark) 255 else 30, if (dark) 255 else 38, if (dark) 255 else 54))
                        cornerRadius = dp(14).toFloat()
                    }
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        val targetId = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getInt(KEY_TARGET_DISPLAY_ID, 0)
                        if (targetId > 0) {
                            Thread {
                                val ok = runRootInputCommand("am start --display " + targetId +
                                    " -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p " + app.packageName)
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    if (ok) hideFloatingDrawer()
                                    else Toast.makeText(this@FloatingDockService, "Gagal membuka aplikasi pada display " + targetId, Toast.LENGTH_LONG).show()
                                }
                            }.start()
                        } else {
                            val launch = packageManager.getLaunchIntentForPackage(app.packageName)
                            if (launch != null) {
                                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                try { startActivity(launch); hideFloatingDrawer() } catch (_: Exception) { Toast.makeText(this@FloatingDockService, "Aplikasi tidak dapat dibuka", Toast.LENGTH_SHORT).show() }
                            }
                        }
                    }
                }
                row.addView(ImageView(this).apply { setImageDrawable(app.icon); scaleType = ImageView.ScaleType.FIT_CENTER }, LinearLayout.LayoutParams(dp(40), dp(40)))
                row.addView(TextView(this).apply {
                    text = app.label
                    textSize = 14f
                    setTextColor(textColor)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(dp(12), 0, 0, 0)
                }, LinearLayout.LayoutParams(0, dp(40), 1f))
                list.addView(row, LinearLayout.LayoutParams(-1, dp(52)).apply { bottomMargin = dp(4) })
            }
        }
        populate("")
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = populate(s?.toString().orEmpty())
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
        scroll.addView(list)
        panel.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val panelParams = WindowManager.LayoutParams(
            dp(300), WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.LEFT or Gravity.TOP; x = dp(8); y = dp(8); softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE }
        try {
            windowManager.addView(panel, panelParams)
            drawerView = panel
        } catch (_: Exception) {
            try { windowManager.removeView(panel) } catch (_: Exception) { }
            drawerView = null
            Toast.makeText(this, "Drawer overlay tidak dapat ditampilkan", Toast.LENGTH_SHORT).show()
        }
    }

    private fun injectTargetDisplayTap(displayId: Int, x: Float, y: Float, screenWidth: Float, desktopHeight: Float): Boolean {
        val target = (getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager).getDisplay(displayId) ?: return false
        val width = target.mode.physicalWidth.coerceAtLeast(2)
        val height = target.mode.physicalHeight.coerceAtLeast(2)
        val displayX = (x / screenWidth * width).toInt().coerceIn(1, width - 1)
        val displayY = (y / desktopHeight * height).toInt().coerceIn(1, height - 1)
        return runRootInputCommand("input -d " + displayId + " tap " + displayX + " " + displayY)
    }

    private fun injectTargetDisplaySwipe(displayId: Int, x: Float, y: Float, deltaY: Int, screenWidth: Float, desktopHeight: Float): Boolean {
        val target = (getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager).getDisplay(displayId) ?: return false
        val width = target.mode.physicalWidth.coerceAtLeast(2)
        val height = target.mode.physicalHeight.coerceAtLeast(2)
        val displayX = (x / screenWidth * width).toInt().coerceIn(1, width - 1)
        val startY = (y / desktopHeight * height).toInt().coerceIn(2, height - 2)
        val endY = (startY + deltaY * height / desktopHeight).coerceIn(2, height - 2)
        return runRootInputCommand("input -d " + displayId + " swipe " + displayX + " " + startY + " " + displayX + " " + endY + " 300")
    }

    /** Runs only internally constructed input/launch commands after the user selected a target display. */
    private fun runRootInputCommand(command: String): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val stdout = process.inputStream.bufferedReader().use { it.readText() }
            val stderr = process.errorStream.bufferedReader().use { it.readText() }
            process.waitFor() == 0 && !stderr.contains("Error", ignoreCase = true) &&
                !stdout.contains("Error", ignoreCase = true) && !stdout.contains("Exception", ignoreCase = true)
        } catch (_: Exception) { false }
    }

    private fun hideFloatingDrawer() {
        navigationView?.let { try { windowManager.removeView(it) } catch (_: Exception) { } }
        drawerView?.let { try { windowManager.removeView(it) } catch (_: Exception) { } }
        navigationView = null
        drawerView = null
    }

    private fun loadApps(): List<AppInfo> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val matches: List<ResolveInfo> = packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        return matches.filter { it.activityInfo.packageName != packageName }
            .distinctBy { it.activityInfo.packageName }
            .map { AppInfo(it.activityInfo.packageName, it.loadLabel(packageManager).toString(), it.loadIcon(packageManager)) }
    }

    private fun hideDockTemporarily() {
        hideQuickMenu()
        hideFloatingDrawer()
        dockView?.let { view -> try { windowManager.removeView(view) } catch (_: Exception) { } }
        dockView = null
        removeDockRevealHandle()
        showAssistiveBubble()
    }

    private fun showAssistiveBubble() {
        if (assistiveBubble != null || !Settings.canDrawOverlays(this)) return
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val size = prefs.getInt(KEY_BUBBLE_SIZE, 54).coerceIn(40, 80)
        val accent = prefs.getInt(KEY_ACCENT, Color.rgb(110, 151, 245))
        val bubble = TextView(this).apply {
            text = "S"
            textSize = 22f
            gravity = Gravity.CENTER
            contentDescription = "Saku Dock: ketuk untuk tampilkan atau sembunyikan dock"
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(accent); setStroke(dp(2), Color.argb(190, 255, 255, 255)) }
            elevation = dp(14).toFloat()
            setOnClickListener { if (dockView == null) showDock() else hideDockTemporarily() }
        }
        val savedX = prefs.getInt(KEY_BUBBLE_X, -1)
        val savedY = prefs.getInt(KEY_BUBBLE_Y, -1)
        val params = WindowManager.LayoutParams(dp(size), dp(size),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.RIGHT or Gravity.TOP
            x = if (savedX >= 0) savedX else dp(12)
            y = if (savedY >= 0) savedY else dp(260)
        }
        var downRawX = 0f
        var downRawY = 0f
        var startX = params.x
        var startY = params.y
        var dragged = false
        bubble.setOnTouchListener { view, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = params.x
                    startY = params.y
                    dragged = false
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (kotlin.math.abs(dx) > dp(5) || kotlin.math.abs(dy) > dp(5)) dragged = true
                    if (dragged) {
                        val maxX = (resources.displayMetrics.widthPixels - view.width).coerceAtLeast(0)
                        val maxY = (resources.displayMetrics.heightPixels - view.height).coerceAtLeast(0)
                        params.x = (startX - dx.toInt()).coerceIn(0, maxX)
                        params.y = (startY + dy.toInt()).coerceIn(0, maxY)
                        try { windowManager.updateViewLayout(view, params) } catch (_: Exception) { }
                    }
                    true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    if (dragged) {
                        prefs.edit().putInt(KEY_BUBBLE_X, params.x).putInt(KEY_BUBBLE_Y, params.y).apply()
                    } else {
                        val now = SystemClock.uptimeMillis()
                        if (now - lastBubbleTapAt <= 330L) {
                            lastBubbleTapAt = 0L
                            showQuickMenu()
                        } else {
                            lastBubbleTapAt = now
                            bubble.postDelayed({
                                if (lastBubbleTapAt == now) {
                                    if (dockView == null) showDock() else hideDockTemporarily()
                                    lastBubbleTapAt = 0L
                                }
                            }, 340L)
                        }
                    }
                    true
                }
                else -> true
            }
        }
        try { windowManager.addView(bubble, params); assistiveBubble = bubble } catch (_: Exception) { assistiveBubble = null }
    }

    private fun showQuickMenu() {
        if (!Settings.canDrawOverlays(this)) return
        quickMenuView?.let { try { windowManager.removeView(it) } catch (_: Exception) { }; quickMenuView = null }
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val dark = prefs.getBoolean(KEY_DARK_THEME, true)
        val foreground = if (dark) Color.WHITE else Color.rgb(24, 31, 48)
        val background = if (dark) Color.rgb(31, 37, 52) else Color.rgb(248, 249, 253)
        val menu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            this.background = GradientDrawable().apply {
                setColor(background); setStroke(dp(1), Color.argb(150, 255, 255, 255)); cornerRadius = dp(22).toFloat()
            }
            elevation = dp(20).toFloat()
        }
        menu.addView(TextView(this).apply {
            text = "Saku Assist"; textSize = 17f; setTextColor(foreground)
            setPadding(dp(8), dp(5), dp(8), dp(10))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        fun action(label: String, description: String, run: () -> Unit) {
            menu.addView(TextView(this).apply {
                text = label; textSize = 14f; setTextColor(foreground); gravity = Gravity.CENTER_VERTICAL
                contentDescription = description
                setPadding(dp(12), dp(10), dp(12), dp(10))
                this.background = GradientDrawable().apply { setColor(Color.argb(if (dark) 24 else 16, 128, 150, 210)); cornerRadius = dp(14).toFloat() }
                isClickable = true; isFocusable = true
                setOnClickListener { run(); hideQuickMenu() }
            }, LinearLayout.LayoutParams(-1, dp(46)).apply { bottomMargin = dp(6) })
        }
        action("⇧  Scroll ke atas", "Gulir layar ke atas") { SakuNavigationAccessibilityService.performScroll(false) }
        action("⇩  Scroll ke bawah", "Gulir layar ke bawah") { SakuNavigationAccessibilityService.performScroll(true) }
        action("⌨  Keyboard bergaya iOS", "Pilih keyboard Saku bergaya iOS") {
            try { (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker() }
            catch (_: Exception) { Toast.makeText(this, "Aktifkan Keyboard Saku di Pengaturan > Bahasa & masukan", Toast.LENGTH_LONG).show() }
        }
        action("▤  Papan klip", "Lihat teks terakhir di papan klip") {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val text = if (clipboard.hasPrimaryClip()) clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty() else ""
            android.app.AlertDialog.Builder(this)
                .setTitle("Papan klip")
                .setMessage(if (text.isBlank()) "Papan klip masih kosong." else text)
                .setPositiveButton("Salin lagi") { _, _ ->
                    if (text.isNotBlank()) clipboard.setPrimaryClip(ClipData.newPlainText("Saku clipboard", text))
                }
                .setNegativeButton("Tutup", null).show()
        }
        action("×  Tutup", "Tutup menu Saku Assist") { }
        val params = WindowManager.LayoutParams(
            dp(250), WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.RIGHT
            x = dp(16); y = dp(160)
        }
        try { windowManager.addView(menu, params); quickMenuView = menu }
        catch (_: Exception) { quickMenuView = null; Toast.makeText(this, "Menu bantuan tidak dapat ditampilkan", Toast.LENGTH_SHORT).show() }
    }

    private fun hideQuickMenu() {
        quickMenuView?.let { try { windowManager.removeView(it) } catch (_: Exception) { } }
        quickMenuView = null
    }

    private fun removeAssistiveBubble() {
        assistiveBubble?.let { try { windowManager.removeView(it) } catch (_: Exception) { } }
        assistiveBubble = null
    }
    private fun removeDockRevealHandle() {
        dockRevealHandle?.let { view ->
            try { windowManager.removeView(view) } catch (_: Exception) { }
        }
        dockRevealHandle = null
    }

    private fun removeDock() {
        hideFloatingDrawer()
        dockView?.let { view ->
            try { windowManager.removeView(view) } catch (_: Exception) { }
        }
        dockView = null
        removeDockRevealHandle()
        removeAssistiveBubble()
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        removeTrackpadOverlay()
        removeDock()
        if (isForeground) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private data class AppInfo(val packageName: String, val label: String, val icon: android.graphics.drawable.Drawable)

    companion object {
        const val ACTION_REFRESH = "com.sakuhome.launcher.REFRESH_DOCK"
        const val ACTION_STOP = "com.sakuhome.launcher.STOP_DOCK"
        const val ACTION_TRACKPAD_ON = "com.sakuhome.launcher.TRACKPAD_ON"
        const val ACTION_TRACKPAD_OFF = "com.sakuhome.launcher.TRACKPAD_OFF"
        private const val NOTIFICATION_ID = 47
        private const val PREFS_NAME = "saku_dock_settings"
        private const val KEY_PINNED = "pinned_packages_order"
        private const val KEY_OVERLAY_ENABLED = "overlay_enabled"
        private const val KEY_ACCENT = "accent_color"
        private const val KEY_BUBBLE_SIZE = "assistive_bubble_size_dp"
        private const val KEY_BUBBLE_X = "assistive_bubble_x"
        private const val KEY_BUBBLE_Y = "assistive_bubble_y"
        private const val KEY_DOCK_POSITION = "dock_navigation_position"
        private const val KEY_DARK_THEME = "dark_theme"
        private const val KEY_TRANSPARENCY = "dock_transparency"
        private const val KEY_ICON_SIZE = "app_icon_size_dp"
        private const val KEY_DOCK_WIDTH = "dock_width_dp"
        private const val KEY_DOCK_HEIGHT = "dock_height_dp"
        private const val KEY_HIDDEN_APPS = "hidden_app_packages"
        private const val KEY_TARGET_DISPLAY_ID = "saku_target_display_id"
    }
}

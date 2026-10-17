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
    private var gestureStartY = 0f
    private var isForeground = false
    private var lastLandscape: Boolean? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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
        lastLandscape = landscape
        val hiddenPackages = prefs.getStringSet(KEY_HIDDEN_APPS, emptySet()) ?: emptySet()
        val pinnedPackages = if (prefs.contains(KEY_PINNED)) {
            prefs.getString(KEY_PINNED, "").orEmpty().split('\n').filter { it.isNotBlank() }
        } else loadApps().take(5).map { it.packageName }
        val apps = loadApps().associateBy { it.packageName }
        val dock = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(7), dp(5), dp(7), dp(5))
            background = GradientDrawable().apply {
                setColor(Color.argb(transparency * 255 / 100, if (darkTheme) 31 else 247, if (darkTheme) 37 else 249, if (darkTheme) 52 else 253))
                setStroke(dp(1), Color.argb(150, 255, 255, 255))
                cornerRadius = dp(22).toFloat()
            }
            elevation = dp(12).toFloat()
        }
        val startButton = TextView(this).apply {
            text = "▦"
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(if (darkTheme) Color.WHITE else Color.rgb(24, 31, 48))
            background = rounded(accentColor, 17)
            contentDescription = "Buka Start"
            setOnClickListener {
                if (drawerView == null) showFloatingDrawer() else hideFloatingDrawer()
            }
        }
        dock.addView(startButton, LinearLayout.LayoutParams(dp(if (landscape) 42 else 45), dp(48)))
        val settingsButton = TextView(this).apply {
            text = "⚙"
            textSize = 21f
            gravity = Gravity.CENTER
            setTextColor(if (darkTheme) Color.WHITE else Color.rgb(24, 31, 48))
            contentDescription = "Pengaturan Saku Launcher"
            setOnClickListener {
                val openSettings = Intent(this@FloatingDockService, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    putExtra(MainActivity.EXTRA_OPEN_SETTINGS, true)
                }
                try { startActivity(openSettings) } catch (_: Exception) {
                    Toast.makeText(this@FloatingDockService, "Pengaturan Saku Launcher tidak dapat dibuka", Toast.LENGTH_SHORT).show()
                }
            }
        }
        dock.addView(settingsButton, LinearLayout.LayoutParams(dp(if (landscape) 42 else 40), dp(48)))
        if (!landscape) pinnedPackages.filterNot { hiddenPackages.contains(it) }.take(6).mapNotNull { apps[it] }.forEach { app ->
            val icon = ImageView(this).apply {
                setImageDrawable(app.icon)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(dp(7), dp(7), dp(7), dp(7))
                contentDescription = app.label
                setOnClickListener {
                    val launch = packageManager.getLaunchIntentForPackage(app.packageName)
                    if (launch != null) {
                        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        try { startActivity(launch) } catch (_: Exception) {
                            Toast.makeText(this@FloatingDockService, "Aplikasi tidak dapat dibuka", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                setOnLongClickListener {
                    val launch = packageManager.getLaunchIntentForPackage(app.packageName)
                    if (launch != null) {
                        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        try { startActivity(launch) } catch (_: Exception) {
                            Toast.makeText(this@FloatingDockService, "Aplikasi tidak dapat dibuka", Toast.LENGTH_SHORT).show()
                        }
                    }
                    true
                }
            }
            dock.addView(icon, LinearLayout.LayoutParams(dp(iconSize.coerceAtMost(dockHeight - 12)), -1))
        }
        if (landscape) {
            fun navButton(label: String, description: String, action: () -> Unit) {
                val button = TextView(this).apply {
                    text = label
                    contentDescription = description
                    textSize = 22f
                    gravity = Gravity.CENTER
                    setTextColor(if (darkTheme) Color.WHITE else Color.rgb(24, 31, 48))
                    setOnTouchListener { _, event ->
                        when (event.action) {
                            android.view.MotionEvent.ACTION_DOWN -> { gestureStartY = event.rawY; true }
                            android.view.MotionEvent.ACTION_UP -> {
                                val deltaY = event.rawY - gestureStartY
                                if (deltaY < -dp(45)) hideDockTemporarily() else action()
                                true
                            }
                            else -> true
                        }
                    }
                }
                dock.addView(button, LinearLayout.LayoutParams(dp(48), -1))
            }
            navButton("‹", "Kembali") {
                if (!SakuNavigationAccessibilityService.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
                    Toast.makeText(this, "Aktifkan aksesibilitas Navigasi desktop Saku", Toast.LENGTH_SHORT).show()
            }
            navButton("○", "Beranda") {
                hideFloatingDrawer()
                try { startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { }
            }
            navButton("□", "Aplikasi terbaru") {
                if (!SakuNavigationAccessibilityService.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS))
                    Toast.makeText(this, "Aktifkan aksesibilitas Navigasi desktop Saku", Toast.LENGTH_SHORT).show()
            }
        }

        val params = WindowManager.LayoutParams(
            dp(if (landscape) 248 else dockWidth),
            dp(dockHeight),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(if (landscape) 8 else 28)
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
                    setPadding(dp(5), dp(5), dp(5), dp(5))
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        val launch = packageManager.getLaunchIntentForPackage(app.packageName)
                        if (launch != null) {
                            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            try { startActivity(launch); hideFloatingDrawer() } catch (_: Exception) { Toast.makeText(this@FloatingDockService, "Aplikasi tidak dapat dibuka", Toast.LENGTH_SHORT).show() }
                        }
                    }
                }
                row.addView(ImageView(this).apply { setImageDrawable(app.icon); scaleType = ImageView.ScaleType.FIT_CENTER }, LinearLayout.LayoutParams(dp(42), dp(42)))
                row.addView(TextView(this).apply {
                    text = app.label
                    textSize = 14f
                    setTextColor(textColor)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(dp(12), 0, 0, 0)
                }, LinearLayout.LayoutParams(0, dp(48), 1f))
                list.addView(row, LinearLayout.LayoutParams(-1, dp(50)))
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
        hideFloatingDrawer()
        dockView?.let { view ->
            try { windowManager.removeView(view) } catch (_: Exception) { }
        }
        dockView = null
        removeDockRevealHandle()

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val darkTheme = prefs.getBoolean(KEY_DARK_THEME, true)
        val handle = View(this).apply {
            contentDescription = "Swipe ke bawah untuk menampilkan kembali navigasi Saku"
            setBackgroundColor(Color.TRANSPARENT)
            setOnTouchListener { _, event ->
                when (event.action) {
                    android.view.MotionEvent.ACTION_DOWN -> { gestureStartY = event.rawY; true }
                    android.view.MotionEvent.ACTION_UP -> {
                        val deltaY = event.rawY - gestureStartY
                        if (deltaY > dp(35)) {
                            removeDockRevealHandle()
                            showDock()
                        }
                        true
                    }
                    else -> true
                }
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, dp(18),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
            y = 0
        }
        try {
            windowManager.addView(handle, params)
            dockRevealHandle = handle
        } catch (_: Exception) {
            dockRevealHandle = null
            showDock()
        }
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
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
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
        private const val NOTIFICATION_ID = 47
        private const val PREFS_NAME = "saku_dock_settings"
        private const val KEY_PINNED = "pinned_packages_order"
        private const val KEY_OVERLAY_ENABLED = "overlay_enabled"
        private const val KEY_ACCENT = "accent_color"
        private const val KEY_DARK_THEME = "dark_theme"
        private const val KEY_TRANSPARENCY = "dock_transparency"
        private const val KEY_ICON_SIZE = "app_icon_size_dp"
        private const val KEY_DOCK_WIDTH = "dock_width_dp"
        private const val KEY_DOCK_HEIGHT = "dock_height_dp"
        private const val KEY_HIDDEN_APPS = "hidden_app_packages"
    }
}

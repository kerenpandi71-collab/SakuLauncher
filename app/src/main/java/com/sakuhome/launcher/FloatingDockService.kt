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
    private var isForeground = false

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
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val accentColor = prefs.getInt(KEY_ACCENT, Color.rgb(110, 151, 245))
        val darkTheme = prefs.getBoolean(KEY_DARK_THEME, true)
        val pinnedPackages = if (prefs.contains(KEY_PINNED)) {
            prefs.getString(KEY_PINNED, "").orEmpty().split('\n').filter { it.isNotBlank() }
        } else loadApps().take(5).map { it.packageName }
        val apps = loadApps().associateBy { it.packageName }
        val dock = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(7), dp(5), dp(7), dp(5))
            background = GradientDrawable().apply {
                setColor(if (darkTheme) Color.rgb(31, 37, 52) else Color.rgb(247, 249, 253))
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
                val open = Intent(this@FloatingDockService, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra(MainActivity.EXTRA_OPEN_START, true)
                }
                startActivity(open)
            }
        }
        dock.addView(startButton, LinearLayout.LayoutParams(dp(45), dp(48)))
        pinnedPackages.take(6).mapNotNull { apps[it] }.forEach { app ->
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
                        val width = resources.displayMetrics.widthPixels
                        val height = resources.displayMetrics.heightPixels
                        val margin = dp(24)
                        val bounds = Rect(margin, dp(64), width - margin, height - dp(84))
                        try {
                            val options = ActivityOptions.makeBasic().setLaunchBounds(bounds)
                            startActivity(launch, options.toBundle())
                        } catch (_: Exception) {
                            Toast.makeText(this@FloatingDockService, "Android tidak mengizinkan jendela bebas untuk aplikasi ini", Toast.LENGTH_SHORT).show()
                        }
                    }
                    true
                }
            }
            dock.addView(icon, LinearLayout.LayoutParams(dp(46), dp(48)))
        }
        val settings = TextView(this).apply {
            text = "⋯"
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(if (darkTheme) Color.rgb(200, 207, 221) else Color.rgb(83, 96, 120))
            contentDescription = "Pengaturan dock"
            setOnClickListener {
                val open = Intent(this@FloatingDockService, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra(MainActivity.EXTRA_OPEN_SETTINGS, true)
                }
                startActivity(open)
            }
        }
        dock.addView(settings, LinearLayout.LayoutParams(dp(38), dp(48)))

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            dp(58),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(28)
        }
        try {
            windowManager.addView(dock, params)
            dockView = dock
        } catch (_: Exception) {
            Toast.makeText(this, "Tidak dapat menampilkan dock di atas aplikasi", Toast.LENGTH_SHORT).show()
            stopSelf()
        }
    }

    private fun loadApps(): List<AppInfo> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val matches: List<ResolveInfo> = packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        return matches.filter { it.activityInfo.packageName != packageName }
            .distinctBy { it.activityInfo.packageName }
            .map { AppInfo(it.activityInfo.packageName, it.loadLabel(packageManager).toString(), it.loadIcon(packageManager)) }
    }

    private fun removeDock() {
        dockView?.let { view ->
            try { windowManager.removeView(view) } catch (_: Exception) { }
        }
        dockView = null
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
    }
}

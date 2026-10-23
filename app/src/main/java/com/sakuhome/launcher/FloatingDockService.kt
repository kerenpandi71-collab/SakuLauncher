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
        val liquidGlass = prefs.getBoolean(KEY_LIQUID_GLASS, true)
        val transparency = prefs.getInt(KEY_TRANSPARENCY, 88).coerceIn(0, 100)
        val iconSize = prefs.getInt(KEY_ICON_SIZE, 48).coerceIn(32, 64)
        val hiddenPackages = prefs.getStringSet(KEY_HIDDEN_APPS, emptySet()) ?: emptySet()
        val pinnedPackages = if (prefs.contains(KEY_PINNED)) {
            prefs.getString(KEY_PINNED, "").orEmpty().split('\n').filter { it.isNotBlank() }
        } else loadApps().take(5).map { it.packageName }
        val apps = loadApps().associateBy { it.packageName }
        val dock = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(5), dp(8), dp(5))
            background = GradientDrawable().apply {
                setColor(Color.argb(transparency * 255 / 100, if (darkTheme) 31 else 247, if (darkTheme) 37 else 249, if (darkTheme) 52 else 253))
                setStroke(dp(1), Color.argb(170, 255, 255, 255))
                cornerRadius = dp(23).toFloat()
            }
            elevation = dp(14).toFloat()
        }
        val startButton = TextView(this).apply {
            text = "▦"
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(if (darkTheme) Color.WHITE else Color.rgb(24, 31, 48))
            background = rounded(accentColor, 17)
            contentDescription = "Buka Start"
            setOnClickListener { openFreeformStartMenu() }
        }
        dock.addView(startButton, LinearLayout.LayoutParams(dp(43), dp(46)))
        dock.addView(View(this).apply {
            setBackgroundColor(Color.argb(95, 255, 255, 255))
        }, LinearLayout.LayoutParams(dp(1), dp(32)).apply {
            marginStart = dp(6)
            marginEnd = dp(4)
        })
        pinnedPackages.filterNot { hiddenPackages.contains(it) }.take(6).mapNotNull { apps[it] }.forEachIndexed { index, app ->
            val icon = ImageView(this).apply {
                setImageDrawable(app.icon)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(dp(5), dp(5), dp(5), dp(5))
                contentDescription = app.label
                setOnClickListener {
                    animate().cancel()
                    scaleX = 0.86f
                    scaleY = 0.86f
                    animate().scaleX(1.08f).scaleY(1.08f).setDuration(120).withEndAction {
                        animate().scaleX(1f).scaleY(1f).setDuration(140).start()
                    }.start()
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
                            try { startActivity(launch) } catch (_: Exception) {
                                Toast.makeText(this@FloatingDockService, "Aplikasi tidak dapat dibuka", Toast.LENGTH_SHORT).show()
                            }
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
            dock.addView(icon, LinearLayout.LayoutParams(dp(iconSize), dp(48)))
        }

        val blurSupported = liquidGlass && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && windowManager.isCrossWindowBlurEnabled()
        var windowFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        if (blurSupported) windowFlags = windowFlags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            dp(58),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            windowFlags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(28)
            if (blurSupported) setBlurBehindRadius(dp(28))
        }
        try {
            windowManager.addView(dock, params)
            dockView = dock
        } catch (_: Exception) {
            if (blurSupported) {
                try {
                    params.flags = params.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND.inv()
                    params.setBlurBehindRadius(0)
                    windowManager.addView(dock, params)
                    dockView = dock
                } catch (_: Exception) {
                    Toast.makeText(this, "Tidak dapat menampilkan dock di atas aplikasi", Toast.LENGTH_SHORT).show()
                    stopSelf()
                }
            } else {
                Toast.makeText(this, "Tidak dapat menampilkan dock di atas aplikasi", Toast.LENGTH_SHORT).show()
                stopSelf()
            }
        }
    }

    /**
     * Open the dedicated Start menu as a separate Android FREEFORM task.
     * Use the root launch path first, then fall back to ActivityOptions.
     */
    private fun openFreeformStartMenu() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK
            putExtra(MainActivity.EXTRA_OPEN_WINDOW_START, true)
        }
        Thread({
            var launchedByRoot = false
            try {
                val command = "settings put global enable_freeform_support 1; " +
                    "settings put secure force_resizable_activities 1; " +
                    "am start -W --user current --windowingMode 5 --activity-multiple-task " +
                    "-n com.sakuhome.launcher/.MainActivity " +
                    "--ez com.sakuhome.launcher.OPEN_WINDOW_START true"
                val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
                val output = java.io.ByteArrayOutputStream()
                val errors = java.io.ByteArrayOutputStream()
                val outThread = Thread { process.inputStream.use { it.copyTo(output) } }
                val errThread = Thread { process.errorStream.use { it.copyTo(errors) } }
                outThread.start()
                errThread.start()
                if (process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    outThread.join(200)
                    errThread.join(200)
                    val stdout = output.toString("UTF-8")
                    val stderr = errors.toString("UTF-8")
                    launchedByRoot = process.exitValue() == 0 &&
                        stdout.contains("Status: ok", true) &&
                        !stdout.contains("Error:", true) &&
                        !stderr.contains("Exception", true)
                    if (!launchedByRoot) android.util.Log.w("SakuFreeform", "Floating Start root launch not verified: $stdout $stderr")
                } else {
                    process.destroyForcibly()
                    android.util.Log.w("SakuFreeform", "Floating Start root launch timed out")
                }
            } catch (e: Exception) {
                android.util.Log.w("SakuFreeform", "Floating Start root launch unavailable", e)
            }
            if (!launchedByRoot) {
                try {
                    val width = resources.displayMetrics.widthPixels
                    val height = resources.displayMetrics.heightPixels
                    val margin = dp(20)
                    val bounds = Rect(margin, dp(50), width - margin, height - dp(70))
                    val options = ActivityOptions.makeBasic().setLaunchBounds(bounds)
                    try {
                        val method = ActivityOptions::class.java.getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType)
                        method.isAccessible = true
                        method.invoke(options, 5)
                    } catch (e: ReflectiveOperationException) {
                        android.util.Log.w("SakuFreeform", "ActivityOptions freeform mode unavailable", e)
                    }
                    startActivity(intent, options.toBundle())
                } catch (e: Exception) {
                    android.util.Log.e("SakuFreeform", "Unable to open floating Start menu", e)
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        Toast.makeText(this@FloatingDockService, "Start menu jendela gagal dibuka. Periksa izin root dan mode freeform Android.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }, "saku-freeform-start").start()
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
        private const val KEY_TRANSPARENCY = "dock_transparency"
        private const val KEY_ICON_SIZE = "app_icon_size_dp"
        private const val KEY_HIDDEN_APPS = "hidden_app_packages"
        private const val KEY_LIQUID_GLASS = "liquid_glass_enabled"
    }
}

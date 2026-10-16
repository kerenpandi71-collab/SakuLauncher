package com.sakuhome.launcher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

class VirtualControlService : Service() {
    private lateinit var wm: WindowManager
    private var panel: View? = null
    private var cursor: TextView? = null
    private var foreground = false
    private var cursorX = 0f
    private var cursorY = 0f
    private var padDownX = 0f
    private var padDownY = 0f
    private var startCursorX = 0f
    private var startCursorY = 0f
    private var moved = false
    private var dragMode = false
    private lateinit var cursorParams: WindowManager.LayoutParams
    private var lastRawX = 0f
    private var lastRawY = 0f

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
            .setContentText("Sentuh panel bawah untuk mengontrol layar")
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
        cursorX = resources.displayMetrics.widthPixels * 0.5f
        cursorY = resources.displayMetrics.heightPixels * 0.38f

        val shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = rounded(Color.rgb(25, 31, 45), 22)
            elevation = dp(14).toFloat()
        }
        val toolbar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        toolbar.addView(TextView(this).apply {
            text = "SAKU  •  VIRTUAL CONTROLS"
            textSize = 11f
            setTextColor(Color.rgb(190, 205, 235))
        }, LinearLayout.LayoutParams(0, dp(38), 1f))
        toolbar.addView(button("⌨", dp(44)) {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        })
        toolbar.addView(button(if (dragMode) "Drag ✓" else "Drag", dp(62)) {
            dragMode = !dragMode
            Toast.makeText(this, if (dragMode) "Mode seret aktif: sentuh dua titik pada panel untuk menyeret" else "Mode seret nonaktif", Toast.LENGTH_SHORT).show()
        })
        toolbar.addView(button("×", dp(40)) { stopSelf() })
        shell.addView(toolbar)

        val pad = TextView(this).apply {
            text = "TOUCHPAD\nGeser untuk pindahkan penunjuk • ketuk untuk klik"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = rounded(Color.rgb(48, 59, 81), 17)
            isHapticFeedbackEnabled = true
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        padDownX = event.x; padDownY = event.y
                        lastRawX = event.rawX; lastRawY = event.rawY
                        startCursorX = cursorX; startCursorY = cursorY
                        moved = false
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - lastRawX
                        val dy = event.rawY - lastRawY
                        if (abs(event.x - padDownX) + abs(event.y - padDownY) > dp(8)) moved = true
                        cursorX = (cursorX + dx * 1.65f).coerceIn(dp(8).toFloat(), resources.displayMetrics.widthPixels - dp(8).toFloat())
                        cursorY = (cursorY + dy * 1.65f).coerceIn(dp(40).toFloat(), resources.displayMetrics.heightPixels * 0.69f)
                        lastRawX = event.rawX; lastRawY = event.rawY
                        moveCursor()
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (dragMode && moved) {
                            VirtualMouseAccessibilityService.instance?.swipe(startCursorX, startCursorY, cursorX, cursorY)
                        } else if (!moved) {
                            VirtualMouseAccessibilityService.instance?.tapAt(cursorX, cursorY)
                        }
                        true
                    }
                    else -> true
                }
            }
        }
        shell.addView(pad, LinearLayout.LayoutParams(-1, dp(112)).apply { topMargin = dp(5) })
        val hint = TextView(this).apply {
            text = "Perlu aktifkan Layanan Aksesibilitas Saku. Ini meniru ketukan/seretan, bukan pointer hardware dengan hover."
            textSize = 10f
            setTextColor(Color.rgb(190, 198, 215))
            setPadding(dp(4), dp(5), dp(4), 0)
        }
        shell.addView(hint)
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
                text = "●"
                textSize = 22f
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

    private fun moveCursor() {
        val v = cursor ?: return
        cursorParams.x = cursorX.toInt()
        cursorParams.y = cursorY.toInt()
        try { wm.updateViewLayout(v, cursorParams) } catch (_: Exception) { }
    }

    private fun button(label: String, width: Int, action: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 12f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        background = rounded(Color.rgb(55, 67, 91), 12)
        isClickable = true
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(width, -1).apply { marginStart = dp(5) }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius * resources.displayMetrics.density
    }

    override fun onDestroy() {
        listOfNotNull(panel, cursor).forEach { try { wm.removeView(it) } catch (_: Exception) { } }
        panel = null; cursor = null
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

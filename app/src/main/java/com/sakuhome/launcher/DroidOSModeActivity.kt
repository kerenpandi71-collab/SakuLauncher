package com.sakuhome.launcher

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import rikka.shizuku.Shizuku

import com.katsuyamaki.DroidOSFOSSLauncher.MainActivity as DroidOSLauncherActivity
import com.katsuyamaki.DroidOSFOSSKeyboardTrackpad.MainActivity as DroidOSTrackpadActivity

/**
 * Single setup entry for the original DroidOS launcher + trackpad.
 * Root helps grant Android overlay/accessibility settings. The upstream FOSS
 * command/input backend still requires Shizuku, so the UI must not imply that
 * root alone is a full replacement for Shizuku.
 */
class DroidOSModeActivity : Activity() {
    private lateinit var shizukuStatus: TextView
    private lateinit var rootStatus: TextView
    private lateinit var overlayStatus: TextView
    private lateinit var launcherAccessStatus: TextView
    private lateinit var trackpadAccessStatus: TextView
    private lateinit var startButton: TextView
    private val requestCode = 9071
    private val bg = Color.rgb(10, 15, 26)
    private val surface = Color.rgb(23, 33, 51)
    private val fg = Color.rgb(239, 245, 255)
    private val muted = Color.rgb(153, 171, 195)
    private var rootSetupSucceeded = false

    private val shizukuListener = Shizuku.OnRequestPermissionResultListener { code, result ->
        if (code == requestCode) runOnUiThread {
            refreshStatuses()
            Toast.makeText(
                this,
                if (result == PackageManager.PERMISSION_GRANTED) "Izin Shizuku diberikan" else "Izin Shizuku belum diberikan",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun rounded(color: Int, radius: Int = 16) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun text(value: String, size: Float, color: Int = fg, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            gravity = Gravity.CENTER_VERTICAL
            includeFontPadding = false
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

    private fun button(value: String, primary: Boolean = false, action: () -> Unit): TextView =
        text(value, 14f, if (primary) Color.WHITE else fg, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(13), dp(14), dp(13))
            background = rounded(if (primary) Color.rgb(45, 105, 200) else surface, 14)
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        Shizuku.addRequestPermissionResultListener(shizukuListener)
        buildUi()
        refreshStatuses()
    }

    override fun onResume() {
        super.onResume()
        if (::shizukuStatus.isInitialized) refreshStatuses()
    }

    override fun onDestroy() {
        try { Shizuku.removeRequestPermissionResultListener(shizukuListener) } catch (_: Exception) { }
        super.onDestroy()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(18))
            setBackgroundColor(bg)
        }
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(text("DroidOS Desktop", 25f, fg, true), LinearLayout.LayoutParams(-1, -2))
        content.addView(
            text("Launcher DroidOS di atas · trackpad asli di bawah", 13f, muted),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6); bottomMargin = dp(16) }
        )

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(surface, 16)
        }
        shizukuStatus = addStatus(card, "Shizuku")
        rootStatus = addStatus(card, "Root Magisk")
        overlayStatus = addStatus(card, "Izin overlay")
        launcherAccessStatus = addStatus(card, "Aksesibilitas launcher DroidOS")
        trackpadAccessStatus = addStatus(card, "Aksesibilitas trackpad DroidOS")
        content.addView(card, LinearLayout.LayoutParams(-1, -2))

        content.addView(button("Hubungkan / beri izin Shizuku") { requestShizuku() },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        content.addView(button("Gunakan Root Magisk untuk mengatur izin Android") { grantViaRoot() },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        content.addView(button("Buka pengaturan Aksesibilitas") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        content.addView(button("Buka izin tampil di atas aplikasi") {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
            }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        startButton = button("MASUK MODE DESKTOP", primary = true) { startDesktopMode() }
        content.addView(startButton, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        content.addView(button("Kembali ke Saku Launcher") { finish() },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        content.addView(
            text(
                "Catatan: DroidOS FOSS memakai Shizuku untuk menjalankan perintah sistem dan input. " +
                    "Tombol Root membantu memberikan izin overlay serta mengaktifkan dua layanan aksesibilitas, " +
                    "tetapi root belum menggantikan backend Shizuku pada versi upstream ini.",
                11f, muted
            ),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) }
        )
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, -1))
        setContentView(root)
    }

    private fun addStatus(parent: LinearLayout, title: String): TextView {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(5), 0, dp(5))
        }
        row.addView(text(title, 12f, muted), LinearLayout.LayoutParams(0, -2, 1f))
        val status = text("Memeriksa…", 12f, fg, true).apply { gravity = Gravity.END }
        row.addView(status, LinearLayout.LayoutParams(dp(145), -2))
        parent.addView(row, LinearLayout.LayoutParams(-1, -2))
        return status
    }

    private fun shizukuGranted(): Boolean = try {
        Shizuku.getBinder() != null && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Exception) { false }

    private fun serviceEnabled(className: String): Boolean = try {
        val flat = "$packageName/$className"
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        enabled.split(':').any { it.equals(flat, ignoreCase = true) }
    } catch (_: Exception) { false }

    private fun launcherEnabled() =
        serviceEnabled("com.katsuyamaki.DroidOSFOSSLauncher.FloatingLauncherService")

    private fun trackpadEnabled() =
        serviceEnabled("com.katsuyamaki.DroidOSFOSSKeyboardTrackpad.OverlayService")

    private fun setState(view: TextView, ready: Boolean, readyText: String = "SIAP", missingText: String = "PERLU IZIN") {
        view.text = if (ready) readyText else missingText
        view.setTextColor(if (ready) Color.rgb(105, 220, 160) else Color.rgb(255, 183, 89))
    }

    private fun refreshStatuses() {
        setState(shizukuStatus, shizukuGranted(), "SIAP", "NYALAKAN SHIZUKU")
        setState(rootStatus, rootSetupSucceeded, "IZIN DIATUR", "OPSIONAL")
        setState(overlayStatus, Settings.canDrawOverlays(this))
        setState(launcherAccessStatus, launcherEnabled())
        setState(trackpadAccessStatus, trackpadEnabled())
        val ready = shizukuGranted() && Settings.canDrawOverlays(this) && launcherEnabled() && trackpadEnabled()
        startButton.text = if (ready) "MASUK MODE DESKTOP" else "LENGKAPI IZIN UNTUK MASUK"
        startButton.alpha = if (ready) 1f else 0.82f
    }

    private fun requestShizuku() {
        try {
            if (Shizuku.getBinder() == null) {
                val launch = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                if (launch != null) {
                    startActivity(launch)
                    Toast.makeText(this, "Jalankan server di Shizuku, lalu kembali ke sini.", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "Aplikasi Shizuku belum terpasang.", Toast.LENGTH_LONG).show()
                }
                return
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Izin Shizuku sudah aktif.", Toast.LENGTH_SHORT).show()
            } else {
                Shizuku.requestPermission(requestCode)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Shizuku belum siap: ${e.message ?: "periksa server Shizuku"}", Toast.LENGTH_LONG).show()
        }
        refreshStatuses()
    }

    private fun grantViaRoot() {
        Toast.makeText(this, "Setujui permintaan superuser di Magisk.", Toast.LENGTH_LONG).show()
        Thread {
            var output = ""
            var exit = -1
            try {
                val launcher = "$packageName/com.katsuyamaki.DroidOSFOSSLauncher.FloatingLauncherService"
                val trackpad = "$packageName/com.katsuyamaki.DroidOSFOSSKeyboardTrackpad.OverlayService"
                val d = "\$"
                val script = """
                    id
                    appops set '$packageName' SYSTEM_ALERT_WINDOW allow
                    current=${d}(settings get secure enabled_accessibility_services); [ "${d}current" = "null" ] && current=""
                    case ":${d}current:" in *":$launcher:"*) ;; *) current="${d}{current:+${d}current:}$launcher" ;; esac
                    case ":${d}current:" in *":$trackpad:"*) ;; *) current="${d}{current:+${d}current:}$trackpad" ;; esac
                    settings put secure enabled_accessibility_services "${d}current"
                    settings put secure accessibility_enabled 1
                    echo ROOT_SETUP_OK
                """.trimIndent()
                val process = Runtime.getRuntime().exec(arrayOf("su", "-c", script))
                output = process.inputStream.bufferedReader().readText() + process.errorStream.bufferedReader().readText()
                exit = process.waitFor()
            } catch (e: Exception) {
                output = e.message.orEmpty()
            }
            val success = exit == 0 && output.contains("uid=0") && output.contains("ROOT_SETUP_OK")
            runOnUiThread {
                rootSetupSucceeded = success
                Toast.makeText(
                    this,
                    if (success) "Root berhasil mengatur izin Android. Shizuku tetap diperlukan untuk backend DroidOS."
                    else "Root gagal memberi izin. Periksa persetujuan Magisk, lalu gunakan pengaturan manual.",
                    Toast.LENGTH_LONG
                ).show()
                refreshStatuses()
            }
        }.start()
    }

    private fun startDesktopMode() {
        refreshStatuses()
        if (!shizukuGranted()) {
            Toast.makeText(this, "Nyalakan Shizuku dan beri izin terlebih dahulu.", Toast.LENGTH_LONG).show()
            return
        }
        if (!Settings.canDrawOverlays(this) || !launcherEnabled() || !trackpadEnabled()) {
            Toast.makeText(this, "Lengkapi izin overlay dan kedua layanan aksesibilitas dahulu.", Toast.LENGTH_LONG).show()
            return
        }
        try {
            startActivity(Intent(this, DroidOSLauncherActivity::class.java))
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    // Start the upstream trackpad service first so its bottom overlay is ready.
                    startActivity(Intent(this, DroidOSTrackpadActivity::class.java))
                    Handler(Looper.getMainLooper()).postDelayed({
                        // Open the original DroidOS app drawer in the reserved upper region.
                        sendBroadcast(
                            Intent("com.katsuyamaki.DroidOSFOSSLauncher.OPEN_DRAWER")
                                .setPackage(packageName)
                        )
                        finish()
                    }, 1700L)
                } catch (e: Exception) {
                    Toast.makeText(this, "Trackpad tidak dapat dijalankan: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }, 450L)
        } catch (e: Exception) {
            Toast.makeText(this, "DroidOS tidak dapat dijalankan: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}

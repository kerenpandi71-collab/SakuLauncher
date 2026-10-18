package com.sakuhome.launcher

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.katsuyamaki.DroidOSFOSSKeyboardTrackpad.MainActivity as TrackpadActivity

class DesktopModeActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("SakuVirtualDesktop", MODE_PRIVATE) }
    private lateinit var status: TextView
    private lateinit var widthInput: EditText
    private lateinit var heightInput: EditText
    private lateinit var dpiInput: EditText
    private var displayId: Int
        get() = prefs.getInt("display_id", -1)
        set(value) { prefs.edit().putInt("display_id", value).apply() }
    private val bg = Color.rgb(12, 17, 29)
    private val panel = Color.rgb(24, 34, 51)
    private val accent = Color.rgb(73, 130, 230)
    private val fg = Color.rgb(239, 245, 255)
    private val muted = Color.rgb(153, 171, 195)

    private fun dp(v: Int) = (v * resources.displayMetrics.density + .5f).toInt()
    private fun rounded(color: Int, radius: Int = 14) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun label(value: String, size: Float = 13f, color: Int = fg, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); gravity = Gravity.CENTER_VERTICAL
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }
    private fun button(value: String, primary: Boolean = false, click: () -> Unit) = Button(this).apply {
        text = value; isAllCaps = false; textSize = 12f; setTextColor(fg)
        background = rounded(if (primary) accent else panel)
        setOnClickListener { click() }
    }
    private fun field(value: String, hintText: String): EditText = EditText(this).apply {
        setText(value); hint = hintText; textSize = 13f; setTextColor(fg); setHintTextColor(muted)
        inputType = android.text.InputType.TYPE_CLASS_NUMBER
        setPadding(dp(8), 0, dp(8), 0); background = rounded(panel)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        buildUi()
        updateStatus()
    }

    override fun onResume() { super.onResume(); if (::status.isInitialized) updateStatus() }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg); setPadding(dp(12), dp(8), dp(12), dp(8)) }
        val top = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = rounded(panel, 18); setPadding(dp(12), dp(8), dp(12), dp(8)) }
        top.addView(label("SAKU · VIRTUAL DESKTOP", 17f, fg, true), LinearLayout.LayoutParams(-1, dp(32)))
        top.addView(label("Ruang atas · simulasi layar sekunder", 11f, muted), LinearLayout.LayoutParams(-1, dp(22)))
        status = label("Memeriksa display…", 11f, muted)
        top.addView(status, LinearLayout.LayoutParams(-1, dp(25)))
        val config = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        widthInput = field(prefs.getInt("width", 900).toString(), "Lebar")
        heightInput = field(prefs.getInt("height", 1080).toString(), "Tinggi")
        dpiInput = field(prefs.getInt("dpi", 240).toString(), "DPI")
        listOf(widthInput, heightInput, dpiInput).forEach { config.addView(it, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(5) }) }
        top.addView(config, LinearLayout.LayoutParams(-1, dp(46)))
        val configActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        configActions.addView(button("Buat / Terapkan", true) { createOrApplyDisplay() }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(6) })
        configActions.addView(button("Tutup display") { releaseDisplay() }, LinearLayout.LayoutParams(0, dp(42), 1f))
        top.addView(configActions, LinearLayout.LayoutParams(-1, dp(44)))
        // No app drawer/menu here: this panel is reserved for virtual display controls.
        root.addView(top, LinearLayout.LayoutParams(-1, 0, 0.58f))

        val controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = rounded(panel, 18); setPadding(dp(10), dp(8), dp(10), dp(8)) }
        controls.addView(label("Ruang bawah · trackpad + keyboard", 13f, fg, true), LinearLayout.LayoutParams(-1, dp(28)))
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row1.addView(button("Aktifkan trackpad + keyboard", true) { startTrackpad() }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(5) })
        row1.addView(button("Tampilkan keyboard") { sendTrackpadCommand("TOGGLE_CUSTOM_KEYBOARD") }, LinearLayout.LayoutParams(0, dp(44), 1f))
        controls.addView(row1, LinearLayout.LayoutParams(-1, dp(46)))
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row2.addView(button("Sembunyikan trackpad") { sendTrackpadCommand("SET_TRACKPAD_VISIBILITY", false) }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(5) })
        row2.addView(button("Izin aksesibilitas") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(5) })
        row2.addView(button("Cek izin root") { toast("Fitur display memerlukan akses root Magisk. Pastikan SakuLauncher diizinkan di prompt Magisk.") }, LinearLayout.LayoutParams(0, dp(42), .7f))
        controls.addView(row2, LinearLayout.LayoutParams(-1, dp(44)))
        val row3 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row3.addView(button("Buka izin overlay") { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(5) })
        row3.addView(button("Kembali") { finish() }, LinearLayout.LayoutParams(0, dp(42), .7f))
        controls.addView(row3, LinearLayout.LayoutParams(-1, dp(44)))
        root.addView(controls, LinearLayout.LayoutParams(-1, 0, 0.42f).apply { topMargin = dp(8) })
        setContentView(root)
    }

    private fun updateStatus(extra: String = "") {
        val id = displayId
        status.text = if (id >= 0) "Virtual display aktif · ID $id  •  ${widthInputOr("900")}×${heightInputOr("1080")} @ ${dpiInputOr("240")} dpi" + if (extra.isBlank()) "" else " · $extra"
        else "Belum ada virtual display. Buat display untuk mengarahkan input." + if (extra.isBlank()) "" else " · $extra"
    }
    private fun widthInputOr(default: String) = if (::widthInput.isInitialized) widthInput.text.toString() else default
    private fun heightInputOr(default: String) = if (::heightInput.isInitialized) heightInput.text.toString() else default
    private fun dpiInputOr(default: String) = if (::dpiInput.isInitialized) dpiInput.text.toString() else default

    private fun validConfig(): Triple<Int, Int, Int>? {
        val w = widthInput.text.toString().toIntOrNull() ?: return null
        val h = heightInput.text.toString().toIntOrNull() ?: return null
        val dpi = dpiInput.text.toString().toIntOrNull() ?: return null
        if (w !in 480..2560 || h !in 320..1600 || dpi !in 120..480) return null
        return Triple(w, h, dpi)
    }

    private fun createOrApplyDisplay() {
        val cfg = validConfig()
        if (cfg == null) { toast("Resolusi: lebar 480–2560, tinggi 320–1600; DPI 120–480."); return }
        val (w, h, dpi) = cfg
        Thread {
            val currentSetting = runPrivileged("settings get global overlay_display_devices").second.trim()
            if (currentSetting != "\${w}x\${h}/$dpi") {
                prefs.edit().putString("previous_overlay_display_devices", if (currentSetting == "null") "" else currentSetting).apply()
            }
            val result = runPrivileged(
                "settings put global overlay_display_devices '\${w}x\${h}/$dpi'; sleep 2; dumpsys display"
            )
            val output = result.second
            val found = Regex(
                """DisplayInfo\{"[^"]*(?:Overlay|Saku)[^"]*"[^}]*displayId\s+(\d+)""",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
            ).find(output)?.groupValues?.getOrNull(1)?.toIntOrNull()
            runOnUiThread {
                if (found != null && found > 0) {
                    displayId = found
                    prefs.edit().putInt("width", w).putInt("height", h).putInt("dpi", dpi).apply()
                    updateStatus(); toast("Overlay virtual display aktif · ID $found")
                } else {
                    updateStatus("gagal menemukan display")
                    toast("Setting overlay diterapkan, tetapi ID display belum terbaca. Periksa Developer Options / dumpsys display.")
                }
            }
        }.start()
    }

    private fun releaseDisplay() {
        val id = displayId
        if (id < 0) { toast("Belum ada virtual display"); return }
        Thread {
            val previous = prefs.getString("previous_overlay_display_devices", "").orEmpty()
            val safePrevious = previous.replace("\\", "\\\\").replace("'", "'\\''")
            val command = if (safePrevious.isBlank()) {
                "settings put global overlay_display_devices ''"
            } else {
                "settings put global overlay_display_devices '$safePrevious'"
            }
            val result = runPrivileged("$command; sleep 1; echo SAKU_OVERLAY_DISPLAY_CLEARED")
            runOnUiThread {
                if (result.first == 0 && result.second.contains("SAKU_OVERLAY_DISPLAY_CLEARED")) {
                    displayId = -1
                    prefs.edit().remove("previous_overlay_display_devices").apply()
                    updateStatus()
                    toast("Overlay virtual display ditutup")
                } else toast("Gagal menutup display: \${result.second.take(140)}")
            }
        }.start()
    }

    private fun runPrivileged(command: String): Pair<Int, String> {
        // This build executes privileged commands through Magisk su. Shizuku's
        // newProcess API is not public in the pinned dependency, so do not call it.
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val stdout = process.inputStream.bufferedReader().use { it.readText() }
            val stderr = process.errorStream.bufferedReader().use { it.readText() }
            process.waitFor() to (stdout + "\n" + stderr).trim()
        } catch (e: Exception) {
            -1 to (e.message ?: "Perintah root gagal. Pastikan Magisk memberikan izin root.")
        }
    }

    private fun startTrackpad() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        try {
            startActivity(Intent(this, TrackpadActivity::class.java).apply {
                putExtra("displayId", 0)
                putExtra("force_start", true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            window.decorView.postDelayed({
                val id = displayId
                if (id >= 0) sendBroadcast(Intent("SAKU_SET_INPUT_TARGET").setPackage(packageName).putExtra("DISPLAY_ID", id))
                sendBroadcast(Intent("SAKU_DESKTOP_LAYOUT").setPackage(packageName))
                sendTrackpadCommand("SET_TRACKPAD_VISIBILITY", true)
            }, 1600L)
            toast("Trackpad dan keyboard diminta aktif. Jika belum muncul, aktifkan layanan aksesibilitas trackpad.")
        } catch (e: Exception) { toast("Trackpad gagal dibuka: ${e.message}") }
    }

    private fun sendTrackpadCommand(action: String, visible: Boolean? = null) {
        val intent = Intent(action).setPackage(packageName)
        if (visible != null) intent.putExtra("VISIBLE", visible)
        sendBroadcast(intent)
        if (action == "TOGGLE_CUSTOM_KEYBOARD") toast("Perintah keyboard dikirim")
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}

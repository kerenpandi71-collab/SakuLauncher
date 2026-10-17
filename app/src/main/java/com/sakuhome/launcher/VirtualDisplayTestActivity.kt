package com.sakuhome.launcher

import android.app.Activity
import android.app.ActivityOptions
import android.app.Presentation
import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Isolated smoke test for SakuLauncher virtual-display work.
 * This screen does not replace or resize the normal launcher UI.
 */
class VirtualDisplayTestActivity : Activity() {
    private lateinit var status: TextView
    private var reader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Saku · Virtual Display Test"

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(16))
        }
        status = TextView(this).apply {
            textSize = 14f
            text = "Belum diuji. Tes ini terpisah dari tampilan launcher utama."
            setPadding(0, 0, 0, dp(12))
        }
        content.addView(status, LinearLayout.LayoutParams(-1, -2))
        content.addView(actionButton("1 · Buat virtual display 820×1320") { createTestDisplay() })
        content.addView(actionButton("2 · Coba buka Saku di display ini") { launchSakuOnDisplay() })
        content.addView(actionButton("3 · Lepas virtual display") { releaseTestDisplay("Dilepas oleh pengguna.") })
        content.addView(actionButton("Refresh daftar display") { refreshDisplayList() })
        content.addView(TextView(this).apply {
            text = "Catatan: tes ini memeriksa apakah Android mengizinkan aplikasi membuat presentation display dan mencoba membuka Saku di sana. Keberhasilan pembuatan display tidak menjamin aplikasi lain atau injeksi input diizinkan."
            textSize = 12f
            setPadding(0, dp(14), 0, 0)
        })
        val scroll = ScrollView(this).apply { addView(content) }
        setContentView(scroll)
        refreshDisplayList()
    }

    private fun actionButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
    }

    private fun createTestDisplay() {
        if (virtualDisplay != null) {
            report("Virtual display sudah aktif dengan ID ${virtualDisplay?.display?.displayId}.")
            return
        }
        try {
            val imageReader = ImageReader.newInstance(WIDTH, HEIGHT, android.graphics.PixelFormat.RGBA_8888, 3)
            imageReader.setOnImageAvailableListener({ source ->
                try { source.acquireLatestImage()?.close() } catch (_: Exception) { }
            }, mainHandler)
            val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
            val created = (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
                .createVirtualDisplay("SakuMinimalTest", WIDTH, HEIGHT, DENSITY_DPI, imageReader.surface, flags)
            if (created == null) {
                imageReader.close()
                report("GAGAL: DisplayManager.createVirtualDisplay() mengembalikan null.")
                return
            }
            reader = imageReader
            virtualDisplay = created
            val display = created.display
            val testPresentation = Presentation(this, display).apply {
                setOnDismissListener { }
                val panel = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(28, 28, 28, 28)
                    setBackgroundColor(android.graphics.Color.rgb(18, 27, 44))
                }
                panel.addView(TextView(context).apply {
                    text = "SAKU VIRTUAL DISPLAY"
                    textSize = 24f
                    setTextColor(android.graphics.Color.WHITE)
                    gravity = Gravity.CENTER
                })
                panel.addView(TextView(context).apply {
                    text = "Display ID: ${display.displayId}\nResolusi: ${WIDTH} × ${HEIGHT}\nJika teks ini muncul, Presentation berhasil dirender."
                    textSize = 16f
                    setTextColor(android.graphics.Color.rgb(190, 210, 240))
                    gravity = Gravity.CENTER
                    setPadding(0, 20, 0, 20)
                })
                setContentView(panel)
            }
            presentation = testPresentation
            testPresentation.show()
            report("BERHASIL membuat display ID ${display.displayId}. Presentation.show() dipanggil. Coba tombol peluncuran Saku berikutnya.")
        } catch (e: SecurityException) {
            releaseTestDisplay("Izin ditolak: ${e.message ?: e.javaClass.simpleName}")
        } catch (e: Exception) {
            releaseTestDisplay("Gagal: ${e.javaClass.simpleName}: ${e.message ?: "tanpa detail"}")
        }
    }

    private fun launchSakuOnDisplay() {
        val displayId = virtualDisplay?.display?.displayId
        if (displayId == null) {
            report("Buat virtual display terlebih dahulu.")
            return
        }
        try {
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            if (intent == null) {
                report("Tidak menemukan launch intent Saku Launcher.")
                return
            }
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
            startActivity(intent, options.toBundle())
            report("Permintaan membuka Saku pada display ID ${displayId} sudah dikirim. Periksa apakah Activity benar-benar muncul di display virtual.")
        } catch (e: Exception) {
            report("PELAKSANAAN DITOLAK: ${e.javaClass.simpleName}: ${e.message ?: "tanpa detail"}. Display mungkin privat atau kebijakan Android membatasi peluncuran.")
        }
    }

    private fun refreshDisplayList() {
        val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val list = dm.displays.joinToString("\n") { display ->
            "ID ${display.displayId}: ${display.name}, ${display.mode.physicalWidth}×${display.mode.physicalHeight}, flags=${display.flags}"
        }
        report("Daftar display yang terlihat oleh aplikasi:\n${list.ifBlank { "(kosong)" }}")
    }

    private fun releaseTestDisplay(message: String) {
        try { presentation?.dismiss() } catch (_: Exception) { }
        presentation = null
        try { virtualDisplay?.release() } catch (_: Exception) { }
        virtualDisplay = null
        try { reader?.setOnImageAvailableListener(null, null); reader?.close() } catch (_: Exception) { }
        reader = null
        report(message + "\n\n" + (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).displays.joinToString("\n") {
            "ID ${it.displayId}: ${it.name}"
        })
    }

    private fun report(message: String) {
        if (::status.isInitialized) status.text = message
        Toast.makeText(this, message.lineSequence().firstOrNull().orEmpty(), Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        releaseTestDisplay("Tes ditutup; resource virtual display dibersihkan.")
        super.onDestroy()
    }

    companion object {
        private const val WIDTH = 820
        private const val HEIGHT = 1320
        private const val DENSITY_DPI = 320
    }
}

package com.sakuhome.launcher

import android.app.Activity
import android.app.AlertDialog
import android.app.PictureInPictureParams
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController

/**
 * SakuTube browser surface backed by GeckoView so real WebExtensions can run.
 * uBlock Origin is installed only from the official Mozilla Add-ons endpoint;
 * GeckoView verifies the Mozilla signature before accepting the XPI.
 */
class YouTubeActivity : Activity() {
    private lateinit var geckoView: GeckoView
    private lateinit var session: GeckoSession
    private lateinit var toolbar: LinearLayout
    private lateinit var root: LinearLayout
    private lateinit var runtime: GeckoRuntime
    private var uboExtension: WebExtension? = null
    private var allowUboInstallPrompt = false
    private var desktopSiteEnabled = false

    private val uboId = "uBlock0@raymondhill.net"
    private val uboXpiUrl = "https://addons.mozilla.org/firefox/downloads/latest/ublock-origin/latest.xpi"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        runtime = GeckoRuntime.getDefault(this)
        configureExtensionPrompts()
        session = GeckoSession(
            GeckoSessionSettings.Builder()
                .allowJavascript(true)
                .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
                .build()
        )
        session.open(runtime)

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(6, 3, 6, 3)
            setBackgroundColor(Color.rgb(18, 18, 18))
        }

        // Settings is deliberately the first and larger control on the left.
        toolbar.addView(makeButton("⋮", 18f, 14, 10) { showSettingsMenu() })
        toolbar.addView(makeButton("‹", 20f, 12, 10) {
            session.goBack()
        })
        toolbar.addView(makeButton("Mini", 12f, 10, 10) { enterMiniPlayer() })
        toolbar.addView(makeButton("⟳", 16f, 12, 10) { session.reload() })
        toolbar.addView(makeButton("✕", 16f, 12, 10) { finish() })
        root.addView(toolbar, LinearLayout.LayoutParams(-1, -2))

        geckoView = GeckoView(this)
        geckoView.setBackgroundColor(Color.BLACK)
        geckoView.setSession(session)
        root.addView(geckoView, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        refreshInstalledExtension()
        session.loadUri("https://m.youtube.com/")
    }

    private fun configureExtensionPrompts() {
        runtime.webExtensionController.setPromptDelegate(
            object : WebExtensionController.PromptDelegate {
                override fun onInstallPromptRequest(
                    extension: WebExtension,
                    permissions: Array<out String>,
                    origins: Array<out String>,
                    dataCollectionPermissions: Array<out String>
                ): GeckoResult<WebExtension.PermissionPromptResponse>? {
                    val isOfficialUbo = extension.metaData.name == "uBlock Origin" &&
                        extension.id == uboId
                    // Permission approval is only possible after the explicit
                    // confirmation dialog shown by SakuTube's settings menu.
                    val approved = isOfficialUbo && allowUboInstallPrompt
                    return GeckoResult.fromValue(
                        WebExtension.PermissionPromptResponse(approved, false, false)
                    )
                }
            }
        )
    }

    private fun makeButton(
        label: String,
        size: Float,
        horizontalPadding: Int,
        verticalPadding: Int,
        action: () -> Unit
    ) = TextView(this).apply {
        text = label
        textSize = size
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
        contentDescription = label
        setOnClickListener { action() }
    }

    private fun showSettingsMenu() {
        val installed = uboExtension != null
        val items = if (installed) {
            arrayOf("uBlock Origin: aktif", "Pengaturan uBlock Origin", "Situs desktop: " + if (desktopSiteEnabled) "ON" else "OFF")
        } else {
            arrayOf("Pasang uBlock Origin", "Situs desktop: " + if (desktopSiteEnabled) "ON" else "OFF")
        }
        AlertDialog.Builder(this)
            .setTitle("Pengaturan SakuTube")
            .setItems(items) { _, which ->
                if (installed) {
                    when (which) {
                        0 -> Toast.makeText(this, "uBlock Origin terpasang. Pemfilteran aktif di mesin GeckoView.", Toast.LENGTH_LONG).show()
                        1 -> openUboSettings()
                        2 -> toggleDesktopMode()
                    }
                } else {
                    when (which) {
                        0 -> confirmInstallUbo()
                        1 -> toggleDesktopMode()
                    }
                }
            }
            .setNegativeButton("Tutup", null)
            .show()
    }

    private fun confirmInstallUbo() {
        AlertDialog.Builder(this)
            .setTitle("Pasang uBlock Origin")
            .setMessage(
                "SakuTube akan mengambil uBlock Origin normal dari situs resmi Mozilla Add-ons. " +
                    "Ekstensi ini perlu membaca dan memfilter permintaan pada situs yang dibuka. " +
                    "GeckoView akan memeriksa tanda tangan Mozilla sebelum memasangnya. Lanjutkan?"
            )
            .setPositiveButton("Pasang") { _, _ -> installUbo() }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun installUbo() {
        Toast.makeText(this, "Mengunduh dan memverifikasi uBlock Origin…", Toast.LENGTH_LONG).show()
        allowUboInstallPrompt = true
        runtime.webExtensionController
            .install(uboXpiUrl, WebExtensionController.INSTALLATION_METHOD_MANAGER)
            .accept(
                { extension ->
                    allowUboInstallPrompt = false
                    if (extension.id == uboId && extension.metaData.name == "uBlock Origin") {
                        uboExtension = extension
                        Toast.makeText(this, "uBlock Origin berhasil dipasang. Memuat ulang halaman…", Toast.LENGTH_LONG).show()
                        session.reload()
                    } else {
                        Toast.makeText(this, "Ekstensi yang diterima bukan uBlock Origin; tidak diaktifkan.", Toast.LENGTH_LONG).show()
                    }
                },
                { error ->
                    allowUboInstallPrompt = false
                    Toast.makeText(
                        this,
                        "Gagal memasang uBlock Origin: " + (error.message ?: "periksa koneksi dan kompatibilitas GeckoView"),
                        Toast.LENGTH_LONG
                    ).show()
                }
            )
    }

    private fun refreshInstalledExtension() {
        runtime.webExtensionController.list().accept(
            { extensions ->
                uboExtension = extensions.firstOrNull { it.id == uboId && it.metaData.name == "uBlock Origin" }
            },
            { /* The settings menu still offers installation if listing fails. */ }
        )
    }

    private fun openUboSettings() {
        val extension = uboExtension
        val optionsUrl = extension?.metaData?.optionsPageUrl
        if (!optionsUrl.isNullOrBlank()) {
            session.loadUri(optionsUrl)
        } else {
            Toast.makeText(this, "Halaman pengaturan uBlock Origin belum tersedia di build GeckoView ini.", Toast.LENGTH_LONG).show()
        }
    }

    private fun toggleDesktopMode() {
        desktopSiteEnabled = !desktopSiteEnabled
        val settings = session.settings
        settings.setUserAgentMode(
            if (desktopSiteEnabled) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
            else GeckoSessionSettings.USER_AGENT_MODE_MOBILE
        )
        settings.setViewportMode(
            if (desktopSiteEnabled) GeckoSessionSettings.VIEWPORT_MODE_DESKTOP
            else GeckoSessionSettings.VIEWPORT_MODE_MOBILE
        )
        session.reload()
        Toast.makeText(this, if (desktopSiteEnabled) "Situs desktop aktif." else "Situs mobile aktif.", Toast.LENGTH_SHORT).show()
    }

    private fun enterMiniPlayer() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Toast.makeText(this, "Jendela mini membutuhkan Android 8 atau lebih baru.", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            enterPictureInPictureMode(
                PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(16, 9))
                    .build()
            )
        } catch (_: Exception) {
            Toast.makeText(this, "Picture-in-Picture tidak tersedia pada perangkat ini.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: android.content.res.Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (::toolbar.isInitialized) toolbar.visibility = if (isInPictureInPictureMode) View.GONE else View.VISIBLE
    }

    override fun onBackPressed() {
        session.goBack()
    }

    override fun onPause() {
        super.onPause()
        if (::session.isInitialized) session.setActive(isInPictureInPictureMode)
    }

    override fun onResume() {
        super.onResume()
        if (::session.isInitialized) session.setActive(true)
    }

    override fun onDestroy() {
        if (::geckoView.isInitialized) geckoView.releaseSession()
        if (::session.isInitialized) session.close()
        super.onDestroy()
    }
}

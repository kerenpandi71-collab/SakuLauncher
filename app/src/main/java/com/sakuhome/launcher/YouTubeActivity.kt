package com.sakuhome.launcher

import android.app.Activity
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
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
import java.io.File
import java.io.IOException

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
    private var allowLocalExtensionInstallPrompt = false
    private var desktopSiteEnabled = false

    private val uboId = "uBlock0@raymondhill.net"
    private val uboXpiUrl = "https://addons.mozilla.org/firefox/downloads/latest/ublock-origin/latest.xpi"
    private val pickExtensionRequest = 4207

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
                    val approved = (isOfficialUbo && allowUboInstallPrompt) || allowLocalExtensionInstallPrompt
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
        val items = arrayListOf<String>()
        val actions = arrayListOf<() -> Unit>()

        items += "Pasang extension dari file (.xpi)"
        actions += { confirmLocalExtensionInstall() }
        items += "Daftar extension terpasang"
        actions += { showInstalledExtensions() }
        items += "Situs desktop: " + if (desktopSiteEnabled) "ON" else "OFF"
        actions += { toggleDesktopMode() }

        if (uboExtension == null) {
            items += "Pasang uBlock Origin dari Mozilla"
            actions += { confirmInstallUbo() }
        } else {
            items += "Pengaturan uBlock Origin"
            actions += { openUboSettings() }
        }

        AlertDialog.Builder(this)
            .setTitle("Pengaturan SakuTube")
            .setItems(items.toTypedArray()) { _, which -> actions[which].invoke() }
            .setNegativeButton("Tutup", null)
            .show()
    }

    private fun confirmLocalExtensionInstall() {
        AlertDialog.Builder(this)
            .setTitle("Pasang extension dari file")
            .setMessage(
                "Pilih file extension .xpi dari penyimpanan perangkat. Extension dapat meminta izin " +
                    "membaca atau mengubah situs yang dibuka. Pasang hanya file yang kamu percaya. " +
                    "GeckoView akan memvalidasi paket dan tanda tangannya."
            )
            .setPositiveButton("Pilih file") { _, _ -> openExtensionPicker() }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun openExtensionPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                "application/x-xpinstall", "application/zip",
                "application/octet-stream", "application/x-zip-compressed"
            ))
        }
        try {
            startActivityForResult(intent, pickExtensionRequest)
        } catch (_: Exception) {
            Toast.makeText(this, "Pemilih file tidak tersedia.", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Kept compatible with the current Activity implementation")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != pickExtensionRequest || resultCode != RESULT_OK) return
        data?.data?.let { copyAndInstallExtension(it) }
    }

    private fun copyAndInstallExtension(uri: Uri) {
        val displayName = queryDisplayName(uri) ?: "extension.xpi"
        if (!displayName.endsWith(".xpi", ignoreCase = true)) {
            Toast.makeText(this, "Pilih file extension berformat .xpi.", Toast.LENGTH_LONG).show()
            return
        }
        try {
            val extensionDir = File(cacheDir, "selected-extensions")
            if (!extensionDir.exists() && !extensionDir.mkdirs()) {
                throw IOException("Tidak bisa membuat folder sementara")
            }
            val safeName = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val destination = File(extensionDir, safeName)
            contentResolver.openInputStream(uri).use { input ->
                if (input == null) throw IOException("File tidak bisa dibaca")
                destination.outputStream().use { output -> input.copyTo(output) }
            }
            installLocalExtension(destination)
        } catch (error: Exception) {
            Toast.makeText(this, "Gagal membaca file: " + (error.message ?: "kesalahan file"), Toast.LENGTH_LONG).show()
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        var cursor: Cursor? = null
        return try {
            cursor = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            if (cursor != null && cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) cursor.getString(index) else null
            } else null
        } catch (_: Exception) {
            null
        } finally {
            cursor?.close()
        }
    }

    private fun installLocalExtension(file: File) {
        Toast.makeText(this, "Memvalidasi dan memasang " + file.name + "…", Toast.LENGTH_LONG).show()
        allowLocalExtensionInstallPrompt = true
        runtime.webExtensionController
            .install(Uri.fromFile(file).toString(), WebExtensionController.INSTALLATION_METHOD_FROM_FILE)
            .accept(
                { extension ->
                    allowLocalExtensionInstallPrompt = false
                    Toast.makeText(this, "Extension terpasang: " + extension.metaData.name, Toast.LENGTH_LONG).show()
                    refreshInstalledExtension()
                    session.reload()
                },
                { error ->
                    allowLocalExtensionInstallPrompt = false
                    Toast.makeText(
                        this,
                        "Gagal memasang extension. Pastikan XPI valid dan ditandatangani Mozilla. " +
                            (error.message ?: ""),
                        Toast.LENGTH_LONG
                    ).show()
                }
            )
    }

    private fun showInstalledExtensions() {
        runtime.webExtensionController.list().accept(
            { extensions ->
                runOnUiThread {
                    val labels = extensions.map { extension ->
                        extension.metaData.name + if (extension.isBuiltIn) " — bawaan" else " — terpasang"
                    }.toTypedArray()
                    AlertDialog.Builder(this)
                        .setTitle("Extension terpasang")
                        .setItems(labels) { _, which ->
                            val optionsUrl = extensions[which].metaData.optionsPageUrl
                            if (!optionsUrl.isNullOrBlank()) session.loadUri(optionsUrl)
                            else Toast.makeText(this, "Extension ini tidak menyediakan halaman pengaturan.", Toast.LENGTH_SHORT).show()
                        }
                        .setPositiveButton("Selesai", null)
                        .show()
                }
            },
            { Toast.makeText(this, "Tidak bisa membaca daftar extension.", Toast.LENGTH_LONG).show() }
        )
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
                        "Gagal memasang uBlock Origin: " + (error?.message ?: "periksa koneksi dan kompatibilitas GeckoView"),
                        Toast.LENGTH_LONG
                    ).show()
                }
            )
    }

    private fun refreshInstalledExtension() {
        runtime.webExtensionController.list().accept(
            { extensions ->
                uboExtension = extensions?.firstOrNull { it.id == uboId && it.metaData.name == "uBlock Origin" }
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

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
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.text.InputType
import android.widget.EditText
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
    private lateinit var addressField: EditText
    private lateinit var zoomLabel: TextView
    private lateinit var zoomLockButton: TextView
    private lateinit var runtime: GeckoRuntime
    private var uboExtension: WebExtension? = null
    private var allowUboInstallPrompt = false
    private var allowLocalExtensionInstallPrompt = false
    private var desktopSiteEnabled = false
    private var backgroundAudioEnabled = true
    private var zoomLocked = false
    private var zoomFactor = 1.0f
    private var swipeStartX = 0f
    private var swipeStartY = 0f
    private val browserPrefs by lazy { getSharedPreferences("sakutube_browser", MODE_PRIVATE) }

    private val uboId = "uBlock0@raymondhill.net"
    private val uboXpiUrl = "https://addons.mozilla.org/firefox/downloads/latest/ublock-origin/latest.xpi"
    private val pickExtensionRequest = 4207

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        runtime = GeckoRuntime.getDefault(this)
        configureExtensionPrompts()
        backgroundAudioEnabled = browserPrefs.getBoolean("background_audio", true)
        zoomLocked = browserPrefs.getBoolean("zoom_locked", false)
        zoomFactor = browserPrefs.getFloat("zoom_factor", 1.0f).coerceIn(0.7f, 1.6f)
        runtime.settings.setAutomaticFontSizeAdjustment(false)
        runtime.settings.setFontSizeFactor(zoomFactor)
        session = GeckoSession(
            GeckoSessionSettings.Builder()
                .allowJavascript(true)
                .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
                .build()
        )
        session.settings.setSuspendMediaWhenInactive(!backgroundAudioEnabled)
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

        // Controls use a minimum 48dp touch target for reliable use on a phone.
        toolbar.addView(makeButton("⋮", 21f, 10, 8) { showSettingsMenu() })
        toolbar.addView(makeButton("‹", 24f, 10, 8) { if (session.canGoBack()) session.goBack() })
        toolbar.addView(makeButton("Mini", 13f, 10, 8) { enterMiniPlayer() })
        toolbar.addView(makeButton("⟳", 20f, 10, 8) { session.reload() })
        toolbar.addView(makeButton("✕", 19f, 10, 8) { finish() })
        root.addView(toolbar, LinearLayout.LayoutParams(-1, -2))

        val addressRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(5), dp(8), dp(5))
            setBackgroundColor(Color.rgb(26, 26, 26))
        }
        addressField = EditText(this).apply {
            hint = "Cari atau masukkan URL"
            textSize = 14f
            singleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
            setPadding(dp(12), dp(4), dp(8), dp(4))
            background = roundedBackground(Color.rgb(42, 42, 42), 18)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                    loadAddressFromField()
                    true
                } else false
            }
        }
        addressRow.addView(addressField, LinearLayout.LayoutParams(0, dp(44), 1f))
        addressRow.addView(makeButton("Buka", 14f, 12, 8) { loadAddressFromField() })
        root.addView(addressRow, LinearLayout.LayoutParams(-1, -2))

        val zoomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(2), dp(8), dp(2))
            setBackgroundColor(Color.rgb(18, 18, 18))
        }
        zoomLabel = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.LTGRAY)
            text = "Geser kiri/kanan untuk zoom"
            gravity = Gravity.CENTER_VERTICAL
        }
        zoomRow.addView(zoomLabel, LinearLayout.LayoutParams(0, dp(38), 1f))
        zoomLockButton = makeButton(if (zoomLocked) "Zoom 🔒" else "Zoom 🔓", 12f, 10, 6) { toggleZoomLock() }
        zoomRow.addView(zoomLockButton)
        zoomRow.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    swipeStartX = event.x
                    swipeStartY = event.y
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val dx = event.x - swipeStartX
                    val dy = event.y - swipeStartY
                    if (kotlin.math.abs(dx) > dp(28) && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.25f) {
                        changeZoom(if (dx > 0) 0.1f else -0.1f)
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> true
            }
        }
        root.addView(zoomRow, LinearLayout.LayoutParams(-1, -2))

        geckoView = GeckoView(this)
        geckoView.setBackgroundColor(Color.BLACK)
        geckoView.setSession(session)
        root.addView(geckoView, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        updateZoomLabel()

        refreshInstalledExtension()
        session.loadUri("https://m.youtube.com/")
    }

    private fun configureExtensionPrompts() {
        runtime.webExtensionController.setPromptDelegate(
            object : WebExtensionController.PromptDelegate {
                override fun onInstallPromptRequest(
                    extension: WebExtension,
                    permissions: Array<out String>,
                    origins: Array<out String>
                ): GeckoResult<WebExtension.PermissionPromptResponse>? {
                    val isOfficialUbo = extension.metaData.name == "uBlock Origin" &&
                        extension.id == uboId
                    // Permission approval is only possible after the explicit
                    // confirmation dialog shown by SakuTube's settings menu.
                    val approved = (isOfficialUbo && allowUboInstallPrompt) || allowLocalExtensionInstallPrompt
                    return GeckoResult.fromValue(
                        WebExtension.PermissionPromptResponse(approved, false)
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
        setPadding(dp(horizontalPadding), dp(verticalPadding), dp(horizontalPadding), dp(verticalPadding))
        minWidth = dp(48)
        minHeight = dp(48)
        contentDescription = label
        setOnClickListener { action() }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun roundedBackground(color: Int, radiusDp: Int) = android.graphics.drawable.GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun loadAddressFromField() {
        val raw = addressField.text.toString().trim()
        if (raw.isEmpty()) return
        val target = when {
            raw.startsWith("https://", true) || raw.startsWith("http://", true) -> raw
            raw.contains(" ") || !raw.contains(".") -> "https://www.google.com/search?q=" + Uri.encode(raw)
            else -> "https://$raw"
        }
        try {
            addressField.hideKeyboard()
        } catch (_: Exception) { }
        session.loadUri(target)
    }

    private fun EditText.hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(windowToken, 0)
    }

    private fun toggleZoomLock() {
        zoomLocked = !zoomLocked
        browserPrefs.edit().putBoolean("zoom_locked", zoomLocked).apply()
        zoomLockButton.text = if (zoomLocked) "Zoom 🔒" else "Zoom 🔓"
        updateZoomLabel()
        Toast.makeText(this, if (zoomLocked) "Zoom dikunci." else "Zoom bisa diubah dengan geser.", Toast.LENGTH_SHORT).show()
    }

    private fun changeZoom(delta: Float) {
        if (zoomLocked) {
            Toast.makeText(this, "Buka kunci zoom dulu untuk mengubah ukuran.", Toast.LENGTH_SHORT).show()
            return
        }
        zoomFactor = (zoomFactor + delta).coerceIn(0.7f, 1.6f)
        runtime.settings.setAutomaticFontSizeAdjustment(false)
        runtime.settings.setFontSizeFactor(zoomFactor)
        browserPrefs.edit().putFloat("zoom_factor", zoomFactor).apply()
        updateZoomLabel()
        session.reload()
    }

    private fun updateZoomLabel() {
        if (!::zoomLabel.isInitialized) return
        zoomLabel.text = "Geser kiri/kanan • teks " + (zoomFactor * 100).toInt() + "%"
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
        items += "Audio latar belakang: " + if (backgroundAudioEnabled) "ON" else "OFF"
        actions += { toggleBackgroundAudio() }
        items += "Kelola semua extension"
        actions += { showInstalledExtensions() }
        items += "Kunci zoom: " + if (zoomLocked) "ON" else "OFF"
        actions += { toggleZoomLock() }

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
                    Toast.makeText(this, "Extension terpasang: " + (extension?.metaData?.name ?: "Extension"), Toast.LENGTH_LONG).show()
                    refreshInstalledExtension()
                    session.reload()
                },
                { error ->
                    allowLocalExtensionInstallPrompt = false
                    Toast.makeText(
                        this,
                        "Gagal memasang extension. Pastikan XPI valid dan ditandatangani Mozilla. " +
                            (error?.message ?: ""),
                        Toast.LENGTH_LONG
                    ).show()
                }
            )
    }

    private fun showInstalledExtensions() {
        runtime.webExtensionController.list().accept(
            { extensions ->
                runOnUiThread {
                    val installed = extensions.orEmpty()
                    if (installed.isEmpty()) {
                        AlertDialog.Builder(this).setTitle("Extension terpasang")
                            .setMessage("Belum ada extension terpasang.")
                            .setPositiveButton("Tutup", null).show()
                        return@runOnUiThread
                    }
                    val labels = installed.map { extension ->
                        val name = extension.metaData?.name ?: extension.id ?: "Extension"
                        val state = if (extension.metaData?.enabled == false) "OFF" else "ON"
                        "$name · $state" + if (extension.isBuiltIn) " · bawaan" else ""
                    }.toTypedArray()
                    AlertDialog.Builder(this)
                        .setTitle("Kelola extension")
                        .setItems(labels) { _, which ->
                            installed.getOrNull(which)?.let { showExtensionActions(it) }
                        }
                        .setPositiveButton("Selesai", null)
                        .show()
                }
            },
            { Toast.makeText(this, "Tidak bisa membaca daftar extension.", Toast.LENGTH_LONG).show() }
        )
    }

    private fun showExtensionActions(extension: WebExtension) {
        val name = extension.metaData?.name ?: extension.id ?: "Extension"
        val enabled = extension.metaData?.enabled != false
        val items = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()
        val optionsUrl = extension.metaData?.optionsPageUrl
        if (!optionsUrl.isNullOrBlank()) {
            items += "Buka pengaturan"
            actions += { session.loadUri(optionsUrl) }
        }
        items += if (enabled) "Nonaktifkan extension" else "Aktifkan extension"
        actions += {
            val operation = if (enabled) {
                runtime.webExtensionController.disable(extension, WebExtensionController.EnableSource.USER)
            } else {
                runtime.webExtensionController.enable(extension, WebExtensionController.EnableSource.USER)
            }
            operation.accept(
                { updated ->
                    val state = if (updated?.metaData?.enabled == false) "dinonaktifkan" else "diaktifkan"
                    Toast.makeText(this, "$name $state.", Toast.LENGTH_SHORT).show()
                    refreshInstalledExtension()
                },
                { Toast.makeText(this, "Tidak bisa mengubah status $name.", Toast.LENGTH_LONG).show() }
            )
        }
        if (!extension.isBuiltIn) {
            items += "Hapus extension"
            actions += {
                AlertDialog.Builder(this)
                    .setTitle("Hapus $name?")
                    .setMessage("Extension dan data lokalnya akan dihapus dari SakuTube.")
                    .setPositiveButton("Hapus") { _, _ ->
                        runtime.webExtensionController.uninstall(extension).accept(
                            {
                                Toast.makeText(this, "$name dihapus.", Toast.LENGTH_SHORT).show()
                                refreshInstalledExtension()
                            },
                            { Toast.makeText(this, "Gagal menghapus $name.", Toast.LENGTH_LONG).show() }
                        )
                    }
                    .setNegativeButton("Batal", null)
                    .show()
            }
        }
        AlertDialog.Builder(this).setTitle(name)
            .setItems(items.toTypedArray()) { _, which -> actions[which].invoke() }
            .setNegativeButton("Tutup", null).show()
    }

    private fun toggleBackgroundAudio() {
        backgroundAudioEnabled = !backgroundAudioEnabled
        browserPrefs.edit().putBoolean("background_audio", backgroundAudioEnabled).apply()
        session.settings.setSuspendMediaWhenInactive(!backgroundAudioEnabled)
        if (backgroundAudioEnabled) session.setPriorityHint(GeckoSession.PRIORITY_HIGH)
        else session.setPriorityHint(GeckoSession.PRIORITY_DEFAULT)
        Toast.makeText(
            this,
            if (backgroundAudioEnabled) "Audio latar belakang diaktifkan. Putar audio dulu; Android tetap bisa membatasi aplikasi di latar." else "Audio latar belakang dimatikan.",
            Toast.LENGTH_LONG
        ).show()
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
                    if (extension != null && extension.id == uboId && extension.metaData?.name == "uBlock Origin") {
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
                uboExtension = extensions.orEmpty().firstOrNull { it.id == uboId && it.metaData?.name == "uBlock Origin" }
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
        if (::session.isInitialized) {
            session.setActive(isInPictureInPictureMode || backgroundAudioEnabled)
            if (backgroundAudioEnabled) session.setPriorityHint(GeckoSession.PRIORITY_HIGH)
        }
    }

    override fun onResume() {
        super.onResume()
        if (::session.isInitialized) {
            session.setActive(true)
            session.setPriorityHint(GeckoSession.PRIORITY_DEFAULT)
        }
    }

    override fun onDestroy() {
        if (::geckoView.isInitialized) geckoView.releaseSession()
        if (::session.isInitialized) session.close()
        super.onDestroy()
    }
}

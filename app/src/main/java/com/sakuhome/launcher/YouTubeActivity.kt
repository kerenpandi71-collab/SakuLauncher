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
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

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
    private lateinit var addressRowView: LinearLayout
    private lateinit var zoomRowView: LinearLayout
    private lateinit var zoomLabel: TextView
    private lateinit var zoomLockButton: TextView
    private lateinit var runtime: GeckoRuntime
    private var uboExtension: WebExtension? = null
    private var autoDubExtension: WebExtension? = null
    private var autoDubTts: TextToSpeech? = null
    private var autoDubTtsReady = false
    private var autoDubPendingText: String? = null
    private val autoDubSpeakResults = java.util.concurrent.ConcurrentHashMap<String, GeckoResult<Any>>()
    private var allowUboInstallPrompt = false
    private var allowLocalExtensionInstallPrompt = false
    private var desktopSiteEnabled = false
    private var backgroundAudioEnabled = true
    private var pipEnabled = true
    private var zoomLocked = false
    private var zoomFactor = 1.0f
    private var swipeStartX = 0f
    private var swipeStartY = 0f
    private val browserPrefs by lazy { getSharedPreferences("sakutube_browser", MODE_PRIVATE) }

    private val uboId = "uBlock0@raymondhill.net"
    private val uboXpiUrl = "https://addons.mozilla.org/firefox/downloads/latest/ublock-origin/latest.xpi"
    private val pickExtensionRequest = 4207
    private val storeExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val popupSessions = mutableSetOf<GeckoSession>()
    private val popupDialogs = mutableSetOf<AlertDialog>()
    private data class BrowserTab(val id: Int, val session: GeckoSession, var url: String, var title: String, val privateMode: Boolean = false)
    private val browserTabs = mutableListOf<BrowserTab>()
    private var nextTabId = 1
    private var tabsDialog: AlertDialog? = null
    private data class DownloadEntry(
        val id: Long,
        val name: String,
        var bytesDownloaded: Long = 0L,
        var totalBytes: Long = -1L,
        var status: String = "Menunggu",
        var outputUri: Uri? = null
    )
    private val trackedDownloads = mutableListOf<DownloadEntry>()
    private var nextDownloadId = 1L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        autoDubTts = TextToSpeech(this) { status ->
            runOnUiThread {
                val engineReady = status == TextToSpeech.SUCCESS
                val languageResult = if (engineReady) {
                    try { autoDubTts?.setLanguage(Locale("id", "ID")) ?: TextToSpeech.LANG_NOT_SUPPORTED }
                    catch (_: Exception) { TextToSpeech.LANG_NOT_SUPPORTED }
                } else {
                    TextToSpeech.LANG_NOT_SUPPORTED
                }
                autoDubTtsReady = engineReady && languageResult >= TextToSpeech.LANG_AVAILABLE
                if (autoDubTtsReady) {
                    val pending = autoDubPendingText
                    autoDubPendingText = null
                    if (!pending.isNullOrBlank()) {
                        try {
                            autoDubTts?.speak(pending, TextToSpeech.QUEUE_FLUSH, null, "saku-auto-dub")
                        } catch (_: Exception) {
                            Toast.makeText(this, "Mesin TTS gagal membacakan teks.", Toast.LENGTH_LONG).show()
                        }
                    }
                } else {
                    Toast.makeText(
                        this,
                        if (!engineReady)
                            "Auto Dub perlu mesin TTS Android. Pasang/aktifkan mesin Text-to-Speech di Setelan."
                        else
                            "Suara Indonesia belum tersedia. Buka Setelan > Text-to-speech > instal data suara Bahasa Indonesia.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
        autoDubTts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}

            override fun onDone(utteranceId: String?) {
                utteranceId?.let { id ->
                    autoDubSpeakResults.remove(id)?.complete(JSONObject().put("ok", true))
                }
            }

            override fun onError(utteranceId: String?) {
                utteranceId?.let { id ->
                    autoDubSpeakResults.remove(id)?.complete(JSONObject().put("ok", false).put("error", "TTS gagal membacakan teks"))
                }
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                utteranceId?.let { id ->
                    autoDubSpeakResults.remove(id)?.complete(JSONObject().put("ok", false).put("error", "Pembacaan dihentikan"))
                }
            }
        })
        runtime = GeckoRuntime.getDefault(this)
        configureExtensionPrompts()
        installBundledAutoDub()
        backgroundAudioEnabled = browserPrefs.getBoolean("background_audio", true)
        pipEnabled = browserPrefs.getBoolean("pip_enabled", true)
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
        if (backgroundAudioEnabled) {
            try {
                val audioService = Intent(this, SakuTubePlaybackService::class.java).setAction(SakuTubePlaybackService.ACTION_START)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(audioService) else startService(audioService)
            } catch (_: Exception) { /* Playback remains available while SakuTube is visible. */ }
        }

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

        // Controls use a 54dp minimum touch target for easier use on a phone.
        toolbar.addView(makeButton("⋮", 21f, 10, 8) { showSettingsMenu() })
        toolbar.addView(makeButton("‹", 24f, 10, 8) { session.goBack() })
        toolbar.addView(makeButton("Mini", 13f, 10, 8) { enterMiniPlayer() })
        toolbar.addView(makeButton("⟳", 20f, 10, 8) { session.reload() })
        toolbar.addView(makeButton("✕", 19f, 10, 8) { finish() })
        toolbar.addView(makeButton("+ Tab", 12f, 8, 8) { openNewTab() })
        toolbar.addView(makeButton("Tabs 1", 11f, 6, 8) { showTabsOverview() }.apply { contentDescription = "Tabs" })
        toolbar.addView(makeButton("↓", 18f, 8, 8) { showDownloadsMenu() })
        root.addView(toolbar, LinearLayout.LayoutParams(-1, -2))

        addressRowView = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(5), dp(8), dp(5))
            setBackgroundColor(Color.rgb(26, 26, 26))
        }
        addressField = EditText(this).apply {
            hint = "Cari atau masukkan URL"
            textSize = 14f
            setSingleLine(true)
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
        addressRowView.addView(addressField, LinearLayout.LayoutParams(0, dp(44), 1f))
        addressRowView.addView(makeButton("Buka", 14f, 12, 8) { loadAddressFromField() })
        root.addView(addressRowView, LinearLayout.LayoutParams(-1, -2))

        zoomRowView = LinearLayout(this).apply {
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
        zoomRowView.addView(zoomLabel, LinearLayout.LayoutParams(0, dp(38), 1f))
        zoomLockButton = makeButton(if (zoomLocked) "Zoom 🔒" else "Zoom 🔓", 12f, 10, 6) { toggleZoomLock() }
        zoomRowView.addView(zoomLockButton)
        zoomRowView.setOnTouchListener { _, event ->
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
        root.addView(zoomRowView, LinearLayout.LayoutParams(-1, -2))

        session.setNavigationDelegate(createPopupNavigationDelegate())
        session.setProgressDelegate(createProgressDelegate())
        session.setContentDelegate(createDownloadContentDelegate())

        geckoView = GeckoView(this)
        geckoView.setBackgroundColor(Color.BLACK)
        geckoView.setSession(session)
        root.addView(geckoView, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        updateZoomLabel()

        refreshInstalledExtension()
        val initialUrl = intent.getStringExtra("sakutube_url") ?: "https://m.youtube.com/"
        browserTabs.add(BrowserTab(nextTabId++, session, initialUrl, "Tab 1"))
        addressField.setText(initialUrl)
        session.loadUri(initialUrl)
        recordHistory(initialUrl)
        updateTabsButton()
    }

    /**
     * Handle target=_blank/window.open requests inside SakuTube instead of silently
     * dropping them. OAuth pages and extension links can use these new-window requests.
     */
    private fun createProgressDelegate(): GeckoSession.ProgressDelegate {
        return object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                runOnUiThread {
                    val tab = browserTabs.firstOrNull { it.session === session }
                    if (tab != null) {
                        tab.url = url
                        if (tab.title == "Tab baru" || tab.title == "Tab Samaran" || tab.title == "Tab 1") tab.title = Uri.parse(url).host ?: url
                    }
                    if (this@YouTubeActivity.session === session && ::addressField.isInitialized) addressField.setText(url)
                    updateTabsButton()
                    if (tab?.privateMode != true) recordHistory(url)
                }
            }
        }
    }

    private fun createDownloadContentDelegate(): GeckoSession.ContentDelegate {
        return object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) {
                val tab = browserTabs.firstOrNull { it.session === session } ?: return
                tab.title = title?.takeIf { it.isNotBlank() } ?: tab.url
                runOnUiThread { updateTabsButton() }
            }

            override fun onExternalResponse(session: GeckoSession, response: org.mozilla.geckoview.WebResponse) {
                val url = response.uri
                val contentType = response.headers.entries.firstOrNull { it.key.equals("content-type", true) }?.value ?: ""
                val disposition = response.headers.entries.firstOrNull { it.key.equals("content-disposition", true) }?.value ?: ""
                val rawName = disposition.substringAfter("filename=", "").trim().trim('"').substringBefore(";")
                val filename = (if (rawName.isNotBlank()) rawName else Uri.parse(url).lastPathSegment ?: "download").substringAfterLast("/").substringBefore("?")
                if (url.contains("addons.mozilla.org", true) && (url.substringBefore("?").endsWith(".xpi", true) || contentType.contains("x-xpinstall", true) || filename.endsWith(".xpi", true))) {
                    installExtensionFromResponse(response, filename)
                } else {
                    downloadResponse(response, filename, contentType)
                }
            }
        }
    }

    private fun installExtensionFromResponse(response: org.mozilla.geckoview.WebResponse, filename: String) {
        runOnUiThread {
            AlertDialog.Builder(this)
                .setTitle("Pasang extension Mozilla?")
                .setMessage("SakuTube menerima paket dari addons.mozilla.org. GeckoView akan memvalidasi paket dan meminta persetujuan izin sebelum pemasangan.")
                .setPositiveButton("Lanjutkan") { _, _ ->
                    storeExecutor.execute {
                        var tempFile: File? = null
                        try {
                            tempFile = File.createTempFile("sakutube-addon-", ".xpi", cacheDir)
                            val body = response.body
                            if (body == null) {
                                runOnUiThread {
                                    runtime.webExtensionController.install(response.uri, WebExtensionController.INSTALLATION_METHOD_MANAGER).accept(
                                        { installed ->
                                            if (installed != null) {
                                                Toast.makeText(this, "Extension terpasang: " + (installed.metaData?.name ?: filename), Toast.LENGTH_LONG).show()
                                                refreshInstalledExtension()
                                                session.reload()
                                            } else Toast.makeText(this, "Pemasangan tidak mengembalikan hasil.", Toast.LENGTH_LONG).show()
                                        },
                                        { error -> Toast.makeText(this, "Pemasangan gagal: " + (error?.message ?: ""), Toast.LENGTH_LONG).show() }
                                    )
                                }
                                return@execute
                            }
                            body.use { input -> tempFile!!.outputStream().use { output -> input.copyTo(output) } }
                            val localUri = Uri.fromFile(tempFile!!).toString()
                            runOnUiThread {
                                runtime.webExtensionController.install(localUri, WebExtensionController.INSTALLATION_METHOD_FROM_FILE).accept(
                                    { installed ->
                                        if (installed != null) {
                                            Toast.makeText(this, "Extension terpasang: " + (installed.metaData?.name ?: filename), Toast.LENGTH_LONG).show()
                                            refreshInstalledExtension()
                                            session.reload()
                                        } else Toast.makeText(this, "Pemasangan tidak mengembalikan hasil.", Toast.LENGTH_LONG).show()
                                        tempFile?.delete()
                                    },
                                    { error ->
                                        Toast.makeText(this, "Pemasangan gagal: " + (error?.message ?: "extension tidak kompatibel"), Toast.LENGTH_LONG).show()
                                        tempFile?.delete()
                                    }
                                )
                            }
                        } catch (error: Exception) {
                            tempFile?.delete()
                            runOnUiThread { Toast.makeText(this, "Gagal menyiapkan extension: " + (error.message ?: ""), Toast.LENGTH_LONG).show() }
                        }
                    }
                }
                .setNegativeButton("Batal") { _, _ -> try { response.body?.close() } catch (_: Exception) { } }
                .setOnCancelListener { try { response.body?.close() } catch (_: Exception) { } }
                .show()
        }
    }

    private fun downloadResponse(response: org.mozilla.geckoview.WebResponse, filenameHint: String, contentType: String) {
        val safeName = filenameHint.map { if (it.isLetterOrDigit() || it in "._-") it else '_' }.joinToString("").ifBlank { "download" }
        runOnUiThread {
            AlertDialog.Builder(this)
                .setTitle("Unduh file?")
                .setMessage("Simpan " + safeName + " ke folder Download? Progres tersedia di menu ↓.")
                .setPositiveButton("Unduh") { _, _ ->
                    val totalBytes = response.headers.entries.firstOrNull { it.key.equals("content-length", true) }?.value?.toLongOrNull() ?: -1L
                    val entry = DownloadEntry(nextDownloadId++, safeName, totalBytes = totalBytes)
                    trackedDownloads.add(0, entry)
                    storeExecutor.execute {
                        var outputUri: Uri? = null
                        try {
                            val values = android.content.ContentValues().apply {
                                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, safeName)
                                put(android.provider.MediaStore.Downloads.MIME_TYPE, contentType.substringBefore(";").ifBlank { "application/octet-stream" })
                                put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
                            }
                            outputUri = contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                                ?: throw IOException("Android tidak bisa membuat file di Download.")
                            entry.outputUri = outputUri
                            entry.status = "Mengunduh"
                            val body = response.body ?: throw IOException("Respons unduhan kosong.")
                            body.use { input ->
                                val output = contentResolver.openOutputStream(outputUri!!) ?: throw IOException("Tidak bisa membuka file tujuan.")
                                output.use { sink ->
                                    val buffer = ByteArray(32 * 1024)
                                    while (true) {
                                        val count = input.read(buffer)
                                        if (count < 0) break
                                        if (count == 0) continue
                                        sink.write(buffer, 0, count)
                                        entry.bytesDownloaded += count
                                        runOnUiThread { refreshDownloadsDialogIfOpen() }
                                    }
                                }
                            }
                            val done = android.content.ContentValues().apply { put(android.provider.MediaStore.Downloads.IS_PENDING, 0) }
                            contentResolver.update(outputUri!!, done, null, null)
                            entry.status = "Selesai"
                            runOnUiThread { Toast.makeText(this, "File disimpan: Download/" + safeName, Toast.LENGTH_LONG).show(); refreshDownloadsDialogIfOpen() }
                        } catch (error: Exception) {
                            outputUri?.let { try { contentResolver.delete(it, null, null) } catch (_: Exception) { } }
                            try { response.body?.close() } catch (_: Exception) { }
                            entry.status = "Gagal: " + (error.message ?: "kesalahan")
                            runOnUiThread { Toast.makeText(this, "Unduhan gagal: " + (error.message ?: ""), Toast.LENGTH_LONG).show(); refreshDownloadsDialogIfOpen() }
                        }
                    }
                }
                .setNegativeButton("Batal") { _, _ -> try { response.body?.close() } catch (_: Exception) { } }
                .setOnCancelListener { try { response.body?.close() } catch (_: Exception) { } }
                .show()
        }
    }

    private fun createPopupNavigationDelegate(): GeckoSession.NavigationDelegate {
        return object : GeckoSession.NavigationDelegate {
            override fun onNewSession(
                sourceSession: GeckoSession,
                uri: String
            ): GeckoResult<GeckoSession>? {
                return try {
                    // Treat window.open()/target=_blank as a normal browser tab, not a
                    // modal dialog. OAuth and extension sign-in flows often need to
                    // navigate through several redirects and must remain visible.
                    val newSession = GeckoSession(
                        GeckoSessionSettings.Builder()
                            .allowJavascript(true)
                            .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
                            .build()
                    )
                    newSession.settings.setSuspendMediaWhenInactive(!backgroundAudioEnabled)
                    newSession.setNavigationDelegate(createPopupNavigationDelegate())
                    newSession.setProgressDelegate(createProgressDelegate())
                    newSession.setContentDelegate(createDownloadContentDelegate())
                    newSession.open(runtime)

                    val startUrl = uri.ifBlank { "about:blank" }
                    val tab = BrowserTab(
                        nextTabId++,
                        newSession,
                        startUrl,
                        Uri.parse(startUrl).host ?: "Login / Tab baru"
                    )
                    browserTabs.add(tab)
                    runOnUiThread {
                        switchToTab(tab.id)
                        updateTabsButton()
                        Toast.makeText(
                            this@YouTubeActivity,
                            "Halaman login dibuka di tab baru. Setelah selesai, kembali ke tab extension.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    GeckoResult.fromValue(newSession)
                } catch (error: Exception) {
                    runOnUiThread {
                        Toast.makeText(
                            this@YouTubeActivity,
                            "Tidak bisa membuka tab login: " + (error.message ?: "kesalahan"),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    GeckoResult.fromValue(null)
                }
            }
        }
    }

    private fun showInAppPopup(popupSession: GeckoSession, initialUri: String) {
        val popupRoot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        val popupToolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setBackgroundColor(Color.rgb(24, 24, 24))
        }
        val popupTitle = TextView(this).apply {
            text = "Jendela popup / login"
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL
        }
        popupToolbar.addView(popupTitle, LinearLayout.LayoutParams(0, dp(48), 1f))
        val closeButton = makeButton("Tutup", 13f, 10, 6) { }
        popupToolbar.addView(closeButton)
        popupRoot.addView(popupToolbar, LinearLayout.LayoutParams(-1, -2))

        val popupView = GeckoView(this).apply {
            setBackgroundColor(Color.BLACK)
            setSession(popupSession)
        }
        popupRoot.addView(popupView, LinearLayout.LayoutParams(-1, 0, 1f))

        val dialog = AlertDialog.Builder(this)
            .setView(popupRoot)
            .create()
        closeButton.setOnClickListener { dialog.dismiss() }
        dialog.setOnDismissListener {
            popupDialogs.remove(dialog)
            popupSessions.remove(popupSession)
            try { popupView.releaseSession() } catch (_: Exception) { }
            try { popupSession.close() } catch (_: Exception) { }
        }
        popupDialogs.add(dialog)
        dialog.show()
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.96f).toInt(),
            (resources.displayMetrics.heightPixels * 0.82f).toInt()
        )
        // The initial URI is loaded by Gecko after onNewSession returns its new session.
        @Suppress("UNUSED_VARIABLE")
        val requestedUri = initialUri
    }

    private fun configureExtensionPrompts() {
        runtime.webExtensionController.setPromptDelegate(
            object : WebExtensionController.PromptDelegate {
                override fun onInstallPromptRequest(
                    extension: WebExtension,
                    permissions: Array<out String>,
                    origins: Array<out String>
                ): GeckoResult<WebExtension.PermissionPromptResponse>? {
                    val result = GeckoResult<WebExtension.PermissionPromptResponse>()
                    val name = extension.metaData.name ?: extension.id ?: "Extension"
                    val permissionText = buildString {
                        append("Extension: ").append(name).append("\\n\\n")
                        if (permissions.isNotEmpty()) {
                            append("Izin: ").append(permissions.joinToString(", ")).append("\\n")
                        }
                        if (origins.isNotEmpty()) {
                            append("Akses situs: ").append(origins.joinToString(", ")).append("\\n")
                        }
                        append("\\nPasang hanya jika kamu mempercayai extension ini.")
                    }
                    runOnUiThread {
                        if (isFinishing || isDestroyed) {
                            result.complete(WebExtension.PermissionPromptResponse(false, false))
                        } else {
                            AlertDialog.Builder(this@YouTubeActivity)
                                .setTitle("Izin pemasangan extension")
                                .setMessage(permissionText)
                                .setPositiveButton("Pasang") { _, _ ->
                                    result.complete(WebExtension.PermissionPromptResponse(true, false))
                                }
                                .setNegativeButton("Batal") { _, _ ->
                                    result.complete(WebExtension.PermissionPromptResponse(false, false))
                                }
                                .setOnCancelListener {
                                    result.complete(WebExtension.PermissionPromptResponse(false, false))
                                }
                                .show()
                        }
                    }
                    return result
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
        minWidth = dp(54)
        minHeight = dp(54)
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
        addressField.setText(target)
        session.loadUri(target)
        recordHistory(target)
    }

    private fun EditText.hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(windowToken, 0)
    }

    private fun openNewTab(privateMode: Boolean = false) {
        try {
            val settings = GeckoSessionSettings.Builder()
                .allowJavascript(true)
                .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
                .usePrivateMode(privateMode)
                .build()
            val newSession = GeckoSession(settings)
            newSession.settings.setSuspendMediaWhenInactive(!backgroundAudioEnabled)
            newSession.open(runtime)
            newSession.setNavigationDelegate(createPopupNavigationDelegate())
            newSession.setProgressDelegate(createProgressDelegate())
            newSession.setContentDelegate(createDownloadContentDelegate())
            val url = "https://m.youtube.com/"
            val tab = BrowserTab(nextTabId++, newSession, url, if (privateMode) "Tab Samaran" else "Tab baru", privateMode)
            browserTabs.add(tab)
            switchToTab(tab.id)
            newSession.loadUri(url)
            updateTabsButton()
            Toast.makeText(this, if (privateMode) "Tab samaran dibuka." else "Tab baru dibuka.", Toast.LENGTH_SHORT).show()
        } catch (error: Exception) {
            Toast.makeText(this, "Tidak bisa membuka tab: " + (error.message ?: ""), Toast.LENGTH_LONG).show()
        }
    }

    private fun switchToTab(id: Int) {
        val tab = browserTabs.firstOrNull { it.id == id } ?: return
        if (::session.isInitialized && session !== tab.session) session.setActive(false)
        session = tab.session
        if (::geckoView.isInitialized) geckoView.setSession(session)
        session.setActive(true)
        addressField.setText(tab.url)
        updateTabsButton()
    }

    private fun closeTab(id: Int) {
        val tab = browserTabs.firstOrNull { it.id == id } ?: return
        if (browserTabs.size <= 1) {
            tab.session.loadUri("https://m.youtube.com/")
            tab.url = "https://m.youtube.com/"
            tab.title = "Tab baru"
            addressField.setText(tab.url)
            updateTabsButton()
            return
        }
        val wasActive = tab.session === session
        browserTabs.remove(tab)
        if (wasActive) switchToTab(browserTabs.last().id)
        try { tab.session.close() } catch (_: Exception) { }
        updateTabsButton()
    }

    private fun updateTabsButton() {
        if (!::toolbar.isInitialized) return
        for (i in 0 until toolbar.childCount) {
            val child = toolbar.getChildAt(i)
            if (child is TextView && child.contentDescription == "Tabs") {
                child.text = "Tabs " + browserTabs.size
                break
            }
        }
    }

    private fun showTabsOverview() {
        val outer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8)) }
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        actions.addView(makeButton("+ Tab", 13f, 4, 4) { openNewTab(); tabsDialog?.dismiss() })
        actions.addView(makeButton("Samaran", 12f, 4, 4) { openNewTab(true); tabsDialog?.dismiss() })
        actions.addView(makeButton("Tutup lain", 12f, 4, 4) {
            val activeId = browserTabs.firstOrNull { it.session === session }?.id
            browserTabs.toList().filter { it.id != activeId }.forEach { closeTab(it.id) }
            tabsDialog?.dismiss()
        })
        outer.addView(actions)
        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        browserTabs.toList().forEach { tab ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(8), dp(8), dp(8))
                background = roundedBackground(if (tab.session === session) Color.rgb(48, 56, 68) else Color.rgb(30, 30, 30), 12)
            }
            val details = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), 0, dp(4), 0) }
            val title = TextView(this).apply { text = (if (tab.privateMode) "Samaran • " else "") + tab.title; setTextColor(Color.WHITE); textSize = 14f; maxLines = 1 }
            val url = TextView(this).apply { text = tab.url; setTextColor(Color.LTGRAY); textSize = 11f; maxLines = 2 }
            details.addView(title)
            details.addView(url)
            card.addView(details, LinearLayout.LayoutParams(0, -2, 1f))
            card.addView(makeButton("Buka", 12f, 4, 4) { switchToTab(tab.id); tabsDialog?.dismiss() })
            card.addView(makeButton("×", 18f, 4, 4) { closeTab(tab.id); tabsDialog?.dismiss(); showTabsOverview() })
            val params = LinearLayout.LayoutParams(-1, -2)
            params.bottomMargin = dp(8)
            list.addView(card, params)
        }
        scroll.addView(list)
        outer.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        tabsDialog = AlertDialog.Builder(this).setTitle("Tab terbuka (" + browserTabs.size + ")").setView(outer).setNegativeButton("Selesai", null).create()
        tabsDialog?.show()
        tabsDialog?.window?.setLayout((resources.displayMetrics.widthPixels * 0.96f).toInt(), (resources.displayMetrics.heightPixels * 0.82f).toInt())
    }

    private fun recordHistory(url: String) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return
        val history = try {
            val saved = org.json.JSONArray(browserPrefs.getString("history_json", "[]") ?: "[]")
            (0 until saved.length()).mapNotNull { saved.optString(it).takeIf { value -> value.isNotBlank() } }.toMutableList()
        } catch (_: Exception) { mutableListOf() }
        history.remove(url)
        history.add(0, url)
        val ordered = org.json.JSONArray()
        history.take(300).forEach { ordered.put(it) }
        browserPrefs.edit().putString("history_json", ordered.toString()).apply()
    }

    private fun showHistory() {
        val history = try {
            val saved = org.json.JSONArray(browserPrefs.getString("history_json", "[]") ?: "[]")
            (0 until saved.length()).mapNotNull { saved.optString(it).takeIf { value -> value.isNotBlank() } }
        } catch (_: Exception) { emptyList() }
        if (history.isEmpty()) {
            AlertDialog.Builder(this).setTitle("Riwayat penelusuran")
                .setMessage("Belum ada riwayat yang tersimpan.")
                .setPositiveButton("OK", null).show()
            return
        }
        AlertDialog.Builder(this).setTitle("Riwayat penelusuran")
            .setItems(history.toTypedArray()) { _, which ->
                val url = history[which]
                addressField.setText(url)
                session.loadUri(url)
                recordHistory(url)
            }
            .setNeutralButton("Hapus riwayat") { _, _ ->
                AlertDialog.Builder(this).setTitle("Hapus riwayat?")
                    .setMessage("Semua riwayat penelusuran SakuTube akan dihapus. Cookie dan sesi login tidak ikut dihapus.")
                    .setPositiveButton("Hapus") { _, _ ->
                        browserPrefs.edit().remove("history_json").remove("history_urls").apply()
                        Toast.makeText(this, "Riwayat dihapus.", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Batal", null).show()
            }
            .setNegativeButton("Tutup", null).show()
    }

    private fun downloadCurrentUrl() {
        val raw = addressField.text.toString().trim()
        if (!raw.startsWith("https://") && !raw.startsWith("http://")) {
            Toast.makeText(this, "Buka halaman atau file yang ingin diunduh terlebih dahulu.", Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this).setTitle("Unduh URL saat ini")
            .setMessage("SakuTube akan meminta Android mengunduh URL halaman saat ini. Untuk mengunduh file, buka URL file langsung terlebih dahulu.")
            .setPositiveButton("Unduh") { _, _ ->
                try {
                    val request = android.app.DownloadManager.Request(Uri.parse(raw))
                        .setTitle(raw.substringAfterLast('/').ifBlank { "unduhan" })
                        .setDescription("Unduhan dari SakuTube")
                        .setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        .setAllowedOverMetered(true)
                        .setAllowedOverRoaming(false)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        request.setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, raw.substringAfterLast('/').substringBefore('?').ifBlank { "download" })
                    } else {
                        request.setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, raw.substringAfterLast('/').substringBefore('?').ifBlank { "download" })
                    }
                    val manager = getSystemService(DOWNLOAD_SERVICE) as android.app.DownloadManager
                    manager.enqueue(request)
                    Toast.makeText(this, "Unduhan dimulai. Lokasi: Download", Toast.LENGTH_LONG).show()
                } catch (error: Exception) {
                    Toast.makeText(this, "Tidak dapat memulai unduhan: " + (error.message ?: ""), Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Batal", null).show()
    }

    private var downloadsDialog: AlertDialog? = null
    private var downloadsListView: LinearLayout? = null

    private fun showDownloadsMenu() {
        AlertDialog.Builder(this).setTitle("Unduhan")
            .setItems(arrayOf("Lihat progres unduhan SakuTube", "Unduh URL saat ini", "Buka daftar unduhan Android")) { _, which ->
                when (which) {
                    0 -> showTrackedDownloads()
                    1 -> downloadCurrentUrl()
                    else -> openDownloads()
                }
            }
            .setNegativeButton("Tutup", null).show()
    }

    private fun showTrackedDownloads() {
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8)) }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        downloadsListView = list
        container.addView(list, LinearLayout.LayoutParams(-1, -2))
        container.addView(makeButton("Segarkan", 13f, 8, 4) { populateTrackedDownloads(list) })
        downloadsDialog = AlertDialog.Builder(this).setTitle("Progres unduhan").setView(ScrollView(this).apply { addView(container) }).setNegativeButton("Tutup", null).create()
        populateTrackedDownloads(list)
        downloadsDialog?.show()
        downloadsDialog?.window?.setLayout((resources.displayMetrics.widthPixels * 0.94f).toInt(), (resources.displayMetrics.heightPixels * 0.75f).toInt())
    }

    private fun refreshDownloadsDialogIfOpen() {
        if (downloadsDialog?.isShowing != true) return
        downloadsListView?.let { list -> populateTrackedDownloads(list) }
    }

    private fun populateTrackedDownloads(list: LinearLayout) {
        list.removeAllViews()
        if (trackedDownloads.isEmpty()) {
            list.addView(TextView(this).apply { text = "Belum ada unduhan dari SakuTube."; setTextColor(Color.LTGRAY); setPadding(0, dp(12), 0, dp(12)) })
            return
        }
        trackedDownloads.toList().forEach { item ->
            val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(8)); background = roundedBackground(Color.rgb(32, 32, 32), 10) }
            card.addView(TextView(this).apply { text = item.name; setTextColor(Color.WHITE); textSize = 14f })
            val amount = if (item.bytesDownloaded >= 1024L * 1024L) String.format(java.util.Locale.US, "%.2f MB", item.bytesDownloaded / (1024.0 * 1024.0)) else "${item.bytesDownloaded / 1024} KB"
            val progressText = if (item.totalBytes > 0L) " • " + ((item.bytesDownloaded * 100L / item.totalBytes).coerceIn(0L, 100L)) + "%" else ""
            card.addView(TextView(this).apply { text = "$amount$progressText • ${item.status}"; setTextColor(Color.LTGRAY); textSize = 12f })
            if (item.status == "Mengunduh") {
                val progress = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                    isIndeterminate = item.totalBytes <= 0L
                    if (item.totalBytes > 0L) { max = 100; this.progress = (item.bytesDownloaded * 100L / item.totalBytes).toInt().coerceIn(0, 100) }
                }
                card.addView(progress, LinearLayout.LayoutParams(-1, dp(6)))
            } else if (item.status == "Selesai") {
                card.addView(makeButton("Buka file", 12f, 4, 2) {
                    item.outputUri?.let { uri ->
                        try { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, contentResolver.getType(uri) ?: "*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
                        catch (_: Exception) { Toast.makeText(this, "Tidak ada aplikasi untuk membuka file ini.", Toast.LENGTH_SHORT).show() }
                    }
                })
            }
            val params = LinearLayout.LayoutParams(-1, -2)
            params.bottomMargin = dp(8)
            list.addView(card, params)
        }
    }

    private fun openDownloads() {
        try {
            startActivity(Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS))
        } catch (_: Exception) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("content://downloads/my_downloads")))
            } catch (_: Exception) {
                Toast.makeText(this, "Daftar unduhan Android tidak tersedia.", Toast.LENGTH_LONG).show()
            }
        }
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
        items += "Toko extension Mozilla"
        actions += { showExtensionStore() }
        items += "Cari & pasang extension (katalog)"
        actions += { promptExtensionSearch() }
        items += "Manajemen cache"
        actions += { showCacheManager() }
        items += "Riwayat penelusuran"
        actions += { showHistory() }
        items += "Unduh URL halaman saat ini"
        actions += { downloadCurrentUrl() }
        items += "Unduhan tersimpan"
        actions += { openDownloads() }
        items += "Situs desktop: " + if (desktopSiteEnabled) "ON" else "OFF"
        actions += { toggleDesktopMode() }
        items += "Audio latar belakang: " + if (backgroundAudioEnabled) "ON" else "OFF"
        actions += { toggleBackgroundAudio() }
        items += "Picture-in-Picture (popup): " + if (pipEnabled) "ON" else "OFF"
        actions += { togglePip() }
        items += "Kelola semua extension"
        actions += { showInstalledExtensions() }
        items += "Buka halaman di browser eksternal"
        actions += { openCurrentPageExternally() }
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


    private data class StoreExtension(val name: String, val summary: String, val xpiUrl: String)

    private fun promptExtensionSearch() {
        val input = EditText(this).apply {
            hint = "Nama extension, misalnya uBlock Origin"
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = roundedBackground(Color.rgb(42, 42, 42), 12)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input, LinearLayout.LayoutParams(-1, dp(48)))
        }
        AlertDialog.Builder(this).setTitle("Cari extension Mozilla")
            .setView(box)
            .setPositiveButton("Cari") { _, _ -> searchExtensionStore(input.text.toString().trim()) }
            .setNeutralButton("Tampilkan populer") { _, _ -> searchExtensionStore("") }
            .setNegativeButton("Batal", null).show()
    }

    private fun showExtensionStore() {
        // Open Mozilla's real Add-ons storefront in the current GeckoView session.
        val storeUrl = "https://addons.mozilla.org/firefox/"
        addressField.setText(storeUrl)
        session.loadUri(storeUrl)
    }

    private fun searchExtensionStore(query: String) {
        Toast.makeText(this, "Mengambil katalog Mozilla…", Toast.LENGTH_SHORT).show()
        storeExecutor.execute {
            var connection: HttpURLConnection? = null
            try {
                val encodedQuery = if (query.isBlank()) "" else "&q=" + Uri.encode(query)
                val endpoint = "https://addons.mozilla.org/api/v5/addons/search/?app=firefox&type=extension&page_size=20" + encodedQuery
                connection = URL(endpoint).openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 15000
                connection.readTimeout = 15000
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("User-Agent", "SakuTube/0.4.0")
                if (connection.responseCode !in 200..299) throw IOException("HTTP " + connection.responseCode)
                val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                val results = json.optJSONArray("results")
                val found = mutableListOf<StoreExtension>()
                if (results != null) {
                    for (i in 0 until results.length()) {
                        val addon = results.optJSONObject(i) ?: continue
                        val version = addon.optJSONObject("current_version") ?: continue
                        val files = version.optJSONArray("files") ?: continue
                        var xpi = ""
                        for (j in 0 until files.length()) {
                            val file = files.optJSONObject(j) ?: continue
                            val urlValue = file.opt("url")
                            val candidate = when (urlValue) {
                                is String -> urlValue
                                is JSONObject -> urlValue.optString("url")
                                else -> ""
                            }
                            if (candidate.startsWith("https://addons.mozilla.org/") && candidate.endsWith(".xpi")) {
                                xpi = candidate
                                break
                            }
                        }
                        if (xpi.isBlank()) continue
                        val nameValue = addon.opt("name")
                        val summaryValue = addon.opt("summary")
                        val name = when (nameValue) {
                            is String -> nameValue
                            is JSONObject -> nameValue.optString("en-US").ifBlank { addon.optString("slug", "Extension") }
                            else -> addon.optString("slug", "Extension")
                        }
                        val summary = when (summaryValue) {
                            is String -> summaryValue
                            is JSONObject -> summaryValue.optString("en-US")
                            else -> ""
                        }
                        found += StoreExtension(name, summary, xpi)
                    }
                }
                runOnUiThread {
                    if (found.isEmpty()) {
                        AlertDialog.Builder(this).setTitle("Tidak ada hasil")
                            .setMessage("Katalog kosong atau koneksi gagal. Coba kata kunci lain.")
                            .setPositiveButton("Cari lagi") { _, _ -> showExtensionStore() }
                            .setNegativeButton("Tutup", null).show()
                    } else showExtensionStoreResults(found)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    AlertDialog.Builder(this).setTitle("Toko tidak tersedia")
                        .setMessage("Tidak bisa memuat katalog Mozilla: " + (e.message ?: "kesalahan jaringan"))
                        .setPositiveButton("Coba lagi") { _, _ -> showExtensionStore() }
                        .setNegativeButton("Tutup", null).show()
                }
            } finally {
                connection?.disconnect()
            }
        }
    }

    private fun showExtensionStoreResults(found: List<StoreExtension>) {
        val labels = found.map { item ->
            item.name + if (item.summary.isBlank()) "" else "\n" + item.summary.take(100)
        }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Pilih extension")
            .setItems(labels) { _, which ->
                val selected = found[which]
                AlertDialog.Builder(this).setTitle("Pasang " + selected.name)
                    .setMessage("Diunduh dari Mozilla Add-ons. Extension dapat meminta akses situs. Tidak semua extension bekerja penuh di GeckoView.")
                    .setPositiveButton("Unduh & pasang") { _, _ -> installFromStore(selected) }
                    .setNegativeButton("Batal", null).show()
            }.setNegativeButton("Tutup", null).show()
    }

    private fun installFromStore(extension: StoreExtension) {
        Toast.makeText(this, "Mengunduh " + extension.name + "…", Toast.LENGTH_LONG).show()
        allowLocalExtensionInstallPrompt = true
        runtime.webExtensionController.install(extension.xpiUrl, WebExtensionController.INSTALLATION_METHOD_MANAGER)
            .accept(
                { installed ->
                    allowLocalExtensionInstallPrompt = false
                    if (installed != null) {
                        Toast.makeText(this, "Extension terpasang: " + (installed.metaData?.name ?: extension.name), Toast.LENGTH_LONG).show()
                        refreshInstalledExtension()
                        session.reload()
                    } else Toast.makeText(this, "Pemasangan tidak mengembalikan hasil.", Toast.LENGTH_LONG).show()
                },
                { error ->
                    allowLocalExtensionInstallPrompt = false
                    Toast.makeText(this, "Gagal memasang; extension mungkin tidak kompatibel: " + (error?.message ?: ""), Toast.LENGTH_LONG).show()
                }
            )
    }

    private fun showCacheManager() {
        val dirs = listOfNotNull(cacheDir, externalCacheDir)
        val bytes = dirs.sumOf { dir -> dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } }
        val mb = bytes / (1024 * 1024)
        AlertDialog.Builder(this).setTitle("Manajemen cache")
            .setMessage("Cache sementara aplikasi: sekitar " + mb + " MB.\nMenghapus cache dapat membuat halaman memuat ulang berkas. Cookie, login, riwayat, dan data situs tidak dihapus.")
            .setPositiveButton("Hapus cache") { _, _ ->
                var deleted = 0
                dirs.forEach { dir ->
                    dir.listFiles()?.forEach { child ->
                        if (child.isDirectory) {
                            if (child.deleteRecursively()) deleted++
                        } else if (child.delete()) deleted++
                    }
                }
                Toast.makeText(this, "Cache sementara dibersihkan (" + deleted + " item).", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Tutup", null).show()
    }

    private fun openCurrentPageExternally() {
        val raw = addressField.text.toString().trim()
        if (raw.isBlank()) return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(raw)))
        } catch (_: Exception) {
            Toast.makeText(this, "Browser eksternal tidak ditemukan.", Toast.LENGTH_LONG).show()
        }
    }

    private fun toggleBackgroundAudio() {
        backgroundAudioEnabled = !backgroundAudioEnabled
        browserPrefs.edit().putBoolean("background_audio", backgroundAudioEnabled).apply()
        session.settings.setSuspendMediaWhenInactive(!backgroundAudioEnabled)
        if (backgroundAudioEnabled) {
            session.setActive(true)
            session.setPriorityHint(GeckoSession.PRIORITY_HIGH)
            try {
                val serviceIntent = Intent(this, SakuTubePlaybackService::class.java).setAction(SakuTubePlaybackService.ACTION_START)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(serviceIntent) else startService(serviceIntent)
            } catch (error: Exception) {
                Toast.makeText(this, "Layanan audio gagal dimulai: " + (error.message ?: ""), Toast.LENGTH_LONG).show()
            }
        } else {
            session.setPriorityHint(GeckoSession.PRIORITY_DEFAULT)
            stopService(Intent(this, SakuTubePlaybackService::class.java))
        }
        Toast.makeText(
            this,
            if (backgroundAudioEnabled) "Audio latar diaktifkan. Putar audio dahulu; notifikasi SakuTube akan muncul." else "Audio latar dimatikan.",
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

    /**
     * Install the bundled Auto Dub WebExtension from APK assets. This keeps the
     * feature inside SakuTube; users do not need to import an XPI manually.
     */
    private fun installBundledAutoDub() {
        runtime.webExtensionController.installBuiltIn(
            "resource://android/assets/extensions/saku-auto-dub/"
        ).accept(
            { extension ->
                autoDubExtension = extension
                extension?.setMessageDelegate(object : WebExtension.MessageDelegate {
                    override fun onMessage(nativeApp: String, message: Any, sender: WebExtension.MessageSender): GeckoResult<Any>? {
                        val data = message as? JSONObject ?: return GeckoResult.fromValue(JSONObject().put("ok", false))
                        val type = data.optString("type")
                        if (type == "SPEAK_TEXT") {
                            val text = data.optString("text").trim().take(700)
                            if (text.isEmpty()) return GeckoResult.fromValue(JSONObject().put("ok", false).put("error", "Teks kosong"))
                            val speakResult = GeckoResult<Any>()
                            runOnUiThread {
                                if (autoDubTtsReady && autoDubTts != null) {
                                    val utteranceId = "saku-auto-dub-${System.nanoTime()}"
                                    autoDubSpeakResults[utteranceId] = speakResult
                                    val result = try {
                                        autoDubTts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId) ?: TextToSpeech.ERROR
                                    } catch (_: Exception) {
                                        TextToSpeech.ERROR
                                    }
                                    if (result == TextToSpeech.ERROR) {
                                        autoDubSpeakResults.remove(utteranceId)
                                        speakResult.complete(JSONObject().put("ok", false).put("error", "Mesin TTS gagal membacakan teks"))
                                    }
                                } else {
                                    // Do not play a stale subtitle later after the browser has moved on.
                                    speakResult.complete(JSONObject().put("ok", false).put("error", "TTS Indonesia belum siap"))
                                    Toast.makeText(
                                        this@YouTubeActivity,
                                        "TTS belum siap. Periksa mesin TTS dan data suara Bahasa Indonesia di Setelan.",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                            // Keep the native-message request open until Android TTS actually finishes.
                            return speakResult
                        }
                        if (type == "STOP_SPEECH") { runOnUiThread { autoDubTts?.stop() }; return GeckoResult.fromValue(JSONObject().put("ok", true)) }
                        return GeckoResult.fromValue(JSONObject().put("ok", false))
                    }
                }, "saku.autodub")
                runOnUiThread {
                    if (extension != null) {
                        Toast.makeText(this, "SakuTube Auto Dub siap · aktif saat subtitle YouTube tersedia", Toast.LENGTH_LONG).show()
                    }
                }
            },
            { error ->
                runOnUiThread {
                    Toast.makeText(
                        this,
                        "Auto Dub belum dapat dimuat: " + (error?.message ?: "GeckoView gagal memuat extension"),
                        Toast.LENGTH_LONG
                    ).show()
                }
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

    private fun togglePip() {
        pipEnabled = !pipEnabled
        browserPrefs.edit().putBoolean("pip_enabled", pipEnabled).apply()
        Toast.makeText(
            this,
            if (pipEnabled) "Picture-in-Picture diaktifkan. Tombol Mini membuka popup video." else "Picture-in-Picture dimatikan.",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun enterMiniPlayer() {
        if (!pipEnabled) {
            Toast.makeText(this, "Picture-in-Picture sedang OFF. Aktifkan dari menu ⋮.", Toast.LENGTH_LONG).show()
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Toast.makeText(this, "Jendela mini membutuhkan Android 8 atau lebih baru.", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            // PiP bounds must describe the content area, not the full browser chrome.
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                enterPictureInPictureMode(params)
            }
        } catch (error: Exception) {
            Toast.makeText(
                this,
                "Picture-in-Picture gagal dibuka: " + (error.message ?: "tidak didukung"),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: android.content.res.Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (::toolbar.isInitialized) toolbar.visibility = if (isInPictureInPictureMode) View.GONE else View.VISIBLE
        if (::addressRowView.isInitialized) addressRowView.visibility = if (isInPictureInPictureMode) View.GONE else View.VISIBLE
        if (::zoomRowView.isInitialized) zoomRowView.visibility = if (isInPictureInPictureMode) View.GONE else View.VISIBLE
        // Keep only the webpage/video surface visible in the PiP window.
        if (::geckoView.isInitialized) {
            geckoView.visibility = View.VISIBLE
            val contentParams = geckoView.layoutParams
            if (contentParams is LinearLayout.LayoutParams) {
                contentParams.width = LinearLayout.LayoutParams.MATCH_PARENT
                contentParams.height = 0
                contentParams.weight = 1f
                geckoView.layoutParams = contentParams
            }
            root.requestLayout()
            geckoView.requestLayout()
        }
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
            session.setPriorityHint(if (backgroundAudioEnabled) GeckoSession.PRIORITY_HIGH else GeckoSession.PRIORITY_DEFAULT)
        }
    }

    override fun onDestroy() {
        if (::geckoView.isInitialized) geckoView.releaseSession()
        try { autoDubTts?.stop(); autoDubTts?.shutdown() } catch (_: Exception) { }
        autoDubTts = null
        browserTabs.forEach { try { it.session.close() } catch (_: Exception) { } }
        browserTabs.clear()
        popupDialogs.toList().forEach { try { it.dismiss() } catch (_: Exception) { } }
        popupSessions.toList().forEach { try { it.close() } catch (_: Exception) { } }
        popupDialogs.clear()
        popupSessions.clear()
        storeExecutor.shutdownNow()
        super.onDestroy()
    }
}

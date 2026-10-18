package com.sakuhome.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController
import java.net.URLEncoder

/**
 * Standalone YouTube web app. Pages stay inside GeckoView; this Activity never
 * hands YouTube links to Android's default browser.
 */
private object SakuYouTubeRuntime {
    @Volatile private var instance: GeckoRuntime? = null

    fun get(context: Context): GeckoRuntime {
        instance?.let { return it }
        return synchronized(this) {
            instance ?: GeckoRuntime.create(
                context.applicationContext,
                GeckoRuntimeSettings.Builder()
                    .javaScriptEnabled(true)
                    .webManifest(true)
                    .extensionsProcessEnabled(true)
                    .build()
            ).also { instance = it }
        }
    }
}

class YoutubeActivity : Activity() {
    companion object {
        private const val HOME_URL = "https://www.youtube.com/"
        private const val UBO_ID = "uBlock0@raymondhill.net"
        private const val UBO_XPI_URL =
            "https://addons.mozilla.org/firefox/downloads/latest/ublock-origin/latest.xpi"
        private const val PREFS = "saku_youtube"
        private const val KEY_UBO_CONSENT = "ublock_origin_consent"
        private const val KEY_DESKTOP_MODE = "desktop_site"
    }

    private lateinit var runtime: GeckoRuntime
    private lateinit var session: GeckoSession
    private lateinit var geckoView: GeckoView
    private lateinit var addressField: EditText
    private lateinit var statusText: TextView
    private lateinit var pageTitle: TextView
    private lateinit var progress: ProgressBar
    private lateinit var toolbar: LinearLayout
    private lateinit var statusRow: LinearLayout
    private var desktopMode = false
    private var fullScreenPage = false

    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }
    private val bg = Color.rgb(10, 10, 10)
    private val surface = Color.rgb(27, 29, 34)
    private val fg = Color.rgb(245, 247, 250)
    private val muted = Color.rgb(166, 174, 187)
    private val accent = Color.rgb(111, 159, 255)

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        desktopMode = prefs.getBoolean(KEY_DESKTOP_MODE, false)
        buildUi()

        try {
            runtime = SakuYouTubeRuntime.get(this)
            session = GeckoSession()
            session.settings.setInt(
                GeckoSessionSettings.USER_AGENT_MODE,
                if (desktopMode) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
                else GeckoSessionSettings.USER_AGENT_MODE_MOBILE
            )
            session.open(runtime)
            geckoView.setSession(session)
            configureSession()
            prepareUBlockOrigin()
        } catch (error: Exception) {
            setStatus("Gagal memulai mesin web: ${error.message ?: "error"}")
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }
        toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(surface)
            setPadding(dp(8), dp(6), dp(8), dp(4))
        }

        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(makeButton("‹") { onBackPressed() }, LinearLayout.LayoutParams(dp(44), dp(42)))
        pageTitle = TextView(this).apply {
            text = "Saku YouTube"
            textSize = 16f
            setTextColor(fg)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        titleRow.addView(pageTitle, LinearLayout.LayoutParams(0, dp(42), 1f))
        titleRow.addView(makeButton("⌂") { session.loadUri(HOME_URL) }, LinearLayout.LayoutParams(dp(42), dp(42)))
        titleRow.addView(makeButton("↻") { session.reload() }, LinearLayout.LayoutParams(dp(42), dp(42)))
        toolbar.addView(titleRow)

        val urlRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(2))
        }
        addressField = EditText(this).apply {
            setSingleLine(true)
            textSize = 13f
            setTextColor(fg)
            setHintTextColor(muted)
            hint = "Cari YouTube atau masukkan URL"
            setPadding(dp(12), 0, dp(8), 0)
            background = rounded(surface, 12)
            imeOptions = EditorInfo.IME_ACTION_GO
            setText(HOME_URL)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_SEARCH) {
                    navigateFromAddress()
                    true
                } else false
            }
        }
        urlRow.addView(addressField, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(6) })
        urlRow.addView(makeButton("Buka") { navigateFromAddress() }, LinearLayout.LayoutParams(dp(62), dp(42)))
        toolbar.addView(urlRow)

        statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statusText = TextView(this).apply {
            text = "Menyiapkan uBlock Origin…"
            textSize = 11f
            setTextColor(muted)
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 2
            setPadding(dp(3), dp(4), dp(3), dp(4))
            isClickable = true
            setOnClickListener { prepareUBlockOrigin(forcePrompt = true) }
        }
        statusRow.addView(statusText, LinearLayout.LayoutParams(0, dp(34), 1f))
        statusRow.addView(makeButton(if (desktopMode) "Desktop ✓" else "Desktop") {
            desktopMode = !desktopMode
            prefs.edit().putBoolean(KEY_DESKTOP_MODE, desktopMode).apply()
            session.settings.setInt(
                GeckoSessionSettings.USER_AGENT_MODE,
                if (desktopMode) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
                else GeckoSessionSettings.USER_AGENT_MODE_MOBILE
            )
            session.reload()
            updateDesktopButton()
        }, LinearLayout.LayoutParams(dp(105), dp(34)))
        toolbar.addView(statusRow)

        root.addView(toolbar, LinearLayout.LayoutParams(-1, -2))
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            progressTintList = android.content.res.ColorStateList.valueOf(accent)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(surface)
        }
        root.addView(progress, LinearLayout.LayoutParams(-1, dp(2)))

        geckoView = GeckoView(this).apply {
            setBackgroundColor(bg)
        }
        root.addView(geckoView, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun rounded(color: Int, radiusDp: Int) =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
        }

    private fun makeButton(text: String, action: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 13f
        setTextColor(fg)
        background = rounded(Color.rgb(45, 49, 58), 10)
        setPadding(dp(4), 0, dp(4), 0)
        setOnClickListener { action() }
    }

    private fun updateDesktopButton() {
        val button = (statusRow.getChildAt(1) as? Button) ?: return
        button.text = if (desktopMode) "Desktop ✓" else "Desktop"
    }

    private fun setStatus(message: String) {
        if (::statusText.isInitialized) statusText.text = message
    }

    private fun configureSession() {
        session.setProgressDelegate(object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                progress.visibility = View.VISIBLE
                progress.progress = 5
                if (url.startsWith("https://") || url.startsWith("http://")) {
                    addressField.setText(url)
                }
                setStatus(if (isUBlockInstalled) "uBlock Origin aktif · Memuat…" else "uBlock belum aktif · Memuat…")
            }

            override fun onProgressChange(session: GeckoSession, progressValue: Int) {
                progress.progress = progressValue.coerceIn(0, 100)
            }

            override fun onPageStop(session: GeckoSession, success: Boolean) {
                progress.visibility = View.GONE
                progress.progress = if (success) 100 else 0
                if (success) {
                    setStatus(if (isUBlockInstalled) "uBlock Origin aktif" else "uBlock belum aktif · ketuk untuk memasang")
                } else {
                    setStatus("Halaman gagal dimuat · periksa internet")
                }
            }
        })

        session.setContentDelegate(object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) {
                pageTitle.text = title?.takeIf { it.isNotBlank() }?.take(70) ?: "Saku YouTube"
            }

            override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
                fullScreenPage = fullScreen
                toolbar.visibility = if (fullScreen) View.GONE else View.VISIBLE
                progress.visibility = if (fullScreen) View.GONE else progress.visibility
                window.decorView.systemUiVisibility = if (fullScreen) {
                    (View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
                } else {
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                }
            }
        })

        session.setNavigationDelegate(object : GeckoSession.NavigationDelegate {
            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                // Back is routed through Gecko; it never starts an external browser.
            }

            override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
                // Forward navigation is available through the page's own navigation history.
            }

            override fun onLoadRequest(
                session: GeckoSession,
                request: GeckoSession.NavigationDelegate.LoadRequest
            ): GeckoResult<AllowOrDeny> {
                val scheme = Uri.parse(request.uri).scheme?.lowercase()
                val allow = scheme == "https" || scheme == "http" || scheme == "about"
                return GeckoResult.fromValue(if (allow) AllowOrDeny.ALLOW else AllowOrDeny.DENY)
            }
        })
    }

    private val isUBlockInstalled: Boolean
        get() = prefs.getBoolean("ublock_origin_installed", false)

    private fun prepareUBlockOrigin(forcePrompt: Boolean = false) {
        if (!::runtime.isInitialized) return
        val controller = runtime.webExtensionController
        controller.setPromptDelegate(object : WebExtensionController.PromptDelegate {
            override fun onInstallPromptRequest(
                extension: WebExtension,
                permissions: Array<String>,
                origins: Array<String>,
                dataCollectionPermissions: Array<String>
            ): GeckoResult<WebExtension.PermissionPromptResponse> {
                val accepted = prefs.getBoolean(KEY_UBO_CONSENT, false) &&
                    (extension.id.equals(UBO_ID, ignoreCase = true) ||
                        extension.id.contains("ublock", ignoreCase = true))
                return GeckoResult.fromValue(
                    WebExtension.PermissionPromptResponse(accepted, false, false)
                )
            }
        })

        controller.list().accept({ installed ->
            val ubo = installed.firstOrNull {
                it.id.equals(UBO_ID, ignoreCase = true) ||
                    it.id.contains("ublock", ignoreCase = true)
            }
            if (ubo != null) {
                prefs.edit().putBoolean("ublock_origin_installed", true).apply()
                setStatus("uBlock Origin aktif")
                if (forcePrompt) {
                    Toast.makeText(this, "uBlock Origin sudah terpasang", Toast.LENGTH_SHORT).show()
                }
                loadHomeIfNeeded()
            } else {
                prefs.edit().putBoolean("ublock_origin_installed", false).apply()
                if (prefs.getBoolean(KEY_UBO_CONSENT, false)) installUBlockOrigin(controller)
                else showUBlockConsent()
            }
        }, {
            if (prefs.getBoolean(KEY_UBO_CONSENT, false)) installUBlockOrigin(controller)
            else showUBlockConsent()
        })
    }

    private fun showUBlockConsent() {
        AlertDialog.Builder(this)
            .setTitle("Aktifkan uBlock Origin")
            .setMessage(
                "Saku YouTube memakai uBlock Origin resmi dari Mozilla untuk memblokir iklan, pelacak, dan pop-up. " +
                    "Ekstensi perlu izin membaca dan menyaring permintaan situs yang dibuka di layar ini. " +
                    "Pemasangan membutuhkan internet dan dilakukan di dalam Saku, bukan melalui browser lain."
            )
            .setPositiveButton("Pasang uBlock") { _, _ ->
                prefs.edit().putBoolean(KEY_UBO_CONSENT, true).apply()
                installUBlockOrigin(runtime.webExtensionController)
            }
            .setNegativeButton("Lanjut tanpa uBlock") { _, _ ->
                prefs.edit().putBoolean("ublock_origin_installed", false).apply()
                setStatus("uBlock tidak aktif · iklan mungkin muncul")
                loadHomeIfNeeded()
            }
            .setOnCancelListener {
                setStatus("uBlock belum dipasang · ketuk status untuk memasang")
            }
            .show()
    }

    private fun installUBlockOrigin(controller: WebExtensionController) {
        setStatus("Mengunduh dan memasang uBlock Origin dari Mozilla…")
        controller.install(UBO_XPI_URL, WebExtensionController.INSTALLATION_METHOD_MANAGER)
            .accept({ extension ->
                if (extension.id.equals(UBO_ID, ignoreCase = true) ||
                    extension.id.contains("ublock", ignoreCase = true)
                ) {
                    prefs.edit().putBoolean("ublock_origin_installed", true).apply()
                    setStatus("uBlock Origin aktif")
                    Toast.makeText(this, "uBlock Origin berhasil dipasang", Toast.LENGTH_LONG).show()
                    loadHomeIfNeeded()
                } else {
                    prefs.edit().putBoolean("ublock_origin_installed", false).apply()
                    showInstallFailure("Mozilla mengembalikan ekstensi yang tidak cocok.")
                }
            }, { error ->
                prefs.edit().putBoolean("ublock_origin_installed", false).apply()
                showInstallFailure(error.message ?: "Pemasangan ekstensi gagal.")
            })
    }

    private fun showInstallFailure(reason: String) {
        setStatus("uBlock gagal dipasang · ketuk untuk mencoba lagi")
        AlertDialog.Builder(this)
            .setTitle("uBlock Origin belum aktif")
            .setMessage(
                "Saku belum bisa memastikan pemblokiran iklan karena ekstensi gagal dipasang. " +
                    "Pastikan internet aktif lalu coba ulang.

Detail: ${reason.take(260)}"
            )
            .setPositiveButton("Coba lagi") { _, _ ->
                prefs.edit().putBoolean(KEY_UBO_CONSENT, true).apply()
                installUBlockOrigin(runtime.webExtensionController)
            }
            .setNegativeButton("Lanjut tanpa uBlock") { _, _ -> loadHomeIfNeeded() }
            .show()
    }

    private fun loadHomeIfNeeded() {
        if (!::session.isInitialized) return
        addressField.setText(HOME_URL)
        session.loadUri(HOME_URL)
    }

    private fun navigateFromAddress() {
        val entered = addressField.text.toString().trim()
        if (entered.isBlank()) return
        val target = when {
            entered.startsWith("https://", true) || entered.startsWith("http://", true) -> entered
            entered.contains(' ') || !entered.contains('.') ->
                "https://www.youtube.com/results?search_query=" + URLEncoder.encode(entered, "UTF-8")
            else -> "https://${entered}"
        }
        val scheme = Uri.parse(target).scheme?.lowercase()
        if (scheme != "https" && scheme != "http") {
            Toast.makeText(this, "Hanya tautan web HTTP/HTTPS yang didukung", Toast.LENGTH_SHORT).show()
            return
        }
        session.loadUri(target)
        addressField.clearFocus()
    }

    override fun onBackPressed() {
        if (fullScreenPage && ::session.isInitialized) {
            session.exitFullScreen()
            return
        }
        if (::session.isInitialized) {
            session.processBackPressed().accept({ handled ->
                if (!handled) finish()
            }, {
                finish()
            })
        } else {
            finish()
        }
    }

    override fun onPause() {
        if (::session.isInitialized) session.setActive(false)
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (::session.isInitialized) session.setActive(true)
    }

    override fun onDestroy() {
        if (::session.isInitialized) {
            try { session.close() } catch (_: Exception) {}
        }
        super.onDestroy()
    }
}

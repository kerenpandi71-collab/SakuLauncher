package com.sakuhome.launcher

import android.app.Activity
import android.app.AlertDialog
import android.app.PictureInPictureParams
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.Gravity
import android.widget.FrameLayout
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.ByteArrayInputStream
import java.util.Locale

/**
 * Lightweight in-app YouTube client. Request filtering blocks known ad/tracker
 * endpoints, but cannot guarantee removal of every YouTube ad.
 */
class YouTubeActivity : Activity() {
    private lateinit var webView: WebView
    private lateinit var adButton: TextView
    private lateinit var audioButton: TextView
    private lateinit var settingsButton: TextView
    private lateinit var toolbar: LinearLayout
    private lateinit var contentRoot: LinearLayout
    private lateinit var playerFrame: FrameLayout
    private lateinit var miniOverlayButton: TextView
    private var videoPlaying = false
    private var adBlockingEnabled = true
    private var backgroundAudioEnabled = false
    private var desktopSiteEnabled = false
    private var zoomLocked = false
    private var lockedZoomScale = 1.0f
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    private val blockedHosts = listOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com",
        "adservice.google.com", "pagead2.googlesyndication.com",
        "googleads.g.doubleclick.net", "ad.doubleclick.net",
        "securepubads.g.doubleclick.net", "ads.youtube.com",
        "youtube.com/pagead", "youtube.com/ptracking", "youtube.com/api/stats/ads",
        "youtube.com/get_video_info?adformat"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        contentRoot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8, 4, 8, 4)
            setBackgroundColor(Color.rgb(18, 18, 18))
        }
        val title = TextView(this).apply {
            text = "SakuTube"
            textSize = 17f
            setTextColor(Color.WHITE)
            setPadding(8, 8, 4, 8)
        }
        toolbar.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        adButton = makeButton("Iklan: ON") {
            adBlockingEnabled = !adBlockingEnabled
            adButton.text = if (adBlockingEnabled) "Iklan: ON" else "Iklan: OFF"
            Toast.makeText(this, if (adBlockingEnabled) "Pemblokir permintaan iklan aktif" else "Pemblokir iklan nonaktif", Toast.LENGTH_SHORT).show()
            webView.reload()
        }
        toolbar.addView(adButton)
        audioButton = makeButton("Audio BG: OFF") { showPlayerSettings() }
        settingsButton = makeButton("⋮") { showPlayerSettings() }
        toolbar.addView(settingsButton)
        toolbar.addView(makeButton("Mini") { enterMiniPlayer() })
        toolbar.addView(makeButton("⟳") { webView.reload() })
        toolbar.addView(makeButton("✕") { finish() })
        contentRoot.addView(toolbar, LinearLayout.LayoutParams(-1, -2))

        webView = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.loadsImagesAutomatically = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    super.onProgressChanged(view, newProgress)
                }
            }
            webViewClient = object : WebViewClient() {
                override fun onScaleChanged(view: WebView?, oldScale: Float, newScale: Float) {
                    super.onScaleChanged(view, oldScale, newScale)
                    if (!zoomLocked && newScale > 0f) {
                        lockedZoomScale = newScale
                    } else if (zoomLocked && newScale > 0f && kotlin.math.abs(newScale - lockedZoomScale) > 0.03f) {
                        view?.post { view.zoomBy(lockedZoomScale / newScale) }
                    }
                }

                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val uri = request?.url ?: return false
                    val host = uri.host?.lowercase(Locale.ROOT).orEmpty()
                    return !(host == "youtube.com" || host.endsWith(".youtube.com") ||
                        host == "youtu.be" || host == "googlevideo.com" ||
                        host.endsWith(".googlevideo.com") || host == "youtube-nocookie.com" ||
                        host.endsWith(".youtube-nocookie.com") || host == "ytimg.com" ||
                        host.endsWith(".ytimg.com") || host == "google.com" || host.endsWith(".google.com") ||
                        host == "gstatic.com" || host.endsWith(".gstatic.com"))
                }

                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    if (!adBlockingEnabled) return super.shouldInterceptRequest(view, request)
                    val uri = request?.url
                    val url = uri?.toString()?.lowercase(Locale.ROOT).orEmpty()
                    val host = uri?.host?.lowercase(Locale.ROOT).orEmpty()
                    val blockedHost = blockedHosts.any { token ->
                        !token.contains('/') && !token.contains('?') &&
                            (host == token || host.endsWith(".$token"))
                    }
                    if (blockedHost) {
                        return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                    }
                    return super.shouldInterceptRequest(view, request)
                }
            }
            loadUrl("https://m.youtube.com/")
        }
        // Keep YouTube's own controls, and add a native shortcut to Android PiP.
        playerFrame = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        playerFrame.addView(
            webView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        miniOverlayButton = TextView(this).apply {
            text = "▣ Mini"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(16, 10, 16, 10)
            background = GradientDrawable().apply {
                setColor(Color.rgb(35, 35, 35))
                cornerRadius = 48f
                setStroke(1, Color.rgb(100, 100, 100))
            }
            elevation = 12f
            visibility = View.GONE
            contentDescription = "Minimalkan video ke jendela mini"
            setOnClickListener { enterMiniPlayer() }
        }
        val miniParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.END
        ).apply { setMargins(0, 0, 18, 18) }
        playerFrame.addView(miniOverlayButton, miniParams)
        contentRoot.addView(playerFrame, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(contentRoot)

        val sampler = android.os.Handler(mainLooper)
        val sample = object : Runnable {
            override fun run() {
                if (!isFinishing && ::webView.isInitialized) {
                    webView.evaluateJavascript("(function(){var v=document.querySelector('video');return !!(v&&!v.paused&&!v.ended);})()") { value ->
                        videoPlaying = value == "true"
                        miniOverlayButton.visibility = if (videoPlaying && !isInPictureInPictureMode) View.VISIBLE else View.GONE
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            try {
                                setPictureInPictureParams(
                                    PictureInPictureParams.Builder()
                                        .setAspectRatio(Rational(16, 9))
                                        .setAutoEnterEnabled(videoPlaying)
                                        .build()
                                )
                            } catch (_: Exception) {
                                // Some device builds reject PiP parameter updates.
                            }
                        }
                    }
                    sampler.postDelayed(this, 1000)
                }
            }
        }
        sampler.post(sample)
    }

    private fun showPlayerSettings() {
        val labels = arrayOf(
            "Filter permintaan iklan (tidak menjamin semua iklan YouTube hilang)",
            "Audio latar (dukungan dibatasi YouTube/WebView)",
            "Situs desktop",
            "Kunci zoom saat ini"
        )
        val enabled = booleanArrayOf(adBlockingEnabled, backgroundAudioEnabled, desktopSiteEnabled, zoomLocked)
        AlertDialog.Builder(this)
            .setTitle("Pengaturan SakuTube")
            .setMultiChoiceItems(labels, enabled) { _, which, checked ->
                when (which) {
                    0 -> {
                        adBlockingEnabled = checked
                        Toast.makeText(this, if (checked) "Filter host iklan aktif; iklan dalam video mungkin tetap muncul." else "Filter permintaan iklan dinonaktifkan.", Toast.LENGTH_LONG).show()
                    }
                    1 -> {
                        backgroundAudioEnabled = checked
                        if (checked) {
                            requestAudioFocus()
                            enableBackgroundPlayback()
                        } else {
                            abandonAudioFocus()
                        }
                        Toast.makeText(this, if (checked) "Mode audio latar dicoba. Gunakan Mini agar video tetap terlihat; audio latar WebView tidak dapat dijamin." else "Mode audio latar dinonaktifkan.", Toast.LENGTH_LONG).show()
                    }
                    2 -> {
                        desktopSiteEnabled = checked
                        applyDesktopSiteMode()
                    }
                    3 -> {
                        zoomLocked = checked
                        if (checked) lockedZoomScale = webView.scale
                        Toast.makeText(this, if (checked) "Zoom dikunci pada " + (lockedZoomScale * 100).toInt() + "%." else "Kunci zoom dilepas.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNeutralButton("Zoom −") { _, _ -> changeZoom(false) }
            .setPositiveButton("Zoom +") { _, _ -> changeZoom(true) }
            .setNegativeButton("Tutup", null)
            .show()
    }

    private fun changeZoom(zoomIn: Boolean) {
        if (!::webView.isInitialized) return
        val factor = if (zoomIn) 1.15f else (1f / 1.15f)
        webView.zoomBy(factor)
        if (zoomLocked) lockedZoomScale = webView.scale
    }

    private fun applyDesktopSiteMode() {
        if (!::webView.isInitialized) return
        webView.settings.useWideViewPort = desktopSiteEnabled
        webView.settings.loadWithOverviewMode = !desktopSiteEnabled
        webView.settings.userAgentString = if (desktopSiteEnabled) {
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        } else {
            WebSettings.getDefaultUserAgent(this)
        }
        webView.reload()
        Toast.makeText(this, if (desktopSiteEnabled) "Situs desktop aktif." else "Situs mobile aktif.", Toast.LENGTH_SHORT).show()
    }

    private fun makeButton(label: String, action: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(7, 10, 7, 10)
            setOnClickListener { action() }
        }

    private fun enterMiniPlayer() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Toast.makeText(this, "Jendela mini membutuhkan Android 8 atau lebih baru", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val builder = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                builder.setAutoEnterEnabled(videoPlaying)
            }
            val params = builder.build()
            enterPictureInPictureMode(params)
        } catch (_: Exception) {
            Toast.makeText(this, "Mode jendela mini tidak tersedia di perangkat ini", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Applies inline playback hints and attempts to resume the current video from
     * the explicit user action. YouTube/WebView may still enforce its own
     * background-playback restrictions; this is a best-effort integration.
     */
    private fun enableBackgroundPlayback() {
        if (!::webView.isInitialized) return
        webView.evaluateJavascript(
            "(function(){var v=document.querySelector('video');if(!v)return false;" +
                "v.setAttribute('playsinline','');v.setAttribute('webkit-playsinline','');" +
                "v.muted=false;var p=v.play();if(p&&p.catch)p.catch(function(){});return true;})()",
            null
        )
    }

    private fun requestAudioFocus() {
        val manager = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setOnAudioFocusChangeListener { }
                .build()
            audioFocusRequest = request
            manager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            manager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
    }

    private fun abandonAudioFocus() {
        val manager = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { manager.abandonAudioFocusRequest(it) }
            audioFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            manager.abandonAudioFocus(null)
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (::toolbar.isInitialized) {
            toolbar.visibility = if (isInPictureInPictureMode) View.GONE else View.VISIBLE
        }
        if (::miniOverlayButton.isInitialized) {
            miniOverlayButton.visibility =
                if (isInPictureInPictureMode || !videoPlaying) View.GONE else View.VISIBLE
        }
    }

    override fun onPause() {
        // Keep WebView alive in background-audio mode; playback still depends on WebView/YouTube policy.
        if (!backgroundAudioEnabled && !isInPictureInPictureMode && ::webView.isInitialized) webView.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized) webView.onResume()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (videoPlaying && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            enterMiniPlayer()
        }
    }

    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        abandonAudioFocus()
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.destroy()
        }
        super.onDestroy()
    }
}

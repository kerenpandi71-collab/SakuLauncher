package com.sakuhome.launcher

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.Gravity
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
    private var videoPlaying = false
    private var adBlockingEnabled = true
    private var backgroundAudioEnabled = false
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
    private val blockedPathTokens = listOf(
        "/pagead/", "/ptracking", "/api/stats/ads", "/pagead/lvz",
        "/youtubei/v1/log_event"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        val bar = LinearLayout(this).apply {
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
        bar.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        adButton = makeButton("Iklan: ON") {
            adBlockingEnabled = !adBlockingEnabled
            adButton.text = if (adBlockingEnabled) "Iklan: ON" else "Iklan: OFF"
            Toast.makeText(this, if (adBlockingEnabled) "Pemblokir permintaan iklan aktif" else "Pemblokir iklan nonaktif", Toast.LENGTH_SHORT).show()
            webView.reload()
        }
        bar.addView(adButton)
        audioButton = makeButton("Audio BG: OFF") {
            backgroundAudioEnabled = !backgroundAudioEnabled
            audioButton.text = if (backgroundAudioEnabled) "Audio BG: ON" else "Audio BG: OFF"
            if (backgroundAudioEnabled) requestAudioFocus() else abandonAudioFocus()
            Toast.makeText(this, if (backgroundAudioEnabled) "Mode audio latar diaktifkan; dukungan tetap bergantung pada YouTube/WebView" else "Mode audio latar dinonaktifkan", Toast.LENGTH_LONG).show()
        }
        bar.addView(audioButton)
        bar.addView(makeButton("Mini") { enterMiniPlayer() })
        bar.addView(makeButton("⟳") { webView.reload() })
        bar.addView(makeButton("✕") { finish() })
        root.addView(bar, LinearLayout.LayoutParams(-1, -2))

        webView = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
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
                        if (token.contains('/') || token.contains('?')) url.contains(token)
                        else host == token || host.endsWith(".$token")
                    }
                    if (blockedHost || blockedPathTokens.any { url.contains(it) }) {
                        return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                    }
                    return super.shouldInterceptRequest(view, request)
                }
            }
            loadUrl("https://m.youtube.com/")
        }
        root.addView(webView, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        val sampler = android.os.Handler(mainLooper)
        val sample = object : Runnable {
            override fun run() {
                if (!isFinishing && ::webView.isInitialized) {
                    webView.evaluateJavascript("(function(){var v=document.querySelector('video');return !!(v&&!v.paused&&!v.ended);})()") { value ->
                        videoPlaying = value == "true"
                    }
                    sampler.postDelayed(this, 1000)
                }
            }
        }
        sampler.post(sample)
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
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .build()
            enterPictureInPictureMode(params)
        } catch (_: Exception) {
            Toast.makeText(this, "Mode jendela mini tidak tersedia di perangkat ini", Toast.LENGTH_SHORT).show()
        }
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

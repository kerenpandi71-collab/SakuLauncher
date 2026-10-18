package com.sakuhome.launcher

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import android.app.PictureInPictureParams
import android.util.Rational
import android.os.Build
import android.widget.LinearLayout
import android.widget.TextView
import java.io.ByteArrayInputStream
import java.util.Locale

/**
 * In-app YouTube surface. This does not launch the user's external browser.
 * The lightweight request filter blocks a small set of known ad/tracker hosts;
 * it is not equivalent to uBlock Origin or a full native extractor client.
 */
class YouTubeActivity : Activity() {
    private lateinit var webView: WebView
    private var videoPlaying = false
    private val blockedHosts = listOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com",
        "adservice.google.com", "pagead2.googlesyndication.com",
        "googleads.g.doubleclick.net", "ad.doubleclick.net", "securepubads.g.doubleclick.net",
        "youtube.com/pagead", "youtube.com/ptracking", "youtube.com/api/stats/ads",
        "youtube.com/get_video_info?adformat"
    )
    private val blockedPathTokens = listOf("/pagead/", "/ptracking", "/api/stats/ads")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16, 6, 12, 6)
            setBackgroundColor(Color.rgb(18, 18, 18))
        }
        val title = TextView(this).apply {
            text = "SakuTube"
            textSize = 18f
            setTextColor(Color.WHITE)
            setPadding(4, 8, 4, 8)
        }
        bar.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        val refresh = TextView(this).apply {
            text = "⟳"
            textSize = 26f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(12, 4, 12, 4)
            setOnClickListener { webView.reload() }
        }
        bar.addView(refresh)
        val close = TextView(this).apply {
            text = "✕"
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(12, 4, 4, 4)
            setOnClickListener { finish() }
        }
        bar.addView(close)
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
            webChromeClient = WebChromeClient()
            settings.loadWithOverviewMode = true
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val uri = request?.url ?: return false
                    val host = uri.host?.lowercase(Locale.ROOT).orEmpty()
                    return !(host == "youtube.com" || host.endsWith(".youtube.com") ||
                        host == "youtu.be" || host == "googlevideo.com" ||
                        host.endsWith(".googlevideo.com") || host == "youtube-nocookie.com" ||
                        host.endsWith(".youtube-nocookie.com") || host == "ytimg.com" ||
                        host.endsWith(".ytimg.com") || host == "google.com" || host.endsWith(".google.com"))
                }

                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    val url = request?.url?.toString()?.lowercase(Locale.ROOT).orEmpty()
                    val host = request?.url?.host?.lowercase(Locale.ROOT).orEmpty()
                    val blockedHost = blockedHosts.any { token ->
                        if (token.contains('/') || token.contains('?')) url.contains(token) else host == token || host.endsWith(".$token")
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
                    sampler.postDelayed(this, 1200)
                }
            }
        }
        sampler.post(sample)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (videoPlaying && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build())
            } catch (_: Exception) { }
        }
    }

    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.destroy()
        }
        super.onDestroy()
    }
}

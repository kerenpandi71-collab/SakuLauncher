package com.sakuhome.launcher

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject

class MacDesktopActivity : Activity() {
    private lateinit var web: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(16, 19, 27)
        window.navigationBarColor = Color.rgb(16, 19, 27)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = 0
        web = WebView(this).apply {
            setBackgroundColor(Color.rgb(23, 28, 42))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.allowContentAccess = true
            settings.javaScriptCanOpenWindowsAutomatically = false
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
            addJavascriptInterface(DesktopBridge(), "SakuAndroid")
            loadUrl("file:///android_asset/index.html")
        }
        setContentView(web)
    }

    override fun onBackPressed() {
        if (::web.isInitialized) web.evaluateJavascript(
            "document.getElementById('launchpad').classList.remove('show');document.getElementById('control').classList.remove('show');",
            null
        ) else super.onBackPressed()
    }

    inner class DesktopBridge {
        @JavascriptInterface
        fun getApps(): String {
            val result = JSONArray()
            val intent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
            val list = packageManager.queryIntentActivities(intent, 0)
            list.distinctBy { it.activityInfo.packageName }.sortedBy {
                it.loadLabel(packageManager).toString().lowercase()
            }.forEach { info ->
                val item = JSONObject()
                item.put("label", info.loadLabel(packageManager).toString())
                item.put("packageName", info.activityInfo.packageName)
                result.put(item)
            }
            return result.toString()
        }

        @JavascriptInterface
        fun launchApp(packageName: String) {
            runOnUiThread {
                try {
                    val launch = packageManager.getLaunchIntentForPackage(packageName)
                    if (launch != null) startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Exception) { }
            }
        }

        @JavascriptInterface
        fun openAndroidSettings() {
            runOnUiThread {
                try { startActivity(Intent(Settings.ACTION_SETTINGS)) } catch (_: Exception) { }
            }
        }
    }
}

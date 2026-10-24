package com.sakuhome.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Independent AOSP Material 3-inspired home dimension.
 * Built with native views so it scales to the Redmi 12C portrait display.
 */
class NewDimensionActivity : Activity() {
    private val ink = Color.rgb(25, 39, 75)
    private val mutedInk = Color.rgb(65, 79, 113)
    private val surface = Color.rgb(239, 244, 255)
    private val surfaceStrong = Color.rgb(219, 230, 255)
    private val blue = Color.rgb(42, 88, 190)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        ThemeManager.setUIStyle(this, ThemeManager.STYLE_AOSP_MATERIAL3)
        window.navigationBarColor = Color.rgb(53, 91, 177)
        // Hide the system status bar completely: no clock, battery, signal or notification indicators.
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        buildDimension()
    }

    private fun buildDimension() {
        val root = FrameLayout(this)
        root.background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.rgb(151, 185, 255), Color.rgb(105, 142, 232), Color.rgb(31, 68, 150))
        )
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(18), dp(22), dp(12))
        }
        root.addView(content, FrameLayout.LayoutParams(-1, -1))

        val search = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), 0, dp(16), 0)
            background = pill(Color.argb(225, 247, 249, 255), 30)
            isClickable = true
            isFocusable = true
        }
        search.addView(text("G", 32f, Color.rgb(66, 133, 244), true), LinearLayout.LayoutParams(dp(45), dp(54)))
        search.addView(text("Telusuri", 16f, mutedInk, false), LinearLayout.LayoutParams(0, dp(54), 1f))
        search.addView(text("🎙", 19f, blue, true))
        val styleButton = text("⋮", 25f, blue, true).apply {
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            setPadding(dp(10), 0, dp(2), 0)
            setOnClickListener { showDimensionStyleChooser() }
        }
        search.addView(styleButton, LinearLayout.LayoutParams(dp(28), dp(54)))
        val searchParams = LinearLayout.LayoutParams(-1, dp(58))
        searchParams.topMargin = dp(4)
        content.addView(search, searchParams)
        search.setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_WEB_SEARCH).putExtra("query", ""))
            } catch (_: Exception) { }
        }

        val widgets = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val clockCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(14), dp(8), dp(14))
            background = pill(Color.argb(232, 242, 246, 255), 28)
        }
        clockCard.addView(text(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()), 43f, ink, false))
        clockCard.addView(text(SimpleDateFormat("EEE, dd MMM yyyy", Locale("id", "ID")).format(Date()), 13f, ink, false))
        widgets.addView(clockCard, LinearLayout.LayoutParams(0, dp(130), 1.05f))
        val weatherCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(10), dp(8), dp(10))
            background = pill(Color.argb(174, 226, 235, 255), 28)
        }
        weatherCard.addView(text("☀  27°C", 23f, ink, true))
        weatherCard.addView(text("Lokasi Anda", 13f, ink, false))
        weatherCard.addView(text("Sebagian cerah", 11f, mutedInk, false))
        val weatherParams = LinearLayout.LayoutParams(0, dp(130), 0.95f)
        weatherParams.leftMargin = dp(12)
        widgets.addView(weatherCard, weatherParams)
        val widgetParams = LinearLayout.LayoutParams(-1, dp(130))
        widgetParams.topMargin = dp(22)
        content.addView(widgets, widgetParams)

        // Keep the widgets at the top and anchor apps/dock toward the bottom of the screen.
        content.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))

        val grid = GridLayout(this).apply {
            columnCount = 4
            useDefaultMargins = false
            alignmentMode = GridLayout.ALIGN_BOUNDS
        }
        val wanted = listOf(
            "com.android.vending", "com.google.android.youtube", "com.android.chrome", "com.google.android.apps.photos",
            "com.google.android.googlequicksearchbox", "com.google.android.gm", "com.google.android.apps.maps", "com.google.android.apps.docs",
            "com.android.settings", "com.google.android.dialer", "com.google.android.apps.messaging", "com.android.camera2"
        )
        val pm = packageManager
        val apps = wanted.mapNotNull { pkg ->
            try {
                val info = pm.getApplicationInfo(pkg, 0)
                Triple(pm.getApplicationLabel(info).toString(), pkg, pm.getApplicationIcon(info))
            } catch (_: Exception) { null }
        }.toMutableList()
        val launchables = try {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), PackageManager.MATCH_ALL)
                .mapNotNull { resolve ->
                    val pkg = resolve.activityInfo?.packageName ?: return@mapNotNull null
                    if (apps.any { it.second == pkg }) return@mapNotNull null
                    Triple(resolve.loadLabel(pm).toString(), pkg, resolve.loadIcon(pm))
                }.sortedBy { it.first.lowercase(Locale.getDefault()) }
        } catch (_: Exception) { emptyList() }
        (apps + launchables).take(12).forEach { app ->
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(2), dp(6), dp(2), dp(8))
                isClickable = true
                isFocusable = true
                background = pill(Color.argb(0, 255, 255, 255), 18)
            }
            val iconWrap = FrameLayout(this).apply {
                background = pill(Color.argb(235, 247, 249, 255), 22)
                setPadding(dp(12), dp(12), dp(12), dp(12))
            }
            iconWrap.addView(ImageView(this).apply {
                setImageDrawable(app.third)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.CENTER))
            cell.addView(iconWrap, LinearLayout.LayoutParams(dp(76), dp(76)))
            cell.addView(text(app.first, 11f, Color.WHITE, false).apply {
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(-1, dp(25)))
            cell.setOnClickListener {
                try { startActivity(pm.getLaunchIntentForPackage(app.second)) } catch (_: Exception) { }
            }
            val params = GridLayout.LayoutParams().apply {
                width = 0
                height = dp(105)
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            }
            grid.addView(cell, params)
        }
        content.addView(grid, LinearLayout.LayoutParams(-1, dp(210)))

        val pageDots = text("●   ○", 15f, Color.WHITE, true).apply { gravity = Gravity.CENTER }
        content.addView(pageDots, LinearLayout.LayoutParams(-1, dp(28)))

        val dock = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = pill(Color.argb(150, 225, 235, 255), 38)
        }
        val dockCandidates = (apps + launchables).distinctBy { it.second }
            .filter { it.second != packageName }
            .take(4)
        dockCandidates.forEach { app ->
            val item = FrameLayout(this).apply {
                background = pill(Color.argb(220, 247, 249, 255), 25)
                setPadding(dp(11), dp(11), dp(11), dp(11))
            }
            item.addView(ImageView(this).apply {
                setImageDrawable(app.third)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }, FrameLayout.LayoutParams(dp(42), dp(42), Gravity.CENTER))
            item.setOnClickListener { try { startActivity(pm.getLaunchIntentForPackage(app.second)) } catch (_: Exception) { } }
            val lp = LinearLayout.LayoutParams(0, dp(66), 1f)
            lp.marginStart = dp(5)
            lp.marginEnd = dp(5)
            dock.addView(item, lp)
        }
        val dockParams = LinearLayout.LayoutParams(-1, dp(82))
        dockParams.topMargin = dp(4)
        content.addView(dock, dockParams)
        setContentView(root)
    }

    private fun showDimensionStyleChooser() {
        val styles = arrayOf("AOSP Material 3 · New Dimension", "SakuLauncher · Gaya utama")
        AlertDialog.Builder(this)
            .setTitle("Gaya antarmuka")
            .setSingleChoiceItems(styles, ThemeManager.getUIStyle(this)) { dialog, which ->
                ThemeManager.setUIStyle(this, which)
                dialog.dismiss()
                if (which == ThemeManager.STYLE_SAKU) {
                    finish()
                } else {
                    buildDimension()
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun text(value: String, size: Float, color: Int, medium: Boolean): TextView =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (medium) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER_VERTICAL
        }

    private fun pill(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
}

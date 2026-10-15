package com.sakuhome.launcher

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class LaunchableApp(
    val label: String,
    val packageName: String,
    val icon: Drawable
)

class MainActivity : Activity() {
    private val ink = Color.rgb(35, 39, 50)
    private val muted = Color.rgb(116, 121, 134)
    private val accent = Color.rgb(81, 108, 190)
    private val surface = Color.WHITE
    private val page = Color.rgb(244, 245, 248)

    private lateinit var grid: GridView
    private lateinit var search: EditText
    private lateinit var sectionTitle: TextView
    private lateinit var drawerButton: TextView
    private lateinit var clockText: TextView
    private lateinit var dateText: TextView
    private lateinit var adapter: AppGridAdapter
    private var allApps: List<LaunchableApp> = emptyList()
    private var showingDrawer = false
    private var query = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = page
        window.navigationBarColor = page
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR

        allApps = loadApps()
        buildScreen()
        refreshClock()
    }

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(10))
            setBackgroundColor(page)
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(18), dp(22), dp(20))
            background = rounded(surface, 30)
        }
        clockText = TextView(this).apply {
            textSize = 48f
            setTextColor(ink)
            letterSpacing = -0.03f
            includeFontPadding = false
            contentDescription = "Waktu saat ini"
        }
        dateText = TextView(this).apply {
            textSize = 15f
            setTextColor(muted)
            setPadding(0, dp(2), 0, 0)
        }
        val subtitle = TextView(this).apply {
            text = "Ruangmu, lebih rapi."
            textSize = 13f
            setTextColor(accent)
            setPadding(0, dp(14), 0, 0)
        }
        top.addView(clockText)
        top.addView(dateText)
        top.addView(subtitle)
        root.addView(top, LinearLayout.LayoutParams(-1, -2))

        search = EditText(this).apply {
            hint = "Cari aplikasi"
            textSize = 15f
            singleLine = true
            setTextColor(ink)
            setHintTextColor(muted)
            setPadding(dp(18), 0, dp(18), 0)
            background = rounded(surface, 24)
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            contentDescription = "Cari aplikasi"
        }
        root.addView(search, LinearLayout.LayoutParams(-1, dp(54)).apply {
            topMargin = dp(14)
        })

        val section = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(18), dp(2), dp(10))
        }
        sectionTitle = TextView(this).apply {
            text = "Favorit"
            textSize = 18f
            setTextColor(ink)
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        drawerButton = TextView(this).apply {
            text = "Semua aplikasi  ›"
            textSize = 13f
            setTextColor(accent)
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setOnClickListener { toggleDrawer() }
            contentDescription = "Buka semua aplikasi"
        }
        section.addView(sectionTitle, LinearLayout.LayoutParams(0, dp(40), 1f))
        section.addView(drawerButton, LinearLayout.LayoutParams(-2, dp(40)))
        root.addView(section)

        grid = GridView(this).apply {
            numColumns = 4
            verticalSpacing = dp(10)
            horizontalSpacing = dp(4)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            gravity = Gravity.TOP
            clipToPadding = false
            setPadding(0, dp(2), 0, dp(8))
            isVerticalScrollBarEnabled = false
        }
        adapter = AppGridAdapter()
        grid.adapter = adapter
        root.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))

        val footer = TextView(this).apply {
            text = "Saku Launcher  ·  Ringan dan rapi"
            textSize = 11f
            setTextColor(muted)
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(2))
        }
        root.addView(footer, LinearLayout.LayoutParams(-1, dp(28)))
        setContentView(root)

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                query = s?.toString()?.trim().orEmpty()
                adapter.notifyDataSetChanged()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
    }

    private fun toggleDrawer() {
        showingDrawer = !showingDrawer
        sectionTitle.text = if (showingDrawer) "Semua aplikasi" else "Favorit"
        drawerButton.text = if (showingDrawer) "Beranda  ‹" else "Semua aplikasi  ›"
        drawerButton.contentDescription = if (showingDrawer) "Kembali ke favorit" else "Buka semua aplikasi"
        adapter.notifyDataSetChanged()
        grid.smoothScrollToPosition(0)
    }

    private fun loadApps(): List<LaunchableApp> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val matches: List<ResolveInfo> = packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        return matches
            .filter { it.activityInfo.packageName != packageName }
            .distinctBy { it.activityInfo.packageName }
            .map { info ->
                LaunchableApp(
                    label = info.loadLabel(packageManager).toString(),
                    packageName = info.activityInfo.packageName,
                    icon = info.loadIcon(packageManager)
                )
            }
            .sortedBy { it.label.lowercase(Locale.getDefault()) }
    }

    private fun refreshClock() {
        val now = Date()
        clockText.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
        dateText.text = SimpleDateFormat("EEEE, d MMMM", Locale("id", "ID")).format(now)
    }

    private fun visibleApps(): List<LaunchableApp> {
        val base = if (showingDrawer || query.isNotEmpty()) allApps else allApps.take(8)
        return if (query.isEmpty()) base else base.filter { it.label.contains(query, ignoreCase = true) }
    }

    private fun launch(app: LaunchableApp) {
        val launchIntent = packageManager.getLaunchIntentForPackage(app.packageName)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launchIntent)
        } else {
            Toast.makeText(this, "Aplikasi tidak dapat dibuka", Toast.LENGTH_SHORT).show()
        }
    }

    private fun rounded(color: Int, radiusDp: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private inner class AppGridAdapter : BaseAdapter() {
        override fun getCount(): Int = visibleApps().size
        override fun getItem(position: Int): LaunchableApp = visibleApps()[position]
        override fun getItemId(position: Int): Long = getItem(position).packageName.hashCode().toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val app = getItem(position)
            val item = (convertView as? LinearLayout) ?: LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(4), dp(8), dp(4), dp(8))
                background = rounded(surface, 22)
                layoutParams = GridView.LayoutParams(-1, dp(104))
                val icon = ImageView(context).apply {
                    id = android.R.id.icon
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    contentDescription = null
                }
                addView(icon, LinearLayout.LayoutParams(dp(42), dp(42)))
                val label = TextView(context).apply {
                    id = android.R.id.text1
                    textSize = 11f
                    setTextColor(ink)
                    gravity = Gravity.CENTER
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(0, dp(6), 0, 0)
                }
                addView(label, LinearLayout.LayoutParams(-1, -2))
            }
            item.findViewById<ImageView>(android.R.id.icon).setImageDrawable(app.icon)
            item.findViewById<TextView>(android.R.id.text1).text = app.label
            item.contentDescription = app.label
            item.setOnClickListener { launch(app) }
            return item
        }
    }
}

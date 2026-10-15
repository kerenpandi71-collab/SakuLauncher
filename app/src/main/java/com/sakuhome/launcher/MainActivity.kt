package com.sakuhome.launcher

import android.app.Activity
import android.app.ActivityOptions
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class LaunchableApp(
    val label: String,
    val packageName: String,
    val icon: Drawable
)

class MainActivity : Activity() {
    private lateinit var preferences: SharedPreferences
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

    private val isDesktop: Boolean
        get() = preferences.getString(KEY_MODE, MODE_NORMAL) == MODE_DESKTOP
    private val darkTheme: Boolean
        get() = preferences.getBoolean(KEY_DARK_THEME, false)
    private val showLabels: Boolean
        get() = preferences.getBoolean(KEY_SHOW_LABELS, true)
    private val iconSize: Int
        get() = preferences.getInt(KEY_ICON_SIZE, 44)
    private val appColumns: Int
        get() = preferences.getInt(KEY_COLUMNS, 4)
    private val floatingWindows: Boolean
        get() = preferences.getBoolean(KEY_FLOATING, true)
    private val accentColor: Int
        get() = preferences.getInt(KEY_ACCENT, DEFAULT_ACCENT)

    private val ink: Int
        get() = if (darkTheme) Color.rgb(242, 244, 250) else Color.rgb(35, 39, 50)
    private val muted: Int
        get() = if (darkTheme) Color.rgb(178, 184, 199) else Color.rgb(116, 121, 134)
    private val page: Int
        get() = when {
            darkTheme -> Color.rgb(25, 29, 40)
            isDesktop -> Color.rgb(223, 230, 244)
            else -> Color.rgb(244, 245, 248)
        }
    private val surface: Int
        get() = if (darkTheme) Color.rgb(42, 47, 61) else Color.WHITE

    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockUpdater = object : Runnable {
        override fun run() {
            if (::clockText.isInitialized) refreshClock()
            clockHandler.postDelayed(this, 30_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        allApps = loadApps()
        buildScreen()
    }

    override fun onResume() {
        super.onResume()
        if (::clockText.isInitialized) refreshClock()
        clockHandler.removeCallbacks(clockUpdater)
        clockHandler.post(clockUpdater)
        allApps = loadApps()
        if (::adapter.isInitialized) adapter.notifyDataSetChanged()
    }

    override fun onPause() {
        clockHandler.removeCallbacks(clockUpdater)
        super.onPause()
    }

    private fun buildScreen() {
        val isDark = darkTheme
        window.statusBarColor = page
        window.navigationBarColor = page
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        if (isDark) {
            window.decorView.systemUiVisibility = 0
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(if (isDesktop) 10 else 14), dp(18), dp(8))
            setBackgroundColor(page)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(if (isDesktop) 12 else 17), dp(14), dp(if (isDesktop) 12 else 17))
            background = rounded(surface, if (isDesktop) 20 else 28)
        }
        val headerText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val eyebrow = TextView(this).apply {
            text = if (isDesktop) "SAKU DESKTOP" else "SAKU LAUNCHER"
            textSize = 10f
            letterSpacing = 0.12f
            setTextColor(accentColor)
        }
        clockText = TextView(this).apply {
            textSize = if (isDesktop) 30f else 46f
            setTextColor(ink)
            letterSpacing = -0.025f
            includeFontPadding = false
            contentDescription = "Waktu saat ini"
        }
        dateText = TextView(this).apply {
            textSize = if (isDesktop) 12f else 14f
            setTextColor(muted)
            setPadding(0, dp(2), 0, 0)
        }
        headerText.addView(eyebrow)
        headerText.addView(clockText, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(3)
        })
        headerText.addView(dateText)
        header.addView(headerText, LinearLayout.LayoutParams(0, -2, 1f))

        val settingsButton = textButton("⚙", surface, ink, 22).apply {
            textSize = 21f
            contentDescription = "Buka kustomisasi"
            setOnClickListener { showSettings() }
        }
        header.addView(settingsButton, LinearLayout.LayoutParams(dp(48), dp(48)))
        root.addView(header, LinearLayout.LayoutParams(-1, -2))

        val modes = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = rounded(surface, 20)
        }
        modes.addView(modeChip("Normal", !isDesktop) { setMode(MODE_NORMAL) },
            LinearLayout.LayoutParams(0, dp(42), 1f))
        modes.addView(modeChip("Desktop", isDesktop) { setMode(MODE_DESKTOP) },
            LinearLayout.LayoutParams(0, dp(42), 1f))
        root.addView(modes, LinearLayout.LayoutParams(-1, dp(50)).apply {
            topMargin = dp(10)
        })

        search = EditText(this).apply {
            hint = "Cari aplikasi"
            textSize = 14f
            setSingleLine(true)
            setTextColor(ink)
            setHintTextColor(muted)
            setPadding(dp(17), 0, dp(17), 0)
            background = rounded(surface, 22)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            contentDescription = "Cari aplikasi"
            setText(query)
        }
        root.addView(search, LinearLayout.LayoutParams(-1, dp(50)).apply {
            topMargin = dp(10)
        })

        val section = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(if (isDesktop) 8 else 14), dp(2), dp(6))
        }
        sectionTitle = TextView(this).apply {
            text = if (showingDrawer) "Semua aplikasi" else if (isDesktop) "Desktop" else "Aplikasi"
            textSize = 17f
            setTextColor(ink)
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        drawerButton = TextView(this).apply {
            textSize = 12f
            setTextColor(accentColor)
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            text = if (showingDrawer) "Beranda  ‹" else "Semua aplikasi  ›"
            contentDescription = if (showingDrawer) "Kembali ke beranda" else "Buka semua aplikasi"
            setOnClickListener { toggleDrawer() }
        }
        section.addView(sectionTitle, LinearLayout.LayoutParams(0, dp(38), 1f))
        section.addView(drawerButton, LinearLayout.LayoutParams(-2, dp(38)))
        root.addView(section)

        grid = GridView(this).apply {
            numColumns = appColumns.coerceIn(3, 6)
            verticalSpacing = dp(8)
            horizontalSpacing = dp(5)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            gravity = Gravity.TOP
            clipToPadding = false
            setPadding(0, dp(2), 0, dp(6))
            isVerticalScrollBarEnabled = false
        }
        adapter = AppGridAdapter()
        grid.adapter = adapter
        root.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))

        if (isDesktop) {
            root.addView(buildTaskbar(), LinearLayout.LayoutParams(-1, dp(58)).apply {
                topMargin = dp(7)
            })
        } else {
            val footer = TextView(this).apply {
                text = "Tahan ikon untuk mengatur favorit  ·  ⚙ untuk kustomisasi"
                textSize = 10f
                setTextColor(muted)
                gravity = Gravity.CENTER
                setPadding(0, dp(7), 0, dp(3))
            }
            root.addView(footer, LinearLayout.LayoutParams(-1, dp(24)))
        }

        setContentView(root)
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                query = s?.toString()?.trim().orEmpty()
                if (::adapter.isInitialized) adapter.notifyDataSetChanged()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        refreshClock()
    }

    private fun buildTaskbar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(7), dp(5), dp(7), dp(5))
            background = rounded(surface, 19)
        }
        val startButton = textButton("▦", accentColor, Color.WHITE, 16).apply {
            contentDescription = "Buka semua aplikasi"
            setOnClickListener { toggleDrawer() }
        }
        bar.addView(startButton, LinearLayout.LayoutParams(dp(44), -1))

        favorites().take(4).forEach { app ->
            val icon = ImageView(this).apply {
                setImageDrawable(app.icon)
                scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = app.label
                setPadding(dp(7), dp(7), dp(7), dp(7))
                setOnClickListener { launch(app) }
            }
            bar.addView(icon, LinearLayout.LayoutParams(dp(42), -1))
        }

        val spacer = View(this)
        bar.addView(spacer, LinearLayout.LayoutParams(0, 1, 1f))
        val time = TextView(this).apply {
            text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            textSize = 12f
            setTextColor(ink)
            gravity = Gravity.CENTER
            contentDescription = "Waktu"
        }
        bar.addView(time, LinearLayout.LayoutParams(dp(48), -1))
        val settings = textButton("⚙", surface, muted, 15).apply {
            contentDescription = "Kustomisasi"
            setOnClickListener { showSettings() }
        }
        bar.addView(settings, LinearLayout.LayoutParams(dp(42), -1))
        return bar
    }

    private fun modeChip(label: String, selected: Boolean, action: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(if (selected) Color.WHITE else muted)
            background = rounded(if (selected) accentColor else surface, 16)
            setOnClickListener { action() }
        }

    private fun textButton(textValue: String, backgroundColor: Int, textColor: Int, radius: Int): TextView =
        TextView(this).apply {
            text = textValue
            gravity = Gravity.CENTER
            setTextColor(textColor)
            background = rounded(backgroundColor, radius)
            isClickable = true
            isFocusable = true
        }

    private fun toggleDrawer() {
        showingDrawer = !showingDrawer
        sectionTitle.text = if (showingDrawer) "Semua aplikasi" else if (isDesktop) "Desktop" else "Aplikasi"
        drawerButton.text = if (showingDrawer) "Beranda  ‹" else "Semua aplikasi  ›"
        drawerButton.contentDescription = if (showingDrawer) "Kembali ke beranda" else "Buka semua aplikasi"
        adapter.notifyDataSetChanged()
        grid.smoothScrollToPosition(0)
    }

    private fun setMode(mode: String) {
        preferences.edit().putString(KEY_MODE, mode).apply()
        showingDrawer = false
        buildScreen()
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

    private fun favorites(): List<LaunchableApp> {
        val selected = preferences.getStringSet(KEY_FAVORITES, emptySet())?.toSet().orEmpty()
        return if (selected.isEmpty()) allApps.take(8)
        else allApps.filter { it.packageName in selected }
    }

    private fun visibleApps(): List<LaunchableApp> {
        val base = if (query.isNotEmpty() || showingDrawer) allApps else favorites()
        return if (query.isEmpty()) base else base.filter { it.label.contains(query, ignoreCase = true) }
    }

    private fun toggleFavorite(app: LaunchableApp) {
        val selected = preferences.getStringSet(KEY_FAVORITES, emptySet())?.toMutableSet() ?: mutableSetOf()
        if (selected.isEmpty()) selected.addAll(allApps.take(8).map { it.packageName })
        val removed = selected.remove(app.packageName)
        if (!removed) selected.add(app.packageName)
        preferences.edit().putStringSet(KEY_FAVORITES, selected).apply()
        adapter.notifyDataSetChanged()
        Toast.makeText(
            this,
            if (removed) "${app.label} dihapus dari favorit" else "${app.label} ditambahkan ke favorit",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun launch(app: LaunchableApp) {
        val launchIntent = packageManager.getLaunchIntentForPackage(app.packageName)
        if (launchIntent == null) {
            Toast.makeText(this, "Aplikasi tidak dapat dibuka", Toast.LENGTH_SHORT).show()
            return
        }
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            if (isDesktop && floatingWindows) {
                val width = resources.displayMetrics.widthPixels
                val height = resources.displayMetrics.heightPixels
                val bounds = Rect(
                    (width * 0.06f).toInt(),
                    (height * 0.08f).toInt(),
                    (width * 0.94f).toInt(),
                    (height * 0.86f).toInt()
                )
                val options = ActivityOptions.makeBasic().setLaunchBounds(bounds)
                startActivity(launchIntent, options.toBundle())
            } else {
                startActivity(launchIntent)
            }
        } catch (_: Exception) {
            Toast.makeText(this, "Perangkat membuka aplikasi dengan mode jendela standarnya", Toast.LENGTH_SHORT).show()
            startActivity(launchIntent)
        }
    }

    private fun showSettings() {
        val options = arrayOf(
            "Mode tampilan: ${if (isDesktop) "Desktop" else "Normal"}",
            "Jumlah kolom: $appColumns",
            "Ukuran ikon: $iconSize dp",
            "Nama aplikasi: ${if (showLabels) "Tampil" else "Sembunyi"}",
            "Tema: ${if (darkTheme) "Gelap" else "Terang"}",
            "Warna aksen",
            "Jendela bebas: ${if (floatingWindows) "Aktif" else "Nonaktif"}",
            "Atur aplikasi favorit"
        )
        AlertDialog.Builder(this)
            .setTitle("Kustomisasi Saku")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> chooseMode()
                    1 -> chooseColumns()
                    2 -> chooseIconSize()
                    3 -> {
                        preferences.edit().putBoolean(KEY_SHOW_LABELS, !showLabels).apply()
                        buildScreen()
                    }
                    4 -> {
                        preferences.edit().putBoolean(KEY_DARK_THEME, !darkTheme).apply()
                        buildScreen()
                    }
                    5 -> chooseAccent()
                    6 -> configureFloatingWindows()
                    7 -> Toast.makeText(this, "Tahan ikon aplikasi untuk menambah atau menghapus favorit", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Tutup", null)
            .show()
    }

    private fun chooseMode() {
        val modes = arrayOf("Normal", "Desktop")
        val selected = if (isDesktop) 1 else 0
        AlertDialog.Builder(this)
            .setTitle("Pilih mode")
            .setSingleChoiceItems(modes, selected) { dialog, which ->
                setMode(if (which == 1) MODE_DESKTOP else MODE_NORMAL)
                dialog.dismiss()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun chooseColumns() {
        val values = intArrayOf(3, 4, 5, 6)
        showChoice("Kolom aplikasi", values.map { "$it kolom" }.toTypedArray(), values.indexOf(appColumns).coerceAtLeast(0)) { index ->
            preferences.edit().putInt(KEY_COLUMNS, values[index]).apply()
            buildScreen()
        }
    }

    private fun chooseIconSize() {
        val values = intArrayOf(36, 44, 52, 60)
        showChoice("Ukuran ikon", values.map { "$it dp" }.toTypedArray(), values.indexOf(iconSize).coerceAtLeast(0)) { index ->
            preferences.edit().putInt(KEY_ICON_SIZE, values[index]).apply()
            buildScreen()
        }
    }

    private fun chooseAccent() {
        val colors = intArrayOf(
            Color.rgb(81, 108, 190),
            Color.rgb(0, 137, 123),
            Color.rgb(125, 86, 179),
            Color.rgb(218, 104, 84)
        )
        val names = arrayOf("Biru", "Teal", "Ungu", "Koral")
        val selected = colors.indexOf(accentColor).coerceAtLeast(0)
        showChoice("Warna aksen", names, selected) { index ->
            preferences.edit().putInt(KEY_ACCENT, colors[index]).apply()
            buildScreen()
        }
    }

    private fun configureFloatingWindows() {
        val enabled = !floatingWindows
        AlertDialog.Builder(this)
            .setTitle(if (enabled) "Aktifkan jendela bebas?" else "Matikan jendela bebas?")
            .setMessage("Mode Desktop akan meminta Android membuka aplikasi dalam batas jendela. Tampilan floating nyata bergantung pada dukungan perangkat, Android, atau DeX; jika tidak didukung, aplikasi tetap terbuka normal.")
            .setPositiveButton(if (enabled) "Aktifkan" else "Matikan") { _, _ ->
                preferences.edit().putBoolean(KEY_FLOATING, enabled).apply()
                buildScreen()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun showChoice(title: String, values: Array<String>, selected: Int, onChoose: (Int) -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setSingleChoiceItems(values, selected) { dialog, which ->
                onChoose(which)
                dialog.dismiss()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun refreshClock() {
        val now = Date()
        if (::clockText.isInitialized) {
            clockText.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
            dateText.text = SimpleDateFormat("EEEE, d MMMM", Locale("id", "ID")).format(now)
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
                setPadding(dp(4), dp(7), dp(4), dp(7))
                background = rounded(surface, if (isDesktop) 15 else 21)
                layoutParams = AbsListView.LayoutParams(-1, dp(iconSize + if (showLabels) 54 else 28))
                val icon = ImageView(context).apply {
                    id = android.R.id.icon
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    contentDescription = null
                }
                addView(icon, LinearLayout.LayoutParams(dp(iconSize), dp(iconSize)))
                val label = TextView(context).apply {
                    id = android.R.id.text1
                    textSize = 10.5f
                    setTextColor(ink)
                    gravity = Gravity.CENTER
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    visibility = if (showLabels) View.VISIBLE else View.GONE
                    setPadding(0, dp(5), 0, 0)
                }
                addView(label, LinearLayout.LayoutParams(-1, -2))
            }
            item.findViewById<ImageView>(android.R.id.icon).setImageDrawable(app.icon)
            item.findViewById<TextView>(android.R.id.text1).apply {
                text = app.label
                visibility = if (showLabels) View.VISIBLE else View.GONE
            }
            item.contentDescription = app.label
            item.setOnClickListener { launch(app) }
            item.setOnLongClickListener {
                toggleFavorite(app)
                true
            }
            return item
        }
    }

    companion object {
        private const val PREFS_NAME = "saku_launcher_settings"
        private const val KEY_MODE = "mode"
        private const val KEY_COLUMNS = "columns"
        private const val KEY_ICON_SIZE = "icon_size"
        private const val KEY_SHOW_LABELS = "show_labels"
        private const val KEY_DARK_THEME = "dark_theme"
        private const val KEY_ACCENT = "accent_color"
        private const val KEY_FLOATING = "floating_windows"
        private const val KEY_FAVORITES = "favorite_packages"
        private const val MODE_NORMAL = "normal"
        private const val MODE_DESKTOP = "desktop"
        private val DEFAULT_ACCENT = Color.rgb(81, 108, 190)
    }
}

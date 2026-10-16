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
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.HorizontalScrollView
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
    val icon: android.graphics.drawable.Drawable
)

class MainActivity : Activity() {
    private lateinit var preferences: SharedPreferences
    private lateinit var rootFrame: FrameLayout
    private lateinit var startPanel: LinearLayout
    private lateinit var startGrid: GridView
    private lateinit var desktopGrid: GridView
    private lateinit var startSearchField: EditText
    private lateinit var pinnedTabButton: TextView
    private lateinit var allAppsTabButton: TextView
    private lateinit var clockText: TextView
    private lateinit var dateText: TextView
    private lateinit var pinnedAdapter: AppGridAdapter
    private lateinit var startAdapter: AppGridAdapter

    private var apps: List<LaunchableApp> = emptyList()
    private var searchQuery = ""
    private var showingAllApps = true

    private val backgroundTop = Color.rgb(24, 31, 48)
    private val backgroundBottom = Color.rgb(37, 47, 68)
    private val dockSurface = Color.rgb(34, 41, 57)
    private val cardSurface = Color.rgb(43, 51, 69)
    private val primaryText = Color.rgb(246, 247, 251)
    private val secondaryText = Color.rgb(179, 188, 207)
    private val accent = Color.rgb(110, 151, 245)

    private fun pinnedPackages(): MutableList<String> {
        if (!preferences.contains(KEY_PINNED)) return apps.take(5).map { it.packageName }.toMutableList()
        return preferences.getString(KEY_PINNED, "").orEmpty()
            .split('\n').filter { it.isNotBlank() }.toMutableList()
    }

    private fun pinnedApps(): List<LaunchableApp> {
        val byPackage = apps.associateBy { it.packageName }
        return pinnedPackages().mapNotNull { byPackage[it] }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        apps = loadApps()
        buildScreen()
        if (intent?.getBooleanExtra(EXTRA_OPEN_START, false) == true) showStartPanel()
        if (intent?.getBooleanExtra(EXTRA_OPEN_SETTINGS, false) == true) rootFrame.post { showSettings() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_START, false)) showStartPanel()
        if (intent.getBooleanExtra(EXTRA_OPEN_SETTINGS, false)) showSettings()
    }

    override fun onResume() {
        super.onResume()
        if (!::preferences.isInitialized) return
        apps = loadApps()
        if (::rootFrame.isInitialized) {
            rebuildPinnedViews()
            updateClock()
        }
        reconcileOverlay()
    }

    override fun onPause() {
        super.onPause()
        startSearchField.hideKeyboard()
    }

    private fun buildScreen() {
        window.statusBarColor = backgroundTop
        window.navigationBarColor = backgroundTop
        window.decorView.systemUiVisibility = 0

        rootFrame = FrameLayout(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(backgroundTop, backgroundBottom)
            )
        }
        val shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(8))
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(3), dp(5))
        }
        val brand = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        brand.addView(TextView(this).apply {
            text = "SAKU DOCK"
            textSize = 11f
            letterSpacing = 0.16f
            setTextColor(accent)
        })
        clockText = TextView(this).apply {
            textSize = 26f
            setTextColor(primaryText)
            includeFontPadding = false
        }
        dateText = TextView(this).apply {
            textSize = 12f
            setTextColor(secondaryText)
        }
        brand.addView(clockText, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(3) })
        brand.addView(dateText, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(2) })
        topBar.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))

        topBar.addView(chipButton("⚙", cardSurface, primaryText, 19).apply {
            contentDescription = "Pengaturan Saku Dock"
            setOnClickListener { showSettings() }
        }, LinearLayout.LayoutParams(dp(46), dp(46)))
        shell.addView(topBar, LinearLayout.LayoutParams(-1, -2))

        val workspaceTitle = TextView(this).apply {
            text = "Ruang kerja"
            textSize = 13f
            setTextColor(secondaryText)
            setPadding(dp(4), dp(8), dp(4), dp(7))
        }
        shell.addView(workspaceTitle)

        desktopGrid = GridView(this).apply {
            numColumns = if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) 6 else 4
            verticalSpacing = dp(15)
            horizontalSpacing = dp(5)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            setSelector(ColorDrawable(Color.TRANSPARENT))
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            setPadding(0, dp(4), 0, dp(4))
        }
        pinnedAdapter = AppGridAdapter(isStartMenu = false)
        desktopGrid.adapter = pinnedAdapter
        shell.addView(desktopGrid, LinearLayout.LayoutParams(-1, 0, 1f))

        shell.addView(buildDock(), LinearLayout.LayoutParams(-1, dp(62)).apply {
            topMargin = dp(7)
        })
        rootFrame.addView(shell, FrameLayout.LayoutParams(-1, -1))
        startPanel = buildStartPanel()
        val screenHeightDp = (resources.displayMetrics.heightPixels / resources.displayMetrics.density).toInt()
        val panelHeight = (screenHeightDp - 190).coerceIn(300, 580)
        val panelWidth = minOf(resources.displayMetrics.widthPixels - dp(28), dp(500))
        rootFrame.addView(startPanel, FrameLayout.LayoutParams(panelWidth, dp(panelHeight), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(82)
        })
        startPanel.visibility = View.GONE
        setContentView(rootFrame)
        updateClock()
    }

    private fun buildDock(): View {
        val dock = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(7), dp(6), dp(7), dp(6))
            background = rounded(dockSurface, 23)
            elevation = dp(9).toFloat()
        }
        dock.addView(chipButton("▦", accent, Color.WHITE, 17).apply {
            textSize = 20f
            contentDescription = "Buka menu Start"
            setOnClickListener { toggleStartPanel() }
        }, LinearLayout.LayoutParams(dp(49), -1))

        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
        }
        val icons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(5), 0, dp(5), 0)
        }
        pinnedApps().take(6).forEachIndexed { index, app ->
            icons.addView(dockIcon(app, index), LinearLayout.LayoutParams(dp(48), -1))
        }
        scroll.addView(icons)
        dock.addView(scroll, LinearLayout.LayoutParams(0, -1, 1f))
        dock.addView(chipButton("⋯", cardSurface, secondaryText, 16).apply {
            contentDescription = "Kelola dock"
            setOnClickListener { showSettings() }
        }, LinearLayout.LayoutParams(dp(43), -1))
        return dock
    }

    private fun dockIcon(app: LaunchableApp, index: Int): View = ImageView(this).apply {
        setImageDrawable(app.icon)
        scaleType = ImageView.ScaleType.FIT_CENTER
        setPadding(dp(8), dp(7), dp(8), dp(7))
        contentDescription = app.label
        isClickable = true
        isFocusable = true
        setOnClickListener { launch(app) }
        setOnLongClickListener {
            showDockItemMenu(app, index)
            true
        }
    }

    private fun buildStartPanel(): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(15), dp(16), dp(12))
            background = rounded(Color.rgb(31, 38, 54), 26)
            elevation = dp(16).toFloat()
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "Start"
            textSize = 20f
            setTextColor(primaryText)
            setTypeface(null, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, dp(42), 1f))
        header.addView(chipButton("×", cardSurface, secondaryText, 15).apply {
            setOnClickListener { hideStartPanel() }
        }, LinearLayout.LayoutParams(dp(38), dp(38)))
        panel.addView(header)

        startSearchField = EditText(this).apply {
            hint = "Cari aplikasi"
            textSize = 14f
            setSingleLine(true)
            setTextColor(primaryText)
            setHintTextColor(secondaryText)
            setPadding(dp(15), 0, dp(15), 0)
            background = rounded(cardSurface, 18)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            contentDescription = "Cari aplikasi di menu Start"
        }
        panel.addView(startSearchField, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(10) })

        val filters = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(7), 0, dp(7))
        }
        pinnedTabButton = chipButton("Disematkan", if (!showingAllApps) accent else cardSurface, primaryText, 15).apply {
            textSize = 12f
            setOnClickListener {
                showingAllApps = false
                refreshStartPanel()
            }
        }
        allAppsTabButton = chipButton("Semua aplikasi", if (showingAllApps) accent else cardSurface, primaryText, 15).apply {
            textSize = 12f
            setOnClickListener {
                showingAllApps = true
                refreshStartPanel()
            }
        }
        filters.addView(pinnedTabButton, LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginEnd = dp(6) })
        filters.addView(allAppsTabButton, LinearLayout.LayoutParams(0, dp(38), 1f))
        panel.addView(filters)

        startGrid = GridView(this).apply {
            numColumns = if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) 5 else 4
            verticalSpacing = dp(5)
            horizontalSpacing = dp(4)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            setSelector(ColorDrawable(Color.TRANSPARENT))
            isVerticalScrollBarEnabled = false
        }
        startAdapter = AppGridAdapter(isStartMenu = true)
        startGrid.adapter = startAdapter
        panel.addView(startGrid, LinearLayout.LayoutParams(-1, 0, 1f))

        startSearchField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchQuery = s?.toString()?.trim().orEmpty()
                refreshStartPanel()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        return panel
    }

    private fun toggleStartPanel() {
        if (startPanel.visibility == View.VISIBLE) hideStartPanel() else showStartPanel()
    }

    private fun showStartPanel() {
        if (!::startPanel.isInitialized) return
        showingAllApps = true
        startPanel.visibility = View.VISIBLE
        startPanel.bringToFront()
        startSearchField.setText("")
        refreshStartPanel()
    }

    private fun hideStartPanel() {
        if (::startPanel.isInitialized) startPanel.visibility = View.GONE
        searchQuery = ""
        if (::startSearchField.isInitialized) startSearchField.setText("")
    }

    private fun refreshStartPanel() {
        if (::pinnedTabButton.isInitialized) {
            pinnedTabButton.background = rounded(if (!showingAllApps) accent else cardSurface, 15)
            allAppsTabButton.background = rounded(if (showingAllApps) accent else cardSurface, 15)
        }
        if (::startAdapter.isInitialized) startAdapter.notifyDataSetChanged()
    }

    private fun visibleApps(isStartMenu: Boolean): List<LaunchableApp> {
        val base = if (isStartMenu && !showingAllApps) pinnedApps() else apps
        return if (searchQuery.isBlank()) base else base.filter { it.label.contains(searchQuery, ignoreCase = true) }
    }

    private fun loadApps(): List<LaunchableApp> {
        val queryIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val matches: List<ResolveInfo> = packageManager.queryIntentActivities(queryIntent, PackageManager.MATCH_ALL)
        return matches.filter { it.activityInfo.packageName != packageName }
            .distinctBy { it.activityInfo.packageName }
            .map { info -> LaunchableApp(info.loadLabel(packageManager).toString(), info.activityInfo.packageName, info.loadIcon(packageManager)) }
            .sortedBy { it.label.lowercase(Locale.getDefault()) }
    }

    private fun rebuildPinnedViews() {
        if (::pinnedAdapter.isInitialized) pinnedAdapter.notifyDataSetChanged()
        if (::startAdapter.isInitialized) startAdapter.notifyDataSetChanged()
        // Rebuild only the dock; keep the open Start panel and its scroll position intact.
        val shell = rootFrame.getChildAt(0) as? LinearLayout ?: return
        val dockIndex = shell.childCount - 1
        shell.removeViewAt(dockIndex)
        shell.addView(buildDock(), LinearLayout.LayoutParams(-1, dp(62)).apply { topMargin = dp(7) })
    }

    private fun launch(app: LaunchableApp, asFreeform: Boolean = false) {
        hideStartPanel()
        val launchIntent = packageManager.getLaunchIntentForPackage(app.packageName)
        if (launchIntent == null) {
            Toast.makeText(this, "Aplikasi tidak dapat dibuka", Toast.LENGTH_SHORT).show()
            return
        }
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            if (asFreeform) {
                val width = resources.displayMetrics.widthPixels
                val height = resources.displayMetrics.heightPixels
                val margin = dp(24)
                val bounds = Rect(margin, dp(64), width - margin, height - dp(84))
                val options = ActivityOptions.makeBasic().setLaunchBounds(bounds)
                startActivity(launchIntent, options.toBundle())
            } else {
                startActivity(launchIntent)
            }
        } catch (_: Exception) {
            if (asFreeform) {
                Toast.makeText(this, "Mode jendela tidak didukung; membuka aplikasi seperti biasa", Toast.LENGTH_SHORT).show()
                startActivity(launchIntent)
            } else {
                Toast.makeText(this, "Aplikasi tidak dapat dibuka pada perangkat ini", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showAppMenu(app: LaunchableApp) {
        val isPinned = pinnedPackages().contains(app.packageName)
        val pinAction = if (isPinned) "Lepas dari dock" else "Sematkan ke dock"
        val options = arrayOf("Buka aplikasi", "Coba buka sebagai jendela", pinAction)
        AlertDialog.Builder(this).setTitle(app.label).setItems(options) { _, which ->
            when (which) {
                0 -> launch(app)
                1 -> launch(app, asFreeform = true)
                2 -> togglePinned(app)
            }
        }.setNegativeButton("Batal", null).show()
    }

    private fun togglePinned(app: LaunchableApp) {
        val current = pinnedPackages()
        if (current.contains(app.packageName)) current.remove(app.packageName) else current.add(app.packageName)
        savePinned(current)
        Toast.makeText(this, if (current.contains(app.packageName)) "${app.label} disematkan" else "${app.label} dilepas", Toast.LENGTH_SHORT).show()
        rebuildPinnedViews()
    }

    private fun savePinned(packages: List<String>) {
        preferences.edit().putString(KEY_PINNED, packages.distinct().joinToString("\n")).apply()
        if (preferences.getBoolean(KEY_OVERLAY_ENABLED, false) && Settings.canDrawOverlays(this)) {
            startService(Intent(this, FloatingDockService::class.java).setAction(FloatingDockService.ACTION_REFRESH))
        }
    }

    private fun showDockItemMenu(app: LaunchableApp, index: Int) {
        val options = arrayOf("Pindah ke kiri", "Pindah ke kanan", "Lepas dari dock")
        AlertDialog.Builder(this).setTitle(app.label).setItems(options) { _, selected ->
            val current = pinnedPackages()
            val position = current.indexOf(app.packageName)
            if (position < 0) return@setItems
            when (selected) {
                0 -> if (position > 0) current.add(position - 1, current.removeAt(position))
                1 -> if (position < current.lastIndex) current.add(position + 1, current.removeAt(position))
                2 -> current.removeAt(position)
            }
            savePinned(current)
            rebuildPinnedViews()
        }.setNegativeButton("Batal", null).show()
    }

    private fun showSettings() {
        val overlayState = if (preferences.getBoolean(KEY_OVERLAY_ENABLED, false)) "Aktif" else "Nonaktif"
        val pinnedState = "${pinnedApps().size} aplikasi"
        val options = arrayOf("Dock mengambang di atas aplikasi — $overlayState", "Kelola pin aplikasi — $pinnedState", "Tentang izin & mode jendela")
        AlertDialog.Builder(this).setTitle("Pengaturan Saku Dock")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> toggleOverlay()
                    1 -> managePins()
                    2 -> showPermissionInfo()
                }
            }.setNegativeButton("Tutup", null).show()
    }

    private fun toggleOverlay() {
        val enabled = preferences.getBoolean(KEY_OVERLAY_ENABLED, false)
        if (enabled) {
            preferences.edit().putBoolean(KEY_OVERLAY_ENABLED, false).apply()
            stopService(Intent(this, FloatingDockService::class.java))
            Toast.makeText(this, "Dock mengambang dimatikan", Toast.LENGTH_SHORT).show()
            return
        }
        if (Settings.canDrawOverlays(this)) {
            preferences.edit().putBoolean(KEY_OVERLAY_ENABLED, true).apply()
            startService(Intent(this, FloatingDockService::class.java))
            Toast.makeText(this, "Dock mengambang aktif", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Izinkan dock tampil di atas aplikasi?")
            .setMessage("Izin ini hanya dipakai untuk menampilkan dock Saku di atas aplikasi lain. Izin tidak memberi akses root dan dapat dicabut kapan saja di Pengaturan Android.")
            .setPositiveButton("Lanjut ke Pengaturan") { _, _ ->
                preferences.edit().putBoolean(KEY_OVERLAY_PENDING, true).apply()
                val permissionIntent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                startActivity(permissionIntent)
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun reconcileOverlay() {
        val pending = preferences.getBoolean(KEY_OVERLAY_PENDING, false)
        if (pending) {
            preferences.edit().putBoolean(KEY_OVERLAY_PENDING, false).apply()
            if (Settings.canDrawOverlays(this)) {
                preferences.edit().putBoolean(KEY_OVERLAY_ENABLED, true).apply()
                startService(Intent(this, FloatingDockService::class.java))
            }
        }
        if (preferences.getBoolean(KEY_OVERLAY_ENABLED, false)) {
            if (Settings.canDrawOverlays(this)) startService(Intent(this, FloatingDockService::class.java))
            else preferences.edit().putBoolean(KEY_OVERLAY_ENABLED, false).apply()
        }
    }

    private fun managePins() {
        val current = pinnedPackages().toMutableSet()
        val names = apps.map { it.label }.toTypedArray()
        val checked = apps.map { current.contains(it.packageName) }.toBooleanArray()
        AlertDialog.Builder(this).setTitle("Aplikasi di dock")
            .setMultiChoiceItems(names, checked) { _, which, isChecked ->
                val pkg = apps[which].packageName
                if (isChecked) current.add(pkg) else current.remove(pkg)
            }
            .setPositiveButton("Simpan") { _, _ ->
                val ordered = apps.map { it.packageName }.filter { current.contains(it) }
                savePinned(ordered)
                rebuildPinnedViews()
            }
            .setNegativeButton("Batal", null).show()
    }

    private fun showPermissionInfo() {
        AlertDialog.Builder(this).setTitle("Izin dan dukungan")
            .setMessage("Dock di atas aplikasi memakai izin overlay Android, bukan root. Menu Start dan pin aplikasi bekerja tanpa Shizuku. Jendela bebas dikendalikan Android dan dukungan perangkat; Saku tidak dapat memaksakannya. Shizuku/root hanya akan ditambahkan untuk kontrol sistem tertentu yang benar-benar memerlukannya dan selalu diminta secara terpisah.")
            .setPositiveButton("Mengerti", null).show()
    }

    private fun updateClock() {
        if (!::clockText.isInitialized) return
        val now = Date()
        clockText.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
        dateText.text = SimpleDateFormat("EEEE, d MMMM", Locale("id", "ID")).format(now)
    }

    private fun chipButton(label: String, bgColor: Int, fgColor: Int, radius: Int): TextView = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        setTextColor(fgColor)
        background = rounded(bgColor, radius)
        isClickable = true
        isFocusable = true
    }

    private fun rounded(color: Int, radiusDp: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun EditText.hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(windowToken, 0)
    }

    private inner class AppGridAdapter(private val isStartMenu: Boolean) : BaseAdapter() {
        override fun getCount(): Int = if (isStartMenu) visibleApps(true).size else pinnedApps().size
        override fun getItem(position: Int): LaunchableApp = if (isStartMenu) visibleApps(true)[position] else pinnedApps()[position]
        override fun getItemId(position: Int): Long = getItem(position).packageName.hashCode().toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val app = getItem(position)
            val cell = (convertView as? LinearLayout) ?: LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(4), dp(7), dp(4), dp(7))
                background = if (isStartMenu) rounded(cardSurface, 17) else ColorDrawable(Color.TRANSPARENT)
                layoutParams = AbsListView.LayoutParams(-1, dp(if (isStartMenu) 88 else 94))
                addView(ImageView(context).apply {
                    id = android.R.id.icon
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    contentDescription = null
                }, LinearLayout.LayoutParams(dp(if (isStartMenu) 42 else 46), dp(if (isStartMenu) 42 else 46)))
                addView(TextView(context).apply {
                    id = android.R.id.text1
                    textSize = 10.5f
                    setTextColor(primaryText)
                    gravity = Gravity.CENTER
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(0, dp(5), 0, 0)
                }, LinearLayout.LayoutParams(-1, -2))
            }
            cell.findViewById<ImageView>(android.R.id.icon).setImageDrawable(app.icon)
            cell.findViewById<TextView>(android.R.id.text1).text = app.label
            cell.contentDescription = app.label
            cell.setOnClickListener { launch(app) }
            cell.setOnLongClickListener {
                showAppMenu(app)
                true
            }
            return cell
        }
    }

    companion object {
        private const val PREFS_NAME = "saku_dock_settings"
        private const val KEY_PINNED = "pinned_packages_order"
        private const val KEY_OVERLAY_ENABLED = "overlay_enabled"
        private const val KEY_OVERLAY_PENDING = "overlay_pending"
        const val EXTRA_OPEN_START = "com.sakuhome.launcher.OPEN_START"
        const val EXTRA_OPEN_SETTINGS = "com.sakuhome.launcher.OPEN_SETTINGS"
    }
}

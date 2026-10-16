package com.sakuhome.launcher

import android.app.Activity
import android.app.ActivityOptions
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.widget.SeekBar
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
    private lateinit var widgetStrip: LinearLayout
    private lateinit var appWidgetHost: AppWidgetHost
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

    private val isDarkTheme: Boolean get() = preferences.getBoolean(KEY_DARK_THEME, true)
    private val backgroundTop: Int get() = if (isDarkTheme) Color.rgb(12, 17, 29) else Color.rgb(235, 241, 250)
    private val backgroundBottom: Int get() = if (isDarkTheme) Color.rgb(24, 35, 56) else Color.rgb(211, 224, 242)
    private val dockSurface: Int get() = if (isDarkTheme) Color.rgb(25, 34, 51) else Color.rgb(246, 249, 255)
    private val cardSurface: Int get() = if (isDarkTheme) Color.rgb(39, 51, 72) else Color.rgb(229, 237, 249)
    private val primaryText: Int get() = if (isDarkTheme) Color.rgb(245, 248, 255) else Color.rgb(25, 37, 57)
    private val secondaryText: Int get() = if (isDarkTheme) Color.rgb(159, 177, 202) else Color.rgb(82, 101, 130)
    private val accent: Int get() = preferences.getInt(KEY_ACCENT, Color.rgb(110, 151, 245))
    private val hideLabels: Boolean get() = preferences.getBoolean(KEY_HIDE_LABELS, false)
    private val liquidGlass: Boolean get() = preferences.getBoolean(KEY_LIQUID_GLASS, true)
    private val iconSizeDp: Int get() = preferences.getInt(KEY_ICON_SIZE, 48)

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
        appWidgetHost = AppWidgetHost(this, APP_WIDGET_HOST_ID)
        apps = loadApps()
        buildScreen()
        restoreWidgets()
        if (intent?.getBooleanExtra(EXTRA_OPEN_START, false) == true) showStartPanel()
        if (intent?.getBooleanExtra(EXTRA_OPEN_SETTINGS, false) == true) rootFrame.post { showSettings() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_START, false)) showStartPanel()
        if (intent.getBooleanExtra(EXTRA_OPEN_SETTINGS, false)) showSettings()
    }

    @Deprecated("Deprecated in Android; retained for broad document-provider compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_WIDGET_PICK) {
            val widgetId = data?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetHost.INVALID_APPWIDGET_ID) ?: AppWidgetHost.INVALID_APPWIDGET_ID
            if (resultCode == RESULT_OK && widgetId != AppWidgetHost.INVALID_APPWIDGET_ID) {
                val info = AppWidgetManager.getInstance(this).getAppWidgetInfo(widgetId)
                if (info != null) addWidgetView(widgetId, info) else appWidgetHost.deleteAppWidgetId(widgetId)
            } else if (widgetId != AppWidgetHost.INVALID_APPWIDGET_ID) {
                appWidgetHost.deleteAppWidgetId(widgetId)
            }
            return
        }
        if (requestCode != REQUEST_WALLPAPER || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            preferences.edit().putString(KEY_WALLPAPER_URI, uri.toString()).apply()
            buildScreen()
            Toast.makeText(this, "Wallpaper diperbarui", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(this, "Wallpaper tidak dapat digunakan", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        if (!::preferences.isInitialized) return
        try { appWidgetHost.startListening() } catch (_: Exception) { }
        apps = loadApps()
        if (::rootFrame.isInitialized) {
            rebuildPinnedViews()
            updateClock()
        }
        reconcileOverlay()
    }

    override fun onPause() {
        super.onPause()
        try { appWidgetHost.stopListening() } catch (_: Exception) { }
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
        loadWallpaper()?.let { bitmap ->
            rootFrame.addView(ImageView(this).apply {
                setImageBitmap(bitmap)
                scaleType = ImageView.ScaleType.CENTER_CROP
            }, FrameLayout.LayoutParams(-1, -1))
            rootFrame.addView(View(this).apply {
                setBackgroundColor(if (isDarkTheme) 0x550D1420 else 0x260D1420)
            }, FrameLayout.LayoutParams(-1, -1))
        }
        val shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(15), dp(18), dp(12))
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(3), dp(5))
        }
        val brand = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        brand.addView(TextView(this).apply {
            text = "SAKU DESKTOP"
            textSize = 10f
            letterSpacing = 0.22f
            setTextColor(accent)
        })
        clockText = TextView(this).apply {
            textSize = 32f
            setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
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
            text = "WORKSPACE  /  APLIKASI FAVORIT"
            textSize = 10f
            letterSpacing = 0.12f
            setTextColor(secondaryText)
            setPadding(dp(5), dp(13), dp(5), dp(10))
        }
        shell.addView(workspaceTitle)

        // Spotlight-style app search, inspired by iPhone home screen.
        val homeSearch = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(14), 0)
            background = rounded(if (isDarkTheme) Color.argb(185, 43, 55, 75) else Color.argb(225, 255, 255, 255), 22)
            isClickable = true
            isFocusable = true
            contentDescription = "Cari aplikasi"
            addView(TextView(this@MainActivity).apply {
                text = "⌕"
                textSize = 24f
                setTextColor(secondaryText)
            })
            addView(TextView(this@MainActivity).apply {
                text = "Cari aplikasi"
                textSize = 14f
                setTextColor(secondaryText)
                setPadding(dp(8), 0, 0, 0)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(TextView(this@MainActivity).apply {
                text = "⌘"
                textSize = 16f
                setTextColor(secondaryText)
            })
            setOnClickListener {
                showStartPanel()
                startSearchField.requestFocus()
                val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                imm.showSoftInput(startSearchField, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }
        }
        shell.addView(homeSearch, LinearLayout.LayoutParams(-1, dp(44)).apply {
            bottomMargin = dp(12)
        })

        widgetStrip = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(2), 0, dp(4))
        }
        shell.addView(widgetStrip, LinearLayout.LayoutParams(-1, -2))
        desktopGrid = GridView(this).apply {
            numColumns = if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) 6 else 4
            verticalSpacing = dp(13)
            horizontalSpacing = dp(9)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            setSelector(ColorDrawable(Color.TRANSPARENT))
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            setPadding(0, dp(4), 0, dp(4))
        }
        pinnedAdapter = AppGridAdapter(isStartMenu = false)
        desktopGrid.adapter = pinnedAdapter
        shell.addView(desktopGrid, LinearLayout.LayoutParams(-1, 0, 1f))

        if (!preferences.getBoolean(KEY_HIDE_TASKBAR, false)) {
            shell.addView(buildDock(), LinearLayout.LayoutParams(-1, dp(66)).apply {
                topMargin = dp(10)
            })
        }
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
            setPadding(dp(8), dp(7), dp(8), dp(7))
            background = if (liquidGlass) glassSurface(25) else rounded(dockSurface, 25)
            elevation = dp(14).toFloat()
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
            icons.addView(dockIcon(app, index), LinearLayout.LayoutParams(dp(iconSizeDp), -1))
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
            background = rounded(if (isDarkTheme) Color.rgb(20, 29, 45) else Color.rgb(248, 251, 255), 28)
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
        val hidden = preferences.getStringSet(KEY_HIDDEN_APPS, emptySet()) ?: emptySet()
        val base = (if (isStartMenu && !showingAllApps) pinnedApps() else apps).filterNot { hidden.contains(it.packageName) }
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
        // When the bottom taskbar is hidden, there is no dock view to rebuild.
        if (preferences.getBoolean(KEY_HIDE_TASKBAR, false)) return
        // Rebuild only the dock; keep the open Start panel and its scroll position intact.
        val shell = rootFrame.getChildAt(0) as? LinearLayout ?: return
        val dockIndex = shell.childCount - 1
        shell.removeViewAt(dockIndex)
        shell.addView(buildDock(), LinearLayout.LayoutParams(-1, dp(66)).apply { topMargin = dp(10) })
    }

    private fun launch(app: LaunchableApp, asFreeform: Boolean = true) {
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
        val options = arrayOf(
            "Buka sebagai jendela",
            "Buka layar penuh",
            pinAction,
            "Info aplikasi",
            "Buka di Play Store",
            "Copot pemasangan"
        )
        AlertDialog.Builder(this).setTitle(app.label).setItems(options) { _, which ->
            when (which) {
                0 -> launch(app, asFreeform = true)
                1 -> launch(app, asFreeform = false)
                2 -> togglePinned(app)
                3 -> openAppInfo(app)
                4 -> openPlayStore(app)
                5 -> uninstallApp(app)
            }
        }.setNegativeButton("Batal", null).show()
    }

    private fun openAppInfo(app: LaunchableApp) {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.packageName}")))
        } catch (_: Exception) {
            Toast.makeText(this, "Info aplikasi tidak tersedia", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openPlayStore(app: LaunchableApp) {
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${app.packageName}"))
        try {
            startActivity(market)
        } catch (_: Exception) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=${app.packageName}")))
            } catch (_: Exception) {
                Toast.makeText(this, "Play Store tidak tersedia", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun uninstallApp(app: LaunchableApp) {
        if (app.packageName == packageName) {
            Toast.makeText(this, "Saku Launcher tidak dapat mencopot dirinya sendiri saat sedang berjalan", Toast.LENGTH_LONG).show()
            return
        }
        val uninstallIntent = Intent(Intent.ACTION_UNINSTALL_PACKAGE).apply {
            data = Uri.parse("package:${app.packageName}")
            putExtra(Intent.EXTRA_RETURN_RESULT, true)
        }
        try {
            if (uninstallIntent.resolveActivity(packageManager) != null) {
                startActivityForResult(uninstallIntent, REQUEST_UNINSTALL)
            } else {
                val fallback = Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.packageName}"))
                startActivityForResult(fallback, REQUEST_UNINSTALL)
            }
        } catch (_: android.content.ActivityNotFoundException) {
            Toast.makeText(this, "Installer sistem tidak tersedia untuk mencopot aplikasi", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Tidak bisa membuka pencopot aplikasi: ${e.localizedMessage ?: "akses ditolak"}", Toast.LENGTH_LONG).show()
        }
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
        val themeState = if (isDarkTheme) "Gelap" else "Cerah"
        val hideTaskbarState = if (preferences.getBoolean(KEY_HIDE_TASKBAR, false)) "Tersembunyi" else "Tampil"
        val labelState = if (hideLabels) "Sembunyi" else "Tampil"
        val glassState = if (liquidGlass) "Aktif" else "Nonaktif"
        val transparency = preferences.getInt(KEY_TRANSPARENCY, 88)
        val options = arrayOf(
            "◧  Taskbar bawah · $hideTaskbarState",
            "▦  Ukuran ikon · ${iconSizeDp} dp",
            "◌  Label aplikasi · $labelState",
            "✧  Liquid Glass · $glassState",
            "◐  Transparansi · $transparency%",
            "◉  Sembunyikan aplikasi · ${hiddenPackages().size}",
            "＋  Tambahkan widget",
            "⌘  Dock mengambang · $overlayState",
            "▤  Kelola aplikasi dock · $pinnedState",
            "☾  Mode tampilan · $themeState",
            "✦  Warna aksen",
            "▧  Pilih wallpaper",
            "↺  Reset wallpaper",
            "ⓘ  Tentang & izin"
        )
        AlertDialog.Builder(this).setTitle("Saku  •  Personalisasi")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        preferences.edit().putBoolean(KEY_HIDE_TASKBAR, !preferences.getBoolean(KEY_HIDE_TASKBAR, false)).apply()
                        buildScreen()
                    }
                    1 -> chooseIconSize()
                    2 -> {
                        preferences.edit().putBoolean(KEY_HIDE_LABELS, !hideLabels).apply()
                        buildScreen()
                    }
                    3 -> {
                        preferences.edit().putBoolean(KEY_LIQUID_GLASS, !liquidGlass).apply()
                        buildScreen()
                    }
                    4 -> chooseTransparency()
                    5 -> manageHiddenApps()
                    6 -> pickWidget()
                    7 -> toggleOverlay()
                    8 -> managePins()
                    9 -> toggleTheme()
                    10 -> chooseAccent()
                    11 -> chooseWallpaper()
                    12 -> resetWallpaper()
                    13 -> showPermissionInfo()
                }
            }.setNegativeButton("Selesai", null).show()
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

    private fun chooseIconSize() {
        val sizes = intArrayOf(36, 42, 48, 54, 60)
        val labels = sizes.map { "$it dp" }.toTypedArray()
        val selected = sizes.indexOf(iconSizeDp).coerceAtLeast(2)
        AlertDialog.Builder(this).setTitle("Ukuran ikon aplikasi")
            .setSingleChoiceItems(labels, selected) { dialog, which ->
                preferences.edit().putInt(KEY_ICON_SIZE, sizes[which]).apply()
                dialog.dismiss()
                buildScreen()
            }.setNegativeButton("Batal", null).show()
    }

    private fun showWidgetInfo() {
        AlertDialog.Builder(this).setTitle("Widget Android")
            .setMessage("Widget interaktif belum aktif di versi ini. Integrasi yang benar membutuhkan AppWidgetHost, pemilih widget Android, izin binding, dan penyimpanan posisi widget di workspace.")
            .setPositiveButton("Mengerti", null).show()
    }

    private fun glassSurface(radius: Int): GradientDrawable = GradientDrawable().apply {
        setColor(if (isDarkTheme) Color.argb(158, 42, 55, 77) else Color.argb(172, 255, 255, 255))
        cornerRadius = dp(radius).toFloat()
        setStroke(dp(1), if (isDarkTheme) Color.argb(115, 220, 233, 255) else Color.argb(170, 255, 255, 255))
    }

    private fun hiddenPackages(): MutableSet<String> =
        preferences.getStringSet(KEY_HIDDEN_APPS, emptySet())?.toMutableSet() ?: mutableSetOf()

    private fun manageHiddenApps() {
        val hidden = hiddenPackages()
        val names = apps.map { it.label }.toTypedArray()
        val checked = apps.map { hidden.contains(it.packageName) }.toBooleanArray()
        AlertDialog.Builder(this).setTitle("Sembunyikan aplikasi")
            .setMultiChoiceItems(names, checked) { _, which, selected ->
                if (selected) hidden.add(apps[which].packageName) else hidden.remove(apps[which].packageName)
            }.setPositiveButton("Simpan") { _, _ ->
                preferences.edit().putStringSet(KEY_HIDDEN_APPS, hidden).apply()
                rebuildPinnedViews()
                buildScreen()
            }.setNegativeButton("Batal", null).show()
    }

    private fun chooseTransparency() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(12), dp(22), dp(4))
        }
        val current = preferences.getInt(KEY_TRANSPARENCY, 88)
        val value = TextView(this).apply {
            text = "$current%"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(primaryText)
        }
        val slider = SeekBar(this).apply {
            max = 100
            progress = current
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) { value.text = "$progress%" }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        layout.addView(value)
        layout.addView(slider)
        AlertDialog.Builder(this).setTitle("Transparansi dock").setView(layout)
            .setPositiveButton("Terapkan") { _, _ ->
                preferences.edit().putInt(KEY_TRANSPARENCY, slider.progress).apply()
                buildScreen()
                refreshFloatingDock()
            }.setNegativeButton("Batal", null).show()
    }

    private fun refreshFloatingDock() {
        if (preferences.getBoolean(KEY_OVERLAY_ENABLED, false) && Settings.canDrawOverlays(this)) {
            startService(Intent(this, FloatingDockService::class.java).setAction(FloatingDockService.ACTION_REFRESH))
        }
    }

    private fun pickWidget() {
        val id = appWidgetHost.allocateAppWidgetId()
        try {
            startActivityForResult(Intent(AppWidgetManager.ACTION_APPWIDGET_PICK).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id), REQUEST_WIDGET_PICK)
        } catch (_: Exception) {
            appWidgetHost.deleteAppWidgetId(id)
            Toast.makeText(this, "Pemilih widget Android tidak tersedia", Toast.LENGTH_LONG).show()
        }
    }

    private fun restoreWidgets() {
        if (!::widgetStrip.isInitialized) return
        val ids = preferences.getString(KEY_WIDGET_IDS, "").orEmpty().split(",").mapNotNull { it.toIntOrNull() }
        val manager = AppWidgetManager.getInstance(this)
        ids.forEach { id -> manager.getAppWidgetInfo(id)?.let { addWidgetView(id, it, false) } }
    }

    private fun addWidgetView(id: Int, info: AppWidgetProviderInfo, persist: Boolean = true) {
        if (!::widgetStrip.isInitialized) return
        val view = appWidgetHost.createView(this, id, info)
        view.setAppWidget(id, info)
        view.setOnLongClickListener {
            AlertDialog.Builder(this).setTitle(info.loadLabel(packageManager))
                .setItems(arrayOf("Hapus widget")) { _, _ ->
                    widgetStrip.removeView(view)
                    appWidgetHost.deleteAppWidgetId(id)
                    val ids = preferences.getString(KEY_WIDGET_IDS, "").orEmpty().split(",").filter { it.isNotBlank() && it != id.toString() }
                    preferences.edit().putString(KEY_WIDGET_IDS, ids.joinToString(",")).apply()
                }.setNegativeButton("Batal", null).show()
            true
        }
        widgetStrip.addView(view, LinearLayout.LayoutParams(-1, dp(150)).apply { bottomMargin = dp(8) })
        if (persist) {
            val ids = preferences.getString(KEY_WIDGET_IDS, "").orEmpty().split(",").filter { it.isNotBlank() }.toMutableList()
            if (!ids.contains(id.toString())) ids.add(id.toString())
            preferences.edit().putString(KEY_WIDGET_IDS, ids.joinToString(",")).apply()
        }
    }

    private fun showPermissionInfo() {
        AlertDialog.Builder(this).setTitle("Izin dan dukungan")
            .setMessage("Dock di atas aplikasi memakai izin overlay Android, bukan root. Menu Start, pin aplikasi, dan dock tidak membutuhkan root atau Shizuku. Jendela bebas tetap bergantung pada dukungan Android dan ROM.")
            .setPositiveButton("Mengerti", null).show()
    }

    private fun toggleTheme() {
        preferences.edit().putBoolean(KEY_DARK_THEME, !isDarkTheme).apply()
        if (preferences.getBoolean(KEY_OVERLAY_ENABLED, false) && Settings.canDrawOverlays(this)) {
            startService(Intent(this, FloatingDockService::class.java).setAction(FloatingDockService.ACTION_REFRESH))
        }
        buildScreen()
    }

    private fun chooseAccent() {
        val names = arrayOf("Biru", "Teal", "Koral", "Ungu")
        val colors = intArrayOf(
            Color.rgb(110, 151, 245), Color.rgb(43, 184, 168),
            Color.rgb(232, 112, 91), Color.rgb(165, 127, 236)
        )
        AlertDialog.Builder(this).setTitle("Warna aksen")
            .setItems(names) { _, which ->
                preferences.edit().putInt(KEY_ACCENT, colors[which]).apply()
                if (preferences.getBoolean(KEY_OVERLAY_ENABLED, false) && Settings.canDrawOverlays(this)) {
                    startService(Intent(this, FloatingDockService::class.java).setAction(FloatingDockService.ACTION_REFRESH))
                }
                buildScreen()
            }.setNegativeButton("Batal", null).show()
    }

    private fun chooseWallpaper() {
        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        try {
            startActivityForResult(picker, REQUEST_WALLPAPER)
        } catch (_: Exception) {
            Toast.makeText(this, "Pemilih gambar tidak tersedia", Toast.LENGTH_SHORT).show()
        }
    }

    private fun resetWallpaper() {
        preferences.edit().remove(KEY_WALLPAPER_URI).apply()
        buildScreen()
    }

    private fun loadWallpaper(): Bitmap? {
        val uri = preferences.getString(KEY_WALLPAPER_URI, null)?.let(Uri::parse) ?: return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            val largestSide = maxOf(bounds.outWidth, bounds.outHeight)
            if (largestSide <= 0) return null
            var sampleSize = 1
            while (largestSide / sampleSize > 2200) sampleSize *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        } catch (_: Exception) {
            null
        }
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
                setPadding(dp(5), dp(9), dp(5), dp(9))
                background = if (liquidGlass) glassSurface(19) else rounded(if (isDarkTheme) Color.argb(if (isStartMenu) 245 else 110, 45, 61, 84) else Color.argb(if (isStartMenu) 255 else 135, 250, 252, 255), 19)
                layoutParams = AbsListView.LayoutParams(-1, dp(if (isStartMenu) 94 else 102))
                addView(ImageView(context).apply {
                    id = android.R.id.icon
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    contentDescription = null
                }, LinearLayout.LayoutParams(dp(if (isStartMenu) (iconSizeDp - 6).coerceAtLeast(28) else iconSizeDp), dp(if (isStartMenu) (iconSizeDp - 6).coerceAtLeast(28) else iconSizeDp)))
                addView(TextView(context).apply {
                    id = android.R.id.text1
                    textSize = 10.5f
                    setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL))
                    setTextColor(primaryText)
                    gravity = Gravity.CENTER
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(0, dp(5), 0, 0)
                    visibility = if (hideLabels) View.GONE else View.VISIBLE
                }, LinearLayout.LayoutParams(-1, -2))
            }
            cell.findViewById<ImageView>(android.R.id.icon).setImageDrawable(app.icon)
            cell.findViewById<TextView>(android.R.id.text1).text = app.label
            cell.findViewById<TextView>(android.R.id.text1).visibility = if (hideLabels) View.GONE else View.VISIBLE
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
        private const val KEY_DARK_THEME = "dark_theme"
        private const val KEY_ACCENT = "accent_color"
        private const val KEY_WALLPAPER_URI = "wallpaper_uri"
        private const val KEY_HIDE_TASKBAR = "hide_bottom_taskbar"
        private const val KEY_HIDE_LABELS = "hide_app_labels"
        private const val KEY_LIQUID_GLASS = "liquid_glass_enabled"
        private const val KEY_ICON_SIZE = "app_icon_size_dp"
        private const val KEY_TRANSPARENCY = "dock_transparency"
        private const val KEY_HIDDEN_APPS = "hidden_app_packages"
        private const val KEY_WIDGET_IDS = "home_widget_ids"
        private const val APP_WIDGET_HOST_ID = 2096
        private const val REQUEST_WIDGET_PICK = 483
        private const val REQUEST_WALLPAPER = 481
        private const val REQUEST_UNINSTALL = 482
        const val EXTRA_OPEN_START = "com.sakuhome.launcher.OPEN_START"
        const val EXTRA_OPEN_SETTINGS = "com.sakuhome.launcher.OPEN_SETTINGS"
    }
}

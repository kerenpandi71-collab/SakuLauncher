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
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings

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
    private var drawerMode = "grid"
    private lateinit var drawerModeButton: TextView
    private var homePage = 0
    private var homePageLabel: TextView? = null

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
    private val dockWidthDp: Int get() = preferences.getInt(KEY_DOCK_WIDTH, 240).coerceIn(160, 360)
    private val dockHeightDp: Int get() = preferences.getInt(KEY_DOCK_HEIGHT, 64).coerceIn(48, 100)
    private val blurRadius: Float get() = preferences.getInt(KEY_BLUR_RADIUS, 0).toFloat()
    private val transparencyPercent: Int get() = preferences.getInt(KEY_TRANSPARENCY, 88).coerceIn(0, 100)
    private val iconPackPackage: String get() = preferences.getString(KEY_ICON_PACK, "").orEmpty()
    private val localIconFolder: String get() = preferences.getString(KEY_LOCAL_ICON_FOLDER, "").orEmpty()

    private fun enableUblockOriginIfNeeded() {
        val extensionFile = File(filesDir, "ublock_origin.xpi")
        try {
            if (!extensionFile.exists()) {
                try {
                    assets.open("extensions/ublock_origin.xpi").use { input ->
                        extensionFile.outputStream().use { output -> input.copyTo(output) }
                    }
                } catch (_: Exception) {
                    return
                }
            }

            val runtime = GeckoRuntime.create(
                applicationContext,
                GeckoRuntimeSettings.Builder()
                    .additionalInputPaths(listOf(filesDir.absolutePath))
                    .build()
            )

            runtime.webExtensionController.setEnabled("uBlock0@raymondhill.net", true)
        } catch (_: Exception) {
            // Keep the launcher functional even if the extension file is not bundled yet.
        }
    }

    private fun pinnedPackages(): MutableList<String> {
        if (!preferences.contains(KEY_PINNED)) return apps.take(5).map { it.packageName }.toMutableList()
        return preferences.getString(KEY_PINNED, "").orEmpty()
            .split('\n').filter { it.isNotBlank() }.toMutableList()
    }

    private fun homePageKey(page: Int) = "home_screen_packages_page_$page"

    private fun homePackages(page: Int = homePage): MutableList<String> {
        if (page == 0 && !preferences.contains(homePageKey(0)) && preferences.contains(KEY_HOME_APPS)) {
            return preferences.getString(KEY_HOME_APPS, "").orEmpty().split('\n').filter { it.isNotBlank() }.toMutableList()
        }
        return preferences.getString(homePageKey(page), "").orEmpty().split('\n').filter { it.isNotBlank() }.toMutableList()
    }

    private fun saveHomePackages(packages: List<String>, page: Int = homePage) {
        preferences.edit().putString(homePageKey(page), packages.distinct().joinToString("\n")).remove(KEY_HOME_APPS).apply()
    }

    private fun homePageCount(): Int = maxOf(1, preferences.getInt(KEY_HOME_PAGE_COUNT, 1))

    private fun showHomePage(page: Int) {
        homePage = page.coerceIn(0, homePageCount() - 1)
        if (::pinnedAdapter.isInitialized) pinnedAdapter.notifyDataSetChanged()
        homePageLabel?.text = "${homePage + 1} / ${homePageCount()}"
    }

    private fun addHomePage() {
        val next = homePageCount()
        preferences.edit().putInt(KEY_HOME_PAGE_COUNT, next + 1).apply()
        showHomePage(next)
        Toast.makeText(this, "Layar baru ditambahkan", Toast.LENGTH_SHORT).show()
    }

    private fun removeCurrentHomePage() {
        val count = homePageCount()
        if (count <= 1) {
            Toast.makeText(this, "Minimal harus ada satu layar utama", Toast.LENGTH_SHORT).show()
            return
        }
        val pages = (0 until count).map { homePackages(it).toMutableList() }.toMutableList()
        val removed = pages.removeAt(homePage)
        val target = if (homePage > 0) homePage - 1 else 0
        removed.forEach { if (!pages[target].contains(it)) pages[target].add(it) }
        preferences.edit().apply {
            for (page in 0 until count) remove(homePageKey(page))
            putInt(KEY_HOME_PAGE_COUNT, pages.size)
            pages.forEachIndexed { index, packages -> putString(homePageKey(index), packages.distinct().joinToString("\n")) }
        }.apply()
        showHomePage((homePage - 1).coerceAtLeast(0).coerceAtMost(pages.size - 1))
        Toast.makeText(this, "Layar dihapus; ikon dipindahkan agar tidak hilang", Toast.LENGTH_SHORT).show()
    }

    private fun showHomeLayoutOptions() {
        val options = arrayOf(
            "＋  Tambah layar utama",
            "－  Kurangi layar utama",
            "↕  Posisi ikon: ${homeIconPositionLabel()}",
            if (preferences.getBoolean(KEY_HIDE_PAGE_CONTROLS, false)) "Tampilkan kontrol layar" else "Sembunyikan kontrol layar",
            "Hapus layar ini"
        )
        AlertDialog.Builder(this).setTitle("Tata letak layar utama").setItems(options) { _, which ->
            when (which) {
                0 -> { addHomePage(); buildScreen() }
                1 -> {
                    if (homePageCount() <= 1) Toast.makeText(this, "Minimal satu layar utama", Toast.LENGTH_SHORT).show()
                    else { removeCurrentHomePage(); buildScreen() }
                }
                2 -> chooseHomeIconPosition()
                3 -> {
                    preferences.edit().putBoolean(KEY_HIDE_PAGE_CONTROLS, !preferences.getBoolean(KEY_HIDE_PAGE_CONTROLS, false)).apply()
                    buildScreen()
                }
                4 -> AlertDialog.Builder(this)
                    .setTitle("Hapus layar ${homePage + 1}?")
                    .setMessage("Ikon di layar ini akan dipindahkan ke layar sebelumnya atau layar terdekat agar tidak hilang.")
                    .setNegativeButton("Batal", null)
                    .setPositiveButton("Hapus") { _, _ -> removeCurrentHomePage(); buildScreen() }
                    .show()
            }
        }.setNegativeButton("Batal", null).show()
    }

    private fun moveHomeApp(app: LaunchableApp, direction: Int) {
        val current = homePackages()
        val index = current.indexOf(app.packageName)
        val target = index + direction
        if (index < 0 || target !in current.indices) return
        val item = current.removeAt(index)
        current.add(target, item)
        saveHomePackages(current)
        pinnedAdapter.notifyDataSetChanged()
    }

    private fun moveHomeAppToPage(app: LaunchableApp, page: Int) {
        val from = homePackages()
        if (!from.remove(app.packageName)) return
        saveHomePackages(from)
        val targetPage = page.coerceIn(0, homePageCount() - 1)
        val target = homePackages(targetPage)
        if (!target.contains(app.packageName)) target.add(app.packageName)
        saveHomePackages(target, targetPage)
        showHomePage(targetPage)
    }

    private fun homeApps(): List<LaunchableApp> {
        val byPackage = apps.associateBy { it.packageName }
        val hidden = preferences.getStringSet(KEY_HIDDEN_APPS, emptySet()) ?: emptySet()
        return homePackages().filterNot { hidden.contains(it) }.mapNotNull { byPackage[it] }
    }

    private fun pinnedApps(): List<LaunchableApp> {
        val byPackage = apps.associateBy { it.packageName }
        val hidden = preferences.getStringSet(KEY_HIDDEN_APPS, emptySet()) ?: emptySet()
        return pinnedPackages().filterNot { hidden.contains(it) }.mapNotNull { byPackage[it] }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        applyDisplayAwareOrientation()
        appWidgetHost = AppWidgetHost(this, APP_WIDGET_HOST_ID)
        apps = loadApps()
        enableUblockOriginIfNeeded()
        buildScreen()
        restoreWidgets()
        if (intent?.getBooleanExtra(EXTRA_OPEN_START, false) == true) showStartPanel()
        if (intent?.getBooleanExtra(EXTRA_OPEN_SETTINGS, false) == true) rootFrame.post { showSettings() }
    }

    /**
     * Keep the phone's main display governed by the user's orientation preference,
     * but always use the existing landscape desktop layout on a secondary display.
     * This does not rotate the device or alter the system-wide display settings.
     */
    private fun applyDisplayAwareOrientation() {
        val secondaryDisplay = if (android.os.Build.VERSION.SDK_INT >= 30) {
            display?.displayId?.let { it != android.view.Display.DEFAULT_DISPLAY } ?: false
        } else {
            windowManager.defaultDisplay.displayId != android.view.Display.DEFAULT_DISPLAY
        }
        // Saku desktop is landscape only on a non-default (secondary/virtual) display.
        // Keep ordinary phone use portrait regardless of the saved legacy orientation preference.
        requestedOrientation = if (secondaryDisplay) {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_START, false)) showStartPanel()
        if (intent.getBooleanExtra(EXTRA_OPEN_SETTINGS, false)) showSettings()
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_DOWN) {
            val ctrl = event.isCtrlPressed
            when {
                event.keyCode == android.view.KeyEvent.KEYCODE_ESCAPE && startPanel.visibility == View.VISIBLE -> {
                    hideStartPanel()
                    return true
                }
                ctrl && event.keyCode == android.view.KeyEvent.KEYCODE_SPACE -> {
                    toggleStartPanel()
                    return true
                }
                ctrl && event.keyCode == android.view.KeyEvent.KEYCODE_COMMA -> {
                    showSettings()
                    return true
                }
                ctrl && event.keyCode == android.view.KeyEvent.KEYCODE_F -> {
                    showStartPanel()
                    startSearchField.requestFocus()
                    startSearchField.post {
                        val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                        imm.showSoftInput(startSearchField, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                    }
                    return true
                }
                event.keyCode == android.view.KeyEvent.KEYCODE_F5 -> {
                    apps = loadApps()
                    rebuildPinnedViews()
                    Toast.makeText(this, "Daftar aplikasi diperbarui", Toast.LENGTH_SHORT).show()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    @Deprecated("Deprecated in Android; retained for broad document-provider compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_WIDGET_PICK) {
            val widgetId = data?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID) ?: AppWidgetManager.INVALID_APPWIDGET_ID
            if (resultCode == RESULT_OK && widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                val info = AppWidgetManager.getInstance(this).getAppWidgetInfo(widgetId)
                if (info != null) addWidgetView(widgetId, info) else appWidgetHost.deleteAppWidgetId(widgetId)
            } else if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                appWidgetHost.deleteAppWidgetId(widgetId)
            }
            return
        }
        if (requestCode == REQUEST_ICON_FOLDER) {
            if (resultCode == RESULT_OK) {
                val folderUri = data?.data
                if (folderUri != null) {
                    try { contentResolver.takePersistableUriPermission(folderUri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) { }
                    preferences.edit().putString(KEY_LOCAL_ICON_FOLDER, folderUri.toString()).putString(KEY_ICON_PACK, "").apply()
                    apps = loadApps()
                    buildScreen()
                    refreshFloatingDock()
                    Toast.makeText(this, "Folder ikon dipilih", Toast.LENGTH_LONG).show()
                }
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

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::preferences.isInitialized) buildScreen()
    }

    private fun buildScreen() {
        val landscapeLayout = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        if (landscapeLayout) {
            buildKdeLandscapeDesktop()
            return
        }
        window.statusBarColor = if (landscapeLayout) Color.TRANSPARENT else backgroundTop
        window.navigationBarColor = backgroundTop
        window.decorView.systemUiVisibility = if (landscapeLayout) 0 else View.SYSTEM_UI_FLAG_FULLSCREEN

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
                if (android.os.Build.VERSION.SDK_INT >= 31 && blurRadius > 0f) {
                    setRenderEffect(android.graphics.RenderEffect.createBlurEffect(blurRadius, blurRadius, android.graphics.Shader.TileMode.CLAMP))
                }
            }, FrameLayout.LayoutParams(-1, -1))
            rootFrame.addView(View(this).apply {
                setBackgroundColor(if (isDarkTheme) 0x550D1420 else 0x260D1420)
            }, FrameLayout.LayoutParams(-1, -1))
        }
        val shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(3), dp(14), dp(10))
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(3), dp(2))
        }
        val brand = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        clockText = TextView(this).apply {
            textSize = 17f
            setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            setTextColor(primaryText)
            includeFontPadding = false
        }
        dateText = TextView(this).apply {
            textSize = 10f
            setTextColor(secondaryText)
        }
        brand.addView(clockText, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(1) })
        brand.addView(dateText, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(0) })
        brand.visibility = if (landscapeLayout) View.GONE else View.VISIBLE
        topBar.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))

        topBar.addView(chipButton("⚙", cardSurface, primaryText, 19).apply {
            contentDescription = "Pengaturan Saku Dock"
            setOnClickListener { showSettings() }
        }, LinearLayout.LayoutParams(dp(46), dp(46)))
        topBar.visibility = if (landscapeLayout) View.GONE else View.VISIBLE
        shell.addView(topBar, LinearLayout.LayoutParams(-1, -2))
        val statusStrip = buildStatusStrip()
        statusStrip.visibility = if (landscapeLayout) View.GONE else View.VISIBLE
        shell.addView(statusStrip, LinearLayout.LayoutParams(-1, dp(34)).apply {
            topMargin = dp(1)
            bottomMargin = dp(1)
        })

        val pageControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(5), dp(if (landscapeLayout) 5 else 13), dp(5), dp(if (landscapeLayout) 5 else 10))
            visibility = if (preferences.getBoolean(KEY_HIDE_PAGE_CONTROLS, false)) View.GONE else View.VISIBLE
        }
        pageControls.addView(TextView(this).apply {
            text = "DESKTOP"
            textSize = 10f
            letterSpacing = 0.12f
            setTextColor(secondaryText)
            visibility = if (landscapeLayout) View.GONE else View.VISIBLE
        }, LinearLayout.LayoutParams(0, -2, 1f))
        pageControls.addView(chipButton("‹", cardSurface, primaryText, 12).apply {
            setOnClickListener { showHomePage(homePage - 1) }
        }, LinearLayout.LayoutParams(dp(34), dp(30)))
        homePageLabel = TextView(this).apply {
            text = "${homePage + 1} / ${homePageCount()}"
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(primaryText)
        }
        pageControls.addView(homePageLabel, LinearLayout.LayoutParams(dp(54), -2))
        pageControls.addView(chipButton("›", cardSurface, primaryText, 12).apply {
            setOnClickListener { showHomePage(homePage + 1) }
        }, LinearLayout.LayoutParams(dp(34), dp(30)))
        pageControls.addView(chipButton("+", accent, Color.WHITE, 12).apply {
            contentDescription = "Buat layar utama baru"
            setOnClickListener { addHomePage(); buildScreen() }
        }, LinearLayout.LayoutParams(dp(34), dp(30)).apply { marginStart = dp(6) })
        pageControls.addView(chipButton("−", cardSurface, primaryText, 12).apply {
            contentDescription = "Hapus layar utama ini"
            setOnClickListener { showHomeLayoutOptions() }
        }, LinearLayout.LayoutParams(dp(34), dp(30)).apply { marginStart = dp(5) })
        pageControls.addView(chipButton("⋯", cardSurface, primaryText, 12).apply {
            contentDescription = "Opsi tata letak layar utama"
            setOnClickListener { showHomeLayoutOptions() }
        }, LinearLayout.LayoutParams(dp(34), dp(30)).apply { marginStart = dp(5) })
        if (landscapeLayout) pageControls.visibility = View.GONE
        shell.addView(pageControls)

        // App search removed from the home screen as requested.
        widgetStrip = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(2), 0, dp(2))
            clipToPadding = false
            clipChildren = false
        }
        shell.addView(widgetStrip, LinearLayout.LayoutParams(-1, -2))
        desktopGrid = GridView(this).apply {
            numColumns = preferences.getInt(KEY_HOME_GRID_COLUMNS, if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) 6 else 5)
            verticalSpacing = dp(10)
            horizontalSpacing = dp(8)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            setSelector(ColorDrawable(Color.TRANSPARENT))
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            clipChildren = false
            // Fixed compact layout: no configurable vertical icon-position mode.
            setPadding(dp(2), dp(40), dp(2), dp(12))
        }
        pinnedAdapter = AppGridAdapter(isStartMenu = false)
        desktopGrid.adapter = pinnedAdapter
        desktopGrid.setOnLongClickListener { false }
        desktopGrid.setOnDragListener { grid, event ->
            when (event.action) {
                android.view.DragEvent.ACTION_DRAG_STARTED -> event.clipDescription?.hasMimeType("text/plain") == true
                android.view.DragEvent.ACTION_DRAG_ENTERED -> { grid.alpha = 0.88f; true }
                android.view.DragEvent.ACTION_DRAG_EXITED -> { grid.alpha = 1f; true }
                android.view.DragEvent.ACTION_DROP -> {
                    grid.alpha = 1f
                    val sourcePackage = event.localState as? String ?: event.clipData?.getItemAt(0)?.text?.toString()
                    val targetPosition = (grid as GridView).pointToPosition(event.x.toInt(), event.y.toInt())
                    val order = homePackages()
                    val from = order.indexOf(sourcePackage)
                    val targetPackage = pinnedAdapter.getItemOrNull(targetPosition)?.packageName
                    val targetIndex = if (targetPackage != null) order.indexOf(targetPackage) else -1
                    if (from >= 0) {
                        val moving = order.removeAt(from)
                        val insertAt = if (targetIndex >= 0) targetIndex.coerceAtMost(order.size) else order.size
                        order.add(insertAt, moving)
                        saveHomePackages(order)
                        pinnedAdapter.notifyDataSetChanged()
                    }
                    true
                }
                android.view.DragEvent.ACTION_DRAG_ENDED -> { grid.alpha = 1f; true }
                else -> true
            }
        }
        rootFrame.addView(shell)
    }
}

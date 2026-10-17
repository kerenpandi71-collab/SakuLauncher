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
    private val freeformDefault: Boolean get() = preferences.getBoolean(KEY_FREEFORM_DEFAULT, false)

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
        requestedOrientation = preferences.getInt("launcher_orientation", android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
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

    private fun buildScreen() {
        window.statusBarColor = backgroundTop
        window.navigationBarColor = backgroundTop
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN

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
        topBar.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))

        topBar.addView(chipButton("⚙", cardSurface, primaryText, 19).apply {
            contentDescription = "Pengaturan Saku Dock"
            setOnClickListener { showSettings() }
        }, LinearLayout.LayoutParams(dp(46), dp(46)))
        shell.addView(topBar, LinearLayout.LayoutParams(-1, -2))
        shell.addView(buildStatusStrip(), LinearLayout.LayoutParams(-1, dp(34)).apply {
            topMargin = dp(1)
            bottomMargin = dp(1)
        })

        val pageControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(5), dp(13), dp(5), dp(10))
            visibility = if (preferences.getBoolean(KEY_HIDE_PAGE_CONTROLS, false)) View.GONE else View.VISIBLE
        }
        pageControls.addView(TextView(this).apply {
            text = "LAYAR UTAMA"
            textSize = 10f
            letterSpacing = 0.12f
            setTextColor(secondaryText)
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
        var downX = 0f
        var downY = 0f
        desktopGrid.setOnTouchListener { _, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> { downX = event.x; downY = event.y; false }
                android.view.MotionEvent.ACTION_UP -> {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    if (dy < -dp(64) && kotlin.math.abs(dy) > kotlin.math.abs(dx) * 1.2f) {
                        showStartPanel()
                        true
                    } else if (dy > dp(64) && kotlin.math.abs(dy) > kotlin.math.abs(dx) * 1.2f && startPanel.visibility == View.VISIBLE) {
                        hideStartPanel()
                        true
                    } else if (kotlin.math.abs(dx) > dp(72)) {
                        val direction = if (dx < 0) -1f else 1f
                        desktopGrid.animate().cancel()
                        desktopGrid.animate().alpha(0f).translationX(-direction * dp(30).toFloat())
                            .setDuration(115L)
                            .setInterpolator(android.view.animation.AccelerateInterpolator())
                            .withEndAction {
                                showHomePage(homePage + if (dx < 0) 1 else -1)
                                desktopGrid.translationX = direction * dp(30).toFloat()
                                desktopGrid.animate().alpha(1f).translationX(0f)
                                    .setDuration(235L)
                                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                                    .start()
                            }.start()
                        true
                    } else false
                }
                else -> false
            }
        }
        shell.addView(desktopGrid, LinearLayout.LayoutParams(-1, 0, 1f))

        if (!preferences.getBoolean(KEY_HIDE_TASKBAR, false)) {
            val availableWidthDp = ((resources.displayMetrics.widthPixels / resources.displayMetrics.density).toInt() - 28).coerceAtLeast(160)
            shell.addView(buildDock(), LinearLayout.LayoutParams(dp(dockWidthDp.coerceAtMost(availableWidthDp)), dp(dockHeightDp)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
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

    private fun buildStatusStrip(): View {
        val strip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(12), 0)
            background = if (liquidGlass) glassSurface(18) else rounded(cardSurface, 18)
            elevation = dp(4).toFloat()
        }
        val now = Date()
        val battery = registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100) ?: 100
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else null

        strip.addView(TextView(this).apply {
            text = "☁  18°"
            textSize = 11f
            setTextColor(secondaryText)
            gravity = Gravity.CENTER_VERTICAL
            contentDescription = "Widget cuaca dekoratif"
        }, LinearLayout.LayoutParams(0, -1, 1f))
        strip.addView(TextView(this).apply {
            text = "▰  ${percent?.let { "$it%" } ?: "--%"}"
            textSize = 11f
            setTextColor(primaryText)
            gravity = Gravity.CENTER
            contentDescription = "Persentase baterai"
            setPadding(dp(8), 0, dp(10), 0)
        }, LinearLayout.LayoutParams(-2, -1))
        strip.addView(View(this).apply {
            background = rounded(if (isDarkTheme) 0x55FFFFFF else 0x22000000, 1)
        }, LinearLayout.LayoutParams(dp(1), dp(18)))
        strip.addView(TextView(this).apply {
            text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
            textSize = 12f
            setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL))
            setTextColor(primaryText)
            gravity = Gravity.CENTER
            contentDescription = "Waktu"
            setPadding(dp(10), 0, dp(5), 0)
        }, LinearLayout.LayoutParams(-2, -1))
        strip.addView(TextView(this).apply {
            text = SimpleDateFormat("EEE, d MMM", Locale("id", "ID")).format(now)
            textSize = 10f
            setTextColor(secondaryText)
            gravity = Gravity.CENTER
            contentDescription = "Tanggal"
            setPadding(dp(5), 0, 0, 0)
        }, LinearLayout.LayoutParams(-2, -1))
        return strip
    }

    private fun toggleNativeQuickSettings() {
        val disable = !preferences.getBoolean(KEY_NATIVE_QS_DISABLED, false)
        Thread {
            val output = runRootCommand(if (disable) "cmd statusbar disable 0x00010000; echo QS_EXIT:$?" else "cmd statusbar enable 0x00010000; echo QS_EXIT:$?")
            val success = output.contains("QS_EXIT:0")
            runOnUiThread {
                if (success) {
                    preferences.edit().putBoolean(KEY_NATIVE_QS_DISABLED, disable).apply()
                    Toast.makeText(this, if (disable) "Ekspansi panel Android dinonaktifkan. menu Saku tersedia dari layar utama." else "Panel Android dipulihkan.", Toast.LENGTH_LONG).show()
                    buildScreen()
                } else {
                    Toast.makeText(this, "Perintah ditolak ROM. Pulihkan manual jika perlu: su -c cmd statusbar enable 0x00010000", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun buildDock(): View {
        val effectiveWidth = dockWidthDp.coerceAtMost(
            ((resources.displayMetrics.widthPixels / resources.displayMetrics.density).toInt() - 28).coerceAtLeast(160)
        )
        val effectiveHeight = dockHeightDp
        val dock = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(3), dp(8), dp(3))
            background = if (liquidGlass) glassSurface(24) else rounded(dockSurface, 24)
            elevation = dp(14).toFloat()
            minimumWidth = dp(effectiveWidth)
            minimumHeight = dp(effectiveHeight)
        }
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val icons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(7), 0, dp(7), 0)
        }
        pinnedApps().take(7).forEachIndexed { index, app ->
            // Ikon menyatu dengan dock: tanpa bubble individual atau sekat di sekelilingnya.
            icons.addView(dockIcon(app, index).apply {
                background = ColorDrawable(Color.TRANSPARENT)
                setPadding(dp(3), dp(4), dp(3), dp(4))
            }, LinearLayout.LayoutParams(dp((iconSizeDp).coerceAtMost(effectiveHeight - 14).coerceAtLeast(24) + 2), -1).apply {
                marginStart = dp(2)
                marginEnd = dp(2)
            })
        }
        scroll.addView(icons, ViewGroup.LayoutParams(-2, -1))
        dock.addView(scroll, LinearLayout.LayoutParams(-1, -1))
        dock.layoutParams = LinearLayout.LayoutParams(dp(effectiveWidth), dp(effectiveHeight)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        }
        return dock
    }

    private fun dockIcon(app: LaunchableApp, index: Int): View = ImageView(this).apply {
        setImageDrawable(app.icon)
        scaleType = ImageView.ScaleType.FIT_CENTER
        setPadding(dp(7), dp(6), dp(7), dp(6))
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
            setPadding(dp(17), dp(16), dp(17), dp(13))
            background = if (liquidGlass) glassSurface(30) else rounded(if (isDarkTheme) Color.rgb(24, 31, 47) else Color.rgb(246, 248, 253), 30)
            elevation = dp(20).toFloat()
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val heading = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        heading.addView(TextView(this).apply {
            text = "Aplikasi"
            textSize = 20f
            setTextColor(primaryText)
            setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD))
            letterSpacing = -0.02f
        })
        header.addView(heading, LinearLayout.LayoutParams(0, -2, 1f))
        drawerMode = preferences.getString("drawer_mode", "grid") ?: "grid"
        drawerModeButton = chipButton(if (drawerMode == "list") "▦" else "☷", cardSurface, primaryText, 15).apply {
            textSize = 18f
            contentDescription = "Ubah tampilan aplikasi"
            setOnClickListener {
                drawerMode = if (drawerMode == "grid") "list" else "grid"
                preferences.edit().putString("drawer_mode", drawerMode).apply()
                if (::startGrid.isInitialized) {
                    startGrid.numColumns = if (drawerMode == "list") 1 else preferences.getInt(KEY_DRAWER_GRID_COLUMNS, 4)
                    startGrid.verticalSpacing = dp(if (drawerMode == "list") 3 else 10)
                    startAdapter.notifyDataSetChanged()
                }
                drawerModeButton.text = if (drawerMode == "list") "▦" else "☷"
            }
        }
        header.addView(drawerModeButton, LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginEnd = dp(6) })
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
            background = if (liquidGlass) glassSurface(16) else rounded(cardSurface, 16)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            contentDescription = "Cari aplikasi"
            compoundDrawablePadding = dp(8)
        }
        panel.addView(startSearchField, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(13) })

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
            numColumns = if (drawerMode == "list") 1 else preferences.getInt(KEY_DRAWER_GRID_COLUMNS, if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) 5 else 4)
            verticalSpacing = dp(if (drawerMode == "list") 3 else 10)
            horizontalSpacing = dp(8)
            setPadding(dp(2), dp(4), dp(2), dp(8))
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            setSelector(ColorDrawable(Color.TRANSPARENT))
            isVerticalScrollBarEnabled = false
            isSmoothScrollbarEnabled = true
            isScrollingCacheEnabled = true
            persistentDrawingCache = ViewGroup.PERSISTENT_NO_CACHE
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
        startPanel.animate().cancel()
        startPanel.visibility = View.VISIBLE
        startPanel.bringToFront()
        startPanel.alpha = 0f
        startPanel.translationY = dp(22).toFloat()
        startPanel.translationX = 0f
        startPanel.scaleX = 0.985f
        startPanel.scaleY = 0.985f
        startSearchField.setText("")
        refreshStartPanel()
        startPanel.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
             .setDuration(250).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
    }

    private fun hideStartPanel() {
        if (!::startPanel.isInitialized || startPanel.visibility != View.VISIBLE) return
        startPanel.animate().cancel()
        startPanel.animate().alpha(0f).translationX(dp(32).toFloat()).scaleX(0.985f).scaleY(0.985f)
            .setDuration(170).setInterpolator(android.view.animation.AccelerateInterpolator())
            .withEndAction {
                startPanel.visibility = View.GONE
                startPanel.alpha = 1f
                startPanel.translationX = 0f
                startPanel.scaleX = 1f
                startPanel.scaleY = 1f
            }.start()
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
        // Keep Start tidy: reveal apps only after the user types a query.
        return if (searchQuery.isBlank()) base else base.filter { it.label.contains(searchQuery, ignoreCase = true) }
    }

    private fun loadApps(): List<LaunchableApp> {
        val queryIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val matches: List<ResolveInfo> = packageManager.queryIntentActivities(queryIntent, PackageManager.MATCH_ALL)
        return matches.filter { it.activityInfo.packageName != packageName }
            .distinctBy { it.activityInfo.packageName }
            .map { info -> LaunchableApp(info.loadLabel(packageManager).toString(), info.activityInfo.packageName, loadThemedIcon(info)) }
            .sortedBy { it.label.lowercase(Locale.getDefault()) }
    }

    private fun componentMatches(component: String, targetPackage: String, targetActivity: String): Boolean {
        val normalized = component.replace("ComponentInfo{", "").replace("}", "")
        val pkg = normalized.substringBefore("/", "")
        val activity = normalized.substringAfter("/", "")
        return pkg == targetPackage && (activity.isBlank() || activity == targetActivity || targetActivity.endsWith(activity.removePrefix(".")))
    }

    private fun loadThemedIcon(info: ResolveInfo): android.graphics.drawable.Drawable {
        val fallback = info.loadIcon(packageManager)
        if (localIconFolder.isNotBlank()) {
            try {
                val tree = Uri.parse(localIconFolder)
                val children = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(tree, android.provider.DocumentsContract.getTreeDocumentId(tree))
                val target = info.activityInfo.packageName
                val targetActivity = info.activityInfo.name
                val names = setOf(
                    target.lowercase(Locale.ROOT),
                    target.replace(".", "_").lowercase(Locale.ROOT),
                    target.substringAfterLast(".").lowercase(Locale.ROOT),
                    targetActivity.substringAfterLast(".").lowercase(Locale.ROOT),
                    ("app_" + target.substringAfterLast(".")).lowercase(Locale.ROOT)
                )
                contentResolver.query(children, arrayOf(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID, android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(1) ?: continue
                        val baseName = name.substringBeforeLast(".").lowercase(Locale.ROOT)
                        if (baseName in names && listOf(".png", ".webp", ".jpg", ".jpeg").any { name.endsWith(it, true) }) {
                            val uri = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0))
                            contentResolver.openInputStream(uri)?.use { stream ->
                                android.graphics.BitmapFactory.decodeStream(stream)?.let { return android.graphics.drawable.BitmapDrawable(resources, it) }
                            }
                        }
                    }
                }
            } catch (_: Exception) { }
        }
        if (iconPackPackage.isBlank()) return fallback
        return try {
            val packResources = packageManager.getResourcesForApplication(iconPackPackage)
            val targetPackage = info.activityInfo.packageName
            val targetActivity = info.activityInfo.name
            val candidates = linkedSetOf<String>()
            // Common icon-pack appfilter entries use component="ComponentInfo{package/activity}" drawable="icon_name".
            val xmlId = packResources.getIdentifier("appfilter", "xml", iconPackPackage).takeIf { it != 0 }
                ?: packResources.getIdentifier("app_filter", "xml", iconPackPackage).takeIf { it != 0 }
            if (xmlId != null) {
                val parser = packResources.getXml(xmlId)
                try {
                    while (parser.eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                        if (parser.eventType == org.xmlpull.v1.XmlPullParser.START_TAG && parser.name == "item") {
                            val component = parser.getAttributeValue(null, "component").orEmpty()
                            val drawable = parser.getAttributeValue(null, "drawable").orEmpty()
                            if (drawable.isNotBlank() && componentMatches(component, targetPackage, targetActivity)) candidates.add(drawable)
                        }
                        parser.next()
                    }
                } finally { parser.close() }
            } else {
                // A few packs ship appfilter.xml in res/raw instead of res/xml.
                val rawId = packResources.getIdentifier("appfilter", "raw", iconPackPackage).takeIf { it != 0 }
                if (rawId != null) {
                    val parser = org.xmlpull.v1.XmlPullParserFactory.newInstance().newPullParser()
                    packResources.openRawResource(rawId).use { parser.setInput(it, "UTF-8");
                        while (parser.eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                            if (parser.eventType == org.xmlpull.v1.XmlPullParser.START_TAG && parser.name == "item") {
                                val component = parser.getAttributeValue(null, "component").orEmpty()
                                val drawable = parser.getAttributeValue(null, "drawable").orEmpty()
                                if (drawable.isNotBlank() && componentMatches(component, targetPackage, targetActivity)) candidates.add(drawable)
                            }
                            parser.next()
                        }
                    }
                }
            }
            val shortName = targetPackage.substringAfterLast(".")
            candidates.addAll(listOf(targetPackage.replace(".", "_"), shortName, "app_" + shortName))
            val id = candidates.firstNotNullOfOrNull { name ->
                packResources.getIdentifier(name, "drawable", iconPackPackage).takeIf { it != 0 }
                    ?: packResources.getIdentifier(name, "mipmap", iconPackPackage).takeIf { it != 0 }
            }
            if (id != null) packResources.getDrawable(id, null) else fallback
        } catch (_: Exception) { fallback }
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
        shell.addView(buildDock(), LinearLayout.LayoutParams(dockWidthDp.let { dp(it) }, dp(dockHeightDp)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(10)
        })
    }

    private fun launch(app: LaunchableApp, asFreeform: Boolean? = null) {
        hideStartPanel()
        val useFreeform = asFreeform ?: freeformDefault
        val launchIntent = packageManager.getLaunchIntentForPackage(app.packageName)
        if (launchIntent == null) {
            Toast.makeText(this, "Aplikasi tidak dapat dibuka", Toast.LENGTH_SHORT).show()
            return
        }
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            if (useFreeform) {
                // Android only honors freeform when the ROM enables multi-window/freeform.
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                val metrics = android.util.DisplayMetrics()
                val wm = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
                val screen = if (android.os.Build.VERSION.SDK_INT >= 30) wm.currentWindowMetrics.bounds else {
                    @Suppress("DEPRECATION")
                    run { wm.defaultDisplay.getRealMetrics(metrics); Rect(0, 0, metrics.widthPixels, metrics.heightPixels) }
                }
                // Use the actual display bounds (including a non-zero origin on secondary displays).
                val windowWidth = (screen.width() * 0.85f).toInt().coerceIn(1, screen.width())
                val windowHeight = (screen.height() * 0.70f).toInt().coerceIn(1, screen.height())
                val left = screen.left + (screen.width() - windowWidth) / 2
                val top = screen.top + (screen.height() - windowHeight) / 2
                val bounds = Rect(left, top, left + windowWidth, top + windowHeight)
                val options = ActivityOptions.makeBasic().setLaunchBounds(bounds)
                var modeRequested = false
                try {
                    // Hidden API on some Android builds; if blocked, launch bounds remain a best-effort hint.
                    val method = ActivityOptions::class.java.getDeclaredMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType!!)
                    method.isAccessible = true
                    method.invoke(options, 5) // WINDOWING_MODE_FREEFORM
                    modeRequested = true
                } catch (_: Exception) { /* ROM may block the hidden API. */ }
                startActivity(launchIntent, options.toBundle())
                if (!modeRequested) {
                    Toast.makeText(this, "Permintaan Freeform dikirim, tetapi ROM mungkin mengabaikan mode jendela. Aktifkan dukungan Freeform di sistem.", Toast.LENGTH_LONG).show()
                }
            } else {
                startActivity(launchIntent)
            }
        } catch (_: Exception) {
            if (useFreeform) {
                Toast.makeText(this, "Mode Freeform ditolak ROM/aplikasi; membuka aplikasi seperti biasa", Toast.LENGTH_SHORT).show()
                startActivity(launchIntent)
            } else {
                Toast.makeText(this, "Aplikasi tidak dapat dibuka pada perangkat ini", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showAppMenu(app: LaunchableApp) {
        val isHome = homePackages().contains(app.packageName)
        val homeAction = if (isHome) "Hapus dari layar utama" else "Tambahkan ke layar utama"
        val isPinned = pinnedPackages().contains(app.packageName)
        val pinAction = if (isPinned) "Lepas dari dock" else "Sematkan ke dock"
        val options = arrayOf(
            "Buka layar penuh",
            "Buka dalam jendela mengambang (Freeform)",
            homeAction,
            if (preferences.getStringSet(KEY_HIDDEN_APPS, emptySet())?.contains(app.packageName) == true) "Tampilkan aplikasi" else "Sembunyikan aplikasi",
            "Opsi tata letak layar utama",
            "Pindah posisi ke kiri",
            "Pindah posisi ke kanan",
            "Pindahkan ke layar lain",
            pinAction,
            "Info aplikasi",
            "Buka di Play Store",
            "Copot pemasangan"
        )
        AlertDialog.Builder(this).setTitle(app.label).setItems(options) { _, which ->
            when (which) {
                0 -> launch(app, asFreeform = false)
                1 -> launch(app, asFreeform = true)
                2 -> {
                    val current = homePackages()
                    if (current.contains(app.packageName)) current.remove(app.packageName) else current.add(app.packageName)
                    saveHomePackages(current)
                    pinnedAdapter.notifyDataSetChanged()
                    Toast.makeText(this, if (current.contains(app.packageName)) "Ditambahkan ke layar utama" else "Dihapus dari layar utama", Toast.LENGTH_SHORT).show()
                }
                3 -> {
                    val hidden = (preferences.getStringSet(KEY_HIDDEN_APPS, emptySet()) ?: emptySet()).toMutableSet()
                    if (hidden.contains(app.packageName)) hidden.remove(app.packageName) else hidden.add(app.packageName)
                    preferences.edit().putStringSet(KEY_HIDDEN_APPS, hidden).apply()
                    rebuildPinnedViews()
                    Toast.makeText(this, if (hidden.contains(app.packageName)) "Aplikasi disembunyikan" else "Aplikasi ditampilkan", Toast.LENGTH_SHORT).show()
                }
                4 -> showHomeLayoutOptions()
                5 -> moveHomeApp(app, -1)
                6 -> moveHomeApp(app, 1)
                7 -> {
                    val pages = (0 until homePageCount()).filter { it != homePage }.map { "Layar ${it + 1}" }.toTypedArray()
                    if (pages.isEmpty()) {
                        addHomePage()
                        moveHomeAppToPage(app, homePage)
                    } else {
                        AlertDialog.Builder(this).setTitle("Pindahkan ke layar").setItems(pages) { _, selected ->
                            val target = (0 until homePageCount()).filter { it != homePage }[selected]
                            moveHomeAppToPage(app, target)
                        }.setNegativeButton("Batal", null).show()
                    }
                }
                8 -> togglePinned(app)
                9 -> openAppInfo(app)
                10 -> openPlayStore(app)
                11 -> uninstallApp(app)
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

    private fun movePinnedApp(app: LaunchableApp, direction: Int) {
        val current = pinnedPackages()
        val index = current.indexOf(app.packageName)
        val target = index + direction
        if (index < 0 || target !in current.indices) return
        val item = current.removeAt(index)
        current.add(target, item)
        savePinned(current)
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
        val themeState = if (isDarkTheme) "Gelap" else "Cerah"
        val options = arrayOf(
            "Mode tampilan · $themeState",
            "Jumlah layar utama · ${homePageCount()}",
            "Background blur · ${preferences.getInt(KEY_BLUR_RADIUS, 0)}",
            "Transparansi · ${transparencyPercent}%",
            "Kustomisasi ikon & icon pack",
            "Ukuran dock · ${dockWidthDp} × ${dockHeightDp} dp",
            "Mode Freeform default · ${if (freeformDefault) "Aktif" else "Mati"}",
            "Pilih wallpaper",
            "Reset wallpaper",
            "Jadikan launcher default",
            "Izin tampil di atas aplikasi",
            "Tentang & izin"
        )
        AlertDialog.Builder(this).setTitle("Saku Launcher")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> toggleTheme()
                    1 -> chooseHomePageCount()
                    2 -> chooseBackgroundBlur()
                    3 -> chooseTransparency()
                    4 -> chooseIconCustomization()
                    5 -> chooseDockSize()
                    6 -> toggleFreeformDefault()
                    7 -> chooseWallpaper()
                    8 -> resetWallpaper()
                    9 -> openHomeSettings()
                    10 -> openOverlaySettings()
                    11 -> showPermissionInfo()
                }
            }.setNegativeButton("Tutup", null).show()
    }

    private fun toggleFreeformDefault() {
        val enabled = !freeformDefault
        preferences.edit().putBoolean(KEY_FREEFORM_DEFAULT, enabled).apply()
        Toast.makeText(this, if (enabled) "Freeform default aktif. Ketuk ikon untuk mencoba membuka mengambang." else "Freeform default dimatikan.", Toast.LENGTH_LONG).show()
        showSettings()
    }

    private fun openHomeSettings() {
        val intent = Intent(Settings.ACTION_HOME_SETTINGS)
        try {
            startActivity(intent)
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
            } catch (_: Exception) {
                Toast.makeText(this, "Buka Pengaturan Android > Aplikasi default > Aplikasi layar utama", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun openOverlaySettings() {
        if (Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Izin tampil di atas aplikasi sudah aktif", Toast.LENGTH_SHORT).show()
        } else {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            } catch (_: Exception) {
                Toast.makeText(this, "Aktifkan izin tampil di atas aplikasi dari Pengaturan Android", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun chooseLauncherOrientation() {
        val options = arrayOf("Ikuti sensor", "Kunci potret", "Kunci landscape")
        val values = intArrayOf(
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        )
        val current = preferences.getInt("launcher_orientation", android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
        val selected = values.indexOf(current).coerceAtLeast(0)
        AlertDialog.Builder(this).setTitle("Orientasi Saku Launcher")
            .setSingleChoiceItems(options, selected) { dialog, which ->
                val value = values[which]
                preferences.edit().putInt("launcher_orientation", value).apply()
                requestedOrientation = value
                dialog.dismiss()
                Toast.makeText(this, "Orientasi launcher diperbarui", Toast.LENGTH_SHORT).show()
            }.setNegativeButton("Batal", null).show()
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

    private fun chooseDrawerGrid() {
        val options = arrayOf("3 kolom · ikon besar", "4 kolom · seimbang", "5 kolom · lebih banyak aplikasi", "6 kolom · padat")
        val values = intArrayOf(3, 4, 5, 6)
        val current = preferences.getInt(KEY_DRAWER_GRID_COLUMNS, if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) 5 else 4)
        AlertDialog.Builder(this).setTitle("Kolom menu aplikasi")
            .setSingleChoiceItems(options, values.indexOf(current).coerceAtLeast(1)) { dialog, which ->
                preferences.edit().putInt(KEY_DRAWER_GRID_COLUMNS, values[which]).apply()
                dialog.dismiss()
                if (::startGrid.isInitialized) {
                    startGrid.numColumns = values[which]
                    startAdapter.notifyDataSetChanged()
                }
                Toast.makeText(this, "Kolom menu aplikasi diperbarui", Toast.LENGTH_SHORT).show()
            }.setNegativeButton("Batal", null).show()
    }

    private fun homeIconPositionLabel(): String = when (preferences.getString(KEY_HOME_ICON_POSITION, "middle")) {
        "top" -> "Atas"
        "bottom" -> "Bawah"
        else -> "Tengah"
    }

    private fun chooseHomePageCount() {
        val countText = TextView(this).apply {
            text = "Jumlah layar utama: " + homePageCount()
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(primaryText)
            setPadding(dp(12), dp(14), dp(12), dp(14))
        }
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(12), 0, dp(12), dp(8))
        }
        val minus = chipButton("−", cardSurface, primaryText, 14)
        val plus = chipButton("+", accent, Color.WHITE, 14)
        fun updateCount(delta: Int) {
            val next = (homePageCount() + delta).coerceIn(1, 5)
            preferences.edit().putInt(KEY_HOME_PAGE_COUNT, next).apply()
            homePage = homePage.coerceAtMost(next - 1)
            countText.text = "Jumlah layar utama: " + next
            minus.isEnabled = next > 1
            minus.alpha = if (next > 1) 1f else 0.45f
            plus.isEnabled = next < 5
            plus.alpha = if (next < 5) 1f else 0.45f
        }
        minus.setOnClickListener { updateCount(-1) }
        plus.setOnClickListener { updateCount(1) }
        controls.addView(minus, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(10) })
        controls.addView(plus, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginStart = dp(10) })
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(countText, LinearLayout.LayoutParams(-1, -2))
            addView(controls, LinearLayout.LayoutParams(-1, -2))
            addView(TextView(this@MainActivity).apply {
                text = "Batas 1–5 layar. Halaman tambahan bisa diakses dengan menggeser layar utama ke kiri atau kanan."
                textSize = 12f
                setTextColor(secondaryText)
                setPadding(dp(18), 0, dp(18), dp(10))
            })
        }
        AlertDialog.Builder(this).setTitle("Jumlah layar utama").setView(content)
            .setPositiveButton("Simpan") { _, _ -> buildScreen() }
            .setNegativeButton("Batal", null).show()
    }

    private fun chooseHomeIconPosition() {
        val labels = arrayOf("Atas", "Tengah", "Bawah")
        val values = arrayOf("top", "middle", "bottom")
        val current = preferences.getString(KEY_HOME_ICON_POSITION, "middle")
        AlertDialog.Builder(this).setTitle("Posisi ikon layar utama")
            .setMessage("Pilih area penempatan ikon aplikasi. Pengaturan ini mengubah ruang kosong di atas grid.")
            .setSingleChoiceItems(labels, values.indexOf(current).coerceAtLeast(0)) { dialog, which ->
                preferences.edit().putString(KEY_HOME_ICON_POSITION, values[which]).apply()
                dialog.dismiss()
                buildScreen()
            }.setNegativeButton("Batal", null).show()
    }

    private fun chooseHomeGrid() {
        val options = arrayOf("3 kolom · ikon besar", "4 kolom · seimbang", "5 kolom · lebih banyak aplikasi", "6 kolom · padat")
        val values = intArrayOf(3, 4, 5, 6)
        val current = preferences.getInt(KEY_HOME_GRID_COLUMNS, if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) 6 else 4)
        AlertDialog.Builder(this).setTitle("Tata letak layar utama")
            .setSingleChoiceItems(options, values.indexOf(current).coerceAtLeast(1)) { dialog, which ->
                preferences.edit().putInt(KEY_HOME_GRID_COLUMNS, values[which]).apply()
                dialog.dismiss()
                buildScreen()
                Toast.makeText(this, "Grid layar utama diperbarui", Toast.LENGTH_SHORT).show()
            }.setNegativeButton("Batal", null).show()
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
            .setMessage("Tambahkan widget asli dari aplikasi yang terpasang. Widget interaktif mengikuti provider Android dan dapat diperbesar, diperkecil, diurutkan, atau dihapus.")
            .setPositiveButton("Pilih widget") { _, _ -> pickWidget() }
            .setNegativeButton("Batal", null).show()
    }

    private fun glassSurface(radius: Int): GradientDrawable = GradientDrawable().apply {
        val alpha = (transparencyPercent * 255 / 100).coerceIn(0, 255)
        setColor(if (isDarkTheme) Color.argb(alpha, 42, 55, 77) else Color.argb(alpha, 255, 255, 255))
        cornerRadius = dp(radius).toFloat()
        setStroke(dp(1), if (isDarkTheme) Color.argb(115, 220, 233, 255) else Color.argb(170, 255, 255, 255))
    }

    private fun hiddenPackages(): MutableSet<String> =
        preferences.getStringSet(KEY_HIDDEN_APPS, emptySet())?.toMutableSet() ?: mutableSetOf()

    private data class ProcessApp(val app: LaunchableApp, val running: Boolean, val frozen: Boolean, val pid: String)

    /** Developer-options-inspired overview for running user apps. */
    private fun showProcessManager(showAll: Boolean = false) {
        val candidates = apps.filter { app ->
            try {
                val info = packageManager.getApplicationInfo(app.packageName, 0)
                (info.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0
            } catch (_: Exception) { false }
        }
        val frozenSet = preferences.getStringSet(KEY_FROZEN_APPS, emptySet()) ?: emptySet()
        Thread {
            val rows = candidates.map { app ->
                val pid = runRootCommand("pidof '${app.packageName}'").trim()
                ProcessApp(app, pid.isNotEmpty(), frozenSet.contains(app.packageName), pid)
            }.sortedWith(compareByDescending<ProcessApp> { it.running }.thenBy { it.app.label.lowercase(Locale.getDefault()) })
            runOnUiThread {
                val visibleRows = if (showAll) rows else rows.filter { it.running || it.frozen }
                val panel = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(16), dp(10), dp(16), dp(8))
                    setBackgroundColor(backgroundTop)
                }
                panel.addView(TextView(this).apply {
                    text = "● ${rows.count { it.running }} berjalan   ·   ❄ ${rows.count { it.frozen }} dibekukan"
                    textSize = 14f
                    setTextColor(accent)
                    setPadding(0, 0, 0, dp(10))
                })
                val memoryInfo = android.app.ActivityManager.MemoryInfo()
                (getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(memoryInfo)
                val totalRamMb = memoryInfo.totalMem / (1024L * 1024L)
                val availableRamMb = memoryInfo.availMem / (1024L * 1024L)
                val usedRamMb = (totalRamMb - availableRamMb).coerceAtLeast(0L)
                val usedPercent = if (totalRamMb > 0) (usedRamMb * 100 / totalRamMb).toInt() else 0
                val memoryCard = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                    background = GradientDrawable().apply {
                        setColor(cardSurface)
                        cornerRadius = dp(18).toFloat()
                    }
                }
                memoryCard.addView(TextView(this).apply {
                    text = "RAM SISTEM  ·  $usedPercent%"
                    textSize = 12f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(primaryText)
                })
                memoryCard.addView(TextView(this).apply {
                    text = String.format(Locale.getDefault(), "%.1f GB digunakan dari %.1f GB", usedRamMb / 1024.0, totalRamMb / 1024.0)
                    textSize = 12f
                    setTextColor(secondaryText)
                    setPadding(0, dp(4), 0, dp(8))
                })
                memoryCard.addView(android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 100
                    progress = usedPercent.coerceIn(0, 100)
                    progressTintList = android.content.res.ColorStateList.valueOf(accent)
                    progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.argb(65, 150, 170, 205))
                }, LinearLayout.LayoutParams(-1, dp(6)))
                panel.addView(memoryCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
                val scroll = android.widget.ScrollView(this)
                val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                if (visibleRows.isEmpty()) {
                    list.addView(TextView(this).apply {
                        text = if (showAll) "Tidak ada aplikasi pengguna yang terdeteksi." else "Belum ada aplikasi pengguna yang sedang berjalan."
                        textSize = 14f
                        setTextColor(secondaryText)
                        setPadding(dp(8), dp(24), dp(8), dp(24))
                    })
                }
                visibleRows.forEach { row ->
                    val card = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(12), dp(10), dp(8), dp(10))
                        background = GradientDrawable().apply {
                            setColor(cardSurface)
                            cornerRadius = dp(16).toFloat()
                            setStroke(dp(1), if (row.frozen) Color.rgb(90, 160, 230) else Color.argb(35, 150, 175, 210))
                        }
                    }
                    card.addView(ImageView(this).apply {
                        setImageDrawable(row.app.icon)
                        scaleType = ImageView.ScaleType.FIT_CENTER
                    }, LinearLayout.LayoutParams(dp(42), dp(42)))
                    val details = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(10), 0, dp(6), 0)
                    }
                    details.addView(TextView(this).apply {
                        text = row.app.label
                        textSize = 14f
                        setTextColor(primaryText)
                        setTypeface(null, android.graphics.Typeface.BOLD)
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    })
                    details.addView(TextView(this).apply {
                        text = "${if (row.frozen) "❄ Dibekukan" else if (row.running) "● Berjalan" else "○ Tidak berjalan"}  ·  PID ${row.pid.ifBlank { "—" }}"
                        textSize = 11f
                        setTextColor(if (row.running) accent else secondaryText)
                        maxLines = 2
                    })
                    details.addView(TextView(this).apply {
                        text = if (row.frozen) "Dibekukan" else if (row.running) "Berjalan" else "Tidak berjalan"
                        textSize = 11f
                        setTextColor(if (row.frozen) Color.rgb(90, 160, 230) else secondaryText)
                        maxLines = 1
                    })
                    card.addView(details, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    card.addView(TextView(this).apply {
                        text = "Kelola"
                        textSize = 12f
                        setTextColor(primaryText)
                        gravity = Gravity.CENTER
                        setPadding(dp(10), dp(9), dp(10), dp(9))
                        background = GradientDrawable().apply {
                            setColor(accent)
                            cornerRadius = dp(12).toFloat()
                        }
                        setOnClickListener { showProcessActions(row) }
                    })
                    val cardParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    cardParams.bottomMargin = dp(9)
                    list.addView(card, cardParams)
                }
                scroll.addView(list)
                panel.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(390)))
                AlertDialog.Builder(this)
                    .setTitle("Layanan & Proses")
                    .setView(panel)
                    .setNeutralButton(if (showAll) "Hanya berjalan" else "Semua aplikasi") { _, _ -> showProcessManager(!showAll) }
                    .setPositiveButton("Segarkan") { _, _ -> showProcessManager(showAll) }
                    .setNegativeButton("Tutup", null)
                    .show()
            }
        }.start()
    }

    private fun showProcessActions(row: ProcessApp) {
        val choices = if (row.frozen) {
            arrayOf("Lanjutkan aplikasi", "Hentikan aplikasi", "Buka aplikasi", "Detail teknis")
        } else {
            arrayOf("Freeze proses", "Hentikan aplikasi", "Buka aplikasi", "Detail teknis")
        }
        AlertDialog.Builder(this).setTitle(row.app.label)
            .setItems(choices) { _, which ->
                when (which) {
                    0 -> if (row.frozen) resumeApp(row.app) else AlertDialog.Builder(this)
                        .setTitle("Bekukan aplikasi?")
                        .setMessage("Aplikasi tidak akan merespons sampai dilanjutkan. Notifikasi dan tugas latar belakang dapat terganggu. Freeze tidak menjamin RAM langsung berkurang.")
                        .setNegativeButton("Batal", null)
                        .setPositiveButton("Freeze") { _, _ -> freezeApp(row.app) }.show()
                    1 -> AlertDialog.Builder(this).setTitle("Hentikan aplikasi?")
                        .setMessage("Force-stop dapat menghentikan notifikasi dan tugas latar belakang sampai aplikasi dibuka kembali.")
                        .setNegativeButton("Batal", null)
                        .setPositiveButton("Hentikan") { _, _ -> stopApp(row.app) }.show()
                    2 -> launch(row.app)
                    3 -> AlertDialog.Builder(this).setTitle("Detail aplikasi")
                        .setMessage("Paket: ${row.app.packageName}\nPID: ${row.pid.ifBlank { "—" }}\nStatus: ${if (row.frozen) "dibekukan (SIGSTOP)" else if (row.running) "berjalan" else "tidak berjalan"}")
                        .setPositiveButton("Tutup", null).show()
                }
            }.setNegativeButton("Kembali") { _, _ -> showProcessManager() }.show()
    }
    private fun runRootCommand(command: String): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
            output
        } catch (_: Exception) { "" }
    }

    private fun stopApp(app: LaunchableApp) {
        Thread {
            runRootCommand("am force-stop '${app.packageName}'")
            runOnUiThread {
                Toast.makeText(this, "Perintah hentikan dikirim: ${app.label}", Toast.LENGTH_SHORT).show()
                showProcessManager()
            }
        }.start()
    }

    private fun freezeApp(app: LaunchableApp) {
        Thread {
            val command = "pids=\$(pidof '${app.packageName}'); if [ -z \"\$pids\" ]; then echo NO_PROCESS; else kill -STOP \$pids && echo FREEZE_SENT || echo FREEZE_FAILED; fi"
            val result = runRootCommand(command).trim()
            val verifyCommand = "pids=\$(pidof '${app.packageName}'); for p in \$pids; do awk '/^State:/ {print \$2}' /proc/\$p/status; done"
            val states = runRootCommand(verifyCommand).trim().lines().filter { it.isNotBlank() }
            val verified = states.isNotEmpty() && states.all { it == "T" || it == "t" }
            runOnUiThread {
                val message = when {
                    result == "NO_PROCESS" -> "Proses aplikasi tidak ditemukan"
                    result == "FREEZE_FAILED" -> "Freeze gagal; periksa izin root"
                    verified -> "Freeze terverifikasi"
                    else -> "Perintah dikirim, tetapi status freeze belum terverifikasi"
                }
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                showProcessManager()
            }
        }.start()
    }
    private fun resumeApp(app: LaunchableApp) {
        Thread {
            runRootCommand("pids=\$(pidof '${app.packageName}'); [ -n \"\$pids\" ] && kill -CONT \$pids")
            runOnUiThread {
                val frozen = (preferences.getStringSet(KEY_FROZEN_APPS, emptySet()) ?: emptySet()).toMutableSet()
                frozen.remove(app.packageName)
                preferences.edit().putStringSet(KEY_FROZEN_APPS, frozen).apply()
                Toast.makeText(this, "Permintaan lanjutkan dikirim: ${app.label}", Toast.LENGTH_SHORT).show()
                showProcessManager()
            }
        }.start()
    }

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

    private fun chooseBackgroundBlur() {
        if (android.os.Build.VERSION.SDK_INT < 31) {
            AlertDialog.Builder(this).setTitle("Background blur")
                .setMessage("Efek blur wallpaper memerlukan Android 12 (API 31) atau lebih baru. Transparansi tetap bisa digunakan di versi ini.")
                .setPositiveButton("OK", null).show()
            return
        }
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(12), dp(22), dp(4)) }
        val current = preferences.getInt(KEY_BLUR_RADIUS, 0)
        val value = TextView(this).apply { text = if (current == 0) "Mati" else "$current px"; textSize = 18f; gravity = Gravity.CENTER; setTextColor(primaryText) }
        val slider = SeekBar(this).apply {
            max = 30; progress = current
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) { value.text = if (progress == 0) "Mati" else "$progress px" }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        layout.addView(value); layout.addView(slider)
        AlertDialog.Builder(this).setTitle("Background blur wallpaper").setView(layout)
            .setPositiveButton("Terapkan") { _, _ -> preferences.edit().putInt(KEY_BLUR_RADIUS, slider.progress).apply(); buildScreen() }
            .setNegativeButton("Batal", null).show()
    }

    private fun chooseIconCustomization() {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(4)) }
        val sizeLabel = TextView(this).apply { text = "Ukuran ikon: ${iconSizeDp} dp"; setTextColor(primaryText); textSize = 14f }
        val sizes = intArrayOf(32, 36, 42, 48, 54, 60, 68)
        val sizeSlider = SeekBar(this).apply {
            max = sizes.lastIndex; progress = sizes.indexOf(iconSizeDp).takeIf { it >= 0 } ?: 3
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) { sizeLabel.text = "Ukuran ikon: ${sizes[progress]} dp" }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        layout.addView(sizeLabel); layout.addView(sizeSlider)
        AlertDialog.Builder(this).setTitle("Ikon & icon pack").setView(layout)
            .setNeutralButton("Pilih icon pack") { _, _ -> chooseIconPack() }
            .setPositiveButton("Terapkan ukuran") { _, _ -> preferences.edit().putInt(KEY_ICON_SIZE, sizes[sizeSlider.progress]).apply(); apps = loadApps(); buildScreen(); refreshFloatingDock() }
            .setNegativeButton("Tutup", null).show()
    }

    private fun chooseIconPack() {
        val actions = listOf("org.adw.launcher.THEMES", "org.adw.ActivityStarter.THEMES", "com.novalauncher.THEME", "com.anddoes.launcher.THEME", "com.teslacoilsw.launcher.THEME", "com.gau.go.launcherex.theme", "com.dlto.atom.launcher.THEME", "ginlemon.smartlauncher.THEMES", "ch.deletescape.lawnchair.ICONPACK", "com.sonymobile.home.ICON_PACK", "net.oneplus.launcher.icons.ACTION_PICK_ICON")
        val detected = actions.flatMap { action ->
            try { packageManager.queryIntentActivities(Intent(action), PackageManager.MATCH_ALL).map { it.activityInfo.packageName } }
            catch (_: Exception) { emptyList() }
        }
        // Some Play Store packs (including Simply Minimal) don't advertise a standard theme intent.
        val packs = (detected + listOf("com.italiano.simplyminimal"))
            .filter { it != packageName }.distinct()
            .filter { pack -> try { packageManager.getApplicationInfo(pack, 0); true } catch (_: Exception) { false } }
            .sorted()
        val labels = (listOf("Ikon bawaan aplikasi") + packs.map { pack ->
            try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pack, 0)).toString() } catch (_: Exception) { pack }
        }).toTypedArray()
        val menu = (listOf("Ikon bawaan aplikasi") + labels.drop(1) + "Pilih folder ikon dari Download…").toTypedArray()
        AlertDialog.Builder(this).setTitle("Pilih icon pack")
            .setMessage("Icon pack terpasang yang kompatibel akan terdeteksi otomatis. Untuk Simply Minimal, pilih paketnya lalu Terapkan; jika ikon tidak berubah, gunakan folder ikon PNG/WebP yang sudah diekstrak.")
            .setItems(menu) { _, which ->
                if (which == menu.lastIndex) { chooseLocalIconFolder(); return@setItems }
                val selected = if (which == 0) "" else packs[which - 1]
                preferences.edit().putString(KEY_ICON_PACK, selected).remove(KEY_LOCAL_ICON_FOLDER).apply()
                apps = loadApps(); rebuildPinnedViews(); buildScreen(); refreshFloatingDock()
                Toast.makeText(this, if (selected.isBlank()) "Ikon bawaan dipakai" else "Icon pack dipilih: ikon yang tersedia di pack akan diterapkan", Toast.LENGTH_SHORT).show()
            }.setNegativeButton("Batal", null).show()
    }

    private fun chooseLocalIconFolder() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        try { startActivityForResult(intent, REQUEST_ICON_FOLDER) }
        catch (_: Exception) { Toast.makeText(this, "Pemilih folder tidak tersedia", Toast.LENGTH_LONG).show() }
    }

    private fun chooseDockSize() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(10), dp(22), dp(4))
        }
        val widthLabel = TextView(this).apply {
            text = "Lebar dock: ${dockWidthDp} dp"
            textSize = 14f
            setTextColor(primaryText)
        }
        val widthSlider = SeekBar(this).apply {
            max = 200
            progress = dockWidthDp - 160
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) { widthLabel.text = "Lebar dock: ${progress + 160} dp" }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        val heightLabel = TextView(this).apply {
            text = "Tinggi dock: ${dockHeightDp} dp"
            textSize = 14f
            setTextColor(primaryText)
        }
        val heightSlider = SeekBar(this).apply {
            max = 52
            progress = dockHeightDp - 48
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) { heightLabel.text = "Tinggi dock: ${progress + 48} dp" }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        layout.addView(widthLabel)
        layout.addView(widthSlider)
        layout.addView(heightLabel)
        layout.addView(heightSlider)
        AlertDialog.Builder(this).setTitle("Ukuran dock").setView(layout)
            .setPositiveButton("Terapkan") { _, _ ->
                preferences.edit()
                    .putInt(KEY_DOCK_WIDTH, widthSlider.progress + 160)
                    .putInt(KEY_DOCK_HEIGHT, heightSlider.progress + 48)
                    .apply()
                buildScreen()
                refreshFloatingDock()
                Toast.makeText(this, "Ukuran dock diterapkan", Toast.LENGTH_SHORT).show()
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
        if ((0 until widgetStrip.childCount).any { (widgetStrip.getChildAt(it).tag as? Int) == id }) return

        val sizeKey = KEY_WIDGET_HEIGHT_PREFIX + id
        var heightDp = preferences.getInt(sizeKey, 150).coerceIn(90, 280)
        val widgetView = appWidgetHost.createView(this, id, info).apply {
            setAppWidget(id, info)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            tag = id
            background = glassSurface(16)
            setPadding(dp(8), dp(6), dp(8), dp(8))
        }
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = info.loadLabel(packageManager)
            textSize = 12f
            setTextColor(secondaryText)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        controls.addView(title, LinearLayout.LayoutParams(0, dp(36), 1f))

        fun control(label: String, description: String, action: () -> Unit) {
            controls.addView(TextView(this).apply {
                text = label
                contentDescription = description
                textSize = 18f
                gravity = Gravity.CENTER
                setTextColor(primaryText)
                background = glassSurface(10)
                setPadding(dp(10), 0, dp(10), 0)
                isClickable = true
                isFocusable = true
                setOnClickListener { action() }
            }, LinearLayout.LayoutParams(-2, dp(34)).apply {
                marginStart = dp(4)
            })
        }

        fun resizeWidget(delta: Int) {
            heightDp = (heightDp + delta).coerceIn(90, 280)
            preferences.edit().putInt(sizeKey, heightDp).apply()
            widgetView.layoutParams = LinearLayout.LayoutParams(-1, dp(heightDp))
            widgetView.requestLayout()
        }

        control("−", "Perkecil widget") { resizeWidget(-40) }
        control("+", "Perbesar widget") { resizeWidget(40) }
        control("↑", "Pindahkan widget ke atas") {
            val from = widgetStrip.indexOfChild(card)
            if (from > 0) {
                widgetStrip.removeView(card)
                widgetStrip.addView(card, from - 1)
                saveWidgetOrder()
            }
        }
        control("↓", "Pindahkan widget ke bawah") {
            val from = widgetStrip.indexOfChild(card)
            if (from >= 0 && from < widgetStrip.childCount - 1) {
                widgetStrip.removeView(card)
                widgetStrip.addView(card, from + 1)
                saveWidgetOrder()
            }
        }
        control("×", "Hapus widget") {
            widgetStrip.removeView(card)
            appWidgetHost.deleteAppWidgetId(id)
            preferences.edit().remove(sizeKey).apply()
            saveWidgetOrder()
            Toast.makeText(this, "Widget dihapus", Toast.LENGTH_SHORT).show()
        }

        card.addView(controls, LinearLayout.LayoutParams(-1, -2))
        card.addView(widgetView, LinearLayout.LayoutParams(-1, dp(heightDp)))
        card.setOnLongClickListener {
            AlertDialog.Builder(this)
                .setTitle(info.loadLabel(packageManager))
                .setItems(arrayOf("Perkecil widget", "Perbesar widget", "Pindahkan ke atas", "Pindahkan ke bawah", "Hapus widget")) { _, selected ->
                    when (selected) {
                        0 -> resizeWidget(-40)
                        1 -> resizeWidget(40)
                        2 -> {
                            val from = widgetStrip.indexOfChild(card)
                            if (from > 0) { widgetStrip.removeView(card); widgetStrip.addView(card, from - 1); saveWidgetOrder() }
                        }
                        3 -> {
                            val from = widgetStrip.indexOfChild(card)
                            if (from >= 0 && from < widgetStrip.childCount - 1) { widgetStrip.removeView(card); widgetStrip.addView(card, from + 1); saveWidgetOrder() }
                        }
                        4 -> {
                            widgetStrip.removeView(card)
                            appWidgetHost.deleteAppWidgetId(id)
                            preferences.edit().remove(sizeKey).apply()
                            saveWidgetOrder()
                        }
                    }
                }.setNegativeButton("Batal", null).show()
            true
        }

        widgetStrip.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        if (persist) {
            val ids = preferences.getString(KEY_WIDGET_IDS, "").orEmpty().split(",").filter { it.isNotBlank() }.toMutableList()
            if (!ids.contains(id.toString())) ids.add(id.toString())
            preferences.edit().putString(KEY_WIDGET_IDS, ids.joinToString(",")).apply()
        }
    }

    private fun saveWidgetOrder() {
        if (!::widgetStrip.isInitialized) return
        val ids = (0 until widgetStrip.childCount).mapNotNull { widgetStrip.getChildAt(it).tag as? Int }
        preferences.edit().putString(KEY_WIDGET_IDS, ids.joinToString(",")).apply()
    }

    private fun showPermissionInfo() {
        AlertDialog.Builder(this).setTitle("Izin dan dukungan")
            .setMessage("Saku Launcher memakai peran HOME Android untuk menjadi launcher utama. Jika layar Recent menampilkan wallpaper launcher lama, buka ‘Jadikan launcher default’ lalu pilih Saku Launcher lagi. Izin overlay hanya diperlukan untuk dock mengambang di atas aplikasi lain; izin ini terpisah dari peran HOME. Android tidak menyediakan izin universal untuk mengambil alih seluruh Recent Apps atau mengganti wallpaper sistem. Semua izin dapat ditinjau dan dicabut dari Pengaturan Android.")
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
        val names = arrayOf("Biru", "Teal", "Koral", "Ungu", "Merah muda", "Amber", "Hijau", "Merah", "Indigo", "Monokrom")
        val colors = intArrayOf(
            Color.rgb(110, 151, 245), Color.rgb(43, 184, 168),
            Color.rgb(232, 112, 91), Color.rgb(165, 127, 236),
            Color.rgb(244, 114, 182), Color.rgb(245, 166, 35),
            Color.rgb(72, 187, 120), Color.rgb(239, 83, 80),
            Color.rgb(99, 102, 241), Color.rgb(148, 163, 184)
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
        // Cache the visible rows. GridView calls getCount/getItem repeatedly while scrolling;
        // rebuilding/filtering the whole app list for every recycled cell causes jank on low-RAM devices.
        private var items: List<LaunchableApp> = emptyList()

        init {
            refreshItems()
        }

        private fun refreshItems() {
            items = if (isStartMenu) visibleApps(true) else homeApps()
        }

        override fun notifyDataSetChanged() {
            refreshItems()
            super.notifyDataSetChanged()
        }

        override fun getCount(): Int = items.size
        override fun getItem(position: Int): LaunchableApp = items[position]
        override fun getItemId(position: Int): Long = items[position].packageName.hashCode().toLong()
        fun getItemOrNull(position: Int): LaunchableApp? = items.getOrNull(position)

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val app = getItem(position)
            val cell = (convertView as? LinearLayout) ?: LinearLayout(this@MainActivity).apply {
                orientation = if (isStartMenu && drawerMode == "list") LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
                gravity = if (isStartMenu && drawerMode == "list") Gravity.CENTER_VERTICAL else Gravity.CENTER
                setPadding(dp(if (isStartMenu && drawerMode == "list") 10 else 2), dp(4), dp(6), dp(4))
                // Native macOS/iOS-inspired icon layout: no surrounding bubble, card, or shadow.
                background = ColorDrawable(Color.TRANSPARENT)
                elevation = 0f
                stateListAnimator = null
                layoutParams = AbsListView.LayoutParams(-1, dp(if (isStartMenu && drawerMode == "list") 64 else if (isStartMenu) 90 else 92))
                addView(ImageView(context).apply {
                    id = android.R.id.icon
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    contentDescription = null
                }, LinearLayout.LayoutParams(dp(if (isStartMenu && drawerMode == "list") 42 else if (isStartMenu) (iconSizeDp - 6).coerceAtLeast(28) else iconSizeDp), dp(if (isStartMenu && drawerMode == "list") 42 else if (isStartMenu) (iconSizeDp - 6).coerceAtLeast(28) else iconSizeDp)).apply {
                    if (isStartMenu && drawerMode == "list") marginEnd = dp(14)
                })
                addView(TextView(context).apply {
                    id = android.R.id.text1
                    textSize = 10.5f
                    setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL))
                    setTextColor(primaryText)
                    gravity = if (isStartMenu && drawerMode == "list") Gravity.START or Gravity.CENTER_VERTICAL else Gravity.CENTER
                    textSize = if (isStartMenu && drawerMode == "list") 13f else 10.5f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(0, dp(5), 0, 0)
                    visibility = if (!isStartMenu && hideLabels) View.GONE else View.VISIBLE
                }, LinearLayout.LayoutParams(-1, -2))
            }
            cell.findViewById<ImageView>(android.R.id.icon).setImageDrawable(app.icon)
            cell.findViewById<TextView>(android.R.id.text1).text = app.label
            cell.findViewById<TextView>(android.R.id.text1).visibility = if (!isStartMenu && hideLabels) View.GONE else View.VISIBLE
            cell.contentDescription = app.label
            cell.setOnClickListener { launch(app) }
            cell.setOnLongClickListener {
                if (!isStartMenu && homePackages().contains(app.packageName)) {
                    val clip = android.content.ClipData.newPlainText("saku-app", app.packageName)
                    val shadow = View.DragShadowBuilder(cell)
                    if (android.os.Build.VERSION.SDK_INT >= 24) cell.startDragAndDrop(clip, shadow, app.packageName, 0)
                    else cell.startDrag(clip, shadow, app.packageName, 0)
                    Toast.makeText(this@MainActivity, "Mode bebas: seret ikon ke posisi yang diinginkan", Toast.LENGTH_SHORT).show()
                    true
                } else { showAppMenu(app); true }
            }
            return cell
        }
    }

    companion object {
        private const val PREFS_NAME = "saku_dock_settings"
        private const val KEY_PINNED = "pinned_packages_order"
        private const val KEY_HOME_APPS = "home_screen_packages"
        private const val KEY_HOME_PAGE_COUNT = "home_screen_page_count"
        private const val KEY_HOME_ICON_POSITION = "home_icon_position"
        private const val KEY_OVERLAY_ENABLED = "overlay_enabled"
        private const val KEY_OVERLAY_PENDING = "overlay_pending"
        private const val KEY_DARK_THEME = "dark_theme"
        private const val KEY_ACCENT = "accent_color"
        private const val KEY_WALLPAPER_URI = "wallpaper_uri"
        private const val KEY_HIDE_TASKBAR = "hide_bottom_taskbar"
        private const val KEY_HIDE_LABELS = "hide_app_labels"
        private const val KEY_LIQUID_GLASS = "liquid_glass_enabled"
        private const val KEY_ICON_SIZE = "app_icon_size_dp"
        private const val KEY_DOCK_WIDTH = "dock_width_dp"
        private const val KEY_DOCK_HEIGHT = "dock_height_dp"
        private const val KEY_LOCAL_ICON_FOLDER = "local_icon_folder_uri"
        private const val KEY_FREEFORM_DEFAULT = "freeform_default_enabled"
        private const val REQUEST_ICON_FOLDER = 7214
        private const val KEY_HIDE_PAGE_CONTROLS = "hide_home_page_controls"
        private const val KEY_NATIVE_QS_DISABLED = "native_quick_settings_disabled"
        private const val KEY_HOME_GRID_COLUMNS = "home_grid_columns"
        private const val KEY_DRAWER_GRID_COLUMNS = "drawer_grid_columns"
        private const val KEY_TRANSPARENCY = "dock_transparency"
        private const val KEY_BLUR_RADIUS = "background_blur_radius"
        private const val KEY_ICON_PACK = "custom_icon_pack_package"
        private const val KEY_HIDDEN_APPS = "hidden_app_packages"
        private const val KEY_FROZEN_APPS = "frozen_app_packages"
        private const val KEY_WIDGET_IDS = "home_widget_ids"
        private const val KEY_WIDGET_HEIGHT_PREFIX = "home_widget_height_"
        private const val APP_WIDGET_HOST_ID = 2096
        private const val REQUEST_WIDGET_PICK = 483
        private const val REQUEST_WALLPAPER = 481
        private const val REQUEST_UNINSTALL = 482
        const val EXTRA_OPEN_START = "com.sakuhome.launcher.OPEN_START"
        const val EXTRA_OPEN_SETTINGS = "com.sakuhome.launcher.OPEN_SETTINGS"
    }
}

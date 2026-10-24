package com.sakuhome.launcher

import android.app.Activity
import android.app.ActivityOptions
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.widget.SeekBar
import android.widget.ScrollView
import android.app.AlertDialog
import android.content.Intent
import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherApps
import android.os.UserHandle
import android.os.Process
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
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
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
    val icon: android.graphics.drawable.Drawable,
    val componentName: ComponentName? = null,
    val userHandle: UserHandle? = null
) {
    val profileKey: String
        get() = "${packageName}@${userHandle?.hashCode() ?: Process.myUid()}"
    val isSecondaryProfile: Boolean
        get() = userHandle != null && userHandle != Process.myUserHandle()
}

class MainActivity : Activity() {
    private lateinit var preferences: SharedPreferences
    private lateinit var rootFrame: FrameLayout
    private lateinit var shellView: LinearLayout
    private lateinit var startPanel: LinearLayout
    private lateinit var startScrim: View
    private lateinit var windowScrim: View
    private lateinit var windowStartPanel: LinearLayout
    private lateinit var windowSearchField: EditText
    private lateinit var windowStartGrid: GridView
    private lateinit var windowStartAdapter: AppGridAdapter
    private lateinit var startGrid: GridView
    private lateinit var widgetStrip: LinearLayout
    private lateinit var appWidgetHost: AppWidgetHost
    private lateinit var desktopGrid: GridView
    private lateinit var desktopCanvas: FrameLayout
    private lateinit var startSearchField: EditText
    private lateinit var drawerGridButton: TextView
    private lateinit var drawerListButton: TextView
    private lateinit var drawerLaunchModeButton: TextView
    private lateinit var clockText: TextView
    private lateinit var dateText: TextView
    private lateinit var pinnedAdapter: AppGridAdapter
    private lateinit var startAdapter: AppGridAdapter

    private var apps: List<LaunchableApp> = emptyList()
    private var searchQuery = ""
    private var windowSearchQuery = ""
    private var drawerListMode = false
    private var groupAppsByColor = false
    private var systemFpsText: TextView? = null
    private var systemFpsFrameCount = 0
    private var systemFpsWindowStartNanos = 0L
    private var systemFpsCallback: Choreographer.FrameCallback? = null
    private var homePage = 0
    private var homePageLabel: TextView? = null
    private var pageTransitionInProgress = false
    private var isDraggingHomeIcon = false
    private var panelAnimationToken = 0

    private val isDarkTheme: Boolean get() = preferences.getBoolean(KEY_DARK_THEME, true)
    private val backgroundTop: Int get() = if (isDarkTheme) Color.rgb(12, 17, 29) else Color.rgb(235, 241, 250)
    private val backgroundBottom: Int get() = if (isDarkTheme) Color.rgb(24, 35, 56) else Color.rgb(211, 224, 242)
    private val dockSurface: Int get() = if (isDarkTheme) Color.rgb(25, 34, 51) else Color.rgb(246, 249, 255)
    private val cardSurface: Int get() = if (isDarkTheme) Color.rgb(39, 51, 72) else Color.rgb(229, 237, 249)
    private val primaryText: Int get() = if (isDarkTheme) Color.rgb(245, 248, 255) else Color.rgb(25, 37, 57)
    private val secondaryText: Int get() = if (isDarkTheme) Color.rgb(159, 177, 202) else Color.rgb(82, 101, 130)
    private val accent: Int get() = preferences.getInt(KEY_ACCENT, Color.rgb(110, 151, 245))
    private val hideLabels: Boolean get() = false // Label layar utama selalu tampil pada desain desktop baru.
    private val hideDrawerLabels: Boolean get() = preferences.getBoolean(KEY_HIDE_DRAWER_LABELS, false)
    private val appFontSizeSp: Int get() = preferences.getInt(KEY_APP_FONT_SIZE, 14).coerceIn(10, 24)
    private val appFontFamily: String get() = preferences.getString(KEY_APP_FONT_FAMILY, "sans-serif") ?: "sans-serif"
    private val liquidGlass: Boolean get() = preferences.getBoolean(KEY_LIQUID_GLASS, true)
    private val iconSizeDp: Int get() = preferences.getInt(KEY_ICON_SIZE, 48)
    private val freeIconPlacement: Boolean get() = false

    private fun pinnedPackages(): MutableList<String> {
        if (!preferences.contains(KEY_PINNED)) return apps.take(5).map { it.packageName }.toMutableList()
        return preferences.getString(KEY_PINNED, "").orEmpty()
            .split('\n').filter { it.isNotBlank() }.distinct().take(MAX_DOCK_APPS).toMutableList()
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
        val targetPage = page.coerceIn(0, homePageCount() - 1)
        if (targetPage == homePage || pageTransitionInProgress) return
        if (!::desktopGrid.isInitialized || desktopGrid.width == 0) {
            applyHomePage(targetPage)
            return
        }
        pageTransitionInProgress = true
        val direction = if (targetPage > homePage) -1 else 1
        val offset = direction * dp(30)
        desktopGrid.animate().cancel()
        desktopGrid.animate().alpha(0.25f).translationX(offset.toFloat())
            .setDuration(115).setInterpolator(AccelerateInterpolator()).withEndAction {
                applyHomePage(targetPage)
                desktopGrid.translationX = -offset.toFloat()
                desktopGrid.alpha = 0f
                desktopGrid.animate().alpha(1f).translationX(0f)
                    .setDuration(235).setInterpolator(DecelerateInterpolator(1.55f)).withEndAction {
                        pageTransitionInProgress = false
                    }.start()
            }.start()
    }

    private fun applyHomePage(page: Int) {
        homePage = page.coerceIn(0, homePageCount() - 1)
        preferences.edit().putInt(KEY_LAST_HOME_PAGE, homePage).apply()
        if (::pinnedAdapter.isInitialized) pinnedAdapter.notifyDataSetChanged()
        homePageLabel?.text = "${homePage + 1} / ${homePageCount()}"
    }

    private fun addHomePage() {
        val next = homePageCount()
        preferences.edit().putInt(KEY_HOME_PAGE_COUNT, next + 1).apply()
        showHomePage(next)
        Toast.makeText(this, "Layar baru ditambahkan", Toast.LENGTH_SHORT).show()
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
        // Restore the user's landscape preference whenever the launcher process starts.
        requestedOrientation = if (preferences.getBoolean(KEY_LANDSCAPE_MODE, false))
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        drawerListMode = preferences.getBoolean(KEY_DRAWER_LIST_MODE, false)
        appWidgetHost = AppWidgetHost(this, APP_WIDGET_HOST_ID)
        homePage = preferences.getInt(KEY_LAST_HOME_PAGE, 0).coerceIn(0, homePageCount() - 1)
        apps = loadApps()
        windowStartOnlyMode = intent?.getBooleanExtra(EXTRA_OPEN_WINDOW_START, false) == true
        if (windowStartOnlyMode) {
            buildWindowStartOnlyScreen()
        } else {
            buildScreen()
            restoreWidgets()
            if (intent?.getBooleanExtra(EXTRA_OPEN_START, false) == true) showStartPanel()
            if (intent?.getBooleanExtra(EXTRA_OPEN_SETTINGS, false) == true) rootFrame.post { showSettings() }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_WINDOW_START, false)) {
            windowStartOnlyMode = true
            buildWindowStartOnlyScreen()
        } else {
            if (intent.getBooleanExtra(EXTRA_OPEN_START, false)) showStartPanel()
            if (intent.getBooleanExtra(EXTRA_OPEN_SETTINGS, false)) showSettings()
        }
    }

    @Deprecated("Deprecated in Android; retained for broad device compatibility")
    override fun onBackPressed() {
        if (::windowStartPanel.isInitialized && windowStartPanel.visibility == View.VISIBLE) hideWindowStartPanel()
        else if (::startPanel.isInitialized && startPanel.visibility == View.VISIBLE) hideStartPanel()
        else super.onBackPressed()
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
        startSystemFpsMonitor()
    }

    override fun onPause() {
        stopSystemFpsMonitor()
        super.onPause()
        // onPause can run before buildScreen() creates the drawer search field
        // (for example, when Android pauses the launcher during startup).
        try {
            if (::appWidgetHost.isInitialized) appWidgetHost.stopListening()
        } catch (_: Exception) { }
        if (::startSearchField.isInitialized) {
            try { startSearchField.hideKeyboard() } catch (_: Exception) { }
        }
    }

    private fun startSystemFpsMonitor() {
        if (systemFpsCallback != null || systemFpsText == null) return
        systemFpsFrameCount = 0
        systemFpsWindowStartNanos = 0L
        val callback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                val target = systemFpsText
                if (target == null || isFinishing || isDestroyed) {
                    systemFpsCallback = null
                    return
                }
                if (systemFpsWindowStartNanos == 0L) systemFpsWindowStartNanos = frameTimeNanos
                systemFpsFrameCount++
                val elapsed = frameTimeNanos - systemFpsWindowStartNanos
                if (elapsed >= 1_000_000_000L) {
                    val fps = (systemFpsFrameCount * 1_000_000_000.0 / elapsed).toInt()
                    val hz = if (android.os.Build.VERSION.SDK_INT >= 30) display?.refreshRate ?: 60f else windowManager.defaultDisplay.refreshRate
                    target.text = "FPS launcher ~${fps.coerceAtMost(hz.toInt() + 2)}  ·  Layar ${"%.0f".format(Locale.US, hz)} Hz"
                    systemFpsFrameCount = 0
                    systemFpsWindowStartNanos = frameTimeNanos
                }
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
        systemFpsCallback = callback
        Choreographer.getInstance().postFrameCallback(callback)
    }

    private fun stopSystemFpsMonitor() {
        systemFpsCallback?.let { Choreographer.getInstance().removeFrameCallback(it) }
        systemFpsCallback = null
        systemFpsFrameCount = 0
        systemFpsWindowStartNanos = 0L
    }

    private fun buildScreen() {
        window.statusBarColor = backgroundTop
        window.navigationBarColor = backgroundTop
        applyStatusBarPreference()

        rootFrame = FrameLayout(this)
        loadWallpaper()?.let { bitmap ->
            rootFrame.addView(ImageView(this).apply {
                setImageBitmap(bitmap)
                scaleType = ImageView.ScaleType.CENTER_CROP
            }, FrameLayout.LayoutParams(-1, -1))
        } ?: run {
            rootFrame.setBackgroundColor(backgroundBottom)
        }
        rootFrame.addView(View(this).apply {
            setBackgroundColor(if (isDarkTheme) 0x44091424 else 0x220D1420)
        }, FrameLayout.LayoutParams(-1, -1))

        val shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(5), dp(12), dp(8))
        }
        shellView = shell

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(3), dp(8), dp(3))
            background = if (liquidGlass) glassSurface(12) else rounded(backgroundTop, 12)
            elevation = dp(3).toFloat()
        }
        topBar.addView(TextView(this).apply {
            text = "⚙"
            textSize = 19f
            gravity = Gravity.CENTER
            setTextColor(primaryText)
            contentDescription = "Pengaturan Saku"
            setOnClickListener { showSettings() }
        }, LinearLayout.LayoutParams(dp(35), dp(30)))
        topBar.addView(TextView(this).apply {
            text = "▦  Aplikasi"
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(primaryText)
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { showStartPanel() }
        }, LinearLayout.LayoutParams(-2, dp(30)))
        topBar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        topBar.addView(TextView(this).apply {
            text = "◉   ᛒ   ▰"
            textSize = 13f
            setTextColor(primaryText)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-2, dp(30)).apply { marginEnd = dp(10) })
        dateText = TextView(this).apply {
            textSize = 11f
            setTextColor(primaryText)
            gravity = Gravity.CENTER_VERTICAL
        }
        clockText = TextView(this).apply {
            textSize = 11f
            setTextColor(primaryText)
            gravity = Gravity.CENTER_VERTICAL
        }
        topBar.addView(dateText, LinearLayout.LayoutParams(-2, dp(30)).apply { marginEnd = dp(8) })
        topBar.addView(clockText, LinearLayout.LayoutParams(-2, dp(30)))
        shell.addView(topBar, LinearLayout.LayoutParams(-1, dp(32)))

        val landscapeLayout = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val workspace = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(30), 0, dp(8))
            clipChildren = false
            clipToPadding = false
        }

        // Reset menu requires a deliberate long press on an empty area.
        installPanelResetLongPress(workspace)

        // The app library is a full-screen page, not a permanently docked side window.
        val rightColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
        }
        val topWidgets = LinearLayout(this).apply {
            orientation = if (landscapeLayout) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = Gravity.TOP
        }
        fun card(title: String, radius: Int = 18): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // Extra bottom room prevents the final text line from touching the glass-card edge.
            setPadding(dp(15), dp(13), dp(15), dp(18))
            background = if (liquidGlass) glassSurface(radius) else rounded(cardSurface, radius)
            // A subtle outline keeps neighboring glass cards visually distinct.
            foreground = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                cornerRadius = dp(radius).toFloat()
                setStroke(dp(1), if (isDarkTheme) 0x557F9DBF else 0x66849AB5)
            }
            elevation = dp(5).toFloat()
            addView(TextView(this@MainActivity).apply {
                text = title
                textSize = 14f
                setTextColor(primaryText)
                setTypeface(null, android.graphics.Typeface.BOLD)
            }, LinearLayout.LayoutParams(-1, -2))
        }
        val timeCard = card("◷  WAKTU")
        enablePanelDragging(timeCard, "time")
        timeCard.addView(TextView(this).apply {
            text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            textSize = 33f
            includeFontPadding = false
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(primaryText)
            setTypeface(null, android.graphics.Typeface.NORMAL)
            tag = "saku_big_clock"
        }, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(5) })
        timeCard.addView(TextView(this).apply {
            text = SimpleDateFormat("EEEE, d MMMM yyyy", Locale("id", "ID")).format(Date())
            textSize = 12f
            setTextColor(secondaryText)
            tag = "saku_big_date"
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
        if (landscapeLayout) {
            topWidgets.addView(timeCard, LinearLayout.LayoutParams(0, dp(142), 1.05f).apply { marginEnd = dp(10) })
        } else {
            // Portrait-only dashboard: the large clock belongs to the left column.
            timeCard.minimumHeight = dp(142)
            timeCard.addView(TextView(this).apply {
                text = "Langkah kecil hari ini, membawa hasil besar esok."
                textSize = 11f
                setTextColor(if (isDarkTheme) Color.rgb(205, 217, 235) else Color.rgb(63, 78, 101))
                setShadowLayer(dp(1).toFloat(), 0f, dp(1).toFloat(), if (isDarkTheme) 0x66000000 else 0x33FFFFFF)
                setPadding(0, dp(7), 0, dp(3))
            }, LinearLayout.LayoutParams(-1, -2))
        }

        // Portrait-only resizing: long-press a panel to open its height setting.
        // Landscape layout stays untouched and has no resize menu.
        fun installPortraitResize(panel: LinearLayout, key: String, defaultHeightDp: Int, minHeightDp: Int, maxHeightDp: Int) {
            if (landscapeLayout) return
            val savedHeight = preferences.getInt("portrait_widget_height_" + key, defaultHeightDp).coerceIn(minHeightDp, maxHeightDp)
            panel.minimumHeight = dp(savedHeight)
            panel.layoutParams = panel.layoutParams?.apply { height = dp(savedHeight) }

            val panelLabel = when (key) {
                "time" -> "jam sistem"
                "cuaca" -> "cuaca"
                "sistem" -> "sistem"
                "pintasan" -> "pintasan"
                "catatan" -> "catatan cepat"
                else -> key
            }
            val openHeightDialog = {
                val currentHeight = preferences.getInt("portrait_widget_height_" + key, defaultHeightDp).coerceIn(minHeightDp, maxHeightDp)
                val seek = SeekBar(this@MainActivity).apply {
                    max = maxHeightDp - minHeightDp
                    progress = currentHeight - minHeightDp
                }
                val preview = TextView(this@MainActivity).apply {
                    text = "Tinggi: " + currentHeight + " dp"
                    textSize = 14f
                    setTextColor(primaryText)
                    setPadding(0, 0, 0, dp(8))
                }
                seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                        preview.text = "Tinggi: " + (minHeightDp + progress) + " dp"
                    }
                    override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                    override fun onStopTrackingTouch(bar: SeekBar?) = Unit
                })
                val content = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(20), dp(8), dp(20), dp(4))
                    addView(preview, LinearLayout.LayoutParams(-1, -2))
                    addView(seek, LinearLayout.LayoutParams(-1, -2))
                }
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Atur tinggi $panelLabel")
                    .setMessage("Tahan panel agak lama untuk membuka pengaturan ini. Hanya berlaku di mode portrait.")
                    .setView(content)
                    .setPositiveButton("Simpan") { _, _ ->
                        preferences.edit().putInt("portrait_widget_height_" + key, minHeightDp + seek.progress).apply()
                        buildScreen()
                    }
                    .setNegativeButton("Batal", null)
                    .show()
            }
            panel.setOnLongClickListener {
                openHeightDialog()
                true
            }
            fun attachLongPressToContents(view: View) {
                if (view is EditText) return
                if (view !== panel && view !is ViewGroup) {
                    view.setOnLongClickListener {
                        openHeightDialog()
                        true
                    }
                }
                if (view is ViewGroup) {
                    for (index in 0 until view.childCount) attachLongPressToContents(view.getChildAt(index))
                }
            }
            attachLongPressToContents(panel)
        }
        val weatherCard = card("☀  CUACA")
        enablePanelDragging(weatherCard, "weather")
        weatherCard.addView(TextView(this).apply {
            text = "Cuaca lokal"
            textSize = 21f
            setTextColor(primaryText)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(13) })
        weatherCard.addView(TextView(this).apply {
            text = "Informasi cuaca belum tersedia"
            textSize = 10f
            setTextColor(secondaryText)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        if (landscapeLayout) {
            rightColumn.addView(weatherCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        } else {
            // Let the weather card wrap its content; a fixed 76dp height clipped its last line.
            topWidgets.addView(weatherCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }

        val systemCard = card("▣  Sistem")
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val memory = android.app.ActivityManager.MemoryInfo().also { activityManager.getMemoryInfo(it) }
        val totalRamGb = memory.totalMem / (1024.0 * 1024.0 * 1024.0)
        val availableRamGb = memory.availMem / (1024.0 * 1024.0 * 1024.0)
        val stat = android.os.StatFs(android.os.Environment.getDataDirectory().path)
        val totalStorage = stat.totalBytes / (1024.0 * 1024.0 * 1024.0)
        val freeStorage = stat.availableBytes / (1024.0 * 1024.0 * 1024.0)
        val batteryIntent = registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val batteryLevel = batteryIntent?.getIntExtra("level", -1) ?: -1
        val batteryScale = batteryIntent?.getIntExtra("scale", 100) ?: 100
        val batteryPercent = if (batteryLevel >= 0 && batteryScale > 0) batteryLevel * 100 / batteryScale else -1
        val refreshHz = if (android.os.Build.VERSION.SDK_INT >= 30) display?.refreshRate ?: 60f else windowManager.defaultDisplay.refreshRate
        fun systemInfoLine(initialText: String): TextView = TextView(this).apply {
            text = initialText
            textSize = 10.5f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(primaryText)
        }
        systemCard.addView(systemInfoLine("RAM tersedia  %.1f / %.1f GB".format(Locale.US, availableRamGb, totalRamGb)),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) })
        systemCard.addView(systemInfoLine("Penyimpanan terpakai  %.1f / %.1f GB".format(Locale.US, totalStorage - freeStorage, totalStorage)),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) })
        systemFpsText = systemInfoLine("FPS launcher: mengukur…  ·  Layar %.0f Hz".format(Locale.US, refreshHz))
        systemCard.addView(systemFpsText, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) })
        systemCard.addView(systemInfoLine("Android ${android.os.Build.VERSION.RELEASE}  ·  ${android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "ABI tidak diketahui"}"),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) })
        val batteryAndUptime = if (batteryPercent >= 0) "Baterai $batteryPercent%" else "Baterai tidak diketahui"
        val uptimeMinutes = android.os.SystemClock.elapsedRealtime() / 60000L
        systemCard.addView(systemInfoLine("$batteryAndUptime  ·  Aktif ${uptimeMinutes / 60}j ${uptimeMinutes % 60}m"),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) })
        topWidgets.addView(systemCard, if (landscapeLayout) LinearLayout.LayoutParams(0, dp(142), 1f) else LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        rightColumn.addView(topWidgets, LinearLayout.LayoutParams(-1, -2))
        installPortraitResize(weatherCard, "cuaca", 140, 125, 240)
        installPortraitResize(systemCard, "sistem", 155, 138, 260)

        val shortcuts = card("▦  Pintasan")
        enablePanelDragging(shortcuts, "shortcuts")
        val shortcutGrid = android.widget.GridLayout(this).apply { columnCount = 2 }
        fun shortcut(label: String, symbol: String, action: () -> Unit) {
            shortcutGrid.addView(TextView(this).apply {
                text = "$symbol  $label"
                contentDescription = label
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                includeFontPadding = true
                textSize = 14f
                setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL))
                gravity = Gravity.CENTER_VERTICAL
                setTextColor(primaryText)
                setPadding(dp(10), dp(10), dp(8), dp(10))
                background = rounded(cardSurface, 12)
                setOnClickListener { action() }
            }, android.widget.GridLayout.LayoutParams().apply {
                width = 0
                height = dp(44)
                columnSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f)
                setMargins(dp(3), dp(4), dp(3), dp(4))
            })
        }
        shortcut("Home", "⌂") { performHome() }
        shortcut("Dokumen", "▤") { openStorage(android.os.Environment.DIRECTORY_DOCUMENTS) }
        shortcut("Unduhan", "↓") { openStorage(android.os.Environment.DIRECTORY_DOWNLOADS) }
        shortcut("Sampah", "▧") { Toast.makeText(this, "Pengelola sampah belum tersedia di Android", Toast.LENGTH_SHORT).show() }
        shortcuts.addView(shortcutGrid, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        // In landscape, stack shortcuts in the right-side column instead of squeezing them
        // between the clock and system cards.
        rightColumn.addView(shortcuts, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        installPortraitResize(shortcuts, "pintasan", 190, 175, 320)

        val notes = card("🗒  Catatan Cepat")
        val noteField = EditText(this).apply {
            hint = "Tulis catatan di sini..."
            textSize = 14f
            setTextColor(primaryText)
            setHintTextColor(secondaryText)
            minLines = 3
            maxLines = 5
            gravity = Gravity.TOP
            setPadding(dp(2), dp(8), dp(2), dp(2))
            setBackgroundColor(Color.TRANSPARENT)
            setText(preferences.getString("quick_note_text", "• Fokus hari ini:\n  - Selesaikan proyek\n  - Baca buku 30 menit\n  - Olahraga ringan").orEmpty())
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    preferences.edit().putString("quick_note_text", s?.toString().orEmpty()).apply()
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        notes.addView(noteField, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) })
        // Keep quick notes visible in both orientations; the landscape column can scroll
        // vertically with its parent content rather than clipping the card.
        rightColumn.addView(notes, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        installPortraitResize(notes, "catatan", 210, 150, 360)

        if (!landscapeLayout) {
            val portraitLeftColumn = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.TOP
                clipChildren = false
                clipToPadding = false
            }
            portraitLeftColumn.addView(timeCard, LinearLayout.LayoutParams(-1, dp(156)).apply { bottomMargin = dp(8) })
            installPortraitResize(timeCard, "time", 156, 142, 240)
            // Restore the original compact Start Menu panel in the left dashboard column.
            startPanel = buildStartPanel().apply {
                elevation = dp(20).toFloat()
                background = if (liquidGlass) glassSurface(25) else rounded(if (isDarkTheme) Color.rgb(19, 32, 49) else Color.rgb(245, 249, 255), 25)
                visibility = View.GONE
            }
            portraitLeftColumn.addView(startPanel, LinearLayout.LayoutParams(-1, 0, 1f))
            workspace.addView(portraitLeftColumn, LinearLayout.LayoutParams(0, -1, 1.02f).apply { marginEnd = dp(7) })
            workspace.addView(rightColumn, LinearLayout.LayoutParams(0, -1, 0.98f))
        } else {
            workspace.addView(rightColumn, LinearLayout.LayoutParams(0, -1, 1f))
        }
        shell.addView(workspace, LinearLayout.LayoutParams(-1, 0, 1f))

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        // Home is provided by the movable overlay button; no duplicate fixed Home button here.
        if (!preferences.getBoolean(KEY_HIDE_TASKBAR, false)) {
            bottom.addView(buildDock(), LinearLayout.LayoutParams(0, dp(60), 1f).apply {
                // Keep the dock wide enough for the horizontal scroller on narrow phones.
                marginStart = dp(12)
                marginEnd = dp(12)
            })
        }
        shell.addView(bottom, LinearLayout.LayoutParams(-1, dp(60)).apply { topMargin = dp(8) })
        bottom.setOnTouchListener(startMenuSwipeListener(openOnSwipeUp = true, closeOnSwipeDown = false))
        workspace.setOnTouchListener(startMenuSwipeListener(openOnSwipeUp = true, closeOnSwipeDown = false))
        // Add the scrim before the shell so it stays behind the Start Menu.
        // A full-screen scrim above the shell caused a dead, dark strip over the menu.
        startScrim = View(this).apply {
            setBackgroundColor(0x99000000.toInt())
            visibility = View.GONE
            setOnClickListener { hideStartPanel() }
        }
        rootFrame.addView(startScrim, FrameLayout.LayoutParams(-1, -1))
        rootFrame.addView(shell, FrameLayout.LayoutParams(-1, -1))
        installPanelResetLongPress(shell)
        applyLauncherTypography(rootFrame)

        // Legacy home-grid objects remain initialized for app-management features, but the
        // new dashboard uses the permanent app library instead of the old icon canvas.
        desktopGrid = GridView(this)
        desktopCanvas = FrameLayout(this)
        widgetStrip = LinearLayout(this)
        startPanel = buildStartPanel().apply {
            elevation = dp(20).toFloat()
            background = if (liquidGlass) glassSurface(25) else rounded(if (isDarkTheme) Color.rgb(19, 32, 49) else Color.rgb(245, 249, 255), 25)
            visibility = View.GONE
        }
        if (landscapeLayout) {
            // Preserve the existing landscape App Library placement exactly.
            val screenWidth = rootFrame.width.takeIf { it > 0 }
                ?: resources.displayMetrics.widthPixels
            val panelWidth = (screenWidth * 0.34f).toInt()
                .coerceIn(dp(200), (screenWidth - dp(24)).coerceAtLeast(dp(200)))
            rootFrame.addView(startPanel, FrameLayout.LayoutParams(panelWidth, -1, Gravity.START).apply {
                topMargin = dp(44)
                bottomMargin = dp(76)
                marginStart = dp(12)
            })
            startPanel.bringToFront()
        }
        setContentView(rootFrame)
        updateClock()
    }

    private fun installPanelResetLongPress(target: View) {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val longPressTimeout = 900L
        var downX = 0f
        var downY = 0f
        var triggered = false
        val showMenu = Runnable {
            triggered = true
            showPanelResetMenu()
        }
        target.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    triggered = false
                    handler.removeCallbacks(showMenu)
                    handler.postDelayed(showMenu, longPressTimeout)
                }
                MotionEvent.ACTION_MOVE -> {
                    val slop = android.view.ViewConfiguration.get(this).scaledTouchSlop
                    if (kotlin.math.abs(event.x - downX) > slop ||
                        kotlin.math.abs(event.y - downY) > slop) {
                        handler.removeCallbacks(showMenu)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> handler.removeCallbacks(showMenu)
            }
            // Consume only touches dispatched to this empty parent area so UP can cancel the timer.
            true
        }
    }

    private fun panelPositionKey(panelName: String, axis: String): String {
        val orientationSuffix = if (resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE) "landscape" else "portrait"
        return "dashboard_panel_${panelName}_${axis}_${orientationSuffix}_dp"
    }

    private fun showPanelResetMenu() {
        val orientationName = if (resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE) "landscape" else "portrait"
        AlertDialog.Builder(this)
            .setTitle("Atur posisi panel")
            .setMessage("Reset posisi Waktu, Cuaca, dan Pintasan untuk mode ${orientationName} saja?")
            .setPositiveButton("Reset posisi") { _, _ ->
                preferences.edit()
                    .remove(panelPositionKey("time", "x"))
                    .remove(panelPositionKey("time", "y"))
                    .remove(panelPositionKey("weather", "x"))
                    .remove(panelPositionKey("weather", "y"))
                    .remove(panelPositionKey("shortcuts", "x"))
                    .remove(panelPositionKey("shortcuts", "y"))
                    .apply()
                Toast.makeText(this, "Posisi panel mode ${orientationName} direset.", Toast.LENGTH_SHORT).show()
                buildScreen()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    /**
     * Lets dashboard panels be moved around the workspace by dragging their title,
     * similar to the Android assistive ball. Offsets are persisted in dp so they
     * survive launcher rebuilds and orientation changes.
     */
    private fun enablePanelDragging(panel: View, panelName: String) {
        val handle = (panel as? ViewGroup)?.getChildAt(0) ?: return
        val density = resources.displayMetrics.density
        val keyX = panelPositionKey(panelName, "x")
        val keyY = panelPositionKey(panelName, "y")
        panel.translationX = preferences.getFloat(keyX, 0f) * density
        panel.translationY = preferences.getFloat(keyY, 0f) * density
        handle.contentDescription = "Tekan dan geser untuk memindahkan panel ${panelName}"

        // Allow panels to travel outside their original row/column and across the full launcher.
        var ancestor: View? = panel.parent as? View
        while (ancestor != null) {
            if (ancestor is ViewGroup) {
                ancestor.clipChildren = false
                ancestor.clipToPadding = false
            }
            if (ancestor === rootFrame) break
            ancestor = ancestor.parent as? View
        }

        handle.setOnTouchListener(object : View.OnTouchListener {
            private var downRawX = 0f
            private var downRawY = 0f
            private var downTime = 0L
            private var startX = 0f
            private var startY = 0f
            private var startScreenX = 0
            private var startScreenY = 0
            private var parentScreenX = 0
            private var parentScreenY = 0
            private var dragging = false

            override fun onTouch(view: View, event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downRawX = event.rawX
                        downRawY = event.rawY
                        downTime = event.eventTime
                        startX = panel.translationX
                        startY = panel.translationY
                        val panelLocation = IntArray(2)
                        val parentLocation = IntArray(2)
                        panel.getLocationOnScreen(panelLocation)
                        (panel.parent as? View)?.getLocationOnScreen(parentLocation)
                        startScreenX = panelLocation[0]
                        startScreenY = panelLocation[1]
                        parentScreenX = parentLocation[0]
                        parentScreenY = parentLocation[1]
                        dragging = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - downRawX
                        val dy = event.rawY - downRawY
                        if (!dragging && (kotlin.math.abs(dx) > android.view.ViewConfiguration.get(this@MainActivity).scaledTouchSlop ||
                                    kotlin.math.abs(dy) > android.view.ViewConfiguration.get(this@MainActivity).scaledTouchSlop)) {
                            dragging = true
                            panel.bringToFront()
                            panel.elevation = dp(24).toFloat()
                        }
                        if (dragging) {
                            val rootLocation = IntArray(2)
                            rootFrame.getLocationOnScreen(rootLocation)
                            val minScreenX = rootLocation[0].toFloat()
                            val minScreenY = rootLocation[1].toFloat()
                            val maxScreenX = (rootLocation[0] + rootFrame.width - panel.width).toFloat()
                            val maxScreenY = (rootLocation[1] + rootFrame.height - panel.height).toFloat()
                            val targetScreenX = (startScreenX + dx).coerceIn(minScreenX, maxScreenX.coerceAtLeast(minScreenX))
                            val targetScreenY = (startScreenY + dy).coerceIn(minScreenY, maxScreenY.coerceAtLeast(minScreenY))
                            // Convert full-screen coordinates back into this panel's parent's translation space.
                            panel.translationX = targetScreenX - parentScreenX - panel.left
                            panel.translationY = targetScreenY - parentScreenY - panel.top
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (dragging) {
                            preferences.edit()
                                .putFloat(keyX, panel.translationX / density)
                                .putFloat(keyY, panel.translationY / density)
                                .apply()
                            panel.performClick()
                        } else if (event.actionMasked == MotionEvent.ACTION_UP &&
                            event.eventTime - downTime >= android.view.ViewConfiguration.getLongPressTimeout()) {
                            // A stationary long-press opens this panel's context action; movement still drags it.
                            panel.performLongClick()
                        }
                        dragging = false
                        return true
                    }
                }
                return true
            }
        })
    }

    private fun applyLauncherTypography(view: View) {
        val base = appFontSizeSp.toFloat()
        val family = appFontFamily
        fun visit(node: View) {
            if (node is TextView && node.tag != "saku_big_clock") {
                // Keep each view's original size as its baseline so rebuilds never compound scaling.
                val original = node.textSize / resources.displayMetrics.scaledDensity
                val scale = (original / 14f).coerceIn(0.65f, 2.2f)
                node.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, (base * scale).coerceIn(9f, 36f))
                val style = if (node.typeface?.isBold == true) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL
                node.typeface = android.graphics.Typeface.create(family, style)
            }
            if (node is android.view.ViewGroup) {
                for (i in 0 until node.childCount) visit(node.getChildAt(i))
            }
        }
        visit(view)
    }

    private fun performHome() {
        try {
            startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Toast.makeText(this, "Tidak bisa membuka Home Android", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openStorage(directory: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).setDataAndType(
                android.net.Uri.parse("content://com.android.externalstorage.documents/root/primary"),
                "*/*"
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (_: Exception) {
            try {
                startActivity(Intent(Intent.ACTION_GET_CONTENT).setType("*/*").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
                Toast.makeText(this, "Tidak bisa membuka penyimpanan", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun buildDock(): View {
        val dock = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(7), dp(8), dp(7))
            background = if (liquidGlass) glassSurface(25) else rounded(dockSurface, 25)
            elevation = dp(14).toFloat()
        }
        dock.addView(chipButton("⊞", accent, Color.WHITE, 15).apply {
            textSize = 23f
            setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD))
            contentDescription = "Buka Start Menu"
            setOnClickListener { toggleStartPanel() }
        }, LinearLayout.LayoutParams(dp(44), -1))
        dock.addView(View(this).apply {
            setBackgroundColor(Color.argb(if (isDarkTheme) 100 else 65, primaryText shr 16 and 0xff, primaryText shr 8 and 0xff, primaryText and 0xff))
        }, LinearLayout.LayoutParams(dp(1), dp(34)).apply { marginStart = dp(7); marginEnd = dp(5) })

        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
        }
        val icons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(5), 0, dp(5), 0)
        }
        // No fixed six-icon cap: the horizontal scroller keeps every pinned app reachable
        // without allowing icons to be clipped at the dock edges.
        val dockApps = pinnedApps().let { pinned ->
            if (preferences.getBoolean("drawer_group_apps_by_color", false)) {
                pinned.sortedWith(compareBy<LaunchableApp> { iconColorGroup(it.icon) }
                    .thenBy { it.label.lowercase(Locale.getDefault()) })
            } else pinned
        }
        dockApps.forEachIndexed { index, app ->
            icons.addView(dockIcon(app, index), LinearLayout.LayoutParams(dp(iconSizeDp.coerceIn(32, 72)), -1))
        }
        scroll.addView(icons)
        scroll.clipChildren = false
        scroll.clipToPadding = false
        dock.addView(scroll, LinearLayout.LayoutParams(0, -1, 1f))
        return dock
    }

    private fun dockIcon(app: LaunchableApp, index: Int): View = ImageView(this).apply {
        setImageDrawable(app.icon)
        scaleType = ImageView.ScaleType.FIT_CENTER
        setPadding(dp(5), dp(5), dp(5), dp(5))
        contentDescription = app.label
        isClickable = true
        isFocusable = true
        setOnClickListener {
            animate().cancel()
            scaleX = 0.86f
            scaleY = 0.86f
            animate().scaleX(1.08f).scaleY(1.08f).setDuration(120).withEndAction {
                animate().scaleX(1f).scaleY(1f).setDuration(140).start()
            }.start()
            launch(app)
        }
        setOnLongClickListener {
            showDockItemMenu(app, index)
            true
        }
    }

    private fun buildStartPanel(): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(14))
            background = if (liquidGlass) glassSurface(32) else rounded(if (isDarkTheme) Color.rgb(20, 29, 45) else Color.rgb(248, 251, 255), 32)
            elevation = dp(18).toFloat()
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "App Library"
            textSize = 21f
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
            background = rounded(cardSurface, 24)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            contentDescription = "Cari aplikasi di menu Start"
        }
        panel.addView(startSearchField, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(10) })

        val filters = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(7), 0, dp(7))
        }
        filters.addView(TextView(this).apply {
            text = "Aplikasi"
            textSize = 12f
            setTextColor(secondaryText)
            gravity = Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(0, dp(38), 1f))
        drawerGridButton = chipButton("▦", if (!drawerListMode) accent else cardSurface, primaryText, 12).apply {
            textSize = 20f
            setOnClickListener { setDrawerLayout(listMode = false) }
            contentDescription = "Tampilan grid aplikasi"
        }
        drawerListButton = chipButton("☷", if (drawerListMode) accent else cardSurface, primaryText, 12).apply {
            textSize = 20f
            setOnClickListener { setDrawerLayout(listMode = true) }
            contentDescription = "Tampilan daftar aplikasi"
        }
        val drawerColorGroupButton = chipButton("🎨", if (groupAppsByColor) accent else cardSurface, primaryText, 12).apply {
            textSize = 18f
            contentDescription = "Kelompokkan aplikasi berdasarkan warna ikon"
            setOnClickListener {
                groupAppsByColor = !groupAppsByColor
                preferences.edit().putBoolean("drawer_group_apps_by_color", groupAppsByColor).apply()
                background = rounded(if (groupAppsByColor) accent else cardSurface, 12)
                refreshStartPanel()
                Toast.makeText(this@MainActivity, if (groupAppsByColor) "Aplikasi dikelompokkan berdasarkan warna ikon" else "Urutan aplikasi normal", Toast.LENGTH_SHORT).show()
            }
        }
        groupAppsByColor = preferences.getBoolean("drawer_group_apps_by_color", false)
        drawerColorGroupButton.background = rounded(if (groupAppsByColor) accent else cardSurface, 12)
        filters.addView(drawerGridButton, LinearLayout.LayoutParams(dp(40), dp(38)).apply { marginEnd = dp(4) })
        filters.addView(drawerListButton, LinearLayout.LayoutParams(dp(40), dp(38)).apply { marginEnd = dp(4) })
        filters.addView(drawerColorGroupButton, LinearLayout.LayoutParams(dp(40), dp(38)))
        panel.addView(filters)

        startGrid = GridView(this).apply {
            numColumns = if (drawerListMode) 1 else preferences.getInt(KEY_DRAWER_GRID_COLUMNS, if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) 5 else 4)
            verticalSpacing = dp(7)
            horizontalSpacing = dp(7)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            setSelector(ColorDrawable(Color.TRANSPARENT))
            isVerticalScrollBarEnabled = false
            isSmoothScrollbarEnabled = true
            isScrollingCacheEnabled = true
            persistentDrawingCache = ViewGroup.PERSISTENT_NO_CACHE
        }
        startAdapter = AppGridAdapter(isStartMenu = true)
        startGrid.adapter = startAdapter
        var downX = 0f
        var downY = 0f
        startGrid.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> { downX = event.x; downY = event.y; false }
                android.view.MotionEvent.ACTION_UP -> {
                    // The app drawer is one complete library; horizontal gestures are left available
                    // to normal list/grid interaction instead of switching to a pinned-only tab.
                    false
                }
                else -> false
            }
        }
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

    private fun startMenuSwipeListener(openOnSwipeUp: Boolean, closeOnSwipeDown: Boolean): View.OnTouchListener {
        var downX = 0f
        var downY = 0f
        var consumedGesture = false
        return View.OnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    consumedGesture = false
                    false
                }
                MotionEvent.ACTION_UP -> {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    val threshold = dp(64)
                    if (kotlin.math.abs(dy) > threshold && kotlin.math.abs(dy) > kotlin.math.abs(dx) * 1.25f) {
                        if (dy < 0 && openOnSwipeUp && startPanel.visibility != View.VISIBLE) {
                            showStartPanel()
                            consumedGesture = true
                        } else if (dy > 0 && closeOnSwipeDown && startPanel.visibility == View.VISIBLE) {
                            hideStartPanel()
                            consumedGesture = true
                        }
                    }
                    consumedGesture
                }
                MotionEvent.ACTION_CANCEL -> {
                    val wasConsumed = consumedGesture
                    consumedGesture = false
                    wasConsumed
                }
                else -> consumedGesture
            }
        }
    }

    private fun toggleStartPanel() {
        if (startPanel.visibility == View.VISIBLE) hideStartPanel() else showStartPanel()
    }

    private var windowStartOnlyMode = false

    private fun buildWindowStartOnlyScreen() {
        windowStartOnlyMode = true
        window.statusBarColor = backgroundTop
        window.navigationBarColor = backgroundTop
        applyStatusBarPreference()
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
        windowScrim = View(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            visibility = View.GONE
            setOnClickListener { hideWindowStartPanel() }
        }
        rootFrame.addView(windowScrim, FrameLayout.LayoutParams(-1, -1))
        windowStartPanel = buildWindowStartPanel()
        rootFrame.addView(windowStartPanel, FrameLayout.LayoutParams(-1, -1))
        setContentView(rootFrame)
        windowStartPanel.alpha = 1f
        windowStartPanel.visibility = View.VISIBLE
        windowStartPanel.setPadding(dp(14), dp(12), dp(14), dp(10))
    }

    private fun buildWindowStartPanel(): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(14))
            background = if (liquidGlass) glassSurface(30) else rounded(if (isDarkTheme) Color.rgb(20, 29, 45) else Color.rgb(248, 251, 255), 30)
            elevation = dp(20).toFloat()
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "Window Start"
            textSize = 21f
            setTextColor(primaryText)
            setTypeface(null, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, dp(42), 1f))
        header.addView(TextView(this).apply {
            text = "FREEFORM"
            textSize = 9f
            letterSpacing = 0.08f
            setTextColor(secondaryText)
            gravity = Gravity.CENTER
            background = rounded(cardSurface, 12)
            setPadding(dp(9), dp(7), dp(9), dp(7))
        }, LinearLayout.LayoutParams(-2, dp(30)).apply { marginEnd = dp(7) })
        header.addView(chipButton("×", cardSurface, secondaryText, 15).apply {
            setOnClickListener { hideWindowStartPanel() }
        }, LinearLayout.LayoutParams(dp(38), dp(38)))
        panel.addView(header)
        panel.addView(TextView(this).apply {
            text = "Pilih aplikasi untuk meminta jendela bebas Android. Dukungan bergantung pada ROM dan aplikasi."
            textSize = 11f
            setTextColor(secondaryText)
            setPadding(dp(2), dp(2), dp(2), dp(7))
        }, LinearLayout.LayoutParams(-1, -2))
        windowSearchField = EditText(this).apply {
            hint = "Cari aplikasi untuk jendela"
            textSize = 14f
            setSingleLine(true)
            setTextColor(primaryText)
            setHintTextColor(secondaryText)
            setPadding(dp(15), 0, dp(15), 0)
            background = rounded(cardSurface, 24)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            contentDescription = "Cari aplikasi untuk mode jendela"
        }
        panel.addView(windowSearchField, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) })
        windowStartGrid = GridView(this).apply {
            numColumns = if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) 5 else 4
            verticalSpacing = dp(7)
            horizontalSpacing = dp(7)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            setSelector(ColorDrawable(Color.TRANSPARENT))
            isVerticalScrollBarEnabled = false
            isSmoothScrollbarEnabled = true
        }
        windowStartAdapter = AppGridAdapter(isStartMenu = true, windowMode = true)
        windowStartGrid.adapter = windowStartAdapter
        windowStartGrid.setOnItemClickListener { _, _, position, _ ->
            windowStartAdapter.getItemOrNull(position)?.let { launch(it, asFreeform = true) }
        }
        windowStartGrid.setOnItemLongClickListener { _, _, position, _ ->
            windowStartAdapter.getItemOrNull(position)?.let { showAppMenu(it) }
            true
        }
        panel.addView(windowStartGrid, LinearLayout.LayoutParams(-1, 0, 1f))
        windowSearchField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                windowSearchQuery = s?.toString()?.trim().orEmpty()
                if (::windowStartAdapter.isInitialized) windowStartAdapter.notifyDataSetChanged()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        return panel
    }

    private fun showWindowStartPanel() {
        if (!::windowStartPanel.isInitialized) return
        hideStartPanel()
        windowStartPanel.animate().cancel()
        windowScrim.animate().cancel()
        windowStartPanel.visibility = View.VISIBLE
        windowScrim.visibility = View.VISIBLE
        windowScrim.bringToFront()
        windowStartPanel.bringToFront()
        windowSearchField.setText("")
        windowSearchQuery = ""
        windowStartAdapter.notifyDataSetChanged()
        windowStartPanel.alpha = 0f
        windowStartPanel.translationY = dp(18).toFloat()
        windowStartPanel.scaleX = 0.985f
        windowStartPanel.scaleY = 0.985f
        windowScrim.alpha = 0f
        windowScrim.animate().alpha(1f).setDuration(160).start()
        windowStartPanel.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
            .setDuration(220).setInterpolator(DecelerateInterpolator(1.5f)).start()
    }

    private fun hideWindowStartPanel() {
        if (windowStartOnlyMode) {
            finish()
            return
        }
        if (!::windowStartPanel.isInitialized || windowStartPanel.visibility != View.VISIBLE) return
        windowStartPanel.animate().cancel()
        windowScrim.animate().cancel()
        windowStartPanel.animate().alpha(0f).translationY(dp(12).toFloat()).setDuration(150)
            .withEndAction {
                windowStartPanel.visibility = View.GONE
                windowStartPanel.alpha = 1f
                windowStartPanel.translationY = 0f
                windowScrim.visibility = View.GONE
                windowScrim.alpha = 0f
            }.start()
        windowScrim.animate().alpha(0f).setDuration(140).start()
        windowSearchField.hideKeyboard()
    }

    private fun showStartPanel() {
        if (!::startPanel.isInitialized) return
        if (startPanel.visibility == View.VISIBLE) return

        // Invalidate any pending close callback and normalize every animated property
        // before reopening. This prevents a stale scrim fade from leaving a dark flash.
        val token = ++panelAnimationToken
        startPanel.animate().cancel()
        startScrim.animate().cancel()
        startGrid.animate().cancel()
        startGrid.alpha = 1f
        startGrid.translationX = 0f
        startScrim.visibility = View.GONE
        startScrim.alpha = 0f
        startPanel.visibility = View.VISIBLE
        startPanel.bringToFront()
        startSearchField.setText("")
        refreshStartPanel()
        startPanel.alpha = 0f
        startPanel.translationY = dp(22).toFloat()
        startPanel.scaleX = 0.985f
        startPanel.scaleY = 0.985f
        startScrim.visibility = View.VISIBLE
        startScrim.animate().alpha(1f).setDuration(120).setInterpolator(DecelerateInterpolator()).start()
        startPanel.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
            .setDuration(220).setInterpolator(DecelerateInterpolator(1.6f)).start()
    }

    private fun hideStartPanel() {
        if (!::startPanel.isInitialized || startPanel.visibility != View.VISIBLE) return
        val token = ++panelAnimationToken
        startPanel.animate().cancel()
        startScrim.animate().cancel()
        startPanel.animate().alpha(0f).translationY(dp(14).toFloat()).scaleX(0.99f).scaleY(0.99f)
            .setDuration(150).setInterpolator(DecelerateInterpolator()).withEndAction {
                if (token == panelAnimationToken) {
                    startPanel.visibility = View.GONE
                    startPanel.alpha = 1f
                    startPanel.translationY = 0f
                    startPanel.scaleX = 1f
                    startPanel.scaleY = 1f
                    startScrim.animate().cancel()
                    startScrim.alpha = 0f
                    startScrim.visibility = View.GONE
                }
            }.start()
        startScrim.animate().alpha(0f).setDuration(120).withEndAction {
            if (token == panelAnimationToken) {
                startScrim.alpha = 0f
                startScrim.visibility = View.GONE
            }
        }.start()
        searchQuery = ""
        if (::startSearchField.isInitialized) startSearchField.setText("")
        if (::startSearchField.isInitialized) startSearchField.hideKeyboard()
    }

    private fun refreshStartPanel() {
        if (::startAdapter.isInitialized) startAdapter.notifyDataSetChanged()
    }

    private fun setDrawerLayout(listMode: Boolean) {
        if (drawerListMode == listMode) return
        drawerListMode = listMode
        preferences.edit().putBoolean(KEY_DRAWER_LIST_MODE, listMode).apply()
        if (::startGrid.isInitialized) {
            startGrid.numColumns = if (listMode) 1 else preferences.getInt(
                KEY_DRAWER_GRID_COLUMNS,
                if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) 5 else 4
            )
            startGrid.animate().cancel()
            startGrid.alpha = 0.75f
            startAdapter.notifyDataSetChanged()
            startGrid.animate().alpha(1f).setDuration(150).start()
        }
        if (::drawerGridButton.isInitialized) drawerGridButton.background = rounded(if (!listMode) accent else cardSurface, 15)
        if (::drawerListButton.isInitialized) drawerListButton.background = rounded(if (listMode) accent else cardSurface, 15)
    }

    private fun visibleApps(isStartMenu: Boolean): List<LaunchableApp> {
        val hidden = preferences.getStringSet(KEY_HIDDEN_APPS, emptySet()) ?: emptySet()
        val base = apps.filterNot { hidden.contains(it.packageName) }
        val query = if (isStartMenu) searchQuery else searchQuery
        return if (query.isBlank()) base else base.filter { it.label.contains(query, ignoreCase = true) }
    }

    private fun loadApps(): List<LaunchableApp> {
        val result = LinkedHashMap<String, LaunchableApp>()
        // LauncherApps enumerates launchable activities per Android user/profile. Xiaomi Dual Apps
        // can install the second copy under another user, so PackageManager alone misses it.
        try {
            val launcherApps = getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
            launcherApps.profiles.forEach { profile ->
                try {
                    launcherApps.getActivityList(null, profile).forEach { activity ->
                        val pkg = activity.applicationInfo.packageName
                        if (pkg == packageName) return@forEach
                        val label = activity.label?.toString()?.takeIf { it.isNotBlank() } ?: pkg
                        val profileLabel = if (profile != Process.myUserHandle()) "$label · Klon" else label
                        val app = LaunchableApp(profileLabel, pkg, activity.getIcon(0), activity.componentName, profile)
                        result[app.profileKey] = app
                    }
                } catch (e: SecurityException) {
                    android.util.Log.w("SakuApps", "Tidak bisa membaca profil aplikasi", e)
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("SakuApps", "LauncherApps profile scan gagal; gunakan profil utama", e)
        }
        // Fallback preserves the regular app list on ROMs that restrict profile enumeration.
        val queryIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val matches: List<ResolveInfo> = packageManager.queryIntentActivities(queryIntent, PackageManager.MATCH_ALL)
        matches.filter { it.activityInfo.packageName != packageName }.forEach { info ->
            val app = LaunchableApp(
                info.loadLabel(packageManager).toString(),
                info.activityInfo.packageName,
                info.loadIcon(packageManager),
                ComponentName(info.activityInfo.packageName, info.activityInfo.name),
                Process.myUserHandle()
            )
            result.putIfAbsent(app.profileKey, app)
        }
        return result.values.sortedWith(compareBy<LaunchableApp> { it.label.lowercase(Locale.getDefault()) }.thenBy { it.profileKey })
    }

    private fun freeIconPositions(): MutableMap<String, String> {
        return preferences.getString(KEY_FREE_ICON_POSITIONS, "").orEmpty().split("\n")
            .filter { it.contains("|") }.associate { it.substringBefore("|") to it.substringAfter("|") }.toMutableMap()
    }

    private fun saveFreeIconPositions(positions: Map<String, String>) {
        preferences.edit().putString(KEY_FREE_ICON_POSITIONS, positions.entries.joinToString("\n") { it.key + "|" + it.value }).apply()
    }

    private fun renderFreeIcons() {
        if (!::desktopCanvas.isInitialized || desktopCanvas.width <= 0 || !freeIconPlacement) return
        desktopCanvas.removeAllViews()
        val positions = freeIconPositions()
        val size = dp((iconSizeDp + 8).coerceAtLeast(36))
        val visible = homeApps()
        visible.forEachIndexed { index, app ->
            val saved = positions[app.packageName]?.split(",")
            val nx = saved?.getOrNull(0)?.toFloatOrNull() ?: ((index % 4) + 0.5f) / 4f
            val ny = saved?.getOrNull(1)?.toFloatOrNull() ?: ((index / 4) + 0.5f) / maxOf(1f, kotlin.math.ceil(visible.size / 4f))
            val x = (nx * desktopCanvas.width - size / 2f).toInt().coerceIn(0, (desktopCanvas.width - size).coerceAtLeast(0))
            val y = (ny * desktopCanvas.height - size / 2f).toInt().coerceIn(0, (desktopCanvas.height - size).coerceAtLeast(0))
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(2), dp(2), dp(2), dp(2))
                background = ColorDrawable(Color.TRANSPARENT)
                addView(ImageView(this@MainActivity).apply { setImageDrawable(app.icon); scaleType = ImageView.ScaleType.FIT_CENTER }, LinearLayout.LayoutParams(size, size))
                addView(TextView(this@MainActivity).apply { text = app.label; textSize = (appFontSizeSp - 3).coerceAtLeast(9).toFloat(); typeface = android.graphics.Typeface.create(appFontFamily, android.graphics.Typeface.NORMAL); gravity = Gravity.CENTER; setTextColor(primaryText); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(dp(84), -2))
                setOnClickListener { launch(app) }
                setOnLongClickListener {
                    val clip = android.content.ClipData.newPlainText("Saku app", app.packageName)
                    val shadow = View.DragShadowBuilder(this)
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) startDragAndDrop(clip, shadow, app.packageName, 0)
                    else { @Suppress("DEPRECATION") startDrag(clip, shadow, app.packageName, 0) }
                    true
                }
            }
            desktopCanvas.addView(cell, FrameLayout.LayoutParams(size + dp(8), dp(82)).apply { leftMargin = x; topMargin = y })
        }
    }
    private fun rebuildPinnedViews() {
        // onResume may run before buildScreen() has created the shell/dock.
        // Never access shellView until its first assignment in buildScreen().
        if (!::shellView.isInitialized) return
        if (::pinnedAdapter.isInitialized) pinnedAdapter.notifyDataSetChanged()
        if (::desktopCanvas.isInitialized && freeIconPlacement) renderFreeIcons()
        if (::startAdapter.isInitialized) startAdapter.notifyDataSetChanged()
        if (::windowStartAdapter.isInitialized) windowStartAdapter.notifyDataSetChanged()
        // When the bottom taskbar is hidden, there is no dock view to rebuild.
        if (preferences.getBoolean(KEY_HIDE_TASKBAR, false)) return
        // Rebuild only the dock; keep the open Start panel and its scroll position intact.
        val dockIndex = shellView.childCount - 1
        if (dockIndex < 0) return
        shellView.removeViewAt(dockIndex)
        shellView.addView(buildDock(), LinearLayout.LayoutParams(-1, dp(66)).apply { topMargin = dp(10) })
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    /** Ask Android's own ActivityTaskManager to create a real FREEFORM task.
     * Root is used only to set the same developer options exposed by Android and to request
     * windowingMode=5. We then inspect dumpsys instead of assuming an accepted command means
     * the app actually entered a freeform window. */
    private fun applyStatusBarPreference() {
        val hide = preferences.getBoolean(KEY_HIDE_STATUS_BAR, false)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = if (hide) {
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        } else 0
    }

    private fun tryLaunchFreeformAsRoot(component: android.content.ComponentName, bounds: Rect): Boolean {
        return try {
            val flat = component.flattenToString() ?: return false
            val pkg = shellQuote(component.packageName)
            val command = "settings put global enable_freeform_support 1; " +
                // Android stores the developer resizability toggle in Global settings.
                "settings put global force_resizable_activities 1; " +
                "launch_output=\$(am start -W --user current --windowingMode 5 " +
                // Android 15's am start parser does not support --activity-bounds.
                // Let ActivityTaskManager choose initial bounds; ActivityOptions below is the
                // compatibility path for launch bounds on builds where root launch is not verified.
                "-n ${shellQuote(flat)} 2>&1); " +
                "launch_code=\$?; " +
                "sleep 2; " +
                "task_dump=\$(dumpsys activity activities | grep -F -B 28 -A 10 ${pkg}); " +
                "printf 'launch_exit=%s\\n%s\\n---TASK DUMP---\\n%s\\n' \"\$launch_code\" \"\$launch_output\" \"\$task_dump\"; " +
                "printf '%s' \"\$task_dump\" | grep -Ei 'windowingMode[=: ]+5|mWindowingMode=5|windowingMode=freeform'";
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val output = java.io.ByteArrayOutputStream()
            val error = java.io.ByteArrayOutputStream()
            val outThread = Thread { process.inputStream.use { it.copyTo(output) } }
            val errThread = Thread { process.errorStream.use { it.copyTo(error) } }
            outThread.start()
            errThread.start()
            if (!process.waitFor(8, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly()
                android.util.Log.w("SakuFreeform", "Root freeform launch/verification timed out; package=${component.packageName}; bounds=$bounds")
                DiagnosticLogger.logFreeform("ROOT launch/verification timed out; package=${component.packageName}; component=$flat; bounds=$bounds; command=$command")
                return false
            }
            outThread.join(300)
            errThread.join(300)
            val stdout = output.toString("UTF-8")
            val stderr = error.toString("UTF-8")
            val verified = process.exitValue() == 0 &&
                !stdout.contains("Error:", true) &&
                !stderr.contains("Exception", true) &&
                (stdout.contains("windowingMode=5", true) || stdout.contains("mWindowingMode=5", true) || stdout.contains("windowingMode: 5", true) || stdout.contains("windowingMode=freeform", true))
            val report = "method=root; package=${component.packageName}; component=$flat; bounds=$bounds; exit=${process.exitValue()}; verified=$verified\\nstdout=$stdout\\nstderr=$stderr"
            android.util.Log.i("SakuFreeform", report)
            if (!verified) DiagnosticLogger.logFreeform(report)
            verified
        } catch (e: Exception) {
            android.util.Log.w("SakuFreeform", "Root freeform launch unavailable; trying ActivityOptions", e)
            DiagnosticLogger.logFreeform("Root launch unavailable; package=${component.packageName}; bounds=$bounds; exception=${e.stackTraceToString()}", e)
            false
        }
    }

    private fun launch(app: LaunchableApp, asFreeform: Boolean = preferences.getBoolean(KEY_APP_LAUNCH_WINDOW, false)) {
        if (asFreeform && ::windowStartPanel.isInitialized && windowStartPanel.visibility == View.VISIBLE) {
            hideWindowStartPanel()
        } else {
            hideStartPanel()
        }
        // A cloned app belongs to a different Android user; packageManager.getLaunchIntentForPackage()
        // would open the primary copy. Start the exact activity in its own profile instead.
        if (app.isSecondaryProfile && app.componentName != null && app.userHandle != null) {
            try {
                val launcherApps = getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
                launcherApps.startMainActivity(app.componentName, app.userHandle, null, null)
            } catch (e: Exception) {
                android.util.Log.e("SakuApps", "Gagal membuka aplikasi clone ${app.packageName}", e)
                Toast.makeText(this, "Aplikasi clone tidak bisa dibuka oleh sistem: ${e.localizedMessage ?: "akses profil ditolak"}", Toast.LENGTH_LONG).show()
            }
            return
        }
        val launchIntent = packageManager.getLaunchIntentForPackage(app.packageName)
        if (launchIntent == null) {
            Toast.makeText(this, "Aplikasi tidak dapat dibuka", Toast.LENGTH_SHORT).show()
            return
        }
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!asFreeform) {
            try {
                startActivity(launchIntent)
            } catch (e: Exception) {
                Toast.makeText(this, "Aplikasi tidak dapat dibuka: ${e.localizedMessage ?: "akses ditolak"}", Toast.LENGTH_LONG).show()
            }
            return
        }

        try {
            val metrics = resources.displayMetrics
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            val margin = dp(18)
            // ActivityOptions launch bounds only hint at a size. Explicitly request Android's
            // FREEFORM windowing mode (5) as well; on ROMs without freeform support Android
            // may ignore the request, in which case we fall back safely.
            val left = margin
            val top = dp(56)
            val right = (width - margin).coerceAtLeast(left + dp(240))
            val bottom = (height - dp(72)).coerceAtLeast(top + dp(240))
            val bounds = Rect(left, top, right, bottom)
            val component = launchIntent.component
            if (component != null && tryLaunchFreeformAsRoot(component, bounds)) {
                Toast.makeText(this, "Permintaan jendela bebas dikirim ke Android", Toast.LENGTH_SHORT).show()
                return
            }
            val options = ActivityOptions.makeBasic().setLaunchBounds(bounds)
            var windowModeRequested = false
            try {
                val method = ActivityOptions::class.java.getMethod(
                    "setLaunchWindowingMode", Int::class.javaPrimitiveType
                )
                method.isAccessible = true
                method.invoke(options, 5) // WindowConfiguration.WINDOWING_MODE_FREEFORM
                windowModeRequested = true
            } catch (e: ReflectiveOperationException) {
                android.util.Log.w("SakuFreeform", "Android does not expose setLaunchWindowingMode", e)
            } catch (e: SecurityException) {
                android.util.Log.w("SakuFreeform", "Freeform request blocked by system", e)
            }
            val report = "method=ActivityOptions; package=${app.packageName}; modeRequested=$windowModeRequested; bounds=$bounds; Android=${android.os.Build.VERSION.RELEASE} API=${android.os.Build.VERSION.SDK_INT}"
            android.util.Log.i("SakuFreeform", report)
            startActivity(launchIntent, options.toBundle())
            rootFrame.postDelayed({ DiagnosticLogger.logFreeform("$report; launch request returned; confirm windowing mode visually") }, 1800)
            if (!windowModeRequested) {
                Toast.makeText(
                    this,
                    "Ukuran jendela diminta. Jika tetap layar penuh, aktifkan 'Aktifkan jendela bentuk bebas' di Opsi Pengembang lalu mulai ulang sistem.",
                    Toast.LENGTH_LONG
                ).show()
            }
        } catch (e: Exception) {
            android.util.Log.e("SakuFreeform", "Freeform launch failed for ${app.packageName}", e)
            DiagnosticLogger.logFreeform("Freeform launch failed; package=${app.packageName}; exception=${e.stackTraceToString()}", e)
            try {
                startActivity(launchIntent)
                Toast.makeText(
                    this,
                    "Mode jendela tidak didukung/diaktifkan di ROM ini; aplikasi dibuka normal.",
                    Toast.LENGTH_LONG
                ).show()
            } catch (fallback: Exception) {
                android.util.Log.e("SakuFreeform", "Normal launch also failed", fallback)
                DiagnosticLogger.logFreeform("Normal fallback launch also failed; package=${app.packageName}; exception=${fallback.stackTraceToString()}", fallback)
                Toast.makeText(this, "Aplikasi tidak dapat dibuka: ${fallback.localizedMessage ?: "akses ditolak"}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showAppMenu(app: LaunchableApp) {
        val isHome = homePackages().contains(app.packageName)
        val homeAction = if (isHome) "Hapus dari layar utama" else "Tambahkan ke layar utama"
        val dockAction = if (pinnedPackages().contains(app.packageName)) "Lepas dari dock" else "Sematkan ke dock"
        val options = arrayOf(
            "Buka sebagai jendela",
            "Buka layar penuh",
            homeAction,
            "Pindah posisi ke kiri",
            "Pindah posisi ke kanan",
            "Pindahkan ke layar lain",
            "Info aplikasi",
            "Buka di Play Store",
            "Copot pemasangan",
            "$dockAction · batas 30 aplikasi"
        )
        AlertDialog.Builder(this).setTitle(app.label).setItems(options) { _, which ->
            when (which) {
                0 -> launch(app, asFreeform = true)
                1 -> launch(app, asFreeform = false)
                2 -> {
                    val current = homePackages()
                    if (current.contains(app.packageName)) current.remove(app.packageName) else current.add(app.packageName)
                    saveHomePackages(current)
                    pinnedAdapter.notifyDataSetChanged()
                    Toast.makeText(this, if (current.contains(app.packageName)) "Ditambahkan ke layar utama" else "Dihapus dari layar utama", Toast.LENGTH_SHORT).show()
                }
                3 -> moveHomeApp(app, -1)
                4 -> moveHomeApp(app, 1)
                5 -> {
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
                6 -> openAppInfo(app)
                7 -> openPlayStore(app)
                8 -> uninstallApp(app)
                9 -> togglePinned(app)
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
        if (current.contains(app.packageName)) {
            current.remove(app.packageName)
        } else {
            if (current.size >= MAX_DOCK_APPS) {
                Toast.makeText(this, "Dock maksimal $MAX_DOCK_APPS aplikasi. Lepas satu aplikasi terlebih dahulu.", Toast.LENGTH_LONG).show()
                return
            }
            current.add(app.packageName)
        }
        savePinned(current)
        Toast.makeText(this, if (current.contains(app.packageName)) "${app.label} disematkan ke dock" else "${app.label} dilepas dari dock", Toast.LENGTH_SHORT).show()
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
        preferences.edit().putString(KEY_PINNED, packages.distinct().take(MAX_DOCK_APPS).joinToString("\n")).apply()
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
        val hideDrawerLabels = preferences.getBoolean(KEY_HIDE_DRAWER_LABELS, false)
        val hideStatusBarState = if (preferences.getBoolean(KEY_HIDE_STATUS_BAR, false)) "Tersembunyi" else "Tampil"
        val glassState = if (liquidGlass) "Aktif" else "Nonaktif"
        val transparency = preferences.getInt(KEY_TRANSPARENCY, 88)
        val options = arrayOf(
            "◧  Taskbar bawah · $hideTaskbarState",
            "▦  Ukuran ikon · ${iconSizeDp} dp",
            "Aa  Font & ukuran teks · ${appFontSizeSp}sp",
            "✧  Liquid Glass · $glassState",
            "◐  Transparansi · $transparency%",
            "◉  Sembunyikan aplikasi · ${hiddenPackages().size}",
            "＋  Tambahkan widget",
            "⌘  Bola Asisten · $overlayState",
            "⌂  Tombol Home mengambang · ${if (preferences.getBoolean(KEY_HOME_BUTTON_VISIBLE, true)) "Selalu tampil" else "Disembunyikan"}",
            "▤  Kelola aplikasi Bola Asisten · $pinnedState",
            "☾  Mode tampilan · $themeState",
            "✦  Warna aksen",
            "▧  Pilih wallpaper",
            "↺  Reset wallpaper",
            "ⓘ  Tentang & izin",
            "▦  Tata letak grid · ${preferences.getInt(KEY_HOME_GRID_COLUMNS, if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) 6 else 4)} kolom",
            "▣  Mode buka aplikasi · ${if (preferences.getBoolean(KEY_APP_LAUNCH_WINDOW, false)) "Jendela" else "Layar penuh"}",
            "▤  Kolom menu aplikasi · ${preferences.getInt(KEY_DRAWER_GRID_COLUMNS, if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) 5 else 4)}",
            "◉  Pengelola proses aplikasi",
            "▦  Sembunyikan label ikon Grid · ${if (hideDrawerLabels) "Aktif" else "Nonaktif"}",
            "▣  Display Extend · Virtual Display",
            "▰  Status bar Android · $hideStatusBarState",
            "↔  Orientasi layar · ${if (preferences.getBoolean(KEY_LANDSCAPE_MODE, false)) "Landscape" else "Ikuti sistem"}"
        )

        val backgroundColor = if (isDarkTheme) Color.rgb(22, 25, 34) else Color.rgb(242, 245, 251)
        val rowColor = if (isDarkTheme) Color.rgb(39, 43, 55) else Color.WHITE
        val textColor = if (isDarkTheme) Color.rgb(245, 247, 252) else Color.rgb(31, 37, 49)
        val mutedColor = if (isDarkTheme) Color.rgb(164, 172, 188) else Color.rgb(112, 121, 138)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(8))
            background = rounded(backgroundColor, 28)
        }
        container.addView(TextView(this).apply {
            text = "Personalisasi"
            textSize = 25f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            setTextColor(textColor)
            setPadding(dp(3), dp(4), dp(3), dp(2))
        })
        container.addView(TextView(this).apply {
            text = "Sesuaikan tampilan dan perilaku Saku"
            textSize = 13f
            setTextColor(mutedColor)
            setPadding(dp(3), 0, dp(3), dp(12))
        })
        val scroll = ScrollView(this).apply { isFillViewport = false; clipToPadding = false }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val sections = listOf(
            "TAMPILAN" to (0..4),
            "APLIKASI & WIDGET" to (5..9),
            "GAYA PERSONAL" to (10..14),
            "TATA LETAK" to (15..19),
            "SISTEM & DISPLAY" to (20..22)
        )
        lateinit var dialog: AlertDialog
        sections.forEach { (heading, indices) ->
            list.addView(TextView(this).apply {
                text = heading
                textSize = 10f
                letterSpacing = 0.12f
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                setTextColor(mutedColor)
                setPadding(dp(5), dp(13), dp(5), dp(6))
            })
            val group = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(rowColor, 17)
                setPadding(dp(10), dp(3), dp(10), dp(3))
            }
            indices.forEach { index ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(7), dp(11), dp(7), dp(11))
                    isClickable = true
                    isFocusable = true
                    background = rounded(rowColor, 12)
                }
                row.addView(TextView(this).apply {
                    text = options[index]
                    textSize = 13f
                    setTextColor(textColor)
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(TextView(this).apply {
                    text = "›"
                    textSize = 24f
                    setTextColor(mutedColor)
                    gravity = Gravity.CENTER
                }, LinearLayout.LayoutParams(dp(24), dp(28)))
                row.setOnClickListener { dialog.dismiss(); performSettingSelection(index) }
                group.addView(row, LinearLayout.LayoutParams(-1, -2))
                if (index != indices.last) {
                    group.addView(View(this).apply { setBackgroundColor(if (isDarkTheme) 0x334D5668 else 0x1A6F7B91) }, LinearLayout.LayoutParams(-1, dp(1)))
                }
            }
            list.addView(group, LinearLayout.LayoutParams(-1, -2))
        }
        scroll.addView(list, android.view.ViewGroup.LayoutParams(-1, -2))
        container.addView(scroll, LinearLayout.LayoutParams(-1, (resources.displayMetrics.heightPixels * 0.58f).toInt()))
        dialog = AlertDialog.Builder(this)
            .setView(container)
            .setNegativeButton("Selesai", null)
            .create()
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.92f).toInt().coerceAtMost(dp(620)), (resources.displayMetrics.heightPixels * 0.84f).toInt())
        }
        dialog.show()
    }

    private fun performSettingSelection(which: Int) {
        when (which) {
            0 -> {
                preferences.edit().putBoolean(KEY_HIDE_TASKBAR, !preferences.getBoolean(KEY_HIDE_TASKBAR, false)).apply()
                buildScreen()
            }
            1 -> chooseIconSize()
            2 -> chooseFontSettings()
            3 -> {
                preferences.edit().putBoolean(KEY_LIQUID_GLASS, !liquidGlass).apply()
                buildScreen()
                refreshFloatingDock()
            }
            4 -> chooseTransparency()
            5 -> manageHiddenApps()
            6 -> pickWidget()
            7 -> toggleOverlay()
            8 -> {
                val visible = !preferences.getBoolean(KEY_HOME_BUTTON_VISIBLE, true)
                preferences.edit().putBoolean(KEY_HOME_BUTTON_VISIBLE, visible).apply()
                if (preferences.getBoolean(KEY_OVERLAY_ENABLED, false)) {
                    startService(Intent(this, FloatingDockService::class.java).setAction(FloatingDockService.ACTION_REFRESH))
                }
                Toast.makeText(this, if (visible) "Tombol Home selalu tampil diaktifkan" else "Tombol Home disembunyikan", Toast.LENGTH_SHORT).show()
            }
            9 -> managePins()
            10 -> toggleTheme()
            11 -> chooseAccent()
            12 -> chooseWallpaper()
            13 -> resetWallpaper()
            14 -> showPermissionInfo()
            15 -> chooseHomeGrid()
            16 -> {
                val windowMode = !preferences.getBoolean(KEY_APP_LAUNCH_WINDOW, false)
                preferences.edit().putBoolean(KEY_APP_LAUNCH_WINDOW, windowMode).apply()
                Toast.makeText(this, if (windowMode) "Mode pembukaan aplikasi: Jendela" else "Mode pembukaan aplikasi: Layar penuh", Toast.LENGTH_SHORT).show()
            }
            17 -> chooseDrawerGrid()
            18 -> showProcessManager()
            19 -> {
                preferences.edit().putBoolean(KEY_HIDE_DRAWER_LABELS, !preferences.getBoolean(KEY_HIDE_DRAWER_LABELS, false)).apply()
                if (::startAdapter.isInitialized) startAdapter.notifyDataSetChanged()
                Toast.makeText(this, if (preferences.getBoolean(KEY_HIDE_DRAWER_LABELS, false)) "Label ikon Grid disembunyikan; List tetap menampilkan teks" else "Label ikon Grid ditampilkan", Toast.LENGTH_SHORT).show()
            }
            20 -> {
                try {
                    startActivity(Intent().setClassName(this, "io.github.jqssun.displayextend.MainActivity"))
                } catch (e: Exception) {
                    DiagnosticLogger.logFreeform("Could not open Display Extend activity", e)
                    Toast.makeText(this, "Display Extend belum bisa dibuka: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
            21 -> {
                preferences.edit().putBoolean(KEY_HIDE_STATUS_BAR, !preferences.getBoolean(KEY_HIDE_STATUS_BAR, false)).apply()
                buildScreen()
                Toast.makeText(this, if (preferences.getBoolean(KEY_HIDE_STATUS_BAR, false)) "Status bar disembunyikan" else "Status bar ditampilkan", Toast.LENGTH_SHORT).show()
            }
            22 -> {
                val landscape = !preferences.getBoolean(KEY_LANDSCAPE_MODE, false)
                preferences.edit().putBoolean(KEY_LANDSCAPE_MODE, landscape).apply()
                requestedOrientation = if (landscape) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                recreate()
            }
        }
    }

    private fun chooseFontSettings() {
        val families = arrayOf(
            "Roboto" to "sans-serif",
            "Roboto" to "sans-serif",
            "Roboto Condensed" to "sans-serif-condensed",
            "Serif" to "serif",
            "Monospace" to "monospace",
            "Sans Serif Light" to "sans-serif-light",
            "Sans Serif Medium" to "sans-serif-medium",
            "Casual" to "casual"
        )
        val currentFamily = appFontFamily
        val familyNames = families.map { it.first }.toTypedArray()
        val selectedFamily = families.indexOfFirst { it.second == currentFamily }.coerceAtLeast(0)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(12), dp(22), dp(4))
        }
        val sizeLabel = TextView(this).apply {
            text = "Ukuran teks: ${appFontSizeSp} sp"
            textSize = 15f
            setTextColor(primaryText)
        }
        container.addView(sizeLabel)
        val slider = SeekBar(this).apply {
            max = 14
            progress = appFontSizeSp - 10
            contentDescription = "Ukuran font, geser kiri untuk memperkecil dan kanan untuk memperbesar"
        }
        container.addView(slider, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        val preview = TextView(this).apply {
            text = "Pratinjau teks aplikasi — Aplikasi"
            textSize = appFontSizeSp.toFloat()
            typeface = android.graphics.Typeface.create(appFontFamily, android.graphics.Typeface.NORMAL)
            setTextColor(primaryText)
            maxLines = 2
            setPadding(0, dp(8), 0, dp(8))
        }
        container.addView(preview)
        val familyLabel = TextView(this).apply {
            text = "Gaya font"
            textSize = 14f
            setTextColor(secondaryText)
            setPadding(0, dp(8), 0, dp(4))
        }
        container.addView(familyLabel)
        val familySpinner = android.widget.Spinner(this)
        familySpinner.adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, familyNames)
        familySpinner.setSelection(selectedFamily)
        container.addView(familySpinner, LinearLayout.LayoutParams(-1, dp(48)))
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val size = progress + 10
                sizeLabel.text = "Ukuran teks: ${size} sp"
                preview.textSize = size.toFloat()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        AlertDialog.Builder(this)
            .setTitle("Font & ukuran teks")
            .setView(container)
            .setPositiveButton("Simpan") { _, _ ->
                val size = slider.progress + 10
                val family = families[familySpinner.selectedItemPosition].second
                preferences.edit().putInt(KEY_APP_FONT_SIZE, size).putString(KEY_APP_FONT_FAMILY, family).apply()
                buildScreen()
                Toast.makeText(this, "Font diperbarui: ${size} sp", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun toggleOverlay() {
        val enabled = preferences.getBoolean(KEY_OVERLAY_ENABLED, false)
        if (enabled) {
            preferences.edit().putBoolean(KEY_OVERLAY_ENABLED, false).apply()
            stopService(Intent(this, FloatingDockService::class.java))
            Toast.makeText(this, "Bola Asisten dimatikan", Toast.LENGTH_SHORT).show()
            return
        }
        if (Settings.canDrawOverlays(this)) {
            preferences.edit().putBoolean(KEY_OVERLAY_ENABLED, true).apply()
            startService(Intent(this, FloatingDockService::class.java))
            Toast.makeText(this, "Bola Asisten aktif", Toast.LENGTH_SHORT).show()
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
        AlertDialog.Builder(this).setTitle("Aplikasi di dock · maksimal $MAX_DOCK_APPS")
            .setMultiChoiceItems(names, checked) { dialog, which, isChecked ->
                val pkg = apps[which].packageName
                if (isChecked && !current.contains(pkg) && current.size >= MAX_DOCK_APPS) {
                    (dialog as? AlertDialog)?.listView?.setItemChecked(which, false)
                    Toast.makeText(this, "Dock maksimal $MAX_DOCK_APPS aplikasi", Toast.LENGTH_SHORT).show()
                } else if (isChecked) current.add(pkg) else current.remove(pkg)
            }
            .setPositiveButton("Simpan") { _, _ ->
                val ordered = apps.map { it.packageName }.filter { current.contains(it) }.take(MAX_DOCK_APPS)
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

    private fun glassSurface(radius: Int): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        if (isDarkTheme) intArrayOf(Color.argb(188, 75, 91, 119), Color.argb(145, 27, 38, 57))
        else intArrayOf(Color.argb(224, 255, 255, 255), Color.argb(178, 225, 236, 251))
    ).apply {
        cornerRadius = dp(radius).toFloat()
        setStroke(dp(1), if (isDarkTheme) Color.argb(125, 220, 233, 255) else Color.argb(185, 255, 255, 255))
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
            .setMessage("Dock di atas aplikasi memakai izin overlay Android. Fitur dasar launcher tidak memerlukan root. Jika Pengelola Proses dibuka, Saku mencoba membaca PID melalui su; perintah hentikan/freeze baru dijalankan setelah konfirmasi di aplikasi dan tetap memerlukan persetujuan root. Shizuku belum terintegrasi pada versi ini. Jendela bebas tetap bergantung pada dukungan Android dan ROM.")
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
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
        clockText.text = time
        dateText.text = SimpleDateFormat("EEE, d MMM", Locale("id", "ID")).format(now)
        // Keep the large dashboard clock synchronized with the same system Date.
        rootFrame.findViewWithTag<TextView>("saku_big_clock")?.text = time
        rootFrame.findViewWithTag<TextView>("saku_big_date")?.text =
            SimpleDateFormat("EEEE, d MMMM yyyy", Locale("id", "ID")).format(now)
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

    // Coarse icon-hue buckets keep similarly colored apps together without altering their icons.
    private fun iconColorGroup(drawable: android.graphics.drawable.Drawable): Int {
        val size = 24
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val copy = drawable.constantState?.newDrawable()?.mutate() ?: drawable.mutate()
        copy.setBounds(0, 0, size, size)
        copy.draw(canvas)
        val hsv = FloatArray(3)
        val counts = IntArray(9)
        for (y in 0 until size step 2) {
            for (x in 0 until size step 2) {
                val color = bitmap.getPixel(x, y)
                if (Color.alpha(color) < 96) continue
                Color.colorToHSV(color, hsv)
                val saturation = hsv[1]
                val value = hsv[2]
                val bucket = when {
                    value < 0.20f -> 0 // dark/black
                    saturation < 0.18f -> 1 // white/gray
                    hsv[0] < 15f || hsv[0] >= 345f -> 2 // red
                    hsv[0] < 45f -> 3 // orange/yellow
                    hsv[0] < 165f -> 4 // green
                    hsv[0] < 255f -> 5 // cyan/blue
                    hsv[0] < 290f -> 6 // indigo
                    hsv[0] < 345f -> 7 // purple/pink
                    else -> 1
                }
                counts[bucket]++
            }
        }
        bitmap.recycle()
        return counts.indices.maxByOrNull { counts[it] } ?: 1
    }

    private inner class AppGridAdapter(private val isStartMenu: Boolean, private val windowMode: Boolean = false) : BaseAdapter() {
        // Cache the visible rows. GridView calls getCount/getItem repeatedly while scrolling;
        // rebuilding/filtering the whole app list for every recycled cell causes jank on low-RAM devices.
        private var items: List<LaunchableApp> = emptyList()

        init {
            refreshItems()
        }

        private fun refreshItems() {
            items = if (isStartMenu) {
                val hidden = preferences.getStringSet(KEY_HIDDEN_APPS, emptySet()) ?: emptySet()
                val base = apps.filterNot { hidden.contains(it.packageName) }
                val query = if (windowMode) windowSearchQuery else searchQuery
                val filtered = if (query.isBlank()) base else base.filter { it.label.contains(query, ignoreCase = true) }
                if (groupAppsByColor && !windowMode) filtered.sortedWith(compareBy<LaunchableApp> { iconColorGroup(it.icon) }.thenBy { it.label.lowercase(Locale.getDefault()) }) else filtered
            } else homeApps()
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
            val wantedMode = if (isStartMenu && drawerListMode) "list" else "grid"
            val existing = convertView as? LinearLayout
            val cell = if (existing?.tag == wantedMode) existing else LinearLayout(this@MainActivity).apply {
                tag = wantedMode
                orientation = if (wantedMode == "list") LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
                gravity = if (wantedMode == "list") Gravity.CENTER_VERTICAL else Gravity.CENTER
                setPadding(if (wantedMode == "list") dp(10) else dp(2), dp(5), if (wantedMode == "list") dp(10) else dp(2), dp(5))
                // Transparent cells keep the native icon artwork free of enclosing bubbles.
                background = ColorDrawable(Color.TRANSPARENT)
                layoutParams = AbsListView.LayoutParams(-1, dp(if (wantedMode == "list") 62 else if (isStartMenu) 88 else 96))
                addView(ImageView(context).apply {
                    id = android.R.id.icon
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    contentDescription = null
                }, LinearLayout.LayoutParams(dp(if (wantedMode == "list") 42 else if (isStartMenu) (iconSizeDp + 2).coerceAtLeast(30) else iconSizeDp + 4), dp(if (wantedMode == "list") 42 else if (isStartMenu) (iconSizeDp + 2).coerceAtLeast(30) else iconSizeDp + 4)).apply {
                    if (wantedMode == "list") marginEnd = dp(14)
                })
                addView(TextView(context).apply {
                    id = android.R.id.text1
                    textSize = if (wantedMode == "list") appFontSizeSp.toFloat() else (appFontSizeSp - 3).coerceAtLeast(9).toFloat()
                    setTypeface(android.graphics.Typeface.create(appFontFamily, android.graphics.Typeface.NORMAL))
                    setTextColor(primaryText)
                    gravity = if (wantedMode == "list") Gravity.CENTER_VERTICAL or Gravity.START else Gravity.CENTER
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(if (wantedMode == "list") 0 else 0, if (wantedMode == "list") 0 else dp(5), 0, 0)
                    visibility = if (isStartMenu && wantedMode == "grid" && hideDrawerLabels) View.GONE else View.VISIBLE
                }, LinearLayout.LayoutParams(if (wantedMode == "list") 0 else -1, -2, if (wantedMode == "list") 1f else 0f))
                if (wantedMode == "list") addView(TextView(context).apply {
                    text = "›"
                    textSize = 24f
                    setTextColor(secondaryText)
                    gravity = Gravity.CENTER
                }, LinearLayout.LayoutParams(dp(20), -1))
            }
            cell.findViewById<ImageView>(android.R.id.icon).setImageDrawable(app.icon)
            cell.findViewById<TextView>(android.R.id.text1).text = app.label
            cell.findViewById<TextView>(android.R.id.text1).visibility = if (isStartMenu && wantedMode == "grid" && hideDrawerLabels) View.GONE else View.VISIBLE
            cell.contentDescription = app.label
            cell.setOnClickListener { launch(app, asFreeform = wantedMode != "list") }
            cell.setOnLongClickListener {
                if (isStartMenu) {
                    // Keep drawer long-press as an app-action menu (uninstall, app info,
                    // add/remove from home, window/fullscreen, Play Store). Dragging from
                    // the drawer is available through the menu's home-placement action.
                    showAppMenu(app)
                    true
                } else {
                    // Desktop icons remain freely draggable; drop at any location when
                    // free placement is enabled, or onto another grid cell otherwise.
                    val clip = android.content.ClipData.newPlainText("Saku app", app.packageName)
                    val shadow = View.DragShadowBuilder(cell)
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                        cell.startDragAndDrop(clip, shadow, app.packageName, 0)
                    } else {
                        @Suppress("DEPRECATION")
                        cell.startDrag(clip, shadow, app.packageName, 0)
                    }
                    true
                }
            }
            return cell
        }
    }

    companion object {
        private const val PREFS_NAME = "saku_dock_settings"
        private const val KEY_PINNED = "pinned_packages_order"
        private const val MAX_DOCK_APPS = 30
        private const val KEY_HOME_APPS = "home_screen_packages"
        private const val KEY_HOME_PAGE_COUNT = "home_screen_page_count"
        private const val KEY_LAST_HOME_PAGE = "last_home_screen_page"
        private const val KEY_OVERLAY_ENABLED = "overlay_enabled"
        private const val KEY_HOME_BUTTON_VISIBLE = "floating_home_button_visible"
        private const val KEY_OVERLAY_PENDING = "overlay_pending"
        private const val KEY_DARK_THEME = "dark_theme"
        private const val KEY_ACCENT = "accent_color"
        private const val KEY_WALLPAPER_URI = "wallpaper_uri"
        private const val KEY_HIDE_TASKBAR = "hide_bottom_taskbar"
        private const val KEY_HIDE_STATUS_BAR = "hide_android_status_bar"
        private const val KEY_HIDE_DRAWER_LABELS = "hide_drawer_app_labels"
        private const val KEY_APP_FONT_SIZE = "app_font_size_sp"
        private const val KEY_APP_FONT_FAMILY = "app_font_family"
        private const val KEY_LIQUID_GLASS = "liquid_glass_enabled"
        private const val KEY_ICON_SIZE = "app_icon_size_dp"
        private const val KEY_HOME_GRID_COLUMNS = "home_grid_columns"
        private const val KEY_FREE_ICON_PLACEMENT = "free_icon_placement"
        private const val KEY_APP_LAUNCH_WINDOW = "app_launch_window_mode"
        private const val KEY_FREE_ICON_POSITIONS = "free_icon_positions"
        private const val KEY_DRAWER_GRID_COLUMNS = "drawer_grid_columns"
        private const val KEY_DRAWER_LIST_MODE = "drawer_list_mode"
        private const val KEY_TRANSPARENCY = "dock_transparency"
        private const val KEY_HIDDEN_APPS = "hidden_app_packages"
        private const val KEY_FROZEN_APPS = "frozen_app_packages"
        private const val KEY_WIDGET_IDS = "home_widget_ids"
        private const val KEY_WIDGET_HEIGHT_PREFIX = "home_widget_height_"
        private const val KEY_LANDSCAPE_MODE = "landscape_mode_enabled"
        private const val APP_WIDGET_HOST_ID = 2096
        private const val REQUEST_WIDGET_PICK = 483
        private const val REQUEST_WALLPAPER = 481
        private const val REQUEST_UNINSTALL = 482
        const val EXTRA_OPEN_START = "com.sakuhome.launcher.OPEN_START"
        const val EXTRA_OPEN_WINDOW_START = "com.sakuhome.launcher.OPEN_WINDOW_START"
        const val EXTRA_OPEN_SETTINGS = "com.sakuhome.launcher.OPEN_SETTINGS"
    }
}
package com.sakuhome.launcher

import android.app.Activity
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Saku split workspace: DroidOS-inspired launcher menu above and a touchpad below.
 * The touchpad cursor operates the launcher menu; apps are launched from the upper panel.
 */
class TrackpadWorkspaceActivity : Activity() {
    private data class AppEntry(val label: String, val packageName: String, val info: ResolveInfo)

    private lateinit var viewport: FrameLayout
    private lateinit var appGrid: GridLayout
    private lateinit var appScroll: ScrollView
    private lateinit var cursorView: CursorView
    private lateinit var searchField: EditText
    private lateinit var touchpad: TouchpadSurface
    private val appTiles = mutableListOf<Pair<AppEntry, View>>()
    private var cursorX = 80f
    private var cursorY = 80f
    private var tileWidth = 0

    private val bg = Color.rgb(10, 15, 26)
    private val panel = Color.rgb(19, 28, 44)
    private val tileBg = Color.rgb(29, 42, 62)
    private val text = Color.rgb(239, 245, 255)
    private val muted = Color.rgb(151, 169, 194)
    private val accent = Color.rgb(105, 161, 255)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        window.decorView.systemUiVisibility = 0
        buildWorkspace()
        populateApps()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun rounded(color: Int, radius: Int = 16): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
        }

    private fun label(value: String, size: Float, color: Int = text, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            gravity = Gravity.CENTER_VERTICAL
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            includeFontPadding = false
        }

    private fun button(value: String, action: () -> Unit): TextView =
        label(value, 12f, text, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = rounded(tileBg, 12)
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
        }

    private fun buildWorkspace() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(12), dp(8), dp(12), dp(10))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(2), dp(10))
        }
        val brand = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        brand.addView(label("DroidOS Workspace", 18f, text, true))
        brand.addView(label("Saku · kontrol launcher dengan touchpad", 10f, muted))
        header.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(button("Saku", ::finish))
        root.addView(header, LinearLayout.LayoutParams(-1, -2))

        val searchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(8))
        }
        searchField = EditText(this).apply {
            hint = "Cari aplikasi…"
            setHintTextColor(muted)
            setTextColor(text)
            textSize = 13f
            singleLine = true
            setPadding(dp(14), 0, dp(14), 0)
            background = rounded(panel, 12)
        }
        searchRow.addView(searchField, LinearLayout.LayoutParams(0, dp(42), 1f))
        searchRow.addView(button("Muat ulang") { populateApps(searchField.text?.toString().orEmpty()) },
            LinearLayout.LayoutParams(-2, dp(42)).apply { marginStart = dp(8) })
        root.addView(searchRow, LinearLayout.LayoutParams(-1, -2))

        viewport = FrameLayout(this).apply {
            background = rounded(panel, 18)
            clipToPadding = true
            clipChildren = true
        }
        appScroll = ScrollView(this).apply {
            isFillViewport = false
            clipToPadding = true
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        appGrid = GridLayout(this).apply {
            columnCount = 4
            useDefaultMargins = false
        }
        appScroll.addView(appGrid, ScrollView.LayoutParams(-1, -2))
        viewport.addView(appScroll, FrameLayout.LayoutParams(-1, -1))
        cursorView = CursorView()
        viewport.addView(cursorView, FrameLayout.LayoutParams(-1, -1))
        root.addView(viewport, LinearLayout.LayoutParams(-1, 0, 1f))

        val padHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(10), dp(2), dp(6))
        }
        padHeader.addView(label("TRACKPAD", 11f, accent, true), LinearLayout.LayoutParams(0, -2, 1f))
        padHeader.addView(label("Gerakkan jari untuk pointer · ketuk untuk klik", 10f, muted))
        root.addView(padHeader, LinearLayout.LayoutParams(-1, -2))

        touchpad = TouchpadSurface(
            onMove = { dx, dy ->
                cursorX = (cursorX + dx * 1.45f).coerceIn(0f, max(0, viewport.width - dp(8)).toFloat())
                cursorY = (cursorY + dy * 1.45f).coerceIn(0f, max(0, viewport.height - dp(8)).toFloat())
                cursorView.invalidate()
            },
            onTap = { clickAtCursor() },
            onRightClick = { finish() },
            onScroll = { dy -> appScroll.smoothScrollBy(0, dy.toInt()) }
        ).apply {
            background = rounded(Color.rgb(23, 34, 51), 18)
            contentDescription = "Area trackpad; gerakkan jari untuk memindahkan pointer"
        }
        root.addView(touchpad, LinearLayout.LayoutParams(-1, 0, 0.68f).apply {
            minimumHeight = dp(130)
        })

        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        footer.addView(label("Klik: ketuk · Klik kanan: tekan lama · Scroll: dua jari", 10f, muted),
            LinearLayout.LayoutParams(0, -2, 1f))
        footer.addView(button("Keluar", ::finish))
        root.addView(footer, LinearLayout.LayoutParams(-1, -2))

        setContentView(root)
        searchField.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                populateApps(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
        viewport.post {
            cursorX = viewport.width * 0.5f
            cursorY = viewport.height * 0.35f
            cursorView.invalidate()
        }
    }

    private fun populateApps(filter: String = "") {
        if (!::appGrid.isInitialized) return
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = packageManager.queryIntentActivities(query, 0)
            .filter { it.activityInfo?.packageName != packageName }
            .map {
                AppEntry(
                    it.loadLabel(packageManager)?.toString().orEmpty().ifBlank { it.activityInfo.name },
                    it.activityInfo.packageName,
                    it
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
            .filter { it.label.contains(filter.trim(), ignoreCase = true) }

        appGrid.removeAllViews()
        appTiles.clear()
        val columns = 4
        appGrid.columnCount = columns
        val availableWidth = max(dp(220), viewport.width - dp(12))
        tileWidth = (availableWidth / columns).coerceAtLeast(dp(64))

        resolved.forEach { app ->
            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(4), dp(10), dp(4), dp(10))
                background = rounded(tileBg, 14)
                isClickable = true
                isFocusable = true
                setOnClickListener { launchApp(app) }
            }
            val icon = ImageView(this).apply {
                try { setImageDrawable(app.info.loadIcon(packageManager)) } catch (_: Exception) { }
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            tile.addView(icon, LinearLayout.LayoutParams(dp(36), dp(36)))
            tile.addView(label(app.label, 10f, text).apply {
                gravity = Gravity.CENTER
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(2), dp(6), dp(2), 0)
            }, LinearLayout.LayoutParams(-1, dp(32)))
            val params = GridLayout.LayoutParams().apply {
                width = 0
                height = dp(86)
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(dp(3), dp(3), dp(3), dp(3))
            }
            appGrid.addView(tile, params)
            appTiles.add(app to tile)
        }
        if (resolved.isEmpty()) {
            val empty = label("Tidak ada aplikasi yang cocok.", 13f, muted)
            appGrid.addView(empty, GridLayout.LayoutParams().apply {
                width = -1
                height = dp(72)
            })
        }
        cursorView.invalidate()
    }

    private fun launchApp(app: AppEntry) {
        val launchIntent = packageManager.getLaunchIntentForPackage(app.packageName)
        if (launchIntent == null) {
            Toast.makeText(this, "Aplikasi tidak bisa dibuka", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launchIntent)
        } catch (_: Exception) {
            Toast.makeText(this, "Gagal membuka ${app.label}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun clickAtCursor() {
        if (!::cursorView.isInitialized) return
        val cursorLocation = IntArray(2)
        cursorView.getLocationOnScreen(cursorLocation)
        val screenX = cursorLocation[0] + cursorX.toInt()
        val screenY = cursorLocation[1] + cursorY.toInt()
        val hitRect = Rect()
        val tile = appTiles.firstOrNull { (_, view) ->
            view.getGlobalVisibleRect(hitRect) &&
                screenX >= hitRect.left && screenX <= hitRect.right &&
                screenY >= hitRect.top && screenY <= hitRect.bottom
        }?.second
        if (tile != null) tile.performClick()
    }

    private inner class CursorView : View(this@TrackpadWorkspaceActivity) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            paint.color = Color.argb(70, 105, 161, 255)
            canvas.drawCircle(cursorX, cursorY, dp(14).toFloat(), paint)
            paint.color = Color.WHITE
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(2).toFloat()
            canvas.drawCircle(cursorX, cursorY, dp(8).toFloat(), paint)
            canvas.drawLine(cursorX - dp(11), cursorY, cursorX + dp(11), cursorY, paint)
            canvas.drawLine(cursorX, cursorY - dp(11), cursorX, cursorY + dp(11), paint)
            paint.style = Paint.Style.FILL
        }
    }

    private class TouchpadSurface(
        private val onMove: (Float, Float) -> Unit,
        private val onTap: () -> Unit,
        private val onRightClick: () -> Unit,
        private val onScroll: (Float) -> Unit
    ) : View(null) {
        private var lastX = 0f
        private var lastY = 0f
        private var downX = 0f
        private var downY = 0f
        private var downAt = 0L
        private var moved = false
        private var multiTouch = false

        constructor(
            context: android.content.Context,
            onMove: (Float, Float) -> Unit,
            onTap: () -> Unit,
            onRightClick: () -> Unit,
            onScroll: (Float) -> Unit
        ) : this(onMove, onTap, onRightClick, onScroll) {
            // Rebind the platform View context through the secondary constructor is not possible;
            // this constructor exists only to keep call sites explicit.
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.x; lastY = event.y
                    downX = event.x; downY = event.y
                    downAt = event.eventTime
                    moved = false
                    multiTouch = false
                    return true
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    multiTouch = true
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (event.pointerCount >= 2) {
                        multiTouch = true
                        val dy = event.y - lastY
                        onScroll((-dy * 1.8f))
                        lastX = event.x; lastY = event.y
                        moved = true
                    } else {
                        val dx = event.x - lastX
                        val dy = event.y - lastY
                        if (abs(event.x - downX) + abs(event.y - downY) > 8f) moved = true
                        onMove(dx, dy)
                        lastX = event.x; lastY = event.y
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved && event.eventTime - downAt > 550L) onRightClick()
                    else if (!moved && !multiTouch) onTap()
                    performClick()
                    return true
                }
                MotionEvent.ACTION_CANCEL -> return true
            }
            return true
        }

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }
    }
}

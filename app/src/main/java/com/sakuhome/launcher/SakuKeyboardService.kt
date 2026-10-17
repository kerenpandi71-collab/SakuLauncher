package com.sakuhome.launcher

import android.content.ClipboardManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Saku Keyboard: QWERTY keyboard with persistent size and vertical-position controls.
 * Position controls adjust the keyboard's content inside the IME window; they do not create an overlay.
 */
class SakuKeyboardService : InputMethodService() {
    private var shifted = false
    private var symbols = false
    private var floating = false
    private val repeatHandler = Handler(Looper.getMainLooper())
    private var deleteRepeating = false
    private val deleteRunnable = object : Runnable {
        override fun run() {
            if (!deleteRepeating) return
            currentInputConnection?.deleteSurroundingText(1, 0)
            repeatHandler.postDelayed(this, 65L)
        }
    }

    override fun onCreateInputView(): View {
        val prefs = getSharedPreferences("saku_keyboard", MODE_PRIVATE)
        floating = prefs.getBoolean("floating_mode", false)
        var keyHeight = prefs.getInt("key_height_dp", 48).coerceIn(40, 60)
        var positionOffset = prefs.getInt("position_offset_dp", 0).coerceIn(0, 60)
        val dark = getSharedPreferences("saku_dock_settings", MODE_PRIVATE).getBoolean("dark_theme", true)
        val bg = if (dark) Color.rgb(35, 38, 45) else Color.rgb(232, 234, 239)
        val keyBg = if (dark) Color.rgb(73, 77, 86) else Color.WHITE
        val textColor = if (dark) Color.WHITE else Color.rgb(30, 33, 40)
        val muted = if (dark) Color.LTGRAY else Color.DKGRAY
        val accent = Color.rgb(0, 122, 255)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(if (floating) 8 else 4), dp(5), dp(if (floating) 8 else 4), dp(5 + positionOffset))
            background = GradientDrawable().apply {
                setColor(bg)
                cornerRadius = dp(if (floating) 22 else 12).toFloat()
            }
            layoutParams = ViewGroup.LayoutParams(
                if (floating) (resources.displayMetrics.widthPixels * 0.94f).toInt() else ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        fun rebuild() { setInputView(onCreateInputView()) }
        fun commit(value: String) { currentInputConnection?.commitText(value, 1) }
        fun clipboard(): ClipboardManager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        fun pasteClipboard() {
            val clip = clipboard()
            val value = if (clip.hasPrimaryClip()) clip.primaryClip?.getItemAt(0)?.coerceToText(this).toString() else ""
            if (value.isNotEmpty()) commit(value) else Toast.makeText(this, "Papan klip kosong", Toast.LENGTH_SHORT).show()
        }
        fun row(): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        val toolbar = row()
        val title = TextView(this).apply {
            text = "Saku Keyboard"
            gravity = Gravity.CENTER
            textSize = 12f
            setTextColor(muted)
            setPadding(dp(4), 0, dp(4), 0)
            contentDescription = "Keyboard. Tombol panah mengatur posisi, A− dan A+ mengatur tinggi tombol."
        }
        toolbar.addView(title, LinearLayout.LayoutParams(0, dp(32), 1f))
        fun toolButton(label: String, description: String, action: () -> Unit) {
            val button = TextView(this).apply {
                text = label
                gravity = Gravity.CENTER
                textSize = 13f
                setTextColor(textColor)
                background = GradientDrawable().apply {
                    setColor(if (dark) Color.rgb(57, 61, 70) else Color.rgb(218, 222, 230))
                    cornerRadius = dp(8).toFloat()
                }
                contentDescription = description
                isClickable = true
                setOnClickListener { action() }
            }
            toolbar.addView(button, LinearLayout.LayoutParams(dp(37), dp(29)).apply {
                marginStart = dp(2); marginEnd = dp(2)
            })
        }
        toolButton("↑", "Geser isi keyboard ke atas") {
            positionOffset = (positionOffset + 8).coerceAtMost(60)
            prefs.edit().putInt("position_offset_dp", positionOffset).apply()
            rebuild()
        }
        toolButton("↓", "Geser isi keyboard ke bawah") {
            positionOffset = (positionOffset - 8).coerceAtLeast(0)
            prefs.edit().putInt("position_offset_dp", positionOffset).apply()
            rebuild()
        }
        toolButton("A−", "Kurangi tinggi tombol") {
            keyHeight = (keyHeight - 2).coerceAtLeast(40)
            prefs.edit().putInt("key_height_dp", keyHeight).apply()
            rebuild()
        }
        toolButton("A+", "Tambah tinggi tombol") {
            keyHeight = (keyHeight + 2).coerceAtMost(60)
            prefs.edit().putInt("key_height_dp", keyHeight).apply()
            rebuild()
        }
        toolButton("⤢", "Beralih mode ringkas atau keyboard penuh") {
            floating = !floating
            prefs.edit().putBoolean("floating_mode", floating).apply()
            rebuild()
        }
        root.addView(toolbar, LinearLayout.LayoutParams(-1, dp(34)).apply {
            bottomMargin = dp(2)
        })

        var currentRow: LinearLayout? = null
        fun key(label: String, flex: Float = 1f, special: Boolean = false, action: () -> Unit) {
            val button = TextView(this).apply {
                text = label
                gravity = Gravity.CENTER
                textSize = if (label.length > 2) 13f else 20f
                setTextColor(if (special && label != "⇧") Color.WHITE else textColor)
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                background = GradientDrawable().apply {
                    setColor(if (special) accent else keyBg)
                    cornerRadius = dp(9).toFloat()
                }
                elevation = dp(1).toFloat()
                isClickable = true
                setOnClickListener { action() }
                if (label == "⌫") {
                    setOnTouchListener { _, event ->
                        when (event.action) {
                            MotionEvent.ACTION_DOWN -> {
                                deleteRepeating = true
                                repeatHandler.postDelayed(deleteRunnable, 380L)
                                false
                            }
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                deleteRepeating = false
                                repeatHandler.removeCallbacks(deleteRunnable)
                                false
                            }
                            else -> false
                        }
                    }
                }
            }
            currentRow?.addView(button, LinearLayout.LayoutParams(0, dp(keyHeight), flex).apply {
                marginStart = dp(2); marginEnd = dp(2); topMargin = dp(3); bottomMargin = dp(3)
            })
        }
        fun addRow(block: () -> Unit) {
            currentRow = row()
            block()
            root.addView(currentRow, LinearLayout.LayoutParams(-1, dp(keyHeight + 6)))
            currentRow = null
        }

        addRow {
            key("123", 1.25f, true) { symbols = !symbols; rebuild() }
            (if (symbols) listOf("1","2","3","4","5","6","7","8","9","0") else listOf("q","w","e","r","t","y","u","i","o","p"))
                .forEach { c -> key(if (shifted && !symbols) c.uppercase() else c) { commit(if (shifted && !symbols) c.uppercase() else c); shifted = false } }
        }
        addRow {
            (if (symbols) listOf("@","#","$","%","&","-","+","(",")") else listOf("a","s","d","f","g","h","j","k","l"))
                .forEach { c -> key(if (shifted && !symbols) c.uppercase() else c) { commit(if (shifted && !symbols) c.uppercase() else c); shifted = false } }
        }
        addRow {
            key("⇧", 1.25f, true) { shifted = !shifted; symbols = false; rebuild() }
            (if (symbols) listOf("*","\\"","'",":",";","!","?") else listOf("z","x","c","v","b","n","m"))
                .forEach { c -> key(if (shifted && !symbols) c.uppercase() else c) { commit(if (shifted && !symbols) c.uppercase() else c); shifted = false } }
            key("⌫", 1.25f, true) { currentInputConnection?.deleteSurroundingText(1, 0) }
        }
        addRow {
            key("🌐", 1f) { requestShowSelf(0) }
            key("▤", 1f) {
                val clip = clipboard()
                val value = if (clip.hasPrimaryClip()) clip.primaryClip?.getItemAt(0)?.coerceToText(this@SakuKeyboardService)?.toString().orEmpty() else ""
                if (value.isNotEmpty()) commit(value) else Toast.makeText(this@SakuKeyboardService, "Papan klip kosong", Toast.LENGTH_SHORT).show()
            }
            key("Paste", 1.2f) { pasteClipboard() }
            key("spasi", 3.2f) { commit(" ") }
            key("↵", 1.25f, true) {
                val action = currentInputEditorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
                val connection: InputConnection? = currentInputConnection
                if (action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) connection?.performEditorAction(action)
                else commit("\n")
            }
        }
        return root
    }

    override fun onDestroy() {
        deleteRepeating = false
        repeatHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

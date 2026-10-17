package com.sakuhome.launcher

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

/**
 * A lightweight iOS-inspired Saku keyboard. Text entry uses Android's supported IME API.
 * The top handle lets users reposition the keyboard content within the IME viewport.
 */
class SakuKeyboardService : InputMethodService() {
    private var shifted = false
    private var symbols = false
    private var keyboardRoot: LinearLayout? = null
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var startTranslationX = 0f
    private var startTranslationY = 0f

    override fun onCreateInputView(): View {
        val prefs = getSharedPreferences("saku_keyboard", MODE_PRIVATE)
        val dark = getSharedPreferences("saku_dock_settings", MODE_PRIVATE).getBoolean("dark_theme", true)
        val bg = if (dark) Color.rgb(35, 38, 45) else Color.rgb(226, 229, 235)
        val keyBg = if (dark) Color.rgb(73, 77, 86) else Color.rgb(250, 250, 252)
        val text = if (dark) Color.WHITE else Color.rgb(30, 33, 40)
        val accent = Color.rgb(0, 122, 255)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(5), dp(5), dp(5), dp(7))
            background = GradientDrawable().apply { setColor(bg); cornerRadius = dp(18).toFloat() }
            translationX = prefs.getFloat("offset_x", 0f)
            translationY = prefs.getFloat("offset_y", 0f)
        }
        keyboardRoot = root
        val handle = TextView(this).apply {
            text = "⠿   Saku Keyboard   ⠿"
            gravity = Gravity.CENTER
            textSize = 12f
            setTextColor(if (dark) Color.LTGRAY else Color.DKGRAY)
            setPadding(dp(12), dp(3), dp(12), dp(5))
            setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        dragStartX = event.rawX; dragStartY = event.rawY
                        startTranslationX = root.translationX; startTranslationY = root.translationY
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        root.translationX = (startTranslationX + event.rawX - dragStartX).coerceIn(-dp(110).toFloat(), dp(110).toFloat())
                        root.translationY = (startTranslationY + event.rawY - dragStartY).coerceIn(-dp(100).toFloat(), dp(100).toFloat())
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        prefs.edit().putFloat("offset_x", root.translationX).putFloat("offset_y", root.translationY).apply()
                        true
                    }
                    else -> true
                }
            }
        }
        root.addView(handle, LinearLayout.LayoutParams(-1, dp(27)))
        fun row(): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        fun key(label: String, flex: Float = 1f, special: Boolean = false, action: () -> Unit) {
            val button = TextView(this).apply {
                text = label
                gravity = Gravity.CENTER
                textSize = if (label.length > 2) 13f else 20f
                setTextColor(if (special && !dark) Color.WHITE else text)
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                background = GradientDrawable().apply {
                    setColor(if (special) accent else keyBg)
                    cornerRadius = dp(8).toFloat()
                }
                elevation = dp(1).toFloat()
                isClickable = true
                setOnClickListener { action() }
            }
            currentRow?.addView(button, LinearLayout.LayoutParams(0, dp(43), flex).apply {
                marginStart = dp(2); marginEnd = dp(2); topMargin = dp(3); bottomMargin = dp(3)
            })
        }
        var currentRow: LinearLayout? = null
        fun addRow(block: () -> Unit) {
            currentRow = row()
            block()
            root.addView(currentRow, LinearLayout.LayoutParams(-1, dp(49)))
            currentRow = null
        }
        fun commit(value: String) { currentInputConnection?.commitText(value, 1) }
        fun clipboard(): ClipboardManager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        fun pasteClipboard() {
            val clip = clipboard()
            val value = if (clip.hasPrimaryClip()) clip.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty() else ""
            if (value.isNotEmpty()) commit(value) else Toast.makeText(this, "Papan klip kosong", Toast.LENGTH_SHORT).show()
        }
        addRow { (if (symbols) listOf("1","2","3","4","5","6","7","8","9","0") else listOf("q","w","e","r","t","y","u","i","o","p")).forEach { c -> key(if (shifted) c.uppercase() else c) { commit(if (shifted) c.uppercase() else c); shifted = false } } }
        addRow { (if (symbols) listOf("@","#","$","%","&","-","+","(",")","/") else listOf("a","s","d","f","g","h","j","k","l")).forEach { c -> key(if (shifted) c.uppercase() else c) { commit(if (shifted) c.uppercase() else c); shifted = false } } }
        addRow {
            key("⇧", 1.25f, true) { shifted = !shifted; symbols = false; setInputView(onCreateInputView()) }
            (if (symbols) listOf("*","\"","'",":",";","!","?") else listOf("z","x","c","v","b","n","m")).forEach { c ->
                key(if (shifted) c.uppercase() else c) { commit(if (shifted) c.uppercase() else c); shifted = false }
            }
            key("⌫", 1.25f, true) { currentInputConnection?.deleteSurroundingText(1, 0) }
        }
        addRow {
            key("123", 1.3f, true) { symbols = !symbols; setInputView(onCreateInputView()) }
            key("🌐", 1f) { requestShowSelf(0) }
            key("Copy", 1.2f) {
                val connection = currentInputConnection
                connection?.performContextMenuAction(android.R.id.copy)
                Toast.makeText(this, "Gunakan pilih teks lalu Copy untuk menyalin", Toast.LENGTH_SHORT).show()
            }
            key("Paste", 1.2f) { pasteClipboard() }
            key("spasi", 3.4f) { commit(" ") }
            key("↵", 1.3f, true) {
                val action = currentInputEditorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
                val connection: InputConnection? = currentInputConnection
                if (action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) connection?.performEditorAction(action)
                else commit("\n")
            }
        }
        return root
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

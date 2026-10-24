package com.sakuhome.launcher

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Saku Keyboard: lightweight native IME with a soft, iOS-inspired key layout.
 * All text is sent through InputConnection, so it works in normal text fields.
 */
class SakuKeyboardService : InputMethodService() {
    private var shifted = false
    private var symbols = false
    private var keyboardRoot: LinearLayout? = null

    override fun onCreateInputView(): View {
        return buildKeyboard().also { keyboardRoot = it }
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        symbols = false
        shifted = attribute?.let {
            it.inputType and android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS != 0 ||
                it.inputType and android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES != 0
        } ?: false
        refreshKeyboard()
    }

    private fun buildKeyboard(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(7), dp(4), dp(8))
            setBackgroundColor(if (isDark()) Color.rgb(34, 36, 41) else Color.rgb(218, 222, 229))
        }
        keyboardRoot = root
        addRow(root, if (symbols) listOf("1","2","3","4","5","6","7","8","9","0") else "qwertyuiop".map { it.toString() })
        addRow(root, if (symbols) listOf("@","#","$","%","&","-","+","(",")","/") else "asdfghjkl".map { it.toString() }, sidePadding = 12)
        val third = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        third.addView(key("⇧", special = true) {
            shifted = !shifted
            refreshKeyboard()
        }, weighted(1.35f))
        (if (symbols) listOf("=", "*", "\"", "'", ":", ";", "!", "?") else "zxcvbnm".map { it.toString() }).forEach { value ->
            third.addView(key(value) { commit(value) }, weighted(1f))
        }
        third.addView(key("⌫", special = true, description = "Hapus karakter") {
            val ic = currentInputConnection
            if (ic != null) {
                val selected = ic.getSelectedText(0)
                if (!selected.isNullOrEmpty()) ic.commitText("", 1)
                else ic.deleteSurroundingText(1, 0)
            }
        }, weighted(1.35f))
        root.addView(third, rowParams())
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        bottom.addView(key(if (symbols) "ABC" else "?123", special = true) {
            symbols = !symbols
            refreshKeyboard()
        }, weighted(1.45f))
        bottom.addView(key(",", special = true) { commit(",") }, weighted(0.8f))
        bottom.addView(key("😊", special = true) { commit("😊") }, weighted(0.8f))
        bottom.addView(key("spasi", special = true, description = "Spasi") { commit(" ") }, weighted(4.2f))
        bottom.addView(key(".", special = true) { commit(".") }, weighted(0.8f))
        bottom.addView(key(actionLabel(), special = true, description = "Aksi berikutnya") {
            performEditorAction()
        }, weighted(1.35f))
        root.addView(bottom, rowParams())
        return root
    }

    private fun addRow(root: LinearLayout, labels: List<String>, sidePadding: Int = 0) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(sidePadding), 0, dp(sidePadding), 0)
        }
        labels.forEach { label ->
            val shown = if (!symbols && shifted) label.uppercase() else label
            row.addView(key(shown) { commit(shown) }, weighted(1f))
        }
        root.addView(row, rowParams())
    }

    private fun key(
        label: String,
        special: Boolean = false,
        description: String = label,
        action: () -> Unit
    ): TextView = TextView(this).apply {
        text = label
        contentDescription = description
        gravity = Gravity.CENTER
        textSize = if (label == "spasi") 12f else 18f
        typeface = Typeface.create("sans-serif", if (special) Typeface.MEDIUM else Typeface.NORMAL)
        includeFontPadding = false
        setTextColor(if (special) (if (isDark()) Color.WHITE else Color.rgb(31, 37, 48)) else if (isDark()) Color.WHITE else Color.rgb(26, 30, 37))
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(7).toFloat()
            setColor(
                if (special) {
                    if (isDark()) Color.rgb(76, 80, 89) else Color.rgb(172, 179, 190)
                } else {
                    if (isDark()) Color.rgb(91, 95, 104) else Color.WHITE
                }
            )
        }
        isFocusable = true
        isClickable = true
        setOnClickListener {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            action()
        }
        setPadding(dp(1), 0, dp(1), 0)
    }

    private fun commit(value: String) {
        currentInputConnection?.commitText(value, 1)
        if (!symbols && shifted) {
            shifted = false
            refreshKeyboard()
        }
    }

    private fun actionLabel(): String {
        val options = currentInputEditorInfo?.imeOptions ?: 0
        return when (options and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_GO -> "Go"
            EditorInfo.IME_ACTION_SEARCH -> "⌕"
            EditorInfo.IME_ACTION_SEND -> "Kirim"
            EditorInfo.IME_ACTION_NEXT -> "→"
            EditorInfo.IME_ACTION_DONE -> "Selesai"
            else -> "↵"
        }
    }

    private fun performEditorAction() {
        val ic: InputConnection = currentInputConnection ?: return
        val action = currentInputEditorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
        val handled = action != EditorInfo.IME_ACTION_NONE && ic.performEditorAction(action)
        if (!handled) {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        }
    }

    private fun refreshKeyboard() {
        if (keyboardRoot != null) {
            keyboardRoot = buildKeyboard()
            setInputView(keyboardRoot)
        }
    }

    private fun rowParams() = LinearLayout.LayoutParams(-1, dp(47)).apply {
        bottomMargin = dp(7)
    }

    private fun weighted(weight: Float) = LinearLayout.LayoutParams(0, -1, weight).apply {
        setMargins(dp(2), dp(1), dp(2), dp(1))
    }

    private fun isDark(): Boolean =
        getSharedPreferences("saku_dock_settings", MODE_PRIVATE).getBoolean("dark_theme", true)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

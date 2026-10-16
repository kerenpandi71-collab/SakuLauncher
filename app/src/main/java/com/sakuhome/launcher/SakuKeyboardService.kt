package com.sakuhome.launcher

import android.inputmethodservice.InputMethodService
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Compact PC-style QWERTY keyboard. Normal keys commit text to the focused editor;
 * navigation/system keys are sent as KeyEvents through the active InputConnection.
 */
class SakuKeyboardService : InputMethodService() {
    private var shifted = false
    private var ctrl = false
    private var alt = false
    private var win = false
    private var symbols = false

    override fun onCreateInputView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(3), dp(5), dp(3), dp(5))
            setBackgroundColor(Color.rgb(21, 26, 38))
        }

        // A compact function/navigation row like a laptop keyboard.
        addRow(root, listOf("Esc","F1","F2","F3","F4","F5","F6","F7","F8","F9","F10","F11","F12"), dp(27), 0.7f)
        addRow(root, listOf("Tab", "Ctrl", "Alt", "←", "↑", "↓", "→", "Del"), dp(29), 0.8f)

        if (!symbols) {
            addRow(root, listOf("1","2","3","4","5","6","7","8","9","0","⌫"), dp(39), 1f)
            addRow(root, listOf("Tab","q","w","e","r","t","y","u","i","o","p"), dp(39), 1f)
            addRow(root, listOf("Caps","a","s","d","f","g","h","j","k","l","Enter"), dp(39), 1f)
            addRow(root, listOf("⇧","z","x","c","v","b","n","m",",",".","⇧"), dp(39), 1f)
        } else {
            addRow(root, listOf("!","@","#","$","%","^","&","*","(",")","⌫"), dp(39), 1f)
            addRow(root, listOf("[","]","{","}","<",">","=","+","-","/","\\","Enter"), dp(39), 1f)
            addRow(root, listOf("~","`","|",":",";","'","\"","?","_","Enter"), dp(39), 1f)
        }

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        bottom.addView(key(if (ctrl) "CTRL ✓" else "CTRL", 1f) { ctrl = !ctrl; refreshKeyboard() }, weighted(dp(40), 1f))
        bottom.addView(key(if (alt) "ALT ✓" else "ALT", 1f) { alt = !alt; refreshKeyboard() }, weighted(dp(40), 1f))
        bottom.addView(key(if (win) "WIN ✓" else "WIN", 1f) { win = !win; refreshKeyboard() }, weighted(dp(40), 1f))
        bottom.addView(key("Fn", 0.8f) { symbols = !symbols; refreshKeyboard() }, weighted(dp(40), 0.8f))
        bottom.addView(key("Spasi", 4.2f) { currentInputConnection?.commitText(" ", 1); clearOneShotModifiers() }, weighted(dp(40), 4.2f))
        bottom.addView(key("-", 0.8f) { type("-") }, weighted(dp(40), 0.8f))
        bottom.addView(key("↵", 1.2f) { pressKey(KeyEvent.KEYCODE_ENTER) }, weighted(dp(40), 1.2f))
        root.addView(bottom)
        return root
    }

    private fun addRow(root: LinearLayout, labels: List<String>, height: Int, keyWeight: Float) {
        val line = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            orientation = LinearLayout.HORIZONTAL
        }
        labels.forEach { label ->
            val weight = when (label) {
                "Ctrl", "Alt", "Caps", "Tab", "Esc", "Enter", "⇧", "⌫", "CTRL", "ALT", "WIN", "Del" -> 1.45f
                else -> keyWeight
            }
            line.addView(key(label, weight) { handle(label) }, weighted(height, weight).apply {
                marginStart = dp(1); marginEnd = dp(1); bottomMargin = dp(3)
            })
        }
        root.addView(line)
    }

    private fun handle(label: String) {
        when (label) {
            "⇧" -> { shifted = !shifted; refreshKeyboard() }
            "Caps" -> { shifted = !shifted; refreshKeyboard() }
            "Ctrl" -> { ctrl = !ctrl; refreshKeyboard() }
            "Alt" -> { alt = !alt; refreshKeyboard() }
            "Esc" -> pressKey(KeyEvent.KEYCODE_ESCAPE)
            "Del" -> pressKey(KeyEvent.KEYCODE_FORWARD_DEL)
            "Tab" -> pressKey(KeyEvent.KEYCODE_TAB)
            "Enter", "↵" -> pressKey(KeyEvent.KEYCODE_ENTER)
            "F1" -> pressKey(KeyEvent.KEYCODE_F1)
            "F2" -> pressKey(KeyEvent.KEYCODE_F2)
            "F3" -> pressKey(KeyEvent.KEYCODE_F3)
            "F4" -> pressKey(KeyEvent.KEYCODE_F4)
            "F5" -> pressKey(KeyEvent.KEYCODE_F5)
            "F6" -> pressKey(KeyEvent.KEYCODE_F6)
            "F7" -> pressKey(KeyEvent.KEYCODE_F7)
            "F8" -> pressKey(KeyEvent.KEYCODE_F8)
            "F9" -> pressKey(KeyEvent.KEYCODE_F9)
            "F10" -> pressKey(KeyEvent.KEYCODE_F10)
            "F11" -> pressKey(KeyEvent.KEYCODE_F11)
            "F12" -> pressKey(KeyEvent.KEYCODE_F12)
            "⌫" -> {
                val ic = currentInputConnection
                if (ic != null) {
                    ic.deleteSurroundingText(1, 0)
                    if (ctrl) sendKeyPair(KeyEvent.KEYCODE_DEL, metaState())
                }
                clearOneShotModifiers()
            }
            "←" -> pressKey(KeyEvent.KEYCODE_DPAD_LEFT)
            "→" -> pressKey(KeyEvent.KEYCODE_DPAD_RIGHT)
            "↑" -> pressKey(KeyEvent.KEYCODE_DPAD_UP)
            "↓" -> pressKey(KeyEvent.KEYCODE_DPAD_DOWN)
            else -> type(label)
        }
    }

    private fun type(raw: String) {
        val ic = currentInputConnection ?: return
        val value = if (shifted) shiftedValue(raw) else raw
        val keyCode = keyCodeFor(value)
        if (ctrl || alt || win) {
            if (keyCode != KeyEvent.KEYCODE_UNKNOWN) {
                sendKeyPair(keyCode, metaState())
            } else {
                ic.commitText(value, 1)
            }
        } else {
            ic.commitText(value, 1)
        }
        clearOneShotModifiers()
    }

    private fun shiftedValue(value: String): String {
        if (value.length != 1) return value
        val c = value[0]
        val symbols = mapOf('1' to '!', '2' to '@', '3' to '#', '4' to '$', '5' to '%',
            '6' to '^', '7' to '&', '8' to '*', '9' to '(', '0' to ')',
            ',' to '<', '.' to '>', '/' to '?', ';' to ':', '\'' to '"',
            '[' to '{', ']' to '}', '-' to '_', '=' to '+', '`' to '~', '\\' to '|')
        return symbols[c]?.toString() ?: c.uppercaseChar().toString()
    }

    private fun keyCodeFor(value: String): Int {
        if (value.length != 1) return KeyEvent.KEYCODE_UNKNOWN
        val c = value[0].uppercaseChar()
        if (c in 'A'..'Z') return KeyEvent.KEYCODE_A + (c - 'A')
        if (c in '0'..'9') return when (c) {
            '0' -> KeyEvent.KEYCODE_0
            else -> KeyEvent.KEYCODE_1 + (c - '1')
        }
        return when (value) {
            " " -> KeyEvent.KEYCODE_SPACE
            "," -> KeyEvent.KEYCODE_COMMA
            "." -> KeyEvent.KEYCODE_PERIOD
            "/" -> KeyEvent.KEYCODE_SLASH
            ";" -> KeyEvent.KEYCODE_SEMICOLON
            "'" , "\"" -> KeyEvent.KEYCODE_APOSTROPHE
            "[" , "{" -> KeyEvent.KEYCODE_LEFT_BRACKET
            "]" , "}" -> KeyEvent.KEYCODE_RIGHT_BRACKET
            "-" , "_" -> KeyEvent.KEYCODE_MINUS
            "=" , "+" -> KeyEvent.KEYCODE_EQUALS
            "`" , "~" -> KeyEvent.KEYCODE_GRAVE
            "\\" , "|" -> KeyEvent.KEYCODE_BACKSLASH
            else -> KeyEvent.KEYCODE_UNKNOWN
        }
    }

    private fun metaState(): Int {
        var state = 0
        if (shifted) state = state or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        if (ctrl) state = state or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        if (alt) state = state or KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        if (win) state = state or KeyEvent.META_META_ON
        return state
    }

    private fun pressKey(code: Int) {
        if (ctrl || alt || win || shifted) sendKeyPair(code, metaState())
        else {
            val ic = currentInputConnection
            if (ic != null) sendKeyPair(code, 0) else if (code == KeyEvent.KEYCODE_ENTER) {
                currentInputConnection?.performEditorAction(EditorInfo.IME_ACTION_DONE)
            }
        }
        clearOneShotModifiers()
    }

    private fun sendKeyPair(code: Int, meta: Int) {
        val ic: InputConnection = currentInputConnection ?: return
        ic.sendKeyEvent(KeyEvent(android.os.SystemClock.uptimeMillis(), android.os.SystemClock.uptimeMillis(),
            KeyEvent.ACTION_DOWN, code, 0, meta, KeyEvent.VIRTUAL_KEYBOARD, 0, 0, KeyEvent.SOURCE_KEYBOARD))
        ic.sendKeyEvent(KeyEvent(android.os.SystemClock.uptimeMillis(), android.os.SystemClock.uptimeMillis(),
            KeyEvent.ACTION_UP, code, 0, meta, KeyEvent.VIRTUAL_KEYBOARD, 0, 0, KeyEvent.SOURCE_KEYBOARD))
    }

    private fun clearOneShotModifiers() {
        shifted = false
        ctrl = false
        alt = false
        win = false
        refreshKeyboard()
    }

    private fun refreshKeyboard() {
        setInputView(onCreateInputView())
    }

    private fun weighted(height: Int, weight: Float) = LinearLayout.LayoutParams(0, height, weight)

    private fun key(label: String, weight: Float, action: () -> Unit) = TextView(this).apply {
        text = label
        textSize = when {
            label.length >= 4 -> 9f
            label.length > 1 -> 11f
            else -> 15f
        }
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply {
            val active = (label == "CTRL" && ctrl) || (label == "ALT" && alt) ||
                (label == "WIN" && win) || (label == "⇧" && shifted)
            setColor(if (label == "Spasi") Color.rgb(68, 86, 118) else if (active) Color.rgb(64, 104, 158) else Color.rgb(43, 53, 73))
            cornerRadius = dp(5).toFloat()
        }
        isClickable = true
        isFocusable = true
        setOnClickListener { action() }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

package com.sakuhome.launcher

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.view.Gravity
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.view.MotionEvent
import android.widget.PopupWindow
import android.content.ClipData
import android.content.ClipboardManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.EditText
import android.text.TextWatcher
import android.text.Editable
import android.view.inputmethod.InputMethodManager

/**
 * Saku Keyboard: lightweight native IME with a soft, iOS-inspired key layout.
 * All text is sent through InputConnection, so it works in normal text fields.
 */
class SakuKeyboardService : InputMethodService() {
    private var shifted = false
    private var symbols = false
    private var keyboardRoot: LinearLayout? = null
    private var emojiMode = false
    private var emojiCategory = 0
    private var emojiSearch = ""
    private var emojiSearchField: EditText? = null
    private var activeKeyPreview: PopupWindow? = null

    override fun onCreateInputView(): View {
        return buildKeyboard().also { keyboardRoot = it }
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        symbols = false
        emojiMode = false
        shifted = attribute?.let {
            it.inputType and android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS != 0 ||
                it.inputType and android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES != 0
        } ?: false
        refreshKeyboard()
    }

    private fun buildKeyboard(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            minimumHeight = dp(keyboardHeightDp())
            setPadding(dp(4), dp(10), dp(4), dp(12))
            setBackgroundColor(if (isDark()) Color.rgb(34, 36, 41) else Color.rgb(218, 222, 229))
        }
        keyboardRoot = root
        if (emojiMode) {
            buildEmojiPicker(root)
            return root
        }
        addEditingToolbar(root)
        addRow(root, if (symbols) listOf("1","2","3","4","5","6","7","8","9","0") else "qwertyuiop".map { it.toString() })
        addRow(root, if (symbols) listOf("@","#","$","%","&","-","+","(",")","/") else "asdfghjkl".map { it.toString() }, sidePadding = 6)
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
        bottom.addView(key("😊", special = true, description = "Buka panel emoji") { emojiMode = true; emojiCategory = 0; emojiSearch = ""; refreshKeyboard() }, weighted(0.8f))
        bottom.addView(key("spasi", special = true, description = "Spasi") { commit(" ") }, weighted(4.2f))
        bottom.addView(key(".", special = true) { commit(".") }, weighted(0.8f))
        bottom.addView(key(actionLabel(), special = true, description = "Aksi berikutnya") {
            performEditorAction()
        }, weighted(1.35f))
        root.addView(bottom, rowParams())
        return root
    }


    private fun addEditingToolbar(root: LinearLayout) {
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fun tool(label: String, description: String, action: () -> Unit) {
            val button = key(label, special = true, description = description, action = action)
            button.textSize = 15f
            button.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(9).toFloat()
                setColor(if (isDark()) Color.rgb(54, 57, 64) else Color.rgb(195, 201, 211))
            }
            toolbar.addView(button, LinearLayout.LayoutParams(0, dp(30), 1f).apply {
                setMargins(dp(2), 0, dp(2), 0)
            })
        }
        tool("⧉", "Salin teks yang dipilih") { copySelectedText() }
        tool("▣", "Tempel dari papan klip") { pasteClipboardText() }
        tool("Pilih", "Pilih semua teks") {
            currentInputConnection?.performContextMenuAction(android.R.id.selectAll)
        }
        tool("←", "Geser kursor ke kiri") { moveCursor(-1) }
        tool("→", "Geser kursor ke kanan") { moveCursor(1) }
        root.addView(toolbar, LinearLayout.LayoutParams(-1, dp(34)).apply {
            bottomMargin = dp(3)
        })
    }

    private fun copySelectedText() {
        val ic = currentInputConnection ?: return
        val selected = ic.getSelectedText(0)?.toString().orEmpty()
        if (selected.isNotEmpty()) {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Saku Keyboard", selected))
        } else {
            ic.performContextMenuAction(android.R.id.copy)
        }
    }

    private fun pasteClipboardText() {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)
        if (!text.isNullOrEmpty()) currentInputConnection?.commitText(text, 1)
        else currentInputConnection?.performContextMenuAction(android.R.id.paste)
    }

    private fun moveCursor(delta: Int) {
        val ic = currentInputConnection ?: return
        val keyCode = if (delta < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    private data class EmojiGroup(val title: String, val icon: String, val values: List<String>)

    private val emojiGroups = listOf(
        EmojiGroup("Sering", "🕘", listOf("😂","❤️","🥹","😭","😍","🙏","😊","🤣","✨","🔥","👍","🥰","😘","😎","💕","🎉","🤔","😅","🙌","💯","👏","🤗","😴","💖")),
        EmojiGroup("Wajah", "😀", listOf("😀","😃","😄","😁","😆","😅","😂","🤣","🥲","🥹","☺️","😊","😇","🙂","🙃","😉","😌","😍","🥰","😘","😗","😙","😚","😋","😛","😜","🤪","😝","🤑","🤗","🤭","🫢","🫣","🤫","🤔","🫡","🤐","🤨","😐","😑","😶","🫥","😏","😒","🙄","😬","😮‍💨","🤥","😌","😔","😪","🤤","😴","😷","🤒","🤕","🤢","🤮","🤧","🥵","🥶","🥴","😵","🤯","🥳","🥸","😎","🤓","🧐","😕","🫤","😟","🙁","☹️","😮","😯","😲","😳","🥺","😦","😧","😨","😰","😥","😢","😭","😱","😖","😣","😞","😓","😩","😫","🥱","😤","😡","😠","🤬","😈","👿","💀","☠️","💩","🤡","👹","👺","👻","👽","👾","🤖","🎃")),
        EmojiGroup("Orang", "👋", listOf("👋","🤚","🖐️","✋","🖖","🫱","🫲","🫳","🫴","👌","🤌","🤏","✌️","🤞","🫰","🤟","🤘","🤙","👈","👉","👆","🖕","👇","☝️","👍","👎","✊","👊","🤛","🤜","👏","🙌","🫶","🤲","🙏","✍️","💅","🤳","💪","🦾","🦿","🦵","🦶","👂","🦻","👃","🧠","🫀","🫁","🦷","🦴","👀","👁️","👅","👄","🫦","👶","🧒","👦","👧","🧑","👨","👩","🧔","👱","👴","👵","🙍","🙎","🙅","🙆","💁","🙋","🧏","🙇","🤦","🤷","👮","🕵️","💂","🥷","👷","🫅","🤴","👸","👳","👲","🧕","🤵","👰","🤰","🫃","🫄","🤱","👼","🎅","🤶","🧑‍🎄","🦸","🦹","🧙","🧚","🧛","🧜","🧝","🧞","🧟","💆","💇","🚶","🧍","🧎","🏃","💃","🕺","🕴️","👯","🧖","🧗","🤺","🏇","⛷️","🏂","🏌️","🏄","🚣","🏊","⛹️","🏋️","🚴","🚵","🤸","🤼","🤽","🤾","🤹","🧘","🛀","🛌")),
        EmojiGroup("Hewan", "🐻", listOf("🐶","🐱","🐭","🐹","🐰","🦊","🐻","🐼","🐻‍❄️","🐨","🐯","🦁","🐮","🐷","🐸","🐵","🙈","🙉","🙊","🐒","🐔","🐧","🐦","🐤","🦆","🦅","🦉","🦇","🐺","🐗","🐴","🦄","🐝","🪱","🐛","🦋","🐌","🐞","🐜","🪰","🪲","🪳","🦟","🦗","🕷️","🦂","🐢","🐍","🦎","🦖","🦕","🐙","🦑","🦐","🦞","🦀","🐡","🐠","🐟","🐬","🐳","🐋","🦈","🦭","🐊","🐅","🐆","🦓","🦍","🦧","🐘","🦛","🦏","🐪","🐫","🦒","🦘","🦬","🐃","🐂","🐄","🐎","🐖","🐏","🐑","🦙","🐐","🦌","🐕","🐩","🦮","🐈","🐈‍⬛","🪶","🐓","🦃","🦚","🦜","🦢","🦩","🕊️","🐇","🦝","🦨","🦡","🦫","🦦","🦥","🐁","🐀","🐿️","🦔")),
        EmojiGroup("Makanan", "🍕", listOf("🍏","🍎","🍐","🍊","🍋","🍌","🍉","🍇","🍓","🫐","🍈","🍒","🍑","🥭","🍍","🥥","🥝","🍅","🍆","🥑","🥦","🥬","🥒","🌶️","🫑","🌽","🥕","🫒","🧄","🧅","🥔","🍠","🫚","🥐","🥯","🍞","🥖","🥨","🧀","🥚","🍳","🧈","🥞","🧇","🥓","🥩","🍗","🍖","🌭","🍔","🍟","🍕","🫓","🥪","🥙","🧆","🌮","🌯","🫔","🥗","🥘","🫕","🥫","🍝","🍜","🍲","🍛","🍣","🍱","🥟","🦪","🍤","🍙","🍚","🍘","🍥","🥠","🥮","🍢","🍡","🍧","🍨","🍦","🥧","🧁","🍰","🎂","🍮","🍭","🍬","🍫","🍿","🍩","🍪","🌰","🥜","🫘","🍯","🥛","🍼","☕","🫖","🍵","🧃","🥤","🧋","🍶","🍺","🍻","🥂","🍷","🥃","🍸","🍹","🧉","🍾")),
        EmojiGroup("Aktivitas", "⚽", listOf("⚽","🏀","🏈","⚾","🥎","🎾","🏐","🏉","🥏","🎱","🪀","🏓","🏸","🏒","🏑","🥍","🏏","🪃","🥅","⛳","🪁","🛝","🏹","🎣","🤿","🥊","🥋","🎽","🛹","🛼","🛷","⛸️","🥌","🎿","⛷️","🏂","🪂","🏋️","🤼","🤸","⛹️","🤺","🏇","🧘","🏄","🏊","🚴","🚵","🎮","🕹️","🎲","♟️","🎯","🎳","🎮","🧩","🎭","🎨","🧵","🪡","🧶","🎼","🎤","🎧","🎹","🥁","🪘","🎷","🎺","🪗","🎸","🪕","🎻","🪈","🎬","🎞️","📸","📷","🎥","📺","📻","🎙️","🎚️","🎛️","🧸","🪅","🎁","🎈","🎉","🎊","🎀","🎗️","🏆","🥇","🥈","🥉","🏅","🎖️","🪄","🪩")),
        EmojiGroup("Perjalanan", "🚗", listOf("🚗","🚕","🚙","🚌","🚎","🏎️","🚓","🚑","🚒","🚐","🛻","🚚","🚛","🚜","🛵","🏍️","🛺","🚲","🛴","🛹","🛼","🚏","🛣️","🛤️","🛢️","⛽","🚨","🚥","🚦","🛑","🚧","⚓","🛟","⛵","🛶","🚤","🛳️","⛴️","🛥️","🚢","✈️","🛩️","🛫","🛬","🪂","💺","🚁","🚟","🚠","🚡","🛰️","🚀","🛸","🌍","🌎","🌏","🌐","🗺️","🗾","🧭","🏔️","⛰️","🌋","🗻","🏕️","🏖️","🏜️","🏝️","🏞️","🏟️","🏛️","🏗️","🧱","🪨","🪵","🛖","🏘️","🏚️","🏠","🏡","🏢","🏣","🏤","🏥","🏦","🏨","🏩","🏪","🏫","🏬","🏭","🏯","🏰","💒","🗼","🗽","⛪","🕌","🛕","🕍","⛩️","🕋","⛲","⛺","🌁","🌃","🌄","🌅","🌆","🌇","🌉","♨️","🎠","🛝","🎡","🎢","💈")),
        EmojiGroup("Simbol", "💖", listOf("❤️","🩷","🧡","💛","💚","💙","🩵","💜","🤎","🖤","🩶","🤍","💔","❤️‍🔥","❤️‍🩹","❣️","💕","💞","💓","💗","💖","💘","💝","💟","☮️","✝️","☪️","🕉️","☸️","✡️","🔯","🕎","☯️","☦️","🛐","⛎","♈","♉","♊","♋","♌","♍","♎","♏","♐","♑","♒","♓","🆔","⚛️","🉑","☢️","☣️","📴","📳","🈶","🈚","🈸","🈺","🈷️","✴️","🆚","💮","🉐","㊙️","㊗️","🈴","🈵","🈹","🈲","🅰️","🅱️","🆎","🅾️","🆑","🅿️","🆘","❌","⭕","🚫","💯","💢","♨️","🚷","🚯","🚳","🚱","🔞","📵","🚭","❗","❕","❓","❔","‼️","⁉️","🔅","🔆","〽️","⚠️","🚸","🔱","⚜️","🔰","♻️","✅","❎","🌐","💠","Ⓜ️","🌀","💤","🏧","🚾","♿","🅿️","🛗","🈳","🈂️","🛂","🛃","🛄","🛅")),
        EmojiGroup("Bendera", "🏳️", listOf("🏳️","🏴","🏁","🚩","🎌","🏴‍☠️","🇮🇩","🇺🇸","🇬🇧","🇯🇵","🇰🇷","🇨🇳","🇸🇬","🇲🇾","🇹🇭","🇵🇭","🇻🇳","🇦🇺","🇨🇦","🇫🇷","🇩🇪","🇮🇹","🇪🇸","🇧🇷","🇮🇳","🇵🇰","🇸🇦","🇹🇷","🇷🇺","🇺🇦","🇳🇿","🇿🇦"))
    )

    private fun buildEmojiPicker(root: LinearLayout) {
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(key("ABC", special = true, description = "Kembali ke huruf") {
            emojiMode = false
            refreshKeyboard()
        }, LinearLayout.LayoutParams(dp(58), dp(42)))
        val search = EditText(this).apply {
            hint = "Cari emoji"
            textSize = 15f
            setSingleLine(true)
            setTextColor(if (isDark()) Color.WHITE else Color.BLACK)
            setHintTextColor(if (isDark()) Color.LTGRAY else Color.DKGRAY)
            setBackground(GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(if (isDark()) Color.rgb(65, 68, 76) else Color.WHITE)
            })
            setPadding(dp(12), 0, dp(8), 0)
            setText(emojiSearch)
        }
        emojiSearchField = search
        top.addView(search, LinearLayout.LayoutParams(0, dp(42), 1f).apply { setMargins(dp(6), 0, dp(6), 0) })
        top.addView(key("⌫", special = true, description = "Hapus karakter") {
            currentInputConnection?.deleteSurroundingText(1, 0)
        }, LinearLayout.LayoutParams(dp(42), dp(42)))
        root.addView(top, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(5) })

        val categories = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        emojiGroups.forEachIndexed { index, group ->
            categories.addView(key(group.icon, special = true, description = group.title) {
                emojiCategory = index
                emojiSearch = ""
                refreshKeyboard()
            }, LinearLayout.LayoutParams(0, dp(38), 1f).apply { setMargins(dp(1), 0, dp(1), 0) })
        }
        root.addView(categories, LinearLayout.LayoutParams(-1, dp(40)).apply { bottomMargin = dp(5) })

        val grid = android.widget.GridLayout(this).apply {
            columnCount = 8
            alignmentMode = android.widget.GridLayout.ALIGN_BOUNDS
            useDefaultMargins = false
        }
        val query = emojiSearch.trim()
        val source = if (query.isNotEmpty()) emojiGroups.flatMap { it.values }.distinct() else emojiGroups[emojiCategory].values
        val shown = if (query.isNotEmpty()) {
            val words = mapOf(
                "senyum" to listOf("😊","😄","😁","🙂","🥰"),
                "sedih" to listOf("😢","😭","🥹","☹️"),
                "ketawa" to listOf("😂","🤣","😆","😅"),
                "cinta" to listOf("❤️","😍","🥰","😘","💕","💖"),
                "hati" to listOf("❤️","🩷","🧡","💛","💚","💙","💜","🖤","🤍"),
                "marah" to listOf("😠","😡","🤬","💢"),
                "makan" to listOf("🍚","🍜","🍕","🍔","🍟","🍎"),
                "kucing" to listOf("🐱","😺","😸","😹","😻","😼","😽","🙀","😿","😾"),
                "anjing" to listOf("🐶","🐕","🐩","🦮","🐕‍🦺"),
                "lucu" to listOf("🥹","🥰","😂","🐱","🐼","✨"),
                "api" to listOf("🔥","❤️‍🔥"),
                "terima kasih" to listOf("🙏","🥹","❤️")
            )
            words.entries.firstOrNull { query.contains(it.key) }?.value ?: source
        } else source
        val filtered = shown.distinct().take(160)
        filtered.forEach { emoji ->
            val cell = TextView(this).apply {
                text = emoji
                textSize = 27f
                gravity = Gravity.CENTER
                contentDescription = "Emoji $emoji"
                setOnClickListener {
                            currentInputConnection?.commitText(emoji, 1)
                }
            }
            grid.addView(cell, android.widget.GridLayout.LayoutParams(android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f), android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED)).apply { height = dp(43); setMargins(dp(2), dp(1), dp(2), dp(1)) })
        }
        val scroller = android.widget.ScrollView(this).apply {
            isFillViewport = true
            addView(grid, android.view.ViewGroup.LayoutParams(-1, -2))
        }
        root.addView(scroller, LinearLayout.LayoutParams(-1, 0, 1f))
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                emojiSearch = s?.toString().orEmpty()
                // Rebuild the grid while keeping the search field focus and keyboard visible.
                val text = emojiSearch
                val newSource = if (text.isBlank()) emojiGroups[emojiCategory].values else emojiGroups.flatMap { it.values }.distinct()
                val newShown = if (text.isBlank()) newSource else {
                    val matches = mapOf(
                        "senyum" to listOf("😊","😄","😁","🙂","🥰"),
                        "sedih" to listOf("😢","😭","🥹","☹️"),
                        "ketawa" to listOf("😂","🤣","😆","😅"),
                        "cinta" to listOf("❤️","😍","🥰","😘","💕","💖"),
                        "hati" to listOf("❤️","🩷","🧡","💛","💚","💙","💜","🖤","🤍"),
                        "marah" to listOf("😠","😡","🤬","💢"),
                        "makan" to listOf("🍚","🍜","🍕","🍔","🍟","🍎"),
                        "kucing" to listOf("🐱","😺","😸","😹","😻","😼","😽","🙀","😿","😾"),
                        "anjing" to listOf("🐶","🐕","🐩","🦮","🐕‍🦺"),
                        "lucu" to listOf("🥹","🥰","😂","🐱","🐼","✨"),
                        "api" to listOf("🔥","❤️‍🔥"),
                        "terima kasih" to listOf("🙏","🥹","❤️")
                    )
                    matches.entries.firstOrNull { text.contains(it.key) }?.value ?: newSource
                }
                val filtered = newShown.distinct().take(160)
                grid.removeAllViews()
                filtered.forEach { emoji ->
                    val cell = TextView(this@SakuKeyboardService).apply {
                        setText(emoji)
                        textSize = 27f
                        gravity = Gravity.CENTER
                        contentDescription = "Emoji $emoji"
                        setOnClickListener {
                                            currentInputConnection?.commitText(emoji, 1)
                        }
                    }
                    grid.addView(cell, android.widget.GridLayout.LayoutParams(android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f), android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED)).apply { height = dp(43); setMargins(0, dp(1), 0, dp(1)) })
                }
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
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
        textSize = if (label == "spasi") 13f else 19f
        typeface = Typeface.create("sans-serif", if (special) Typeface.BOLD else Typeface.NORMAL)
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
        // This keyboard draws its own pressed-state feedback; avoid platform elevation animations.
        stateListAnimator = null
        setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.alpha = 0.82f
                    if (!special && label.length == 1 && label[0].isLetter()) {
                        showKeyPreview(view, label)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.alpha = 1f
                    dismissKeyPreview()
                }
            }
            false
        }
        setOnClickListener { action() }
        setPadding(dp(1), 0, dp(1), 0)
    }

    private fun showKeyPreview(anchor: View, value: String) {
        dismissKeyPreview()
        val preview = TextView(this).apply {
            text = value
            gravity = Gravity.CENTER
            textSize = 30f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(if (isDark()) Color.WHITE else Color.rgb(26, 30, 37))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(13).toFloat()
                setColor(if (isDark()) Color.rgb(76, 80, 89) else Color.WHITE)
                setStroke(dp(1), if (isDark()) Color.rgb(110, 115, 126) else Color.rgb(185, 191, 201))
            }
            elevation = dp(6).toFloat()
        }
        val popup = PopupWindow(preview, dp(52), dp(54), false).apply {
            isTouchable = false
            isFocusable = false
            isOutsideTouchable = false
            elevation = dp(6).toFloat()
        }
        activeKeyPreview = popup
        try {
            popup.showAsDropDown(anchor, 0, -anchor.height - dp(56))
        } catch (_: Exception) {
            activeKeyPreview = null
            popup.dismiss()
        }
    }

    private fun dismissKeyPreview() {
        activeKeyPreview?.dismiss()
        activeKeyPreview = null
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
        if (action != EditorInfo.IME_ACTION_NONE && ic.performEditorAction(action)) return

        // Some WebView/chat editors do not implement the declared IME action.
        // Fall back to Enter; insert a newline only if both key events are rejected.
        val down = ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        val up = ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        if (!down && !up) ic.commitText("\n", 1)
    }

    private fun refreshKeyboard() {
        if (keyboardRoot != null) {
            val updatedRoot = buildKeyboard()
            keyboardRoot = updatedRoot
            setInputView(updatedRoot)
        }
    }

    private fun keyboardHeightDp(): Int =
        getSharedPreferences("saku_dock_settings", MODE_PRIVATE)
            .getInt("saku_keyboard_height_dp", 340).coerceIn(240, 520)

    private fun rowParams() = LinearLayout.LayoutParams(-1, dp(((keyboardHeightDp() - 80) / 4).coerceIn(40, 110))).apply {
        bottomMargin = dp(6)
    }

    private fun weighted(weight: Float) = LinearLayout.LayoutParams(0, -1, weight).apply {
        setMargins(dp(1), dp(1), dp(1), dp(1))
    }

    private fun isDark(): Boolean =
        getSharedPreferences("saku_dock_settings", MODE_PRIVATE).getBoolean("dark_theme", true)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

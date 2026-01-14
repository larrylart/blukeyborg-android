package com.blu.blukeyborg

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import androidx.core.view.setPadding

import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

import com.blu.blukeyborg.PreferencesUtil
import com.blu.blukeyborg.R

class FullKeyboardActivity : AppCompatActivity() {

    // Simple dp -> px helper
    private fun Int.toPx(): Int =
        (this * resources.displayMetrics.density).toInt()

    private enum class KeyKind {
        NORMAL,
        MOD_SHIFT,
        MOD_CTRL,
        MOD_ALT,
        MOD_GUI,      // Win / GUI
        BACKSPACE,
        SPACE,
        ENTER,
        ARROW,
        TAB
    }

    private data class KeyDef(
        val baseLabel: String,
        val shiftedLabel: String? = null,
        val usage: Int,
        val kind: KeyKind = KeyKind.NORMAL
    )

    // Latched modifier state (applies to next non-modifier key)
    private var shiftLatched = false
    private var ctrlLatched = false
    private var altLatched = false
    private var guiLatched = false

    // References to modifier buttons so we can update their visual state
    private lateinit var shiftButton: MaterialButton
    private lateinit var ctrlButton: MaterialButton
    private lateinit var altButton: MaterialButton
    private lateinit var guiButton: MaterialButton

    // Buttons whose label should change when Shift is latched (digits & punctuation, letters)
    private val shiftSensitiveButtons = mutableListOf<Pair<KeyDef, MaterialButton>>()

    private fun enableFullscreenImmersive() {
        // Let our content go behind system bars
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        controller.hide(
            WindowInsetsCompat.Type.statusBars() or
                WindowInsetsCompat.Type.navigationBars()
        )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            enableFullscreenImmersive()
        }
    }

    override fun onResume() {
        super.onResume()
        BleHub.userActive()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Hide action bar if present for full-screen look
        supportActionBar?.hide()

        // ---- Root layout (full screen) ----
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setPadding(8.toPx())
        }

        // --- Preview + Esc + close button on the same top bar ---
        val previewBuffer = StringBuilder()
        val previewMaxLen = 64

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // Esc key on the far left
        val escKey = KeyDef("Esc", null, 0x29, KeyKind.NORMAL)

        fun createTopKeyButton(key: KeyDef): MaterialButton {
            return MaterialButton(this).apply {
                text = key.baseLabel
                isAllCaps = false
                strokeWidth = 2
                setPadding(8.toPx(), 4.toPx(), 8.toPx(), 4.toPx())
                minimumWidth = 0
                minimumHeight = 0
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    rightMargin = 8.toPx()
                }
                setOnClickListener {
                    // Esc just sends itself, no preview change
                    val mods = 0
                    BleHub.sendRawKeyTap(mods, key.usage) { ok, err ->
                        runOnUiThread {
                            if (!ok) {
                                Toast.makeText(
                                    this@FullKeyboardActivity,
                                    err ?: getString(R.string.msg_send_failed),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                }
            }
        }

        val escButton = createTopKeyButton(escKey)

        val previewText = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply {
                rightMargin = 8.toPx()
            }
            textSize = 18f
            isSingleLine = true
        }

        val closeBtn = ImageButton(this).apply {
            setImageResource(R.drawable.baseline_close_24)
            background = null
            contentDescription = getString(android.R.string.cancel)
            val size = 32.toPx()
            layoutParams = LinearLayout.LayoutParams(size, size)
            setOnClickListener { finish() }
        }

        topBar.addView(escButton)
        topBar.addView(previewText)
        topBar.addView(closeBtn)
        root.addView(topBar)

        // ---- Helpers for modifiers & preview ----

        fun computeLatchedMods(): Int {
            var mods = 0
            if (ctrlLatched) mods = mods or 0x01
            if (shiftLatched) mods = mods or 0x02
            if (altLatched) mods = mods or 0x04
            if (guiLatched) mods = mods or 0x08  // Left GUI in HID modifier byte
            return mods
        }

        fun updateModifierButtonStyles() {
            fun style(btn: MaterialButton, active: Boolean) {
                btn.alpha = if (active) 1.0f else 0.7f
                btn.strokeWidth = if (active) 4 else 2
            }
            if (this::shiftButton.isInitialized) style(shiftButton, shiftLatched)
            if (this::ctrlButton.isInitialized) style(ctrlButton, ctrlLatched)
            if (this::altButton.isInitialized) style(altButton, altLatched)
            if (this::guiButton.isInitialized) style(guiButton, guiLatched)
        }

        fun currentLabelFor(key: KeyDef): String {
            return if (shiftLatched && key.shiftedLabel != null) {
                key.shiftedLabel
            } else {
                key.baseLabel
            }
        }

        fun updateShiftSensitiveLabels() {
            val useShift = shiftLatched
            shiftSensitiveButtons.forEach { (key, btn) ->
                val label = if (useShift && key.shiftedLabel != null) key.shiftedLabel else key.baseLabel
                btn.text = label
            }
        }

        fun clearLatchedModifiers() {
            shiftLatched = false
            ctrlLatched = false
            altLatched = false
            guiLatched = false
            updateModifierButtonStyles()
            updateShiftSensitiveLabels()
        }

        fun updatePreviewForKey(kind: KeyKind, displayLabel: String) {
            when (kind) {
                KeyKind.BACKSPACE -> {
                    if (previewBuffer.isNotEmpty()) {
                        previewBuffer.deleteCharAt(previewBuffer.length - 1)
                    }
                }
                KeyKind.SPACE -> {
                    previewBuffer.append(' ')
                }
                else -> {
                    // Append single printable characters (letters, digits, punctuation).
                    if (displayLabel.length == 1) {
                        val ch = displayLabel[0]
                        if (ch.isLetterOrDigit() || "!@#$%^&*()_-+={}[]|;:'\",.<>?/".contains(ch)) {
                            previewBuffer.append(ch)
                        }
                    }
                }
            }

            // Trim from the beginning if buffer is too long
            if (previewBuffer.length > previewMaxLen) {
                val extra = previewBuffer.length - previewMaxLen
                previewBuffer.delete(0, extra)
            }

            previewText.text = previewBuffer.toString()
        }

        // ---- Key factory ----

        fun createKeyButton(
            key: KeyDef,
            weight: Float = 1f,
            shiftSensitive: Boolean = false,
            small: Boolean = false
        ): MaterialButton {
            val btn = MaterialButton(this).apply {
                text = currentLabelFor(key)
                isAllCaps = false
                strokeWidth = 2
                if (small) {
                    setPadding(8.toPx(), 6.toPx(), 8.toPx(), 6.toPx())
                    minimumHeight = 32.toPx()
                } else {
                    setPadding(12, 12, 12, 12)
                    minimumHeight = 40.toPx()
                }
                minimumWidth = 36.toPx()
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    weight
                ).apply {
                    val m = 2.toPx()
                    setMargins(m, m, m, m)
                }
            }

            if (shiftSensitive) {
                shiftSensitiveButtons.add(key to btn)
            }

            btn.setOnClickListener {
                when (key.kind) {
                    KeyKind.MOD_SHIFT -> {
                        shiftLatched = !shiftLatched
                        updateModifierButtonStyles()
                        updateShiftSensitiveLabels()
                    }
                    KeyKind.MOD_CTRL -> {
                        ctrlLatched = !ctrlLatched
                        updateModifierButtonStyles()
                    }
                    KeyKind.MOD_ALT -> {
                        altLatched = !altLatched
                        updateModifierButtonStyles()
                    }
                    KeyKind.MOD_GUI -> {
                        guiLatched = !guiLatched
                        updateModifierButtonStyles()
                    }
                    else -> {
                        val displayLabel = currentLabelFor(key)
                        updatePreviewForKey(key.kind, displayLabel)

                        val mods = computeLatchedMods()
                        BleHub.sendRawKeyTap(mods, key.usage) { ok, err ->
                            runOnUiThread {
                                if (!ok) {
                                    Toast.makeText(
                                        this@FullKeyboardActivity,
                                        err ?: getString(R.string.msg_send_failed),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }

                        // After a "real" key, clear modifiers
                        clearLatchedModifiers()
                    }
                }
            }

            return btn
        }

        // ---- Define keys based on selected layout (from preferences) ----
        val layoutCode = PreferencesUtil.getKeyboardLayout(this)
        val layoutDef = buildLayout(layoutCode)

        val row1 = layoutDef.row1
        val row2 = layoutDef.row2
        val row3 = layoutDef.row3
        val row4 = layoutDef.row4
        val backslashKey = layoutDef.backslashKey

        // NEW: standard key at start of number row: ` ~
        val graveKey = KeyDef("`", "~", 0x35, KeyKind.NORMAL)

        // NEW: Caps Lock key before 'a' row
        val capsKey = KeyDef("Caps", null, 0x39, KeyKind.NORMAL)

        val spaceKey = KeyDef("␣", null, 0x2C, KeyKind.SPACE)
        val enterKey = KeyDef("⏎", null, 0x28, KeyKind.ENTER)
        val backspace = KeyDef("⌫", null, 0x2A, KeyKind.BACKSPACE)
        val tabKey = KeyDef("Tab", null, 0x2B, KeyKind.TAB)

        val shiftKey = KeyDef("Shift", null, 0x00, KeyKind.MOD_SHIFT)
        val winKey = KeyDef("Win", null, 0x00, KeyKind.MOD_GUI)
        val ctrlKey = KeyDef("Ctrl", null, 0x00, KeyKind.MOD_CTRL)
        val altKey = KeyDef("Alt", null, 0x00, KeyKind.MOD_ALT)

        val left = KeyDef("←", null, 0x50, KeyKind.ARROW)
        val down = KeyDef("↓", null, 0x51, KeyKind.ARROW)
        val up = KeyDef("↑", null, 0x52, KeyKind.ARROW)
        val right = KeyDef("→", null, 0x4F, KeyKind.ARROW)

        val arrowWidth = 60.toPx()

        // Keyboard container (5 rows total)
        val kbContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        // ---- Row 1: ` | digits | Backspace ----
        // (Tab moved OFF this row; `~ key added at start)
        run {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
            }

            // ` ~ (standard key left of 1)
            row.addView(createKeyButton(graveKey, weight = 1f, shiftSensitive = true))

            // 1..0 - =
            row1.forEach { key ->
                val shiftSensitive = key.shiftedLabel != null
                row.addView(createKeyButton(key, weight = 1f, shiftSensitive = shiftSensitive))
            }

            // Backspace (wider on the right)
            row.addView(
                createKeyButton(backspace, weight = 1.5f, shiftSensitive = false)
            )

            kbContainer.addView(row)
        }

        // ---- Row 2: Tab | Q..] \ ----
        // (Tab moved to start of Q row like standard)
        run {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
            }

            // Tab at start of Q row
            row.addView(createKeyButton(tabKey, weight = 1.5f, shiftSensitive = false))

            row2.forEach { key ->
                val shiftSensitive = key.shiftedLabel != null
                row.addView(createKeyButton(key, weight = 1f, shiftSensitive = shiftSensitive))
            }

            // \ | at the end of Q row
            row.addView(
                createKeyButton(backslashKey, weight = 1.2f, shiftSensitive = true)
            )

            kbContainer.addView(row)
        }

        // ---- Rows 3+4 combined:
        // Left column has (Caps + A-row) + (Shift + Z-row + Up), right column is vertical Enter spanning both rows.
        run {
            val row34Wrap = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    2f
                )
            }

            // Left side: stacked rows
            val leftCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    1f
                )
            }

            // ---- Row 3: Caps | A..' ----
            val aRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
            }

            // Caps at start of ASD row
            aRow.addView(createKeyButton(capsKey, weight = 1.5f, shiftSensitive = false))

            row3.forEach { key ->
                val shiftSensitive = key.shiftedLabel != null
                aRow.addView(createKeyButton(key, weight = 1f, shiftSensitive = shiftSensitive))
            }

            leftCol.addView(aRow)

            // ---- Row 4: Shift | Z.. / | Up ----
            // (Shift moved up like standard; removed blank spacer before Up)
            val zRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
            }

            // Shift at start of Z row
            shiftButton = createKeyButton(shiftKey, weight = 1.8f, shiftSensitive = false, small = false)
            zRow.addView(shiftButton)

            row4.forEach { key ->
                val shiftSensitive = key.shiftedLabel != null
                zRow.addView(
                    createKeyButton(
                        key,
                        weight = 1f,
                        shiftSensitive = shiftSensitive,
                        small = true
                    )
                )
            }

            // Up arrow immediately after '/' (no blank spacer)
            zRow.addView(
                createKeyButton(up, weight = 0f, shiftSensitive = false, small = false).apply {
                    (layoutParams as LinearLayout.LayoutParams).width = arrowWidth
                }
            )

            leftCol.addView(zRow)

            // Right side: ENTER spanning both rows
            val rightCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    arrowWidth,
                    LinearLayout.LayoutParams.MATCH_PARENT
                ).apply {
                    val m = 2.toPx()
                    setMargins(m, m, m, m)
                }
            }

            val enterBtn = createKeyButton(enterKey, weight = 0f, shiftSensitive = false).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.MATCH_PARENT
                )
            }
            rightCol.addView(enterBtn)

            row34Wrap.addView(leftCol)
            row34Wrap.addView(rightCol)

            kbContainer.addView(row34Wrap)
        }

        // ---- Row 5: Ctrl | Win | big Space | Alt | ← ↓ → ----
        // (Ctrl moved into the old Shift position)
        run {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
            }

            val modsAndSpace = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
            }

            ctrlButton = createKeyButton(ctrlKey, weight = 1.2f, shiftSensitive = false)
            guiButton = createKeyButton(winKey, weight = 1.1f, shiftSensitive = false)
            val spaceBtn = createKeyButton(spaceKey, weight = 4f, shiftSensitive = false)
            altButton = createKeyButton(altKey, weight = 1.1f, shiftSensitive = false)

            modsAndSpace.addView(ctrlButton)
            modsAndSpace.addView(guiButton)
            modsAndSpace.addView(spaceBtn)
            modsAndSpace.addView(altButton)

            val arrowRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            arrowRow.addView(
                createKeyButton(left, weight = 0f, shiftSensitive = false, small = true).apply {
                    (layoutParams as LinearLayout.LayoutParams).width = arrowWidth
                }
            )
            arrowRow.addView(
                createKeyButton(down, weight = 0f, shiftSensitive = false, small = true).apply {
                    (layoutParams as LinearLayout.LayoutParams).width = arrowWidth
                }
            )
            arrowRow.addView(
                createKeyButton(right, weight = 0f, shiftSensitive = false, small = true).apply {
                    (layoutParams as LinearLayout.LayoutParams).width = arrowWidth
                }
            )

            row.addView(modsAndSpace)
            row.addView(arrowRow)

            kbContainer.addView(row)
        }

        root.addView(kbContainer)

        // Initialize modifier visuals
        updateModifierButtonStyles()
        updateShiftSensitiveLabels()

        setContentView(root)
    }

    // Describes the key labels for a given layout
    private data class LayoutDef(
        val row1: List<KeyDef>,   // number row + - =
        val row2: List<KeyDef>,   // Q-row
        val row3: List<KeyDef>,   // A-row
        val row4: List<KeyDef>,   // Z-row
        val backslashKey: KeyDef  // extra key at end of Q-row
    )

    private fun buildLayout(layoutCode: String?): LayoutDef {
        val code = layoutCode ?: "US_WINLIN"

        return when (code) {
            "US_WINLIN", "US_MAC" -> buildUsLayout()
            "UK_WINLIN", "UK_MAC" -> buildUkLayout()
            "IE_WINLIN", "IE_MAC" -> buildIeLayout()
            "DE_WINLIN", "DE_MAC" -> buildDeLayout()
            "FR_WINLIN", "FR_MAC" -> buildFrLayout()
            "ES_WINLIN", "ES_MAC" -> buildEsLayout()
            "IT_WINLIN", "IT_MAC" -> buildItLayout()
            "PT_PT_WINLIN", "PT_PT_MAC" -> buildPtPtLayout()
            "PT_BR_WINLIN", "PT_BR_MAC" -> buildPtBrLayout()
            "SE_WINLIN" -> buildSeLayout()
            "NO_WINLIN" -> buildNoLayout()
            "DK_WINLIN" -> buildDkLayout()
            "FI_WINLIN" -> buildFiLayout()
            "CH_DE_WINLIN" -> buildChDeLayout()
            "CH_FR_WINLIN" -> buildChFrLayout()
            "TR_WINLIN", "TR_MAC" -> buildTrLayout()
            else -> buildUsLayout()
        }
    }

    // --- Layout builders (UNCHANGED) ---

    private fun buildUsLayout(): LayoutDef {
        val row1 = listOf(
            KeyDef("1", "!", 0x1E),
            KeyDef("2", "@", 0x1F),
            KeyDef("3", "#", 0x20),
            KeyDef("4", "$", 0x21),
            KeyDef("5", "%", 0x22),
            KeyDef("6", "^", 0x23),
            KeyDef("7", "&", 0x24),
            KeyDef("8", "*", 0x25),
            KeyDef("9", "(", 0x26),
            KeyDef("0", ")", 0x27),
            KeyDef("-", "_", 0x2D),
            KeyDef("=", "+", 0x2E)
        )

        val row2 = listOf(
            KeyDef("q", "Q", 0x14),
            KeyDef("w", "W", 0x1A),
            KeyDef("e", "E", 0x08),
            KeyDef("r", "R", 0x15),
            KeyDef("t", "T", 0x17),
            KeyDef("y", "Y", 0x1C),
            KeyDef("u", "U", 0x18),
            KeyDef("i", "I", 0x0C),
            KeyDef("o", "O", 0x12),
            KeyDef("p", "P", 0x13),
            KeyDef("[", "{", 0x2F),
            KeyDef("]", "}", 0x30)
        )

        val row3 = listOf(
            KeyDef("a", "A", 0x04),
            KeyDef("s", "S", 0x16),
            KeyDef("d", "D", 0x07),
            KeyDef("f", "F", 0x09),
            KeyDef("g", "G", 0x0A),
            KeyDef("h", "H", 0x0B),
            KeyDef("j", "J", 0x0D),
            KeyDef("k", "K", 0x0E),
            KeyDef("l", "L", 0x0F),
            KeyDef(";", ":", 0x33),
            KeyDef("'", "\"", 0x34)
        )

        val row4 = listOf(
            KeyDef("z", "Z", 0x1D),
            KeyDef("x", "X", 0x1B),
            KeyDef("c", "C", 0x06),
            KeyDef("v", "V", 0x19),
            KeyDef("b", "B", 0x05),
            KeyDef("n", "N", 0x11),
            KeyDef("m", "M", 0x10),
            KeyDef(",", "<", 0x36),
            KeyDef(".", ">", 0x37),
            KeyDef("/", "?", 0x38)
        )

        val backslashKey = KeyDef("\\", "|", 0x31)
        return LayoutDef(row1, row2, row3, row4, backslashKey)
    }

	private fun buildUkLayout(): LayoutDef {
		val row1 = listOf(
			KeyDef("1", "!", 0x1E),
			KeyDef("2", "@", 0x1F),   // CHANGED (was ")
			KeyDef("3", "#", 0x20),   // CHANGED (was £)
			KeyDef("4", "$", 0x21),
			KeyDef("5", "%", 0x22),
			KeyDef("6", "^", 0x23),
			KeyDef("7", "&", 0x24),
			KeyDef("8", "*", 0x25),
			KeyDef("9", "(", 0x26),
			KeyDef("0", ")", 0x27),
			KeyDef("-", "_", 0x2D),
			KeyDef("=", "+", 0x2E)
		)

		val row2 = listOf(
			KeyDef("q", "Q", 0x14),
			KeyDef("w", "W", 0x1A),
			KeyDef("e", "E", 0x08),
			KeyDef("r", "R", 0x15),
			KeyDef("t", "T", 0x17),
			KeyDef("y", "Y", 0x1C),
			KeyDef("u", "U", 0x18),
			KeyDef("i", "I", 0x0C),
			KeyDef("o", "O", 0x12),
			KeyDef("p", "P", 0x13),
			KeyDef("[", "{", 0x2F),
			KeyDef("]", "}", 0x30)
		)

		val row3 = listOf(
			KeyDef("a", "A", 0x04),
			KeyDef("s", "S", 0x16),
			KeyDef("d", "D", 0x07),
			KeyDef("f", "F", 0x09),
			KeyDef("g", "G", 0x0A),
			KeyDef("h", "H", 0x0B),
			KeyDef("j", "J", 0x0D),
			KeyDef("k", "K", 0x0E),
			KeyDef("l", "L", 0x0F),
			KeyDef(";", ":", 0x33),
			KeyDef("'", "\"", 0x34)  // keep " here (now no longer duplicated)
		)

		val row4 = listOf(
			KeyDef("z", "Z", 0x1D),
			KeyDef("x", "X", 0x1B),
			KeyDef("c", "C", 0x06),
			KeyDef("v", "V", 0x19),
			KeyDef("b", "B", 0x05),
			KeyDef("n", "N", 0x11),
			KeyDef("m", "M", 0x10),
			KeyDef(",", "<", 0x36),
			KeyDef(".", ">", 0x37),
			KeyDef("/", "?", 0x38)
		)

		val backslashKey = KeyDef("\\", "|", 0x31)
		return LayoutDef(row1, row2, row3, row4, backslashKey)
	}


    private fun buildIeLayout(): LayoutDef {
        val row1 = listOf(
            KeyDef("1", "!", 0x1E),
            KeyDef("2", "\"", 0x1F),
            KeyDef("3", "£", 0x20),
            KeyDef("4", "$", 0x21),
            KeyDef("5", "%", 0x22),
            KeyDef("6", "^", 0x23),
            KeyDef("7", "&", 0x24),
            KeyDef("8", "*", 0x25),
            KeyDef("9", "(", 0x26),
            KeyDef("0", ")", 0x27),
            KeyDef("-", "_", 0x2D),
            KeyDef("=", "+", 0x2E)
        )

        val row2 = listOf(
            KeyDef("q", "Q", 0x14),
            KeyDef("w", "W", 0x1A),
            KeyDef("e", "E", 0x08),
            KeyDef("r", "R", 0x15),
            KeyDef("t", "T", 0x17),
            KeyDef("y", "Y", 0x1C),
            KeyDef("u", "U", 0x18),
            KeyDef("i", "I", 0x0C),
            KeyDef("o", "O", 0x12),
            KeyDef("p", "P", 0x13),
            KeyDef("[", "{", 0x2F),
            KeyDef("]", "}", 0x30)
        )

        val row3 = listOf(
            KeyDef("a", "A", 0x04),
            KeyDef("s", "S", 0x16),
            KeyDef("d", "D", 0x07),
            KeyDef("f", "F", 0x09),
            KeyDef("g", "G", 0x0A),
            KeyDef("h", "H", 0x0B),
            KeyDef("j", "J", 0x0D),
            KeyDef("k", "K", 0x0E),
            KeyDef("l", "L", 0x0F),
            KeyDef(";", ":", 0x33),
            KeyDef("'", "@", 0x34)
        )

        val row4 = listOf(
            KeyDef("z", "Z", 0x1D),
            KeyDef("x", "X", 0x1B),
            KeyDef("c", "C", 0x06),
            KeyDef("v", "V", 0x19),
            KeyDef("b", "B", 0x05),
            KeyDef("n", "N", 0x11),
            KeyDef("m", "M", 0x10),
            KeyDef(",", "<", 0x36),
            KeyDef(".", ">", 0x37),
            KeyDef("/", "?", 0x38)
        )

        val backslashKey = KeyDef("\\", "|", 0x31)
        return LayoutDef(row1, row2, row3, row4, backslashKey)
    }

    private fun buildDeLayout(): LayoutDef {
        val row1 = listOf(
            KeyDef("1", "!", 0x1E),
            KeyDef("2", "\"", 0x1F),
            KeyDef("3", "§", 0x20),
            KeyDef("4", "$", 0x21),
            KeyDef("5", "%", 0x22),
            KeyDef("6", "&", 0x23),
            KeyDef("7", "/", 0x24),
            KeyDef("8", "(", 0x25),
            KeyDef("9", ")", 0x26),
            KeyDef("0", "=", 0x27),
            KeyDef("ß", "?", 0x2D),
            KeyDef("´", "`", 0x2E)
        )

        val row2 = listOf(
            KeyDef("q", "Q", 0x14),
            KeyDef("w", "W", 0x1A),
            KeyDef("e", "E", 0x08),
            KeyDef("r", "R", 0x15),
            KeyDef("t", "T", 0x17),
            KeyDef("z", "Z", 0x1C),
            KeyDef("u", "U", 0x18),
            KeyDef("i", "I", 0x0C),
            KeyDef("o", "O", 0x12),
            KeyDef("p", "P", 0x13),
            KeyDef("ü", "Ü", 0x2F),
            KeyDef("+", "*", 0x30)
        )

        val row3 = listOf(
            KeyDef("a", "A", 0x04),
            KeyDef("s", "S", 0x16),
            KeyDef("d", "D", 0x07),
            KeyDef("f", "F", 0x09),
            KeyDef("g", "G", 0x0A),
            KeyDef("h", "H", 0x0B),
            KeyDef("j", "J", 0x0D),
            KeyDef("k", "K", 0x0E),
            KeyDef("l", "L", 0x0F),
            KeyDef("ö", "Ö", 0x33),
            KeyDef("ä", "Ä", 0x34)
        )

        val row4 = listOf(
            KeyDef("y", "Y", 0x1D),
            KeyDef("x", "X", 0x1B),
            KeyDef("c", "C", 0x06),
            KeyDef("v", "V", 0x19),
            KeyDef("b", "B", 0x05),
            KeyDef("n", "N", 0x11),
            KeyDef("m", "M", 0x10),
            KeyDef(",", ";", 0x36),
            KeyDef(".", ":", 0x37),
            KeyDef("-", "_", 0x38)
        )

        val backslashKey = KeyDef("\\", "|", 0x31)
        return LayoutDef(row1, row2, row3, row4, backslashKey)
    }

    private fun buildFrLayout(): LayoutDef {
        val row1 = listOf(
            KeyDef("&", "1", 0x1E),
            KeyDef("é", "2", 0x1F),
            KeyDef("\"", "3", 0x20),
            KeyDef("'", "4", 0x21),
            KeyDef("(", "5", 0x22),
            KeyDef("-", "6", 0x23),
            KeyDef("è", "7", 0x24),
            KeyDef("_", "8", 0x25),
            KeyDef("ç", "9", 0x26),
            KeyDef("à", "0", 0x27),
            KeyDef(")", "°", 0x2D),
            KeyDef("=", "+", 0x2E)
        )

        val row2 = listOf(
            KeyDef("a", "A", 0x14),
            KeyDef("z", "Z", 0x1A),
            KeyDef("e", "E", 0x08),
            KeyDef("r", "R", 0x15),
            KeyDef("t", "T", 0x17),
            KeyDef("y", "Y", 0x1C),
            KeyDef("u", "U", 0x18),
            KeyDef("i", "I", 0x0C),
            KeyDef("o", "O", 0x12),
            KeyDef("p", "P", 0x13),
            KeyDef("^", "¨", 0x2F),
            KeyDef("$", "£", 0x30)
        )

        val row3 = listOf(
            KeyDef("q", "Q", 0x04),
            KeyDef("s", "S", 0x16),
            KeyDef("d", "D", 0x07),
            KeyDef("f", "F", 0x09),
            KeyDef("g", "G", 0x0A),
            KeyDef("h", "H", 0x0B),
            KeyDef("j", "J", 0x0D),
            KeyDef("k", "K", 0x0E),
            KeyDef("l", "L", 0x0F),
            KeyDef("m", "M", 0x33),
            KeyDef("ù", "%", 0x34)
        )

        val row4 = listOf(
            KeyDef("w", "W", 0x1D),
            KeyDef("x", "X", 0x1B),
            KeyDef("c", "C", 0x06),
            KeyDef("v", "V", 0x19),
            KeyDef("b", "B", 0x05),
            KeyDef("n", "N", 0x11),
            KeyDef(",", "?", 0x10),
            KeyDef(";", ".", 0x36),
            KeyDef(":", "/", 0x37),
            KeyDef("!", "§", 0x38)
        )

        val backslashKey = KeyDef("*", "µ", 0x31)
        return LayoutDef(row1, row2, row3, row4, backslashKey)
    }

    private fun buildEsLayout(): LayoutDef {
        val row1 = listOf(
            KeyDef("1", "!", 0x1E),
            KeyDef("2", "\"", 0x1F),
            KeyDef("3", "·", 0x20),
            KeyDef("4", "$", 0x21),
            KeyDef("5", "%", 0x22),
            KeyDef("6", "&", 0x23),
            KeyDef("7", "/", 0x24),
            KeyDef("8", "(", 0x25),
            KeyDef("9", ")", 0x26),
            KeyDef("0", "=", 0x27),
            KeyDef("'", "?", 0x2D),
            KeyDef("¡", "¿", 0x2E)
        )

        val row2 = listOf(
            KeyDef("q", "Q", 0x14),
            KeyDef("w", "W", 0x1A),
            KeyDef("e", "E", 0x08),
            KeyDef("r", "R", 0x15),
            KeyDef("t", "T", 0x17),
            KeyDef("y", "Y", 0x1C),
            KeyDef("u", "U", 0x18),
            KeyDef("i", "I", 0x0C),
            KeyDef("o", "O", 0x12),
            KeyDef("p", "P", 0x13),
            KeyDef("`", "^", 0x2F),
            KeyDef("+", "*", 0x30)
        )

        val row3 = listOf(
            KeyDef("a", "A", 0x04),
            KeyDef("s", "S", 0x16),
            KeyDef("d", "D", 0x07),
            KeyDef("f", "F", 0x09),
            KeyDef("g", "G", 0x0A),
            KeyDef("h", "H", 0x0B),
            KeyDef("j", "J", 0x0D),
            KeyDef("k", "K", 0x0E),
            KeyDef("l", "L", 0x0F),
            KeyDef("ñ", "Ñ", 0x33),
            KeyDef("´", "¨", 0x34)
        )

        val row4 = listOf(
            KeyDef("z", "Z", 0x1D),
            KeyDef("x", "X", 0x1B),
            KeyDef("c", "C", 0x06),
            KeyDef("v", "V", 0x19),
            KeyDef("b", "B", 0x05),
            KeyDef("n", "N", 0x11),
            KeyDef("m", "M", 0x10),
            KeyDef(",", ";", 0x36),
            KeyDef(".", ":", 0x37),
            KeyDef("-", "_", 0x38)
        )

        val backslashKey = KeyDef("ç", "Ç", 0x31)
        return LayoutDef(row1, row2, row3, row4, backslashKey)
    }

    private fun buildItLayout(): LayoutDef = buildUsLayout()
    private fun buildPtPtLayout(): LayoutDef = buildUsLayout()
    private fun buildPtBrLayout(): LayoutDef = buildUsLayout()
    private fun buildSeLayout(): LayoutDef = buildUsLayout()
    private fun buildNoLayout(): LayoutDef = buildUsLayout()
    private fun buildDkLayout(): LayoutDef = buildUsLayout()
    private fun buildFiLayout(): LayoutDef = buildUsLayout()
    private fun buildChDeLayout(): LayoutDef = buildUsLayout()
    private fun buildChFrLayout(): LayoutDef = buildUsLayout()
    private fun buildTrLayout(): LayoutDef = buildUsLayout()
}

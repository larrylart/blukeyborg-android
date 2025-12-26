package com.blu.blukeyborg

import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.math.max

class RemoteControlActivity : AppCompatActivity() {

    // Local session flag for this activity; we don't share MainActivity's
    // boolean, but BleHub.enableFastKeys is idempotent at the dongle side.
    private var fastKeysEnabled: Boolean = false

    // keep a reference so we can update text dynamically
    private lateinit var bodyText: TextView

    // container for the dynamic remote panel
    private lateinit var panelContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Transparent full-screen root
        val root = FrameLayout(this).apply {
            setBackgroundColor(0x00000000) // fully transparent
        }

        // Centered "card" UI
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
            setBackgroundColor(0xFF222222.toInt())
        }

        // --- Top bar inside the card: Title + Close X ---
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val titleView = TextView(this).apply {
            text = "BluKeyborg Remote"
            textSize = 18f
            setTextColor(0xFFFFFFFF.toInt())
        }

        val titleLp = LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            1f
        )

        val closeButton = ImageButton(this).apply {
            setImageResource(R.drawable.baseline_close_24_white)
            background = null
            setPadding(dp(4), dp(4), dp(4), dp(4))
            setOnClickListener { finish() }
        }

        topBar.addView(titleView, titleLp)
        topBar.addView(closeButton)

        // --- Body text / mapping ---
        bodyText = TextView(this).apply {
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, dp(16), 0, 0)
        }

        // --- Panel container (Media / Presentation) ---
        panelContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(14), 0, 0)
        }

        card.addView(topBar)
        card.addView(bodyText)
        card.addView(panelContainer)

        // Set initial text + panel
        updateBodyTextAndPanel()

        val cardParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        )
        root.addView(card, cardParams)

        setContentView(root)

        // Set status bar icons to be dark (since the bar will be transparent by default)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
        }

        // handle top bar issue in immersive view
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())
            val systemBarsInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())

            val bottomPadding = max(systemBarsInsets.bottom, imeInsets.bottom)

            v.setPadding(
                systemBarsInsets.left,
                systemBarsInsets.top,   // keeps content below status bar
                systemBarsInsets.right,
                bottomPadding           // keeps content above nav bar / IME
            )

            insets
        }

        // hide only bottom nav bar
        hideBottomNavBar()

        // Make window fully transparent and non-dimming
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setBackgroundDrawableResource(android.R.color.transparent)
        window.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT
        )

        // Show over lock screen & turn screen on
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        // Keep screen on while in remote mode
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideBottomNavBar()
        }
    }

    override fun onResume() {
        super.onResume()
        updateBodyTextAndPanel()
    }

    private fun hideBottomNavBar() {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        // Hide ONLY navigation bar (same as settings activity)
        controller.hide(WindowInsetsCompat.Type.navigationBars())
    }

    private fun updateBodyTextAndPanel() {
        val upLabel = VolumeKeyActions.getActionDisplayLabel(this, isVolumeUp = true)
        val downLabel = VolumeKeyActions.getActionDisplayLabel(this, isVolumeUp = false)

        bodyText.text = "\nVolume Up  → $upLabel\n" +
                "Volume Down → $downLabel\n\n"

        rebuildRemotePanel()
    }

    private fun rebuildRemotePanel() {
        panelContainer.removeAllViews()

        when (PreferencesUtil.getRemoteActionsPanel(this)) {
            "presentation" -> buildPresentationPanel()
            else -> buildMediaPanel() // default
        }
    }

    /**
     * Media panel styled like your iPhone screenshot:
     * Row1: Rew / Stop / Play / FF
     * Row2: Prev / Next (wide)
     * Row3: Mute / Vol- / Vol+
     */
	// baseline_volume_up_24 baseline_volume_down_24 outline_play_pause_24 baseline_stop_24
    private fun buildMediaPanel() {
        // Row 1 (4 tiles)
        panelContainer.addView(buildRow(
            remoteTile(android.R.drawable.ic_media_rew, "Rew", square = true) {
                sendConsumer(VolumeKeyActions.Action.REWIND)
            },
            remoteTile(R.drawable.baseline_stop_24, "Stop", square = true) {
                sendConsumer(VolumeKeyActions.Action.STOP)
            },
            remoteTile(R.drawable.outline_play_pause_24, "Play", square = true) {
                sendConsumer(VolumeKeyActions.Action.PLAY)
            },
            remoteTile(android.R.drawable.ic_media_ff, "FF", square = true) {
                sendConsumer(VolumeKeyActions.Action.FAST_FORWARD)
            }
        ))

        panelContainer.addView(spacer(dp(10)))

        // Row 2 (2 wide tiles)
        panelContainer.addView(buildRow(
            remoteTile(android.R.drawable.ic_media_previous, "Prev", square = false) {
                sendConsumer(VolumeKeyActions.Action.PREV_TRACK)
            },
            remoteTile(android.R.drawable.ic_media_next, "Next", square = false) {
                sendConsumer(VolumeKeyActions.Action.NEXT_TRACK)
            }
        ))

        panelContainer.addView(spacer(dp(10)))

        // Row 3 (3 tiles)
        panelContainer.addView(buildRow(
            remoteTile(android.R.drawable.ic_lock_silent_mode, "Mute", square = true) {
                sendConsumer(VolumeKeyActions.Action.MUTE)
            },
            remoteTile(R.drawable.baseline_volume_down_24, "Vol-", square = true) {
                sendConsumer(VolumeKeyActions.Action.VOL_DOWN)
            },
            remoteTile(R.drawable.baseline_volume_up_24, "Vol+", square = true) {
                sendConsumer(VolumeKeyActions.Action.VOL_UP)
            }
        ))
    }

    /**
     * Simple presentation panel (optional — you already asked for it earlier).
     * You can refine icons later.
     */
    private fun buildPresentationPanel() {
        panelContainer.addView(buildRow(
            remoteTile(android.R.drawable.ic_media_play, "Start", square = false) { sendKey(0x28 /*Enter*/) },
            remoteTile(android.R.drawable.ic_menu_close_clear_cancel, "End", square = false) { sendKey(0x29 /*Esc*/) }
        ))

        panelContainer.addView(spacer(dp(10)))

        panelContainer.addView(buildRow(
            remoteTile(android.R.drawable.ic_media_previous, "Prev", square = false) { sendKey(0x50 /*Left Arrow*/) },
            remoteTile(android.R.drawable.ic_media_next, "Next", square = false) { sendKey(0x4F /*Right Arrow*/) }
        ))

        panelContainer.addView(spacer(dp(10)))

        panelContainer.addView(buildRow(
            remoteTile(android.R.drawable.ic_delete, "Black", square = true) { sendKey(0x05 /*B*/) },
            remoteTile(android.R.drawable.ic_input_add, "White", square = true) { sendKey(0x1A /*W*/) },
            remoteTile(android.R.drawable.ic_menu_close_clear_cancel, "Esc", square = true) { sendKey(0x29 /*Esc*/) }
        ))
    }

    private fun buildRow(vararg tiles: View): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            tiles.forEachIndexed { idx, v ->
                val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (idx > 0) leftMargin = dp(10)
                }
                addView(v, lp)
            }
        }
    }

    private fun spacer(hPx: Int): View =
        View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                hPx
            )
        }

    /**
     * A "tile" button:
     * - rounded purple background
     * - system icon centered
     * - small label underneath
     *
     * square=true -> square-ish tile (like top row / bottom row)
     * square=false -> wide tile (like Prev/Next row)
     */
    private fun remoteTile(iconRes: Int, label: String, square: Boolean, onTap: () -> Unit): View {
        val bg = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(0xFF6A57B6.toInt()) // iPhone-like purple
        }

        val icon = ImageView(this).apply {
            setImageResource(iconRes)
            setColorFilter(0xFFFFFFFF.toInt())
            val size = if (square) dp(28) else dp(26)
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
        }

        val text = TextView(this).apply {
            this.text = label
            textSize = 12f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, 0)
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = bg
            isClickable = true
            isFocusable = true

            // size feel
            val minH = if (square) dp(74) else dp(74)
            minimumHeight = minH

            addView(icon)
            addView(text)

            setOnClickListener {
                ensureFastKeysThen(onTap)
            }
        }

        return content
    }

    private fun ensureFastKeysThen(action: () -> Unit) {
        if (fastKeysEnabled) {
            action()
            return
        }

        BleHub.enableFastKeys { ok, err ->
            runOnUiThread {
                if (ok) {
                    fastKeysEnabled = true
                    action()
                } else {
                    Toast.makeText(
                        this,
                        err ?: getString(R.string.msg_failed_enable_fast_keys),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun sendConsumer(action: VolumeKeyActions.Action) {
        val usage: Int? = when (action) {
            VolumeKeyActions.Action.PLAY         -> 0x00CD
            VolumeKeyActions.Action.STOP         -> 0x00B7
            VolumeKeyActions.Action.NEXT_TRACK   -> 0x00B5
            VolumeKeyActions.Action.PREV_TRACK   -> 0x00B6
            VolumeKeyActions.Action.FAST_FORWARD -> 0x00B3
            VolumeKeyActions.Action.REWIND       -> 0x00B4
            VolumeKeyActions.Action.VOL_UP       -> 0x00E9
            VolumeKeyActions.Action.VOL_DOWN     -> 0x00EA
            VolumeKeyActions.Action.MUTE         -> 0x00E2
            else -> null
        }
        if (usage == null) return

        BleHub.sendRawKeyTap(0x00, usage) { ok, err ->
            if (!ok) {
                runOnUiThread {
                    Toast.makeText(
                        this,
                        err ?: getString(R.string.msg_send_failed),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun sendKey(usageKeyboardPage: Int) {
        BleHub.sendRawKeyTap(0x00, usageKeyboardPage) { ok, err ->
            if (!ok) {
                runOnUiThread {
                    Toast.makeText(
                        this,
                        err ?: getString(R.string.msg_send_failed),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()

    private fun handleVolumeKeyPress(isVolumeUp: Boolean) {
        if (fastKeysEnabled) {
            VolumeKeyActions.handleVolumeKey(this, isVolumeUp)
            return
        }

        BleHub.enableFastKeys { ok, err ->
            runOnUiThread {
                if (ok) {
                    fastKeysEnabled = true
                    VolumeKeyActions.handleVolumeKey(this, isVolumeUp)
                } else {
                    Toast.makeText(
                        this,
                        err ?: getString(R.string.msg_failed_enable_fast_keys),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                handleVolumeKeyPress(isVolumeUp = true)
                true
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                handleVolumeKeyPress(isVolumeUp = false)
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }
}

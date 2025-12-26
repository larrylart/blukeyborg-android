package com.blu.blukeyborg

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.setPadding
import android.view.Gravity
import com.google.android.material.button.MaterialButton
import android.app.Dialog
import android.view.ViewGroup
import android.content.pm.ActivityInfo
import androidx.core.view.ViewCompat
import kotlin.math.max
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.view.KeyEvent
import android.content.Intent
import android.content.res.Configuration
import android.util.Log

import com.blu.blukeyborg.R
import com.blu.blukeyborg.BleHub

class MainActivity : AppCompatActivity() {

	// debug instrumentation
    //companion object {
   //     private var nextId = 1
    //}

    //private val instanceId = nextId++

    private lateinit var historyContainer: LinearLayout
    private lateinit var historyScroll: ScrollView
    private lateinit var inputEdit: EditText
    private lateinit var sendButton: ImageButton
    private lateinit var specialKeysButton: ImageButton
	private lateinit var fullKeyboardButton: ImageButton

	private lateinit var settingsButtonTop: ImageButton
	private lateinit var closeButtonTop: ImageButton

    private lateinit var remoteButton: ImageButton

	private lateinit var ledDot: View
	private lateinit var titleCluster: View

    // Tracks whether we've successfully enabled fast keys (raw mode)
    // on the dongle for this app session.
    private var enableFastKeys: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
		
		//Log.d("BK/Main", "onCreate(instance=$instanceId, savedInstance=${savedInstanceState != null})")
		
		// Let the system handle status/nav bar insets for us
		WindowCompat.setDecorFitsSystemWindows(window, true)		
		
        setContentView(R.layout.activity_main)

		//window.statusBarColor = 0xFF000000.toInt()   
		WindowInsetsControllerCompat(window, window.decorView).apply {
			isAppearanceLightStatusBars = true    
		}

        historyContainer = findViewById(R.id.historyContainer)
        historyScroll = findViewById(R.id.historyScroll)
        inputEdit = findViewById(R.id.inputEdit)
        sendButton = findViewById(R.id.btnSend)
        specialKeysButton = findViewById(R.id.btnSpecialKeys)
		fullKeyboardButton = findViewById(R.id.btnFullKeyboard)

		settingsButtonTop = findViewById(R.id.btnSettingsTop)
		closeButtonTop = findViewById(R.id.btnCloseTop)

        // remote control button
        remoteButton = findViewById(R.id.btnRemote)

		val rootView = findViewById<View>(R.id.root)

		titleCluster = findViewById(R.id.titleCluster)
		ledDot = findViewById(R.id.ledDot)


		// Default: disabled until MTLS session is ready
		setDongleUiEnabled(false)

		// React to MTLS-ready state changes
		BleHub.connected.observe(this) { ready ->
			setDongleUiEnabled(ready == true)
		}

		// Update LED on BOTH: plain BLE link + secure MTLS ready
		BleHub.bleConnected.observe(this) { updateLed() }
		BleHub.connected.observe(this) { updateLed() }

		// initial paint
		updateLed()

		ViewCompat.setOnApplyWindowInsetsListener(rootView) { v, insets ->
			val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())
			val systemBarsInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())

			// When keyboard is visible, imeInsets.bottom > 0
			val bottomPadding = max(systemBarsInsets.bottom, imeInsets.bottom)

			v.setPadding(
				systemBarsInsets.left,
				systemBarsInsets.top,
				systemBarsInsets.right,
				bottomPadding
			)

			insets
		}

		hideBottomNavBar()

		settingsButtonTop.setOnClickListener {
			startActivity(
				android.content.Intent(
					this,
					com.blu.blukeyborg.OutputDeviceSettingsActivity::class.java
				)
			)
		}

		closeButtonTop.setOnClickListener {
			// Option A: keeps app in background - maybe if we want the listeners for volume buttons?
			//moveTaskToBack(true)

			// Option B: kill the activity
			//finish()
			
			// C: 1) Explicitly drop BLE + MTLS
			BleHub.disconnect()			
			// 2) Close the whole task (not just this Activity)
			finishAffinity() 
		}

        remoteButton.setOnClickListener {
            openRemoteControl()
        }

        sendButton.setOnClickListener { sendCurrentText() }

        specialKeysButton.setOnClickListener { onSpecialKeysButtonClicked() }
		
		fullKeyboardButton.setOnClickListener { onFullKeyboardButtonClicked() }
		
		// connect led behaviour 
		val ledClickListener = View.OnClickListener {
			// If no device is selected, show toast and exit (matches iOS)
			if (!BleHub.hasSelectedDevice()) {
				Toast.makeText(this, "Select a device in Settings first", Toast.LENGTH_SHORT).show()
				return@OnClickListener
			}

			val secure = (BleHub.connected.value == true)       // GREEN
			val ble = (BleHub.bleConnected.value == true)       // ORANGE condition

			// GREEN: fully secure-connected => not interactive
			if (secure) return@OnClickListener

			// ORANGE: BLE connected but not secure => disconnect then reconnect
			if (ble) {
				BleHub.disconnect()
				ledDot.postDelayed({
					BleHub.connectSelectedDevice { ok, msg ->
						if (!ok && msg != null) {
							runOnUiThread {
								Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
							}
						}
					}
				}, 250)
				return@OnClickListener
			}

			// RED: not connected => connect
			BleHub.connectSelectedDevice { ok, msg ->
				if (!ok && msg != null) {
					runOnUiThread {
						Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
					}
				}
			}
		}

		ledDot.setOnClickListener(ledClickListener)
		titleCluster.setOnClickListener(ledClickListener) // tapping title area also works like iOS cluster
		
		
		//Log.d("BK/Main", "onCreate(instance=$instanceId) historyChildren=${historyContainer.childCount}")
    }

    override fun onStart() {
        super.onStart()
		//Log.d("BK/Main", "onStart(instance=$instanceId) historyChildren=${historyContainer.childCount}")


		// If we're already up, do NOT restart the auto-connect state machine.
		if (BleHub.bleConnected.value == true || BleHub.connected.value == true) {
			updateLed()
			setDongleUiEnabled(BleHub.connected.value == true)
			return
		}
		
        // Auto-connect using stored preferences, but ignore result for now
        BleHub.autoConnectFromPrefs { ok, msg ->
			//Log.d("BK/Main", "autoConnectFromPrefs callback(instance=$instanceId, ok=$ok, msg=$msg)")
            if (!ok && msg != null) {
                runOnUiThread {
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

	// hack to avoid main activity recreation
	override fun onConfigurationChanged(newConfig: Configuration) {
		super.onConfigurationChanged(newConfig)
		//Log.d("BK/Main", "onConfigurationChanged(instance=$instanceId, orientation=${newConfig.orientation})")
		// You can decide if you need to adjust layout here
	}


//override fun onResume() {
//    super.onResume()
//    Log.d("BK/Main", "onResume(instance=$instanceId) historyChildren=${historyContainer.childCount}")
//}

//override fun onDestroy() {
//    super.onDestroy()
//    Log.d("BK/Main", "onDestroy(instance=$instanceId)")
//}

/*
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                // If we handled it, consume - otherwise fall back to default.
                if (VolumeKeyActions.handleVolumeKey(this, isVolumeUp = true)) {
                    true
                } else {
                    super.onKeyDown(keyCode, event)
                }
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (VolumeKeyActions.handleVolumeKey(this, isVolumeUp = false)) {
                    true
                } else {
                    super.onKeyDown(keyCode, event)
                }
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }
*/

	private fun setDongleUiEnabled(ready: Boolean) {
		// Buttons / entry fields that require MTLS-ready session
		sendButton.isEnabled = ready
		specialKeysButton.isEnabled = ready
		fullKeyboardButton.isEnabled = ready
		remoteButton.isEnabled = ready
		inputEdit.isEnabled = ready

		// Optional: make it visually obvious (Android ImageButton doesn't always dim enough)
		val alpha = if (ready) 1.0f else 0.35f
		sendButton.alpha = alpha
		specialKeysButton.alpha = alpha
		fullKeyboardButton.alpha = alpha
		remoteButton.alpha = alpha
		inputEdit.alpha = alpha

		// If you have more buttons (e.g. "send screen input"), add them here the same way.
	}

	private fun updateLed() {
		val hasDevice = BleHub.hasSelectedDevice()
		val secure = (BleHub.connected.value == true)
		val ble = (BleHub.bleConnected.value == true)

		// Match iOS colors: green (secure), orange (ble only), red (disconnected)
		val colorInt = when {
			secure -> 0xFF2ECC71.toInt() // green
			ble -> 0xFFFFA500.toInt()    // orange
			else -> 0xFFFF3B30.toInt()   // red-ish iOS style
		}

		// Tint the dot background
		ledDot.background?.setTint(colorInt)

		// Match iOS "dim if no device selected"
		val alpha = if (hasDevice) 1.0f else 0.4f
		ledDot.alpha = alpha
		titleCluster.alpha = 1.0f // keep title readable; only dot dims on iOS, but either is fine
	}

    private fun openRemoteControl() {
		
		if (BleHub.connected.value != true) {
			Toast.makeText(this, "Dongle not connected", Toast.LENGTH_SHORT).show()
			return
		}		
		
        val intent = Intent(this, RemoteControlActivity::class.java).apply {
            // Optional: reuse existing instance if one already exists
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(intent)
    }

    ////////////////////////////////////////////////////////////////
    // Handle a volume key press:
    //  - If fast keys already enabled: just send the configured HID key.
    //  - If not: enable fast keys via BleHub.enableFastKeys, then send once.
    // The KeyEvent itself is always consumed so the system volume bar won't show.
    ////////////////////////////////////////////////////////////////
    private fun handleVolumeKeyPress(isVolumeUp: Boolean) {
		
		if (BleHub.connected.value != true) {
			// Not MTLS-ready => ignore / consume the key so system volume UI doesn't appear
			Toast.makeText(this, "Dongle not connected", Toast.LENGTH_SHORT).show()
			return
		}		
		
        if (enableFastKeys) {
            // We don't care about the return value here; we always consume the event.
            VolumeKeyActions.handleVolumeKey(this, isVolumeUp)
            return
        }

        // Fast keys not yet enabled – enable them first, then fire the action once.
        BleHub.enableFastKeys { ok, err ->
            runOnUiThread {
                if (ok) {
                    enableFastKeys = true
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

/*
	override fun onWindowFocusChanged(hasFocus: Boolean) {
		super.onWindowFocusChanged(hasFocus)
		if (hasFocus) {
			enableFullscreenImmersive()
		}
	}

	private fun enableFullscreenImmersive() {
		// We keep decor fitting ON so Android applies status-bar insets correctly
		WindowCompat.setDecorFitsSystemWindows(window, true)

		val controller = WindowInsetsControllerCompat(window, window.decorView)
		controller.systemBarsBehavior =
			WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

		// Hide ONLY nav bar.
		controller.hide(WindowInsetsCompat.Type.navigationBars())
	}
*/
	override fun onWindowFocusChanged(hasFocus: Boolean) {
		super.onWindowFocusChanged(hasFocus)
		if (hasFocus) {
			hideBottomNavBar()
		}
	}

	private fun hideBottomNavBar() {
		val controller = WindowInsetsControllerCompat(window, window.decorView)

		controller.systemBarsBehavior =
			WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

		// ❗ HIDE ONLY NAV BAR — do NOT hide status bar
		controller.hide(WindowInsetsCompat.Type.navigationBars())
	}


    private fun sendCurrentText() {
		
		if (BleHub.connected.value != true) {
			Toast.makeText(this, "Dongle not connected", Toast.LENGTH_SHORT).show()
			return
		}
		
        val value = inputEdit.text.toString()
        if (value.isEmpty()) {
            Toast.makeText(this, R.string.msg_no_text_to_send, Toast.LENGTH_SHORT).show()
            return
        }

        BleHub.sendStringAwaitHash(value) { ok, err ->
            runOnUiThread {
                if (ok) {
                    appendHistory(value)
                    inputEdit.text?.clear()
                } else {
                    Toast.makeText(this, err ?: getString(R.string.msg_send_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

	private fun appendHistory(text: String) {
		val tv = TextView(this).apply {
			this.text = text
			setPadding(16) // a bit more padding so the row looks nice

			// Decide background based on index (0-based)
			val index = historyContainer.childCount
			val bgColor = if (index % 2 == 0) {
				0xFFFFFFFF.toInt()      // white for even rows
			} else {
				0xFFF3F3F3.toInt()      // light gray for odd rows
			}
			setBackgroundColor(bgColor)

			// Make each row span full width of the container
			layoutParams = LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.MATCH_PARENT,
				LinearLayout.LayoutParams.WRAP_CONTENT
			)
		}

		historyContainer.addView(tv)

		historyScroll.post {
			historyScroll.fullScroll(View.FOCUS_DOWN)
		}
	}

	private fun onFullKeyboardButtonClicked() {
		// Reuse the same fast-keys gating: if already on, just show keyboard.
		if (enableFastKeys) {
			// Instead of showing a dialog, launch the full keyboard activity
			startActivity(
				android.content.Intent(this, FullKeyboardActivity::class.java)
			)
			return
		}

		BleHub.enableFastKeys { ok, err ->
			runOnUiThread {
				if (ok) {
					enableFastKeys = true
					startActivity(
						android.content.Intent(this, FullKeyboardActivity::class.java)
					)
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

    private fun onSpecialKeysButtonClicked() 
	{
		if (BleHub.connected.value != true) {
			Toast.makeText(this, "Dongle not connected", Toast.LENGTH_SHORT).show()
			return
		}		
		
        // If we already enabled fast keys this session, just show the panel.
        if (enableFastKeys) {
            showSpecialKeysDialog()
            return
        }

        // First-time tap: enable fast keys on the dongle via C8.
        BleHub.enableFastKeys { ok, err ->
            runOnUiThread {
                if (ok) {
                    enableFastKeys = true
                    showSpecialKeysDialog()
                } else {
                    Toast.makeText(
                        this,
                        err ?: getString(R.string.msg_failed_enable_fast_keys),
                        Toast.LENGTH_SHORT
                    ).show()
                    // Do NOT show the panel if enabling fast keys failed.
                }
            }
        }
    }

    // Modifiers bitmask matches RawKeyboard on the dongle side:
    //  bit0 = Left Ctrl, bit1 = Left Shift, bit2 = Left Alt, bit3 = Left GUI
    //  bit4 = Right Ctrl, bit5 = Right Shift, bit6 = Right Alt, bit7 = Right GUI
    data class SpecialKey(val label: String, val mods: Int, val usage: Int)


     private fun showSpecialKeysDialog() {
        // Helper: convert dp-ish ints to px (we've been using raw ints anyway,
        // so this just keeps things a bit nicer on different screens).
        fun Int.toPx(): Int =
            (this * resources.displayMetrics.density).toInt()

        // Helper to build a "key button" that looks like a keyboard key
		fun createKeyButton(key: SpecialKey, large: Boolean = false): MaterialButton {
			return MaterialButton(this).apply {
				text = key.label
				isAllCaps = false

				// Replace removed inset* with normal padding
				setPadding(12, 12, 12, 12)

				strokeWidth = 2

				if (large) {
					textSize = 18f
					minimumWidth = 56.toPx()
					minimumHeight = 56.toPx()
				} else {
					textSize = 14f
					minimumWidth = 40.toPx()
					minimumHeight = 40.toPx()
				}

				setOnClickListener {
					BleHub.sendRawKeyTap(key.mods, key.usage) { ok, err ->
						runOnUiThread {
							if (ok) {
								// do not add to the main history
								//appendHistory("[${key.label}]")
							} else {
								Toast.makeText(
									this@MainActivity,
									err ?: getString(R.string.msg_send_failed),
									Toast.LENGTH_SHORT
								).show()
							}
						}
					}
				}
			}
		}

        // Define all keys we need with HID usages
        val esc   = SpecialKey("ESC", 0x00, 0x29)
        val tab   = SpecialKey("TAB", 0x00, 0x2B)

        val f1    = SpecialKey("F1",  0x00, 0x3A)
        val f2    = SpecialKey("F2",  0x00, 0x3B)
        val f3    = SpecialKey("F3",  0x00, 0x3C)
        val f4    = SpecialKey("F4",  0x00, 0x3D)
        val f5    = SpecialKey("F5",  0x00, 0x3E)
        val f6    = SpecialKey("F6",  0x00, 0x3F)
        val f7    = SpecialKey("F7",  0x00, 0x40)
        val f8    = SpecialKey("F8",  0x00, 0x41)
        val f9    = SpecialKey("F9",  0x00, 0x42)
        val f10   = SpecialKey("F10", 0x00, 0x43)
        val f11   = SpecialKey("F11", 0x00, 0x44)
        val f12   = SpecialKey("F12", 0x00, 0x45)

        val ins   = SpecialKey("Ins",   0x00, 0x49)
        val home  = SpecialKey("Home",  0x00, 0x4A)
        val pgUp  = SpecialKey("PgUp",  0x00, 0x4B)
        val del   = SpecialKey("Del",   0x00, 0x4C)
        val end   = SpecialKey("End",   0x00, 0x4D)
        val pgDn  = SpecialKey("PgDn",  0x00, 0x4E)

        val left  = SpecialKey("←", 0x00, 0x50)
        val down  = SpecialKey("↓", 0x00, 0x51)
        val up    = SpecialKey("↑", 0x00, 0x52)
        val right = SpecialKey("→", 0x00, 0x4F)

        // Root layout of the dialog
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(16)
        }

        fun addSpacer(heightDp: Int = 8) {
            val spacer = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    heightDp.toPx()
                )
            }
            root.addView(spacer)
        }

        fun addRow(keys: List<SpecialKey>, large: Boolean = false) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_HORIZONTAL
            }
            keys.forEach { key ->
                val btn = createKeyButton(key, large)
                val lp = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                ).apply {
                    val m = 4.toPx()
                    setMargins(m, m, m, m)
                }
                row.addView(btn, lp)
            }
            root.addView(row)
        }

        // --- Top: ESC / TAB (two longer keys, centered) ---
        addRow(listOf(esc, tab), large = true)

        addSpacer(12)

        // --- F1–F12: 2 rows, centered ---
        addRow(listOf(f1, f2, f3, f4, f5, f6))
        addRow(listOf(f7, f8, f9, f10, f11, f12))

        addSpacer(12)

        // --- Nav cluster: Insert Home PgUp / Del End PgDn (2 rows x 3) ---
        run {
            val rowTop = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_HORIZONTAL
            }
            val rowBottom = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_HORIZONTAL
            }

            listOf(ins, home, pgUp).forEach { key ->
                val btn = createKeyButton(key)
                val lp = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                ).apply {
                    val m = 4.toPx()
                    setMargins(m, m, m, m)
                }
                rowTop.addView(btn, lp)
            }

            listOf(del, end, pgDn).forEach { key ->
                val btn = createKeyButton(key)
                val lp = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                ).apply {
                    val m = 4.toPx()
                    setMargins(m, m, m, m)
                }
                rowBottom.addView(btn, lp)
            }

            root.addView(rowTop)
            root.addView(rowBottom)
        }

        addSpacer(16)

        // --- Arrow keys: inverted T, larger buttons ---
        run {
            val arrowsContainer = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
            }

            // Top row: Up in the middle
            val rowUp = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_HORIZONTAL
            }
            // Add an empty spacer, then Up, then empty spacer so it's centred
            val spacerWeight = 1f

            fun emptyArrowSpacer(): View {
                return View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        0,
                        spacerWeight
                    )
                }
            }

            rowUp.addView(emptyArrowSpacer())
            rowUp.addView(createKeyButton(up, large = true).apply {
                val lp = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    spacerWeight
                ).apply {
                    val m = 4.toPx()
                    setMargins(m, m, m, m)
                }
                layoutParams = lp
            })
            rowUp.addView(emptyArrowSpacer())

            // Bottom row: Left, Down, Right
            val rowBottom = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_HORIZONTAL
            }

            fun addArrowKey(key: SpecialKey) {
                val btn = createKeyButton(key, large = true)
                val lp = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                ).apply {
                    val m = 4.toPx()
                    setMargins(m, m, m, m)
                }
                rowBottom.addView(btn, lp)
            }

            addArrowKey(left)
            addArrowKey(down)
            addArrowKey(right)

            arrowsContainer.addView(rowUp)
            arrowsContainer.addView(rowBottom)

            root.addView(arrowsContainer)
        }

		// custom dialog
		val dialog = Dialog(this)
		dialog.setContentView(
			LinearLayout(this).apply {
				orientation = LinearLayout.VERTICAL

				// Top bar with X close button
				val topBar = LinearLayout(context).apply {
					orientation = LinearLayout.HORIZONTAL
					gravity = Gravity.END
					setPadding(8, 8, 8, 8)
					layoutParams = LinearLayout.LayoutParams(
						LinearLayout.LayoutParams.MATCH_PARENT,
						LinearLayout.LayoutParams.WRAP_CONTENT
					)

					val closeBtn = ImageButton(context).apply {
						setImageResource(R.drawable.baseline_close_24)
						background = null
						val size = 32
						layoutParams = LinearLayout.LayoutParams(size.toPx(), size.toPx())
						setOnClickListener { dialog.dismiss() }
					}
					addView(closeBtn)
				}

				// Add top bar and content root into dialog container
				addView(topBar)
				addView(root)
			}
		)

		// Nice rounded dialog window
		dialog.window?.setLayout(
			ViewGroup.LayoutParams.MATCH_PARENT,
			ViewGroup.LayoutParams.WRAP_CONTENT
		)

		dialog.show()

    }

    ////////////////////////////////////////////////////////////////
    // Intercept hardware volume keys in the main screen.
    // If the configured action is not NONE, consume the event
    // so Android does not change the media volume.
    ////////////////////////////////////////////////////////////////
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                handleVolumeKeyPress(isVolumeUp = true)
                true  // always consume; no system volume UI
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                handleVolumeKeyPress(isVolumeUp = false)
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }


}

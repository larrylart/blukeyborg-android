package com.blu.blukeyborg

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.navigation.NavOptions
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.navOptions
import androidx.core.view.ViewCompat
import androidx.core.graphics.drawable.DrawableCompat
import android.text.InputType
import android.widget.EditText
import androidx.appcompat.app.AlertDialog

import com.blu.blukeyborg.ui.TypeFragment

////////////////////////////////////////////////////////////////////
// Activity hosting the app chrome (top/bottom bars) and the NavHostFragment.
// Fragments render the content area only.
////////////////////////////////////////////////////////////////////
class MainActivity : AppCompatActivity() {

    private lateinit var navHost: NavHostFragment

    private lateinit var ledDot: View
    private lateinit var titleCluster: View

    private lateinit var btnSettingsTop: ImageButton
    private lateinit var btnCloseTop: ImageButton

    private lateinit var btnDevices: ImageButton
    private lateinit var btnType: ImageButton

    // Action buttons in the shell (used when TypeFragment is showing)
    private lateinit var btnSpecialKeys: ImageButton
    private lateinit var btnFullKeyboard: ImageButton
    private lateinit var btnRemoteBottom: ImageButton

	private var appClosing = false
	
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Shell layout: top bar + NavHost + bottom bar
        setContentView(R.layout.activity_main)

        // ensure BleHub holds app context + has a password prompt early
        //BleHub.init(requireContext())
		// Init BLE + register a global password prompt (used by provisioning/handshake failures).
		BleHub.init(applicationContext)

		BleHub.setPasswordPrompt { _, reply ->
			runOnUiThread {
				val edit = android.widget.EditText(this).apply {
					inputType =
						android.text.InputType.TYPE_CLASS_TEXT or
						android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
					hint = "Dongle password"
				}

				androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Secure the dongle")
                    .setMessage("Enter the dongle password")
					.setView(edit)
					.setPositiveButton("OK") { _, _ ->
						reply(edit.text.toString().toCharArray())
					}
					.setNegativeButton(android.R.string.cancel) { _, _ ->
						reply(null)
					}
					.show()
			}
		}

        applyImmersiveNavBar()

        navHost = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHost.navController

        // Decide the initial destination only on first creation
        if (savedInstanceState == null) {
            val graph = navController.navInflater.inflate(R.navigation.nav_main)

            val hasSelectedDevice = BleHub.hasSelectedDevice()
            val useExt = PreferencesUtil.useExternalKeyboardDevice(this)

            graph.setStartDestination(
                if (!hasSelectedDevice || !useExt) R.id.devicesFragment else R.id.typeFragment
            )

            navController.graph = graph
        }

        bindShellViews()
		applyShellInsets()
        wireShellButtons()
        wireLedIndicator()
        wireShellStateObservers()
        wireDestinationBehavior()
    }

	override fun onStart() {
		super.onStart()

		// IMPORTANT: if we’re already connected (transport or secure),
		// do NOT restart autoConnectFromPrefs (it disconnects internally).
		if (BleHub.bleConnected.value == true || BleHub.connected.value == true) {
			updateLed()
			return
		}

		if (BleHub.hasSelectedDevice() && PreferencesUtil.useExternalKeyboardDevice(this)) {
			BleHub.autoConnectFromPrefs { ok, msg ->
				if (!ok) {
					runOnUiThread {
						val navController = navHost.navController

						if (navController.currentDestination?.id != R.id.devicesFragment) {
							val args = Bundle().apply {
								putString("autoLaunchReason", msg ?: "auto-connect failed")
							}
							val opts = androidx.navigation.NavOptions.Builder()
								.setLaunchSingleTop(true)
								.setPopUpTo(R.id.devicesFragment, inclusive = false)
								.build()

							navController.navigate(R.id.devicesFragment, args, opts)
						}

						Toast.makeText(
							this,
							msg ?: "Could not connect to the dongle",
							Toast.LENGTH_SHORT
						).show()
					}
				}
			}
		}
	}

    // --------------------------------------------------------------------
    // Shell view binding
    // --------------------------------------------------------------------

    private fun bindShellViews() {
        // TOP BAR (top_bar.xml)
        btnSettingsTop = findViewById(R.id.btnSettingsTop)
        btnCloseTop = findViewById(R.id.btnCloseTop)

        ledDot = findViewById(R.id.ledDot)
        titleCluster = findViewById(R.id.titleCluster)

        // BOTTOM BAR (bottom_bar.xml)
        btnDevices = findViewById(R.id.btnDevices)
        btnType = findViewById(R.id.btnType)

        btnSpecialKeys = findViewById(R.id.btnSpecialKeys)
        btnFullKeyboard = findViewById(R.id.btnFullKeyboard)
        btnRemoteBottom = findViewById(R.id.btnRemoteBottom)
    }

    // --------------------------------------------------------------------
    // Shell buttons wiring (these used to be in old MainActivity)
    // --------------------------------------------------------------------

	private fun wireShellButtons() {
		val navController = navHost.navController

		// TOP BAR
		btnSettingsTop.setOnClickListener {
			startActivity(Intent(this, OutputDeviceSettingsActivity::class.java))
		}

		btnCloseTop.setOnClickListener {
			// Make shutdown robust even if DevicesFragment is scanning
			appClosing = true
			BleHub.stopDeviceScan()
			BleHub.disconnect()
			finishAffinity()
		}

		// BOTTOM NAV TABS
		btnDevices.setOnClickListener {
			if (navController.currentDestination?.id != R.id.devicesFragment) {
				navController.navigate(R.id.devicesFragment)
			}
		}

		btnType.setOnClickListener {
			if (navController.currentDestination?.id != R.id.typeFragment) {
				navController.navigate(R.id.typeFragment)
			}
		}

		// SHELL ACTION BUTTONS

		// Special Keys should NOT navigate to Type. Show dialog over current fragment.
		btnSpecialKeys.setOnClickListener {
			if (BleHub.connected.value != true) {
				Toast.makeText(this, "Dongle not connected", Toast.LENGTH_SHORT).show()
				return@setOnClickListener
			}

			BleHub.enableFastKeys { ok, err ->
				runOnUiThread {
					if (!ok) {
						Toast.makeText(
							this,
							err ?: getString(R.string.msg_failed_enable_fast_keys),
							Toast.LENGTH_SHORT
						).show()
						return@runOnUiThread
					}

					com.blu.blukeyborg.ui.SpecialKeysDialog()
						.show(supportFragmentManager, "SpecialKeysDialog")
				}
			}
		}

		// Keep existing behavior for Full Keyboard (still delegated to TypeFragment)
		btnFullKeyboard.setOnClickListener { callTypeFragmentAction { it.shellFullKeyboardClicked() } }

		btnRemoteBottom.setOnClickListener {
			startActivity(Intent(this, RemoteControlActivity::class.java))
		}
	}


    ////////////////////////////////////////////////////////////////
    // Helper: find the currently displayed TypeFragment and call a method on it.
    // If we are not on TypeFragment, we navigate to it first then call after nav finishes.
    ////////////////////////////////////////////////////////////////
    private fun callTypeFragmentAction(block: (TypeFragment) -> Unit) {
        val navController = navHost.navController
        if (navController.currentDestination?.id != R.id.typeFragment) {
            navController.navigate(R.id.typeFragment)
            navController.addOnDestinationChangedListener(object :
                androidx.navigation.NavController.OnDestinationChangedListener {
                override fun onDestinationChanged(
                    controller: androidx.navigation.NavController,
                    destination: androidx.navigation.NavDestination,
                    arguments: Bundle?
                ) {
                    if (destination.id == R.id.typeFragment) {
                        controller.removeOnDestinationChangedListener(this)
                        (navHost.childFragmentManager.primaryNavigationFragment as? TypeFragment)?.let(block)
                    }
                }
            })
            return
        }

        (navHost.childFragmentManager.primaryNavigationFragment as? TypeFragment)?.let(block)
    }

	////////////////////////////////////////////////////////////////
    // LED status logic 
    ////////////////////////////////////////////////////////////////
    private fun wireLedIndicator() {
        // Keep LED state fresh
        updateLed()
        BleHub.bleConnected.observe(this) { updateLed() }
        BleHub.connected.observe(this) { updateLed() }

        val ledClickListener = View.OnClickListener {
            if (!BleHub.hasSelectedDevice()) {
                Toast.makeText(this, "Select a device in Settings first", Toast.LENGTH_SHORT).show()
                return@OnClickListener
            }

            val secure = (BleHub.connected.value == true)
            val ble = (BleHub.bleConnected.value == true)

            if (secure) return@OnClickListener

            if (ble) {
                BleHub.disconnect()
                ledDot.postDelayed({
                    BleHub.connectSelectedDevice { ok, msg ->
                        if (!ok && msg != null) {
                            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                        }
                    }
                }, 250)
                return@OnClickListener
            }

            BleHub.connectSelectedDevice { ok, msg ->
                if (!ok && msg != null) {
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                }
            }
        }

        ledDot.setOnClickListener(ledClickListener)
        titleCluster.setOnClickListener(ledClickListener)
    }

	private fun updateLed() {
		val hasDevice = BleHub.hasSelectedDevice()
		val secure = (BleHub.connected.value == true)
		val ble = (BleHub.bleConnected.value == true)

		val color = when {
			!hasDevice -> 0xFFAAAAAA.toInt()
			secure -> 0xFF00C853.toInt()
			ble -> 0xFFFFA000.toInt()
			else -> 0xFFE53935.toInt()
		}

		val d = ledDot.background?.mutate()
		if (d != null) {
			DrawableCompat.setTint(d, color)
			ledDot.background = d
		} else {
			ledDot.setBackgroundColor(color) // fallback
		}
	}

    ////////////////////////////////////////////////////////////////
    // Enable/disable shell buttons based on MTLS-ready 
    ////////////////////////////////////////////////////////////////
	private fun wireShellStateObservers() {
		setShellUiEnabled(BleHub.connected.value == true)

		var everConnected = (BleHub.connected.value == true)

		BleHub.connected.observe(this) { ready ->
			val ok = (ready == true)
			if (ok) everConnected = true

			setShellUiEnabled(ok)

			// Optional: redirect only on a *drop* (not on cold start)
			if (!ok && everConnected) {
				val navController = navHost.navController
				if (navController.currentDestination?.id != R.id.devicesFragment) {
					val opts = androidx.navigation.NavOptions.Builder()
						.setLaunchSingleTop(true)
						.build()
					navController.navigate(R.id.devicesFragment, null, opts)
				}
			}
		}
	}

	private fun setShellUiEnabled(ready: Boolean) {
		// Type tab should only be usable when MTLS-ready
		btnType.isEnabled = ready

		// Buttons / entry fields that require MTLS-ready session:
		btnSpecialKeys.isEnabled = ready
		btnFullKeyboard.isEnabled = ready
		btnRemoteBottom.isEnabled = ready

		val alpha = if (ready) 1.0f else 0.35f

		btnType.alpha = alpha

		btnSpecialKeys.alpha = alpha
		btnFullKeyboard.alpha = alpha
		btnRemoteBottom.alpha = alpha
	}

    ////////////////////////////////////////////////////////////////
    // Destination-aware shell behavior 
    ////////////////////////////////////////////////////////////////
	private fun wireDestinationBehavior() {
		val navController = navHost.navController
		navController.addOnDestinationChangedListener { _, destination, _ ->

			val onType = destination.id == R.id.typeFragment
			val onDevices = destination.id == R.id.devicesFragment

			val onSetup = destination.id == R.id.setupFragment

			// Hide the shell chrome on Setup (full-screen flow)
			findViewById<View>(R.id.topBar).visibility = if (onSetup) View.GONE else View.VISIBLE
			findViewById<View>(R.id.bottomBar).visibility = if (onSetup) View.GONE else View.VISIBLE

			// If Setup is showing, stop here (don’t force-show any shell buttons)
			if (onSetup) return@addOnDestinationChangedListener


			// Tab highlight
			//btnType.isSelected = onType
			//btnDevices.isSelected = onDevices

			//btnType.alpha = if (onType) 1.0f else 0.45f
			//btnDevices.alpha = if (onDevices) 1.0f else 0.45f

			// keep the full bottom menu visible on all screens
			btnSpecialKeys.visibility = View.VISIBLE
			btnFullKeyboard.visibility = View.VISIBLE
			btnRemoteBottom.visibility = View.VISIBLE

			// Optional: if you want them slightly dimmed when not on Type:
			//val actionAlpha = if (onType) 1.0f else 0.75f
			//btnSpecialKeys.alpha = actionAlpha
			//btnFullKeyboard.alpha = actionAlpha
			//btnRemoteBottom.alpha = actionAlpha
		}
	}

	////////////////////////////////////////////////////////////////
    // Immersive nav bar 
	////////////////////////////////////////////////////////////////
	private fun applyImmersiveNavBar() {
		// Edge-to-edge; we handle insets manually
		WindowCompat.setDecorFitsSystemWindows(window, false)

		// Force white status bar
		window.statusBarColor = android.graphics.Color.WHITE
		window.decorView.systemUiVisibility = 0

		val controller = WindowInsetsControllerCompat(window, window.decorView)

		controller.systemBarsBehavior =
			WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

		// Hide ONLY nav bar
		controller.hide(WindowInsetsCompat.Type.navigationBars())

		// Dark icons on light (white) status bar
		controller.isAppearanceLightStatusBars = true
	}


    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersiveNavBar()
    }

	private fun applyShellInsets() {
		val root = findViewById<View>(android.R.id.content)
		val topBar = requireNotNull(findViewById<View>(R.id.topBar)) { "Missing @id/topBar in activity_main.xml" }
		val bottomBar = requireNotNull(findViewById<View>(R.id.bottomBar)) { "Missing @id/bottomBar in activity_main.xml" }

		val rootTop = root.paddingTop
		val botStart = bottomBar.paddingStart
		val botTop = bottomBar.paddingTop
		val botEnd = bottomBar.paddingEnd
		val botBottom = bottomBar.paddingBottom

		// Apply status bar padding to the WHOLE window content so the blue bar starts below it
		ViewCompat.setOnApplyWindowInsetsListener(root) { v: View, insets: WindowInsetsCompat ->
			val sb = insets.getInsets(WindowInsetsCompat.Type.statusBars())
			v.setPadding(v.paddingLeft, rootTop + sb.top, v.paddingRight, v.paddingBottom)
			insets
		}

		// Bottom bar: gesture + IME 
		ViewCompat.setOnApplyWindowInsetsListener(bottomBar) { v: View, insets: WindowInsetsCompat ->
			val gest = insets.getInsets(WindowInsetsCompat.Type.systemGestures())
			val ime = insets.getInsets(WindowInsetsCompat.Type.ime())

			val gestureSafe = gest.bottom.coerceAtMost(dpToPx(12))
			val bottomInset = maxOf(gestureSafe, ime.bottom)

			v.setPaddingRelative(botStart, botTop, botEnd, botBottom + bottomInset)
			insets
		}

		ViewCompat.requestApplyInsets(root)
	}


	private fun dpToPx(dp: Int): Int =
		(dp * resources.displayMetrics.density).toInt()

	override fun onStop() {
		super.onStop()

		// If the task is being finished, ensure BLE is torn down.
		if (isFinishing || appClosing) {
			BleHub.stopDeviceScan()
			BleHub.disconnect()
		}
	}

	override fun onDestroy() {
		// Extra safety: if Android destroys us while finishing, disconnect anyway.
		if (isFinishing || appClosing) {
			BleHub.stopDeviceScan()
			BleHub.disconnect()
		}
		super.onDestroy()
	}

	override fun onResume() {
		super.onResume()
		BleHub.userActive()
	}
}

package com.blu.blukeyborg.ui

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.lifecycle.Observer
import com.blu.blukeyborg.BleHub
import com.blu.blukeyborg.PreferencesUtil
import com.blu.blukeyborg.BleAppSec
import com.blu.blukeyborg.R

class SetupFragment : Fragment(R.layout.fragment_setup) {

    companion object {
        const val ARG_ADDR = "addr"
        const val ARG_NAME = "name"
		
		const val ARG_BONDED = "bonded"
		const val ARG_PROVISIONED = "provisioned"
		const val ARG_SKIP_INTRO = "skip_intro"
		const val ARG_LAST_ERROR = "last_error"		
    }

    private lateinit var title: TextView
    private lateinit var scroll: ScrollView
    private lateinit var btn: Button
    private lateinit var btnBack: ImageButton

    private lateinit var beforeText: TextView
    private lateinit var stepsText: TextView

    private var addr: String = ""
    private var name: String = ""

    private enum class UiState { INTRO, RUNNING, FAILED, SUCCESS }
    private var state: UiState = UiState.INTRO

    private lateinit var introSection: View
    private lateinit var progressSection: View
    private lateinit var progressTitle: TextView
    private lateinit var progressBody: TextView
    private lateinit var progressList: LinearLayout

    // Simplified user-facing steps (only 4)
    private enum class Step {
        CONNECTION,
        PAIRING,
        PROVISIONED,
        SECURE
    }

    private enum class Final { NONE, OK, FAIL }
    private val latched = LinkedHashMap<Step, Final>()
    private val stepViews = LinkedHashMap<Step, TextView>()

    private fun isFinal(step: Step): Boolean {
        val f = latched[step] ?: Final.NONE
        return f == Final.OK || f == Final.FAIL
    }

    // Observe true transport connect/disconnect (GATT only)
    // Important: historical. If CONNECTION already succeeded, never downgrade later.
	private val transportObserver = Observer<Boolean> { up ->
		if (state != UiState.RUNNING) return@Observer

		if (up == true) {
			// First successful GATT connection
			if (!isFinal(Step.CONNECTION)) {
				setStepOk(Step.CONNECTION, "Connection")
			}
		} else {
			// IMPORTANT:
			// Only treat disconnect as failure if we were previously connected
			val wasConnected = latched[Step.CONNECTION] == Final.OK
			if (wasConnected && !isFinal(Step.CONNECTION)) {
				setStepFail(Step.CONNECTION, "Connection", "Disconnected")
			}
		}
	}


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        title = view.findViewById(R.id.setupTitle)

        beforeText = view.findViewById(R.id.setupBeforeText)
        stepsText = view.findViewById(R.id.setupStepsText)

        introSection = view.findViewById(R.id.setupIntroSection)
        progressSection = view.findViewById(R.id.setupProgressSection)
        progressTitle = view.findViewById(R.id.setupProgressTitle)
        progressBody = view.findViewById(R.id.setupProgressBody)
        progressList = view.findViewById(R.id.setupProgressList)

        scroll = view.findViewById(R.id.setupScroll)
        btn = view.findViewById(R.id.setupButton)

        btnBack = view.findViewById(R.id.btnBack)
        btnBack.setOnClickListener {
            findNavController().popBackStack(R.id.devicesFragment, false)
        }

        addr = arguments?.getString(ARG_ADDR).orEmpty()
        name = arguments?.getString(ARG_NAME).orEmpty()

        title.text = "Setup: ${if (name.isNotBlank()) name else addr}"

/*        BleHub.init(requireContext())

        // Password prompt used by provisioning (same idea as settings screen)
        BleHub.setPasswordPrompt { _, reply ->
            val activity = requireActivity()
            val edit = android.widget.EditText(activity).apply {
                inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                hint = "Dongle password"
            }
            AlertDialog.Builder(activity)
                .setTitle("Dongle password")
                .setMessage("Enter the password you set during the dongle’s initial setup.")
                .setView(edit)
                .setPositiveButton("OK") { _, _ -> reply(edit.text.toString().toCharArray()) }
                .setNegativeButton(android.R.string.cancel) { _, _ -> reply(null) }
                .show()
        }
*/
        //renderIntro()

        btn.setOnClickListener {
            when (state) {
                UiState.INTRO, UiState.FAILED -> runSetup()
                UiState.SUCCESS -> findNavController().navigate(R.id.typeFragment)
                UiState.RUNNING -> Unit
            }
        }
		
		val bonded = arguments?.getBoolean(ARG_BONDED, false) ?: false
		val provisioned = arguments?.getBoolean(ARG_PROVISIONED, false) ?: false
		val lastError = arguments?.getString(ARG_LAST_ERROR)

		// FLOW:
		// - Not bonded yet: show full intro + user presses Continue for first-time setup
		// - Bonded but not provisioned: go straight to progress screen + allow retry provisioning
		if (!bonded) {
			renderIntro()
		} else if (!provisioned) {
			// Paired but missing app key: provisioning-only flow
			resetProgressUiForRetry()
			progressTitle.text = "Finish setup"
			progressBody.text = lastError?.takeIf { it.isNotBlank() }
				?: "This dongle is paired but not provisioned yet. Tap Retry setup to finish setup."
		} else {
			// Already paired + provisioned -> show intro (or you could auto-continue later)
			renderIntro()
		}
		
    }

    override fun onStart() {
        super.onStart()
        BleHub.bleConnected.observe(viewLifecycleOwner, transportObserver)
    }

    private fun setMainChromeVisible(visible: Boolean) {
        val a = activity ?: return
        a.findViewById<View?>(R.id.topBar)?.visibility = if (visible) View.VISIBLE else View.GONE
        a.findViewById<View?>(R.id.bottomBar)?.visibility = if (visible) View.VISIBLE else View.GONE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        BleHub.clearPasswordPrompt()
    }

    private fun renderIntro() {
        state = UiState.INTRO
        btn.isEnabled = true
        btn.text = "Continue"

        showIntroUi()

        beforeText.text = buildString {
            append("• Make sure the dongle is on the latest firmware.\n")
            append("• Run the dongle’s initial Wi-Fi setup portal (set name, layout, password).")
        }

        // Only 4 user-facing steps
        stepsText.text = buildString {
            append("• Connection (confirm the dongle is powered on)\n")
            append("• Pairing (if required)\n")
            append("• Provisioned (app key setup if needed)\n")
            append("• Secure connection\n")
        }

        scrollToBottom()
    }

	private fun runSetup() {
		// Disable button immediately (fine), but DO NOT enter RUNNING yet
		btn.isEnabled = false
		btn.text = "Setting things up…"

		// 1) Close any BLE connection first (while NOT in RUNNING, so observer ignores the down event)
		BleHub.disconnect(suppressMs = 0L)

		// 2) Now enter RUNNING and build fresh progress UI
		state = UiState.RUNNING
		showProgressUi()

		// Build 4 bullet steps
		addStep(Step.CONNECTION, "Connection")
		addStep(Step.PAIRING, "Pairing")
		addStep(Step.PROVISIONED, "Provisioned")
		addStep(Step.SECURE, "Secure connection")

		// initial running state
		setStepRunning(Step.CONNECTION, "Connection")
		setStepRunning(Step.PAIRING, "Pairing")
		setStepRunning(Step.PROVISIONED, "Provisioned")
		setStepRunning(Step.SECURE, "Secure connection")

		// Select this device so BleHub.connectFromSettings targets it
		val ctx = requireContext().applicationContext

		// keep a copy
		val prevId = PreferencesUtil.getOutputDeviceId(ctx)
		val prevName = PreferencesUtil.getOutputDeviceName(ctx)
		val prevUse = PreferencesUtil.useExternalKeyboardDevice(ctx)
		
		// do not set it up as a default yet - just when successful 
		PreferencesUtil.setOutputDeviceId(ctx, addr)
		PreferencesUtil.setOutputDeviceName(ctx, if (name.isBlank()) addr else name)

		// Kick off setup flow
		try {
			BleHub.connectFromSettings(
				onProgress = { stage, st, msg ->
					if (!isAdded) return@connectFromSettings
					requireActivity().runOnUiThread {
						applyProgress(stage, st, msg)
					}
				},
				onDone = { ok, err ->
					if (!isAdded) return@connectFromSettings
					requireActivity().runOnUiThread {
						if (ok) {
							progressBody.text = "✅ Dongle setup completed successfully."							
							PreferencesUtil.setUseExternalKeyboardDevice(ctx, true)

							state = UiState.SUCCESS
							btn.isEnabled = true
							btn.text = "Continue to typing"
						} else 
						{
							// on failure restore prev state
							PreferencesUtil.setOutputDeviceId(ctx, prevId)
							PreferencesUtil.setOutputDeviceName(ctx, prevName)
							PreferencesUtil.setUseExternalKeyboardDevice(ctx, prevUse)							
							
							// IMPORTANT: on ANY failure, hard reset BLE/MTLS state so Retry can start clean
							BleHub.disconnect(suppressMs = 0L)

							progressBody.text = "❌ Setup failed: ${err ?: "Unknown error"}"
							state = UiState.FAILED
							btn.isEnabled = true
							btn.text = "Retry"
						}
					}
				}
			)
		} catch (_: Throwable) {
			BleHub.connectFromSettings { ok, err ->
				if (!isAdded) return@connectFromSettings
				requireActivity().runOnUiThread {
					if (ok) {
						setStepOk(Step.CONNECTION, "Connection")
						setStepOk(Step.PAIRING, "Pairing")
						setStepOk(Step.PROVISIONED, "Provisioned")
						setStepOk(Step.SECURE, "Secure connection")

						progressBody.text = "✅ Dongle setup completed successfully."
					
						PreferencesUtil.setUseExternalKeyboardDevice(ctx, true)

						state = UiState.SUCCESS
						btn.isEnabled = true
						btn.text = "Continue to typing"
					} else 
					{
						// on failure restore prev state
						PreferencesUtil.setOutputDeviceId(ctx, prevId)
						PreferencesUtil.setOutputDeviceName(ctx, prevName)
						PreferencesUtil.setUseExternalKeyboardDevice(ctx, prevUse)
						
						// IMPORTANT: on ANY failure, hard reset BLE/MTLS state so Retry can start clean
						BleHub.disconnect(suppressMs = 0L)

						if (!isFinal(Step.CONNECTION)) {
							setStepFail(Step.CONNECTION, "Connection", "Failed")
						}
						setStepFail(Step.SECURE, "Secure connection", err ?: "Failed")

						progressBody.text = "❌ Setup failed: ${err ?: "Unknown error"}"
						state = UiState.FAILED
						btn.isEnabled = true
						btn.text = "Retry"
					}
				}
			}
		}
	}

    // Map BleHub progress -> the 4 UI steps
    private fun applyProgress(stage: BleHub.SetupStage, st: BleHub.StageState, msg: String?) {
        when (stage) {
            BleHub.SetupStage.GATT_CONNECT -> {
                // Actual OK comes from bleConnected observer, but if BleHub reports FAIL we show it.
                when (st) {
                    BleHub.StageState.START -> setStepRunning(Step.CONNECTION, "Connection")
                    BleHub.StageState.FAIL -> if (!isFinal(Step.CONNECTION)) {
                        setStepFail(Step.CONNECTION, "Connection", msg ?: "Failed")
                    }
                    BleHub.StageState.OK -> if (!isFinal(Step.CONNECTION)) {
                        setStepOk(Step.CONNECTION, "Connection")
                    }
                    BleHub.StageState.INFO -> Unit
                }
            }

            BleHub.SetupStage.NOTIFICATIONS_READY -> {
                // Keep under "Connection" (don’t expose internal detail)
                when (st) {
                    BleHub.StageState.FAIL -> if (!isFinal(Step.CONNECTION)) {
                        setStepFail(Step.CONNECTION, "Connection", msg ?: "Failed")
                    }
                    else -> Unit
                }
            }

            BleHub.SetupStage.PAIRING -> {
                // Pairing is system-driven; reflect what BleHub observes.
                when (st) {
                    BleHub.StageState.START -> setStepRunning(Step.PAIRING, "Pairing")
                    BleHub.StageState.OK -> setStepOk(Step.PAIRING, "Pairing")
                    BleHub.StageState.FAIL -> setStepFail(Step.PAIRING, "Pairing", msg ?: "Failed")
                    BleHub.StageState.INFO -> Unit
                }
            }

            BleHub.SetupStage.PROVISION_APPKEY -> {
                // Generic messaging (don’t mention protocol specifics)
                when (st) {
                    BleHub.StageState.START -> setStepRunning(Step.PROVISIONED, "Provisioned")
                    BleHub.StageState.OK -> setStepOk(Step.PROVISIONED, "Provisioned")
                    BleHub.StageState.FAIL -> setStepFail(
                        Step.PROVISIONED,
                        "Provisioned",
                        msg ?: "Failed (check password / dongle response)"
                    )
                    BleHub.StageState.INFO -> Unit
                }
            }

            BleHub.SetupStage.WAIT_B0 -> {
                // Users don’t care about B0; if it fails, treat as "Secure connection" failure.
                if (st == BleHub.StageState.FAIL) {
                    setStepFail(Step.SECURE, "Secure connection", msg ?: "No response from dongle")
                }
            }

            BleHub.SetupStage.MTLS_HANDSHAKE -> {
                when (st) {
                    BleHub.StageState.START -> setStepRunning(Step.SECURE, "Secure connection")
                    BleHub.StageState.OK -> {
						setStepOk(Step.SECURE, "Secure connection")
						if (BleAppSec.getKey(requireContext().applicationContext, addr) != null) {
							setStepOk(Step.PROVISIONED, "Provisioned")
						}
					}
                    BleHub.StageState.FAIL -> setStepFail(Step.SECURE, "Secure connection", msg ?: "Failed")
                    BleHub.StageState.INFO -> Unit
                }
            }

            BleHub.SetupStage.FETCH_LAYOUT -> {
                // Not shown as a step. Ignore for user-facing progress.
            }
        }

        // Keep INFO messages in body if you want (existing behavior)
        if (st == BleHub.StageState.INFO && !msg.isNullOrBlank()) {
            progressBody.text = msg
        }
    }

    private fun scrollToBottom() {
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }	
    }

	override fun onResume() {
		super.onResume()
		setMainChromeVisible(false)

		val bonded = arguments?.getBoolean(ARG_BONDED, false) ?: false
		val provisioned = arguments?.getBoolean(ARG_PROVISIONED, false) ?: false

		// Only "unstick" the UI for the provisioning-only flow.
		if (bonded && !provisioned) {
			// If we were mid-run and came back with a disabled button, allow retry.
			if (state == UiState.RUNNING && (::btn.isInitialized && !btn.isEnabled)) {
				resetProgressUiForRetry()
			}
		}
	}


    override fun onPause() {
        setMainChromeVisible(true)
        super.onPause()
    }

    private fun showIntroUi() {
        introSection.visibility = View.VISIBLE
        progressSection.visibility = View.GONE
    }

    private fun showProgressUi() {
        introSection.visibility = View.GONE
        progressSection.visibility = View.VISIBLE
        progressTitle.text = "Setting up your dongle…"
        progressBody.text = "Please keep the dongle connected. This may take a few seconds."
        progressList.removeAllViews()
        stepViews.clear()
        latched.clear()
    }

    private fun addStep(step: Step, label: String) {
        latched[step] = Final.NONE

        val tv = TextView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            textSize = 16f
            setLineSpacing(0f, 1.25f)
            setTextColor(Color.parseColor("#666666"))
            text = "• $label"
            setPadding(0, 8, 0, 8)
        }
        progressList.addView(tv)
        stepViews[step] = tv
    }

    private fun setStepRunning(step: Step, label: String) {
        if (isFinal(step)) return
        stepViews[step]?.apply {
            setTextColor(Color.parseColor("#666666"))
            text = "• $label …"
        }
    }

    private fun setStepOk(step: Step, label: String) {
        if (isFinal(step)) return
        latched[step] = Final.OK
        stepViews[step]?.apply {
            setTextColor(Color.parseColor("#1B7F3A"))
            text = "• $label ✓"
        }
    }

    private fun setStepFail(step: Step, label: String, err: String?) {
        if (isFinal(step)) return
        latched[step] = Final.FAIL
        stepViews[step]?.apply {
            setTextColor(Color.parseColor("#B3261E"))
            text = "• $label ✕${if (!err.isNullOrBlank()) " — $err" else ""}"
        }
    }
	
	private fun resetProgressUiForRetry() {
		// Disarm "disconnect is a failure" logic BEFORE we intentionally disconnect
		//connectAttemptArmed = false

		// Stop any existing connection/autoconnect attempt
		BleHub.disconnect(suppressMs = 0L)

		// Show the progress UI (second screen) and clear prior steps/state
		showProgressUi()

		// Rebuild the 4 bullet steps every time, so we don't keep stale state
		addStep(Step.CONNECTION, "Connection")
		addStep(Step.PAIRING, "Pairing")
		addStep(Step.PROVISIONED, "Provisioned")
		addStep(Step.SECURE, "Secure connection")

		// Reset rows to "ready"
		setStepRunning(Step.CONNECTION, "Connection")
		setStepRunning(Step.PAIRING, "Pairing")
		setStepRunning(Step.PROVISIONED, "Provisioned")
		setStepRunning(Step.SECURE, "Secure connection")

		// Make button clickable
		state = UiState.FAILED // so click triggers runSetup()
		btn.isEnabled = true
		btn.text = "Retry setup"

		if (progressTitle.text.isNullOrBlank()) {
			progressTitle.text = "Set up your dongle"
		}
		if (progressBody.text.isNullOrBlank()) {
			progressBody.text = "Tap Retry setup to start."
		}
	}

}

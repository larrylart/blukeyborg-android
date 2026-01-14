package com.blu.blukeyborg.ui

import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.appcompat.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.os.SystemClock
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

import com.blu.blukeyborg.ui.SpecialKeysDialog
import com.blu.blukeyborg.*

class DevicesFragment : Fragment(R.layout.fragment_devices) {

    private lateinit var recycler: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var adapter: DevicesAdapter

	private lateinit var scanOverlay: LinearLayout
	private val main = Handler(Looper.getMainLooper())
	private var scanUiActive: Boolean = false
	private var scanUiTimeout: Runnable? = null

	private var scanUiShownAtMs: Long = 0L
	private val scanUiMinVisibleMs: Long = 450L

	private var lastDevices: List<BtDevice> = emptyList()

	private val blePermLauncher =
		registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
			val ok = res.values.all { it }
			if (ok) {
				scanOverlay.post {
					startInitialScanUi()
					BleHub.startDeviceScan()
				}
			} else {
				// Don’t keep the gray overlay forever if perms denied
				scanUiActive = false
				scanOverlay.visibility = View.GONE
				emptyView.visibility = View.VISIBLE
				Toast.makeText(requireContext(), "Bluetooth scan permission denied", Toast.LENGTH_LONG).show()
			}
		}

	private fun requiredBlePerms(): Array<String> {
		return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
			arrayOf(
				Manifest.permission.BLUETOOTH_SCAN,
				Manifest.permission.BLUETOOTH_CONNECT
			)
		} else {
			// Android 6–11: BLE scan requires Location permission at runtime
			arrayOf(
				Manifest.permission.ACCESS_FINE_LOCATION
			)
		}
	}

	private fun hasBlePerms(): Boolean {
		val ctx = requireContext()
		return requiredBlePerms().all {
			ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
		}
	}

	private fun ensureBlePermsThenStartScan() {
		if (hasBlePerms()) {
			scanOverlay.post {
				startInitialScanUi()
				BleHub.startDeviceScan()
			}
		} else {
			blePermLauncher.launch(requiredBlePerms())
		}
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)

		recycler = view.findViewById(R.id.devicesList)
		emptyView = view.findViewById(R.id.devicesEmpty)
		scanOverlay = view.findViewById(R.id.devicesScanOverlay)

		adapter = DevicesAdapter(
			onSelectClicked = { deviceUi ->
				// If not provisioned, go to setup flow instead of selecting
				if (!deviceUi.isProvisioned) {
					goToSetup(deviceUi)
					return@DevicesAdapter
				}

				// Provisioned device => select + connect/switch
				val ctx = requireContext()

				val tappedAddr = deviceUi.address
				val currentTarget = BleHub.getCurrentTargetAddress()

				val secure = (BleHub.connected.value == true)
				val ble = (BleHub.bleConnected.value == true)

				val tappedIsCurrentTarget =
					currentTarget != null && tappedAddr.equals(currentTarget, ignoreCase = true)

				// Always persist selection first (connectSelectedDevice uses prefs)
	
				// keep a copy and restore on failure
				val prevId = PreferencesUtil.getOutputDeviceId(ctx)
				val prevName = PreferencesUtil.getOutputDeviceName(ctx)
				val prevUse = PreferencesUtil.useExternalKeyboardDevice(ctx)

				PreferencesUtil.setOutputDeviceId(ctx, tappedAddr)
				PreferencesUtil.setOutputDeviceName(ctx, deviceUi.name)
				PreferencesUtil.setUseExternalKeyboardDevice(ctx, true)

				// If we tapped what we're already targeting:
				// - if secure is up, just go to Type
				// - if not secure but BLE is up, re-run connectSelectedDevice to finish MTLS
				if (tappedIsCurrentTarget) {
					if (secure) {
						Toast.makeText(ctx, "Selected: ${deviceUi.name}", Toast.LENGTH_SHORT).show()
						findNavController().navigate(R.id.typeFragment)
						return@DevicesAdapter
					}

					BleHub.connectSelectedDevice { ok, msg ->
						requireActivity().runOnUiThread {
							if (ok) {
								Toast.makeText(ctx, "Connected: ${deviceUi.name}", Toast.LENGTH_SHORT).show()																				
								findNavController().navigate(R.id.typeFragment)
							} else if (!msg.isNullOrBlank()) {
								Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
							} else {
								Toast.makeText(ctx, "Could not connect to ${deviceUi.name}", Toast.LENGTH_SHORT).show()
							}
							// if not ok restore
							if( !ok )
							{
								// rollback: tapped device failed, restore previous default
								PreferencesUtil.setOutputDeviceId(ctx, prevId)
								PreferencesUtil.setOutputDeviceName(ctx, prevName)
								PreferencesUtil.setUseExternalKeyboardDevice(ctx, prevUse)								
							}							
						}
					}
					return@DevicesAdapter
				}

				// Switching devices:
				// If we're currently connected (BLE or secure) to *something else*,
				// force a disconnect first, then connect to the tapped one.
				if (ble || secure) {
					Toast.makeText(ctx, "Switching to: ${deviceUi.name}", Toast.LENGTH_SHORT).show()
					BleHub.disconnect()

					// Give the stack a moment to settle before reconnecting
					view.postDelayed({
						BleHub.connectSelectedDevice { ok, msg ->
							requireActivity().runOnUiThread {
								if (ok) {
									Toast.makeText(ctx, "Connected: ${deviceUi.name}", Toast.LENGTH_SHORT).show()								
									findNavController().navigate(R.id.typeFragment)
								} else if (!msg.isNullOrBlank()) {
									Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
								} else {
									Toast.makeText(ctx, "Could not connect to ${deviceUi.name}", Toast.LENGTH_SHORT).show()
								}
								// if not ok restore
								if( !ok )
								{
									// rollback: tapped device failed, restore previous default
									PreferencesUtil.setOutputDeviceId(ctx, prevId)
									PreferencesUtil.setOutputDeviceName(ctx, prevName)
									PreferencesUtil.setUseExternalKeyboardDevice(ctx, prevUse)								
								}									
							}
						}
					}, 250)
					
				} else 
				{
					// Not connected to anything => just connect
					Toast.makeText(ctx, "Connecting to: ${deviceUi.name}", Toast.LENGTH_SHORT).show()
					BleHub.connectSelectedDevice { ok, msg ->
						requireActivity().runOnUiThread {
							if( ok ) 
							{
								Toast.makeText(ctx, "Connected: ${deviceUi.name}", Toast.LENGTH_SHORT).show()					
								findNavController().navigate(R.id.typeFragment)
								
							} else if (!msg.isNullOrBlank()) {
								Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
							} else {
								Toast.makeText(ctx, "Could not connect to ${deviceUi.name}", Toast.LENGTH_SHORT).show()
							}
							// if not ok restore
							if( !ok )
							{
								// rollback: tapped device failed, restore previous default
								PreferencesUtil.setOutputDeviceId(ctx, prevId)
								PreferencesUtil.setOutputDeviceName(ctx, prevName)
								PreferencesUtil.setUseExternalKeyboardDevice(ctx, prevUse)								
							}								
						}
					}
				}
			},
			onSetupClicked = { deviceUi ->
				goToSetup(deviceUi)
			},
			onDeleteClicked = { deviceUi ->
				val addr = deviceUi.address
				val appCtx = requireContext().applicationContext

				AlertDialog.Builder(requireContext())
					.setTitle("Remove device?")
					.setMessage("This will forget the device and remove its stored key. You can set it up again later.")
					.setNegativeButton(android.R.string.cancel, null)
					.setPositiveButton("Remove") { _, _ ->
						Thread {
							val mgr = BluetoothDeviceManager(appCtx)
							mgr.unpair(addr)
							BleAppSec.clearKey(appCtx, addr)
						}.start()
					}
					.show()
			}
		)

		recycler.layoutManager = LinearLayoutManager(requireContext())
		recycler.adapter = adapter

		fun refreshUi() {
			val ui = buildUiModels(lastDevices)
			adapter.submitList(ui)

			// While we show the initial scanning overlay, suppress the
			// "No devices" text to avoid the brief empty flicker.
			if (scanUiActive) {
				emptyView.visibility = View.GONE
				if (ui.isNotEmpty()) finishInitialScanUi(ui)
			} else {
				emptyView.visibility = if (ui.isEmpty()) View.VISIBLE else View.GONE
			}
		}

		// 1) Device list updates (scan/bonded changes)
		BleHub.devicesLive.observe(viewLifecycleOwner) { devices ->
			lastDevices = devices ?: emptyList()
			refreshUi()
		}

		// 2) Connection state updates (LED should change while staying on this screen)
		BleHub.bleConnected.observe(viewLifecycleOwner) {
			refreshUi()
		}
		BleHub.connected.observe(viewLifecycleOwner) {
			refreshUi()
		}

		// 3) Target address changes (LED should change immediately when a connect attempt targets a device)
		BleHub.currentTarget.observe(viewLifecycleOwner) {
			refreshUi()
		}

		BleHub.userActive()
	}

	private fun goToSetup(deviceUi: DeviceUiModel) {
		val skipIntro = deviceUi.bonded && !deviceUi.isProvisioned

		val b = Bundle().apply {
			putString(SetupFragment.ARG_ADDR, deviceUi.address)
			putString(SetupFragment.ARG_NAME, deviceUi.name)

			// reuse device-card state (no BluetoothDeviceManager calls in SetupFragment)
			putBoolean(SetupFragment.ARG_BONDED, deviceUi.bonded)
			putBoolean(SetupFragment.ARG_PROVISIONED, deviceUi.isProvisioned)

			// - not paired => show intro first screen
			// - paired but not provisioned => go straight to progress screen with “Retry …”
			putBoolean(SetupFragment.ARG_SKIP_INTRO, skipIntro)

			// optional: if you want to show a helpful reason on the progress screen
			putString(SetupFragment.ARG_LAST_ERROR, deviceUi.lastError)
		}
		findNavController().navigate(R.id.setupFragment, b)
	}

	private fun showSpecialKeysPopup() {
		if (BleHub.connected.value != true) {
			Toast.makeText(requireContext(), "Dongle not connected", Toast.LENGTH_SHORT).show()
			return
		}

		BleHub.enableFastKeys { ok, err ->
			requireActivity().runOnUiThread {
				if (!ok) {
					Toast.makeText(
						requireContext(),
						err ?: getString(R.string.msg_failed_enable_fast_keys),
						Toast.LENGTH_SHORT
					).show()
					return@runOnUiThread
				}

				SpecialKeysDialog().show(parentFragmentManager, "SpecialKeysDialog")
			}
		}
	}

	override fun onStart() {
		super.onStart()

		ensureBlePermsThenStartScan()
		
		// Post so the fragment is actually drawn first 
		//scanOverlay.post {
		//	startInitialScanUi()
		//	BleHub.startDeviceScan()
		//}
	}

	override fun onStop() {
		super.onStop()
		// Ensure we don't keep the overlay alive if the user navigates away.
		scanUiTimeout?.let { main.removeCallbacks(it) }
		scanUiTimeout = null
		scanUiActive = false
		scanOverlay.visibility = View.GONE
		BleHub.stopDeviceScan()
	}

	private fun buildUiModels(devices: List<BtDevice>): List<DeviceUiModel> {
		val ctx = requireContext()
		val appCtx = ctx.applicationContext

		val selectedAddr = PreferencesUtil.getOutputDeviceId(ctx)
		val currentTarget = BleHub.getCurrentTargetAddress()
		val bleConnected = BleHub.bleConnected.value == true
		val secureConnected = BleHub.connected.value == true

		// global, or  device-specific layout ? keep global for now
		val keyboardLayout = PreferencesUtil.getKeyboardLayout(ctx)

		return devices.map { d ->
			val addr = d.address
			val isSelected = selectedAddr != null && addr.equals(selectedAddr, ignoreCase = true)
			val isProvisioned = BleAppSec.getKey(appCtx, addr) != null

			val isTarget = currentTarget != null && addr.equals(currentTarget, ignoreCase = true)

			// - CONNECTED/PARTIAL refers to "the currently targeted device"
			// - DISCONNECTED_AVAILABLE = provisioned + bonded but not currently connected
			val state = when {
				isTarget && secureConnected -> ConnectionState.CONNECTED
				isTarget && bleConnected && !secureConnected -> ConnectionState.PARTIAL
				isProvisioned && d.bonded -> ConnectionState.DISCONNECTED_AVAILABLE
				else -> ConnectionState.OFFLINE
			}

			DeviceUiModel(
				name = d.name,
				address = addr,
				bonded = d.bonded,
				isProvisioned = isProvisioned,
				keyboardLayout = keyboardLayout,
				firmwareVersion = null,
				protocolVersion = null,
				rssi = d.rssi,
				isSelected = isSelected,
				connectionState = state,
				lastError = null
			)
		}
	}

	override fun onResume() {
		super.onResume()

		BleHub.devicesLive.value?.let { devices ->
			val ui = buildUiModels(devices)
			adapter.submitList(ui)

			// If the scan overlay is still active, keep the empty state hidden
			// to prevent flicker on resume.
			if (scanUiActive) {
				emptyView.visibility = View.GONE
				if (ui.isNotEmpty()) finishInitialScanUi(ui)
			} else {
				emptyView.visibility = if (ui.isEmpty()) View.VISIBLE else View.GONE
			}
		}

		BleHub.userActive()
	}

	private fun startInitialScanUi() {
		// Show the spinner + "Scanning..." overlay for a brief initial window.
		scanUiActive = true
		scanUiShownAtMs = SystemClock.elapsedRealtime()

		scanOverlay.visibility = View.VISIBLE
		emptyView.visibility = View.GONE

		// Safety timeout: hide overlay even if no devices show up.
		scanUiTimeout?.let { main.removeCallbacks(it) }
		val r = Runnable {
			finishInitialScanUi(adapter.currentList)
		}
		scanUiTimeout = r
		main.postDelayed(r, 2500L)
	}

	private fun finishInitialScanUi(currentUi: List<DeviceUiModel>) {
		if (!scanUiActive) return

		val elapsed = SystemClock.elapsedRealtime() - scanUiShownAtMs
		val remaining = scanUiMinVisibleMs - elapsed

		if (remaining > 0) {
			// Keep it visible a tiny bit longer so the user actually sees it
			main.postDelayed({ finishInitialScanUi(currentUi) }, remaining)
			return
		}

		scanUiActive = false
		scanOverlay.visibility = View.GONE

		scanUiTimeout?.let { main.removeCallbacks(it) }
		scanUiTimeout = null

		emptyView.visibility = if (currentUi.isEmpty()) View.VISIBLE else View.GONE
	}

}

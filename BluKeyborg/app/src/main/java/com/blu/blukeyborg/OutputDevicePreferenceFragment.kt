////////////////////////////////////////////////////////////////////
// OutputDevicePreferenceFragment 
// - new simplified version all device pairing, provisioning has 
// been moved in devices fragment/screen
//
// Settings screen responsible for:
//
// - Letting the user toggle "Use external keyboard device" on/off
// - Updating the keyboard layout on the dongle (binary MTLS C0/C1/C2 ops)
// - toggle allow input from share 
// - toggle/enable KP2A plugin input 
// - map phone volume buttons, and layout for remote action
//
////////////////////////////////////////////////////////////////////
package com.blu.blukeyborg

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import keepass2android.pluginsdk.Strings

object Const {
    const val REQUEST_CODE_ENABLE_PLUGIN = 123
    const val REQUEST_CODE_SELECT_APP = 124
    const val REQUEST_CODE_SMS_PROXY_ACTIVATE = 125
    const val REQUEST_CODE_NOTIFICATIONS_PERMISSION = 126
}

class OutputDevicePreferenceFragment : PreferenceFragmentCompat() {

    // Replaces the old device-row switch with a simple labeled switch.
    // This MUST use the same underlying pref-key as PreferencesUtil.useExternalKeyboardDevice().
    private var autoConnectPref: LabeledSwitchPreference? = null

    // Made nullable so the screen can work even if simple XML omits them
    private var layoutPref: SimpleDropdownPreference? = null

    // volume action dropdowns
    private var volumeUpPref: SimpleDropdownPreference? = null
    private var volumeDownPref: SimpleDropdownPreference? = null

    // remote actions panel dropdown
    private var remoteActionsPanelPref: SimpleDropdownPreference? = null

    private val uiHandler = Handler(Looper.getMainLooper())

    private fun postUI(block: (Context) -> Unit) {
        uiHandler.post {
            val ctx = context ?: return@post
            if (!isAdded) return@post
            block(ctx)
        }
    }

    ////////////////////////////////////////////////////////////////////
    // onCreatePreferences
    ////////////////////////////////////////////////////////////////////
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        // ensure BleHub holds app context + has a password prompt EARLY
/*        BleHub.init(requireContext())

        // Register a UI callback for BleHub that can show a password dialog.
        BleHub.setPasswordPrompt { _, reply ->
            postUI {
                val activity = requireActivity()
                val edit = android.widget.EditText(activity).apply {
                    inputType =
                        android.text.InputType.TYPE_CLASS_TEXT or
                                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                    hint = "App password"
                }
                androidx.appcompat.app.AlertDialog.Builder(activity)
                    .setTitle("Secure the dongle")
                    .setMessage("Enter the dongle password")
                    .setView(edit)
                    .setPositiveButton("OK") { _, _ -> reply(edit.text.toString().toCharArray()) }
                    .setNegativeButton("Cancel") { _, _ -> reply(null) }
                    .show()
            }
        }
*/
        setPreferencesFromResource(R.xml.preferences_output_device, rootKey)

        ////////////////////////////////////////////////////////////////////
        // KP2A toggle (keep as-is)
        ////////////////////////////////////////////////////////////////////
        val kp2aToggle = findPreference<LabeledSwitchPreference>("pref_enable_kp2a_plugin")
        kp2aToggle?.setOnPreferenceChangeListener(
            Preference.OnPreferenceChangeListener { preference, newValue ->
                val enable = newValue as Boolean
                val toggle = preference as LabeledSwitchPreference

                if (!enable) {
                    // Turning OFF: no dialog
                    PreferencesUtil.setKp2aPluginEnabled(requireContext(), false)
                    true
                } else {
                    // Turning ON: show warning dialog first
                    val activity = activity ?: return@OnPreferenceChangeListener false

                    androidx.appcompat.app.AlertDialog.Builder(activity)
                        .setTitle("KeePass2Android UI notice")
                        .setMessage(
                            "KeePass2Android's plugin settings screen may be cut off in portrait.\n\n" +
                                    "Please rotate your device to LANDSCAPE so the bottom buttons are visible."
                        )
                        .setPositiveButton("OK") { _, _ ->
                            PreferencesUtil.setKp2aPluginEnabled(requireContext(), true)
                            toggle.setCheckedFromCode(true)

                            try {
                                val intent = Intent(Strings.ACTION_EDIT_PLUGIN_SETTINGS).apply {
                                    putExtra(
                                        Strings.EXTRA_PLUGIN_PACKAGE,
                                        requireContext().packageName
                                    )
                                }
                                startActivityForResult(intent, Const.REQUEST_CODE_ENABLE_PLUGIN)
                            } catch (e: Exception) {
                                e.printStackTrace()
                                Toast.makeText(
                                    requireContext(),
                                    "KeePass2Android not installed (or plugin UI not available)",
                                    Toast.LENGTH_LONG
                                ).show()

                                PreferencesUtil.setKp2aPluginEnabled(requireContext(), false)
                                toggle.setCheckedFromCode(false)
                            }
                        }
                        .setNegativeButton("Cancel") { _, _ ->
                            PreferencesUtil.setKp2aPluginEnabled(requireContext(), false)
                            toggle.setCheckedFromCode(false)
                        }
                        .setOnCancelListener {
                            PreferencesUtil.setKp2aPluginEnabled(requireContext(), false)
                            toggle.setCheckedFromCode(false)
                        }
                        .show()

                    // Important: we handle the toggle manually
                    false
                }
            }
        )

        ////////////////////////////////////////////////////////////////////
        // New "Enable Auto connect on start" switch (replaces device row)
        ////////////////////////////////////////////////////////////////////
        // IMPORTANT: in XML, this preference must use android:key="@string/settings_output_device_key"
        // so it maps to PreferencesUtil.useExternalKeyboardDevice().
        autoConnectPref = findPreference(getString(R.string.settings_output_device_key))

        // Initial UI state
        val enabled = PreferencesUtil.useExternalKeyboardDevice(requireContext())
        autoConnectPref?.setCheckedFromCode(enabled)

        autoConnectPref?.setOnPreferenceChangeListener { _, newValue ->
            val wantEnable = newValue as Boolean
            val ctx = requireContext()

            if (!wantEnable) {
                // OFF: no async needed, let preference framework persist
                PreferencesUtil.setUseExternalKeyboardDevice(ctx, false)
                PreferencesUtil.setOutputDeviceDisabledByError(ctx, false)
                BleHub.disconnect()
                true
            } else {
                // ON: only enable if we have a selected device and connect succeeds.
                val addr = PreferencesUtil.getOutputDeviceId(ctx)
                if (addr.isNullOrBlank()) {
                    Toast.makeText(ctx, R.string.msg_no_device_selected, Toast.LENGTH_SHORT).show()
                    false
                } else {
                    // We handle persistence manually after async result.
                    BleHub.connectFromSettings { ok, _ ->
                        postUI {
                            if (ok) {
                                PreferencesUtil.setUseExternalKeyboardDevice(ctx, true)
                                PreferencesUtil.setOutputDeviceDisabledByError(ctx, false)
                                autoConnectPref?.setCheckedFromCode(true)
                            } else {
                                Toast.makeText(
                                    ctx,
                                    R.string.msg_failed_connect_device,
                                    Toast.LENGTH_SHORT
                                ).show()
                                PreferencesUtil.setUseExternalKeyboardDevice(ctx, false)
                                autoConnectPref?.setCheckedFromCode(false)
                            }
                        }
                    }
                    false
                }
            }
        }

        ////////////////////////////////////////////////////////////////////
        // Keyboard Layout dropdown (keep: sends to currently selected device)
        ////////////////////////////////////////////////////////////////////
        layoutPref = findPreference(getString(R.string.pref_keyboard_layout_key))
        layoutPref?.let { lp ->
            val (layoutEntries, layoutValues) = keyboardLayoutOptions()
            val savedLayout = PreferencesUtil.getKeyboardLayout(requireContext())
            lp.setData(layoutEntries, layoutValues, savedLayout)

            lp.onSelected = { value, _ ->
                val c = requireContext()
                val prev = PreferencesUtil.getKeyboardLayout(c)
                val address = PreferencesUtil.getOutputDeviceId(c)

                if (address.isNullOrBlank()) {
                    Toast.makeText(c, R.string.msg_no_device_selected, Toast.LENGTH_SHORT).show()
                    lp.setSelectedValue(prev)
                } else {
                    BleHub.setLayoutString(value) { ok, err ->
                        postUI {
                            if (ok) {
                                PreferencesUtil.setKeyboardLayout(c, value)
                                Toast.makeText(c, R.string.msg_layout_set_ok, Toast.LENGTH_SHORT)
                                    .show()
                            } else {
                                lp.setSelectedValue(prev)
                                val msg =
                                    if (err.isNullOrBlank()) getString(R.string.msg_layout_set_failed)
                                    else getString(R.string.msg_layout_set_failed) + ": " + err
                                Toast.makeText(c, msg, Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
            }
        }

        ////////////////////////////////////////////////////////////////////
        // Volume key actions (keep)
        ////////////////////////////////////////////////////////////////////
        val volumeEntries = resources.getStringArray(R.array.volume_action_entries).toList()
        val volumeValues = resources.getStringArray(R.array.volume_action_values).toList()

        volumeUpPref = findPreference(getString(R.string.pref_volume_up_action_key))
        volumeUpPref?.let { up ->
            val saved = PreferencesUtil.getVolumeUpAction(requireContext())
            up.setData(volumeEntries, volumeValues, saved)
            up.onSelected = { value, _ ->
                PreferencesUtil.setVolumeUpAction(requireContext(), value)
            }
        }

        volumeDownPref = findPreference(getString(R.string.pref_volume_down_action_key))
        volumeDownPref?.let { down ->
            val saved = PreferencesUtil.getVolumeDownAction(requireContext())
            down.setData(volumeEntries, volumeValues, saved)
            down.onSelected = { value, _ ->
                PreferencesUtil.setVolumeDownAction(requireContext(), value)
            }
        }

        ////////////////////////////////////////////////////////////////////
        // Remote actions panel selector (keep)
        ////////////////////////////////////////////////////////////////////
        val remotePanelEntries =
            resources.getStringArray(R.array.remote_actions_panel_entries).toList()
        val remotePanelValues =
            resources.getStringArray(R.array.remote_actions_panel_values).toList()

        remoteActionsPanelPref = findPreference(getString(R.string.pref_remote_actions_panel_key))
        remoteActionsPanelPref?.let { rp ->
            val saved = PreferencesUtil.getRemoteActionsPanel(requireContext())
            rp.setData(remotePanelEntries, remotePanelValues, saved)
            rp.onSelected = { value, _ ->
                PreferencesUtil.setRemoteActionsPanel(requireContext(), value)
            }
        }

        ////////////////////////////////////////////////////////////////////
        // Enable/disable dependent prefs based on the same master toggle
        ////////////////////////////////////////////////////////////////////
        val isEnabledNow = PreferencesUtil.useExternalKeyboardDevice(requireContext())
        layoutPref?.isEnabled = isEnabledNow
        volumeUpPref?.isEnabled = isEnabledNow
        volumeDownPref?.isEnabled = isEnabledNow
        remoteActionsPanelPref?.isEnabled = isEnabledNow

        // When the switch changes, keep dependent items enabled/disabled.
        autoConnectPref?.setOnPreferenceChangeListener { _, newValue ->
            val wantEnable = newValue as Boolean
            // NOTE: we must not duplicate the listener work, so handle enable/disable here
            // by calling the earlier logic via a small inline dispatch:
            // We'll do the connect/disconnect behavior above (already set). So just mirror enablement.
            // (We can’t easily chain listeners in Preference, so we repeat minimal state updates.)
            layoutPref?.isEnabled = wantEnable
            volumeUpPref?.isEnabled = wantEnable
            volumeDownPref?.isEnabled = wantEnable
            remoteActionsPanelPref?.isEnabled = wantEnable

            // Re-run the same connect gating logic as above:
            val ctx2 = requireContext()
            if (!wantEnable) {
                PreferencesUtil.setUseExternalKeyboardDevice(ctx2, false)
                PreferencesUtil.setOutputDeviceDisabledByError(ctx2, false)
                BleHub.disconnect()
                true
            } else {
                val addr = PreferencesUtil.getOutputDeviceId(ctx2)
                if (addr.isNullOrBlank()) {
                    Toast.makeText(ctx2, R.string.msg_no_device_selected, Toast.LENGTH_SHORT).show()
                    false
                } else {
                    BleHub.connectFromSettings { ok, _ ->
                        postUI {
                            if (ok) {
                                PreferencesUtil.setUseExternalKeyboardDevice(ctx2, true)
                                PreferencesUtil.setOutputDeviceDisabledByError(ctx2, false)
                                autoConnectPref?.setCheckedFromCode(true)
                            } else {
                                Toast.makeText(
                                    ctx2,
                                    R.string.msg_failed_connect_device,
                                    Toast.LENGTH_SHORT
                                ).show()
                                PreferencesUtil.setUseExternalKeyboardDevice(ctx2, false)
                                autoConnectPref?.setCheckedFromCode(false)
                            }
                        }
                    }
                    false
                }
            }
        }
    }

    ////////////////////////////////////////////////////////////////////
    // Lifecycle
    ////////////////////////////////////////////////////////////////////
    override fun onStart() {
        super.onStart()

        // Refresh state from prefs
        val enabled = PreferencesUtil.useExternalKeyboardDevice(requireContext())
        autoConnectPref?.setCheckedFromCode(enabled)

        // refresh layout dropdown from prefs on (re)entry
        val current = PreferencesUtil.getKeyboardLayout(requireContext())
        layoutPref?.let { lp ->
            if (!current.isNullOrBlank()) {
                postUI { lp.setSelectedValue(current) }
            }
        }

        layoutPref?.isEnabled = enabled
        volumeUpPref?.isEnabled = enabled
        volumeDownPref?.isEnabled = enabled
        remoteActionsPanelPref?.isEnabled = enabled
    }

    override fun onStop() {
        super.onStop()
        BleHub.clearPasswordPrompt()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        uiHandler.removeCallbacksAndMessages(null)
    }

    ////////////////////////////////////////////////////////////////////
    // Keyboard layout options (labels, values)
    ////////////////////////////////////////////////////////////////////
    private fun keyboardLayoutOptions(): Pair<List<String>, List<String>> {
        val pairs = listOf(
            "UK_WINLIN" to "Layout UK Windows/Linux",
            "UK_MAC" to "Layout UK Mac",
            "IE_WINLIN" to "Layout IE Windows/Linux",
            "IE_MAC" to "Layout IE Mac",
            "US_WINLIN" to "Layout US Windows/Linux",
            "US_MAC" to "Layout US Mac",

            "DE_WINLIN" to "Layout DE Windows/Linux",
            "DE_MAC" to "Layout DE Mac",
            "FR_WINLIN" to "Layout FR Windows/Linux",
            "FR_MAC" to "Layout FR Mac",
            "ES_WINLIN" to "Layout ES Windows/Linux",
            "ES_MAC" to "Layout ES Mac",
            "IT_WINLIN" to "Layout IT Windows/Linux",
            "IT_MAC" to "Layout IT Mac",

            "PT_PT_WINLIN" to "Layout PT-PT Windows/Linux",
            "PT_PT_MAC" to "Layout PT-PT Mac",
            "PT_BR_WINLIN" to "Layout PT-BR Windows/Linux",
            "PT_BR_MAC" to "Layout PT-BR Mac",

            "SE_WINLIN" to "Layout SE Windows/Linux",
            "NO_WINLIN" to "Layout NO Windows/Linux",
            "DK_WINLIN" to "Layout DK Windows/Linux",
            "FI_WINLIN" to "Layout FI Windows/Linux",

            "CH_DE_WINLIN" to "Layout CH-DE Windows/Linux",
            "CH_FR_WINLIN" to "Layout CH-FR Windows/Linux",

            "TR_WINLIN" to "Layout TR Windows/Linux",
            "TR_MAC" to "Layout TR Mac",
			
			"TV_SAMSUNG" to "Layout for Samsung TV",
			"TV_LG" to "Layout for LG TV",
			"TV_ANDROID" to "Layout for Android TV",
			"TV_ROKU" to "Layout for Roku TV",
			"TV_FIRETV" to "Layout for FireTV"
        )

        val values = pairs.map { it.first }
        val entries = pairs.map { it.second }
        return entries to values
    }
}

package com.blu.blukeyborg.kp2a

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import com.blu.blukeyborg.BleHub
import com.blu.blukeyborg.PreferencesUtil
import keepass2android.pluginsdk.KeepassDefs
import keepass2android.pluginsdk.PluginActionBroadcastReceiver
import keepass2android.pluginsdk.PluginAccessException
import keepass2android.pluginsdk.Strings

import android.os.Handler
import android.os.Looper

import android.util.Log
private const val TAG = "KP2A-BluKeyborg"

class Kp2aActionReceiver : PluginActionBroadcastReceiver() {

    companion object {
        private const val EXTRA_MODE = "mode"
        private const val MODE_SEND_USERNAME = "send_username"
        private const val MODE_SEND_PASSWORD = "send_password"
        private const val MODE_SEND_USER_TAB_PASS_ENTER = "send_user_tab_pass_enter"

		private const val MODE_SEND_FIELD = "send_field"
		private const val ACTION_ID_FIELD_SEND = "BK_SEND_FIELD"
		private const val EXTRA_FIELD_KEY = "field_key"
		
		// optional: lets you turn off auto-disconnect later if you want
		private const val DISCONNECT_AFTER_SEND = true				
	}

	private val main = Handler(Looper.getMainLooper())

	private fun toastMain(ctx: Context, msg: String, duration: Int = Toast.LENGTH_SHORT) {
		main.post {
			Toast.makeText(ctx, msg, duration).show()
		}
	}

    override fun openEntry(oe: OpenEntryAction) {
        // KP2A calls this when an entry is opened; you add actions into KP2A’s UI.
        val ctx = oe.context

		android.util.Log.d(
			TAG,
			"openEntry() called " +
			"hostPkg=${oe.hostPackage} " +
			"entryFields=${oe.entryFields.keys}"
		)

		if (!PreferencesUtil.isKp2aPluginEnabled(ctx)) {
			android.util.Log.d(TAG, "Plugin disabled in settings, skipping")
			return
		}

        val token = try {
            oe.accessTokenForCurrentEntryScope
        } catch (e: PluginAccessException) {
            // Usually means user hasn’t granted plugin access yet
            toastMain(ctx, "Enable BluKeyborg plugin access in KeePass2Android", Toast.LENGTH_SHORT)
            return
        }

		android.util.Log.d(TAG, "Access token OK, adding actions")

        try {
            // Add entry actions into KP2A menu
            oe.addEntryAction("BluKeyborg(Username)", android.R.drawable.ic_menu_send,
                Bundle().apply { putString(EXTRA_MODE, MODE_SEND_USERNAME) },
                token
            )
            oe.addEntryAction("BluKeyborg(Password)", android.R.drawable.ic_menu_send,
                Bundle().apply { putString(EXTRA_MODE, MODE_SEND_PASSWORD) },
                token
            )
            oe.addEntryAction("BluKeyborg(User&Pass)", android.R.drawable.ic_menu_send,
                Bundle().apply { putString(EXTRA_MODE, MODE_SEND_USER_TAB_PASS_ENTER) },
                token
            )
						
			// Add a popup menu item on each field: "BluKeyborg"
			for (fieldKey in oe.entryFields.keys) {
				oe.addEntryFieldAction(
					ACTION_ID_FIELD_SEND,                       // actionId
					Strings.PREFIX_STRING + fieldKey,           // fieldId (important: prefix like InputStick)
					"BluKeyborg",                             // label in field popup
					android.R.drawable.ic_menu_send,            // icon
					Bundle().apply {
						putString(EXTRA_MODE, MODE_SEND_FIELD)
						putString(EXTRA_FIELD_KEY, fieldKey)    // store which field this action targets
					},
					token
				)
			}			
			
        } catch (_: Exception) {
            // Don’t crash receiver if KP2A rejects something
        }
    }

	// Keep field menu updated when a field changes
	override fun entryOutputModified(eom: EntryOutputModifiedAction) {
		val ctx = eom.context

		if (!PreferencesUtil.isKp2aPluginEnabled(ctx)) return

		val token = try {
			eom.accessTokenForCurrentEntryScope
		} catch (_: PluginAccessException) {
			return
		}

		val modifiedFieldId = eom.modifiedFieldId ?: return
		val fieldKey = modifiedFieldId.removePrefix(Strings.PREFIX_STRING)

		try {
			eom.addEntryFieldAction(
				ACTION_ID_FIELD_SEND,
				modifiedFieldId, // keep exactly what KP2A says was modified
				"BluKeyborg",
				android.R.drawable.ic_menu_send,
				Bundle().apply {
					putString(EXTRA_MODE, MODE_SEND_FIELD)
					putString(EXTRA_FIELD_KEY, fieldKey)
				},
				token
			)
		} catch (_: Exception) {
			// ignore, don't crash receiver
		}
	}

	// handle the tap
    override fun actionSelected(a: ActionSelectedAction) {
        val ctx = a.context

        // Your existing settings must point to a dongle
        if (!PreferencesUtil.useExternalKeyboardDevice(ctx) || PreferencesUtil.getOutputDeviceId(ctx).isNullOrBlank()) {
            toastMain(ctx, "BluKeyborg: select/enable output device first", Toast.LENGTH_LONG)
            return
        }

		val fields = a.entryFields
		val mode = a.actionData?.getString(EXTRA_MODE) ?: return

		val payload = when (mode) {
			MODE_SEND_USERNAME -> fields[KeepassDefs.UserNameField].orEmpty()
			MODE_SEND_PASSWORD -> fields[KeepassDefs.PasswordField].orEmpty()
			MODE_SEND_USER_TAB_PASS_ENTER -> buildString {
				append(fields[KeepassDefs.UserNameField].orEmpty())
				append('\t')
				append(fields[KeepassDefs.PasswordField].orEmpty())
				append('\n')
			}
			MODE_SEND_FIELD -> {
				// Field popup action: send the selected field's value
				val fieldKey = a.actionData?.getString(EXTRA_FIELD_KEY)
					?: a.fieldId?.removePrefix(Strings.PREFIX_STRING)
					?: return
				fields[fieldKey].orEmpty()
			}
			else -> return
		}

        if (payload.isEmpty()) {
            toastMain(ctx, "BluKeyborg: nothing to send", Toast.LENGTH_SHORT)
            return
        }

        // Use existing BLE send primitive (D0/D1)
		BleHub.connectSelectedDevice { ok, err ->
			if (!ok) {
				toastMain(ctx, "BluKeyborg: connect failed (${err ?: "?"})", Toast.LENGTH_SHORT)
				if (DISCONNECT_AFTER_SEND) disconnectSafe()
				return@connectSelectedDevice
			}

			BleHub.sendStringAwaitHash(payload) { ok2, err2 ->
				toastMain(
					ctx,
					if (ok2) "Sent" else "Send failed (${err2 ?: "?"})",
					Toast.LENGTH_SHORT
				)

				if (DISCONNECT_AFTER_SEND) disconnectSafe()
			}
		}
    }
	
	private fun disconnectSafe() {
		try {
			Log.d(TAG, "Disconnecting BleHub after KP2A action")
			BleHub.suppressAutoConnectFor(4000L)
			BleHub.disconnectFromPlugin()
		} catch (t: Throwable) {
			Log.w(TAG, "BleHub.disconnect() threw", t)
		}
	}
	
}

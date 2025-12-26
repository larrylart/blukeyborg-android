package com.blu.blukeyborg

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class ShareSendPopupActivity : AppCompatActivity() {

    private val ui = Handler(Looper.getMainLooper())

    private var connectedByShare: Boolean = false
    private var finished: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Make it behave like a popup and keep screen on briefly
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContentView(R.layout.activity_share_send_popup)

        // Ensure popup is not tiny on some OEMs (enforce ~70% width)
        //val dm = resources.displayMetrics
        //val w = (dm.widthPixels * 0.70f).toInt()
        //window.setLayout(w, WindowManager.LayoutParams.WRAP_CONTENT)

		window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
		window.decorView.setPadding(0, 0, 0, 0)
		
        val statusTv = findViewById<TextView>(R.id.tvStatus)
        //val previewTv = findViewById<TextView>(R.id.tvPreview)

        // Respect your toggle
        if (!PreferencesUtil.allowShareInput(this)) {
            Toast.makeText(this, "Share input disabled in settings", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Also require output device toggle ON (since this feature types to dongle)
        if (!PreferencesUtil.useExternalKeyboardDevice(this)) {
            Toast.makeText(this, "Output device is disabled", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val shared = extractSharedText(intent)?.trim().orEmpty()
        if (shared.isEmpty()) {
            finish()
            return
        }

        // Hard limit (adjust as you want)
        val limited = shared.take(512)

        //previewTv.text = buildPreview(limited)
        statusTv.text = "Sending…"

        // Ensure a device exists
        if (!BleHub.hasSelectedDevice()) {
            statusTv.text = "No device selected"
            autoCloseSoon()
            return
        }

        // If already MTLS-connected, just send. Otherwise connect first.
        val alreadySecure = (BleHub.connected.value == true)

        if (alreadySecure) {
            connectedByShare = false
            doSend(statusTv, limited)
        } else {
            connectedByShare = true
            statusTv.text = "Connecting…"
            BleHub.connectSelectedDevice { ok, err ->
                runOnUiThread {
                    if (!ok) {
                        statusTv.text = "Failed: ${err ?: "connect"}"
                        autoCloseSoon()
                        return@runOnUiThread
                    }
                    doSend(statusTv, limited)
                }
            }
        }
    }

    override fun finish() {
        if (finished) return
        finished = true
        super.finish()
    }

    private fun doSend(statusTv: TextView, text: String) {
        statusTv.text = "Sending…"

        BleHub.sendStringAwaitHash(text, timeoutMs = 8000L) { ok, err ->
            runOnUiThread {
                statusTv.text = if (ok) "Sent ✓" else "Failed: ${err ?: "send"}"

                // Only drop the link if THIS popup was the one that connected
                if (connectedByShare) {
                    // prevent autoConnectFromPrefs() from reconnecting immediately
                    BleHub.disconnect(suppressMs = 4000L)
                }

                autoCloseSoon()
            }
        }

    }

    private fun autoCloseSoon() {
        ui.postDelayed({ finish() }, 700)
    }

    private fun buildPreview(s: String): String {
        // show small preview in popup
        val oneLine = s.replace("\n", "⏎ ")
        return if (oneLine.length <= 60) oneLine else oneLine.take(60) + "…"
    }

    private fun extractSharedText(intent: Intent?): String? {
        if (intent == null) return null
        if (intent.action != Intent.ACTION_SEND) return null

        intent.getStringExtra(Intent.EXTRA_TEXT)?.let { return it }

        val cd = intent.clipData
        if (cd != null && cd.itemCount > 0) {
            cd.getItemAt(0)?.text?.toString()?.let { return it }
        }
        return null
    }
}

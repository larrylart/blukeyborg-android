package com.blu.blukeyborg.ui

import android.app.Dialog
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import android.widget.ImageButton
import com.blu.blukeyborg.BleHub
import com.blu.blukeyborg.FullKeyboardActivity
import com.blu.blukeyborg.ui.SpecialKeysDialog
import com.blu.blukeyborg.R
import kotlin.math.max

class TypeFragment : Fragment(R.layout.fragment_type) {

    private lateinit var historyContainer: LinearLayout
	private lateinit var historyLabel: TextView
    private lateinit var historyScroll: ScrollView
    private lateinit var inputEdit: EditText
	private lateinit var btnSendInline: ImageButton

    // Tracks whether we enabled fast keys (raw mode) this session
    private var enableFastKeys: Boolean = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Content-only views (fragment_type.xml)
        historyContainer = view.findViewById(R.id.historyContainer)
		historyLabel = view.findViewById(R.id.historyLabel)
        historyScroll = view.findViewById(R.id.historyScroll)
        inputEdit = view.findViewById(R.id.inputEdit)

		btnSendInline = view.findViewById(R.id.btnSendInline)
		btnSendInline.setOnClickListener { sendCurrentText() }


        // Content enablement: only the input belongs to fragment now.
        setContentEnabled(false)

        BleHub.connected.observe(viewLifecycleOwner) { ready ->
            setContentEnabled(ready == true)
        }
    }

    override fun onStart() {
        super.onStart()

		// DO NOT autoconnect here. MainActivity owns autoconnect.
		setContentEnabled(BleHub.connected.value == true)		
    }

    // --------------------------------------------------------------------
    // Shell callbacks (MainActivity calls these)
    // --------------------------------------------------------------------

	fun shellSendClicked() {
		if (BleHub.connected.value != true) {
			context?.let { Toast.makeText(it, "Not connected", Toast.LENGTH_SHORT).show() }
			return
		}
		sendCurrentText()
	}

    fun shellSpecialKeysClicked() {
        onSpecialKeysButtonClicked()
    }

    fun shellFullKeyboardClicked() {
        onFullKeyboardButtonClicked()
    }

    // --------------------------------------------------------------------
    // Content enable/disable
    // --------------------------------------------------------------------

	private fun setContentEnabled(ready: Boolean) {
		val alpha = if (ready) 1.0f else 0.35f

		inputEdit.isEnabled = ready
		inputEdit.alpha = alpha

		btnSendInline.isEnabled = ready
		btnSendInline.alpha = alpha
		
		// History label
		historyLabel.isEnabled = ready
		historyLabel.alpha = alpha	
	}

    // --------------------------------------------------------------------
    // Send current text
    // --------------------------------------------------------------------

    private fun sendCurrentText() {
        if (BleHub.connected.value != true) {
            Toast.makeText(requireContext(), "Dongle not connected", Toast.LENGTH_SHORT).show()
            return
        }

        val value = inputEdit.text?.toString().orEmpty()
        if (value.isEmpty()) {
            Toast.makeText(requireContext(), R.string.msg_no_text_to_send, Toast.LENGTH_SHORT).show()
            return
        }

        BleHub.sendStringAwaitHash(value) { ok, err ->
            requireActivity().runOnUiThread {
                if (ok) {
                    appendHistory(value)
                    inputEdit.text?.clear()
                } else {
                    Toast.makeText(
                        requireContext(),
                        err ?: getString(R.string.msg_send_failed),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun appendHistory(text: String) {
        val tv = TextView(requireContext()).apply {
            this.text = text
            val pad = (16f * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)

            val index = historyContainer.childCount
            val bgColor = if (index % 2 == 0) 0xFFFFFFFF.toInt() else 0xFFF3F3F3.toInt()
            setBackgroundColor(bgColor)

            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        historyContainer.addView(tv)
        historyScroll.post { historyScroll.fullScroll(View.FOCUS_DOWN) }
    }

    // --------------------------------------------------------------------
    // Full keyboard
    // --------------------------------------------------------------------

    private fun onFullKeyboardButtonClicked() {
        if (enableFastKeys) {
            startActivity(Intent(requireContext(), FullKeyboardActivity::class.java))
            return
        }

        BleHub.enableFastKeys { ok, err ->
            requireActivity().runOnUiThread {
                if (ok) {
                    enableFastKeys = true
                    startActivity(Intent(requireContext(), FullKeyboardActivity::class.java))
                } else {
                    Toast.makeText(
                        requireContext(),
                        err ?: getString(R.string.msg_failed_enable_fast_keys),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    // --------------------------------------------------------------------
    // Special keys (exact same key-set as old + X close)
    // --------------------------------------------------------------------

	private fun onSpecialKeysButtonClicked() {
		if (BleHub.connected.value != true) {
			Toast.makeText(requireContext(), "Dongle not connected", Toast.LENGTH_SHORT).show()
			return
		}

		fun showPopup() {
			SpecialKeysDialog().show(parentFragmentManager, "SpecialKeysDialog")
		}

		if (enableFastKeys) {
			showPopup()
			return
		}

		BleHub.enableFastKeys { ok, err ->
			requireActivity().runOnUiThread {
				if (ok) {
					enableFastKeys = true
					showPopup()
				} else {
					Toast.makeText(
						requireContext(),
						err ?: getString(R.string.msg_failed_enable_fast_keys),
						Toast.LENGTH_SHORT
					).show()
				}
			}
		}
	}


}

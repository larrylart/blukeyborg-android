package com.blu.blukeyborg

import android.graphics.PorterDuff
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import android.graphics.Color
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan

class DevicesAdapter(
	private val onSelectClicked: (DeviceUiModel) -> Unit,
    private val onSetupClicked: (DeviceUiModel) -> Unit,
    private val onDeleteClicked: (DeviceUiModel) -> Unit
) : ListAdapter<DeviceUiModel, DevicesAdapter.DeviceViewHolder>(Diff) {

    object Diff : DiffUtil.ItemCallback<DeviceUiModel>() {
        override fun areItemsTheSame(oldItem: DeviceUiModel, newItem: DeviceUiModel): Boolean =
            oldItem.address == newItem.address

        override fun areContentsTheSame(oldItem: DeviceUiModel, newItem: DeviceUiModel): Boolean =
            oldItem == newItem
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_device_row, parent, false)
        return DeviceViewHolder(view)
    }

    override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
		holder.bind(getItem(position), onSelectClicked, onSetupClicked, onDeleteClicked)
    }

    class DeviceViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val statusDot: View = view.findViewById(R.id.deviceStatusDot)
        private val nameView: TextView = view.findViewById(R.id.deviceName)
        private val macView: TextView = view.findViewById(R.id.deviceMac)
        private val statusLine: TextView = view.findViewById(R.id.deviceStatusLine)
        private val detailsLine: TextView = view.findViewById(R.id.deviceDetailsLine)
        private val lastErrorView: TextView = view.findViewById(R.id.deviceLastError)
        private val setupButton: Button = view.findViewById(R.id.deviceSetupButton)
        private val removeButton: Button = view.findViewById(R.id.deviceRemoveButton)

		fun bind(
			model: DeviceUiModel,
			onSelectClicked: (DeviceUiModel) -> Unit,
			onSetupClicked: (DeviceUiModel) -> Unit,
			onDeleteClicked: (DeviceUiModel) -> Unit
		) {
			nameView.text = if (model.isSelected) {
				"${model.name}  (selected)"
			} else {
				model.name
			}
			macView.text = model.address

			val pairingStatus = if (model.bonded) "Paired" else "Not paired"
			val provStatus = if (model.isProvisioned) "Provisioned" else "Not provisioned"
			statusLine.text = "$pairingStatus • $provStatus"

			// Keep existing wiring for layout/fw/proto (may be re-enabled later),
			// but only show RSSI on the card for now.
			val layoutStr = model.keyboardLayout ?: "Unknown"
			val fwStr = model.firmwareVersion ?: "N/A"
			val protoStr = model.protocolVersion ?: "N/A"

			val ctx = itemView.context
			val rssiText = model.rssi?.let { rssi ->
				val quality = when {
					rssi >= -60 -> "Excellent"
					rssi >= -70 -> "Good"
					rssi >= -80 -> "Fair"
					else -> "Poor"
				}
				"RSSI: $rssi dBm ($quality)"
			} ?: "RSSI: N/A"

			// Old full details line (layout/fw/proto/rssi) - maybe will bring some of that back 
			// detailsLine.text = "Layout: $layoutStr • FW: $fwStr • Proto: $protoStr • $rssiText"

			if (model.rssi == null) {
				detailsLine.text = rssiText
				detailsLine.setTextColor(ContextCompat.getColor(ctx, android.R.color.darker_gray))
			} else {
				// Color the entire RSSI line from red->green based on signal quality.
				val rssi = model.rssi
				val clamped = rssi!!.coerceIn(-100, -40)
				val t = (clamped + 100).toFloat() / 60f // -100 => 0, -40 => 1
				val hue = 120f * t // 0=red, 120=green
				val color = Color.HSVToColor(floatArrayOf(hue, 1f, 1f))

				val sp = SpannableString(rssiText)
				sp.setSpan(ForegroundColorSpan(color), 0, sp.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
				detailsLine.setText(sp, TextView.BufferType.SPANNABLE)
			}

			if (!model.lastError.isNullOrBlank()) {
				lastErrorView.visibility = View.VISIBLE
				lastErrorView.text = model.lastError
			} else {
				lastErrorView.visibility = View.GONE
			}

			// Connection indicator color
			val colorRes = when (model.connectionState) {
				ConnectionState.CONNECTED -> android.R.color.holo_green_dark
				ConnectionState.PARTIAL -> android.R.color.holo_orange_dark
				ConnectionState.DISCONNECTED_AVAILABLE -> android.R.color.holo_red_dark
				ConnectionState.OFFLINE -> android.R.color.darker_gray
			}
			val dotColor = ContextCompat.getColor(ctx, colorRes)
			statusDot.background.setColorFilter(dotColor, PorterDuff.Mode.SRC_IN)

			// Only show ONE action:
			// - Not provisioned  => Setup
			// - Provisioned      => Remove
			val showSetup = !model.isProvisioned
			setupButton.visibility = if (showSetup) View.VISIBLE else View.GONE
			removeButton.visibility = if (showSetup) View.GONE else View.VISIBLE

			setupButton.setOnClickListener { onSetupClicked(model) }
			removeButton.setOnClickListener { onDeleteClicked(model) }

			itemView.setOnClickListener { onSelectClicked(model) }
		}

    }
}

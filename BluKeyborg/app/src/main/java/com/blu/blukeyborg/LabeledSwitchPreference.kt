package com.blu.blukeyborg

import android.content.Context
import android.util.AttributeSet
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.google.android.material.materialswitch.MaterialSwitch

class LabeledSwitchPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : Preference(context, attrs) {

    private var switchView: MaterialSwitch? = null
    private var suppressListener = false

    init {
        layoutResource = R.layout.pref_labeled_switch_row
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)

        val titleView = holder.findViewById(R.id.switchTitle) as? TextView
        val sw = holder.findViewById(R.id.rowSwitch) as? MaterialSwitch
        switchView = sw

        // Title comes from android:title in your preference XML
        titleView?.text = title ?: ""

        val checked = getPersistedBoolean(false)

        sw?.apply {
            setOnCheckedChangeListener(null)
            isChecked = checked

            setOnCheckedChangeListener { _, isChecked ->
                if (suppressListener) return@setOnCheckedChangeListener

                if (callChangeListener(isChecked)) {
                    persistBoolean(isChecked)
                } else {
                    // Revert if someone rejected changeListener
                    suppressListener = true
                    this.isChecked = !isChecked
                    suppressListener = false
                }
            }
        }
    }

    /** Optional helper if you ever need to flip it from code */
    fun setCheckedFromCode(value: Boolean) {
        persistBoolean(value)
        suppressListener = true
        switchView?.isChecked = value
        suppressListener = false
    }
}

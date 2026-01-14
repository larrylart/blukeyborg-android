package com.blu.blukeyborg.ui

import android.app.Dialog
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import com.blu.blukeyborg.BleHub
import com.blu.blukeyborg.R
import com.google.android.material.button.MaterialButton

class SpecialKeysDialog : DialogFragment() {

    // Mods bitmask matches dongle side:
    // bit0..3 = LCtrl/LShift/LAlt/LGui, bit4..7 = RCtrl/RShift/RAlt/RGui
    private data class SpecialKey(val label: String, val mods: Int, val usage: Int)

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val ctx = requireContext()
        fun Int.toPx(): Int = (this * resources.displayMetrics.density).toInt()

        fun createKeyButton(key: SpecialKey, large: Boolean = false): MaterialButton {
            return MaterialButton(ctx).apply {
                text = key.label
                isAllCaps = false
                setPadding(12, 12, 12, 12)
                strokeWidth = 2

                if (large) {
                    textSize = 18f
                    minimumWidth = 56.toPx()
                    minimumHeight = 56.toPx()
                } else {
                    textSize = 16f
                    minimumWidth = 40.toPx()
                    minimumHeight = 40.toPx()
                }

                setOnClickListener {
                    BleHub.sendRawKeyTap(key.mods, key.usage) { ok, err ->
                        activity?.runOnUiThread {
                            if (!ok) {
                                Toast.makeText(
                                    ctx,
                                    err ?: ctx.getString(R.string.msg_send_failed),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                }
            }
        }

        // EXACT key list
        val esc = SpecialKey("ESC", 0x00, 0x29)
        val tab = SpecialKey("TAB", 0x00, 0x2B)

        val f1 = SpecialKey("F1", 0x00, 0x3A)
        val f2 = SpecialKey("F2", 0x00, 0x3B)
        val f3 = SpecialKey("F3", 0x00, 0x3C)
        val f4 = SpecialKey("F4", 0x00, 0x3D)
        val f5 = SpecialKey("F5", 0x00, 0x3E)
        val f6 = SpecialKey("F6", 0x00, 0x3F)
        val f7 = SpecialKey("F7", 0x00, 0x40)
        val f8 = SpecialKey("F8", 0x00, 0x41)
        val f9 = SpecialKey("F9", 0x00, 0x42)
        val f10 = SpecialKey("F10", 0x00, 0x43)
        val f11 = SpecialKey("F11", 0x00, 0x44)
        val f12 = SpecialKey("F12", 0x00, 0x45)

        val ins = SpecialKey("Ins", 0x00, 0x49)
        val home = SpecialKey("Home", 0x00, 0x4A)
        val pgUp = SpecialKey("PgUp", 0x00, 0x4B)
        val del = SpecialKey("Del", 0x00, 0x4C)
        val end = SpecialKey("End", 0x00, 0x4D)
        val pgDn = SpecialKey("PgDn", 0x00, 0x4E)

        val left = SpecialKey("←", 0x00, 0x50)
        val down = SpecialKey("↓", 0x00, 0x51)
        val up = SpecialKey("↑", 0x00, 0x52)
        val right = SpecialKey("→", 0x00, 0x4F)

		val enter = SpecialKey("Enter", 0x00, 0x28)
		val backspace = SpecialKey("⌫", 0x00, 0x2A)

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val p = 16.toPx()
            setPadding(p, p, p, p)
        }

        fun addSpacer(heightDp: Int = 8) {
            root.addView(View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    heightDp.toPx()
                )
            })
        }

        fun addRow(keys: List<SpecialKey>, large: Boolean = false) {
            val row = LinearLayout(ctx).apply {
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

        addRow(listOf(esc, tab), large = true)
        addSpacer(12)

        addRow(listOf(f1, f2, f3, f4, f5, f6))
        addRow(listOf(f7, f8, f9, f10, f11, f12))
        addSpacer(12)

        addRow(listOf(ins, home, pgUp))
        addRow(listOf(del, end, pgDn))
        addSpacer(16)

        run {
            val arrows = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
            }

            val rowUp = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_HORIZONTAL
            }

            /* old layout to clean - fun emptySpacer(): View =
                View(ctx).apply { layoutParams = LinearLayout.LayoutParams(0, 0, 1f) }

            rowUp.addView(emptySpacer())
            rowUp.addView(createKeyButton(up, large = true).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    val m = 4.toPx()
                    setMargins(m, m, m, m)
                }
            })
            rowUp.addView(emptySpacer())*/			

			fun addTopKey(key: SpecialKey) {
				rowUp.addView(
					createKeyButton(key, large = true),
					LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
						val m = 4.toPx()
						setMargins(m, m, m, m)
					}
				)
			}

			addTopKey(backspace)
			addTopKey(up)
			addTopKey(enter)

            val rowBottom = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_HORIZONTAL
            }

            fun addArrow(key: SpecialKey) {
                rowBottom.addView(
                    createKeyButton(key, large = true),
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        val m = 4.toPx()
                        setMargins(m, m, m, m)
                    }
                )
            }

            addArrow(left); addArrow(down); addArrow(right)

            arrows.addView(rowUp)
            arrows.addView(rowBottom)
            root.addView(arrows)
        }

        val dialog = Dialog(ctx)
        dialog.setContentView(
            LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL

                val topBar = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.END
                    setPadding(8, 8, 8, 8)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )

                    val closeBtn = ImageButton(ctx).apply {
                        setImageResource(R.drawable.baseline_close_24)
                        background = null
                        val size = 32
                        layoutParams = LinearLayout.LayoutParams(size.toPx(), size.toPx())
                        setOnClickListener { dialog.dismiss() }
                    }
                    addView(closeBtn)
                }

                addView(topBar)
                addView(root)
            }
        )

        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        return dialog
    }
}

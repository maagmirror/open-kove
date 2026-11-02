// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.zanderp.opencfmoto

import android.text.InputType
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/**
 * Dialog to pair a bike by typing the SoftAP SSID + password (TRK / no-QR dashes).
 *
 * [hiddenDefault] pre-ticks "hidden network" for a dash whose AP doesn't show up in a scan;
 * [identity] carries `modelid`/`sn` from an identity-only Carbit QR (see [QrData.parseIdentityOnly])
 * so profile selection still works for dashes that publish no credentials (e.g. Kove).
 */
object ManualWifiPairing {

    fun show(
        activity: AppCompatActivity,
        hiddenDefault: Boolean = false,
        identity: QrData.Companion.Identity? = null,
        onPaired: (raw: String, qr: QrData) -> Unit,
    ) {
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val pad = dp(20)
        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(8), pad, 0)
        }

        fun field(hint: String, password: Boolean = false): EditText {
            val til = TextInputLayout(activity).apply {
                this.hint = hint
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(6) }
            }
            val et = TextInputEditText(til.context).apply {
                inputType = if (password) {
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                } else {
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                }
                isSingleLine = true
            }
            til.addView(et)
            col.addView(til)
            return et
        }

        val ssid = field("Wi‑Fi name (SSID)")
        val pwd = field("Password (blank = open network)", password = true)
        val name = field("Bike name (optional)")
        val hidden = CheckBox(activity).apply {
            text = "Hidden network (not shown in Wi‑Fi scans)"
            isChecked = hiddenDefault
        }
        col.addView(hidden)

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle("Enter bike Wi‑Fi")
            .setMessage(
                "For dashes that show SSID + password instead of a QR (e.g. Benelli TRK), or whose " +
                    "QR carries no credentials. Use the exact network name from the bike screen.",
            )
            .setView(col)
            .setPositiveButton("Add bike", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val pass = pwd.text?.toString().orEmpty()
                val built = QrData.buildManual(
                    ssid = ssid.text?.toString().orEmpty(),
                    pwd = pass,
                    displayName = name.text?.toString(),
                    hidden = hidden.isChecked,
                    modelId = identity?.modelId,
                    sn = identity?.sn,
                )
                if (built == null) {
                    Toast.makeText(activity, "A Wi‑Fi name (SSID) is required", Toast.LENGTH_SHORT)
                        .show()
                    return@setOnClickListener
                }
                if (pass.isNotEmpty() && pass.length < 8) {
                    Toast.makeText(
                        activity,
                        "A WPA2 passphrase is at least 8 characters",
                        Toast.LENGTH_SHORT,
                    ).show()
                    return@setOnClickListener
                }
                LogBus.log(
                    "[pair] manual entry: ssid='${built.second.ssid}' " +
                        "hidden=${built.second.hidden} open=${built.second.isOpen}",
                )
                dialog.dismiss()
                onPaired(built.first, built.second)
            }
        }
        dialog.show()
    }
}

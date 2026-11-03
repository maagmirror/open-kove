// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of Open Kove. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.aa

import android.app.Activity
import androidx.appcompat.app.AlertDialog
import dev.zanderp.opencfmoto.R

/** In-app notice shown before the rider is sent to enable the accessibility service. */
object AccessibilityDisclosure {
    fun show(activity: Activity, onAccept: () -> Unit) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.aa_disclosure_title)
            .setMessage(R.string.aa_disclosure_body)
            .setPositiveButton(R.string.aa_disclosure_accept) { _, _ -> onAccept() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}

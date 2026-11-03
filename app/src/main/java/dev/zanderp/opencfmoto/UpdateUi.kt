// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of Open Kove. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.text.method.LinkMovementMethod
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

/** Update dialog and checks, shared by the main screen and About. */
object UpdateUi {

    fun checkManual(activity: Activity) {
        Toast.makeText(activity, R.string.main_checking_update, Toast.LENGTH_SHORT).show()
        Thread {
            val release = UpdateChecker.check(activity, manual = true)
            activity.runOnUiThread {
                if (activity.isFinishing) return@runOnUiThread
                if (release == null) {
                    Toast.makeText(activity, R.string.main_up_to_date, Toast.LENGTH_SHORT).show()
                } else {
                    show(activity, release)
                }
            }
        }.start()
    }

    /** Once a day, silently. [onFound] runs on the main thread when something newer exists. */
    fun checkAuto(activity: Activity, onFound: (UpdateChecker.Release) -> Unit) {
        Thread {
            val release = UpdateChecker.check(activity, manual = false) ?: return@Thread
            activity.runOnUiThread { if (!activity.isFinishing) onFound(release) }
        }.start()
    }

    fun show(activity: Activity, release: UpdateChecker.Release) {
        val dialog = AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.main_update_title, release.version))
            .setMessage(ReleaseNotes.toSpanned(release.notes))
            .setPositiveButton(R.string.main_update_download) { _, _ ->
                try {
                    activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.downloadUrl)))
                } catch (_: Exception) {
                    Toast.makeText(activity, R.string.main_download_failed, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.main_update_skip) { _, _ -> UpdateChecker.skip(activity, release.version) }
            .setNeutralButton(R.string.main_update_later, null)
            .show()
        dialog.findViewById<TextView>(android.R.id.message)?.movementMethod = LinkMovementMethod.getInstance()
    }
}

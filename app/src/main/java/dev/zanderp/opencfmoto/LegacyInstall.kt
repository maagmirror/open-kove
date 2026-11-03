// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Maag <https://whoismaag.dev>
// Part of Open Kove. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.appcompat.app.AlertDialog

/**
 * Open Kove before 1.1.0 used OpenCfMoto's application id. Either one left installed binds the same
 * link ports (10920-10922), so the bike may connect to it instead.
 */
object LegacyInstall {
    const val PACKAGE = "dev.zanderp.opencfmoto"
    private var warnedThisProcess = false

    fun isInstalled(ctx: Context): Boolean = try {
        ctx.packageManager.getPackageInfo(PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    } catch (_: Exception) {
        false
    }

    fun maybeWarn(activity: android.app.Activity) {
        if (warnedThisProcess || !isInstalled(activity)) return
        warnedThisProcess = true
        AlertDialog.Builder(activity)
            .setTitle(R.string.legacy_install_title)
            .setMessage(R.string.legacy_install_body)
            .setPositiveButton(R.string.legacy_install_uninstall) { _, _ -> uninstall(activity) }
            .setNegativeButton(R.string.legacy_install_later, null)
            .show()
    }

    private fun uninstall(ctx: Context) {
        try {
            ctx.startActivity(
                Intent(Intent.ACTION_DELETE, Uri.fromParts("package", PACKAGE, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (_: Exception) {
            try {
                ctx.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", PACKAGE, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (_: Exception) {
            }
        }
    }
}

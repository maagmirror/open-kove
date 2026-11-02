// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings

/**
 * The official CFMoto / EasyConnect app. It binds the same mirroring-link ports the bike calls back
 * on (10920-10922), so when it's running the bike connects to *it* instead of us and OpenCfMoto's
 * dash stays blank ([EasyConnProber] detects the failed bind and surfaces this).
 *
 * Android has no public API to force-stop another app, so the best we can do is:
 *   1. [closeBestEffort] — ask the OS to kill its *background* process (needs KILL_BACKGROUND_PROCESSES;
 *      unreliable across OEMs and useless if the app is foregrounded), then
 *   2. [openAppInfo] — jump to its App-info screen where the user taps the guaranteed **Force stop**.
 */
object RivalClient {
    private val PACKAGES = listOf("net.easyconn.carman.easyride", "com.cfmoto.cfmotointernational")

    private fun installed(ctx: Context): String? = PACKAGES.firstOrNull { pkg ->
        try {
            ctx.packageManager.getPackageInfo(pkg, 0); true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun isInstalled(ctx: Context): Boolean = installed(ctx) != null

    /** Best-effort kill of the rival's background process. Returns false if we couldn't even try. */
    fun closeBestEffort(ctx: Context): Boolean = try {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        PACKAGES.forEach { am.killBackgroundProcesses(it) }
        true
    } catch (_: Exception) {
        false
    }

    /** Open the rival's system App-info page so the user can hit the guaranteed Force stop button. */
    fun openAppInfo(ctx: Context): Boolean = try {
        val pkg = installed(ctx) ?: return false
        val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
        true
    } catch (_: Exception) {
        false
    }
}

// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

/**
 * Bridge so the phone can drive the search of the dash that's currently projected to the bike
 * HUD (both run in the same app process). Like Google Maps' "type on your phone" for the car:
 * the rider taps Search on the bike screen, types with the phone keyboard, results show on the dash.
 *
 * The active [GpxDashUi] registers a query handler while bound; [MainActivity] registers the
 * keyboard/focus handler so a bike tap can raise the phone IME.
 */
object DashRemote {
    @Volatile private var handler: ((String) -> Unit)? = null
    @Volatile private var typeOnPhone: (() -> Unit)? = null

    /** True when a dash is bound and can receive a search (e.g. projected to the bike). */
    val isAvailable: Boolean get() = handler != null

    fun setHandler(h: ((String) -> Unit)?) {
        handler = h
    }

    fun setTypeOnPhoneHandler(h: (() -> Unit)?) {
        typeOnPhone = h
    }

    /** Send a search query to the active dash. Returns false if no dash is listening. */
    fun submit(query: String): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return false
        val h = handler ?: return false
        h(q)
        return true
    }

    /** Ask the phone UI to focus the destination field and show the keyboard. */
    fun requestTypeOnPhone(): Boolean {
        val h = typeOnPhone ?: return false
        h()
        return true
    }
}

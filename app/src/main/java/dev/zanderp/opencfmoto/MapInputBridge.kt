// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

/**
 * Handlebar / Controls pad → OpenCfMoto Map input. Same keycodes as [AaVideoBridge] /
 * [dev.zanderp.opencfmoto.aa.AaInput] (DPAD, ENTER, BACK, HOME, ASSISTANT) plus rotary
 * [scrollSink] for knob focus steps.
 *
 * Installed by [GpxDashUi] while the map Presentation is projected; [MediaButtonBridge] prefers
 * these sinks over Android Auto when both would otherwise compete.
 */
object MapInputBridge {
    @Volatile var keySink: ((keycode: Int) -> Unit)? = null
    @Volatile var scrollSink: ((delta: Int) -> Unit)? = null

    val isActive: Boolean
        get() = keySink != null || scrollSink != null

    fun clear() {
        keySink = null
        scrollSink = null
    }
}

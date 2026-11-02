// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.content.Context

/**
 * Small on/off app preferences (kept apart from the video-tuning [VideoPrefs]).
 *
 *  - [autoConnect]  — on launch, if a bike is paired and its Wi-Fi is in range, start connecting
 *    automatically instead of waiting for a tap. Default on; the rider can turn it off.
 *  - [autoRecovery] — the watchdog ([AndroidAutoService]) restarts the bike link if projection
 *    stalls or the dash drops, without a manual Stop/Start. Default on.
 *  - [forceNonTouch] — never advertise a touchscreen to Android Auto (focus/knob UI). Use when
 *    handlebar buttons do nothing because a touch profile was selected for a non-touch dash.
 *  - [forceTouch] — always advertise a touchscreen to Android Auto (touch UI). Use on bikes that
 *    default to focus/knob (e.g. 1000 MT‑X) when the rider wants panel touch. Mutually exclusive
 *    with [forceNonTouch].
 */
object AppSettings {
    private const val PREFS = "opencfmoto_bike"
    private const val KEY_AUTO_CONNECT = "auto_connect"
    private const val KEY_AUTO_RECOVERY = "auto_recovery"
    private const val KEY_LOG_TRIPS = "log_trips"
    private const val KEY_FORCE_NON_TOUCH = "force_non_touch"
    private const val KEY_FORCE_TOUCH = "force_touch"
    private const val KEY_INCLUDE_SECRETS = "include_secrets_in_logs"
    private const val KEY_TRANSPORT = "wifi_transport"
    private const val KEY_ANON_TELEMETRY = "anonymous_telemetry"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun autoConnect(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_AUTO_CONNECT, true)
    fun setAutoConnect(ctx: Context, on: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_AUTO_CONNECT, on).apply()

    fun autoRecovery(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_AUTO_RECOVERY, true)
    fun setAutoRecovery(ctx: Context, on: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_AUTO_RECOVERY, on).apply()

    fun logTrips(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_LOG_TRIPS, true)
    fun setLogTrips(ctx: Context, on: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_LOG_TRIPS, on).apply()

    fun forceNonTouch(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_FORCE_NON_TOUCH, false)
    fun setForceNonTouch(ctx: Context, on: Boolean) {
        val e = prefs(ctx).edit().putBoolean(KEY_FORCE_NON_TOUCH, on)
        if (on) e.putBoolean(KEY_FORCE_TOUCH, false)
        e.apply()
        BikeProfileHolder.forceNonTouch = on
        if (on) BikeProfileHolder.forceTouch = false
    }

    fun forceTouch(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_FORCE_TOUCH, false)
    fun setForceTouch(ctx: Context, on: Boolean) {
        val e = prefs(ctx).edit().putBoolean(KEY_FORCE_TOUCH, on)
        if (on) e.putBoolean(KEY_FORCE_NON_TOUCH, false)
        e.apply()
        BikeProfileHolder.forceTouch = on
        if (on) BikeProfileHolder.forceNonTouch = false
    }

    /** When true, Share Logs / LogBus keep Wi‑Fi passwords and serials. Default off. */
    fun includeSecretsInLogs(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_INCLUDE_SECRETS, false)
    fun setIncludeSecretsInLogs(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_INCLUDE_SECRETS, on).apply()
        LogBus.includeSecrets = on
    }

    fun transport(ctx: Context): WifiTransport =
        WifiTransport.byId(prefs(ctx).getString(KEY_TRANSPORT, null))
    fun setTransport(ctx: Context, t: WifiTransport) =
        prefs(ctx).edit().putString(KEY_TRANSPORT, t.id).apply()

    /**
     * Anonymous install ping + crash/error upload (random UUID only). Default **on**;
     * rider can turn off in Setup → Privacy. See PRIVACY.md.
     */
    fun anonymousTelemetry(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_ANON_TELEMETRY, true)
    fun setAnonymousTelemetry(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_ANON_TELEMETRY, on).apply()
        if (!on) AnonymousTelemetry.onDisabled(ctx)
    }

    /** Sync holder flags from prefs (call on process start / before connect). */
    fun applyToHolder(ctx: Context) {
        BikeProfileHolder.forceNonTouch = forceNonTouch(ctx)
        BikeProfileHolder.forceTouch = forceTouch(ctx)
        LogBus.includeSecrets = includeSecretsInLogs(ctx)
        ProfilePrefs.applyToHolder(ctx)
        ButtonMap.ensureDefaultsMigrated(ctx)
        ScreenMargins.load(ctx)
    }
}

/** How to join the bike Wi‑Fi. [AUTO] prefers AP when the QR advertises it, else P2P. */
enum class WifiTransport(val id: String, val label: String) {
    AUTO("auto", "Auto (from QR)"),
    AP("ap", "Force Wi‑Fi AP"),
    P2P("p2p", "Force Wi‑Fi Direct"),

    /**
     * Don't join anything — run the bike link over the network the phone is already on.
     *
     * For dashes that keep no permanent hotspot: they spin up a SoftAP with a **per-session SSID
     * and passphrase** only while their own app is connected (verified on Kove: `Easyconn_AP-2981`
     * → `-6788` → `-3326` across sessions, WPA2/CCMP, locally-administered BSSID). There is no
     * stable credential to store, so the only way in is to let that app bring the AP up and ride
     * the association it created.
     */
    EXISTING("existing", "Use current Wi‑Fi (no join)"),
    ;

    companion object {
        fun byId(id: String?): WifiTransport =
            entries.firstOrNull { it.id == id } ?: AUTO
    }
}

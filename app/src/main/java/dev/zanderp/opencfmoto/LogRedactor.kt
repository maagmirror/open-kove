// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
// Adapted from ionutradu252/open-cflink.
package dev.zanderp.opencfmoto

/**
 * Strips secrets out of log lines when written (so Share Logs / problem reports are safe by default).
 *
 * Masks Wi‑Fi / EasyConn / OTA passwords and shortens vehicle serials. SSID and model id are kept.
 * Bypass via [AppSettings.includeSecretsInLogs] for local debugging only.
 */
object LogRedactor {

    private const val MASK = "«redacted»"

    private val JSON_SECRET = Regex(
        """("(?:password|pwd|passphrase|psk|btPin)"\s*:\s*)"[^"]*"""",
        RegexOption.IGNORE_CASE,
    )
    private val URL_SECRET = Regex(
        """\b(pwd|password|psk|passphrase)=([^&\s"]*)""",
        RegexOption.IGNORE_CASE,
    )
    private val JSON_SERIAL = Regex(
        """("(?:HUID|sn|uuid|carHuid)"\s*:\s*)"([^"]*)"""",
        RegexOption.IGNORE_CASE,
    )
    private val BARE_SERIAL = Regex(
        """\b(carHuid|HUID|sn)=([A-Za-z0-9]{6,})""",
        RegexOption.IGNORE_CASE,
    )

    fun redact(line: String): String {
        var s = line
        s = JSON_SECRET.replace(s) { m -> m.groupValues[1] + "\"$MASK\"" }
        s = URL_SECRET.replace(s) { m -> m.groupValues[1] + "=" + MASK }
        s = JSON_SERIAL.replace(s) { m -> m.groupValues[1] + "\"" + maskTail(m.groupValues[2]) + "\"" }
        s = BARE_SERIAL.replace(s) { m -> m.groupValues[1] + "=" + maskTail(m.groupValues[2]) }
        return s
    }

    private fun maskTail(v: String): String =
        if (v.length <= 4) v else v.take(4) + "…" + "*".repeat((v.length - 4).coerceAtMost(8))
}

package dev.zanderp.opencfmoto

import android.net.Uri
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Mirrors net.easyconn.carman.common.base.QrResult.parseResult — the bike's QR is a URL
 * whose query string carries the connection params:
 *
 *   http://www.carbit.com.cn/...?modelid=...&sn=...&action=9
 *     &ssid=CFMOTO-xxxxxx&pwd=xxxxxx&auth=wpa2-psk
 *     &mac=xx:xx:xx:xx:xx:xx&name=CFMOTO-xxxxxx
 *
 * Moto Morini / MotoFun uses a different delimiter style (hash separators — not a URL fragment):
 *
 *   http://admin.motomorini.com/app.html?Wifi=ML174167#12345678#dc0d30da1b6c
 *     &MachineID=dc0d30da1b6c&ProductID=00297
 *
 * `action` is a bitmask: bit0=basic AP, bit1=AP+internet, bit3=Wi-Fi P2P, bit6=BT.
 */
data class QrData(
    val ssid: String,
    val pwd: String,
    val auth: String?,
    val mac: String?,
    val name: String?,
    val action: Int,
    val modelId: String?,
    val sn: String?,
    val channel: String?,
    /** SoftAP that does not broadcast its SSID — needs WifiNetworkSpecifier.setIsHiddenSsid. */
    val hidden: Boolean = false,
    /**
     * Dash BLE address for bikes that have **no hotspot of their own**: the phone hosts the network
     * and hands the credentials over BLE ([BleNetLink]). Set → the whole Wi‑Fi-join path is skipped.
     */
    val bleMac: String? = null,
) {
    val supportsAp: Boolean get() = (action and 1) != 0 || (action and 2) != 0
    val supportsP2p: Boolean get() = (action and 8) != 0

    /** Open (no passphrase) network — some dashes bring the AP up unencrypted. */
    val isOpen: Boolean
        get() = pwd.isEmpty() ||
            auth?.lowercase()?.let { it == "none" || it == "open" || it == "nopass" } == true

    /** WPA3 / SAE-only AP (rare on dashes, but a WPA2 join is rejected outright on those). */
    val isSae: Boolean get() = auth?.contains("sae", ignoreCase = true) == true

    companion object {
        fun parse(raw: String): QrData? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null
            parseCarbit(trimmed)?.let { return it }
            parseMotoMorini(trimmed)?.let { return it }
            return null
        }

        /**
         * Classic Carbit / EasyConnect `ssid=` + `pwd=` query params.
         *
         * `pwd` is optional: a dash may publish an **open** SoftAP (no passphrase). Only `ssid` is
         * required — without a network name there is nothing to join.
         */
        private fun parseCarbit(raw: String): QrData? {
            val q = queryParams(raw)
            val ssid = q["ssid"] ?: return null
            val pwd = q["pwd"] ?: ""
            return QrData(
                ssid = ssid,
                pwd = pwd,
                auth = q["auth"] ?: if (pwd.isEmpty()) "none" else null,
                mac = q["mac"] ?: q["bm"],
                name = q["name"],
                action = q["action"]?.toIntOrNull() ?: 0,
                modelId = q["modelid"],
                sn = q["sn"],
                channel = q["channel"],
                hidden = q["hidden"] == "1" || q["hidden"].equals("true", ignoreCase = true),
                bleMac = q["ble"],
            )
        }

        /**
         * Pairing entry for a dash that builds the network over BLE: the phone becomes the access
         * point and [bm] (the QR's `bm=`, its LE address) is who we hand the credentials to. There
         * is no SSID to store — the phone's hotspot gets a fresh one every session.
         */
        fun buildBleNet(
            bm: String,
            modelId: String? = null,
            sn: String? = null,
            displayName: String? = null,
        ): Pair<String, QrData>? {
            val mac = bm.trim().takeIf { it.isNotEmpty() } ?: return null
            val name = displayName?.trim()?.takeIf { it.isNotEmpty() } ?: "Bike ($mac)"
            val raw = Uri.Builder()
                .scheme("http")
                .authority("manual.opencfmoto.local")
                .path("/")
                .appendQueryParameter("ssid", "")   // parsed, but unused on this path
                .appendQueryParameter("ble", mac)
                .appendQueryParameter("action", "128")
                .appendQueryParameter("name", name)
                .apply {
                    modelId?.takeIf { it.isNotBlank() }?.let { appendQueryParameter("modelid", it) }
                    sn?.takeIf { it.isNotBlank() }?.let { appendQueryParameter("sn", it) }
                }
                .build()
                .toString()
            val qr = parse(raw) ?: return null
            return raw to qr
        }

        /**
         * Identity-only dash QR: a genuine Carbit pairing code that carries **no credentials**
         * (e.g. Kove: `…/down6/642/643/_ylqx?modelid=21318&sn=JRGk&action=128&bm=DD:0D:…`).
         *
         * Those dashes negotiate the Wi‑Fi credentials outside the QR, so there is nothing to join
         * with — but the code still identifies the head unit, and the SoftAP is discoverable by
         * scanning (`Easyconn_AP-xxxx`). Returned so the UI can log the model and send the rider to
         * the Wi‑Fi scan pairing flow instead of calling the QR invalid.
         */
        data class Identity(
            val modelId: String?,
            val sn: String?,
            val action: Int,
            val bm: String?,
        )

        fun parseIdentityOnly(raw: String): Identity? {
            val t = raw.trim()
            if (!t.startsWith("http", ignoreCase = true)) return null
            val q = queryParams(t)
            if (q.containsKey("ssid")) return null // credential QR — parse() handles it
            val looksCarbit = t.contains("carbit.com.cn", ignoreCase = true) ||
                (q.containsKey("modelid") && q.containsKey("action"))
            if (!looksCarbit) return null
            return Identity(
                modelId = q["modelid"],
                sn = q["sn"],
                action = q["action"]?.toIntOrNull() ?: 0,
                bm = formatMac(q["bm"]) ?: q["bm"],
            )
        }

        /** Query map from a URL (before `#` fragment). Keys are lower-cased. */
        private fun queryParams(raw: String): Map<String, String> {
            val query = raw.substringAfter('?', missingDelimiterValue = "")
                .substringBefore('#')
            if (query.isEmpty()) return emptyMap()
            val out = LinkedHashMap<String, String>()
            for (part in query.split('&')) {
                if (part.isEmpty()) continue
                val eq = part.indexOf('=')
                val key = if (eq >= 0) part.substring(0, eq) else part
                val value = if (eq >= 0) part.substring(eq + 1) else ""
                if (key.isEmpty()) continue
                out[key.lowercase()] = try {
                    URLDecoder.decode(value, StandardCharsets.UTF_8.name())
                } catch (_: Exception) {
                    value
                }
            }
            return out
        }

        /**
         * Moto Morini / MotoFun dash QR.
         *
         * Uses `#` as field separators after `Wifi=`, so [Uri] fragment parsing would eat the
         * password — we scan the raw string instead.
         */
        private fun parseMotoMorini(raw: String): QrData? {
            val wifi = Regex("""(?i)(?:^|[?&])Wifi=([^&#\s]+)""").find(raw) ?: return null
            val ssid = wifi.groupValues[1].trim()
            if (ssid.isEmpty()) return null

            // After Wifi=<ssid> expect #<pwd>#<mac>&MachineID=…&ProductID=…
            val after = raw.substring(wifi.range.last + 1)
            if (!after.startsWith('#')) return null
            val hashParts = after.removePrefix("#").split('#', limit = 2)
            val pwd = hashParts.getOrNull(0)?.substringBefore('&')?.trim().orEmpty()
            if (pwd.isEmpty()) return null

            val macToken = hashParts.getOrNull(1)?.substringBefore('&')?.trim()
                ?.takeIf { it.isNotEmpty() }
            val machineId = Regex("""(?i)MachineID=([^&#\s]+)""").find(raw)?.groupValues?.get(1)
            val productId = Regex("""(?i)ProductID=([^&#\s]+)""").find(raw)?.groupValues?.get(1)

            return QrData(
                ssid = ssid,
                pwd = pwd,
                auth = "wpa2-psk",
                mac = formatMac(macToken) ?: formatMac(machineId),
                name = ssid,
                action = 1, // SoftAP
                modelId = productId,
                sn = machineId,
                channel = null,
            )
        }

        /** `aabbccddeeff` / `aa:bb:…` → colon form; null if not 12 hex digits. */
        private fun formatMac(raw: String?): String? {
            if (raw.isNullOrBlank()) return null
            val hex = raw.filter { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
            if (hex.length != 12) {
                // Already colon-separated or other — keep if it looks like a MAC.
                return raw.takeIf { it.contains(':') && it.length >= 11 }
            }
            return hex.chunked(2).joinToString(":") { it.lowercase() }
        }

        /**
         * Hint for failed scans (vehicle-info QR, app-download QR, etc.) — not a pairing code.
         */
        fun parseFailureHint(raw: String): String? {
            val t = raw.trim()
            when {
                t.contains("motomorini.com", ignoreCase = true) &&
                    !t.contains("Wifi=", ignoreCase = true) ->
                    return "Moto Morini QR has no Wifi= field — open the phone-link / MotoFun QR on the dash"
                Regex("""(?i)^code:.*engine:.*vin:""").containsMatchIn(t) ||
                    t.contains("color:", ignoreCase = true) && t.contains("vin:", ignoreCase = true) ->
                    return "that QR is bike info (color/VIN), not Wi‑Fi pairing — open MotoPlay / EasyConnect / MotoFun QR"
                parseIdentityOnly(t) != null ->
                    return "this dash publishes no Wi‑Fi credentials in its QR — " +
                        "pair by scanning for its hotspot instead (Garage → Scan Wi‑Fi networks)"
                t.startsWith("http", ignoreCase = true) &&
                    !t.contains("ssid=", ignoreCase = true) &&
                    !t.contains("Wifi=", ignoreCase = true) ->
                    return "URL QR without ssid/pwd — need the dash pairing QR (Carbit / EasyConnect / MotoFun)"
                else -> return null
            }
        }

        /**
         * Synthetic pairing URL for bikes that show SSID + password (e.g. Benelli TRK), or whose QR
         * carries no credentials at all (Kove / EasyConn `action=128`) and were paired by picking
         * the hotspot out of a Wi‑Fi scan. [action]=1 → SoftAP path (not Wi‑Fi Direct).
         *
         * Only the SSID is required: [pwd] may be blank for an **open** AP. [hidden] marks an AP
         * that doesn't broadcast its SSID, [auth] lets the caller record `sae` (WPA3) or `none`.
         * Extra identity params from an identity-only QR ([modelId]/[sn]) are carried along so the
         * dash still selects the right [BikeProfile].
         *
         * @return raw URL + parsed [QrData], or null if the SSID is blank.
         */
        fun buildManual(
            ssid: String,
            pwd: String,
            displayName: String? = null,
            hidden: Boolean = false,
            auth: String? = null,
            modelId: String? = null,
            sn: String? = null,
        ): Pair<String, QrData>? {
            val s = ssid.trim()
            val p = pwd // keep internal spaces; a blank password means "open network"
            if (s.isEmpty()) return null
            val name = displayName?.trim()?.takeIf { it.isNotEmpty() } ?: s
            val authValue = auth?.trim()?.takeIf { it.isNotEmpty() }
                ?: if (p.isEmpty()) "none" else "wpa2-psk"
            val raw = Uri.Builder()
                .scheme("http")
                .authority("manual.opencfmoto.local")
                .path("/")
                .appendQueryParameter("ssid", s)
                .appendQueryParameter("pwd", p)
                .appendQueryParameter("auth", authValue)
                .appendQueryParameter("action", "1")
                .appendQueryParameter("name", name)
                .apply {
                    if (hidden) appendQueryParameter("hidden", "1")
                    modelId?.takeIf { it.isNotBlank() }?.let { appendQueryParameter("modelid", it) }
                    sn?.takeIf { it.isNotBlank() }?.let { appendQueryParameter("sn", it) }
                }
                .build()
                .toString()
            val qr = parse(raw) ?: return null
            return raw to qr
        }
    }
}

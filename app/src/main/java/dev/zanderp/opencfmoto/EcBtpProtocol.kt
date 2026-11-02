// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import java.nio.charset.StandardCharsets

/**
 * EasyConn "BTP" — the tiny BLE command protocol some dashes use to build the Wi‑Fi link
 * out-of-band, instead of publishing credentials in their pairing QR.
 *
 * ## Why this exists
 * A dash like the Kove (`modelid=21318`, `action=128`) shows a QR with **no `ssid`/`pwd`**, only its
 * identity and BLE address (`bm=`). It never brings up a hotspot of its own. Instead the *phone*
 * creates one and hands the credentials to the dash over BLE; the dash joins it as a client and
 * reports back the IP it got, and the normal PXC session (port 10930, see `EasyConnProber`) runs
 * over that link with the phone as the access point.
 *
 * That is why the `Easyconn_AP-xxxx` network seen next to such a bike has a **different SSID every
 * session** and never appears in the phone's own scan list: it is the phone's SoftAP.
 *
 * ## Frame
 * ```
 * [0]    0x24 '$'
 * [1]    cmd
 * [2]    len = payload.size + 4          (single byte — payloads must stay < 252 bytes)
 * [3..]  payload
 * [n-2]  checksum = 0x24 ^ cmd ^ len ^ (XOR of every payload byte)
 * [n-1]  0x0A '\n'
 * ```
 * Total size is `payload.size + 5`.
 *
 * Payloads are optionally passed through [Codec], a Base64 variant with a shuffled alphabet — see
 * its docs. Whether a given dash expects that is negotiated at GATT level, so [BleNetLink] sends
 * plain first and falls back.
 */
object EcBtpProtocol {

    // Command bytes (net.easyconn.carman.ble.model.PacketCommand).
    const val CMD_CLIENT_INFO = 48              // 0x30  both ways: PhoneInfo out, old handshake back
    const val CMD_REQUEST_BUILD_NET = 80        // 0x50  phone → dash, empty payload
    const val CMD_NOTIFY_BUILD_NET_FINISH = 81  // 0x51
    const val CMD_NOTIFY_AP_INFO = 82           // 0x52  phone → dash, PhoneApInfo JSON
    const val CMD_NOTIFY_CAR_NET_INFO = 83      // 0x53  dash → phone, {cnt, data:[{name,ip,mask}]}
    const val CMD_EXTEND_CLIENT_INFO = 84       // 0x54  phone → dash, name + local IPv4s
    const val CMD_HANDSHAKE_OUTER = 88          // 0x58  dash → phone, binary handshake ("outer")
    const val CMD_HANDSHAKE_INNER = 89          // 0x59  dash → phone, binary handshake ("inner")
    const val CMD_VERIFY_RESULT = 96            // 0x60  phone → dash, licence verdict

    /** Build-net result codes (net.easyconn.carman.ble.model.ECCommand). */
    const val BUILD_NET_AUTH_FAIL = -2
    const val BUILD_NET_AUTH_PENDING = -1
    const val BUILD_NET_SUCCEED = 0
    const val BUILD_NET_NEED_PHONE_BUILD = 1
    const val BUILD_NET_USE_PHONE_AP = 2

    /** `supportFunction` bits carried by the handshake response. */
    const val FN_BUILD_NET = 2   // dash can be handed credentials over BLE at all
    const val FN_SAVE_AP = 4     // dash wants the phone to reuse one SSID across sessions
    const val FN_SUPPORT_IP = 8  // dash understands the `ip` field of the AP payload

    private const val START: Byte = 0x24
    private const val END: Byte = 0x0A

    /** Largest payload that fits the single-byte length field. */
    const val MAX_PAYLOAD = 251

    data class Frame(val cmd: Int, val payload: ByteArray) {
        val text: String get() = String(payload, StandardCharsets.UTF_8)

        // ByteArray in a data class needs these to compare by content.
        override fun equals(other: Any?): Boolean =
            other is Frame && cmd == other.cmd && payload.contentEquals(other.payload)

        override fun hashCode(): Int = 31 * cmd + payload.contentHashCode()
    }

    fun frame(cmd: Int, payload: ByteArray = ByteArray(0), obfuscate: Boolean = false): ByteArray {
        val body = if (obfuscate) Codec.encode(payload) else payload
        require(body.size <= MAX_PAYLOAD) { "payload too long for the 1-byte length: ${body.size}" }
        val len = body.size + 4
        val out = ByteArray(body.size + 5)
        out[0] = START
        out[1] = cmd.toByte()
        out[2] = len.toByte()
        body.copyInto(out, 3)
        out[out.size - 2] = checksum(cmd, len, body)
        out[out.size - 1] = END
        return out
    }

    private fun checksum(cmd: Int, len: Int, payload: ByteArray): Byte {
        var x = (START.toInt() and 0xFF) xor (cmd and 0xFF) xor (len and 0xFF)
        for (b in payload) x = x xor (b.toInt() and 0xFF)
        return x.toByte()
    }

    /**
     * Pull every complete frame out of [buf], returning them plus the number of bytes consumed —
     * BLE notifications arrive in MTU-sized pieces, so the caller keeps the remainder buffered.
     * Garbage before a `$` is skipped rather than aborting the scan.
     */
    fun parse(buf: ByteArray, length: Int = buf.size): Pair<List<Frame>, Int> {
        val frames = mutableListOf<Frame>()
        var i = 0
        var consumed = 0
        while (i < length) {
            if (buf[i] != START) { i++; consumed = i; continue }
            if (i + 3 > length) break                       // need cmd + len
            val cmd = buf[i + 1].toInt() and 0xFF
            val len = buf[i + 2].toInt() and 0xFF
            val total = len + 1                             // whole frame, see docs
            if (len < 4 || i + total > length) break        // wait for the rest
            val payload = buf.copyOfRange(i + 3, i + 3 + (len - 4))
            val okSum = buf[i + total - 2] == checksum(cmd, len, payload)
            val okEnd = buf[i + total - 1] == END
            if (okSum && okEnd) {
                frames += Frame(cmd, payload)
                i += total
                consumed = i
            } else {
                i++                                        // false '$' inside a payload
                consumed = i
            }
        }
        return frames to consumed
    }

    /**
     * What the dash answers a [CMD_CLIENT_INFO] with. Three shapes exist — a JSON one on the old
     * command byte and two binary ones — and which a dash uses is only discoverable by asking.
     * Fields absent from a given shape stay null/0.
     */
    data class Handshake(
        val head: Int,
        val uuid: String? = null,
        val pwd: String? = null,
        val supportFunction: Int = 0,
        val blesdk: Int = 0,
        val wpn: Int = 0,
        val flavor: String? = null,
        val carBrand: String? = null,
        val carModel: String? = null,
        val carConfig: String? = null,
        val mtu: Int = 0,
    ) {
        val supportsBuildNet: Boolean get() = supportFunction and FN_BUILD_NET == FN_BUILD_NET
        val supportsIp: Boolean get() = supportFunction and FN_SUPPORT_IP == FN_SUPPORT_IP
    }

    /** The JSON handshake, answered on [CMD_CLIENT_INFO] itself. */
    fun parseHandshakeOld(json: String): Handshake {
        val o = runCatching { org.json.JSONObject(json) }.getOrNull()
            ?: return Handshake(CMD_CLIENT_INFO)
        return Handshake(
            head = CMD_CLIENT_INFO,
            uuid = o.optString("uuid").ifBlank { null },
            pwd = o.optString("pwd").ifBlank { null },
            supportFunction = o.optInt("supportFunction"),
            blesdk = o.optInt("blesdk"),
        )
    }

    /** `key(32) uuid(32) pwd(32) flavor(4)` and, on longer frames, car identity + a 16-bit MTU. */
    fun parseHandshakeOuter(p: ByteArray): Handshake {
        if (p.size < 100) return Handshake(CMD_HANDSHAKE_OUTER)
        return Handshake(
            head = CMD_HANDSHAKE_OUTER,
            uuid = str(p, 32, 32),
            pwd = str(p, 64, 32),
            carBrand = if (p.size >= 166) str(p, 100, 16) else null,
            carModel = if (p.size >= 166) str(p, 116, 24) else null,
            carConfig = if (p.size >= 166) str(p, 140, 24) else null,
            // The only big-endian number in the protocol.
            mtu = if (p.size >= 166) ((p[164].toInt() and 0xFF) shl 8) or (p[165].toInt() and 0xFF) else 0,
        )
    }

    /** `channel(4) uuid(32) pwd(32) supportFunction(4) blesdk(1) wpn(1)` + optional car identity. */
    fun parseHandshakeInner(p: ByteArray): Handshake {
        if (p.size < 74) return Handshake(CMD_HANDSHAKE_INNER)
        return Handshake(
            head = CMD_HANDSHAKE_INNER,
            uuid = str(p, 4, 32),
            pwd = str(p, 36, 32),
            supportFunction = le32(p, 68),
            blesdk = p[72].toInt() and 0xFF,
            wpn = p[73].toInt() and 0xFF,
            flavor = if (p.size >= 142) str(p, 74, 4) else null,
            carBrand = if (p.size >= 142) str(p, 78, 16) else null,
            carModel = if (p.size >= 142) str(p, 94, 24) else null,
            carConfig = if (p.size >= 142) str(p, 118, 24) else null,
        )
    }

    private fun str(p: ByteArray, off: Int, len: Int): String? =
        String(p, off, minOf(len, p.size - off), StandardCharsets.UTF_8)
            .trim { it <= ' ' }
            .ifBlank { null }

    private fun le32(p: ByteArray, off: Int): Int =
        (p[off].toInt() and 0xFF) or
            ((p[off + 1].toInt() and 0xFF) shl 8) or
            ((p[off + 2].toInt() and 0xFF) shl 16) or
            ((p[off + 3].toInt() and 0xFF) shl 24)

    /**
     * The SDK's `BleBase64Encoder`: Base64 with a **shuffled alphabet**, `'W'` as the pad character
     * (`'='` is an ordinary symbol at index 6), and the **first byte of every 3-byte group XORed
     * with 0x66**. Obfuscation, not encryption — reimplemented here so frames can be produced and
     * read without the vendor SDK.
     */
    object Codec {
        private val ALPHABET = charArrayOf(
            'm', 'L', 'V', '6', 'J', '0', '=', 'u', 'A', 'p', 'H', '7', 'g', 'D', 'z', 't',
            'w', 'a', 'K', 'l', 'Z', '5', '/', 'U', 'Q', 'X', 'q', 'j', 'f', 'P', 'S', 'h',
            'R', 'o', 'G', 'i', 'B', 'n', 'Y', 'y', '9', 'c', 'b', '2', 'F', 'C', '8', '1',
            'E', '3', 'k', 'd', '4', 'v', 'M', 'e', 'O', '+', 's', 'I', 'T', 'x', 'N', 'r',
            'W', // index 64 — the pad
        )
        private const val XOR = 0x66

        private val REVERSE = IntArray(256) { -1 }.also { rev ->
            for (i in ALPHABET.indices) rev[ALPHABET[i].code] = i
        }

        fun encode(data: ByteArray): ByteArray {
            if (data.isEmpty()) return ByteArray(0)
            val rem = data.size % 3
            val out = CharArray((data.size / 3) * 4 + if (rem == 0) 0 else 4)
            var i = 0
            var o = 0
            while (i <= data.size - 3) {
                val b0 = (data[i].toInt() and 0xFF) xor XOR
                val b1 = data[i + 1].toInt() and 0xFF
                val b2 = data[i + 2].toInt() and 0xFF
                out[o] = ALPHABET[(b0 and 0xFC) shr 2]
                out[o + 1] = ALPHABET[((b0 and 0x03) shl 4) or ((b1 and 0xF0) shr 4)]
                out[o + 2] = ALPHABET[((b1 and 0x0F) shl 2) or ((b2 and 0xC0) shr 6)]
                out[o + 3] = ALPHABET[b2 and 0x3F]
                i += 3
                o += 4
            }
            if (rem == 1) {
                val b0 = (data[i].toInt() and 0xFF) xor XOR
                out[o] = ALPHABET[(b0 and 0xFC) shr 2]
                out[o + 1] = ALPHABET[(b0 and 0x03) shl 4]
                out[o + 2] = ALPHABET[64]
                out[o + 3] = ALPHABET[64]
            } else if (rem == 2) {
                val b0 = (data[i].toInt() and 0xFF) xor XOR
                val b1 = data[i + 1].toInt() and 0xFF
                out[o] = ALPHABET[(b0 and 0xFC) shr 2]
                out[o + 1] = ALPHABET[((b0 and 0x03) shl 4) or ((b1 and 0xF0) shr 4)]
                out[o + 2] = ALPHABET[(b1 and 0x0F) shl 2]
                out[o + 3] = ALPHABET[64]
            }
            return String(out).toByteArray(StandardCharsets.US_ASCII)
        }

        /** Returns null when [data] isn't valid for this alphabet (so callers can treat it as plain). */
        fun decode(data: ByteArray): ByteArray? {
            if (data.isEmpty() || data.size % 4 != 0) return null
            val out = ByteArray(data.size)
            var o = 0
            var i = 0
            while (i < data.size) {
                val v0 = REVERSE[data[i].toInt() and 0xFF]
                val v1 = REVERSE[data[i + 1].toInt() and 0xFF]
                val v2 = REVERSE[data[i + 2].toInt() and 0xFF]
                val v3 = REVERSE[data[i + 3].toInt() and 0xFF]
                if (v0 < 0 || v1 < 0 || v2 < 0 || v3 < 0) return null
                out[o++] = (((v0 shl 2) or (v1 shr 4)) xor XOR).toByte()
                if (v2 != 64) {
                    out[o++] = (((v1 and 0x0F) shl 4) or (v2 shr 2)).toByte()
                    if (v3 != 64) out[o++] = (v3 or ((v2 and 0x03) shl 6)).toByte()
                }
                i += 4
            }
            return out.copyOf(o)
        }
    }
}

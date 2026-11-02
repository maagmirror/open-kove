// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.util.Base64
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathFactory
import org.xml.sax.InputSource

/**
 * Carbit online licence check — a **byte-for-byte** reimplementation of the vendor's
 * `LicenseHttpApi` (`u2.c.b`) / `LicenseManager` (`u2.d`) `onlinecheck` path, which the EasyRide
 * client runs when the dash sends `ECP_C2P_CHECK_SN`.
 *
 * The vendor's exact flow, decompiled from the working APK:
 *  1. Build the query
 *     `cmd=onlinecheck&hardware_id=<channel>.<HUID>&client_set=&sn=<sn>&conn_phone_imei=&conn_phone_model=`
 *     (`client_set`/imei/model are `CustomMade2CRequest.SETTING = ""`; `hardware_id` runs through
 *     `URLEncoder` but has no special chars).
 *  2. RSA/ECB/PKCS1Padding-encrypt the query bytes with Carbit's 2048-bit public key, Base64 NO_WRAP.
 *  3. POST it form-encoded as `q=<enc>` to `https://reg.carbit.com.cn/index.php`.
 *  4. Parse the XML reply `<Doc><state>..</state><data>..</data><setInfo>..</setInfo></Doc>`:
 *     `isOk = state == "1"`, `id = data`, `client_set = setInfo`.
 *
 * The dash never sees this HTTP exchange — only the `CHECK_SN_RESULT` we build from it. So this is
 * strictly for producing the *real* verdict instead of a hardcoded pass. [check] returns null on any
 * failure (no network over the P2P link, timeout, malformed reply); the caller then keeps the
 * previous safe behaviour rather than sending a spurious reject.
 */
object CarbitLicense {

    private const val URL_ONLINECHECK = "https://reg.carbit.com.cn/index.php"

    /** Carbit's licence-server RSA public key, lifted verbatim from `u2.c.a()`. */
    private const val PUBLIC_KEY_B64 =
        "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA0OUZJk+czvufIQwRmPzbUOUaGLAX83j/" +
            "VjVQTCDdPaivUaTjqwaEZu5QwqmEZLwv/YaXqzcfNH+bgYuBSrrvpVGg4tDaaY3hSlHi3ptApNn" +
            "F+FeavGW93qCNNg6DAesE+1PHiGUvd2DpjKuXuR8mYGKn9PaXuNMLSQdKa8+x8fqeeqG2tNNHm0" +
            "d+iTdMXa185IbXXUoCX/P6EKNPue5b+utiuALNZqD3PZKfSb6b4Z7gdlX3wk5XErD9ZtyrkAyll" +
            "3qK7uH8OTVIBcqmYvK+Dez9kcGwFxRNf+QDyWgfL1wUbVKAKQ06EAVtFrFfPEe8j0JML00I7otb" +
            "I8wymxdG4QIDAQAB"

    data class Verdict(val isOk: Boolean, val id: String, val clientSet: String)

    /**
     * Run the vendor's onlinecheck. [channel] is the dash channel (e.g. "21318"), [huid] the dash's
     * HUID from CLIENT_INFO, [sn] the value in the CHECK_SN frame. Returns the parsed verdict, or
     * null if the call could not be completed.
     */
    fun check(channel: String, huid: String, sn: String, log: (String) -> Unit): Verdict? {
        val query = "cmd=onlinecheck" +
            "&hardware_id=" + urlEnc("$channel.$huid") +
            "&client_set=" +
            "&sn=" + sn +
            "&conn_phone_imei=" +
            "&conn_phone_model="
        return try {
            val enc = Base64.encodeToString(rsaEncrypt(query.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
            val body = "q=" + urlEnc(enc)
            log("[license] POST onlinecheck hardware_id=$channel.$huid sn=$sn")
            val resp = post(URL_ONLINECHECK, body)
            val v = parse(resp)
            log("[license] response state=${if (v.isOk) "1" else "≠1"} id='${v.id}' client_set='${v.clientSet}'")
            v
        } catch (e: Exception) {
            log("[license] onlinecheck failed (${e.javaClass.simpleName}: ${e.message}) — caller falls back")
            null
        }
    }

    private fun rsaEncrypt(data: ByteArray): ByteArray {
        val key = KeyFactory.getInstance("RSA")
            .generatePublic(X509EncodedKeySpec(Base64.decode(PUBLIC_KEY_B64, Base64.NO_WRAP)))
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return cipher.doFinal(data)
    }

    private fun urlEnc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun post(urlStr: String, body: String): String {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        return try {
            conn.readTimeout = 10_000
            conn.connectTimeout = 15_000
            conn.requestMethod = "POST"
            conn.doInput = true
            conn.doOutput = true
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }
            conn.connect()
            BufferedReader(InputStreamReader(conn.inputStream)).use { r ->
                buildString {
                    var line = r.readLine()
                    while (line != null) { append(line).append('\n'); line = r.readLine() }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    /** XPath over `/Doc/{state,data,setInfo}`, exactly as `u2.f.d`. */
    private fun parse(xml: String): Verdict {
        val xp = XPathFactory.newInstance().newXPath()
        fun node(path: String): String = try {
            val nl = xp.evaluate(path, InputSource(xml.byteInputStream()), XPathConstants.NODESET)
                as? org.w3c.dom.NodeList
            if (nl != null && nl.length > 0) nl.item(0).textContent ?: "" else ""
        } catch (_: Exception) { "" }
        val state = node("/Doc/state")
        return Verdict(isOk = state == "1", id = node("/Doc/data"), clientSet = node("/Doc/setInfo"))
    }
}

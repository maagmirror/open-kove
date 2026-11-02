package dev.zanderp.opencfmoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QrDataTest {
    @Test
    fun parsesCarbitSsidPwd() {
        val raw =
            "http://www.carbit.com.cn/downsdk/657/658/_sdk?modelid=37426&sn=Vasr&action=1" +
                "&ssid=CFMOTO4288&pwd=e278450c&uid=abc"
        val qr = QrData.parse(raw)!!
        assertEquals("CFMOTO4288", qr.ssid)
        assertEquals("e278450c", qr.pwd)
        assertEquals(1, qr.action)
        assertTrue(qr.supportsAp)
        assertFalse(qr.supportsP2p)
    }

    @Test
    fun parsesMotoMoriniWifiHashFormat() {
        val raw =
            "http://admin.motomorini.com/app.html?Wifi=ML174167#12345678#dc0d30da1b6c" +
                "&MachineID=dc0d30da1b6c&ProductID=00297"
        val qr = QrData.parse(raw)
        assertNotNull(qr)
        assertEquals("ML174167", qr!!.ssid)
        assertEquals("12345678", qr.pwd)
        assertEquals("dc:0d:30:da:1b:6c", qr.mac)
        assertEquals("00297", qr.modelId)
        assertEquals("dc0d30da1b6c", qr.sn)
        assertTrue(qr.supportsAp)
        assertFalse(qr.supportsP2p)
    }

    @Test
    fun rejectsVehicleInfoQr() {
        assertNull(QrData.parse("code:&engine:&vin:&color:Fuji White"))
        val hint = QrData.parseFailureHint("code:&engine:&vin:&color:Fuji White")
        assertNotNull(hint)
        assertTrue(hint!!.contains("bike info", ignoreCase = true))
    }
}

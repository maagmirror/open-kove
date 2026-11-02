// OpenCfMoto glue (technique ported from headunit-revived AGPLv3 AapService.startSelfMode).
// Triggers Google Android Auto's loopback "self-mode": asks gearhead to project to 127.0.0.1:PORT
// with NO VPN. Best launched from a foreground Activity to satisfy Android's background-activity-
// launch restrictions (Android 12+/15).
//
// Path (additive, oldest → newest AA):
//  1) WirelessStartupActivity (worked on older gearhead)
//  2) WirelessStartupReceiver (AA 16.4+ when activity is not exported)
//  3) WifiBluetoothReceiver + START_WIRELESS_PROJECTION (AA 17.4+ / HUR)
// Callers must NOT re-fire this while a session is already live — that kills AAP.
package dev.zanderp.opencfmoto.aa

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import android.os.Parcel
import android.os.Parcelable

object AaSelfMode {
    private const val GEARHEAD_PKG = "com.google.android.projection.gearhead"
    private const val DUMMY_MAC = "00:11:22:33:44:55"

    fun trigger(context: Context, port: Int = AaReceiver.PORT, log: (String) -> Unit) {
        // Additive safety: never poke gearhead again if AAP is already up.
        if (dev.zanderp.opencfmoto.AaVideoBridge.aaSessionLive ||
            dev.zanderp.opencfmoto.AaVideoBridge.aaDecoding
        ) {
            log("[AA] self-mode skip — session already live/decoding")
            return
        }

        val app = context.applicationContext
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val networkToUse: Parcelable? = (cm.activeNetwork as? Parcelable) ?: createFakeNetwork(0)
        val fakeWifiInfo = createFakeWifiInfo()

        // 1) Original path — Activity (works on older Android Auto).
        try {
            val intent = Intent().apply {
                setClassName(
                    GEARHEAD_PKG,
                    "com.google.android.apps.auto.wireless.setup.service.impl.WirelessStartupActivity",
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra("PARAM_HOST_ADDRESS", "127.0.0.1")
                putExtra("PARAM_SERVICE_PORT", port)
                networkToUse?.let { putExtra("PARAM_SERVICE_WIFI_NETWORK", it) }
                fakeWifiInfo?.let { putExtra("wifi_info", it) }
            }
            log("[AA] launching Android Auto WirelessStartupActivity → 127.0.0.1:$port")
            context.startActivity(intent)
            return
        } catch (e: Exception) {
            log("[AA] Activity trigger failed (${e.message}); trying broadcast fallback")
        }

        // 2) Original fallback — WirelessStartupReceiver (AA 16.4+).
        try {
            val receiverIntent = Intent().apply {
                setClassName(
                    GEARHEAD_PKG,
                    "com.google.android.apps.auto.wireless.setup.receiver.WirelessStartupReceiver",
                )
                action = "com.google.android.apps.auto.wireless.setup.receiver.wirelessstartup.START"
                putExtra("ip_address", "127.0.0.1")
                putExtra("projection_port", port)
                networkToUse?.let { putExtra("PARAM_SERVICE_WIFI_NETWORK", it) }
                fakeWifiInfo?.let { putExtra("wifi_info", it) }
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            }
            app.sendBroadcast(receiverIntent)
            log("[AA] broadcast fallback sent")
        } catch (e: Exception) {
            log("[AA] broadcast fallback failed: ${e.message}")
        }

        // 3) Additive — AA 17.4+ / HUR WifiBluetoothReceiver (dummy MAC OK on first trigger).
        try {
            val mac = pickProjectionMac(app, log)
            val btReceiverIntent = Intent("com.google.android.projection.gearhead.START_WIRELESS_PROJECTION").apply {
                setClassName(
                    GEARHEAD_PKG,
                    "com.google.android.apps.auto.wireless.bluetooth.WifiBluetoothReceiver",
                )
                putExtra("DEVICE_ADDRESS", mac)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            }
            app.sendBroadcast(btReceiverIntent)
            log("[AA] broadcast fallback 2 (START_WIRELESS_PROJECTION mac=$mac) sent")
        } catch (e: Exception) {
            log("[AA] broadcast fallback 2 failed: ${e.message} — is Android Auto installed & set up?")
        }
    }

    private fun pickProjectionMac(context: Context, log: (String) -> Unit): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            log("[AA] no BLUETOOTH_CONNECT — using dummy MAC for START_WIRELESS_PROJECTION")
            return DUMMY_MAC
        }
        return try {
            val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val adapter = mgr?.adapter ?: return DUMMY_MAC
            if (!adapter.isEnabled) return DUMMY_MAC

            val a2dp = adapter.getProfileConnectionState(BluetoothProfile.A2DP)
            val hfp = adapter.getProfileConnectionState(BluetoothProfile.HEADSET)
            val audioConnected =
                a2dp == BluetoothProfile.STATE_CONNECTED || hfp == BluetoothProfile.STATE_CONNECTED

            val bonded = try {
                adapter.bondedDevices
            } catch (_: SecurityException) {
                null
            }

            val connectedDev = bonded?.firstOrNull { dev ->
                try {
                    val m = dev.javaClass.getMethod("isConnected")
                    (m.invoke(dev) as? Boolean) == true
                } catch (_: Exception) {
                    false
                }
            }
            val selected = connectedDev
                ?: if (audioConnected) bonded?.firstOrNull() else bonded?.firstOrNull()

            log(
                "[AA] SelfMode BT: bonded=${bonded?.size ?: 0} " +
                    "connectedMac=${connectedDev?.address} selected=${selected?.address}",
            )
            selected?.address ?: DUMMY_MAC
        } catch (e: Exception) {
            log("[AA] BT MAC lookup failed: ${e.message}")
            DUMMY_MAC
        }
    }

    private fun createFakeNetwork(netId: Int): Parcelable? {
        val parcel = Parcel.obtain()
        return try {
            parcel.writeInt(netId)
            parcel.setDataPosition(0)
            val creator = Class.forName("android.net.Network").getField("CREATOR").get(null) as Parcelable.Creator<*>
            creator.createFromParcel(parcel) as Parcelable
        } catch (_: Exception) {
            null
        } finally {
            parcel.recycle()
        }
    }

    private fun createFakeWifiInfo(): Parcelable? {
        return try {
            val wifiInfoClass = Class.forName("android.net.wifi.WifiInfo")
            val wifiInfo = wifiInfoClass.getDeclaredConstructor().apply { isAccessible = true }
                .newInstance() as Parcelable
            try {
                wifiInfoClass.getDeclaredField("mSSID").apply { isAccessible = true }
                    .set(wifiInfo, "\"Headunit-Fake-Wifi\"")
            } catch (_: Exception) {
            }
            wifiInfo
        } catch (_: Exception) {
            null
        }
    }
}

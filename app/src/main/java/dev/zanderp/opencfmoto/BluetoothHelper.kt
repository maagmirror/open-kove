package dev.zanderp.opencfmoto

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings

/**
 * The handlebar media/call buttons on the CFMoto dash don't ride the mirroring (PXC) link — the dash
 * sends them as ordinary Bluetooth AVRCP/HFP commands to whatever phone is paired to the bike. So the
 * app can't "capture" them; the fix is simply making sure the phone is paired to the bike over
 * Bluetooth. This helper reports that pairing/connection status and deep-links to Bluetooth settings
 * so pairing is one tap away.
 */
object BluetoothHelper {

    data class Status(
        val supported: Boolean,
        val enabled: Boolean,
        val connected: Boolean,
        val deviceName: String?,
    ) {
        /** One-line summary for the Setup card. */
        fun describe(context: Context): String = when {
            !supported -> context.getString(R.string.setup_bt_no_bluetooth)
            !enabled -> context.getString(R.string.setup_bt_off)
            connected -> context.getString(
                R.string.setup_bt_connected,
                deviceName ?: context.getString(R.string.setup_bt_audio_device),
            )
            else -> context.getString(R.string.setup_bt_not_connected)
        }
    }

    fun status(context: Context): Status {
        val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter: BluetoothAdapter? = mgr?.adapter
        if (adapter == null) return Status(supported = false, enabled = false, connected = false, deviceName = null)

        if (!hasConnectPermission(context)) {
            // Can't read device names/state without the runtime permission; report enabled-only.
            return Status(supported = true, enabled = adapter.isEnabled, connected = false, deviceName = null)
        }

        val a2dp = adapter.getProfileConnectionState(BluetoothProfile.A2DP)
        val headset = adapter.getProfileConnectionState(BluetoothProfile.HEADSET)
        val connected = a2dp == BluetoothProfile.STATE_CONNECTED || headset == BluetoothProfile.STATE_CONNECTED

        val name = if (connected) {
            try {
                adapter.bondedDevices?.firstOrNull { it.name != null }?.name
            } catch (_: SecurityException) { null }
        } else null

        return Status(supported = true, enabled = adapter.isEnabled, connected = connected, deviceName = name)
    }

    /** True if we hold BLUETOOTH_CONNECT (Android 12+) — needed to read state/names. */
    fun hasConnectPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    /** Open the system Bluetooth settings so the rider can pair/connect the bike. */
    fun openBluetoothSettings(context: Context) {
        val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            context.startActivity(Intent(Settings.ACTION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    }
}

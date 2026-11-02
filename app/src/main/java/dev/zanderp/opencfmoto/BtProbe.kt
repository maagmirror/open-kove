// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Read-only Bluetooth reconnaissance for dashes that gate their Wi‑Fi behind Bluetooth.
 *
 * Some head units keep no permanent SoftAP: the hotspot (with a **per-session SSID and passphrase**)
 * only comes up once the phone talks to the dash over Bluetooth. Their pairing QR reflects that — it
 * carries no `ssid`/`pwd`, just `modelid` + `bm` (the dash's **Bluetooth** MAC), e.g. Kove:
 * `…_ylqx?modelid=21318&sn=…&action=128&bm=DD:0D:30:24:1D:B1`. Confirmed on a real unit: the
 * advertised AP name rotates between sessions (`Easyconn_AP-2981` → `Easyconn_AP-6788`) and its BSSID
 * is locally-administered, i.e. a virtual AP spun up on demand.
 *
 * Implementing that handshake needs to know which transport carries it — BLE/GATT or classic
 * RFCOMM/SPP — which is exactly what this probe answers. It **only enumerates**: bonded devices,
 * SDP service records (`fetchUuidsWithSdp`), classic discovery and a BLE advertisement scan. It
 * connects to nothing and writes nothing. Everything lands in the Logs panel via [LogBus] so one
 * export is enough to decide.
 */
@SuppressLint("MissingPermission")
object BtProbe {

    private const val SCAN_MS = 15_000L

    /** Well-known service UUIDs, so the log reads as protocols instead of hex. */
    private val KNOWN_UUIDS = mapOf(
        "00001101" to "SPP (serial port — classic RFCOMM)",
        "00001103" to "DUN",
        "00001105" to "OBEX push",
        "0000110a" to "A2DP source",
        "0000110b" to "A2DP sink (audio)",
        "0000110c" to "AVRCP target",
        "0000110e" to "AVRCP controller",
        "00001112" to "HFP audio gateway",
        "0000111e" to "HFP hands-free",
        "0000111f" to "HFP audio gateway",
        "00001132" to "MAP (messages)",
        "0000112f" to "PBAP (phonebook)",
        "00001200" to "PnP information",
        "0000180a" to "GATT device information",
        "0000b354" to "Carbit/CFMoto vehicle-control GATT service",
    )

    private fun describeUuid(u: ParcelUuid): String {
        val s = u.uuid.toString().lowercase()
        val short = s.substringBefore('-')
        val known = KNOWN_UUIDS[short]
        return if (known != null) "$s  ← $known" else s
    }

    private fun describeType(device: BluetoothDevice): String = when (device.type) {
        BluetoothDevice.DEVICE_TYPE_CLASSIC -> "CLASSIC (BR/EDR)"
        BluetoothDevice.DEVICE_TYPE_LE -> "LE (BLE only)"
        BluetoothDevice.DEVICE_TYPE_DUAL -> "DUAL (classic + BLE)"
        else -> "UNKNOWN"
    }

    private fun describeBond(device: BluetoothDevice): String = when (device.bondState) {
        BluetoothDevice.BOND_BONDED -> "bonded"
        BluetoothDevice.BOND_BONDING -> "bonding"
        else -> "NOT bonded"
    }

    /**
     * Run the probe. [targetMac] is the QR's `bm=` value when known — the dash is then reported
     * first and queried directly even if it isn't bonded yet.
     */
    fun run(activity: AppCompatActivity, targetMac: String?) {
        if (!ensurePermissions(activity)) return

        val manager = activity.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter: BluetoothAdapter? = manager?.adapter
        if (adapter == null) {
            LogBus.log("[BT] no Bluetooth adapter on this phone")
            Toast.makeText(activity, "No Bluetooth on this phone", Toast.LENGTH_LONG).show()
            return
        }
        if (!adapter.isEnabled) {
            LogBus.log("[BT] Bluetooth is OFF — turn it on and run the probe again")
            Toast.makeText(activity, "Turn Bluetooth on first", Toast.LENGTH_LONG).show()
            return
        }

        val mac = targetMac?.trim()?.uppercase()?.takeIf { BluetoothAdapter.checkBluetoothAddress(it) }
        LogBus.log("[BT] ===== dash Bluetooth probe =====")
        LogBus.log("[BT] target from QR: ${mac ?: "(none — will list everything found)"}")

        // 1. Already-bonded devices: the dash may be paired from an earlier session with the
        //    official app, in which case its SDP record is available immediately.
        val bonded = runCatching { adapter.bondedDevices }.getOrNull().orEmpty()
        LogBus.log("[BT] bonded devices: ${bonded.size}")
        for (d in bonded) {
            LogBus.log("[BT]   ${d.address}  '${d.name}'  ${describeType(d)}  ${describeBond(d)}")
            d.uuids?.forEach { LogBus.log("[BT]       uuid ${describeUuid(it)}") }
        }

        // 2. SDP query against the dash itself — this is what says SPP vs GATT-only.
        if (mac != null) {
            val dash = runCatching { adapter.getRemoteDevice(mac) }.getOrNull()
            if (dash == null) {
                LogBus.log("[BT] '$mac' is not a valid Bluetooth address")
            } else {
                LogBus.log(
                    "[BT] dash $mac: name='${dash.name ?: "?"}' " +
                        "${describeType(dash)} ${describeBond(dash)}",
                )
                dash.uuids?.let { cached ->
                    LogBus.log("[BT] cached SDP records (${cached.size}):")
                    cached.forEach { LogBus.log("[BT]     ${describeUuid(it)}") }
                }
                watchSdp(activity, mac)
                val started = runCatching { dash.fetchUuidsWithSdp() }.getOrDefault(false)
                LogBus.log(
                    if (started) "[BT] SDP query sent — records arrive below"
                    else "[BT] SDP query could not be started (dash out of range or BT busy)",
                )
            }
        }

        // 3. Classic discovery + BLE advertisement scan, so the dash is found even unbonded and we
        //    see which of the two radios it answers on. The BLE scan then chains into the GATT
        //    service dump, which is what names the protocol.
        //
        //    A dash exposes two addresses one apart — BR/EDR and LE (verified on Kove: classic
        //    DC:0D:30:24:1D:B1 vs LE DD:0D:30:24:1D:B1, both named 'KY241DB1'). The QR's `bm` is the
        //    LE one, and the classic side only carries AVRCP/HFP (handlebar buttons + audio), so
        //    the GATT dump has to target the address as given.
        discoverClassic(activity, mac)
        scanLe(activity, adapter, mac)

        Toast.makeText(
            activity,
            "Probing Bluetooth for ${SCAN_MS / 1000}s — watch the Logs panel",
            Toast.LENGTH_LONG,
        ).show()
    }

    /** SDP results arrive asynchronously as ACTION_UUID broadcasts. */
    private fun watchSdp(context: Context, mac: String) {
        val handler = Handler(Looper.getMainLooper())
        var receiver: BroadcastReceiver? = null
        val rx = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (intent?.action != BluetoothDevice.ACTION_UUID) return
                val dev = intentDevice(intent) ?: return
                if (!dev.address.equals(mac, ignoreCase = true)) return
                @Suppress("DEPRECATION")
                val uuids = intent.getParcelableArrayExtra(BluetoothDevice.EXTRA_UUID)
                if (uuids == null || uuids.isEmpty()) {
                    LogBus.log("[BT] SDP: dash exposes NO classic service records → BLE/GATT path")
                    return
                }
                LogBus.log("[BT] SDP records from ${dev.address} (${uuids.size}):")
                for (u in uuids) (u as? ParcelUuid)?.let { LogBus.log("[BT]     ${describeUuid(it)}") }
            }
        }
        receiver = rx
        ContextCompat.registerReceiver(
            context,
            rx,
            IntentFilter(BluetoothDevice.ACTION_UUID),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        handler.postDelayed({ runCatching { context.unregisterReceiver(receiver) } }, SCAN_MS)
    }

    /** Classic inquiry — finds a BR/EDR dash that was never bonded. */
    private fun discoverClassic(context: Context, mac: String?) {
        val handler = Handler(Looper.getMainLooper())
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
            ?: return
        val seen = HashSet<String>()
        val rx = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (intent?.action != BluetoothDevice.ACTION_FOUND) return
                val d = intentDevice(intent) ?: return
                if (!seen.add(d.address)) return
                val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE)
                val star = if (mac != null && d.address.equals(mac, true)) "  ★ THIS IS THE DASH" else ""
                LogBus.log(
                    "[BT] classic found ${d.address} '${d.name ?: "?"}' " +
                        "${describeType(d)} rssi=${rssi}dBm$star",
                )
            }
        }
        ContextCompat.registerReceiver(
            context,
            rx,
            IntentFilter(BluetoothDevice.ACTION_FOUND),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        val started = runCatching { adapter.startDiscovery() }.getOrDefault(false)
        LogBus.log(if (started) "[BT] classic discovery started …" else "[BT] classic discovery refused")
        handler.postDelayed({
            runCatching { adapter.cancelDiscovery() }
            runCatching { context.unregisterReceiver(rx) }
            LogBus.log("[BT] classic discovery finished (${seen.size} device(s))")
        }, SCAN_MS)
    }

    /** BLE advertisement scan — shows the GATT services the dash advertises, if it is LE. */
    private fun scanLe(context: Context, adapter: BluetoothAdapter, mac: String?) {
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            LogBus.log("[BT] BLE scanner unavailable")
            return
        }
        val seen = HashSet<String>()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val addr = result.device?.address ?: return
                if (!seen.add(addr)) return
                val rec = result.scanRecord
                val star = if (mac != null && addr.equals(mac, true)) "  ★ THIS IS THE DASH" else ""
                LogBus.log(
                    "[BT] BLE adv $addr '${rec?.deviceName ?: result.device?.name ?: "?"}' " +
                        "rssi=${result.rssi}dBm$star",
                )
                rec?.serviceUuids?.forEach { LogBus.log("[BT]     adv service ${describeUuid(it)}") }
                rec?.manufacturerSpecificData?.let { msd ->
                    for (i in 0 until msd.size()) {
                        LogBus.log(
                            "[BT]     mfg 0x${msd.keyAt(i).toString(16)} = " +
                                BleProtocol.bytesToHex(msd.valueAt(i)),
                        )
                    }
                }
            }

            override fun onScanFailed(errorCode: Int) {
                LogBus.log("[BT] BLE scan failed: error=$errorCode")
            }
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        // Unfiltered: the dash may advertise under a different address than the QR's `bm`, and we
        // want that in the log rather than a silent miss.
        val filters = emptyList<ScanFilter>()
        runCatching { scanner.startScan(filters, settings, cb) }
            .onSuccess { LogBus.log("[BT] BLE scan started …") }
            .onFailure { LogBus.log("[BT] BLE scan could not start: $it") }
        Handler(Looper.getMainLooper()).postDelayed({
            runCatching { scanner.stopScan(cb) }
            LogBus.log("[BT] BLE scan finished (${seen.size} device(s))")
            // A dash that isn't advertising is still connectable by address, and its GATT table is
            // what actually says which protocol the credential exchange speaks — so always try.
            if (mac != null) gattDump(context, adapter, mac) else finish()
        }, SCAN_MS)
    }

    private fun finish() = LogBus.log("[BT] ===== probe done — export these Logs =====")

    /** Human-readable characteristic property flags. */
    private fun props(p: Int): String = buildList {
        if (p and android.bluetooth.BluetoothGattCharacteristic.PROPERTY_READ != 0) add("READ")
        if (p and android.bluetooth.BluetoothGattCharacteristic.PROPERTY_WRITE != 0) add("WRITE")
        if (p and android.bluetooth.BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) add("WRITE_NR")
        if (p and android.bluetooth.BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) add("NOTIFY")
        if (p and android.bluetooth.BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) add("INDICATE")
        if (p and android.bluetooth.BluetoothGattCharacteristic.PROPERTY_BROADCAST != 0) add("BROADCAST")
    }.joinToString("|").ifEmpty { "none" }

    /**
     * Connect over GATT and enumerate the service table. **Read-only**: it discovers services and
     * logs them, then disconnects. Nothing is written to the dash, no characteristic is subscribed,
     * no handshake is attempted.
     *
     * This is the step that identifies the protocol: a Carbit head unit exposes the vehicle-control
     * service `0000B354-…` with its write/notify pair (see [BleProtocol]), in which case the existing
     * framing applies and only the per-pairing secrets differ.
     */
    private fun gattDump(context: Context, adapter: BluetoothAdapter, mac: String) {
        val device = runCatching { adapter.getRemoteDevice(mac) }.getOrNull()
        if (device == null) {
            LogBus.log("[BT] cannot build a device for $mac")
            finish()
            return
        }
        LogBus.log("[BT] GATT: connecting to $mac (read-only service discovery) …")

        val handler = Handler(Looper.getMainLooper())
        var closed = false
        var gattRef: android.bluetooth.BluetoothGatt? = null
        fun close(reason: String) {
            if (closed) return
            closed = true
            handler.removeCallbacksAndMessages(null)
            runCatching { gattRef?.disconnect() }
            runCatching { gattRef?.close() }
            LogBus.log("[BT] GATT: closed ($reason)")
            finish()
        }

        val cb = object : android.bluetooth.BluetoothGattCallback() {
            override fun onConnectionStateChange(
                gatt: android.bluetooth.BluetoothGatt,
                status: Int,
                newState: Int,
            ) {
                when (newState) {
                    android.bluetooth.BluetoothProfile.STATE_CONNECTED -> {
                        LogBus.log("[BT] GATT: connected (status=$status) — discovering services …")
                        gatt.discoverServices()
                    }
                    android.bluetooth.BluetoothProfile.STATE_DISCONNECTED -> {
                        // 133 is the catch-all Android GATT error: usually "not advertising /
                        // not connectable right now", not a wrong address.
                        LogBus.log(
                            "[BT] GATT: disconnected (status=$status)" +
                                if (status == 133) " — dash not connectable now; wake the dash " +
                                    "or start its own app, then probe again" else "",
                        )
                        close("disconnected")
                    }
                }
            }

            override fun onServicesDiscovered(gatt: android.bluetooth.BluetoothGatt, status: Int) {
                val services = gatt.services.orEmpty()
                LogBus.log("[BT] GATT: ${services.size} service(s), status=$status")
                for (s in services) {
                    LogBus.log("[BT]   service ${describeUuid(ParcelUuid(s.uuid))}")
                    for (c in s.characteristics) {
                        LogBus.log(
                            "[BT]     char ${c.uuid}  [${props(c.properties)}]" +
                                "  descriptors=${c.descriptors.size}",
                        )
                    }
                }
                close("dump complete")
            }
        }
        gattRef = device.connectGatt(context, false, cb, BluetoothDevice.TRANSPORT_LE)
        handler.postDelayed({ close("timeout") }, SCAN_MS)
    }

    private fun intentDevice(intent: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

    private fun ensurePermissions(activity: AppCompatActivity): Boolean {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            for (p in listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)) {
                if (ContextCompat.checkSelfPermission(activity, p) != PackageManager.PERMISSION_GRANTED) {
                    needed += p
                }
            }
        }
        // Classic discovery reports devices only with location permission on older releases.
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (needed.isEmpty()) return true
        ActivityCompat.requestPermissions(activity, needed.toTypedArray(), 4243)
        Toast.makeText(activity, "Allow Bluetooth + location, then tap again", Toast.LENGTH_LONG).show()
        LogBus.log("[BT] requesting permissions: ${needed.joinToString()}")
        return false
    }
}

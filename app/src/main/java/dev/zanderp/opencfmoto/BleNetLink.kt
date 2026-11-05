// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.PowerManager
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetAddress
import java.util.ArrayDeque
import java.util.UUID

/**
 * "Build net" over BLE: hands the phone's hotspot credentials to a dash that has no hotspot of its
 * own, and learns the IP it takes on our network.
 *
 * Sequence (mirrors the vendor SDK's `BleProtocolManager` + `BleNetHelper`):
 * 1. GATT connect to the dash's LE address — the `bm=` field of its pairing QR
 * 2. subscribe to the single write+notify characteristic ([CHAR_UUID]) of [SERVICE_UUID]
 * 3. `CLIENT_INFO` (cmd 48) carrying `{phoneType, phoneID}` — the BLE handshake
 * 4. the dash answers a handshake (cmd 48 JSON, or the binary cmd 88 / cmd 89) and, for the binary
 *    shapes, expects a `VERIFY_RESULT` (cmd 96) licence verdict back before it treats the phone as
 *    an authorised peer
 * 5. `REQUEST_BUILD_NET` (cmd 80, empty)
 * 6. `NOTIFY_AP_INFO` (cmd 82) carrying `{ssid, pwd, auth, mac, ip}` — the phone's SoftAP
 * 7. the dash associates and answers `NOTIFY_CAR_NET_INFO` (cmd 83) with `{cnt, data:[{name, ip,
 *    mask}]}` → [onCarNet] fires with its address, and the caller points [EasyConnProber] at it
 *
 * Skipping steps 3–4 is not fatal to the Wi‑Fi link — the dash still joins and still serves PXC —
 * but it answers every `REQUEST_BUILD_NET` with `status:-1` (`AUTH_PENDING`) and never considers
 * itself connected, which is what leaves its Wi‑Fi indicator dark and the projection blank.
 *
 * The service is the same vendor family as the CFMoto vehicle-control one in [BleProtocol]
 * (`…-d6d8-c7ec-bdf0-eab1bfc6bcbc`) but a different generation: one characteristic doing both
 * directions instead of a write/notify pair.
 *
 * Whether the dash wants payloads obfuscated ([EcBtpProtocol.Codec]) is negotiated in the vendor
 * SDK, so this sends plain first and re-sends obfuscated if nothing answers — cheap, and idempotent
 * on the dash side.
 */
@SuppressLint("MissingPermission")
class BleNetLink(
    private val context: Context,
    private val mac: String,
    private val log: (String) -> Unit,
    private val onCarNet: (car: Inet4Address, name: String?) -> Unit,
    private val onFailed: (String) -> Unit,
    /**
     * Fired once, when the BLE build-net handshake has completed and we send the first
     * `REQUEST_BUILD_NET` — i.e. the moment the vendor brings its Wi‑Fi Direct group up. The host
     * must NOT create the group before this: on 5GHz, creating the P2P group concurrently with the
     * fragile GATT MTU/discovery phase disrupts the BLE link and the handshake stalls after
     * `requestMtu` (the dash never gets its credentials, never joins). The vendor creates the group
     * only after the build-net request; we now do the same.
     */
    private val onApNeeded: () -> Unit = {},
    /**
     * Waiting for a dash that has been gone a while: scan at a lower duty cycle and, if the dash is
     * not seen advertising, fail fast instead of dialling its address blind (a direct GATT connect
     * keeps the radio busy for up to 30s per try). The caller retries later.
     */
    private val lowPower: Boolean = false,
) {

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("0000b360-d6d8-c7ec-bdf0-eab1bfc6bcbc")
        val CHAR_UUID: UUID = UUID.fromString("0000b362-d6d8-c7ec-bdf0-eab1bfc6bcbc")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val MTU = 185
        /** Re-send the AP info if the dash hasn't reported an address yet. */
        private const val RESEND_MS = 6_000L
        /** The vendor sends it once and waits; a few retries only cover a lost write. */
        private const val MAX_AP_INFO_TRIES = 4
        private const val GIVE_UP_MS = 90_000L

        /** What the vendor waits after subscribing before writing CLIENT_INFO. */
        private const val HANDSHAKE_DELAY_MS = 1_000L
        /** Re-send CLIENT_INFO at this point if nothing came back. Must stay below the wait below. */
        private const val HANDSHAKE_RETRY_MS = 3_500L
        /** Ask for the network anyway if the dash never answers the handshake. */
        private const val HANDSHAKE_WAIT_MS = 6_000L
        /** How long to leave the dash to authorise us before re-asking. */
        private const val AUTH_RETRY_MS = 3_000L
        private const val MAX_BUILD_NET_TRIES = 20

        /** A refused or dropped GATT link is retried rather than ending the session. */
        private const val GATT_RETRY_MS = 1_500L
        private const val MAX_GATT_TRIES = 12
        /** How long to look for the dash advertising before dialling its address blind. */
        private const val SCAN_MS = 12_000L

        private const val PREFS = "opencfmoto.blenet"
        private const val KEY_PHONE_ID = "phone_id"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var adapter: BluetoothAdapter? = null
    private var scanCallback: ScanCallback? = null
    private var device: BluetoothDevice? = null
    private var gattTries = 0
    private var gatt: BluetoothGatt? = null
    private var characteristic: BluetoothGattCharacteristic? = null
    private var mtu = 23
    @Volatile private var done = false
    @Volatile private var apInfo: PhoneHotspot.Info? = null
    @Volatile private var hotspotIp: String? = null
    @Volatile private var attempts = 0
    /** Set when the dash answers USE_PHONE_AP — the vendor's gate for handing over credentials. */
    @Volatile private var apRequested = false
    @Volatile private var handshake: EcBtpProtocol.Handshake? = null
    @Volatile private var buildNetTries = 0

    /** Notifications arrive MTU-sized; frames can straddle them. */
    private var rx = ByteArray(0)

    /** One GATT write may be in flight at a time. */
    private val writeQueue = ArrayDeque<ByteArray>()
    @Volatile private var writing = false

    fun start() {
        val adapter: BluetoothAdapter? =
            (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            fail("Bluetooth is off — the dash only builds the Wi‑Fi link over BLE")
            return
        }
        if (!BluetoothAdapter.checkBluetoothAddress(mac.uppercase())) {
            fail("invalid dash BLE address '$mac'")
            return
        }
        this.adapter = adapter
        scanThenConnect()
        handler.postDelayed({
            if (!done) fail("dash never reported an IP within ${GIVE_UP_MS / 1000}s")
        }, GIVE_UP_MS)
    }

    /**
     * Find the dash by scanning, then connect to the [BluetoothDevice] the scan hands back — never
     * to one built from the address string.
     *
     * `BluetoothAdapter.getRemoteDevice(String)` has no way to know the peer's address *type* and
     * always assumes PUBLIC. This dash advertises a **random static** address — `DD:0D:30:24:1D:B1`,
     * and `0xDD` is `0b11011101`, whose top two bits `11` are exactly what marks one. Connecting to
     * it as PUBLIC reaches nobody: the attempt sits there and ends in `status=147`
     * (GATT_CONNECTION_TIMEOUT) about 30s later, which is the failure in the logs. A device that
     * came from a `ScanResult` carries the right type, so the connection lands.
     *
     * It also means we only dial once the dash is genuinely advertising, which is what the vendor
     * client gets from its own scan-then-connect loop.
     */
    private fun scanThenConnect() {
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            log("[blenet] no BLE scanner — connecting by address (may time out)")
            connectByAddress()
            return
        }
        val want = mac.uppercase()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (result.device?.address?.uppercase() != want) return
                stopScan()
                log("[blenet] found dash advertising (rssi=${result.rssi}) — connecting")
                device = result.device
                gattTries = 0
                openGatt()
            }

            override fun onScanFailed(errorCode: Int) {
                log("[blenet] scan failed (code=$errorCode) — connecting by address")
                stopScan()
                connectByAddress()
            }
        }
        scanCallback = cb
        // No filter: a ScanFilter built from an address string carries the same PUBLIC assumption
        // this scan exists to avoid. Matching the address ourselves side-steps it.
        try {
            val settings = ScanSettings.Builder()
                .setScanMode(if (lowPower) ScanSettings.SCAN_MODE_BALANCED else ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            // Android returns nothing from an unfiltered scan while the screen is off, and the locked
            // phone is exactly when a reconnect has to find the dash. The filters only narrow the
            // scan: the callback above still matches on the address.
            val interactive = (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
            scanner.startScan(if (interactive) null else screenOffFilters(), settings, cb)
            log("[blenet] scanning for dash $mac …")
        } catch (e: Exception) {
            log("[blenet] could not start scan ($e) — connecting by address")
            scanCallback = null
            connectByAddress()
            return
        }
        handler.postDelayed({
            if (!done && device == null && lowPower) {
                stopScan()
                fail("dash not seen advertising in ${SCAN_MS / 1000}s (low-power wait)")
            } else if (!done && device == null) {
                log("[blenet] dash not seen advertising in ${SCAN_MS / 1000}s — connecting by address")
                stopScan()
                connectByAddress()
            }
        }, SCAN_MS)
    }

    /** The dash advertises its name as KY + the last three address bytes, and service 0x6967. */
    private fun screenOffFilters(): List<ScanFilter> = listOf(
        ScanFilter.Builder().setDeviceName("KY" + mac.replace(":", "").takeLast(6).uppercase()).build(),
        ScanFilter.Builder()
            .setServiceUuid(ParcelUuid.fromString("00006967-0000-1000-8000-00805f9b34fb"))
            .build(),
    )

    private fun stopScan() {
        val cb = scanCallback ?: return
        scanCallback = null
        try { adapter?.bluetoothLeScanner?.stopScan(cb) } catch (_: Exception) {}
    }

    /** Last resort, and what this class used to do unconditionally. */
    private fun connectByAddress() {
        if (done || device != null) return
        device = try {
            adapter?.getRemoteDevice(mac.uppercase())
        } catch (e: Exception) {
            fail("cannot address the dash: $e"); return
        }
        openGatt()
    }

    private fun openGatt() {
        val dev = device ?: return
        // A GATT handle is a scarce resource — the stack leaks one per connectGatt() that is never
        // closed, and after a few leaked handles every further attempt fails outright.
        try { gatt?.close() } catch (_: Exception) {}
        gatt = null
        characteristic = null
        synchronized(writeQueue) { writeQueue.clear(); writing = false }
        rx = ByteArray(0)
        handshake = null
        log("[blenet] connecting to dash $mac … (attempt ${gattTries + 1})")
        gatt = dev.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    /**
     * A GATT connect that never establishes, or drops before we have the network, is ordinary: the
     * dash advertises in windows, and it refuses a second central while a stale link from a previous
     * session is still open on its side. The vendor client never treats one failure as final — it
     * goes back to scanning and reconnecting — so neither do we.
     */
    private fun retryGatt(why: String) {
        if (done) return
        if (gattTries >= MAX_GATT_TRIES) {
            fail("$why — gave up after $gattTries attempts")
            return
        }
        gattTries++
        log("[blenet] $why — re-scanning in ${GATT_RETRY_MS}ms (attempt $gattTries)")
        // Go back through the scan: a dash that dropped us may have stopped advertising for a
        // while, and re-finding it is also how we keep hold of the right address type.
        device = null
        handler.postDelayed({ if (!done) scanThenConnect() }, GATT_RETRY_MS)
    }

    /** Supply the hotspot credentials; safe to call before or after the GATT link is ready. */
    fun setApInfo(info: PhoneHotspot.Info, localIp: String?) {
        apInfo = info
        hotspotIp = localIp
        // Stored, not sent. The dash asks for the credentials by answering REQUEST_BUILD_NET with
        // USE_PHONE_AP; handing them over before that put a NOTIFY_AP_INFO into the middle of its
        // build-net state machine while it was still reporting AUTH_PENDING. The vendor's order is
        // strict: handshake → sendBuildNetRequest() → status 2 → create the AP → sendHotspot2Car().
        if (apRequested) sendApInfo()
    }

    fun stop() {
        done = true
        handler.removeCallbacksAndMessages(null)
        stopScan()
        try { gatt?.disconnect() } catch (_: Exception) {}
        try { gatt?.close() } catch (_: Exception) {}
        gatt = null
        characteristic = null
    }

    private fun fail(reason: String) {
        if (done) return
        done = true
        log("[blenet] FAILED: $reason")
        stop()
        onFailed(reason)
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    log("[blenet] connected (status=$status) — requesting MTU $MTU")
                    if (!g.requestMtu(MTU)) g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED ->
                    if (!done) {
                        retryGatt(
                            "BLE link dropped (status=$status)" +
                                if (status == 133) " — dash asleep or out of range" else "",
                        )
                    }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            mtu = if (status == BluetoothGatt.GATT_SUCCESS) newMtu else 23
            log("[blenet] MTU=$mtu — discovering services")
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val svc = g.getService(SERVICE_UUID)
            if (svc == null) {
                val seen = g.services.orEmpty().joinToString { it.uuid.toString() }
                fail("dash has no build-net service $SERVICE_UUID (found: $seen)")
                return
            }
            val ch = svc.getCharacteristic(CHAR_UUID)
            if (ch == null) {
                fail("build-net service has no characteristic $CHAR_UUID")
                return
            }
            characteristic = ch
            g.setCharacteristicNotification(ch, true)
            val cccd = ch.getDescriptor(CCCD_UUID)
            if (cccd != null) {
                writeDescriptor(g, cccd)
            } else {
                log("[blenet] no CCCD on the characteristic — proceeding without explicit subscribe")
                onReady()
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            log("[blenet] notifications enabled (status=$status)")
            onReady()
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            c: BluetoothGattCharacteristic,
            status: Int,
        ) {
            writing = false
            if (status != BluetoothGatt.GATT_SUCCESS) log("[blenet] write failed: status=$status")
            pump()
        }

        // API 33+ delivers the value as a parameter; older releases read it off the characteristic.
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            c: BluetoothGattCharacteristic,
            value: ByteArray,
        ) = onNotify(value)

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) onNotify(c.value ?: return)
        }
    }

    @Suppress("DEPRECATION")
    private fun writeDescriptor(g: BluetoothGatt, d: BluetoothGattDescriptor) {
        val enable = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(d, enable)
        } else {
            d.value = enable
            g.writeDescriptor(d)
        }
    }

    private fun onReady() {
        // The vendor does not introduce itself the instant the characteristic is subscribed: its
        // `sendBleHandShake()` posts the CLIENT_INFO write a full second later
        // (`sendMessageDelayed(obtainMessage(1, characteristic), 1000L)`). Matching that gives the
        // dash the same settling time after the CCCD write that it gets from the client it ships with.
        log("[blenet] link ready — introducing ourselves in ${HANDSHAKE_DELAY_MS}ms (BLE handshake)")
        handler.postDelayed({ if (!done) sendClientInfo() }, HANDSHAKE_DELAY_MS)
        // A notification carrying the frame header can get lost; without a handshake no licence
        // verdict goes out. Ask once more before falling back to the unauthenticated request below.
        handler.postDelayed({
            if (!done && handshake == null) {
                log("[blenet] no handshake reply yet — sending CLIENT_INFO again")
                rx = ByteArray(0)
                sendClientInfo()
            }
        }, HANDSHAKE_RETRY_MS)
        // A dash that never answers is no worse off than before this handshake existed, so fall
        // through to the plain request rather than stalling.
        handler.postDelayed({
            if (!done && handshake == null) {
                log("[blenet] no handshake reply in ${HANDSHAKE_WAIT_MS / 1000}s — asking for the network unauthenticated")
                requestBuildNet()
            }
        }, HANDSHAKE_WAIT_MS)
    }

    /**
     * A stable per-install id, which is all the vendor's `phoneID` ever is once it stops being
     * allowed to read the IMEI (Android 10+ returns nothing for it).
     */
    private fun phoneId(): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_PHONE_ID, null)?.let { return it }
        return UUID.randomUUID().toString().also { prefs.edit().putString(KEY_PHONE_ID, it).apply() }
    }

    private fun sendClientInfo() {
        val json = JSONObject().put("phoneType", 0).put("phoneID", phoneId()).toString()
        log("[blenet] → CLIENT_INFO $json")
        enqueue(EcBtpProtocol.frame(EcBtpProtocol.CMD_CLIENT_INFO, json.toByteArray(Charsets.UTF_8)))
    }

    private fun onHandshake(hs: EcBtpProtocol.Handshake) {
        val first = handshake == null
        handshake = hs
        log(
            "[blenet] handshake head=0x%02X supportFunction=0x%02X blesdk=%d wpn=%d brand=%s model=%s cfg=%s mtu=%d"
                .format(hs.head, hs.supportFunction, hs.blesdk, hs.wpn, hs.carBrand, hs.carModel, hs.carConfig, hs.mtu),
        )
        // The binary handshakes end in a licence verdict. The vendor client sends "licensed" both
        // when its licence server agrees and when it cannot be reached, so that is the only verdict
        // its dashes ever see in the field — and the only one we can honestly reproduce offline.
        //
        // CRITICAL: the vendor sends this verdict for BOTH shapes and for EVERY blesdk — its handler
        // (`p.java` d.a/b/c → `I(char, 0, null)`) always fires the VerifyResult(byte 0) after the
        // licence step, and ONLY THEN, for the inner shape with blesdk!=1 && wpn==1, also sends
        // EXTEND_CLIENT_INFO. We used to gate the verdict on `blesdk == 1`, so a Kove-style INNER
        // handshake with blesdk=0 got EXTEND_CLIENT_INFO but NEVER the licence verdict: the dash then
        // treats us as unverified — it still hands over the network (build-net succeeds) but never
        // authorises the projection, so it pulls frames forever and never displays / lights Wi‑Fi.
        // Send the verdict for every binary handshake, verdict first, then the extend info.
        if (hs.head == EcBtpProtocol.CMD_HANDSHAKE_OUTER ||
            hs.head == EcBtpProtocol.CMD_HANDSHAKE_INNER
        ) {
            sendVerifyResult()
        }
        if (hs.head == EcBtpProtocol.CMD_HANDSHAKE_INNER && hs.blesdk != 1 && hs.wpn == 1) {
            sendExtendClientInfo()
        }
        if (!hs.supportsBuildNet) {
            log("[blenet] handshake does not advertise build-net (bit 1 clear) — asking anyway")
        }
        if (!hs.supportsIp) {
            log("[blenet] dash may not read the 'ip' field of the AP payload (bit 3 clear)")
        }
        if (first) requestBuildNet()
    }

    private fun sendVerifyResult() {
        log("[blenet] → VERIFY_RESULT licence=ok")
        // license byte, then an optional message we have nothing to say in.
        enqueue(EcBtpProtocol.frame(EcBtpProtocol.CMD_VERIFY_RESULT, byteArrayOf(0)))
    }

    /**
     * `phoneName(32) count(1)` then, per interface, an IPv4 and a mask. The vendor writes both at
     * overlapping offsets (`33 + i*4` and `33 + i*8`), so on the wire the mask lands on top of the
     * address; reproduced as-is, because what matters is what the dash has been built to parse.
     */
    private fun sendExtendClientInfo() {
        val nets = NetDiag.localIpv4s()
        val name = (BluetoothAdapter.getDefaultAdapter()?.name ?: "").toByteArray(Charsets.UTF_8)
        val out = ByteArray(nets.size * 8 + 33)
        name.copyInto(out, 0, 0, minOf(name.size, 32))
        out[32] = nets.size.toByte()
        nets.forEachIndexed { i, n ->
            ipv4(n.first).copyInto(out, 33 + i * 4)
            ipv4(n.second).copyInto(out, 33 + i * 8)
        }
        log("[blenet] → EXTEND_CLIENT_INFO name='${String(name, Charsets.UTF_8)}' nets=${nets.size}")
        enqueue(EcBtpProtocol.frame(EcBtpProtocol.CMD_EXTEND_CLIENT_INFO, out))
    }

    private fun ipv4(dotted: String): ByteArray {
        val out = ByteArray(4)
        val parts = dotted.split('.')
        if (parts.size != 4) return out
        for (i in 0 until 4) out[i] = (parts[i].toIntOrNull() ?: 0).toByte()
        return out
    }

    @Volatile private var apNeededFired = false

    private fun requestBuildNet() {
        if (done || buildNetTries >= MAX_BUILD_NET_TRIES) return
        buildNetTries++
        // Bring the phone's AP up now, not before — the BLE handshake is done, so creating the P2P
        // group can no longer disrupt the GATT MTU/discovery phase. The vendor sequences it the same
        // way (build-net request → CreateGroup → send credentials).
        if (!apNeededFired) {
            apNeededFired = true
            log("[blenet] handshake done → bringing the phone AP up now (vendor order)")
            onApNeeded()
        }
        log("[blenet] → REQUEST_BUILD_NET (try $buildNetTries)")
        enqueue(EcBtpProtocol.frame(EcBtpProtocol.CMD_REQUEST_BUILD_NET))
    }

    private fun onBuildNetStatus(body: String) {
        // Dump the whole status object so we can see EVERY field the dash sends here — notably
        // `phoneApFrequency` on status=2 (USE_PHONE_AP): the vendor hosts its AP on exactly that
        // frequency (`onBuildNetUsePhoneAp(freq)`). We currently host on a fixed 5GHz band; if the
        // dash asks for a specific frequency we are ignoring it, which can leave the dash joined but
        // unwilling to project. This log reveals whether that field is present on this Kove.
        log("[blenet] build-net status body: $body")
        val status = try {
            JSONObject(body).optInt("status", Int.MIN_VALUE)
        } catch (_: Exception) {
            Int.MIN_VALUE
        }
        when (status) {
            EcBtpProtocol.BUILD_NET_AUTH_FAIL ->
                log("[blenet] dash rejected us (AUTH_FAIL) — it will not hand over its network")
            EcBtpProtocol.BUILD_NET_AUTH_PENDING -> {
                log("[blenet] dash has not authorised us yet (AUTH_PENDING) — re-asking")
                handler.postDelayed({ if (!done) requestBuildNet() }, AUTH_RETRY_MS)
            }
            EcBtpProtocol.BUILD_NET_SUCCEED -> log("[blenet] dash reports the network is up")
            EcBtpProtocol.BUILD_NET_NEED_PHONE_BUILD, EcBtpProtocol.BUILD_NET_USE_PHONE_AP -> {
                // This is the vendor's `onBuildNetUsePhoneAp()` → `l()`: only now does it bring the
                // AP up and only then hand the credentials over.
                log("[blenet] dash wants the phone to host the network (status=$status)")
                apRequested = true
                attempts = 0
                if (apInfo != null) sendApInfo() else log("[blenet] hotspot not up yet — will send as soon as it is")
            }
            else -> log("[blenet] unrecognised build-net status in '$body'")
        }
    }

    private fun sendApInfo() {
        val info = apInfo ?: return
        val obfuscate = attempts % 2 == 1
        attempts++
        // The vendor's PhoneApInfo carries {ssid, pwd, auth, mac, ip} — and it ALWAYS fills mac (the
        // AP BSSID) and ip (the group-owner address). We used to send mac="" and omit ip, which the
        // dash may treat as an unverifiable AP: it joins and pulls frames but never trusts the link
        // enough to show the projection / light the Wi‑Fi icon. Fill both: ip falls back to the fixed
        // Wi‑Fi Direct group-owner address (192.168.49.1), mac to the real p2p0 BSSID when readable.
        val goIp = hotspotIp?.takeIf { it.isNotBlank() } ?: PhoneHotspot.GROUP_OWNER_IP
        val apMac = PhoneHotspot.p2pBssid() ?: ""
        val json = JSONObject()
            .put("ssid", info.ssid)
            .put("pwd", info.passphrase)
            .put("auth", info.auth)
            .put("mac", apMac)
            .put("ip", goIp)
            .toString()
        log(
            "[blenet] sending hotspot to dash (ssid=${info.ssid}, ip=$goIp, mac=${apMac.ifBlank { "?" }}, " +
                "${if (obfuscate) "obfuscated" else "plain"} payload, try $attempts)",
        )
        enqueue(
            EcBtpProtocol.frame(
                EcBtpProtocol.CMD_NOTIFY_AP_INFO,
                json.toByteArray(Charsets.UTF_8),
                obfuscate = obfuscate,
            ),
        )
        // The vendor sends this once per request and then waits (`startWaitApConnectTimer`). A few
        // bounded retries cover a lost write; the old unconditional loop kept firing every 6s for
        // the whole session, long after the dash had stopped asking.
        if (attempts < MAX_AP_INFO_TRIES) {
            handler.postDelayed({ if (!done && apRequested) sendApInfo() }, RESEND_MS)
        }
    }

    private fun enqueue(frame: ByteArray) {
        synchronized(writeQueue) {
            // Split across ATT payloads (MTU minus the 3-byte ATT header).
            val max = (mtu - 3).coerceAtLeast(20)
            var off = 0
            while (off < frame.size) {
                val end = minOf(off + max, frame.size)
                writeQueue.add(frame.copyOfRange(off, end))
                off = end
            }
        }
        pump()
    }

    @Suppress("DEPRECATION")
    private fun pump() {
        val g = gatt ?: return
        val ch = characteristic ?: return
        val chunk = synchronized(writeQueue) {
            if (writing || writeQueue.isEmpty()) return
            writing = true
            writeQueue.poll()
        } ?: return
        val type = if (ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }
        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(ch, chunk, type) == BluetoothGatt.GATT_SUCCESS
        } else {
            ch.writeType = type
            ch.value = chunk
            g.writeCharacteristic(ch)
        }
        if (!ok) {
            writing = false
            log("[blenet] write rejected by the stack")
        }
    }

    private fun onNotify(value: ByteArray) {
        rx = rx + value
        val (frames, consumed) = EcBtpProtocol.parse(rx)
        if (consumed > 0) rx = rx.copyOfRange(consumed, rx.size)
        for (f in frames) handleFrame(f)
    }

    private fun handleFrame(f: EcBtpProtocol.Frame) {
        // Payloads may be obfuscated; prefer whichever decodes to something JSON-shaped.
        val plain = f.text
        val decoded = EcBtpProtocol.Codec.decode(f.payload)?.toString(Charsets.UTF_8)
        val body = when {
            plain.trimStart().startsWith("{") -> plain
            decoded?.trimStart()?.startsWith("{") == true -> decoded
            else -> plain
        }
        // The handshake replies are fixed-width binary, so text would be noise — show bytes.
        val shown = if (f.cmd == EcBtpProtocol.CMD_HANDSHAKE_OUTER ||
            f.cmd == EcBtpProtocol.CMD_HANDSHAKE_INNER
        ) {
            f.payload.take(96).joinToString("") { "%02x".format(it) }
        } else {
            body.take(200)
        }
        log("[blenet] rx cmd=${f.cmd} len=${f.payload.size} body=$shown")

        when (f.cmd) {
            EcBtpProtocol.CMD_NOTIFY_CAR_NET_INFO -> handleCarNet(body)
            EcBtpProtocol.CMD_NOTIFY_BUILD_NET_FINISH ->
                log("[blenet] dash reports build-net finished")
            EcBtpProtocol.CMD_REQUEST_BUILD_NET -> onBuildNetStatus(body)
            EcBtpProtocol.CMD_CLIENT_INFO -> onHandshake(EcBtpProtocol.parseHandshakeOld(body))
            EcBtpProtocol.CMD_HANDSHAKE_OUTER ->
                onHandshake(EcBtpProtocol.parseHandshakeOuter(f.payload))
            EcBtpProtocol.CMD_HANDSHAKE_INNER ->
                onHandshake(EcBtpProtocol.parseHandshakeInner(f.payload))
        }
    }

    private fun handleCarNet(body: String) {
        try {
            val arr = JSONObject(body).optJSONArray("data")
            if (arr == null || arr.length() == 0) {
                log("[blenet] car-net info had no entries")
                return
            }
            val first = arr.getJSONObject(0)
            val addr = first.optString("ip").takeIf { it.isNotBlank() }
            if (addr == null) {
                log("[blenet] car-net entry had no ip")
                return
            }
            val parsed = InetAddress.getByName(addr) as? Inet4Address
            if (parsed == null) {
                log("[blenet] car ip '$addr' is not IPv4")
                return
            }
            val name = first.optString("name").takeIf { it.isNotBlank() }
            done = true
            handler.removeCallbacksAndMessages(null)
            log("[blenet] dash joined our hotspot: ip=$addr name=$name")
            onCarNet(parsed, name)
        } catch (e: Exception) {
            log("[blenet] could not read car-net info: $e")
        }
    }
}

package dev.zanderp.opencfmoto

import org.json.JSONObject
import java.net.Socket
import java.util.UUID

/**
 * Server-side PXC control dispatcher. The phone is the SERVER: after we send the
 * ECP_PXC_MDNS_RESPOND probe to the bike, the bike connects back to our listening
 * ports and drives the handshake. We reply per cmd.
 *
 * Verified against cfmoto-tcp-v5.log (official app, standard Car PXC, JSON CLIENT_INFO):
 *   bike 0x10000 (CAR_CTRL select)  -> we 0x10001
 *   bike 0x20000 (CAR_DATA select)  -> we 0x20001
 *   bike 0x10010 CLIENT_INFO (JSON) -> we 0x10011 (our info + RSA pubkey + signed HUID)
 *   bike 0x10690 {usbSpeed,wifiSpeed} -> we 0x10691
 *   bike 0x103e0 {client_set,sn}    -> we 0x103e1, then 0x201c0 CHECK_SN_RESULT {isOk:true}
 *   bike 0x70000000 heartbeat        -> we 0x70000001
 */
class PxcHandshake(
    private val log: (String) -> Unit,
) {
    private val phoneUuid: String = UUID.randomUUID().toString()
    @Volatile var carHuid: String? = null

    /** The dash channel from CLIENT_INFO (e.g. "21318") — the `hardware_id` prefix for the licence check. */
    @Volatile var carChannel: String? = null
        private set
    @Volatile var lastClientInfo: JSONObject? = null
        private set
    /** Dashboard-variant strategy, selected from the bike's CLIENT_INFO. Read by the media plane too
     *  (via EasyConnProber.handshake.profile). Written once on the control thread, before any media
     *  socket exists. */
    @Volatile var profile: BikeProfile = BikeProfiles.legacy
        private set

    /**
     * Called when the bike selects a PXC channel on a :10922 socket (CAR_CTRL or CAR_DATA).
     * [EasyConnProber] starts a proactive 0x70000000 heartbeat on **each** of those sockets —
     * the 800NK (sdk 0.9.23.x) tears the whole session down after ~7s of silence on either one.
     */
    @Volatile var onPxcChannelSelected: ((Socket, String) -> Unit)? = null

    /** The dash's CAR_CTRL socket, once it has picked one. */
    /**
     * The dash opens **two** PXC sockets to :10922, one per direction (confirmed against the vendor
     * capture, which shows `PXCForCar-C2P run() timeout:0` then `PXCForCar-P2C run() timeout:9000`):
     *  - [carCtrlSocket] = the **C2P** socket (0x10000 CAR_CTRL): dash→phone. CLIENT_INFO and
     *    CHECK_SN arrive here; their replies go back here. No read timeout.
     *  - [carDataSocket] = the **P2C** socket (0x20000 CAR_DATA): phone→dash. Every unsolicited
     *    notification (APPSTATUS, DRIVING_MASK, SWITCH_FRONT, the opening burst) belongs here, and
     *    the dash **reads it with a 9s timeout** — silence for 9s tears the whole session down. We
     *    were sending all of these on carCtrlSocket, so the P2C socket starved and the dash reset at
     *    ~10s. The vendor sends them on P2C at 1Hz.
     */
    @Volatile private var carCtrlSocket: Socket? = null
    @Volatile private var carDataSocket: Socket? = null

    /** The socket for phone→dash (P2C) notifications; falls back to the ctrl socket until CAR_DATA opens. */
    private fun p2cSocket(): Socket? = carDataSocket ?: carCtrlSocket

    /**
     * `mode` in APPSTATUS_FOREGROUND — how the phone says it is projecting.
     *
     * `MediaProjectService.sendCurrentStateToCar()` picks it in `q0(z)`: `a(1)` only when its `U()`
     * predicate holds, otherwise `a(2)` (and `a(3)` for the overlay path). A logcat of the vendor
     * client projecting to this dash sends **`mode: 2`** on every single one of those messages, so 2
     * is what this hardware is actually driven with — 1 was a guess read off the branch, not the wire.
     */
    private val MIRROR_MODE_PROJECTING = 2

    // CORRECTION (2026-08-10): an earlier reading of the vendor capture concluded the Kove only
    // displays under APPSTATUS_BACKGROUND(mode:1) because `recordSupportVirModeByPxc: isSupport=false`.
    // That was wrong. The vendor's own logog.txt shows the dash projects with projectionType
    // **VIRTUAL_MAP** and `onProjectionConnectStatusChange connected: true` at 34.053 — under mode:2
    // (the APPSTATUS at 33.983 was mode:2), after just TWO frames. The first mode:1 is at 41.319,
    // ~7s LATER (the app backgrounding into driving mode). So mode:2 is the display state; mode:1 is
    // post-display. We now stay FOREGROUND mode:2 the whole time (see the heartbeat below).

    /**
     * How often the vendor client repeats APPSTATUS_FOREGROUND: once a second, forever, for as long
     * as it is projecting. In its whole steady-state logcat it is the *only* control command it
     * sends — so the dash treats the repetition as the liveness signal for "a phone is projecting",
     * and a one-shot announcement decays back to "nothing to show".
     */
    private val APPSTATUS_PERIOD_MS = 1_000L

    @Volatile private var appStatusThread: Thread? = null

    /**
     * Kove: after the licence verdict the vendor repeats APPSTATUS_BACKGROUND (0x20030, still mode 2)
     * instead of FOREGROUND. On a freshly powered dash it is the one control command we were not
     * sending, and that dash shows nothing without the vendor's session having run first.
     */
    @Volatile private var appStatusBackground = false
    private val KOVE_BACKGROUND_DELAY_MS = 1_600L

    /**
     * Whether the dash is an instrument cluster in the SDK's terms — `ConnectType.NI`, which is what
     * unlocks the EC-mode command below. Reproduces `ECP_C2P_CLIENT_INFO.h()`: `productTypeExtend[0]`
     * when present, else 3 for a dash on one of two known channels, without a touch screen, or in
     * `mirrorMode == 1`.
     */
    @Volatile var isNiCluster: Boolean = false
        private set

    private fun resolveNiCluster(info: JSONObject): Boolean {
        info.optJSONArray("productTypeExtend")?.takeIf { it.length() > 0 }?.let {
            return it.optInt(0) == 3
        }
        val channel = info.optString("channel")
        return channel == "18303" || channel == "21304" ||
            !info.optBoolean("supportScreenTouch", true) ||
            info.optInt("mirrorMode") == 1
    }

    /**
     * `MotorCustomMadeManager.sendPxcToCarEcMode(state)` — tells a cluster which EC mode to show.
     * The vendor drops it entirely for non-NI dashes, so this stays behind the same gate.
     */
    private fun sendEcMode(tag: String, state: Int) {
        if (!isNiCluster) return
        val sock = p2cSocket() ?: return
        if (sock.isClosed) return
        val body = JSONObject().put("state", state).put("reason", 0).put("msg", "")
        log("[$tag] → VIRTUAL_INST_STATE_CHANGED $body")
        try {
            PxcFrame(
                PxcFrame.CMD_VIRTUAL_INST_STATE_CHANGED,
                body.toString().toByteArray(Charsets.UTF_8),
            ).write(sock.getOutputStream())
        } catch (e: Exception) {
            log("[$tag] VIRTUAL_INST_STATE_CHANGED failed: $e")
        }
    }

    /** Re-announce foreground once frames actually start, like `onMirrorProjectionSuccess`. */
    fun announceMirrorActive(tag: String) {
        if (EasyConnProber.spoofedPackage == EasyConnProber.PKG_EASYRIDE) {
            // A dash that already checked its licence this boot sends no CHECK_SN, so this cannot wait
            // for that exchange. The vendor switches about 1.6s after data start either way.
            Thread {
                try { Thread.sleep(KOVE_BACKGROUND_DELAY_MS) } catch (_: InterruptedException) { return@Thread }
                if (p2cSocket()?.isClosed == false) {
                    appStatusBackground = true
                    log("[$tag] APPSTATUS now BACKGROUND (mode $MIRROR_MODE_PROJECTING), as the vendor")
                }
            }.apply { isDaemon = true; name = "pxc-background"; start() }
        }
        val sock = p2cSocket() ?: return
        if (sock.isClosed) return
        try {
            sendAppStatusForeground(tag, sock.getOutputStream(), MIRROR_MODE_PROJECTING)
            // NO DRIVING_MASK here. In the working Kove session the FIRST sendDrivingMaskStatus is at
            // 42.079 — ~8s AFTER the projection is already connected (34.053). It is a post-display,
            // steady-state message, never part of the connect burst. The heartbeat below starts it only
            // after ~8s to match.
        } catch (e: Exception) {
            log("[$tag] APPSTATUS(mirror active) failed: $e")
        }
        if (sendEcModeOnMirror) sendEcMode(tag, EC_MODE_ON)
        startAppStatusHeartbeat(tag)
    }

    /** `sendPxcToCarEcMode(1)` — the branch that calls `net.easyconn.carman.ec.o.a().c()`. */
    private val EC_MODE_ON = 1

    /**
     * Off: the vendor sends this from a *settings screen*, when the rider changes the virtual
     * instrument mode — not as part of connecting. Sending it unprompted right after `RLY 113` puts
     * an unexpected command on the control channel at exactly the point where the dash goes quiet
     * (its 1Hz `REQ_HEARTBEAT` arrives once and stops), so it stays out until that is understood.
     */
    // The Kove dash answers 0x80000010 (UNSUPPORT) to VIRTUAL_INST_STATE_CHANGED — it does NOT
    // handle the EC-mode command at all, so it is not the display trigger. Keep it off (sending it
    // just floods UNSUPPORT replies).
    @Volatile var sendEcModeOnMirror: Boolean = false

    /** Keep saying "projecting" at [APPSTATUS_PERIOD_MS] until the control socket goes away. */
    @Synchronized
    private fun startAppStatusHeartbeat(tag: String) {
        if (appStatusThread?.isAlive == true) return
        log("[$tag] APPSTATUS heartbeat started (mode=$MIRROR_MODE_PROJECTING every ${APPSTATUS_PERIOD_MS}ms)")
        appStatusThread = Thread {
            var sent = 0
            while (!Thread.currentThread().isInterrupted) {
                try {
                    Thread.sleep(APPSTATUS_PERIOD_MS)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                val sock = p2cSocket() ?: return@Thread
                if (sock.isClosed) {
                    log("[$tag] APPSTATUS heartbeat stopped (P2C socket closed after $sent)")
                    return@Thread
                }
                try {
                    // ALWAYS FOREGROUND mode:2. The vendor's logog.txt proves the dash's projection
                    // `connected: true` fires at 34.053 under mode:2 (the APPSTATUS at 33.983 was
                    // mode:2), and the FIRST mode:1 does not go out until 41.319 — SEVEN SECONDS AFTER
                    // the display is already up. So mode:1/BACKGROUND is a post-display state (the app
                    // backgrounding into driving mode), NOT the signal that triggers display. Sending it
                    // early (our old TRUE_MIRROR theory) tells the dash "app is backgrounded, don't
                    // show" and can keep the projection from ever appearing. Match the vendor: stay
                    // FOREGROUND mode:2 throughout — the display is gated on a decodable stream, nothing
                    // more. (Kept as a single mode so DRIVING_MASK still pairs every beat, which the
                    // continuous-pull sessions needed.)
                    sendAppStatusForeground(
                        tag, sock.getOutputStream(),
                        MIRROR_MODE_PROJECTING,
                        background = appStatusBackground,
                        quiet = sent > 0,
                    )
                    // DRIVING_MASK only after ~8s, matching the vendor: its first sendDrivingMaskStatus
                    // is at 42.079, ~8s after data-start / after the projection is already up. It is a
                    // post-display steady-state message, so keep it OUT of the connect→display window
                    // (APPSTATUS at 1Hz already holds off the dash's 9s read timeout on its own).
                    if (sent >= 8) sendDrivingMaskStatus(tag, sock.getOutputStream(), quiet = sent > 8)
                    sent++
                } catch (e: Exception) {
                    log("[$tag] APPSTATUS heartbeat stopped: $e")
                    return@Thread
                }
            }
        }.apply { isDaemon = true; name = "pxc-appstatus"; start() }
    }

    /** Stop the repeat when the session ends. */
    fun stop() {
        appStatusBackground = false
        appStatusThread?.interrupt()
        appStatusThread = null
        carCtrlSocket = null
        carDataSocket = null
    }

    /**
     * Ask the dash to show the projection page, from anywhere in the session.
     *
     * Goes out on the P2C (CAR_DATA) socket like every other phone→dash notification; a no-op before
     * the dash has opened that channel.
     */
    fun requestSwitchFront(tag: String, why: String) {
        val sock = p2cSocket() ?: return
        if (sock.isClosed) return
        try {
            sendSwitchFront(tag, sock.getOutputStream(), why)
            // Do NOT flip the heartbeat to BACKGROUND(mode:1) here. The vendor's own log shows the
            // display comes up under mode:2 and mode:1 only follows ~7s later, so backgrounding early
            // can block the very display we are trying to trigger. Keep FOREGROUND mode:2.
        } catch (e: Exception) {
            log("[$tag] SWITCH_FRONT failed: $e")
        }
    }

    /** Dispatch one inbound frame on a given socket (ctrl or media). */
    fun handle(tag: String, frame: PxcFrame, socket: Socket) {
        val out = socket.getOutputStream()
        when (frame.cmd) {
            PxcFrame.CMD_CHANNEL_CAR_CTRL -> {
                log("[$tag] bike selected CAR_CTRL (0x10000) → ack 0x10001")
                PxcFrame(PxcFrame.CMD_CHANNEL_CAR_CTRL + 1, ByteArray(0)).write(out)
                // C2P socket: dash→phone. CLIENT_INFO / CHECK_SN land here and their replies go back
                // here. Our unsolicited phone→dash notifications do NOT — those go on CAR_DATA (P2C).
                carCtrlSocket = socket
                onPxcChannelSelected?.invoke(socket, "CAR_CTRL")
            }
            PxcFrame.CMD_CHANNEL_CAR_DATA -> {
                log("[$tag] bike selected CAR_DATA (0x20000) → ack 0x20001")
                PxcFrame(PxcFrame.CMD_CHANNEL_CAR_DATA + 1, ByteArray(0)).write(out)
                // P2C socket: phone→dash, read by the dash with a 9s timeout. Now that it exists,
                // send the opening burst here (the vendor sends it ~10ms after this socket opens, not
                // as part of the CLIENT_INFO reply), and route every later notification here too.
                carDataSocket = socket
                sendOpeningBurst(tag, out)
                onPxcChannelSelected?.invoke(socket, "CAR_DATA")
            }
            PxcFrame.CMD_CLIENT_INFO -> onClientInfo(tag, frame, out)
            PxcFrame.CMD_QUERY_SPEED -> {
                log("[$tag] QUERY_SPEED ${frame.payload.asText()} → reply 0x10691")
                PxcFrame(PxcFrame.CMD_QUERY_SPEED_RLY, ByteArray(0)).write(out)
            }
            PxcFrame.CMD_CHECK_NETWORK -> {
                // Vendor ECP_C2P_CHECK_NETWORK: reply {"status":true} so the dash treats the phone as
                // a working internet uplink (via our SOCKS5 on :10923) and lights its Wi‑Fi indicator.
                val body = JSONObject().put("status", true).toString().toByteArray(Charsets.UTF_8)
                log("[$tag] CHECK_NETWORK from bike → reply {\"status\":true} (net-share available)")
                PxcFrame(PxcFrame.CMD_CHECK_NETWORK_RLY, body).write(out)
            }
            PxcFrame.CMD_CHECK_SN -> onCheckSn(tag, frame, out)
            PxcFrame.CMD_HEARTBEAT -> {
                PxcFrame(PxcFrame.CMD_HEARTBEAT_ACK, ByteArray(0)).write(out)
            }
            PxcFrame.CMD_HEARTBEAT_ACK,
            PxcFrame.CMD_CHECK_SN_RESULT + 1 -> {
                // acks from the bike — nothing to do
            }
            else -> {
                if (!profile.handleUnknownControl(tag, frame, out, log)) {
                    log("[$tag] cmd=0x${frame.cmd.toUInt().toString(16)} (${PxcFrame.nameOf(frame.cmd)}) " +
                        "len=${frame.payload.size} ${frame.payload.asText()}")
                }
            }
        }
    }

    private fun onClientInfo(tag: String, frame: PxcFrame, out: java.io.OutputStream) {
        val text = frame.payload.asText()
        log("[$tag] *** CLIENT_INFO from bike *** $text")
        val json = try { JSONObject(text) } catch (e: Exception) {
            log("[$tag] CLIENT_INFO parse failed: $e"); return
        }
        lastClientInfo = json
        KoveLink.onDashUptime(json.optLong("currentHUTime"))
        isNiCluster = resolveNiCluster(json)
        log("[$tag] connect type: ${if (isNiCluster) "NI (instrument cluster)" else "STANDARD"}")
        carHuid = json.optString("HUID").ifEmpty { json.optString("huid") }.ifEmpty { null }
        carChannel = json.optString("channel").ifEmpty { null }
        log("[$tag] carHuid=$carHuid HUName=${json.optString("HUName")} channel=${json.optString("channel")}")

        profile = BikeProfiles.select(json, log)
        val early = BikeProfileHolder.active
        val startedSpec = BikeProfileHolder.aaVideo  // what Android Auto actually started / negotiated with
        // Keep a measured landscape panel (e.g. 800MT 1280×576) when CLIENT_INFO scoring would
        // flip to the near-square 800NK Advanced profile — that mis-route resized touch/margins
        // mid-session while AA stayed on the landscape stream (connect/drop flaps in field logs).
        val earlyPanel = early.panelSize
        val earlyIsWide = earlyPanel != null && earlyPanel.first >= earlyPanel.second * 3 / 2
        if (earlyIsWide && profile === Cfdl26NkTouchProfile) {
            log("[$tag] keeping landscape QR/measured profile '${early.name}' " +
                "(CLIENT_INFO preferred '${profile.name}' but panel ${earlyPanel!!.first}x${earlyPanel.second} is wide)")
            profile = early
        }
        if (early !== profile) {
            log("[$tag] profile refined from QR guess '${early.name}' → CLIENT_INFO '${profile.name}' " +
                "(AA already started at the QR-guess resolution)")
        }
        BikeProfileHolder.active = profile  // authoritative; QR modelId was only the early hint
        // Android Auto's video surface + decoder buffer were sized when AA started (from the QR guess)
        // and CANNOT be resized mid-session. If the refined profile wants a different resolution and no
        // explicit override is in effect, pin the spec to what AA is really running: otherwise the
        // compositor scales the live source (e.g. 800x480) as if it were the profile's (e.g. 720x1280),
        // producing a wildly wrong draw rect and a picture the dash rejects — which drops the link every
        // few seconds (the connect/drop flap). Known/consistent bikes hit no-op here.
        if (BikeProfileHolder.aaVideoOverride == null && BikeProfileHolder.aaVideo != startedSpec) {
            BikeProfileHolder.aaVideoOverride = startedSpec
            log("[$tag] pinned AA video to ${startedSpec.width}x${startedSpec.height} " +
                "(can't resize mid-session; refined profile wanted ${profile.aaVideo.width}x${profile.aaVideo.height})")
        }
        log("[$tag] *** BikeProfile selected: ${profile.name} ***")

        if (EasyConnProber.emptyClientInfoReply) {
            // What Carbit Ride puts on the wire: the reply command id and nothing else.
            log("[$tag] → CLIENT_INFO reply (empty body, as the vendor client sends it)")
            PxcFrame(PxcFrame.CMD_CLIENT_INFO_RLY, ByteArray(0)).write(out)
        } else {
            val reply = profile.buildClientInfoReply(json, carHuid, phoneUuid)
            log("[$tag] → CLIENT_INFO reply ${reply}")
            PxcFrame(PxcFrame.CMD_CLIENT_INFO_RLY, reply.toString().toByteArray(Charsets.UTF_8)).write(out)
        }
        // The opening burst is NOT sent here: the vendor sends it on the P2C (CAR_DATA) socket, ~10ms
        // after that socket opens — which is a beat after this CLIENT_INFO reply on C2P. See
        // [sendOpeningBurst], fired from the CAR_DATA channel handler.
    }

    /**
     * The vendor's opening burst, in its exact order and on the P2C socket the dash reads with a 9s
     * timeout (times from the working Kove session, 14:01:00.657→.698):
     *   0x20490 TALK_TEAM_UP {status:0} → 0x20310 TALKIE {ismute:false,islogin:false} →
     *   0x20050 SCREEN_UNLOCKED → 0x20020 APPSTATUS mode:2 (×2) → 0x204B0 VIRTUAL_INST (empty).
     * SWITCH_FRONT is deliberately NOT here — the vendor sends that once, ~10s later, at mirror start.
     */
    private fun sendOpeningBurst(tag: String, out: java.io.OutputStream) {
        // Exact vendor burst (logog.txt, case-insensitive census, all within 33.284–33.322):
        //   0x20490 TALK_TEAM_UP → 0x20310 TALKIE → 0x20050 SCREEN_UNLOCKED →
        //   0x20020 APPSTATUS mode:2 (×2) → 0x204B0 VIRTUAL_INST (empty, dash replies UNSUPPORT).
        // (An earlier note wrongly said VIRTUAL_INST is never sent — that was a case-sensitive grep
        // miss; the log has it as 0x000204B0. The dash UNSUPPORTing it is normal and expected.)
        sendSubsystemStatus(tag, out)
        sendScreenUnlocked(tag, "handshake")
        sendAppStatusForeground(tag, out)
        sendAppStatusForeground(tag, out, quiet = true)
        try {
            log("[$tag] → VIRTUAL_INST_STATE_CHANGED (empty, closes handshake burst — vendor sends it too)")
            PxcFrame(PxcFrame.CMD_VIRTUAL_INST_STATE_CHANGED, ByteArray(0)).write(out)
        } catch (e: Exception) {
            log("[$tag] VIRTUAL_INST_STATE_CHANGED(empty) failed: $e")
        }
        // The vendor keeps APPSTATUS flowing at 1Hz on this P2C socket from here on — not only after
        // mirror start — which is exactly what holds off the dash's 9s read timeout. Start the
        // heartbeat now, on CAR_DATA. (announceMirrorActive re-calls this; the isAlive guard no-ops.)
        startAppStatusHeartbeat(tag)
    }

    /**
     * `ECP_P2C_TALK_TEAM_UP_STATUS` + `ECP_P2C_TALKIE_STATUS` — "no team-up, not muted, not logged
     * in". We have neither feature, so the honest values are the vendor's own defaults; what matters
     * is that the dash hears them before projection is negotiated.
     */
    private fun sendSubsystemStatus(tag: String, out: java.io.OutputStream) {
        try {
            val team = JSONObject().put("status", 0)
            log("[$tag] → TALK_TEAM_UP_STATUS $team")
            PxcFrame(PxcFrame.CMD_TALK_TEAM_UP_STATUS, team.toString().toByteArray(Charsets.UTF_8))
                .write(out)
            // Vendor wire key is `isLogin` (capital L) — `{"ismute":false,"isLogin":false}`. We had
            // `islogin`, which the dash silently drops. Match the vendor exactly.
            val talkie = JSONObject().put("ismute", false).put("isLogin", false)
            log("[$tag] → TALKIE_STATUS $talkie")
            PxcFrame(PxcFrame.CMD_TALKIE_STATUS, talkie.toString().toByteArray(Charsets.UTF_8))
                .write(out)
        } catch (e: Exception) {
            log("[$tag] subsystem status failed: $e")
        }
    }

    /**
     * `ECP_P2C_SWITCH_FRONT` — asks the dash to put the projection page on screen.
     *
     * Empty body; the command id alone carries the meaning. The vendor client fires it whenever it
     * wants the dash looking at the projection — on "start mirror", on a hard-key press, after the
     * screen-capture permission dialog. Announcing that we are foreground ([sendAppStatusForeground])
     * is not the same thing: it states where *our* app is, this asks the dash to change page.
     */
    fun sendSwitchFront(tag: String, out: java.io.OutputStream, why: String) {
        log("[$tag] → SWITCH_FRONT (show the projection page) — $why")
        try {
            PxcFrame(PxcFrame.CMD_SWITCH_FRONT, ByteArray(0)).write(out)
        } catch (e: Exception) {
            log("[$tag] SWITCH_FRONT failed: $e")
        }
    }

    /**
     * `ECP_P2C_APPSTATUS_FOREGROUND` (0x20060) — tells the dash our projection app is on screen.
     *
     * CFMoto MotoPlay dashes switch to the projection page by themselves, so this was never needed.
     * Instrument-cluster style units don't: they stay on their own HUD page, ask for HUD content in
     * a loop (`ECP_C2P_ENABLE_DOWNLOAD_PHONE_HUD`, 0x10040) and never show the video they are
     * already pulling — the symptom is a healthy session with frames flowing to a dash that shows
     * nothing.
     *
     * Payload matches the vendor SDK's `ECP_P2C_APPSTATUS_BACKGROUND` base: the phone's display
     * geometry plus the two input-capability flags.
     */
    fun sendAppStatusForeground(
        tag: String,
        out: java.io.OutputStream,
        mirrorMode: Int = MIRROR_MODE_PROJECTING,
        background: Boolean = false,
        quiet: Boolean = false,
    ) {
        // Field-for-field what the vendor client sends: the **phone's own** display size (not the
        // projected video size). `setMode(2)` in the SDK also forces rotation 0 and portrait
        // dimensions, so width=min, height=max regardless of how the phone is being held.
        val dm = android.content.res.Resources.getSystem().displayMetrics
        val w = minOf(dm.widthPixels, dm.heightPixels)
        val h = maxOf(dm.widthPixels, dm.heightPixels)
        val body = JSONObject().apply {
            put("displayRotation", 0)
            put("width", w)
            put("height", h)
            put("enableAccessibility", false)
            put("enableAOAHid", true)
            put("mode", mirrorMode)
        }
        val cmd = if (background) PxcFrame.CMD_APPSTATUS_BACKGROUND else PxcFrame.CMD_APPSTATUS_FOREGROUND
        if (!quiet) log("[$tag] → APPSTATUS_${if (background) "BACKGROUND" else "FOREGROUND"} $body")
        try {
            PxcFrame(cmd, body.toString().toByteArray(Charsets.UTF_8)).write(out)
        } catch (e: Exception) {
            log("[$tag] APPSTATUS failed: $e")
        }
    }

    /**
     * `ECP_P2C_APPSTATUS_SCREEN_UNLOCKED` — empty body, sent on the P2C (CAR_DATA) socket.
     *
     * Goes out at the two points the vendor sends it: in the opening burst once the P2C socket
     * opens, and again while handling `REQ_RV_DATA_START` before the reply. Best-effort — a dash
     * that doesn't care ignores an unknown-to-it status.
     */
    fun sendScreenUnlocked(tag: String, reason: String) {
        val sock = p2cSocket() ?: return
        if (sock.isClosed) return
        log("[$tag] → APPSTATUS_SCREEN_UNLOCKED ($reason)")
        try {
            PxcFrame(PxcFrame.CMD_APPSTATUS_SCREEN_UNLOCKED, ByteArray(0))
                .write(sock.getOutputStream())
        } catch (e: Exception) {
            log("[$tag] APPSTATUS_SCREEN_UNLOCKED failed: $e")
        }
    }

    /**
     * `ECP_P2C_DRIVING_MASK_STATUS` — "the driving-mode mask is/isn't covering the projection".
     *
     * The vendor pairs this with APPSTATUS on the same 1Hz tick for the whole session, and reports
     * `false` throughout while its `DrivingModeUtils.isDrivingModeNew` stays false. We have no
     * driving-mode overlay at all, so the honest answer is always `false` — but the dash has to hear
     * it, because "projecting" and "actually showing something" are two separate statements to it.
     */
    fun sendDrivingMaskStatus(
        tag: String,
        out: java.io.OutputStream,
        maskVisible: Boolean = false,
        quiet: Boolean = false,
    ) {
        val body = JSONObject().apply { put("isMaskVisible", maskVisible) }
        if (!quiet) log("[$tag] → DRIVING_MASK_STATUS $body")
        try {
            PxcFrame(PxcFrame.CMD_DRIVING_MASK_STATUS, body.toString().toByteArray(Charsets.UTF_8))
                .write(out)
        } catch (e: Exception) {
            log("[$tag] DRIVING_MASK_STATUS failed: $e")
        }
    }

    private fun onCheckSn(tag: String, frame: PxcFrame, out: java.io.OutputStream) {
        val text = frame.payload.asText()
        log("[$tag] CHECK_SN from bike: $text")
        val sn = try { JSONObject(text).optString("sn") } catch (e: Exception) { "" }
        // Restored (was: dropped as "extra"): the sessions that pulled frames continuously (20:01,
        // 22:05) acked CHECK_SN here on C2P. Dropping it coincided with the dash pulling ~2 frames
        // then closing. Keep the ack until proven otherwise.
        PxcFrame(PxcFrame.CMD_CHECK_SN_ACK, ByteArray(0)).write(out)

        // The licence call is a ~3s network round-trip. It MUST NOT run on this PXC read thread —
        // blocking here stalls CAR_CTRL reads for 3s (the dash can't get its other frames acked and
        // tears the session down). The vendor runs it async too. Fire it on a background thread and
        // send CHECK_SN_RESULT when it returns; if the socket has closed by then the write just fails.
        val channel = carChannel ?: ""
        val huid = carHuid ?: ""
        Thread {
            val verdict = if (channel.isNotEmpty() && huid.isNotEmpty() && sn.isNotEmpty()) {
                CarbitLicense.check(channel, huid, sn) { log("[$tag] $it") }
            } else {
                log("[$tag] CHECK_SN: missing channel/huid/sn — using fallback pass")
                null
            }
            val result = JSONObject().apply {
                put("isOk", verdict?.isOk ?: true)
                put("errCode", 0)
                put("errMsg", "")
                put("id", verdict?.id ?: "")
                put("client_set", verdict?.clientSet ?: "")
            }
            log("[$tag] → CHECK_SN_RESULT $result${if (verdict == null) " (fallback)" else " (server)"}")
            try {
                // Kove: on P2C, as the vendor sends it (PXCForCar-P2C process 0x000201C0, in captures
                // from two different phones). Every other dash keeps the reply on the request socket.
                val p2c = if (EasyConnProber.spoofedPackage == EasyConnProber.PKG_EASYRIDE) {
                    p2cSocket()?.takeUnless { it.isClosed }
                } else {
                    null
                }
                val target = p2c?.getOutputStream() ?: out
                log("[$tag] CHECK_SN_RESULT via ${if (p2c != null) "P2C" else "request socket"}")
                PxcFrame(PxcFrame.CMD_CHECK_SN_RESULT, result.toString().toByteArray(Charsets.UTF_8)).write(target)
            } catch (e: Exception) {
                log("[$tag] CHECK_SN_RESULT write failed (socket gone?): ${e.message}")
            }
        }.apply { isDaemon = true; name = "carbit-license"; start() }
    }
}

private fun ByteArray.asText(): String =
    if (isEmpty()) "" else try { String(this, Charsets.UTF_8) } catch (e: Exception) { "<${size}b>" }

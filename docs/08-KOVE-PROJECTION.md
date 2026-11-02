# Kove dash projection (modelId 21318)

Field notes from 2026-11-02: how we first put our own picture on a Kove dash, and what is still
unproven. Evidence levels are stated on purpose. Do not read an entry under "Not isolated" as a cause.

## Android Auto from the app (2026-11-02, 16:48)

The rider reported the dash working with OpenCfMoto alone on a POCO F8 Pro, vendor app disabled:
head unit server on `:5277`, Android Auto decoding at 30 fps, Wi-Fi Direct group, PXC, c2.qti.avc
encoder at 4194 kbps, about 40 frames/s pulled.

Operating notes from that run:

- Start the head unit server first, then open the app once. Auto-connect runs on open.
- Pressing Connect again tears the AAP socket down. The server then accepts TCP but sends no
  `VERSION_RESPONSE` until it is restarted.
- The vendor app keeps `10920-10922` open in the background, which aborts PXC with `link ports are held`.
- The dash took about 35s to join in this run.

The same dash had not been shown to be free of vendor-session state, so the cold-start question below remains open.

## Link supervision (`KoveLink`), 2026-11-02

Observed on the dash with the POCO F8 Pro:

- **Automatic recovery:** after the Wi-Fi Direct group was removed at 17:11:13, the group, BLE credentials and PXC were rebuilt and the first frame went out 13s later, with Android Auto kept alive.
- **Screen off:** with the phone locked for 26s the stream kept going (11418 to 12421 frames), with no group removal. A longer lock is untested.
- **Cause of the removal:** that removal followed closing the task from recents (`TASK_CLOSE`), not locking. The earlier cut at 17:01:37 was first blamed on the lock, but that was inferred and not isolated.

Still untested: recovery after powering the dash off and on, and starting from a dash with no earlier vendor session.

## Handlebar buttons

Tested on 2026-11-02 pressing up, down, enter and back:

- With Open Kove: no key event, AVRCP event or new PXC command reached the phone.
- With the vendor app projecting: nothing reached the phone, and the rider saw nothing move on the dash.

The vendor app does define `ECP_C2P_SEND_BTN_EVENT_TO_PHONE` (`0x10350`, JSON `code` and `type`), but this dash did not send it. On this dash, Android Auto is therefore driven from the on-screen Controls. If another dash sends `0x10350`, it appears in the log as an unhandled control frame.

## Correction

Heavy `0x10040` traffic does not mark a failed session; it also occurs while a picture is showing.

## Result

A test card sent from a Windows PC (`OPENCFMOTO DESDE LA PC` over `testsrc2`) appeared on the dash:
2085 frames in about 90s. The vendor app was not running during that session. It had projected on
the same dash a few minutes earlier, so a start from a power-cycled dash is still untested.

## Hardware and identity

| Item | Value |
|---|---|
| BLE name | `KY241DB1` |
| BLE address | `DD:0D:30:24:1D:B1` (random static: connect to a device from a scan) |
| BLE module | Feasycom FSC-BW121, firmware `V1.3.8.8` |
| Wi-Fi address | `dc:0d:30:a4:1d:b1` |
| GATT | service `0000b360-…`, characteristic `0000b362-…` (write + notify) |
| QR | `…/_ylqx?modelid=21318&sn=<rotating>&action=128&bm=<BLE address>` |
| PXC `CLIENT_INFO` | `productType:3`, `mirrorMode:1`, `supportConnect:776`, `flavor:50`, `sdkVersion:1.0.10` |
| Capture | 800×352, `bitRate:4194304`, `capScreenMode:8`, `supportExtendProtocol:0` |

## Sequence that showed a picture

1. BLE: scan, connect, subscribe, send `CLIENT_INFO` (cmd 0x30). The dash answers INNER 0x59 (142 bytes).
2. Send `VERIFY_RESULT`, `EXTEND_CLIENT_INFO`, then `REQUEST_BUILD_NET` until status `2` (it answers `-1` first).
3. Send `NOTIFY_AP_INFO` with the phone's Wi-Fi Direct group. The dash joins in about 5s and reports its IP with cmd 0x53.
4. Send `0x70000010` to `<dash>:10930` from the address the dash should call back.
5. The dash opens two sockets to `:10922`, then `:10921` (media control) and `:10920` (media data).
6. On `:10922` answer channel selects and `CLIENT_INFO`. On P2C send the opening burst and `APPSTATUS` at 1 Hz.
7. On `:10921` answer `0x10`, `0x40` and `0x70`. On `:10920` answer each `0x72` with `[size LE32][Annex-B access unit]`.

In the session that worked, steps 1–3 ran on the phone (OpenCfMoto waiting on Android Auto, so it
held the group without starting PXC) and steps 4–7 ran on the PC, joined to that group as a client.

### Stream that was displayed

- libx264 High@3.0, 800×352, nominal 36 fps (about 24 fps delivered), 4194 kbps, no B-frames, GOP 108.
- Access units contain only SPS, PPS, IDR or one slice. No SEI, no AUD.

## What did not work

| Attempt | Result |
|---|---|
| Dash sent the credentials of a Windows Mobile Hotspot (Auto, 5GHz, 2.4GHz) | Never joined |
| Dash sent the credentials of the home router (channel 6) | Never joined or reported an IP |
| OpenCfMoto Mirror, ~3 fps at 1.2 Mbps | Frames pulled, nothing shown, no Wi-Fi icon |
| PC stream at 36 fps, 1.2 Mbps, first access unit holding SEI and an extra slice | Frames pulled, nothing shown |

Whether the Windows hotspot was actually beaconing was never verified from another device.

## Why a picture sometimes did not appear (found 2026-11-02)

**The dash only shows the projection if it is on Connection → WiFi Connection when the session starts.** Confirmed by the rider: from that screen, reconnecting without scanning showed the map. Scanning only appeared to fix it because scanning requires being on that screen. Android Auto, the BLE sequence and the video were not the cause, so the candidates listed below are superseded.

Opening that screen during a session does not start the picture, and sends nothing over PXC or BLE that the app could detect. `ECP_P2C_AUTO_START_SETTING` (`0x20240`, `{"status":true}`) got no acknowledgement and changed nothing. As a workaround, `KoveLink` restarts a session once if it began before the dash had been up for 45s (`currentHUTime`), so a rider who opened the screen in that time gets a picture. That workaround is not yet verified on the dash.

`SWITCH_FRONT` is acknowledged but does not change the screen. Untested commands include `ECP_P2C_SWITCH_TO_SYSTEM_MAIN_PAGE`. `ECP_P2C_REQUEST_EXEC_ADB_SHELL_CMD` exists too and is deliberately unused, since running commands on the dash could damage it.

## Not isolated

The last failing PC run and the first working one differ in three ways, changed together:

1. Clean access units instead of a first unit with SEI and an extra slice.
2. 4194 kbps instead of 1200 kbps.
3. The vendor app had just projected on the dash.

Point 3 matters: in the working run the dash sent no `CHECK_SN` (0x103E0), sent `0x10430` for the
first time, and sent 23 `0x10040` frames in total instead of one per video frame. Its state may
have changed, so a power-cycle test is the next step.

The dash's Wi-Fi icon state during the working run was not recorded.

## Vendor capture on the same phone (POCO F8 Pro)

- Encoder `c2.qti.avc.encoder`, 800×352@36, 4194304 CBR, I-frame interval 3, High@3.0.
- SPS/PPS: `67 64 00 1E AC B4 06 41 6D 00 DA 14 26 A0` / `68 EE 06 F2 C0`.
  OpenCfMoto on this phone produces `…6D 35 05 06 05 06 D0 A1 35`, so `patchSpsColorToVendor()`,
  which looks for the Nothing Phone pattern, does not match here.
- Empty frames answer the first pulls until the encoder has output. Frames range up to about 90KB.
- `0x201C0` goes out on P2C about 1.6s after `CHECK_SN`, after the licence response.
- `0x20030` repeats after `onApp2Background`. It looks tied to the app leaving the foreground.
- The map came from a `VirtualMapPresentation` (`trueMirror:false`), before any screen-capture consent.

Ruled out: the Base64 block in `CONFIG_CAPTURE` is our own `encryptedHUID` echoed back.

## App changes (Kove path only, `PKG_EASYRIDE`)

| Change | Why | Verified on the dash |
|---|---|---|
| Encoder uses the dash's bitrate, including Mirror | Replaces the 1.2 Mbps cap; candidate 2 | Not yet |
| Repeat frames at 36 fps on a still screen | A still mirror fell to ~3 fps | Not yet |
| `CHECK_SN_RESULT` on P2C | Vendor captures on two phones | Not yet; the earlier note reported a drop at ~9s on P2C |
| BLE `CLIENT_INFO` re-sent after 3.5s | A handshake reply was lost on Android and on Windows | Not yet |

## Android Auto startup on AA 17.9

**Connect** waits for steady Android Auto video before PXC starts. On Android Auto 17.9.664044 that
video never arrived, so the app stayed on "Connecting" (2026-11-02, POCO F8 Pro):

- `WirelessStartupActivity`: `Permission Denial ... not exported from uid 10151`.
- `START_WIRELESS_PROJECTION`: `WIRELESS_SETUP_FAILED_TO_START_NO_HFP_FROM_HU_PRESENCE`, after
  `waitForHeadUnitConnected timeout`.

`AaReceiver` now also dials Android Auto's developer head unit server at `127.0.0.1:5277`. AAP does
not change, because the head unit is the TLS client either way. This path is not yet verified on the
dash. `DeveloperHeadUnitNetworkService` is not exported, so the rider has to start it once:

1. Android Auto settings: tap **Version** about 10 times to unlock developer mode.
2. Overflow menu: **Start head unit server**.
3. In OpenCfMoto press Connect, and look for `connected to Android Auto head unit server` in the log.

**Mirror** does not wait for Android Auto.

## Reproduce from a PC

`scripts/kove_pc_session.py` is the script used for the session above. It needs `bleak`,
`cryptography` and `imageio-ffmpeg`, plus `adb` access to a debug build of OpenCfMoto.

1. Power the dash and show the QR.
2. In OpenCfMoto press Connect and leave it on "Connecting". Do not start Mirror.
3. Wait for `dash joined our hotspot` in the log, then run:

```
python scripts/kove_pc_session.py 90 join <path-to-adb> <adb-device>
```

The script reads the group passphrase from the app's preferences, adds a temporary Wi-Fi profile on
the interface named `Wi-Fi`, and removes it on exit. Windows Firewall must allow Python inbound on
ports 10920–10922.

## Next tests

1. Power-cycle the dash and run the PC script with no vendor app. This separates candidate 3.
2. Install this build, power-cycle, and use Mirror with moving content.
3. If Mirror stays blank, compare `scripts/kove_pc_session.py` with the phone's stream, starting with the SPS.

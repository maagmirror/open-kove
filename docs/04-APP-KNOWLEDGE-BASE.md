# OpenCfMoto — Technical Architecture & Knowledge Base

This document serves as a comprehensive technical guide to the **OpenCfMoto** codebase. It outlines the reverse-engineered protocol specs of the CFMoto dashboard, the implementation details of the loopback Android Auto Projection (AAP) receiver, the transcoding pipeline, and the application's overall system architecture.

---

## 1. Project Overview & Objectives

**OpenCfMoto** is a clean-room, proof-of-concept Kotlin application designed to enable **Android Auto (Google Maps / Waze)** on CFMoto motorcycle dashboard displays (such as the ~5" landscape unit **CFDL16-6GUV**) that support Carbit's "MotoPlay / EasyConn (PXC)" projection technology.

### The Problem
* The official CFMoto app uses a proprietary Carbit EasyConn client to mirror the phone screen or stream custom maps.
* Mirroring the entire screen is highly inconvenient: it occupies the phone, prevents the phone from being locked, triggers letterboxing/pillarboxing due to aspect ratio differences, and drains the battery.
* Google Maps / Waze cannot be natively projected to the bike dashboard.

### The Solution
* Embed a headless **Android Auto Projection (AAP)** head-unit receiver directly inside the OpenCfMoto application (ported from **headunit-revived**).
* Trigger Google Android Auto (Gearhead) on the phone in **non-VPN loopback self-mode** (connecting locally to `127.0.0.1:5288`).
* Capture the decoded Android Auto H.264 stream (`800x480` pixels), feed it into a hardware `MediaCodec` encoder to scale and re-encode to the bike's native resolution (`800x384` Baseline H.264), and stream the output to the bike via Carbit PXC.
* Result: A root-free, VPN-free projection system where the phone's screen remains lockable and usable while the dashboard displays navigation.

---

## 2. System Architecture

The application's logic is split into three main parts: **UI & Wi-Fi Management**, the **Android Auto Receiver (Local)**, and the **Bike PXC Streaming Client (Outbound)**.

```
       [ Google Android Auto / Gearhead ]
                       │
                       │ Projects H.264 via Wi-Fi Loopback (no VPN)
                       ▼
            [ 127.0.0.1 : 5288 Server ]
                       │
            ┌──────────┴────────────────────────┐
            │   AndroidAutoService (FGS)        │
            │   ├── AaReceiver                  │
            │   └── VideoDecoder (MediaCodec)   │
            └──────────┬────────────────────────┘
                       │ Decoded Frames
                       ▼
            [ VideoPipeline.inputSurface ]
                       │
            ┌──────────┴────────────────────────┐
            │   VideoPipeline (H.264 Encoder)   │  <-- Encodes to 800x384 Baseline
            └──────────┬────────────────────────┘
                       │ Bounded Frame Queue
                       ▼
            ┌──────────┴────────────────────────┐
            │   EasyConnProber (PXC Client)     │
            │   ├── PxcHandshake (Control)      │
            │   └── Port 10920 Data Socket      │
            └──────────┬────────────────────────┘
                       │
                       │ Streams H.264 frames over Wi-Fi AP
                       ▼
           [ CFMoto MotoPlay Dashboard ]
```

### Component Breakdown

| Package / File | Class | Responsibility |
| :--- | :--- | :--- |
| `dev.zanderp.opencfmoto` | [MainActivity](file:///d:/Projects/open-cfmoto/app/src/main/java/dev/zanderp/opencfmoto/MainActivity.kt) | Entry activity, manages permission checks, handles QR scan triggers, starts the Android Auto Service, and displays running diagnostic logs. |
| `dev.zanderp.opencfmoto` | [AndroidAutoService](file:///d:/Projects/open-cfmoto/app/src/main/java/dev/zanderp/opencfmoto/AndroidAutoService.kt) | Foreground service (`connectedDevice` type) that hosts the AAP receiver and H.264 encoder. It requests a partial `WakeLock` to survive backgrounding and lock-screen states. |
| `dev.zanderp.opencfmoto` | [EasyConnProber](file:///d:/Projects/open-cfmoto/app/src/main/java/dev/zanderp/opencfmoto/EasyConnProber.kt) | Orchestrates outbound socket probes and hosts local TCP servers (ports 10920/10921/10922) to handle inbound connections from the bike. |
| `dev.zanderp.opencfmoto` | [VideoPipeline](file:///d:/Projects/open-cfmoto/app/src/main/java/dev/zanderp/opencfmoto/VideoPipeline.kt) | Manages the `MediaCodec` AVC encoder. Operates in three modes: own presentation (UI), whole-screen mirror (MediaProjection), or external source (Android Auto). |
| `dev.zanderp.opencfmoto.aa` | [AaReceiver](file:///d:/Projects/open-cfmoto/app/src/main/java/dev/zanderp/opencfmoto/aa/AaReceiver.kt) | Listens on port `5288` for Android Auto connections, handles version negotiations, registers NSD services, and spawns the session handler. |
| `dev.zanderp.opencfmoto.aa` | [AapTransport](file:///d:/Projects/open-cfmoto/app/src/main/java/dev/zanderp/opencfmoto/aa/AapTransport.kt) | Drives the AAP SSL handshake (using Conscrypt), establishes session threads, and directs incoming video payloads to the decoder. |
| `dev.zanderp.opencfmoto.aa` | [VideoDecoder](file:///d:/Projects/open-cfmoto/app/src/main/java/dev/zanderp/opencfmoto/aa/VideoDecoder.kt) | Decodes incoming Android Auto H.264 NAL units into a Target Surface (the encoder input surface). Parses SPS headers to establish video dimensions. |
| `dev.zanderp.opencfmoto.aa` | [AaSelfMode](file:///d:/Projects/open-cfmoto/app/src/main/java/dev/zanderp/opencfmoto/aa/AaSelfMode.kt) | Reflectively generates the necessary system Intents to trigger Android Auto Wireless projection to localhost. |
| `dev.zanderp.opencfmoto` | [BikeWifi](file:///d:/Projects/open-cfmoto/app/src/main/java/dev/zanderp/opencfmoto/BikeWifi.kt) | Connects to the bike's AP using `WifiNetworkSpecifier` and binds the process network interface to keep other traffic on cellular. |

---

## 3. Network Topology & Port Mapping

The connection sequence relies on the phone acting as a **TCP Server** after sending a brief initial handshake probe. 

### Network Parameters
1. **SSID & Password**: Obtained from the dashboard's QR code.
2. **IP Allocation**:
   * **Bike (Gateway)**: `192.168.0.1`
   * **Phone**: Assigned dynamically by the bike's DHCP, typically `192.168.0.50` (subnet mask `/24`).
3. **mDNS Advertisement**: The bike advertises `_EasyConn._tcp.local.` on port `10930`.

### Socket Port Assignments
The phone binds servers to its local bike-network IP on three key ports:

| Port | Protocol Plane | Framing Type | Description |
| :--- | :--- | :--- | :--- |
| **10922** | PXC Control | `CmdBaseHead` (16 bytes) | Handshake negotiations, `CLIENT_INFO` JSON exchange, serial number checks, control heartbeats. |
| **10921** | Media Control | `ReqBase` (8 bytes) | Media setup parameters, capabilities exchange, media-plane heartbeats. |
| **10920** | Media Data | `ReqBase` (8 bytes) / Raw | Direct pull-based H.264 stream. Receives frame pull commands and returns raw H.264 access units. |
| **10930** | Bike Probe | `CmdBaseHead` (16 bytes) | Hosted by the bike. The phone makes a single outbound socket connection to this port to announce its IP, then closes it. |

---

## 4. Carbit PXC Wire Protocols

### 4.1. `CmdBaseHead` Framing (Control Plane — Port 10922 & Probe)
The control plane exchanges messages with a 16-byte little-endian header followed by a payload (primarily JSON strings):

```
+-------------------+-------------------+-------------------+-------------------+
|  cmdType (int32)  | totalLength(int32)|   magic (int32)   |  reserved (int32) |
|      0 .. 3       |      4 .. 7       |      8 .. 11      |     12 .. 15      |
+-------------------+-------------------+-------------------+-------------------+
|                                                                               |
|                            Payload (totalLength - 16 bytes)                   |
|                                                                               |
+-------------------------------------------------------------------------------+
```
* **Integrity Validation**: The `magic` integer is computed as `cmdType XOR totalLength`. The bike will drop connections if the magic value does not match this calculation.

#### Key Command Constants:
* `0x70000010` (`CMD_MDNS_RESPOND`): Sent by the phone to the bike's port 10930 to announce itself. Payload: `{"phoneType":"Android","packageName":"com.cfmoto.cfmotointernational"}`.
* `0x70000011` (`CMD_MDNS_RESPOND_ACK`): Bike's response. Payload: `{"status":true}`.
* `0x10000` (`CMD_CHANNEL_CAR_CTRL`): Sent by the bike to select the control channel. Phone replies with `0x10001`.
* `0x20000` (`CMD_CHANNEL_CAR_DATA`): Sent by the bike to select the data channel. Phone replies with `0x20001`.
* `0x10010` (`CMD_CLIENT_INFO`): Sent by the bike to report its capabilities. The phone must reply with `0x10011` containing the phone's metadata, RSA public key, and private-key-signed HUID string.
* `0x103e0` (`CMD_CHECK_SN`): Sent by the bike containing its serial number. Phone must reply with an empty ACK (`0x103e1`), followed by a separate frame `0x201c0` (`ECP_P2C_CHECK_SN_RESULT`) indicating `{"isOk":true}`.
* `0x70000000` (`CMD_HEARTBEAT`): Sent periodically by the bike. Phone replies with `0x70000001`.
* `0x103a0` (`CMD_FTP_INFO`): Sent by the bike to report OTA update server details. Phone replies with `0x103a1`.
* `0x10020` (`CMD_APP_FEATURES`): Sent by the bike to report and negotiate supported application features (e.g., music, TTS, VR status). Phone replies with `0x10021`.
* **Handshake Auto-Ack Fallback**: In the Carbit PXC handshake, request commands sent by the bike are even-numbered and expect a reply of `command + 1`. The dispatcher automatically replies to any unhandled even-numbered command with `command + 1` (empty body) to prevent connection timeouts.

---

## 5. Transcoding & Video Pipeline

Because Android Auto projects in standard resolutions (minimum `800x480` for our profile) and the bike displays at a non-standard `800x384`, direct passthrough is impossible. A hardware transcode is required:

```
[Android Auto H.264 Stream] ──(Network)──► [VideoDecoder (MediaCodec)]
                                                    │
                                                    ▼ (Decodes to Surface)
                                           [VideoPipeline.inputSurface]
                                                    │
                                                    ▼ (Hardware Scaled by GPU)
                                           [VideoPipeline (MediaCodec Encoder)]
                                                    │
                                                    ▼ (Encodes to 800x384 Baseline H.264)
                                           [Bounded Frame Queue] ──► PXC Stream
```

### Video Codec Specifications
* **MIME Type**: `video/avc` (H.264)
* **Resolution**: `800x384`
* **Profile**: **Baseline Profile @ Level 3.1** (critical for compatibility with the embedded car/bike decoders).
* **Frame Rate**: `30 FPS`
* **Bitrate**: `2.5 Mbps`
* **I-Frame Interval**: `1 second` (frequent keyframes permit recovery from packet drops).
* **Key Configuration (`KEY_REPEAT_PREVIOUS_FRAME_AFTER`)**:
  * Set to `100,000` microseconds (100 ms).
  * Encoders running from a `Surface` only output frames on *new* buffers. If the display is static (e.g., an idle map screen), the encoder stops producing output.
  * This configuration guarantees a minimum output of `10 FPS`, preventing the bike's 9-second timeout from closing the media socket.

---

## 6. Android Auto Loopback Integration

### 6.1. Wi-Fi Loopback Self-Mode
The app implements Android Auto's "Wi-Fi loopback self-mode". Unlike HUR's "Fake VPN offline" mode which captures the default route and redirects all network requests, loopback operates over `127.0.0.1` and does not use a VPN interface.
* **Why it matters**: A VPN interface swallows the bike's inbound connections on ports 10920–10922. The loopback method coexists with the bike Wi-Fi network seamlessly.
* **Network Routing**: The app binds its sockets to the bike AP using `bindProcessToNetwork`. System networking routes internet traffic through the phone's cellular network, while local operations loop through `127.0.0.1`.

### 6.2. Gearhead Service Trigger
The startup sequence reflectively creates a fake `android.net.Network` and `WifiInfo` structure, launching Google Auto's wireless service:
* **Target Activity**: `com.google.android.apps.auto.wireless.setup.service.impl.WirelessStartupActivity` on package `com.google.android.projection.gearhead`.
* **Fallback Broadcast**: `com.google.android.apps.auto.wireless.setup.receiver.WirelessStartupReceiver` (action `...wirelessstartup.START`).
* **Extras**:
  * `PARAM_HOST_ADDRESS` = `"127.0.0.1"`
  * `PARAM_SERVICE_PORT` = `5288`
  * `PARAM_SERVICE_WIFI_NETWORK` = Mapped system `Network`
  * `wifi_info` = Custom `WifiInfo` carrying a fake SSID (`"Headunit-Fake-Wifi"`).

### 6.3. Service Discovery Configuration
The embedded head-unit declares its profile via protobuf in `ServiceDiscoveryResponse`:
* **Resolution**: `800x480` at `160 DPI`.
* **Margins**: Set to `0` width and `0` height.
* **Audio Sinks**: Declares `MEDIA_CODEC_AUDIO_PCM` for `SYSTEM` stream (16kHz, 16-bit, mono) on channel `ID_AU2`. This is required to prevent Android Auto from refusing the connection, though the PCM data is discarded in this headless version.
* **Microphone**: Declares PCM voice input support (`ID_MIC`) to allow communication.
* **Sensors**: Requests `DRIVING_STATUS` and `NIGHT` sensors.

---

## 7. Build Details & Configuration

The project compiles with standard Android configurations:

### Version Constraints
* **Min SDK**: `29` (Android 10)
* **Target SDK**: `36` (Android 15)
* **JVM Target**: `11`
* **AGP Version**: `9.2.1`

### Third-Party Dependencies

```toml
[versions]
mlkit = "17.3.0"        # Barcode scanning for bike QR
camerax = "1.4.1"      # CameraX UI for QR scanner
jmdns = "3.5.9"        # JmDNS for bike discovery
protobuf = "3.25.1"    # Protobuf-Java for AAP messaging
conscrypt = "2.5.3"    # Conscrypt engine for SSL handshake
```

---

## 8. Dormant BLE Wake-Up Protocol

While not required for MotoPlay projection, the app contains code reflecting the bike's Bluetooth Low Energy (BLE) control protocol:

1. **GATT Services**:
   * Service UUID: `0000B354-D6D8-C7EC-BDF0-EAB1BFC6BCBC`
   * Write Characteristic: `0000B356-...`
   * Notification Characteristic: `0000B357-...`
2. **Frame Format**:
   * `[ 0xAB ][ 0xCD ][ cmdId: 1 byte ][ payloadLength: 2 bytes LE ][ Protobuf Payload ][ checksum: 1 byte ][ 0xCF ]`
   * **Checksum**: computed as `(cmdId + len_lo + len_hi + sum(payload)) & 0xFF`.
3. **Authentication Handshake**:
   * The bike sends a 64-byte token (`0x5A`).
   * The bike requests a challenge response (`0x5B`), sending a 16-byte cipher (32 hex characters).
   * The phone decrypts the cipher with AES-256-ECB using a pairing key (keys stored in `BleSecrets`).
   * The phone sends back the 8-digit decoded plaintext (`0x5C`).
   * The bike responds with a success status (`0x5D`).

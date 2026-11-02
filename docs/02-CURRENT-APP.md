# Current App — OpenCfMoto Architecture & State

Package `dev.zanderp.opencfmoto`. Kotlin, `minSdk 29`, `targetSdk 36`. Gradle version catalog at
`gradle/libs.versions.toml`. Deps: AppCompat/Material, CameraX + ML Kit barcode (QR), JmDNS (mDNS),
constraintlayout, **protobuf + Conscrypt** (for the embedded Android Auto receiver). The HUR-derived
Android Auto stack lives under `aa/` (see below).

## What works TODAY on real hardware ✅

1. Scan the bike QR → parse creds.
2. Join the bike Wi-Fi (`WifiNetworkSpecifier`).
3. Full **PXC control handshake** with the bike (probe → CLIENT_INFO → channel selects → SN check →
   heartbeats). Phone acts as server on 10920/10921/10922.
4. **Media negotiation** (config capture → 800×384 H.264, version, heartbeat, configcaptureextend).
5. **H.264 streaming to the dash** in two modes, both confirmed showing on the bike:
   - **UI mode** — a `Presentation` ("Hello World") on a private `VirtualDisplay` → encoder → bike.
   - **Mirror mode** — full phone screen via `MediaProjection` (**"Entire screen"** capture) → encoder → bike.
6. Confirmed: running **Android Auto in non-VPN self mode** (via headunit-revived) alongside the app and
   using **whole-screen mirror** shows Android Auto navigation on the dash. (The smoke test that
   green-lit the full plan.)
7. **Embedded Android Auto receiver → dash, end-to-end** (the `03` plan, DONE): Google AA projects to our
   in-app loopback receiver; the decoded video is re-encoded and streamed to the bike via the PXC pipeline.
   Phone can be locked/backgrounded (foreground service + wake lock). Confirmed with **Waze/Maps readable
   on the dash**.
8. **Two dashboards supported** via bike profiles: **CFDL16** (675, landscape) and **CFDL26** (1000 MT-X,
   portrait touch, `sdkVersion 1.1.4`). The app auto-detects which and adapts the handshake + video
   geometry. See "Multi-bike profiles" below and `01` §7.

The old mirror-path limitations (occupies the phone, dies on lock, single-app capture unsupported) are
**superseded** by the embedded receiver (#7) — mirror mode still exists as a fallback but isn't the path
for Android Auto anymore.

## Source files (all under `app/src/main/java/dev/zanderp/opencfmoto/`)

### Bike / PXC pipeline (the working core — DO NOT rewrite; you'll only swap the video source)
- **`EasyConnProber.kt`** — the heart. Resolves IPs, binds servers on 10920/10921/10922, sends the
  `0x70000010` probe to `bike:10930`, accepts the bike's call-backs, routes by port to either the
  CmdBaseHead control loop (`PxcHandshake`) or the ReqBase `mediaLoop`. `handleMediaReq` answers
  config-capture(16→17), version(48→49), heartbeat(64→65), extend(96→97), data-start(112→113), and on
  data-next(114) pulls a frame from `VideoPipeline.pollFrame()` and `sendFrameRaw()`s it
  (`[size LE][AnnexB AU]`). Owns the `VideoPipeline` instance (created on cmd 112).
- **`PxcFrame.kt`** — CmdBaseHead 16-byte codec (`read`/`write`) + all cmd-ID constants.
- **`PxcHandshake.kt`** — control-plane JSON dispatcher (CLIENT_INFO reply, SN result, channel acks,
  heartbeats). Builds the phone CLIENT_INFO with `RsaKeys`.
- **`RsaKeys.kt`** — in-memory RSA keypair; `publicKeyBase64` + `signHuid()` for CLIENT_INFO.
- **`VideoPipeline.kt`** — MediaCodec H.264 encoder (800×384, see `01` §5). Two sources:
  `setupDisplayAndPresentation()` (UI mode, DisplayManager VirtualDisplay + Presentation) and
  `setupProjectionDisplay()` (mirror mode, MediaProjection). Drain loop → bounded frame queue →
  `pollFrame(timeoutMs)`. **This is where the Android Auto video will plug in as a third source.**

### Wi-Fi / QR / UI
- **`MainActivity.kt`** — buttons (Scan / Scan Mirror / Stop / Share / Clear), the QR scan launcher, the
  MediaProjection consent flow (+ `ProjectionService` foreground-service polling), the on-screen log +
  Share export. Note: a stashed batch had Reconnect/auto-connect + a two-row layout; current tree is the
  simpler version.
- **`QrScanActivity.kt`** / **`QrData.kt`** — CameraX + ML Kit QR scan and URL parse.
- **`BikeWifi.kt`** — `WifiNetworkSpecifier` join. NOTE: this always shows a system "connect?" dialog
  (no silent join possible for a non-system app). `bindProcessToNetwork` binds our process to the bike
  Wi-Fi so our sockets use it while other apps (e.g. Android Auto) use cellular for internet.

### MediaProjection plumbing
- **`ProjectionService.kt`** — `mediaProjection` foreground service (required on Android 14+ before
  `getMediaProjection`). Exposes `isForeground` flag that MainActivity polls.
- **`ProjectionHolder.kt`** — holds the active `MediaProjection`; null = UI mode, non-null = mirror.

### BLE (dormant — not used for projection)
- **`BleWakeUp.kt`**, **`BleProtocol.kt`**, **`BleSecrets.kt`** — the BLE wake-up (see `01` §6). Leave.

## Build / run / debug

- Standard Gradle Android build. Owner installs to a Samsung SM-G991B (Android 15) for real use.
- **On-screen log + Share button** is the primary debugging channel — the owner pastes the exported log.
  Anything you add MUST log its key steps with a stage tag.
- The `MediaProjection` dialog offers **Entire screen** or **a single app** (Android 14+). Single-app
  capture is handled via `onCapturedContentResize` + an [AaCompositor] letterbox into the bike
  encoder (`VideoPipeline.setupProjectionDisplay`). Whole-screen still works.
- Bike media timeout is ~9s — a source that doesn't produce a frame quickly will make the bike drop.

## Android Auto + multi-bike profiles (IMPLEMENTED)

The `03` plan is done, and the app now supports **two different dashboards**. Two subsystems were added.

### Bike profiles — `BikeProfile.kt`
A strategy that detects *which* dashboard is connecting and dispatches per-bike behavior, so the verified
CFDL16 path never regresses while CFDL26 gets its own quirks.

- **`BikeProfile`** interface: `score(CLIENT_INFO)` (authoritative selection), `matchesModelId(qr)`
  (early selection), `buildClientInfoReply(...)`, `handleUnknownControl(...)` (per-bike control-frame
  handling), `roundCaptureDimension(...)`, and **`aaVideo: AaVideoSpec`** (the Android Auto
  resolution/orientation + dpi to request for this dash).
- **`BikeProfiles`** registry + **`BikeProfileHolder`** (process-global active profile).
- **`LegacyCfdl16Profile`** — reproduces the original behavior byte-for-byte (landscape AA 800×480@160;
  unknown control frames = log only). Safe default / fallback.
- **`Cfdl26Profile`** — the 1000 MT-X (see `01` §7): advertises `supportFunction=128`/`supportScreenTouch`;
  **acks every otherwise-unknown control frame with `cmd+1`** (clears the post-CHECK_SN notify burst that
  gates the media plane); AA video = **portrait 720×1280@240**. Matched by QR `modelId 37426`.
- Detection timing: the profile is picked **early from the QR `modelId`** (so the AA resolution is right
  before AA starts — it can't change mid-session), then **confirmed authoritatively from CLIENT_INFO** in
  `PxcHandshake` (logs if they disagree). `EasyConnProber`/`PxcHandshake` read `handshake.profile`;
  `ServiceDiscoveryResponse` reads `BikeProfileHolder.active.aaVideo`.

### Android Auto video path — `AaCompositor.kt` + `VideoPipeline` compositor mode
AA and the bike often disagree on size *and orientation* (CFDL26: AA renders portrait 720×1280, bike wants
800×944). Rendering the decoder straight onto the encoder surface stretched the image. So:

- **`AaCompositor`** (EGL/GLES2) sits between the AA decoder and the encoder: the decoder renders into a
  `SurfaceTexture` (`decoderInputSurface()`), and each frame is drawn **aspect-preserved, centered, on a
  black background** (letterbox) into the encoder canvas. Viewport math in `computeViewport()`.
- **`VideoPipeline` compositor mode** (`compositor=true`): at AA start the compositor's input surface is
  up immediately (so AA can reach steady video *before* the bike connects — which is what triggers the
  hand-off). The **encoder is created lazily in `configureBikeCanvas(w,h)`**, called from
  `EasyConnProber` on `REQ_CONFIG_CAPTURE` (cmd 16) with the bike's **runtime** canvas — no hardcoded
  resolution. `AndroidAutoService` creates the pipeline in this mode and hands `decoderInputSurface()` to
  `AaReceiver`.
- Result for CFDL26: AA 720×1280 → letterbox `531×944 @(134,0)` inside the 800×944 stream → correct-aspect
  portrait nav with black side bars. Confirmed readable on the dash.

### Reordered start flow — `MainActivity`
Because the AA resolution must be chosen before AA starts, the flow is: **Start → scan QR (→ modelId →
profile → AA resolution) → start AA → on steady video, join bike Wi-Fi + run PXC**. (Previously AA started
first, then scanned.) The mirror path (`ProjectionHolder`) is unchanged.

### New/changed files (this work)
- **`BikeProfile.kt`** (new) — profiles, registry, holder, `AaVideoSpec`/`AaResolution`.
- **`AaCompositor.kt`** (new) — EGL letterbox compositor.
- **`VideoPipeline.kt`** — added compositor mode + `configureBikeCanvas()` + `decoderInputSurface()`;
  encoder creation extracted into `createEncoder(w,h)` (dynamic size).
- **`PxcHandshake.kt`** — holds the selected `profile`; routes CLIENT_INFO reply + unknown-control
  handling through it; sets `BikeProfileHolder`.
- **`PxcFrame.kt`** — CFDL26 cmd constants (`CMD_LOG_REPORT 0x10780`, `CMD_OTA_FTP_INFO 0x103a0`, …).
- **`EasyConnProber.kt`** — routes dimension rounding + `configureBikeCanvas` through the active profile.
- **`ServiceDiscoveryResponse.kt`** — AA video config is profile-driven (was hardcoded 800×480).
- **`AndroidAutoService.kt`** — creates the pipeline in compositor mode.
- **`MainActivity.kt`** — reordered start flow (QR → profile → AA → connect).

### Still open (next: `03` M5)
- **Touch input** back to AA (CFDL26 is a touch panel — `supportScreenTouch`, `supportHID`).
- **Audio** to the bike / phone / BT helmet (currently video-only; the AA audio sink is advertised but
  its PCM is discarded).

# OpenCfMoto — Handoff Brief for the Implementing Agent

You (the reading agent, e.g. Anthropic Fable) are taking over an in-progress reverse-engineering +
Android app project. This folder is the complete knowledge transfer. **Read all four docs before
writing code.**

| File | What it contains |
|------|------------------|
| `00-README-HANDOFF.md` | This file — orientation, goal, how to work, how to test. |
| `01-REVERSE-ENGINEERING.md` | Everything reverse-engineered about the CFMoto bike: QR, Wi-Fi, the PXC/EasyConn protocol (control + media planes), BLE, exact byte formats, cmd IDs, verified values. |
| `02-CURRENT-APP.md` | The current OpenCfMoto app: every source file, what works today, how to build/run/debug. |
| `03-PLAN-ANDROID-AUTO.md` | The task you are to implement: embed a full Android Auto head-unit receiver so Google Maps/Waze (via Android Auto) render on the bike. Milestones, HUR internals, risks, decisions. |

## The one-paragraph story so far

The CFMoto motorcycle has a ~5" 800×386 landscape dashboard ("MotoPlay") that the official app
(`com.cfmoto.cfmotointernational`) streams a map/UI to over Wi-Fi using the Carbit **EasyConn / PXC**
protocol. That protocol has been fully mapped out and confirmed against the real bike, and built into
**OpenCfMoto**, a clean-room Kotlin app that already connects to the bike and streams H.264 video to
the dash (both a custom "Hello World" UI and a full phone-screen mirror work end-to-end on real
hardware). The remaining goal is to put **real navigation apps (Google Maps / Waze)** on the dash by
embedding an **Android Auto head-unit receiver** in the app.

## The goal you are implementing (Route "A-full")

Embed a full **Android Auto Projection (AAP) head-unit receiver** into OpenCfMoto so that:

1. Google's Android Auto app on the phone projects its UI (Maps/Waze in "Auto" mode) to our in-app
   receiver via **non-VPN loopback "self mode"** (127.0.0.1 — no root, no VPN).
2. We take the decoded AA video and feed it into the **already-working PXC pipeline** that streams
   H.264 to the bike (see `02-CURRENT-APP.md` / `EasyConnProber.kt` + `VideoPipeline.kt`).
3. Result: the phone can be used (or locked) while the bike shows live Android Auto navigation.

The reference implementation to port from is **headunit-revived (HUR)** — an open-source AAP head-unit
emulator. Full plan in `03-PLAN-ANDROID-AUTO.md`.

## Ground rules & decisions already made (do not re-litigate)

- **License: AGPLv3 is accepted.** The owner will release OpenCfMoto's source under AGPLv3 (same as
  HUR). You may freely port/adapt HUR code. Preserve HUR's copyright headers.
- **Phone is the SERVER** in the bike protocol (the bike connects back to us). This is settled and
  verified — do not try the "outbound channel-select" model (it was a wrong early theory).
- **The bike speaks standard Car PXC with JSON `CLIENT_INFO`** — NOT the MCULite/ECTinyPlus protobuf
  variant (that was a wrong detour; ignore any protobuf-CLIENT_INFO notion).
- **BLE is NOT required** for MotoPlay projection (it's for vehicle lock/telemetry). The BLE code in
  the app is dormant; leave it.
- **Use the non-VPN "Wi-Fi loopback" self-mode** for Android Auto. The "Fake VPN offline" self-mode
  installs a default-route VPN that **breaks the bike networking** (the bike's inbound connect-back is
  swallowed). This was verified the hard way. See `01` and `03`.
- **Target/test device is a non-rooted phone.** Do not design anything that requires root.

## How to test (important — most of this needs real hardware)

- **The bike is required** to test the PXC path end-to-end. The owner runs bike tests and returns logs.
  You cannot self-test against the bike. Design so that a single bike session yields a diagnosable log.
- The app has an **on-screen log view** and a **Share** button that exports the log to a file — every
  meaningful step must `log(...)` so a captured log is self-explanatory. Follow this convention.
- If a wire-format detail proves uncertain in practice, resolve it with a live bike test session and
  verbose logging around the step in question (see `01` §8).
- Keep changes **incremental and independently testable** — the owner's workflow is "you implement, they
  run one bike/phone test, they paste the log, you adjust." Optimize for that loop.

## Coding conventions (match the existing app)

- Kotlin, `minSdk 29`, `targetSdk 36`, package `dev.zanderp.opencfmoto`.
- Everything user-visible/diagnostic goes through the `log: (String) -> Unit` callback threaded into
  each component (see `EasyConnProber`, `VideoPipeline`). Prefix logs with a stage tag, e.g. `[AA]`,
  `[VIDEO]`, `[:10922]`.
- No root. No system/privileged APIs. Foreground services for anything that must survive backgrounding.
- Little networking helper classes per concern (see the existing `Pxc*`, `Ble*` split). Keep the
  bike/PXC code untouched except where you swap the video source.

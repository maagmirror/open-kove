# OpenCfMoto — Ideas & Possible Issues (round 2)

A second research pass, digging into areas the first round (`06-OPTIMIZATION-IDEAS.md`) didn't touch:
the **connection/PXC layer**, **Wi-Fi handling**, **audio/mic**, **sensors**, **BLE**, and overall
**code health** — plus some deliberately bold new directions. Grounded in a full read of
`EasyConnProber`, `AaMicrophone`, `ServiceDiscoveryResponse`, `AapControl`, `BikeWifi`, and friends.

**Nothing here is implemented** — it's a menu to prioritise. Each item cites the code it's about and
rates rough impact / effort / risk. Ideas 1, 2, 5 from doc 06 already shipped (v1.0.10); doc 06's
ideas 3 (latency) and 4 (instant single-press) are still open and complement several items below.

---

## Part A — Concrete issues & quick improvements

### A1 ★ No Wi-Fi lock → avoidable stream jitter, worst exactly when riding

> **✅ Implemented (v1.0.10).** `AndroidAutoService` now holds two Wi-Fi locks for the streaming
> session — **`WIFI_MODE_FULL_HIGH_PERF`** (keeps Wi-Fi out of power-save regardless of screen state,
> the screen-off workhorse) **and `WIFI_MODE_FULL_LOW_LATENCY`** (extra latency reduction while
> foreground + screen-on). Acquired with the wake lock (`reacquireLocks`), released on park/stop.
> Note: `LOW_LATENCY` alone would **not** cover screen-off riding — it's active only foreground +
> screen-on ([AOSP Wi-Fi low-latency docs][aosp-ll]) — which is exactly why both are held.

[aosp-ll]: https://source.android.com/docs/core/connect/wifi-low-latency

**The gap.** The app streams video over the bike's Wi-Fi and explicitly tells riders to **turn the
phone screen off** while riding — but it never acquires a `WifiManager.WifiLock`. With the screen off,
Android's Wi-Fi power-save aggressively parks the radio between beacons, adding latency and jitter and
sometimes throttling throughput. That's precisely the condition the whole app runs in.

**Evidence.** A repo-wide search finds only a BLE scan mode — **no `WifiLock` / `WIFI_MODE_*`
anywhere.** `BikeWifi` binds the process to the bike network but takes no lock; `EasyConnProber` takes
a `MulticastLock` only ([EasyConnProber.kt:570](../app/src/main/java/dev/zanderp/opencfmoto/EasyConnProber.kt#L570)).

**Proposal.** Hold a `WifiLock` with **`WIFI_MODE_FULL_LOW_LATENCY`** (added API 29 — the app's
`minSdk`) for the duration of a streaming session, acquired next to the existing partial wake lock in
`AndroidAutoService`, and released on park/stop. Low-latency mode disables power-save *and* asks the
firmware to prioritise latency while the app is foregrounded/holding the lock.

**Impact:** smoother stream + snappier touch, especially screen-off — the single highest-value, lowest-
risk item here. **Effort:** small. **Risk:** low (slightly more Wi-Fi radio power; already gated to a
live session, and it *reduces* re-transmit/stall overhead). Pairs naturally with doc 06's adaptive
tuning and latency ideas.

### A2 — "Hacked by Coletz :P" placeholder renders to the dash

The own-content Presentation path draws literal `"Hacked by Coletz :P"` + a `"frame tick N"` ticker
onto the bike screen ([VideoPipeline.kt:241](../app/src/main/java/dev/zanderp/opencfmoto/VideoPipeline.kt#L241)).
It's leftover from the pre-AA proof-of-concept and only shows when neither AA nor mirror is the source,
but it *is* shipped code that can appear on a motorcycle dash. Replace with a neutral OpenCfMoto
standby card (logo + connection state), or delete the path if own-content mode is dead.
**Effort:** trivial. **Risk:** none. (Polish, but it's the kind of thing that ends up in a screenshot.)

### A3 — Thin automated test coverage on the fragile, pure code

Only `AdaptivePolicyTest` + the example test exist. Several **pure, unit-testable** pieces are exactly
the ones with a history of subtle breakage, and would be cheap to lock down:
- `PxcFrame` framing + the `magic = cmdType XOR totalLength` check — the docs note the bike **drops the
  connection** on a bad magic ([04-APP-KNOWLEDGE-BASE.md §4.1]).
- The H.264 `SpsParser` in `VideoDecoder` — dimension parsing sits on the black-screen fault line.
- `AaCompositor.mapCanvasToSource()` letterbox inverse — touch accuracy across FIT/FILL/STRETCH
  ([AaCompositor.kt:231](../app/src/main/java/dev/zanderp/opencfmoto/AaCompositor.kt#L231)).
- The `MediaButtonBridge` double-tap / long-press state machine (recently reworked in 1.0.8).

**Impact:** regression insurance on the code most likely to regress. **Effort:** small–medium.
**Risk:** none.

### A4 — Media data socket blocks up to 1.5 s per pull

On each `REQ_RV_DATA_NEXT(114)` the media thread calls `pollFrame(1500)`, blocking up to 1.5 s
([EasyConnProber.kt:443](../app/src/main/java/dev/zanderp/opencfmoto/EasyConnProber.kt#L443)). Now that
idle throttle (doc 06 idea 5) drops static output to ~1 fps, a still screen can make the bike wait
~0.9–1.5 s for a frame. It still works (the idle repeat delivers within the window), but it's worth a
quick on-bike check that the raised idle-repeat and this timeout stay comfortably inside the ~9 s
media-socket timeout, and logging `no frame ready` occurrences. **Effort:** small (measure first).
**Risk:** low — flagged so idea-5's on-bike verification also covers it.

### A5 — Port-conflict error could self-heal

When the official CFMoto/EasyConnect app holds ports 10920–10922, the prober fails fast with a good
message ([EasyConnProber.kt:125](../app/src/main/java/dev/zanderp/opencfmoto/EasyConnProber.kt#L125)).
Nice, but it dead-ends until the rider manually force-stops that app. Since the app already knows how
to deep-link to App settings (README troubleshooting), the ERROR state could offer a one-tap
"force-stop the CFMoto app" action and **auto-retry the bind** once the ports free — turning a manual
recovery into one tap. **Effort:** small–medium. **Risk:** low.

### A6 — Hardcoded BLE pairing secrets (dormant, but a footgun if promoted)

`BleSecrets` ships AES-256 pairing keys with a `TODO: replace with a runtime loader`
([BleSecrets.kt:7](../app/src/main/java/dev/zanderp/opencfmoto/BleSecrets.kt#L7)). The BLE wake-up path
is dormant, so it's harmless today — but if BLE wake is ever promoted, hardcoded keys mean it only
works for the bikes those keys match, and bakes secrets into an open repo. If/when it's revived, load
keys at runtime (e.g. from the official app's store) instead. **Effort:** medium. **Risk:** n/a while
dormant — noted so it isn't forgotten.

### A7 — R8 / resource shrinking off for release

Carried over from doc 06's appendix: `isMinifyEnabled = false`
([app/build.gradle.kts](../app/build.gradle.kts)). Enabling R8 + `shrinkResources` shrinks the
sideloaded APK and strips debug logging in release, but needs keep-rules for protobuf, Conscrypt, and
the reflective `AaSelfMode`. **Effort:** small–medium (keep rules). **Risk:** medium — test the full
connect flow after enabling.

---

## Part B — Bold new directions

### B1 ★★ Turn the letterbox bars into a rider HUD (telemetry overlay)

**The insight.** Android Auto renders at a fixed 16:9-ish resolution; the bike dash is a different
shape; so the compositor **letterboxes** AA into the canvas, leaving black bars (e.g. a portrait
1000 MT-X, or any FIT-mode dash). Those bars are wasted pixels on a screen the rider stares at.

**The idea.** The GL `AaCompositor` already composites the AA texture into a viewport and *already*
supports a second render target (the in-app preview). Extend it to draw an **OpenCfMoto telemetry
strip** into the otherwise-black margin: GPS speed, trip distance/time, a clock, phone battery + a hot-
phone warning, and a glanceable next-turn. Every value already exists — `TripRecorder` has live
speed/distance, and the adaptive controller now knows thermal state. This is a genuinely novel,
motorcycle-specific dash that *no* stock Android Auto head unit offers, built almost entirely on
plumbing that's already there.

**Impact:** flagship differentiator; makes the mismatched-aspect dashes (the majority) strictly better
instead of "letterboxed." **Effort:** medium (GL text/quad rendering + a data feed into the
compositor). **Risk:** low–medium (must not steal frame budget from the map; render the strip only when
values change, reusing idea-5's idle logic).

### B2 ★★ Crash detection + SOS

A motorcycle safety feature with **zero Android Auto dependency**. The phone already has the sensors
and (with trip logging) location. Watch the accelerometer/GPS for a crash signature — sharp
deceleration + orientation flip + a sustained stop — then show a **cancelable countdown** on the dash /
phone; if the rider doesn't cancel, surface an SOS with the last known GPS coordinates (share-sheet or
a pre-set emergency contact — the actual send stays rider-initiated). Fits the existing ride-logging
theme and the "we're a riding app, not just a projector" identity.

**Impact:** high — a headline, trust-building feature riders genuinely value. **Effort:** medium
(sensor fusion + a tunable detector + UI). **Risk:** medium — false positives must be easy to dismiss;
be explicit it's best-effort and never a substitute for a real crash beacon. Keep it opt-in.

### B3 ★ Feed the phone's GPS as an AA head-unit sensor

The AAP **SENSOR** channel is rich — `Sensors.java` carries `SensorBatch` with location, speed,
odometer, RPM, fuel, etc. — but today the head unit only advertises `DRIVING_STATUS` and `NIGHT`, and
`DRIVING_STATUS` is sent **once** as `UNRESTRICTED`
([AapControl.kt:297](../app/src/main/java/dev/zanderp/opencfmoto/aa/AapControl.kt#L297),
[ServiceDiscoveryResponse.kt:44](../app/src/main/java/dev/zanderp/opencfmoto/aa/ServiceDiscoveryResponse.kt#L44)).
Advertising and feeding a **LOCATION/speed sensor** from the phone's fused location could steady Maps'
positioning (tunnels, urban canyons) and unlock an accurate in-map speed readout. Scoped and protocol-
careful, but the proto support is already generated. **Impact:** medium. **Effort:** medium. **Risk:**
medium (keep `DRIVING_STATUS` UNRESTRICTED — deliberately chosen so the dash stays fully interactive).

### B4 — Localization (reach CFMoto's actual markets) ✅ in progress

UI strings live in `values/strings.xml` with draft locales for Discord languages
(`values-de` / `it` / `fr` / `es` / `pt` / `pl` / `cs` / `ro` / `nl`) and `localeConfig` for per-app
language on Android 13+. Community polish via PRs — see `docs/TRANSLATIONS.md`. Remaining: GPX
turn-by-turn / TTS catalog and scattered Toasts. **Impact:** high (reach). **Effort:** ongoing.
**Risk:** low.

### B5 — "It just knows" context automation

Small orchestrations that make the app feel smart, composing features that already exist: auto-enable
handlebar-button mode when a **non-touch** profile connects; auto-apply the bike's saved map theme;
optionally auto-launch a saved "home" navigation on connect (the `NavLauncher` + `SavedPlaces` +
per-bike settings are all there). Each is a few lines of glue in the connect path. **Impact:** medium
(polish/delight). **Effort:** small. **Risk:** low (all opt-in/toggleable).

---

## Suggested priority

| # | Item | Type | Impact | Effort | Risk |
|---|---|---|---|---|---|
| A1 | **Wi-Fi high-perf + low-latency lock** ✅ done (v1.0.10) | Fix/perf | High | Small | Low |
| B1 | **Telemetry HUD in the letterbox bars** | Feature | High (flagship) | Medium | Low–Med |
| A2 | Remove "Hacked by Coletz" placeholder | Cleanup | Low | Trivial | None |
| B2 | Crash detection + SOS | Feature | High | Medium | Medium |
| A3 | Unit-test the fragile pure code | Health | Med | Small–Med | None |
| B4 | Localization ✅ extract + draft locales | Reach | High | Ongoing | Low |
| A5 | Self-healing port-conflict recovery | UX | Med | Small–Med | Low |
| B3 | GPS-as-AA-sensor | Feature | Med | Medium | Medium |
| B5 | Context automation on connect | Polish | Med | Small | Low |
| A7 | R8 / resource shrink | Build | Low–Med | Small–Med | Med |
| A4 | Cap/measure pollFrame block | Perf | Low | Small | Low |
| A6 | BLE runtime key loader (if revived) | Security | — | Medium | n/a |

**If I were picking two to do next:** **A1 (Wi-Fi low-latency lock)** — the best effort-to-impact ratio
in the whole list, and it reinforces the adaptive work just shipped — and **B1 (telemetry HUD)** as the
flagship feature, since the GL and data plumbing it needs already exist. Doc 06's **idea 4 (instant
single-press)** remains the cheapest UX win for non-touch bikes and would slot in alongside A1.
</content>

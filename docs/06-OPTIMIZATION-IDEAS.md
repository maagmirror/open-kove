# OpenCfMoto — Optimization & Android Auto Behaviour Ideas

Research notes proposing concrete improvements to app performance, battery/heat, and Android Auto
(AA) runtime behaviour. Written from a full read of the codebase (video pipeline, AA receiver,
compositor, handlebar-button bridge, foreground service). **No code has been changed** — this is a
proposal for discussion/prioritisation.

Read alongside:
- `04-APP-KNOWLEDGE-BASE.md` — architecture & protocol
- `05-DEBUG-KNOWLEDGE.md` — runtime flow & the resolved black-screen bugs

---

## The video/AA path today (baseline for these ideas)

```
Google AA ──H.264──▶ VideoDecoder ──▶ SurfaceTexture ──▶ AaCompositor (GL letterbox)
   (loopback :5288)     (decode)         (texture)          │ throttled to PowerMode fps
                                                            ▼
                                              VideoPipeline encoder (H.264 800x384-ish)
                                                            │
                                                            ▼
                                                 frameQueue (LinkedBlockingDeque, cap 8)
                                                            │  bike pulls 1 per REQ_RV_DATA_NEXT(114)
                                                            ▼
                                              EasyConnProber :10920 ──▶ dash
```

Key tunables already in place: `PowerMode` fps cap (compositor), `VideoQuality` bitrate multiplier,
`KEY_REPEAT_PREVIOUS_FRAME_AFTER = 100 ms` (encoder), compositor `IDLE_REDRAW_MS = 2000`,
`frameQueue` capacity 8, double-tap / long-press timing prefs.

References: [VideoPipeline.kt](../app/src/main/java/dev/zanderp/opencfmoto/VideoPipeline.kt),
[AaCompositor.kt](../app/src/main/java/dev/zanderp/opencfmoto/AaCompositor.kt),
[VideoPrefs.kt](../app/src/main/java/dev/zanderp/opencfmoto/VideoPrefs.kt),
[MediaButtonBridge.kt](../app/src/main/java/dev/zanderp/opencfmoto/MediaButtonBridge.kt).

---

## Idea 1 — Thermal-adaptive frame rate & bitrate (auto Power mode)

> **✅ Implemented (v1.0.10).** Shipped as `PowerMode.AUTO` (now the default). Thermal status is polled
> each watchdog tick and mapped to a bitrate factor + fps cap by `AdaptivePolicy`; applied live via
> `VideoPipeline.setEncoderBitrate` / `setFrameCap`. See `AdaptiveVideoController.kt`.

**Problem.** Heat/battery is the headline limitation, called out repeatedly in the README ("this app
runs a live video transcoder, so it always draws power and warms the phone"). Today the mitigation is
a *manual* `PowerMode` choice (Smooth 30 / Balanced 24 / Saver 20 fps) that the rider sets once and
forgets. On a long summer ride the phone can thermally throttle — at which point encode/Wi-Fi slow
down and the dash stutters — and the app never reacts.

**Evidence.**
- `PowerMode` is a static user pick, applied once via `AaCompositor.setFrameCap()`
  ([VideoPrefs.kt:39](../app/src/main/java/dev/zanderp/opencfmoto/VideoPrefs.kt#L39),
  [VideoPipeline.kt:184](../app/src/main/java/dev/zanderp/opencfmoto/VideoPipeline.kt#L184)).
- Nothing reads Android's thermal signals anywhere in the tree.

**Proposal.** Add an optional "Auto" power mode that listens to
`PowerManager.addThermalStatusListener()` (API 29+, already `minSdk`) and/or polls
`getThermalHeadroom()`, and steps the compositor fps cap (and optionally the encoder bitrate) down as
the device heats:

| Thermal status | fps cap | bitrate |
|---|---|---|
| NONE / LIGHT | user's chosen cap | 100% |
| MODERATE | 20 | 80% |
| SEVERE+ | 15 | 60% |

`AaCompositor.setFrameCap()` already supports live changes; bitrate needs
`MediaCodec.setParameters(PARAMETER_KEY_VIDEO_BITRATE)` (no re-create). Recovery is symmetric as the
device cools.

**Impact.** Directly attacks the #1 user-visible limitation. Keeps the dash *smooth-at-a-lower-rate*
instead of stuttering when hot, and extends how long a phone can project before it's uncomfortable.
**Effort:** small–medium. **Risk:** low (falls back to current static behaviour; gated behind a new
"Auto" enum value so existing choices are untouched).

---

## Idea 2 — Adaptive bitrate from link-health feedback

> **✅ Implemented (v1.0.10).** Under `PowerMode.AUTO`, `AdaptivePolicy` runs an AIMD loop on the
> `VideoPipeline` dropped-frame counter (queue-full events) each watchdog tick: multiplicative
> back-off on congestion, additive recovery when clean, floored at 600 kbps. The more aggressive of
> the thermal/link factors wins. See `AdaptiveVideoController.kt` + `AdaptivePolicyTest.kt`.

**Problem.** Bitrate is fixed for a session (`profile.videoBitrate × VideoQuality.multiplier`). The
README admits "in poor Wi-Fi you may hit the occasional hiccup and need a Stop → Connect." The bike
↔ phone Wi-Fi degrades with distance/obstruction, but the encoder keeps trying to push the same
2.5 Mbps, causing frame backlog, drops, and eventually the 9 s media-socket timeout → full drop.

**Evidence.**
- Bitrate chosen once at encoder create; never revised
  ([VideoPipeline.kt:118](../app/src/main/java/dev/zanderp/opencfmoto/VideoPipeline.kt#L118)).
- We already have two cheap link-health signals we don't use for control:
  - `frameQueue` drop-oldest events (encoder outrunning the bike pull rate = downstream congestion)
    ([VideoPipeline.kt:285](../app/src/main/java/dev/zanderp/opencfmoto/VideoPipeline.kt#L285)).
  - `EasyConnProber.msSinceLastFrame()` / pull cadence on `REQ_RV_DATA_NEXT`
    ([EasyConnProber.kt:170](../app/src/main/java/dev/zanderp/opencfmoto/EasyConnProber.kt#L170),
    [:442](../app/src/main/java/dev/zanderp/opencfmoto/EasyConnProber.kt#L442)).

**Proposal.** A lightweight controller (in the existing watchdog tick, every 5 s) that watches
queue-drop rate and pull cadence and nudges the encoder bitrate via
`setParameters(PARAMETER_KEY_VIDEO_BITRATE)`:
- Sustained queue drops or pull cadence falling below the fps cap → step bitrate **down** (e.g.
  −20%, floor ~800 kbps) and request a keyframe.
- Clean streaming for N seconds → step **up** toward the configured target (AIMD, like TCP).

This is a control loop over signals the app already computes — no new protocol, no re-create of the
codec.

**Impact.** Fewer full drops in marginal Wi-Fi (further from the bike, urban interference), which is
the most common "it hiccuped" complaint. Graceful degradation (softer picture) instead of a hard
disconnect. **Effort:** medium. **Risk:** medium — needs bike-test tuning of thresholds; keep it
behind the existing Auto-recovery setting and log every bitrate change so sessions stay diagnosable.

---

## Idea 3 — Cut glass-to-glass latency (video + touch round-trip)

**Problem.** The pipeline has several buffering stages, and the bike **pulls** frames one at a time.
The `frameQueue` holds up to 8 encoded access units and, when full, drops the *oldest* while keeping
the newest 8 — so a bike that pulls slightly slower than the encoder produces can be handed a frame
up to ~8 frames (≈330 ms at 24 fps) stale. That latency is invisible for steady map panning but hurts
the **dash-touch → AA → re-encode → dash** round trip (pinch/scroll feel) and turn-prompt timeliness.

**Evidence.**
- `frameQueue = LinkedBlockingDeque(8)`; drop-oldest-keep-newest on overflow
  ([VideoPipeline.kt:66](../app/src/main/java/dev/zanderp/opencfmoto/VideoPipeline.kt#L66),
  [:285](../app/src/main/java/dev/zanderp/opencfmoto/VideoPipeline.kt#L285)).
- Touch travels dash → PXC cmdType 32 → compositor inverse-map → AAP INPUT → Gearhead, then the
  visual reply travels the whole video path back
  ([AaInput.kt](../app/src/main/java/dev/zanderp/opencfmoto/aa/AaInput.kt),
  `05-DEBUG-KNOWLEDGE.md` §2e).

**Proposal (measure first, then tune).**
1. Reduce `frameQueue` capacity to **2–3**. With drop-oldest already in place this bounds staleness
   to ~1–2 frames while still absorbing pull jitter. (`KEY_LATENCY = 1` is already set, so the
   encoder side is already low-latency —
   [VideoPipeline.kt:131](../app/src/main/java/dev/zanderp/opencfmoto/VideoPipeline.kt#L131).)
2. Add a one-line "age of served frame" / queue-depth counter to the log so latency is measurable on
   a real bike before/after.
3. Optionally prioritise a fresh keyframe after a touch gesture so scroll/zoom snaps.

**Impact.** Snappier touch on the CFDL26 touch dashes and slightly fresher navigation, plus a small
memory win. **Effort:** small (capacity + logging) / medium (touch-triggered sync). **Risk:** low —
smaller queue could in theory increase `no frame ready` under bursty pulls; the added logging tells
us immediately, and the value is a one-line revert.

---

## Idea 4 — Instant handlebar single-press when no double-tap is mapped

**Problem.** Every single handlebar press is delayed by the double-tap window
(`ButtonTimingPrefs.doubleTapMs`, 200–450 ms) because the detector must wait to see whether a second
tap arrives. On a non-touch dash (450SR etc.) where the buttons are the *only* way to drive AA, that
delay is felt on **every** navigation click — it makes the whole UI feel laggy.

**Evidence.**
- `detectDoubleTap()` always schedules the single press for `doubleTapMs` later unless a coalesced
  double is detected
  ([MediaButtonBridge.kt:375](../app/src/main/java/dev/zanderp/opencfmoto/MediaButtonBridge.kt#L375)).
- The double-tap variant of a gesture is frequently mapped to `NONE` (do-nothing) or left default —
  the mapping is fully user-configurable
  ([ButtonMap.kt](../app/src/main/java/dev/zanderp/opencfmoto/ButtonMap.kt),
  [ButtonMappingActivity.kt](../app/src/main/java/dev/zanderp/opencfmoto/ButtonMappingActivity.kt)).

**Proposal.** Before arming the deferred single, check whether the *double* gesture actually resolves
to a real action (`ButtonMap.get(...) != NONE`). If it doesn't, **fire the single immediately** — no
window to wait for, because there's nothing to disambiguate against. Keep the current deferred path
only when a double-tap action is genuinely mapped. (The coalesced-volume `forceDouble` fast path is
unaffected.)

**Impact.** Instant, native-feeling button response for the common case, especially on the non-touch
bikes that depend on buttons entirely — arguably the single biggest *perceived* responsiveness win
for those riders. **Effort:** small. **Risk:** low; purely a latency optimisation of existing logic,
and the safe (deferred) path stays for anyone who maps a double action.

---

## Idea 5 — Idle-aware encoder to cut static-screen heat further

> **✅ Implemented (v1.0.10).** The encoder's `KEY_REPEAT_PREVIOUS_FRAME_AFTER` floor was raised from
> 100 ms (≈10 fps) to `IDLE_ENCODER_REPEAT_US = 900 ms` (≈1 fps), so a static map is no longer
> re-encoded ~10× more than needed. `AaCompositor`'s ~2 s idle redraw remains the primary keep-alive;
> the encoder repeat is now just a backstop under the bike's ~9 s timeout. Both stay well within the
> timeout. Delivered as a constant change (the repeat interval is configure-time, not live-settable).

**Problem.** When the map is static (stopped at lights, straight highway with no re-draw), the
compositor already backs off to a ~2 s idle redraw — a deliberate battery win over the old 15 fps
floor. But the **encoder** is still told to repeat the previous frame every 100 ms
(`KEY_REPEAT_PREVIOUS_FRAME_AFTER = 100_000`), so it keeps emitting ~10 fps of (near-identical)
output that gets encoded, queued, and Wi-Fi-transmitted even when nothing is moving. The two idle
strategies aren't coordinated.

**Evidence.**
- Compositor idle redraw: `IDLE_REDRAW_MS = 2000`
  ([AaCompositor.kt:100](../app/src/main/java/dev/zanderp/opencfmoto/AaCompositor.kt#L100)).
- Encoder repeat floor: `KEY_REPEAT_PREVIOUS_FRAME_AFTER = 100_000L` → ≥10 fps even when static
  ([VideoPipeline.kt:125](../app/src/main/java/dev/zanderp/opencfmoto/VideoPipeline.kt#L125)).
- The bike only needs a frame roughly every ~9 s (`socketTimeoutPeriodWifi`) to hold the link
  (`05-DEBUG-KNOWLEDGE.md` §2f, AaCompositor comment at :96).

**Proposal.** Coordinate the two: when the compositor has been in idle-redraw for a while (no live AA
frames), raise the encoder's repeat interval toward the compositor's idle cadence via
`setParameters()` (or simply rely on the compositor's own ~2 s redraws driving the surface and lift
the 100 ms floor once steady). The safety keyframe/`onBikeDataStart()` logic is unchanged, and the
9 s timeout is respected with wide margin. On live motion, snap back to the normal cadence.

**Impact.** Meaningful heat/battery reduction during the large fraction of a ride where the map isn't
moving, on top of the existing compositor win. Complements Idea 1 (thermal) and Idea 2 (bitrate).
**Effort:** medium. **Risk:** medium — must be careful not to reintroduce the 9 s media-socket
timeout drop; requires a bike test confirming the link survives long static stretches. Log the idle
transitions so a regression is obvious.

---

## Appendix — quick wins (lower effort, worth batching)

- **Enable R8 for release.** `isMinifyEnabled = false` in
  [app/build.gradle.kts:25](../app/build.gradle.kts#L25). Turning on minify + `shrinkResources`
  (with keep rules for protobuf/Conscrypt/reflection paths) shrinks the sideloaded APK and can
  slightly improve startup/runtime. Needs a keep-rule pass because the app uses reflection
  (`AaSelfMode`) and protobuf — test thoroughly before release. *Effort: small; Risk: medium (keep
  rules).* 
- **Pre-warm the encoder to cut connect time.** The AA encoder is created lazily at
  `REQ_CONFIG_CAPTURE`; for a *recognized* bike the canvas size is already known from the profile, so
  the encoder could be created a beat earlier to shorten the black-to-map gap on connect.
- **Surface link quality in the UI.** Even before adaptive bitrate (Idea 2), showing the pull
  cadence / dropped-frame count in the Dash view or Logs helps riders self-diagnose "why is it
  hiccuping" and gives us the telemetry to tune Ideas 2/3.
- **Single "Auto" umbrella.** Ideas 1–2 could ship as one rider-facing "Auto (adapts to heat &
  signal)" power option that internally drives both the thermal fps step-down and the bitrate loop —
  fewer knobs, better defaults.

---

## Suggested priority

| # | Idea | Impact | Effort | Risk | Notes |
|---|---|---|---|---|---|
| 1 | Thermal-adaptive fps/bitrate | High | Small–Med | Low | ✅ Done (v1.0.10) — `PowerMode.AUTO` |
| 2 | Adaptive bitrate (link) | High | Medium | Medium | ✅ Done (v1.0.10) — needs bike-test tuning |
| 5 | Idle encoder throttle | Medium (battery) | Medium | Medium | ✅ Done (v1.0.10) — watch 9 s timeout on-bike |
| 4 | Instant single-press | High (non-touch bikes) | Small | Low | Best effort:impact ratio |
| 3 | Lower latency (queue + touch) | Medium | Small–Med | Low | Measure first |

Ideas 1, 2 and 5 shipped together (v1.0.10) — they share plumbing: live `MediaCodec.setParameters` on
the encoder (`VideoPipeline.setEncoderBitrate` / `setFrameCap`) driven from the existing
`AndroidAutoService` watchdog tick, with the decision math isolated in the pure, unit-tested
`AdaptivePolicy`. Ideas 3 and 4 remain — independent, low-risk latency wins.

### On-bike verification checklist (for the next real ride)

The adaptation math is unit-tested, but the thresholds want a hardware pass. Watch the shared log for:
- `[adaptive] thermal=… drops/tick=… link=… → NNNNkbps` — confirms AUTO is live and reacting.
- On a long **static** stretch, the bike stays connected (no `media closed / bike closed`) with the
  raised idle repeat — the key idea-5 safety check against the ~9 s media-socket timeout.
- In **marginal Wi-Fi** (ride away from the bike), bitrate steps down and recovers rather than the
  projection dropping. Tune `AdaptivePolicy.DROP_CONGESTION_THRESHOLD` / `LINK_*` if it's too eager or
  too slow.
- When the phone gets **hot**, fps/bitrate drop (thermal branch). If it never triggers, the device may
  report thermal status conservatively — consider `getThermalHeadroom()` (API 30) as a finer signal.
</content>
</invoke>

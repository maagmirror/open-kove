package dev.zanderp.opencfmoto

import android.annotation.SuppressLint
import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Surface
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import android.widget.FrameLayout

/**
 * H.264 video pipeline for MotoPlay mirroring.
 *
 *   VirtualDisplay  ──hosts──▶  Presentation ("Hello World", live clock)
 *        │ output Surface = encoder input
 *        ▼
 *   MediaCodec (video/avc, 800x384)  ──encoded access units──▶  frameQueue
 *
 * The data socket pulls one frame per REQ_RV_DATA_NEXT(114) via [pollFrame].
 * Frames are Annex-B; SPS/PPS (codec config) is prepended to the first keyframe so the
 * decoder on the bike can start.
 *
 * No MediaProjection needed: we render our OWN content onto a private VirtualDisplay
 * (the same approach as MotoPlay's SplitScreenPresentation).
 */
class VideoPipeline(
    private val context: Context,
    private val width: Int,
    private val height: Int,
    private val log: (String) -> Unit,
    /**
     * When true, the pipeline only runs the H.264 encoder and exposes [encoderInputSurface] for
     * an EXTERNAL producer (the Android Auto video decoder) to render into. No Presentation /
     * MediaProjection source is created. See [encoderInputSurface] and AaVideoBridge.
     */
    private val externalSource: Boolean = false,
    /**
     * When true, an [AaCompositor] sits between the AA decoder and the encoder: the decoder renders
     * into [decoderInputSurface] and the compositor letterboxes it (aspect-preserved) into the
     * encoder canvas. The encoder is created lazily in [configureBikeCanvas] once the bike reports
     * its canvas size (so the encoder matches the bike, not a hardcoded resolution).
     */
    private val compositor: Boolean = false,
) {
    private val main = Handler(Looper.getMainLooper())
    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var drainThread: Thread? = null
    private var aaCompositor: AaCompositor? = null
    /** Letterbox scaler for MediaProjection (whole-screen or single-app) → bike encoder. */
    private var mirrorScaler: AaCompositor? = null
    private var projectionCallback: android.media.projection.MediaProjection.Callback? = null
    private var mirrorDisplayListener: DisplayManager.DisplayListener? = null
    private var gpxVoice: GpxVoice? = null
    private var gpxDashUi: GpxDashUi? = null
    private var encoderW = 0
    private var encoderH = 0
    @Volatile private var running = false
    /** True after a successful [start] until [stop]. */
    val isAlive: Boolean get() = running

    // The bitrate the encoder was configured with (the user's target). Adaptive tuning scales DOWN
    // from this; it is the ceiling. 0 until the encoder is created.
    @Volatile private var baseBitrate = 0
    // The bitrate currently applied to the running encoder (via live setParameters).
    @Volatile private var currentBitrate = 0
    // Monotonic count of encoded frames dropped because the queue was full (the bike couldn't pull
    // them fast enough). A rising count is the downstream-congestion signal the adaptive controller
    // uses to back the bitrate off. See [droppedFramesTotal] and [AdaptiveVideoController].
    @Volatile private var droppedFrames = 0L

    private val frameQueue = LinkedBlockingDeque<ByteArray>(8)
    @Volatile private var codecConfig: ByteArray? = null   // SPS/PPS
    @Volatile private var loggedKeyframeHead = false
    @Volatile private var nalLogCount = 0

    /** Summarise the NAL units in an Annex-B access unit: "type(size) …". type 1=slice 5=IDR 6=SEI
     *  7=SPS 8=PPS 9=AUD. Lets us spot SEI/AUD/multi-slice structure the vendor's stream lacks. */
    private fun nalSummary(b: ByteArray): String {
        val out = StringBuilder()
        var i = 0
        var prev = -1
        while (i + 3 < b.size) {
            val sc = (b[i].toInt() == 0 && b[i + 1].toInt() == 0 &&
                (b[i + 2].toInt() == 1 || (b[i + 2].toInt() == 0 && i + 3 < b.size && b[i + 3].toInt() == 1)))
            if (sc) {
                val hdrLen = if (b[i + 2].toInt() == 1) 3 else 4
                val nalType = b[i + hdrLen].toInt() and 0x1F
                if (prev >= 0) out.append("${prevType}(${i - prevStart}) ")
                prevType = nalType; prevStart = i; prev = 1
                i += hdrLen
            } else i++
        }
        if (prev >= 0) out.append("${prevType}(${b.size - prevStart})")
        return out.toString().trim()
    }
    private var prevType = -1
    private var prevStart = 0
    // When set, the drain loop discards encoder output until the next keyframe, so the first frame a
    // freshly-attached bike client receives is a full SPS+PPS+IDR. See onBikeDataStart().
    @Volatile private var awaitKeyframe = false

    // Diagnostic: dump the exact Annex-B H.264 access units we send to the bike into a .h264 file so
    // the stream can be inspected off-device (ffprobe/ffmpeg). Bounded to DUMP_CAP frames so it never
    // grows unbounded or stalls the send path for long. Filename is tagged by source (aa/mirror/own)
    // so an AA capture and a mirror capture can be compared directly. See docs/05-DEBUG-KNOWLEDGE.md.
    private var dumpOut: java.io.OutputStream? = null
    private var dumpFrames = 0
    private var dumpPath: String? = null

    fun start() {
        if (running) return
        running = true

        if (compositor) {
            // Android Auto (letterbox) mode: bring up the compositor now so the AA decoder has an
            // input surface and can reach steady video; the encoder is created later, once the bike
            // tells us its canvas size (see [configureBikeCanvas]).
            aaCompositor = AaCompositor(log).also { it.start() }
            log("[VIDEO] COMPOSITOR mode — decoder input ready; awaiting bike canvas")
            return
        }

        if (!createEncoder(width, height)) { stop(); return }

        if (externalSource) {
            // Android Auto mode: the AA VideoDecoder renders into inputSurface (see
            // encoderInputSurface()). No Presentation/MediaProjection source here.
            log("[VIDEO] EXTERNAL source mode (Android Auto) — encoder input surface ready")
            return
        }

        val projection = ProjectionHolder.projection
        if (projection != null) {
            log("[VIDEO] mirror mode (MediaProjection — whole screen or single app)")
            setupProjectionDisplay(projection)
        } else if (GpxSession.active) {
            log("[VIDEO] GPX viewer mode (Presentation)")
            // Soft AA→Map often runs on the main thread — set up immediately so the first IDR
            // already has map pixels (postponing left the dash on a frozen AA frame).
            if (Looper.myLooper() == Looper.getMainLooper()) setupGpxPresentation()
            else main.post { setupGpxPresentation() }
        } else {
            log("[VIDEO] own-content mode (Presentation)")
            if (Looper.myLooper() == Looper.getMainLooper()) setupDisplayAndPresentation()
            else main.post { setupDisplayAndPresentation() }
        }
    }

    /** Bitrate the dash asked for in REQ_CONFIG_CAPTURE, or null when it left the field at 0. */
    @Volatile var dashRequestedBitrate: Int? = null

    /** Create + start the H.264 encoder at [w]x[h] and its drain thread. Returns false on failure. */
    private fun createEncoder(w: Int, h: Int): Boolean {
        try {
            val profile = BikeProfileHolder.active
            // Map / GPX Presentation changes every pan/GPS tick — give H.264 more bits + shorter GOP
            // so motion doesn't smear into block artifacts on the bike (phone preview skips encode).
            val mapBoost = GpxSession.active
            // REQ_CONFIG_CAPTURE carries the dash's own bitrate at offset 20; the vendor client feeds
            // it straight to MediaFormat (`if (!containsKey("bitrate")) setInteger("bitrate", i5)` in
            // net.easyconn.carman.r0). Honour it when the dash asks, so we don't undershoot a panel
            // that budgeted for more — falling back to our own preference when it asks for nothing.
            val asked = dashRequestedBitrate
            val reqBitrate = if (asked != null && asked > 0) {
                asked
            } else {
                (VideoPrefs.bitrateFor(context, profile) * if (mapBoost) 1.35f else 1f).toInt()
            }
            // Kove: configure what the dash asks for (REQ_CONFIG_CAPTURE carries 4194304), like the
            // vendor's initCodec. The former 1.2Mbps cap assumed big frames were being rejected, but
            // a capture of the vendor on this dash shows frames up to ~90KB, and the first picture
            // from our own stream came at ~4.2Mbps. See docs/08-KOVE-PROJECTION.md.
            val bitrate = if (EasyConnProber.spoofedPackage == EasyConnProber.PKG_EASYRIDE) {
                asked?.takeIf { it > 0 } ?: KOVE_DEFAULT_BITRATE
            } else {
                reqBitrate
            }
            // The vendor's Kove initCodec logs `i-frame-interval=3` (seconds) — match it on the
            // EasyRide path rather than our generic 1s, so our keyframe cadence and GOP structure
            // line up with the stream the dash is built to decode.
            val iframeSec = if (EasyConnProber.spoofedPackage == EasyConnProber.PKG_EASYRIDE) {
                3
            } else if (mapBoost) {
                maxOf(1, profile.videoIFrameIntervalSec / 2)
            } else {
                profile.videoIFrameIntervalSec
            }
            // The vendor's encoder runs at finalFps=36 on this dash (its initCodec log), not 30.
            val encoderFps = if (EasyConnProber.spoofedPackage == EasyConnProber.PKG_EASYRIDE) 36
                             else profile.videoFrameRate
            fun baseFormat() = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, encoderFps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iframeSec)
                // Do NOT set KEY_COLOR_STANDARD/RANGE: decoding the vendor's real SPS shows its VUI
                // colour_primaries=0 and matrix_coefficients=0 (UNSPECIFIED — universally accepted).
                // Setting BT601_PAL made ours colour_primaries=5 (BT.470 System B/G), a value a
                // strict decoder like the Kove's can REJECT. Leaving these unset matches the vendor's
                // unspecified VUI (the ONLY remaining byte difference in our otherwise-identical SPS).
                // Surface-input encoders only emit on new buffers; a STATIC screen (e.g. mirror of
                // an idle app) then produces zero frames and the bike times out. Repeat the last
                // frame if nothing new arrives so output is continuous even when the screen is still.
                // The floor is deliberately LOW frequency: AaCompositor already re-emits a static
                // frame every ~2s (IDLE_REDRAW_MS) to hold the bike's ~9s media-socket timeout, so the
                // encoder only needs a backstop below that timeout — not a 10fps re-encode of an
                // unchanging map, which was pure wasted heat/battery (see docs/06-OPTIMIZATION-IDEAS.md
                // idea 5). At real frame rates the compositor delivers new buffers well before this
                // fires, so it never caps motion.
                // Kove: a floor well under the ~31 frames/s the dash pulls. A floor above that rate
                // overfills the queue and costs frames.
                setLong(
                    MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER,
                    if (EasyConnProber.spoofedPackage == EasyConnProber.PKG_EASYRIDE) KOVE_REPEAT_US
                    else IDLE_ENCODER_REPEAT_US,
                )
                // Vendor mediaFormat: priority=0 (realtime), intra-refresh-period=0, and its qcom QP
                // sets qp-b-enable=0 (no B-frames). Match all three — no reordered frames means the
                // dash decodes strictly in receive order (our wire carries no timestamps).
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                }
                setInteger("intra-refresh-period", 0)
            }
            // The vendor's exact Qualcomm encoder tuning (r0.java, OMX.qcom.video.encoder.avc path):
            // low-latency mode + fixed initial/range QP, no B-frames. On the Nothing Phone 2
            // (Snapdragon) this is what shapes the bitstream the Kove decoder accepts. Applied only
            // when the encoder is a Qualcomm one; unknown keys would fail configure() elsewhere.
            fun MediaFormat.applyQcomTuning(encoderName: String) {
                if (!encoderName.contains("qcom", true) && !encoderName.contains("qti", true)) return
                setInteger("vendor.qti-ext-enc-low-latency.enable", 1)
                // Vendor 1:1 (restored — smaller frames did NOT help, the dash still pulled ~16fps and
                // never displayed, so frame size/decode was not the block).
                setInteger("vendor.qti-ext-enc-initial-qp.qp-i-enable", 1)
                setInteger("vendor.qti-ext-enc-initial-qp.qp-p-enable", 1)
                setInteger("vendor.qti-ext-enc-initial-qp.qp-b-enable", 0)
                setInteger("vendor.qti-ext-enc-initial-qp.qp-i", 18)
                setInteger("vendor.qti-ext-enc-initial-qp.qp-p", 18)
                setInteger("vendor.qti-ext-enc-qp-range.qp-i-min", 18)
                setInteger("vendor.qti-ext-enc-qp-range.qp-i-max", 22)
                setInteger("vendor.qti-ext-enc-qp-range.qp-p-min", 18)
                setInteger("vendor.qti-ext-enc-qp-range.qp-p-max", 22)
            }
            // Some dashes only render the software encoder's stream: the vendor client logs
            // "isUseSoftEncode: true" on a Kove session that projects, and its capture shows no
            // hardware AVC encoder at all. A hardware encoder's output is valid H.264 but differs in
            // NAL packaging/SPS details, which a picky decoder can accept-and-not-display — exactly
            // the symptom of frames flowing to a black dash.
            val c = if (preferSoftwareEncoder) {
                runCatching { MediaCodec.createByCodecName(SOFTWARE_AVC_ENCODER) }
                    .onSuccess { log("[VIDEO] using software encoder $SOFTWARE_AVC_ENCODER") }
                    .onFailure { log("[VIDEO] software encoder unavailable ($it) — using default") }
                    .getOrNull()
                    ?: MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            } else if (EasyConnProber.spoofedPackage == EasyConnProber.PKG_EASYRIDE) {
                // Pick the Qualcomm AVC encoder by name, like the vendor (r0 uses
                // OMX.qcom.video.encoder.avc). The default createEncoderByType can resolve to a
                // Google/soft encoder that ignores KEY_PROFILE and the vendor.qti-* keys, so High
                // profile silently falls back to Baseline. The named Qualcomm encoder honours both.
                (qualcommAvcEncoderName()?.let { name ->
                    runCatching { MediaCodec.createByCodecName(name) }
                        .onSuccess { log("[VIDEO] using Qualcomm encoder $name (like the vendor)") }
                        .getOrNull()
                }) ?: MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            } else {
                MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            }
            val forceBaseline = BikeProfileHolder.active.forceBaseline
            try {
                if (forceBaseline) {
                    // Match the vendor's encoder EXACTLY, per its Kove `initCodec` RUNTIME log:
                    //   inputFormat:{color-format=2135033992, i-frame-interval=3, mime=video/avc,
                    //     width=800, bitrate-mode=2, bitrate=4194304, frame-rate=36.0, height=352}
                    // The decisive point: the vendor sets **NO KEY_PROFILE and NO KEY_LEVEL**. It lets
                    // c2.qti.avc.encoder pick its own default profile/level. We were FORCING High@3.1
                    // (SPS 0x6764001f); the Kove decoder pulls that stream but never displays it —
                    // classic "accept the NALs, refuse to present a High-profile GOP". Because we run
                    // on the SAME phone as the vendor capture (Nothing Phone 2 / A065), leaving profile
                    // and level unset yields the SAME default SPS the vendor emits — the stream the dash
                    // is built to decode. Set ONLY bitrate-mode=CBR (2) on top of baseFormat(); no
                    // profile, no level, no qp/low-latency tuning — exactly the vendor's minimal format.
                    val fmt = baseFormat().apply {
                        setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                    }
                    c.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    log("[VIDEO] configured vendor-exact: NO profile/level (system default), CBR ${bitrate/1000}kbps ${encoderFps}fps iFrame=${iframeSec}s (encoder=${c.name})")
                } else {
                    c.configure(baseFormat(), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    log("[VIDEO] configured default profile (Main/High)")
                }
            } catch (e: Exception) {
                log("[VIDEO] encoder configure failed ($e) — retrying default profile")
                c.reset()
                c.configure(baseFormat(), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
            inputSurface = c.createInputSurface()
            c.start()
            codec = c
            baseBitrate = bitrate; currentBitrate = bitrate
            encoderW = w; encoderH = h
            log("[VIDEO] encoder started ${w}x${h} h264 ${profile.videoFrameRate}fps ${bitrate / 1000}kbps (${VideoPrefs.get(context).name.lowercase()})")
            maybeStartDump()
            if (drainThread == null) drainThread = thread(name = "video-drain", isDaemon = true) { drainLoop() }
            return true
        } catch (e: Exception) {
            log("[VIDEO] createEncoder failed: $e")
            return false
        }
    }

    /**
     * Compositor mode only: create the encoder at the bike's canvas size and point the compositor
     * at it. Called when the bike's REQ_CONFIG_CAPTURE dimensions are known. Idempotent; a later
     * different size is not re-applied live (logged instead).
     */
    fun configureBikeCanvas(w: Int, h: Int) {
        if (!compositor) return
        if (codec != null) {
            if (encoderW != w || encoderH != h) {
                log("[VIDEO] bike canvas changed ${encoderW}x$encoderH → ${w}x$h — live resize unsupported, keeping ${encoderW}x$encoderH")
            }
            return
        }
        if (!createEncoder(w, h)) return
        val src = BikeProfileHolder.aaVideo
        val surf = inputSurface
        if (surf != null) {
            // Kove/BLE build-net: the dash renders a native 800x352 map via MediaProjection; our
            // AA source is 800x480. FILL crops top/bottom, STRETCH distorts — both hand the dash a
            // frame whose content geometry differs from what it expects. Force FIT (letterbox, whole
            // AA frame, black bars) on this path so the encoded picture is undistorted and complete,
            // ruling the aspect handling out as the reason the dash won't render.
            val fit = if (EasyConnProber.spoofedPackage == EasyConnProber.PKG_EASYRIDE) {
                ScreenFit.FIT
            } else {
                VideoPrefs.fit(context)
            }
            val power = VideoPrefs.power(context)
            // Match-panel-aspect: AA drew its UI into (coded - margins); sample only that sub-rect so
            // the aspect-correct content — not the padded coded frame — is mapped onto the bike canvas.
            val margins = BikeProfileHolder.aaContentMargins
            val usableW = (src.width - margins.marginW).coerceIn(16, src.width)
            val usableH = (src.height - margins.marginH).coerceIn(16, src.height)
            // Kove/EasyRide: the dash pulls frames as fast as we produce them, and a cluster unit
            // only commits to display once it sees a steady, high-rate stream. The power-mode cap
            // (24fps balanced) combined with AaCompositor's coalesce-if-too-soon logic silently
            // halves a 30fps AA source to ~15fps (draw-skip-draw), which is exactly the ~16fps the
            // dash was pulling. Uncap the compositor on this path so EVERY AA frame is drawn — the
            // encoder then emits a steady 30fps and the dash pulls 30fps (near the vendor's 36).
            if (EasyConnProber.spoofedPackage == EasyConnProber.PKG_EASYRIDE) {
                aaCompositor?.setFrameCap(60)
            } else {
                aaCompositor?.setFrameCap(power.fps)
            }
            aaCompositor?.setSourceCrop(usableW.toFloat() / src.width, usableH.toFloat() / src.height)
            aaCompositor?.setOutput(surf, w, h, usableW, usableH, fit)
            log("[VIDEO] bike canvas ${w}x$h configured; AA source ${src.width}x${src.height} " +
                "usable ${usableW}x$usableH (margins ${margins.marginW}x${margins.marginH}) → " +
                "fit=$fit power=${power.fps}fps")
        }
    }

    /**
     * Mirror the phone (whole display or a single app on Android 14+) into the bike encoder.
     *
     * Capture goes into an [AaCompositor] SurfaceTexture so [onCapturedContentResize] can retarget
     * the VirtualDisplay to the real capture size (required for single-app sharing — feeding the
     * encoder surface directly at bike size yields black / no frames). The compositor maps into the
     * fixed bike canvas [width]x[height].
     *
     * Single-app capture only produces frames while that app is visible. Staying in OpenCfMoto after
     * picking an app yields `visible=false` and a black dash — [MainActivity] moves our task back.
     */
    private fun setupProjectionDisplay(projection: android.media.projection.MediaProjection) {
        try {
            val encSurf = inputSurface ?: run {
                log("[VIDEO] projection display failed: no encoder surface"); return
            }
            val metrics = context.resources.displayMetrics
            val density = metrics.densityDpi.coerceAtLeast(160)
            // Start the capture buffer at the physical display size; app-capture resize updates it.
            val initW = metrics.widthPixels.coerceAtLeast(width).let { it - (it % 2) }
            val initH = metrics.heightPixels.coerceAtLeast(height).let { it - (it % 2) }

            val scaler = AaCompositor(log).also { it.start(initW, initH) }
            mirrorScaler = scaler
            val captureSurf = scaler.inputSurface ?: run {
                log("[VIDEO] projection display failed: no compositor input"); return
            }
            // Setup ▸ Screen fit (same preference as AA). Prefer Fit for mirror so tall phone /
            // single-app buffers letterbox instead of cropping to a slice.
            fun currentFit() = VideoPrefs.fit(context)
            fun applyMirrorOutput(srcW: Int, srcH: Int, reason: String) {
                val fit = currentFit()
                scaler.updateCaptureSize(srcW, srcH)
                scaler.setOutput(encSurf, width, height, srcW, srcH, fit)
                log("[MIRROR] $reason src=${srcW}x$srcH → canvas ${width}x$height fit=$fit")
            }
            applyMirrorOutput(initW, initH, "initial")
            scaler.setFrameCap(VideoPrefs.power(context).fps)

            var lastCapW = initW
            var lastCapH = initH

            fun onCaptureSize(w: Int, h: Int, reason: String) {
                // Even dims for SurfaceTexture / encoder math. Must use VirtualDisplay.resize —
                // MediaProjection allows only ONE createVirtualDisplay per consent token.
                val rw = w.coerceAtLeast(2).let { it + (it % 2) }
                val rh = h.coerceAtLeast(2).let { it + (it % 2) }
                if (rw == lastCapW && rh == lastCapH && reason == "resize") return
                lastCapW = rw; lastCapH = rh
                log("[MIRROR] app-capture $reason ${w}x$h → VD ${rw}x$rh → canvas ${width}x$height")
                try {
                    virtualDisplay?.resize(rw, rh, density)
                } catch (e: Exception) {
                    log("[MIRROR] VirtualDisplay.resize failed: $e")
                }
                applyMirrorOutput(rw, rh, reason)
            }

            val cb = object : android.media.projection.MediaProjection.Callback() {
                override fun onStop() {
                    log("[MIRROR] MediaProjection stopped")
                    ProjectionService.setKeepScreenOn(context, false)
                }

                override fun onCapturedContentResize(w: Int, h: Int) {
                    onCaptureSize(w, h, "resize")
                }

                override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
                    log("[MIRROR] capture visible=$isVisible")
                    if (!isVisible) {
                        log(
                            "[MIRROR] shared app is in the background — Android sends no frames. " +
                                "Prefer Entire screen for riding, or leave the shared app on top.",
                        )
                    }
                }
            }
            projectionCallback = cb
            // Required on API 34+: register before createVirtualDisplay.
            projection.registerCallback(cb, main)

            val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR
            virtualDisplay = projection.createVirtualDisplay(
                "OpenCfMotoMirror", initW, initH, density, flags, captureSurf, null, main,
            )
            ProjectionService.setKeepScreenOn(context, true)
            // Fold / rotate: re-apply fit if metrics change without a content-resize callback.
            val displayListener = object : DisplayManager.DisplayListener {
                override fun onDisplayAdded(displayId: Int) {}
                override fun onDisplayRemoved(displayId: Int) {}
                override fun onDisplayChanged(displayId: Int) {
                    if (!running) return
                    val m = context.resources.displayMetrics
                    val dw = m.widthPixels.coerceAtLeast(2).let { it - (it % 2) }
                    val dh = m.heightPixels.coerceAtLeast(2).let { it - (it % 2) }
                    // Only nudge when we're still on whole-screen-sized capture (not single-app).
                    if (lastCapW >= dw - 2 || lastCapH >= dh - 2) {
                        onCaptureSize(dw, dh, "display-changed")
                    } else {
                        applyMirrorOutput(lastCapW, lastCapH, "display-changed-refit")
                    }
                }
            }
            mirrorDisplayListener = displayListener
            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            dm.registerDisplayListener(displayListener, main)
            log("[VIDEO] mirroring → capture ${initW}x$initH → bike ${width}x$height (fit=${currentFit()})")
        } catch (e: Exception) {
            log("[VIDEO] projection display failed: $e")
        }
    }

    private fun setupDisplayAndPresentation() {
        try {
            val display = createOwnVirtualDisplay() ?: return
            val pres = Presentation(context, display)
            val root = LinearLayout(pres.context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setBackgroundColor(Color.parseColor("#0D47A1"))
            }
            val title = TextView(pres.context).apply {
                text = "OpenCfMoto"
                setTextColor(Color.WHITE)
                textSize = 28f
                gravity = Gravity.CENTER
            }
            val clock = TextView(pres.context).apply {
                setTextColor(Color.parseColor("#80D8FF"))
                textSize = 20f
                gravity = Gravity.CENTER
            }
            root.addView(title)
            root.addView(clock)
            pres.setContentView(root)
            pres.show()
            presentation = pres
            log("[VIDEO] presentation shown on virtual display")

            val ticker = object : Runnable {
                var n = 0
                override fun run() {
                    if (!running) return
                    clock.text = "frame tick ${n++}"
                    main.postDelayed(this, 100)
                }
            }
            main.post(ticker)
        } catch (e: Exception) {
            log("[VIDEO] display/presentation failed: $e")
        }
    }

    @SuppressLint("MissingPermission")
    private fun setupGpxPresentation() {
        try {
            if (!GpxSession.active) {
                log("[GPX] session inactive — placeholder"); setupDisplayAndPresentation(); return
            }
            if (GpxSession.mode == GpxSession.Mode.GPX && GpxSession.trackFile == null) {
                log("[GPX] no track — placeholder"); setupDisplayAndPresentation(); return
            }
            val display = createOwnVirtualDisplay() ?: return
            val pres = Presentation(context, display)
            // VirtualDisplay Presentation contexts often lack the app theme — AppCompat/Material
            // ?attr/… then fail with InflateException "Error inflating class <unknown>".
            val themed = ContextThemeWrapper(pres.context, R.style.Theme_OpenCfMoto)
            val root = LayoutInflater.from(themed).inflate(R.layout.presentation_gpx, null)
            val ui = GpxDashUi(themed, root, log, isAlive = { running }, projected = true)
            if (!ui.bind()) {
                ui.release()
                setupDisplayAndPresentation()
                return
            }
            gpxDashUi = ui
            gpxVoice = null
            pres.setContentView(root)
            try {
                pres.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } catch (_: Exception) {
            }
            pres.show()
            presentation = pres
            // Screen + FGS wake so phone sleep / background doesn't stall VirtualDisplay.
            AppHttp.ensureCellularUplink()
            AndroidAutoService.setGpxScreenWake(context, true)
            log("[GPX] presentation shown → ${width}x${height}")
        } catch (e: Exception) {
            val cause = generateSequence(e.cause) { it.cause }.lastOrNull()
            log("[GPX] presentation failed: $e${cause?.let { " cause=$it" } ?: ""}")
            try { gpxDashUi?.release() } catch (_: Exception) {}
            gpxDashUi = null
            setupDisplayAndPresentation()
        }
    }

    private fun createOwnVirtualDisplay(): android.view.Display? {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
        // Scale density to the bike resolution so controls stay glanceable without crowding the panel.
        // Higher TARGET_DASH_WIDTH_DP → lower dpi → smaller chrome (~20% vs the old 720dp target).
        val densityDpi = Math.round(160.0 * width / TARGET_DASH_WIDTH_DP).toInt().coerceIn(160, 640)
        val vd = dm.createVirtualDisplay("OpenCfMoto", width, height, densityDpi, inputSurface, flags)
        virtualDisplay = vd
        log("[VIDEO] own display ${width}x${height} @ ${densityDpi}dpi")
        return vd?.display ?: run { log("[VIDEO] virtualDisplay.display null"); null }
    }

    /**
     * Strip a leading H.264 Access Unit Delimiter (NAL type 9). Qualcomm surface encoders emit one
     * (`00 00 00 01 09 XX` or `00 00 01 09 XX`) at the start of each access unit; the vendor's stream
     * has none. Returns the frame unchanged when there is no leading AUD.
     */
    /**
     * Rewrite the SPS VUI colour to match the vendor's exactly: video_full_range_flag 1→0,
     * colour_primaries 5→0, matrix_coefficients 5→0. The surface encoder forces
     * `…6d 37 05 06 05 06 d0 a1 35` and ignores KEY_COLOR_*; the vendor's is
     * `…6d 35 00 06 00 06 d0 a1 35`. Colour is metadata only, so patching the exact 9-byte window
     * makes our SPS byte-identical without touching the coded picture. No-op if the pattern differs.
     */
    private fun patchSpsColorToVendor(cfg: ByteArray): ByteArray {
        // pattern (ours):   6D 37 05 06 05 06 D0 A1 35
        // pattern (vendor): 6D 35 00 06 00 06 D0 A1 35
        val pat = intArrayOf(0x6D, 0x37, 0x05, 0x06, 0x05, 0x06, 0xD0, 0xA1, 0x35)
        var i = 0
        while (i + pat.size <= cfg.size) {
            var match = true
            for (k in pat.indices) if ((cfg[i + k].toInt() and 0xFF) != pat[k]) { match = false; break }
            if (match) {
                val out = cfg.copyOf()
                out[i + 1] = 0x35.toByte()  // full_range 1→0
                out[i + 2] = 0x00.toByte()  // colour_primaries 5→0
                out[i + 4] = 0x00.toByte()  // matrix_coefficients 5→0
                log("[VIDEO] patched SPS VUI colour to vendor (full_range=0, primaries=0)")
                return out
            }
            i++
        }
        return cfg
    }

    /**
     * Rebuild an Annex-B access unit keeping ONLY the NAL types the vendor's stream carries —
     * slice (1), IDR (5), SPS (7), PPS (8) — and dropping AUD (9), SEI (6), filler (12) and anything
     * else. Our Qualcomm SURFACE encoder can prepend an AUD and insert SEI (buffering-period /
     * recovery-point) NALs; the vendor's byte-buffer encoder emits none, and a strict decoder like
     * the Kove's can stall on them. This makes our access units byte-clean like the vendor's.
     */
    private fun filterToEssentialNals(b: ByteArray): ByteArray {
        // collect (start, headerLen) of each NAL
        val nals = ArrayList<IntArray>() // [payloadStart, nalHeaderIndex]
        var i = 0
        val starts = ArrayList<Int>() // index of each start-code first byte
        val hdr = ArrayList<Int>()    // nal header byte index
        while (i + 3 < b.size) {
            val four = b[i].toInt() == 0 && b[i + 1].toInt() == 0 && b[i + 2].toInt() == 0 && b[i + 3].toInt() == 1
            val three = b[i].toInt() == 0 && b[i + 1].toInt() == 0 && b[i + 2].toInt() == 1
            if (four) { starts.add(i); hdr.add(i + 4); i += 4 }
            else if (three) { starts.add(i); hdr.add(i + 3); i += 3 }
            else i++
        }
        if (starts.isEmpty()) return b
        var changed = false
        val out = java.io.ByteArrayOutputStream(b.size)
        for (n in starts.indices) {
            val nalStart = starts[n]
            val nalEnd = if (n + 1 < starts.size) starts[n + 1] else b.size
            val type = b[hdr[n]].toInt() and 0x1F
            if (type == 1 || type == 5 || type == 7 || type == 8) {
                out.write(b, nalStart, nalEnd - nalStart)
            } else {
                changed = true // drop AUD/SEI/filler/etc
            }
        }
        return if (changed) out.toByteArray() else b
    }

    private fun stripLeadingAud(b: ByteArray): ByteArray {
        // 4-byte start code
        if (b.size >= 6 && b[0].toInt() == 0 && b[1].toInt() == 0 && b[2].toInt() == 0 &&
            b[3].toInt() == 1 && (b[4].toInt() and 0x1F) == 9
        ) {
            var p = 5
            // find the next start code (00 00 01 …)
            while (p + 2 < b.size && !(b[p].toInt() == 0 && b[p + 1].toInt() == 0 && b[p + 2].toInt() == 1)) p++
            if (p + 2 < b.size) {
                val start = if (p >= 1 && b[p - 1].toInt() == 0) p - 1 else p
                return b.copyOfRange(start, b.size)
            }
        }
        // 3-byte start code
        if (b.size >= 5 && b[0].toInt() == 0 && b[1].toInt() == 0 && b[2].toInt() == 1 &&
            (b[3].toInt() and 0x1F) == 9
        ) {
            var p = 4
            while (p + 2 < b.size && !(b[p].toInt() == 0 && b[p + 1].toInt() == 0 && b[p + 2].toInt() == 1)) p++
            if (p + 2 < b.size) {
                val start = if (p >= 1 && b[p - 1].toInt() == 0) p - 1 else p
                return b.copyOfRange(start, b.size)
            }
        }
        return b
    }

    /** Name of a hardware Qualcomm H.264 encoder, or null. Prefers c2.qti / OMX.qcom, like the vendor. */
    private fun qualcommAvcEncoderName(): String? = try {
        val list = MediaCodecList(MediaCodecList.ALL_CODECS)
        list.codecInfos
            .filter { it.isEncoder && it.supportedTypes.any { t -> t.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) } }
            .map { it.name }
            .firstOrNull { it.contains("qti", true) || it.contains("qcom", true) }
    } catch (e: Exception) {
        log("[VIDEO] qualcomm encoder lookup failed: $e"); null
    }

    private fun drainLoop() {
        val codec = this.codec ?: return
        val info = MediaCodec.BufferInfo()
        while (running) {
            val idx = try { codec.dequeueOutputBuffer(info, 100_000) } catch (e: Exception) {
                log("[VIDEO] dequeue failed: $e"); break
            }
            if (idx < 0) continue
            val buf = try { codec.getOutputBuffer(idx) } catch (e: Exception) { null }
            if (buf != null && info.size > 0) {
                buf.position(info.offset)
                buf.limit(info.offset + info.size)
                val bytes = ByteArray(info.size)
                buf.get(bytes)

                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    // The surface encoder writes a VUI colour with video_full_range_flag=1 and
                    // colour_primaries=5 (BT.470 PAL) and IGNORES KEY_COLOR_* — so our SPS ends
                    // `…6d 37 05 06 05 06 d0 a1 35`. The vendor's is `…6d 35 00 06 00 06 d0 a1 35`
                    // (full_range=0, primaries/matrix=unspecified). A strict decoder can reject the
                    // PAL primaries. Colour is pure metadata (doesn't change the coded samples), so
                    // patch those exact bytes to the vendor's, making our SPS byte-identical.
                    codecConfig = patchSpsColorToVendor(bytes)   // SPS/PPS — prepended to keyframes
                    // Log the real SPS bytes so we can read the actual profile_idc the encoder
                    // produced (byte after the 00 00 00 01 67 start = profile: 0x42=Baseline,
                    // 0x4D=Main, 0x64=High). Many Android AVC encoders silently ignore KEY_PROFILE,
                    // so this is the only way to know whether High@3.0 actually took. Vendor SPS:
                    // 67 64 00 1E …
                    val cc = codecConfig ?: bytes
                    log("[VIDEO] got codec config (SPS/PPS) ${cc.size}b hex=" +
                        BleProtocol.bytesToHex(cc.copyOf(minOf(40, cc.size))))
                } else {
                    val isKey = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                    if (awaitKeyframe && !isKey) {
                        // A bike client just attached but this is a P-frame — it references frames the
                        // client never received, so serving it would leave the dash decoder uninitialised
                        // (black). Drop until the next keyframe. (Buffer still released below.)
                    } else {
                        if (isKey) awaitKeyframe = false
                        // Qualcomm surface encoders prepend an Access Unit Delimiter (NAL type 9,
                        // `00 00 00 01 09 XX`) to every frame. The vendor's byte-buffer path has none
                        // — its stream starts straight at the SPS/slice. A strict decoder can stall on
                        // the unexpected leading AUD. Strip it so our access units match the vendor's.
                        val frame = filterToEssentialNals(bytes)
                        // Prepend SPS/PPS to keyframes ONLY if the encoder didn't already include
                        // them. Qualcomm encoders often emit the keyframe as SPS+PPS+IDR already;
                        // prepending our held copy on top then yields a DOUBLE SPS/PPS, which a strict
                        // decoder (the Kove) can choke on. Detect a leading SPS NAL (type 7) and skip
                        // the prepend when present.
                        val keyHasConfig = isKey && frame.size >= 5 &&
                            frame[0].toInt() == 0 && frame[1].toInt() == 0 &&
                            ((frame[2].toInt() == 0 && frame[3].toInt() == 1 && (frame[4].toInt() and 0x1F) == 7) ||
                             (frame[2].toInt() == 1 && (frame[3].toInt() and 0x1F) == 7))
                        val joined = if (isKey && codecConfig != null && !keyHasConfig) codecConfig!! + frame else frame
                        val out = joined
                        // Log the NAL breakdown of the first few access units WE SEND so the exact
                        // stream structure (SEI/AUD/slice types, count) is visible without pulling a
                        // file — the one layer not yet compared against the vendor.
                        if (nalLogCount < 4) {
                            nalLogCount++
                            log("[VIDEO] AU#$nalLogCount ${out.size}b key=$isKey nals=[${nalSummary(out)}]")
                        }
                        writeDump(out)
                        // Keep the queue fresh: if full, drop oldest so we never lag far behind. A
                        // sustained drop rate means the bike can't pull as fast as we encode
                        // (downstream/Wi-Fi congestion) — counted for the adaptive controller.
                        if (!frameQueue.offerLast(out)) {
                            droppedFrames++
                            if (EasyConnProber.spoofedPackage == EasyConnProber.PKG_EASYRIDE) {
                                // A missing P-frame corrupts the picture until the next keyframe, up
                                // to 3s away on this path. Restart cleanly from a keyframe instead.
                                frameQueue.clear()
                                awaitKeyframe = true
                                requestKeyframe()
                                if (droppedFrames == 1L || droppedFrames % 20 == 0L) {
                                    log("[VIDEO] dash fell behind — restarting from a keyframe (#$droppedFrames)")
                                }
                            } else {
                                frameQueue.pollFirst()
                                frameQueue.offerLast(out)
                            }
                        }
                    }
                }
            }
            try { codec.releaseOutputBuffer(idx, false) } catch (_: Exception) {}
        }
    }

    /**
     * The encoder's input Surface. In [externalSource] mode the Android Auto video decoder is
     * pointed at this surface (`VideoDecoder.setSurface`), so decoded AA frames are re-encoded to
     * the bike's 800x384 H.264 and pulled by the PXC data socket via [pollFrame]. Valid only
     * after [start].
     */
    fun encoderInputSurface(): android.view.Surface? = inputSurface

    /**
     * The bitrate the encoder was configured with (the user's target for this session). This is the
     * ceiling the adaptive controller scales down from; 0 before the encoder exists.
     */
    fun targetBitrate(): Int = baseBitrate

    /** Monotonic count of encoded frames dropped due to a full queue (downstream congestion signal). */
    fun droppedFramesTotal(): Long = droppedFrames

    /**
     * Live-adjust the running encoder's target bitrate (no codec re-create) via [MediaCodec.setParameters].
     * Used by [AdaptiveVideoController] to shed bits when the phone is hot or the Wi-Fi link is
     * congested, and to restore them as conditions recover. Clamped to (0, [baseBitrate]] so it can
     * never exceed the user's chosen target. No-op if unchanged or the encoder isn't up yet.
     */
    fun setEncoderBitrate(bps: Int) {
        val c = codec ?: return
        if (baseBitrate <= 0) return
        val clamped = bps.coerceIn(1, baseBitrate)
        if (clamped == currentBitrate) return
        try {
            c.setParameters(android.os.Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, clamped)
            })
            currentBitrate = clamped
            log("[VIDEO] bitrate → ${clamped / 1000}kbps (target ${baseBitrate / 1000}kbps)")
        } catch (e: Exception) {
            log("[VIDEO] setEncoderBitrate failed: $e")
        }
    }

    /**
     * Live-adjust how many frames/sec the compositor pushes into the encoder (compositor mode only).
     * Delegates to [AaCompositor.setFrameCap]; used by [AdaptiveVideoController] to drop the rate as
     * the phone heats up. No-op outside compositor mode.
     */
    fun setFrameCap(fps: Int) {
        // Kove/EasyRide path is deliberately uncapped (pass every AA frame → steady 30fps so the
        // cluster commits to display). The adaptive/thermal controller and fixed-mode restore both
        // call this every watchdog tick; honour their request on every other path, but never let
        // them re-clamp the Kove stream below the AA source rate (the coalesce logic would halve it).
        if (EasyConnProber.spoofedPackage == EasyConnProber.PKG_EASYRIDE) {
            aaCompositor?.setFrameCap(60)
        } else {
            aaCompositor?.setFrameCap(fps)
        }
    }

    /** Compositor mode: the surface the AA decoder renders into (letterboxed before the encoder). */
    fun decoderInputSurface(): android.view.Surface? = aaCompositor?.inputSurface

    /** Compositor mode: map a bike-canvas touch point to Android Auto source coords (letterbox-aware);
     *  null if the point is in a black bar. See AaCompositor.mapCanvasToSource. */
    fun mapBikeTouchToSource(cx: Int, cy: Int): Pair<Int, Int>? = aaCompositor?.mapCanvasToSource(cx, cy)

    /** Compositor mode: attach an in-app phone preview surface (HudViewActivity). No-op otherwise. */
    fun setPreviewSurface(surface: Surface, w: Int, h: Int) = aaCompositor?.setPreview(surface, w, h)

    /** Compositor mode: the preview SurfaceView changed size (rotation / relayout). */
    fun updatePreviewSize(w: Int, h: Int) = aaCompositor?.updatePreviewSize(w, h)

    /** Compositor mode: detach the phone preview (blocks until the GL surface is destroyed). */
    fun clearPreviewSurface() = aaCompositor?.clearPreview()

    /** Live update when the rider changes [ScreenMargins] while projecting. */
    fun refreshScreenMargins() = aaCompositor?.refreshMargins()

    /** Called by the data socket on each REQ_RV_DATA_NEXT(114). Returns one access unit. */
    fun pollFrame(timeoutMs: Long): ByteArray? =
        try { frameQueue.pollFirst(timeoutMs, TimeUnit.MILLISECONDS) } catch (e: InterruptedException) { null }

    /**
     * A bike video client is (re)attaching and about to pull frames (REQ_RV_DATA_START). Guarantee the
     * FIRST access unit it receives is a full keyframe (SPS+PPS+IDR):
     *  1. flush stale frames already in the queue (they may be mid-GOP P-frames),
     *  2. drop further encoder output until the next keyframe (awaitKeyframe), and
     *  3. ask the encoder for an immediate sync frame so that keyframe arrives right away.
     *
     * Without this, Android Auto mode is black: its encoder is created at REQ_CONFIG_CAPTURE — up to a
     * second before the bike opens the data socket — so the initial IDR is long evicted from the small
     * queue and the bike starts on a P-frame with no SPS/PPS, leaving the dash decoder uninitialised.
     * (Mirror mode avoided this only by creating its encoder lazily right as the bike attaches.)
     */
    fun onBikeDataStart() {
        frameQueue.clear()
        awaitKeyframe = true
        // Do NOT queue the SPS/PPS as a standalone data frame: the vendor's config output is
        // write:0 (not sent as a frame) — its dash gets SPS/PPS from the keyframe, not a bare
        // parameter-set frame. We prepend the config to the keyframe below instead.
        try {
            codec?.setParameters(android.os.Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            })
            log("[VIDEO] bike attached → flushed queue + requested IDR (first frame will be a keyframe)")
        } catch (e: Exception) {
            log("[VIDEO] requestKeyframe failed: $e")
        }
    }

    private fun requestKeyframe() {
        try {
            codec?.setParameters(android.os.Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            })
        } catch (e: Exception) {
            log("[VIDEO] requestKeyframe failed: $e")
        }
    }

    private fun maybeStartDump() {
        if (!DUMP_H264 || dumpOut != null) return
        try {
            val base = context.getExternalFilesDir(null) ?: run { log("[DUMP] no external files dir"); return }
            val dir = java.io.File(base, "video").apply { mkdirs() }
            val tag = when {
                compositor -> "aa"
                ProjectionHolder.projection != null -> "mirror"
                else -> "own"
            }
            val f = java.io.File(dir, "opencfmoto-video-$tag.h264")
            dumpOut = java.io.BufferedOutputStream(java.io.FileOutputStream(f))
            dumpFrames = 0
            dumpPath = f.absolutePath
            log("[DUMP] recording H.264 → ${f.absolutePath} (first $DUMP_CAP frames, then auto-stops)")
        } catch (e: Exception) {
            log("[DUMP] open failed: $e"); dumpOut = null
        }
    }

    /** Append one Annex-B access unit to the diagnostic dump until the cap, then close. */
    private fun writeDump(au: ByteArray) {
        val d = dumpOut ?: return
        try {
            d.write(au)
            dumpFrames++
            if (dumpFrames >= DUMP_CAP) {
                d.flush(); d.close(); dumpOut = null
                log("[DUMP] complete: $dumpPath ($dumpFrames frames) — Share Log to send it")
            }
        } catch (e: Exception) {
            try { d.close() } catch (_: Exception) {}
            dumpOut = null
            log("[DUMP] write failed: $e")
        }
    }

    /**
     * @param abandonNavigation when true (default), a live NAV_TO is cleared so reopening the app
     *   doesn't resume a killed route. Soft AA→Map switches pass false so the destination survives.
     */
    fun stop(abandonNavigation: Boolean = true) {
        running = false
        try { dumpOut?.flush(); dumpOut?.close() } catch (_: Exception) {}
        if (dumpOut != null) log("[DUMP] stopped: $dumpPath ($dumpFrames frames) — Share Log to send it")
        dumpOut = null
        drainThread?.interrupt(); drainThread = null
        try { aaCompositor?.release() } catch (_: Exception) {}
        aaCompositor = null
        try { mirrorScaler?.release() } catch (_: Exception) {}
        mirrorScaler = null
        mirrorDisplayListener?.let { listener ->
            try {
                val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
                dm.unregisterDisplayListener(listener)
            } catch (_: Exception) {}
        }
        mirrorDisplayListener = null
        projectionCallback?.let { cb ->
            try { ProjectionHolder.projection?.unregisterCallback(cb) } catch (_: Exception) {}
        }
        projectionCallback = null
        ProjectionService.setKeepScreenOn(context, false)
        // Soft AA→Map keeps the FGS wake; only release when this was the map Presentation itself
        // or a full stop (abandonNavigation). Soft-switch stops the AA compositor with
        // abandonNavigation=false and must not drop the screen wake before GPX attaches.
        if (abandonNavigation || gpxDashUi != null) {
            AndroidAutoService.setGpxScreenWake(context, false)
        }
        GpxSession.clearTouchTarget()
        // Killing projection mid-route must not leave NAV_TO sticky — reopen would "resume"
        // and immediately flash Arrived if you're still near that place.
        if (abandonNavigation && GpxSession.active && GpxSession.mode == GpxSession.Mode.NAV_TO) {
            GpxSession.abandonStaleNavigation("projection stopped")
        }
        try { gpxVoice?.shutdown() } catch (_: Exception) {}
        gpxVoice = null
        main.post {
            try { gpxDashUi?.release() } catch (_: Exception) {}
            gpxDashUi = null
            try { presentation?.dismiss() } catch (_: Exception) {}
            presentation = null
            try { virtualDisplay?.release() } catch (_: Exception) {}
            virtualDisplay = null
        }
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        codec = null
        try { inputSurface?.release() } catch (_: Exception) {}
        inputSurface = null
        frameQueue.clear()
        codecConfig = null
    }

    companion object {
        /** Google's software AVC encoder — the one the vendor client uses on dashes that project. */
        const val SOFTWARE_AVC_ENCODER = "c2.android.avc.encoder"

        /**
         * Encode with [SOFTWARE_AVC_ENCODER] instead of the device's hardware encoder.
         *
         * Set per bike before the video pipeline starts. Off by default — every dash supported so
         * far renders the hardware encoder's stream fine, and hardware encoding is much cheaper.
         */
        @Volatile
        var preferSoftwareEncoder: Boolean = false

        /**
         * Logical width (dp) we render the own-content dash at, regardless of the bike's pixel
         * resolution. Smaller = bigger chrome. 900dp keeps HUD controls readable without the FAB /
         * speedometer crowding the metrics bar on typical bike panels.
         */
        const val TARGET_DASH_WIDTH_DP = 900.0

        /**
         * Encoder's static-screen frame-repeat floor (µs). Must stay comfortably under the bike's ~9s
         * media-socket timeout so an unchanging map still holds the link, but far slower than the old
         * 100ms/10fps value which re-encoded a still screen ~10× more than needed. AaCompositor's ~2s
         * idle redraw is the primary keep-alive; this is the encoder-side backstop. See idea 5 in
         * docs/06-OPTIMIZATION-IDEAS.md.
         */
        const val IDLE_ENCODER_REPEAT_US = 900_000L   // 0.9s → ≈1fps floor on a static screen

        /** Kove fallback when REQ_CONFIG_CAPTURE carries no bitrate: the value that dash asks for. */
        const val KOVE_DEFAULT_BITRATE = 4_194_304

        /** Kove repeat interval on a still screen: 10 frames/s. */
        const val KOVE_REPEAT_US = 100_000L

        /** Diagnostic build flag: dump the H.264 we send to the bike (bounded). Turn on to capture a
         *  .h264 of the exact wire stream for offline ffprobe analysis; off for normal use. */
        const val DUMP_H264 = false
        /** How many access units to capture before auto-stopping the dump (~20s at 30fps). */
        const val DUMP_CAP = 120
    }
}

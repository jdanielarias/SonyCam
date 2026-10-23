package com.sonycam.monitor.ui

import android.app.Application
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sonycam.monitor.BuildConfig
import com.sonycam.monitor.ptp.DeviceInfo
import com.sonycam.monitor.ptp.PtpEvent
import com.sonycam.monitor.ptp.PtpTransportException
import com.sonycam.monitor.sony.DPC_EXPOSURE_PROGRAM
import com.sonycam.monitor.sony.DPC_SONY_LIVEVIEW_STATUS
import com.sonycam.monitor.sony.DPC_SONY_SAVE_DEST
import com.sonycam.monitor.sony.DPC_SONY_SHUTTER
import com.sonycam.monitor.sony.DTC_UINT32
import com.sonycam.monitor.sony.ExposureControl
import com.sonycam.monitor.sony.PickList
import com.sonycam.monitor.sony.SonyCamera
import com.sonycam.monitor.sony.SonyCaptureStrategy
import com.sonycam.monitor.sony.SonyDeviceProp
import com.sonycam.monitor.sony.SonyDialect
import com.sonycam.monitor.sony.SonyDocumentedCodes
import com.sonycam.monitor.sony.SonyLiveViewState
import com.sonycam.monitor.sony.NotACamera
import com.sonycam.monitor.sony.SonyNotInPcRemoteMode
import com.sonycam.monitor.sony.SonyReadings
import com.sonycam.monitor.sony.SonyWriteMode
import com.sonycam.monitor.sony.ApertureLimits
import com.sonycam.monitor.sony.isSentinelFNumber
import com.sonycam.monitor.sony.DPC_FNUMBER
import com.sonycam.monitor.sony.DPC_SONY_MANUAL_FOCUS
import com.sonycam.monitor.sony.buildPickList
import com.sonycam.monitor.sony.canRecordMovie
import com.sonycam.monitor.sony.decodeReadings
import com.sonycam.monitor.sony.exposureModeGroup
import com.sonycam.monitor.sony.normalizeExposureMode
import com.sonycam.monitor.sony.propertyCode
import com.sonycam.monitor.sony.ShutterReading
import com.sonycam.monitor.sony.shutterReading
import com.sonycam.monitor.usb.CameraNotResponding
import com.sonycam.monitor.usb.UsbTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Seek-level diagnostics: pairs with the per-transaction "PtpTx" log. */
private const val TAG_SEEK = "SeekCtl"

/** How long to watch D21D before deciding the camera ignored a REC press. */
private const val REC_CONFIRM_WINDOW_MS = 2_500L
private const val REC_CONFIRM_POLL_MS = 250L

/** A body waking from standby can take a few seconds to answer the first ask. */
private const val FIRST_CONTACT_TIMEOUT_MS = 8_000
/** Past the first failure the body is known unwell; probe, do not wait it out. */
private const val RECOVERY_PROBE_TIMEOUT_MS = 3_000

/**
 * How long to keep waiting once the camera says it is no longer on the shot.
 * Measured gap between the controls un-greying and the JPEG arriving: ~0.6 s.
 */
private const val CAPTURE_IDLE_GRACE_MS = 6_000L

/**
 * A shot short enough that the busy flag passes between two polls never marks
 * the camera busy at all. That case keeps the window already proven on both
 * bodies, which covers metering, the card write and the transfer.
 */
private const val CAPTURE_NEVER_BUSY_MS = 12_000L

/**
 * Polls between complete property snapshots on a body that streams differences.
 * A delta the camera counted as delivered but that never reached us would leave
 * a reading frozen; at 500 ms a poll this bounds that to half a minute.
 */
private const val FULL_RESYNC_EVERY = 60

/**
 * How many properties may legitimately differ between the delta-maintained
 * cache and the resync dump. A handful is one dial being turned in the last
 * half second; a pile means the stream is not telling us everything.
 */
private const val DELTA_DRIFT_LIMIT = 8

/**
 * How long teardown waits for each job that owns the camera.
 *
 * Sized against the worst legitimate case rather than picked round: a BULB
 * release is two button writes at 4 s each, so three seconds guaranteed a
 * timeout on exactly the path that matters. Exceeding it is not fatal any more —
 * the close goes through the USB lock — but timing out here for no reason would
 * throw away the cheap ordering and lean on the expensive one.
 */
private const val TEARDOWN_JOIN_TIMEOUT_MS = 10_000L

/**
 * How long the close waits for the USB lock once the joins are done. Longer
 * still, because this is the guarantee the joins fall back to.
 */
private const val TEARDOWN_LOCK_TIMEOUT_MS = 12_000L

/** How often the open BULB shutter checks for the closing press. */
private const val BULB_TICK_MS = 100L

/** Backstop for a body that latches busy forever; not a normal-path timeout. */
private const val CAPTURE_ABSOLUTE_CAP_MS = 600_000L

/**
 * Consecutive failed property polls that end a capture wait.
 *
 * A still being processed can briefly answer DeviceBusy, so one failure means
 * nothing; a hundred in a row means the body is not coming back, and waiting
 * out the ten-minute cap for that helps nobody. At 250 ms a poll this is ~25 s.
 */
private const val CAPTURE_MAX_POLL_FAILURES = 100

/** Past this, say the wait is the exposure so it does not read as a freeze. */
private const val LONG_EXPOSURE_NOTICE_S = 1.5

/**
 * Quiet window before live view may fetch again after a still.
 *
 * The A7 III un-greys its controls while it is still writing the file, and a
 * live-view fetch landing in that gap stalled bulk-IN and took the session down
 * about four seconds after a 15" frame was saved. D221 gates the fetch as well;
 * this is the margin behind it.
 */
private const val POST_CAPTURE_QUIET_MS = 6_000L

/** RAW+JPEG is two objects; anything past a few is not one shot any more. */
private const val MAX_COMPANION_OBJECTS = 3
/** Reads of D215 after each download before deciding nothing else is coming. */
private const val COMPANION_READY_POLLS = 3
private const val COMPANION_POLL_MS = 150L

private const val MODERN_CAMERA_SAVE_MENU =
    "En la cámara: MENÚ → Red → ImageConex./PC rem. → Ajuste toma remota → " +
        "Dest. guard. img fija → Destino+cámara. Luego pulsa PHOTO otra vez."

private const val CLASSIC_CAMERA_SAVE_MENU =
    "En la cámara: MENÚ → Ajustes → Ajustes de PC remoto → Dest. guard. img fija " +
        "→ PC+cámara. Luego pulsa PHOTO otra vez."

/** What the verified D222 bit mask means for the post-shutter transfer. */
internal enum class CaptureDestination(val hostReceives: Boolean) {
    HOST_AND_CAMERA(hostReceives = true),
    CAMERA_ONLY(hostReceives = false),
    /** D222 is absent on this body: retain the compatible host-download path. */
    UNKNOWN(hostReceives = true),
}

/** Exact Sony D222 enum decoder; placeholder/status bit patterns are rejected. */
internal fun captureDestinationFromRaw(value: Long): CaptureDestination? = when (
    (value and 0xFFFFL).toInt()
) {
    SonyCamera.SAVE_DEST_HOST_AND_CAMERA -> CaptureDestination.HOST_AND_CAMERA
    SonyCamera.SAVE_DEST_CAMERA -> CaptureDestination.CAMERA_ONLY
    else -> null
}

/**
 * Whether a live-view object fetch may be put on the wire right now.
 *
 * D221 is the camera's own statement about whether it is producing live view.
 * When it says no, asking is not merely wasted: bodies answer the fetch by
 * halting bulk-IN, and an A7 III session died seconds after connecting because
 * the capability probe asked anyway. So the probe obeys D221 exactly like a
 * steady-state fetch does; D221 returning to 1 is what lets live view start.
 *
 * A body with no D221 at all passes [liveViewStatus] as null and is unaffected.
 */
internal fun mayFetchLiveView(
    state: SonyLiveViewState,
    liveViewStatus: Long?,
    capturing: Boolean = false,
    millisSinceCapture: Long = Long.MAX_VALUE,
): Boolean = !capturing && millisSinceCapture >= POST_CAPTURE_QUIET_MS && when (state) {
    SonyLiveViewState.UNSUPPORTED, SonyLiveViewState.DISABLED -> false
    SonyLiveViewState.AVAILABLE, SonyLiveViewState.UNVERIFIED -> liveViewStatus != 0L
}

/**
 * Property codes whose value disagrees between a delta-maintained cache and a
 * complete dump taken afterwards.
 *
 * A few are ordinary: settings do change between two polls. A lot means the
 * difference stream dropped something and the display has been showing stale
 * numbers — the failure this project has paid for more than once, and one that
 * is invisible without this check.
 */
internal fun divergentCodes(
    cached: Map<Int, SonyDeviceProp>,
    fresh: Map<Int, SonyDeviceProp>,
): List<Int> = fresh.entries
    .filter { (code, p) -> cached[code]?.currentValue != p.currentValue }
    .map { it.key }
    .sorted()

/**
 * The screen after a session died under the poll loop: the connect pane, with
 * the reason and a way back. `info` must be null — it is what selects the
 * monitor screen, and the monitor without a session is controls wired to
 * nothing.
 */
internal fun lostConnectionState(reason: String): UiState = UiState(
    status = "Lost connection.",
    error = "$reason\n\nPulsa Connect para volver a conectar.",
)

/** Codes the body currently reports as adjustable - its own "not greyed out". */
internal fun enabledCodes(props: Map<Int, SonyDeviceProp>): Set<Int> =
    props.filterValues { it.isEnabled == 1 }.keys

/**
 * Whether the body is still working on the shot that was just fired.
 *
 * Sony greys out the controls it locks while exposing and writing, so this is
 * the camera answering for itself instead of a stopwatch. Measured on an
 * ILCE-7M3 at 15": property 0x5004 went isEnabled 1 -> 0 as the shutter opened
 * and back to 1 exactly 32 s later - the exposure plus the equal-length Long
 * Exposure NR dark frame - a beat before the JPEG appeared.
 *
 * A clock cannot know that duration. BULB does not announce one at all, and a
 * flat 12 s window reported a good 15" frame as a failed shot. This does not
 * care how long the shot takes, only whether the camera is still on it.
 */
internal fun stillCaptureInProgress(
    baselineEnabled: Set<Int>,
    current: Map<Int, SonyDeviceProp>,
): Boolean = !enabledCodes(current).containsAll(baselineEnabled)

private enum class PhonePhotoLocation(val userLabel: String) {
    GALLERY("la Galería"),
    APP_STORAGE("el almacenamiento privado de Sony Monitor"),
}

/**
 * Process-wide claim on the camera: exactly one [MainViewModel] may hold a USB
 * session at a time.
 *
 * The manifest keeps a single activity alive, so normally nothing contends for
 * this. It still matters during an activity recreation, where the outgoing
 * view model is cleared only after the incoming one has run: two open
 * transports on one device is not a degraded state but a broken one, because
 * `claimInterface(force = true)` gives the interface to whichever opened last
 * and either one's `releaseInterface` then unbinds it from under the other.
 */
private object CameraSession {
    private val owner = AtomicReference<Any?>(null)

    /** True only for the caller that actually took the claim. */
    fun acquire(claimant: Any): Boolean = owner.compareAndSet(null, claimant)

    /** No-op unless [claimant] is the current owner. */
    fun release(claimant: Any) {
        owner.compareAndSet(claimant, null)
    }

    val isHeld: Boolean get() = owner.get() != null
}

/** How long a new connection waits for the previous session's cleanup. */
private const val CLAIM_RELEASE_WAIT_MS = 30_000L
private const val CLAIM_RELEASE_POLL_MS = 200L

/** UI state for Phase 2: identity + live exposure readings. */
data class UiState(
    val status: String = "Connect a Sony camera in PC Remote mode.",
    val connecting: Boolean = false,
    val info: DeviceInfo? = null,
    val readings: SonyReadings? = null,
    val error: String? = null,
    /** Sony protocol mode: 2 = pre-2020 stepping bodies, 3 = 2020+ absolute. */
    val modeVer: Int = 0,
    /** Vendor dialect negotiated at connect time. */
    val dialect: SonyDialect = SonyDialect.GENERIC,
    /** False when the body only speaks plain PTP (MTP mode, or an old compact). */
    val canControl: Boolean = true,
    /** Still-capture path negotiated from the camera's advertised controls. */
    val captureStrategy: SonyCaptureStrategy = SonyCaptureStrategy.UNSUPPORTED,
    /** True only when D2C8 was advertised or returned as a control descriptor. */
    val canMovieRecord: Boolean = false,
    /** Session-scoped live-view probe state, exposed so UI can degrade cleanly. */
    val liveViewState: SonyLiveViewState = SonyLiveViewState.UNSUPPORTED,
    /**
     * Aperture ends this body has proved for the lens on it now. Classic bodies
     * publish no aperture list at all, so without this the wheel offers stops
     * the lens does not have. See [ApertureLimits].
     */
    val apertureLimits: ApertureLimits = ApertureLimits(),
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /**
     * Aperture ends proved by the camera refusing to move further. Lives here
     * as well as in the state because the seek both reads and writes it.
     */
    @Volatile
    private var apertureLimits = ApertureLimits()

    /**
     * Widen or forget the proved ends when the body reports an aperture outside
     * them: a fixed lens cannot do that, so the lens was changed and the old
     * ends describe glass that is no longer on the camera.
     */
    private fun noteAperture(map: Map<Int, SonyDeviceProp>) {
        val raw = map[_state.value.dialect.propertyCode(DPC_FNUMBER)]?.currentValue ?: return
        if (!apertureLimits.outgrownBy(raw)) return
        diag("aperture: f/${"%.1f".format(raw / 100.0)} is outside the proved range; lens changed, forgetting it")
        apertureLimits = ApertureLimits()
        _state.value = _state.value.copy(apertureLimits = apertureLimits)
    }

    // Latest raw property map — the jog wheel builds its value list from this.
    private val _props = MutableStateFlow<Map<Int, SonyDeviceProp>>(emptyMap())
    val props: StateFlow<Map<Int, SonyDeviceProp>> = _props.asStateFlow()

    /** The seek currently in flight (control + exact property/value), null when idle.
     *  The wheel pins itself to the target while this is set — otherwise it
     *  would snap back to the old value for the seconds the camera needs to
     *  walk there, which reads as "it reverted". */
    data class SeekState(
        val ctl: ExposureControl,
        val propCode: Int,
        val targetRaw: Long,
    )
    private val _seeking = MutableStateFlow<SeekState?>(null)
    val seeking: StateFlow<SeekState?> = _seeking.asStateFlow()

    /** Latest live-view frame (Phase 5), null until the first one arrives. */
    private val _frame = MutableStateFlow<Bitmap?>(null)
    val frame: StateFlow<Bitmap?> = _frame.asStateFlow()

    /** When the BULB shutter was opened, null while it is closed. */
    private val _bulbOpenSince = MutableStateFlow<Long?>(null)
    val bulbOpenSince: StateFlow<Long?> = _bulbOpenSince.asStateFlow()

    // Set by the second press. The exposure loop owns the actual close, so the
    // shutter is released on exactly one path however the cycle ends.
    @Volatile private var bulbCloseRequested = false

    /** `/dev/bus/usb/...` of the camera in this session, for detach matching. */
    @Volatile private var connectedDeviceName: String? = null

    /**
     * Survives [onCleared] on purpose: closing the PTP session and releasing the
     * USB interface must complete even though the view model is being disposed,
     * and neither belongs on the main thread.
     */
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var transport: UsbTransport? = null
    @Volatile private var camera: SonyCamera? = null
    /** Unique per connection, so a duplicate teardown cannot release a newer claim. */
    private var connectionClaim: Any? = null
    private var pollJob: Job? = null
    /** Kept so a detach mid-handshake can actually stop it. */
    private var connectJob: Job? = null
    private var seekJob: Job? = null
    private var liveViewJob: Job? = null
    private var captureJob: Job? = null

    // Serializes every USB transaction: the poll loop and control actions must
    // never run a PTP transaction concurrently or the framing desyncs.
    private val usbMutex = Mutex()

    @Volatile private var busy = false

    // The A7 III stalls its USB bulk endpoints for a moment right after a
    // capture (a live-view fetch there fails with a bulk-write timeout and can
    // knock the whole session down). Live view keeps quiet inside this window.
    @Volatile private var lastCaptureAt = 0L

    /** Events seen on the interrupt endpoint this session. A measurement. */
    @Volatile private var interruptEventCount = 0

    // One capture cycle at a time: pressing PHOTO again while the previous shot
    // is still being processed/downloaded is what crashed the camera firmware.
    @Volatile private var capturing = false
    @Volatile private var captureGeneration = 0L

    // Rolling diagnostic transcript (connect dump + seek/capture/liveview
    // events). Exported to a text file via the DIAG button so testers with
    // OTHER camera models can send it without any developer tooling.
    private val diagBuf = StringBuilder()

    // The connect-time compatibility report lives in its own buffer so the
    // rolling trim of [diagBuf] can never eat it — it is the part a tester with
    // an unknown model actually needs to send.
    private val reportBuf = StringBuilder()

    /** Log to logcat and to the (never-trimmed) compatibility report. */
    private fun rep(msg: String) {
        // The report carries the body's model, firmware and its whole property
        // blob. That is the point of it, and it is also why it must not go to
        // logcat in a shipped build — where any app with log access could read
        // it. The buffer and the export are unaffected.
        if (BuildConfig.DEBUG) Log.i(TAG_SEEK, msg)
        synchronized(reportBuf) { reportBuf.append(msg).append('\n') }
    }

    /** Log to logcat AND to the exportable diagnostic transcript. */
    private fun diag(msg: String) {
        if (BuildConfig.DEBUG) Log.i(TAG_SEEK, msg)
        synchronized(diagBuf) {
            if (diagBuf.length > 300_000) diagBuf.delete(0, 100_000)
            diagBuf.append("%tH:%<tM:%<tS.%<tL ".format(System.currentTimeMillis())).append(msg).append('\n')
        }
    }

    /** Surface an action failure while the monitor screen is visible. */
    private suspend fun tellUser(message: String, long: Boolean = true) {
        withContext(Dispatchers.Main) {
            Toast.makeText(
                getApplication<Application>(),
                message,
                if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT,
            ).show()
        }
    }

    /** Save the diagnostic transcript to Downloads (API 29+) or app storage. */
    fun exportDiagnostics() {
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            val model = _state.value.info?.model?.replace(Regex("[^A-Za-z0-9-]"), "") ?: "camera"
            val name = "SonyMonitor-diag-$model-%tY%<tm%<td-%<tH%<tM%<tS.txt".format(System.currentTimeMillis())
            val head = synchronized(reportBuf) { reportBuf.toString() }
            val tail = synchronized(diagBuf) { diagBuf.toString() }
            val text = (head + tail).ifEmpty { "No data yet - connect the camera first.\n" }
            val msg = try {
                if (Build.VERSION.SDK_INT >= 29) {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, name)
                        put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                        // Same reason as the photo: nothing else should be able
                        // to read a transcript that is still being written.
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    }
                    val uri = app.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        ?: throw IllegalStateException("MediaStore insert failed")
                    // A failure past this point has already created the row, and
                    // a pending row nobody removes is an orphan the user cannot
                    // see or delete.
                    try {
                        app.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                            ?: throw IllegalStateException("openOutputStream failed")
                        val published = app.contentResolver.update(
                            uri,
                            ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                            null, null,
                        )
                        if (published != 1) {
                            throw IllegalStateException("MediaStore did not publish (updated=$published)")
                        }
                    } catch (e: Exception) {
                        runCatching { app.contentResolver.delete(uri, null, null) }
                        throw e
                    }
                    "Diagnóstico guardado: Descargas/$name"
                } else {
                    val f = java.io.File(app.getExternalFilesDir(null), name)
                    f.writeText(text)
                    "Diagnóstico guardado: ${f.absolutePath}"
                }
            } catch (e: Exception) {
                "No se pudo guardar el diagnóstico: ${e.message}"
            }
            withContext(Dispatchers.Main) { Toast.makeText(app, msg, Toast.LENGTH_LONG).show() }
        }
    }

    /**
     * The compatibility report: everything needed to add support for a body we
     * do not have on hand. A tester with any Sony model can hit DIAG and send
     * this file, and it is enough to reproduce the whole handshake and property
     * parse offline — including the raw 0x9209 blob, which turns the report into
     * a replayable fixture. Nothing here is model-specific by design.
     */
    /**
     * Record whatever turned up on the interrupt endpoint.
     *
     * Sony bodies advertise event codes in DeviceInfo — the A7 III lists
     * 0xC201/0xC202/0xC203 — but libgphoto2 does not trust the interrupt
     * endpoint for Sony and polls 0x9209 instead, and there are reports that the
     * events never arrive. Whether they do is an open question that only a real
     * body can answer, so this counts and logs them and does nothing else.
     *
     * If the count stays at zero across a session with captures and dial
     * changes, the polling design is confirmed and this can be deleted. If
     * events do arrive, 0xC206 CapturedEvent could replace the busy-flag
     * heuristic and 0xC224 the REC read-back — but only then.
     */
    private fun noteInterruptEvents(events: List<PtpEvent>) {
        if (events.isEmpty()) return
        interruptEventCount += events.size
        for (e in events) diag("interrupt: $e")
    }

    /**
     * Check what the body offered against what Sony documents for that model.
     *
     * Purely a report line — nothing branches on it. Its value is in a tester's
     * transcript from a camera the developer does not own: "Sony documents
     * 0x9211 for this body and it did not appear" points at our handshake,
     * whereas the same session silently running at reduced capability looks
     * like the camera's limitation.
     */
    private fun repDocumentedCodeCheck(di: DeviceInfo, proto: Int) {
        // The two references describe two command sets, so look the body up in
        // the table for the protocol it actually negotiated.
        if (!SonyDocumentedCodes.knows(proto, di.model)) {
            rep("sonyDocs: sin contraste para ${di.model.ifEmpty { "(sin modelo)" }} " +
                "en la tabla PTP 0x%03X de Sony".format(proto))
            return
        }
        val advertised = (di.operationsSupported + di.eventsSupported).toSet()
        val missing = SonyDocumentedCodes.missingFor(proto, di.model, advertised)
        val extra = SonyDocumentedCodes.undocumentedFor(proto, di.model, advertised)
        if (missing.isEmpty()) {
            rep("sonyDocs: la cámara ofrece todo lo que Sony documenta para ${di.model}")
        } else {
            rep("sonyDocs: FALTAN ${missing.size} código(s) que Sony documenta para ${di.model}:")
            for ((code, name) in missing) rep("  - %04X %s".format(code, name))
        }
        if (extra.isNotEmpty()) {
            rep("sonyDocs: además ofrece ${extra.size} sin documentar: " +
                extra.joinToString(" ") { "%04X".format(it) })
        }
    }

    private fun dumpCompatibilityReport(
        di: DeviceInfo,
        cam: SonyCamera,
        map: Map<Int, SonyDeviceProp>,
        device: UsbDevice,
        transport: UsbTransport,
    ) {
        fun codes(a: IntArray) = a.joinToString(" ") { "%04X".format(it) }
        synchronized(reportBuf) { reportBuf.setLength(0) }
        rep("=== COMPATIBILITY REPORT ===")
        rep("usb=0x%04X:0x%04X product='%s' maxPacketIn=%d extendedSession=%s".format(
            device.vendorId, device.productId, transport.productName ?: "", transport.maxPacketIn,
            cam.extendedSession))
        rep("model=${di.model} mfr=${di.manufacturer} fw=${di.deviceVersion}")
        rep("ptpStd=0x%04X vendorExt=0x%X v0x%04X '%s'".format(
            di.standardVersion, di.vendorExtensionId, di.vendorExtensionVersion, di.vendorExtensionDesc))
        rep("deviceInfo.ops   = ${codes(di.operationsSupported)}")
        rep("deviceInfo.events= ${codes(di.eventsSupported)}")
        rep("deviceInfo.props = ${codes(di.devicePropertiesSupported)}")
        repDocumentedCodeCheck(di, cam.profile.protocolVersion)
        rep("profile: ${cam.profile.summary()}")
        rep("vendorProps    = ${cam.profile.vendorProps.joinToString(" ") { "%04X".format(it) }}")
        rep("vendorControls = ${cam.profile.vendorControls.joinToString(" ") { "%04X".format(it) }}")
        rep("props=${map.size} saveDest(D222)=0x%X".format(
            map[cam.profile.dialect.propertyCode(DPC_SONY_SAVE_DEST)]?.currentValue ?: -1L))
        val shootingModeRaw = map[cam.profile.dialect.propertyCode(DPC_EXPOSURE_PROGRAM)]?.currentValue
        rep(
            "shooting raw=%s code=%s group=%s".format(
                shootingModeRaw?.let { "0x%08X".format(it) } ?: "-",
                shootingModeRaw?.let { "0x%04X".format(normalizeExposureMode(it)) } ?: "-",
                shootingModeRaw?.let { exposureModeGroup(it).toString() } ?: "-",
            ),
        )
        fun enumStats(values: List<Long>?): String = if (values == null) {
            "-"
        } else {
            "${values.size}/max=${values.maxOrNull()?.let { "0x%X".format(it) } ?: "-"}"
        }
        for ((code, p) in map) {
            rep("prop 0x%04X type=0x%04X getset=0x%02X en=%d write=%s cur=0x%X form=%d enumSet=%s enumGetSet=%s range=%s%s".format(
                code, p.dataType, p.getSet, p.isEnabled, p.writeMode(cam.sonyModeVer),
                p.currentValue ?: -1L, p.formFlag, enumStats(p.setEnumValues),
                enumStats(p.getSetEnumValues), p.range?.toString() ?: "-",
                if (p.synthetic) " SYNTH" else ""))
        }
        // The raw dump last: bulky, but it is the one artifact that lets the
        // parser be fixed for a body without ever touching it.
        val blob = cam.lastPropBlob
        if (blob.isNotEmpty()) {
            rep("rawPropBlob[${blob.size}B]=" + blob.joinToString("") { "%02X".format(it) })
        }
        rep("=== END REPORT ===")
    }

    /** Ignore anything that is not the camera this session is holding. */
    fun onDeviceDetached(deviceName: String) {
        if (deviceName != connectedDeviceName) return
        teardown()
        _state.value = UiState(status = "Camera disconnected.")
    }

    /** Open the device, run the full Sony handshake, then poll live readings. */
    @Synchronized
    fun connect(manager: UsbManager, device: UsbDevice) {
        if (busy || transport != null) return
        // Losing this means another view model already owns the camera; opening
        // it a second time would take the interface away from that session.
        val claim = Any()
        if (!CameraSession.acquire(claim)) {
            // Usually the previous session of this very view model: its close
            // runs on cleanupScope and keeps the claim until the interface is
            // released. Dropping the request here meant a camera replugged
            // during that window was simply ignored — no message, no retry.
            diag("connect deferred: the previous session is still closing")
            deferConnect(manager, device)
            return
        }
        connectRetryJob?.cancel()
        connectRetryJob = null
        connectionClaim = claim
        busy = true
        connectedDeviceName = device.deviceName
        _state.value = UiState(status = "Connecting…", connecting = true)
        connectJob = viewModelScope.launch {
            try {
                val openingTransport = UsbTransport(manager, device)
                val resources = openingTransport to SonyCamera(openingTransport)
                val (info, readings) = initializeConnection(
                    resources = resources,
                    initialize = { (t, cam) ->
                        t.open()
                        currentCoroutineContext().ensureActive()
                        val di = firstContact(cam)
                        currentCoroutineContext().ensureActive()
                        cam.openNegotiatedSession(1, onWarn = { diag("handshake: $it") })
                        currentCoroutineContext().ensureActive()
                        val map = cam.handshake(onWarn = { diag("handshake: $it") })
                        currentCoroutineContext().ensureActive()
                        dumpCompatibilityReport(di, cam, map, device, t)
                        Triple(di, decodeReadings(map, cam.sonyModeVer, cam.profile.dialect), map)
                    },
                    publish = { (t, cam), (_, _, map) ->
                        synchronized(this@MainViewModel) {
                            if (connectionClaim !== claim) {
                                throw kotlinx.coroutines.CancellationException("Connection was closed while opening")
                            }
                            transport = t
                            camera = cam
                            _props.value = map
                        }
                    },
                    close = { (t, cam) ->
                        runCatching { cam.closeSession() }
                        runCatching { t.close() }
                    },
                )
                val prof = camera?.profile
                _state.value = UiState(
                    status = "Connected", info = info, readings = readings,
                    modeVer = prof?.modeVer ?: 0,
                    dialect = prof?.dialect ?: SonyDialect.GENERIC,
                    canControl = prof?.canControl ?: true,
                    captureStrategy = prof?.captureStrategy ?: SonyCaptureStrategy.UNSUPPORTED,
                    canMovieRecord = prof?.canMovieRecord ?: false,
                    liveViewState = prof?.liveViewState ?: SonyLiveViewState.UNSUPPORTED,
                )
                startPolling()
                if (prof?.canLiveView == true || prof?.shouldProbeLiveView == true) startLiveView()
            // The reason comes first, then the teardown. Teardown now cancels
            // this very coroutine, and while nothing between the two suspends
            // today, ordering it this way means a future suspending call cannot
            // quietly cost the user the explanation for the failure.
            } catch (e: kotlinx.coroutines.CancellationException) {
                // A detach cancels this job, and a CancellationException is an
                // Exception: swallowing it below reported "Failed to connect"
                // over the "Camera disconnected" the detach had just set, and
                // told the coroutine machinery the job had completed normally.
                throw e
            } catch (e: SonyNotInPcRemoteMode) {
                currentCoroutineContext().ensureActive()
                diag("connect REJECTED: ${e.message}")
                _state.value = UiState(status = "La cámara no está en PC Remote.", error = e.message)
                teardown()
            } catch (e: NotACamera) {
                currentCoroutineContext().ensureActive()
                // Not the same failure as a camera in the wrong USB mode, and
                // not the same advice: sending someone holding a phone to find
                // the PC Remote menu is a wrong answer that sounds right.
                diag("connect REJECTED: not a camera: ${e.manufacturer} ${e.model}")
                _state.value = UiState(status = "Ese dispositivo no es una cámara.", error = e.message)
                teardown()
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                diag("connect FAILED: ${e.message ?: e}")
                _state.value = UiState(status = "Failed to connect.", error = e.message ?: e.toString())
                teardown()
            } finally {
                busy = false
            }
        }
    }

    /** Waits for the previous session's claim, then connects. */
    private var connectRetryJob: Job? = null

    /**
     * Retry [connect] once the claim held by a closing session is released.
     *
     * Bounded: the close is itself bounded (joins plus the USB lock), so a claim
     * still held after [CLAIM_RELEASE_WAIT_MS] means something is wrong, and the
     * person is told what to do instead of watching a spinner.
     */
    @Synchronized
    private fun deferConnect(manager: UsbManager, device: UsbDevice) {
        if (connectRetryJob?.isActive == true) return
        _state.value = UiState(status = "Cerrando la sesión anterior…", connecting = true)
        connectRetryJob = viewModelScope.launch {
            val released = awaitCondition(CLAIM_RELEASE_WAIT_MS, CLAIM_RELEASE_POLL_MS) {
                !CameraSession.isHeld
            }
            if (released) {
                connect(manager, device)
            } else {
                diag("connect abandoned: the previous session never released the camera")
                _state.value = UiState(
                    status = "Failed to connect.",
                    error = "La sesión anterior no terminó de cerrarse. Desconecta el cable " +
                        "USB y vuelve a conectarlo.",
                )
            }
        }
    }

    /**
     * GetDeviceInfo, the first thing the camera is ever asked.
     *
     * This used to be a ladder that retried behind the still-image class reset
     * (0x66). Both rungs are gone. The reset was measured on a wedged A7 III —
     * accepted, and the body still moved nothing over bulk — so it never once
     * helped; it is also MTP's own reset request on the shared 6/1/1 class, so
     * it was the most dangerous thing here. What actually recovers a session
     * this app lost is [UsbTransport.drainInput], which runs on every open.
     */
    private fun firstContact(cam: SonyCamera): DeviceInfo = try {
        cam.getDeviceInfo(FIRST_CONTACT_TIMEOUT_MS)
    } catch (e: PtpTransportException) {
        throw CameraNotResponding(e)
    }

    /** Refresh exposure readings ~2x/second, like the desktop `--watch`. */
    private fun startPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch(Dispatchers.IO) {
            var failures = 0
            var pollsSinceFullRead = 0
            var fullResyncDue = true
            var deltasUsable = true
            var totalPolls = 0
            while (isActive) {
                delay(500)
                // A seek is driving the setting — don't fight it with a stale read
                // (that's what made the wheel "bounce" back). The seek does its own.
                // A capture also owns the camera through its destination
                // preflight, shutter and D215 processing window. Let that one
                // workflow decide which transient failures are recoverable.
                if (capturing || seekJob?.isActive == true) {
                    // A control path just re-read everything under the mutex, so
                    // the delta stream's idea of what we have seen is no longer
                    // ours. Start again from a whole snapshot.
                    fullResyncDue = true
                    continue
                }
                val cam = camera ?: break
                // Ask only for what changed once the first full snapshot is in.
                // A delta the camera thinks it delivered but we never merged
                // would freeze a reading silently, so any failure and every
                // FULL_RESYNC_EVERY polls go back to the complete dump.
                val wantDelta = cam.supportsPropertyDeltas &&
                    deltasUsable &&
                    !fullResyncDue &&
                    _props.value.isNotEmpty() &&
                    pollsSinceFullRead < FULL_RESYNC_EVERY
                val map = try {
                    usbMutex.withLock {
                        // Measurement only, inside the same lock so it can never
                        // interleave with a bulk transfer. Nothing branches on
                        // the result; see noteInterruptEvents.
                        // Diagnostics must not hold the preview/focus USB lock
                        // for 10 ms waiting for an event that may never arrive.
                        noteInterruptEvents(cam.pollEvents(timeoutMs = 1))
                        if (wantDelta) cam.getChangedProps() else cam.getAllProps()
                    }
                } catch (e: Exception) {
                    fullResyncDue = true
                    // Sony lists Parameter_Not_Supported among the valid answers
                    // to 0x9209, so a body may simply refuse the difference flag.
                    // Give it up for the session and take the full dump instead —
                    // an optimisation must never be able to end a session, and
                    // this one cannot be tried on the only body that has it.
                    if (wantDelta) {
                        deltasUsable = false
                        diag("poll: la cámara rechazó el sondeo diferencial (${e.message}); " +
                            "volviendo al volcado completo")
                        continue
                    }
                    // The camera stalls transiently (esp. right after captures) —
                    // don't tear the session down on the first hiccup.
                    if (++failures < 3) { delay(800); continue }
                    // A fresh state, not a copy. Keeping `info` kept the monitor
                    // on screen with the last readings and live-looking
                    // controls wired to nothing, and no Connect button: only
                    // unplugging the camera or killing the app got out of it.
                    withContext(Dispatchers.Main) {
                        _state.value = lostConnectionState(e.message ?: e.toString())
                    }
                    teardown()
                    break
                }
                failures = 0
                val merged: Map<Int, SonyDeviceProp>
                if (wantDelta) {
                    pollsSinceFullRead++
                    merged = if (map.isEmpty()) _props.value else _props.value + map
                } else {
                    // The resync is also the audit: a delta stream that quietly
                    // dropped something shows up as a cache that no longer
                    // matches a complete dump. Being wrong here costs only a
                    // return to the old behaviour, so it errs towards caution.
                    if (pollsSinceFullRead > 0 && _props.value.isNotEmpty()) {
                        val drift = divergentCodes(_props.value, map)
                        if (drift.size > DELTA_DRIFT_LIMIT) {
                            deltasUsable = false
                            diag("poll: el sondeo diferencial se desfasó en ${drift.size} " +
                                "propiedades (" + drift.take(6).joinToString(" ") { "%04X".format(it) } +
                                "); volviendo al volcado completo")
                        } else if (drift.isNotEmpty()) {
                            diag("poll: resync con ${drift.size} cambio(s) desde el último delta")
                        }
                    }
                    // Checkpoint for the interrupt-endpoint measurement. A run
                    // of these reading zero is the finding: the events Sony
                    // advertises never actually arrive, and polling is right.
                    totalPolls += pollsSinceFullRead + 1
                    diag("interrupt: $interruptEventCount evento(s) en $totalPolls sondeos")
                    fullResyncDue = false
                    pollsSinceFullRead = 0
                    merged = map
                }
                _props.value = merged
                noteAperture(merged)
                _state.value = _state.value.copy(readings = decodeReadings(merged, cam.sonyModeVer, cam.profile.dialect))
            }
        }
    }

    /**
     * Phase 5: pull live-view JPEGs (object 0xFFFFC002) as fast as the camera
     * serves them (~20-30 fps on the A7 III) and decode into [frame]. Shares
     * [usbMutex] with the poll/seek so transactions never interleave; frame
     * fetches use few retries so a pending seek step never waits long.
     */
    private fun startLiveView() {
        liveViewJob?.cancel()
        liveViewJob = viewModelScope.launch(Dispatchers.IO) {
            var misses = 0
            val performance = LiveViewPerformance()
            var announcedOff = false
            var announcedDisabled = false

            // Every decoded frame reaches the screen through this one queue, in
            // order, so switching pacing on or off can never show an older
            // frame after a newer one. See [FramePacer] for why recording
            // needs it; outside a recording frames are due the moment they
            // arrive and nothing is delayed.
            val pacer = FramePacer()
            var pacing = false
            val presentation = Channel<Pair<Bitmap, Long>>(
                capacity = 3, onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )
            val shown = if (BuildConfig.DEBUG) LiveViewPresentationPerformance() else null
            val presenter = launch {
                for ((bitmap, dueAtMs) in presentation) {
                    val waitMs = dueAtMs - SystemClock.uptimeMillis()
                    if (waitMs > 0) delay(waitMs)
                    // A teardown clears `camera` before it clears the frame; a
                    // paced frame still waiting must not bring the old picture back.
                    if (camera == null) continue
                    _frame.value = bitmap
                    shown?.shown(System.nanoTime(), pacing)
                }
            }
            // Give the just-opened remote subsystem a brief turn before the
            // first bounded probe; polling and controls stay responsive between
            // every subsequent attempt.
            delay(350)
            try {
            while (isActive) {
                val cam = camera ?: break
                val liveViewState = cam.profile.liveViewState
                if (liveViewState == SonyLiveViewState.UNSUPPORTED ||
                    liveViewState == SonyLiveViewState.DISABLED) {
                    _state.value = _state.value.copy(liveViewState = liveViewState)
                    if (!announcedDisabled) {
                        diag("liveview: disabled for this session (state=$liveViewState, " +
                            "probeFailures=${cam.liveViewProbeFailures}); camera controls remain active")
                        announcedDisabled = true
                    }
                    presenter.cancel()
                    _frame.value = null
                    break
                }
                val probing = liveViewState == SonyLiveViewState.UNVERIFIED

                // Quiet during a capture cycle and shortly after: the camera is
                // busy processing/writing and a fetch there kills the session.
                if (capturing || System.currentTimeMillis() - lastCaptureAt < POST_CAPTURE_QUIET_MS) {
                    delay(250); continue
                }

                // If the camera reports live view off (0xD221, e.g. in menus or
                // some movie states), don't hammer it: unanswered fetches hold
                // the USB mutex for their whole timeout and freeze every other
                // control (this is what froze the app in movie standby).
                //
                // This holds for the capability probe too. A body that says the
                // object is not there right now will answer the fetch by
                // halting bulk-IN, and asking anyway is how an A7 III session
                // died seconds after connecting. D221 going back to 1 is what
                // makes the probe run, so live view still starts by itself.
                val lvStatus = _props.value[cam.profile.dialect.propertyCode(DPC_SONY_LIVEVIEW_STATUS)]?.currentValue
                if (!mayFetchLiveView(liveViewState, lvStatus)) {
                    if (!announcedOff) {
                        diag("liveview: camera reports off (D221=0)")
                        announcedOff = true
                    }
                    delay(500); continue
                }
                announcedOff = false

                val requestedAt = System.nanoTime()
                var fetchingAt = requestedAt
                val fetch = try {
                    runCameraOperation(usbMutex, allowed = {
                        camera === cam && mayFetchLiveView(
                            cam.profile.liveViewState,
                            _props.value[cam.profile.dialect.propertyCode(DPC_SONY_LIVEVIEW_STATUS)]?.currentValue,
                            capturing,
                            System.currentTimeMillis() - lastCaptureAt,
                        )
                    }) {
                        fetchingAt = System.nanoTime()
                        if (probing) cam.probeLiveViewFrame(_props.value)
                        else cam.getLiveViewFrame(tries = 1, timeoutMs = 1200)
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    currentCoroutineContext().ensureActive()
                    // Timeout / desync: eat any late response so the next
                    // transaction doesn't read a stale container, then back off.
                    diag("liveview ${if (probing) "probe" else "fetch"} failed " +
                        "(${e.message}); state=${cam.profile.liveViewState} " +
                        "failures=${cam.liveViewProbeFailures}")
                    runCatching {
                        runCameraOperation(usbMutex, allowed = {
                            camera === cam && !capturing &&
                                System.currentTimeMillis() - lastCaptureAt >= POST_CAPTURE_QUIET_MS
                        }) { transport?.drainInput() }
                    }
                    _state.value = _state.value.copy(liveViewState = cam.profile.liveViewState)
                    if (cam.profile.liveViewState == SonyLiveViewState.DISABLED) continue
                    delay(if (probing) 1500 else 2000)
                    continue
                }
                val jpeg = when (fetch) {
                    CameraOperation.Skipped -> { delay(100); continue }
                    is CameraOperation.Completed -> fetch.value
                }
                if (jpeg == null) {
                    if (BuildConfig.DEBUG) performance.notReady()
                    if (probing) {
                        _state.value = _state.value.copy(liveViewState = cam.profile.liveViewState)
                        diag("liveview probe returned no JPEG " +
                            "(${cam.liveViewProbeFailures}/3); releasing USB bus before retry")
                        if (cam.profile.liveViewState == SonyLiveViewState.DISABLED) continue
                        delay((750L + cam.liveViewProbeFailures * 500L).coerceAtMost(2000L))
                        continue
                    }
                    // Self-heal: a pending captured still (0xFFFFC001) starves the
                    // live-view object. Recheck capture ownership under the USB
                    // lock, then use this dialect's readiness signal before download.
                    if (++misses % 10 == 0) {
                        runCatching {
                            val recovered = runCameraOperation(usbMutex, allowed = {
                                camera === cam && !capturing &&
                                    System.currentTimeMillis() - lastCaptureAt >= POST_CAPTURE_QUIET_MS
                            }) {
                                if (cam.hasPendingPhoto(_props.value)) cam.drainLastPhoto() else null
                            }
                            if (recovered is CameraOperation.Completed) {
                                recovered.value?.let { img -> runCatching { savePhotoToGallery(img) } }
                            }
                        }
                    }
                    // A missing next frame is normal between camera exposures.
                    // Retry briefly OUTSIDE the USB mutex, so focus can proceed.
                    delay(8); continue
                }
                if (probing) {
                    diag("liveview: capability confirmed by a valid JPEG")
                    _state.value = _state.value.copy(liveViewState = cam.profile.liveViewState)
                }
                misses = 0
                val receivedAt = System.nanoTime()
                // A JPEG declaring absurd dimensions allocates its bitmap from
                // the header alone. Dropping a frame is nothing; losing the
                // process mid-shoot is not, and OutOfMemoryError is an Error, so
                // runCatching is what actually catches it.
                runCatching { BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) }
                    .onSuccess { bmp -> bmp?.let {
                        val arrivedAtMs = SystemClock.uptimeMillis()
                        val recording = _state.value.readings?.recording == true
                        if (recording != pacing) {
                            pacing = recording
                            pacer.reset()
                            diag(if (pacing) "liveview: grabando; fotogramas a cadencia regular"
                                else "liveview: sin grabar; fotogramas sin retraso")
                        }
                        presentation.trySend(it to if (pacing) pacer.schedule(arrivedAtMs) else arrivedAtMs)
                        if (BuildConfig.DEBUG) performance.frame(
                            requestedAt, fetchingAt, receivedAt, System.nanoTime(),
                            jpeg.size, it.width, it.height, jpeg.contentHashCode(),
                        )
                    } }
                    .onFailure { diag("liveview: no se pudo decodificar el fotograma: ${it.message}") }
                yield() // Mutex is fair; queued focus/polls run before the next fetch.
            }
            } finally {
                // The presenter is a child: left running, it would keep this job
                // alive forever and could put a frame back after it was cleared.
                presenter.cancel()
                presentation.close()
            }
        }
    }

    // -- control actions ---------------------------------------------------

    /** Run a camera control op serialized against the poll loop; report failures. */
    private fun control(action: (SonyCamera) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val cam = camera ?: return@launch
            try {
                usbMutex.withLock { action(cam) }
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message ?: e.toString())
            }
        }
    }

    /**
     * One full capture cycle, libgphoto2-style (library.c camera_capture):
     * press/release the shutter, then poll 0xD215 (ObjectInMemory) until it
     * reaches >= 0x8000 — ONLY then is the shot ready at 0xFFFFC001. Fetching
     * earlier CRASHES the camera firmware (USB drops, session resets — this was
     * the "se reinicia la conexión" bug on rapid shots). Re-entry is ignored
     * while a cycle is in flight for the same reason.
     */
    private var focusMotion = FocusMotionQueue()
    private var focusFine = false
    private var focusJob: Job? = null
    private var focusInputStartedAt = 0L

    /** Move by the finger's travel, without continuously driving a held wheel. */
    @Synchronized
    fun moveFocus(deltaDp: Float, fine: Boolean = false) {
        if (capturing || _state.value.readings?.manualFocus != true) return
        if (!deltaDp.isFinite() || deltaDp == 0f) return
        if (fine != focusFine) {
            stopFocusDrive()
            focusFine = fine
            focusMotion = FocusMotionQueue(fine)
        }
        if (focusInputStartedAt == 0L) focusInputStartedAt = System.nanoTime()
        focusMotion.add(deltaDp)
        if (!focusMotion.hasSteps()) return
        if (focusJob?.isActive == true) return
        val motion = focusMotion
        val inputStartedAt = focusInputStartedAt
        focusJob = viewModelScope.launch(Dispatchers.IO) {
            var sent = 0
            try {
                while (isActive) {
                    motion.awaitMotion()
                    val cam = camera ?: break
                    if (capturing || _state.value.readings?.manualFocus != true) motion.clear()
                    if (motion.hasSteps()) {
                        val result = runCameraOperation(usbMutex, allowed = {
                            camera === cam && !capturing &&
                                _state.value.readings?.manualFocus == true
                        }) {
                            val prop = _props.value[DPC_SONY_MANUAL_FOCUS]
                            val steps = motion.takeSteps()
                            if (prop != null && steps != 0) {
                                if (BuildConfig.DEBUG && sent == 0) {
                                    Log.d("FocusPerf", "firstMovementToUsbMs=" +
                                        (System.nanoTime() - inputStartedAt) / 1_000_000.0)
                                }
                                cam.driveManualFocus(prop, steps)
                                true
                            } else {
                                false
                            }
                        }
                        if (result is CameraOperation.Completed && result.value) sent++
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                motion.clear()
                diag("focus: movimiento fallido: ${e.message}")
                _state.value = _state.value.copy(error = "No se pudo mover el foco: ${e.message}")
            } finally {
                if (sent > 0) diag("focus: $sent movimiento(s), rueda detenida")
            }
        }
    }

    /** Cancel queued work immediately; an already-sent nudge may finish. */
    @Synchronized
    fun stopFocusDrive() {
        focusMotion.clear()
        // A cancelled worker retains only its old queue, even if a new gesture
        // begins before an in-flight USB transaction returns.
        focusMotion = FocusMotionQueue(focusFine)
        focusInputStartedAt = 0L
        focusJob?.cancel()
        focusJob = null
    }

    /**
     * Clear the monitor's error banner, but only if it still shows [message].
     * A newer error that arrived while the old one was on screen must get its
     * own full display time, not inherit the tail end of the previous one.
     */
    fun dismissError(message: String) {
        val current = _state.value
        if (current.error == message) _state.value = current.copy(error = null)
    }

    /** True when this body offers the manual focus drive at all. */
    fun hasFocusDrive(): Boolean = _props.value.containsKey(DPC_SONY_MANUAL_FOCUS)

    fun capturePhoto() = runStillCapture(bulb = false)

    /**
     * BULB: the first press opens the shutter, the second closes it.
     *
     * The exposure belongs to the person taking it — that is the entire point of
     * the mode — so nothing here estimates or bounds it. A tap toggle rather
     * than press-and-hold, because a bulb frame is a tripod frame and holding a
     * finger on the phone for minutes would shake the rig.
     */
    fun toggleBulb() {
        if (_bulbOpenSince.value != null) {
            bulbCloseRequested = true
            return
        }
        runStillCapture(bulb = true)
    }

    private fun runStillCapture(bulb: Boolean) {
        if (capturing) return
        if (seekJob?.isActive == true) {
            _state.value = _state.value.copy(
                error = "Espera a que termine el cambio de exposición antes de tomar la foto.",
            )
            return
        }
        val capability = camera?.profile?.captureStrategy ?: return
        if (capability == SonyCaptureStrategy.UNSUPPORTED) {
            val msg = "La cámara no anunció un control de disparo compatible."
            diag("capture refused: strategy=UNSUPPORTED")
            _state.value = _state.value.copy(error = msg)
            return
        }
        capturing = true
        val captureToken = ++captureGeneration
        captureJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val cam = camera ?: return@launch
                _state.value = _state.value.copy(error = null)

                // Never silently fire while a known D222 value says "host
                // only". Request host+camera on modern bodies, then trust it
                // only after a fresh 0x9209 readback confirms the card bit.
                val destination = prepareCaptureDestination(cam) ?: return@launch
                if (destination.hostReceives && !prepareHostCaptureSlot(cam)) return@launch
                // What the camera calls adjustable while idle. It greys some of
                // these out for as long as the shot lasts, and that difference
                // is what ends the wait below.
                val baselineEnabled = enabledCodes(_props.value)
                val exposureStartedAt = System.currentTimeMillis()
                lastCaptureAt = exposureStartedAt
                if (bulb) holdBulbShutter(cam)
                else {
                    usbMutex.withLock { cam.capturePhoto() }
                    cam.lastShutterHold?.let { diag("capture: shutter $it") }
                }
                lastCaptureAt = System.currentTimeMillis()
                val heldExposureMs = lastCaptureAt - exposureStartedAt

                if (!destination.hostReceives) {
                    lastCaptureAt = System.currentTimeMillis()
                    diag("capture: camera-only destination confirmed; no host object expected")
                    tellUser("Disparo enviado; destino configurado: tarjeta de la cámara.", long = false)
                    return@launch
                }

                // SDIO signals busy through its properties; QX announces the
                // completed photo through ObjectInfo. A timeout only bounds a
                // failed transfer after release; it never closes BULB.
                val exposure = _props.value[cam.profile.dialect.propertyCode(DPC_SONY_SHUTTER)]
                    ?.currentValue?.let(::shutterReading)
                val exposureSeconds = (exposure as? ShutterReading.Seconds)?.value
                val qxCapture = cam.profile.dialect == SonyDialect.QX
                // libgphoto2 allows QX 35 seconds for its 30-second maximum
                // exposure plus saving. Also allow a matching dark frame for
                // known long exposures and manually held BULB.
                val captureWaitCapMs = if (qxCapture) maxOf(
                    35_000L,
                    ((exposureSeconds ?: 0.0) * 2_000).toLong() + 5_000L,
                    if (bulb) heldExposureMs + 5_000L else 0L,
                ).coerceAtMost(CAPTURE_ABSOLUTE_CAP_MS) else CAPTURE_ABSOLUTE_CAP_MS
                if (exposureSeconds == null || exposureSeconds >= LONG_EXPOSURE_NOTICE_S) {
                    diag("capture: exposure=${exposureSeconds?.let { "${it}s" } ?: exposure}; " +
                            "waiting on the camera's capture readiness")
                    tellUser(
                        "Exposición larga: la foto llegará cuando la cámara termine.",
                        long = false,
                    )
                }

                var img: ByteArray? = null
                val t0 = System.currentTimeMillis()
                var lastBusyAt = t0
                var everBusy = false
                var gaveUp: String? = null
                var pollFailures = 0
                while (isActive) {
                    val now = System.currentTimeMillis()
                    // Both limits are tested *first*, before anything that can
                    // `continue` past them. The absolute cap used to sit at the
                    // bottom of the loop, so a poll that failed every time
                    // jumped straight back to the top and never reached it:
                    // `capturing` then stayed set for the life of the session,
                    // blocking capture, the exposure controls and REC until the
                    // camera was unplugged.
                    if (now - t0 > captureWaitCapMs) {
                        gaveUp = "camera did not offer a photo within ${captureWaitCapMs / 1000}s"
                        break
                    }
                    if (pollFailures >= CAPTURE_MAX_POLL_FAILURES) {
                        gaveUp = "$pollFailures property polls in a row failed; the body stopped answering"
                        break
                    }
                    val ready = try {
                        usbMutex.withLock {
                            // QX processing need not answer a property dump;
                            // its ObjectFormat is the authoritative ready flag.
                            val map = if (qxCapture) _props.value else cam.getAllProps().also {
                                _props.value = it
                                _state.value = _state.value.copy(
                                    readings = decodeReadings(it, cam.sonyModeVer, cam.profile.dialect),
                                )
                            }
                            val pending = cam.hasPendingPhoto(map)
                            pollFailures = 0
                            pending
                        }
                    } catch (e: Exception) {
                        // Processing a still can briefly answer DeviceBusy or
                        // stall one property dump. Keep polling; tearing down
                        // here loses the ready object that often appears on the
                        // next read.
                        pollFailures++
                        diag("capture: transient readiness poll failure ($pollFailures): ${e.message ?: e}")
                        delay(250)
                        continue
                    }
                    if (ready) {
                        img = usbMutex.withLock { cam.drainLastPhoto(tries = 5) }
                        if (img != null) break
                        diag("capture: READY but host object not readable yet; retrying")
                        delay(200)
                        continue
                    }
                    if (qxCapture) {
                        // QX has no D215/property-enable busy contract. Keep
                        // polling ObjectInfo until ready or its bounded timeout.
                        delay(200)
                        continue
                    }
                    if (stillCaptureInProgress(baselineEnabled, _props.value)) {
                        if (!everBusy) diag("capture: camera reports the shot in progress")
                        everBusy = true
                        lastBusyAt = now
                    } else if (everBusy && now - lastBusyAt > CAPTURE_IDLE_GRACE_MS) {
                        gaveUp = "camera finished the shot but never offered an object"
                        break
                    } else if (!everBusy && now - t0 > CAPTURE_NEVER_BUSY_MS) {
                        gaveUp = "camera never reported a shot in progress"
                        break
                    }
                    delay(200)
                }
                if (gaveUp != null) diag("capture: giving up - $gaveUp")

                lastCaptureAt = System.currentTimeMillis()
                if (img != null) {
                    val phoneSave = runCatching { savePhotoToGallery(img) }
                        .onFailure { e -> diag("photo save failed: $e") }
                    if (!qxCapture) collectCompanionObjects(cam)
                    lastCaptureAt = System.currentTimeMillis()
                    val phoneLocation = phoneSave.getOrNull()
                    if (phoneLocation != null) {
                        if (destination == CaptureDestination.HOST_AND_CAMERA) {
                            tellUser(
                                "Foto guardada en ${phoneLocation.userLabel}; la cámara estaba configurada " +
                                    "para guardarla también en la tarjeta.",
                                long = false,
                            )
                        } else {
                            tellUser(
                                "Foto guardada en ${phoneLocation.userLabel}; esta cámara no informó " +
                                    "si también la guardó en la tarjeta.",
                                long = false,
                            )
                        }
                    } else {
                        val msg = if (destination == CaptureDestination.HOST_AND_CAMERA) {
                            "La cámara estaba configurada para la tarjeta, pero falló la copia al teléfono."
                        } else {
                            "La foto llegó desde la cámara, pero no se pudo guardar en el teléfono."
                        }
                        _state.value = _state.value.copy(error = msg)
                        tellUser(msg)
                    }
                } else {
                    diag("capture: image never became ready; $gaveUp")
                    val msg = if (destination == CaptureDestination.HOST_AND_CAMERA) {
                        "La cámara no confirmó el disparo. Revisa que la tarjeta esté insertada y pueda grabar."
                    } else {
                        "La cámara no entregó la foto al teléfono. Revisa el destino de guardado y la tarjeta."
                    }
                    _state.value = _state.value.copy(error = msg)
                    tellUser(msg)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                val msg = e.message ?: e.toString()
                _state.value = _state.value.copy(error = msg)
                tellUser(msg)
            } finally {
                if (captureGeneration == captureToken) {
                    capturing = false
                    captureJob = null
                }
            }
        }
    }

    /**
     * Keep the shutter open until the second press, then close it.
     *
     * The close runs NonCancellable: a teardown mid-exposure cancels this job,
     * and a cancelled `withLock` would skip the release and leave the camera
     * exposing with nobody left to stop it.
     */
    private suspend fun holdBulbShutter(cam: SonyCamera) {
        bulbCloseRequested = false
        usbMutex.withLock { cam.openShutter() }
        val openedAt = System.currentTimeMillis()
        _bulbOpenSince.value = openedAt
        diag("bulb: shutter open")
        try {
            while (!bulbCloseRequested) delay(BULB_TICK_MS)
        } finally {
            _bulbOpenSince.value = null
            val heldMs = System.currentTimeMillis() - openedAt
            withContext(NonCancellable) {
                usbMutex.withLock { runCatching { cam.closeShutter() } }
                    .onSuccess { diag("bulb: shutter closed after ${heldMs}ms") }
                    // Saying "closed" right after "close failed" is how a
                    // transcript ends up describing a shutter that is still open.
                    .onFailure { diag("bulb: SHUTTER MAY STILL BE OPEN after ${heldMs}ms: ${it.message}") }
            }
        }
    }

    /**
     * Return the verified transfer route, or null when taking the shot would
     * knowingly omit the memory card. A PTP OK from D222 is not sufficient:
     * several Sony generations acknowledge that write but leave the menu-owned
     * value unchanged, so every request is followed by fresh property dumps.
     */
    private suspend fun prepareCaptureDestination(cam: SonyCamera): CaptureDestination? {
        fun publish(map: Map<Int, SonyDeviceProp>) {
            _props.value = map
            _state.value = _state.value.copy(readings = decodeReadings(map, cam.sonyModeVer, cam.profile.dialect))
        }

        val saveDestCode = cam.profile.dialect.propertyCode(DPC_SONY_SAVE_DEST)
        val previousRaw = _props.value[saveDestCode]?.currentValue
        val destinationWasAdvertised = previousRaw != null ||
            saveDestCode in cam.profile.vendorProps
        var map = usbMutex.withLock { cam.prepareStillCapture(cam.getAllProps()) }
        publish(map)
        var raw = map[saveDestCode]?.currentValue
        diag("capture destination preflight: D222=${raw?.let { "0x%04X".format(it) } ?: "missing"}")

        // Some Sony dialects do not publish D222 at all. Preserve their prior
        // capture path, but once a body has explicitly reported HOST-only we
        // require a non-null readback containing the card bit before firing.
        val initialRaw = raw
        if (initialRaw == null && !destinationWasAdvertised) {
            return CaptureDestination.UNKNOWN
        }

        // HOST-only and Sony's warm-up placeholders (0 / 0xFFFF) are not
        // evidence that the card is included. Refresh and, if necessary, block
        // rather than interpreting arbitrary status bits.
        initialRaw?.let(::captureDestinationFromRaw)?.let { return it }

        val requestAccepted = try {
            usbMutex.withLock { cam.requestHostAndCameraSaveDestination() }
        } catch (e: Exception) {
            diag("capture destination request failed: ${e.message ?: e}")
            false
        }
        diag("capture destination host+camera request accepted=$requestAccepted")

        if (requestAccepted || destinationWasAdvertised) {
            repeat(8) { attempt ->
                delay(if (attempt == 0) 150 else 200)
                val fresh = try {
                    usbMutex.withLock { cam.getAllProps() }
                } catch (e: Exception) {
                    diag("capture destination readback ${attempt + 1}/8 failed: ${e.message ?: e}")
                    null
                }
                if (fresh != null) {
                    map = fresh
                    publish(map)
                    raw = map[saveDestCode]?.currentValue
                    diag("capture destination readback ${attempt + 1}/8: " +
                        (raw?.let { "0x%04X".format(it) } ?: "missing"))
                    raw?.let(::captureDestinationFromRaw)?.let { return it }
                }
            }
        }

        diag("capture blocked: camera card is not included in D222")
        val menuHelp = if (cam.sonyModeVer >= 3) MODERN_CAMERA_SAVE_MENU else CLASSIC_CAMERA_SAVE_MENU
        _state.value = _state.value.copy(error = menuHelp)
        tellUser(menuHelp)
        return null
    }

    /**
     * Sony exposes one fixed host object handle. Recover a previous READY image
     * before firing again so it cannot be mistaken for the new shot. The object
     * is downloaded only after the dialect's readiness signal is confirmed.
     */
    private suspend fun prepareHostCaptureSlot(cam: SonyCamera): Boolean {
        if (!usbMutex.withLock { cam.hasPendingPhoto(_props.value) }) return true
        diag("capture preflight: previous photo READY; recovering host object before shutter")
        val recovered = usbMutex.withLock { cam.drainLastPhoto(tries = 5) }
        if (recovered != null) {
            val recoverySave = runCatching { savePhotoToGallery(recovered) }
                .onSuccess { diag("capture preflight: recovered pending image (${recovered.size}B)") }
                .onFailure { e -> diag("capture preflight: pending image save failed: $e") }
            if (recoverySave.isFailure) {
                val msg = "Se recuperó una foto anterior, pero no se pudo guardar en el teléfono. " +
                    "Libera espacio y vuelve a intentarlo."
                _state.value = _state.value.copy(error = msg)
                tellUser(msg)
                return false
            }
        }

        repeat(10) { attempt ->
            delay(100)
            val pending = try {
                usbMutex.withLock {
                    val map = cam.getAllProps()
                    _props.value = map
                    _state.value = _state.value.copy(readings = decodeReadings(map, cam.sonyModeVer, cam.profile.dialect))
                    cam.hasPendingPhoto(map)
                }
            } catch (e: Exception) {
                diag("capture preflight clear ${attempt + 1}/10 failed: ${e.message ?: e}")
                null
            }
            if (pending == false) return true
        }

        val msg = "Hay una foto anterior pendiente en la cámara. Espera un momento y pulsa PHOTO otra vez."
        diag("capture blocked: previous host object did not clear")
        _state.value = _state.value.copy(error = msg)
        tellUser(msg)
        return false
    }

    /**
     * Download the rest of a multi-file shot.
     *
     * RAW+JPEG puts two objects behind the one fixed handle, and D215 counts
     * them: libgphoto2 re-reads it after every download and fetches again while
     * it stays at or above 0x8000 (library.c, the Sony branch of
     * camera_wait_for_event). Stopping after the first file left the second
     * pending until the live-view self-heal or the next PHOTO found it, saved
     * under a later timestamp than the shot it belongs to.
     *
     * Bounded on both axes, because a single-file shot pays for this too: a few
     * short reads, and a readiness flag that is still set but has nothing
     * behind it ends the loop on the first empty download.
     */
    private suspend fun collectCompanionObjects(cam: SonyCamera) {
        repeat(MAX_COMPANION_OBJECTS) {
            var pending = false
            for (poll in 0 until COMPANION_READY_POLLS) {
                delay(COMPANION_POLL_MS)
                val map = runCatching {
                    usbMutex.withLock {
                        cam.getAllProps().also {
                            _props.value = it
                            _state.value = _state.value.copy(
                                readings = decodeReadings(it, cam.sonyModeVer, cam.profile.dialect),
                            )
                        }
                    }
                }.getOrNull() ?: continue
                if (cam.hasPendingPhoto(map)) { pending = true; break }
            }
            if (!pending) return
            val extra = runCatching { usbMutex.withLock { cam.drainLastPhoto(tries = 3) } }
                .onFailure { diag("capture: companion download failed: ${it.message ?: it}") }
                .getOrNull() ?: return
            runCatching { savePhotoToGallery(extra) }
                .onSuccess { diag("capture: companion object saved (${extra.size / 1024} KB)") }
                .onFailure { e -> diag("companion photo save failed: $e") }
        }
    }

    /** Save a drained capture into Pictures/SonyMonitor via MediaStore. */
    private fun savePhotoToGallery(img: ByteArray): PhonePhotoLocation {
        val format = CapturedImageFormat.of(img)
        val mime = format.mime
        val name = "SONY_%tY%<tm%<td_%<tH%<tM%<tS_%<tL.%s".format(System.currentTimeMillis(), format.extension)
        if (format == CapturedImageFormat.UNKNOWN) {
            diag("photo: formato no reconocido (${img.take(12).joinToString("") { "%02X".format(it) }}); " +
                "se guarda sin convertir")
        }
        val app = getApplication<Application>()
        if (Build.VERSION.SDK_INT >= 29) {
            val resolver = app.contentResolver
            var inserted: android.net.Uri? = null
            val mediaSave = runCatching {
                inserted = GalleryWriter.insertPending(resolver, name, mime)
                GalleryWriter.publish(resolver, inserted!!, img)
            }
            if (mediaSave.isSuccess) {
                diag("photo saved: $name (${img.size / 1024} KB)")
                return PhonePhotoLocation.GALLERY
            }
            inserted?.let { uri -> runCatching { resolver.delete(uri, null, null) } }
            diag("MediaStore save failed; using app storage: ${mediaSave.exceptionOrNull()}")
        }

        // RELATIVE_PATH does not exist on Android 8/9. App-specific external
        // Pictures also provides a no-permission fallback when MediaStore fails,
        // so the only in-memory copy of a recovered capture is never discarded.
        val root = app.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: app.filesDir
        val dir = File(root, "SonyMonitor")
        check(dir.isDirectory || dir.mkdirs()) { "Could not create ${dir.absolutePath}" }
        val file = File(dir, name)
        file.writeBytes(img)
        diag("photo saved (app storage): ${file.absolutePath} (${img.size / 1024} KB)")
        return PhonePhotoLocation.APP_STORAGE
    }

    /**
     * Drive a control to the exact property/value identity selected by the UI.
     *
     * Replicates libgphoto2's Sony mode-2 algorithm (config.c PUT_SONY_VALUE_ and
     * _put_Sony_ShutterSpeedMode2): 0x9207 steps are ASYNCHRONOUS — the A7 III
     * takes ~0.5-1 s to apply one — so after each step we poll 0x9209 every
     * 200 ms (up to 3 s) until the reported value actually CHANGES, and only then
     * decide the next step. First try is a multi-notch jump (two's-complement
     * UINT8 position difference); if the camera ignores it, fall back to single
     * ±1 steps. Stop on overshoot (value not selectable) or when the value stops
     * moving (mode-locked / end of range). If the body exposes ShutterSpeed2
     * (0xD229), shutter is written absolutely via 0x9205 instead.
     */
    fun seek(ctl: ExposureControl, targetPropCode: Int, targetRaw: Long) {
        if (capturing) {
            _state.value = _state.value.copy(error = "Espera a que termine la captura.")
            return
        }
        seekJob?.cancel()
        val mySeek = SeekState(ctl, targetPropCode, targetRaw)
        _seeking.value = mySeek
        seekJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val modeVer = camera?.sonyModeVer ?: 0
                val dialect = camera?.profile?.dialect ?: SonyDialect.GENERIC
                if (modeVer !in 2..3) {
                    val msg = "Generación Sony sin confirmar; los ajustes quedan en solo lectura."
                    diag("seek refused: modeVer=$modeVer (unknown)")
                    _state.value = _state.value.copy(error = msg)
                    return@launch
                }
                suspend fun refreshProps(): Map<Int, SonyDeviceProp>? {
                    val cam = camera ?: return null
                    val map = usbMutex.withLock { cam.getAllProps() }
                    _props.value = map
                    return map
                }
                suspend fun read(): PickList? {
                    val map = refreshProps() ?: return null
                    val pick = buildPickList(ctl, map, modeVer, apertureLimits, dialect) ?: return null
                    if (pick.propCode != targetPropCode) {
                        diag(
                            "seek ctl=${ctl.label} source changed " +
                                "0x%04X -> 0x%04X; aborting".format(targetPropCode, pick.propCode),
                        )
                        return null
                    }
                    if (targetRaw !in pick.raws) {
                        diag(
                            ("seek ctl=${ctl.label} raw=0x%X disappeared " +
                                "from Enum Set; aborting").format(targetRaw),
                        )
                        return null
                    }
                    return pick
                }

                var pl = read() ?: return@launch
                val propCode = targetPropCode
                val targetIndex = pl.raws.indexOf(targetRaw)
                if (targetIndex < 0) {
                    diag(
                        ("seek ctl=${ctl.label} refused: prop=0x%04X raw=0x%X " +
                            "is no longer in Enum Set").format(propCode, targetRaw),
                    )
                    return@launch
                }
                val targetLabel = pl.labels[targetIndex]
                diag(
                    ("seek ctl=${ctl.label} prop=0x%04X ${pl.labels.getOrNull(pl.index)} " +
                        "(idx=${pl.index}) -> $targetLabel (idx=$targetIndex)").format(propCode),
                )
                if (pl.currentRaw == targetRaw) return@launch

                // How this body wants this property written — taken from the
                // camera's own descriptor, not from the generation. A mode-2 body
                // that happens to expose a directly-settable property gets the
                // absolute path, and vice versa.
                val descriptor = _props.value[propCode] ?: return@launch
                val writeMode = ctl.writeModeFor(descriptor, modeVer)
                if (writeMode == SonyWriteMode.READ_ONLY) {
                    diag("seek ctl=${ctl.label} refused: camera reports it read-only right now")
                    return@launch
                }

                // One absolute write via 0x9205, then wait for the camera to
                // reflect it. No stepping needed at all.
                if (writeMode == SonyWriteMode.ABSOLUTE) {
                    val cam = camera ?: return@launch
                    usbMutex.withLock { cam.setAbsoluteFor(ctl, descriptor, targetRaw) }
                    diag(
                        ("seek ctl=${ctl.label} absolute (0x9205) prop=0x%04X " +
                            "raw=0x%X -> $targetLabel").format(propCode, targetRaw),
                    )
                    val t0 = System.currentTimeMillis()
                    while (System.currentTimeMillis() - t0 < 3000) {
                        delay(200)
                        val map = refreshProps() ?: return@launch
                        // Readback is authoritative even if applying the value
                        // simultaneously removes it from the next Set enum.
                        if (map[propCode]?.currentValue == targetRaw) {
                            diag("seek DONE ctl=${ctl.label} at $targetLabel")
                            return@launch
                        }
                        val observed = ctl.effectiveProp(map, modeVer, dialect)
                        if (observed?.code != propCode) {
                            diag("seek ctl=${ctl.label} source changed during readback; aborting")
                            return@launch
                        }
                        pl = buildPickList(ctl, map, modeVer, apertureLimits, dialect) ?: return@launch
                        if (targetRaw !in pl.raws) {
                            diag(
                                ("seek ctl=${ctl.label} target raw=0x%X disappeared " +
                                    "from Enum Set; aborting").format(targetRaw),
                            )
                            return@launch
                        }
                    }
                    diag("seek absolute not reflected after 3s, at ${pl.labels.getOrNull(pl.index)} (value rejected?)")
                    return@launch
                }

                // Absolute shortcut: mode-2 bodies that expose ShutterSpeed2 accept
                // a direct 0x9205 write of the packed (num<<16|den) value.
                if (dialect != SonyDialect.QX && ctl == ExposureControl.SHUTTER &&
                    _props.value.containsKey(SonyCamera.DPC_SONY_SHUTTER2)) {
                    val cam = camera ?: return@launch
                    usbMutex.withLock {
                        cam.setAbsolute(SonyCamera.DPC_SONY_SHUTTER2, targetRaw, DTC_UINT32)
                    }
                    diag("seek ctl=SHUTTER absolute 0x9205 D229 raw=0x%X".format(targetRaw))
                    val t0 = System.currentTimeMillis()
                    while (System.currentTimeMillis() - t0 < 3000) {
                        delay(200)
                        val map = refreshProps() ?: return@launch
                        if (map[propCode]?.currentValue == targetRaw) {
                            diag("seek DONE (absolute) at $targetLabel")
                            return@launch
                        }
                        val observed = ctl.effectiveProp(map, modeVer, dialect)
                        if (observed?.code != propCode) return@launch
                        pl = buildPickList(ctl, map, modeVer, dialect = dialect) ?: return@launch
                        if (targetRaw !in pl.raws) return@launch
                    }
                    diag("seek absolute write not reflected after 3s")
                    return@launch
                }

                // Byte direction that moves UP our list. Measured on the A7 III
                // (capture 2026-07-14): ISO/EV/aperture step +1 = next list entry,
                // but the shutter notch runs opposite to our fast→slow table
                // (byte +1 = faster = LOWER index), so it starts inverted.
                var sign = if (ctl == ExposureControl.SHUTTER) -1 else 1
                var flipped = false
                // Camera-enumerated props step in enum order and wrap like the
                // physical dial (…204800 → Auto → 50…); only the hardcoded tables
                // can have a genuinely unknown direction worth flipping.
                val enumDriven = ctl == ExposureControl.ISO || ctl == ExposureControl.EV
                // One large jump first, then single steps (libgphoto2 "Update: try
                // the large jump first"; some bodies only accept single steps).
                var allowJump = ctl == ExposureControl.ISO || ctl == ExposureControl.SHUTTER
                repeat(60) {
                    val cam = camera ?: return@launch
                    val pos = pl.index
                    if (pl.currentRaw == targetRaw) {
                        diag("seek DONE ctl=${ctl.label} at $targetLabel")
                        return@launch
                    }
                    val liveTargetIndex = pl.raws.indexOf(targetRaw)
                    if (liveTargetIndex < 0) {
                        diag("seek ctl=${ctl.label} target disappeared from Enum Set")
                        return@launch
                    }
                    val diff = liveTargetIndex - pos
                    if (diff == 0) return@launch
                    val mag = if (allowJump) diff else if (diff > 0) 1 else -1
                    val wasJump = kotlin.math.abs(mag) > 1
                    allowJump = false
                    val step = (mag * sign).coerceIn(-100, 100)
                    usbMutex.withLock { cam.stepBy(propCode, step) }
                    diag("seek ctl=${ctl.label} sent step=$step from idx=$pos (${pl.labels.getOrNull(pos)})")

                    // Wait for the camera to actually apply it. A multi-notch jump
                    // is executed by the camera as an internal walk, so keep
                    // polling until the value SETTLES (two equal reads), not just
                    // until the first intermediate value shows up.
                    var before = pl.currentRaw
                    var changed = false
                    val t0 = System.currentTimeMillis()
                    while (System.currentTimeMillis() - t0 < 3000) {
                        delay(200)
                        pl = read() ?: return@launch
                        if (pl.currentRaw != before) {
                            changed = true
                            if (!wasJump) break
                            before = pl.currentRaw
                        } else if (changed) break   // stopped moving after a jump
                    }

                    if (!changed) {
                        if (wasJump) {
                            diag("seek ctl=${ctl.label} jump ignored, falling back to single steps")
                        } else {
                            diag("seek STALL ctl=${ctl.label} no change after 3s at ${pl.labels.getOrNull(pl.index)} (mode-locked or end of range)")
                            // For aperture that stop belongs to the *lens*, and
                            // the body never announces it. This is the only
                            // moment it can be known, so keep it: the wheel
                            // stops offering anything past here.
                            //
                            // But only when the camera says the aperture is
                            // adjustable right now. The diagnostic above names
                            // both causes of a stall — "mode-locked or end of
                            // range" — and they are not the same fact: in S or
                            // P the body sets the aperture itself and refuses
                            // every notch, which would otherwise be written
                            // down as a lens that stops here. IsEnabled is the
                            // camera's own "is it greyed out".
                            val apProp = _props.value[dialect.propertyCode(DPC_FNUMBER)]
                            val adjustable = apProp?.writeMode(modeVer) == SonyWriteMode.NOTCH
                            if (ctl == ExposureControl.APERTURE && !adjustable) {
                                diag("aperture: stall while the body owns the aperture " +
                                    "(${apProp?.writeMode(modeVer) ?: "no descriptor"}); " +
                                    "not a lens limit, wheel left alone")
                            }
                            if (ctl == ExposureControl.APERTURE && adjustable &&
                                !isSentinelFNumber(pl.currentRaw)
                            ) {
                                apertureLimits = apertureLimits.lockedAt(pl.currentRaw, wider = mag < 0)
                                _state.value = _state.value.copy(apertureLimits = apertureLimits)
                                diag("aperture: lens stops at f/${"%.1f".format(pl.currentRaw / 100.0)} " +
                                    "(${if (mag < 0) "widest" else "narrowest"}); wheel trimmed there")
                            }
                            return@launch
                        }
                    } else {
                        val newPos = pl.index
                        val newTargetIndex = pl.raws.indexOf(targetRaw)
                        if (newTargetIndex < 0) {
                            diag("seek ctl=${ctl.label} target disappeared from Enum Set")
                            return@launch
                        }
                        diag("seek ctl=${ctl.label} applied idx=$pos -> $newPos (${pl.labels.getOrNull(newPos)})")
                        if (pl.currentRaw == targetRaw) {
                            diag("seek DONE ctl=${ctl.label} at $targetLabel")
                            return@launch
                        }
                        val moved = newPos - pos
                        // Compare against the index direction we WANT (mag), not the
                        // byte we sent — after a flip those differ on purpose.
                        if (moved != 0 && Integer.signum(moved) != Integer.signum(mag)) {
                            if (enumDriven) {
                                // The dial wrapped past the end of the reachable
                                // range (e.g. 204800 → Auto): the target isn't
                                // selectable. Undo one notch and stop.
                                diag("seek WRAP ctl=${ctl.label} dial wrapped at idx=$pos, target $targetLabel unreachable — undoing")
                                val undo = if (mag > 0) -sign else sign
                                usbMutex.withLock { cam.stepBy(propCode, undo) }
                                val b2 = pl.currentRaw
                                val t1 = System.currentTimeMillis()
                                while (System.currentTimeMillis() - t1 < 3000) {
                                    delay(200)
                                    pl = read() ?: return@launch
                                    if (pl.currentRaw != b2) break
                                }
                                diag("seek STOP ctl=${ctl.label} settled at ${pl.labels.getOrNull(pl.index)}")
                                return@launch
                            }
                            if (!flipped) {
                                flipped = true; sign = -sign
                                diag("seek FLIP ctl=${ctl.label} notch direction reversed, sign=$sign")
                            } else {
                                diag("seek ABORT ctl=${ctl.label} direction unstable")
                                return@launch
                            }
                        }
                        // A confirmed single step that passed the target: that exact
                        // value isn't selectable right now (libgphoto2 overshoot).
                        if (!wasJump &&
                            ((diff > 0 && newPos > newTargetIndex) ||
                                (diff < 0 && newPos < newTargetIndex))) {
                            diag("seek OVERSHOOT ctl=${ctl.label} $targetLabel not selectable, stopped at ${pl.labels.getOrNull(newPos)}")
                            return@launch
                        }
                    }
                }
                diag("seek EXHAUSTED ctl=${ctl.label} at ${pl.labels.getOrNull(pl.index)} target=$targetLabel")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e                 // a newer wheel gesture superseded this seek
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message ?: e.toString())
            } finally {
                // Unpin the wheel unless a newer seek already took over.
                _seeking.compareAndSet(mySeek, null)
                // On normal completion (not cancellation by a newer seek), reflect
                // the settled value at once so the tiles don't lag a poll behind.
                if (isActive) runCatching {
                    camera?.let { cam ->
                        val map = usbMutex.withLock { cam.getAllProps() }
                        _props.value = map
                        _state.value = _state.value.copy(readings = decodeReadings(map, cam.sonyModeVer, cam.profile.dialect))
                    }
                }
            }
        }
    }

    fun toggleVideo() {
        if (capturing) {
            _state.value = _state.value.copy(error = "Espera a que termine la captura.")
            return
        }
        val cam = camera ?: return
        if (!cam.profile.canMovieRecord) {
            val msg = "La cámara no anunció el control REC (0xD2C8)."
            diag("REC refused: D2C8 not advertised")
            _state.value = _state.value.copy(error = msg)
            return
        }
        val recording = _state.value.readings?.recording == true
        if (recording) {
            control { it.movieRecStop() }
            return
        }
        control { it.movieRecStart() }
        confirmRecordingStarted()
    }

    /**
     * D21D is the camera's own recording state, so whether REC took effect is an
     * observation rather than a guess.
     *
     * An A7 III set to "MOVIE button: Movie Mode Only" acknowledges 0xD2C8 from a
     * still mode and does nothing. That silence is the whole problem: the button
     * used to be disabled outright to avoid it, which also blocked every body and
     * setting that does record from a still mode. Press it, watch D21D, and only
     * say something if the camera ignored the press.
     */
    private fun confirmRecordingStarted() {
        viewModelScope.launch {
            val deadline = System.currentTimeMillis() + REC_CONFIRM_WINDOW_MS
            while (System.currentTimeMillis() < deadline) {
                delay(REC_CONFIRM_POLL_MS)
                if (_state.value.readings?.recording == true) return@launch
                if (camera == null) return@launch
            }
            diag("REC ignored by the camera (D21D still 0 after ${REC_CONFIRM_WINDOW_MS}ms)")
            tellUser(
                if (_state.value.dialect != SonyDialect.QX && _state.value.modeVer == 2 &&
                    !canRecordMovie(_state.value.readings?.modeRaw)) {
                    "La cámara aceptó REC pero no empezó a grabar. En modos de foto " +
                        "el A7 III sólo obedece si tiene MENÚ → Ajustes → Botón MOVIE " +
                        "→ Siempre. Si no, pon el dial en Película."
                } else {
                    "La cámara aceptó REC pero no empezó a grabar."
                }
            )
        }
    }

    /**
     * End the session without leaving the camera holding anything.
     *
     * Two hazards this has to respect, both learned the hard way:
     *
     * A cancelled coroutine finishes *later*. The BULB hold releases the shutter
     * from a NonCancellable `finally`, so closing the transport straight after
     * `cancel()` raced it: the release would find the transport already shut,
     * `runCatching` would swallow the failure, and the camera would be left
     * exposing with nobody to stop it. So the jobs are joined — bounded — before
     * anything is closed, and the bulb hold is asked to end first so the join
     * has something to wait for rather than a cancellation to fight.
     *
     * And this runs from `onCleared` and from a detach, both on the main thread,
     * while `closeSession` is a PTP transaction with an eight second timeout and
     * `close` is a USB syscall. Neither belongs on the main thread, so the
     * closing happens on [cleanupScope], which deliberately outlives
     * `viewModelScope`: cleanup must finish even though the view model is gone.
     */
    @Synchronized
    private fun teardown() {
        // Proved lens ends belong to this session's camera and lens.
        apertureLimits = ApertureLimits()
        captureGeneration++
        // Let a held BULB shutter close itself rather than be cancelled out of
        // its own release path.
        bulbCloseRequested = true
        // A deferred connect belongs to the situation this teardown is ending:
        // after a detach it would only fail against a device that has gone.
        connectRetryJob?.cancel()
        connectRetryJob = null
        val closingConnectJob = connectJob
        closingConnectJob?.cancel()
        val closingClaim = connectionClaim
        connectionClaim = null
        val jobs = listOfNotNull(captureJob, liveViewJob, seekJob, pollJob, connectJob, focusJob)
        stopFocusDrive()
        captureJob = null; liveViewJob = null; seekJob = null
        pollJob = null; connectJob = null

        val closingCamera = camera
        val closingTransport = transport
        camera = null
        transport = null
        connectedDeviceName = null
        capturing = false
        _frame.value = null
        _bulbOpenSince.value = null
        // The descriptors describe the camera that just left. Kept, they made
        // the tiles look adjustable with no session behind them.
        _props.value = emptyMap()
        _seeking.value = null

        cleanupScope.launch {
            val outcome = SessionShutdown(
                joinTimeoutMs = TEARDOWN_JOIN_TIMEOUT_MS,
                lockTimeoutMs = TEARDOWN_LOCK_TIMEOUT_MS,
            ).run(jobs, usbMutex) {
                runCatching { closingCamera?.closeSession() }
                runCatching { closingTransport?.close() }
            }
            if (outcome.jobsTimedOut > 0 || !outcome.closedUnderLock) {
                diag("teardown: ${outcome.jobsTimedOut} trabajo(s) no terminaron a tiempo; " +
                    "cierre bajo el candado=${outcome.closedUnderLock}")
            }
            // Opening resources remain local until publication. Do not let the
            // next session claim USB while their non-cancellable cleanup runs,
            // even if the bounded shutdown join already gave up waiting.
            closingConnectJob?.join()
            closingClaim?.let(CameraSession::release)
        }
    }

    override fun onCleared() = teardown()
}

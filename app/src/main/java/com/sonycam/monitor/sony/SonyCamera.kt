package com.sonycam.monitor.sony

import com.sonycam.monitor.ptp.DeviceInfo
import com.sonycam.monitor.ptp.PtpException
import com.sonycam.monitor.ptp.PtpReader
import com.sonycam.monitor.ptp.PtpSession
import com.sonycam.monitor.ptp.PtpTransport
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Decoded, human-readable snapshot of the main exposure settings. */
data class SonyReadings(
    val iso: String,
    val aperture: String,
    val shutter: String,
    val exposureComp: String,
    val whiteBalance: String,
    val colorTemp: String,
    val wbShiftGm: String,
    val wbShiftAb: String,
    val focusMode: String,
    /** True while the focus ring is the one driving, so remote focus is live. */
    val manualFocus: Boolean,
    val mode: String,
    val modeRaw: Int?,
    val recording: Boolean,
    val propCount: Int,
)

/**
 * Sony-specific PTP layer on top of [PtpSession].
 *
 * Everything model-specific is negotiated at connect time into a [SonyProfile]:
 * which vendor dialect the body speaks, which protocol generation it is, and —
 * per property — how it must be written. Nothing here branches on the model
 * name or the USB product id, so a body nobody has tested behaves correctly as
 * long as it speaks one of the four dialects.
 */
class SonyCamera(transport: PtpTransport) : PtpSession(transport) {

    @Volatile
    var profile: SonyProfile = SonyProfile.UNKNOWN
        private set

    /** Sony protocol generation (2 = stepping, 3 = absolute). */
    val sonyModeVer: Int get() = profile.modeVer

    /** Descriptors we synthesise for mode-3 write-only controls. */
    private var syntheticControls: Map<Int, SonyDeviceProp> = emptyMap()

    /** Failed bounded probes in this session; reset as soon as one JPEG arrives. */
    var liveViewProbeFailures: Int = 0
        private set

    /** D313/D312 only need to be negotiated once before probing the object. */
    private var liveViewPrepared = false

    /** 0x9210 sessions require Sony's DevicePropertyOption flag on writes. */
    var extendedSession: Boolean = false
        private set

    /** Raw bytes of the last property dump — the single most useful diagnostic. */
    var lastPropBlob: ByteArray = ByteArray(0)
        private set

    // -- handshake -----------------------------------------------------------

    /**
     * Refuse, before a session is opened, a device that has announced itself as
     * an MTP responder and offers nothing of a camera.
     *
     * Deliberately narrow. MTP responders and old PTP compacts both answer
     * plain 0x1014 with a property list, so no test separates a camera from a
     * phone in general — and guessing wrong would cost the read-mostly GENERIC
     * path that old DSC bodies rely on. A Sony-branded MTP responder is left to
     * proceed for exactly that reason: it may be a body whose USB mode is MTP
     * instead of PC Remote. What is unambiguous, and all this refuses, is a
     * device that named Microsoft's extension and is not Sony at all.
     */
    private fun refuseIfNotACamera(di: DeviceInfo) {
        if (di.isTrustedSonyCamera || !di.isMtpResponder || di.isSonyBrand) return
        throw NotACamera(di.model, di.manufacturer)
    }

    private fun sdioConnect(phase: Int, p2: Int = 0, p3: Int = 0) {
        // Data-in op; libgphoto2 discards the returned data.
        transact(OC_SDIO_CONNECT, intArrayOf(phase, p2, p3))
    }

    /**
     * New bodies advertise Sony's extended OpenSession (0x9210), whose second
     * parameter selects remote shooting plus content transfer. Older bodies,
     * including the A7 III, continue through the standard PTP OpenSession.
     */
    fun openNegotiatedSession(sessionId: Int = 1, onWarn: ((String) -> Unit)? = null) {
        val di = lastDeviceInfo ?: getDeviceInfo()
        refuseIfNotACamera(di)
        val ops = di.operationsSupported.toSet()
        extendedSession = false
        if (OC_SDIO_OPEN_SESSION !in ops) {
            openStandardSessionWithWarmup(sessionId, onWarn)
            onWarn?.invoke("session: standard PTP 0x1002")
            return
        }
        try {
            transact(OC_SDIO_OPEN_SESSION, intArrayOf(sessionId, SONY_FUNCTION_REMOTE_AND_TRANSFER))
            sessionOpen = true
            extendedSession = true
            onWarn?.invoke("session: Sony SDIO 0x9210 function=remote+transfer")
        } catch (e: PtpException) {
            if (e.code == RC_SESSION_ALREADY_OPEN) {
                sessionOpen = true
                extendedSession = true
                onWarn?.invoke("session: Sony SDIO already open")
            } else if (e.code == RC_OPERATION_NOT_SUPPORTED || e.code == RC_PARAMETER_NOT_SUPPORTED) {
                extendedSession = false
                onWarn?.invoke("session: advertised 0x9210 rejected (${e.message}); using 0x1002")
                openStandardSessionWithWarmup(sessionId, onWarn)
            } else {
                throw e
            }
        }
    }

    /**
     * Some classic bodies return General_Error while their previous PC-Remote
     * context is still winding down. Retry only that valid response, with the
     * bounded settle windows measured on the a6600; every other error fails
     * immediately instead of repeatedly resetting the camera.
     */
    private fun openStandardSessionWithWarmup(
        sessionId: Int,
        onWarn: ((String) -> Unit)?,
    ) {
        val settleMs = longArrayOf(0, 2_000, 2_500, 3_000)
        var last: PtpException? = null
        for ((attempt, delayMs) in settleMs.withIndex()) {
            if (delayMs > 0) Thread.sleep(delayMs)
            try {
                openSession(sessionId)
                if (attempt > 0) onWarn?.invoke(
                    "session: standard PTP ready on retry $attempt after ${delayMs}ms"
                )
                return
            } catch (e: PtpException) {
                if (e.code != RC_GENERAL_ERROR || attempt == settleMs.lastIndex) throw e
                last = e
                onWarn?.invoke(
                    "session: standard PTP still warming up (${e.message}); retrying"
                )
            }
        }
        throw last ?: IllegalStateException("standard PTP session did not open")
    }

    /**
     * Full Sony init. Picks the dialect from what the body advertises, runs that
     * dialect's handshake, and returns the first property snapshot.
     *
     * [onWarn] receives anything that would otherwise be swallowed — parser
     * resyncs, unexpected counts, optional steps that failed. Feed it to the
     * diagnostics transcript; it is what makes an untested body debuggable from
     * a user's log alone.
     */
    fun handshake(onWarn: ((String) -> Unit)? = null): Map<Int, SonyDeviceProp> {
        val di = lastDeviceInfo ?: getDeviceInfo()
        val ops = di.operationsSupported.toMutableSet()

        profile = when {
            OC_SDIO_GET_EXT_DEVICE_INFO in ops -> {
                val negotiated = sdioHandshake(ops, onWarn)
                when {
                    negotiated.has(OC_SDIO_GET_ALL_EXT_DEVICE_PROP_INFO) -> negotiated
                    negotiated.has(OC_SONY_GET_DEVICE_PROPDESC) -> {
                        onWarn?.invoke("Sony SDIO without 0x9209: using per-property 0x9203 reads")
                        negotiated.copy(dialect = SonyDialect.LEGACY_PER_PROP)
                    }
                    negotiated.has(OC_GET_DEVICE_PROP_DESC) -> {
                        onWarn?.invoke("Sony SDIO has no vendor property reader: plain PTP read-only fallback")
                        negotiated.copy(dialect = SonyDialect.GENERIC, modeVer = 0)
                    }
                    else -> {
                        onWarn?.invoke("Sony handshake succeeded but no property-read opcode was advertised")
                        negotiated.copy(dialect = SonyDialect.GENERIC, modeVer = 0)
                    }
                }
            }
            OC_QX_CONNECT in ops -> qxHandshake(ops, onWarn)
            OC_SONY_GET_DEVICE_PROPDESC in ops -> {
                onWarn?.invoke("legacy Sony body: per-property 0x9203 only")
                SonyProfile(SonyDialect.LEGACY_PER_PROP, 0, 2, ops,
                    di.devicePropertiesSupported.toSet(), emptySet(), di.eventsSupported.toSet())
            }
            OC_GET_DEVICE_PROP_DESC in ops && di.devicePropertiesSupported.isNotEmpty() -> {
                onWarn?.invoke("no Sony vendor operations: falling back to plain PTP (read-mostly)")
                SonyProfile(SonyDialect.GENERIC, 0, 0, ops,
                    di.devicePropertiesSupported.toSet(), emptySet(), di.eventsSupported.toSet())
            }
            // Both mean "no camera dialect here", but the useful sentence
            // depends on who is listening: a Sony body needs the PC Remote menu
            // path, a phone needs to be told it is a phone.
            di.isSonyBrand -> throw SonyNotInPcRemoteMode(di.model, di.deviceVersion)
            else -> throw NotACamera(di.model, di.manufacturer)
        }

        val props = getAllProps(onWarn)
        profile = profile.withObservedProps(props)
        prewarmClassicShutter(onWarn)
        onWarn?.invoke("profile: ${profile.summary()}")
        return props
    }

    /** SDIO handshake: connect 1/2, read the vendor code lists, connect 3. */
    private fun sdioHandshake(ops: MutableSet<Int>, onWarn: ((String) -> Unit)?): SonyProfile {
        sdioConnect(1)
        sdioConnect(2)

        // Newer bodies answer empty while PC Remote warms up. Start with the
        // modern request, then explicitly try protocol 200 for bodies (notably
        // some A7C-era firmware) that reject 300. An empty reply is never used as
        // evidence for mode 2.
        var ext = queryExtDeviceInfo(
            requestedVersion = SONY_PROTOCOL_3_00,
            maxAttempts = EXT_DEVICE_INFO_MODERN_TRIES,
            maxWaitMs = EXT_DEVICE_INFO_MODERN_WINDOW_MS,
            onWarn = onWarn,
        )
        if (ext.data.isEmpty()) {
            onWarn?.invoke("SDIO_GetExtDeviceInfo: no modern reply; explicitly trying protocol 200")
            val legacy = queryExtDeviceInfo(
                requestedVersion = SONY_PROTOCOL_2_00,
                maxAttempts = EXT_DEVICE_INFO_LEGACY_TRIES,
                maxWaitMs = EXT_DEVICE_INFO_LEGACY_WINDOW_MS,
                onWarn = onWarn,
            )
            if (legacy.data.isNotEmpty()) ext = legacy
        }

        var version = 0
        val props = LinkedHashSet<Int>()
        val controls = LinkedHashSet<Int>()
        val events = LinkedHashSet<Int>()

        if (ext.data.size < 2) {
            onWarn?.invoke(
                "SDIO_GetExtDeviceInfo returned no usable version after ${ext.totalAttempts} attempts; " +
                    "protocol generation remains UNKNOWN (controls fail closed)"
            )
        } else {
            val r = PtpReader(ext.data)
            version = r.u16()
            // Two u16 arrays follow: the body's property codes and its control
            // codes. Both may also contain operation and event codes, told apart
            // by bit pattern (library.c: xprops[i] & 0x7000).
            val list1 = runCatching { r.u16Array() }.getOrElse { IntArray(0) }
            val list2 = runCatching { r.u16Array() }.getOrElse { IntArray(0) }
            classify(list1, ops, props, events)
            classify(list2, ops, controls, events)
            onWarn?.invoke("SDIO_GetExtDeviceInfo v0x%03X: %d props, %d controls"
                .format(version, list1.size, list2.size))
        }

        val modeVer = when (version) {
            SONY_PROTOCOL_3_00 -> 3
            SONY_PROTOCOL_2_00 -> 2
            else -> 0
        }
        sdioConnect(3)

        // Hand control to the host. Without this newer bodies keep dial priority
        // and quietly ignore host writes. libgphoto2 sends it unconditionally and
        // ignores the result (library.c, right after sdioconnect(3)).
        // (explicit opcode: the profile is not built yet at this point)
        if (OC_SDIO_SET_EXT_DEVICE_PROP_VALUE in ops) {
            setApplicationPriorityWithWarmup(onWarn)
        } else {
            onWarn?.invoke("PriorityMode skipped: 0x9205 was not advertised")
        }

        val all = LinkedHashSet(props).apply { addAll(controls) }
        val profile = SonyProfile(SonyDialect.SDIO, version, modeVer, ops, all, controls, events)

        // Mode-3 bodies keep their write-only controls out of the bulk dump.
        syntheticControls = if (modeVer >= 3) synthesiseControls(all) else emptyMap()
        if (syntheticControls.isNotEmpty()) {
            onWarn?.invoke("synthesised ${syntheticControls.size} mode-3 controls: " +
                syntheticControls.keys.joinToString(",") { "0x%04X".format(it) })
        }
        return profile
    }

    /** The first write can remain busy for several seconds on a6000/a6600-era bodies. */
    private fun setApplicationPriorityWithWarmup(onWarn: ((String) -> Unit)?) {
        val deadline = System.currentTimeMillis() + FIRST_CONTROL_WARMUP_WINDOW_MS
        var attempts = 0
        var lastError: Exception? = null
        do {
            attempts++
            try {
                transact(
                    OC_SDIO_SET_EXT_DEVICE_PROP_VALUE,
                    writeParams(DPC_SONY_PRIORITY_MODE),
                    packDpv(1, DTC_INT8),
                    timeoutMs = FIRST_CONTROL_TIMEOUT_MS,
                )
                if (attempts > 1) onWarn?.invoke("PriorityMode ready on attempt $attempts")
                return
            } catch (e: Exception) {
                lastError = e
                if (e is PtpException &&
                    (e.code == RC_OPERATION_NOT_SUPPORTED || e.code == RC_PARAMETER_NOT_SUPPORTED)) {
                    break
                }
                if (e !is PtpException) runCatching {
                    transport.drainInput(quietPeriodMs = 50, maxDurationMs = 250)
                }
            }
            if (System.currentTimeMillis() < deadline) Thread.sleep(FIRST_CONTROL_BACKOFF_MS)
        } while (System.currentTimeMillis() < deadline)
        onWarn?.invoke("PriorityMode=application unavailable after $attempts attempts: ${lastError?.message}")
    }

    private data class ExtInfoAttempt(
        val data: ByteArray,
        val totalAttempts: Int,
    )

    /** One protocol-version probe with capped exponential-ish backoff. */
    private fun queryExtDeviceInfo(
        requestedVersion: Int,
        maxAttempts: Int,
        maxWaitMs: Long,
        onWarn: ((String) -> Unit)?,
    ): ExtInfoAttempt {
        var lastError: Exception? = null
        var attempts = 0
        val deadline = System.currentTimeMillis() + maxWaitMs
        while (attempts < maxAttempts && System.currentTimeMillis() < deadline) {
            val attempt = ++attempts
            try {
                val data = transact(
                    OC_SDIO_GET_EXT_DEVICE_INFO,
                    intArrayOf(requestedVersion, 1),
                    timeoutMs = EXT_DEVICE_INFO_TIMEOUT_MS,
                ).data
                if (data.size >= 2) {
                    if (attempt > 1) onWarn?.invoke(
                        "SDIO_GetExtDeviceInfo request=0x%03X ready on attempt %d"
                            .format(requestedVersion, attempt)
                    )
                    return ExtInfoAttempt(data, attempt)
                }
            } catch (e: Exception) {
                lastError = e
                // A definite parameter/opcode rejection will not improve with
                // warm-up; move straight to the explicit protocol-200 request.
                if (e is PtpException &&
                    (e.code == RC_PARAMETER_NOT_SUPPORTED || e.code == RC_OPERATION_NOT_SUPPORTED)) {
                    onWarn?.invoke(
                        "SDIO_GetExtDeviceInfo request=0x%03X rejected: %s"
                            .format(requestedVersion, e.message)
                    )
                    return ExtInfoAttempt(ByteArray(0), attempt)
                }
                // A transport timeout can leave a late DATA/RESPONSE container
                // queued; clear it before assigning the next transaction id.
                if (e !is PtpException) runCatching {
                    transport.drainInput(quietPeriodMs = 50, maxDurationMs = 250)
                }
            }
            if (attempt < maxAttempts && System.currentTimeMillis() < deadline) {
                Thread.sleep((EXT_DEVICE_INFO_BACKOFF_BASE_MS * attempt).coerceAtMost(
                    EXT_DEVICE_INFO_BACKOFF_MAX_MS))
            }
        }
        if (lastError != null) onWarn?.invoke(
            "SDIO_GetExtDeviceInfo request=0x%03X last error: %s"
                .format(requestedVersion, lastError.message)
        )
        return ExtInfoAttempt(ByteArray(0), attempts)
    }

    /** QX dialect: same shape as SDIO at the 0x96Fx opcode block. */
    private fun qxHandshake(ops: MutableSet<Int>, onWarn: ((String) -> Unit)?): SonyProfile {
        transact(OC_QX_CONNECT, intArrayOf(1, 0xDA01, 0xDA01))
        transact(OC_QX_CONNECT, intArrayOf(2, 0xDA01, 0xDA01))
        val props = LinkedHashSet<Int>()
        val controls = LinkedHashSet<Int>()
        val events = LinkedHashSet<Int>()
        val data = runCatching {
            transact(OC_QX_GET_EXT_DEVICE_INFO, intArrayOf(SONY_PROTOCOL_2_00)).data
        }.getOrElse { ByteArray(0) }
        var version = 0
        if (data.size > 2) {
            val r = PtpReader(data)
            version = r.u16()
            classify(runCatching { r.u16Array() }.getOrElse { IntArray(0) }, ops, props, events)
            classify(runCatching { r.u16Array() }.getOrElse { IntArray(0) }, ops, controls, events)
        } else {
            onWarn?.invoke("QX_GetExtDeviceInfo returned nothing")
        }
        transact(OC_QX_CONNECT, intArrayOf(3, 0xDA01, 0xDA01))
        val all = LinkedHashSet(props).apply { addAll(controls) }
        val modeVer = if (version == SONY_PROTOCOL_2_00) 2 else 0
        return SonyProfile(SonyDialect.QX, version, modeVer, ops, all, controls, events)
    }

    /**
     * Sort a Sony vendor code list into operations / events / properties.
     * `code and 0x7000`: 0x1000 = operation, 0x4000 = event, 0x5000 = property
     * (0xDxxx property codes land in 0x5000 once masked).
     */
    private fun classify(
        codes: IntArray,
        ops: MutableSet<Int>,
        props: MutableSet<Int>,
        events: MutableSet<Int>,
    ) {
        for (c in codes) when (c and 0x7000) {
            0x1000 -> ops.add(c)
            0x4000 -> events.add(c)
            0x5000 -> props.add(c)
        }
    }

    private fun synthesiseControls(known: Set<Int>): Map<Int, SonyDeviceProp> =
        SONY_MODE3_CONTROLS
            .filterKeys { it in known }
            .mapValues { (code, spec) ->
                val (dataType, formFlag) = spec
                SonyDeviceProp(
                    code = code, dataType = dataType,
                    getSet = 0x81,          // control, button-like: written via 0x9207
                    isEnabled = 1, currentValue = null, formFlag = formFlag,
                    synthetic = true,
                )
            }

    // -- reading -------------------------------------------------------------

    /** All device properties, by whichever mechanism this body supports. */
    fun getAllProps(onWarn: ((String) -> Unit)? = null): Map<Int, SonyDeviceProp> =
        when (profile.dialect) {
            SonyDialect.SDIO -> bulkProps(OC_SDIO_GET_ALL_EXT_DEVICE_PROP_INFO, onWarn)
            SonyDialect.QX -> bulkProps(OC_QX_GET_ALL_DEVICE_PROP_DATA, onWarn)
            SonyDialect.LEGACY_PER_PROP -> perPropProps(OC_SONY_GET_DEVICE_PROPDESC, sony = true)
            SonyDialect.GENERIC -> perPropProps(OC_GET_DEVICE_PROP_DESC, sony = false)
        }

    /**
     * Whether this body can answer 0x9209 with only the properties that changed.
     *
     * Sony's PTP 3 reference gives 0x9209 an operation parameter — `0x00000000`
     * gets all data, `0x00000001` gets only difference data. The PTP 2 reference
     * gives the same opcode **no parameters at all**, so a classic body such as
     * an A7 III has nothing to opt into and keeps taking the whole dump.
     */
    val supportsPropertyDeltas: Boolean
        get() = profile.dialect == SonyDialect.SDIO && profile.modeVer >= 3

    /**
     * Only the properties that changed since the camera last answered, or the
     * full set on a body that cannot do deltas.
     *
     * The difference stream is stateful — the camera tracks what it has already
     * told *this session* — so a caller that drops a reply silently keeps a
     * stale value. Only the idle poll uses this; every control path stays on
     * [getAllProps], because validating a write against a partially cached map
     * is exactly the kind of mistake that has cost this project releases.
     */
    fun getChangedProps(onWarn: ((String) -> Unit)? = null): Map<Int, SonyDeviceProp> {
        if (!supportsPropertyDeltas) return getAllProps(onWarn)
        return bulkProps(OC_SDIO_GET_ALL_EXT_DEVICE_PROP_INFO, onWarn, onlyChanged = true)
    }

    private fun bulkProps(
        opcode: Int,
        onWarn: ((String) -> Unit)?,
        onlyChanged: Boolean = false,
    ): Map<Int, SonyDeviceProp> {
        // A body that takes no parameters must keep receiving none: the request
        // container's length changes with them, and the classic generation is
        // documented without any.
        val params = if (onlyChanged) intArrayOf(GET_ALL_PROPS_ONLY_CHANGED) else IntArray(0)
        val blob = transact(opcode, params).data
        val parsed = parseSonyAllProps(blob, onWarn)
        // A delta is not a snapshot: it must not become the diagnostic blob, and
        // the synthesised mode-3 controls belong to a complete set.
        if (onlyChanged) return parsed
        lastPropBlob = blob
        if (syntheticControls.isEmpty()) return parsed
        // Real descriptors always win over our synthesised stand-ins.
        val out = LinkedHashMap<Int, SonyDeviceProp>(parsed)
        for ((code, p) in syntheticControls) out.putIfAbsent(code, p)
        return out
    }

    /**
     * One descriptor per property, for bodies with no bulk dump. Write-only
     * controls answer with an error here; that is expected, so they are skipped
     * rather than aborting the sweep.
     */
    private fun perPropProps(opcode: Int, sony: Boolean): Map<Int, SonyDeviceProp> {
        val out = LinkedHashMap<Int, SonyDeviceProp>()
        for (code in profile.vendorProps) {
            val data = runCatching { transact(opcode, intArrayOf(code)).data }.getOrNull() ?: continue
            if (data.isEmpty()) continue
            val dpd = if (sony) parseSonyDpd(PtpReader(data)) else parseStandardDpd(data)
            if (dpd != null) out[dpd.code] = dpd
        }
        return out
    }

    // -- writing -------------------------------------------------------------

    /** SetControlDeviceA — absolute property write (0x9205, or 0x96FA on QX). */
    private fun writeA(propcode: Int, value: Long, dataType: Int, timeoutMs: Int = 8000) {
        transact(profile.opSetA, writeParams(propcode), packDpv(value, dataType), timeoutMs)
    }

    /** SetControlDeviceB — control write: buttons, notches (0x9207, or 0x96F8). */
    private fun writeB(propcode: Int, value: Long, dataType: Int, timeoutMs: Int = 8000) {
        transact(profile.opSetB, writeParams(propcode), packDpv(value, dataType), timeoutMs)
    }

    private fun writeParams(propcode: Int): IntArray =
        if (extendedSession && profile.dialect != SonyDialect.QX)
            intArrayOf(profile.dialect.propertyCode(propcode), SONY_CONTROL_FLAG_DEFAULT)
        else intArrayOf(profile.dialect.propertyCode(propcode))

    /** Absolute write. Kept public for the ShutterSpeed2 shortcut and probes. */
    fun setAbsolute(propcode: Int, value: Long, dataType: Int) = writeA(propcode, value, dataType)

    /**
     * Best-effort request for the remote-shoot destination to include both the
     * host and the camera card.
     *
     * Sony documents D222 as a status property and some bodies (including the
     * A7 III) acknowledge but ignore attempts to change it.  Current mode-3
     * bodies may nevertheless accept the same UINT16 value through 0x9205, as
     * used by libgphoto2.  Therefore `true` means only that this session safely
     * issued the request and received PTP OK; it does not mean the setting took
     * effect.  The caller must refresh 0x9209 and verify D222 before promising
     * that a copy will be retained on the card.
     */
    fun requestHostAndCameraSaveDestination(): Boolean {
        if (profile.dialect != SonyDialect.SDIO ||
            profile.modeVer != 3 ||
            !profile.has(OC_SDIO_SET_EXT_DEVICE_PROP_VALUE)) {
            return false
        }
        writeA(DPC_SONY_SAVE_DEST, SAVE_DEST_HOST_AND_CAMERA.toLong(), DTC_UINT16)
        return true
    }

    /**
     * Relative notch step. libgphoto2 always sends these as a single UINT8:
     * 0x01 = one notch up, 0xFF = one notch down, and multi-notch jumps as the
     * two's-complement position difference (config.c PUT_SONY_VALUE_).
     */
    private fun stepControl(propcode: Int, steps: Int) =
        writeB(propcode, (steps and 0xFF).toLong(), DTC_UINT8)

    /**
     * Drive manual focus by [steps], signed: the A7 III advertises
     * `range=(-7, 7, 1)` on 0xD2D1, so the sign is the direction and the size is
     * the speed. libgphoto2 calls this control "Near Far"; our own control table
     * has it as ManualFocusAdjust.
     *
     * It does **not** go through [stepControl], which packs one byte: this
     * property declares itself INT16 (`type=0x0003`), so the descriptor's own
     * type is what goes on the wire.
     */
    fun driveManualFocus(prop: SonyDeviceProp, steps: Int) =
        writeB(prop.code, steps.toLong(), prop.dataType)

    /** Momentary button: 2 = press down, 1 = release. */
    private fun button(propcode: Int, down: Boolean) =
        writeB(propcode, if (down) 2L else 1L, DTC_UINT16)

    /**
     * The first SetControlDeviceB call can take 8–10 seconds on an a6600-class
     * body. Pay that cost during connection with a harmless half-press/release,
     * before Live View starts. Never pre-warm D2E6 because that combined control
     * can create an exposure.
     */
    private fun prewarmClassicShutter(onWarn: ((String) -> Unit)?) {
        if (profile.captureStrategy != SonyCaptureStrategy.HALF_AND_FULL_PRESS) return
        val started = System.currentTimeMillis()
        var downSucceeded = false
        try {
            writeB(DPC_SONY_SHUTTER_HALF, 2, DTC_UINT16, SHUTTER_PREWARM_TIMEOUT_MS)
            downSucceeded = true
            Thread.sleep(100)
        } catch (e: Exception) {
            onWarn?.invoke("shutter pre-warm press failed (non-fatal): ${e.message}")
        } finally {
            runCatching {
                writeB(DPC_SONY_SHUTTER_HALF, 1, DTC_UINT16, SHUTTER_RELEASE_TIMEOUT_MS)
            }.onFailure { e ->
                onWarn?.invoke("shutter pre-warm release failed (non-fatal): ${e.message}")
            }
        }
        onWarn?.invoke(
            "shutter pre-warm ${if (downSucceeded) "complete" else "attempted"} " +
                "in ${System.currentTimeMillis() - started}ms"
        )
    }

    /**
     * Write [value] to [prop] by whatever mechanism the camera says it needs.
     * This is the model-agnostic entry point — callers never decide between
     * 0x9205 and 0x9207 themselves.
     */
    fun writeProp(prop: SonyDeviceProp, value: Long): Boolean {
        when (prop.writeMode(sonyModeVer)) {
            SonyWriteMode.ABSOLUTE -> writeA(prop.code, value, prop.dataType)
            SonyWriteMode.NOTCH -> stepControl(prop.code, value.toInt())
            SonyWriteMode.BUTTON -> button(prop.code, value != 1L)
            SonyWriteMode.LOCK, SonyWriteMode.VARIABLE -> writeB(prop.code, value, prop.dataType)
            SonyWriteMode.READ_ONLY -> return false
        }
        return true
    }

    /**
     * QX requires OperatingMode 2 (Still Capture) before S1/S2. Keep the
     * readback bounded and return the new snapshot for the caller's baseline.
     */
    fun prepareStillCapture(props: Map<Int, SonyDeviceProp>): Map<Int, SonyDeviceProp> {
        if (profile.dialect != SonyDialect.QX) return props
        val mode = props[DPC_QX_OPERATING_MODE]?.currentValue
        check(mode != null) { "La cámara QX no informó su modo de captura." }
        if (mode == 2L) return props
        check(profile.has(OC_QX_SET_CONTROL_DEVICE_A)) { "La cámara QX no permite activar el modo foto." }
        writeA(DPC_QX_OPERATING_MODE, 2, DTC_UINT8)
        repeat(5) { attempt ->
            if (attempt > 0) Thread.sleep(100)
            val observed = getAllProps()
            if (observed[DPC_QX_OPERATING_MODE]?.currentValue == 2L) return observed
        }
        error("La cámara QX no confirmó el modo foto. No se envió el disparo.")
    }

    /**
     * Take a still using only a control the camera advertised. Current mode-3
     * bodies expose the combined S1/S2 button; the A7 III path remains the
     * classic half-press + full-press sequence.
     *
     * On the classic path [holdMs] is only the ceiling. A full press held for
     * a fixed second fired a four-frame burst on an A7 III in continuous Hi —
     * measured, one PHOTO press, four JPEGs. See [holdUntilFrameTaken].
     */
    fun capturePhoto(holdMs: Long = 1000) {
        when (profile.captureStrategy) {
            SonyCaptureStrategy.COMBINED_S1_S2 -> {
                try {
                    // A lost reply does not mean the camera ignored the press.
                    // Arm the release before sending it, not after its reply.
                    button(DPC_SONY_S1_S2_BUTTON, down = true)
                    Thread.sleep(holdMs.coerceIn(50, 250))
                } finally {
                    runCatching { button(DPC_SONY_S1_S2_BUTTON, down = false) }
                }
            }
            SonyCaptureStrategy.HALF_AND_FULL_PRESS -> {
                try {
                    button(DPC_SONY_SHUTTER_HALF, down = true)
                    try {
                        button(DPC_SONY_SHUTTER_RELEASE, down = true)
                        holdUntilFrameTaken(holdMs)
                    } finally {
                        runCatching { button(DPC_SONY_SHUTTER_RELEASE, down = false) }
                    }
                } finally {
                    runCatching { button(DPC_SONY_SHUTTER_HALF, down = false) }
                }
            }
            SonyCaptureStrategy.UNSUPPORTED -> throw UnsupportedOperationException(
                "La cámara no anunció un control seguro para tomar fotos."
            )
        }
    }

    /**
     * Keep the full press down until the body has taken the frame, for at most
     * [maxMs].
     *
     * In AF the press cannot simply be released: an A7 III given a press and an
     * immediate release takes no picture at all (measured). libgphoto2 waits for
     * 0xD213 FocusFound, but on this body that flag turns 2 only ~860 ms after
     * the press, by which time continuous Hi had already fired three frames.
     * What moves at the exposure itself is D215: its count went 0 → 1 at
     * ~400 ms, as the first frame was taken, then 2 and 3 with the rest of the
     * burst. So the first change in D215 ends the hold, and FocusFound still
     * does for a body whose counter lags. A body with neither is held for the
     * ceiling, as libgphoto2 would. A failed read never ends the hold early;
     * the caller's `finally` guarantees the release.
     *
     * MF is not special-cased, although libgphoto2 does not hold there at all:
     * the no-picture result above was measured in AF and nobody has shown that
     * MF behaves otherwise. Waiting for D215 costs MF only the moment the frame
     * takes to register.
     */
    private fun holdUntilFrameTaken(maxMs: Long) {
        val started = System.currentTimeMillis()
        val deadline = started + maxMs
        var baselineObjects: Long? = null
        var reason = "tope"
        try {
            while (System.currentTimeMillis() < deadline) {
                val props = runCatching { getAllProps() }.getOrNull()
                if (props != null) {
                    val objects = props[DPC_SONY_OBJECT_IN_MEMORY]?.currentValue
                    if (baselineObjects == null) baselineObjects = objects
                    else if (objects != null && objects != baselineObjects) {
                        reason = "D215 0x%X→0x%X".format(baselineObjects, objects)
                        return
                    }
                    val found = props[DPC_SONY_FOCUS_FOUND]?.currentValue
                    if (found == FOCUS_FOUND_ACHIEVED || found == FOCUS_FOUND_ACHIEVED_RX0) {
                        reason = "D213=$found"
                        return
                    }
                }
                Thread.sleep(minOf(FOCUS_POLL_MS, (deadline - System.currentTimeMillis()).coerceAtLeast(0)))
            }
        } finally {
            lastShutterHold = "soltado a ${System.currentTimeMillis() - started}ms ($reason)"
        }
    }

    /** How the last classic full press ended, for the diagnostic transcript. */
    var lastShutterHold: String? = null
        private set

    /**
     * Open the shutter and leave it open, for BULB.
     *
     * On success the exposure runs until the caller invokes [closeShutter].
     * An opening failure can mean an accepted press with a lost reply, so it
     * must attempt a release here before propagating the failure. The caller
     * has not yet entered its held-exposure loop and cannot close that case.
     */
    fun openShutter() {
        try {
            when (profile.captureStrategy) {
                SonyCaptureStrategy.COMBINED_S1_S2 -> button(DPC_SONY_S1_S2_BUTTON, down = true)
                SonyCaptureStrategy.HALF_AND_FULL_PRESS -> {
                    button(DPC_SONY_SHUTTER_HALF, down = true)
                    button(DPC_SONY_SHUTTER_RELEASE, down = true)
                }
                SonyCaptureStrategy.UNSUPPORTED -> throw UnsupportedOperationException(
                    "La cámara no anunció un control seguro para tomar fotos."
                )
            }
        } catch (e: Exception) {
            // closeShutter attempts both releases even if the full release fails,
            // and uses bounded timeouts for this uncertain-opening recovery.
            val releaseFailure = runCatching { closeShutter() }.exceptionOrNull()
            if (releaseFailure != null) {
                if (releaseFailure !== e) e.addSuppressed(releaseFailure)
                throw IllegalStateException(
                    "No se pudo confirmar el cierre del obturador. " +
                        "Comprueba la cámara antes de volver a disparar.",
                    e,
                )
            }
            throw e
        }
    }

    /** End a [openShutter] exposure. Releases both buttons even if one fails. */
    /**
     * End a [openShutter] exposure. Releases both buttons even if one fails.
     *
     * Bounded on purpose. These are button writes that answer in milliseconds,
     * but they inherit an 8 second default, and two of them at that timeout is
     * sixteen seconds — long enough that a teardown waiting on this gives up and
     * closes the transport underneath it, which is the race this whole path
     * exists to avoid.
     */
    fun closeShutter(timeoutMs: Int = SHUTTER_RELEASE_TIMEOUT_MS) {
        when (profile.captureStrategy) {
            SonyCaptureStrategy.COMBINED_S1_S2 ->
                writeB(DPC_SONY_S1_S2_BUTTON, 1L, DTC_UINT16, timeoutMs)
            SonyCaptureStrategy.HALF_AND_FULL_PRESS -> {
                try {
                    writeB(DPC_SONY_SHUTTER_RELEASE, 1L, DTC_UINT16, timeoutMs)
                } finally {
                    writeB(DPC_SONY_SHUTTER_HALF, 1L, DTC_UINT16, timeoutMs)
                }
            }
            SonyCaptureStrategy.UNSUPPORTED -> Unit
        }
    }

    fun movieRecStart() {
        check(profile.canMovieRecord) { "La cámara no anunció el control REC (0xD2C8)." }
        button(DPC_SONY_MOVIE_REC, down = true)
    }

    fun movieRecStop() {
        check(profile.canMovieRecord) { "La cámara no anunció el control REC (0xD2C8)." }
        button(DPC_SONY_MOVIE_REC, down = false)
    }

    /** Relative step for the descriptor selected by the exposure capability layer. */
    fun stepBy(propcode: Int, steps: Int) = stepControl(propcode, steps)

    /** Backwards-compatible shorthand for controls without alternate properties. */
    fun stepBy(ctl: ExposureControl, steps: Int) = stepBy(ctl.code, steps)

    /**
     * Absolute write for an exposure control, using libgphoto2's wire types
     * (config.c): ISO/aperture/shutter go as UINT32 — even f-number, whose
     * property is declared UINT16 — and EV as INT16.
     */
    fun setAbsoluteFor(ctl: ExposureControl, propcode: Int, raw: Long) {
        require(propcode == ctl.wireCode(profile.dialect) ||
            (profile.dialect != SonyDialect.QX &&
                ctl == ExposureControl.ISO && propcode == DPC_SONY_ISO2)) {
            "Property 0x%04X does not belong to ${ctl.label}".format(propcode)
        }
        when (ctl) {
            ExposureControl.ISO -> writeA(propcode, raw, DTC_UINT32)
            ExposureControl.APERTURE -> writeA(
                propcode, raw, if (profile.dialect == SonyDialect.QX) DTC_UINT16 else DTC_UINT32,
            )
            ExposureControl.SHUTTER -> writeA(propcode, raw, DTC_UINT32)
            ExposureControl.EV -> writeA(propcode, raw, DTC_INT16)
            // White balance and colour temperature are ordinary absolute
            // properties, not part of the stepped exposure quartet above, so
            // they go on the wire as the *declared* type. Both are UINT16 on
            // the A7 III (`type=0x0004`), measured.
            ExposureControl.WB, ExposureControl.TEMP,
            ExposureControl.FOCUS -> writeA(propcode, raw, DTC_UINT16)
            // Both shift axes are declared UINT8 (`type=0x0002`), measured.
            ExposureControl.WB_GM, ExposureControl.WB_AB -> writeA(propcode, raw, DTC_UINT8)
        }
    }

    /** Backwards-compatible shorthand for controls without alternate properties. */
    fun setAbsoluteFor(ctl: ExposureControl, raw: Long) =
        setAbsoluteFor(ctl, ctl.wireCode(profile.dialect), raw)

    /** QX regular writes use the descriptor's declared type (libgphoto2 generic setter). */
    fun setAbsoluteFor(ctl: ExposureControl, prop: SonyDeviceProp, raw: Long) {
        if (profile.dialect == SonyDialect.QX) {
            require(prop.code == ctl.wireCode(profile.dialect)) {
                "Property 0x%04X does not belong to ${ctl.label}".format(prop.code)
            }
            writeA(prop.code, raw, prop.dataType)
        } else {
            setAbsoluteFor(ctl, prop.code, raw)
        }
    }

    // -- objects: captures and live view -------------------------------------

    /**
     * SDIO announces a pending host photo in D215; QX instead signals it by
     * returning a nonzero ObjectFormat from GetObjectInfo(0xFFFFC001).
     * (reference/libgphoto2/library.c, camera_sony_qx_capture.)
     */
    fun hasPendingPhoto(props: Map<Int, SonyDeviceProp>): Boolean {
        if (profile.dialect != SonyDialect.QX) {
            return ((props[DPC_SONY_OBJECT_IN_MEMORY]?.currentValue ?: 0L) and 0xFFFFL) >= 0x8000L
        }
        return try {
            qxPhotoObjectReady(transact(OC_GET_OBJECT_INFO, intArrayOf(OH_LAST_PHOTO)).data)
        } catch (e: PtpException) {
            when (e.code) {
                RC_INVALID_OBJECT_HANDLE, RC_ACCESS_DENIED, RC_DEVICE_BUSY -> false
                else -> throw e
            }
        }
    }

    private fun qxPhotoObjectReady(info: ByteArray): Boolean =
        info.size >= 6 && (info[4].toInt() != 0 || info[5].toInt() != 0)

    /**
     * Download and discard the pending captured still (object 0xFFFFC001). In
     * PC Remote the body holds each shot for the host to fetch; while one is
     * pending the live-view object can starve (frames stop). Call after every
     * capture ([waitForAppear] = true retries while the shot lands) and from
     * the live-view loop as self-healing. Returns the image bytes (JPEG or ARW
     * depending on the camera's quality setting), or null if nothing was pending.
     */
    fun drainLastPhoto(tries: Int = 10, waitForAppear: Boolean = false): ByteArray? {
        for (i in 0 until tries) {
            try {
                val info = transact(OC_GET_OBJECT_INFO, intArrayOf(OH_LAST_PHOTO)).data
                if (profile.dialect == SonyDialect.QX && !qxPhotoObjectReady(info)) {
                    // A successful metadata request can still mean "not yet".
                    // QX must not receive GetObject until ObjectFormat is set.
                    if (!waitForAppear) return null
                    Thread.sleep(150)
                    continue
                }
                // The one transaction that legitimately runs into tens of
                // megabytes: the captured still. Everything else stays on the
                // small default so a faulty device cannot ask for a RAW-sized
                // allocation from a property read.
                val d = transact(
                    OC_GET_OBJECT, intArrayOf(OH_LAST_PHOTO),
                    maxPayload = MAX_CAPTURED_STILL_BYTES,
                ).data
                if (d.isNotEmpty()) return d
            } catch (e: PtpException) {
                when (e.code) {
                    RC_INVALID_OBJECT_HANDLE ->
                        if (waitForAppear) Thread.sleep(150) else return null
                    RC_ACCESS_DENIED, RC_DEVICE_BUSY -> Thread.sleep(100)
                    else -> return null
                }
            }
        }
        return null
    }

    /**
     * Fetch one live-view JPEG from Sony object 0xFFFFC002: GetObjectInfo primes
     * the frame (retry on Invalid_ObjectHandle = not ready), GetObject downloads
     * it (retry on Access_Denied/Device_Busy = polled too fast). First u32 of the
     * payload is the offset to the JPEG SOI. Returns null if no frame arrived.
     */
    fun getLiveViewFrame(tries: Int = 3, timeoutMs: Int = 2500): ByteArray? {
        if (profile.liveViewState == SonyLiveViewState.UNSUPPORTED ||
            profile.liveViewState == SonyLiveViewState.DISABLED) return null
        var data: ByteArray? = null
        for (i in 0 until tries) {
            // Short timeout: in some states (movie standby, menus) the camera
            // simply never answers these; the caller backs off instead of
            // holding the USB lock for the full default timeout.
            try {
                transact(OC_GET_OBJECT_INFO, intArrayOf(OH_LIVE_VIEW), timeoutMs = timeoutMs)
            } catch (e: PtpException) {
                if (e.code == RC_INVALID_OBJECT_HANDLE) {
                    // A single-attempt preview caller retries outside the USB
                    // mutex. Never sleep after the final attempt while owning it.
                    if (i + 1 < tries) Thread.sleep(50)
                    continue
                }
                if (isConclusiveLiveViewFailure(e)) {
                    disableLiveView()
                    throw e
                }
                // other info errors: still try to grab the object
            }
            try {
                data = transact(
                    OC_GET_OBJECT, intArrayOf(OH_LIVE_VIEW), timeoutMs = timeoutMs,
                    maxPayload = MAX_LIVE_VIEW_BYTES,
                ).data
                break
            } catch (e: PtpException) {
                if (e.code == RC_ACCESS_DENIED || e.code == RC_DEVICE_BUSY) {
                    if (i + 1 < tries) Thread.sleep(20)
                    continue
                }
                if (isConclusiveLiveViewFailure(e)) disableLiveView()
                throw e
            }
        }
        val d = data ?: return null
        if (d.size < 4) return null
        val offset = ByteBuffer.wrap(d, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
        var soi = -1
        if (offset in 0 until d.size - 1 && d[offset] == 0xFF.toByte() && d[offset + 1] == 0xD8.toByte()) {
            soi = offset
        } else {
            for (j in 0 until d.size - 1) {
                if (d[j] == 0xFF.toByte() && d[j + 1] == 0xD8.toByte()) { soi = j; break }
            }
        }
        if (soi < 0) return null
        // Trim after the EOI marker if present (scan backwards).
        var end = d.size
        for (j in d.size - 2 downTo soi) {
            if (d[j] == 0xFF.toByte() && d[j + 1] == 0xD9.toByte()) { end = j + 2; break }
        }
        return d.copyOfRange(soi, end)
    }

    /**
     * One bounded live-view capability probe. It performs no internal retry, so
     * MainViewModel releases the USB mutex between attempts and polling/control
     * traffic always gets a turn. Three independent misses (or one definite
     * unsupported response) disable live view without disconnecting the camera.
     */
    fun probeLiveViewFrame(props: Map<Int, SonyDeviceProp>): ByteArray? {
        if (!profile.shouldProbeLiveView) return null
        return try {
            prepareLiveViewIfAdvertised(props)
            val jpeg = getLiveViewFrame(tries = 1, timeoutMs = LIVE_VIEW_PROBE_TIMEOUT_MS)
            if (jpeg != null) {
                liveViewProbeFailures = 0
                profile = profile.copy(liveViewState = SonyLiveViewState.AVAILABLE)
            } else {
                noteLiveViewProbeFailure()
            }
            jpeg
        } catch (e: Exception) {
            if (profile.liveViewState != SonyLiveViewState.DISABLED) {
                noteLiveViewProbeFailure(conclusive = isConclusiveLiveViewFailure(e))
            }
            throw e
        }
    }

    /** D313/D312 use the special ControlDevice flag parameter on newer bodies. */
    private fun prepareLiveViewIfAdvertised(props: Map<Int, SonyDeviceProp>) {
        if (liveViewPrepared) return
        val hasControlWriter = when (profile.dialect) {
            SonyDialect.SDIO -> profile.has(OC_SDIO_CONTROL_DEVICE)
            SonyDialect.QX -> profile.has(OC_QX_SET_CONTROL_DEVICE_B)
            else -> false
        }
        if (!hasControlWriter) {
            liveViewPrepared = true
            return
        }
        for (code in intArrayOf(DPC_SONY_LIVEVIEW_ENABLE, DPC_SONY_POSTVIEW_ENABLE)) {
            val advertised = code in profile.vendorControls || props[code]?.isControl == true
            if (!advertised) continue
            transact(
                profile.opSetB,
                intArrayOf(code, SONY_CONTROL_FLAG_DEFAULT),
                packDpv(SONY_CONTROL_ENABLE.toLong(), DTC_UINT16),
                timeoutMs = LIVE_VIEW_PREPARE_TIMEOUT_MS,
            )
        }
        liveViewPrepared = true
    }

    private fun isConclusiveLiveViewFailure(e: Exception): Boolean =
        e is PtpException &&
            (e.code == RC_OPERATION_NOT_SUPPORTED || e.code == RC_PARAMETER_NOT_SUPPORTED)

    private fun noteLiveViewProbeFailure(conclusive: Boolean = false) {
        liveViewProbeFailures++
        if (conclusive || liveViewProbeFailures >= LIVE_VIEW_PROBE_FAILURE_LIMIT) disableLiveView()
    }

    private fun disableLiveView() {
        profile = profile.copy(liveViewState = SonyLiveViewState.DISABLED)
    }

    private fun packDpv(value: Long, dataType: Int): ByteArray {
        val size = when (dataType) {
            DTC_INT8, DTC_UINT8 -> 1
            DTC_INT16, DTC_UINT16 -> 2
            DTC_INT32, DTC_UINT32 -> 4
            DTC_INT64, DTC_UINT64 -> 8
            else -> throw IllegalArgumentException("unsupported pack type 0x%04X".format(dataType))
        }
        val buf = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        when (size) {
            1 -> buf.put(value.toByte())
            2 -> buf.putShort(value.toShort())
            4 -> buf.putInt(value.toInt())
            8 -> buf.putLong(value)
        }
        return buf.array()
    }

    companion object {
        const val OC_SDIO_CONNECT = 0x9201
        const val OC_SDIO_GET_EXT_DEVICE_INFO = 0x9202
        const val OC_SDIO_SET_EXT_DEVICE_PROP_VALUE = 0x9205
        const val OC_SDIO_CONTROL_DEVICE = 0x9207
        const val OC_SDIO_GET_ALL_EXT_DEVICE_PROP_INFO = 0x9209

        /** 0x9209 parameter 1: 0 = all data, 1 = only what changed (PTP 3). */
        const val GET_ALL_PROPS_ONLY_CHANGED = 1
        const val SONY_PROTOCOL_2_00 = 0x0C8
        const val SONY_PROTOCOL_3_00 = 0x12C
        const val SONY_ISO_MASK = 0x00FFFFFFL

        /** ISO kinds Sony packs into the high byte of an ISO raw value. */
        const val SONY_ISO_KIND_MFNR = 1L
        const val SONY_ISO_KIND_MFNR_PLUS = 2L
        const val SONY_ISO_KIND_EXTENDED = 0x10L

        /** Bounded warm-up: modern first, then an explicit legacy request. */
        private const val EXT_DEVICE_INFO_MODERN_TRIES = 12
        private const val EXT_DEVICE_INFO_LEGACY_TRIES = 8
        private const val EXT_DEVICE_INFO_MODERN_WINDOW_MS = 5000L
        private const val EXT_DEVICE_INFO_LEGACY_WINDOW_MS = 3000L
        private const val EXT_DEVICE_INFO_TIMEOUT_MS = 2000
        private const val EXT_DEVICE_INFO_BACKOFF_BASE_MS = 100L
        private const val EXT_DEVICE_INFO_BACKOFF_MAX_MS = 600L

        private const val FIRST_CONTROL_WARMUP_WINDOW_MS = 8000L
        private const val FIRST_CONTROL_TIMEOUT_MS = 2000
        private const val FIRST_CONTROL_BACKOFF_MS = 350L
        private const val SHUTTER_PREWARM_TIMEOUT_MS = 12_000
        private const val SHUTTER_RELEASE_TIMEOUT_MS = 4_000

        private const val SONY_FUNCTION_REMOTE_AND_TRANSFER = 2
        private const val SONY_CONTROL_FLAG_DEFAULT = 1
        private const val SONY_CONTROL_ENABLE = 2

        private const val FOCUS_FOUND_ACHIEVED = 2L
        private const val FOCUS_FOUND_ACHIEVED_RX0 = 3L
        private const val FOCUS_POLL_MS = 10L

        private const val LIVE_VIEW_PROBE_TIMEOUT_MS = 700
        private const val LIVE_VIEW_PREPARE_TIMEOUT_MS = 1500
        private const val LIVE_VIEW_PROBE_FAILURE_LIMIT = 3

        const val DPC_SONY_SHUTTER_HALF = 0xD2C1
        const val DPC_SONY_SHUTTER_RELEASE = 0xD2C2
        const val DPC_SONY_REQUEST_ONE_SHOOTING = 0xD2C7
        const val DPC_SONY_MOVIE_REC = 0xD2C8
        const val DPC_SONY_S1_S2_BUTTON = 0xD2E6
        const val DPC_SONY_MOVIE_STATE = 0xD21D
        const val DPC_SONY_POSTVIEW_ENABLE = 0xD312
        const val DPC_SONY_LIVEVIEW_ENABLE = 0xD313

        /** Bit values reported by Still Image Save Destination (0xD222). */
        const val SAVE_DEST_HOST = 0x0001
        const val SAVE_DEST_CAMERA = 0x0010
        const val SAVE_DEST_HOST_AND_CAMERA = 0x0011

        // Absolute-settable twins that only some mode-2 bodies expose
        // (libgphoto2: PTP_DPC_SONY_ShutterSpeed2 / _ISO2).
        const val DPC_SONY_SHUTTER2 = 0xD229
        const val DPC_SONY_ISO2 = 0xD226

        /** Sony live-view frame object handle ("02 C0 FF FF"). */
        const val OH_LIVE_VIEW = -0x3FFE          // 0xFFFFC002
        /** Last captured still ("01 C0 FF FF"). */
        const val OH_LAST_PHOTO = -0x3FFF         // 0xFFFFC001
        const val RC_INVALID_OBJECT_HANDLE = 0x2009
        const val RC_GENERAL_ERROR = 0x2002
        const val RC_ACCESS_DENIED = 0x200F
        const val RC_DEVICE_BUSY = 0x2019
        const val RC_OPERATION_NOT_SUPPORTED = 0x2005
        const val RC_PARAMETER_NOT_SUPPORTED = 0x2006
    }
}

// --- Value decoders (ports of config.c / CameraSetting.cs) -----------------

/** ISO value without Sony's high-byte kind marker. */
fun sonyIsoBaseRaw(raw: Long): Long = raw and SonyCamera.SONY_ISO_MASK

/**
 * Sony packs an ISO *kind* into the high byte: 0 native, 1/2 multi-frame noise
 * reduction, 0x10 the expanded range either side of the native one. Only the
 * MFNR kinds are the "same ISO, different capture mode" duplicates.
 */
fun isSonyIsoMfnr(raw: Long): Boolean =
    (raw ushr 24) in SonyCamera.SONY_ISO_KIND_MFNR..SonyCamera.SONY_ISO_KIND_MFNR_PLUS

fun decodeIso(raw: Long): String {
    val base = sonyIsoBaseRaw(raw)
    val s = if (base == SonyCamera.SONY_ISO_MASK) "Auto" else base.toString()
    if (!isSonyIsoMfnr(raw)) return s
    return "$s +MFNR${if ((raw ushr 24) == SonyCamera.SONY_ISO_KIND_MFNR_PLUS) "+" else ""}"
}

/** Shown for a value the body is explicitly declining to report. */
const val READING_UNAVAILABLE = "--"

/**
 * F-Number (0x5007) is 100x the real value — but three codes are not values at
 * all, and dividing them produced readings like "f/655.35" that looked like
 * facts. Sony's reference lists them under "Value / Description" for 0x5007.
 */
fun decodeFNumber(raw: Long): String = when (raw) {
    0xFFFDL -> "Iris cerrado"
    0xFFFEL, 0xFFFFL -> READING_UNAVAILABLE
    else -> {
        val v = raw / 100.0
        "f/" + (if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString())
    }
}

/**
 * How the body is reporting its shutter right now.
 *
 * BULB and "no reading" are genuinely different states and must not collapse
 * into one another. Sony's reference for Shutter Speed gives 0x00000000 = BULB
 * and 0xFFFFFFFF = "nothing to display"; the old decoder tested only for a zero
 * numerator or denominator, so 0xFFFFFFFF (seen in a real ILCE-7M3 dump during
 * warm-up) came out as 65535/65535 and was displayed as a confident 1".
 *
 * Worse, the same test was what armed the BULB button, so an unknown shutter
 * would have offered to hold the shutter open.
 */
sealed interface ShutterReading {
    /** 0x00000000. The exposure lasts as long as the photographer wants. */
    data object Bulb : ShutterReading

    /** 0xFFFFFFFF, or a pair that cannot be a duration. Report nothing. */
    data object Unavailable : ShutterReading

    /** A real duration, as the numerator/denominator pair and in seconds. */
    data class Seconds(val numerator: Long, val denominator: Long) : ShutterReading {
        val value: Double get() = numerator.toDouble() / denominator.toDouble()
    }
}

/** Decode Shutter Speed (0xD20D / 0xD229). Upper 16 bits over lower 16 bits. */
fun shutterReading(raw: Long): ShutterReading {
    if (raw == SHUTTER_BULB_RAW) return ShutterReading.Bulb
    if (raw == 0xFFFFFFFFL) return ShutterReading.Unavailable
    val num = (raw ushr 16) and 0xFFFF
    val den = raw and 0xFFFF
    if (num == 0L || den == 0L) return ShutterReading.Unavailable
    return ShutterReading.Seconds(num, den)
}

fun decodeShutter(raw: Long): String = when (val s = shutterReading(raw)) {
    ShutterReading.Bulb -> "BULB"
    ShutterReading.Unavailable -> READING_UNAVAILABLE
    is ShutterReading.Seconds -> {
        if (s.numerator == 1L) "1/${s.denominator}"
        else {
            val sec = s.value
            "${if (sec == sec.toLong().toDouble()) sec.toLong().toString() else sec.toString()}\""
        }
    }
}

fun decodeEv(raw: Long): String {
    if (raw == 0L) return "±0.0"
    val v = raw / 1000.0
    return (if (v > 0) "+" else "") + v
}

/**
 * Normalise the exposure-program code (0x500E) across generations.
 *
 * Mode-2 bodies report it as UINT16 (M = 0x0001, A = 0x0003, Movie-M = 0x8053).
 * Mode-3 bodies report the SAME setting as UINT32 with a group in the high word
 * (M = 0x00000001, P = 0x00010002, A = 0x00020003, Movie-M = 0x00078053) — see
 * libgphoto2 config.c `sony_exposure_program_32_modes`. The low 16 bits are
 * identical in both, so masking gives one code space for every body.
 */
fun normalizeExposureMode(raw: Long): Int = (raw and 0xFFFF).toInt()

/** The mode-3 high word: 0 = still PASM, 4 = auto, 5 = scene, 7 = movie, … */
fun exposureModeGroup(raw: Long): Int = (raw ushr 16).toInt()

/**
 * Exposure-program mode, generation-independent. Values from libgphoto2
 * config.c `exposure_program_modes`; anything unknown is shown as its hex code.
 */
fun decodeExposureMode(raw: Long): String = when (normalizeExposureMode(raw)) {
    0x0001 -> "M"
    0x0002 -> "P"
    0x0003 -> "A"
    0x0004 -> "S"
    0x8000 -> "iAuto"
    0x8001 -> "iAuto+"
    0x8008 -> "P_A"; 0x8009 -> "P_S"
    0x8050 -> "Movie-P"; 0x8051 -> "Movie-A"; 0x8052 -> "Movie-S"; 0x8053 -> "Movie-M"
    0x8054 -> "Movie"
    0x8059 -> "S&Q-P"; 0x805A -> "S&Q-A"; 0x805B -> "S&Q-S"; 0x805C -> "S&Q-M"
    0x8084 -> "S&Q-P"; 0x8085 -> "S&Q-A"; 0x8086 -> "S&Q-S"; 0x8087 -> "S&Q-M"
    0x8080 -> "HFR-P"; 0x8081 -> "HFR-A"; 0x8082 -> "HFR-S"; 0x8083 -> "HFR-M"
    0x8088 -> "Movie"; 0x8089 -> "Still"; 0x808A -> "F"
    0x8090 -> "Movie-F"; 0x8091 -> "S&Q-F"
    0x8092 -> "Interval Movie-F"
    0x8093 -> "Interval Movie-P"
    0x8094 -> "Interval Movie-A"
    0x8095 -> "Interval Movie-S"
    0x8096 -> "Interval Movie-M"
    0x8097 -> "Interval Movie-Auto"
    else -> "0x%X".format(raw)
}

// Exposure-program modes in which each jog control is actually adjustable.
// Used only as the mode-2 fallback: on mode-3 bodies the camera's own IsEnabled
// byte answers this question directly and correctly (see ExposureControl.editable).
// Codes are the NORMALISED 16-bit ones, so they cover both generations.
private val APERTURE_MODES = setOf(
    0x0001, 0x0003, 0x8051, 0x8053, 0x8081, 0x8083, 0x8085, 0x8087, 0x805A, 0x805C)
private val SHUTTER_MODES = setOf(
    0x0001, 0x0004, 0x8052, 0x8053, 0x8082, 0x8083, 0x8086, 0x8087, 0x805B, 0x805C)
private val FULLY_AUTO_MODES = setOf(0x8000, 0x8001)
// Full-manual modes: exposure comp only does anything here with ISO on AUTO
// (the body silently ignores the EV notch otherwise — verified 2026-07-14).
private val MANUAL_MODES = setOf(0x0001, 0x8053, 0x8083, 0x8087, 0x805C)

// Movie / S&Q / HFR modes: over PTP the A7 III only starts recording (0xD2C8)
// with the dial physically here — in still modes it ACKs (0x2001) and ignores.
private val MOVIE_MODES = setOf(
    0x8050, 0x8051, 0x8052, 0x8053, 0x8054,
    0x8059, 0x805A, 0x805B, 0x805C, 0x805D,
    0x8080, 0x8081, 0x8082, 0x8083, 0x8084, 0x8085, 0x8086, 0x8087,
    0x8088, 0x808A, 0x8090, 0x8091,
    0x8092, 0x8093, 0x8094, 0x8095, 0x8096, 0x8097,
)

/** Whether PTP movie-record works in this exposure-program mode (null = allow). */
fun canRecordMovie(mode: Int?): Boolean = mode == null || mode in MOVIE_MODES

// --- Exposure controls: the jog-adjustable settings ------------------------

enum class ExposureControl(val label: String, val code: Int) {
    ISO("ISO", DPC_SONY_ISO),
    APERTURE("APERTURE", DPC_FNUMBER),
    SHUTTER("SHUTTER", DPC_SONY_SHUTTER),
    EV("EV", DPC_EXPOSURE_BIAS),
    // Declaration order is display order in the control bar, so the settings a
    // monitor is actually touched for come first and the white-balance
    // fine-tuning — set once, then left alone — goes last.
    FOCUS("FOCUS", DPC_FOCUS_MODE),
    WB("WB", DPC_WHITE_BALANCE),
    TEMP("TEMP", DPC_SONY_COLOR_TEMP),
    WB_GM("G-M", DPC_SONY_WB_SHIFT_GM),
    WB_AB("A-B", DPC_SONY_WB_SHIFT_AB);

    fun wireCode(dialect: SonyDialect): Int = dialect.propertyCode(code)

    fun decode(raw: Long): String = when (this) {
        ISO -> decodeIso(raw)
        APERTURE -> decodeFNumber(raw)
        SHUTTER -> decodeShutter(raw)
        EV -> decodeEv(raw)
        WB -> decodeWhiteBalance(raw)
        TEMP -> decodeColorTemp(raw)
        WB_GM -> decodeWbShift(raw, towardLow = "M", towardHigh = "G")
        WB_AB -> decodeWbShift(raw, towardLow = "B", towardHigh = "A")
        FOCUS -> decodeFocusMode(raw)
    }

    /**
     * Descriptor that represents this setting on the connected body.
     *
     * Sony exposes ISO under two aliases. Newer bodies may publish ISO2 (D226),
     * which libgphoto2 deliberately tries before legacy ISO (D21E). We only
     * prefer a well-formed UINT32 D226 descriptor with a current value and an
     * announced enum/range. An explicitly empty Set enum is still a valid read
     * source, but yields no picker. A disabled D226 does not mask a writable,
     * interactive D21E alias; malformed aliases never become fallbacks.
     */
    fun effectiveProp(
        props: Map<Int, SonyDeviceProp>,
        modeVer: Int = 2,
        dialect: SonyDialect = SonyDialect.SDIO,
    ): SonyDeviceProp? {
        if (dialect == SonyDialect.QX) {
            return props[wireCode(dialect)]?.takeIf { this != ISO || it.isReadableIsoDescriptor() }
        }
        if (this != ISO) return props[code]
        val legacy = props[DPC_SONY_ISO]?.takeIf(SonyDeviceProp::isReadableIsoDescriptor)
        val iso2 = props[SonyCamera.DPC_SONY_ISO2]
            ?.takeIf(SonyDeviceProp::isReadableIsoDescriptor)
            ?: return legacy
        if (legacy == null) return iso2
        if (!iso2.hasRecognizedSelectionForm()) return legacy

        // A disabled ISO2 must not mask a writable legacy ISO alias. If both are
        // locked, retain ISO2 as the richer read source; editable() still fails
        // closed until the body enables one of them.
        val iso2Mode = writeModeFor(iso2, modeVer)
        val legacyMode = writeModeFor(legacy, modeVer)
        return if (iso2Mode == SonyWriteMode.READ_ONLY &&
            legacyMode != SonyWriteMode.READ_ONLY && legacy.hasInteractiveSelection()) {
            legacy
        } else {
            iso2
        }
    }

    /** Write mode for an already-selected descriptor; ISO2 is always absolute. */
    fun writeModeFor(prop: SonyDeviceProp, modeVer: Int): SonyWriteMode {
        val advertised = prop.writeMode(modeVer)
        return if (this == ISO && prop.code == SonyCamera.DPC_SONY_ISO2 &&
            advertised != SonyWriteMode.READ_ONLY) {
            SonyWriteMode.ABSOLUTE
        } else {
            advertised
        }
    }

    /**
     * Mode-2 fallback: whether this control can be changed in the given
     * exposure-program mode. Unknown mode -> allow (fail open). Sending a control
     * the mode forbids just gets silently ignored by the camera, so the UI greys
     * it. EV additionally needs ISO on AUTO when the mode is full manual.
     */
    fun editableIn(mode: Int?, isoAuto: Boolean = true): Boolean {
        if (mode == null) return true
        return when (this) {
            APERTURE -> mode in APERTURE_MODES
            SHUTTER -> mode in SHUTTER_MODES
            ISO -> mode !in FULLY_AUTO_MODES
            EV -> mode !in FULLY_AUTO_MODES && (mode !in MANUAL_MODES || isoAuto)
            // White balance is not an exposure-program setting: every mode that
            // is not fully automatic lets you pick it. Whether the *temperature*
            // is live is a different question, and the camera answers it — it
            // enables 0xD20F only while WB is on C.Temp, so `editable` picks
            // that up from IsEnabled without a table of our own.
            // Neither white balance nor focus is an exposure-program setting:
            // every mode that is not fully automatic lets you pick them.
            WB, TEMP, WB_GM, WB_AB, FOCUS -> mode !in FULLY_AUTO_MODES
        }
    }

    /**
     * Whether this control is adjustable right now, preferring what the camera
     * itself reports over our exposure-program tables.
     *
     * A mode-3 body fills in IsEnabled correctly for every property, so it is
     * authoritative and works on models nobody has tested. Mode-2 bodies fill it
     * in inconsistently, so there we keep the measured tables.
     */
    fun editable(
        props: Map<Int, SonyDeviceProp>,
        modeVer: Int,
        mode: Int?,
        isoAuto: Boolean = true,
        dialect: SonyDialect = SonyDialect.SDIO,
    ): Boolean {
        val p = effectiveProp(props, modeVer, dialect) ?: return false
        if (p.formFlag == DPFF_ENUM && buildPickList(this, props, modeVer, dialect = dialect) == null) return false
        // The old SDIO mode-2 exposure tables and stepping exceptions are not
        // QX restrictions. Its regular controls use SetControlDeviceA.
        if (dialect == SonyDialect.QX) return writeModeFor(p, modeVer) != SonyWriteMode.READ_ONLY
        if (modeVer >= 3) return writeModeFor(p, modeVer) != SonyWriteMode.READ_ONLY
        if (writeModeFor(p, modeVer) == SonyWriteMode.READ_ONLY) return false
        return editableIn(mode, isoAuto)
    }

    /** Short reason shown when the control is locked in the current mode. */
    fun lockReason(mode: Int?): String = when (this) {
        APERTURE -> "A / M only"
        SHUTTER -> "S / M only"
        ISO -> "not in Auto"
        EV -> if (mode in MANUAL_MODES) "ISO AUTO only" else "not in Auto"
        WB -> "not in Auto"
        // The honest reason: the body only enables the kelvin dial while white
        // balance is set to C.Temp.
        TEMP -> "WB = T. color"
        WB_GM, WB_AB, FOCUS -> "not in Auto"
    }
}

/**
 * The ordered list of selectable labels for a control plus the current index.
 * [raws] are the wire encodings behind each label; [currentRaw] is the exact
 * value the camera reported (used to detect when an async write really lands).
 */
data class PickList(
    /** Exact descriptor backing [raws], [currentRaw], and subsequent writes. */
    val propCode: Int,
    val labels: List<String>,
    val index: Int,
    val raws: List<Long>,
    val currentRaw: Long,
)

/** Values the camera currently advertises as safe Set choices. */
fun SonyDeviceProp.settableEnumValues(): List<Long>? =
    if (formFlag == DPFF_ENUM) setEnumValues else null

private fun SonyDeviceProp.hasRecognizedSelectionForm(): Boolean = when (formFlag) {
    // Empty Set is a legitimate read-only state, not a malformed descriptor.
    DPFF_ENUM -> setEnumValues != null
    DPFF_RANGE -> range?.let { (min, max, step) -> step > 0L && min <= max } == true
    else -> false
}

private fun SonyDeviceProp.hasInteractiveSelection(): Boolean = when (formFlag) {
    // Set is the camera's authoritative list of values writable *now*. The
    // current readback may legitimately carry extra state bits (ISO MFNR) or
    // lag behind a changing descriptor, so it need not be one of those values.
    DPFF_ENUM -> setEnumValues?.isNotEmpty() == true
    DPFF_RANGE -> range?.let { (min, max, step) -> step > 0L && min <= max } == true
    DPFF_NONE -> true // legacy bodies may require the canonical fallback table
    else -> false
}

private fun SonyDeviceProp.isReadableIsoDescriptor(): Boolean =
    dataType == DTC_UINT32 && currentValue != null

private fun rangeValues(prop: SonyDeviceProp): List<Long> {
    val (min, max, step) = prop.range ?: return emptyList()
    if (step <= 0L || min > max) return emptyList()
    val out = ArrayList<Long>()
    var value = min
    var guard = 0
    while (value <= max && guard < 2000) {
        out.add(value)
        if (value > Long.MAX_VALUE - step) break
        value += step
        guard++
    }
    return out
}

// Standard 1/3-stop value tables. Sony *mode 2* bodies (A7 III) do NOT publish a
// real enumerated list for aperture/shutter — they report a useless placeholder
// range (0..MAX, step 1). We fall back to these canonical tables, whose raw
// encodings match the camera's own current-value encoding so the reported value
// lands exactly on an entry, and drive the setting by relative steps as usual.
// Mode-3 bodies DO enumerate these, and their own list always wins.

/**
 * The aperture ends this body has actually proved, for the lens mounted now.
 *
 * Aperture is the one exposure control whose range belongs to the *lens*, and
 * classic mode-2 bodies never publish it: the A7 III answers 0x5007 with
 * `FormFlag = RANGE (0, 65535, 1)` — the whole UINT16 space — and no
 * enumeration, warm or cold. (Sony's PTP 3 reference does define 0x5007 as an
 * enumeration, but that is mode-3 behaviour.) So the wheel used to fall back to
 * a canonical f/1.0–f/32 ladder and offer stops no lens on earth has, which the
 * body then silently clamped.
 *
 * Nothing here is guessed. An end is recorded only when the camera **stops
 * moving**: a stalled notch means the lens is against its stop, and that value
 * is the real limit. Until an end is proved, the ladder still reaches that way
 * — refusing to offer anything unproven would leave a wheel one stop wide.
 *
 * Session-scoped and never persisted: it describes the lens on the body right
 * now, and [outgrownBy] notices a swap.
 */
data class ApertureLimits(
    /** Smallest reachable f-number × 100 — the widest aperture. Null: unproved. */
    val widestRaw: Long? = null,
    /** Largest reachable f-number × 100 — the narrowest. Null: unproved. */
    val narrowestRaw: Long? = null,
) {
    /**
     * Keep [raw] and everything the body has not yet ruled out, **plus one
     * exploratory stop past each proved end**.
     *
     * A learned end is a fact about the glass that was on the body when it was
     * learned, and that fact goes stale in ways nothing here can observe:
     *
     * - Mount an f/1.8 lens over an f/4.5 one while both sit at f/8 and the
     *   reported aperture never leaves the old window, so nothing notices.
     * - A variable-aperture zoom moves its own widest end: learn f/5.6 at the
     *   long end, zoom wide, and f/3.5–f/5.0 are suddenly real again.
     * - A single ignored notch is what proves an end, and a transient — a busy
     *   body, a hiccup on the cable — looks exactly like one.
     *
     * Without a way out, any of those locks the wheel out of stops the lens
     * actually has, permanently and invisibly. One stop past each end is the
     * way out: it costs a single value on the wheel instead of the dozen this
     * whole mechanism exists to remove, it re-proves the end at no cost when
     * the end is real, and it lets the camera discover a new lens the moment
     * anyone reaches for it.
     */
    fun allows(raw: Long): Boolean =
        (widestRaw == null || raw >= widestRaw) &&
            (narrowestRaw == null || raw <= narrowestRaw)

    /**
     * [allows], widened by one stop of [ladder] beyond each proved end. This is
     * what the wheel offers; [allows] stays the strict question.
     */
    fun allowsWithProbe(raw: Long, ladder: List<Long>): Boolean {
        if (allows(raw)) return true
        widestRaw?.let { w ->
            val i = ladder.indexOf(w)
            if (i > 0 && raw == ladder[i - 1]) return true
        }
        narrowestRaw?.let { n ->
            val i = ladder.indexOf(n)
            if (i >= 0 && i < ladder.size - 1 && raw == ladder[i + 1]) return true
        }
        return false
    }

    /**
     * True when the body reports an aperture outside what we had proved, which
     * a fixed lens cannot do — someone changed it. The old ends are then lies
     * about a lens that is no longer there.
     */
    fun outgrownBy(raw: Long): Boolean = !isSentinelFNumber(raw) && !allows(raw)

    /** Record a stop the camera refused to move past. */
    fun lockedAt(raw: Long, wider: Boolean): ApertureLimits =
        if (wider) copy(widestRaw = raw) else copy(narrowestRaw = raw)
}

/** The three 0x5007 codes that are messages, not apertures (PTP 3 pág. 175). */
fun isSentinelFNumber(raw: Long): Boolean = raw >= 0xFFFDL

/**
 * White Balance (0x5005), straight from Sony's own table.
 *
 * Every value here is transcribed from the *Camera Control PTP 3 Reference*,
 * pág. 174, not guessed from what a body happened to send. The wheel still only
 * ever offers what the camera announced in its enumeration — this table just
 * puts a name on each code, and says so plainly when it does not know one.
 */
fun decodeWhiteBalance(raw: Long): String = when (raw) {
    0x0001L -> "Manual"
    0x0002L -> "AWB"
    0x0003L -> "Un toque"
    0x0004L -> "Luz día"
    0x0005L -> "Fluorescente"
    0x0006L -> "Incandescente"
    0x0007L -> "Flash"
    0x8001L -> "Fluor. cálido"
    0x8002L -> "Fluor. frío"
    0x8003L -> "Fluor. día"
    0x8004L -> "Fluor. luz día"
    0x8010L -> "Nublado"
    0x8011L -> "Sombra"
    0x8012L -> "T. color"
    0x8020L -> "Pers. 1"
    0x8021L -> "Pers. 2"
    0x8022L -> "Pers. 3"
    0x8023L -> "Personal."
    0x8030L -> "Submarino"
    else -> "0x%04X".format(raw)
}

/**
 * Colour temperature in kelvin (0xD20F), with the two out-of-range codes Sony
 * defines for it.
 *
 * The reference (pág. 448) is explicit: **`0x0000` means "less than 2500K"** and
 * **`0xFFFF` means "greater than 9900K"**, and *"these values are not included
 * in the value of Range (they are only used as CurrentValue)"*. So they are
 * readings, not temperatures — printing 0xFFFF through the ordinary path gave
 * `65535K`, which is the same invented reading as the shutter's `0xFFFFFFFF`
 * showing up as `1"` before v0.9.
 *
 * A body that is not on C.Temp reports 0 here, which reads as "< 2500K" and is
 * still not a temperature you can act on, so both ends stay unambiguous.
 */
fun decodeColorTemp(raw: Long): String = when {
    // Kept to five characters, like "5500K": the tile is narrow and "<2500K"
    // wrapped onto a second line. The TEMP label above it carries the unit.
    raw <= COLOR_TEMP_BELOW_RANGE -> "<2500"
    raw >= COLOR_TEMP_ABOVE_RANGE -> ">9900"
    else -> "${raw}K"
}

/** `0x0000`: the body reports a colour temperature below the advertised range. */
const val COLOR_TEMP_BELOW_RANGE = 0L

/** `0xFFFF`: above the advertised range. Never a kelvin value. */
const val COLOR_TEMP_ABOVE_RANGE = 0xFFFFL

/**
 * Neutral position of both white-balance shift axes.
 *
 * Sony documents that 0xD210 and 0xD21C exist and are UINT8 Get/Set
 * (*Camera Control PTP 3*, págs. 448 and 454) and **stops there** — no mapping
 * from raw value to the shift the camera displays. So this is derived, and the
 * derivation is checkable:
 *
 * The A7 III advertises `range=(164, 220, 1)` on the G-M axis and
 * `range=(164, 220, 2)` on A-B — **57 and 29 selectable values**. Sony's own
 * specification for the body says the colour filter runs *"G7 to M7 (57-step)
 * and A7 to B7 (29-step)"*. The counts match exactly, on both axes, which fixes
 * the scale: 28 raw units each side of centre cover 7 whole steps, so a whole
 * step is 4 raw units — quarter steps on G-M, half steps on A-B.
 *
 * Centre is the midpoint, 192, which is also what an untouched body reports.
 */
const val WB_SHIFT_NEUTRAL = 192L

/** Raw units in one whole shift step. See [WB_SHIFT_NEUTRAL] for the derivation. */
const val WB_SHIFT_UNITS_PER_STEP = 4.0

/**
 * A white-balance shift as the camera writes it: `0`, or a letter and a size.
 *
 * [towardLow] and [towardHigh] are the two ends of the axis. The step counts
 * pin the *scale* but say nothing about which letter sits at which end, so that
 * was **measured on the camera**: each axis was driven to `220` on its own and
 * the live view read.
 *
 * Both came back the opposite of the obvious guess. At 220 the G-M axis turns
 * the picture **green** and the A-B axis turns it **amber** — so the first
 * letter of Sony's own property name ("G-M direction", "A-B direction") sits at
 * the *high* end, not the low one. It stays a parameter, and not a sign buried
 * in the formula, precisely because it is the part no document states.
 */
fun decodeWbShift(raw: Long, towardLow: String, towardHigh: String): String {
    val steps = (raw - WB_SHIFT_NEUTRAL) / WB_SHIFT_UNITS_PER_STEP
    if (steps == 0.0) return "0"
    val size = kotlin.math.abs(steps)
    val text = if (size % 1.0 == 0.0) size.toInt().toString() else "%.2f".format(size).trimEnd('0').trimEnd('.')
    return (if (steps < 0) towardLow else towardHigh) + text
}

/**
 * Focus Mode (0x500A), from Sony's own table (*Camera Control PTP 3*, pág. 176).
 *
 * The names are the ones on the camera's own dial rather than the reference's
 * prose — a body shows "AF-S", not "Automatic(AF S)".
 */
fun decodeFocusMode(raw: Long): String = when (raw) {
    0x0001L -> "MF"
    0x0002L -> "AF-S"
    0x0003L -> "Macro"
    0x8004L -> "AF-C"
    0x8005L -> "AF-A"
    0x8006L -> "DMF"
    0x8007L -> "MF-R"
    0x8008L -> "AF-D"
    0x8009L -> "PF"
    else -> "0x%04X".format(raw)
}

/** Focus modes where the ring — and so the remote focus drive — is live. */
fun isManualFocusMode(raw: Long): Boolean =
    raw == 0x0001L || raw == 0x8006L || raw == 0x8007L

/** f-number * 100, from f/1.0 to f/32 in third-stops (raw for DPC_FNUMBER). */
private val STD_FNUMBERS: List<Long> = longArrayOf(
    100, 110, 120, 140, 160, 180, 200, 220, 250, 280, 320, 350, 400, 450, 500,
    560, 630, 710, 800, 900, 1000, 1100, 1300, 1400, 1600, 1800, 2000, 2200,
    2500, 2900, 3200,
).toList()

/**
 * Canonical ISO list (Auto + third-stops 50..409600), raw encoding = plain value
 * with Auto = 0x00FFFFFF. Fallback for bodies/states where the camera doesn't
 * publish the ISO enum (seen on the A7 IV dump). Unsupported entries are simply
 * rejected by the camera (mode 3) or stopped at the dial end (mode 2).
 */
private val STD_ISOS: List<Long> = buildList {
    add(0x00FFFFFFL) // Auto
    longArrayOf(50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800,
        1000, 1250, 1600, 2000, 2500, 3200, 4000, 5000, 6400, 8000, 10000,
        12800, 16000, 20000, 25600, 32000, 40000, 51200, 64000, 80000, 102400,
        128000, 160000, 204800, 256000, 320000, 409600).forEach { add(it) }
}

/** Canonical EV list: ±5 EV in third-stops, Sony's x.0/x.3/x.7 encoding ×1000. */
private val STD_EVS: List<Long> = buildList {
    for (k in 0..4) { add(k * 1000L); add(k * 1000L + 300); add(k * 1000L + 700) }
    add(5000L)
    val pos = toList()
    clear()
    addAll(pos.map { -it }.filter { it != 0L })
    addAll(pos)
    sort()
}

/**
 * Shutter speeds fast→slow, packed as (numerator shl 16) or denominator to match
 * DPC_SONY_SHUTTER: fast speeds are 1/den; sub-second/long speeds are tenths/10.
 */
private val STD_SHUTTERS: List<Long> = buildList {
    for (den in intArrayOf(8000, 6400, 5000, 4000, 3200, 2500, 2000, 1600, 1250,
        1000, 800, 640, 500, 400, 320, 250, 200, 160, 125, 100, 80, 60, 50, 40,
        30, 25, 20, 15, 13, 10, 8, 6, 5, 4, 3)) {
        add((1L shl 16) or den.toLong())            // 1/den
    }
    for (tenths in intArrayOf(4, 5, 6, 8, 10, 13, 16, 20, 25, 32, 40, 50, 60, 80,
        100, 130, 150, 200, 250, 300)) {
        add((tenths.toLong() shl 16) or 10L)        // tenths/10 seconds
    }
    // One notch past 30", where the physical dial also puts it. Read off an
    // ILCE-7M3 with the dial in BULB: D20D reports 0x00000000, the zero
    // numerator and denominator that [decodeShutter] already renders as BULB.
    add(SHUTTER_BULB_RAW)
}

/** Sony's BULB: no numerator, no denominator, hence no announced duration. */
const val SHUTTER_BULB_RAW = 0L

/**
 * Build the selectable value list for a control: the camera's own enum (in
 * camera order) when it publishes one — ISO/EV on the A7 III; everything on
 * mode-3 bodies — otherwise a canonical third-stop table for aperture/shutter
 * (mode-2 bodies don't enumerate these). The choices always come from the
 * camera's Set enum. A non-exact readback only affects visual centring; it never
 * becomes a writable choice or imports values from the broader Get/Set enum.
 */
fun buildPickList(
    ctl: ExposureControl,
    props: Map<Int, SonyDeviceProp>,
    modeVer: Int = 2,
    apertureLimits: ApertureLimits = ApertureLimits(),
    dialect: SonyDialect = SonyDialect.SDIO,
): PickList? {
    val p = ctl.effectiveProp(props, modeVer, dialect) ?: return null
    val cur = p.currentValue ?: return null
    val announcedEnum = p.settableEnumValues()
    val raws: List<Long> = when {
        // An advertised enum is authoritative, including an empty one. Never
        // fill it with generic values the camera did not announce as selectable.
        p.formFlag == DPFF_ENUM -> when (ctl) {
            // The camera reports the EV enum unsorted (0, then positives, then
            // negatives); sort it so the wheel runs -5 → +5 and the notch
            // direction matches list order.
            ExposureControl.EV -> announcedEnum.orEmpty().sorted()
            // Sony's high byte is an ISO *kind*, not a single "special" flag:
            // EXTENDED marks the expanded range either side of the native one
            // (a6700 Set enum: 50/64/80 and 40000-102400 all carry 0x10), which
            // are ordinary selectable ISOs and must stay. MFNR variants are the
            // ones the physical notch cannot reach, plus — on mode-2 bodies —
            // MFNR-only levels (256000-409600) listed as plain values; stepping
            // toward those wraps the dial past the ceiling back to Auto
            // (measured on the A7 III, whose expanded ceiling is 204800).
            ExposureControl.ISO -> announcedEnum.orEmpty().filter {
                !isSonyIsoMfnr(it) && (dialect == SonyDialect.QX || modeVer >= 3 ||
                    p.code == SonyCamera.DPC_SONY_ISO2 ||
                    (it and 0xFFFFFFL) == 0xFFFFFFL || (it and 0xFFFFFFL) <= 204800L)
            }
            else -> announcedEnum.orEmpty()
        }
        dialect == SonyDialect.QX && p.formFlag == DPFF_RANGE -> rangeValues(p)
        // Unlike the placeholder aperture/shutter ranges on classic mode-2
        // bodies, an ISO range is meaningful capability data and must win over
        // the generic fallback table.
        // A real advertised range is capability data, unlike the placeholder
        // ranges the same body publishes for aperture and shutter.
        (ctl == ExposureControl.ISO || ctl == ExposureControl.TEMP ||
            ctl == ExposureControl.WB_GM || ctl == ExposureControl.WB_AB) &&
            p.formFlag == DPFF_RANGE -> rangeValues(p)
        // The ladder is a scale to count notches along, not a claim about the
        // lens. Everything the body has proved unreachable is dropped, and the
        // value the camera reports right now always stays — otherwise a lens
        // just swapped for a slower one would have no wheel at all.
        ctl == ExposureControl.APERTURE ->
            STD_FNUMBERS.filter { apertureLimits.allowsWithProbe(it, STD_FNUMBERS) || it == cur }
        ctl == ExposureControl.SHUTTER -> STD_SHUTTERS
        // Some bodies/states publish no enum for ISO/EV (A7 IV dump): fall back
        // to canonical tables; invalid entries get rejected by the body.
        ctl == ExposureControl.ISO -> STD_ISOS
        ctl == ExposureControl.EV -> STD_EVS
        p.formFlag == DPFF_RANGE -> rangeValues(p)
        else -> return null
    }
    if (raws.isEmpty()) return null
    // Prefer the exact wire value. ISO readback can carry MFNR flags in its
    // high byte while the Set enum contains the corresponding ordinary ISO;
    // centre on that base value when present. If neither matches, keep the
    // selector usable and centre on the nearest *announced* choice. [raws]
    // remains Set-derived, so a later write can never send the foreign current.
    val centringRaw = if (ctl == ExposureControl.ISO) sonyIsoBaseRaw(cur) else cur
    var idx = raws.indexOf(cur)
    if (idx < 0) idx = raws.indexOf(centringRaw)
    if (idx < 0) {
        // A nearest visual anchor is safe only for an ISO descriptor written
        // absolutely: selecting any item still sends that exact advertised Set
        // raw. Relative mode-2 notches need a trustworthy source position, and
        // other controls keep the conservative exact-current contract.
        if (p.formFlag == DPFF_ENUM &&
            (ctl != ExposureControl.ISO ||
                ctl.writeModeFor(p, modeVer) != SonyWriteMode.ABSOLUTE)) {
            return null
        }
        idx = raws.indices.minByOrNull {
            kotlin.math.abs(raws[it] - centringRaw)
        } ?: 0
    }
    return PickList(p.code, raws.map { ctl.decode(it) }, idx, raws, cur)
}

/** Decode the exposure props into display strings; "--" for anything missing. */
fun decodeReadings(
    props: Map<Int, SonyDeviceProp>,
    modeVer: Int = 2,
    dialect: SonyDialect = SonyDialect.SDIO,
): SonyReadings {
    fun cur(code: Int): Long? = props[dialect.propertyCode(code)]?.currentValue
    val shootingModeRaw = cur(DPC_EXPOSURE_PROGRAM)
    return SonyReadings(
        iso = ExposureControl.ISO.effectiveProp(
            props,
            modeVer,
            dialect,
        )?.currentValue?.let(::decodeIso) ?: "--",
        aperture = cur(DPC_FNUMBER)?.let { decodeFNumber(it) } ?: "--",
        shutter = cur(DPC_SONY_SHUTTER)?.let { decodeShutter(it) } ?: "--",
        exposureComp = cur(DPC_EXPOSURE_BIAS)?.let { decodeEv(it) } ?: "--",
        whiteBalance = cur(DPC_WHITE_BALANCE)?.let { decodeWhiteBalance(it) } ?: READING_UNAVAILABLE,
        // Only while the body says the field means something. A camera that is
        // not on C.Temp disables 0xD20F and parks it at 0, and 0 is Sony's code
        // for "less than 2500K" — so decoding it regardless printed a reading
        // where the camera was reporting none.
        colorTemp = props[dialect.propertyCode(DPC_SONY_COLOR_TEMP)]
            ?.takeIf { it.isEnabled == 1 }
            ?.currentValue?.let { decodeColorTemp(it) } ?: READING_UNAVAILABLE,
        wbShiftGm = cur(DPC_SONY_WB_SHIFT_GM)
            ?.let { decodeWbShift(it, "M", "G") } ?: READING_UNAVAILABLE,
        wbShiftAb = cur(DPC_SONY_WB_SHIFT_AB)
            ?.let { decodeWbShift(it, "B", "A") } ?: READING_UNAVAILABLE,
        focusMode = cur(DPC_FOCUS_MODE)?.let { decodeFocusMode(it) } ?: READING_UNAVAILABLE,
        manualFocus = cur(DPC_FOCUS_MODE)?.let { isManualFocusMode(it) } ?: false,
        mode = shootingModeRaw?.let { decodeExposureMode(it) } ?: "--",
        modeRaw = shootingModeRaw?.let { normalizeExposureMode(it) },
        recording = cur(SonyCamera.DPC_SONY_MOVIE_STATE) == 1L,
        propCount = props.size,
    )
}

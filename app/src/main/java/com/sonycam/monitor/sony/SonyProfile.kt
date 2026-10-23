package com.sonycam.monitor.sony

/**
 * Which Sony vendor dialect a body speaks. Chosen from the operations the camera
 * itself advertises — never from the model name or the USB product id, both of
 * which change per body and per USB mode for no protocol reason.
 */
enum class SonyDialect {
    /**
     * SDIO family: 0x9201 Connect / 0x9202 GetExtDeviceInfo / 0x9209
     * GetAllExtDevicePropInfo. Every E-mount body from ~2013 on, both
     * generations (mode 2 stepping and mode 3 absolute).
     */
    SDIO,

    /**
     * QX family (DSC-QX10/QX30, ILCE-QX1): the same design at a different opcode
     * block — 0x96FE Connect / 0x96FD GetExtDeviceInfo / 0x96F6 GetAllDevicePropData.
     */
    QX,

    /**
     * Pre-SDIO A-mount bodies (A900, A700, SLT-A58/A99). They have 0x9201/0x9202
     * but no bulk property dump; each property is fetched one at a time with
     * 0x9203 GetDevicePropdesc, in the same Sony descriptor layout.
     */
    LEGACY_PER_PROP,

    /**
     * No Sony vendor operations at all: plain ISO-15740 PTP via 0x1014/0x1015.
     * Old DSC compacts, and any body whose USB mode is MTP/MSC instead of
     * PC Remote. Read-mostly.
     */
    GENERIC,
}

/** How this body has proved that it can trigger a still image. */
enum class SonyCaptureStrategy {
    /** No advertised write-only control can safely trigger a capture. */
    UNSUPPORTED,

    /** Mode-3 combined S1/S2 button (0xD2E6), used by current Sony bodies. */
    COMBINED_S1_S2,

    /** Classic half-press + full-press sequence used by the A7 III generation. */
    HALF_AND_FULL_PRESS,
}

/**
 * Live view is deliberately a state, not a dialect-wide Boolean. Sony bodies
 * can expose the same SDIO command set while omitting the 0xFFFFC002 object.
 */
enum class SonyLiveViewState {
    /** The dialect/standard object operations cannot provide Sony live view. */
    UNSUPPORTED,

    /** The prerequisites exist, but a short object probe has not succeeded yet. */
    UNVERIFIED,

    /** At least one valid JPEG was received in this session. */
    AVAILABLE,

    /** A conclusive or repeated probe failure disabled it for this session. */
    DISABLED,
}

/**
 * Everything about the connected body that the rest of the app is allowed to
 * branch on. Built once at connect time from GetDeviceInfo plus the Sony
 * handshake, so a model nobody has ever tested still gets a correct profile.
 */
data class SonyProfile(
    val dialect: SonyDialect,
    /** Raw protocol version from SDIO_GetExtDeviceInfo (0x0C8 / 0x12C), 0 if unknown. */
    val protocolVersion: Int,
    /** 2 = pre-2020 stepping generation, 3 = 2020+ absolute generation. */
    val modeVer: Int,
    /** Operations the body supports, after merging the Sony vendor list. */
    val ops: Set<Int>,
    /** Device-property codes from the vendor lists (0x5xxx-class after masking). */
    val vendorProps: Set<Int>,
    /** Codes the vendor "controls" list contributed, kept for diagnostics. */
    val vendorControls: Set<Int>,
    val vendorEvents: Set<Int>,
    val captureStrategy: SonyCaptureStrategy = SonyCaptureStrategy.UNSUPPORTED,
    val canMovieRecord: Boolean = false,
    val liveViewState: SonyLiveViewState = SonyLiveViewState.UNSUPPORTED,
) {
    fun has(op: Int) = op in ops

    /** Opcode for an absolute property write ("SetControlDeviceA"). */
    val opSetA: Int
        get() = if (dialect == SonyDialect.QX) OC_QX_SET_CONTROL_DEVICE_A
                else SonyCamera.OC_SDIO_SET_EXT_DEVICE_PROP_VALUE

    /** Opcode for a control write — buttons and notches ("SetControlDeviceB"). */
    val opSetB: Int
        get() = if (dialect == SonyDialect.QX) OC_QX_SET_CONTROL_DEVICE_B
                else SonyCamera.OC_SDIO_CONTROL_DEVICE

    /** True only after this session has actually received a valid live-view JPEG. */
    val canLiveView: Boolean get() = liveViewState == SonyLiveViewState.AVAILABLE

    /** Whether a bounded, low-frequency probe is safe and still useful. */
    val shouldProbeLiveView: Boolean get() = liveViewState == SonyLiveViewState.UNVERIFIED

    /** Whether the host can drive settings at all, or is limited to reading. */
    val canControl: Boolean
        get() = dialect != SonyDialect.GENERIC &&
            (dialect != SonyDialect.SDIO || modeVer in 2..3)

    /** Large objects can be pulled in chunks instead of one huge bulk read. */
    val hasPartialLargeObject: Boolean get() = has(OC_SDIO_GET_PARTIAL_LARGE_OBJECT)

    /** The camera can supply human-readable labels for its own enum values. */
    val hasDisplayStringList: Boolean get() = has(OC_SDIO_GET_DISPLAY_STRING_LIST)

    val hasLensInformation: Boolean get() = has(OC_SDIO_GET_LENS_INFORMATION)

    /**
     * Finish capability negotiation after the first descriptor snapshot. A
     * control counts only when it was in Sony's controls list or arrived as an
     * actual control descriptor; merely knowing a D2xx numeric code is not
     * enough to send a write-only command to an untested body.
     */
    fun withObservedProps(props: Map<Int, SonyDeviceProp>): SonyProfile {
        fun controlIsAdvertised(code: Int): Boolean {
            val wireCode = dialect.propertyCode(code)
            return wireCode in vendorControls || props[wireCode]?.isControl == true
        }

        val canWriteControls = when (dialect) {
            SonyDialect.SDIO -> has(SonyCamera.OC_SDIO_CONTROL_DEVICE)
            SonyDialect.QX -> has(OC_QX_SET_CONTROL_DEVICE_B)
            else -> false
        }
        val capture = when {
            !canWriteControls -> SonyCaptureStrategy.UNSUPPORTED
            dialect == SonyDialect.SDIO && modeVer >= 3 &&
                controlIsAdvertised(SonyCamera.DPC_SONY_S1_S2_BUTTON) ->
                SonyCaptureStrategy.COMBINED_S1_S2
            controlIsAdvertised(SonyCamera.DPC_SONY_SHUTTER_HALF) &&
                controlIsAdvertised(SonyCamera.DPC_SONY_SHUTTER_RELEASE) ->
                SonyCaptureStrategy.HALF_AND_FULL_PRESS
            else -> SonyCaptureStrategy.UNSUPPORTED
        }
        val liveView = if (
            (dialect == SonyDialect.SDIO || dialect == SonyDialect.QX) &&
            has(OC_PTP_GET_OBJECT_INFO) && has(OC_PTP_GET_OBJECT)
        ) SonyLiveViewState.UNVERIFIED else SonyLiveViewState.UNSUPPORTED

        return copy(
            captureStrategy = capture,
            canMovieRecord = canWriteControls && controlIsAdvertised(SonyCamera.DPC_SONY_MOVIE_REC),
            liveViewState = liveView,
        )
    }

    fun summary(): String =
        "dialect=$dialect mode=$modeVer proto=0x%03X ops=${ops.size} ".format(protocolVersion) +
            "vprops=${vendorProps.size} vctrls=${vendorControls.size} " +
            "capture=$captureStrategy rec=$canMovieRecord liveView=$liveViewState " +
            "partialLargeObj=$hasPartialLargeObject displayStrings=$hasDisplayStringList"

    companion object {
        /** Fallback used before the handshake runs. */
        val UNKNOWN = SonyProfile(
            SonyDialect.GENERIC, 0, 0, emptySet(), emptySet(), emptySet(), emptySet())
    }
}

private const val OC_PTP_GET_OBJECT_INFO = 0x1008
private const val OC_PTP_GET_OBJECT = 0x1009

// --- Vendor opcodes beyond the core five ------------------------------------
// Present only on newer bodies; always gate on profile.has(...) before use.

const val OC_SDIO_OPEN_SESSION = 0x9210
const val OC_SDIO_GET_PARTIAL_LARGE_OBJECT = 0x9211
const val OC_SDIO_SET_CONTENTS_TRANSFER_MODE = 0x9212
const val OC_SDIO_GET_DISPLAY_STRING_LIST = 0x9215
const val OC_SDIO_GET_LENS_INFORMATION = 0x9223
const val OC_SDIO_OPERATIONS_RESULTS_SUPPORTED = 0x922F

/** Legacy per-property descriptor fetch (A900 / A700 / SLT). */
const val OC_SONY_GET_DEVICE_PROPDESC = 0x9203
const val OC_SONY_GET_DEVICE_PROP_VALUE = 0x9204

// QX block
const val OC_QX_GET_ALL_DEVICE_PROP_DATA = 0x96F6
const val OC_QX_SET_CONTROL_DEVICE_B = 0x96F8
const val OC_QX_SET_CONTROL_DEVICE_A = 0x96FA
const val OC_QX_GET_EXT_DEVICE_INFO = 0x96FD
const val OC_QX_CONNECT = 0x96FE

/** Standard PTP property access, for the GENERIC dialect. */
const val OC_GET_DEVICE_PROP_DESC = 0x1014
const val OC_SET_DEVICE_PROP_VALUE = 0x1016

/**
 * Controls that a mode-3 body lists in its vendor property codes but does NOT
 * return from GetAllExtDevicePropInfo, because they are write-only buttons and
 * ranges. Querying them answers PTP 0x2002 — that is expected, not a failure.
 * We synthesise descriptors for them so the UI can offer them like any other
 * control. (libgphoto2 ptp.c `sony_mode3_controls`.)
 */
val SONY_MODE3_CONTROLS: Map<Int, Pair<Int, Int>> = mapOf(
    //  code   to  (dataType    to formFlag)
    0xD2C1 to (DTC_UINT16 to DPFF_ENUM),   // ShutterHalfRelease
    0xD2C2 to (DTC_UINT16 to DPFF_ENUM),   // ShutterRelease
    0xD2C3 to (DTC_UINT16 to DPFF_ENUM),   // AEL button
    0xD2C4 to (DTC_UINT16 to DPFF_ENUM),   // AFL button
    0xD2C7 to (DTC_UINT16 to DPFF_ENUM),   // RequestOneShooting
    0xD2C8 to (DTC_UINT16 to DPFF_ENUM),   // MovieRecButtonHold
    0xD2C9 to (DTC_UINT16 to DPFF_ENUM),   // FEL button
    0xD2D1 to (DTC_UINT16 to DPFF_RANGE),  // ManualFocusAdjust
    0xD2D9 to (DTC_UINT16 to DPFF_ENUM),   // AWBL button
    0xD2DC to (DTC_UINT32 to DPFF_RANGE),  // AFAreaPosition
    0xD2DD to (DTC_INT8 to DPFF_RANGE),    // ZoomOperation
    0xD2E6 to (DTC_UINT16 to DPFF_ENUM),   // Combined S1/S2 shutter button
    0xD2E9 to (DTC_UINT8 to DPFF_ENUM),    // SaveZoomAndFocusPosition
    0xD2EA to (DTC_UINT8 to DPFF_ENUM),    // LoadZoomAndFocusPosition
    0xD312 to (DTC_UINT16 to DPFF_ENUM),   // SetPostViewEnable
    0xD313 to (DTC_UINT16 to DPFF_ENUM),   // SetLiveViewEnable
)

/**
 * The camera is a Sony but is not in PC Remote mode, so none of the vendor
 * operations exist. Carries the exact menu path for this body, because this is a
 * camera setting and no amount of protocol work fixes it — and because Sony has
 * moved that setting three times, so one generic path is wrong for most owners.
 */
/**
 * The device announced Microsoft's MTP extension, offers no Sony camera
 * operation, and is not even Sony-branded: a phone or a media player sharing
 * the 6/1/1 still-image interface class with real cameras.
 *
 * Separate from [SonyNotInPcRemoteMode] because the advice differs. Telling
 * someone holding a phone to go find the PC Remote menu is a wrong answer that
 * sounds like a right one.
 */
class NotACamera(
    val model: String,
    val manufacturer: String,
) : Exception(
    "Este dispositivo no es una cámara: " +
        listOf(manufacturer, model).filter { it.isNotBlank() }.joinToString(" ").ifBlank { "el aparato conectado" } +
        " responde como almacenamiento MTP. Conecta la cámara con un cable de datos " +
        "y ponla en modo PC Remote."
)

class SonyNotInPcRemoteMode(
    val model: String,
    val firmware: String = "",
) : Exception(pcRemoteInstructions(model, firmware))

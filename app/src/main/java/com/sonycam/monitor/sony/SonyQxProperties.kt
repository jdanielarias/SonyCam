package com.sonycam.monitor.sony

// QX uses its own property namespace as well as different operation codes.
// Values below are documented in reference/libgphoto2/ptp.h.
const val DPC_QX_MOVIE_REC = 0xD60F
const val DPC_QX_SHUTTER_RELEASE = 0xD617
const val DPC_QX_SHUTTER_HALF = 0xD61D
const val DPC_QX_WHITE_BALANCE = 0xD6B8
const val DPC_QX_EXPOSURE_BIAS = 0xD6C3
const val DPC_QX_APERTURE = 0xD6C5
const val DPC_QX_FOCUS_MODE = 0xD6CB
const val DPC_QX_EXPOSURE_PROGRAM = 0xD6CC
const val DPC_QX_MOVIE_STATE = 0xD6CD
const val DPC_QX_LIVEVIEW_STATUS = 0xD6DE
const val DPC_QX_OPERATING_MODE = 0xD6E2
const val DPC_QX_WB_SHIFT_AB = 0xD6E3
const val DPC_QX_SHUTTER = 0xD6EA
const val DPC_QX_WB_SHIFT_GM = 0xD6EF
const val DPC_QX_COLOR_TEMP = 0xD6F0
const val DPC_QX_ISO = 0xD6F2

/** Resolve a familiar SDIO property to this session's actual wire code.
 *
 * Descriptors and property-map keys retain their original codes. This lookup
 * is used at the read/command boundaries; it never creates SDIO aliases that
 * could accidentally be selected for a QX write.
 */
fun SonyDialect.propertyCode(code: Int): Int {
    if (this != SonyDialect.QX) return code
    return when (code) {
        SonyCamera.DPC_SONY_MOVIE_REC -> DPC_QX_MOVIE_REC
        SonyCamera.DPC_SONY_SHUTTER_HALF -> DPC_QX_SHUTTER_HALF
        SonyCamera.DPC_SONY_SHUTTER_RELEASE -> DPC_QX_SHUTTER_RELEASE
        DPC_WHITE_BALANCE -> DPC_QX_WHITE_BALANCE
        DPC_EXPOSURE_BIAS -> DPC_QX_EXPOSURE_BIAS
        DPC_FNUMBER -> DPC_QX_APERTURE
        DPC_FOCUS_MODE -> DPC_QX_FOCUS_MODE
        DPC_EXPOSURE_PROGRAM -> DPC_QX_EXPOSURE_PROGRAM
        SonyCamera.DPC_SONY_MOVIE_STATE -> DPC_QX_MOVIE_STATE
        DPC_SONY_LIVEVIEW_STATUS -> DPC_QX_LIVEVIEW_STATUS
        DPC_SONY_WB_SHIFT_AB -> DPC_QX_WB_SHIFT_AB
        DPC_SONY_SHUTTER -> DPC_QX_SHUTTER
        DPC_SONY_WB_SHIFT_GM -> DPC_QX_WB_SHIFT_GM
        DPC_SONY_COLOR_TEMP -> DPC_QX_COLOR_TEMP
        DPC_SONY_ISO -> DPC_QX_ISO
        else -> code
    }
}

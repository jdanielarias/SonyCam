package com.sonycam.monitor.sony

import com.sonycam.monitor.ptp.PtpReader

// --- PTP data-type codes (subset we handle) --------------------------------
const val DTC_INT8 = 0x0001
const val DTC_UINT8 = 0x0002
const val DTC_INT16 = 0x0003
const val DTC_UINT16 = 0x0004
const val DTC_INT32 = 0x0005
const val DTC_UINT32 = 0x0006
const val DTC_INT64 = 0x0007
const val DTC_UINT64 = 0x0008
const val DTC_STR = 0xFFFF
const val DTC_ARRAY_MASK = 0x4000

// --- Form flags ------------------------------------------------------------
const val DPFF_NONE = 0x00
const val DPFF_RANGE = 0x01
const val DPFF_ENUM = 0x02

// --- Well-known device-property codes --------------------------------------
const val DPC_WHITE_BALANCE = 0x5005
const val DPC_FNUMBER = 0x5007
const val DPC_FOCUS_MODE = 0x500A
const val DPC_EXPOSURE_METERING_MODE = 0x500B
const val DPC_FLASH_MODE = 0x500C
const val DPC_EXPOSURE_PROGRAM = 0x500E
const val DPC_EXPOSURE_BIAS = 0x5010
const val DPC_SONY_SHUTTER = 0xD20D
/** Colour temperature in kelvin. Only settable while White Balance is C.Temp. */
const val DPC_SONY_COLOR_TEMP = 0xD20F
/** Biaxial fine-tuning, green–magenta axis. */
const val DPC_SONY_WB_SHIFT_GM = 0xD210
/** Biaxial fine-tuning, amber–blue axis. */
const val DPC_SONY_WB_SHIFT_AB = 0xD21C
/** Manual focus drive ("Near Far"): signed speed, -7..+7 on the A7 III. */
const val DPC_SONY_MANUAL_FOCUS = 0xD2D1
const val DPC_SONY_ISO = 0xD21E
/** Focus indication: 1 = searching, 2 (3 on RX0) = focus achieved (libgphoto2). */
const val DPC_SONY_FOCUS_FOUND = 0xD213
const val DPC_SONY_LIVEVIEW_STATUS = 0xD221
const val DPC_SONY_SAVE_DEST = 0xD222       // Still Img. Save Dest. status; any mode-3 request needs readback
const val DPC_SONY_OBJECT_IN_MEMORY = 0xD215 // >= 0x8000: capture ready at 0xFFFFC001
const val DPC_SONY_PRIORITY_MODE = 0xD25A   // 1 = application (host) owns the controls

/**
 * How a property must be WRITTEN on this particular body. Derived from the two
 * bytes the camera itself puts in every descriptor, so it is correct per model
 * without any model table (libgphoto2 `ptp-pack.c` ptp_unpack_Sony_DPD):
 *
 *   GetSet & 0x80 -> this is a "control", written via SDIO_ControlDevice (0x9207)
 *     0x81 button | 0x82 notch | 0x83 lock | 0x84 variable
 *   otherwise     -> a regular property, written via SetExtDevicePropValue (0x9205)
 */
enum class SonyWriteMode {
    /** Absolute write via 0x9205 (mode-3 bodies, and most props on mode 2). */
    ABSOLUTE,
    /** Relative ±1 notch via 0x9207, UINT8 0x01 / 0xFF (mode-2 exposure props). */
    NOTCH,
    /** Momentary button via 0x9207, UINT16 2 = press, 1 = release. */
    BUTTON,
    /** Lock-style control via 0x9207. */
    LOCK,
    /** Control that takes a value via 0x9207. */
    VARIABLE,
    /** The camera reports it cannot be set right now. */
    READ_ONLY,
}

/**
 * The four exposure settings that pre-2020 (Sony *mode 2*) bodies refuse to set
 * absolutely: 0x9205 answers OK and does nothing, they only move by relative
 * notches. Everything else on a mode-2 body takes a normal absolute write.
 * Mode-3 bodies set all of these directly. (libgphoto2 config.c: `_put_Sony_ISO`,
 * `_put_Sony_FNumber`, `_put_Sony_ShutterSpeedMode2`, `_put_Sony_ExposureCompensation`.)
 */
private val MODE2_STEPPED_PROPS = setOf(
    DPC_SONY_ISO, DPC_FNUMBER, DPC_SONY_SHUTTER, DPC_EXPOSURE_BIAS,
)

/** Mode-2 props marked "display only" (IsEnabled 2) that are settable anyway. */
private val MODE2_SETTABLE_WHEN_DISPLAY_ONLY = setOf(DPC_FOCUS_MODE, DPC_EXPOSURE_METERING_MODE)

/** Mode-2 props marked disabled (IsEnabled 0) that are settable anyway. */
private val MODE2_SETTABLE_ALWAYS = setOf(DPC_FLASH_MODE)

/** One Sony device-property descriptor: current value plus its allowed set. */
data class SonyDeviceProp(
    val code: Int,
    val dataType: Int,
    val getSet: Int,
    val isEnabled: Int,
    val currentValue: Long?,
    val formFlag: Int,
    /** Values currently accepted by SetExtDevicePropValue (first wire enum). */
    val enumValues: List<Long>? = null,
    val range: Triple<Long, Long, Long>? = null, // (min, max, step) for FormFlag = Range
    /** True when the camera sent the PTP3 Get/Set enumeration after Set. */
    val hadSecondaryEnum: Boolean = false,
    /** True when we synthesised this entry (mode-3 write-only control). */
    val synthetic: Boolean = false,
    /** Readable Get/Set catalogue appended by PTP3 bodies; null if absent. */
    val secondaryEnumValues: List<Long>? = null,
) {
    /** Semantic aliases matching Sony Camera Control PTP3 terminology. */
    val setEnumValues: List<Long>? get() = enumValues
    val getSetEnumValues: List<Long>? get() = secondaryEnumValues

    /** Positional aliases retained for fixture/source compatibility. */
    val primaryEnumValues: List<Long>? get() = enumValues

    /** Whether the camera reports this as a write-only "control" value. */
    val isControl: Boolean get() = (getSet and 0x80) != 0

    /**
     * The write mechanism for this property on this body. [modeVer] is the Sony
     * protocol generation (2 or 3) and only matters for regular properties.
     */
    fun writeMode(modeVer: Int): SonyWriteMode {
        if (isControl) return when (getSet and 0x0F) {
            1 -> SonyWriteMode.BUTTON
            2 -> SonyWriteMode.NOTCH
            3 -> SonyWriteMode.LOCK
            else -> SonyWriteMode.VARIABLE   // 0x84 and anything unrecognised
        }
        // Regular property: IsEnabled is the camera's own "is it greyed out".
        if (modeVer >= 3) return when (isEnabled) {
            1 -> SonyWriteMode.ABSOLUTE
            else -> SonyWriteMode.READ_ONLY  // 0 = greyed out, 2 = display only
        }
        // Mode 2 fills IsEnabled in inconsistently; libgphoto2 keeps a small
        // allow-list for the props it is known to lie about.
        val settable = when (isEnabled) {
            1 -> true
            2 -> code in MODE2_SETTABLE_WHEN_DISPLAY_ONLY
            else -> code in MODE2_SETTABLE_ALWAYS
        }
        if (!settable) return SonyWriteMode.READ_ONLY
        return if (code in MODE2_STEPPED_PROPS) SonyWriteMode.NOTCH else SonyWriteMode.ABSOLUTE
    }
}

private fun scalarSize(dataType: Int): Int = when (dataType) {
    DTC_INT8, DTC_UINT8 -> 1
    DTC_INT16, DTC_UINT16 -> 2
    DTC_INT32, DTC_UINT32 -> 4
    DTC_INT64, DTC_UINT64 -> 8
    else -> 0
}

private fun readScalar(r: PtpReader, dataType: Int): Long = when (dataType) {
    DTC_INT8 -> r.i8().toLong()
    DTC_UINT8 -> r.u8().toLong()
    DTC_INT16 -> r.i16().toLong()
    DTC_UINT16 -> r.u16().toLong()
    DTC_INT32 -> r.i32().toLong()
    DTC_UINT32 -> r.u32()
    DTC_INT64, DTC_UINT64 -> r.i64()
    else -> 0L
}

/**
 * Read one typed property value, advancing the reader. Returns the scalar as a
 * Long; for strings/arrays it advances and returns null. For an unsupported type
 * (or truncated data) it returns null WITHOUT advancing — the caller detects the
 * non-advance and stops, matching libgphoto2 ptp_unpack_DPV semantics.
 */
private fun readTyped(r: PtpReader, dataType: Int): Long? {
    val size = scalarSize(dataType)
    if (size > 0) {
        if (!r.canRead(size)) return null
        return readScalar(r, dataType)
    }
    if (dataType == DTC_STR) {
        if (!r.canRead(1)) return null
        r.string()
        return null
    }
    if (dataType and DTC_ARRAY_MASK != 0) {
        val base = dataType and DTC_ARRAY_MASK.inv()
        val bs = scalarSize(base)
        if (bs <= 0 || !r.canRead(4)) return null
        // The count is a u32 off the wire. Multiplied as Int it can wrap: a
        // count of 0x40000001 with a 4-byte element yields 4, canRead(4) passes,
        // and a sixteen-gigabyte array is accepted as a four-byte field. Size it
        // in Long.
        //
        // Rejecting also has to put the count itself back. The caller tells
        // "understood this field" from "did not" by whether the reader moved, so
        // leaving those four bytes consumed made a refused array look like a
        // parsed one and the descriptor was built anyway.
        val at = r.pos
        val count = r.u32()
        val bytes = count * bs
        if (count < 0L || bytes > Int.MAX_VALUE.toLong() || !r.canRead(bytes.toInt())) {
            r.seek(at)
            return null
        }
        r.skip(bytes.toInt())
        return null
    }
    return null // unknown type — do not advance
}

/** Read `count` typed scalars, stopping early on truncation (libgphoto2 does the same). */
private fun readEnumValues(r: PtpReader, dataType: Int, count: Int): List<Long> {
    val list = ArrayList<Long>(count.coerceAtMost(4096))
    for (i in 0 until count) {
        val before = r.pos
        val v = readTyped(r, dataType)
        if (v == null || r.pos == before) break
        list.add(v)
    }
    return list
}

/**
 * Parse one Sony device-property descriptor. Port of libgphoto2
 * ptp_unpack_Sony_DPD. Layout:
 *   u16 code | u16 dataType | u8 getSet | u8 isEnabled |
 *   <default value> | <current value> | u8 formFlag | <form>
 *
 * **2024-format bodies** (a6700, a9 III, a1 II, and older bodies on recent
 * firmware) append a SECOND enumeration list after the first. Sony Camera
 * Control PTP3 defines the first as "Enum value (Set)" (values accepted now)
 * and the second as "Enum value (Get/Set)" (the readable catalogue). Both are
 * preserved, but only the first is safe to expose as writable choices. It is
 * detected exactly as libgphoto2 does — peek the next u16, and if it is below
 * 0x200 it cannot be the next property code (those are 0x5xxx / 0xDxxx), so it
 * is the count of a second list. Missing this desynchronises the reader and
 * turns every following property in the blob into garbage.
 *
 * Returns the descriptor and leaves the reader after it, or null (reader
 * position restored to the start) to signal the caller to stop/resync.
 */
fun parseSonyDpd(r: PtpReader): SonyDeviceProp? {
    val start = r.pos
    return try {
        parseSonyDpdChecked(r)
    } catch (e: IllegalArgumentException) {
        // A blob that lies about its own lengths makes the reader throw, and
        // that escaped all the way out of parseSonyAllProps — past the resync
        // machinery built for exactly this case, which only runs on a null.
        // Rewind and report it the way the contract above promises.
        r.seek(start)
        null
    }
}

private fun parseSonyDpdChecked(r: PtpReader): SonyDeviceProp? {
    if (r.remaining < 6) return null
    val start = r.pos
    val code = r.u16()
    val dataType = r.u16()
    val getSet = r.u8()
    val isEnabled = r.u8()

    // Both values are checked for having actually been consumed, not just the
    // default. A descriptor whose default array is fine and whose *current*
    // array carries an impossible count used to slip through: the second read
    // refused the field and left the reader untouched, nothing noticed, and
    // formFlag was then read from the middle of that refused count — building a
    // descriptor out of the wrong bytes and leaving the position mid-field.
    val beforeDefault = r.pos
    readTyped(r, dataType)               // default value (skipped)
    if (r.pos == beforeDefault) {        // type not understood -> stop cleanly
        r.seek(start)
        return null
    }
    val beforeCurrent = r.pos
    val current = readTyped(r, dataType) // current value
    if (r.pos == beforeCurrent) {
        r.seek(start)
        return null
    }

    var formFlag = DPFF_NONE
    var enumValues: List<Long>? = null
    var range: Triple<Long, Long, Long>? = null
    if (r.canRead(1)) {
        formFlag = r.u8()
        when (formFlag) {
            DPFF_RANGE -> {
                val mn = readTyped(r, dataType)
                val mx = readTyped(r, dataType)
                val st = readTyped(r, dataType)
                if (mn != null && mx != null && st != null) range = Triple(mn, mx, st)
            }
            DPFF_ENUM -> if (r.canRead(2)) {
                enumValues = readEnumValues(r, dataType, r.u16())
            }
        }
    }

    // Optional PTP3 Get/Set catalogue. Preserve it for readback/diagnostics;
    // never replace the authoritative first Set enumeration with it.
    var hadSecondary = false
    var secondaryEnumValues: List<Long>? = null
    if (formFlag == DPFF_ENUM) {
        val peek = r.peekU16()
        if (peek in 0 until SECONDARY_ENUM_MAX_COUNT) {
            r.u16()                       // consume the count we just peeked
            val second = readEnumValues(r, dataType, peek)
            secondaryEnumValues = second
            hadSecondary = true
        }
    }

    return SonyDeviceProp(
        code, dataType, getSet, isEnabled, current, formFlag, enumValues, range,
        hadSecondaryEnum = hadSecondary,
        secondaryEnumValues = secondaryEnumValues,
    )
}

/**
 * A plausible next property code: Sony descriptors start with a standard PTP
 * property (0x5xxx) or a vendor one (0xDxxx). Used to resynchronise after a
 * malformed entry instead of throwing the rest of the blob away.
 */
private fun looksLikePropCode(code: Int): Boolean =
    (code in 0x5000..0x5FFF) || (code in 0xD000..0xDFFF)

private fun looksLikeDataType(t: Int): Boolean =
    t in DTC_INT8..DTC_UINT64 || t == DTC_STR ||
        (t and DTC_ARRAY_MASK != 0 && (t and DTC_ARRAY_MASK.inv()) in DTC_INT8..DTC_UINT64)

/**
 * Parse the SDIO_GetAllExtDevicePropInfo blob into {code -> descriptor}.
 *
 * On a malformed entry it does not give up: it scans forward for the next
 * plausible `(propcode, datatype)` header and keeps going, reporting what it
 * skipped through [onWarn]. A silent `break` here is what makes an unsupported
 * body look like "connects but has no controls" with no clue why.
 */
fun parseSonyAllProps(
    data: ByteArray,
    onWarn: ((String) -> Unit)? = null,
): Map<Int, SonyDeviceProp> {
    val props = LinkedHashMap<Int, SonyDeviceProp>()
    if (data.size <= 8) {
        onWarn?.invoke("prop blob too short (${data.size} B)")
        return props
    }
    val r = PtpReader(data)
    val declared = r.u32().toInt()   // header: u32 entry-count, u32 zero
    r.skip(4)

    var resyncs = 0
    while (r.remaining > 0) {
        val before = r.pos
        val dpd = parseSonyDpd(r)
        if (dpd != null && r.pos > before) {
            props[dpd.code] = dpd
            continue
        }
        // Malformed here. Dump context once, then hunt for the next header.
        if (resyncs == 0) {
            onWarn?.invoke("parse stopped at offset $before after ${props.size} props; " +
                "context=${hexAt(data, before, 48)}")
        }
        if (resyncs >= MAX_RESYNCS) {
            onWarn?.invoke("giving up after $MAX_RESYNCS resync attempts")
            break
        }
        val next = findNextHeader(data, before + 1)
        if (next < 0) break
        onWarn?.invoke("resync: skipped ${next - before} B to offset $next")
        r.seek(next)
        resyncs++
    }

    if (declared in 1..4096 && props.size != declared) {
        onWarn?.invoke("prop count mismatch: header says $declared, parsed ${props.size}")
    }
    return props
}

private fun findNextHeader(data: ByteArray, from: Int): Int {
    var i = from
    while (i + 6 <= data.size) {
        val code = (data[i].toInt() and 0xFF) or ((data[i + 1].toInt() and 0xFF) shl 8)
        val type = (data[i + 2].toInt() and 0xFF) or ((data[i + 3].toInt() and 0xFF) shl 8)
        if (looksLikePropCode(code) && looksLikeDataType(type)) return i
        i++
    }
    return -1
}

private fun hexAt(data: ByteArray, offset: Int, n: Int): String {
    val start = offset.coerceIn(0, data.size)
    val end = (start + n).coerceAtMost(data.size)
    val sb = StringBuilder((end - start) * 2)
    for (i in start until end) sb.append("%02X".format(data[i]))
    return sb.toString()
}

/**
 * Parse a STANDARD PTP device-property descriptor (0x1014 GetDevicePropDesc).
 * Same layout as the Sony one minus the IsEnabled byte. Used for plain-PTP
 * bodies that expose no Sony vendor operations at all.
 */
fun parseStandardDpd(data: ByteArray): SonyDeviceProp? = try {
    parseStandardDpdChecked(data)
} catch (e: IllegalArgumentException) {
    // The generic PTP path parses the same untrusted lengths as the Sony one
    // and had neither of its guards: a string that lies about its length threw
    // out of here, and a refused current value produced a descriptor built from
    // whatever bytes came next.
    null
}

private fun parseStandardDpdChecked(data: ByteArray): SonyDeviceProp? {
    if (data.size < 5) return null
    val r = PtpReader(data)
    val code = r.u16()
    val dataType = r.u16()
    val getSet = r.u8()

    val beforeDefault = r.pos
    readTyped(r, dataType)
    if (r.pos == beforeDefault) return null
    val beforeCurrent = r.pos
    val current = readTyped(r, dataType)
    if (r.pos == beforeCurrent) return null

    var formFlag = DPFF_NONE
    var enumValues: List<Long>? = null
    var range: Triple<Long, Long, Long>? = null
    if (r.canRead(1)) {
        formFlag = r.u8()
        when (formFlag) {
            DPFF_RANGE -> {
                val mn = readTyped(r, dataType)
                val mx = readTyped(r, dataType)
                val st = readTyped(r, dataType)
                if (mn != null && mx != null && st != null) range = Triple(mn, mx, st)
            }
            DPFF_ENUM -> if (r.canRead(2)) {
                enumValues = readEnumValues(r, dataType, r.u16())
            }
        }
    }
    // Standard PTP has no IsEnabled byte: treat GetSet 1 as writable.
    return SonyDeviceProp(code, dataType, getSet, if (getSet == 1) 1 else 2,
        current, formFlag, enumValues, range)
}

/** Above this, the peeked u16 is a property code, not a second-list count. */
private const val SECONDARY_ENUM_MAX_COUNT = 0x200

private const val MAX_RESYNCS = 16

package com.sonycam.monitor.sony

import java.io.ByteArrayOutputStream

/**
 * Binary fixtures for Sony's 0x9209 (GetAllExtDevicePropInfo) response.
 *
 * Keeping these builders in the test source set gives us camera replays without
 * needing Android, USB hardware, or a model-name table. The layout intentionally
 * mirrors the wire format instead of calling production serializers, so parser
 * regressions cannot make both sides of a test agree on the same mistake.
 */
internal object SonyDpdFixtures {
    data class Descriptor(
        val code: Int,
        val dataType: Int,
        val getSet: Int = 1,
        val isEnabled: Int = 1,
        val defaultValue: Long = 0,
        val currentValue: Long = 0,
        val formFlag: Int = DPFF_NONE,
        val enumValues: List<Long> = emptyList(),
        val range: Triple<Long, Long, Long>? = null,
        val secondaryEnumValues: List<Long>? = null,
    )

    /** Representative pre-2020/mode-2 payload (A7 III family). */
    fun mode2Blob(): ByteArray = blob(
        Descriptor(
            code = DPC_EXPOSURE_PROGRAM,
            dataType = DTC_UINT16,
            currentValue = 0x0001,
            formFlag = DPFF_ENUM,
            enumValues = listOf(0x0001, 0x0002, 0x0003, 0x0004),
        ),
        Descriptor(
            code = DPC_SONY_ISO,
            dataType = DTC_UINT32,
            currentValue = 400,
            formFlag = DPFF_ENUM,
            enumValues = listOf(0x00FF_FFFFL, 100, 200, 400, 800, 204_800, 256_000),
        ),
        Descriptor(
            code = DPC_FNUMBER,
            dataType = DTC_UINT16,
            currentValue = 280,
            formFlag = DPFF_RANGE,
            range = Triple(0, 0xFFFF, 1),
        ),
        Descriptor(
            code = DPC_EXPOSURE_BIAS,
            dataType = DTC_INT16,
            currentValue = -300,
            formFlag = DPFF_ENUM,
            enumValues = listOf(0, 300, 700, -300, -700),
        ),
    )

    /**
     * Representative 2024/mode-3 payload. ISO has its primary Set enum followed
     * by the secondary Get/Set enum, then another descriptor. That final
     * descriptor proves the parser stayed aligned after the second list.
     */
    fun mode3WithSecondaryEnumBlob(): ByteArray = blob(
        Descriptor(
            code = DPC_SONY_ISO,
            dataType = DTC_UINT32,
            currentValue = 800,
            formFlag = DPFF_ENUM,
            enumValues = listOf(400, 800, 1_600),
            secondaryEnumValues = listOf(100, 200, 400, 800, 1_600, 3_200, 6_400),
        ),
        Descriptor(
            code = DPC_FNUMBER,
            dataType = DTC_UINT16,
            currentValue = 400,
            formFlag = DPFF_ENUM,
            enumValues = listOf(280, 320, 350, 400, 450),
        ),
        Descriptor(
            code = DPC_EXPOSURE_PROGRAM,
            dataType = DTC_UINT32,
            currentValue = 0x0002_0003,
            formFlag = DPFF_ENUM,
            enumValues = listOf(0x0000_0001, 0x0001_0002, 0x0002_0003, 0x0003_0004),
        ),
    )

    /**
     * a6700-style ISO descriptor. Sony PTP 3 defines the first enumeration as
     * the values accepted by Set in the current camera state and the second as
     * Get/Set metadata. Callers choose the still/movie Set list explicitly.
     */
    fun a6700IsoBlob(
        current: Long,
        setValues: List<Long> = a6700PhotoSetIsos,
        getSetValues: List<Long> = a6700GetSetIsos,
        isoCode: Int = DPC_SONY_ISO,
        shootingModeRaw: Long? = null,
        legacyIso: Descriptor? = null,
    ): ByteArray {
        val descriptors = buildList {
            shootingModeRaw?.let { mode ->
                add(
                    Descriptor(
                        code = DPC_EXPOSURE_PROGRAM,
                        dataType = DTC_UINT32,
                        currentValue = mode,
                    ),
                )
            }
            legacyIso?.let(::add)
            add(
                Descriptor(
                    code = isoCode,
                    dataType = DTC_UINT32,
                    currentValue = current,
                    formFlag = DPFF_ENUM,
                    enumValues = setValues,
                    secondaryEnumValues = getSetValues,
                ),
            )
        }
        return blob(*descriptors.toTypedArray())
    }

    /** Wire raw for an ISO stop outside the native range (a6700: 50-80, 40000+). */
    fun ext(iso: Long): Long = (SonyCamera.SONY_ISO_KIND_EXTENDED shl 24) or iso

    /**
     * D21E Set enum exactly as an ILCE-6700 (fw 2.00) sends it in photo mode,
     * transcribed from a live 0x9209 capture. The stops outside the native
     * 100-32000 range carry the EXTENDED kind in the high byte — they are not
     * plain integers, and a fixture that spells them as plain integers cannot
     * reproduce the wheel capping at 32000.
     */
    val a6700PhotoSetIsos: List<Long> = listOf(
        0x00FF_FFFFL,
        ext(50), ext(64), ext(80),
        100, 125, 160, 200, 250, 320, 400, 500, 640, 800,
        1_000, 1_250, 1_600, 2_000, 2_500, 3_200, 4_000, 5_000, 6_400,
        8_000, 10_000, 12_800, 16_000, 20_000, 25_600, 32_000,
        ext(40_000), ext(51_200), ext(64_000), ext(80_000), ext(102_400),
    )

    /** The same list with every stop the wheel should offer, as decoded values. */
    val a6700PhotoIsoStops: List<Long> = a6700PhotoSetIsos.map { it and 0x00FF_FFFFL }

    val a6700MovieSetIsos: List<Long> = a6700PhotoSetIsos.filter {
        it == 0x00FF_FFFFL || it in 100L..32_000L
    }

    /** Get/Set metadata observed as the complete range in either shooting state. */
    val a6700GetSetIsos: List<Long> = a6700PhotoSetIsos

    /** Flagged multi-frame-NR wire values which must never become normal ISO stops. */
    val a6700MfnrIsos: List<Long> = listOf(0x0100_0064L, 0x0200_0190L)

    fun blob(vararg descriptors: Descriptor): ByteArray {
        val out = LeWriter()
        out.u32(descriptors.size.toLong())
        out.u32(0)
        descriptors.forEach { out.descriptor(it) }
        return out.bytes()
    }

    fun descriptorBytes(descriptor: Descriptor): ByteArray =
        LeWriter().apply { descriptor(descriptor) }.bytes()

    /** Header for an unsupported descriptor; [tail] can contain a later valid DPD. */
    fun unknownDescriptor(code: Int = 0xD2F0, tail: ByteArray = byteArrayOf()): ByteArray =
        LeWriter().apply {
            u16(code)
            u16(0x1234) // deliberately unsupported PTP data type
            u8(1)
            u8(1)
            raw(byteArrayOf(0x55, 0x66, 0x77))
            raw(tail)
        }.bytes()

    /** UINT32 descriptor cut in the middle of its default value. */
    fun truncatedDescriptor(code: Int = 0xD2F1): ByteArray = LeWriter().apply {
        u16(code)
        u16(DTC_UINT32)
        u8(1)
        u8(1)
        u16(0xBEEF)
    }.bytes()

    fun rawBlob(declaredCount: Int, vararg chunks: ByteArray): ByteArray = LeWriter().apply {
        u32(declaredCount.toLong())
        u32(0)
        chunks.forEach(::raw)
    }.bytes()

    private class LeWriter {
        private val out = ByteArrayOutputStream()

        fun u8(value: Int) = out.write(value and 0xFF)

        fun u16(value: Int) {
            u8(value)
            u8(value ushr 8)
        }

        fun u32(value: Long) {
            repeat(4) { i -> u8((value ushr (8 * i)).toInt()) }
        }

        fun u64(value: Long) {
            repeat(8) { i -> u8((value ushr (8 * i)).toInt()) }
        }

        fun scalar(dataType: Int, value: Long) {
            when (dataType) {
                DTC_INT8, DTC_UINT8 -> u8(value.toInt())
                DTC_INT16, DTC_UINT16 -> u16(value.toInt())
                DTC_INT32, DTC_UINT32 -> u32(value)
                DTC_INT64, DTC_UINT64 -> u64(value)
                else -> error("Fixture does not support PTP type 0x%04X".format(dataType))
            }
        }

        fun descriptor(d: Descriptor) {
            u16(d.code)
            u16(d.dataType)
            u8(d.getSet)
            u8(d.isEnabled)
            scalar(d.dataType, d.defaultValue)
            scalar(d.dataType, d.currentValue)
            u8(d.formFlag)
            when (d.formFlag) {
                DPFF_RANGE -> {
                    val (min, max, step) = requireNotNull(d.range)
                    scalar(d.dataType, min)
                    scalar(d.dataType, max)
                    scalar(d.dataType, step)
                }
                DPFF_ENUM -> {
                    u16(d.enumValues.size)
                    d.enumValues.forEach { scalar(d.dataType, it) }
                    d.secondaryEnumValues?.let { secondary ->
                        u16(secondary.size)
                        secondary.forEach { scalar(d.dataType, it) }
                    }
                }
            }
        }

        fun raw(bytes: ByteArray) = out.write(bytes)

        fun bytes(): ByteArray = out.toByteArray()
    }

}

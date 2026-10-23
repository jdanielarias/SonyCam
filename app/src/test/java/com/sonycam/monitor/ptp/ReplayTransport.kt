package com.sonycam.monitor.ptp

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Deterministic, Android-free PTP byte stream. Each item in [chunks] represents
 * one bulk-IN completion: it may be a partial header, a complete container, or
 * several coalesced containers. This is intentionally lower level than a queue
 * of parsed transactions so the real production framing state machine is used.
 */
internal class ReplayTransport(
    chunks: List<ByteArray>,
    override val maxPacketIn: Int = 512,
) : PtpTransport {
    data class ReadCall(val max: Int, val timeoutMs: Int)
    data class DrainCall(val quietPeriodMs: Int, val maxDurationMs: Int)

    private val inbound = ArrayDeque(chunks.map(ByteArray::copyOf))
    val writes = mutableListOf<ByteArray>()
    val reads = mutableListOf<ReadCall>()
    val drains = mutableListOf<DrainCall>()
    var forcedWriteResult: Int? = null

    override fun write(data: ByteArray, timeoutMs: Int): Int {
        writes += data.copyOf()
        return forcedWriteResult ?: data.size
    }

    override fun read(max: Int, timeoutMs: Int): ByteArray {
        require(max > 0)
        reads += ReadCall(max, timeoutMs)
        if (inbound.isEmpty()) return ByteArray(0)

        val next = inbound.removeFirst()
        if (next.size <= max) return next

        // Model a transport honoring a smaller receive buffer than the fixture
        // chunk. The remainder is returned by the following bulk-IN call.
        inbound.addFirst(next.copyOfRange(max, next.size))
        return next.copyOfRange(0, max)
    }

    override fun drainInput(quietPeriodMs: Int, maxDurationMs: Int): Int {
        drains += DrainCall(quietPeriodMs, maxDurationMs)
        var discarded = 0
        while (inbound.isNotEmpty()) discarded += inbound.removeFirst().size
        return discarded
    }
}

/** Independent little-endian fixture writer for PTP container replays. */
internal object PtpReplayWire {
    data class DecodedContainer(
        val length: Int,
        val type: Int,
        val code: Int,
        val tid: Int,
        val payload: ByteArray,
    )

    fun container(
        type: Int,
        code: Int,
        tid: Int,
        payload: ByteArray = byteArrayOf(),
        declaredLength: Long = 12L + payload.size,
    ): ByteArray = LeWriter().apply {
        u32(declaredLength)
        u16(type)
        u16(code)
        u32(tid.toLong())
        raw(payload)
    }.bytes()

    fun data(opcode: Int, tid: Int, payload: ByteArray): ByteArray =
        container(PtpSession.TYPE_DATA, opcode, tid, payload)

    fun response(code: Int = PtpSession.RC_OK, tid: Int): ByteArray =
        container(PtpSession.TYPE_RESPONSE, code, tid)

    fun event(code: Int = 0x4002, tid: Int = 0): ByteArray =
        container(PtpSession.TYPE_EVENT, code, tid)

    fun concat(vararg bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(bytes.sumOf(ByteArray::size))
        bytes.forEach(out::write)
        return out.toByteArray()
    }

    fun split(bytes: ByteArray, vararg offsets: Int): List<ByteArray> {
        val positions = (listOf(0) + offsets.toList() + bytes.size)
        require(positions.zipWithNext().all { (a, b) -> a in 0..bytes.size && b >= a })
        return positions.zipWithNext { a, b -> bytes.copyOfRange(a, b) }
    }

    fun decode(bytes: ByteArray): DecodedContainer {
        require(bytes.size >= 12)
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val length = b.int
        val type = b.short.toInt() and 0xFFFF
        val code = b.short.toInt() and 0xFFFF
        val tid = b.int
        return DecodedContainer(length, type, code, tid, bytes.copyOfRange(12, bytes.size))
    }

    /** A realistic-enough GetDeviceInfo payload for exercising its real parser. */
    fun sonyDeviceInfo(
        operations: IntArray = intArrayOf(0x1001, 0x1002, 0x1008, 0x1009, 0x9201, 0x9209),
        properties: IntArray = intArrayOf(0x500E, 0x5010, 0xD21E),
        model: String = "ILCE-7M3",
        deviceVersion: String = "4.01",
    ): ByteArray = LeWriter().apply {
        u16(100)                 // StandardVersion 1.00
        u32(0x11)                // Sony vendor extension id
        u16(100)
        ptpString("Sony PTP Extensions")
        u16(0)                   // FunctionalMode
        u16Array(*operations)
        u16Array(0x4002, 0xC201)
        u16Array(*properties)
        u16Array(0x3801)
        u16Array(0x3801)
        ptpString("Sony Corporation")
        ptpString(model)
        ptpString(deviceVersion)
        ptpString("TEST0001")
    }.bytes()

    /** Payload returned by Sony SDIO_GetExtDeviceInfo (0x9202). */
    fun sonyExtDeviceInfo(
        protocolVersion: Int,
        propertiesAndOps: IntArray,
        controlsAndEvents: IntArray,
    ): ByteArray = LeWriter().apply {
        u16(protocolVersion)
        u16Array(*propertiesAndOps)
        u16Array(*controlsAndEvents)
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

        fun raw(bytes: ByteArray) = out.write(bytes)

        fun u16Array(vararg values: Int) {
            u32(values.size.toLong())
            values.forEach(::u16)
        }

        fun ptpString(value: String) {
            if (value.isEmpty()) {
                u8(0)
                return
            }
            u8(value.length + 1)
            value.forEach { u16(it.code) }
            u16(0)
        }

        fun bytes(): ByteArray = out.toByteArray()
    }
}

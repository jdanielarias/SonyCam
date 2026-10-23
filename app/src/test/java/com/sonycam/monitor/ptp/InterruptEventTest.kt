package com.sonycam.monitor.ptp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Decoding of PTP events as they would arrive on the interrupt endpoint.
 *
 * This is instrumentation for an open question — whether Sony bodies deliver
 * events there at all — so it errs towards reporting nothing over reporting
 * something wrong. A packet that is not a well-formed event is a finding to log,
 * never a value to act on.
 */
class InterruptEventTest {

    private class Fixture : PtpTransport {
        override val maxPacketIn = 512
        override fun write(data: ByteArray, timeoutMs: Int) = data.size
        override fun read(max: Int, timeoutMs: Int) = ByteArray(0)
        override fun drainInput(quietPeriodMs: Int, maxDurationMs: Int) = 0
    }

    private val session = PtpSession(Fixture())

    private fun packet(vararg bytes: Int) = bytes.map { it.toByte() }.toByteArray()

    /** 0xC203 Sony_DevicePropChanged carrying the property code, no parameters. */
    @Test
    fun `a bare event decodes`() {
        val e = session.parseEvent(packet(
            0x0C, 0, 0, 0,        // length 12
            0x04, 0x00,           // type 4 = event
            0x03, 0xC2,           // code 0xC203
            0x07, 0, 0, 0,        // tid 7
        ))
        assertEquals(0xC203, e!!.code)
        assertEquals(7, e.tid)
        assertEquals(emptyList<Int>(), e.params)
    }

    /** 0xC201 ObjectAdded carries the object handle. */
    @Test
    fun `an event with parameters decodes`() {
        val e = session.parseEvent(packet(
            0x10, 0, 0, 0,        // length 16
            0x04, 0x00,
            0x01, 0xC2,           // code 0xC201
            0x09, 0, 0, 0,        // tid 9
            0x01, 0xC0, 0xFF, 0xFF, // param 0xFFFFC001
        ))
        assertEquals(listOf(0xFFFFC001.toInt()), e!!.params)
        assertEquals("0xC201 tid=9 params=0xFFFFC001", e.toString())
    }

    /** Anything that is not an event must not be reported as one. */
    @Test
    fun `non-events are rejected rather than guessed at`() {
        // A response container, not an event.
        assertNull(session.parseEvent(packet(0x0C, 0, 0, 0, 0x03, 0x00, 0x01, 0x20, 1, 0, 0, 0)))
        // An event code outside the legal ranges.
        assertNull(session.parseEvent(packet(0x0C, 0, 0, 0, 0x04, 0x00, 0x34, 0x12, 1, 0, 0, 0)))
        // Truncated below the header.
        assertNull(session.parseEvent(packet(0x0C, 0, 0, 0, 0x04, 0x00)))
        // A length claiming more than the packet holds.
        assertNull(session.parseEvent(packet(0xFF, 0, 0, 0, 0x04, 0x00, 0x03, 0xC2, 1, 0, 0, 0)))
        assertNull(session.parseEvent(ByteArray(0)))
    }

    /** With no interrupt endpoint there is never an event, and never an error. */
    @Test
    fun `a transport without an interrupt endpoint reports nothing`() {
        assertEquals(emptyList<PtpEvent>(), session.pollEvents())
    }
}

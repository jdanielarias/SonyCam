package com.sonycam.monitor.ptp

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PtpSessionReplayTest {
    private var previousVerbose = true

    @Before
    fun disableAndroidLoggingForJvmReplay() {
        previousVerbose = PtpSession.verbose
        PtpSession.verbose = false
    }

    @After
    fun restoreLoggingFlag() {
        PtpSession.verbose = previousVerbose
    }

    @Test
    fun `split header and coalesced data response survive real framing path`() {
        val payload = PtpReplayWire.sonyDeviceInfo()
        val incoming = PtpReplayWire.concat(
            PtpReplayWire.data(PtpSession.OC_GET_DEVICE_INFO, tid = 1, payload),
            PtpReplayWire.response(tid = 1),
        )
        // Both cuts are inside the first 12-byte DATA header; the last bulk read
        // contains its remainder, the payload, and the complete RESPONSE.
        val transport = ReplayTransport(PtpReplayWire.split(incoming, 5, 11))

        val info = PtpSession(transport).getDeviceInfo()

        assertEquals("ILCE-7M3", info.model)
        assertEquals("4.01", info.deviceVersion)
        assertEquals("TEST0001", info.serialNumber)
        assertTrue(info.isTrustedSonyCamera)
        assertArrayEquals(
            intArrayOf(0x1001, 0x1002, 0x1008, 0x1009, 0x9201, 0x9209),
            info.operationsSupported,
        )
        assertEquals(3, transport.reads.size)
        assertTrue(transport.drains.isEmpty())

        val command = PtpReplayWire.decode(transport.writes.single())
        assertEquals(12, command.length)
        assertEquals(PtpSession.TYPE_COMMAND, command.type)
        assertEquals(PtpSession.OC_GET_DEVICE_INFO, command.code)
        assertEquals(1, command.tid)
        assertTrue(command.payload.isEmpty())
    }

    @Test
    fun `stale response and asynchronous event are skipped before current transaction`() {
        val opcode = 0x9209
        val expected = byteArrayOf(0x11, 0x22, 0x33)
        val incoming = PtpReplayWire.concat(
            PtpReplayWire.response(tid = 0),       // stale transaction
            PtpReplayWire.event(code = 0xC201),    // Sony vendor event
            PtpReplayWire.data(opcode, tid = 1, expected),
            PtpReplayWire.response(tid = 1),
        )
        val transport = ReplayTransport(listOf(incoming))

        val result = PtpSession(transport).transact(opcode)

        assertArrayEquals(expected, result.data)
        assertEquals(PtpSession.RC_OK, result.responseCode)
        assertTrue(transport.drains.isEmpty())
    }

    @Test
    fun `command parameters and outbound data use same increasing transaction id`() {
        val opcode = 0x9205
        val dataOut = byteArrayOf(0x78, 0x56, 0x34, 0x12)
        val transport = ReplayTransport(listOf(PtpReplayWire.response(tid = 1)))

        PtpSession(transport).transact(opcode, intArrayOf(0xD21E), dataOut)

        assertEquals(2, transport.writes.size)
        val command = PtpReplayWire.decode(transport.writes[0])
        val data = PtpReplayWire.decode(transport.writes[1])
        assertEquals(PtpSession.TYPE_COMMAND, command.type)
        assertEquals(PtpSession.TYPE_DATA, data.type)
        assertEquals(opcode, command.code)
        assertEquals(opcode, data.code)
        assertEquals(1, command.tid)
        assertEquals(command.tid, data.tid)
        assertArrayEquals(byteArrayOf(0x1E, 0xD2.toByte(), 0, 0), command.payload)
        assertArrayEquals(dataOut, data.payload)
    }

    @Test
    fun `container length below header is rejected and input is drained`() {
        val malformed = PtpReplayWire.container(
            type = PtpSession.TYPE_RESPONSE,
            code = PtpSession.RC_OK,
            tid = 1,
            declaredLength = 11,
        )
        val transport = ReplayTransport(listOf(malformed, byteArrayOf(1, 2, 3)))

        val error = assertThrows(PtpException::class.java) {
            PtpSession(transport).transact(0x9209)
        }

        assertEquals(0, error.code)
        assertTrue(error.message.orEmpty().contains("Invalid PTP container length 11"))
        assertEquals(listOf(ReplayTransport.DrainCall(350, 1400)), transport.drains)
    }

    @Test
    fun `future transaction id is rejected and input is drained`() {
        val transport = ReplayTransport(listOf(PtpReplayWire.response(tid = 2)))

        val error = assertThrows(PtpException::class.java) {
            PtpSession(transport).transact(0x9209)
        }

        assertEquals(0, error.code)
        assertTrue(error.message.orEmpty().contains("transaction id 2; expected 1"))
        assertEquals(1, transport.drains.size)
    }

    @Test
    fun `mismatched data opcode is rejected and input is drained`() {
        val expectedOpcode = 0x9209
        val transport = ReplayTransport(
            listOf(PtpReplayWire.data(0x9208, tid = 1, byteArrayOf(1))),
        )

        val error = assertThrows(PtpException::class.java) {
            PtpSession(transport).transact(expectedOpcode)
        }

        assertEquals(0, error.code)
        assertTrue(error.message.orEmpty().contains("does not match operation 0x9209"))
        assertEquals(1, transport.drains.size)
    }

    @Test
    fun `operation code in response namespace is rejected before acceptance`() {
        val malformed = PtpReplayWire.container(
            type = PtpSession.TYPE_RESPONSE,
            code = 0x1001,
            tid = 1,
        )
        val transport = ReplayTransport(listOf(malformed))

        val error = assertThrows(PtpException::class.java) {
            PtpSession(transport).transact(0x9209)
        }

        assertEquals(0, error.code)
        assertTrue(error.message.orEmpty().contains("Invalid PTP response code 0x1001"))
        assertEquals(1, transport.drains.size)
    }

    @Test
    fun `valid camera error preserves response code and does not drain aligned stream`() {
        val transport = ReplayTransport(
            listOf(PtpReplayWire.response(code = 0x2005, tid = 1)),
        )

        val error = assertThrows(PtpException::class.java) {
            PtpSession(transport).transact(0x9209)
        }

        assertEquals(0x2005, error.code)
        assertTrue(error.message.orEmpty().contains("Operation_Not_Supported"))
        assertTrue(transport.drains.isEmpty())
    }

    @Test
    fun `open session accepts already-open response without recovery drain`() {
        val transport = ReplayTransport(
            listOf(PtpReplayWire.response(code = PtpSession.RC_SESSION_ALREADY_OPEN, tid = 0)),
        )
        val session = PtpSession(transport)

        session.openSession(sessionId = 7)

        assertTrue(session.sessionOpen)
        assertTrue(transport.drains.isEmpty())
        val command = PtpReplayWire.decode(transport.writes.single())
        assertEquals(PtpSession.OC_OPEN_SESSION, command.code)
        assertEquals(0, command.tid)
        assertArrayEquals(byteArrayOf(7, 0, 0, 0), command.payload)
    }

    @Test
    fun `short transport write fails distinctly and invokes recovery`() {
        val transport = ReplayTransport(emptyList()).apply { forcedWriteResult = 4 }

        val error = assertThrows(PtpTransportException::class.java) {
            PtpSession(transport).transact(0x9209)
        }

        assertTrue(error.message.orEmpty().contains("short PTP write"))
        assertEquals(1, transport.drains.size)
        assertFalse(transport.writes.isEmpty())
    }
}

package com.sonycam.monitor.sony

import com.sonycam.monitor.ptp.PtpReplayWire
import com.sonycam.monitor.ptp.PtpSession
import com.sonycam.monitor.ptp.ReplayTransport
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 0x9209 takes an operation parameter on the 2020+ generation only.
 *
 * Sony's PTP 3 reference documents parameter 1 as `0x00000000` = gets all data,
 * `0x00000001` = gets only difference data. The PTP 2 reference gives the same
 * opcode **no parameters at all**, so the classic generation must keep sending
 * an empty parameter list — the request container's length changes with it, and
 * this is the one call the poll loop makes twice a second on every body.
 *
 * The profile comes from a real replayed handshake rather than being forced, so
 * these tests fail if the negotiation ever stops classifying the generation.
 */
class PropertyDeltaTest {
    private var previousVerbose = true

    @Before fun quiet() { previousVerbose = PtpSession.verbose; PtpSession.verbose = false }
    @After fun restore() { PtpSession.verbose = previousVerbose }

    private fun le32(v: Int) = byteArrayOf(
        v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte(),
    )

    private val operations = intArrayOf(
        PtpSession.OC_GET_DEVICE_INFO, PtpSession.OC_OPEN_SESSION,
        SonyCamera.OC_SDIO_CONNECT, SonyCamera.OC_SDIO_GET_EXT_DEVICE_INFO,
        SonyCamera.OC_SDIO_SET_EXT_DEVICE_PROP_VALUE, SonyCamera.OC_SDIO_CONTROL_DEVICE,
        SonyCamera.OC_SDIO_GET_ALL_EXT_DEVICE_PROP_INFO,
    )

    /** Connect a replayed body of the given protocol generation. */
    private fun connect(protocolVersion: Int): Pair<SonyCamera, ReplayTransport> {
        val props = SonyDpdFixtures.mode2Blob()
        val transport = ReplayTransport(
            listOf(
                PtpReplayWire.concat(
                    PtpReplayWire.data(PtpSession.OC_GET_DEVICE_INFO, tid = 1,
                        PtpReplayWire.sonyDeviceInfo(operations = operations)),
                    PtpReplayWire.response(tid = 1),
                    PtpReplayWire.response(tid = 0),   // OpenSession
                    PtpReplayWire.response(tid = 1),   // SDIO connect 1
                    PtpReplayWire.response(tid = 2),   // SDIO connect 2
                    PtpReplayWire.data(SonyCamera.OC_SDIO_GET_EXT_DEVICE_INFO, tid = 3,
                        PtpReplayWire.sonyExtDeviceInfo(
                            protocolVersion = protocolVersion,
                            propertiesAndOps = intArrayOf(DPC_EXPOSURE_PROGRAM, DPC_SONY_ISO),
                            controlsAndEvents = IntArray(0),
                        )),
                    PtpReplayWire.response(tid = 3),
                    PtpReplayWire.response(tid = 4),   // SDIO connect 3
                    PtpReplayWire.response(tid = 5),   // PriorityMode
                    PtpReplayWire.data(SonyCamera.OC_SDIO_GET_ALL_EXT_DEVICE_PROP_INFO,
                        tid = 6, props),
                    PtpReplayWire.response(tid = 6),
                    // One spare property read for the test body to consume.
                    PtpReplayWire.data(SonyCamera.OC_SDIO_GET_ALL_EXT_DEVICE_PROP_INFO,
                        tid = 7, props),
                    PtpReplayWire.response(tid = 7),
                ),
            ),
        )
        val cam = SonyCamera(transport)
        cam.getDeviceInfo()
        cam.openNegotiatedSession(1)
        cam.handshake()
        return cam to transport
    }

    /** Parameters of the last 0x9209 command that went out. */
    private fun lastPropReadParams(transport: ReplayTransport): ByteArray =
        transport.writes.map(PtpReplayWire::decode)
            .last { it.type == PtpSession.TYPE_COMMAND &&
                it.code == SonyCamera.OC_SDIO_GET_ALL_EXT_DEVICE_PROP_INFO }
            .payload

    /** The regression risk: a classic body must not start receiving a parameter. */
    @Test
    fun `a mode 2 body is never sent the difference flag`() {
        val (cam, transport) = connect(SonyCamera.SONY_PROTOCOL_2_00)
        assertEquals(2, cam.profile.modeVer)
        assertFalse(cam.supportsPropertyDeltas)
        cam.getChangedProps()
        assertArrayEquals(
            "PTP 2 documents 0x9209 with no parameters",
            ByteArray(0),
            lastPropReadParams(transport),
        )
    }

    /** A mode-3 body gets parameter 1 = 1, exactly as the reference specifies. */
    @Test
    fun `a mode 3 body asks for only the difference`() {
        val (cam, transport) = connect(SonyCamera.SONY_PROTOCOL_3_00)
        assertEquals(3, cam.profile.modeVer)
        assertTrue(cam.supportsPropertyDeltas)
        cam.getChangedProps()
        assertArrayEquals(le32(1), lastPropReadParams(transport))
    }

    /** The full read stays parameterless on every generation. */
    @Test
    fun `a full read stays parameterless`() {
        for (v in listOf(SonyCamera.SONY_PROTOCOL_2_00, SonyCamera.SONY_PROTOCOL_3_00)) {
            val (cam, transport) = connect(v)
            cam.getAllProps()
            assertArrayEquals("protocol $v", ByteArray(0), lastPropReadParams(transport))
        }
    }

    /**
     * A delta is not a snapshot. It must not overwrite the diagnostic blob a
     * tester's report depends on, which is the ground truth for parser work.
     */
    @Test
    fun `a delta does not replace the diagnostic snapshot`() {
        val (cam, _) = connect(SonyCamera.SONY_PROTOCOL_3_00)
        val fromHandshake = cam.lastPropBlob.copyOf()
        assertTrue(fromHandshake.isNotEmpty())
        cam.getChangedProps()
        assertArrayEquals(fromHandshake, cam.lastPropBlob)
    }
}

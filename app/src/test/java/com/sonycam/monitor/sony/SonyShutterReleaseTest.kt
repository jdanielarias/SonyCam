package com.sonycam.monitor.sony

import com.sonycam.monitor.ptp.PtpReplayWire
import com.sonycam.monitor.ptp.PtpSession
import com.sonycam.monitor.ptp.PtpTransport
import com.sonycam.monitor.ptp.PtpTransportException
import com.sonycam.monitor.ptp.ReplayTransport
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.ArrayDeque
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Camera accepts the button DATA, but its response never reaches the host. */
class SonyShutterReleaseTest {
    private var previousVerbose = true

    @Before
    fun disableAndroidLogging() {
        previousVerbose = PtpSession.verbose
        PtpSession.verbose = false
    }

    @After
    fun restoreAndroidLogging() {
        PtpSession.verbose = previousVerbose
    }

    @Test
    fun `photo releases combined button after lost press response`() {
        val (camera, transport) = connected(SonyCaptureStrategy.COMBINED_S1_S2)
        val failure = transport.loseReply(COMBINED, DOWN)

        assertSame(failure, runCatching { camera.capturePhoto(holdMs = 0) }.exceptionOrNull())

        assertEquals(listOf(COMBINED to DOWN, COMBINED to UP), transport.buttons)
    }

    @Test
    fun `photo releases half button after lost half press response`() {
        val (camera, transport) = connected(SonyCaptureStrategy.HALF_AND_FULL_PRESS)
        val failure = transport.loseReply(HALF, DOWN)

        assertSame(failure, runCatching { camera.capturePhoto(holdMs = 0) }.exceptionOrNull())

        assertEquals(listOf(HALF to DOWN, HALF to UP), transport.buttons)
    }

    @Test
    fun `photo releases both buttons after lost full press response`() {
        val (camera, transport) = connected(SonyCaptureStrategy.HALF_AND_FULL_PRESS)
        val failure = transport.loseReply(FULL, DOWN)

        assertSame(failure, runCatching { camera.capturePhoto(holdMs = 0) }.exceptionOrNull())

        assertEquals(listOf(HALF to DOWN, FULL to DOWN, FULL to UP, HALF to UP), transport.buttons)
    }

    @Test
    fun `photo still releases half button when full release also loses its reply`() {
        val (camera, transport) = connected(SonyCaptureStrategy.HALF_AND_FULL_PRESS)
        val failure = transport.loseReply(FULL, DOWN)
        transport.loseReply(FULL, UP)

        assertSame(failure, runCatching { camera.capturePhoto(holdMs = 0) }.exceptionOrNull())

        assertEquals(listOf(HALF to DOWN, FULL to DOWN, FULL to UP, HALF to UP), transport.buttons)
    }

    /**
     * A full press held for a fixed second fired four frames in continuous Hi on
     * an A7 III. D215 counted the first frame at ~400 ms; that change, not the
     * ceiling, must end the press.
     */
    @Test
    fun `classic photo releases the full press as soon as D215 counts the frame`() {
        val (camera, transport) = connected(SonyCaptureStrategy.HALF_AND_FULL_PRESS)
        transport.objectCounts.addAll(listOf(0L, 0L, 1L))
        val started = System.nanoTime()
        camera.capturePhoto(holdMs = 5_000)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("held the shutter for ${elapsedMs}ms", elapsedMs < 2_000)
        assertEquals(3, transport.propReads)
        assertEquals(listOf(HALF to DOWN, FULL to DOWN, FULL to UP, HALF to UP), transport.buttons)
    }

    /** A counter that never moves is held for the ceiling, then still released. */
    @Test
    fun `classic photo without a frame signal holds for the ceiling and releases`() {
        val (camera, transport) = connected(SonyCaptureStrategy.HALF_AND_FULL_PRESS)
        transport.objectCounts.add(0L)
        val started = System.nanoTime()
        camera.capturePhoto(holdMs = 200)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        // The ceiling runs on the wall clock and this on nanoTime; allow for the
        // millisecond rounding between them. An immediate release would be ~0.
        assertTrue("released after ${elapsedMs}ms", elapsedMs >= 150)
        assertEquals(listOf(HALF to DOWN, FULL to DOWN, FULL to UP, HALF to UP), transport.buttons)
    }

    @Test
    fun `BULB closes combined button after lost opening response`() {
        val (camera, transport) = connected(SonyCaptureStrategy.COMBINED_S1_S2)
        val failure = transport.loseReply(COMBINED, DOWN)

        assertSame(failure, runCatching { camera.openShutter() }.exceptionOrNull())

        assertEquals(listOf(COMBINED to DOWN, COMBINED to UP), transport.buttons)
    }

    @Test
    fun `BULB closes both buttons after lost half press response`() {
        val (camera, transport) = connected(SonyCaptureStrategy.HALF_AND_FULL_PRESS)
        val failure = transport.loseReply(HALF, DOWN)

        assertSame(failure, runCatching { camera.openShutter() }.exceptionOrNull())

        assertEquals(listOf(HALF to DOWN, FULL to UP, HALF to UP), transport.buttons)
    }

    @Test
    fun `BULB closes both buttons after lost full press response`() {
        val (camera, transport) = connected(SonyCaptureStrategy.HALF_AND_FULL_PRESS)
        val failure = transport.loseReply(FULL, DOWN)

        assertSame(failure, runCatching { camera.openShutter() }.exceptionOrNull())

        assertEquals(listOf(HALF to DOWN, FULL to DOWN, FULL to UP, HALF to UP), transport.buttons)
    }

    @Test
    fun `failed BULB cleanup retains the opening failure and still releases half`() {
        val (camera, transport) = connected(SonyCaptureStrategy.HALF_AND_FULL_PRESS)
        val failure = transport.loseReply(FULL, DOWN)
        val cleanupFailure = transport.loseReply(FULL, UP)

        val reportedFailure = runCatching { camera.openShutter() }.exceptionOrNull()
        assertTrue(reportedFailure is IllegalStateException)
        assertSame(failure, reportedFailure?.cause)
        assertTrue(reportedFailure?.message.orEmpty().contains("cierre del obturador"))

        assertEquals(listOf(HALF to DOWN, FULL to DOWN, FULL to UP, HALF to UP), transport.buttons)
        assertTrue(failure.suppressed.contains(cleanupFailure))
    }

    @Test
    fun `failed combined BULB cleanup is suppressed on the opening failure`() {
        val (camera, transport) = connected(SonyCaptureStrategy.COMBINED_S1_S2)
        val failure = transport.loseReply(COMBINED, DOWN)
        val cleanupFailure = transport.loseReply(COMBINED, UP)

        val reportedFailure = runCatching { camera.openShutter() }.exceptionOrNull()
        assertTrue(reportedFailure is IllegalStateException)
        assertSame(failure, reportedFailure?.cause)
        assertTrue(reportedFailure?.message.orEmpty().contains("cierre del obturador"))

        assertEquals(listOf(COMBINED to DOWN, COMBINED to UP), transport.buttons)
        assertTrue(failure.suppressed.contains(cleanupFailure))
    }

    @Test
    fun `successful BULB remains open until the separate close action`() {
        for (strategy in listOf(SonyCaptureStrategy.COMBINED_S1_S2, SonyCaptureStrategy.HALF_AND_FULL_PRESS)) {
            val (camera, transport) = connected(strategy)
            val presses = if (strategy == SonyCaptureStrategy.COMBINED_S1_S2) {
                listOf(COMBINED to DOWN)
            } else {
                listOf(HALF to DOWN, FULL to DOWN)
            }
            val releases = if (strategy == SonyCaptureStrategy.COMBINED_S1_S2) {
                listOf(COMBINED to UP)
            } else {
                listOf(FULL to UP, HALF to UP)
            }

            camera.openShutter()
            assertEquals("Opening BULB must leave the shutter held for $strategy", presses, transport.buttons)

            camera.closeShutter()
            assertEquals(presses + releases, transport.buttons)
        }
    }

    private fun connected(strategy: SonyCaptureStrategy): Pair<SonyCamera, LostReplyTransport> {
        val classic = strategy == SonyCaptureStrategy.HALF_AND_FULL_PRESS
        val controls = if (classic) intArrayOf(HALF, FULL) else intArrayOf(COMBINED)
        val deviceInfo = PtpReplayWire.sonyDeviceInfo(
            operations = intArrayOf(
                PtpSession.OC_GET_DEVICE_INFO, PtpSession.OC_OPEN_SESSION,
                SonyCamera.OC_SDIO_CONNECT, SonyCamera.OC_SDIO_GET_EXT_DEVICE_INFO,
                SonyCamera.OC_SDIO_SET_EXT_DEVICE_PROP_VALUE, SonyCamera.OC_SDIO_CONTROL_DEVICE,
                SonyCamera.OC_SDIO_GET_ALL_EXT_DEVICE_PROP_INFO,
            ),
        )
        val extInfo = PtpReplayWire.sonyExtDeviceInfo(
            protocolVersion = if (classic) SonyCamera.SONY_PROTOCOL_2_00 else SonyCamera.SONY_PROTOCOL_3_00,
            propertiesAndOps = intArrayOf(DPC_EXPOSURE_PROGRAM),
            controlsAndEvents = controls,
        )
        val transcript = PtpReplayWire.concat(
            PtpReplayWire.data(PtpSession.OC_GET_DEVICE_INFO, 1, deviceInfo),
            PtpReplayWire.response(tid = 1),
            PtpReplayWire.response(tid = 0), // OpenSession
            PtpReplayWire.response(tid = 1), // Connect 1
            PtpReplayWire.response(tid = 2), // Connect 2
            PtpReplayWire.data(SonyCamera.OC_SDIO_GET_EXT_DEVICE_INFO, 3, extInfo),
            PtpReplayWire.response(tid = 3),
            PtpReplayWire.response(tid = 4), // Connect 3
            PtpReplayWire.response(tid = 5), // PriorityMode
            PtpReplayWire.data(SonyCamera.OC_SDIO_GET_ALL_EXT_DEVICE_PROP_INFO, 6, SonyDpdFixtures.mode2Blob()),
            PtpReplayWire.response(tid = 6),
            if (classic) PtpReplayWire.concat(
                PtpReplayWire.response(tid = 7), // Half-press pre-warm
                PtpReplayWire.response(tid = 8), // Pre-warm release
            ) else byteArrayOf(),
        )
        val transport = LostReplyTransport(transcript)
        val camera = SonyCamera(transport)
        camera.getDeviceInfo()
        camera.openNegotiatedSession()
        camera.handshake()
        assertEquals(strategy, camera.profile.captureStrategy)
        transport.shooting = true
        return camera to transport
    }

    /** Inject failure only after both outbound containers have reached the camera. */
    private class LostReplyTransport(transcript: ByteArray) : PtpTransport {
        override val maxPacketIn = 512
        private val handshake = ReplayTransport(listOf(transcript))
        var shooting = false
        val buttons = mutableListOf<Pair<Int, Int>>()
        private val failures = mutableMapOf<Pair<Int, Int>, PtpTransportException>()
        private var command: PtpReplayWire.DecodedContainer? = null
        private var response: ByteArray? = null
        private var responseFailure: PtpTransportException? = null

        fun loseReply(prop: Int, value: Int): PtpTransportException =
            PtpTransportException("USB read timeout after accepted button $prop=$value").also {
                failures[prop to value] = it
            }

        /** D215 values served to successive 0x9209 reads; the last one repeats. */
        val objectCounts = ArrayDeque<Long>()
        var propReads = 0

        override fun write(data: ByteArray, timeoutMs: Int): Int {
            if (!shooting) return handshake.write(data, timeoutMs)
            val container = PtpReplayWire.decode(data)
            if (container.code == SonyCamera.OC_SDIO_GET_ALL_EXT_DEVICE_PROP_INFO) {
                assertEquals(PtpSession.TYPE_COMMAND, container.type)
                propReads++
                val count = if (objectCounts.size > 1) objectCounts.removeFirst() else objectCounts.first()
                val blob = SonyDpdFixtures.blob(
                    SonyDpdFixtures.Descriptor(DPC_SONY_OBJECT_IN_MEMORY, DTC_UINT16, currentValue = count),
                )
                response = PtpReplayWire.concat(
                    PtpReplayWire.data(container.code, container.tid, blob),
                    PtpReplayWire.response(tid = container.tid),
                )
                return data.size
            }
            assertEquals(SonyCamera.OC_SDIO_CONTROL_DEVICE, container.code)
            when (container.type) {
                PtpSession.TYPE_COMMAND -> {
                    check(command == null)
                    command = container
                }
                PtpSession.TYPE_DATA -> {
                    val current = checkNotNull(command)
                    assertEquals(current.tid, container.tid)
                    assertEquals(2, container.payload.size)
                    val prop = ByteBuffer.wrap(current.payload).order(ByteOrder.LITTLE_ENDIAN).int
                    val value = ByteBuffer.wrap(container.payload).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
                    val button = prop to value
                    buttons += button
                    responseFailure = failures.remove(button)
                    response = PtpReplayWire.response(tid = container.tid)
                    command = null
                }
                else -> error("Unexpected outbound container ${container.type}")
            }
            return data.size
        }

        override fun read(max: Int, timeoutMs: Int): ByteArray {
            if (!shooting) return handshake.read(max, timeoutMs)
            responseFailure?.let {
                responseFailure = null
                response = null
                throw it
            }
            return checkNotNull(response).also { response = null }
        }

        override fun drainInput(quietPeriodMs: Int, maxDurationMs: Int): Int {
            if (!shooting) return handshake.drainInput(quietPeriodMs, maxDurationMs)
            response = null
            responseFailure = null
            return 0
        }
    }

    private companion object {
        const val COMBINED = SonyCamera.DPC_SONY_S1_S2_BUTTON
        const val HALF = SonyCamera.DPC_SONY_SHUTTER_HALF
        const val FULL = SonyCamera.DPC_SONY_SHUTTER_RELEASE
        const val DOWN = 2
        const val UP = 1
    }
}

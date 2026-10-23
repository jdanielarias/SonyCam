package com.sonycam.monitor.sony

import com.sonycam.monitor.ptp.PtpReplayWire
import com.sonycam.monitor.ptp.PtpSession
import com.sonycam.monitor.ptp.PtpTransport
import com.sonycam.monitor.ptp.PtpTransportException
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** QX byte-stream replays use literal reference/libgphoto2 wire codes. */
class SonyQxTest {
    private var previousVerbose = true

    @Before fun disableAndroidLogging() {
        previousVerbose = PtpSession.verbose
        PtpSession.verbose = false
    }

    @After fun restoreAndroidLogging() { PtpSession.verbose = previousVerbose }

    @Test fun `negotiated QX snapshot decodes its own properties`() {
        val (camera, props, transport) = connected()
        assertEquals(SonyDialect.QX, camera.profile.dialect)
        assertTrue(transport.commands.any { it.code == 0x96F6 })
        assertFalse(props.containsKey(DPC_SONY_ISO))

        val readings = decodeReadings(props, camera.sonyModeVer, camera.profile.dialect)
        assertEquals("400", readings.iso)
        assertEquals("f/2.8", readings.aperture)
        assertEquals("1/125", readings.shutter)
        assertEquals(decodeEv(-300), readings.exposureComp)
        assertEquals(decodeWhiteBalance(2), readings.whiteBalance)
        assertEquals(decodeColorTemp(5500), readings.colorTemp)
        assertEquals(decodeWbShift(1, "M", "G"), readings.wbShiftGm)
        assertEquals(decodeWbShift(2, "B", "A"), readings.wbShiftAb)
        assertTrue(readings.manualFocus)
        assertTrue(readings.recording)
        assertEquals(1, readings.modeRaw)
    }

    @Test fun `QX pickers retain the advertised wire code and absolute write mode`() {
        val (camera, props) = connected()
        for (ctl in ExposureControl.entries) {
            val descriptor = checkNotNull(ctl.effectiveProp(props, 2, SonyDialect.QX))
            val pick = checkNotNull(buildPickList(ctl, props, 2, dialect = SonyDialect.QX))
            assertEquals(descriptor.code, pick.propCode)
            assertTrue(pick.propCode in 0xD600..0xD6FF)
            assertEquals(SonyWriteMode.ABSOLUTE, ctl.writeModeFor(descriptor, 2))
            assertTrue(ctl.editable(props, 2, 1, isoAuto = false, dialect = SonyDialect.QX))
            assertEquals(descriptor.currentValue, pick.currentRaw)
        }
        // An unrelated SDIO alias must never take precedence in a QX session.
        val conflicting = props + (DPC_SONY_ISO to checkNotNull(props[0xD6F2]).copy(code = DPC_SONY_ISO, currentValue = 999))
        assertEquals("400", decodeReadings(conflicting, 2, camera.profile.dialect).iso)
    }

    @Test fun `QX settings send absolute QX operations and correct payload widths`() {
        val (camera, props, transport) = connected()
        transport.settings.clear()
        val widths = mapOf(
            ExposureControl.ISO to 4, ExposureControl.APERTURE to 2,
            ExposureControl.SHUTTER to 4, ExposureControl.EV to 2,
            ExposureControl.FOCUS to 2, ExposureControl.WB to 2,
            ExposureControl.TEMP to 2, ExposureControl.WB_GM to 1, ExposureControl.WB_AB to 1,
        )
        for ((ctl, width) in widths) {
            val pick = checkNotNull(buildPickList(ctl, props, 2, dialect = SonyDialect.QX))
            val raw = pick.raws.last()
            camera.setAbsoluteFor(ctl, checkNotNull(props[pick.propCode]), raw)
            val sent = transport.settings.last()
            assertEquals(0x96FA, sent.opcode)
            assertEquals(pick.propCode, sent.property)
            assertEquals(width, sent.bytes.size)
            val mask = if (width == 4) 0xFFFF_FFFFL else (1L shl (width * 8)) - 1
            assertEquals(raw and mask, sent.unsignedValue)
        }
        assertTrue(runCatching {
            camera.setAbsoluteFor(ExposureControl.ISO, DPC_SONY_ISO, 800)
        }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun `QX photo and recording controls are negotiated and sent with QX codes`() {
        val (camera, _, transport) = connected()
        assertEquals(SonyCaptureStrategy.HALF_AND_FULL_PRESS, camera.profile.captureStrategy)
        assertTrue(camera.profile.canMovieRecord)
        // Connection pre-warm also uses the QX half-press control.
        assertEquals(listOf(0xD61D to 2L, 0xD61D to 1L), transport.buttons)
        transport.settings.clear()

        camera.capturePhoto(holdMs = 0)
        camera.movieRecStart()
        camera.movieRecStop()

        assertEquals(listOf(
            0xD61D to 2L, 0xD617 to 2L, 0xD617 to 1L, 0xD61D to 1L,
            0xD60F to 2L, 0xD60F to 1L,
        ), transport.buttons)
        assertTrue(transport.settings.all { it.opcode == 0x96F8 && it.bytes.size == 2 })
    }

    @Test fun `QX BULB lost response releases the QX shutter buttons`() {
        val (camera, _, transport) = connected()
        transport.settings.clear()
        val failure = PtpTransportException("accepted QX S2, response lost")
        transport.loseFullPressReply = failure

        assertSame(failure, runCatching { camera.openShutter() }.exceptionOrNull())
        assertEquals(listOf(
            0xD61D to 2L, 0xD617 to 2L, 0xD617 to 1L, 0xD61D to 1L,
        ), transport.buttons)
    }

    @Test fun `QX only enables controls actually advertised in its own namespace`() {
        val (camera, props) = connected()
        val base = camera.profile.copy(vendorControls = emptySet())
        val noControls = base.withObservedProps(props)
        assertEquals(SonyCaptureStrategy.UNSUPPORTED, noControls.captureStrategy)
        assertFalse(noControls.canMovieRecord)

        val sdioOnly = base.copy(vendorControls = setOf(
            SonyCamera.DPC_SONY_SHUTTER_HALF, SonyCamera.DPC_SONY_SHUTTER_RELEASE,
            SonyCamera.DPC_SONY_MOVIE_REC,
        )).withObservedProps(props)
        assertEquals(SonyCaptureStrategy.UNSUPPORTED, sdioOnly.captureStrategy)
        assertFalse(sdioOnly.canMovieRecord)

        val observedControls = listOf(0xD61D, 0xD617, 0xD60F).associateWith { code ->
            SonyDeviceProp(code, DTC_UINT16, 0x81, 1, 1, DPFF_NONE)
        }
        val observed = base.withObservedProps(props + observedControls)
        assertEquals(SonyCaptureStrategy.HALF_AND_FULL_PRESS, observed.captureStrategy)
        assertTrue(observed.canMovieRecord)
    }

    @Test fun `QX waits for a nonzero object format before downloading the capture`() {
        val (camera, props, transport) = connected()
        assertFalse(camera.hasPendingPhoto(props))
        assertNull(camera.drainLastPhoto(tries = 1))
        assertFalse(transport.commands.any { it.code == 0x1009 })

        transport.photoFormat = 0x3801
        assertTrue(camera.hasPendingPhoto(props))
        assertArrayEquals(transport.photoBytes, camera.drainLastPhoto(tries = 1))
        assertEquals(1, transport.commands.count { it.code == 0x1009 })
    }

    @Test fun `QX setters preserve the type the camera declares`() {
        val (camera, props, transport) = connected()
        val prop = checkNotNull(props[0xD6CB]).copy(dataType = DTC_UINT8)
        camera.setAbsoluteFor(ExposureControl.FOCUS, prop, 2)
        assertEquals(0x96FA, transport.settings.last().opcode)
        assertArrayEquals(byteArrayOf(2), transport.settings.last().bytes)
    }

    @Test fun `QX capture preparation confirms Still Capture before returning its baseline`() {
        val (camera, props, transport) = connected()
        transport.operatingMode = 1
        transport.settings.clear()
        val before = props + (0xD6E2 to checkNotNull(props[0xD6E2]).copy(currentValue = 1))
        val prepared = camera.prepareStillCapture(before)
        assertEquals(2L, prepared[0xD6E2]?.currentValue)
        assertEquals(0x96FA, transport.settings.single().opcode)
        assertEquals(0xD6E2, transport.settings.single().property)
        assertArrayEquals(byteArrayOf(2), transport.settings.single().bytes)
    }

    @Test fun `QX capture preparation fails when the camera ignores the operating mode change`() {
        val (camera, props, transport) = connected()
        transport.operatingMode = 1
        transport.applyModeWrites = false
        transport.settings.clear()
        val before = props + (0xD6E2 to checkNotNull(props[0xD6E2]).copy(currentValue = 1))
        assertTrue(runCatching { camera.prepareStillCapture(before) }.exceptionOrNull() is IllegalStateException)
        assertTrue(transport.settings.none { it.opcode == 0x96F8 })
    }

    private fun connected(): Triple<SonyCamera, Map<Int, SonyDeviceProp>, QxTransport> {
        val transport = QxTransport()
        val camera = SonyCamera(transport)
        camera.getDeviceInfo()
        camera.openNegotiatedSession()
        val props = camera.handshake()
        return Triple(camera, props, transport)
    }

    private class QxTransport : PtpTransport {
        override val maxPacketIn = 512
        private val inbound = ArrayDeque<ByteArray>()
        private var current: PtpReplayWire.DecodedContainer? = null
        private var readFailure: PtpTransportException? = null
        var loseFullPressReply: PtpTransportException? = null
        var photoFormat = 0
        var operatingMode = 2L
        var applyModeWrites = true
        val photoBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
        val commands = mutableListOf<PtpReplayWire.DecodedContainer>()
        val settings = mutableListOf<Setting>()
        val buttons: List<Pair<Int, Long>> get() = settings.map { it.property to it.unsignedValue }

        data class Setting(val opcode: Int, val property: Int, val bytes: ByteArray) {
            val unsignedValue: Long get() = bytes.withIndex().fold(0L) { acc, (index, byte) ->
                acc or ((byte.toLong() and 0xFFL) shl (8 * index))
            }
        }

        override fun write(data: ByteArray, timeoutMs: Int): Int {
            val container = PtpReplayWire.decode(data)
            when (container.type) {
                PtpSession.TYPE_COMMAND -> {
                    commands += container
                    if (container.code == 0x96FA || container.code == 0x96F8) {
                        current = container
                    } else {
                        val payload = when (container.code) {
                            0x1001 -> PtpReplayWire.sonyDeviceInfo(operations = intArrayOf(
                                0x1001, 0x1002, 0x1008, 0x1009, 0x96FE, 0x96FD, 0x96F6, 0x96FA, 0x96F8,
                            ))
                            0x96FD -> PtpReplayWire.sonyExtDeviceInfo(
                                protocolVersion = 200,
                                propertiesAndOps = intArrayOf(0xD6F2, 0xD6C5, 0xD6EA),
                                controlsAndEvents = intArrayOf(0xD61D, 0xD617, 0xD60F),
                            )
                            0x96F6 -> qxBlob(operatingMode)
                            0x1008 -> ByteBuffer.allocate(52).order(ByteOrder.LITTLE_ENDIAN)
                                .putInt(1).putShort(photoFormat.toShort()).array()
                            0x1009 -> photoBytes
                            0x1002, 0x96FE -> null
                            else -> error("Unexpected QX operation 0x%04X".format(container.code))
                        }
                        if (payload != null) inbound += PtpReplayWire.data(container.code, container.tid, payload)
                        inbound += PtpReplayWire.response(tid = container.tid)
                    }
                }
                PtpSession.TYPE_DATA -> {
                    val command = checkNotNull(current)
                    assertEquals(command.tid, container.tid)
                    assertEquals(4, command.payload.size)
                    val code = ByteBuffer.wrap(command.payload).order(ByteOrder.LITTLE_ENDIAN).int
                    val setting = Setting(command.code, code, container.payload)
                    settings += setting
                    if (code == 0xD6E2 && applyModeWrites) operatingMode = setting.unsignedValue
                    if (code == 0xD617 && setting.unsignedValue == 2L && loseFullPressReply != null) {
                        readFailure = loseFullPressReply
                        loseFullPressReply = null
                    } else inbound += PtpReplayWire.response(tid = container.tid)
                    current = null
                }
                else -> error("Unexpected outbound container ${container.type}")
            }
            return data.size
        }

        override fun read(max: Int, timeoutMs: Int): ByteArray {
            readFailure?.let { readFailure = null; throw it }
            val next = inbound.removeFirst()
            if (next.size <= max) return next
            inbound.addFirst(next.copyOfRange(max, next.size))
            return next.copyOfRange(0, max)
        }

        override fun drainInput(quietPeriodMs: Int, maxDurationMs: Int): Int {
            val count = inbound.sumOf { it.size }
            inbound.clear()
            return count
        }
    }

    private companion object {
        fun qxBlob(operatingMode: Long = 2): ByteArray {
            fun enum(code: Int, type: Int, current: Long, vararg values: Long) =
                SonyDpdFixtures.Descriptor(
                    code = code, dataType = type, currentValue = current,
                    formFlag = DPFF_ENUM, enumValues = values.toList(),
                )
            return SonyDpdFixtures.blob(
                enum(0xD6F2, DTC_UINT32, 400, 100, 200, 400, 800),
                enum(0xD6C5, DTC_UINT16, 280, 280, 400, 560),
                enum(0xD6EA, DTC_UINT32, 0x1007D, 0x1003C, 0x1007D, 0x100FA),
                enum(0xD6C3, DTC_INT16, -300, -300, 0, 300),
                enum(0xD6CB, DTC_UINT16, 1, 1, 2),
                enum(0xD6B8, DTC_UINT16, 2, 2, 4),
                enum(0xD6F0, DTC_UINT16, 5500, 3200, 5500, 6500),
                enum(0xD6EF, DTC_UINT8, 1, 0, 1, 2),
                enum(0xD6E3, DTC_UINT8, 2, 0, 1, 2),
                enum(0xD6CC, DTC_UINT16, 1, 1, 2, 3, 4),
                enum(0xD6CD, DTC_UINT8, 1, 0, 1),
                enum(0xD6E2, DTC_UINT8, operatingMode, 1, 2),
            )
        }
    }
}

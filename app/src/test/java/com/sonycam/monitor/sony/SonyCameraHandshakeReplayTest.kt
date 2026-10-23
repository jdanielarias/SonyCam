package com.sonycam.monitor.sony

import com.sonycam.monitor.ptp.PtpReplayWire
import com.sonycam.monitor.ptp.PtpSession
import com.sonycam.monitor.ptp.ReplayTransport
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Full-engine replays: DeviceInfo -> negotiated session -> Sony handshake -> DPD. */
class SonyCameraHandshakeReplayTest {
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
    fun `A7 III transcript negotiates standard session and classic capture`() {
        val operations = baseSdioOperations(includeExtendedSession = false)
        val deviceInfo = PtpReplayWire.sonyDeviceInfo(
            operations = operations,
            model = "ILCE-7M3",
            deviceVersion = "4.01",
        )
        val extInfo = PtpReplayWire.sonyExtDeviceInfo(
            protocolVersion = SonyCamera.SONY_PROTOCOL_2_00,
            propertiesAndOps = intArrayOf(
                DPC_EXPOSURE_PROGRAM,
                DPC_SONY_ISO,
                DPC_FNUMBER,
                DPC_EXPOSURE_BIAS,
            ),
            controlsAndEvents = intArrayOf(
                SonyCamera.DPC_SONY_SHUTTER_HALF,
                SonyCamera.DPC_SONY_SHUTTER_RELEASE,
            ),
        )
        val transport = ReplayTransport(
            listOf(
                handshakeTranscript(
                    deviceInfo = deviceInfo,
                    sessionOpcode = PtpSession.OC_OPEN_SESSION,
                    extInfo = extInfo,
                    props = SonyDpdFixtures.mode2Blob(),
                    afterHandshake = PtpReplayWire.concat(
                        PtpReplayWire.response(tid = 7), // half-press pre-warm
                        PtpReplayWire.response(tid = 8), // release pre-warm
                        PtpReplayWire.response(tid = 9), // ISO notch
                    ),
                ),
            ),
        )
        val warnings = mutableListOf<String>()
        val camera = SonyCamera(transport)

        assertEquals("ILCE-7M3", camera.getDeviceInfo().model)
        camera.openNegotiatedSession(sessionId = 3, onWarn = warnings::add)
        val props = camera.handshake(warnings::add)

        assertFalse(camera.extendedSession)
        assertEquals(SonyDialect.SDIO, camera.profile.dialect)
        assertEquals(SonyCamera.SONY_PROTOCOL_2_00, camera.profile.protocolVersion)
        assertEquals(2, camera.profile.modeVer)
        assertEquals(SonyCaptureStrategy.HALF_AND_FULL_PRESS, camera.profile.captureStrategy)
        assertFalse(camera.profile.canMovieRecord)
        assertEquals(4, props.size)
        assertTrue(warnings.any { it == "session: standard PTP 0x1002" })

        val isoPick = requireNotNull(buildPickList(ExposureControl.ISO, props, modeVer = 2))
        assertEquals(DPC_SONY_ISO, isoPick.propCode)
        assertEquals(
            SonyWriteMode.NOTCH,
            ExposureControl.ISO.writeModeFor(props.getValue(isoPick.propCode), modeVer = 2),
        )
        camera.stepBy(isoPick.propCode, steps = 1)

        val writes = transport.writes.map(PtpReplayWire::decode)
        val commands = writes.filter { it.type == PtpSession.TYPE_COMMAND }
        assertEquals(
            listOf(
                0x1001, 0x1002, 0x9201, 0x9201, 0x9202, 0x9201, 0x9205, 0x9209,
                0x9207, 0x9207, 0x9207,
            ),
            commands.map { it.code },
        )
        assertEquals(listOf(1, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9), commands.map { it.tid })

        val open = commands.single { it.code == PtpSession.OC_OPEN_SESSION }
        assertEquals(0, open.tid)
        assertArrayEquals(le32(3), open.payload)

        val priorityCommand = commands.single { it.code == SonyCamera.OC_SDIO_SET_EXT_DEVICE_PROP_VALUE }
        assertArrayEquals(le32(DPC_SONY_PRIORITY_MODE), priorityCommand.payload)
        val priorityData = writes.single {
            it.type == PtpSession.TYPE_DATA &&
                it.code == SonyCamera.OC_SDIO_SET_EXT_DEVICE_PROP_VALUE
        }
        assertEquals(priorityCommand.tid, priorityData.tid)
        assertArrayEquals(byteArrayOf(1), priorityData.payload)

        val prewarmCommands = commands.filter { it.code == SonyCamera.OC_SDIO_CONTROL_DEVICE }
        assertEquals(3, prewarmCommands.size)
        prewarmCommands.take(2).forEach {
            assertArrayEquals(le32(SonyCamera.DPC_SONY_SHUTTER_HALF), it.payload)
        }
        assertArrayEquals(le32(DPC_SONY_ISO), prewarmCommands.last().payload)
        val prewarmData = writes.filter {
            it.type == PtpSession.TYPE_DATA && it.code == SonyCamera.OC_SDIO_CONTROL_DEVICE
        }
        assertEquals(listOf(listOf<Byte>(2, 0), listOf<Byte>(1, 0)),
            prewarmData.take(2).map { it.payload.toList() })
        assertArrayEquals(byteArrayOf(1), prewarmData.last().payload)
        assertTrue(transport.drains.isEmpty())
    }

    @Test
    fun `a6700 transcript negotiates extended session and modern controls`() {
        val operations = baseSdioOperations(includeExtendedSession = true)
        val deviceInfo = PtpReplayWire.sonyDeviceInfo(
            operations = operations,
            model = "ILCE-6700",
            deviceVersion = "1.03",
        )
        val extInfo = PtpReplayWire.sonyExtDeviceInfo(
            protocolVersion = SonyCamera.SONY_PROTOCOL_3_00,
            propertiesAndOps = intArrayOf(
                DPC_EXPOSURE_PROGRAM,
                DPC_SONY_ISO,
                DPC_FNUMBER,
            ),
            controlsAndEvents = intArrayOf(
                SonyCamera.DPC_SONY_S1_S2_BUTTON,
                SonyCamera.DPC_SONY_MOVIE_REC,
                SonyCamera.DPC_SONY_POSTVIEW_ENABLE,
                SonyCamera.DPC_SONY_LIVEVIEW_ENABLE,
            ),
        )
        val transport = ReplayTransport(
            listOf(
                handshakeTranscript(
                    deviceInfo = deviceInfo,
                    sessionOpcode = OC_SDIO_OPEN_SESSION,
                    extInfo = extInfo,
                    props = SonyDpdFixtures.mode3WithSecondaryEnumBlob(),
                    afterHandshake = PtpReplayWire.response(tid = 7), // REC press
                ),
            ),
        )
        val warnings = mutableListOf<String>()
        val camera = SonyCamera(transport)

        assertEquals("ILCE-6700", camera.getDeviceInfo().model)
        camera.openNegotiatedSession(sessionId = 9, onWarn = warnings::add)
        val props = camera.handshake(warnings::add)

        assertTrue(camera.extendedSession)
        assertEquals(SonyDialect.SDIO, camera.profile.dialect)
        assertEquals(SonyCamera.SONY_PROTOCOL_3_00, camera.profile.protocolVersion)
        assertEquals(3, camera.profile.modeVer)
        assertEquals(SonyCaptureStrategy.COMBINED_S1_S2, camera.profile.captureStrategy)
        assertTrue(camera.profile.canMovieRecord)
        assertEquals(SonyLiveViewState.UNVERIFIED, camera.profile.liveViewState)
        assertTrue(camera.profile.shouldProbeLiveView)
        assertFalse(camera.profile.canLiveView)
        assertTrue(props.getValue(SonyCamera.DPC_SONY_S1_S2_BUTTON).synthetic)
        assertTrue(props.getValue(SonyCamera.DPC_SONY_MOVIE_REC).synthetic)
        assertTrue(props.getValue(SonyCamera.DPC_SONY_POSTVIEW_ENABLE).synthetic)
        assertTrue(props.getValue(SonyCamera.DPC_SONY_LIVEVIEW_ENABLE).synthetic)
        assertTrue(warnings.any { it == "session: Sony SDIO 0x9210 function=remote+transfer" })

        // Exercise one negotiated control write so the extended-session option
        // flag is checked beyond PriorityMode as well.
        camera.movieRecStart()

        val writes = transport.writes.map(PtpReplayWire::decode)
        val commands = writes.filter { it.type == PtpSession.TYPE_COMMAND }
        assertEquals(
            listOf(0x1001, 0x9210, 0x9201, 0x9201, 0x9202, 0x9201, 0x9205, 0x9209, 0x9207),
            commands.map { it.code },
        )
        assertEquals(listOf(1, 0, 1, 2, 3, 4, 5, 6, 7), commands.map { it.tid })

        val open = commands.single { it.code == OC_SDIO_OPEN_SESSION }
        assertEquals(0, open.tid)
        assertArrayEquals(le32(9) + le32(2), open.payload)

        val priority = commands.single { it.code == SonyCamera.OC_SDIO_SET_EXT_DEVICE_PROP_VALUE }
        assertArrayEquals(le32(DPC_SONY_PRIORITY_MODE) + le32(1), priority.payload)

        val recCommand = commands.single { it.code == SonyCamera.OC_SDIO_CONTROL_DEVICE }
        assertArrayEquals(le32(SonyCamera.DPC_SONY_MOVIE_REC) + le32(1), recCommand.payload)
        val recData = writes.single {
            it.type == PtpSession.TYPE_DATA && it.code == SonyCamera.OC_SDIO_CONTROL_DEVICE
        }
        assertEquals(recCommand.tid, recData.tid)
        assertArrayEquals(byteArrayOf(2, 0), recData.payload)
        assertTrue(transport.drains.isEmpty())
    }

    @Test
    fun `combined capture verifies host and card destination before D2E6`() {
        val operations = baseSdioOperations(includeExtendedSession = true)
        val deviceInfo = PtpReplayWire.sonyDeviceInfo(
            operations = operations,
            properties = intArrayOf(DPC_SONY_SAVE_DEST),
            model = "ILCE-6700",
            deviceVersion = "1.03",
        )
        val extInfo = PtpReplayWire.sonyExtDeviceInfo(
            protocolVersion = SonyCamera.SONY_PROTOCOL_3_00,
            propertiesAndOps = intArrayOf(DPC_SONY_SAVE_DEST),
            controlsAndEvents = intArrayOf(SonyCamera.DPC_SONY_S1_S2_BUTTON),
        )
        // D222 is status/readback data on Sony bodies. The primitive may request
        // 0x0011, but the caller must refresh 0x9209 and see that exact value
        // before allowing the combined shutter control to fire.
        val initialProps = saveDestinationBlob(SonyCamera.SAVE_DEST_HOST)
        val verifiedProps = saveDestinationBlob(SonyCamera.SAVE_DEST_HOST_AND_CAMERA)
        val transport = ReplayTransport(
            listOf(
                handshakeTranscript(
                    deviceInfo = deviceInfo,
                    sessionOpcode = OC_SDIO_OPEN_SESSION,
                    extInfo = extInfo,
                    props = initialProps,
                    afterHandshake = PtpReplayWire.concat(
                        PtpReplayWire.response(tid = 7), // request D222 = host + card
                        PtpReplayWire.data(
                            SonyCamera.OC_SDIO_GET_ALL_EXT_DEVICE_PROP_INFO,
                            tid = 8,
                            verifiedProps,
                        ),
                        PtpReplayWire.response(tid = 8), // D222 readback
                        PtpReplayWire.response(tid = 9), // D2E6 down
                        PtpReplayWire.response(tid = 10), // D2E6 up
                    ),
                ),
            ),
        )
        val camera = SonyCamera(transport)

        camera.getDeviceInfo()
        camera.openNegotiatedSession(sessionId = 13)
        val props = camera.handshake()
        assertEquals(SonyCamera.SAVE_DEST_HOST.toLong(),
            props.getValue(DPC_SONY_SAVE_DEST).currentValue)
        assertEquals(SonyCaptureStrategy.COMBINED_S1_S2, camera.profile.captureStrategy)

        assertTrue(camera.requestHostAndCameraSaveDestination())
        val refreshed = camera.getAllProps()
        assertEquals(
            SonyCamera.SAVE_DEST_HOST_AND_CAMERA.toLong(),
            refreshed.getValue(DPC_SONY_SAVE_DEST).currentValue,
        )
        camera.capturePhoto(holdMs = 50)

        val writes = transport.writes.map(PtpReplayWire::decode)
        val commands = writes.filter { it.type == PtpSession.TYPE_COMMAND }
        assertEquals(
            listOf(
                0x1001, 0x9210, 0x9201, 0x9201, 0x9202, 0x9201,
                0x9205, 0x9209, 0x9205, 0x9209, 0x9207, 0x9207,
            ),
            commands.map { it.code },
        )
        assertEquals(listOf(1, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10), commands.map { it.tid })

        val destinationCommand = commands.single {
            it.code == SonyCamera.OC_SDIO_SET_EXT_DEVICE_PROP_VALUE && it.tid == 7
        }
        assertArrayEquals(
            le32(DPC_SONY_SAVE_DEST) + le32(1),
            destinationCommand.payload,
        )
        val destinationData = writes.single {
            it.type == PtpSession.TYPE_DATA &&
                it.code == SonyCamera.OC_SDIO_SET_EXT_DEVICE_PROP_VALUE &&
                it.tid == destinationCommand.tid
        }
        assertArrayEquals(le16(SonyCamera.SAVE_DEST_HOST_AND_CAMERA), destinationData.payload)

        val shutterCommands = commands.filter {
            it.code == SonyCamera.OC_SDIO_CONTROL_DEVICE && it.tid >= 9
        }
        assertEquals(2, shutterCommands.size)
        shutterCommands.forEach {
            assertArrayEquals(
                le32(SonyCamera.DPC_SONY_S1_S2_BUTTON) + le32(1),
                it.payload,
            )
        }
        val shutterData = writes.filter {
            it.type == PtpSession.TYPE_DATA &&
                it.code == SonyCamera.OC_SDIO_CONTROL_DEVICE &&
                it.tid >= 9
        }
        assertEquals(
            listOf(listOf<Byte>(2, 0), listOf<Byte>(1, 0)),
            shutterData.map { it.payload.toList() },
        )
        assertTrue(transport.drains.isEmpty())
    }

    @Test
    fun `mode 2 ISO2 uses absolute D226 instead of the legacy notch path`() {
        val deviceInfo = PtpReplayWire.sonyDeviceInfo(
            operations = baseSdioOperations(includeExtendedSession = false),
            properties = intArrayOf(DPC_SONY_ISO, SonyCamera.DPC_SONY_ISO2),
            model = "ILCE-TEST",
        )
        val extInfo = PtpReplayWire.sonyExtDeviceInfo(
            protocolVersion = SonyCamera.SONY_PROTOCOL_2_00,
            propertiesAndOps = intArrayOf(DPC_SONY_ISO, SonyCamera.DPC_SONY_ISO2),
            controlsAndEvents = intArrayOf(),
        )
        val propsBlob = SonyDpdFixtures.blob(
            SonyDpdFixtures.Descriptor(
                code = DPC_SONY_ISO,
                dataType = DTC_UINT32,
                currentValue = 32_000,
                formFlag = DPFF_ENUM,
                enumValues = listOf(100, 32_000),
            ),
            SonyDpdFixtures.Descriptor(
                code = SonyCamera.DPC_SONY_ISO2,
                dataType = DTC_UINT32,
                currentValue = 102_400,
                formFlag = DPFF_ENUM,
                enumValues = listOf(100, 32_000, 102_400),
            ),
        )
        val transport = ReplayTransport(
            listOf(
                handshakeTranscript(
                    deviceInfo = deviceInfo,
                    sessionOpcode = PtpSession.OC_OPEN_SESSION,
                    extInfo = extInfo,
                    props = propsBlob,
                    afterHandshake = PtpReplayWire.response(tid = 7),
                ),
            ),
        )
        val camera = SonyCamera(transport)

        camera.getDeviceInfo()
        camera.openNegotiatedSession(sessionId = 11)
        val props = camera.handshake()
        val pick = requireNotNull(buildPickList(ExposureControl.ISO, props, modeVer = 2))
        assertEquals(
            SonyWriteMode.ABSOLUTE,
            ExposureControl.ISO.writeModeFor(props.getValue(pick.propCode), modeVer = 2),
        )
        camera.setAbsoluteFor(ExposureControl.ISO, pick.propCode, 102_400)

        assertEquals(SonyCamera.DPC_SONY_ISO2, pick.propCode)
        val writes = transport.writes.map(PtpReplayWire::decode)
        val isoCommand = writes.last {
            it.type == PtpSession.TYPE_COMMAND &&
                it.code == SonyCamera.OC_SDIO_SET_EXT_DEVICE_PROP_VALUE
        }
        assertArrayEquals(le32(SonyCamera.DPC_SONY_ISO2), isoCommand.payload)
        val isoData = writes.single {
            it.type == PtpSession.TYPE_DATA &&
                it.code == SonyCamera.OC_SDIO_SET_EXT_DEVICE_PROP_VALUE &&
                it.tid == isoCommand.tid
        }
        assertArrayEquals(le32(102_400), isoData.payload)
    }

    /**
     * A responder that cannot supply a data phase aborts it by halting bulk-IN;
     * once the transport clears that halt, what actually arrives is a RESPONSE
     * container where the engine was expecting DATA. This is how an A7 III
     * answers a live-view fetch while live view is off, and mishandling it took
     * the whole session down rather than one probe.
     *
     * The stall itself is a USB-level event that this JVM replay cannot stage.
     * What it pins down is the half that broke the camera: the engine must
     * report the PTP error, leave framing aligned, and need no input recovery,
     * so the very next transaction still parses.
     */
    @Test
    fun `aborted live-view data phase reports the error and leaves framing intact`() {
        val operations = baseSdioOperations(includeExtendedSession = false)
        val transport = ReplayTransport(
            listOf(
                handshakeTranscript(
                    deviceInfo = PtpReplayWire.sonyDeviceInfo(
                        operations = operations,
                        model = "ILCE-7M3",
                        deviceVersion = "4.01",
                    ),
                    sessionOpcode = PtpSession.OC_OPEN_SESSION,
                    extInfo = PtpReplayWire.sonyExtDeviceInfo(
                        protocolVersion = SonyCamera.SONY_PROTOCOL_2_00,
                        propertiesAndOps = intArrayOf(DPC_EXPOSURE_PROGRAM, DPC_SONY_ISO),
                        controlsAndEvents = intArrayOf(
                            SonyCamera.DPC_SONY_SHUTTER_HALF,
                            SonyCamera.DPC_SONY_SHUTTER_RELEASE,
                        ),
                    ),
                    props = SonyDpdFixtures.mode2Blob(),
                    afterHandshake = PtpReplayWire.concat(
                        PtpReplayWire.response(tid = 7), // half-press pre-warm
                        PtpReplayWire.response(tid = 8), // release pre-warm
                        // GetObjectInfo(0xFFFFC002): no DATA container at all,
                        // just the error the camera had queued behind the stall.
                        PtpReplayWire.response(SonyCamera.RC_INVALID_OBJECT_HANDLE, tid = 9),
                        // The session must still be usable straight afterwards.
                        PtpReplayWire.data(
                            SonyCamera.OC_SDIO_GET_ALL_EXT_DEVICE_PROP_INFO,
                            tid = 10,
                            SonyDpdFixtures.mode2Blob(),
                        ),
                        PtpReplayWire.response(tid = 10),
                    ),
                ),
            ),
        )
        val camera = SonyCamera(transport)
        camera.getDeviceInfo()
        camera.openNegotiatedSession(sessionId = 1)
        val props = camera.handshake()
        assertEquals(SonyLiveViewState.UNVERIFIED, camera.profile.liveViewState)

        // One bounded probe: no frame, one counted miss, live view still open
        // to a later attempt rather than the session being torn down.
        assertNull(camera.probeLiveViewFrame(props))
        assertEquals(1, camera.liveViewProbeFailures)
        assertEquals(SonyLiveViewState.UNVERIFIED, camera.profile.liveViewState)

        // Framing survived the missing data phase.
        assertEquals(props.keys, camera.getAllProps().keys)
        assertTrue(transport.drains.isEmpty())

        val commands = transport.writes.map(PtpReplayWire::decode)
            .filter { it.type == PtpSession.TYPE_COMMAND }
        assertEquals(
            listOf(0x1001, 0x1002, 0x9201, 0x9201, 0x9202, 0x9201, 0x9205, 0x9209,
                0x9207, 0x9207, 0x1008, 0x9209),
            commands.map { it.code },
        )
        assertEquals(listOf(1, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10), commands.map { it.tid })
    }

    private fun handshakeTranscript(
        deviceInfo: ByteArray,
        sessionOpcode: Int,
        extInfo: ByteArray,
        props: ByteArray,
        afterHandshake: ByteArray = byteArrayOf(),
    ): ByteArray = PtpReplayWire.concat(
        PtpReplayWire.data(PtpSession.OC_GET_DEVICE_INFO, tid = 1, deviceInfo),
        PtpReplayWire.response(tid = 1),
        PtpReplayWire.response(tid = 0), // standard or extended OpenSession
        PtpReplayWire.response(tid = 1), // SDIO connect phase 1
        PtpReplayWire.response(tid = 2), // SDIO connect phase 2
        PtpReplayWire.data(SonyCamera.OC_SDIO_GET_EXT_DEVICE_INFO, tid = 3, extInfo),
        PtpReplayWire.response(tid = 3),
        PtpReplayWire.response(tid = 4), // SDIO connect phase 3
        PtpReplayWire.response(tid = 5), // PriorityMode = application
        PtpReplayWire.data(SonyCamera.OC_SDIO_GET_ALL_EXT_DEVICE_PROP_INFO, tid = 6, props),
        PtpReplayWire.response(tid = 6),
        afterHandshake,
    ).also {
        // Documents which opener the TID-0 slot belongs to; outgoing framing is
        // asserted independently, so the replay cannot silently choose another.
        require(sessionOpcode == PtpSession.OC_OPEN_SESSION || sessionOpcode == OC_SDIO_OPEN_SESSION)
    }

    private fun baseSdioOperations(includeExtendedSession: Boolean): IntArray = buildList {
        add(PtpSession.OC_GET_DEVICE_INFO)
        add(PtpSession.OC_OPEN_SESSION)
        add(PtpSession.OC_GET_OBJECT_INFO)
        add(PtpSession.OC_GET_OBJECT)
        add(SonyCamera.OC_SDIO_CONNECT)
        add(SonyCamera.OC_SDIO_GET_EXT_DEVICE_INFO)
        add(SonyCamera.OC_SDIO_SET_EXT_DEVICE_PROP_VALUE)
        add(SonyCamera.OC_SDIO_CONTROL_DEVICE)
        add(SonyCamera.OC_SDIO_GET_ALL_EXT_DEVICE_PROP_INFO)
        if (includeExtendedSession) add(OC_SDIO_OPEN_SESSION)
    }.toIntArray()

    private fun le32(value: Int): ByteArray = byteArrayOf(
        value.toByte(),
        (value ushr 8).toByte(),
        (value ushr 16).toByte(),
        (value ushr 24).toByte(),
    )

    private fun le16(value: Int): ByteArray = byteArrayOf(
        value.toByte(),
        (value ushr 8).toByte(),
    )

    private fun saveDestinationBlob(current: Int): ByteArray = SonyDpdFixtures.blob(
        SonyDpdFixtures.Descriptor(
            code = DPC_SONY_SAVE_DEST,
            dataType = DTC_UINT16,
            getSet = 0,
            currentValue = current.toLong(),
        ),
    )
}

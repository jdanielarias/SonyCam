package com.sonycam.monitor.sony

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SonyExposureCapabilitiesTest {
    /**
     * Byte-for-byte D21E descriptor from a live ILCE-6700 (fw 2.00) 0x9209
     * capture, photo mode M, taken over ADB on 2026-08-24. The camera offers
     * ISO 50-102400, but every stop outside the native 100-32000 range arrives
     * with Sony's EXTENDED kind byte (0x10) in the high byte. Treating that
     * byte as "MFNR, hide it" is what capped the wheel at 32000 through v0.5.
     */
    private val a6700LiveD21eDescriptor = (
        "1ED20600010100000000007D0000022300" +
            "FFFFFF00320000104000001050000010640000007D000000A0000000C8000000" +
            "FA0000004001000090010000F40100008002000020030000E8030000E2040000" +
            "40060000D0070000C4090000800C0000A00F00008813000000190000401F0000" +
            "1027000000320000803E0000204E0000006400 00007D0000409C001000C80010" +
            "00FA0010803801100090011022D2040001010000100002"
        ).replace(" ", "")

    @Test
    fun `live a6700 D21E capture offers every announced stop up to ISO 102400`() {
        val bytes = a6700LiveD21eDescriptor.chunked(2)
            .map { it.toInt(16).toByte() }.toByteArray()
        // parseSonyAllProps expects the 8-byte count/reserved header.
        val blob = byteArrayOf(1, 0, 0, 0, 0, 0, 0, 0) + bytes

        val props = parseSonyAllProps(blob)
        val iso = requireNotNull(props[DPC_SONY_ISO])
        assertEquals(35, iso.setEnumValues?.size)

        val pick = requireNotNull(buildPickList(ExposureControl.ISO, props, modeVer = 3))

        assertEquals(35, pick.raws.size)
        assertEquals("Auto", pick.labels.first())
        assertEquals("50", pick.labels[1])
        assertEquals("102400", pick.labels.last())
        // The extended stops must read as ordinary ISOs, never as "+MFNR".
        assertTrue(pick.labels.none { it.contains("MFNR") })
        // ...and the raw the app would write back is the camera's own encoding.
        assertEquals(0x1001_9000L, pick.raws.last())
        assertEquals(0x1000_0032L, pick.raws[1])
        // Current value 32000 still centres the wheel exactly where it was.
        assertEquals(0x7D00L, pick.currentRaw)
        assertEquals("32000", pick.labels[pick.index])
        assertTrue(ExposureControl.ISO.editable(props, 3, mode = 0x0001))
    }

    @Test
    fun `extended ISO kind decodes as a plain stop and MFNR kinds stay labelled`() {
        assertEquals("102400", decodeIso(0x1001_9000L))
        assertEquals("50", decodeIso(0x1000_0032L))
        assertEquals("Auto", decodeIso(0x00FF_FFFFL))
        assertEquals("100 +MFNR", decodeIso(0x0100_0064L))
        assertEquals("400 +MFNR+", decodeIso(0x0200_0190L))
        assertFalse(isSonyIsoMfnr(0x1001_9000L))
        assertTrue(isSonyIsoMfnr(0x0100_0064L))
    }

    @Test
    fun `exposure program normalizes mode 2 and grouped mode 3 values`() {
        assertEquals(0x0003, normalizeExposureMode(0x0003))
        assertEquals(0x0003, normalizeExposureMode(0x0002_0003))
        assertEquals("A", decodeExposureMode(0x0003))
        assertEquals("A", decodeExposureMode(0x0002_0003))

        assertEquals(0x8053, normalizeExposureMode(0x0007_8053))
        assertEquals(7, exposureModeGroup(0x0007_8053))
        assertEquals("Movie-M", decodeExposureMode(0x0007_8053))
        assertEquals("Interval Movie-M", decodeExposureMode(0x000C_8096))
        assertTrue(canRecordMovie(normalizeExposureMode(0x000C_8096)))
    }

    @Test
    fun `mode 2 ISO pick list removes unreachable and flagged entries`() {
        val prop = enumProp(
            code = DPC_SONY_ISO,
            current = 204_800,
            values = listOf(
                0x00FF_FFFFL, 100, 200, 204_800, 256_000, 409_600,
                0x0100_0064L, // flag bits: multi-frame-NR variant of ISO 100
            ),
            dataType = DTC_UINT32,
        )

        val pick = requireNotNull(buildPickList(ExposureControl.ISO, mapOf(prop.code to prop), 2))

        assertEquals(DPC_SONY_ISO, pick.propCode)
        assertEquals(listOf(0x00FF_FFFFL, 100L, 200L, 204_800L), pick.raws)
        assertEquals(3, pick.index)
        assertEquals("204800", pick.labels[pick.index])
    }

    @Test
    fun `mode 2 notch ISO fails closed when current has no exact Set position`() {
        val prop = enumProp(
            code = DPC_SONY_ISO,
            current = 460,
            values = listOf(100, 200, 400, 500, 800),
            dataType = DTC_UINT32,
        )
        val props = mapOf(prop.code to prop)

        // A nearest index is unsafe for relative notches: the subsequent delta
        // would be calculated from a position the camera never reported.
        assertNull(buildPickList(ExposureControl.ISO, props, modeVer = 2))
        assertFalse(ExposureControl.ISO.editable(props, 2, mode = 0x0001))
    }

    @Test
    fun `ISO2 wins coherently when its own descriptor is usable`() {
        val legacy = enumProp(
            code = DPC_SONY_ISO,
            current = 32_000,
            values = listOf(100, 200, 32_000),
            dataType = DTC_UINT32,
        )
        val iso2 = enumProp(
            code = SonyCamera.DPC_SONY_ISO2,
            current = 102_400,
            values = listOf(100, 32_000, 102_400),
            dataType = DTC_UINT32,
        )
        val props = linkedMapOf(legacy.code to legacy, iso2.code to iso2)

        val effective = requireNotNull(ExposureControl.ISO.effectiveProp(props))
        val pick = requireNotNull(buildPickList(ExposureControl.ISO, props, modeVer = 2))

        assertEquals(SonyCamera.DPC_SONY_ISO2, effective.code)
        assertEquals(SonyCamera.DPC_SONY_ISO2, pick.propCode)
        assertEquals(102_400L, pick.currentRaw)
        assertEquals(listOf(100L, 32_000L, 102_400L), pick.raws)
        assertEquals(102_400L, pick.raws[pick.index])
        assertEquals("102400", decodeReadings(props).iso)
        assertEquals(SonyWriteMode.ABSOLUTE, ExposureControl.ISO.writeModeFor(effective, 2))
        assertTrue(ExposureControl.ISO.editable(props, 2, mode = 0x0001))
    }

    @Test
    fun `ISO falls back to D21E when ISO2 descriptor is not usable`() {
        val legacy = enumProp(
            code = DPC_SONY_ISO,
            current = 2_000,
            values = listOf(100, 1_000, 2_000, 3_200),
            dataType = DTC_UINT32,
        )
        val iso2WithoutForm = SonyDeviceProp(
            code = SonyCamera.DPC_SONY_ISO2,
            dataType = DTC_UINT32,
            getSet = 1,
            isEnabled = 1,
            currentValue = 102_400,
            formFlag = DPFF_NONE,
        )
        val props = linkedMapOf(legacy.code to legacy, iso2WithoutForm.code to iso2WithoutForm)

        val effective = requireNotNull(ExposureControl.ISO.effectiveProp(props))
        val pick = requireNotNull(buildPickList(ExposureControl.ISO, props, modeVer = 2))

        assertEquals(DPC_SONY_ISO, effective.code)
        assertEquals(DPC_SONY_ISO, pick.propCode)
        assertEquals(2_000L, pick.currentRaw)
        assertEquals(legacy.enumValues, pick.raws)
        assertEquals("2000", decodeReadings(props).iso)
        assertEquals(SonyWriteMode.NOTCH, ExposureControl.ISO.writeModeFor(effective, 2))

        // A present but malformed legacy alias is not a viable fallback. In
        // that case readable ISO2 remains the only coherent source.
        val malformedLegacy = legacy.copy(dataType = DTC_UINT16)
        val malformedMap = linkedMapOf(
            malformedLegacy.code to malformedLegacy,
            iso2WithoutForm.code to iso2WithoutForm,
        )
        assertEquals(
            SonyCamera.DPC_SONY_ISO2,
            ExposureControl.ISO.effectiveProp(malformedMap, modeVer = 2)?.code,
        )
    }

    @Test
    fun `disabled ISO2 does not mask a writable D21E alias`() {
        val legacy = enumProp(
            code = DPC_SONY_ISO,
            current = 2_000,
            values = listOf(100, 1_000, 2_000),
            dataType = DTC_UINT32,
        )
        val disabledIso2 = enumProp(
            code = SonyCamera.DPC_SONY_ISO2,
            current = 102_400,
            values = listOf(100, 32_000, 102_400),
            dataType = DTC_UINT32,
        ).copy(isEnabled = 0)
        val props = linkedMapOf(legacy.code to legacy, disabledIso2.code to disabledIso2)

        val effective = requireNotNull(ExposureControl.ISO.effectiveProp(props, modeVer = 3))
        val pick = requireNotNull(buildPickList(ExposureControl.ISO, props, modeVer = 3))

        assertEquals(DPC_SONY_ISO, effective.code)
        assertEquals(DPC_SONY_ISO, pick.propCode)
        assertEquals(legacy.enumValues, pick.raws)
        assertEquals("2000", decodeReadings(props, modeVer = 3).iso)
        assertTrue(ExposureControl.ISO.editable(props, 3, mode = 0x0001))
    }

    @Test
    fun `active empty ISO2 enum does not invent generic choices`() {
        val iso2 = SonyDeviceProp(
            code = SonyCamera.DPC_SONY_ISO2,
            dataType = DTC_UINT32,
            getSet = 1,
            isEnabled = 1,
            currentValue = 100,
            formFlag = DPFF_ENUM,
            enumValues = emptyList(),
        )

        assertEquals(
            null,
            buildPickList(ExposureControl.ISO, mapOf(iso2.code to iso2), modeVer = 3),
        )
    }

    @Test
    fun `mode 3 ISO pick list retains camera advertised high values`() {
        val prop = enumProp(
            code = DPC_SONY_ISO,
            current = 409_600,
            values = listOf(100, 204_800, 256_000, 409_600, 0x0100_0064L),
            dataType = DTC_UINT32,
        )

        val pick = requireNotNull(buildPickList(ExposureControl.ISO, mapOf(prop.code to prop), 3))

        assertEquals(listOf(100L, 204_800L, 256_000L, 409_600L), pick.raws)
        assertEquals(3, pick.index)
    }

    @Test
    fun `a6700 photo and movie replays follow their state specific Set enum`() {
        fun replay(current: Long, setValues: List<Long>, shootingModeRaw: Long?): PickList {
            val props = parseSonyAllProps(
                SonyDpdFixtures.a6700IsoBlob(
                    current = current,
                    setValues = setValues + SonyDpdFixtures.a6700MfnrIsos,
                    getSetValues = SonyDpdFixtures.a6700GetSetIsos +
                        SonyDpdFixtures.a6700MfnrIsos,
                    shootingModeRaw = shootingModeRaw,
                ),
            )
            return requireNotNull(buildPickList(ExposureControl.ISO, props, modeVer = 3))
        }

        val expandedStill = replay(
            SonyDpdFixtures.ext(102_400),
            SonyDpdFixtures.a6700PhotoSetIsos,
            0x0000_0001,
        )
        // The expanded stops are ordinary ISOs wearing Sony's EXTENDED kind
        // byte; the wheel must offer all 35 of them, ISO 50 and 102400 included.
        assertEquals(SonyDpdFixtures.a6700PhotoSetIsos, expandedStill.raws)
        assertEquals(SonyDpdFixtures.ext(102_400), expandedStill.raws.last())
        assertEquals("102400", expandedStill.labels.last())
        assertEquals("50", expandedStill.labels[1])
        assertEquals(SonyDpdFixtures.ext(102_400), expandedStill.raws[expandedStill.index])
        assertTrue(expandedStill.raws.none { it in SonyDpdFixtures.a6700MfnrIsos })

        val movie = replay(32_000, SonyDpdFixtures.a6700MovieSetIsos, 0x0007_8053)
        assertEquals(32_000L, movie.raws.last())
        assertFalse(movie.raws.contains(SonyDpdFixtures.ext(40_000)))
        assertFalse(movie.raws.contains(SonyDpdFixtures.ext(102_400)))
        assertEquals(32_000L, movie.raws[movie.index])
        assertTrue(movie.raws.none { it in SonyDpdFixtures.a6700MfnrIsos })

        val slowAndQuick = replay(32_000, SonyDpdFixtures.a6700MovieSetIsos, 0x0009_805C)
        assertEquals(movie.raws, slowAndQuick.raws)

        // The Set list — not a mode classifier — drives the result.
        assertEquals(
            32_000L,
            replay(32_000, SonyDpdFixtures.a6700MovieSetIsos, null).raws.last(),
        )
    }

    @Test
    fun `synthetic wire order regression never replaces Set with shorter GetSet`() {
        val props = parseSonyAllProps(
            SonyDpdFixtures.a6700IsoBlob(
                current = SonyDpdFixtures.ext(102_400),
                setValues = SonyDpdFixtures.a6700PhotoSetIsos,
                // Deliberately shorter than the usual Get/Set catalogue. This
                // synthetic case isolates protocol order from model/mode guesses.
                getSetValues = SonyDpdFixtures.a6700MovieSetIsos,
                isoCode = SonyCamera.DPC_SONY_ISO2,
                shootingModeRaw = 0x0007_8053,
            ),
        )

        val pick = requireNotNull(buildPickList(ExposureControl.ISO, props, modeVer = 3))

        assertEquals(SonyCamera.DPC_SONY_ISO2, pick.propCode)
        assertEquals(SonyDpdFixtures.ext(102_400), pick.raws.last())
        assertEquals(SonyDpdFixtures.ext(102_400), pick.currentRaw)
    }

    @Test
    fun `modern ISO2 keeps D226 current list and write binding coherent over D21E`() {
        val legacy = SonyDpdFixtures.Descriptor(
            code = DPC_SONY_ISO,
            dataType = DTC_UINT32,
            currentValue = 222,
            formFlag = DPFF_ENUM,
            enumValues = listOf(111, 222, 333),
        )

        fun replay(
            current: Long,
            setValues: List<Long>,
            shootingModeRaw: Long,
        ): Pair<Map<Int, SonyDeviceProp>, PickList> {
            val props = parseSonyAllProps(
                SonyDpdFixtures.a6700IsoBlob(
                    current = current,
                    setValues = setValues + SonyDpdFixtures.a6700MfnrIsos,
                    getSetValues = SonyDpdFixtures.a6700GetSetIsos +
                        SonyDpdFixtures.a6700MfnrIsos,
                    isoCode = SonyCamera.DPC_SONY_ISO2,
                    shootingModeRaw = shootingModeRaw,
                    legacyIso = legacy,
                ),
            )
            return props to requireNotNull(
                buildPickList(ExposureControl.ISO, props, modeVer = 3),
            )
        }

        val (stillProps, still) = replay(
            SonyDpdFixtures.ext(102_400),
            SonyDpdFixtures.a6700PhotoSetIsos,
            0x0000_0001,
        )
        val stillSource = requireNotNull(
            ExposureControl.ISO.effectiveProp(stillProps, modeVer = 3),
        )
        assertEquals(SonyCamera.DPC_SONY_ISO2, stillSource.code)
        assertEquals(SonyCamera.DPC_SONY_ISO2, still.propCode)
        assertEquals(SonyDpdFixtures.ext(102_400), still.currentRaw)
        assertEquals(SonyDpdFixtures.ext(102_400), still.raws.last())
        assertEquals("102400", decodeReadings(stillProps, modeVer = 3).iso)
        assertEquals(SonyWriteMode.ABSOLUTE, ExposureControl.ISO.writeModeFor(stillSource, 3))
        assertTrue(still.raws.none { it in legacy.enumValues })
        assertTrue(still.raws.none { it in SonyDpdFixtures.a6700MfnrIsos })

        val (movieProps, movie) = replay(
            32_000,
            SonyDpdFixtures.a6700MovieSetIsos,
            0x0007_8053,
        )
        assertEquals(SonyCamera.DPC_SONY_ISO2, movie.propCode)
        assertEquals(32_000L, movie.currentRaw)
        assertEquals(32_000L, movie.raws.last())
        assertEquals("32000", decodeReadings(movieProps, modeVer = 3).iso)
        assertTrue(movie.raws.none { it in legacy.enumValues })
        assertTrue(movie.raws.none { it in SonyDpdFixtures.a6700MfnrIsos })
    }

    @Test
    fun `ISO Set enum never invents values absent from the wire`() {
        val announcedSet = SonyDpdFixtures.a6700PhotoSetIsos
            .filter { it == 0x00FF_FFFFL || (it and 0x00FF_FFFFL) <= 64_000L } +
            SonyDpdFixtures.a6700MfnrIsos
        val props = parseSonyAllProps(
            SonyDpdFixtures.a6700IsoBlob(
                current = SonyDpdFixtures.ext(64_000),
                setValues = announcedSet,
                getSetValues = SonyDpdFixtures.a6700GetSetIsos +
                    SonyDpdFixtures.a6700MfnrIsos,
                isoCode = SonyCamera.DPC_SONY_ISO2,
                shootingModeRaw = 0x0000_0001,
            ),
        )

        val pick = requireNotNull(buildPickList(ExposureControl.ISO, props, modeVer = 3))

        assertEquals(SonyCamera.DPC_SONY_ISO2, pick.propCode)
        assertEquals(SonyDpdFixtures.ext(64_000), pick.raws.last())
        assertFalse(pick.raws.contains(SonyDpdFixtures.ext(80_000)))
        assertFalse(pick.raws.contains(SonyDpdFixtures.ext(102_400)))
        assertTrue(pick.raws.none { it in SonyDpdFixtures.a6700MfnrIsos })
    }

    @Test
    fun `empty Set enum does not borrow nonempty GetSet values`() {
        val props = parseSonyAllProps(
            SonyDpdFixtures.a6700IsoBlob(
                current = 100,
                setValues = emptyList(),
                getSetValues = listOf(100, 200, 400, 32_000),
                isoCode = SonyCamera.DPC_SONY_ISO2,
            ),
        )

        val iso2 = props.getValue(SonyCamera.DPC_SONY_ISO2)
        assertTrue(iso2.setEnumValues.orEmpty().isEmpty())
        assertTrue(iso2.getSetEnumValues.orEmpty().isNotEmpty())
        assertNull(buildPickList(ExposureControl.ISO, props, modeVer = 3))
    }

    @Test
    fun `ISO2 read identity with empty Set never borrows legacy D21E choices`() {
        val legacy = SonyDpdFixtures.Descriptor(
            code = DPC_SONY_ISO,
            dataType = DTC_UINT32,
            currentValue = 2_000,
            formFlag = DPFF_ENUM,
            enumValues = listOf(100, 1_000, 2_000, 3_200),
        )
        val props = parseSonyAllProps(
            SonyDpdFixtures.a6700IsoBlob(
                current = 102_400,
                setValues = emptyList(),
                getSetValues = listOf(100, 32_000, 102_400),
                isoCode = SonyCamera.DPC_SONY_ISO2,
                legacyIso = legacy,
            ),
        )

        val effective = requireNotNull(
            ExposureControl.ISO.effectiveProp(props, modeVer = 3),
        )
        assertEquals(SonyCamera.DPC_SONY_ISO2, effective.code)
        assertEquals(102_400L, effective.currentValue)
        assertEquals("102400", decodeReadings(props, modeVer = 3).iso)
        assertNull(buildPickList(ExposureControl.ISO, props, modeVer = 3))
        assertFalse(ExposureControl.ISO.editable(props, 3, mode = 0x0001))
    }

    @Test
    fun `MFNR current anchors to its base ISO without exposing flagged Set raws`() {
        val flaggedCurrent = 0x0200_0190L // MFNR+ variant whose base ISO is 400
        val props = parseSonyAllProps(
            SonyDpdFixtures.a6700IsoBlob(
                current = flaggedCurrent,
                setValues = listOf(0x00FF_FFFFL, 100, 200, 400, 800) +
                    SonyDpdFixtures.a6700MfnrIsos,
                getSetValues = SonyDpdFixtures.a6700GetSetIsos,
                isoCode = SonyCamera.DPC_SONY_ISO2,
            ),
        )

        val pick = requireNotNull(buildPickList(ExposureControl.ISO, props, modeVer = 3))

        assertEquals(
            listOf(0x00FF_FFFFL, 100L, 200L, 400L, 800L),
            pick.raws,
        )
        assertEquals(flaggedCurrent, pick.currentRaw)
        assertEquals(400L, pick.raws[pick.index])
        assertEquals("400", pick.labels[pick.index])
        assertTrue(pick.raws.none { (it ushr 24) != 0L })
        assertTrue(ExposureControl.ISO.editable(props, 3, mode = 0x0001))
    }

    @Test
    fun `non exact ISO current anchors to nearest writable Set raw`() {
        val props = parseSonyAllProps(
            SonyDpdFixtures.a6700IsoBlob(
                current = 460,
                setValues = listOf(100, 200, 400, 500, 800),
                getSetValues = listOf(100, 200, 400, 460, 500, 800),
                isoCode = SonyCamera.DPC_SONY_ISO2,
            ),
        )

        val pick = requireNotNull(buildPickList(ExposureControl.ISO, props, modeVer = 3))

        assertEquals(listOf(100L, 200L, 400L, 500L, 800L), pick.raws)
        assertEquals(460L, pick.currentRaw)
        assertEquals(500L, pick.raws[pick.index])
        assertTrue(ExposureControl.ISO.editable(props, 3, mode = 0x0001))
    }

    @Test
    fun `ISO current outside Set range clamps to nearest writable endpoint`() {
        val props = parseSonyAllProps(
            SonyDpdFixtures.a6700IsoBlob(
                current = 102_400,
                setValues = listOf(100, 200, 400, 800),
                getSetValues = listOf(100, 200, 400, 800, 102_400),
                isoCode = SonyCamera.DPC_SONY_ISO2,
            ),
        )

        val pick = requireNotNull(buildPickList(ExposureControl.ISO, props, modeVer = 3))
        assertEquals(listOf(100L, 200L, 400L, 800L), pick.raws)
        assertEquals(102_400L, pick.currentRaw)
        assertEquals(800L, pick.raws[pick.index])
        assertTrue(ExposureControl.ISO.editable(props, 3, mode = 0x0001))
    }

    @Test
    fun `non ISO controls use the Set enum rather than GetSet metadata`() {
        val aperture = SonyDeviceProp(
            code = DPC_FNUMBER,
            dataType = DTC_UINT16,
            getSet = 1,
            isEnabled = 1,
            currentValue = 400,
            formFlag = DPFF_ENUM,
            enumValues = listOf(280, 320, 350, 400, 450, 500, 560),
            hadSecondaryEnum = true,
            secondaryEnumValues = listOf(400, 450),
        )
        val mode = SonyDeviceProp(
            code = DPC_EXPOSURE_PROGRAM,
            dataType = DTC_UINT32,
            getSet = 1,
            isEnabled = 1,
            currentValue = 0x0000_0003,
            formFlag = DPFF_NONE,
        )

        val pick = requireNotNull(
            buildPickList(
                ExposureControl.APERTURE,
                mapOf(aperture.code to aperture, mode.code to mode),
                modeVer = 3,
            ),
        )

        assertEquals(listOf(280L, 320L, 350L, 400L, 450L, 500L, 560L), pick.raws)
    }

    @Test
    fun `ISO fallback has no 32000 ceiling when camera omits enumeration`() {
        val noEnum = SonyDeviceProp(
            code = DPC_SONY_ISO,
            dataType = DTC_UINT32,
            getSet = 1,
            isEnabled = 1,
            currentValue = 102_400,
            formFlag = DPFF_NONE,
        )

        val pick = requireNotNull(
            buildPickList(ExposureControl.ISO, mapOf(noEnum.code to noEnum), modeVer = 3),
        )

        assertTrue(pick.raws.containsAll(listOf(32_000L, 40_000L, 102_400L)))
        assertEquals(102_400L, pick.raws[pick.index])
        assertEquals("102400", pick.labels[pick.index])
    }

    @Test
    fun `EV pick list sorts camera enum and keeps exact current index`() {
        val prop = enumProp(
            code = DPC_EXPOSURE_BIAS,
            current = -300,
            values = listOf(0, 300, 700, -300, -700),
            dataType = DTC_INT16,
        )

        val pick = requireNotNull(buildPickList(ExposureControl.EV, mapOf(prop.code to prop), 2))

        assertEquals(listOf(-700L, -300L, 0L, 300L, 700L), pick.raws)
        assertEquals(1, pick.index)
        assertEquals("-0.3", pick.labels[pick.index])
    }

    @Test
    fun `mode 2 aperture range uses canonical thirds and nearest current`() {
        val prop = SonyDeviceProp(
            code = DPC_FNUMBER,
            dataType = DTC_UINT16,
            getSet = 1,
            isEnabled = 1,
            currentValue = 333, // deliberately between the camera-independent thirds
            formFlag = DPFF_RANGE,
            range = Triple(0, 0xFFFF, 1),
        )

        val pick = requireNotNull(buildPickList(ExposureControl.APERTURE, mapOf(prop.code to prop), 2))

        assertTrue(pick.raws.size > 20)
        assertEquals(320L, pick.raws[pick.index])
        assertEquals("f/3.2", pick.labels[pick.index])
    }

    @Test
    fun `pick list is absent when descriptor or current value is unavailable`() {
        assertEquals(null, buildPickList(ExposureControl.SHUTTER, emptyMap(), 3))

        val noCurrent = SonyDeviceProp(
            code = DPC_SONY_SHUTTER,
            dataType = DTC_UINT32,
            getSet = 1,
            isEnabled = 1,
            currentValue = null,
            formFlag = DPFF_ENUM,
            enumValues = listOf(0x0001_0064),
        )
        assertEquals(
            null,
            buildPickList(ExposureControl.SHUTTER, mapOf(noCurrent.code to noCurrent), 3),
        )
    }

    @Test
    fun `profile capabilities come only from negotiated dialect and opcodes`() {
        val baseMode3 = SonyProfile(
            dialect = SonyDialect.SDIO,
            protocolVersion = 0x12C,
            modeVer = 3,
            ops = setOf(
                SonyCamera.OC_SDIO_CONTROL_DEVICE,
                OC_SDIO_GET_PARTIAL_LARGE_OBJECT,
                OC_SDIO_GET_DISPLAY_STRING_LIST,
                OC_SDIO_GET_LENS_INFORMATION,
                0x1008, // standard GetObjectInfo
                0x1009, // standard GetObject
            ),
            vendorProps = setOf(DPC_SONY_ISO, DPC_FNUMBER),
            vendorControls = setOf(
                SonyCamera.DPC_SONY_S1_S2_BUTTON,
                SonyCamera.DPC_SONY_MOVIE_REC,
            ),
            vendorEvents = emptySet(),
        )
        val mode3 = baseMode3.withObservedProps(emptyMap())

        assertTrue(mode3.canControl)
        assertFalse(mode3.canLiveView)
        assertTrue(mode3.shouldProbeLiveView)
        assertEquals(SonyLiveViewState.UNVERIFIED, mode3.liveViewState)
        assertEquals(SonyCaptureStrategy.COMBINED_S1_S2, mode3.captureStrategy)
        assertTrue(mode3.canMovieRecord)
        assertTrue(mode3.hasPartialLargeObject)
        assertTrue(mode3.hasDisplayStringList)
        assertTrue(mode3.hasLensInformation)
        assertEquals(3, mode3.modeVer)
        assertTrue(mode3.copy(liveViewState = SonyLiveViewState.AVAILABLE).canLiveView)

        val generic = SonyProfile.UNKNOWN
        assertFalse(generic.canControl)
        assertFalse(generic.canLiveView)
        assertFalse(generic.shouldProbeLiveView)
        assertFalse(generic.hasPartialLargeObject)
    }

    @Test
    fun `capture strategy distinguishes classic and combined shutter controls`() {
        fun profile(mode: Int, controls: Set<Int>) = SonyProfile(
            dialect = SonyDialect.SDIO,
            protocolVersion = if (mode == 3) 0x12C else 0x0C8,
            modeVer = mode,
            ops = setOf(SonyCamera.OC_SDIO_CONTROL_DEVICE),
            vendorProps = emptySet(),
            vendorControls = controls,
            vendorEvents = emptySet(),
        ).withObservedProps(emptyMap())

        assertEquals(
            SonyCaptureStrategy.COMBINED_S1_S2,
            profile(3, setOf(SonyCamera.DPC_SONY_S1_S2_BUTTON)).captureStrategy,
        )
        assertEquals(
            SonyCaptureStrategy.HALF_AND_FULL_PRESS,
            profile(
                2,
                setOf(SonyCamera.DPC_SONY_SHUTTER_HALF, SonyCamera.DPC_SONY_SHUTTER_RELEASE),
            ).captureStrategy,
        )

        // D2C7 is RequestOneShooting in some older tables, but it is not the
        // combined mode-3 S1/S2 control and must never be guessed as one.
        assertEquals(
            SonyCaptureStrategy.UNSUPPORTED,
            profile(3, setOf(0xD2C7)).captureStrategy,
        )
        assertEquals(
            SonyCaptureStrategy.UNSUPPORTED,
            profile(2, setOf(SonyCamera.DPC_SONY_SHUTTER_HALF)).captureStrategy,
        )
    }

    @Test
    fun `actual control descriptor can advertise capability absent from vendor list`() {
        val s1s2 = SonyDeviceProp(
            code = SonyCamera.DPC_SONY_S1_S2_BUTTON,
            dataType = DTC_UINT16,
            getSet = 0x81,
            isEnabled = 1,
            currentValue = null,
            formFlag = DPFF_ENUM,
        )
        val profile = SonyProfile(
            dialect = SonyDialect.SDIO,
            protocolVersion = 0x12C,
            modeVer = 3,
            ops = setOf(SonyCamera.OC_SDIO_CONTROL_DEVICE),
            vendorProps = emptySet(),
            vendorControls = emptySet(),
            vendorEvents = emptySet(),
        ).withObservedProps(mapOf(s1s2.code to s1s2))

        assertEquals(SonyCaptureStrategy.COMBINED_S1_S2, profile.captureStrategy)
    }

    @Test
    fun `mode 3 enabled byte overrides fallback exposure mode tables`() {
        val disabledAperture = SonyDeviceProp(
            code = DPC_FNUMBER,
            dataType = DTC_UINT16,
            getSet = 1,
            isEnabled = 0,
            currentValue = 280,
            formFlag = DPFF_ENUM,
            enumValues = listOf(280, 320, 400),
        )
        val enabledShutter = disabledAperture.copy(
            code = DPC_SONY_SHUTTER,
            dataType = DTC_UINT32,
            isEnabled = 1,
        )
        val props = mapOf(
            disabledAperture.code to disabledAperture,
            enabledShutter.code to enabledShutter,
        )

        // M would normally allow both. For mode 3 the descriptor is authoritative.
        assertFalse(ExposureControl.APERTURE.editable(props, 3, mode = 0x0001))
        assertTrue(ExposureControl.SHUTTER.editable(props, 3, mode = 0x0001))
    }

    @Test
    fun `missing exposure descriptor fails closed instead of showing a dead control`() {
        assertFalse(ExposureControl.ISO.editable(emptyMap(), 2, mode = 0x0001))
        assertFalse(ExposureControl.APERTURE.editable(emptyMap(), 3, mode = 0x0003))
    }

    private fun enumProp(
        code: Int,
        current: Long,
        values: List<Long>,
        dataType: Int,
    ) = SonyDeviceProp(
        code = code,
        dataType = dataType,
        getSet = 1,
        isEnabled = 1,
        currentValue = current,
        formFlag = DPFF_ENUM,
        enumValues = values,
    )

    /**
     * BULB is the last notch past 30", exactly where the physical dial puts it.
     *
     * The A7 III publishes D20D as a bare range with no enum, so this table is
     * the whole wheel for it — and it used to stop at 30", which is why BULB was
     * unreachable from the app. The value is transcribed from the body with the
     * dial in BULB: `prop 0xD20D ... cur=0x0`.
     */
    @Test
    fun `the shutter wheel reaches BULB one notch past 30 seconds`() {
        val descriptor = SonyDpdFixtures.Descriptor(
            code = DPC_SONY_SHUTTER,
            dataType = DTC_UINT32,
            getSet = 0x00,
            currentValue = 0x012C_000AL,   // 300/10 = 30"
            formFlag = DPFF_RANGE,
            range = Triple(0L, 0xFFFF_FFFFL, 1L),
        )
        val props = parseSonyAllProps(SonyDpdFixtures.blob(descriptor))
        val pick = requireNotNull(buildPickList(ExposureControl.SHUTTER, props, modeVer = 2))

        assertEquals(SHUTTER_BULB_RAW, pick.raws.last())
        assertEquals("BULB", decodeShutter(pick.raws.last()))
        // Exactly one step past 30", so the app walks there like the dial does.
        val prev = shutterReading(pick.raws[pick.raws.lastIndex - 1]) as ShutterReading.Seconds
        assertEquals(30.0, prev.value, 1e-9)
    }
}

package com.sonycam.monitor.sony

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * White balance and colour temperature.
 *
 * Both tables are transcribed from Sony's *Camera Control PTP 3 Reference*
 * (pág. 174 for 0x5005) and checked against what an ILCE-7M3 actually publishes:
 * `0x5005 ... en=1 write=ABSOLUTE cur=0x2 form=2 enumSet=15/max=0x8030` and
 * `0xD20F ... en=0 write=READ_ONLY cur=0x0 form=1 range=(2500, 9900, 100)`.
 */
class WhiteBalanceTest {

    /** 0x5005 as the A7 III reports it: an enumeration the camera owns. */
    private fun wbProp(currentRaw: Long, announced: List<Long>, enabled: Int = 1) = SonyDeviceProp(
        code = DPC_WHITE_BALANCE,
        dataType = DTC_UINT16,
        getSet = 0x01,
        isEnabled = enabled,
        currentValue = currentRaw,
        formFlag = DPFF_ENUM,
        enumValues = announced,
    )

    /** 0xD210 / 0xD21C as the A7 III reports them: UINT8, range 164..220. */
    private fun shiftProp(code: Int, currentRaw: Long, step: Long) = SonyDeviceProp(
        code = code,
        dataType = DTC_UINT8,
        getSet = 0x01,
        isEnabled = 1,
        currentValue = currentRaw,
        formFlag = DPFF_RANGE,
        range = Triple(164L, 220L, step),
    )

    /** 0xD20F as the A7 III reports it while white balance is *not* C.Temp. */
    private fun tempProp(currentRaw: Long, enabled: Int) = SonyDeviceProp(
        code = DPC_SONY_COLOR_TEMP,
        dataType = DTC_UINT16,
        getSet = 0x01,
        isEnabled = enabled,
        currentValue = currentRaw,
        formFlag = DPFF_RANGE,
        range = Triple(2500L, 9900L, 100L),
    )

    @Test
    fun everyDocumentedPresetHasItsOwnName() {
        assertEquals("AWB", decodeWhiteBalance(0x0002L))
        assertEquals("Luz día", decodeWhiteBalance(0x0004L))
        assertEquals("Incandescente", decodeWhiteBalance(0x0006L))
        assertEquals("Nublado", decodeWhiteBalance(0x8010L))
        assertEquals("Sombra", decodeWhiteBalance(0x8011L))
        assertEquals("T. color", decodeWhiteBalance(0x8012L))
        assertEquals("Submarino", decodeWhiteBalance(0x8030L))
        // The four fluorescent steps are distinct settings, not one label.
        val fluor = listOf(0x8001L, 0x8002L, 0x8003L, 0x8004L).map(::decodeWhiteBalance)
        assertEquals(fluor.size, fluor.toSet().size)
    }

    /** An unknown code says so instead of being silently renamed to a known one. */
    @Test
    fun anUndocumentedCodeIsShownAsItself() {
        assertEquals("0x8099", decodeWhiteBalance(0x8099L))
    }

    /**
     * Sony defines two colour-temperature codes that are not temperatures
     * (pág. 448): `0x0000` = "less than 2500K", `0xFFFF` = "greater than
     * 9900K", and *"these values are not included in the value of Range"*.
     * Through the ordinary path `0xFFFF` rendered as **65535K** — the same
     * invented reading as the shutter's `0xFFFFFFFF` showing `1"` before v0.9.
     */
    @Test
    fun theTwoOutOfRangeCodesAreNotTemperatures() {
        assertEquals("<2500", decodeColorTemp(0L))
        assertEquals(">9900", decodeColorTemp(0xFFFFL))
        assertEquals("5500K", decodeColorTemp(5500L))
        assertEquals("2500K", decodeColorTemp(2500L))
        assertEquals("9900K", decodeColorTemp(9900L))
    }

    // -- biaxial fine tuning --------------------------------------------------

    /**
     * The scale is derived, and this is the derivation.
     *
     * Sony documents that 0xD210/0xD21C exist and stops there. But the A7 III
     * advertises `range=(164, 220, 1)` and `range=(164, 220, 2)` — **57 and 29**
     * selectable values — and Sony's specification for the same body says the
     * colour filter runs *"G7 to M7 (57-step) and A7 to B7 (29-step)"*. Two
     * independent axes matching exactly is what fixes 4 raw units per step.
     */
    @Test
    fun theAdvertisedRangesMatchSonysPublishedStepCounts() {
        val gm = (220 - 164) / 1 + 1
        val ab = (220 - 164) / 2 + 1
        assertEquals("G7 to M7 is a 57-step filter", 57, gm)
        assertEquals("A7 to B7 is a 29-step filter", 29, ab)
        // 7 whole steps each side of centre, so a whole step is 4 raw units.
        assertEquals(WB_SHIFT_UNITS_PER_STEP, (220.0 - 164.0) / 2 / 7, 0.0)
        assertEquals(WB_SHIFT_NEUTRAL, (220L + 164L) / 2)
    }

    /**
     * Which letter sits at which end was **measured**, not assumed: each axis
     * was driven to 220 alone and the live view read. At 220 the picture goes
     * green on G-M and amber on A-B, so the first letter of Sony's property
     * name is the *high* end — the opposite of the obvious reading, and the
     * reason these labels are decided in one place instead of by a sign.
     */
    @Test
    fun theEndsOfEachAxisAreTheFullSevenSteps() {
        assertEquals("M7", decodeWbShift(164L, "M", "G"))
        assertEquals("G7", decodeWbShift(220L, "M", "G"))
        assertEquals("B7", decodeWbShift(164L, "B", "A"))
        assertEquals("A7", decodeWbShift(220L, "B", "A"))
    }

    /** The measured orientation, through the readings the tiles actually show. */
    @Test
    fun theTilesReadTheAxesTheWayTheCameraTintsThePicture() {
        val green = decodeReadings(mapOf(DPC_SONY_WB_SHIFT_GM to shiftProp(DPC_SONY_WB_SHIFT_GM, 220L, 1L)))
        assertEquals("220 turns the picture green", "G7", green.wbShiftGm)

        val amber = decodeReadings(mapOf(DPC_SONY_WB_SHIFT_AB to shiftProp(DPC_SONY_WB_SHIFT_AB, 220L, 2L)))
        assertEquals("220 turns the picture amber", "A7", amber.wbShiftAb)
    }

    @Test
    fun neutralIsZeroAndNotAnAxisLetter() {
        assertEquals("0", decodeWbShift(WB_SHIFT_NEUTRAL, "M", "G"))
        assertEquals("0", decodeWbShift(WB_SHIFT_NEUTRAL, "B", "A"))
    }

    /** Whole steps read as whole numbers; partial ones keep only what they need. */
    @Test
    fun stepSizesAreWrittenWithoutTrailingNoise() {
        assertEquals("G1", decodeWbShift(196L, "M", "G"))
        assertEquals("M1", decodeWbShift(188L, "M", "G"))
        assertEquals("A0.5", decodeWbShift(194L, "B", "A"))
        assertEquals("M0.25", decodeWbShift(191L, "M", "G"))
        assertEquals("G3.5", decodeWbShift(206L, "M", "G"))
    }

    /**
     * The A-B axis advertises a step of 2, so it can only land on halves; G-M
     * advertises 1 and reaches quarters. Both come off the camera's own range.
     */
    @Test
    fun eachAxisOffersExactlyWhatTheBodyAdvertises() {
        val gm = buildPickList(
            ExposureControl.WB_GM,
            mapOf(DPC_SONY_WB_SHIFT_GM to shiftProp(DPC_SONY_WB_SHIFT_GM, 192L, step = 1L)),
        )!!
        assertEquals(57, gm.raws.size)
        assertEquals("M7", gm.labels.first())
        assertEquals("G7", gm.labels.last())
        assertEquals("0", gm.labels[gm.index])

        val ab = buildPickList(
            ExposureControl.WB_AB,
            mapOf(DPC_SONY_WB_SHIFT_AB to shiftProp(DPC_SONY_WB_SHIFT_AB, 192L, step = 2L)),
        )!!
        assertEquals(29, ab.raws.size)
        assertEquals("B7", ab.labels.first())
        assertEquals("A7", ab.labels.last())
    }

    /** The wheel offers what the camera announced, and nothing else. */
    @Test
    fun theWheelCarriesOnlyTheAnnouncedPresets() {
        val announced = listOf(0x0002L, 0x0004L, 0x8010L, 0x8011L, 0x8012L)
        val pick = buildPickList(
            ExposureControl.WB,
            mapOf(DPC_WHITE_BALANCE to wbProp(0x0002L, announced)),
        )!!

        assertEquals(announced, pick.raws)
        assertEquals(listOf("AWB", "Luz día", "Nublado", "Sombra", "T. color"), pick.labels)
        assertEquals(0, pick.index)
    }

    /** Sony's whole documented set survives a round trip through the wheel. */
    @Test
    fun aBodyOfferingEveryPresetGetsEveryName() {
        val all = listOf(
            0x0001L, 0x0002L, 0x0003L, 0x0004L, 0x0005L, 0x0006L, 0x0007L,
            0x8001L, 0x8002L, 0x8003L, 0x8004L, 0x8010L, 0x8011L, 0x8012L,
            0x8020L, 0x8021L, 0x8022L, 0x8023L, 0x8030L,
        )
        val pick = buildPickList(
            ExposureControl.WB,
            mapOf(DPC_WHITE_BALANCE to wbProp(0x8012L, all)),
        )!!
        assertEquals(all.size, pick.labels.size)
        assertFalse("no preset falls through to a hex code", pick.labels.any { it.startsWith("0x") })
        assertEquals("the wheel starts on what the camera says it is", 13, pick.index)
    }

    /**
     * The kelvin dial is a real range, unlike the placeholder ranges the same
     * body publishes for aperture and shutter, so it becomes the wheel.
     */
    @Test
    fun theKelvinDialComesFromTheAdvertisedRange() {
        val pick = buildPickList(
            ExposureControl.TEMP,
            mapOf(DPC_SONY_COLOR_TEMP to tempProp(5500L, enabled = 1)),
        )!!

        assertEquals(2500L, pick.raws.first())
        assertEquals(9900L, pick.raws.last())
        assertEquals("100 K steps across 2500..9900", 75, pick.raws.size)
        assertEquals("5500K", pick.labels[pick.index])
    }

    /**
     * The camera enables 0xD20F only while white balance is on C.Temp, so the
     * greying needs no table of ours — IsEnabled already says it.
     */
    /**
     * A body that is not on C.Temp disables 0xD20F and parks it at 0 — which is
     * Sony's code for "less than 2500K", so decoding it anyway printed a
     * temperature the camera was not reporting.
     */
    @Test
    fun aParkedTemperatureIsNoReadingAtAll() {
        val parked = decodeReadings(mapOf(DPC_SONY_COLOR_TEMP to tempProp(0L, enabled = 0)))
        assertEquals(READING_UNAVAILABLE, parked.colorTemp)

        val live = decodeReadings(mapOf(DPC_SONY_COLOR_TEMP to tempProp(5500L, enabled = 1)))
        assertEquals("5500K", live.colorTemp)
    }

    @Test
    fun theKelvinDialIsLockedUntilTheBodyEnablesIt() {
        val locked = tempProp(0L, enabled = 0)
        assertEquals(SonyWriteMode.READ_ONLY, locked.writeMode(2))
        assertFalse(
            ExposureControl.TEMP.editable(
                mapOf(DPC_SONY_COLOR_TEMP to locked), modeVer = 2, mode = 0x0001,
            ),
        )
        assertEquals("WB = T. color", ExposureControl.TEMP.lockReason(0x0001))

        val live = tempProp(5500L, enabled = 1)
        assertTrue(
            ExposureControl.TEMP.editable(
                mapOf(DPC_SONY_COLOR_TEMP to live), modeVer = 2, mode = 0x0001,
            ),
        )
    }

    /** White balance is not an exposure-program setting, but Auto still owns it. */
    @Test
    fun whiteBalanceIsAdjustableInEveryModeButFullAuto() {
        val props = mapOf(DPC_WHITE_BALANCE to wbProp(0x0002L, listOf(0x0002L, 0x8010L)))
        for (mode in intArrayOf(0x0001, 0x0002, 0x0003, 0x0004)) {
            assertTrue(
                "mode 0x%04X".format(mode),
                ExposureControl.WB.editable(props, modeVer = 2, mode = mode),
            )
        }
        assertFalse(ExposureControl.WB.editable(props, modeVer = 2, mode = 0x8000))
    }
}

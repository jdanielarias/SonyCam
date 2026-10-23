package com.sonycam.monitor.sony

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The aperture wheel must never offer a stop the lens does not have.
 *
 * Aperture is the one exposure control whose range belongs to the lens, and a
 * classic body publishes none of it: the A7 III answers 0x5007 with
 * `FormFlag = RANGE (0, 65535, 1)` and no enumeration — measured, warm and
 * cold. The wheel therefore fell back to a canonical f/1.0–f/32 ladder and
 * offered f/1 on a lens that opens at f/4; the body silently clamped.
 */
class ApertureLimitsTest {

    /** 0x5007 exactly as the physical A7 III reports it. */
    private fun a7iiiAperture(currentRaw: Long) = SonyDeviceProp(
        code = DPC_FNUMBER,
        dataType = DTC_UINT16,
        getSet = 0x00,
        isEnabled = 1,
        currentValue = currentRaw,
        formFlag = DPFF_RANGE,
        enumValues = null,
        range = Triple(0L, 65535L, 1L),
    )

    private fun wheel(limits: ApertureLimits, currentRaw: Long = 1100L): List<Long> =
        buildPickList(
            ExposureControl.APERTURE,
            mapOf(DPC_FNUMBER to a7iiiAperture(currentRaw)),
            modeVer = 2,
            apertureLimits = limits,
        )!!.raws

    @Test
    fun withNothingProvedTheLadderStillReachesBothWays() {
        // Refusing to offer anything unproved would leave a wheel one stop wide.
        val raws = wheel(ApertureLimits())
        assertEquals(100L, raws.first())
        assertEquals(3200L, raws.last())
    }

    /**
     * The complaint, as a test: a lens that opens at f/4 must not be offered
     * f/1. The camera proves that stop by refusing to move past it.
     */
    /**
     * The complaint, as a test: a lens that opens at f/4 must not be offered
     * f/1. Everything below the proved stop goes — except the single
     * exploratory step, which is the point of the next test.
     */
    @Test
    fun stopsTheLensCannotReachDisappearOnceProved() {
        val raws = wheel(ApertureLimits().lockedAt(400L, wider = true))

        assertFalse("f/1 was never on this lens", raws.contains(100L))
        assertFalse(raws.contains(280L))
        assertFalse(raws.contains(320L))
        assertTrue("everything from the proved stop up stays", raws.contains(3200L))
    }

    /**
     * Exactly one stop past a proved end stays reachable, and no more.
     *
     * A proved end describes the glass that was mounted when it was proved, and
     * that goes stale invisibly: swap an f/1.8 lens over an f/4.5 one while both
     * sit at f/8 and nothing in the reported value ever leaves the old window;
     * zoom a variable-aperture lens and its widest end moves on its own; and a
     * single ignored notch — which is all that proves an end — can be a
     * transient. The exploratory stop is the way back out of all three.
     */
    @Test
    fun exactlyOneStopPastAProvedEndStaysReachable() {
        val raws = wheel(ApertureLimits().lockedAt(400L, wider = true))

        assertEquals("one stop wider than proved, and that is the floor", 350L, raws.first())
        assertEquals("and nothing beyond it", 400L, raws[1])
    }

    @Test
    fun theNarrowEndIsProvedIndependently() {
        val raws = wheel(ApertureLimits().lockedAt(2200L, wider = false))
        assertEquals(2500L, raws.last())
        assertEquals(2200L, raws[raws.size - 2])
        assertTrue("the wide end is untouched by a narrow-end proof", raws.contains(100L))
    }

    @Test
    fun bothEndsTogetherDescribeTheLensPlusItsTwoProbes() {
        val raws = wheel(ApertureLimits().lockedAt(400L, wider = true).lockedAt(2200L, wider = false))
        assertEquals(listOf(350L, 400L), raws.take(2))
        assertEquals(listOf(2200L, 2500L), raws.takeLast(2))
    }

    /**
     * A lens swap: the body reports an aperture the old ends called impossible.
     * A fixed lens cannot do that, so the old ends are stale and must go —
     * otherwise a faster lens stays locked out of its own best stops.
     */
    @Test
    fun anApertureOutsideTheProvedEndsMeansTheLensChanged() {
        val proved = ApertureLimits().lockedAt(400L, wider = true)
        assertTrue(proved.outgrownBy(180L))
        assertFalse(proved.outgrownBy(560L))
    }

    /** The three 0x5007 message codes are not apertures and prove nothing. */
    @Test
    fun sentinelsNeverCountAsALensStop() {
        val proved = ApertureLimits().lockedAt(400L, wider = true)
        for (sentinel in longArrayOf(0xFFFDL, 0xFFFEL, 0xFFFFL)) {
            assertFalse(
                "0x%04X is a message, not a stop".format(sentinel),
                proved.outgrownBy(sentinel),
            )
            assertTrue(isSentinelFNumber(sentinel))
        }
        assertFalse(isSentinelFNumber(3200L))
    }

    /**
     * A stall has two causes and they are not the same fact. In S or P the body
     * owns the aperture and refuses every notch; writing that down as "the lens
     * stops here" would trim the wheel for a reason that has nothing to do with
     * the glass. IsEnabled is the camera's own "is it greyed out", and it is
     * what tells the two apart.
     */
    @Test
    fun theBodyOwningTheApertureIsNotALensLimit() {
        val adjustable = a7iiiAperture(1100L)                 // en=1, as measured in M
        assertEquals(SonyWriteMode.NOTCH, adjustable.writeMode(2))

        val bodyOwned = adjustable.copy(isEnabled = 2)        // as measured when not settable
        assertEquals(SonyWriteMode.READ_ONLY, bodyOwned.writeMode(2))
    }

    /**
     * Right after a swap to a slower lens the body may report a stop the old
     * ends excluded. The wheel must still render something rather than vanish.
     */
    @Test
    fun theValueTheCameraReportsIsAlwaysOnTheWheel() {
        val raws = wheel(ApertureLimits().lockedAt(400L, wider = true), currentRaw = 280L)
        assertTrue("the camera says we are at f/2.8; the wheel cannot deny it", raws.contains(280L))
    }
}

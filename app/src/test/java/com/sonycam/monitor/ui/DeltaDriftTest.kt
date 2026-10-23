package com.sonycam.monitor.ui

import com.sonycam.monitor.sony.DTC_UINT32
import com.sonycam.monitor.sony.SonyDeviceProp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The audit behind the difference-polling optimisation.
 *
 * Sony's difference stream is stateful: the camera tracks what it has already
 * told this session. A reply we never merge leaves the display frozen on a stale
 * number and says nothing — the exact failure that let three releases ship with
 * a broken ISO wheel. Every resync therefore compares the delta-maintained cache
 * against a complete dump, and this is the comparison.
 */
class DeltaDriftTest {

    private fun prop(code: Int, value: Long) = SonyDeviceProp(
        code = code,
        dataType = DTC_UINT32,
        getSet = 0x01,
        isEnabled = 1,
        currentValue = value,
        formFlag = 0,
    )

    private fun snapshot(vararg pairs: Pair<Int, Long>) =
        pairs.associate { (c, v) -> c to prop(c, v) }

    @Test
    fun `a cache that matches the dump has not drifted`() {
        val s = snapshot(0xD21E to 400L, 0x5007 to 1100L, 0xD20D to 0x0096_000AL)
        assertEquals(emptyList<Int>(), divergentCodes(s, s))
    }

    /** One dial turned between two polls is ordinary, not a fault. */
    @Test
    fun `a single changed value is reported without alarm`() {
        val cached = snapshot(0xD21E to 400L, 0x5007 to 1100L)
        val fresh = snapshot(0xD21E to 800L, 0x5007 to 1100L)
        assertEquals(listOf(0xD21E), divergentCodes(cached, fresh))
    }

    /** The failure the audit exists for: the cache silently left behind. */
    @Test
    fun `a cache left behind shows up as broad divergence`() {
        val cached = snapshot(0xD21E to 400L, 0x5007 to 1100L, 0xD20D to 10L, 0x5010 to 0L)
        val fresh = snapshot(0xD21E to 800L, 0x5007 to 2200L, 0xD20D to 20L, 0x5010 to 1000L)
        assertEquals(listOf(0x5007, 0x5010, 0xD20D, 0xD21E), divergentCodes(cached, fresh))
    }

    /** A property the cache never held counts as divergent, not as absent. */
    @Test
    fun `a property missing from the cache is divergent`() {
        val cached = snapshot(0xD21E to 400L)
        val fresh = snapshot(0xD21E to 400L, 0x5007 to 1100L)
        assertEquals(listOf(0x5007), divergentCodes(cached, fresh))
    }

    /**
     * A property the dump no longer carries is not divergence. Sony's own
     * reference warns that a body advertises only the properties available in
     * its current shooting mode, so a set that shrinks is normal.
     */
    @Test
    fun `a property the dump dropped is not divergence`() {
        val cached = snapshot(0xD21E to 400L, 0x5007 to 1100L)
        val fresh = snapshot(0xD21E to 400L)
        assertEquals(emptyList<Int>(), divergentCodes(cached, fresh))
    }

    @Test
    fun `an empty dump reports nothing rather than everything`() {
        assertEquals(emptyList<Int>(), divergentCodes(snapshot(0xD21E to 400L), emptyMap()))
    }

    /** The result is ordered, so a diagnostic transcript is comparable run to run. */
    @Test
    fun `divergent codes come back sorted`() {
        val cached = snapshot(0xD21E to 1L, 0x5007 to 1L, 0x500A to 1L)
        val fresh = snapshot(0xD21E to 2L, 0x5007 to 2L, 0x500A to 2L)
        val out = divergentCodes(cached, fresh)
        assertEquals(out.sorted(), out)
        assertTrue(out.size == 3)
    }
}

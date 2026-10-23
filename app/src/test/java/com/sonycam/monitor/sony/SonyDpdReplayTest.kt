package com.sonycam.monitor.sony

import com.sonycam.monitor.ptp.PtpReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SonyDpdReplayTest {
    @Test
    fun `mode 2 replay parses descriptors and derives stepped capabilities`() {
        val props = parseSonyAllProps(SonyDpdFixtures.mode2Blob())

        assertEquals(4, props.size)
        assertEquals(0x0001L, props.getValue(DPC_EXPOSURE_PROGRAM).currentValue)
        assertEquals(
            listOf(0x00FF_FFFFL, 100L, 200L, 400L, 800L, 204_800L, 256_000L),
            props.getValue(DPC_SONY_ISO).enumValues,
        )
        assertEquals(Triple(0L, 0xFFFFL, 1L), props.getValue(DPC_FNUMBER).range)
        assertEquals(SonyWriteMode.NOTCH, props.getValue(DPC_SONY_ISO).writeMode(modeVer = 2))
        assertEquals(SonyWriteMode.NOTCH, props.getValue(DPC_FNUMBER).writeMode(modeVer = 2))
        assertEquals(SonyWriteMode.ABSOLUTE, props.getValue(DPC_SONY_ISO).writeMode(modeVer = 3))
    }

    @Test
    fun `mode 3 replay preserves Set and GetSet enums and remains aligned`() {
        val props = parseSonyAllProps(SonyDpdFixtures.mode3WithSecondaryEnumBlob())

        val iso = props.getValue(DPC_SONY_ISO)
        assertTrue(iso.hadSecondaryEnum)
        assertEquals(
            listOf(400L, 800L, 1_600L),
            iso.setEnumValues,
        )
        assertEquals(
            listOf(100L, 200L, 400L, 800L, 1_600L, 3_200L, 6_400L),
            iso.getSetEnumValues,
        )
        assertEquals(800L, iso.currentValue)

        // These entries occur after the optional list on the wire. Finding both
        // is the regression guard for a6700/2024-firmware desynchronisation.
        assertEquals(400L, props.getValue(DPC_FNUMBER).currentValue)
        assertEquals(0x0002_0003L, props.getValue(DPC_EXPOSURE_PROGRAM).currentValue)
        assertEquals(3, props.size)
    }

    @Test
    fun `a6700 ISO 102400 survives UINT32 wire parsing without sign or width loss`() {
        // ISO 102400 reaches the wire as 0x10019000: the EXTENDED kind byte sets
        // bit 28, so a parser that treats the value as signed or narrows it to
        // 16/24 bits corrupts it long before the wheel ever sees it.
        val extended102400 = SonyDpdFixtures.ext(102_400)
        val props = parseSonyAllProps(
            SonyDpdFixtures.a6700IsoBlob(
                current = extended102400,
                setValues = SonyDpdFixtures.a6700PhotoSetIsos,
                getSetValues = SonyDpdFixtures.a6700GetSetIsos,
            ),
        )

        val iso = props.getValue(DPC_SONY_ISO)
        assertEquals(DTC_UINT32, iso.dataType)
        assertEquals(extended102400, iso.currentValue)
        assertTrue(iso.hadSecondaryEnum)
        assertEquals(extended102400, iso.setEnumValues?.last())
        assertTrue(
            iso.setEnumValues.orEmpty().containsAll(
                listOf(32_000L, SonyDpdFixtures.ext(40_000), extended102400),
            ),
        )
        assertEquals(extended102400, iso.getSetEnumValues?.last())
        assertTrue(iso.getSetEnumValues.orEmpty().contains(extended102400))
        assertEquals("102400", decodeIso(requireNotNull(iso.currentValue)))
    }

    @Test
    fun `unknown descriptor is skipped and replay resumes at next valid header`() {
        val before = SonyDpdFixtures.Descriptor(
            code = DPC_SONY_ISO,
            dataType = DTC_UINT32,
            currentValue = 200,
        )
        val after = SonyDpdFixtures.Descriptor(
            code = DPC_FNUMBER,
            dataType = DTC_UINT16,
            currentValue = 560,
        )
        val warnings = mutableListOf<String>()
        val blob = SonyDpdFixtures.rawBlob(
            declaredCount = 3,
            SonyDpdFixtures.descriptorBytes(before),
            SonyDpdFixtures.unknownDescriptor(
                tail = SonyDpdFixtures.descriptorBytes(after),
            ),
        )

        val props = parseSonyAllProps(blob, warnings::add)

        assertEquals(setOf(DPC_SONY_ISO, DPC_FNUMBER), props.keys)
        assertEquals(560L, props.getValue(DPC_FNUMBER).currentValue)
        assertTrue(warnings.any { it.startsWith("parse stopped at offset") })
        assertTrue(warnings.any { it.startsWith("resync: skipped") })
        assertTrue(warnings.any { it.startsWith("prop count mismatch") })
    }

    @Test
    fun `truncated tail degrades to prior properties without throwing`() {
        val valid = SonyDpdFixtures.Descriptor(
            code = DPC_SONY_ISO,
            dataType = DTC_UINT32,
            currentValue = 1_600,
        )
        val warnings = mutableListOf<String>()
        val blob = SonyDpdFixtures.rawBlob(
            declaredCount = 2,
            SonyDpdFixtures.descriptorBytes(valid),
            SonyDpdFixtures.truncatedDescriptor(),
        )

        val props = parseSonyAllProps(blob, warnings::add)

        assertEquals(1, props.size)
        assertEquals(1_600L, props.getValue(DPC_SONY_ISO).currentValue)
        assertTrue(warnings.any { it.startsWith("parse stopped at offset") })
        assertTrue(warnings.any { it == "prop count mismatch: header says 2, parsed 1" })
    }

    @Test
    fun `single unknown descriptor restores reader position`() {
        val bytes = SonyDpdFixtures.unknownDescriptor()
        val reader = PtpReader(bytes)

        assertNull(parseSonyDpd(reader))
        assertEquals(0, reader.pos)
    }

    @Test
    fun `short and empty property blobs return empty diagnostics-friendly result`() {
        val warnings = mutableListOf<String>()

        val props = parseSonyAllProps(byteArrayOf(1, 2, 3), warnings::add)

        assertTrue(props.isEmpty())
        assertEquals(listOf("prop blob too short (3 B)"), warnings)
    }

    @Test
    fun `write mode is negotiated from control bytes rather than model name`() {
        fun prop(getSet: Int, enabled: Int = 1, code: Int = 0xD2C0) = SonyDeviceProp(
            code = code,
            dataType = DTC_UINT16,
            getSet = getSet,
            isEnabled = enabled,
            currentValue = 0,
            formFlag = DPFF_NONE,
        )

        assertEquals(SonyWriteMode.BUTTON, prop(0x81).writeMode(2))
        assertEquals(SonyWriteMode.NOTCH, prop(0x82).writeMode(2))
        assertEquals(SonyWriteMode.LOCK, prop(0x83).writeMode(3))
        assertEquals(SonyWriteMode.VARIABLE, prop(0x84).writeMode(3))
        assertEquals(SonyWriteMode.READ_ONLY, prop(0x01, enabled = 0).writeMode(3))

        val mode2FocusDisplayOnly = prop(0x01, enabled = 2, code = DPC_FOCUS_MODE)
        assertEquals(SonyWriteMode.ABSOLUTE, mode2FocusDisplayOnly.writeMode(2))
        assertEquals(SonyWriteMode.READ_ONLY, mode2FocusDisplayOnly.writeMode(3))
        assertFalse(mode2FocusDisplayOnly.isControl)
        assertTrue(prop(0x82).isControl)
    }
}

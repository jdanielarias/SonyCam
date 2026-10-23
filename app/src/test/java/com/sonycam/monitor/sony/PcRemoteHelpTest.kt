package com.sonycam.monitor.sony

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PC Remote lives in a different menu on each Sony generation, and the paths are
 * not variations on a theme: an a7C has no PC Remote entry under USB Connection
 * at all. Sending an owner to a menu their camera does not have is the most
 * common reason a supported body looks unsupported, so each grouping here is
 * transcribed from Sony's own help guide for a body in that group.
 */
class PcRemoteHelpTest {

    // -- grouping ----------------------------------------------------------

    /** ILCE-7M3 help guide: MENU → (Setup) → [USB Connection] → [PC Remote]. */
    @Test
    fun `2017 to 2019 bodies use the classic USB Connection entry`() {
        for (model in listOf(
            "ILCE-7M3", "ILCE-7RM3", "ILCE-7RM3A", "ILCE-9",
            "ILCE-6100", "ILCE-6400", "ILCE-6600",
        )) {
            assertEquals(model, PcRemoteMenu.CLASSIC_USB, pcRemoteMenuFor(model))
        }
    }

    /**
     * ILCE-7C help guide: its [USB Connection] menu offers only Auto,
     * Mass Storage and MTP. PC Remote exists solely under Network, so the
     * classic instructions would send this owner looking for nothing.
     */
    @Test
    fun `2019 to 2020 bodies keep PC Remote under Network only`() {
        for (model in listOf("ILCE-7RM4", "ILCE-7RM4A", "ILCE-9M2", "ILCE-7C")) {
            assertEquals(model, PcRemoteMenu.NETWORK_PC_REMOTE, pcRemoteMenuFor(model))
        }
    }

    /** ILCE-6700 / ILCE-7M4 / ILCE-7SM3 help guides: Setup → USB → USB Connection Mode. */
    @Test
    fun `2021 and later bodies use USB Connection Mode`() {
        for (model in listOf(
            "ILCE-7M4", "ILCE-7RM5", "ILCE-1", "ILCE-9M3", "ILCE-6700",
            "ILCE-7CM2", "ILCE-7CR", "ILCE-7SM3", "ILME-FX3", "ILME-FX30",
            "ZV-E1", "ZV-E10",
        )) {
            assertEquals(model, PcRemoteMenu.MODERN_USB_MODE, pcRemoteMenuFor(model))
        }
    }

    /** A body nobody has classified must never be handed a specific wrong path. */
    @Test
    fun `an unknown model is not guessed`() {
        assertEquals(PcRemoteMenu.UNKNOWN, pcRemoteMenuFor("ILCE-9999"))
        assertEquals(PcRemoteMenu.UNKNOWN, pcRemoteMenuFor(""))
        assertEquals(PcRemoteMenu.UNKNOWN, pcRemoteMenuFor("NIKON Z6"))
    }

    /** GetDeviceInfo strings arrive with whatever spacing and case the body used. */
    @Test
    fun `matching tolerates case and surrounding whitespace`() {
        assertEquals(PcRemoteMenu.CLASSIC_USB, pcRemoteMenuFor("  ilce-7m3 "))
        assertEquals(PcRemoteMenu.MODERN_USB_MODE, pcRemoteMenuFor("zv-e1"))
    }

    /** One body must never appear in two groups; that would make the text random. */
    @Test
    fun `the generation groups do not overlap`() {
        val seen = mutableMapOf<String, PcRemoteMenu>()
        for (model in listOf(
            "ILCE-7M3", "ILCE-7RM3", "ILCE-7RM3A", "ILCE-9", "ILCE-6100",
            "ILCE-6400", "ILCE-6600", "ILCE-6300", "ILCE-6500", "ILCE-7M2",
            "ILCE-7RM2", "ILCE-7SM2", "ILCE-7RM4", "ILCE-7RM4A", "ILCE-9M2",
            "ILCE-7C", "ILCE-1", "ILCE-1M2", "ILCE-9M3", "ILCE-7M4", "ILCE-7M5",
            "ILCE-7RM5", "ILCE-7RM6", "ILCE-7CM2", "ILCE-7CR", "ILCE-7SM3",
            "ILME-FX3", "ILME-FX3A", "ILCE-6700", "ILME-FX30", "ILME-FX2",
            "ILME-FX6V", "ILME-FX6T", "ILX-LR1", "ZV-E1", "ZV-E10", "ZV-E10M2",
            "ZV-1", "ZV-1A", "ZV-1F", "ZV-1M2",
        )) {
            val menu = pcRemoteMenuFor(model)
            assertTrue("$model fell through to UNKNOWN", menu != PcRemoteMenu.UNKNOWN)
            assertEquals("$model classified twice", null, seen.put(model, menu))
        }
    }

    // -- the text the user actually reads ----------------------------------

    /** The classic path must carry the Ctrl w/ Smartphone prerequisite. */
    @Test
    fun `the a7 III message names its menu and its prerequisite`() {
        val text = pcRemoteInstructions("ILCE-7M3", "4.0")
        assertTrue(text, text.contains("ILCE-7M3"))
        assertTrue(text, text.contains("firmware 4.0"))
        assertTrue(text, text.contains("Conexión USB"))
        assertTrue(text, text.contains("Ctrl con smartphone"))
        // Must NOT send an a7 III owner to the modern menu.
        assertFalse(text, text.contains("Modo conexión USB"))
    }

    /** The a7C has no such entry, so the text must not mention it. */
    @Test
    fun `the a7C message points at Network and says why`() {
        val text = pcRemoteInstructions("ILCE-7C")
        assertTrue(text, text.contains("Función PC Remoto"))
        assertTrue(text, text.contains("Método cnx. PC Remoto: USB"))
        assertTrue(text, text.contains("sólo ofrece Auto"))
    }

    /** Both spellings, because Sony renamed the option between these bodies. */
    @Test
    fun `the modern message names both option spellings and the old firmware route`() {
        val text = pcRemoteInstructions("ILCE-6700", "2.00")
        assertTrue(text, text.contains("Modo conexión USB"))
        assertTrue(text, text.contains("[PC Remote]"))
        assertTrue(text, text.contains("[Disparo remoto]"))
        assertTrue(text, text.contains("firmware antiguo"))
    }

    /** An unknown body gets every route rather than a confident wrong one. */
    @Test
    fun `an unknown model is offered all three routes`() {
        val text = pcRemoteInstructions("ILCE-FUTURE")
        assertTrue(text, text.contains("Modo conexión USB"))
        assertTrue(text, text.contains("Función PC Remoto"))
        assertTrue(text, text.contains("Conexión USB"))
        assertTrue(text, text.contains("No conozco este modelo"))
    }

    /** An empty model string still produces a usable sentence, not "null". */
    @Test
    fun `a missing model name degrades to a readable message`() {
        val text = pcRemoteInstructions("", "")
        assertTrue(text, text.startsWith("La cámara no está en modo PC Remote"))
        assertFalse(text, text.contains("firmware "))
    }

    /**
     * Every message ends with the two things that actually unblock people: the
     * cable has to be re-seated, and USB Power Supply is worth turning off.
     *
     * The second is worded as a suggestion on purpose. Sony documents that the
     * setting makes the camera draw from the connected device, but not that this
     * drops the link — that part comes from a third-party app's help pages, and
     * the app must not state it as a cause it cannot source.
     */
    @Test
    fun `every message ends with the reconnect and power supply advice`() {
        for (model in listOf("ILCE-7M3", "ILCE-7C", "ILCE-6700", "ILCE-UNKNOWN")) {
            val text = pcRemoteInstructions(model)
            assertTrue(model, text.contains("vuelve a conectar el cable"))
            assertTrue(model, text.contains("Alimentación USB"))
        }
    }

    /** The exception the handshake throws must carry that same text. */
    @Test
    fun `the exception message is the per-generation instructions`() {
        val e = SonyNotInPcRemoteMode("ILCE-7C", "2.00")
        assertEquals(pcRemoteInstructions("ILCE-7C", "2.00"), e.message)
        assertEquals("ILCE-7C", e.model)
        assertEquals("2.00", e.firmware)
    }
}

package com.sonycam.monitor.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The banner used to stay over the live view until the next PHOTO press. */
class ErrorBannerTest {

    @Test
    fun shortMessagesStayLongEnoughToRead() {
        assertEquals(4_000L, errorDisplayMillis("Espera a que termine la captura."))
    }

    @Test
    fun longerMessagesStayLonger() {
        val menuHelp = "En la cámara: MENÚ → Red → ImageConex./PC rem. → Ajuste toma remota → " +
            "Dest. guard. img fija → Destino+cámara. Luego pulsa PHOTO otra vez."
        assertTrue(errorDisplayMillis(menuHelp) > errorDisplayMillis("Espera a que termine la captura."))
    }

    @Test
    fun nothingCoversTheLiveViewForLong() {
        assertEquals(15_000L, errorDisplayMillis("x".repeat(10_000)))
    }
}

package com.sonycam.monitor.ui

import android.os.Build
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The gallery write, against the real MediaStore.
 *
 * This is the half of the capture path a JVM test cannot reach: `IS_PENDING` is
 * a MediaStore behaviour, not app logic, and asserting that the app sets the
 * column proves nothing about whether the provider honoured it. So the test
 * writes a row, reads it back through the provider, and stops between the two
 * steps to stand in for the process death the flag exists to survive.
 */
@RunWith(AndroidJUnit4::class)
class GalleryWriterTest {

    private val resolver =
        InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
    private val created = mutableListOf<android.net.Uri>()

    private fun name() = "SonyMonitorTest_%d.jpg".format(System.nanoTime())

    @Before
    fun requiresScopedStorage() {
        assumeTrue("IS_PENDING only exists from API 29", Build.VERSION.SDK_INT >= 29)
    }

    @After
    fun cleanUp() {
        for (uri in created) runCatching { resolver.delete(uri, null, null) }
        created.clear()
    }

    /** A freshly inserted row is hidden until the bytes are all there. */
    @Test
    fun insertedRowStartsPending() {
        val uri = GalleryWriter.insertPending(resolver, name(), "image/jpeg").also { created += it }
        assertTrue("a row must not be visible before it is written", GalleryWriter.isPending(resolver, uri))
    }

    /** Publishing clears the flag and the bytes are exactly what went in. */
    @Test
    fun publishingClearsPendingAndKeepsTheBytes() {
        val bytes = ByteArray(4096) { (it and 0xFF).toByte() }
        val uri = GalleryWriter.insertPending(resolver, name(), "image/jpeg").also { created += it }

        GalleryWriter.publish(resolver, uri, bytes)

        assertFalse("published rows must not stay pending", GalleryWriter.isPending(resolver, uri))
        val readBack = resolver.openInputStream(uri)!!.use { it.readBytes() }
        assertArrayEquals("the photo must survive the round trip", bytes, readBack)
    }

    /**
     * The guarantee itself. Stopping between insert and publish is what a
     * process death looks like from the provider's side: the row stays pending,
     * so nothing else ever sees the truncated file.
     */
    @Test
    fun anInterruptedWriteStaysHidden() {
        val uri = GalleryWriter.insertPending(resolver, name(), "image/jpeg").also { created += it }
        // Write only part of the photo, then stop — no publish.
        resolver.openOutputStream(uri)!!.use { it.write(ByteArray(512)) }

        assertTrue(
            "a half-written capture must never be published",
            GalleryWriter.isPending(resolver, uri),
        )
    }

    /** The row lands where the app says it does, not in the gallery root. */
    @Test
    fun theRowCarriesTheAppsOwnFolder() {
        val uri = GalleryWriter.insertPending(resolver, name(), "image/jpeg").also { created += it }
        val path = resolver.query(
            uri, arrayOf(MediaStore.Images.Media.RELATIVE_PATH), null, null, null,
        )!!.use { c -> c.moveToFirst(); c.getString(0) }
        assertTrue("unexpected folder: $path", path.contains("SonyMonitor"))
    }

    /** Deleting a pending row works, which is how a failed save cleans up. */
    @Test
    fun aFailedSaveCanRemoveItsOwnRow() {
        val uri = GalleryWriter.insertPending(resolver, name(), "image/jpeg")
        assertEquals(1, resolver.delete(uri, null, null))
    }
}

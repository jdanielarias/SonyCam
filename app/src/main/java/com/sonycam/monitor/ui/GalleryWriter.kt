package com.sonycam.monitor.ui

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore

/**
 * Publishes a capture into the gallery in two steps, so nothing else can read a
 * photo that is still arriving.
 *
 * The row is created `IS_PENDING`, which keeps it out of every other app's view
 * of the MediaStore, and only cleared once the last byte is written. Inserting
 * and writing in one go meant the Gallery could index a half-transferred frame,
 * and a process death mid-write left a truncated image published as though it
 * were the shot.
 *
 * Split into two calls because that is also the only way to test the guarantee:
 * a test can stop between them, which is exactly what a process death does.
 */
internal object GalleryWriter {

    /** Create the row, hidden from other apps until [publish]. API 29+. */
    fun insertPending(resolver: ContentResolver, name: String, mime: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + "/SonyMonitor",
            )
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        return resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("MediaStore insert failed")
    }

    /** Write the bytes, then make the row visible. */
    fun publish(resolver: ContentResolver, uri: Uri, bytes: ByteArray) {
        resolver.openOutputStream(uri)?.use { it.write(bytes) }
            ?: throw IllegalStateException("openOutputStream failed")
        // The row count matters: an update that changed nothing leaves the
        // photo hidden as pending while the caller reports a successful save.
        val published = resolver.update(
            uri,
            ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
            null,
            null,
        )
        if (published != 1) {
            throw IllegalStateException("MediaStore did not publish the row (updated=$published)")
        }
    }

    /** True while the row is still hidden from other apps. */
    fun isPending(resolver: ContentResolver, uri: Uri): Boolean =
        resolver.query(uri, arrayOf(MediaStore.Images.Media.IS_PENDING), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getInt(0) != 0 else false }
            ?: false
}

package com.sonycam.monitor.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Every non-JPEG capture used to be saved as `.arw`, so a HEIF shot from a
 * current body reached the gallery labelled as Sony RAW.
 */
class CapturedImageFormatTest {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    /** ISO-BMFF header: u32 box size, "ftyp", then the major brand. */
    private fun heif(brand: String): ByteArray =
        bytes(0x00, 0x00, 0x00, 0x18) + "ftyp".toByteArray() + brand.toByteArray() + ByteArray(8)

    @Test
    fun jpegStartsWithSoiAndAMarker() {
        assertEquals(CapturedImageFormat.JPEG, CapturedImageFormat.of(bytes(0xFF, 0xD8, 0xFF, 0xE1, 0, 0)))
    }

    @Test
    fun heifIsRecognisedByItsFtypBrand() {
        for (brand in listOf("heic", "heix", "mif1", "msf1")) {
            assertEquals(brand, CapturedImageFormat.HEIF, CapturedImageFormat.of(heif(brand)))
        }
    }

    @Test
    fun heifIsNotSavedAsRaw() {
        val format = CapturedImageFormat.of(heif("heic"))
        assertEquals("heic", format.extension)
        assertEquals("image/heif", format.mime)
    }

    @Test
    fun arwIsLittleEndianTiff() {
        val format = CapturedImageFormat.of(bytes(0x49, 0x49, 0x2A, 0x00, 0x08, 0, 0, 0))
        assertEquals(CapturedImageFormat.ARW, format)
        assertEquals("image/x-sony-arw", format.mime)
    }

    /** An ISO-BMFF file that is not HEIF (an MP4, say) is not guessed at. */
    @Test
    fun otherFtypBrandsStayUnknown() {
        assertEquals(CapturedImageFormat.UNKNOWN, CapturedImageFormat.of(heif("isom")))
    }

    @Test
    fun unrecognisedOrTruncatedBytesAreUnknownNotRaw() {
        assertEquals(CapturedImageFormat.UNKNOWN, CapturedImageFormat.of(ByteArray(0)))
        assertEquals(CapturedImageFormat.UNKNOWN, CapturedImageFormat.of(bytes(0xFF, 0xD8)))
        assertEquals(CapturedImageFormat.UNKNOWN, CapturedImageFormat.of(bytes(0x4D, 0x4D, 0x00, 0x2A)))
        assertEquals(CapturedImageFormat.UNKNOWN, CapturedImageFormat.of(ByteArray(32) { 0x55 }))
    }

    /** Not an image MIME, so MediaStore's Images table refuses it and the app-storage copy keeps it. */
    @Test
    fun unknownIsNotAnImageMime() {
        assertEquals(false, CapturedImageFormat.UNKNOWN.mime.startsWith("image/"))
    }
}

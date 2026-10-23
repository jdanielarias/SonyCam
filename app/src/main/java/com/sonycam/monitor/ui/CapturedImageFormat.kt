package com.sonycam.monitor.ui

/**
 * What a downloaded capture actually is, read from its own first bytes.
 *
 * The object at 0xFFFFC001 carries no reliable name, and "not a JPEG" is not
 * the same as "an ARW": current bodies (A7 IV, A6700, ZV-E1) can shoot HEIF,
 * which is an ISO-BMFF file whose `ftyp` box sits at offset 4. Saving one of
 * those as `.arw` / `image/x-sony-arw` hands the gallery a file it tries to
 * decode as the wrong format.
 *
 * Anything not recognised is kept rather than guessed at: [UNKNOWN] is not an
 * image MIME, so MediaStore's Images collection refuses it and the caller's
 * app-storage fallback keeps the bytes intact.
 */
internal enum class CapturedImageFormat(val extension: String, val mime: String) {
    JPEG("jpg", "image/jpeg"),
    HEIF("heic", "image/heif"),
    /** Sony RAW. ARW is TIFF-based, little-endian ("II*\0"). */
    ARW("arw", "image/x-sony-arw"),
    UNKNOWN("bin", "application/octet-stream");

    companion object {
        /** HEIF brands (ISO/IEC 23008-12) a Sony body can write. */
        private val HEIF_BRANDS = setOf("heic", "heix", "heim", "heis", "hevc", "hevx", "mif1", "msf1")

        fun of(bytes: ByteArray): CapturedImageFormat = when {
            bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() &&
                bytes[2] == 0xFF.toByte() -> JPEG
            bytes.size >= 12 && ascii(bytes, 4, 4) == "ftyp" &&
                ascii(bytes, 8, 4) in HEIF_BRANDS -> HEIF
            bytes.size >= 4 && bytes[0] == 'I'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
                bytes[2] == 0x2A.toByte() && bytes[3] == 0x00.toByte() -> ARW
            else -> UNKNOWN
        }

        private fun ascii(bytes: ByteArray, offset: Int, length: Int): String =
            String(bytes, offset, length, Charsets.US_ASCII)
    }
}

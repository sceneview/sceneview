package io.github.sceneview.demo.demos.internal

/**
 * The image work the exporters and the `.rrd` reader need, behind one small interface so they
 * stay pure Kotlin: the app implements it with `BitmapFactory` / `Bitmap`
 * (`BitmapRerunImageCodec`), the JVM tests with a fake. The iOS demo does the same with ImageIO
 * (`RerunImageCodec`, `RerunImageTranscoder`).
 */
interface RerunImageCodec {
    /**
     * A photo as a `.rrd`'s `EncodedImage` carries it: JPEG and PNG pass through untouched,
     * anything else (the capture's WebP) is re-encoded to JPEG, since Rerun's decoders do not all
     * read WebP. `null` when [encoded] does not decode.
     */
    fun photo(encoded: ByteArray): Photo?

    /**
     * [encoded] decoded, downscaled so its longer side is at most [maxDimension]: RGB, or RGBA when
     * one of its texels is not opaque. `null` if it does not decode.
     */
    fun rgbPixels(encoded: ByteArray, maxDimension: Int): Pixels?

    /** A PNG of [pixels] (RGB or RGBA, see [Pixels.channels]); `null` when it cannot be encoded. */
    fun encodePng(pixels: Pixels): ByteArray?

    /**
     * A plane photo as core glTF accepts it: JPEG and PNG kept, anything else re-encoded to JPEG,
     * or PNG when it has a translucent pixel. `null` when [encoded] does not decode.
     */
    fun transcode(encoded: ByteArray): Transcoded?

    /** An encoded photo, its media type, and its size in pixels. */
    class Photo(val data: ByteArray, val mediaType: String, val width: Int, val height: Int)

    /** Row-major texels, top row first, 8 bits per channel: [channels] is 3 (RGB) or 4 (RGBA). */
    class Pixels(val width: Int, val height: Int, val data: ByteArray, val channels: Int = RGB)

    /** An encoded image, and whether one of its pixels is not fully opaque (its material blends). */
    class Transcoded(val data: ByteArray, val mimeType: String, val isTranslucent: Boolean)

    companion object {
        const val RGB = 3
        const val RGBA = 4
        const val JPEG = "image/jpeg"
        const val PNG = "image/png"
        const val WEBP = "image/webp"

        private val JPEG_MAGIC = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
        private val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)

        fun isJpeg(bytes: ByteArray): Boolean = startsWith(bytes, JPEG_MAGIC)

        fun isPng(bytes: ByteArray): Boolean = startsWith(bytes, PNG_MAGIC)

        /** `RIFF....WEBP`. */
        fun isWebp(bytes: ByteArray): Boolean = bytes.size >= WEBP_HEADER &&
            String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP"

        private const val WEBP_HEADER = 12

        private fun startsWith(bytes: ByteArray, prefix: ByteArray): Boolean =
            bytes.size >= prefix.size && prefix.indices.all { bytes[it] == prefix[it] }
    }
}

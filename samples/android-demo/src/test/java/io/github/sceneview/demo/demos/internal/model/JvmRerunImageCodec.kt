package io.github.sceneview.demo.demos.internal.model

import io.github.sceneview.demo.demos.internal.RerunImageCodec

/**
 * [RerunImageCodec] on the plain JVM, through [JvmImages]: what reading a real `.rrd` needs off
 * the phone. JPEG and PNG pass through; anything ImageIO cannot read (WebP) does not decode.
 */
internal object JvmRerunImageCodec : RerunImageCodec {
    override fun photo(encoded: ByteArray): RerunImageCodec.Photo? {
        val image = JvmImages.decode(encoded) ?: return null
        val type = when {
            RerunImageCodec.isJpeg(encoded) -> RerunImageCodec.JPEG
            RerunImageCodec.isPng(encoded) -> RerunImageCodec.PNG
            else -> return RerunImageCodec.Photo(
                JvmImages.jpeg(image.argb, image.width, image.height), RerunImageCodec.JPEG, image.width, image.height,
            )
        }
        return RerunImageCodec.Photo(encoded, type, image.width, image.height)
    }

    override fun rgbPixels(encoded: ByteArray, maxDimension: Int): RerunImageCodec.Pixels? {
        val image = JvmImages.decode(encoded) ?: return null
        val scale = maxOf(1, (maxOf(image.width, image.height) + maxDimension - 1) / maxDimension)
        val width = image.width / scale
        val height = image.height / scale
        val data = ByteArray(width * height * RerunImageCodec.RGB)
        for (y in 0 until height) for (x in 0 until width) {
            val c = image.argb[(y * scale) * image.width + x * scale]
            val o = (y * width + x) * RerunImageCodec.RGB
            data[o] = (c shr 16).toByte()
            data[o + 1] = (c shr 8).toByte()
            data[o + 2] = c.toByte()
        }
        return RerunImageCodec.Pixels(width, height, data)
    }

    override fun encodePng(pixels: RerunImageCodec.Pixels): ByteArray? {
        val argb = IntArray(pixels.width * pixels.height) { i ->
            val o = i * pixels.channels
            (0xFF shl 24) or ((pixels.data[o].toInt() and 0xFF) shl 16) or
                ((pixels.data[o + 1].toInt() and 0xFF) shl 8) or (pixels.data[o + 2].toInt() and 0xFF)
        }
        return JvmImages.png(argb, pixels.width, pixels.height)
    }

    override fun transcode(encoded: ByteArray): RerunImageCodec.Transcoded? = when {
        RerunImageCodec.isJpeg(encoded) -> RerunImageCodec.Transcoded(encoded, RerunImageCodec.JPEG, false)
        RerunImageCodec.isPng(encoded) -> RerunImageCodec.Transcoded(encoded, RerunImageCodec.PNG, false)
        else -> JvmImages.decode(encoded)?.let {
            RerunImageCodec.Transcoded(JvmImages.jpeg(it.argb, it.width, it.height), RerunImageCodec.JPEG, false)
        }
    }
}

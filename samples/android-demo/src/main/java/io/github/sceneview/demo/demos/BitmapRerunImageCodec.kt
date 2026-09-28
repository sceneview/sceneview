package io.github.sceneview.demo.demos

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import io.github.sceneview.demo.demos.internal.RerunImageCodec
import java.io.ByteArrayOutputStream

/**
 * [RerunImageCodec] on `BitmapFactory` and `Bitmap`: what the `.rrd`, `.glb` and `.ply` writers
 * and the `.rrd` reader need to decode and encode images on the phone. Blocking: call it off the
 * main thread.
 */
internal object BitmapRerunImageCodec : RerunImageCodec {
    private const val JPEG_QUALITY = 90
    private const val ALPHA_SHIFT = 24
    private const val RED_SHIFT = 16
    private const val GREEN_SHIFT = 8
    private const val BYTE = 0xFF

    override fun photo(encoded: ByteArray): RerunImageCodec.Photo? {
        val (width, height) = bounds(encoded) ?: return null
        return when {
            RerunImageCodec.isJpeg(encoded) -> RerunImageCodec.Photo(encoded, RerunImageCodec.JPEG, width, height)
            RerunImageCodec.isPng(encoded) -> RerunImageCodec.Photo(encoded, RerunImageCodec.PNG, width, height)
            else -> {
                val bitmap = decode(encoded) ?: return null
                val jpeg = compress(bitmap, Bitmap.CompressFormat.JPEG)
                val photo = jpeg?.let { RerunImageCodec.Photo(it, RerunImageCodec.JPEG, bitmap.width, bitmap.height) }
                bitmap.recycle()
                photo
            }
        }
    }

    override fun rgbPixels(encoded: ByteArray, maxDimension: Int): RerunImageCodec.Pixels? {
        val (width, height) = bounds(encoded) ?: return null
        val limit = maxDimension.coerceAtLeast(1)
        var sample = 1
        while (maxOf(width, height) / (sample * 2) >= limit) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(
            encoded,
            0,
            encoded.size,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            },
        ) ?: return null
        val longer = maxOf(decoded.width, decoded.height)
        val bitmap = if (longer > limit) {
            val scale = limit.toFloat() / longer
            val w = (decoded.width * scale).toInt().coerceAtLeast(1)
            val h = (decoded.height * scale).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(decoded, w, h, true).also { if (it !== decoded) decoded.recycle() }
        } else {
            decoded
        }
        val argb = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(argb, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val rgb = ByteArray(argb.size * RerunImageCodec.RGB)
        for (i in argb.indices) {
            val c = argb[i]
            rgb[3 * i] = (c shr RED_SHIFT and BYTE).toByte()
            rgb[3 * i + 1] = (c shr GREEN_SHIFT and BYTE).toByte()
            rgb[3 * i + 2] = (c and BYTE).toByte()
        }
        val pixels = RerunImageCodec.Pixels(bitmap.width, bitmap.height, rgb)
        bitmap.recycle()
        return pixels
    }

    override fun encodePng(pixels: RerunImageCodec.Pixels): ByteArray? {
        val channels = pixels.channels
        val count = pixels.width * pixels.height
        if (pixels.width <= 0 || pixels.height <= 0 || pixels.data.size < count * channels) return null
        val argb = IntArray(count) { i ->
            val base = i * channels
            val alpha = if (channels == RerunImageCodec.RGBA) pixels.data[base + 3].toInt() and BYTE else BYTE
            (alpha shl ALPHA_SHIFT) or ((pixels.data[base].toInt() and BYTE) shl RED_SHIFT) or
                ((pixels.data[base + 1].toInt() and BYTE) shl GREEN_SHIFT) or (pixels.data[base + 2].toInt() and BYTE)
        }
        val bitmap = Bitmap.createBitmap(argb, pixels.width, pixels.height, Bitmap.Config.ARGB_8888)
        val png = compress(bitmap, Bitmap.CompressFormat.PNG)
        bitmap.recycle()
        return png
    }

    override fun transcode(encoded: ByteArray): RerunImageCodec.Transcoded? {
        val bitmap = decode(encoded) ?: return null
        val translucent = bitmap.hasAlpha() && hasTranslucentPixel(bitmap)
        val result = when {
            RerunImageCodec.isJpeg(encoded) -> RerunImageCodec.Transcoded(encoded, RerunImageCodec.JPEG, false)
            RerunImageCodec.isPng(encoded) -> RerunImageCodec.Transcoded(encoded, RerunImageCodec.PNG, translucent)
            translucent -> compress(bitmap, Bitmap.CompressFormat.PNG)
                ?.let { RerunImageCodec.Transcoded(it, RerunImageCodec.PNG, true) }
            else -> compress(bitmap, Bitmap.CompressFormat.JPEG)
                ?.let { RerunImageCodec.Transcoded(it, RerunImageCodec.JPEG, false) }
        }
        bitmap.recycle()
        return result
    }

    private fun bounds(encoded: ByteArray): Pair<Int, Int>? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(encoded, 0, encoded.size, options)
        return if (options.outWidth > 0 && options.outHeight > 0) options.outWidth to options.outHeight else null
    }

    private fun decode(encoded: ByteArray): Bitmap? = BitmapFactory.decodeByteArray(
        encoded,
        0,
        encoded.size,
        BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 },
    )

    private fun compress(bitmap: Bitmap, format: Bitmap.CompressFormat): ByteArray? {
        val out = ByteArrayOutputStream()
        return if (bitmap.compress(format, JPEG_QUALITY, out)) out.toByteArray() else null
    }

    private fun hasTranslucentPixel(bitmap: Bitmap): Boolean {
        val row = IntArray(bitmap.width)
        for (y in 0 until bitmap.height) {
            bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
            if (row.any { (it ushr ALPHA_SHIFT) != BYTE }) return true
        }
        return false
    }
}

package io.github.sceneview.demo.demos.internal

import java.io.File
import java.nio.ByteBuffer

/**
 * A deterministic [RerunImageCodec] for JVM tests, where `BitmapFactory` is a stub. JPEG and PNG
 * pass through; anything else becomes a "JPEG" (the JPEG magic, then the input). Its PNGs are
 * `PNG magic + FAKE + width, height, channels + texels`, which [rgbPixels] reads back exactly, so
 * a plane photo survives any number of `.rrd` round trips bit for bit.
 */
internal object FakeRerunImageCodec : RerunImageCodec {
    const val PHOTO_WIDTH = 480
    const val PHOTO_HEIGHT = 640
    private const val SIDE = 4
    private val JPEG_MAGIC = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
    private val FAKE_PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47) + "FAKE".toByteArray()
    private const val FAKE_HEADER = 20

    override fun photo(encoded: ByteArray): RerunImageCodec.Photo? = when {
        encoded.size < 4 -> null
        RerunImageCodec.isJpeg(encoded) ->
            RerunImageCodec.Photo(encoded, RerunImageCodec.JPEG, PHOTO_WIDTH, PHOTO_HEIGHT)
        RerunImageCodec.isPng(encoded) ->
            RerunImageCodec.Photo(encoded, RerunImageCodec.PNG, PHOTO_WIDTH, PHOTO_HEIGHT)
        else -> RerunImageCodec.Photo(JPEG_MAGIC + encoded, RerunImageCodec.JPEG, PHOTO_WIDTH, PHOTO_HEIGHT)
    }

    override fun rgbPixels(encoded: ByteArray, maxDimension: Int): RerunImageCodec.Pixels? {
        if (encoded.size < 4) return null
        fake(encoded)?.let { return it }
        return RerunImageCodec.Pixels(SIDE, SIDE, ByteArray(SIDE * SIDE * 3) { encoded[it % encoded.size] })
    }

    override fun encodePng(pixels: RerunImageCodec.Pixels): ByteArray? {
        val header = ByteBuffer.allocate(FAKE_HEADER - FAKE_PNG.size)
            .putInt(pixels.width).putInt(pixels.height).putInt(pixels.channels).array()
        return FAKE_PNG + header + pixels.data.copyOf(pixels.width * pixels.height * pixels.channels)
    }

    override fun transcode(encoded: ByteArray): RerunImageCodec.Transcoded? = when {
        encoded.size < 4 -> null
        RerunImageCodec.isJpeg(encoded) -> RerunImageCodec.Transcoded(encoded, RerunImageCodec.JPEG, false)
        RerunImageCodec.isPng(encoded) -> RerunImageCodec.Transcoded(encoded, RerunImageCodec.PNG, false)
        else -> RerunImageCodec.Transcoded(JPEG_MAGIC + encoded, RerunImageCodec.JPEG, false)
    }

    private fun fake(encoded: ByteArray): RerunImageCodec.Pixels? {
        if (encoded.size < FAKE_HEADER || !FAKE_PNG.indices.all { encoded[it] == FAKE_PNG[it] }) return null
        val buffer = ByteBuffer.wrap(encoded, FAKE_PNG.size, FAKE_HEADER - FAKE_PNG.size)
        val width = buffer.int
        val height = buffer.int
        val channels = buffer.int
        val data = encoded.copyOfRange(FAKE_HEADER, encoded.size)
        return RerunImageCodec.Pixels(width, height, data, channels)
    }

    /** The bundled showcase's three files, from the module or the repository root. */
    fun showcase(): RerunCapturePack {
        val dir = listOf(
            File("src/main/assets/${RerunReplayAssets.DIR}"),
            File("samples/android-demo/src/main/assets/${RerunReplayAssets.DIR}"),
        ).first { it.isDirectory }
        return RerunCapturePack(
            manifest = File(dir, "showcase-manifest.json").readBytes(),
            log = File(dir, "showcase-session.jsonl").readBytes(),
            media = File(dir, "showcase-media.bin").readBytes(),
        )
    }
}

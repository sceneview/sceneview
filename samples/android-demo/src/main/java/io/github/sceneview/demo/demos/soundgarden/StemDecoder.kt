package io.github.sceneview.demo.demos.soundgarden

import android.content.res.AssetManager
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import java.nio.ByteOrder

/**
 * Decodes one bundled stem (`assets/audio/garden_*.ogg`) to mono float PCM at the mix rate.
 *
 * The asset is read into memory and handed to [MediaExtractor] through a [MediaDataSource]
 * rather than `openFd`: `openFd` only works on an asset stored uncompressed, and whether an
 * `.ogg` is stored so is a packaging detail this demo should not depend on. The stems are
 * 30–95 KB each.
 */
internal object StemDecoder {

    private const val TIMEOUT_US = 10_000L

    fun decodeMono(assets: AssetManager, path: String): FloatArray {
        val bytes = assets.open(path).use { it.readBytes() }
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(ByteArraySource(bytes))
            val track = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: error("No audio track in $path")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val codec = MediaCodec.createDecoderByType(mime)
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                val pcm = drain(codec, extractor, format)
                return SoundGardenStems.fitToLoop(pcm)
            } finally {
                runCatching { codec.stop() }
                codec.release()
            }
        } finally {
            extractor.release()
        }
    }

    private fun drain(codec: MediaCodec, extractor: MediaExtractor, inputFormat: MediaFormat): FloatArray {
        var channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var floatOutput = false
        val out = FloatBuilder(SoundGardenStems.LOOP_FRAMES + 4_096)
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        while (true) {
            if (!inputDone) {
                val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                if (inIndex >= 0) {
                    val buffer = codec.getInputBuffer(inIndex)!!
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
            when {
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val f = codec.outputFormat
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    floatOutput = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                }
                outIndex >= 0 -> {
                    val buffer = codec.getOutputBuffer(outIndex)!!
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    buffer.order(ByteOrder.nativeOrder())
                    if (floatOutput) {
                        val samples = buffer.asFloatBuffer()
                        while (samples.remaining() >= channels) {
                            var sum = 0f
                            repeat(channels) { sum += samples.get() }
                            out.add(sum / channels)
                        }
                    } else {
                        val samples = buffer.asShortBuffer()
                        while (samples.remaining() >= channels) {
                            var sum = 0f
                            repeat(channels) { sum += samples.get() / 32_768f }
                            out.add(sum / channels)
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        }
        val mono = out.toArray()
        return if (sampleRate == SoundGardenStems.SAMPLE_RATE) mono else resample(mono, sampleRate)
    }

    /** Linear resampling — only reached if a decoder ever hands back another rate. */
    private fun resample(input: FloatArray, fromRate: Int): FloatArray {
        val ratio = fromRate.toDouble() / SoundGardenStems.SAMPLE_RATE
        val length = (input.size / ratio).toInt()
        return FloatArray(length) { i ->
            val position = i * ratio
            val base = position.toInt().coerceAtMost(input.size - 1)
            val next = (base + 1).coerceAtMost(input.size - 1)
            val fraction = (position - base).toFloat()
            input[base] + (input[next] - input[base]) * fraction
        }
    }

    private class ByteArraySource(private val bytes: ByteArray) : MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= bytes.size) return -1
            val count = minOf(size.toLong(), bytes.size - position).toInt()
            System.arraycopy(bytes, position.toInt(), buffer, offset, count)
            return count
        }

        override fun getSize(): Long = bytes.size.toLong()

        override fun close() = Unit
    }

    private class FloatBuilder(capacity: Int) {
        private var data = FloatArray(capacity)
        private var size = 0

        fun add(value: Float) {
            if (size == data.size) data = data.copyOf(data.size * 2)
            data[size++] = value
        }

        fun toArray(): FloatArray = data.copyOf(size)
    }
}

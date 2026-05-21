package io.github.sceneview.demo.feedback

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/** Smallest demux buffer when [MediaFormat.KEY_MAX_INPUT_SIZE] is absent. */
private const val DEMUX_BUFFER_MIN = 256 * 1024

/** Hard ceiling on the demux buffer — a single AAC sample never approaches this. */
private const val DEMUX_BUFFER_MAX = 4 * 1024 * 1024

/**
 * Copy the audio track out of [source] (an mp4 screen recording) into a
 * standalone [dest] file — a plain track copy, no re-encode. The feedback
 * worker sends this audio-only file to Whisper for transcription.
 *
 * Returns [dest] on success, or null if [source] has no audio track or the
 * copy fails (in which case the screen recording is still usable on its own).
 *
 * The sample buffer is sized from [MediaFormat.KEY_MAX_INPUT_SIZE] when the
 * extractor reports it, and grows-and-retries otherwise — a fixed buffer would
 * throw `BufferOverflowException` on a larger-than-expected AAC sample and drop
 * the audio entirely (no transcription).
 */
fun demuxAudioTrack(source: File, dest: File): File? {
    val extractor = MediaExtractor()
    var muxer: MediaMuxer? = null
    return try {
        extractor.setDataSource(source.absolutePath)

        var audioTrack = -1
        var audioFormat: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            if (format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                audioTrack = i
                audioFormat = format
                break
            }
        }
        if (audioTrack < 0 || audioFormat == null) return null

        extractor.selectTrack(audioTrack)
        muxer = MediaMuxer(dest.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val outTrack = muxer.addTrack(audioFormat)
        muxer.start()

        // Seed the buffer from the format's declared max input size when present;
        // otherwise start at a sane minimum and grow on demand below.
        var bufferSize = if (audioFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
            audioFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
                .coerceIn(DEMUX_BUFFER_MIN, DEMUX_BUFFER_MAX)
        } else {
            DEMUX_BUFFER_MIN
        }
        var buffer = ByteBuffer.allocate(bufferSize)
        val info = MediaCodec.BufferInfo()
        while (true) {
            var size = extractor.readSampleData(buffer, 0)
            // readSampleData returns -1 at EOS. It can also return -1 (or throw
            // IllegalArgumentException, handled by the outer catch) when the
            // sample does not fit — grow-and-retry guards the latter.
            if (size < 0) {
                // Distinguish genuine EOS from a too-small buffer: a sample
                // still pending has a non-negative sampleTime.
                if (extractor.sampleTime < 0 || bufferSize >= DEMUX_BUFFER_MAX) break
                bufferSize = (bufferSize * 2).coerceAtMost(DEMUX_BUFFER_MAX)
                buffer = ByteBuffer.allocate(bufferSize)
                size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
            }
            info.offset = 0
            info.size = size
            info.presentationTimeUs = extractor.sampleTime
            info.flags = extractor.sampleFlags
            muxer.writeSampleData(outTrack, buffer, info)
            extractor.advance()
        }
        muxer.stop()
        dest
    } catch (e: Exception) {
        dest.delete()
        null
    } finally {
        runCatching { extractor.release() }
        runCatching { muxer?.release() }
    }
}

package io.github.sceneview.demo.feedback

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/**
 * Copy the audio track out of [source] (an mp4 screen recording) into a
 * standalone [dest] file — a plain track copy, no re-encode. The feedback
 * worker sends this audio-only file to Whisper for transcription.
 *
 * Returns [dest] on success, or null if [source] has no audio track or the
 * copy fails (in which case the screen recording is still usable on its own).
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

        val buffer = ByteBuffer.allocate(256 * 1024)
        val info = MediaCodec.BufferInfo()
        while (true) {
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
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

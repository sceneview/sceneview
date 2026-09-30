package io.github.sceneview.demo.demos.soundgarden

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.Process
import android.util.Log

/**
 * Plays a [SpatialMixCore] on the device: one stereo float [AudioTrack] fed by one audio
 * thread, in 256-frame (5.3 ms) blocks.
 *
 * Why not the platform's own spatial audio: `android.media.Spatializer` binauralises
 * multichannel *beds*, not positioned mono sources, and Google ships it on Pixel 6 and later
 * only; Resonance Audio was archived in 2023. A 4-voice binaural mix is a few hundred
 * multiply-adds per frame — the audio thread spends well under 1 % of a Pixel 4a core on it.
 *
 * Lifecycle: [start] once, [pause] / [resume] with the screen, [close] exactly once.
 */
internal class SoundGardenAudio(private val core: SpatialMixCore) : AutoCloseable {

    private val track: AudioTrack = buildTrack()
    private val lock = Object()

    @Volatile private var running = false
    @Volatile private var paused = false

    private var thread: Thread? = null

    /** Frames the device has actually played — what the ear hears *now*, for the visuals. */
    val playedFrames: Long
        get() = runCatching { track.playbackHeadPosition.toLong() and 0xFFFF_FFFFL }.getOrDefault(0L)

    fun start() {
        if (running) return
        running = true
        track.play()
        thread = Thread(::loop, "SoundGardenAudio").apply { start() }
    }

    fun pause() {
        paused = true
        runCatching { track.pause() }
    }

    fun resume() {
        if (!running) return
        runCatching { track.play() }
        synchronized(lock) {
            paused = false
            lock.notifyAll()
        }
    }

    override fun close() {
        running = false
        synchronized(lock) {
            paused = false
            lock.notifyAll()
        }
        // pause + flush drop what is queued (no tail after the screen is gone); stop interrupts
        // a write blocked on a full buffer so the thread can see `running == false` and leave.
        runCatching { track.pause() }
        runCatching { track.flush() }
        runCatching { track.stop() }
        thread?.join(JOIN_TIMEOUT_MS)
        thread = null
        track.release()
    }

    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val block = FloatArray(BLOCK_FRAMES * 2)
        while (running) {
            if (paused) {
                synchronized(lock) {
                    while (paused && running) lock.wait()
                }
            } else {
                core.render(block, BLOCK_FRAMES)
                val written = track.write(block, 0, block.size, AudioTrack.WRITE_BLOCKING)
                if (written < 0) {
                    Log.w(TAG, "AudioTrack.write failed ($written) — stopping the garden's audio")
                    break
                }
            }
        }
    }

    private fun buildTrack(): AudioTrack {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .apply {
                // The mix is already binaural. On a phone whose system spatializer also treats
                // stereo, running it a second time would smear the cues this class computes.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S_V2) {
                    setSpatializationBehavior(AudioAttributes.SPATIALIZATION_BEHAVIOR_NEVER)
                }
            }
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(SoundGardenStems.SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .build()
        val minBytes = AudioTrack.getMinBufferSize(
            SoundGardenStems.SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_FLOAT,
        )
        // ≥ 43 ms of audio queued: enough to ride out a GC pause, short enough that turning
        // the head is heard turning the mix without a lag.
        val bytes = maxOf(minBytes * 2, BUFFER_FRAMES * BYTES_PER_FRAME)
        return AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(bytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    private companion object {
        const val TAG = "SoundGardenAudio"
        const val BLOCK_FRAMES = 256
        const val BUFFER_FRAMES = 2_048
        const val BYTES_PER_FRAME = 2 * 4
        const val JOIN_TIMEOUT_MS = 500L
    }
}

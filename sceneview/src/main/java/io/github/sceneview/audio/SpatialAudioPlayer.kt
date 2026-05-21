package io.github.sceneview.audio

import android.content.res.AssetFileDescriptor
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.util.Log
import androidx.annotation.MainThread
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import io.github.sceneview.math.Position

/**
 * Per-node playback backend for [SpatialAudioNode].
 *
 * Each [SpatialAudioPlayer] constructs and **owns its own** [MediaPlayer] from the shared,
 * resource-free [AudioSource]. Two `SpatialAudioNode`s backed by the same source therefore
 * have fully independent players — no volume / start / pause / seek / loop cross-talk. The
 * player recomputes per-frame gain + L/R pan from the latest listener pose pushed by
 * [SpatialAudioEngine]. The class is package-private — callers go through the
 * [SpatialAudioNode] composable.
 *
 * Threading: the `MediaPlayer` is constructed on the **main thread** (its internal event
 * handler binds to the constructing thread's `Looper`; building it on a Looper-less pool
 * thread silently breaks every callback-driven feature). Only opening the asset file
 * descriptor — which [AudioSource.openFd] does — touches the file system, and it is cheap
 * enough to run inline. Every public method is `@MainThread`. The class is constructed
 * inside a composable `remember { … }` block, registered with [SpatialAudioEngine] in a
 * `DisposableEffect`, and fully released on dispose.
 *
 * Phase 1 backend: `MediaPlayer` + manual L/R pan via `setVolume(left, right)`. Falloff
 * gain is multiplied into both channels. Phase 2 will introduce a `Spatializer` path
 * (API 33+) that delegates panning to the OS audio HAL and removes the per-frame software
 * pan; the [SpatialAudioPlayer] API will not change between phases.
 */
internal class SpatialAudioPlayer
@MainThread constructor(
    val source: AudioSource,
    initialFalloff: AudioFalloff,
    initialLoop: Boolean,
    initialVolume: Float,
    initialPitch: Float,
) : AudioListenerTarget {
    /** Distance-attenuation curve. Read/written directly — re-applies on next listener push. */
    var falloff: AudioFalloff = initialFalloff
    private var loop: Boolean = initialLoop
    private var baseVolume: Float = initialVolume
    private var pitch: Float = initialPitch
    var sourcePosition: Position = Position(x = 0f)
        set(value) {
            field = value
            // Self-refresh: triggers an immediate gain update for *this* player so the
            // very next sample block reflects the new source pose without waiting for a
            // frame tick. Only this player is affected — other players are untouched.
            refreshFromLastListener()
        }

    /** Reactive flag mirrored back to user code through [AudioController.isPlaying]. */
    val isPlayingState: MutableState<Boolean> = mutableStateOf(false)

    private var destroyed = false

    /**
     * This player's private [MediaPlayer]. Built on the constructing (main) thread so its
     * event handler has a live `Looper`. `null` only if construction failed.
     */
    private val mediaPlayer: MediaPlayer? = runCatching {
        val afd: AssetFileDescriptor = source.openFd()
        MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            afd.use { setDataSource(it.fileDescriptor, it.startOffset, it.length) }
            // Synchronous prepare — the asset is a short, in-`assets` clip so the blocking
            // initialise is sub-millisecond. Running it on the main thread is intentional:
            // construction MUST stay main-thread so the event handler binds to a Looper.
            prepare()
        }
    }.onFailure {
        Log.w(TAG, "Failed to create MediaPlayer for ${source.assetPath}: ${it.message}")
    }.getOrNull()

    init {
        applyLoop()
        applyPitch()
        // Default gain — silenced until the engine pushes a real listener pose. Prevents a
        // half-second of full-volume audio bleeding through during the first compose pass.
        mediaPlayer?.runCatching { setVolume(0f, 0f) }
    }

    @MainThread
    fun play() {
        if (destroyed) return
        val mp = mediaPlayer ?: return
        runCatching {
            if (!mp.isPlaying) mp.start()
            isPlayingState.value = mp.isPlaying
        }.onFailure {
            Log.w(TAG, "play() failed: ${it.message}")
        }
    }

    @MainThread
    fun pause() {
        if (destroyed) return
        runCatching {
            mediaPlayer?.takeIf { it.isPlaying }?.pause()
            isPlayingState.value = mediaPlayer?.isPlaying ?: false
        }
    }

    @MainThread
    fun stop() {
        if (destroyed) return
        // MediaPlayer.stop() requires a re-prepare() before next start(); we instead pause
        // + seekTo(0) so the caller can hit play() again immediately. This matches the
        // expectation from AudioController.stop() docs ("rewinds to 0").
        runCatching {
            val mp = mediaPlayer ?: return@runCatching
            if (mp.isPlaying) mp.pause()
            mp.seekTo(0)
            isPlayingState.value = false
        }
    }

    @MainThread
    fun seekTo(positionMs: Long) {
        if (destroyed) return
        runCatching {
            val clamped = positionMs.coerceIn(0L, source.durationMs.coerceAtLeast(0L))
            mediaPlayer?.seekTo(clamped.toInt())
        }
    }

    @MainThread
    fun setBaseVolume(value: Float) {
        if (baseVolume == value) return
        baseVolume = value
        // Push by re-evaluating *this* player against the last known listener.
        refreshFromLastListener()
    }

    @MainThread
    fun setPitch(value: Float) {
        if (pitch == value) return
        pitch = value
        applyPitch()
    }

    @MainThread
    fun setLoop(value: Boolean) {
        if (loop == value) return
        loop = value
        applyLoop()
    }

    private fun applyLoop() {
        runCatching { mediaPlayer?.isLooping = loop }
    }

    private fun applyPitch() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return  // setPlaybackParams 23+
        runCatching {
            val mp = mediaPlayer ?: return
            val params = mp.playbackParams
            // Clamp to MediaPlayer's accepted range — outside [0.5, 2.0] some devices crash.
            val clampedPitch = pitch.coerceIn(0.5f, 2f)
            params.speed = clampedPitch
            params.pitch = clampedPitch
            mp.playbackParams = params
        }
    }

    /** Re-applies gain/pan for this player using the engine's last listener pose. */
    private fun refreshFromLastListener() {
        if (destroyed) return
        SpatialAudioEngine.refreshPlayer(this)
    }

    @MainThread
    override fun updateFromListener(
        listenerPosition: Position,
        @Suppress("UNUSED_PARAMETER") listenerForward: Position,
        listenerRight: Position
    ) {
        if (destroyed) return
        val distance = distance3(sourcePosition, listenerPosition)
        val falloffGain = AudioFalloff.gainFor(falloff, distance)
        val totalGain = (baseVolume * falloffGain).coerceIn(0f, 1f)
        val (panL, panR) = panLR(sourcePosition, listenerPosition, listenerRight)
        runCatching {
            mediaPlayer?.setVolume(totalGain * panL, totalGain * panR)
        }
    }

    /**
     * Fully stops and releases this player's private [MediaPlayer].
     *
     * Unlike a bare `pause()`, this guarantees the clip is silenced and the native player
     * freed — a source swapped while still remembered upstream can no longer keep playing
     * at its last volume. Idempotent.
     */
    @MainThread
    fun destroy() {
        if (destroyed) return
        destroyed = true
        SpatialAudioEngine.unregister(this)
        runCatching {
            mediaPlayer?.apply {
                if (isPlaying) stop()
                release()
            }
        }
    }

    internal companion object {
        const val TAG = "SpatialAudio"
    }
}

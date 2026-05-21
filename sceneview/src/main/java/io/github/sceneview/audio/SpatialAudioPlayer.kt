package io.github.sceneview.audio

import android.media.MediaPlayer
import android.os.Build
import android.util.Log
import androidx.annotation.MainThread
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import io.github.sceneview.math.Position

/**
 * Per-source playback backend for [SpatialAudioNode].
 *
 * Owns one [MediaPlayer] borrowed from an [AudioSource] and recomputes per-frame gain +
 * L/R pan from the latest listener pose pushed by [SpatialAudioEngine]. The class is
 * package-private — callers go through the [SpatialAudioNode] composable.
 *
 * Threading: every public method is `@MainThread`. The class is constructed inside a
 * composable `remember { … }` block, registered with [SpatialAudioEngine] in a
 * `DisposableEffect`, and released on dispose.
 *
 * Phase 1 backend: `MediaPlayer` + manual L/R pan via `setVolume(left, right)`. Falloff
 * gain is multiplied into both channels. Phase 2 will introduce a `Spatializer` path
 * (API 33+) that delegates panning to the OS audio HAL and removes the per-frame software
 * pan; the [SpatialAudioPlayer] API will not change between phases.
 */
internal class SpatialAudioPlayer(
    val source: AudioSource,
    initialFalloff: AudioFalloff,
    initialLoop: Boolean,
    initialVolume: Float,
    initialPitch: Float,
) {
    /** Distance-attenuation curve. Read/written directly — re-applies on next listener push. */
    var falloff: AudioFalloff = initialFalloff
    private var loop: Boolean = initialLoop
    private var baseVolume: Float = initialVolume
    private var pitch: Float = initialPitch
    var sourcePosition: Position = Position(x = 0f)
        set(value) {
            field = value
            // Self-refresh: triggers an immediate gain update so the very next sample
            // block reflects the new source pose without waiting for a frame tick.
            SpatialAudioEngine.refreshAll()
        }

    /** Reactive flag mirrored back to user code through [AudioController.isPlaying]. */
    val isPlayingState: MutableState<Boolean> = mutableStateOf(false)

    private var destroyed = false

    init {
        applyLoop()
        applyPitch()
        // Default gain — silenced until the engine pushes a real listener pose. Prevents a
        // half-second of full-volume audio bleeding through during the first compose pass.
        source.mediaPlayer?.runCatching { setVolume(0f, 0f) }
    }

    @MainThread
    fun play() {
        if (destroyed) return
        val mp = source.mediaPlayer ?: return
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
            source.mediaPlayer?.takeIf { it.isPlaying }?.pause()
            isPlayingState.value = source.mediaPlayer?.isPlaying ?: false
        }
    }

    @MainThread
    fun stop() {
        if (destroyed) return
        // MediaPlayer.stop() requires a re-prepare() before next start(); we instead pause
        // + seekTo(0) so the caller can hit play() again immediately. This matches the
        // expectation from AudioController.stop() docs ("rewinds to 0").
        runCatching {
            val mp = source.mediaPlayer ?: return@runCatching
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
            source.mediaPlayer?.seekTo(clamped.toInt())
        }
    }

    @MainThread
    fun setBaseVolume(value: Float) {
        baseVolume = value
        // Push by triggering a re-eval against the last known listener.
        SpatialAudioEngine.refreshAll()
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
        runCatching { source.mediaPlayer?.isLooping = loop }
    }

    private fun applyPitch() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return  // setPlaybackParams 23+
        runCatching {
            val mp = source.mediaPlayer ?: return
            val params = mp.playbackParams
            // Clamp to MediaPlayer's accepted range — outside [0.5, 2.0] some devices crash.
            params.speed = pitch.coerceIn(0.5f, 2f)
            params.pitch = pitch.coerceIn(0.5f, 2f)
            mp.playbackParams = params
        }
    }

    @MainThread
    fun updateFromListener(
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
            source.mediaPlayer?.setVolume(totalGain * panL, totalGain * panR)
        }
    }

    @MainThread
    fun destroy() {
        if (destroyed) return
        destroyed = true
        SpatialAudioEngine.unregister(this)
        runCatching { source.mediaPlayer?.takeIf { it.isPlaying }?.pause() }
        // The MediaPlayer itself is owned by AudioSource — don't release here.
    }

    private companion object {
        const val TAG = "SpatialAudio"
    }
}

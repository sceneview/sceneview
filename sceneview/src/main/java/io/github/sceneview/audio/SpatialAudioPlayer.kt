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
 * enough to run inline. Preparation itself is `prepareAsync()`, not the blocking `prepare()`
 * (#3427): even a short in-`assets` clip's container parse + decoder setup is enough to drop
 * a frame when it runs synchronously on the exact composition pass that also calls `play()`
 * — that stall is what the beep's "légers lag" bug report was. [PreparePlayGate] defers
 * `play()` until the async `onPrepared` callback fires. Every public method is `@MainThread`.
 * The class is constructed inside a composable `remember { … }` block, registered with
 * [SpatialAudioEngine] in a `DisposableEffect`, and fully released on dispose.
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
     * Deferred-play state machine (#3427) — see [PreparePlayGate]. Every call that reaches
     * the `MediaPlayer` before `onPrepared` fires goes through this gate instead of the
     * native player directly.
     */
    private val prepareGate = PreparePlayGate()

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
            setOnPreparedListener { onPrepared() }
            setOnErrorListener { _, what, extra ->
                Log.w(TAG, "MediaPlayer error for ${source.assetPath}: what=$what extra=$extra")
                true
            }
            // Async prepare (#3427) — the container parse + decoder setup this triggers is
            // NOT the "sub-millisecond" no-op a short in-`assets` clip suggests: on a real
            // device it lands squarely inside the same composition pass that also calls
            // `play()` (autoPlay), i.e. main-thread work landing on the exact frame the
            // sound is meant to start — a dropped frame heard as a hitch on the beep itself.
            // `prepareAsync()` still requires construction on a Looper thread (main, here),
            // but returns immediately and reports readiness through `onPrepared` instead of
            // blocking it. `play()` before that point only records [pendingPlay]; the actual
            // `start()` happens from [onPrepared].
            prepareAsync()
        }
    }.onFailure {
        Log.w(TAG, "Failed to create MediaPlayer for ${source.assetPath}: ${it.message}")
    }.getOrNull()

    /** Runs on the main thread once the async `prepareAsync()` above completes. */
    private fun onPrepared() {
        if (destroyed) return
        applyLoop()
        applyPitch()
        // Default gain — silenced until the engine pushes a real listener pose. Prevents a
        // half-second of full-volume audio bleeding through during the first compose pass.
        runCatching { mediaPlayer?.setVolume(0f, 0f) }
        if (prepareGate.markPrepared()) play()
    }

    @MainThread
    fun play() {
        if (destroyed) return
        val mp = mediaPlayer ?: return
        if (!prepareGate.requestPlay()) {
            // Preparing is still in flight — start() as soon as onPrepared fires instead.
            return
        }
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
        prepareGate.cancelPendingPlay()
        if (!prepareGate.isPrepared) return
        runCatching {
            mediaPlayer?.takeIf { it.isPlaying }?.pause()
            isPlayingState.value = mediaPlayer?.isPlaying ?: false
        }
    }

    @MainThread
    fun stop() {
        if (destroyed) return
        prepareGate.cancelPendingPlay()
        if (!prepareGate.isPrepared) return
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
        if (destroyed || !prepareGate.isPrepared) return
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

/**
 * Deferred-play state machine for [SpatialAudioPlayer] (#3427).
 *
 * `MediaPlayer.prepareAsync()` returns immediately and reports readiness later via
 * `onPrepared`; a `play()` requested before that (as `autoPlay` always does — it fires from
 * the same `DisposableEffect` that constructs the player) must wait rather than call
 * `start()` on a player that is not ready yet. This class holds only that one decision —
 * "start now, or remember to start once prepared" — with no `MediaPlayer` reference, so it
 * is plain-JVM testable independently of the JNI-bound class around it (see the class KDoc
 * on `SpatialAudioNodeTest` for why `SpatialAudioPlayer` itself is not).
 */
internal class PreparePlayGate {
    var isPrepared: Boolean = false
        private set
    private var pendingPlay = false

    /**
     * Call when `play()` is requested. Returns `true` if the caller should start the native
     * player immediately; if `false`, the request has been recorded and will be honoured by
     * the next [markPrepared] call instead.
     */
    fun requestPlay(): Boolean {
        if (isPrepared) return true
        pendingPlay = true
        return false
    }

    /** Call when `pause()` or `stop()` is requested — clears any deferred play request. */
    fun cancelPendingPlay() {
        pendingPlay = false
    }

    /**
     * Call from `onPrepared`. Returns `true` if a [requestPlay] call arrived before this one
     * and is still pending — the caller should start the native player now.
     */
    fun markPrepared(): Boolean {
        isPrepared = true
        val shouldPlay = pendingPlay
        pendingPlay = false
        return shouldPlay
    }
}

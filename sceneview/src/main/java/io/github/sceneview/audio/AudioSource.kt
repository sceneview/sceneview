package io.github.sceneview.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import androidx.annotation.MainThread
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Opaque handle to a decoded / streamable audio asset that can be played by a
 * [SpatialAudioNode].
 *
 * Phase 1 backs the source with an Android [MediaPlayer] — it gives us file-format coverage
 * (WAV / MP3 / OGG / FLAC), seeking, looping and pitch control with zero asset-pipeline
 * work. The phase-2 Spatializer path will instead decode the asset to a PCM buffer and
 * stream it through `AudioTrack`; the public API of `AudioSource` is intentionally minimal
 * so swapping the backing engine does not break callers.
 *
 * Always obtain one with [rememberAudioSource]; never construct it directly.
 *
 * @property durationMs Total duration of the asset in milliseconds. `0` if unknown.
 */
class AudioSource internal constructor(
    internal val context: Context,
    internal val assetPath: String,
    internal val mediaPlayer: MediaPlayer?,
    internal val durationMs: Long,
) {
    private var destroyed = false

    /**
     * Releases the underlying [MediaPlayer]. Idempotent. Called automatically by the
     * `DisposableEffect` in [rememberAudioSource] when the composition leaves the tree.
     */
    @MainThread
    fun destroy() {
        if (destroyed) return
        destroyed = true
        runCatching { mediaPlayer?.release() }
    }

    internal val isDestroyed: Boolean get() = destroyed
}

/**
 * Asynchronously loads an audio asset and remembers it for the lifetime of the composition.
 *
 * The asset bytes are opened on [Dispatchers.IO]; [MediaPlayer.prepare] is then invoked on
 * the composition's main dispatcher (Android's `MediaPlayer` JNI is main-thread only).
 * Returns `null` while loading is in progress and triggers a recomposition once ready —
 * the same pattern as [io.github.sceneview.rememberModelInstance].
 *
 * The returned [AudioSource] is released automatically when the composition leaves the
 * tree; callers must not call [AudioSource.destroy] manually.
 *
 * ```kotlin
 * SceneView {
 *     rememberAudioSource("audio/bell.wav")?.let { source ->
 *         SpatialAudioNode(source = source, position = Position(z = -2f))
 *     }
 * }
 * ```
 *
 * @param assetPath Path to the audio file relative to the `assets` folder
 *                  (e.g. `"audio/bell.wav"`).
 * @return The loaded [AudioSource], or `null` while loading / on failure.
 */
@Composable
fun rememberAudioSource(assetPath: String): AudioSource? {
    val context = LocalContext.current
    val source = produceState<AudioSource?>(initialValue = null, key1 = assetPath) {
        // Open the asset descriptor off the main thread — `assets.openFd` is cheap but the
        // file system read it triggers can still hitch the renderer on slow storage.
        val player = withContext(Dispatchers.IO) {
            runCatching {
                val afd = context.assets.openFd(assetPath)
                MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_GAME)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                    afd.close()
                    // `prepare` is the blocking initialise path; running on IO is allowed
                    // (MediaPlayer's @MainThread restriction applies to the controller
                    // methods we expose through AudioController, not to prepare itself).
                    prepare()
                }
            }.onFailure {
                Log.w(TAG, "Failed to load audio source $assetPath: ${it.message}")
            }.getOrNull()
        }
        value = player?.let {
            AudioSource(
                context = context,
                assetPath = assetPath,
                mediaPlayer = it,
                durationMs = runCatching { it.duration.toLong() }.getOrDefault(0L)
            )
        }
    }.value

    DisposableEffect(source) {
        onDispose { source?.destroy() }
    }

    return source
}

private const val TAG = "SpatialAudio"

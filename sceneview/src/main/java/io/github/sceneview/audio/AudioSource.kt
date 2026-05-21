package io.github.sceneview.audio

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.util.Log
import androidx.annotation.MainThread
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Opaque, *shareable* handle to an audio asset that can be played by one or more
 * [SpatialAudioNode]s.
 *
 * An [AudioSource] holds only the cheap, immutable description of the asset (its `assets`
 * path plus a snapshot of its duration). It deliberately does **not** own an
 * [android.media.MediaPlayer] — a `MediaPlayer` is a single playback bus and cannot be
 * shared between two nodes without volume / start / pause / seek cross-talk. Each
 * [SpatialAudioPlayer] therefore constructs its **own** `MediaPlayer` from this source via
 * [openFd], so two `SpatialAudioNode`s backed by the same source play independently.
 *
 * Because the source carries no native resources it is itself free to share, re-use across
 * recompositions, and pass to several nodes at once.
 *
 * Phase 1 backs playback with Android [android.media.MediaPlayer] — it gives us file-format
 * coverage (WAV / MP3 / OGG / FLAC), seeking, looping and pitch control with zero
 * asset-pipeline work. The phase-2 Spatializer path will instead decode the asset to a PCM
 * buffer and stream it through `AudioTrack`; the public API of `AudioSource` is
 * intentionally minimal so swapping the backing engine does not break callers.
 *
 * Always obtain one with [rememberAudioSource]; never construct it directly.
 *
 * @property durationMs Total duration of the asset in milliseconds. `0` if unknown.
 */
class AudioSource internal constructor(
    internal val context: Context,
    internal val assetPath: String,
    internal val durationMs: Long,
) {
    /**
     * Opens a fresh [AssetFileDescriptor] for the backing asset. Each [SpatialAudioPlayer]
     * calls this to feed its own private `MediaPlayer.setDataSource`. The caller owns the
     * returned descriptor and must close it once `setDataSource` has consumed it.
     *
     * Cheap, but performs a file-system lookup — call it off the main thread.
     */
    internal fun openFd(): AssetFileDescriptor = context.assets.openFd(assetPath)
}

/**
 * Asynchronously prepares an audio asset and remembers it for the lifetime of the
 * composition.
 *
 * The asset descriptor is opened on [Dispatchers.IO] once to read the asset duration;
 * the resulting [AudioSource] is a lightweight, shareable handle that owns no native
 * playback resources. Each [SpatialAudioNode] given this source builds its own private
 * [android.media.MediaPlayer] on the main thread, so multiple nodes never fight over a
 * shared player.
 *
 * Returns `null` while the duration probe is in progress and triggers a recomposition
 * once ready — the same pattern as [io.github.sceneview.rememberModelInstance].
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
 * @return The prepared [AudioSource], or `null` while probing / on failure.
 */
@Composable
fun rememberAudioSource(assetPath: String): AudioSource? {
    val context = LocalContext.current
    return produceState<AudioSource?>(initialValue = null, key1 = assetPath) {
        // Probe the asset duration off the main thread — opening the descriptor and
        // building a throw-away MediaPlayer to read `duration` triggers a file-system
        // read that can hitch the renderer on slow storage.
        value = withContext(Dispatchers.IO) {
            runCatching {
                val durationMs = probeDurationMs(context, assetPath)
                AudioSource(
                    context = context.applicationContext,
                    assetPath = assetPath,
                    durationMs = durationMs,
                )
            }.onFailure {
                Log.w(TAG, "Failed to load audio source $assetPath: ${it.message}")
            }.getOrNull()
        }
    }.value
}

/**
 * Reads the asset duration with a short-lived `MediaMetadataRetriever`. Returns `0` when
 * the duration cannot be determined. Safe to call on any thread.
 */
private fun probeDurationMs(context: Context, assetPath: String): Long {
    val retriever = android.media.MediaMetadataRetriever()
    return try {
        context.assets.openFd(assetPath).use { afd ->
            retriever.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
        }
        retriever
            .extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull() ?: 0L
    } catch (t: Throwable) {
        Log.w(TAG, "Could not read duration for $assetPath: ${t.message}")
        0L
    } finally {
        runCatching { retriever.release() }
    }
}

internal const val TAG = "SpatialAudio"

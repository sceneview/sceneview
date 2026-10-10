package io.github.sceneview

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import androidx.annotation.MainThread
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * Where the video player of [rememberMediaPlayer] stands.
 *
 * A video is prepared off the main thread, so a player goes through [Preparing] before it is
 * [Ready], and a missing file, an unsupported codec or a broken stream ends in [Failed] with the
 * reason — the three cases an app has to tell apart to show a placeholder, the video or an error.
 */
@ExperimentalSceneViewApi
sealed interface MediaPlayerState {

    /** The video is being prepared: there is no player to hand to a `VideoNode` yet. */
    data object Preparing : MediaPlayerState

    /**
     * The video is prepared.
     *
     * @property player The prepared player, for `VideoNode(player = …)`. It is released when the
     *                  [rememberMediaPlayer] call that produced it leaves the composition: do
     *                  not release it yourself and do not keep it past that point.
     */
    class Ready(val player: MediaPlayer) : MediaPlayerState

    /**
     * The video could not be prepared, or broke while playing. Nothing plays from here on.
     *
     * @property cause What went wrong: the exception thrown while opening the source (a
     *                 `FileNotFoundException` for a missing asset), or a [MediaPlayerException]
     *                 carrying the codes the player reported.
     */
    class Failed(val cause: Exception) : MediaPlayerState
}

/**
 * An error a [MediaPlayer] reported through its `OnErrorListener`.
 *
 * @property what  The error type, e.g. [MediaPlayer.MEDIA_ERROR_UNKNOWN] or
 *                 [MediaPlayer.MEDIA_ERROR_SERVER_DIED].
 * @property extra The error detail, e.g. [MediaPlayer.MEDIA_ERROR_UNSUPPORTED],
 *                 [MediaPlayer.MEDIA_ERROR_IO] or [MediaPlayer.MEDIA_ERROR_TIMED_OUT].
 */
@ExperimentalSceneViewApi
class MediaPlayerException(val what: Int, val extra: Int) :
    Exception("MediaPlayer error (what=$what, extra=$extra)")

/**
 * Creates, prepares and remembers a [MediaPlayer] for the video at [fileLocation].
 *
 * The player is prepared asynchronously — composition never waits for the container to be parsed
 * or the decoder to be set up — and the call returns where it stands: [MediaPlayerState.Preparing]
 * first, then [MediaPlayerState.Ready] with the player, or [MediaPlayerState.Failed] with the
 * cause. A failure is also logged once. The player is released when [fileLocation] changes and
 * when the call leaves the composition.
 *
 * ```kotlin
 * SceneView {
 *     when (val video = rememberMediaPlayer("videos/promo.mp4")) {
 *         is MediaPlayerState.Ready -> VideoNode(player = video.player, position = Position(z = -2f))
 *         is MediaPlayerState.Failed -> Unit // video.cause says why: show your own fallback
 *         MediaPlayerState.Preparing -> Unit // not ready yet: show your own placeholder
 *     }
 * }
 * ```
 *
 * @param fileLocation Path to the video relative to the `assets` folder (`"videos/promo.mp4"`), or
 *                     a location with a scheme: `https://…`, `file://…`, `content://…`,
 *                     `android.resource://…`.
 * @param isLooping    Whether the video loops. Applied when it changes.
 * @param autoPlay     Whether the video plays. Applied when the video becomes ready and each time
 *                     the value changes — `true` starts playback, `false` pauses it — so a
 *                     play/pause button can drive it from Compose state.
 * @return Where the player stands. Read it during composition: it recomposes the caller when the
 *         video becomes ready or fails.
 */
@ExperimentalSceneViewApi
@Composable
fun rememberMediaPlayer(
    fileLocation: String,
    isLooping: Boolean = true,
    autoPlay: Boolean = true
): MediaPlayerState {
    val context = LocalContext.current
    val session = remember(context, fileLocation) {
        MediaPlayerSession(fileLocation, isLooping, autoPlay)
    }
    // The player is a native resource: it is created when the composition is committed and
    // released with it, never during a composition pass that may be discarded.
    DisposableEffect(session) {
        session.open(context)
        onDispose { session.release() }
    }
    SideEffect {
        session.isLooping = isLooping
        session.autoPlay = autoPlay
    }
    return session.state
}

/**
 * Reports a [MediaPlayerState.Failed] to [onError], once per failure, for the composables that
 * cannot return the state to their caller.
 */
@OptIn(ExperimentalSceneViewApi::class)
@Composable
internal fun MediaPlayerFailureEffect(state: MediaPlayerState, onError: ((Exception) -> Unit)?) {
    val currentOnError by rememberUpdatedState(onError)
    if (state is MediaPlayerState.Failed) {
        LaunchedEffect(state) { currentOnError?.invoke(state.cause) }
    }
}

/**
 * One [MediaPlayer] and its way from [MediaPlayerState.Preparing] to [MediaPlayerState.Ready] or
 * [MediaPlayerState.Failed]. Main thread only: the player delivers its callbacks there.
 */
@OptIn(ExperimentalSceneViewApi::class)
internal class MediaPlayerSession(
    private val fileLocation: String,
    isLooping: Boolean,
    autoPlay: Boolean
) {
    var state: MediaPlayerState by mutableStateOf(MediaPlayerState.Preparing)
        private set

    /** The player from [open] to [release], whatever [state] it is in. */
    var player: MediaPlayer? = null
        private set

    private var released = false

    var isLooping: Boolean = isLooping
        set(value) {
            if (field == value) return
            field = value
            whenReady { it.isLooping = value }
        }

    var autoPlay: Boolean = autoPlay
        set(value) {
            if (field == value) return
            field = value
            whenReady { if (value) it.start() else if (it.isPlaying) it.pause() }
        }

    /**
     * Creates the player, hands it its source and starts preparing it. Nothing here waits for the
     * media: `prepareAsync()` returns at once and answers through the listeners.
     *
     * @param openAsset Opens an `assets` path. The descriptor is closed as soon as the player has
     *                  taken its own copy of it.
     */
    @MainThread
    fun open(
        context: Context,
        openAsset: (String) -> AssetFileDescriptor = context.assets::openFd
    ) {
        try {
            val player = MediaPlayer().also { this.player = it }
            player.setOnPreparedListener(::onPrepared)
            player.setOnErrorListener { _, what, extra ->
                fail(MediaPlayerException(what, extra))
                // Handled: the player must not report a completion on top of the error.
                true
            }
            val uri = Uri.parse(fileLocation)
            if (uri.scheme == null) {
                openAsset(fileLocation).use {
                    player.setDataSource(it.fileDescriptor, it.startOffset, it.length)
                }
            } else {
                player.setDataSource(context, uri)
            }
            player.prepareAsync()
        } catch (e: Exception) {
            fail(e)
        }
    }

    @MainThread
    fun release() {
        released = true
        player?.let {
            it.setOnPreparedListener(null)
            it.setOnErrorListener(null)
            runCatching { it.release() }
        }
        player = null
    }

    private fun onPrepared(player: MediaPlayer) {
        if (released || state != MediaPlayerState.Preparing) return
        try {
            player.isLooping = isLooping
            if (autoPlay) player.start()
            state = MediaPlayerState.Ready(player)
        } catch (e: IllegalStateException) {
            fail(e)
        }
    }

    /** A setting changed after the video became ready: a player that refuses it has failed. */
    private inline fun whenReady(block: (MediaPlayer) -> Unit) {
        val ready = state as? MediaPlayerState.Ready ?: return
        try {
            block(ready.player)
        } catch (e: IllegalStateException) {
            fail(e)
        }
    }

    private fun fail(cause: Exception) {
        if (released || state is MediaPlayerState.Failed) return
        // Without the query: a signed URL carries its token there.
        Log.w(TAG, "Could not play ${fileLocation.substringBefore('?')}", cause)
        state = MediaPlayerState.Failed(cause)
    }

}

private const val TAG = "MediaPlayerState"

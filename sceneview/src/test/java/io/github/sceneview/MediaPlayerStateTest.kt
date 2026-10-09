package io.github.sceneview

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.media.MediaPlayer
import android.net.Uri
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.LocalContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.ShadowMediaPlayer.MediaInfo
import org.robolectric.shadows.ShadowMediaPlayer.State
import org.robolectric.shadows.util.DataSource

/**
 * Pins the asynchronous state, failure visibility, live settings and resource ownership added for
 * #4388, so blocking preparation, swallowed failures, leaked descriptors and stale options cannot
 * return unnoticed.
 */
@OptIn(ExperimentalSceneViewApi::class, ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
class MediaPlayerStateTest {

    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        ShadowLog.clear()
        ShadowMediaPlayer.setMediaInfoProvider { MediaInfo(MEDIA_DURATION_MS, 0) }
    }

    @After
    fun tearDown() {
        ShadowMediaPlayer.resetStaticState()
        ShadowLog.clear()
    }

    @Test
    fun `open returns while preparation is pending then publishes the same player`() {
        val session = session(URL)

        session.open(context)

        val player = requireNotNull(session.player)
        assertSame(MediaPlayerState.Preparing, session.state)
        assertEquals(State.PREPARING, shadowOf(player).state)

        idleMainLooper()

        val ready = session.state as MediaPlayerState.Ready
        assertSame(player, ready.player)
        assertSame(player, session.player)
    }

    @Test
    fun `auto play starts only the players configured to start`() {
        val playing = session("https://example.com/playing.mp4", autoPlay = true)
        val paused = session("https://example.com/paused.mp4", autoPlay = false)

        playing.open(context)
        paused.open(context)
        idleMainLooper()

        assertTrue(shadowOf(readyPlayer(playing)).isReallyPlaying)
        assertFalse(shadowOf(readyPlayer(paused)).isReallyPlaying)
        assertEquals(State.PREPARED, shadowOf(readyPlayer(paused)).state)
    }

    @Test
    fun `the constructor looping value is applied when each player becomes ready`() {
        val looping = session("https://example.com/looping.mp4", isLooping = true)
        val once = session("https://example.com/once.mp4", isLooping = false)

        looping.open(context)
        once.open(context)
        idleMainLooper()

        assertTrue(readyPlayer(looping).isLooping)
        assertFalse(readyPlayer(once).isLooping)
    }

    @Test
    fun `only locations without a scheme are opened as assets with their exact path`() {
        val assetPath = "videos/clip.mp4"
        val openedPaths = mutableListOf<String>()
        val asset = trackingAsset()
        val assetSession = session(assetPath)

        assetSession.open(context) { path ->
            openedPaths += path
            asset
        }
        session("https://example.com/clip.mp4").open(context) { path ->
            openedPaths += path
            trackingAsset()
        }
        session("file:///tmp/clip.mp4").open(context) { path ->
            openedPaths += path
            trackingAsset()
        }

        assertEquals(listOf(assetPath), openedPaths)
    }

    @Test
    fun `the asset descriptor is closed before open returns`() {
        val asset = trackingAsset()

        session("videos/clip.mp4").open(context) { asset }

        assertEquals(1, asset.closeCount)
    }

    @Test
    fun `a missing asset becomes failed with the original exception`() {
        val failure = FileNotFoundException("missing asset")
        val session = session("videos/missing.mp4")

        session.open(context) { throw failure }

        assertSame(failure, (session.state as MediaPlayerState.Failed).cause)
    }

    @Test
    fun `a data source failure is exposed and still closes the descriptor`() {
        val failure = IOException("unreadable descriptor")
        val asset = trackingAsset()
        ShadowMediaPlayer.addException(
            DataSource.toDataSource(asset.fileDescriptor, asset.startOffset, asset.length),
            failure,
        )
        val session = session("videos/broken.mp4")

        session.open(context) { asset }

        assertSame(failure, (session.state as MediaPlayerState.Failed).cause)
        assertEquals(1, asset.closeCount)
    }

    @Test
    fun `an asynchronous error while preparing exposes its media player codes`() {
        val session = session(URL)
        session.open(context)

        shadowOf(requireNotNull(session.player)).invokeErrorListener(
            MediaPlayer.MEDIA_ERROR_UNKNOWN,
            MediaPlayer.MEDIA_ERROR_UNSUPPORTED,
        )

        val cause = (session.state as MediaPlayerState.Failed).cause as MediaPlayerException
        assertEquals(MediaPlayer.MEDIA_ERROR_UNKNOWN, cause.what)
        assertEquals(MediaPlayer.MEDIA_ERROR_UNSUPPORTED, cause.extra)
    }

    @Test
    fun `an error after ready moves the session to failed`() {
        val session = session(URL)
        session.open(context)
        idleMainLooper()

        shadowOf(readyPlayer(session)).invokeErrorListener(
            MediaPlayer.MEDIA_ERROR_UNKNOWN,
            MediaPlayer.MEDIA_ERROR_IO,
        )

        assertTrue(session.state is MediaPlayerState.Failed)
    }

    @Test
    fun `a second error preserves the first failed instance and cause`() {
        val session = session(URL)
        session.open(context)
        val shadow = shadowOf(requireNotNull(session.player))
        shadow.invokeErrorListener(MediaPlayer.MEDIA_ERROR_UNKNOWN, MediaPlayer.MEDIA_ERROR_IO)
        val first = session.state as MediaPlayerState.Failed

        shadow.invokeErrorListener(
            MediaPlayer.MEDIA_ERROR_SERVER_DIED,
            MediaPlayer.MEDIA_ERROR_UNSUPPORTED,
        )

        assertSame(first, session.state)
        val cause = first.cause as MediaPlayerException
        assertEquals(MediaPlayer.MEDIA_ERROR_UNKNOWN, cause.what)
        assertEquals(MediaPlayer.MEDIA_ERROR_IO, cause.extra)
    }

    @Test
    fun `repeated errors log one warning without the URL query`() {
        val session = session("https://example.com/clip.mp4?token=secret")
        session.open(context)
        val shadow = shadowOf(requireNotNull(session.player))

        shadow.invokeErrorListener(MediaPlayer.MEDIA_ERROR_UNKNOWN, MediaPlayer.MEDIA_ERROR_IO)
        shadow.invokeErrorListener(
            MediaPlayer.MEDIA_ERROR_SERVER_DIED,
            MediaPlayer.MEDIA_ERROR_UNSUPPORTED,
        )

        val logs = ShadowLog.getLogsForTag("MediaPlayerState")
        assertEquals(1, logs.size)
        assertEquals(Log.WARN, logs.single().type)
        assertFalse(logs.single().msg.contains("secret"))
        assertEquals("Could not play https://example.com/clip.mp4", logs.single().msg)
    }

    @Test
    fun `settings changed after ready update the same player`() {
        val session = session(URL, isLooping = false, autoPlay = true)
        session.open(context)
        idleMainLooper()
        val player = readyPlayer(session)
        val shadow = shadowOf(player)

        session.isLooping = true
        session.autoPlay = false

        assertTrue(player.isLooping)
        assertFalse(shadow.isReallyPlaying)
        assertEquals(State.PAUSED, shadow.state)

        session.autoPlay = true

        assertTrue(shadow.isReallyPlaying)
        assertEquals(State.STARTED, shadow.state)
        assertSame(player, readyPlayer(session))
    }

    @Test
    fun `settings changed while preparing are applied when ready`() {
        val session = session(URL, isLooping = false, autoPlay = false)
        session.open(context)

        session.isLooping = true
        session.autoPlay = true
        idleMainLooper()

        val player = readyPlayer(session)
        assertTrue(player.isLooping)
        assertTrue(shadowOf(player).isReallyPlaying)
    }

    @Test
    fun `release ends the player and later callbacks do not change or log`() {
        val session = session(URL)
        session.open(context)
        idleMainLooper()
        val stateBeforeRelease = session.state
        val player = readyPlayer(session)
        val shadow = shadowOf(player)

        session.release()

        assertEquals(State.END, shadow.state)
        ShadowLog.clear()
        shadow.invokePreparedListener()
        shadow.invokeErrorListener(MediaPlayer.MEDIA_ERROR_UNKNOWN, MediaPlayer.MEDIA_ERROR_IO)
        assertSame(stateBeforeRelease, session.state)
        assertTrue(ShadowLog.getLogsForTag("MediaPlayerState").isEmpty())
    }

    @Test
    fun `release before preparation completes never publishes ready`() {
        val session = session(URL)
        session.open(context)
        val player = requireNotNull(session.player)

        session.release()
        assertEquals(State.END, shadowOf(player).state)
        idleMainLooper()

        assertSame(MediaPlayerState.Preparing, session.state)
    }

    @Test
    fun `remember media player recomposes from preparing to ready`() = runTest {
        val host = MediaPlayerHost(this, context, URL)

        assertSame(MediaPlayerState.Preparing, host.value)

        idleMainLooper()
        host.frame()

        assertTrue(host.value is MediaPlayerState.Ready)
    }

    @Test
    fun `composition setting changes reach the same ready player`() = runTest {
        val host = MediaPlayerHost(this, context, URL)
        idleMainLooper()
        host.frame()
        val player = (host.value as MediaPlayerState.Ready).player

        host.isLooping = false
        host.autoPlay = false
        host.frame()

        assertSame(player, (host.value as MediaPlayerState.Ready).player)
        assertFalse(player.isLooping)
        assertFalse(shadowOf(player).isReallyPlaying)

        host.isLooping = true
        host.autoPlay = true
        host.frame()

        assertSame(player, (host.value as MediaPlayerState.Ready).player)
        assertTrue(player.isLooping)
        assertTrue(shadowOf(player).isReallyPlaying)
    }

    @Test
    fun `changing the composition location releases the first player and prepares another`() =
        runTest {
            val host = MediaPlayerHost(this, context, URL)
            idleMainLooper()
            host.frame()
            val first = (host.value as MediaPlayerState.Ready).player

            host.fileLocation = "https://example.com/replacement.mp4"
            host.frame()

            assertEquals(State.END, shadowOf(first).state)
            assertSame(MediaPlayerState.Preparing, host.value)

            idleMainLooper()
            host.frame()
            val second = (host.value as MediaPlayerState.Ready).player
            assertNotSame(first, second)
        }

    @Test
    fun `leaving the composition releases the player`() = runTest {
        val host = MediaPlayerHost(this, context, URL)
        idleMainLooper()
        host.frame()
        val player = (host.value as MediaPlayerState.Ready).player

        host.present = false
        host.frame()

        assertEquals(State.END, shadowOf(player).state)
    }

    @Test
    fun `a remembered failure is returned and reported once across recompositions`() = runTest {
        val location = "https://example.com/failing.mp4"
        val failure = IOException("network failure")
        ShadowMediaPlayer.addException(
            DataSource.toDataSource(context, Uri.parse(location)),
            failure,
        )
        val reported = mutableListOf<Exception>()
        val host = MediaPlayerHost(this, context, location, onError = reported::add)

        host.frame()

        assertSame(failure, (host.value as MediaPlayerState.Failed).cause)
        assertEquals(listOf(failure), reported)

        repeat(3) {
            host.recompositionSignal++
            host.frame()
        }
        assertEquals(listOf(failure), reported)

        host.onError = null
        host.frame()
        assertEquals(listOf(failure), reported)
    }

    @Test
    fun `failure effect ignores preparing and ready and accepts a null callback`() = runTest {
        val reported = mutableListOf<Exception>()
        val host = FailureEffectHost(this, onError = reported::add)

        host.frame()
        assertTrue(reported.isEmpty())

        host.state = MediaPlayerState.Ready(MediaPlayer())
        host.frame()
        assertTrue(reported.isEmpty())

        host.onError = null
        host.state = MediaPlayerState.Failed(IOException("ignored"))
        host.frame()
        assertTrue(reported.isEmpty())
    }

    private fun session(
        location: String,
        isLooping: Boolean = true,
        autoPlay: Boolean = true,
    ) = MediaPlayerSession(location, isLooping, autoPlay)

    private fun readyPlayer(session: MediaPlayerSession) =
        (session.state as MediaPlayerState.Ready).player

    private fun idleMainLooper() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun trackingAsset(): TrackingAssetFileDescriptor {
        val file = File.createTempFile("sceneview-media-player", ".mp4").apply {
            writeBytes(ByteArray(8))
            deleteOnExit()
        }
        return TrackingAssetFileDescriptor(
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY),
            file.length(),
        )
    }

    private class TrackingAssetFileDescriptor(
        parcelFileDescriptor: ParcelFileDescriptor,
        length: Long,
    ) : AssetFileDescriptor(parcelFileDescriptor, 0, length) {
        var closeCount = 0
            private set

        override fun close() {
            closeCount++
            super.close()
        }
    }

    /** A no-UI composition containing [rememberMediaPlayer], driven one frame at a time. */
    private class MediaPlayerHost(
        scope: TestScope,
        private val context: Context,
        fileLocation: String,
        isLooping: Boolean = true,
        autoPlay: Boolean = true,
        onError: ((Exception) -> Unit)? = null,
    ) : CompositionHost(scope) {
        var fileLocation by mutableStateOf(fileLocation)
        var isLooping by mutableStateOf(isLooping)
        var autoPlay by mutableStateOf(autoPlay)
        var onError by mutableStateOf(onError)
        var present by mutableStateOf(true)
        var recompositionSignal by mutableStateOf(0)
        var value: MediaPlayerState? = null
            private set

        init {
            setContent { Content() }
        }

        // A member and not the body of `init`: there, the constructor parameters shadow the
        // state-backed properties of the same name, and the composition would never see a change.
        @Composable
        private fun Content() {
            CompositionLocalProvider(LocalContext provides context) {
                @Suppress("UNUSED_EXPRESSION")
                recompositionSignal
                value = if (present) {
                    rememberMediaPlayer(fileLocation, isLooping, autoPlay).also {
                        MediaPlayerFailureEffect(it, onError)
                    }
                } else {
                    null
                }
            }
        }
    }

    /** A no-UI composition for exercising [MediaPlayerFailureEffect] independently. */
    private class FailureEffectHost(
        scope: TestScope,
        onError: ((Exception) -> Unit)?,
    ) : CompositionHost(scope) {
        var state by mutableStateOf<MediaPlayerState>(MediaPlayerState.Preparing)
        var onError by mutableStateOf(onError)

        init {
            setContent { Content() }
        }

        // A member for the same reason as in [MediaPlayerHost]: `onError` must be the property.
        @Composable
        private fun Content() {
            MediaPlayerFailureEffect(state, onError)
        }
    }

    /** The headless Recomposer + frame clock shared by the two composition hosts. */
    private abstract class CompositionHost(private val scope: TestScope) {
        private val clock = BroadcastFrameClock()
        private val recomposer = Recomposer(scope.backgroundScope.coroutineContext + clock)
        private val composition = Composition(NoNodes(), recomposer)
        private var frameTime = 0L

        init {
            scope.backgroundScope.launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        }

        protected fun setContent(content: @Composable () -> Unit) {
            composition.setContent(content)
            settle()
        }

        fun frame() {
            settle()
            clock.sendFrame(frameTime++)
            settle()
        }

        private fun settle() {
            repeat(2) {
                Snapshot.sendApplyNotifications()
                scope.runCurrent()
            }
        }
    }

    private class NoNodes : AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(index: Int, instance: Unit) = Unit
        override fun insertBottomUp(index: Int, instance: Unit) = Unit
        override fun remove(index: Int, count: Int) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun onClear() = Unit
    }

    private companion object {
        const val MEDIA_DURATION_MS = 1_000
        const val URL = "https://example.com/clip.mp4"
    }
}

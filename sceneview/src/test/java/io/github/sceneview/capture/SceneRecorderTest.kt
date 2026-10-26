package io.github.sceneview.capture

import android.Manifest
import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.os.Looper
import android.view.Surface
import android.view.View
import io.github.sceneview.utils.SurfaceMirrorer
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Lifecycle contract of [SceneRecorder]: what reaches the encoder, what is mirrored and when,
 * what a finished recording reports, and how a photo gets its overlay.
 *
 * The encoder, the overlay compositor and the pixel copy are the three places that need a GPU or
 * a codec; they are replaced by fakes, everything else is the real class on a Robolectric main
 * looper.
 */
@RunWith(RobolectricTestRunner::class)
class SceneRecorderTest {

    private val app: Application get() = RuntimeEnvironment.getApplication()
    private lateinit var mirrorer: SurfaceMirrorer
    private lateinit var sessions: FakeSessions
    private lateinit var renderView: View

    @Before
    fun setUp() {
        mirrorer = SurfaceMirrorer()
        sessions = FakeSessions()
        renderView = View(app).apply { layout(0, 0, 1080, 2400) }
    }

    private fun recorder(
        compositors: FakeCompositors? = null,
        pixels: PixelSource = PixelSource { null },
    ) = SceneRecorder(
        context = app,
        surfaceMirrorer = mirrorer,
        sessionFactory = sessions,
        compositorFactory = compositors,
        pixelSource = pixels,
        encoderSupports = { _, _ -> true },
    )

    private fun bindView() {
        mirrorer.host = object : SurfaceMirrorer.Host {
            override val renderView: View get() = this@SceneRecorderTest.renderView
            override fun requestRender() = Unit
        }
    }

    private fun idleFor(millis: Long) =
        shadowOf(Looper.getMainLooper()).idleFor(millis, TimeUnit.MILLISECONDS)

    // ── Video ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `recording needs a scene to record`() {
        val recorder = recorder()

        assertFalse(recorder.startRecording(File(app.cacheDir, "a.mp4")))
        assertFalse(recorder.isRecording)
        assertNotNull(recorder.lastError)
        assertTrue(sessions.created.isEmpty())
    }

    @Test
    fun `recording encodes the scene at 1080p and 30 fps and mirrors it to the encoder`() {
        bindView()
        val recorder = recorder()
        val file = File(app.cacheDir, "a.mp4")

        assertTrue(recorder.startRecording(file))

        val session = sessions.created.single()
        assertEquals(VideoSize(864, 1920), session.request.size)
        assertEquals(30, session.request.frameRate)
        assertEquals(file, session.request.file)
        assertTrue(session.started)
        assertEquals(listOf(session.inputSurface), mirrorer.mirroredSurfaces)
        assertTrue(recorder.isRecording)
    }

    @Test
    fun `a second start while recording is refused`() {
        bindView()
        val recorder = recorder()

        assertTrue(recorder.startRecording(File(app.cacheDir, "a.mp4")))
        assertFalse(recorder.startRecording(File(app.cacheDir, "b.mp4")))
        assertEquals(1, sessions.created.size)
    }

    @Test
    fun `stop unmirrors, finalises the file and reports the recording`() {
        bindView()
        val recorder = recorder()
        val file = File(app.cacheDir, "a.mp4")
        var finished: SceneRecording? = null
        recorder.onRecordingFinished = { finished = it }

        recorder.startRecording(file)
        idleFor(2_000)
        assertTrue(recorder.elapsedMillis >= 1_750)
        val recording = recorder.stopRecording()

        val session = sessions.created.single()
        assertTrue(session.stopped)
        assertTrue(session.released)
        assertTrue(mirrorer.mirroredSurfaces.isEmpty())
        assertFalse(recorder.isRecording)
        assertNotNull(recording)
        assertEquals(file, recording!!.file)
        assertEquals(864, recording.width)
        assertEquals(1920, recording.height)
        assertEquals(2_000L, recording.durationMillis)
        assertFalse(recording.reachedMaxDuration)
        assertSame(recording, recorder.lastRecording)
        assertSame(recording, finished)
    }

    @Test
    fun `stop when nothing is recording returns null`() {
        assertNull(recorder().stopRecording())
    }

    @Test
    fun `a recording the encoder could not finalise is deleted and reported as an error`() {
        bindView()
        sessions.stopResult = false
        val recorder = recorder()
        val file = File(app.cacheDir, "a.mp4")

        recorder.startRecording(file)
        val recording = recorder.stopRecording()

        assertNull(recording)
        assertFalse(file.exists())
        assertNotNull(recorder.lastError)
        assertTrue(sessions.created.single().released)
    }

    @Test
    fun `an encoder that fails to prepare leaves nothing mirrored`() {
        bindView()
        sessions.failOnCreate = true
        val recorder = recorder()

        assertFalse(recorder.startRecording(File(app.cacheDir, "a.mp4")))
        assertTrue(mirrorer.mirroredSurfaces.isEmpty())
        assertFalse(recorder.isRecording)
        assertNotNull(recorder.lastError)
    }

    @Test
    fun `the recording stops by itself at its maximum duration`() {
        bindView()
        val recorder = recorder()
        var finished: SceneRecording? = null
        recorder.onRecordingFinished = { finished = it }

        recorder.startRecording(
            File(app.cacheDir, "a.mp4"),
            VideoSettings(maxDurationMillis = 5_000),
        )
        idleFor(4_900)
        assertTrue(recorder.isRecording)
        idleFor(200)

        assertFalse(recorder.isRecording)
        assertTrue(mirrorer.mirroredSurfaces.isEmpty())
        assertNotNull(finished)
        assertTrue(finished!!.reachedMaxDuration)
        assertEquals(5_000L, finished!!.durationMillis)
    }

    @Test
    fun `the microphone is recorded only when asked and allowed`() {
        bindView()
        val recorder = recorder()

        recorder.startRecording(File(app.cacheDir, "a.mp4"), VideoSettings(recordAudio = true))
        val withoutPermission = recorder.stopRecording()
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        recorder.startRecording(File(app.cacheDir, "b.mp4"), VideoSettings(recordAudio = true))
        val withPermission = recorder.stopRecording()
        recorder.startRecording(File(app.cacheDir, "c.mp4"))
        val notAsked = recorder.stopRecording()

        assertEquals(listOf(false, true, false), sessions.created.map { it.request.recordAudio })
        assertFalse(withoutPermission!!.hasAudio)
        assertTrue(withPermission!!.hasAudio)
        assertFalse(notAsked!!.hasAudio)
    }

    @Test
    fun `an overlay routes the mirror through the compositor`() {
        bindView()
        val compositors = FakeCompositors()
        val recorder = recorder(compositors = compositors)
        recorder.overlay = CaptureOverlay { _, _, _ -> }

        recorder.startRecording(File(app.cacheDir, "a.mp4"))

        val session = sessions.created.single()
        val compositor = compositors.created.single()
        assertSame(session.inputSurface, compositor.output)
        assertEquals(VideoSize(864, 1920), compositor.size)
        assertEquals(listOf(compositor.inputSurface), mirrorer.mirroredSurfaces)

        val recording = recorder.stopRecording()
        assertTrue(compositor.released)
        assertTrue(recording!!.hasOverlay)
    }

    @Test
    fun `without a compositor the video is recorded without its overlay`() {
        bindView()
        val recorder = recorder(compositors = null)
        recorder.overlay = CaptureOverlay { _, _, _ -> }

        recorder.startRecording(File(app.cacheDir, "a.mp4"))

        assertEquals(listOf(sessions.created.single().inputSurface), mirrorer.mirroredSurfaces)
        assertFalse(recorder.stopRecording()!!.hasOverlay)
    }

    @Test
    fun `release stops a running recording`() {
        bindView()
        val recorder = recorder()

        recorder.startRecording(File(app.cacheDir, "a.mp4"))
        recorder.release()

        assertFalse(recorder.isRecording)
        assertTrue(sessions.created.single().released)
        assertTrue(mirrorer.mirroredSurfaces.isEmpty())
    }

    @Test
    fun `capture files are timestamped under the app captures folder`() {
        val file = recorder().newCaptureFile("mp4")

        assertEquals("captures", file.parentFile!!.name)
        assertTrue(file.name, file.name.matches(Regex("scene_\\d{8}_\\d{6}_\\d{3}\\.mp4")))
    }

    // ── Photo ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a photo needs a scene to capture`() = runTest {
        assertNull(recorder(pixels = { solid(Color.BLUE) }).takePhoto())
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `a photo is the full-resolution copy of the scene view`() = runTest {
        bindView()
        var copiedFrom: View? = null
        val recorder = recorder(pixels = { view -> copiedFrom = view; solid(Color.BLUE) })

        val photo = recorder.takePhoto()!!

        assertSame(renderView, copiedFrom)
        assertEquals(Color.BLUE, photo.getPixel(0, 0))
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `the overlay is drawn on the photo at the photo size`() = runTest {
        bindView()
        val recorder = recorder(pixels = { solid(Color.BLUE) })
        var drawnAt: Pair<Int, Int>? = null
        recorder.overlay = CaptureOverlay { canvas, width, height ->
            drawnAt = width to height
            canvas.drawColor(Color.RED)
        }

        val photo = recorder.takePhoto()!!

        assertEquals(8 to 4, drawnAt)
        assertEquals(Color.RED, photo.getPixel(3, 2))
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `savePhoto writes a decodable image`() = runTest {
        bindView()
        val recorder = recorder(pixels = { solid(Color.BLUE) })
        val file = File(app.cacheDir, "photo.png")

        val saved = recorder.savePhoto(file, PhotoFormat.PNG)

        assertEquals(file, saved)
        val decoded = BitmapFactory.decodeFile(file.absolutePath)
        assertEquals(8, decoded.width)
        assertEquals(Color.BLUE, decoded.getPixel(1, 1))
    }

    @Test
    fun `savePhoto returns null when there is nothing to capture`() = runTest {
        val file = File(app.cacheDir, "nothing.jpg")

        assertNull(recorder().savePhoto(file))
        assertFalse(file.exists())
    }

    private fun solid(color: Int): Bitmap =
        Bitmap.createBitmap(8, 4, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    // ── Fakes ───────────────────────────────────────────────────────────────────────────────────

    private class FakeSession(val request: VideoSessionRequest, private val stopResult: Boolean) :
        VideoSession {
        override val inputSurface = Surface(SurfaceTexture(0))
        var started = false
        var stopped = false
        var released = false

        override fun start() {
            started = true
            request.file.writeText("mp4")
        }

        override fun stop(): Boolean {
            stopped = true
            return stopResult
        }

        override fun release() {
            released = true
        }
    }

    private class FakeSessions : VideoSessionFactory {
        val created = mutableListOf<FakeSession>()
        var stopResult = true
        var failOnCreate = false

        override fun create(request: VideoSessionRequest): VideoSession {
            if (failOnCreate) throw IllegalStateException("prepare failed")
            return FakeSession(request, stopResult).also { created += it }
        }
    }

    private class FakeCompositor(val size: VideoSize, val output: Surface) : OverlayCompositor {
        override val inputSurface = Surface(SurfaceTexture(0))
        var released = false
        override fun release() {
            released = true
        }
    }

    private class FakeCompositors : OverlayCompositorFactory {
        val created = mutableListOf<FakeCompositor>()
        override fun create(size: VideoSize, output: Surface, overlay: CaptureOverlay) =
            FakeCompositor(size, output).also { created += it }
    }
}

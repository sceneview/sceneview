package io.github.sceneview

import android.graphics.SurfaceTexture
import android.util.Log
import android.view.Choreographer
import android.view.TextureView
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.filament.Camera
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.Renderer
import com.google.android.filament.Scene
import com.google.android.filament.View
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/**
 * A [TextureView]-backed scene must keep presenting frames after its view is resized (#3944).
 *
 * Filament's `UiHelper` answers `onSurfaceTextureSizeChanged` by calling
 * `RendererCallback.onNativeWindowChanged` again with the **same** `Surface`, so that the swap
 * chain is rebuilt at the new buffer size. [SceneRenderer] used to create the new swap chain
 * before destroying the old one. On Android a window can only have one producer connected at a
 * time, so the new `eglCreateWindowSurface` was refused (`BufferQueueProducer: connect: already
 * connected` → `EGL_BAD_ALLOC`) and the old one was then destroyed: the view kept its last frame
 * and never drew again, while `renderFrame` went on reporting frames as presented.
 *
 * The frames are counted where they land — `TextureView.SurfaceTextureListener
 * .onSurfaceTextureUpdated`, which fires once per buffer the view latches — not from
 * [SceneRenderer.presentedFrameCount], which was exactly the counter that did not notice.
 */
@RunWith(AndroidJUnit4::class)
class SurfaceResizeRenderTest {

    private lateinit var scenario: ActivityScenario<SurfaceHostActivity>
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private lateinit var engine: Engine
    private lateinit var renderer: Renderer
    private lateinit var scene: Scene
    private lateinit var view: View
    private lateinit var camera: Camera
    private lateinit var sceneRenderer: SceneRenderer
    private lateinit var textureView: TextureView

    /** Buffers the TextureView has latched, i.e. frames that actually reached the screen. */
    private val latchedFrames = AtomicInteger()

    @Volatile
    private var rendering = false

    private val frameLoop = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!rendering) return
            sceneRenderer.renderFrame(frameTimeNanos) {}
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    @Before
    fun setUp() {
        scenario = ActivityScenario.launch(SurfaceHostActivity::class.java)
        scenario.onActivity { activity ->
            engine = createEngine(createEglContext())
            renderer = createRenderer(engine).apply {
                clearOptions = clearOptions.apply {
                    clear = true
                    clearColor = doubleArrayOf(0.1, 0.4, 0.8, 1.0)
                }
            }
            scene = engine.createScene()
            camera = engine.createCamera(EntityManager.get().create())
            view = engine.createView().also {
                it.scene = scene
                it.camera = camera
            }
            sceneRenderer = SceneRenderer(engine, view, renderer)

            textureView = TextureView(activity)
            val root = FrameLayout(activity)
            root.addView(textureView, FrameLayout.LayoutParams(INITIAL_SIZE_PX, INITIAL_SIZE_PX))
            activity.setContentView(root)

            @Suppress("DEPRECATION")
            sceneRenderer.attachToTextureView(
                textureView,
                isOpaque = true,
                context = activity,
                display = activity.windowManager.defaultDisplay
            )
            // Wrap UiHelper's listener to count the frames that land on the view.
            val uiHelperListener = checkNotNull(textureView.surfaceTextureListener)
            textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) =
                    uiHelperListener.onSurfaceTextureAvailable(st, w, h)

                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) =
                    uiHelperListener.onSurfaceTextureSizeChanged(st, w, h)

                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean =
                    uiHelperListener.onSurfaceTextureDestroyed(st)

                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {
                    latchedFrames.incrementAndGet()
                    uiHelperListener.onSurfaceTextureUpdated(st)
                }
            }

            rendering = true
            Choreographer.getInstance().postFrameCallback(frameLoop)
        }
    }

    @After
    fun tearDown() {
        scenario.onActivity {
            rendering = false
            Choreographer.getInstance().removeFrameCallback(frameLoop)
            sceneRenderer.destroy()
            engine.destroyView(view)
            engine.destroyScene(scene)
            engine.destroyCameraComponent(camera.entity)
            EntityManager.get().destroy(camera.entity)
            engine.destroyRenderer(renderer)
            engine.safeDestroy()
        }
        scenario.close()
    }

    @Test
    fun textureViewKeepsPresentingAfterResize() {
        awaitLatchedFrames(since = 0, what = "before any resize")

        // Two resizes in a row: a viewport animating its bounds (a bottom sheet, a shared-element
        // transition) resizes the view several times, and every one of them must survive.
        for ((index, size) in listOf(RESIZED_WIDTH_PX to RESIZED_HEIGHT_PX, INITIAL_SIZE_PX to INITIAL_SIZE_PX).withIndex()) {
            var framesAtResize = 0
            scenario.onActivity {
                framesAtResize = latchedFrames.get()
                textureView.layoutParams = FrameLayout.LayoutParams(size.first, size.second)
            }
            instrumentation.waitForIdleSync()
            awaitLatchedFrames(since = framesAtResize, what = "after resize #${index + 1} to ${size.first}x${size.second}")
        }
    }

    /** Waits until [MIN_FRAMES] more frames than [since] have landed, or fails. */
    private fun awaitLatchedFrames(since: Int, what: String) {
        val deadline = System.nanoTime() + TIMEOUT_NANOS
        while (latchedFrames.get() - since < MIN_FRAMES && System.nanoTime() < deadline) {
            Thread.sleep(50)
        }
        var presented = 0L
        scenario.onActivity { presented = sceneRenderer.presentedFrameCount }
        val landed = latchedFrames.get() - since
        Log.i(TAG, "$what: $landed frame(s) landed on the TextureView (renderer presented $presented in total)")
        assertTrue(
            "Only $landed frame(s) reached the TextureView $what within ${TIMEOUT_NANOS / 1_000_000} ms " +
                "(renderer believes it presented $presented). The swap chain rebuilt for the resize is dead.",
            landed >= MIN_FRAMES
        )
    }

    private companion object {
        const val TAG = "SurfaceResizeRenderTest"
        const val INITIAL_SIZE_PX = 240
        const val RESIZED_WIDTH_PX = 360
        const val RESIZED_HEIGHT_PX = 300
        const val MIN_FRAMES = 10
        const val TIMEOUT_NANOS = 5_000_000_000L
    }
}

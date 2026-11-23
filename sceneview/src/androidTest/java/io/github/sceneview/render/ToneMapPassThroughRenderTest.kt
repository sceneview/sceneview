package io.github.sceneview.render

import android.graphics.SurfaceTexture
import android.util.Log
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.filament.Camera
import com.google.android.filament.ColorGrading
import com.google.android.filament.EntityManager
import com.google.android.filament.MaterialInstance
import com.google.android.filament.Skybox
import com.google.android.filament.Stream
import com.google.android.filament.Texture
import com.google.android.filament.ToneMapper
import com.google.android.filament.android.StreamHelper
import io.github.sceneview.SceneNodeManager
import io.github.sceneview.collision.CollisionSystem
import io.github.sceneview.filmicViews
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.math.Direction
import io.github.sceneview.math.Position
import io.github.sceneview.math.Size
import io.github.sceneview.node.PlaneNode
import org.junit.AfterClass
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * The pass-through materials display a saturated source colour as it is (#5219).
 *
 * `video_texture.mat` (a `VideoNode`) and `view_texture_unlit.mat` (an unlit `ViewNode`) sample
 * an external stream, an sRGB image, and pre-distort it by the inverse of the View's tone mapper
 * so the View's grading lands it back on the source colour. Filament grades in Rec.2020: the
 * inverse used to run channel by channel in Rec.709, exact for greys only, so a `#66B0F5` sky
 * came out lighter and less saturated.
 *
 * Each colour of a saturated chart is painted into the stream through a [Surface], as a decoded
 * video frame or a drawn View lands in it, then drawn full frame and read back at the centre, in
 * a [io.github.sceneview.createView] view (PBR Neutral) and in a Filmic view like
 * [io.github.sceneview.createARView]'s. Needs pixel readback ([RenderTestCapabilities]): run with
 * `-e gpuReadback true`.
 */
@RunWith(AndroidJUnit4::class)
class ToneMapPassThroughRenderTest {

    companion object {
        private const val SIZE = 64
        private const val TAG = "ToneMapPassThrough"

        /**
         * A saturated chart, sRGB bytes: the Scene Semantics legend's most saturated classes
         * (sky, terracotta, green, strong blue, pink, cyan, amber, purple), and a grey.
         */
        private val CHART = intArrayOf(
            0x66B0F5, 0xD95F57, 0x4CB050, 0x2196F2, 0xF5428C, 0x00BDD4, 0xFFC207, 0x9E75BD, 0x808080,
        )

        /**
         * Vivid cyans no inverse can show as they are: they need a negative Rec.709 channel, which
         * the colour buffer cannot hold. Each maps to what `ToneMapInverseContractTest`'s model of
         * the shared inverse predicts: under PBR Neutral, the per-channel Rec.709 inverse's result
         * (clipping alone showed #00FFFF as (150, 254, 254)); under Filmic, the clipped one, nearer
         * the source than before (#00FFFF used to show (245, 255, 255)).
         */
        private val VIVID_IN_A_3D_VIEW = mapOf(0x00FFFF to 0x00E9E9, 0x00E5FF to 0x00E3E9)
        private val VIVID_IN_AN_AR_VIEW = mapOf(0x00FFFF to 0xDFF9FE, 0x00E5FF to 0xAEE2FE)

        /** 8-bit steps per channel left to the grading LUT, the colour buffer and dithering. */
        private const val TOLERANCE = 6

        /**
         * A channel darker than [DARK_CHANNEL] may land up to [DARK_TOLERANCE] steps above its
         * source: under Filmic, a strong blue's or a cyan's red would need a negative scene value,
         * which the colour buffer cannot hold (the inverse clips it at zero).
         */
        private const val DARK_CHANNEL = 0x30
        private const val DARK_TOLERANCE = 16

        private lateinit var harness: RenderTestHarness
        private lateinit var materialLoader: MaterialLoader
        private lateinit var nodeManager: SceneNodeManager
        private var cameraEntity: Int? = null

        @JvmStatic
        @BeforeClass
        fun setupClass() {
            harness = RenderTestHarness(width = SIZE, height = SIZE)
            harness.runOnMain {
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                materialLoader = MaterialLoader(harness.engine, context)
                nodeManager = SceneNodeManager(harness.scene, CollisionSystem(harness.view))
            }
        }

        @JvmStatic
        @AfterClass
        fun teardownClass() {
            harness.runOnMain {
                materialLoader.destroy()
                cameraEntity?.let {
                    harness.engine.destroyCameraComponent(it)
                    EntityManager.get().destroy(it)
                }
            }
            harness.destroy()
        }
    }

    /** An external stream fed through a [Surface], as `VideoNode` and `ViewNode` build theirs. */
    private class StreamSource(private val harness: RenderTestHarness) {
        val surfaceTexture = SurfaceTexture(0).apply {
            detachFromGLContext()
            setDefaultBufferSize(16, 16)
        }
        val surface = Surface(surfaceTexture)
        val stream: Stream = StreamHelper.setStreamSource(Stream.Builder(), surfaceTexture)
            .build(harness.engine)
        val texture: Texture = Texture.Builder()
            .sampler(Texture.Sampler.SAMPLER_EXTERNAL)
            .format(Texture.InternalFormat.RGB8)
            .build(harness.engine)
            .apply { setExternalStream(harness.engine, stream) }

        /** Posts one frame of the opaque sRGB colour [rgb]. */
        fun paint(rgb: Int) {
            val canvas = surface.lockCanvas(null)
            canvas.drawColor(0xFF000000.toInt() or rgb)
            surface.unlockCanvasAndPost(canvas)
        }

        fun destroy() {
            harness.engine.destroyTexture(texture)
            harness.engine.destroyStream(stream)
            surface.release()
            surfaceTexture.release()
        }
    }

    private fun lookAtTheOrigin() {
        val engine = harness.engine
        val entity = cameraEntity ?: EntityManager.get().create().also { cameraEntity = it }
        harness.view.camera = (engine.getCameraComponent(entity) ?: engine.createCamera(entity)).apply {
            setProjection(45.0, 1.0, 0.1, 100.0, Camera.Fov.VERTICAL)
            lookAt(0.0, 0.0, 5.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0)
            setExposure(1.0f)
        }
        harness.scene.skybox = Skybox.Builder().color(1f, 0f, 0f, 1f).build(engine)
    }

    private fun channels(rgb: Int) = IntArray(3) { (rgb shr (16 - 8 * it)) and 0xFF }

    /**
     * Each chart colour through [material]'s instance on a full-frame quad, read back at the
     * centre, against the source colour; each [vivid] colour against the colour it maps to.
     */
    private fun assertChartShowsAsSource(
        view: String,
        material: String,
        vivid: Map<Int, Int>,
        instanceOf: (Texture) -> MaterialInstance,
    ) {
        harness.resetScene()
        harness.runOnMain { lookAtTheOrigin() }
        var source: StreamSource? = null
        var instance: MaterialInstance? = null
        var quad: PlaneNode? = null
        val drawn = LinkedHashMap<Int, Int>()
        try {
            harness.runOnMain {
                val src = StreamSource(harness).also { source = it }
                val mi = instanceOf(src.texture).also { instance = it }
                val node = PlaneNode(
                    engine = harness.engine,
                    size = Size(6f, 6f),
                    center = Position(0f, 0f, 0f),
                    normal = Direction(0f, 0f, 1f),
                    materialInstances = listOf(mi)
                ).also { quad = it }
                // Added as a Scene or ARScene adds a node: that is where the material is pointed
                // at its view's inverse.
                nodeManager.addNode(node)
            }
            for (rgb in CHART.toList() + vivid.keys) {
                harness.runOnMain { source!!.paint(rgb) }
                // The stream latches the newest frame at each beginFrame: give the producer a
                // moment, then draw a few frames so the grading pipeline settles on it.
                repeat(3) {
                    Thread.sleep(40)
                    harness.runOnMain { harness.renderFrames(3) }
                }
                drawn[rgb] = harness.capturePixels().getPixel(SIZE / 2, SIZE / 2) and 0xFFFFFF
            }
        } finally {
            harness.runOnMain {
                quad?.let {
                    nodeManager.removeNode(it)
                    it.destroy()
                }
                harness.renderFrames(2)
                harness.engine.flushAndWait()
                instance?.let { materialLoader.destroyMaterialInstance(it) }
                source?.destroy()
            }
        }
        val said = drawn.entries.joinToString("; ") { (src, shown) ->
            val off = channels(shown).zip(channels(vivid[src] ?: src)).maxOf { (a, b) -> abs(a - b) }
            val expected = vivid[src]?.let { " expected #%06X".format(it) } ?: ""
            "source #%06X$expected shown #%06X (%d off)".format(src, shown, off)
        }
        Log.i(TAG, "$material, $view: $said")
        drawn.forEach { (src, shown) ->
            val want = channels(vivid[src] ?: src)
            channels(shown).forEachIndexed { c, value ->
                val tolerance = if (want[c] < DARK_CHANNEL) DARK_TOLERANCE else TOLERANCE
                assertTrue(
                    "$material, $view, #%06X channel $c: ${abs(value - want[c])} steps off (tolerance $tolerance): $said"
                        .format(src),
                    abs(value - want[c]) <= tolerance
                )
            }
        }
    }

    /** Runs [block] with the harness's view graded by Filmic and listed among the Filmic views. */
    private fun inAFilmicView(block: () -> Unit) {
        var filmic: ColorGrading? = null
        var neutral: ColorGrading? = null
        harness.runOnMain {
            neutral = harness.view.colorGrading
            filmic = ColorGrading.Builder().toneMapper(ToneMapper.Filmic()).build(harness.engine)
            harness.view.colorGrading = filmic
            filmicViews += harness.view
        }
        try {
            block()
        } finally {
            harness.runOnMain {
                filmicViews -= harness.view
                harness.view.colorGrading = neutral
                harness.renderFrames(2)
                harness.engine.flushAndWait()
                filmic?.let { harness.engine.destroyColorGrading(it) }
            }
        }
    }

    private val video: (Texture) -> MaterialInstance = { materialLoader.createVideoInstance(it) }
    private val viewUnlit: (Texture) -> MaterialInstance = { materialLoader.createViewInstance(it, unlit = true) }

    @Test
    fun video_showsASaturatedChartAsItsSource_inA3dView() {
        RenderTestCapabilities.assumeGpuReadbackAvailable()
        assertChartShowsAsSource("PBR Neutral (createView)", "video_texture", VIVID_IN_A_3D_VIEW, video)
    }

    @Test
    fun video_showsASaturatedChartAsItsSource_inAnArView() {
        RenderTestCapabilities.assumeGpuReadbackAvailable()
        inAFilmicView { assertChartShowsAsSource("Filmic (createARView)", "video_texture", VIVID_IN_AN_AR_VIEW, video) }
    }

    @Test
    fun viewTextureUnlit_showsASaturatedChartAsItsSource_inA3dView() {
        RenderTestCapabilities.assumeGpuReadbackAvailable()
        assertChartShowsAsSource("PBR Neutral (createView)", "view_texture_unlit", VIVID_IN_A_3D_VIEW, viewUnlit)
    }

    @Test
    fun viewTextureUnlit_showsASaturatedChartAsItsSource_inAnArView() {
        RenderTestCapabilities.assumeGpuReadbackAvailable()
        inAFilmicView { assertChartShowsAsSource("Filmic (createARView)", "view_texture_unlit", VIVID_IN_AN_AR_VIEW, viewUnlit) }
    }
}

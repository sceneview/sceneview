package io.github.sceneview.render

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.filament.Camera
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.RenderTarget
import com.google.android.filament.Skybox
import com.google.android.filament.Texture
import com.google.android.filament.Viewport
import io.github.sceneview.createEglContext
import io.github.sceneview.createEngine
import io.github.sceneview.environment.PrefilterEntitySlots
import io.github.sceneview.loaders.EnvironmentLoader
import io.github.sceneview.safeDestroy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * An HDR environment is lit on every engine of the process, not only on the first one.
 *
 * On Filament 1.77+, `IBLPrefilterContext` creates its entities in a second copy of
 * `utils::EntityManager` (see [PrefilterEntitySlots]). Once the first engine is torn down, its
 * entities' slots are dead in the engine's own manager, the prefilter's full-screen triangle is
 * culled as a dead entity, and both cubemaps it produces come out black: the skybox and the
 * specular reflections. This test loads the same HDR on three engines created and destroyed one
 * after the other, the way a demo app opens one 3D screen after another, and reads back a frame of
 * each cubemap.
 *
 * Without [PrefilterEntitySlots] it fails on the second engine (mean 0 on both cubemaps).
 *
 * Reads pixels back from a [RenderTarget] after `renderStandaloneView`, so it needs no swap chain
 * and no `beginFrame`; the readback callback runs on an inline [Executor] (Filament drops a
 * callback whose handler is null).
 */
@RunWith(AndroidJUnit4::class)
class HdrEnvironmentSecondEngineRenderTest {

    private companion object {
        const val TAG = "HdrSecondEngine"
        const val SIZE = 32
        const val ENGINES = 3

        /** Mean 8-bit channel value under which a frame counts as black. The HDR renders ~200. */
        const val BLACK = 8.0
    }

    @Before
    fun requireReadback() = RenderTestCapabilities.assumeGpuReadbackAvailable()

    @Test
    fun hdrEnvironment_isLitOnEverySuccessiveEngine() {
        val means = (1..ENGINES).map { renderOnFreshEngine() }
        val report = means.mapIndexed { i, (sky, reflections) ->
            "engine ${i + 1}: skybox $sky, reflections $reflections"
        }.joinToString("; ")
        Log.i(TAG, report)

        // The workaround only holds if it ran before any other entity existed.
        assertEquals("first reserved entity slot", 1, PrefilterEntitySlots.firstSlot)
        means.forEachIndexed { i, (sky, reflections) ->
            assertTrue("engine ${i + 1} skybox is black: $report", sky > BLACK)
            assertTrue("engine ${i + 1} reflections are black: $report", reflections > BLACK)
        }
    }

    /** Builds an engine, loads the HDR on it, returns the mean of each cubemap, destroys it all. */
    private fun renderOnFreshEngine(): Pair<Double, Double> {
        lateinit var probe: Probe
        onMain {
            val engine = createEngine(createEglContext())
            val loader = EnvironmentLoader(
                engine,
                InstrumentationRegistry.getInstrumentation().targetContext
            )
            val environment = checkNotNull(loader.createHDREnvironment(flatHdr())) {
                "the HDR did not decode"
            }
            probe = Probe(engine, loader)
            probe.skyboxes += checkNotNull(environment.skybox)
            probe.skyboxes += Skybox.Builder()
                .environment(checkNotNull(environment.indirectLight?.reflectionsTexture))
                .build(engine)
        }
        val sky = probe.meanOf(0)
        val reflections = probe.meanOf(1)
        onMain { probe.destroy() }
        return sky to reflections
    }

    /** A headless view rendering one skybox at a time into a readable [RenderTarget]. */
    private inner class Probe(val engine: Engine, val loader: EnvironmentLoader) {
        val skyboxes = mutableListOf<Skybox>()
        private val renderer = engine.createRenderer()
        private val scene = engine.createScene()
        private val cameraEntity = EntityManager.get().create()
        private val camera = engine.createCamera(cameraEntity).apply {
            setProjection(90.0, 1.0, 0.1, 10.0, Camera.Fov.VERTICAL)
            lookAt(0.0, 0.0, 0.0, 0.0, 0.0, -1.0, 0.0, 1.0, 0.0)
        }
        private val color = Texture.Builder()
            .width(SIZE).height(SIZE).levels(1)
            .usage(Texture.Usage.COLOR_ATTACHMENT or Texture.Usage.SAMPLEABLE)
            .format(Texture.InternalFormat.RGBA8)
            .build(engine)
        private val target = RenderTarget.Builder()
            .texture(RenderTarget.AttachmentPoint.COLOR, color)
            .build(engine)
        private val view = engine.createView().apply {
            this.camera = this@Probe.camera
            this.scene = this@Probe.scene
            viewport = Viewport(0, 0, SIZE, SIZE)
            isPostProcessingEnabled = false
            renderTarget = target
        }

        /** Mean 8-bit RGB value of a frame showing [skyboxes] at [index]. */
        fun meanOf(index: Int): Double {
            val pixels = ByteBuffer.allocateDirect(SIZE * SIZE * 4).order(ByteOrder.nativeOrder())
            val read = CountDownLatch(1)
            onMain {
                scene.skybox = skyboxes[index]
                renderer.renderStandaloneView(view)
                renderer.readPixels(
                    target, 0, 0, SIZE, SIZE,
                    Texture.PixelBufferDescriptor(
                        pixels, Texture.Format.RGBA, Texture.Type.UBYTE,
                        1, 0, 0, 0, Executor { it.run() }, Runnable { read.countDown() }
                    )
                )
            }
            // Off the main thread, so the backend can complete the readback (see RenderTestHarness).
            engine.flushAndWait()
            check(read.await(5, TimeUnit.SECONDS)) { "readPixels callback did not fire" }
            var sum = 0L
            for (i in 0 until SIZE * SIZE) {
                for (c in 0 until 3) sum += pixels.get(i * 4 + c).toInt() and 0xFF
            }
            return sum / (3.0 * SIZE * SIZE)
        }

        fun destroy() {
            scene.skybox = null
            // The first skybox belongs to the environment, which the loader destroys.
            skyboxes.drop(1).forEach { engine.destroySkybox(it) }
            engine.destroyView(view)
            engine.destroyRenderTarget(target)
            engine.destroyTexture(color)
            engine.destroyCameraComponent(cameraEntity)
            EntityManager.get().destroy(cameraEntity)
            engine.destroyScene(scene)
            engine.destroyRenderer(renderer)
            loader.destroy()
            engine.safeDestroy()
        }
    }

    /**
     * A 64×32 equirect, flat (not run-length encoded) Radiance file of one warm colour, about
     * (1.6, 1.2, 0.8) in linear light.
     */
    private fun flatHdr(): ByteBuffer {
        val width = 64
        val height = 32
        val header = "#?RADIANCE\nFORMAT=32-bit_rle_rgbe\n\n-Y $height +X $width\n"
            .toByteArray(Charsets.US_ASCII)
        val pixel = byteArrayOf(200.toByte(), 150.toByte(), 100.toByte(), 129.toByte())
        val bytes = header + ByteArray(width * height * pixel.size) { pixel[it % pixel.size] }
        return ByteBuffer.allocateDirect(bytes.size).apply {
            put(bytes)
            rewind()
        }
    }

    private fun onMain(block: () -> Unit) =
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
}

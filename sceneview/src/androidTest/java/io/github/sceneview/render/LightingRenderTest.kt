package io.github.sceneview.render

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.filament.Camera
import com.google.android.filament.LightManager
import com.google.android.filament.Skybox
import io.github.sceneview.geometries.Cube
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.math.Position
import io.github.sceneview.math.colorOf
import io.github.sceneview.node.CubeNode
import io.github.sceneview.node.LightNode
import io.github.sceneview.render.RenderTestHarness.Companion.colorsMatch
import org.junit.AfterClass
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Render tests verifying that lighting affects the rendered output.
 *
 * These tests compare renders with and without lights to ensure the
 * lighting pipeline is working correctly.
 *
 * **Threading:** A single [RenderTestHarness] is shared across all tests (created once
 * in [setupClass], destroyed in [teardownClass]) to avoid the rapid Engine create/destroy
 * cycles that crash SwiftShader on CI (#803). The scene is reset between tests.
 */
@RunWith(AndroidJUnit4::class)
class LightingRenderTest {

    companion object {
        private lateinit var harness: RenderTestHarness
        private lateinit var materialLoader: MaterialLoader

        @JvmStatic
        @BeforeClass
        fun setupClass() {
            harness = RenderTestHarness(width = 128, height = 128)
            harness.runOnMain {
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                materialLoader = MaterialLoader(harness.engine, context)
            }
        }

        @JvmStatic
        @AfterClass
        fun teardownClass() {
            harness.runOnMain { materialLoader.destroy() }
            harness.destroy()
        }
    }

    @Before
    fun resetScene() {
        // Run on a hardware-GPU runner; cleanly skip on the SwiftShader / Apple-Silicon
        // emulator where Filament's async readPixels callback never fires (#803, #912).
        RenderTestCapabilities.assumeGpuReadbackAvailable()
        harness.resetScene()
    }

    // ── Directional light changes the render output ─────────────────────────

    @Test
    fun directionalLight_changesRenderedOutput() {
        var bitmapNoLight: Bitmap? = null
        var bitmapWithLight: Bitmap? = null

        harness.runOnMain {
            // Black sky, no IBL — scene starts completely dark
            harness.scene.skybox = Skybox.Builder()
                .color(0f, 0f, 0f, 1f)
                .build(harness.engine)

            val camera = harness.engine.createCamera(harness.engine.entityManager.create())
            camera.setProjection(45.0, 1.0, 0.1, 100.0, Camera.Fov.VERTICAL)
            camera.lookAt(0.0, 0.0, 5.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0)
            // Disable auto-exposure so lighting differences are visible
            camera.setExposure(1.0f)
            harness.view.camera = camera

            val material = materialLoader.createColorInstance(colorOf(0.8f, 0.8f, 0.8f, 1f))
            val cube = CubeNode(
                engine = harness.engine,
                size = Cube.DEFAULT_SIZE,
                center = Position(0f, 0f, 0f),
                materialInstances = listOf(material)
            )
            harness.scene.addEntity(cube.entity)

            // Render WITHOUT light
            harness.renderFrames(5)
            bitmapNoLight = harness.capturePixels()

            // Add directional light
            val light = LightNode(
                engine = harness.engine,
                type = LightManager.Type.DIRECTIONAL
            ) {
                direction(0f, -1f, -1f)
                intensity(100_000f)
            }
            harness.scene.addEntity(light.entity)

            // Render WITH light
            harness.renderFrames(5)
            bitmapWithLight = harness.capturePixels()

            // Cleanup
            harness.scene.remove(cube.entity)
            harness.scene.remove(light.entity)
            cube.destroy()
            light.destroy()
        }

        // The lit scene should be brighter than the unlit scene
        val cx = 64
        val cy = 64
        val noLightBrightness = averageBrightness(bitmapNoLight!!, cx, cy, 15)
        val withLightBrightness = averageBrightness(bitmapWithLight!!, cx, cy, 15)

        assertTrue(
            "Scene with directional light should be brighter than without. " +
                    "No light avg=$noLightBrightness, With light avg=$withLightBrightness",
            withLightBrightness > noLightBrightness + 5
        )
    }

    // ── Point light produces localized illumination ─────────────────────────

    @Test
    fun pointLight_producesLocalisedIllumination() {
        var bitmap: Bitmap? = null

        harness.runOnMain {
            harness.scene.skybox = Skybox.Builder()
                .color(0f, 0f, 0f, 1f)
                .build(harness.engine)

            val camera = harness.engine.createCamera(harness.engine.entityManager.create())
            camera.setProjection(45.0, 1.0, 0.1, 100.0, Camera.Fov.VERTICAL)
            camera.lookAt(0.0, 0.0, 5.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0)
            camera.setExposure(1.0f)
            harness.view.camera = camera

            val material = materialLoader.createColorInstance(colorOf(1f, 1f, 1f, 1f))
            val cube = CubeNode(
                engine = harness.engine,
                size = Cube.DEFAULT_SIZE,
                center = Position(0f, 0f, 0f),
                materialInstances = listOf(material)
            )
            harness.scene.addEntity(cube.entity)

            // Point light close to the cube
            val light = LightNode(
                engine = harness.engine,
                type = LightManager.Type.POINT
            ) {
                position(0f, 0f, 3f)
                intensity(50_000f)
                falloff(10f)
            }
            harness.scene.addEntity(light.entity)

            harness.renderFrames(5)
            bitmap = harness.capturePixels()

            harness.scene.remove(cube.entity)
            harness.scene.remove(light.entity)
            cube.destroy()
            light.destroy()
        }

        val bmp = bitmap!!
        // Point light should illuminate the center of the cube
        val centerBrightness = averageBrightness(bmp, 64, 64, 10)
        assertTrue(
            "Point light should produce visible illumination at center. " +
                    "Center brightness=$centerBrightness",
            centerBrightness > 10
        )
    }

    private fun averageBrightness(bmp: Bitmap, cx: Int, cy: Int, radius: Int): Float {
        var total = 0L
        var count = 0
        for (x in (cx - radius) until (cx + radius)) {
            for (y in (cy - radius) until (cy + radius)) {
                if (x < 0 || x >= bmp.width || y < 0 || y >= bmp.height) continue
                val pixel = bmp.getPixel(x, y)
                total += Color.red(pixel) + Color.green(pixel) + Color.blue(pixel)
                count++
            }
        }
        return if (count > 0) total.toFloat() / (count * 3) else 0f
    }
}

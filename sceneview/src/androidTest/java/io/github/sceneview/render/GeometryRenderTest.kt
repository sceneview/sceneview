package io.github.sceneview.render

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.filament.Camera
import com.google.android.filament.LightManager
import com.google.android.filament.Skybox
import io.github.sceneview.geometries.Cube
import io.github.sceneview.geometries.Sphere
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.math.Direction
import io.github.sceneview.math.Position
import io.github.sceneview.math.Size
import io.github.sceneview.math.colorOf
import io.github.sceneview.node.CubeNode
import io.github.sceneview.node.LightNode
import io.github.sceneview.node.PlaneNode
import io.github.sceneview.node.SphereNode
import io.github.sceneview.render.RenderTestHarness.Companion.colorsMatch
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Render tests for geometry nodes (CubeNode, SphereNode, PlaneNode).
 *
 * These tests verify that procedural geometry actually appears in the rendered output.
 * A [MaterialLoader] is used to create coloured PBR materials, and a directional light
 * is added so PBR-lit meshes are actually visible (without a light, PBR materials render black).
 *
 * Camera exposure is set to 1.0 (unit-less) to avoid tone-mapping distortion.
 *
 * **Threading:** A single [RenderTestHarness] is shared across all tests (created once
 * in [setupClass], destroyed in [teardownClass]) to avoid the rapid Engine create/destroy
 * cycles that crash SwiftShader on CI (#803). The scene is reset between tests.
 *
 * Requires an Android device or emulator with GPU support (SwiftShader OK).
 */
@RunWith(AndroidJUnit4::class)
class GeometryRenderTest {

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

    private var light: LightNode? = null

    @Before
    fun setup() {
        // Run on a hardware-GPU runner; cleanly skip on the SwiftShader / Apple-Silicon
        // emulator where Filament's async readPixels callback never fires (#803, #912).
        RenderTestCapabilities.assumeGpuReadbackAvailable()
        harness.resetScene()
        harness.runOnMain {
            val l = LightNode(
                engine = harness.engine,
                type = LightManager.Type.DIRECTIONAL
            ) {
                direction(0f, -1f, -1f)
                intensity(100_000f)
            }
            light = l
            harness.scene.addEntity(l.entity)
        }
    }

    @After
    fun teardown() {
        harness.runOnMain {
            light?.let {
                harness.scene.remove(it.entity)
                it.destroy()
            }
        }
    }

    private fun createTestCamera(): com.google.android.filament.Camera {
        val camera = harness.engine.createCamera(harness.engine.entityManager.create())
        camera.setProjection(45.0, 1.0, 0.1, 100.0, Camera.Fov.VERTICAL)
        camera.lookAt(0.0, 0.0, 5.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0)
        camera.setExposure(1.0f)
        return camera
    }

    // ── CubeNode renders visible pixels ─────────────────────────────────────

    @Test
    fun cubeNode_rendersNonBlackPixels() {
        var bitmap: Bitmap? = null
        harness.runOnMain {
            harness.scene.skybox = Skybox.Builder()
                .color(0f, 0f, 0f, 1f)
                .build(harness.engine)
            harness.view.camera = createTestCamera()

            val redMaterial = materialLoader.createColorInstance(colorOf(1f, 0f, 0f, 1f))
            val cubeNode = CubeNode(
                engine = harness.engine,
                size = Cube.DEFAULT_SIZE,
                center = Position(0f, 0f, 0f),
                materialInstances = listOf(redMaterial)
            )
            harness.scene.addEntity(cubeNode.entity)

            harness.renderFrames(5)
            bitmap = harness.capturePixels()

            harness.scene.remove(cubeNode.entity)
            cubeNode.destroy()
        }

        val bmp = bitmap!!
        val cx = bmp.width / 2
        val cy = bmp.height / 2
        var nonBlackPixels = 0
        val region = 20
        for (x in (cx - region) until (cx + region)) {
            for (y in (cy - region) until (cy + region)) {
                val pixel = bmp.getPixel(x, y)
                if (Color.red(pixel) > 10 || Color.green(pixel) > 10 || Color.blue(pixel) > 10) {
                    nonBlackPixels++
                }
            }
        }
        assertTrue(
            "Cube should render visible pixels at center. Found $nonBlackPixels non-black pixels",
            nonBlackPixels > 50
        )
    }

    // ── Different material colors produce different renders ──────────────────

    @Test
    fun cubeNode_differentColors_produceDifferentRenders() {
        var bitmapRed: Bitmap? = null
        var bitmapBlue: Bitmap? = null

        harness.runOnMain {
            harness.scene.skybox = Skybox.Builder()
                .color(0f, 0f, 0f, 1f)
                .build(harness.engine)
            harness.view.camera = createTestCamera()

            // Red cube
            val redMaterial = materialLoader.createColorInstance(colorOf(1f, 0f, 0f, 1f))
            val redCube = CubeNode(
                engine = harness.engine,
                size = Cube.DEFAULT_SIZE,
                center = Position(0f, 0f, 0f),
                materialInstances = listOf(redMaterial)
            )
            harness.scene.addEntity(redCube.entity)
            harness.renderFrames(5)
            bitmapRed = harness.capturePixels()
            harness.scene.remove(redCube.entity)
            redCube.destroy()

            // Blue cube
            val blueMaterial = materialLoader.createColorInstance(colorOf(0f, 0f, 1f, 1f))
            val blueCube = CubeNode(
                engine = harness.engine,
                size = Cube.DEFAULT_SIZE,
                center = Position(0f, 0f, 0f),
                materialInstances = listOf(blueMaterial)
            )
            harness.scene.addEntity(blueCube.entity)
            harness.renderFrames(5)
            bitmapBlue = harness.capturePixels()
            harness.scene.remove(blueCube.entity)
            blueCube.destroy()
        }

        assertNotNull("Red bitmap should have been captured", bitmapRed)
        assertNotNull("Blue bitmap should have been captured", bitmapBlue)
        val cx = bitmapRed!!.width / 2
        val cy = bitmapRed!!.height / 2
        val redCenter = bitmapRed!!.getPixel(cx, cy)
        val blueCenter = bitmapBlue!!.getPixel(cx, cy)

        assertTrue(
            "Red and blue cubes should produce different center pixels. " +
                    "Red=${colorStr(redCenter)}, Blue=${colorStr(blueCenter)}",
            !colorsMatch(redCenter, blueCenter, tolerance = 20)
        )
    }

    // ── SphereNode renders visible pixels ──────────────────────────────────

    @Test
    fun sphereNode_rendersNonBlackPixels() {
        var bitmap: Bitmap? = null
        harness.runOnMain {
            harness.scene.skybox = Skybox.Builder()
                .color(0f, 0f, 0f, 1f)
                .build(harness.engine)
            harness.view.camera = createTestCamera()

            val greenMaterial = materialLoader.createColorInstance(colorOf(0f, 1f, 0f, 1f))
            val sphereNode = SphereNode(
                engine = harness.engine,
                radius = Sphere.DEFAULT_RADIUS,
                center = Position(0f, 0f, 0f),
                materialInstances = listOf(greenMaterial)
            )
            harness.scene.addEntity(sphereNode.entity)

            harness.renderFrames(5)
            bitmap = harness.capturePixels()

            harness.scene.remove(sphereNode.entity)
            sphereNode.destroy()
        }

        val bmp = bitmap!!
        val cx = bmp.width / 2
        val cy = bmp.height / 2
        var nonBlackPixels = 0
        val region = 15
        for (x in (cx - region) until (cx + region)) {
            for (y in (cy - region) until (cy + region)) {
                val pixel = bmp.getPixel(x, y)
                if (Color.red(pixel) > 10 || Color.green(pixel) > 10 || Color.blue(pixel) > 10) {
                    nonBlackPixels++
                }
            }
        }
        assertTrue(
            "Sphere should render visible pixels at center. Found $nonBlackPixels non-black pixels",
            nonBlackPixels > 30
        )
    }

    // ── PlaneNode renders visible pixels ────────────────────────────────────

    @Test
    fun planeNode_rendersNonBlackPixels() {
        var bitmap: Bitmap? = null
        harness.runOnMain {
            harness.scene.skybox = Skybox.Builder()
                .color(0f, 0f, 0f, 1f)
                .build(harness.engine)

            // Look down at the plane from above
            val camera = harness.engine.createCamera(harness.engine.entityManager.create())
            camera.setProjection(45.0, 1.0, 0.1, 100.0, Camera.Fov.VERTICAL)
            camera.lookAt(0.0, 5.0, 0.1, 0.0, 0.0, 0.0, 0.0, 0.0, -1.0)
            camera.setExposure(1.0f)
            harness.view.camera = camera

            val yellowMaterial = materialLoader.createColorInstance(colorOf(1f, 1f, 0f, 1f))
            val planeNode = PlaneNode(
                engine = harness.engine,
                size = Size(2f, 2f),
                center = Position(0f, 0f, 0f),
                normal = Direction(0f, 1f, 0f),
                materialInstances = listOf(yellowMaterial)
            )
            harness.scene.addEntity(planeNode.entity)

            harness.renderFrames(5)
            bitmap = harness.capturePixels()

            harness.scene.remove(planeNode.entity)
            planeNode.destroy()
        }

        val bmp = bitmap!!
        val cx = bmp.width / 2
        val cy = bmp.height / 2
        var nonBlackPixels = 0
        val region = 20
        for (x in (cx - region) until (cx + region)) {
            for (y in (cy - region) until (cy + region)) {
                val pixel = bmp.getPixel(x, y)
                if (Color.red(pixel) > 10 || Color.green(pixel) > 10 || Color.blue(pixel) > 10) {
                    nonBlackPixels++
                }
            }
        }
        assertTrue(
            "Plane should render visible pixels at center. Found $nonBlackPixels non-black pixels",
            nonBlackPixels > 30
        )
    }

    // ── Golden screenshot comparison ────────────────────────────────────────

    @Test
    @Ignore(
        "SwiftShader dithering/TAA is fundamentally non-deterministic across frames. " +
                "Even with generous tolerances (20/5%), the process crashes on CI when the " +
                "assertion fails, killing all remaining tests. The non-golden geometry tests " +
                "above provide sufficient coverage. Tracked in #803."
    )
    fun cubeNode_goldenComparison_selfConsistent() {
        var bitmap1: Bitmap? = null
        var bitmap2: Bitmap? = null

        harness.runOnMain {
            harness.scene.skybox = Skybox.Builder()
                .color(0.2f, 0.2f, 0.2f, 1f)
                .build(harness.engine)
            harness.view.camera = createTestCamera()

            val material = materialLoader.createColorInstance(colorOf(0.8f, 0.2f, 0.1f, 1f))
            val cubeNode = CubeNode(
                engine = harness.engine,
                size = Cube.DEFAULT_SIZE,
                center = Position(0f, 0f, 0f),
                materialInstances = listOf(material)
            )
            harness.scene.addEntity(cubeNode.entity)

            // First render — extra frames to let TAA/dithering converge on SwiftShader
            harness.renderFrames(10)
            bitmap1 = harness.capturePixels()

            // Second render of exact same scene
            harness.renderFrames(10)
            bitmap2 = harness.capturePixels()

            harness.scene.remove(cubeNode.entity)
            cubeNode.destroy()
        }

        // SwiftShader uses non-deterministic dithering/TAA that produces visible
        // per-frame variance. Tolerances must be generous to avoid flaky CI failures.
        // Real GPU emulators would allow tighter thresholds.
        val comparator = GoldenImageComparator(maxChannelDiff = 20, maxDiffPixelsPercent = 5.0f)
        val result = comparator.compare(bitmap1!!, bitmap2!!)
        assertTrue(
            "Same scene rendered twice should produce identical output: ${result.message}",
            result.passed
        )
    }

    private fun colorStr(c: Int) =
        "ARGB(${Color.alpha(c)},${Color.red(c)},${Color.green(c)},${Color.blue(c)})"
}

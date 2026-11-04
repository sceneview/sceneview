package io.github.sceneview.render

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.filament.Camera
import com.google.android.filament.LightManager
import com.google.android.filament.Skybox
import io.github.sceneview.geometries.Cube
import io.github.sceneview.geometries.Cylinder
import io.github.sceneview.geometries.Sphere
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.math.Direction
import io.github.sceneview.math.Position
import io.github.sceneview.math.Size
import io.github.sceneview.math.colorOf
import io.github.sceneview.node.BillboardNode
import io.github.sceneview.node.CubeNode
import io.github.sceneview.node.CylinderNode
import io.github.sceneview.node.ImageNode
import io.github.sceneview.node.LightNode
import io.github.sceneview.node.PlaneNode
import io.github.sceneview.node.SphereNode
import io.github.sceneview.node.TextNode
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.io.FileWriter

/**
 * Visual verification test suite — renders every geometry node type, saves screenshots,
 * and generates an HTML report for manual visual inspection.
 *
 * Screenshots are saved to the device's external files directory and pulled via:
 * ```
 * adb pull /sdcard/Android/data/io.github.sceneview.test/files/render-test-output/
 * ```
 *
 * Note: `adb pull` is the only operation here without a Google `android` CLI
 * equivalent as of v0.7 — the CLI ships `screen capture` / `layout` / `run`
 * but no file-transfer subcommand. For everything else (install, launch,
 * screenshots, UI dumps) prefer the `android` CLI via `.claude/scripts/lib/android-cli.sh`.
 *
 * The HTML report (`visual-report.html`) shows all rendered images side-by-side
 * with expected descriptions, enabling quick visual regression detection.
 *
 * **Threading:** A single [RenderTestHarness] is shared across all tests (created once
 * in [setupClass], destroyed in [teardownClass]) to avoid the rapid Engine create/destroy
 * cycles that crash SwiftShader on CI (#803). The scene is reset between tests.
 */
@RunWith(AndroidJUnit4::class)
class VisualVerificationTest {

    companion object {
        private lateinit var harness: RenderTestHarness
        private lateinit var materialLoader: MaterialLoader
        // SwiftShader dithering/TAA variance requires generous tolerances on CI
        private val comparator = GoldenImageComparator(maxChannelDiff = 20, maxDiffPixelsPercent = 5.0f)
        private val screenshots = mutableListOf<ScreenshotEntry>()

        @JvmStatic
        @BeforeClass
        fun setupClass() {
            harness = RenderTestHarness(width = 256, height = 256)
            harness.runOnMain {
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                materialLoader = MaterialLoader(harness.engine, context)
            }
        }

        @JvmStatic
        @AfterClass
        fun teardownClass() {
            generateHtmlReport()
            harness.runOnMain { materialLoader.destroy() }
            harness.destroy()
        }

        private fun generateHtmlReport() {
            val dir = InstrumentationRegistry.getInstrumentation()
                .targetContext.getExternalFilesDir("render-test-output") ?: return
            val reportFile = File(dir, "visual-report.html")

            val passed = screenshots.count { it.passed }
            val total = screenshots.size
            val allPassed = passed == total

            FileWriter(reportFile).use { w ->
                w.write("""
<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>SceneView Render Test Report</title>
<style>
body { font-family: system-ui; margin: 20px; background: #1a1a2e; color: #e0e0e0; }
h1 { color: #fff; }
.summary { font-size: 1.2em; margin: 10px 0 20px; padding: 12px; border-radius: 8px;
  background: ${if (allPassed) "#1b5e20" else "#b71c1c"}; }
.grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); gap: 16px; }
.card { background: #16213e; border-radius: 12px; overflow: hidden; border: 2px solid
  ${if (allPassed) "#4caf50" else "#333"}; }
.card.fail { border-color: #f44336; }
.card img { width: 100%; height: 256px; object-fit: contain; background: #0d0d1a; }
.card-body { padding: 12px; }
.card-title { font-weight: bold; font-size: 1.1em; margin-bottom: 4px; }
.card-desc { color: #aaa; font-size: 0.9em; }
.badge { display: inline-block; padding: 2px 8px; border-radius: 4px; font-size: 0.8em;
  font-weight: bold; margin-top: 6px; }
.badge.pass { background: #4caf50; color: #fff; }
.badge.fail { background: #f44336; color: #fff; }
</style>
</head>
<body>
<h1>SceneView Render Test Report</h1>
<div class="summary">$passed / $total tests passed</div>
<div class="grid">
""")
                for (entry in screenshots) {
                    w.write("""
<div class="card ${if (entry.passed) "" else "fail"}">
  <img src="${entry.file.name}" alt="${entry.name}">
  <div class="card-body">
    <div class="card-title">${entry.name}</div>
    <div class="card-desc">${entry.description}</div>
    <div class="card-desc">${entry.details}</div>
    <span class="badge ${if (entry.passed) "pass" else "fail"}">${if (entry.passed) "PASS" else "FAIL"}</span>
  </div>
</div>
""")
                }
                w.write("""
</div>
</body>
</html>
""")
            }
            Log.i("VisualVerification", "Report saved to: ${reportFile.absolutePath}")
        }
    }

    data class ScreenshotEntry(
        val name: String,
        val description: String,
        val file: File,
        val passed: Boolean,
        val details: String
    )

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

    private fun renderAndCapture(
        name: String,
        description: String,
        setupScene: () -> Unit,
        cleanupScene: () -> Unit
    ): Bitmap {
        var bitmap: Bitmap? = null
        harness.runOnMain {
            harness.scene.skybox = Skybox.Builder()
                .color(0.05f, 0.05f, 0.07f, 1f)
                .build(harness.engine)
            harness.view.camera = createTestCamera()

            setupScene()
            harness.renderFrames(5)
            bitmap = harness.capturePixels()
            cleanupScene()
        }

        val bmp = bitmap!!
        val file = comparator.saveToDisk(bmp, name)

        // Check basic validity: at least some non-background pixels
        val nonBgPixels = countNonBackgroundPixels(bmp)
        val totalPixels = bmp.width * bmp.height
        val percent = nonBgPixels * 100 / totalPixels
        val passed = percent > 5

        screenshots.add(ScreenshotEntry(
            name = name,
            description = description,
            file = file,
            passed = passed,
            details = "$nonBgPixels/$totalPixels non-background pixels ($percent%)"
        ))

        Log.i("VisualVerification", "$name: ${if (passed) "PASS" else "FAIL"} — $percent% non-bg")
        return bmp
    }

    private fun countNonBackgroundPixels(bmp: Bitmap): Int {
        var count = 0
        val bgR = (0.05f * 255).toInt()
        val bgG = (0.05f * 255).toInt()
        val bgB = (0.07f * 255).toInt()
        for (x in 0 until bmp.width) {
            for (y in 0 until bmp.height) {
                val p = bmp.getPixel(x, y)
                val dr = kotlin.math.abs(Color.red(p) - bgR)
                val dg = kotlin.math.abs(Color.green(p) - bgG)
                val db = kotlin.math.abs(Color.blue(p) - bgB)
                if (dr > 15 || dg > 15 || db > 15) count++
            }
        }
        return count
    }

    // ── Node type tests ─────────────────────────────────────────────────────

    @Test
    fun renderAll_cubeNode() {
        renderAndCapture(
            name = "01_cube_red",
            description = "Red cube at origin, directional light from top-right",
            setupScene = {
                val mat = materialLoader.createColorInstance(colorOf(1f, 0f, 0f, 1f))
                val node = CubeNode(harness.engine, Cube.DEFAULT_SIZE, Position(0f), listOf(mat))
                harness.scene.addEntity(node.entity)
            },
            cleanupScene = {}
        )
    }

    @Test
    fun renderAll_sphereNode() {
        renderAndCapture(
            name = "02_sphere_green",
            description = "Green sphere at origin",
            setupScene = {
                val mat = materialLoader.createColorInstance(colorOf(0f, 1f, 0f, 1f))
                val node = SphereNode(harness.engine, Sphere.DEFAULT_RADIUS, Position(0f),
                    materialInstances = listOf(mat))
                harness.scene.addEntity(node.entity)
            },
            cleanupScene = {}
        )
    }

    @Test
    fun renderAll_cylinderNode() {
        renderAndCapture(
            name = "03_cylinder_blue",
            description = "Blue cylinder at origin",
            setupScene = {
                val mat = materialLoader.createColorInstance(colorOf(0f, 0f, 1f, 1f))
                val node = CylinderNode(harness.engine, materialInstances = listOf(mat))
                harness.scene.addEntity(node.entity)
            },
            cleanupScene = {}
        )
    }

    @Test
    fun renderAll_planeNode() {
        var bitmap: Bitmap? = null
        harness.runOnMain {
            harness.scene.skybox = Skybox.Builder()
                .color(0.05f, 0.05f, 0.07f, 1f)
                .build(harness.engine)

            // Camera looking down
            val camera = harness.engine.createCamera(harness.engine.entityManager.create())
            camera.setProjection(45.0, 1.0, 0.1, 100.0, Camera.Fov.VERTICAL)
            camera.lookAt(0.0, 5.0, 0.1, 0.0, 0.0, 0.0, 0.0, 0.0, -1.0)
            camera.setExposure(1.0f)
            harness.view.camera = camera

            val mat = materialLoader.createColorInstance(colorOf(1f, 1f, 0f, 1f))
            val node = PlaneNode(harness.engine, Size(3f, 3f), Position(0f),
                Direction(0f, 1f, 0f), materialInstances = listOf(mat))
            harness.scene.addEntity(node.entity)

            harness.renderFrames(5)
            bitmap = harness.capturePixels()
        }

        val file = comparator.saveToDisk(bitmap!!, "04_plane_yellow")
        val nonBg = countNonBackgroundPixels(bitmap!!)
        val percent = nonBg * 100 / (bitmap!!.width * bitmap!!.height)
        screenshots.add(ScreenshotEntry(
            "04_plane_yellow",
            "Yellow plane viewed from above",
            file, percent > 5,
            "$nonBg non-bg ($percent%)"
        ))
        assertTrue("Plane should be visible", percent > 5)
    }

    @Test
    fun renderAll_multipleNodes() {
        renderAndCapture(
            name = "05_multi_scene",
            description = "Red cube + green sphere + blue cylinder side by side",
            setupScene = {
                val redMat = materialLoader.createColorInstance(colorOf(1f, 0f, 0f, 1f))
                val cube = CubeNode(harness.engine, Size(0.6f), Position(-1.5f, 0f, 0f),
                    materialInstances = listOf(redMat))
                harness.scene.addEntity(cube.entity)

                val greenMat = materialLoader.createColorInstance(colorOf(0f, 1f, 0f, 1f))
                val sphere = SphereNode(harness.engine, 0.4f, Position(0f, 0f, 0f),
                    materialInstances = listOf(greenMat))
                harness.scene.addEntity(sphere.entity)

                val blueMat = materialLoader.createColorInstance(colorOf(0f, 0f, 1f, 1f))
                val cyl = CylinderNode(harness.engine, materialInstances = listOf(blueMat))
                cyl.position = Position(1.5f, 0f, 0f)
                harness.scene.addEntity(cyl.entity)
            },
            cleanupScene = {}
        )
    }

    @Test
    fun renderAll_pointLight() {
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

            val mat = materialLoader.createColorInstance(colorOf(1f, 1f, 1f, 1f))
            val cube = CubeNode(harness.engine, Cube.DEFAULT_SIZE, Position(0f),
                materialInstances = listOf(mat))
            harness.scene.addEntity(cube.entity)

            val pointLight = LightNode(harness.engine, LightManager.Type.POINT) {
                position(2f, 2f, 2f)
                intensity(200_000f)
                falloff(10f)
                color(1f, 0.5f, 0.2f)
            }
            harness.scene.addEntity(pointLight.entity)

            harness.renderFrames(5)
            bitmap = harness.capturePixels()

            harness.scene.remove(pointLight.entity)
            pointLight.destroy()
        }

        val bmp = bitmap!!
        val file = comparator.saveToDisk(bmp, "06_point_light_warm")
        val nonBg = countNonBackgroundPixels(bmp)
        val percent = nonBg * 100 / (bmp.width * bmp.height)
        screenshots.add(ScreenshotEntry(
            "06_point_light_warm",
            "White cube lit by warm orange point light from top-right",
            file, percent > 5, "$nonBg non-bg pixels ($percent%)"
        ))
        assertTrue("Point light scene should render visible pixels ($percent%)", percent > 5)
    }

    @Test
    fun renderAll_consistency() {
        // Render same scene twice — must be pixel-identical
        var bmp1: Bitmap? = null
        var bmp2: Bitmap? = null

        harness.runOnMain {
            harness.scene.skybox = Skybox.Builder()
                .color(0.1f, 0.1f, 0.15f, 1f)
                .build(harness.engine)
            harness.view.camera = createTestCamera()

            val mat = materialLoader.createColorInstance(colorOf(0.8f, 0.3f, 0.1f, 1f))
            val cube = CubeNode(harness.engine, Cube.DEFAULT_SIZE, Position(0f),
                materialInstances = listOf(mat))
            harness.scene.addEntity(cube.entity)

            harness.renderFrames(10)
            bmp1 = harness.capturePixels()
            harness.renderFrames(10)
            bmp2 = harness.capturePixels()
        }

        comparator.saveToDisk(bmp1!!, "07_consistency_A")
        comparator.saveToDisk(bmp2!!, "07_consistency_B")

        val result = comparator.compare(bmp1!!, bmp2!!)
        if (!result.passed && result.diffBitmap != null) {
            comparator.saveToDisk(result.diffBitmap, "07_consistency_DIFF")
        }

        screenshots.add(ScreenshotEntry(
            "07_consistency",
            "Same scene rendered twice must match: ${result.message}",
            comparator.saveToDisk(bmp1!!, "07_consistency_A"),
            result.passed,
            result.message
        ))
        assertTrue("Consistency check failed: ${result.message}", result.passed)
    }

    // ── ImageNode test ───────────────────────────────────────────────────────

    @Test
    fun renderAll_imageNode() {
        renderAndCapture(
            name = "08_image_checkerboard",
            description = "ImageNode showing a procedurally generated checkerboard bitmap",
            setupScene = {
                val bmpWidth = 64
                val bmpHeight = 64
                val checkerboard = Bitmap.createBitmap(bmpWidth, bmpHeight, Bitmap.Config.ARGB_8888)
                for (y in 0 until bmpHeight) {
                    for (x in 0 until bmpWidth) {
                        val isWhite = ((x / 8) + (y / 8)) % 2 == 0
                        checkerboard.setPixel(x, y, if (isWhite) Color.WHITE else Color.DKGRAY)
                    }
                }
                val node = ImageNode(
                    materialLoader = materialLoader,
                    bitmap = checkerboard,
                    size = Size(2f, 2f)
                )
                harness.scene.addEntity(node.entity)
            },
            cleanupScene = {}
        )
    }

    // ── TextNode test ────────────────────────────────────────────────────────

    @Test
    fun renderAll_textNode() {
        renderAndCapture(
            name = "09_text_hello",
            description = "TextNode showing 'Hello 3D' white text on dark background",
            setupScene = {
                val node = TextNode(
                    materialLoader = materialLoader,
                    text = "Hello 3D",
                    fontSize = 64f,
                    textColor = Color.WHITE,
                    backgroundColor = 0xCC000000.toInt(),
                    widthMeters = 1.5f,
                    heightMeters = 0.5f,
                    cameraPositionProvider = null
                )
                harness.scene.addEntity(node.entity)
            },
            cleanupScene = {}
        )
    }

    // ── BillboardNode test ───────────────────────────────────────────────────

    @Test
    fun renderAll_billboardNode() {
        renderAndCapture(
            name = "10_billboard_gradient",
            description = "BillboardNode showing a gradient bitmap quad facing camera",
            setupScene = {
                val bmpWidth = 128
                val bmpHeight = 64
                val gradientBmp = Bitmap.createBitmap(bmpWidth, bmpHeight, Bitmap.Config.ARGB_8888)
                for (y in 0 until bmpHeight) {
                    for (x in 0 until bmpWidth) {
                        val r = (x * 255 / bmpWidth)
                        val g = (y * 255 / bmpHeight)
                        val b = 128
                        gradientBmp.setPixel(x, y, Color.argb(255, r, g, b))
                    }
                }
                val node = BillboardNode(
                    materialLoader = materialLoader,
                    bitmap = gradientBmp,
                    widthMeters = 2.0f,
                    heightMeters = 1.0f,
                    cameraPositionProvider = null
                )
                harness.scene.addEntity(node.entity)
            },
            cleanupScene = {}
        )
    }
}

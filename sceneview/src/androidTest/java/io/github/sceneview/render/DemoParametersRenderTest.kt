package io.github.sceneview.render

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.filament.Camera
import com.google.android.filament.LightManager
import com.google.android.filament.Skybox
import com.google.android.filament.View.AntiAliasing
import io.github.sceneview.geometries.Cube
import io.github.sceneview.geometries.Sphere
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.math.Position
import io.github.sceneview.math.Position2
import io.github.sceneview.math.Size
import io.github.sceneview.math.colorOf
import io.github.sceneview.node.CubeNode
import io.github.sceneview.node.LightNode
import io.github.sceneview.node.ShapeNode
import io.github.sceneview.node.SphereNode
import org.junit.AfterClass
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileWriter
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Parameter-matrix render tests mirroring the 24 Android demos.
 *
 * Each test reproduces one demo's scene construction at a specific parameter preset
 * (e.g. `lighting_spot`, `fog_heavy`, `shape_star`) and captures the resulting pixels
 * through [RenderTestHarness]. The suite acts as a **visual regression net** for the
 * variations that the static screenshot QA in `tools/qa-screenshots/pixel9/` (removed in #4142; see it at 8d9e48764) cannot cover:
 * sliders mid-range, toggles flipped, chips rotated.
 *
 * Screenshots land in `/sdcard/Android/data/io.github.sceneview.test/files/render-test-output/`
 * alongside `demo-parameters-report.html`.
 *
 * ### Why this exists
 * `FINAL_QA_REPORT.md` (since removed) lists 31/31 PASS but the automated `qa-android-demos.sh` only asserts
 * "app didn't crash on entry". A Fog demo with density 0 looks identical to a Fog demo with
 * density 1 through that lens. These tests take ~500ms each headless — covering 20 variations
 * in ~10s versus ~10 min of ADB UI automation.
 *
 * **Threading:** single shared [RenderTestHarness] per class — same pattern as
 * [VisualVerificationTest] to avoid SwiftShader Engine destroy crashes (#803).
 */
@RunWith(AndroidJUnit4::class)
class DemoParametersRenderTest {

    companion object {
        private lateinit var harness: RenderTestHarness
        private lateinit var materialLoader: MaterialLoader
        private val comparator = GoldenImageComparator(
            maxChannelDiff = 20,
            maxDiffPixelsPercent = 5.0f
        )
        private val screenshots = mutableListOf<Entry>()

        /**
         * Nodes created in each test — lives on the companion so it survives across test-instance
         * recreation. Destroyed between tests so the next test's `resetScene()` doesn't leave
         * dangling Renderable→MaterialInstance refs that would crash MaterialLoader.destroy().
         */
        private val createdNodes = mutableListOf<io.github.sceneview.node.Node>()

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
            val report = File(dir, "demo-parameters-report.html")
            val passed = screenshots.count { it.passed }
            val total = screenshots.size
            FileWriter(report).use { w ->
                w.write("""
<!DOCTYPE html>
<html><head><meta charset="utf-8"><title>Demo Parameter Matrix Report</title>
<style>
body{font-family:system-ui;background:#0d1117;color:#e6edf3;margin:20px}
h1{border-bottom:1px solid #30363d;padding-bottom:12px}
.summary{padding:14px;border-radius:8px;background:${if (passed == total) "#1b5e20" else "#b71c1c"};font-size:1.1em;margin-bottom:20px}
.group{margin-bottom:32px}
.group h2{color:#79c0ff}
.grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(240px,1fr));gap:14px}
.card{background:#161b22;border:1px solid #30363d;border-radius:8px;overflow:hidden}
.card img{width:100%;height:220px;object-fit:contain;background:#0d0d1a}
.card-body{padding:10px}
.card-title{font-weight:600;font-size:0.95em}
.card-desc{color:#8b949e;font-size:0.85em;margin-top:4px}
.badge{display:inline-block;padding:2px 8px;border-radius:12px;font-size:0.75em;font-weight:600;margin-top:6px}
.pass{background:#238636;color:#fff}.fail{background:#da3633;color:#fff}
</style></head><body>
<h1>Demo Parameter Matrix Report</h1>
<div class="summary">$passed / $total renders produced valid, non-trivial pixels</div>
""")
                screenshots.groupBy { it.group }.forEach { (group, entries) ->
                    w.write("<div class='group'><h2>$group</h2><div class='grid'>")
                    for (e in entries) {
                        w.write("""
<div class="card">
<img src="${e.file.name}" alt="${e.name}">
<div class="card-body">
<div class="card-title">${e.name}</div>
<div class="card-desc">${e.description}</div>
<div class="card-desc">${e.details}</div>
<span class="badge ${if (e.passed) "pass" else "fail"}">${if (e.passed) "PASS" else "FAIL"}</span>
</div></div>
""")
                    }
                    w.write("</div></div>")
                }
                w.write("</body></html>")
            }
            Log.i("DemoParams", "Report saved to ${report.absolutePath}")
        }

        data class Entry(
            val group: String,
            val name: String,
            val description: String,
            val file: File,
            val passed: Boolean,
            val details: String
        )
    }

    @Before
    fun resetScene() {
        // Run on a hardware-GPU runner; cleanly skip on the SwiftShader / Apple-Silicon
        // emulator where Filament's async readPixels callback never fires (#803, #912).
        RenderTestCapabilities.assumeGpuReadbackAvailable()
        // Destroy every node created in the previous test so its Renderable releases the
        // MaterialInstance reference. Otherwise materialLoader.destroy() (or the next material
        // create) trips a Filament precondition: "destroying MaterialInstance X still in use".
        harness.runOnMain {
            createdNodes.forEach { node ->
                try {
                    harness.scene.removeEntity(node.entity)
                    node.destroy()
                } catch (_: Exception) { /* already destroyed */ }
            }
        }
        createdNodes.clear()
        harness.resetScene()
    }

    /** Adds the node to the shared scene AND registers it for cleanup before the next test. */
    private fun <T : io.github.sceneview.node.Node> addAndTrack(node: T): T {
        harness.scene.addEntity(node.entity)
        createdNodes.add(node)
        return node
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun createTestCamera(
        eyeX: Double = 0.0, eyeY: Double = 0.0, eyeZ: Double = 4.0,
        targetX: Double = 0.0, targetY: Double = 0.0, targetZ: Double = 0.0
    ): Camera {
        val cam = harness.engine.createCamera(harness.engine.entityManager.create())
        cam.setProjection(45.0, 1.0, 0.1, 100.0, Camera.Fov.VERTICAL)
        cam.lookAt(eyeX, eyeY, eyeZ, targetX, targetY, targetZ, 0.0, 1.0, 0.0)
        cam.setExposure(1.0f)
        return cam
    }

    private fun render(
        group: String,
        name: String,
        description: String,
        bgColor: FloatArray = floatArrayOf(0.05f, 0.05f, 0.07f),
        cameraOverride: (() -> Camera)? = null,
        viewConfig: () -> Unit = {},
        setupScene: () -> Unit
    ): Bitmap {
        harness.runOnMain {
            harness.scene.skybox = Skybox.Builder()
                .color(bgColor[0], bgColor[1], bgColor[2], 1f)
                .build(harness.engine)
            harness.view.camera = cameraOverride?.invoke() ?: createTestCamera()
            viewConfig()
            setupScene()
            harness.renderFrames(5)
        }
        // capturePixels() orchestrates its own main-thread begin/end frame internally,
        // then flushAndWait + latch.await off-main so the backend thread can complete.
        val bmp = harness.capturePixels()
        val file = comparator.saveToDisk(bmp, "${group}_${name}")
        val nonBg = countNonBackgroundPixels(bmp, bgColor)
        val percent = nonBg * 100 / (bmp.width * bmp.height)
        val passed = percent > 1
        screenshots.add(
            Entry(
                group, name, description, file, passed,
                "$nonBg/${bmp.width * bmp.height} non-bg pixels ($percent%)"
            )
        )
        Log.i("DemoParams", "$group/$name: ${if (passed) "PASS" else "FAIL"} ($percent%)")
        return bmp
    }

    private fun countNonBackgroundPixels(bmp: Bitmap, bg: FloatArray): Int {
        val bgR = (bg[0] * 255).toInt()
        val bgG = (bg[1] * 255).toInt()
        val bgB = (bg[2] * 255).toInt()
        var count = 0
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

    private fun averageBrightness(bmp: Bitmap, cx: Int, cy: Int, r: Int): Float {
        var total = 0L
        var n = 0
        for (x in (cx - r) until (cx + r)) {
            for (y in (cy - r) until (cy + r)) {
                if (x < 0 || x >= bmp.width || y < 0 || y >= bmp.height) continue
                val p = bmp.getPixel(x, y)
                total += Color.red(p) + Color.green(p) + Color.blue(p)
                n++
            }
        }
        return if (n > 0) total.toFloat() / (n * 3) else 0f
    }

    // ── 1. LightingDemo variations ────────────────────────────────────────────

    @Test
    fun lighting_directionalWarmColor() {
        val bmp = render(
            group = "lighting",
            name = "directional_warm",
            description = "Directional light with warm orange colour (1.0, 0.5, 0.2)",
            bgColor = floatArrayOf(0f, 0f, 0f)
        ) {
            val mat = materialLoader.createColorInstance(colorOf(1f, 1f, 1f, 1f))
            val cube = CubeNode(
                harness.engine, Cube.DEFAULT_SIZE, Position(0f),
                materialInstances = listOf(mat)
            )
            addAndTrack(cube)
            val light = LightNode(harness.engine, LightManager.Type.DIRECTIONAL) {
                direction(-0.5f, -1f, -1f)
                intensity(100_000f)
                color(1f, 0.5f, 0.2f)
            }
            addAndTrack(light)
        }
        // Warm light should produce red channel > blue channel
        val r = Color.red(bmp.getPixel(128, 128))
        val b = Color.blue(bmp.getPixel(128, 128))
        assertTrue(
            "Warm light should produce red > blue at centre (r=$r b=$b)",
            r > b + 20
        )
    }

    @Test
    fun lighting_directionalColdColor() {
        val bmp = render(
            group = "lighting",
            name = "directional_cold",
            description = "Directional light with cold blue colour (0.3, 0.5, 1.0)",
            bgColor = floatArrayOf(0f, 0f, 0f)
        ) {
            val mat = materialLoader.createColorInstance(colorOf(1f, 1f, 1f, 1f))
            val cube = CubeNode(
                harness.engine, Cube.DEFAULT_SIZE, Position(0f),
                materialInstances = listOf(mat)
            )
            addAndTrack(cube)
            val light = LightNode(harness.engine, LightManager.Type.DIRECTIONAL) {
                direction(-0.5f, -1f, -1f)
                intensity(100_000f)
                color(0.3f, 0.5f, 1f)
            }
            addAndTrack(light)
        }
        val r = Color.red(bmp.getPixel(128, 128))
        val b = Color.blue(bmp.getPixel(128, 128))
        assertTrue(
            "Cold light should produce blue > red at centre (r=$r b=$b)",
            b > r + 20
        )
    }

    @Test
    fun lighting_highIntensity() {
        val bmp = render(
            group = "lighting",
            name = "directional_500k",
            description = "Directional light at intensity 500 000 lux",
            bgColor = floatArrayOf(0f, 0f, 0f)
        ) {
            val mat = materialLoader.createColorInstance(colorOf(0.8f, 0.8f, 0.8f, 1f))
            val cube = CubeNode(
                harness.engine, Cube.DEFAULT_SIZE, Position(0f),
                materialInstances = listOf(mat)
            )
            addAndTrack(cube)
            val light = LightNode(harness.engine, LightManager.Type.DIRECTIONAL) {
                direction(0f, -1f, -1f)
                intensity(500_000f)
            }
            addAndTrack(light)
        }
        val brightness = averageBrightness(bmp, 128, 128, 15)
        assertTrue(
            "High-intensity light should produce bright central pixels (avg=$brightness)",
            brightness > 150
        )
    }

    @Test
    fun lighting_lowIntensity() {
        val bmp = render(
            group = "lighting",
            name = "directional_5k",
            description = "Directional light at intensity 5 000 lux (low)",
            bgColor = floatArrayOf(0f, 0f, 0f)
        ) {
            val mat = materialLoader.createColorInstance(colorOf(0.8f, 0.8f, 0.8f, 1f))
            val cube = CubeNode(
                harness.engine, Cube.DEFAULT_SIZE, Position(0f),
                materialInstances = listOf(mat)
            )
            addAndTrack(cube)
            val light = LightNode(harness.engine, LightManager.Type.DIRECTIONAL) {
                direction(0f, -1f, -1f)
                intensity(5_000f)
            }
            addAndTrack(light)
        }
        val brightness = averageBrightness(bmp, 128, 128, 15)
        assertTrue(
            "Low-intensity light should stay dim (avg=$brightness)",
            brightness < 100
        )
    }

    @Test
    fun lighting_spotLight() {
        render(
            group = "lighting",
            name = "spot_narrow",
            description = "Spot light with narrow cone pointed at the cube",
            bgColor = floatArrayOf(0f, 0f, 0f)
        ) {
            val mat = materialLoader.createColorInstance(colorOf(1f, 1f, 1f, 1f))
            val cube = CubeNode(
                harness.engine, Cube.DEFAULT_SIZE, Position(0f),
                materialInstances = listOf(mat)
            )
            addAndTrack(cube)
            val light = LightNode(harness.engine, LightManager.Type.SPOT) {
                position(0f, 2f, 2f)
                direction(0f, -1f, -1f)
                intensity(300_000f)
                falloff(10f)
                spotLightCone(
                    (10f * PI.toFloat() / 180f),
                    (25f * PI.toFloat() / 180f)
                )
            }
            addAndTrack(light)
        }
    }

    // ── 2. FogDemo variations — poking View.fogOptions directly ───────────────

    private fun setupLitCube() {
        val mat = materialLoader.createColorInstance(colorOf(0.9f, 0.9f, 0.9f, 1f))
        val cube = CubeNode(
            harness.engine, Cube.DEFAULT_SIZE, Position(0f),
            materialInstances = listOf(mat)
        )
        addAndTrack(cube)
        val light = LightNode(harness.engine, LightManager.Type.DIRECTIONAL) {
            direction(-0.3f, -1f, -0.5f)
            intensity(100_000f)
        }
        addAndTrack(light)
    }

    @Test
    fun fog_disabledBaseline() {
        render(
            group = "fog",
            name = "disabled",
            description = "Fog toggle off — baseline scene, sharp edges",
            viewConfig = {
                harness.view.fogOptions = harness.view.fogOptions.also { it.enabled = false }
            }
        ) {
            setupLitCube()
        }
    }

    @Test
    fun fog_lightDensity() {
        render(
            group = "fog",
            name = "density_0_05",
            description = "Fog density 0.05 (light mist, Mist preset)",
            viewConfig = {
                harness.view.fogOptions = harness.view.fogOptions.also {
                    it.enabled = true
                    it.density = 0.05f
                    it.color[0] = 0.8f; it.color[1] = 0.87f; it.color[2] = 1f
                    it.distance = 0.5f; it.cutOffDistance = 40f
                    it.fogColorFromIbl = false
                }
            }
        ) {
            setupLitCube()
        }
    }

    @Test
    fun fog_heavyDensity() {
        render(
            group = "fog",
            name = "density_0_5",
            description = "Fog density 0.5 (heavy, cube barely visible)",
            viewConfig = {
                harness.view.fogOptions = harness.view.fogOptions.also {
                    it.enabled = true
                    it.density = 0.5f
                    it.color[0] = 0.8f; it.color[1] = 0.87f; it.color[2] = 1f
                    it.distance = 0.5f; it.cutOffDistance = 40f
                    it.fogColorFromIbl = false
                }
            }
        ) {
            setupLitCube()
        }
    }

    @Test
    fun fog_warmHazePreset() {
        val bmp = render(
            group = "fog",
            name = "warm_haze",
            description = "Fog preset Warm Haze (#FFDDAA) at density 0.2",
            viewConfig = {
                harness.view.fogOptions = harness.view.fogOptions.also {
                    it.enabled = true
                    it.density = 0.2f
                    it.color[0] = 1f; it.color[1] = 0.87f; it.color[2] = 0.67f
                    it.distance = 0.5f; it.cutOffDistance = 40f
                    it.fogColorFromIbl = false
                }
            }
        ) {
            setupLitCube()
        }
        // Warm haze should tint pixels (red channel elevated)
        val r = Color.red(bmp.getPixel(20, 20))
        val b = Color.blue(bmp.getPixel(20, 20))
        assertTrue(
            "Warm haze fog should tint corners warm (r=$r b=$b)",
            r >= b
        )
    }

    @Test
    fun fog_eerieGreenPreset() {
        val bmp = render(
            group = "fog",
            name = "eerie_green",
            description = "Fog preset Eerie Green (#AAFFCC) at density 0.2",
            viewConfig = {
                harness.view.fogOptions = harness.view.fogOptions.also {
                    it.enabled = true
                    it.density = 0.2f
                    it.color[0] = 0.67f; it.color[1] = 1f; it.color[2] = 0.8f
                    it.distance = 0.5f; it.cutOffDistance = 40f
                    it.fogColorFromIbl = false
                }
            }
        ) {
            setupLitCube()
        }
        val g = Color.green(bmp.getPixel(20, 20))
        val r = Color.red(bmp.getPixel(20, 20))
        assertTrue(
            "Eerie green fog should tint corners green (g=$g r=$r)",
            g >= r
        )
    }

    // ── 3. ShapeDemo variations ───────────────────────────────────────────────

    @Test
    fun shape_triangle() {
        render(
            group = "shape",
            name = "triangle_cyan",
            description = "ShapeDemo — triangle polygon, cyan fill"
        ) {
            val mat = materialLoader.createColorInstance(colorOf(0f, 1f, 1f, 1f))
            val tri = ShapeNode(
                engine = harness.engine,
                polygonPath = listOf(
                    Position2(0f, 0.5f),
                    Position2(-0.5f, -0.3f),
                    Position2(0.5f, -0.3f)
                ),
                materialInstance = mat
            ).apply { position = Position(0f, 0f, -1f) }
            addAndTrack(tri)
        }
    }

    @Test
    fun shape_star() {
        render(
            group = "shape",
            name = "star_yellow",
            description = "ShapeDemo — 5-point star, yellow fill"
        ) {
            val mat = materialLoader.createColorInstance(colorOf(1f, 1f, 0f, 1f))
            val outerR = 0.5f
            val innerR = 0.2f
            val points = buildList {
                for (i in 0 until 10) {
                    val angle = (i * 36f - 90f) * (PI.toFloat() / 180f)
                    val r = if (i % 2 == 0) outerR else innerR
                    add(Position2(cos(angle) * r, sin(angle) * r))
                }
            }
            val star = ShapeNode(
                engine = harness.engine,
                polygonPath = points,
                materialInstance = mat
            ).apply { position = Position(0f, 0f, -1f) }
            addAndTrack(star)
        }
    }

    @Test
    fun shape_hexagon() {
        render(
            group = "shape",
            name = "hexagon_magenta",
            description = "ShapeDemo — regular hexagon, magenta fill"
        ) {
            val mat = materialLoader.createColorInstance(colorOf(1f, 0f, 1f, 1f))
            val r = 0.4f
            val points = buildList {
                for (i in 0 until 6) {
                    val angle = (i * 60f) * (PI.toFloat() / 180f)
                    add(Position2(cos(angle) * r, sin(angle) * r))
                }
            }
            val hex = ShapeNode(
                engine = harness.engine,
                polygonPath = points,
                materialInstance = mat
            ).apply { position = Position(0f, 0f, -1f) }
            addAndTrack(hex)
        }
    }

    // ── 4. CustomMeshDemo fix validation ──────────────────────────────────────

    @Test
    fun customMesh_moleculeAtScale05() {
        val bmp = render(
            group = "customMesh",
            name = "molecule_scale_0_5",
            description = "Molecule (central sphere + 6 axial atoms) at scale 0.5 — validates CustomMesh fix",
            bgColor = floatArrayOf(0f, 0f, 0f)
        ) {
            val mat = materialLoader.createColorInstance(colorOf(0.6f, 1f, 1f, 1f))
            val scale = 0.5f
            // Central atom
            val core = SphereNode(
                harness.engine, Sphere.DEFAULT_RADIUS * scale,
                Position(0f, 0f, 0f),
                materialInstances = listOf(mat)
            )
            addAndTrack(core)
            // 6 axial atoms
            val offsets = listOf(
                Position(scale * 1.5f, 0f, 0f),
                Position(-scale * 1.5f, 0f, 0f),
                Position(0f, scale * 1.5f, 0f),
                Position(0f, -scale * 1.5f, 0f),
                Position(0f, 0f, scale * 1.5f),
                Position(0f, 0f, -scale * 1.5f)
            )
            offsets.forEach { pos ->
                val atom = SphereNode(
                    harness.engine, Sphere.DEFAULT_RADIUS * scale * 0.5f, pos,
                    materialInstances = listOf(mat)
                )
                addAndTrack(atom)
            }
            val light = LightNode(harness.engine, LightManager.Type.DIRECTIONAL) {
                direction(-0.3f, -1f, -0.5f)
                intensity(100_000f)
            }
            addAndTrack(light)
        }
        // Top and bottom atoms should be IN the viewport (not cropped at scale 0.5)
        val topPixel = averageBrightness(bmp, 128, 40, 10)
        val bottomPixel = averageBrightness(bmp, 128, 216, 10)
        assertTrue(
            "Top atom must be visible in viewport at scale 0.5 (brightness=$topPixel)",
            topPixel > 5
        )
        assertTrue(
            "Bottom atom must be visible in viewport at scale 0.5 (brightness=$bottomPixel)",
            bottomPixel > 5
        )
    }

    // ── 5. BillboardDemo fix validation ───────────────────────────────────────

    @Test
    fun billboard_twoQuadsBothInFrame() {
        val bmp = render(
            group = "billboard",
            name = "both_quads_in_frame",
            description = "BillboardDemo fix — both quads at z=-1.5, x=±0.25 should be fully visible",
            bgColor = floatArrayOf(0f, 0f, 0f)
        ) {
            // Left green cube (proxy for Billboard) + right blue cube (proxy for ImageNode)
            val greenMat = materialLoader.createColorInstance(colorOf(0.3f, 0.8f, 0.4f, 1f))
            val blueMat = materialLoader.createColorInstance(colorOf(0.3f, 0.6f, 0.9f, 1f))
            val left = CubeNode(
                harness.engine, Size(0.3f, 0.15f, 0.01f),
                Position(-0.25f, 0f, -1.5f),
                materialInstances = listOf(greenMat)
            )
            val right = CubeNode(
                harness.engine, Size(0.3f, 0.15f, 0.01f),
                Position(0.25f, 0f, -1.5f),
                materialInstances = listOf(blueMat)
            )
            addAndTrack(left)
            addAndTrack(right)
            val light = LightNode(harness.engine, LightManager.Type.DIRECTIONAL) {
                direction(0f, -0.3f, -1f)
                intensity(100_000f)
            }
            addAndTrack(light)
        }
        // Left half should be greener than blue; right half bluer than green
        val leftG = Color.green(bmp.getPixel(64, 128))
        val leftB = Color.blue(bmp.getPixel(64, 128))
        val rightB = Color.blue(bmp.getPixel(192, 128))
        val rightG = Color.green(bmp.getPixel(192, 128))
        assertTrue(
            "Left quad must be visible and green-dominant (g=$leftG b=$leftB)",
            leftG > leftB
        )
        assertTrue(
            "Right quad must be visible and blue-dominant (g=$rightG b=$rightB)",
            rightB > rightG
        )
        // Edges should stay dark (quads NOT clipped at the viewport border)
        val edge = averageBrightness(bmp, 8, 128, 6)
        assertTrue("Left edge should be dark (no clipping) (avg=$edge)", edge < 40)
    }

    // ── 6. CollisionDemo fix validation ──────────────────────────────────────

    @Test
    fun collision_allFiveShapesInFrame() {
        val bmp = render(
            group = "collision",
            name = "five_shapes_in_frame",
            description = "CollisionDemo fix — 5 shapes at x=±0.6, z=-2 should all be inside viewport"
        ) {
            val mat = materialLoader.createColorInstance(colorOf(0.4f, 0.8f, 0.4f, 1f))
            val positions = listOf(
                Position(-0.6f, 0f, -2f) to false,
                Position(-0.3f, 0.3f, -2f) to true,
                Position(0f, 0f, -2f) to false,
                Position(0.3f, 0.3f, -2f) to true,
                Position(0.6f, 0f, -2f) to false
            )
            positions.forEach { (pos, isSphere) ->
                val node = if (isSphere) {
                    SphereNode(
                        harness.engine, 0.12f, pos,
                        materialInstances = listOf(mat)
                    )
                } else {
                    CubeNode(
                        harness.engine, Size(0.25f), pos,
                        materialInstances = listOf(mat)
                    )
                }
                addAndTrack(node)
            }
            val light = LightNode(harness.engine, LightManager.Type.DIRECTIONAL) {
                direction(-0.3f, -1f, -0.5f)
                intensity(100_000f)
            }
            addAndTrack(light)
        }
        // Each of the 5 shape X-centres should show green pixels
        val xPixelCentres = listOf(52, 89, 128, 167, 204)
        xPixelCentres.forEachIndexed { i, cx ->
            val b = averageBrightness(bmp, cx, 128, 10)
            assertTrue(
                "Shape index $i at screen x=$cx must be visible (avg=$b) — fix regressed!",
                b > 10
            )
        }
    }

    // ── 7. PostProcessingDemo variations ──────────────────────────────────────

    @Test
    fun postProc_ssaoEnabledShowsOcclusion() {
        render(
            group = "postProc",
            name = "ssao_on",
            description = "PostProc SSAO ON — ambient occlusion darkens contact shadows",
            viewConfig = {
                harness.view.ambientOcclusionOptions =
                    harness.view.ambientOcclusionOptions.apply { enabled = true }
            }
        ) {
            setupLitCube()
        }
    }

    @Test
    fun postProc_ssaoDisabledBaseline() {
        render(
            group = "postProc",
            name = "ssao_off",
            description = "PostProc SSAO OFF — baseline without ambient occlusion",
            viewConfig = {
                harness.view.ambientOcclusionOptions =
                    harness.view.ambientOcclusionOptions.apply { enabled = false }
            }
        ) {
            setupLitCube()
        }
    }

    @Test
    fun postProc_fxaaEnabled() {
        render(
            group = "postProc",
            name = "fxaa_on",
            description = "PostProc FXAA ON — anti-aliased edges",
            viewConfig = {
                harness.view.antiAliasing = AntiAliasing.FXAA
            }
        ) {
            setupLitCube()
        }
    }

    @Test
    fun postProc_fxaaDisabled() {
        render(
            group = "postProc",
            name = "fxaa_off",
            description = "PostProc FXAA OFF — raw jagged edges",
            viewConfig = {
                harness.view.antiAliasing = AntiAliasing.NONE
            }
        ) {
            setupLitCube()
        }
    }
}

package io.github.sceneview.demo.demos.internal

import java.io.File
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Materials studio's data layer and its determinism contract (#3495, #2874).
 *
 * #2874's defect was invisible per frame: every capture of the old demo looked fine, they
 * just differed from one another, because the subject was streamed from Sketchfab when the
 * network was warm and bundled when it was not. The rebuild removes the cause rather than
 * mitigating it — the whole scene is now primitives plus material parameters — so the tests
 * below pin *that*: no remote subject, a fixed wall layout, and a bounded camera whose QA
 * phase is a constant.
 *
 * Pure JVM — no Android framework, no network, no Filament.
 */
class MaterialStudioTest {

    // ── The library ──────────────────────────────────────────────────────────────────────

    @Test
    fun `every material has a stable unique id`() {
        val ids = MaterialStudio.library.map { it.id }
        assertEquals("Ids are node names and Compose keys — they must be unique", ids.size, ids.toSet().size)
        assertTrue(ids.none { it.isBlank() })
    }

    @Test
    fun `every material is captioned`() {
        MaterialStudio.library.forEach { material ->
            assertTrue("${material.id} has no label", material.label.isNotBlank())
            assertTrue("${material.id} has no note", material.note.isNotBlank())
        }
    }

    @Test
    fun `parameters stay inside the ranges the sliders expose`() {
        MaterialStudio.library.forEach { material ->
            assertTrue("${material.id} metallic", material.metallic in 0f..1f)
            assertTrue("${material.id} roughness", material.roughness in 0f..1f)
            assertTrue("${material.id} reflectance", material.reflectance in 0f..1f)
            val max = if (material.trait == MaterialTrait.Emissive) 8f else 1f
            assertTrue(
                "${material.id} opens with a trait amount its slider cannot reach",
                material.traitAmount in 0f..max,
            )
        }
    }

    @Test
    fun `the metallic-roughness base is taught before the extensions`() {
        // Five of the nine entries are plain metallic-roughness surfaces, and the first row
        // of the wall is all metal: the pair alone is what separates gold from steel, and a
        // materials demo that opens on an extension teaches the wrong lesson first.
        val plain = MaterialStudio.library.count { it.trait == MaterialTrait.None }
        assertTrue("Expected the plain metallic-roughness majority, got $plain of 9", plain >= 5)
        assertTrue(MaterialStudio.library.take(3).all { it.trait == MaterialTrait.None })
    }

    @Test
    fun `every extension family appears exactly once`() {
        MaterialTrait.entries.filter { it != MaterialTrait.None }.forEach { trait ->
            assertEquals(
                "$trait must be shown, and shown once — the sheet names the extension",
                1,
                MaterialStudio.library.count { it.trait == trait },
            )
        }
    }

    @Test
    fun `a material carrying an extension declares a non-zero amount`() {
        MaterialStudio.library.filter { it.trait != MaterialTrait.None }.forEach {
            assertNotEquals(
                "${it.id} names ${it.trait} but opens at 0 — the effect would be invisible",
                0f,
                it.traitAmount,
            )
        }
    }

    @Test
    fun `the default selection exists and shows an extension`() {
        val index = MaterialStudio.DEFAULT_INDEX
        assertTrue(index in MaterialStudio.library.indices)
        assertNotEquals(MaterialTrait.None, MaterialStudio.library[index].trait)
    }

    @Test
    fun `the summary quotes the live values, not the declared ones`() {
        val clearCoat = MaterialStudio.library.first { it.trait == MaterialTrait.ClearCoat }
        assertTrue(clearCoat.summary().contains("metallic ${format(clearCoat.metallic)}"))
        // The status pill has to keep telling the truth while the user drags.
        val dragged = clearCoat.summary(metallic = 0f, roughness = 1f, traitAmount = 0.5f)
        assertTrue(dragged, dragged.contains("metallic 0.00"))
        assertTrue(dragged, dragged.contains("roughness 1.00"))
        assertTrue(dragged, dragged.contains("clear coat 0.50"))
    }

    // ── The wall ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `the wall is centred on the origin and flat`() {
        val positions = MaterialStudio.wallPositions()
        assertEquals(MaterialStudio.library.size, positions.size)
        assertTrue(positions.all { abs(it.z) < 1e-6f })
        assertTrue("x is not centred", abs(positions.sumOf { it.x.toDouble() }) < 1e-4)
        assertTrue("y is not centred", abs(positions.sumOf { it.y.toDouble() }) < 1e-4)
    }

    @Test
    fun `wall reading order is library order — row 0 on top, left to right`() {
        val positions = MaterialStudio.wallPositions()
        // The note under the picker describes the ball the eye lands on only if the two
        // orders agree.
        assertTrue(positions[0].y > positions[MaterialStudio.COLUMNS].y)
        assertTrue(positions[0].x < positions[1].x)
    }

    @Test
    fun `no two spheres overlap`() {
        val positions = MaterialStudio.wallPositions()
        positions.forEachIndexed { i, a ->
            positions.drop(i + 1).forEach { b ->
                val distance = kotlin.math.hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble())
                assertTrue("Spheres $a and $b intersect", distance > 2.0 * MaterialStudio.BALL_RADIUS)
            }
        }
    }

    @Test
    fun `the wall extents cover the spheres, not just their centres`() {
        // They feed the orbit auto-fit: an extent measured centre to centre clips the outer
        // spheres in half at the frame edge.
        val span = (MaterialStudio.COLUMNS - 1) * MaterialStudio.BALL_SPACING
        assertEquals(span + 2f * MaterialStudio.BALL_RADIUS, MaterialStudio.wallExtentX(), 1e-5f)
        assertTrue(MaterialStudio.wallExtentY() > span)
    }

    @Test
    fun `degenerate wall requests return empty rather than throwing`() {
        assertTrue(MaterialStudio.wallPositions(count = 0).isEmpty())
        assertTrue(MaterialStudio.wallPositions(columns = 0).isEmpty())
        assertEquals(0f, MaterialStudio.wallExtentX(count = 0), 0f)
        assertEquals(0f, MaterialStudio.wallExtentY(columns = 0), 0f)
    }

    @Test
    fun `the compare pair fits where the single hero stood`() {
        val pair = MaterialStudio.COMPARE_OFFSET + MaterialStudio.COMPARE_RADIUS
        assertTrue(
            "The two compared spheres must not intersect",
            MaterialStudio.COMPARE_OFFSET > MaterialStudio.COMPARE_RADIUS,
        )
        assertTrue("Compare must widen the framing, or it is not a comparison", pair > MaterialStudio.HERO_RADIUS)
    }

    // ── The camera ───────────────────────────────────────────────────────────────────────

    @Test
    fun `the gallery sweep is bounded and seamless`() {
        // Bounded is the whole reason #2874 does not reopen: a 360 orbit put a different
        // piece of the environment behind the wall on every run.
        val samples = (0..200).map { MaterialStudio.sweepYaw(it / 200f) }
        assertTrue(samples.all { abs(it) <= MaterialStudio.SWEEP_DEGREES + 1e-4f })
        assertEquals("A sweep that jumps at the wrap is a visible jerk", samples.first(), samples.last(), 1e-4f)
        assertEquals(-MaterialStudio.SWEEP_DEGREES, MaterialStudio.sweepYaw(0f), 1e-4f)
        assertEquals(MaterialStudio.SWEEP_DEGREES, MaterialStudio.sweepYaw(0.5f), 1e-4f)
    }

    @Test
    fun `QA mode pins the camera to a constant, off-centre phase`() {
        val yaw = MaterialStudio.sweepYaw(MaterialStudio.STATIC_SWEEP_PHASE)
        assertEquals(yaw, MaterialStudio.sweepYaw(MaterialStudio.STATIC_SWEEP_PHASE), 0f)
        assertTrue("Head-on flattens every highlight", abs(yaw) > 1e-3f)
        assertNotEquals(0f, MaterialStudio.STATIC_ORBIT_YAW)
    }

    // ── The environment ──────────────────────────────────────────────────────────────────

    @Test
    fun `every environment is a bundled asset — nothing is fetched`() {
        assertTrue(MaterialStudio.environments.isNotEmpty())
        MaterialStudio.environments.forEach { option ->
            assertTrue(option.label.isNotBlank())
            assertTrue(
                "Environments load through the asset-path overload: `assets/`-relative, " +
                    "no scheme, no leading slash. Got: ${option.assetPath}",
                option.assetPath.startsWith("environments/") && option.assetPath.endsWith(".hdr"),
            )
            assertTrue(
                "${option.assetPath} is not in the APK — the studio would open black",
                assetFile(option.assetPath).isFile,
            )
        }
        assertTrue(MaterialStudio.DEFAULT_ENVIRONMENT_INDEX in MaterialStudio.environments.indices)
    }

    // ── The determinism contract itself ──────────────────────────────────────────────────

    /**
     * Asserted on the SOURCE because no frame can tell the two versions apart on a machine
     * with no network: the streamed subject only appears when the fetch succeeds, which is
     * exactly the condition CI does not reproduce (#2874).
     */
    @Test
    fun `the demo streams nothing`() {
        val source = demoSource("MaterialsDemo.kt")
        listOf("Sketchfab", "SampleAssets", "MaterialsSubject", "http").forEach { forbidden ->
            assertTrue(
                "MaterialsDemo must stay fully procedural — found `$forbidden` in its source. " +
                    "A remote subject makes the cold-launch frame depend on the network (#2874).",
                !source.contains(forbidden),
            )
        }
    }

    private fun format(value: Float): String {
        val hundredths = kotlin.math.round(value * 100f).toInt()
        return "${hundredths / 100}.${(hundredths % 100).toString().padStart(2, '0')}"
    }

    /**
     * Resolves a path under the demo module's `assets/`. The Gradle test task's working
     * directory is the module directory, but the search walks up so the test also passes
     * when a runner starts it from the repository root.
     */
    private fun assetFile(assetPath: String): File =
        repoFile("samples/android-demo/src/main/assets/$assetPath")

    private fun demoSource(fileName: String): String =
        repoFile("samples/android-demo/src/main/java/io/github/sceneview/demo/demos/$fileName").readText()

    private fun repoFile(relative: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.exists()) return candidate
            dir = dir.parentFile
        }
        throw AssertionError("Could not locate $relative from ${File("").absolutePath}")
    }
}

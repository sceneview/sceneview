package io.github.sceneview.ar.depth

import dev.romainguy.kotlin.math.Float2
import dev.romainguy.kotlin.math.Float3
import io.github.sceneview.ar.camera.kGroundContactParameter
import io.github.sceneview.ar.camera.kGroundNormalParameter
import io.github.sceneview.ar.depth.DepthOcclusionShaping.GROUND_BAND
import io.github.sceneview.ar.depth.DepthOcclusionShaping.groundBand
import io.github.sceneview.ar.depth.DepthOcclusionShaping.groundSurface
import io.github.sceneview.ar.depth.DepthOcclusionShaping.hides
import io.github.sceneview.ar.depth.DepthOcclusionShaping.occluderDistance
import io.github.sceneview.ar.depth.DepthOcclusionShaping.tolerance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.cos
import kotlin.math.sin

class DepthOcclusionShapingTest {

    private val thresholds = (0 until 16).map { (it + 0.5f) / 16f }

    /** Share of the pixels (over the dither thresholds) where the virtual fragment is hidden. */
    private fun hiddenShare(
        depth: Float,
        virtualDepth: Float,
        ground: Float = depth,
        band: Float = 0f,
    ): Float = thresholds.count { hides(depth, virtualDepth, it, ground, band) } / thresholds.size.toFloat()

    @Test
    fun `tolerance grows with distance`() {
        assertEquals(0.06f, tolerance(1f), 1e-4f)
        assertEquals(0.11f, tolerance(2f), 1e-4f)
        assertEquals(0.27f, tolerance(4f), 1e-4f)
    }

    @Test
    fun `a mullion 40 cm away hides a model 2 m away on every pixel`() {
        assertEquals(1f, hiddenShare(depth = 0.4f, virtualDepth = 2f))
    }

    @Test
    fun `a model standing on the floor is not cut by the floor's noise`() {
        // Floor measured 3 cm short of the virtual contact point, 1.5 m away.
        assertEquals(0f, hiddenShare(depth = 1.47f, virtualDepth = 1.5f))
    }

    @Test
    fun `the tolerance is a hard limit, never dithered`() {
        // A dithered tolerance band broke a model's floor contact into vertical streaks on the
        // Pixel 4a: inside the tolerance nothing is hidden, past it everything is.
        assertEquals(0f, hiddenShare(depth = 2f, virtualDepth = 2f + tolerance(2f) * 0.99f))
        assertEquals(1f, hiddenShare(depth = 2f, virtualDepth = 2f + tolerance(2f) * 1.01f))
    }

    @Test
    fun `depth beyond 8 m never occludes, and fades from 5 m`() {
        assertEquals(0f, hiddenShare(depth = 9f, virtualDepth = 30f))
        assertEquals(1f, hiddenShare(depth = 4.5f, virtualDepth = 10f))
        val mid = hiddenShare(depth = 6.5f, virtualDepth = 20f)
        assertTrue("share $mid", mid > 0.2f && mid < 0.8f)
    }

    @Test
    fun `no depth occludes nothing`() {
        assertEquals(0f, hiddenShare(depth = 0f, virtualDepth = 3f))
        assertEquals(0f, hiddenShare(depth = 65.535f, virtualDepth = 3f))
    }

    // ── Rule 4: contact grounding ──────────────────────────────────────────────────────────────

    @Test
    fun `floor noise under a placed object never cuts its contact shadow or ring`() {
        // Pixel 4a, 2026-11-04: the floor measured short by more than the tolerance right under
        // the helmet cut a pale tongue into its contact shadow and broke its ring. Inside the
        // contact disc, the floor may be measured up to the band plus the tolerance short and a
        // decal lifted 5 mm above it stays whole...
        for (d in listOf(0.6f, 1f, 1.5f, 2f, 3f)) {
            val decal = d - 0.005f
            val noisy = d - GROUND_BAND - tolerance(d - GROUND_BAND) * 0.8f
            assertEquals("floor at $d m", 0f, hiddenShare(noisy, decal, ground = d, band = GROUND_BAND))
            // ...where the same noise without the rule cut it.
            assertEquals("floor at $d m, rule off", 1f, hiddenShare(noisy, decal))
        }
    }

    @Test
    fun `a chair leg 25 cm in front still hides the shadow, the ring and the model`() {
        // Anything nearer than the plane by more than band + tolerance occludes (21 cm at 2 m):
        // the chair leg 30-40 cm in front of the model in the Pixel 4a run hides all three.
        for (d in listOf(0.6f, 1f, 1.5f, 2f, 2.5f)) {
            val leg = d - 0.25f
            assertEquals("shadow at $d m", 1f, hiddenShare(leg, d - 0.005f, ground = d, band = GROUND_BAND))
            assertEquals("model base at $d m", 1f, hiddenShare(leg, d - 0.02f, ground = d, band = GROUND_BAND))
        }
        assertEquals(1f, hiddenShare(3f - 0.35f, 3f - 0.005f, ground = 3f, band = GROUND_BAND))
    }

    @Test
    fun `grounding pushes the occluder back to the plane at most, by the band at most`() {
        // A real surface behind the plane (a hole, a step down) is left as measured...
        assertEquals(occluderDistance(2.3f, 0.5f), occluderDistance(2.3f, 0.5f, ground = 2f, band = GROUND_BAND), 0f)
        // ...one just in front lands on the plane, one farther in front moves by the band.
        assertEquals(2f + tolerance(1.97f), occluderDistance(1.97f, 0.5f, ground = 2f, band = GROUND_BAND), 1e-6f)
        assertEquals(1.6f + tolerance(1.5f), occluderDistance(1.5f, 0.5f, ground = 2f, band = GROUND_BAND), 1e-6f)
        // Outside every contact disc (no band) it is the rule without grounding, exactly.
        for (depth in listOf(0.5f, 1.5f, 4f)) {
            assertEquals(occluderDistance(depth, 0.5f), occluderDistance(depth, 0.5f, ground = 0.1f, band = 0f), 0f)
        }
        // No depth still occludes nothing.
        assertEquals(
            DepthOcclusionShaping.NO_DEPTH_DISTANCE,
            occluderDistance(0f, 0.5f, ground = 2f, band = GROUND_BAND),
            0f,
        )
    }

    @Test
    fun `the band covers the contact disc and is gone at one and a half radii`() {
        assertEquals(GROUND_BAND, groundBand(distance = 0f, radius = 0.3f), 0f)
        assertEquals(GROUND_BAND, groundBand(distance = 0.3f, radius = 0.3f), 1e-6f)
        assertEquals(GROUND_BAND / 2f, groundBand(distance = 0.375f, radius = 0.3f), 1e-6f)
        assertEquals(0f, groundBand(distance = 0.45f, radius = 0.3f), 1e-6f)
        assertEquals(0f, groundBand(distance = 3f, radius = 0.3f), 0f)
    }

    @Test
    fun `the contact plane is met along the pixel ray`() {
        // Camera 1.2 m above the floor, pitched 30 degrees down: the view axis meets the floor
        // 2.4 m away, and the floor's normal in view space is (0, cos 30, sin 30).
        val pitch = Math.toRadians(30.0)
        val normal = Float3(0f, cos(pitch).toFloat(), sin(pitch).toFloat())
        val contact = Float3(0f, 0f, -2.4f)
        val centre = groundSurface(Float3(0f, 0f, -0.37f), contact, normal, radius = 0.25f, enabled = 1f)
        assertEquals(2.4f, centre.x, 1e-4f)
        assertEquals(GROUND_BAND, centre.y, 1e-6f)
        // Off, there is no band whatever the geometry.
        assertEquals(Float2(0f, 0f), groundSurface(contact, contact, normal, radius = 0.25f, enabled = 0f))
        // A ray meeting the plane behind the camera (looking up) grounds nothing.
        assertEquals(0f, groundSurface(Float3(0f, 1f, -1f), contact, normal, radius = 100f, enabled = 1f).y, 0f)
        // Neither does a pixel 60 cm beside a 25 cm contact.
        val aside = groundSurface(contact + Float3(0.6f, 0f, 0f), contact, normal, radius = 0.25f, enabled = 1f)
        assertEquals(0f, aside.y, 0f)
    }

    @Test
    fun `the materials carry the same constants`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "arsceneview/src/main/materials").isDirectory }
        val expected = mapOf(
            "kTolBase" to DepthOcclusionShaping.TOL_BASE,
            "kTolLinear" to DepthOcclusionShaping.TOL_LINEAR,
            "kTolQuad" to DepthOcclusionShaping.TOL_QUAD,
            "kFarFadeStart" to DepthOcclusionShaping.FAR_FADE_START,
            "kFarFadeEnd" to DepthOcclusionShaping.FAR_FADE_END,
            "kMaxValidDepthMeters" to DepthOcclusionShaping.MAX_VALID_DEPTH,
            "kNoDepthDistanceMeters" to DepthOcclusionShaping.NO_DEPTH_DISTANCE,
            "kGroundBand" to DepthOcclusionShaping.GROUND_BAND,
            "kGroundFade" to DepthOcclusionShaping.GROUND_FADE,
        )
        listOf("camera_stream_depth.mat", "camera_stream_person_occlusion.mat").forEach { name ->
            val source = File(root, "arsceneview/src/main/materials/$name").readText()
            // The tolerance is a hard limit in the shader too, as in [DepthOcclusionShaping]...
            assertTrue("hard tolerance in $name", "float occluder_m = surface_m + tolerance;" in source)
            // ...over the measured depth grounded on the contact plane by the same expression...
            assertTrue(
                "contact grounding in $name",
                "float surface_m = max(depth_m, min(ground.x, depth_m + ground.y));" in source,
            )
            // ...with the band gated by the stream's parameters and fed by the pixel's view ray...
            listOf(
                "if (materialParams.groundNormal.w < 0.5) return vec2(0.0);",
                "highp float band_m = kGroundBand * step(0.0, along) * fade;",
                "material.groundRay = getViewFromClipMatrix() * clip;",
                "vec2 ground = groundSurface(variable_groundRay.xyz / variable_groundRay.w);",
                "name : $kGroundContactParameter",
                "name : $kGroundNormalParameter",
            ).forEach { line -> assertTrue("'$line' in $name", line in source) }
            // ...applied to a smooth reconstruction of the depth, or its edge shows the
            // 160x90 texel grid as steps.
            assertTrue(
                "smooth occlusion depth in $name",
                "float occlusion_m = occlusionDepthMeters(getUV0());" in source,
            )
            expected.forEach { (constant, value) ->
                val match = Regex("const float $constant = ([0-9.]+);").find(source)
                    ?: throw AssertionError("$constant missing from $name")
                assertEquals("$constant in $name", value, match.groupValues[1].toFloat(), 0f)
            }
        }
    }
}

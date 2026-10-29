package io.github.sceneview.demo.demos

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import io.github.sceneview.demo.demos.internal.DebugGroup
import io.github.sceneview.demo.demos.internal.DebugLayer
import io.github.sceneview.demo.demos.internal.DebugPose
import io.github.sceneview.demo.demos.internal.ReplayLens
import io.github.sceneview.demo.theme.DebugPalette
import io.github.sceneview.demo.theme.SceneViewTokens.DebugView
import io.github.sceneview.demo.theme.StageChrome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** The Room Scan replay's control rules and its path colours (#4597). */
class RerunReplayControlsTest {

    // ── Path colours: one blue, faint to strong ─────────────────────────────────────────────

    @Test
    fun `the camera path is one blue hue in both palettes`() {
        for ((name, palette) in palettes) {
            for (i in 0..STEPS) {
                val hue = hueOf(trailColorAt(palette, i / STEPS.toFloat()))
                assertTrue("$name step $i: hue $hue is not blue", hue in BLUE_HUES)
            }
        }
    }

    @Test
    fun `the path stands out more from its ground the newer it is`() {
        val grounds = mapOf("dark" to StageChrome.Media.ground, "light" to StageChrome.Light.ground)
        for ((name, palette) in palettes) {
            val ground = grounds.getValue(name).luminance()
            val contrasts = (0..STEPS).map { abs(trailColorAt(palette, it / STEPS.toFloat()).luminance() - ground) }
            contrasts.zipWithNext().forEachIndexed { i, (older, newer) ->
                assertTrue("$name step $i: $older then $newer", newer >= older)
            }
        }
    }

    @Test
    fun `the path ends on the camera frustum's colour`() {
        for ((name, palette) in palettes) {
            assertClose(name, palette.frustum, trailColorAt(palette, 1f))
        }
    }

    @Test
    fun `the 3D view paints its path steps with the ramp`() {
        for ((_, palette) in palettes) {
            assertClose("oldest", palette.trailOld, paintOf(DebugLayer.trailSteps.first(), palette).color)
            assertClose("newest", palette.trailNew, paintOf(DebugLayer.trailSteps.last(), palette).color)
        }
    }

    // ── Layers ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the replay lists its camera photos apart from the path, and no anchors`() {
        val groups = RerunReplayControls.layers.map { it.group }
        assertEquals(listOf(DebugGroup.Trail, DebugGroup.Cameras, DebugGroup.Planes, DebugGroup.Points), groups)
        assertFalse(DebugGroup.Anchors in groups)
        RerunReplayControls.layers.forEach { assertTrue(it.label, it.hint.isNotBlank()) }
    }

    @Test
    fun `the frusta are their own layers, not the path's`() {
        listOf(DebugLayer.Keyframes, DebugLayer.Frustum, DebugLayer.FrustumFace).forEach {
            assertEquals("$it", DebugGroup.Cameras, it.group)
        }
        (DebugLayer.trailSteps + DebugLayer.TrailHead).forEach { assertEquals("$it", DebugGroup.Trail, it.group) }
    }

    // ── 3D over the camera frame ────────────────────────────────────────────────────────────

    private val lens = ReplayLens(halfWidthPerDepth = 0.5f, halfHeightPerDepth = 0.75f)

    @Test
    fun `a point straight ahead lands in the middle of the frame`() {
        val p = RerunReplayControls.project(DebugPose(0f, 0f, 0f), lens, 0f, 0f, -2f)!!
        assertEquals(0.5f, p[0], EPS)
        assertEquals(0.5f, p[1], EPS)
    }

    @Test
    fun `right is right and up is up on the frame`() {
        // 1 m right and 1.5 m up, 2 m ahead: on the frame's right and top edges.
        val p = RerunReplayControls.project(DebugPose(0f, 0f, 0f), lens, 0.99f, 1.49f, -2f)!!
        assertEquals(1f, p[0], 0.01f)
        assertEquals(0f, p[1], 0.01f)
    }

    @Test
    fun `points behind the camera or off the frame are not drawn`() {
        val pose = DebugPose(0f, 0f, 0f)
        assertNull(RerunReplayControls.project(pose, lens, 0f, 0f, 2f))
        assertNull(RerunReplayControls.project(pose, lens, 0f, 0f, -0.05f))
        assertNull(RerunReplayControls.project(pose, lens, 3f, 0f, -2f))
    }

    @Test
    fun `the frame follows the camera's pose`() {
        // Turned 90° to its left (about +Y), the camera looks down −X.
        val half = sqrt(0.5f)
        val turned = DebugPose(1f, 0f, 0f, qy = half, qw = half)
        val p = RerunReplayControls.project(turned, lens, -1f, 0f, 0f)!!
        assertEquals(0.5f, p[0], EPS)
        assertEquals(0.5f, p[1], EPS)
        assertNull(RerunReplayControls.project(turned, lens, 1f, 0f, -2f))
    }

    private companion object {
        const val STEPS = 8
        const val EPS = 1e-4f
        val BLUE_HUES = 200f..230f
        val palettes: Map<String, DebugPalette> = mapOf("dark" to DebugView.Dark, "light" to DebugView.Light)

        fun assertClose(message: String, expected: Color, actual: Color) {
            val off = maxOf(
                abs(expected.red - actual.red),
                abs(expected.green - actual.green),
                abs(expected.blue - actual.blue),
            )
            assertTrue("$message: $expected vs $actual", off < 0.01f)
        }

        fun hueOf(c: Color): Float {
            val hi = max(c.red, max(c.green, c.blue))
            val lo = min(c.red, min(c.green, c.blue))
            val d = hi - lo
            if (d == 0f) return 0f
            val h = when (hi) {
                c.red -> ((c.green - c.blue) / d).mod(6f)
                c.green -> (c.blue - c.red) / d + 2f
                else -> (c.red - c.green) / d + 4f
            }
            return h * 60f
        }
    }
}

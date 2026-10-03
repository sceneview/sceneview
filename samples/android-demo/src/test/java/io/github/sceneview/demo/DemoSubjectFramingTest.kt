package io.github.sceneview.demo

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.sceneview.demo.demos.internal.DemoMath
import io.github.sceneview.verticalFovDegreesForFocalLength
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.tan

/**
 * What the demos with a scene under the glass hand to `contentPadding`, and what they fit their
 * camera for (#4326): Double Pendulum, Model Viewer and Contact Shadow on a phone held sideways.
 */
class DemoSubjectFramingTest {

    // A Pixel 7a held sideways, at its default display size.
    private val width = 914.dp
    private val height = 411.dp
    private val chromeTop = 80.dp

    private fun padding(bottom: Float) = demoContentPadding(
        cover = PaddingValues(top = chromeTop, bottom = bottom.dp),
        sceneHeight = height,
    )

    @Test
    fun `with room to spare the cover is handed over as it is`() {
        val padding = padding(bottom = 190f)
        assertEquals(chromeTop, padding.calculateTopPadding())
        assertEquals(190.dp, padding.calculateBottomPadding())
    }

    @Test
    fun `under a sheet dragged all the way up the top yields and the bottom does not`() {
        // 411 - 320 leaves 91 dp above the sheet; the title row would keep 11 dp of it, less
        // than the tenth of the view (41.1 dp) the SDK keeps visible.
        val padding = padding(bottom = 320f)
        assertEquals(320.dp, padding.calculateBottomPadding())
        assertEquals(411f - 320f - 41.1f, padding.calculateTopPadding().value, 0.01f)
    }

    @Test
    fun `the top never goes negative when the sheet alone passes the floor`() {
        val padding = padding(bottom = 400f)
        assertEquals(0.dp, padding.calculateTopPadding())
        assertEquals(400.dp, padding.calculateBottomPadding())
    }

    @Test
    fun `a phone held sideways keeps only the status bar clear at the top`() {
        // The title is a chip in a corner; its row is not a band over a centred subject.
        val padding = demoContentPadding(
            cover = PaddingValues(top = chromeTop, bottom = 190.dp),
            sceneHeight = height,
            statusBar = 24.dp,
            compactHeight = true,
        )
        assertEquals(24.dp, padding.calculateTopPadding())
        assertEquals(190.dp, padding.calculateBottomPadding())
    }

    @Test
    fun `a slot the scaffold already inset gets no top, sideways or not`() {
        // Its cover has no top: the status bar is above the slot, not over it.
        val padding = demoContentPadding(
            cover = PaddingValues(bottom = 120.dp),
            sceneHeight = 300.dp,
            statusBar = 24.dp,
            compactHeight = true,
        )
        assertEquals(0.dp, padding.calculateTopPadding())
    }

    @Test
    fun `sideways too the top yields before the band above the sheet does`() {
        val padding = demoContentPadding(
            cover = PaddingValues(top = chromeTop, bottom = 360.dp),
            sceneHeight = height,
            statusBar = 24.dp,
            compactHeight = true,
        )
        assertEquals(411f - 360f - 41.1f, padding.calculateTopPadding().value, 0.01f)
    }

    @Test
    fun `the sides are the window's own, whatever the layout direction`() {
        // The cutout is on the left of the glass, in RTL too.
        val padding = demoContentPadding(
            cover = PaddingValues(top = chromeTop, bottom = 190.dp),
            sceneHeight = height,
            left = 48.dp,
            right = 0.dp,
        )
        LayoutDirection.entries.forEach { direction ->
            assertEquals(48.dp, padding.calculateLeftPadding(direction))
            assertEquals(0.dp, padding.calculateRightPadding(direction))
        }
    }

    @Test
    fun `the free aspect is the area between the bands and the side insets`() {
        val padding = demoContentPadding(
            cover = PaddingValues(top = chromeTop, bottom = 190.dp),
            sceneHeight = height,
            left = 48.dp,
            right = 24.dp,
        )
        assertEquals((914f - 72f) / (411f - 270f), demoFreeAspect(width, height, padding), 0.0001f)
    }

    @Test
    fun `a scene already inset by its bands is free edge to edge`() {
        // Upright, the scaffold insets the slot itself and reports nothing over it.
        val padding = demoContentPadding(PaddingValues(0.dp), sceneHeight = 538.dp)
        assertEquals(411f / 538f, demoFreeAspect(411.dp, 538.dp, padding), 0.0001f)
    }

    @Test
    fun `the free aspect keeps the SDK's floor on each axis`() {
        val padding = PaddingValues(horizontal = 500.dp, vertical = 300.dp)
        assertEquals(91.4f / 41.1f, demoFreeAspect(width, height, padding), 0.0001f)
    }

    @Test
    fun `a scene that has not been measured has a usable aspect`() {
        assertEquals(1f, demoFreeAspect(0.dp, 0.dp, PaddingValues(0.dp)), 0f)
    }

    @Test
    fun `the swing envelope holds both bobs at every angle and slider extreme`() {
        val bob = 0.085f
        for (l1 in listOf(0.3f, 0.65f)) for (l2 in listOf(0.2f, 0.5f)) {
            val reach = pendulumSwingExtent(l1, l2, bob) / 2f
            assertEquals(l1 + l2 + bob, reach, 0.000001f)
            for (a in 0..360 step 15) for (b in 0..360 step 15) {
                val t1 = Math.toRadians(a.toDouble())
                val t2 = Math.toRadians(b.toDouble())
                val x = l1 * sin(t1) + l2 * sin(t2)
                val y = l1 * cos(t1) + l2 * cos(t2)
                assertTrue(hypot(x, y) + bob <= reach + 1e-6)
            }
        }
    }

    @Test
    fun `a swing fitted for the resting area stays inside every narrower band a sheet leaves`() {
        // The lens's vertical field spans the free band whatever its height, so only the aspect
        // changes as the sheet rises — and it only widens.
        val extent = pendulumSwingExtent(0.65f, 0.5f, 0.085f)
        val tanY = tan(Math.toRadians(verticalFovDegreesForFocalLength(28.0)) / 2.0)
        val restingAspect = (914f - 48f) / (411f - 80f - 190f)
        val elevation = Math.toRadians(10.0)
        val distance = fitOrbitRadius(
            extentX = extent, extentY = extent, extentZ = 0.17f,
            aspect = restingAspect, elevationDegrees = 10f, fill = 0.96f, azimuthInvariant = false,
        )
        for (sheet in listOf(190f, 240f, 300f, 329.9f)) {
            val aspect = (914f - 48f) / (411f - 80f - sheet).coerceAtLeast(41.1f)
            for (turn in 0 until 360 step 5) for (z in listOf(-0.085, 0.085)) {
                val angle = Math.toRadians(turn.toDouble())
                val x = extent / 2.0 * sin(angle)
                val y = extent / 2.0 * cos(angle)
                // Seen from an eye pitched `elevation` above the hinge, looking down at it.
                val depth = distance - y * sin(elevation) - z * cos(elevation)
                val up = y * cos(elevation) - z * sin(elevation)
                assertTrue("height under a $sheet dp sheet", kotlin.math.abs(up) / depth <= tanY)
                assertTrue("width under a $sheet dp sheet", kotlin.math.abs(x) / depth <= tanY * aspect)
            }
        }
    }

    @Test
    fun `upright the pendulum is fitted around its hinge, in a strip down to the floor`() {
        val extent = pendulumSwingExtent(0.5f, 0.35f, 0.085f)
        val upright = pendulumSubject(pivotHeight = 1.4f, swingExtent = extent, freeAspect = 0.55f)
        assertEquals(extent, upright.extentY, 0.000001f)
        assertEquals(1.4f, upright.centerY, 0.000001f)
        val strip = pendulumSubject(pivotHeight = 1.4f, swingExtent = extent, freeAspect = 5.8f)
        assertEquals(1.4f + extent / 2f, strip.extentY, 0.000001f)
        assertEquals((1.4f + extent / 2f) / 2f, strip.centerY, 0.000001f)
    }

    @Test
    fun `in a strip the stand's foot and the whole swing are drawn inside the free area`() {
        val tanY = tan(Math.toRadians(verticalFovDegreesForFocalLength(28.0)) / 2.0)
        val aspect = (914f - 48f) / (411f - 48f - 215f)
        val elevation = Math.toRadians(10.0)
        for (l1 in listOf(0.3f, 0.65f)) for (l2 in listOf(0.2f, 0.5f)) {
            val extent = pendulumSwingExtent(l1, l2, 0.085f)
            val subject = pendulumSubject(pivotHeight = 1.4f, swingExtent = extent, freeAspect = aspect)
            val distance = fitOrbitRadius(
                extentX = extent, extentY = subject.extentY, extentZ = 0.17f,
                aspect = aspect, elevationDegrees = 10f, fill = 0.88f, azimuthInvariant = false,
            )
            // Points as (x, height above the floor, z): the swing disc's rim, then the base
            // plate's corners — 0.42 m wide, 0.3 m deep, centred 0.18 m behind the swing plane.
            val rim = (0 until 360 step 5).map {
                val angle = Math.toRadians(it.toDouble())
                Triple(extent / 2.0 * sin(angle), 1.4 + extent / 2.0 * cos(angle), 0.0)
            }
            val foot = listOf(-0.21, 0.21).flatMap { x ->
                listOf(-0.33, -0.03).flatMap { z -> listOf(Triple(x, 0.0, z), Triple(x, 0.03, z)) }
            }
            for ((x, height, z) in rim + foot) {
                val y = height - subject.centerY
                val depth = distance - y * sin(elevation) - z * cos(elevation)
                val up = y * cos(elevation) - z * sin(elevation)
                assertTrue("height of ($x, $height, $z)", kotlin.math.abs(up) / depth <= tanY)
                assertTrue("width of ($x, $height, $z)", kotlin.math.abs(x) / depth <= tanY * aspect)
            }
        }
    }

    @Test
    fun `a sheet taller than half the window covers half of it until it is dragged up`() {
        // The Lighting sheet on a phone held sideways: it measures the whole window.
        assertEquals(205.5.dp, settledSheetCover(measured = 411.dp, windowHeight = 411.dp, expanded = false))
        assertEquals(411.dp, settledSheetCover(measured = 411.dp, windowHeight = 411.dp, expanded = true))
        // Upright it is shorter than half the window and sits fully on screen either way.
        assertEquals(330.dp, settledSheetCover(measured = 330.dp, windowHeight = 914.dp, expanded = false))
        assertEquals(330.dp, settledSheetCover(measured = 330.dp, windowHeight = 914.dp, expanded = true))
        // A window not measured yet takes nothing away.
        assertEquals(330.dp, settledSheetCover(measured = 330.dp, windowHeight = 0.dp, expanded = false))
    }

    @Test
    fun `the viewer framing of a free area has no offset and only depends on its aspect`() {
        val fov = verticalFovDegreesForFocalLength(28.0)
        // The Fox, in glTF units.
        val inDp = DemoMath.viewerFraming(
            extentX = 26f, extentY = 79f, extentZ = 155f,
            viewportWidth = 866f, viewportHeight = 141f,
            topInset = 0f, bottomInset = 0f, verticalFovDegrees = fov,
        )
        val byAspect = DemoMath.viewerFraming(
            extentX = 26f, extentY = 79f, extentZ = 155f,
            viewportWidth = 866f / 141f, viewportHeight = 1f,
            topInset = 0f, bottomInset = 0f, verticalFovDegrees = fov,
        )
        byAspect.targetOffset.toList().forEach { assertEquals(0f, it, 0f) }
        assertEquals(inDp.distance, byAspect.distance, 0.001f)
        // Height-limited in a strip: the model spans the viewer's fill of the band, not less.
        val depthAllowance = 155f * DemoMath.DEPTH_ALLOWANCE
        val spanned = (79f / 2f) / ((byAspect.distance - depthAllowance) * tan(Math.toRadians(fov) / 2.0).toFloat())
        assertEquals(DemoMath.VIEWER_FILL, spanned, 0.001f)
    }
}

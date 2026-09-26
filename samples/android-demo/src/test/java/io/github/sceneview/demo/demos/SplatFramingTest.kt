package io.github.sceneview.demo.demos

import io.github.sceneview.core.splat.SplatCloud
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * Guards the `splat-preview` orbit-home arithmetic (#2646, #3620). The first version of the demo
 * hand-picked `z = 1.6` and clipped its subject in portrait; these cases pin the property that
 * actually matters — the framed radius fits inside the frustum, and the framing is derived from
 * the capture rather than typed in.
 */
class SplatFramingTest {

    /** Half-width of the frustum at [distance], for the same vertical-fit model the demo uses. */
    private fun halfWidthAt(distance: Float, aspect: Float = 9f / 20f, focalLengthMm: Float = 28f): Float {
        val tanHalfVertical = (24f / 2f) / focalLengthMm
        return distance * aspect * tanHalfVertical
    }

    /** Perpendicular distance from the view axis to the tangent point of a sphere of [radius]. */
    private fun silhouetteHalfWidth(distance: Float, radius: Float): Float {
        val sinHalf = radius / distance
        return sinHalf / sqrt(1f - sinHalf * sinHalf) // = tan(asin(r/d))
    }

    /**
     * A cloud whose points sit on a sphere of [radius] around [center] — every point at the same
     * distance, so the "radius containing half the points" the demo measures IS [radius].
     */
    private fun shellCloud(
        radius: Float,
        center: Triple<Float, Float, Float> = Triple(0f, 0f, 0f),
        count: Int = 512,
    ): SplatCloud {
        val positions = FloatArray(count * 3)
        for (i in 0 until count) {
            // Fibonacci sphere: an even spread, and deterministic.
            val y = 1f - 2f * (i + 0.5f) / count
            val ring = sqrt((1f - y * y).coerceAtLeast(0f))
            val theta = GOLDEN_ANGLE * i
            positions[i * 3] = center.first + radius * ring * kotlin.math.cos(theta)
            positions[i * 3 + 1] = center.second + radius * y
            positions[i * 3 + 2] = center.third + radius * ring * kotlin.math.sin(theta)
        }
        return SplatCloud(
            count = count,
            positions = positions,
            scales = FloatArray(count * 3) { 0.01f },
            rotations = FloatArray(count * 4) { if (it % 4 == 3) 1f else 0f },
            colors = FloatArray(count * 3) { 0.5f },
            opacities = FloatArray(count) { 1f },
        )
    }

    @Test
    fun `the framed radius fits inside the portrait frustum`() {
        val radius = 0.32f
        val distance = splatFramingDistance(radius = radius)
        val tanHalfHorizontal = halfWidthAt(distance) / distance
        val tanSilhouette = silhouetteHalfWidth(distance, radius = radius)
        assertTrue(
            "Silhouette angle $tanSilhouette must stay inside the frustum half-angle " +
                "$tanHalfHorizontal at distance $distance",
            tanSilhouette < tanHalfHorizontal,
        )
    }

    @Test
    fun `the margin is small - the subject still fills most of the frame`() {
        val radius = 0.32f
        val distance = splatFramingDistance(radius = radius)
        val fill = silhouetteHalfWidth(distance, radius) / (halfWidthAt(distance) / distance)
        assertTrue("Subject should fill >=90% of the half-width, was $fill", fill >= 0.90f)
        assertTrue("Subject should not touch the edge, was $fill", fill <= 0.99f)
    }

    @Test
    fun `a wider viewport needs less distance`() {
        assertTrue(
            splatFramingDistance(radius = 0.4f, aspect = 16f / 9f) <
                splatFramingDistance(radius = 0.4f, aspect = 9f / 20f)
        )
    }

    @Test
    fun `the framing targets the capture's own centre`() {
        val framing = scanFraming(shellCloud(radius = 0.4f, center = Triple(1f, -2f, 0.5f)))
        assertEquals(1f, framing.target.x, 1e-3f)
        assertEquals(-2f, framing.target.y, 1e-3f)
        assertEquals(0.5f, framing.target.z, 1e-3f)
    }

    @Test
    fun `the camera stands back far enough for the measured radius`() {
        val radius = 0.45f
        val framing = scanFraming(shellCloud(radius = radius))
        val distance = sqrt(
            framing.cameraPosition.x * framing.cameraPosition.x +
                framing.cameraPosition.y * framing.cameraPosition.y +
                framing.cameraPosition.z * framing.cameraPosition.z
        )
        assertEquals(splatFramingDistance(radius = radius), distance, 1e-2f)
        assertTrue("Camera must sit in front of the capture", framing.cameraPosition.z > 0f)
        assertTrue("Camera is tilted slightly above the target", framing.cameraPosition.y > 0f)
    }

    @Test
    fun `a bigger capture is framed from further away`() {
        val near = scanFraming(shellCloud(radius = 0.3f)).cameraPosition.z
        val far = scanFraming(shellCloud(radius = 0.9f)).cameraPosition.z
        assertTrue("0.9 m capture ($far) must be framed further than a 0.3 m one ($near)", far > near)
    }

    companion object {
        private const val GOLDEN_ANGLE = 2.3999632f
    }
}

package io.github.sceneview.demo.demos

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * Guards the `splat-preview` orbit-home arithmetic (#2646). The first version of the demo
 * hand-picked `z = 1.6` for a 0.536 m silhouette and clipped the shell on both edges in
 * portrait; these cases pin the property that actually matters — the silhouette fits inside
 * the frustum — instead of re-asserting whatever number the code happens to produce.
 */
class SplatFramingTest {

    /** Half-width of the frustum at [distance], for the same vertical-fit model the demo uses. */
    private fun halfWidthAt(distance: Float, aspect: Float = 9f / 20f, focalLengthMm: Float = 28f): Float {
        val tanHalfVertical = (24f / 2f) / focalLengthMm
        return distance * aspect * tanHalfVertical
    }

    /** Perpendicular distance from the view axis to the tangent point of a sphere of [radius]. */
    private fun silhouetteHalfWidth(distance: Float, radius: Float): Float {
        // The silhouette of a sphere seen from `distance` subtends asin(r/d); its projected
        // half-extent at the sphere's own depth is r * sqrt(1 - (r/d)^2) / (1 - (r/d)^2) — but
        // the containment test only needs the tangent condition, so compare angles instead.
        val sinHalf = radius / distance
        return sinHalf / sqrt(1f - sinHalf * sinHalf) // = tan(asin(r/d))
    }

    @Test
    fun `the whole shell fits inside the portrait frustum`() {
        val distance = splatFramingDistance()
        val tanHalfHorizontal = halfWidthAt(distance) / distance
        val tanSilhouette = silhouetteHalfWidth(distance, radius = 0.536f)
        assertTrue(
            "Silhouette angle $tanSilhouette must stay inside the frustum half-angle " +
                "$tanHalfHorizontal at distance $distance",
            tanSilhouette < tanHalfHorizontal,
        )
    }

    @Test
    fun `the margin is small - the shell still fills most of the frame`() {
        val distance = splatFramingDistance()
        val tanHalfHorizontal = halfWidthAt(distance) / distance
        val tanSilhouette = silhouetteHalfWidth(distance, radius = 0.536f)
        val fill = tanSilhouette / tanHalfHorizontal
        assertTrue("Shell should fill >=80% of the half-width, was $fill", fill >= 0.80f)
        assertTrue("Shell should not touch the edge, was $fill", fill <= 0.95f)
    }

    @Test
    fun `the old hand-picked distance is proven to clip`() {
        // Regression witness: 1.6 m is what shipped in P1c, and it does not contain the shell.
        val tanHalfHorizontal = halfWidthAt(1.6f) / 1.6f
        val tanSilhouette = silhouetteHalfWidth(1.6f, radius = 0.536f)
        assertTrue("1.6 m was expected to clip", tanSilhouette > tanHalfHorizontal)
    }

    @Test
    fun `a wider viewport needs less distance`() {
        assertTrue(splatFramingDistance(aspect = 16f / 9f) < splatFramingDistance(aspect = 9f / 20f))
    }
}

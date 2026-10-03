package io.github.sceneview.demo.demos

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-JVM tests for [confidenceBucketPercent] — the bucketing that keeps the `ar-ml-object-label`
 * label-bitmap cache from re-rasterising on every sub-percent confidence jitter between detector
 * passes. [StickyConfidenceTest] covers the hysteresis on top of it, [LabelHitChoiceTest] the
 * surface a label is anchored on.
 */
class ARMLObjectLabelDemoTest {

    @Test
    fun `buckets down to the nearest 5 percent step by default`() {
        assertEquals(80, confidenceBucketPercent(0.84f))
        assertEquals(85, confidenceBucketPercent(0.85f))
        assertEquals(85, confidenceBucketPercent(0.89f))
    }

    @Test
    fun `0 and 1 confidence map to their own buckets`() {
        assertEquals(0, confidenceBucketPercent(0f))
        assertEquals(100, confidenceBucketPercent(1f))
    }

    @Test
    fun `out-of-range confidence is coerced into 0 to 1 before bucketing`() {
        assertEquals(0, confidenceBucketPercent(-0.5f))
        assertEquals(100, confidenceBucketPercent(1.5f))
    }

    @Test
    fun `custom step size changes the bucket width`() {
        assertEquals(70, confidenceBucketPercent(0.73f, step = 10))
        assertEquals(75, confidenceBucketPercent(0.79f, step = 25))
    }
}

class StickyConfidenceTest {
    @Test
    fun `a new label shows its bucket`() {
        assertEquals(80, stickyConfidencePercent(shownPercent = null, confidence = 0.84f))
    }

    @Test
    fun `jitter around a bucket edge keeps the text shown`() {
        // Raw buckets would read 80, 75, 80, 75: four redraws of the same label.
        var shown: Int? = null
        listOf(0.801f, 0.799f, 0.803f, 0.782f).forEach { confidence ->
            shown = stickyConfidencePercent(shown, confidence)
            assertEquals(80, shown)
        }
    }

    @Test
    fun `jitter around the upper edge keeps the text shown too`() {
        assertEquals(80, stickyConfidencePercent(shownPercent = 80, confidence = 0.86f))
        assertEquals(80, stickyConfidencePercent(shownPercent = 80, confidence = 0.874f))
    }

    @Test
    fun `a real change redraws the label with the new bucket`() {
        assertEquals(75, stickyConfidencePercent(shownPercent = 80, confidence = 0.77f))
        assertEquals(85, stickyConfidencePercent(shownPercent = 80, confidence = 0.88f))
        assertEquals(40, stickyConfidencePercent(shownPercent = 80, confidence = 0.42f))
    }
}

class LabelHitChoiceTest {
    private fun hit(surface: LabelSurface, distance: Float) = LabelHitCandidate(surface, distance)

    @Test
    fun `depth along the centre ray wins over the plane behind the object`() {
        val centre = listOf(
            hit(LabelSurface.PlaneInsidePolygon, 2.4f), // the wall behind
            hit(LabelSurface.Depth, 0.8f), // the object itself
        )
        val base = listOf(hit(LabelSurface.PlaneInsidePolygon, 0.9f))

        assertEquals(LabelHitChoice(LabelRay.Centre, 1), chooseLabelHit(centre, base))
    }

    @Test
    fun `without depth the label goes to the support under the object, not to the wall behind`() {
        val centre = listOf(hit(LabelSurface.PlaneInsidePolygon, 2.4f))
        val base = listOf(
            hit(LabelSurface.PlaneInsidePolygon, 1.7f),
            hit(LabelSurface.PlaneInsidePolygon, 0.9f),
        )

        assertEquals(LabelHitChoice(LabelRay.Base, 1), chooseLabelHit(centre, base))
    }

    @Test
    fun `a plane hit outside its polygon is not a surface`() {
        // ARCore also reports where the ray meets the plane's infinite extension.
        val base = listOf(hit(LabelSurface.Other, 0.9f))

        assertEquals(null, chooseLabelHit(emptyList(), base))
    }

    @Test
    fun `a plane along the centre ray alone places no label`() {
        val centre = listOf(hit(LabelSurface.PlaneInsidePolygon, 2.4f))

        assertEquals(null, chooseLabelHit(centre, emptyList()))
    }

    @Test
    fun `feature points are the last resort, centre ray first`() {
        val centre = listOf(hit(LabelSurface.FeaturePoint, 1.1f))
        val base = listOf(hit(LabelSurface.FeaturePoint, 0.7f))

        assertEquals(LabelHitChoice(LabelRay.Centre, 0), chooseLabelHit(centre, base))
        assertEquals(LabelHitChoice(LabelRay.Base, 0), chooseLabelHit(emptyList(), base))
        assertEquals(
            LabelHitChoice(LabelRay.Base, 1),
            chooseLabelHit(centre, base + hit(LabelSurface.Depth, 0.75f)),
        )
    }

    @Test
    fun `the nearest hit of a kind wins whatever the list order, out of range ignored`() {
        val centre = listOf(
            hit(LabelSurface.Depth, 0.05f), // inside the minimum focus distance
            hit(LabelSurface.Depth, 3.5f),
            hit(LabelSurface.Depth, 1.4f),
            hit(LabelSurface.Depth, 6f), // beyond useful tracking
        )

        assertEquals(LabelHitChoice(LabelRay.Centre, 2), chooseLabelHit(centre, emptyList()))
        assertEquals(
            null,
            chooseLabelHit(listOf(hit(LabelSurface.Depth, 0.05f), hit(LabelSurface.Depth, 6f)), emptyList()),
        )
    }
}

class ReanchorToleranceTest {
    @Test
    fun `hit jitter of a still object keeps the anchor`() {
        assertEquals(
            true,
            isWithinReanchorTolerance(floatArrayOf(0f, 0f, -1f), floatArrayOf(0.02f, 0.01f, -1.02f)),
        )
    }

    @Test
    fun `an object moved by ten centimetres is re-anchored`() {
        assertEquals(
            false,
            isWithinReanchorTolerance(floatArrayOf(0f, 0f, -1f), floatArrayOf(0.1f, 0f, -1f)),
        )
    }
}

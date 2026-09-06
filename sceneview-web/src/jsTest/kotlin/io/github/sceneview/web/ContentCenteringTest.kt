package io.github.sceneview.web

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for the [ContentCentering] bounding-box math used by the
 * library-level `autoCenterContent` feature (web port of iOS #1026 — #1052).
 */
class ContentCenteringTest {

    private fun aabb(min: DoubleArray, max: DoubleArray) = ContentCentering.Aabb(min, max)

    @Test
    fun unionOfEmptyListIsNull() {
        assertNull(ContentCentering.union(emptyList()), "Nothing to centre yet -> null union.")
    }

    @Test
    fun unionOfSingleBoxIsThatBox() {
        val box = aabb(doubleArrayOf(-1.0, -1.0, -1.0), doubleArrayOf(1.0, 1.0, 1.0))
        val union = ContentCentering.union(listOf(box))!!
        assertEquals(box, union)
    }

    @Test
    fun unionExpandsToCoverAllBoxes() {
        val a = aabb(doubleArrayOf(-2.0, 0.0, 0.0), doubleArrayOf(0.0, 1.0, 1.0))
        val b = aabb(doubleArrayOf(0.0, -3.0, -1.0), doubleArrayOf(4.0, 0.0, 5.0))
        val union = ContentCentering.union(listOf(a, b))!!
        assertEquals(-2.0, union.min[0]); assertEquals(-3.0, union.min[1]); assertEquals(-1.0, union.min[2])
        assertEquals(4.0, union.max[0]); assertEquals(1.0, union.max[1]); assertEquals(5.0, union.max[2])
    }

    @Test
    fun centerIsMidpointOfMinAndMax() {
        val box = aabb(doubleArrayOf(0.0, 0.0, -4.0), doubleArrayOf(2.0, 6.0, 0.0))
        val c = ContentCentering.center(box)
        assertEquals(1.0, c[0]); assertEquals(3.0, c[1]); assertEquals(-2.0, c[2])
    }

    @Test
    fun extentsIsPerAxisSize() {
        val box = aabb(doubleArrayOf(0.0, 0.0, -4.0), doubleArrayOf(2.0, 6.0, 0.0))
        val e = ContentCentering.extents(box)
        assertEquals(2.0, e[0]); assertEquals(6.0, e[1]); assertEquals(4.0, e[2])
    }

    @Test
    fun diagonalIsSpaceDiagonalLength() {
        // A 3-4-12 box has a space diagonal of 13.
        val box = aabb(doubleArrayOf(0.0, 0.0, 0.0), doubleArrayOf(3.0, 4.0, 12.0))
        assertEquals(13.0, ContentCentering.diagonal(box), 1e-9)
    }

    @Test
    fun diagonalOfNullIsZero() {
        assertEquals(0.0, ContentCentering.diagonal(null), "no content -> zero diagonal")
    }

    /**
     * #1540: a 2nd async model that grows the union must produce a strictly
     * larger diagonal — that growth is what re-arms the auto-center pass.
     */
    @Test
    fun unionDiagonalGrowsWhenASecondModelLands() {
        val first = aabb(doubleArrayOf(-0.5, -0.5, -0.5), doubleArrayOf(0.5, 0.5, 0.5))
        val firstDiagonal = ContentCentering.diagonal(ContentCentering.union(listOf(first)))

        val second = aabb(doubleArrayOf(3.0, -0.5, -0.5), doubleArrayOf(4.0, 0.5, 0.5))
        val unionDiagonal =
            ContentCentering.diagonal(ContentCentering.union(listOf(first, second)))

        assertTrue(
            unionDiagonal > firstDiagonal,
            "#1540: a 2nd model off to +x must grow the union diagonal so the pass re-frames",
        )
    }

    @Test
    fun isStableRejectsZeroExtentBox() {
        // Degenerate placeholder before resources finish loading.
        val box = aabb(doubleArrayOf(0.0, 0.0, 0.0), doubleArrayOf(0.0, 0.0, 0.0))
        assertFalse(ContentCentering.isStable(box))
    }

    @Test
    fun isStableRejectsNonFiniteBox() {
        // RealityKit/Filament empty box: min = +inf, max = -inf.
        val box = aabb(
            doubleArrayOf(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY),
            doubleArrayOf(Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY),
        )
        assertFalse(ContentCentering.isStable(box))
    }

    @Test
    fun isStableAcceptsRealContent() {
        val box = aabb(doubleArrayOf(-0.5, -0.5, -0.5), doubleArrayOf(0.5, 0.5, 0.5))
        assertTrue(ContentCentering.isStable(box))
    }

    @Test
    fun centeringOffsetIsNegatedCentroid() {
        // Content sitting at z = -2 should be pulled back to the origin.
        val box = aabb(doubleArrayOf(-1.0, -1.0, -3.0), doubleArrayOf(1.0, 1.0, -1.0))
        val offset = ContentCentering.centeringOffset(box)!!
        assertEquals(0.0, offset[0])
        assertEquals(0.0, offset[1])
        assertEquals(2.0, offset[2], "Centroid at z=-2 -> offset of +2 brings it to origin.")
    }

    @Test
    fun centeringOffsetIsNullForUnstableBox() {
        val degenerate = aabb(doubleArrayOf(0.0, 0.0, 0.0), doubleArrayOf(0.0, 0.0, 0.0))
        assertNull(ContentCentering.centeringOffset(degenerate))
        assertNull(ContentCentering.centeringOffset(null))
    }

    @Test
    fun alreadyCenteredContentGetsZeroOffset() {
        val box = aabb(doubleArrayOf(-1.0, -1.0, -1.0), doubleArrayOf(1.0, 1.0, 1.0))
        val offset = ContentCentering.centeringOffset(box)!!
        assertEquals(0.0, offset[0]); assertEquals(0.0, offset[1]); assertEquals(0.0, offset[2])
    }

    /**
     * Loading a 2nd model after the 1st was centered (#1357): the union of both models has a
     * different centroid, so re-running the centering pass must produce a *non-zero* offset.
     * `SceneView.loadModel` resets `didCenterContent` so this re-run actually happens — this
     * test pins the math that makes the reset worthwhile.
     */
    @Test
    fun loadingSecondModelShiftsCentroidSoReCenterIsNonZero() {
        // 1st model: centered around the origin; once it loads, offset is zero.
        val first = aabb(doubleArrayOf(-1.0, -1.0, -1.0), doubleArrayOf(1.0, 1.0, 1.0))
        val offsetAfterFirst = ContentCentering.centeringOffset(ContentCentering.union(listOf(first)))!!
        assertEquals(0.0, offsetAfterFirst[0])
        assertEquals(0.0, offsetAfterFirst[1])
        assertEquals(0.0, offsetAfterFirst[2])

        // 2nd model loaded off to +x — the combined content centroid moves, so a re-center
        // (only possible because `didCenterContent` was reset) yields a real translation.
        val second = aabb(doubleArrayOf(3.0, -1.0, -1.0), doubleArrayOf(5.0, 1.0, 1.0))
        val offsetAfterBoth =
            ContentCentering.centeringOffset(ContentCentering.union(listOf(first, second)))!!
        assertEquals(-2.0, offsetAfterBoth[0], "Union centroid at x=2 -> offset of -2.")
        assertEquals(0.0, offsetAfterBoth[1])
        assertEquals(0.0, offsetAfterBoth[2])
        assertTrue(
            offsetAfterBoth[0] != offsetAfterFirst[0],
            "A 2nd model must change the offset — otherwise re-centering is pointless.",
        )
    }

    // --- #2432: model { scale(...) } bounds scaling ----------------------------

    @Test
    fun scaleByOneReturnsAnEqualBox() {
        val box = aabb(doubleArrayOf(-1.0, -2.0, -3.0), doubleArrayOf(4.0, 5.0, 6.0))
        // The no-scale path must be a true identity — the auto-centre math is
        // unchanged for the default `scale == 1f`.
        assertEquals(box, ContentCentering.scale(box, 1.0))
    }

    @Test
    fun scaleMultipliesBothCornersUniformly() {
        val box = aabb(doubleArrayOf(-1.0, -2.0, -3.0), doubleArrayOf(2.0, 4.0, 6.0))
        val scaled = ContentCentering.scale(box, 2.0)
        assertEquals(-2.0, scaled.min[0]); assertEquals(-4.0, scaled.min[1]); assertEquals(-6.0, scaled.min[2])
        assertEquals(4.0, scaled.max[0]); assertEquals(8.0, scaled.max[1]); assertEquals(12.0, scaled.max[2])
    }

    @Test
    fun scaledBoxFeedsCenteringOffsetSoAScaledModelStaysCentred() {
        // A model whose unscaled box is centred at x=2 (off-origin). Scaling by
        // 2 moves the rendered centroid to x=4 — the centring offset must be
        // computed from the *scaled* box (-4), not the unscaled box (-2), or the
        // model lands off-centre. This pins the #2432 wiring invariant.
        val unscaled = aabb(doubleArrayOf(1.0, -1.0, -1.0), doubleArrayOf(3.0, 1.0, 1.0))
        val scaled = ContentCentering.scale(unscaled, 2.0)
        val offset = ContentCentering.centeringOffset(scaled)!!
        assertEquals(-4.0, offset[0], "Scaled centroid at x=4 -> offset of -4.")
        assertEquals(0.0, offset[1])
        assertEquals(0.0, offset[2])
    }

    @Test
    fun scaledBoxGrowsTheDiagonalSoAutoDollyFramesTheRenderedSize() {
        // The auto-dolly fits on the union diagonal; a 3x-scaled model must
        // report a 3x diagonal so the camera frames the rendered (not authored)
        // extent.
        val box = aabb(doubleArrayOf(-1.0, -1.0, -1.0), doubleArrayOf(1.0, 1.0, 1.0))
        val base = ContentCentering.diagonal(box)
        val scaled = ContentCentering.diagonal(ContentCentering.scale(box, 3.0))
        assertEquals(base * 3.0, scaled, 1e-9, "A 3x uniform scale must triple the diagonal.")
    }

    @Test
    fun nonPositiveScaleClampsToDegenerateBoxRatherThanInverting() {
        // A guard: a zero/negative factor must not produce an inverted (min>max)
        // box that would corrupt the union — it collapses to a zero-extent box
        // the stability gate then defers on.
        val box = aabb(doubleArrayOf(-2.0, -2.0, -2.0), doubleArrayOf(2.0, 2.0, 2.0))
        val scaled = ContentCentering.scale(box, -1.0)
        assertEquals(0.0, ContentCentering.diagonal(scaled), "Non-positive scale -> zero-extent box.")
        assertFalse(ContentCentering.isStable(scaled), "A degenerate box is not framable.")
    }

    @Test
    fun translatingByTheCenteringOffsetPutsTheBoxOnTheOrigin() {
        // The auto-dolly aims at the centre of the box it is given. The union is
        // measured BEFORE the content-root pivot moves it, so the fit must be
        // handed the moved box or it aims at where the content used to be.
        // A 3MF is authored in the positive octant by specification — 150 mm
        // spanning 0..0.15 m on every axis — which is exactly the case that made
        // the old code render the part a third of a frame off-centre (#3482).
        val authored = aabb(doubleArrayOf(0.0, 0.0, -0.15), doubleArrayOf(0.15, 0.15, 0.0))
        val offset = ContentCentering.centeringOffset(authored)!!
        val moved = ContentCentering.translated(authored, offset)!!
        val centre = ContentCentering.center(moved)
        assertEquals(0.0, centre[0], 1e-12, "Centred box must sit on the origin in x.")
        assertEquals(0.0, centre[1], 1e-12, "Centred box must sit on the origin in y.")
        assertEquals(0.0, centre[2], 1e-12, "Centred box must sit on the origin in z.")
    }

    @Test
    fun translatingKeepsTheExtentsSoTheDollyDistanceIsUnchanged() {
        // Only the centroid moves: the fit distance is derived from the diagonal,
        // which a translation must leave alone.
        val box = aabb(doubleArrayOf(0.0, 0.0, 0.0), doubleArrayOf(2.0, 4.0, 6.0))
        val moved = ContentCentering.translated(box, doubleArrayOf(-1.0, -2.0, -3.0))!!
        assertEquals(
            ContentCentering.diagonal(box),
            ContentCentering.diagonal(moved),
            1e-12,
            "A translation must not change the box diagonal.",
        )
    }

    @Test
    fun alreadyCentredContentIsUnmovedByItsOwnOffset() {
        // The regression guard in the other direction: the overwhelmingly common
        // case — a glTF authored around the origin — must be byte-identical
        // before and after, so this fix changes nothing for existing models.
        val box = aabb(doubleArrayOf(-1.0, -1.0, -1.0), doubleArrayOf(1.0, 1.0, 1.0))
        val offset = ContentCentering.centeringOffset(box)!!
        assertEquals(box, ContentCentering.translated(box, offset))
    }
}

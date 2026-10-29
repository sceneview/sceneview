package io.github.sceneview.demo.demos

import io.github.sceneview.demo.demos.internal.LivePlane
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.roundToInt

/**
 * Measure's live walls through a tracking loss. Measure reads no plane while ARCore has lost the
 * camera, and when tracking comes back ARCore finds the floor and the walls' planes again only a
 * few frames later. The walls wait for them, under the same ids, instead of all going at once on
 * the first frame back.
 */
class MeasureLiveWallsTrackingTest {
    private val walls = MeasureLiveWalls()

    /** The camera stands at 0, the floor 1.4 m under it. */
    private val floorY = -1.4f

    /** A vertical plane from (x0, z0) to (x1, z1), 0.1 m to 2.3 m over the floor. */
    private fun wall(id: Int, x0: Float, z0: Float, x1: Float, z1: Float) = LivePlane(
        id,
        LivePlane.Kind.Wall,
        floatArrayOf(
            x0, floorY + 0.1f, z0, x1, floorY + 0.1f, z1,
            x1, floorY + 2.3f, z1, x0, floorY + 2.3f, z0,
        ),
    )

    private val floor = LivePlane(
        1,
        LivePlane.Kind.Floor,
        floatArrayOf(-2f, floorY, -2.6f, 2f, floorY, -2.6f, 2f, floorY, 1f, -2f, floorY, 1f),
    )
    private val front = wall(2, -2f, -2.6f, 2f, -2.6f)
    private val side = wall(3, -2f, -2.6f, -2f, 1f)

    private var nanos = 10_000_000_000L

    /** The ids shown at each frame, 30 a second, for [seconds], ARCore reading [planes]. */
    private fun frames(seconds: Float, planes: List<LivePlane>, tracking: Boolean = true): List<Set<Int>> =
        List((seconds * FPS).roundToInt()) {
            nanos += NANOS_PER_FRAME
            walls.update(planes, cameraY = 0f, timestampNanos = nanos, tracking = tracking).walls.mapTo(HashSet()) { it.id }
        }

    @Test
    fun `the walls wait through a tracking loss for their planes to come back`() {
        val before = frames(1f, listOf(floor, front, side)).last()
        assertEquals("walls before the loss", 2, before.size)
        // Lost for two seconds: Measure reads no plane meanwhile.
        frames(2f, emptyList(), tracking = false).forEachIndexed { i, shown -> assertEquals("walls while lost, frame $i", before, shown) }
        // Back: no plane at all for a tenth of a second, then the floor alone, then the walls' planes.
        frames(0.1f, emptyList()).forEachIndexed { i, shown -> assertEquals("walls back with no plane, frame $i", before, shown) }
        frames(0.2f, listOf(floor)).forEachIndexed { i, shown -> assertEquals("walls back with the floor alone, frame $i", before, shown) }
        frames(1f, listOf(floor, front, side)).forEachIndexed { i, shown -> assertEquals("walls with their planes back, frame $i", before, shown) }
    }

    @Test
    fun `a wall whose plane does not come back still goes after the loss`() {
        assertEquals(2, frames(1f, listOf(floor, front, side)).last().size)
        frames(2f, emptyList(), tracking = false)
        assertEquals("a second after tracking came back without one wall's plane", 1, frames(1f, listOf(floor, front)).last().size)
    }

    private companion object {
        const val FPS = 30f
        const val NANOS_PER_FRAME = 33_333_333L
    }
}

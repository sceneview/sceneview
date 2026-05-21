package io.github.sceneview.ar.arcore

import io.github.sceneview.math.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Pins the pure projection + normal-estimation math behind [Frame.hitTestDepth] (#1712).
 *
 * The ARCore [com.google.ar.core.Frame] / depth [android.media.Image] plumbing cannot run in a
 * JVM unit test, so [unprojectDepthPixel] and [estimateNormal] are `internal` and verified here in
 * isolation — a sign error in either would otherwise only surface on a physical device.
 */
class DepthHitResultTest {

    // Synthetic pinhole intrinsics — focal length 100 px, principal point at (50, 40).
    private val fx = 100f
    private val fy = 100f
    private val cx = 50f
    private val cy = 40f

    @Test
    fun `principal-point pixel unprojects onto the optical axis`() {
        val p = unprojectDepthPixel(cx.toInt(), cy.toInt(), depthMeters = 2f, fx, fy, cx, cy)

        assertEquals(0f, p.x, EPSILON)
        assertEquals(0f, p.y, EPSILON)
        // Depth is a positive forward distance; ARCore camera space looks down -Z.
        assertEquals(-2f, p.z, EPSILON)
    }

    @Test
    fun `a pixel one focal length right of centre unprojects to +X = depth`() {
        val p = unprojectDepthPixel((cx + fx).toInt(), cy.toInt(), depthMeters = 2f, fx, fy, cx, cy)

        assertEquals(2f, p.x, EPSILON)
        assertEquals(0f, p.y, EPSILON)
    }

    @Test
    fun `a pixel above centre unprojects to +Y (image Y is flipped)`() {
        // Smaller pixelY is higher on screen, which is +Y up in ARCore camera space.
        val p = unprojectDepthPixel(cx.toInt(), (cy - fy).toInt(), depthMeters = 2f, fx, fy, cx, cy)

        assertEquals(2f, p.y, EPSILON)
    }

    @Test
    fun `normal of a horizontal floor points up toward a camera above it`() {
        // A floor lying in the XZ plane.
        val normal = estimateNormal(
            center = Position(0f, 0f, 0f),
            right = Position(1f, 0f, 0f),
            left = Position(-1f, 0f, 0f),
            up = Position(0f, 0f, -1f),
            down = Position(0f, 0f, 1f),
            cameraPosition = Position(0f, 5f, 0f)
        )

        assertEquals(0f, normal.x, EPSILON)
        assertEquals(1f, normal.y, EPSILON)
        assertEquals(0f, normal.z, EPSILON)
    }

    @Test
    fun `normal is flipped to face a camera below the surface`() {
        val normal = estimateNormal(
            center = Position(0f, 0f, 0f),
            right = Position(1f, 0f, 0f),
            left = Position(-1f, 0f, 0f),
            up = Position(0f, 0f, -1f),
            down = Position(0f, 0f, 1f),
            cameraPosition = Position(0f, -5f, 0f)
        )

        assertEquals(-1f, normal.y, EPSILON)
    }

    @Test
    fun `degenerate neighbourhood falls back to facing the camera`() {
        val center = Position(0f, 0f, 0f)
        val normal = estimateNormal(
            center = center,
            right = center,
            left = center,
            up = center,
            down = center,
            cameraPosition = Position(0f, 0f, 3f)
        )

        assertEquals(0f, normal.x, EPSILON)
        assertEquals(0f, normal.y, EPSILON)
        assertEquals(1f, normal.z, EPSILON)
    }

    // ── Input validation (#1812) ──────────────────────────────────────────────────────────────────

    @Test
    fun `unprojectDepthPixel throws on zero focal length X`() {
        assertThrows(IllegalArgumentException::class.java) {
            unprojectDepthPixel(0, 0, depthMeters = 1f, fx = 0f, fy = fy, cx = cx, cy = cy)
        }
    }

    @Test
    fun `unprojectDepthPixel throws on zero focal length Y`() {
        assertThrows(IllegalArgumentException::class.java) {
            unprojectDepthPixel(0, 0, depthMeters = 1f, fx = fx, fy = 0f, cx = cx, cy = cy)
        }
    }

    companion object {
        private const val EPSILON = 1e-4f
    }
}

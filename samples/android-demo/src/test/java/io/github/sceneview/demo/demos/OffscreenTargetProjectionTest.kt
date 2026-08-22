package io.github.sceneview.demo.demos

import dev.romainguy.kotlin.math.Float4
import dev.romainguy.kotlin.math.Mat4
import io.github.sceneview.math.Position
import io.github.sceneview.math.Transform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Pure-JVM tests for [projectOffscreenTarget] — the world-to-screen-edge projection
 * behind the `ar-orbital` demo's off-screen directional arrows (#1482, #3269).
 *
 * No ARCore [com.google.ar.core.Frame]/[com.google.ar.core.Camera] involved: the camera
 * is described purely by a `view · projection` [Transform] plus a world-space
 * [Position], which is exactly what [OrbitalARDemo.kt]'s `computeOffscreenTarget`
 * wrapper hands the function once it reads those two values off the real ARCore
 * `Frame`. That split is what makes this testable on the JVM at all.
 *
 * The camera used throughout: sitting at the world origin, looking down -Z (the OpenGL
 * ES / ARCore `getProjectionMatrix` convention documented on [computeOffscreenTarget]),
 * +Y up — i.e. the view matrix is the identity, so `viewProjection == projection`. The
 * projection is built by hand ([standardPerspective]) rather than via a library helper:
 * `dev.romainguy.kotlin.math.perspective()` was tried first and turned out to use a
 * *different* handedness (`w == +z_view`, not ARCore's `w == -z_view`) — using it would
 * have tested this function against a camera convention production code never actually
 * hands it. A 90° vertical FOV, square aspect ratio, keeps the numbers easy to check by
 * hand: at `z = -1`, the visible half-extent on X and Y is exactly 1.
 */
class OffscreenTargetProjectionTest {

    private val cameraAtOrigin = Position(0f, 0f, 0f)

    // 90° vertical FOV, 1:1 aspect, near/far bracketing every distance used below.
    private val projection: Transform = standardPerspective(fovYDegrees = 90f, aspect = 1f, near = 0.05f, far = 30f)

    @Test
    fun `target dead ahead and well inside the frustum is on-screen`() {
        val result = projectOffscreenTarget(
            viewProjection = projection,
            cameraPosition = cameraAtOrigin,
            targetWorld = Position(0f, 0f, -5f),
        )
        assertNull("centre-screen target must report no off-screen indicator", result)
    }

    @Test
    fun `target far to the right is off-screen and points right`() {
        // At Z = -5 the 90°-FOV half-extent is 5 world units; X = 40 is far outside it.
        val result = projectOffscreenTarget(
            viewProjection = projection,
            cameraPosition = cameraAtOrigin,
            targetWorld = Position(40f, 0f, -5f),
        )
        assertNotNull("target far outside the X extent must be off-screen", result)
        // Compose screen space: 0 rad = +X = right.
        assertEquals(0f, result!!.angleRad, 0.05f)
    }

    @Test
    fun `target far above is off-screen and points up`() {
        val result = projectOffscreenTarget(
            viewProjection = projection,
            cameraPosition = cameraAtOrigin,
            targetWorld = Position(0f, 40f, -5f),
        )
        assertNotNull(result)
        // Compose screen Y points down, so "world up" is negative angle (-π/2).
        assertEquals(-(Math.PI / 2).toFloat(), result!!.angleRad, 0.05f)
    }

    @Test
    fun `target directly behind the camera is off-screen`() {
        // clip.w <= 0 path: the perspective divide would mirror x/y, so this must
        // still resolve to *some* off-screen direction rather than reporting on-screen.
        val result = projectOffscreenTarget(
            viewProjection = projection,
            cameraPosition = cameraAtOrigin,
            targetWorld = Position(0f, 0f, 5f),
        )
        assertNotNull("a target behind the camera must never read as on-screen", result)
    }

    @Test
    fun `distance is the straight-line distance from the camera, not from the origin`() {
        val cameraPosition = Position(1f, 2f, 3f)
        // Far off to the right so it is guaranteed off-screen regardless of camera offset.
        val targetWorld = Position(1f + 100f, 2f, 3f - 5f)
        val result = projectOffscreenTarget(
            viewProjection = projection,
            cameraPosition = cameraPosition,
            targetWorld = targetWorld,
        )
        assertNotNull(result)
        val expectedDistance = sqrt(100f * 100f + 5f * 5f)
        assertEquals(expectedDistance, result!!.distanceMeters, 0.01f)
    }

    @Test
    fun `target exactly on the frustum boundary is treated as on-screen`() {
        // At Z = -5 with a 90° FOV the boundary is X = 5 exactly (ndcX == 1).
        val result = projectOffscreenTarget(
            viewProjection = projection,
            cameraPosition = cameraAtOrigin,
            targetWorld = Position(5f, 0f, -5f),
        )
        assertNull("the [-1, 1] bound is inclusive, so the exact edge is on-screen", result)
    }

    @Test
    fun `target just past the frustum boundary is off-screen`() {
        val result = projectOffscreenTarget(
            viewProjection = projection,
            cameraPosition = cameraAtOrigin,
            targetWorld = Position(5.5f, 0f, -5f),
        )
        assertTrue("just past the boundary must be off-screen", result != null)
    }

    /**
     * Hand-rolled symmetric-frustum perspective matrix, standard OpenGL ES convention
     * (camera looks down -Z, `w = -z_view`) — the same convention ARCore's
     * `Camera.getProjectionMatrix` documents. Column-major, matching
     * `dev.romainguy.kotlin.math.Mat4`'s `Mat4(col0, col1, col2, col3)` constructor.
     */
    private fun standardPerspective(fovYDegrees: Float, aspect: Float, near: Float, far: Float): Mat4 {
        val f = 1f / tan(Math.toRadians(fovYDegrees / 2.0)).toFloat()
        return Mat4(
            Float4(f / aspect, 0f, 0f, 0f),
            Float4(0f, f, 0f, 0f),
            Float4(0f, 0f, (far + near) / (near - far), -1f),
            Float4(0f, 0f, (2f * far * near) / (near - far), 0f),
        )
    }
}

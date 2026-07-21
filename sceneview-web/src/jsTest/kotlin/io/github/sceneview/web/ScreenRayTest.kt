package io.github.sceneview.web

import dev.romainguy.kotlin.math.Float4
import dev.romainguy.kotlin.math.inverse
import io.github.sceneview.collision.Vector3
import io.github.sceneview.math.Position
import io.github.sceneview.math.Transform
import io.github.sceneview.math.toTransform
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * #2024 P5c — screen-point → world-ray unprojection, proven without the
 * engine. The matrices are hand-built with the standard OpenGL perspective
 * layout — the exact layout Filament's `getProjectionMatrix()` returns
 * (asserted in-browser by the `kotlin-bundle.spec.ts` P5c probe).
 */
class ScreenRayTest {

    private val eps = 1e-3f

    private fun assertClose(expected: Float, actual: Float, message: String) {
        assertTrue(abs(expected - actual) < eps, "$message: expected $expected, was $actual")
    }

    /** Standard column-major OpenGL perspective — what Filament produces. */
    private fun perspectiveProjection(
        fovYDegrees: Float,
        aspect: Float,
        near: Float,
        far: Float,
    ): Transform {
        val f = 1f / tan(fovYDegrees * PI.toFloat() / 360f)
        val cols = FloatArray(16)
        cols[0] = f / aspect
        cols[5] = f
        cols[10] = (far + near) / (near - far)
        cols[11] = -1f
        cols[14] = 2f * far * near / (near - far)
        return cols.toTransform()
    }

    private val projection = perspectiveProjection(90f, 1f, 0.1f, 100f)

    @Test
    fun centerOfScreenLooksStraightDownMinusZ() {
        val ray = screenPointToRay(
            x = 300f, y = 300f, viewportWidth = 600f, viewportHeight = 600f,
            projection = projection, cameraModel = Transform(),
        )
        val d = ray.getDirection()
        assertClose(0f, d.x, "direction.x")
        assertClose(0f, d.y, "direction.y")
        assertClose(-1f, d.z, "direction.z")
        val o = ray.getOrigin()
        assertClose(0f, o.x, "origin.x")
        assertClose(-0.1f, o.z, "origin.z is on the near plane")
    }

    @Test
    fun translatedCameraShiftsTheRayOrigin() {
        val ray = screenPointToRay(
            x = 300f, y = 300f, viewportWidth = 600f, viewportHeight = 600f,
            projection = projection,
            cameraModel = Transform(position = Position(0f, 0f, 5f)),
        )
        val o = ray.getOrigin()
        assertClose(0f, o.x, "origin.x")
        assertClose(0f, o.y, "origin.y")
        assertClose(4.9f, o.z, "origin.z = camera z minus near")
        assertClose(-1f, ray.getDirection().z, "direction.z")
    }

    @Test
    fun cornerRayDivergesDiagonally() {
        // fov 90° + aspect 1 → the top-right corner (NDC 1,1) looks along
        // normalize(1, 1, -1).
        val ray = screenPointToRay(
            x = 600f, y = 0f, viewportWidth = 600f, viewportHeight = 600f,
            projection = projection, cameraModel = Transform(),
        )
        val d = ray.getDirection()
        val c = 1f / sqrt(3f)
        assertClose(c, d.x, "direction.x")
        assertClose(c, d.y, "direction.y")
        assertClose(-c, d.z, "direction.z")
    }

    @Test
    fun roundTripThroughAProjectedWorldPoint() {
        // Project a known world point with the same matrices, then unproject
        // its screen position — the resulting ray must pass through the point.
        val width = 800f
        val height = 600f
        val projection = perspectiveProjection(60f, width / height, 0.1f, 100f)
        val cameraModel = Transform(position = Position(2f, 1f, 8f))
        val world = Float4(1f, -0.5f, 3f, 1f)

        val view = inverse(cameraModel) * world
        val clip = projection * view
        val ndcX = clip.x / clip.w
        val ndcY = clip.y / clip.w
        val screenX = (ndcX + 1f) / 2f * width
        val screenY = (1f - ndcY) / 2f * height

        val ray = screenPointToRay(screenX, screenY, width, height, projection, cameraModel)

        // Distance from the point to the ray line: |d × (P - O)| with |d| = 1.
        val toPoint = Vector3.subtract(
            Vector3(world.x, world.y, world.z),
            ray.getOrigin(),
        )
        val distance = Vector3.cross(ray.getDirection(), toPoint).length()
        assertTrue(distance < eps, "ray must pass through the projected point (distance=$distance)")
    }
}

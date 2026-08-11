package io.github.sceneview.math

import dev.romainguy.kotlin.math.Float2
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Float4
import dev.romainguy.kotlin.math.Mat4
import dev.romainguy.kotlin.math.inverse
import kotlin.math.abs
import kotlin.math.tan
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CameraProjectionTest {

    /** Build a simple symmetric perspective projection matrix. */
    private fun perspectiveMatrix(
        fovYDegrees: Float,
        aspect: Float,
        near: Float,
        far: Float
    ): Mat4 {
        val fovRad = fovYDegrees * (kotlin.math.PI.toFloat() / 180f)
        val f = 1f / tan(fovRad / 2f)
        val rangeInv = 1f / (near - far)
        return Mat4(
            Float4(f / aspect, 0f, 0f, 0f),
            Float4(0f, f, 0f, 0f),
            Float4(0f, 0f, (far + near) * rangeInv, -1f),
            Float4(0f, 0f, 2f * far * near * rangeInv, 0f)
        )
    }

    /** Build a simple look-at view matrix. */
    private fun lookAtMatrix(eye: Float3, center: Float3, up: Float3): Mat4 {
        val f = run {
            val d = center - eye
            val len = kotlin.math.sqrt(d.x * d.x + d.y * d.y + d.z * d.z)
            Float3(d.x / len, d.y / len, d.z / len)
        }
        val s = run {
            val c = Float3(f.y * up.z - f.z * up.y, f.z * up.x - f.x * up.z, f.x * up.y - f.y * up.x)
            val len = kotlin.math.sqrt(c.x * c.x + c.y * c.y + c.z * c.z)
            Float3(c.x / len, c.y / len, c.z / len)
        }
        val u = Float3(s.y * f.z - s.z * f.y, s.z * f.x - s.x * f.z, s.x * f.y - s.y * f.x)
        return Mat4(
            Float4(s.x, u.x, -f.x, 0f),
            Float4(s.y, u.y, -f.y, 0f),
            Float4(s.z, u.z, -f.z, 0f),
            Float4(
                -(s.x * eye.x + s.y * eye.y + s.z * eye.z),
                -(u.x * eye.x + u.y * eye.y + u.z * eye.z),
                f.x * eye.x + f.y * eye.y + f.z * eye.z,
                1f
            )
        )
    }

    @Test
    fun viewToWorldCenterWithIdentity() {
        val worldPos = viewToWorld(Float2(0.5f, 0.5f), 1.0f, Mat4.identity(), Mat4.identity())
        assertTrue(abs(worldPos.x) < 0.01f, "x should be near 0, got ${worldPos.x}")
        assertTrue(abs(worldPos.y) < 0.01f, "y should be near 0, got ${worldPos.y}")
    }

    @Test
    fun worldToViewCenterProjectsToCenter() {
        val proj = perspectiveMatrix(60f, 1f, 0.1f, 100f)
        val view = lookAtMatrix(Float3(0f, 0f, 5f), Float3(0f, 0f, 0f), Float3(0f, 1f, 0f))

        val viewPos = worldToView(Float3(0f, 0f, 0f), proj, view)
        assertNotNull(viewPos, "A point in front of the camera must project to a non-null coordinate")
        assertTrue(abs(viewPos.x - 0.5f) < 0.1f, "Origin should project near center x, got ${viewPos.x}")
        assertTrue(abs(viewPos.y - 0.5f) < 0.1f, "Origin should project near center y, got ${viewPos.y}")
    }

    // ── worldToView near-plane guard ─────────────────────────────────────────────────────────
    // A world point at or behind the camera's eye plane has clip-space w <= 0. Dividing by a non-positive w
    // produces a finite, MIRRORED coordinate on the wrong side of the view (or NaN/Inf right on the
    // plane) — silently wrong, so no isFinite check downstream catches it. worldToView must return
    // null for such points. (This is what made an AR bbox overlay blink as the camera panned and a
    // corner crossed the eye plane.)
    //
    // Conventions of the matrix builders above (standard OpenGL: the camera looks down -Z, so
    // clip.w = -viewZ): with eye at z = 5 looking at the origin, points at world z < 5 are IN FRONT
    // (w > 0), world z = 5 is exactly on the eye/near boundary (w = 0), and world z > 5 is BEHIND
    // (w < 0). All the coordinates below were verified numerically against these exact builders.

    /** The exact pre-fix math, inlined so the regression baseline is real (no w guard). */
    private fun unguardedWorldToView(worldPosition: Float3, proj: Mat4, view: Mat4): Float2 {
        val clip = (proj * view) * Float4(worldPosition, w = 1.0f)
        return (clip / clip.w).xy / 2.0f + 0.5f
    }

    @Test
    fun worldToViewBehindCameraWasAFiniteMirroredPointBeforeTheFix() {
        // Reproduce baseline: prove the pre-fix behaviour this guard replaces. An off-axis corner
        // BEHIND the camera (world z = 12, up-and-right) had w = -7 and pre-fix projected to a
        // finite (0.25, 0.31) — mirrored to the lower-LEFT although the corner is upper-right.
        val proj = perspectiveMatrix(60f, 1f, 0.1f, 100f)
        val view = lookAtMatrix(Float3(0f, 0f, 5f), Float3(0f, 0f, 0f), Float3(0f, 1f, 0f))
        val behindOffAxis = Float3(2f, 1.5f, 12f)

        val clipW = ((proj * view) * Float4(behindOffAxis, w = 1.0f)).w
        assertTrue(clipW <= 0f, "precondition: behind-camera point must have w <= 0, got $clipW")

        val raw = unguardedWorldToView(behindOffAxis, proj, view)
        // The whole reason the bug was silent: a perfectly finite pixel, not NaN/Inf.
        assertTrue(raw.x.isFinite() && raw.y.isFinite(), "pre-fix value must be finite, got $raw")
        // And it is MIRRORED: the corner is up-and-right (would be x>0.5, y>0.5 in front) yet lands
        // lower-left. This is precisely the wrong-side pixel the guard must suppress.
        assertTrue(raw.x < 0.5f && raw.y < 0.5f, "pre-fix behind-camera point is mirrored, got $raw")
    }

    @Test
    fun worldToViewReturnsNullForPointBehindEyePlane() {
        val proj = perspectiveMatrix(60f, 1f, 0.1f, 100f)
        val view = lookAtMatrix(Float3(0f, 0f, 5f), Float3(0f, 0f, 0f), Float3(0f, 1f, 0f))

        // world z = 10 → w = -5, behind the camera.
        assertNull(
            worldToView(Float3(0f, 0f, 10f), proj, view),
            "A point behind the camera's eye plane has no view position"
        )
    }

    @Test
    fun worldToViewReturnsNullForPointOnTheEyePlaneBoundary() {
        val proj = perspectiveMatrix(60f, 1f, 0.1f, 100f)
        val view = lookAtMatrix(Float3(0f, 0f, 5f), Float3(0f, 0f, 0f), Float3(0f, 1f, 0f))

        // Exactly on the eye plane (world z = 5) → w == 0. The divide is undefined (pre-fix this
        // off-axis point produced (Inf, NaN)); the `w <= 0` guard must report null.
        assertNull(
            worldToView(Float3(1f, 0f, 5f), proj, view),
            "A point on the near-plane boundary (w == 0) has no view position"
        )
    }

    @Test
    fun worldToViewReturnsNullForOffAxisPointBehindTheCamera() {
        // The real failure mode: a bbox corner up-and-to-the-side that crossed behind the near
        // plane. Pre-fix this gave the finite mirrored pixel proven above; post-fix it must be null.
        val proj = perspectiveMatrix(60f, 1f, 0.1f, 100f)
        val view = lookAtMatrix(Float3(0f, 0f, 5f), Float3(0f, 0f, 0f), Float3(0f, 1f, 0f))
        val behindOffAxis = Float3(2f, 1.5f, 12f)

        val clipW = ((proj * view) * Float4(behindOffAxis, w = 1.0f)).w
        assertTrue(clipW <= 0f, "precondition: this corner is behind the camera (w <= 0), got $clipW")
        assertNull(worldToView(behindOffAxis, proj, view))
    }

    @Test
    fun worldToViewIsUnchangedForPointsInFrontOfTheCamera() {
        // The guard must not perturb the normal (in-front) path: it matches the raw divide exactly.
        val proj = perspectiveMatrix(60f, 1f, 0.1f, 100f)
        val view = lookAtMatrix(Float3(0f, 0f, 5f), Float3(0f, 0f, 0f), Float3(0f, 1f, 0f))
        val front = Float3(2f, 1.5f, 0f) // in front (w = 5), off the view axis (upper-right)

        val guarded = worldToView(front, proj, view)
        val raw = unguardedWorldToView(front, proj, view)
        assertNotNull(guarded, "an in-front point must still project")
        assertTrue(abs(guarded.x - raw.x) < 1e-5f, "x must match the raw divide, got ${guarded.x} vs ${raw.x}")
        assertTrue(abs(guarded.y - raw.y) < 1e-5f, "y must match the raw divide, got ${guarded.y} vs ${raw.y}")
        // Correct side: upper-right corner in front projects right-and-up of centre (NOT mirrored).
        assertTrue(guarded.x > 0.5f && guarded.y > 0.5f, "in-front upper-right must stay upper-right, got $guarded")
    }

    @Test
    fun viewToRayDirectionIsNonZero() {
        val proj = perspectiveMatrix(60f, 1f, 0.1f, 100f)
        val view = lookAtMatrix(Float3(0f, 0f, 5f), Float3(0f, 0f, 0f), Float3(0f, 1f, 0f))

        val ray = viewToRay(Float2(0.5f, 0.5f), proj, view)
        val dirLen = kotlin.math.sqrt(
            ray.direction.x * ray.direction.x +
            ray.direction.y * ray.direction.y +
            ray.direction.z * ray.direction.z
        )
        assertTrue(dirLen > 0.01f, "Ray direction should be non-zero, got length $dirLen")
    }

    @Test
    fun viewToRayEdgePointsDiffer() {
        val proj = perspectiveMatrix(60f, 1f, 0.1f, 100f)
        val view = lookAtMatrix(Float3(0f, 0f, 5f), Float3(0f, 0f, 0f), Float3(0f, 1f, 0f))

        val leftRay = viewToRay(Float2(0f, 0.5f), proj, view)
        val rightRay = viewToRay(Float2(1f, 0.5f), proj, view)

        // Left and right rays should have different X directions
        assertTrue(
            abs(leftRay.direction.x - rightRay.direction.x) > 0.01f,
            "Left and right rays should differ in X direction"
        )
    }

    @Test
    fun exposureEV100SunnyDay() {
        // f/16, 1/125s, ISO 100 → sunny day, EV ~15
        val ev = exposureEV100(aperture = 16f, shutterSpeed = 1f / 125f, sensitivity = 100f)
        assertTrue(ev > 14f && ev < 16f, "EV100 should be ~15 for sunny day settings, got $ev")
    }

    @Test
    fun exposureFactorIsInverseOfEV() {
        val ev = 10f
        val factor = exposureFactor(ev)
        assertTrue(abs(factor - 0.1f) < 0.001f, "Factor should be 1/10, got $factor")
    }
}

package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class MemoryPalaceTest {

    private val lens = ReplayLens(0.5f, 0.66f)

    /** A camera at (0, 1.4, 0) turned [yawDegrees] about +Y: at 0 it looks down -Z. */
    private fun camera(yawDegrees: Float = 0f): DebugPose {
        val half = Math.toRadians(yawDegrees / 2.0)
        return DebugPose(0f, 1.4f, 0f, 0f, sin(half).toFloat(), 0f, cos(half).toFloat())
    }

    private fun assertVec(expected: Vec3, actual: Vec3, tolerance: Float = 1e-4f) {
        assertEquals(expected.x, actual.x, tolerance)
        assertEquals(expected.y, actual.y, tolerance)
        assertEquals(expected.z, actual.z, tolerance)
    }

    @Test
    fun `the window stands at the median depth of the points the lens saw`() {
        val points = ArrayList<Float>()
        // Nine points 1 to 3 m ahead, on the lens axis's neighbourhood: median 2 m.
        for (i in 0..8) points += listOf(0.05f * (i - 4), 1.4f, -(1f + 0.25f * i))
        // Behind the camera and far off to the side: not in the photo, ignored.
        repeat(20) { points += listOf(0f, 1.4f, 2f + it) }
        repeat(20) { points += listOf(5f + it, 1.4f, -1f) }
        val depth = MemoryPalace.windowDepth(camera(), lens, points.toFloatArray(), emptyList())
        assertEquals(2f, depth!!, 1e-4f)
    }

    @Test
    fun `too few points - the centre ray's plane decides`() {
        // A wall 3 m ahead, facing the camera.
        val wall = DebugPlane(
            1, DebugPlaneKind.Wall,
            floatArrayOf(-2f, 0f, -3f, 2f, 0f, -3f, 2f, 3f, -3f, -2f, 3f, -3f),
        )
        val depth = MemoryPalace.windowDepth(camera(), lens, floatArrayOf(0f, 1.4f, -1f), listOf(wall))
        assertEquals(3f, depth!!, 1e-4f)
        // Turned away from it, the ray misses: nothing measured, no window.
        assertNull(MemoryPalace.windowDepth(camera(180f), lens, FloatArray(0), listOf(wall)))
    }

    @Test
    fun `a ray outside a plane's polygon misses it`() {
        val offToTheSide = DebugPlane(
            2, DebugPlaneKind.Wall,
            floatArrayOf(3f, 0f, -3f, 5f, 0f, -3f, 5f, 3f, -3f, 3f, 3f, -3f),
        )
        assertNull(MemoryPalace.centreRayHit(camera(), listOf(offToTheSide)))
    }

    @Test
    fun `toLocal undoes the pose`() {
        val pose = DebugPose(0.3f, 1.2f, -0.7f, 0.1f, 0.3f, -0.2f, 0.927f).let {
            val n = kotlin.math.sqrt(it.qx * it.qx + it.qy * it.qy + it.qz * it.qz + it.qw * it.qw)
            it.copy(qx = it.qx / n, qy = it.qy / n, qz = it.qz / n, qw = it.qw / n)
        }
        val world = pose.transform(0.4f, -0.2f, -1.5f)
        assertVec(Vec3(0.4f, -0.2f, -1.5f), MemoryPalace.toLocal(pose, world.x, world.y, world.z))
    }

    @Test
    fun `lookPose looks at its target, upright`() {
        val eye = Vec3(2f, 3f, 4f)
        val target = Vec3(0f, 1f, 0f)
        val pose = MemoryPalace.lookPose(eye, target)
        assertVec(eye, pose.position)
        assertVec((target - eye).normalized(), pose.forward)
        // No roll: the camera's right stays level.
        assertEquals(0f, pose.rotate(1f, 0f, 0f).y, 1e-5f)
        assertEquals(true, pose.rotate(0f, 1f, 0f).y > 0f)
    }

    @Test
    fun `blend runs from one pose to the other`() {
        val a = MemoryPalace.lookPose(Vec3(3f, 2f, 3f), Vec3.Zero)
        val b = camera(40f)
        val start = MemoryPalace.blend(a, b, 0f)
        val end = MemoryPalace.blend(a, b, 1f)
        assertVec(a.position, start.position)
        assertVec(a.forward, start.forward)
        assertVec(b.position, end.position)
        assertVec(b.forward, end.forward)
        val mid = MemoryPalace.blend(a, b, 0.5f)
        assertVec((a.position + b.position) * 0.5f, mid.position)
        assertNotNull(mid)
    }

    @Test
    fun `the matrix places the pose's axes and position`() {
        val pose = camera(90f)
        val m = MemoryPalace.matrix(pose)
        // -Z (the view direction) turned 90° about +Y is -X.
        assertVec(Vec3(-1f, 0f, 0f), Vec3(-m[8], -m[9], -m[10]))
        assertVec(pose.position, Vec3(m[12], m[13], m[14]))
    }

    @Test
    fun `ease leaves and lands still`() {
        assertEquals(0f, MemoryPalace.ease(0f), 0f)
        assertEquals(1f, MemoryPalace.ease(1f), 0f)
        assertEquals(0.5f, MemoryPalace.ease(0.5f), 1e-6f)
        assertEquals(1f, MemoryPalace.ease(2f), 0f)
    }
}

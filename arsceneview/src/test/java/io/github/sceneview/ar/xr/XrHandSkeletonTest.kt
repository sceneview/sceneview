package io.github.sceneview.ar.xr

import io.github.sceneview.math.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM unit tests for [XrHandSkeleton] — the runtime-free joint / bone math
 * behind [XrHandNode].
 *
 * No Robolectric, no Filament, no `androidx.xr.arcore`: this file only touches
 * SceneView's own [Position] type, which is exactly the point of keeping the
 * skeleton math in its own file. These are the JVM tests the #1902 acceptance
 * criteria asks for ("JVM unit tests for any pure-Kotlin joint math").
 */
class XrHandSkeletonTest {

    private val tolerance = 1e-5f

    @Test
    fun `jointCount matches the XrHandJoint enum size`() {
        assertEquals(XrHandJoint.entries.size, XrHandSkeleton.jointCount)
    }

    @Test
    fun `every bone references joints within range and is non-degenerate`() {
        for (bone in XrHandSkeleton.BONES) {
            assertTrue(
                "bone endpoints must differ",
                bone.from != bone.to,
            )
        }
    }

    @Test
    fun `bone topology connects every joint to the skeleton`() {
        // Every joint except the wrist (the root) must appear as the `to` end of
        // at least one bone — otherwise it would float disconnected.
        val connected = XrHandSkeleton.BONES.map { it.to }.toSet()
        for (joint in XrHandJoint.entries) {
            if (joint == XrHandJoint.WRIST) continue
            assertTrue(
                "$joint is not connected by any bone",
                joint in connected || joint == XrHandJoint.PALM ||
                    XrHandSkeleton.BONES.any { it.from == joint },
            )
        }
    }

    @Test
    fun `bone count equals palm fan plus finger chains`() {
        // 5 palm-fan bones + thumb (3) + 4 fingers x 4 segments (16) = 24.
        assertEquals(24, XrHandSkeleton.BONES.size)
    }

    @Test
    fun `boneLength is the euclidean distance`() {
        val a = Position(0f, 0f, 0f)
        val b = Position(3f, 4f, 0f)
        assertEquals(5f, XrHandSkeleton.boneLength(a, b), tolerance)
    }

    @Test
    fun `boneLength of a point with itself is zero`() {
        val p = Position(1.5f, -2f, 0.3f)
        assertEquals(0f, XrHandSkeleton.boneLength(p, p), tolerance)
    }

    @Test
    fun `boneLength returns zero when an endpoint is untracked`() {
        val p = Position(1f, 2f, 3f)
        assertEquals(0f, XrHandSkeleton.boneLength(null, p), tolerance)
        assertEquals(0f, XrHandSkeleton.boneLength(p, null), tolerance)
        assertEquals(0f, XrHandSkeleton.boneLength(null, null), tolerance)
    }

    @Test
    fun `boneMidpoint is the average of the two endpoints`() {
        val a = Position(0f, 0f, 0f)
        val b = Position(2f, 4f, 6f)
        val mid = XrHandSkeleton.boneMidpoint(a, b)
        assertNotNull(mid)
        assertEquals(1f, mid!!.x, tolerance)
        assertEquals(2f, mid.y, tolerance)
        assertEquals(3f, mid.z, tolerance)
    }

    @Test
    fun `boneMidpoint returns null when an endpoint is untracked`() {
        assertNull(XrHandSkeleton.boneMidpoint(null, Position(1f, 1f, 1f)))
        assertNull(XrHandSkeleton.boneMidpoint(Position(1f, 1f, 1f), null))
    }

    @Test
    fun `lerp at zero returns the start position`() {
        val a = Position(1f, 2f, 3f)
        val b = Position(9f, 9f, 9f)
        val r = XrHandSkeleton.lerp(a, b, 0f)!!
        assertEquals(a.x, r.x, tolerance)
        assertEquals(a.y, r.y, tolerance)
        assertEquals(a.z, r.z, tolerance)
    }

    @Test
    fun `lerp at one returns the end position`() {
        val a = Position(1f, 2f, 3f)
        val b = Position(9f, 9f, 9f)
        val r = XrHandSkeleton.lerp(a, b, 1f)!!
        assertEquals(b.x, r.x, tolerance)
        assertEquals(b.y, r.y, tolerance)
        assertEquals(b.z, r.z, tolerance)
    }

    @Test
    fun `lerp at half returns the midpoint`() {
        val a = Position(0f, 0f, 0f)
        val b = Position(4f, 8f, 12f)
        val r = XrHandSkeleton.lerp(a, b, 0.5f)!!
        assertEquals(2f, r.x, tolerance)
        assertEquals(4f, r.y, tolerance)
        assertEquals(6f, r.z, tolerance)
    }

    @Test
    fun `lerp clamps t outside the unit interval`() {
        val a = Position(0f, 0f, 0f)
        val b = Position(10f, 0f, 0f)
        // t below 0 clamps to the start, t above 1 clamps to the end.
        assertEquals(0f, XrHandSkeleton.lerp(a, b, -2f)!!.x, tolerance)
        assertEquals(10f, XrHandSkeleton.lerp(a, b, 5f)!!.x, tolerance)
    }

    @Test
    fun `lerp returns null when an endpoint is untracked`() {
        assertNull(XrHandSkeleton.lerp(null, Position(1f, 1f, 1f), 0.5f))
        assertNull(XrHandSkeleton.lerp(Position(1f, 1f, 1f), null, 0.5f))
    }

    @Test
    fun `totalBoneLength of a fully untracked hand is zero`() {
        val joints = arrayOfNulls<Position>(XrHandSkeleton.jointCount)
        assertEquals(0f, XrHandSkeleton.totalBoneLength(joints), tolerance)
    }

    @Test
    fun `totalBoneLength sums every tracked bone`() {
        // Place every joint at the origin except the thumb tip, 1m away from the
        // thumb distal — only the THUMB_DISTAL→THUMB_TIP bone contributes.
        val joints = Array<Position?>(XrHandSkeleton.jointCount) { Position(0f, 0f, 0f) }
        joints[XrHandJoint.THUMB_TIP.ordinal] = Position(1f, 0f, 0f)
        // THUMB_DISTAL→THUMB_TIP = 1m. THUMB_TIP is only a `to` end, so exactly
        // one bone has non-zero length.
        assertEquals(1f, XrHandSkeleton.totalBoneLength(joints), tolerance)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `totalBoneLength rejects a wrongly-sized joints array`() {
        XrHandSkeleton.totalBoneLength(arrayOfNulls(3))
    }

    @Test
    fun `trackedJointCount counts non-null slots`() {
        val joints = arrayOfNulls<Position>(XrHandSkeleton.jointCount)
        assertEquals(0, XrHandSkeleton.trackedJointCount(joints))

        joints[XrHandJoint.PALM.ordinal] = Position(0f, 0f, 0f)
        joints[XrHandJoint.INDEX_TIP.ordinal] = Position(0f, 0.1f, 0f)
        assertEquals(2, XrHandSkeleton.trackedJointCount(joints))

        for (i in joints.indices) joints[i] = Position(0f, 0f, 0f)
        assertEquals(XrHandSkeleton.jointCount, XrHandSkeleton.trackedJointCount(joints))
    }
}

package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PlaneBakeTest {

    @Test
    fun `a floor's texture runs along X and Z, facing up from where the scan was walked`() {
        val rect = PlaneBake.rectOf(FLOOR, viewpoint = Vec3(0f, 2f, 0f))!!
        assertVec(Vec3(-1f, 0f, -1f), rect.origin)
        assertVec(Vec3(2f, 0f, 0f), rect.u)
        assertVec(Vec3(0f, 0f, 2f), rect.v)
        assertVec(Vec3(0f, 1f, 0f), rect.normal)
    }

    @Test
    fun `a wall's texture runs along it and down it, read upright from the room`() {
        val wall = DebugPlane(
            2,
            DebugPlaneKind.Wall,
            floatArrayOf(-1f, 0f, -2f, 1f, 0f, -2f, 1f, 2f, -2f, -1f, 2f, -2f),
        )
        val rect = PlaneBake.rectOf(wall, viewpoint = Vec3(0f, 1f, 0f))!!
        assertVec(Vec3(0f, 0f, 1f), rect.normal)
        assertVec(Vec3(-1f, 2f, -2f), rect.origin)
        assertVec(Vec3(2f, 0f, 0f), rect.u)
        assertVec(Vec3(0f, -2f, 0f), rect.v)
    }

    @Test
    fun `a degenerate plane has no texture`() {
        assertNull(PlaneBake.rectOf(DebugPlane(1, DebugPlaneKind.Floor, floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f))))
        val line = floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 2f, 0f, 0f)
        assertNull(PlaneBake.rectOf(DebugPlane(1, DebugPlaneKind.Floor, line)))
    }

    @Test
    fun `a texture has a texel a centimetre, its long side capped`() {
        val square = PlaneRect(Vec3.Zero, Vec3(2f, 0f, 0f), Vec3(0f, 0f, 2f), Vec3(0f, 1f, 0f))
        assertEquals(200 to 200, PlaneBake.sizeOf(square))
        val long = PlaneRect(Vec3.Zero, Vec3(10f, 0f, 0f), Vec3(0f, 0f, 1f), Vec3(0f, 1f, 0f))
        assertEquals(PlaneBake.MAX_SIDE to 52, PlaneBake.sizeOf(long))
    }

    @Test
    fun `the floor is painted from a photo looking down on it, each corner in its place`() {
        val rect = PlaneBake.rectOf(FLOOR, viewpoint = Vec3(0f, 2f, 0f))!!
        val photo = BakePhoto(LOOKING_DOWN, 4, 4, quadrants())
        val texels = PlaneBake.bake(rect, 4, 4, listOf(photo), LENS)!!.argb

        // Looking down, the photo's top is -Z (the floor's small t) and its right is +X.
        assertEquals(RED, texels[0 * 4 + 0])
        assertEquals(BLUE, texels[0 * 4 + 3])
        assertEquals(GREEN, texels[3 * 4 + 0])
        assertEquals(WHITE, texels[3 * 4 + 3])
    }

    @Test
    fun `what no photo saw is cropped away, a neutral rim where it was cut`() {
        val big = DebugPlane(1, DebugPlaneKind.Floor, floatArrayOf(-3f, 0f, -3f, 3f, 0f, -3f, 3f, 0f, 3f, -3f, 0f, 3f))
        val rect = PlaneBake.rectOf(big, viewpoint = Vec3(0f, 2f, 0f))!!
        val photo = BakePhoto(LOOKING_DOWN, 4, 4, IntArray(16) { RED })
        val baked = PlaneBake.bake(rect, 60, 60, listOf(photo), LENS)!!

        // The photo sees the middle 2 m of the 6 m floor: 20 texels, plus the margin each side.
        val side = 20 + 2 * BakeFinish.MARGIN
        assertEquals(side, baked.width)
        assertEquals(side, baked.height)
        assertEquals(side * 0.1f, baked.rect.u.length(), 1e-4f)
        assertEquals(RED, baked.argb[side / 2 * side + side / 2])
        val grey = baked.argb[0]
        assertEquals(grey shr 16 and 0xFF, grey and 0xFF)
        assertEquals(grey shr 8 and 0xFF, grey and 0xFF)
        assertTrue((0 until side).all { baked.argb[it] == grey && baked.argb[it * side] == grey })
    }

    @Test
    fun `a texel hidden behind something nearer is not painted from that photo`() {
        val rect = PlaneBake.rectOf(FLOOR, viewpoint = Vec3(0f, 2f, 0f))!!
        val photo = BakePhoto(LOOKING_DOWN, 4, 4, IntArray(16) { RED })
        // A table top a metre over the floor's -X half: the photo sees the table there, not the floor.
        val table = DebugPlane(
            9,
            DebugPlaneKind.Floor,
            floatArrayOf(-1f, 1f, -1f, 0f, 1f, -1f, 0f, 1f, 1f, -1f, 1f, 1f),
        )
        val occlusion = BakeOcclusion(FloatArray(0), listOf(FLOOR, table))
        val baked = PlaneBake.bake(rect, 40, 40, listOf(photo), LENS, occlusion, FLOOR.id)!!

        // Only the +X half was seen: the texture is cropped to it, the floor under the table left out.
        assertTrue(baked.rect.origin.x > -0.6f)
        assertTrue(baked.argb.any { it == RED })
    }

    @Test
    fun `furniture is not painted on the synthetic room's floor and walls`() {
        val scene = RerunSyntheticRoom.scene
        val room = scene.shapes[0]
        val furniture = scene.shapes.drop(1).flatMap { listOf(it.color(0f, 0f, 0f), it.color(0f, 0.5f, 0f)) }.toSet()
        val poses = RerunSyntheticRoom.poses()
        val lens = ReplayLens.of(PHOTO_W.toFloat(), PHOTO_H.toFloat(), PHOTO_F, PHOTO_F)!!
        val fusion = DenseFusion()
        val photos = poses.map { pose ->
            val frame = scene.render(pose, PHOTO_W, PHOTO_H, PHOTO_F, PHOTO_F)
            fusion.add(DepthBackProjection.project(frame))
            val pixels = IntArray(PHOTO_W * PHOTO_H) { frame.colors[it].takeIf { c -> c != 0 } ?: BLACK }
            BakePhoto(pose, PHOTO_W, PHOTO_H, pixels)
        }
        val planes = listOf(SYNTH_FLOOR, SYNTH_SAGE_WALL, SYNTH_RIGHT_WALL)
        val occlusion = BakeOcclusion.of(fusion.cloud(minViews = 1), planes)
        for (plane in planes) {
            val rect = PlaneBake.rectOf(plane, Vec3(0f, 1.4f, 0.6f))!!
            val (w, h) = PlaneBake.sizeOf(rect)
            val picked = PlaneBake.pickPhotos(rect, poses, lens).map { photos[it] }
            val baked = PlaneBake.bake(rect, w, h, picked, lens, occlusion, plane.id)!!
            var smeared = 0
            var wrong = 0
            for (y in 0 until baked.height) for (x in 0 until baked.width) {
                val c = baked.argb[y * baked.width + x]
                val p = baked.rect.at((x + 0.5f) / baked.width, (y + 0.5f) / baked.height)
                if (c in furniture) smeared++
                if (!isGrey(c) && !near(c, room.color(p.x, p.y, p.z))) wrong++
            }
            val total = baked.width * baked.height
            // Before #4589 the floor carried 37 % furniture, the walls 14 % and 28 %.
            assertTrue("plane ${plane.id}: $smeared of $total texels carry furniture", smeared * 100 < total)
            assertTrue("plane ${plane.id}: $wrong of $total texels wrong", wrong * 100 < total * 5)
        }
    }

    /** Within a blend of the truth: planks and the rug's border meet in a filtered texel. */
    private fun near(a: Int, b: Int) = (0..2).all { abs((a shr it * 8 and 0xFF) - (b shr it * 8 and 0xFF)) <= 48 }

    private fun isGrey(c: Int) = (c shr 16 and 0xFF) == (c and 0xFF) && (c shr 8 and 0xFF) == (c and 0xFF)

    @Test
    fun `a photo looking away paints nothing and is not picked`() {
        val rect = PlaneBake.rectOf(FLOOR, viewpoint = Vec3(0f, 2f, 0f))!!
        val lookingUp = DebugPose(0f, 2f, 0f, 0.70710677f, 0f, 0f, 0.70710677f)
        val photo = BakePhoto(lookingUp, 4, 4, IntArray(16) { RED })

        assertNull(PlaneBake.bake(rect, 4, 4, listOf(photo), LENS))
        assertEquals(listOf(1), PlaneBake.pickPhotos(rect, listOf(lookingUp, LOOKING_DOWN), LENS))
        assertNotNull(PlaneBake.bake(rect, 4, 4, listOf(BakePhoto(LOOKING_DOWN, 4, 4, quadrants())), LENS))
    }

    private fun quadrants() = IntArray(16) { i ->
        val left = i % 4 < 2
        val top = i / 4 < 2
        when {
            top && left -> RED
            top -> BLUE
            left -> GREEN
            else -> WHITE
        }
    }

    private fun assertVec(expected: Vec3, actual: Vec3) {
        assertEquals(expected.x, actual.x, 1e-5f)
        assertEquals(expected.y, actual.y, 1e-5f)
        assertEquals(expected.z, actual.z, 1e-5f)
    }

    private companion object {
        val FLOOR = DebugPlane(
            1,
            DebugPlaneKind.Floor,
            floatArrayOf(-1f, 0f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, -1f, 0f, 1f),
        )

        /** Two metres above the origin, looking straight down, the photo's top toward -Z. */
        val LOOKING_DOWN = DebugPose(0f, 2f, 0f, -0.70710677f, 0f, 0f, 0.70710677f)

        /** Sees two metres across at two metres: the whole floor. */
        val LENS = ReplayLens(0.5f, 0.5f)

        val RED = 0xFFFF0000.toInt()
        val BLUE = 0xFF0000FF.toInt()
        val GREEN = 0xFF00FF00.toInt()
        val WHITE = 0xFFFFFFFF.toInt()
        val BLACK = 0xFF000000.toInt()

        const val PHOTO_W = 192
        const val PHOTO_H = 108
        const val PHOTO_F = 150f

        // The synthetic room's floor and two of its walls, as ARCore would report them.
        val SYNTH_FLOOR = DebugPlane(
            1,
            DebugPlaneKind.Floor,
            floatArrayOf(-2f, 0f, -1.8f, 2f, 0f, -1.8f, 2f, 0f, 1.8f, -2f, 0f, 1.8f),
        )
        val SYNTH_SAGE_WALL = DebugPlane(
            2,
            DebugPlaneKind.Wall,
            floatArrayOf(-2f, 0f, -1.8f, 2f, 0f, -1.8f, 2f, 2.6f, -1.8f, -2f, 2.6f, -1.8f),
        )
        val SYNTH_RIGHT_WALL = DebugPlane(
            3,
            DebugPlaneKind.Wall,
            floatArrayOf(2f, 0f, -1.8f, 2f, 0f, 1.8f, 2f, 2.6f, 1.8f, 2f, 2.6f, -1.8f),
        )
    }
}

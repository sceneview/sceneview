package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The plane bake's decoded photos: each one decoded once, on the first plane that paints from it,
 * and let go after the last, so a room's bake holds a handful of photos at a time instead of every
 * photo it decoded, until the build ends.
 */
class BakePhotoCacheTest {

    @Test
    fun `a photo two planes paint from is decoded once`() {
        val decoded = ArrayList<Int>()
        val cache = BakePhotoCache(listOf(listOf(0, 1), listOf(1, 2))) { decoded += it; "photo $it" }
        assertEquals(listOf("photo 0", "photo 1"), cache.photosFor(0))
        assertEquals(listOf("photo 1", "photo 2"), cache.photosFor(1))
        assertEquals(listOf(0, 1, 2), decoded)
        assertEquals(3, cache.loads)
    }

    @Test
    fun `a photo is let go after the last plane that paints from it`() {
        val cache = BakePhotoCache(listOf(listOf(0, 1), listOf(1, 2), listOf(3))) { "photo $it" }
        cache.photosFor(0)
        assertEquals(setOf(1), cache.held)
        cache.photosFor(1)
        assertEquals(emptySet<Int>(), cache.held)
        cache.photosFor(2)
        assertEquals(emptySet<Int>(), cache.held)
        // Two photos at once at most, never all four the bake decodes.
        assertEquals(2, cache.peak)
        assertEquals(4, cache.loads)
    }

    @Test
    fun `a photo that does not decode is not decoded again, and does not count as held`() {
        val decoded = ArrayList<Int>()
        val cache = BakePhotoCache(listOf(listOf(0, 1), listOf(1), listOf(1, 2))) {
            decoded += it
            if (it == 1) null else "photo $it"
        }
        assertEquals(listOf("photo 0"), cache.photosFor(0))
        assertEquals(emptyList<String>(), cache.photosFor(1))
        assertEquals(listOf("photo 2"), cache.photosFor(2))
        assertEquals(listOf(0, 1, 2), decoded)
        assertEquals(1, cache.peak)
    }

    @Test
    fun `a plane that paints from no photo decodes nothing`() {
        val cache = BakePhotoCache(listOf(emptyList(), listOf(4))) { "photo $it" }
        assertEquals(emptyList<String>(), cache.photosFor(0))
        assertEquals(0, cache.loads)
        assertEquals(listOf("photo 4"), cache.photosFor(1))
    }

    /**
     * The cache lets a photo go after the last plane that paints from it, counted in the order
     * [BakePhotoCache.photosFor] is called: a plane asked for early, or twice, would find its
     * photos already gone and decode them again, or let go of photos a plane still to come needs.
     * So planes are asked for in order, each once, or the cache refuses.
     */
    @Test
    fun `planes asked for out of order, or twice, are refused`() {
        val picks = listOf(listOf(0), listOf(0, 1), listOf(1))
        val skipped = BakePhotoCache(picks) { "photo $it" }
        assertThrows(IllegalStateException::class.java) { skipped.photosFor(1) }
        val repeated = BakePhotoCache(picks) { "photo $it" }
        repeated.photosFor(0)
        assertThrows(IllegalStateException::class.java) { repeated.photosFor(0) }
        val inOrder = BakePhotoCache(picks) { "photo $it" }
        assertEquals(
            listOf(listOf("photo 0"), listOf("photo 0", "photo 1"), listOf("photo 1")),
            picks.indices.map(inOrder::photosFor),
        )
    }

    @Test
    fun `the log line gives the most photos held at once and how many were decoded`() {
        val cache = BakePhotoCache(listOf(listOf(0, 1), listOf(2))) { "photo $it" }
        cache.photosFor(0)
        cache.photosFor(1)
        assertEquals("photos held at once: at most 2 of 3 decoded", cache.logLine())
    }

    /**
     * The furnished synthetic room, walked the way a scan is (270 photo poses, near the scan's cap
     * of 300): its floor, four walls, the table and the sofa seat, each painted from the photos
     * [PlaneBake.pickPhotos] picks. Holding every decoded photo until the build ends kept all of
     * them in memory at once; letting each go after its last plane keeps only the most any one
     * plane, plus the photos it shares with planes still to come, needs.
     */
    @Test
    fun `the synthetic room's bake holds far fewer photos at once than it decodes`() {
        val poses = RerunSyntheticRoom.poses()
        val viewpoint = roomViewpoint()
        val picks = roomPlanes().map { plane ->
            PlaneBake.rectOf(plane, viewpoint)?.let { PlaneBake.pickPhotos(it, poses, ReplayLens.Default) }.orEmpty()
        }
        val decodedBefore = picks.flatten().distinct().size
        val cache = BakePhotoCache(picks) { it }
        picks.indices.forEach { cache.photosFor(it) }
        val mb = { photos: Int -> photos * BAKE_PHOTO_BYTES / (1024.0 * 1024.0) }
        println(
            "synthetic room bake: ${poses.size} photos, ${picks.size} planes; held at once " +
                "before $decodedBefore (%.1f MB), after ${cache.peak} (%.1f MB)".format(mb(decodedBefore), mb(cache.peak)),
        )
        assertEquals(decodedBefore, cache.loads)
        assertTrue("every plane paints from photos", picks.all { it.isNotEmpty() })
        assertTrue("held ${cache.peak} of $decodedBefore", cache.peak * 2 <= decodedBefore)
        assertEquals(emptySet<Int>(), cache.held)
    }

    /**
     * The release changes when a photo is held, never what a plane is painted with: on the
     * furnished synthetic room, photographed from every pose and occluded by its own dense cloud,
     * the bake that kept every decoded photo until the build ended and the bake that lets each go
     * after its last plane paint the very same texels.
     */
    @Test
    fun `the synthetic room's planes are painted the same as when every photo was kept`() {
        val scene = RerunSyntheticRoom.scene
        val poses = RerunSyntheticRoom.poses()
        val lens = ReplayLens.of(PHOTO_W.toFloat(), PHOTO_H.toFloat(), PHOTO_F, PHOTO_F)!!
        val fusion = DenseFusion()
        val shots = poses.map { pose ->
            val frame = scene.render(pose, PHOTO_W, PHOTO_H, PHOTO_F, PHOTO_F)
            fusion.add(DepthBackProjection.project(frame))
            val pixels = IntArray(PHOTO_W * PHOTO_H) { frame.colors[it].takeIf { c -> c != 0 } ?: BLACK }
            BakePhoto(pose, PHOTO_W, PHOTO_H, pixels)
        }
        // Each read a fresh copy, as a JPEG decode would be.
        fun decode(index: Int) = shots[index].let { BakePhoto(it.pose, it.width, it.height, it.argb.copyOf()) }
        val planes = roomPlanes()
        val occlusion = BakeOcclusion.of(fusion.cloud(minViews = 1), planes)
        val viewpoint = roomViewpoint()

        // Before: every photo decoded once and kept until the build ends.
        val kept = HashMap<Int, BakePhoto?>()
        val before = planes.map { plane ->
            val rect = PlaneBake.rectOf(plane, viewpoint) ?: return@map null
            val (width, height) = PlaneBake.sizeOf(rect)
            val picked = PlaneBake.pickPhotos(rect, poses, lens).mapNotNull { kept.getOrPut(it) { decode(it) } }
            PlaneBake.bake(rect, width, height, picked, lens, occlusion, plane.id)
        }

        // After: each photo let go after the last plane that paints from it.
        val rects = planes.map { PlaneBake.rectOf(it, viewpoint) }
        val picks = rects.map { rect -> rect?.let { PlaneBake.pickPhotos(it, poses, lens) }.orEmpty() }
        val cache = BakePhotoCache(picks, ::decode)
        val after = planes.indices.map { i ->
            val picked = cache.photosFor(i)
            val rect = rects[i] ?: return@map null
            val (width, height) = PlaneBake.sizeOf(rect)
            PlaneBake.bake(rect, width, height, picked, lens, occlusion, planes[i].id)
        }

        println("synthetic room bake, texels compared on ${before.count { it != null }} planes; held at once " +
            "before ${kept.size}, after ${cache.peak}")
        assertTrue("planes baked: ${before.count { it != null }}", before.count { it != null } >= 5)
        planes.forEachIndexed { i, plane ->
            val old = before[i]
            val new = after[i]
            assertEquals("plane ${plane.id} baked", old != null, new != null)
            if (old == null || new == null) return@forEachIndexed
            assertEquals("plane ${plane.id} width", old.width, new.width)
            assertEquals("plane ${plane.id} height", old.height, new.height)
            assertArrayEquals("plane ${plane.id} texels", old.argb, new.argb)
        }
        assertEquals(kept.size, cache.loads)
        assertTrue("held ${cache.peak} of ${kept.size}", cache.peak < kept.size)
        assertEquals(emptySet<Int>(), cache.held)
    }

    /** Where the room is seen from: the middle of the scan's stops. */
    private fun roomViewpoint(): Vec3 {
        val stops = RerunSyntheticRoom.STOPS
        return Vec3(
            stops.map { it.x }.average().toFloat(),
            stops.map { it.y }.average().toFloat(),
            stops.map { it.z }.average().toFloat(),
        )
    }

    private fun roomPlanes(): List<DebugPlane> {
        val lo = RerunSyntheticRoom.MIN
        val hi = RerunSyntheticRoom.MAX
        fun plane(id: Int, kind: DebugPlaneKind, vararg corners: Float) = DebugPlane(id, kind, corners)
        return listOf(
            plane(1, DebugPlaneKind.Floor, lo.x, 0f, lo.z, hi.x, 0f, lo.z, hi.x, 0f, hi.z, lo.x, 0f, hi.z),
            plane(2, DebugPlaneKind.Wall, lo.x, 0f, lo.z, hi.x, 0f, lo.z, hi.x, hi.y, lo.z, lo.x, hi.y, lo.z),
            plane(3, DebugPlaneKind.Wall, hi.x, 0f, lo.z, hi.x, 0f, hi.z, hi.x, hi.y, hi.z, hi.x, hi.y, lo.z),
            plane(4, DebugPlaneKind.Wall, hi.x, 0f, hi.z, lo.x, 0f, hi.z, lo.x, hi.y, hi.z, hi.x, hi.y, hi.z),
            plane(5, DebugPlaneKind.Wall, lo.x, 0f, hi.z, lo.x, 0f, lo.z, lo.x, hi.y, lo.z, lo.x, hi.y, hi.z),
            // The table top and the sofa seat, as ARCore finds them.
            plane(6, DebugPlaneKind.Floor, 0.3f, 0.76f, -0.3f, 1.3f, 0.76f, -0.3f, 1.3f, 0.76f, 0.4f, 0.3f, 0.76f, 0.4f),
            plane(7, DebugPlaneKind.Floor, -1.7f, 0.45f, -1.55f, -0.3f, 0.45f, -1.55f, -0.3f, 0.45f, -0.95f, -1.7f, 0.45f, -0.95f),
        )
    }

    private companion object {
        /** A photo decoded for the bake: 270 × 480 ARGB, the sharp photos' read size. */
        const val BAKE_PHOTO_BYTES = 270 * 480 * 4

        /** The synthetic photos: small, the lens of the room's depth camera at that size. */
        const val PHOTO_W = 96
        const val PHOTO_H = 54
        const val PHOTO_F = 75f
        const val BLACK = 0xFF000000.toInt()
    }
}

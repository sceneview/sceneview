package io.github.sceneview.demo.demos.internal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Room Scan's final fusion on up-to-date poses ([RerunAnchoredFrames]): depth frames kept
 * relative to an anchor come back, when the scan stops, where the anchor's corrected pose puts
 * them — so a wall seen twice with a drift between the two passes is one wall again — within a
 * byte cap and an anchor cap that thin the scan instead of growing.
 */
class RerunAnchoredFramesTest {

    @Test
    fun `a wall seen again after a 10 cm drift is one wall once its anchor is corrected, two without`() {
        val still = scanWallTwice(drift = DebugPose(0f, 0f, 0f), corrected = false)
        val doubled = scanWallTwice(drift = DRIFT, corrected = false)
        val corrected = scanWallTwice(drift = DRIFT, corrected = true)
        println(
            "wall seen twice, RMS thickness of the surfels / RMS distance of the mesh to the wall: " +
                "no drift ${mm(still.thickness)} / ${mm(still.meshOff)} mm, " +
                "10 cm drift ${mm(doubled.thickness)} / ${mm(doubled.meshOff)} mm, " +
                "drift corrected by the anchor ${mm(corrected.thickness)} / ${mm(corrected.meshOff)} mm",
        )
        // Without a drift the wall is as thin as millimetre depth allows.
        assertTrue("flat wall, ${mm(still.thickness)} mm thick", still.thickness < 0.002f)
        // The drift left alone: two sheets 10 cm apart, 5 cm from their middle each.
        assertEquals(0.05f, doubled.thickness, 0.01f)
        assertTrue("two sheets, ${doubled.surfels} surfels", doubled.surfels > still.surfels * 1.7f)
        assertTrue("mesh ${mm(doubled.meshOff)} mm off the wall", doubled.meshOff > RerunTsdf.VOXEL_M)
        // The anchor's corrected pose brings the second pass back onto the first.
        assertEquals(still.thickness, corrected.thickness, 0.0005f)
        assertEquals(still.surfels.toFloat(), corrected.surfels.toFloat(), still.surfels * 0.02f)
        assertEquals(still.meshOff, corrected.meshOff, 0.001f)
        assertEquals("the camera of the second pass moved back", 0.10f, corrected.refusion.maxShiftM, 0.001f)
        assertEquals(0f, doubled.refusion.maxShiftM, 1e-4f)
    }

    @Test
    fun `a frame follows its anchor through a correction that turns as well as moves`() {
        val store = RerunAnchoredFrames()
        val anchorThen = RerunSyntheticRoom.lookPose(Vec3(1f, 1.4f, 0.5f), 0.7f, -0.2f)
        val cameraThen = RerunSyntheticRoom.lookPose(Vec3(1.3f, 1.5f, 0.2f), 1.1f, 0.1f)
        val anchor = store.addAnchor(anchorThen)
        assertTrue(store.offer(FRAME.at(cameraThen)))
        assertPose(cameraThen, store.poses().single())
        // ARCore moves its map 14 cm and turns it 3°: the anchor follows, and the frame with it.
        val correction = RerunSyntheticRoom.lookPose(Vec3(0.12f, -0.03f, 0.08f), Math.toRadians(3.0).toFloat(), 0.01f)
        store.moveAnchor(anchor, correction.then(anchorThen))
        assertPose(correction.then(cameraThen), store.poses().single())
    }

    @Test
    fun `a frame kept before any anchor stays where it was captured`() {
        val store = RerunAnchoredFrames()
        val camera = RerunSyntheticRoom.lookPose(Vec3(0.2f, 1.4f, 0.1f), 0.4f, 0f)
        assertEquals(RerunAnchoredFrames.NO_ANCHOR, store.latestAnchor)
        store.offer(FRAME.at(camera))
        val later = store.addAnchor(camera)
        store.moveAnchor(later, DebugPose(5f, 5f, 5f))
        assertPose(camera, store.poses().single())
    }

    @Test
    fun `an anchor is due half a metre or 30 degrees from the last one`() {
        val store = RerunAnchoredFrames()
        assertTrue("the first frame wants one", store.anchorDue(DebugPose(0f, 1.4f, 0f)))
        store.addAnchor(DebugPose(0f, 1.4f, 0f))
        assertFalse(store.anchorDue(DebugPose(0.3f, 1.4f, 0.3f)))
        assertTrue(store.anchorDue(DebugPose(0.4f, 1.4f, 0.4f)))
        val turned = { degrees: Double ->
            RerunSyntheticRoom.lookPose(Vec3(0f, 1.4f, 0f), Math.toRadians(degrees).toFloat(), 0f)
        }
        assertFalse(store.anchorDue(turned(25.0)))
        assertTrue(store.anchorDue(turned(-35.0)))
    }

    @Test
    fun `the anchor cap lets every other anchor go, doubles the spacing, and moves no frame`() {
        val store = RerunAnchoredFrames(maxAnchors = 8)
        val detached = ArrayList<Int>()
        val captured = ArrayList<DebugPose>()
        var created = 0
        // 20 m in a straight line, a depth frame every 20 cm.
        for (i in 0 until 100) {
            val camera = DebugPose(i * 0.2f, 1.4f, 0f)
            if (store.anchorDue(camera)) {
                store.addAnchor(camera) { detached += it }
                created++
            }
            assertTrue("${store.anchorCount} anchors", store.anchorCount <= 8)
            store.offer(FRAME.at(camera))
            captured += camera
        }
        println(
            "20 m walked under a cap of 8: $created anchors created, ${detached.size} let go, " +
                "spacing ×${store.anchorSpacing}",
        )
        assertTrue("the cap was reached, $created anchors created", created > 8)
        assertEquals("every anchor is live or was handed back", created, store.anchorCount + detached.size)
        assertEquals(detached.size, detached.toSet().size)
        assertTrue("spacing ×${store.anchorSpacing}", store.anchorSpacing >= 4)
        // 0.6 m between the first anchors, then 1 m, 2 m and 4 m as the cap is reached again.
        assertFalse(store.anchorDue(DebugPose(19.8f + 0.6f, 1.4f, 0f)))
        // No anchor has moved, so no frame may have: re-parenting kept each one's pose.
        store.poses().forEachIndexed { i, pose -> assertPose(captured[i], pose) }
    }

    @Test
    fun `room made before the cap lets every other anchor go, moves no frame, and spaces the next ones`() {
        val store = RerunAnchoredFrames(maxAnchors = 40)
        // ARCore is the one out of room here: the store holds 6 anchors of the 40 it allows.
        val captured = ArrayList<DebugPose>()
        val ids = ArrayList<Int>()
        for (i in 0 until 6) {
            val camera = RerunSyntheticRoom.lookPose(Vec3(i * 0.7f, 1.4f, 0f), i * 0.2f, 0f)
            ids += store.addAnchor(camera)
            store.offer(FRAME.at(camera))
            captured += camera
        }
        val detached = ArrayList<Int>()
        assertTrue(store.makeRoom { detached += it })
        assertEquals(listOf(ids[1], ids[3], ids[5]), detached)
        assertEquals(3, store.anchorCount)
        assertEquals(2, store.anchorSpacing)
        store.poses().forEachIndexed { i, pose -> assertPose(captured[i], pose) }
        // The anchor ARCore now grants is one more, and the frames after it are tied to it.
        val next = store.addAnchor(DebugPose(5f, 1.4f, 0f)) { detached += it }
        assertEquals(3, detached.size)
        assertEquals(4, store.anchorCount)
        assertEquals(next, store.latestAnchor)
    }

    @Test
    fun `no room is made out of a single anchor`() {
        val store = RerunAnchoredFrames()
        assertFalse(store.makeRoom { error("nothing to let go") })
        val only = store.addAnchor(DebugPose(0f, 1.4f, 0f))
        assertFalse(store.makeRoom { error("the only anchor stays") })
        assertEquals(only, store.latestAnchor)
        assertEquals(1, store.anchorSpacing)
    }

    @Test
    fun `a frame whose anchor the cap let go keeps the correction it had earned, then follows its heir`() {
        val store = RerunAnchoredFrames(maxAnchors = 2)
        val p0 = DebugPose(0f, 1.4f, 0f)
        val p1 = RerunSyntheticRoom.lookPose(Vec3(1f, 1.4f, 0f), 0.5f, 0f)
        val camera = RerunSyntheticRoom.lookPose(Vec3(1.2f, 1.45f, 0.1f), 0.6f, 0.1f)
        val a = store.addAnchor(p0)
        store.offer(FRAME.at(p0))
        val b = store.addAnchor(p1)
        store.offer(FRAME.at(camera))
        // ARCore corrects b before the cap retires it.
        val g = RerunSyntheticRoom.lookPose(Vec3(0.05f, 0f, -0.08f), 0.03f, 0f)
        store.moveAnchor(b, g.then(p1))
        val detached = ArrayList<Int>()
        store.addAnchor(DebugPose(2f, 1.4f, 0f)) { detached += it }
        assertEquals(listOf(b), detached)
        assertEquals(2, store.anchorCount)
        assertPose(g.then(camera), store.poses()[1])
        // The frame now hangs from a, and b's later news is nobody's.
        val h = RerunSyntheticRoom.lookPose(Vec3(-0.02f, 0.01f, 0.04f), -0.02f, 0f)
        store.moveAnchor(a, h.then(p0))
        store.moveAnchor(b, DebugPose(9f, 9f, 9f))
        assertPose(h.then(g.then(camera)), store.poses()[1])
    }

    @Test
    fun `the byte cap halves the frames kept and takes every other one from then on`() {
        val frameBytes = FRAME.width * FRAME.height * RerunAnchoredFrames.BYTES_PER_PIXEL.toLong()
        val store = RerunAnchoredFrames(maxBytes = frameBytes * 10)
        val measured = FRAME.withConfidence()
        for (i in 0 until 100) {
            store.offer(measured.at(DebugPose(i.toFloat(), 0f, 0f)))
            assertTrue("${store.bytes} bytes", store.bytes <= store.maxBytes)
        }
        // Full at 10 frames: thinned on the 11th, the 21st, the 41st and the 81st offered.
        assertEquals(16, store.frameStride)
        assertEquals(listOf(0f, 16f, 32f, 48f, 64f, 80f, 96f), store.poses().map { it.x })
        assertEquals(7 * frameBytes, store.bytes)
    }

    @Test
    fun `a frame that outweighs the cap alone is refused and costs the store nothing`() {
        val frameBytes = FRAME.width * FRAME.height * RerunAnchoredFrames.BYTES_PER_PIXEL.toLong()
        val store = RerunAnchoredFrames(maxBytes = frameBytes * 3, maxFramePixels = 640 * 480)
        val small = FRAME.withConfidence()
        repeat(3) { assertTrue(store.offer(small)) }
        val huge = WALL.render(DebugPose(0f, 0f, 0f), width = 640, height = 480, fx = 500f, fy = 500f)
        assertFalse(store.offer(huge))
        assertEquals(3, store.frameCount)
        assertEquals(1, store.frameStride)
    }

    @Test
    fun `a time-of-flight sized depth image is kept every fourth pixel and fuses to the same wall`() = runBlocking {
        val store = RerunAnchoredFrames()
        val large = WALL.render(DebugPose(0f, 0f, 0f), width = 640, height = 480, fx = 500f, fy = 500f)
        assertTrue(store.offer(large))
        assertEquals(160 * 120 * 4L, store.bytes) // no confidence in a synthetic frame: 4 bytes a pixel
        val fusion = DenseFusion()
        store.drainInto(fusion)
        val cloud = fusion.cloud()
        assertTrue("${cloud.count} surfels", cloud.count > 10_000)
        val b = cloud.bounds()
        // The same view cone as the whole image: ±1.28 m across, ±0.96 m up, 2 m away.
        assertEquals(-1.28f, b[0], 0.04f)
        assertEquals(1.28f, b[3], 0.04f)
        assertEquals(-0.96f, b[1], 0.04f)
        assertEquals(0.96f, b[4], 0.04f)
        assertEquals(-2f, b[2], 0.002f)
        assertEquals(-2f, b[5], 0.002f)
    }

    @Test
    fun `colours survive their 16 bits within four levels, and black is not mistaken for none`() {
        assertEquals(0, Rgb565.unpack(Rgb565.pack(0)))
        assertEquals(0xFFFFFFFF.toInt(), Rgb565.unpack(Rgb565.pack(0xFFFFFFFF.toInt())))
        val black = Rgb565.unpack(Rgb565.pack(0xFF000000.toInt()))
        assertNotEquals(0, black)
        assertTrue("black stays dark", (black and 0xFF) <= 8 && (black shr 8 and 0xFFFF) == 0)
        for (v in 0..255) {
            val back = Rgb565.unpack(Rgb565.pack((0xFF shl 24) or (v shl 16) or (v shl 8) or v))
            assertEquals(0xFF, back ushr 24)
            assertTrue("red $v", abs((back shr 16 and 0xFF) - v) <= 4)
            assertTrue("green $v", abs((back shr 8 and 0xFF) - v) <= 2)
            // Blue alone carries the mark that tells black from "none": one step up at 0.
            assertTrue("blue $v", abs((back and 0xFF) - v) <= if (v < 4) 8 else 4)
        }
    }

    @Test
    fun `the re-fused wall keeps its colour`() = runBlocking {
        val store = RerunAnchoredFrames()
        store.offer(FRAME)
        store.offer(FRAME.at(DebugPose(0.05f, 0f, 0f)))
        val fusion = DenseFusion()
        store.drainInto(fusion)
        val cloud = fusion.cloud(minViews = DenseFusion.MIN_VIEWS)
        assertTrue(cloud.count > 1_000)
        for (color in cloud.colors) {
            assertTrue("red", abs((color shr 16 and 0xFF) - (OAK shr 16 and 0xFF)) <= 4)
            assertTrue("green", abs((color shr 8 and 0xFF) - (OAK shr 8 and 0xFF)) <= 2)
            assertTrue("blue", abs((color and 0xFF) - (OAK and 0xFF)) <= 4)
        }
    }

    @Test
    fun `the final fusion reports its progress and leaves the store empty`() = runBlocking {
        val store = RerunAnchoredFrames()
        repeat(12) { store.offer(FRAME.at(DebugPose(it * 0.02f, 0f, 0f))) }
        val seen = ArrayList<Float>()
        val tsdf = RerunTsdf()
        val done = store.drainInto(DenseFusion(), tsdf) { seen += it }
        assertEquals(12, done.frames)
        assertEquals(12, tsdf.frames)
        assertEquals(12, seen.size)
        assertEquals(seen.sorted(), seen)
        assertEquals(1f, seen.last(), 0f)
        assertEquals(0, store.frameCount)
        assertEquals(0L, store.bytes)
    }

    @Test
    fun `a cancelled final fusion stops between two frames and lets the frames go`() = runBlocking {
        val store = RerunAnchoredFrames()
        repeat(12) { store.offer(FRAME.at(DebugPose(it * 0.02f, 0f, 0f))) }
        val tsdf = RerunTsdf()
        lateinit var job: Job
        job = launch { store.drainInto(DenseFusion(), tsdf) { if (it >= 0.5f) job.cancel() } }
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(6, tsdf.frames)
        assertEquals(0, store.frameCount)
        assertEquals(0L, store.bytes)
    }

    @Test
    fun `with no correction the final fusion of the synthetic room is the live map, in a measured time`() {
        val poses = RerunSyntheticRoom.poses()
        val frames = poses.map { RerunSyntheticRoom.scene.render(it) }
        val live = DenseFusion()
        frames.forEach { live.add(DepthBackProjection.project(it)) }
        val store = RerunAnchoredFrames()
        frames.forEach { frame ->
            if (store.anchorDue(frame.pose)) store.addAnchor(frame.pose)
            store.offer(frame)
        }
        val kept = store.frameCount
        val keptBytes = store.bytes
        val anchors = store.anchorCount
        val fusion = DenseFusion()
        val tsdf = RerunTsdf()
        val start = System.nanoTime()
        val done = runBlocking(Dispatchers.Default) { store.drainInto(fusion, tsdf) }
        val ms = (System.nanoTime() - start) / 1_000_000
        println(
            "synthetic room: ${frames.size} frames offered, $kept kept in ${keptBytes / 1024} KB on $anchors anchors " +
                "(spacing ×${store.anchorSpacing}); final fusion in $ms ms, " +
                "${tenth(ms.toFloat() / kept)} ms a frame, surfels and TSDF side by side: " +
                "${fusion.count} surfels, ${tsdf.blockCount} TSDF blocks",
        )
        assertEquals(frames.size, done.frames)
        assertTrue("$anchors anchors", anchors in 2..RerunAnchoredFrames.MAX_ANCHORS)
        assertEquals(0f, done.maxShiftM, 1e-4f)
        assertEquals(frames.size, tsdf.frames)
        assertEquals(live.count.toFloat(), fusion.count.toFloat(), live.count * 0.01f)
    }

    /** What two passes over [WALL] fuse to: see [scanWallTwice]. */
    private class WallScan(
        val surfels: Int,
        val thickness: Float,
        val meshOff: Float,
        val refusion: AnchoredRefusion,
    )

    /**
     * A wall scanned twice, each pass under its own anchor. The first pass is where ARCore says;
     * in the second ARCore's poses are off by [drift], as after a walk around the room. With
     * [corrected], ARCore then recognises where it is and moves the second anchor back to the
     * truth, as an anchor's pose does when the map is corrected. The final fusion follows.
     */
    private fun scanWallTwice(drift: DebugPose, corrected: Boolean): WallScan = runBlocking {
        val store = RerunAnchoredFrames()
        store.addAnchor(FIRST_PASS.first())
        FIRST_PASS.forEach { store.offer(WALL.render(it)) }
        val reported = SECOND_PASS.map { drift.then(it) }
        val second = store.addAnchor(reported.first())
        SECOND_PASS.forEachIndexed { i, truth -> store.offer(WALL.render(truth).at(reported[i])) }
        if (corrected) store.moveAnchor(second, SECOND_PASS.first())
        val fusion = DenseFusion()
        val tsdf = RerunTsdf()
        val refusion = store.drainInto(fusion, tsdf)
        val cloud = fusion.cloud(minViews = DenseFusion.MIN_VIEWS)
        val mesh = RerunMarchingCubes.extract(tsdf, minComponentTriangles = 0).mesh
        WallScan(cloud.count, thickness(cloud), offWall(mesh), refusion)
    }

    /** RMS distance of the surfels to their mean plane across the view axis: the wall's thickness. */
    private fun thickness(cloud: DenseCloud): Float {
        var mean = 0.0
        for (i in 0 until cloud.count) mean += cloud.positions[i * 3 + 2]
        mean /= cloud.count
        var sum = 0.0
        for (i in 0 until cloud.count) {
            val d = cloud.positions[i * 3 + 2] - mean
            sum += d * d
        }
        return sqrt(sum / cloud.count).toFloat()
    }

    /** RMS distance of the mesh's vertices to the wall's true face. */
    private fun offWall(mesh: RerunMesh): Float {
        var sum = 0.0
        for (v in 0 until mesh.vertexCount) {
            val d = mesh.positions[v * 3 + 2] - WALL_Z
            sum += d * d
        }
        return sqrt(sum / mesh.vertexCount).toFloat()
    }

    private fun mm(metres: Float) = tenth(metres * 1000f)

    private fun tenth(value: Float) = "%.1f".format(Locale.US, value)

    private fun assertPose(expected: DebugPose, actual: DebugPose) {
        assertEquals("x", expected.x, actual.x, POSE_TOLERANCE)
        assertEquals("y", expected.y, actual.y, POSE_TOLERANCE)
        assertEquals("z", expected.z, actual.z, POSE_TOLERANCE)
        val dot = expected.qx * actual.qx + expected.qy * actual.qy + expected.qz * actual.qz + expected.qw * actual.qw
        assertEquals("rotation", 1f, abs(dot), POSE_TOLERANCE)
    }

    private fun DepthFrame.at(pose: DebugPose) =
        DepthFrame(width, height, depthMm, confidence, colors, fx, fy, cx, cy, pose)

    private fun DepthFrame.withConfidence() =
        DepthFrame(width, height, depthMm, ByteArray(width * height) { -1 }, colors, fx, fy, cx, cy, pose)

    private companion object {
        const val OAK = 0xFFB08457.toInt()
        const val WALL_Z = -2f
        const val POSE_TOLERANCE = 1e-4f

        /** A wall 2 m in front of a camera at the origin, which looks down -Z. */
        val WALL = SyntheticScene(
            listOf(SyntheticShape.Box(Vec3(-5f, -5f, -2.1f), Vec3(5f, 5f, WALL_Z)) { _, _, _ -> OAK }),
        )
        val FRAME: DepthFrame = WALL.render(DebugPose(0f, 0f, 0f))

        /** Where the camera truly stood, a few centimetres apart, on each pass. */
        val FIRST_PASS = listOf(
            DebugPose(0f, 0f, 0f),
            DebugPose(0.1f, 0.05f, 0f),
            DebugPose(-0.1f, 0.02f, 0f),
            DebugPose(0.05f, -0.08f, 0f),
        )
        val SECOND_PASS = listOf(
            DebugPose(0.03f, 0.02f, 0f),
            DebugPose(-0.06f, 0.04f, 0f),
            DebugPose(0.08f, -0.03f, 0f),
            DebugPose(-0.02f, -0.06f, 0f),
        )

        /** 10 cm along the view axis: the second pass lands the wall 10 cm in front of the first. */
        val DRIFT = DebugPose(0f, 0f, 0.10f)
    }
}

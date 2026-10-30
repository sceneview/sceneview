package io.github.sceneview.demo.demos.internal

import io.github.sceneview.demo.demos.internal.semantic.RecognizedItem
import io.github.sceneview.demo.demos.internal.semantic.SceneLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * A tap on a replayed scan (#4485): the piece of furniture under the finger, its oriented box to
 * within 2 cm when the scan went all round it, the name scene recognition gave it when one of its
 * boxes is that object, a word when the scan only saw one side of it, and its sizes in the units
 * the phone reads — pure Kotlin on synthetic rooms, no ARCore, no Filament.
 */
class ReplayTapTest {
    // ── Known sizes, seen all round ───────────────────────────────────────────

    @Test
    fun `furniture of known size seen from every side is boxed to within 2 cm, and not flagged`() {
        val scene = TapScene()
        scene.room(HALF_ROOM, HALF_ROOM, ROOM_HEIGHT)
        val pieces = listOf(
            Piece("cube", -1f, -0.9f, 0f, 0.6f, 0.6f, 0.6f),
            Piece("sideboard", 0.9f, -1f, 20f, 1.2f, 0.45f, 0.8f),
            Piece("bench", -1f, 1f, 0f, 1f, 0.4f, 0.45f),
            Piece("cabinet", 1.1f, 1f, 0f, 0.6f, 0.45f, 1.4f),
        )
        pieces.forEach { scene.box(it.x, it.z, it.yawDeg, it.length, it.depth, it.height) }
        val cloud = scene.cloud()
        val objects = ScanObjects.of(cloud)
        for (piece in pieces) {
            val pick = objects.pick(EYE, toward(Vec3(piece.x, piece.height / 2, piece.z)))
            val answer = ReplayTap.answer(cloud, pick, emptyList())
            assertTrue("${piece.name}: no box ($pick)", answer != null)
            val box = answer!!.box
            assertEquals("${piece.name} length", piece.length, box.length, TAPE_M)
            assertEquals("${piece.name} depth", piece.depth, box.depth, TAPE_M)
            assertEquals("${piece.name} height", piece.height, box.height, TAPE_M)
            assertFalse("${piece.name} was seen all round", answer.oneSided)
            assertNull("${piece.name}: nothing recognised, no name", answer.label)
        }
    }

    // ── Seen from one side ────────────────────────────────────────────────────

    @Test
    fun `a sofa the scan only saw from the front measures short, and says so`() {
        val scene = TapScene()
        scene.room(HALF_ROOM, HALF_ROOM, ROOM_HEIGHT)
        // Its front, facing the room, and the front half of its seat: the back and the sides were
        // never in view.
        scene.box(0f, 1f, 0f, 1.6f, 0.9f, 0.8f, faces = setOf(Face.Front, Face.Top), topShare = 0.55f)
        val cloud = scene.cloud()
        val pick = ScanObjects.of(cloud).pick(EYE, toward(Vec3(0f, 0.4f, 0.55f)))
        val answer = ReplayTap.answer(cloud, pick, emptyList())!!
        assertEquals("its front is all there", 1.6f, answer.box.length, TAPE_M)
        assertTrue("its depth is short: ${answer.box.depth}", answer.box.depth < 0.9f - 0.2f)
        assertTrue("seen from one side", answer.oneSided)
    }

    @Test
    fun `a cabinet seen from its front and one end is measured both ways, and not flagged`() {
        val scene = TapScene()
        scene.room(HALF_ROOM, HALF_ROOM, ROOM_HEIGHT)
        scene.box(1f, 1f, 0f, 0.9f, 0.5f, 0.7f, faces = setOf(Face.Front, Face.Left, Face.Top))
        val cloud = scene.cloud()
        val answer = ReplayTap.answer(cloud, ScanObjects.of(cloud).pick(EYE, toward(Vec3(1f, 0.35f, 0.75f))), emptyList())!!
        assertEquals(0.9f, answer.box.length, TAPE_M)
        assertEquals(0.5f, answer.box.depth, TAPE_M)
        assertFalse(answer.oneSided)
    }

    @Test
    fun `a plant whose points tell no side, or a scan without normals, is never flagged`() {
        val scene = TapScene()
        scene.room(HALF_ROOM, HALF_ROOM, ROOM_HEIGHT)
        val random = Random(4485)
        for (a in 0 until 20) for (b in 0 until 45) for (c in 0 until 20) {
            if (random.nextInt(8) == 0) {
                scene.point(Vec3(1f + (a + 0.5f) * VOXEL, (b + 0.5f) * VOXEL, -1.3f + (c + 0.5f) * VOXEL))
            }
        }
        val plant = scene.cloud()
        val ray = toward(Vec3(1.2f, 0.45f, -1.1f))
        val answer = ReplayTap.answer(plant, ScanObjects.of(plant).pick(EYE, ray), emptyList())!!
        assertFalse("no face to tell a side by", answer.oneSided)

        val front = TapScene()
        front.room(HALF_ROOM, HALF_ROOM, ROOM_HEIGHT)
        front.box(0f, 1f, 0f, 1.6f, 0.9f, 0.8f, faces = setOf(Face.Front, Face.Top), topShare = 0.55f)
        val bare = front.cloud().let { DenseCloud(it.positions, it.colors) }
        val pick = ScanObjects.of(bare).pick(EYE, toward(Vec3(0f, 0.4f, 0.55f)))
        assertFalse("no normals, nothing to tell", ReplayTap.answer(bare, pick, emptyList())!!.oneSided)
    }

    // ── What is not an object ─────────────────────────────────────────────────

    @Test
    fun `the floor, a wall, the ceiling and the void answer nothing, a long row says tap closer`() {
        val cloud = furnished.cloud
        for (at in listOf(Vec3(1.2f, 0f, -1.1f), Vec3(HALF_ROOM, 1.3f, -0.5f), Vec3(0.3f, ROOM_HEIGHT, 0.2f))) {
            val pick = furnished.objects.pick(EYE, toward(at))
            assertSame("at $at", ScanPick.Empty, pick)
            assertNull(ReplayTap.answer(cloud, pick, listOf(chairAt(cube()))))
        }
        assertNull(ReplayTap.answer(cloud, furnished.objects.pick(Vec3(0f, 5f, 0f), Vec3.Up), emptyList()))

        val scene = TapScene()
        scene.room(3f, HALF_ROOM, ROOM_HEIGHT)
        scene.box(0f, 0f, 0f, 3.6f, 0.5f, 0.8f)
        val long = scene.cloud()
        val pick = ScanObjects.of(long).pick(EYE, toward(Vec3(0.4f, 0.8f, 0f)))
        assertSame(ScanPick.TapCloser, pick)
        assertNull(ReplayTap.answer(long, pick, emptyList()))
    }

    // ── Its name ──────────────────────────────────────────────────────────────

    @Test
    fun `the box carries the name of the recognised object made of the same points`() {
        val found = cube()
        val half = found.surfels.copyOfRange(0, found.surfels.size / 2)
        val chair = RecognizedItem(SceneLabel.Chair, found.box, 0.8f, views = 3, surfels = half)
        assertEquals(SceneLabel.Chair, ReplayTap.answer(furnished.cloud, found, listOf(chair))!!.label)
    }

    @Test
    fun `a recognised box standing on the same footprint names it, its points or not`() {
        val found = cube()
        val b = found.box
        // What a recognition fitted round fewer points, a few centimetres off.
        val table = RecognizedItem(
            SceneLabel.Table,
            ScanBox(b.centerX + 0.04f, b.centerZ - 0.03f, b.baseY, b.yaw, b.length - 0.05f, b.depth, b.height),
            0.6f, views = 2, surfels = intArrayOf(0),
        )
        assertEquals(SceneLabel.Table, ReplayTap.nameOf(found, listOf(table)))
    }

    @Test
    fun `no name for a recognised object elsewhere, above it, or a door or window`() {
        val found = cube()
        val b = found.box
        val elsewhere = RecognizedItem(SceneLabel.Sofa, turned().box, 0.9f, views = 4, surfels = turned().surfels)
        val above = RecognizedItem(
            SceneLabel.Television,
            ScanBox(b.centerX, b.centerZ, b.baseY + b.height + 0.4f, b.yaw, b.length, b.depth, 0.5f),
            0.9f, views = 4, surfels = intArrayOf(0),
        )
        val door = RecognizedItem(SceneLabel.Door, b, 0.9f, views = 0, surfels = found.surfels)
        assertNull(ReplayTap.nameOf(found, listOf(elsewhere, above, door)))
        assertNull(ReplayTap.nameOf(found, emptyList()))
    }

    @Test
    fun `of two recognised objects, the one that overlaps most names it`() {
        val found = cube()
        val b = found.box
        val near = RecognizedItem(
            SceneLabel.Bench,
            ScanBox(b.centerX + 0.2f, b.centerZ, b.baseY, b.yaw, b.length, b.depth, b.height),
            0.95f, views = 5, surfels = intArrayOf(0),
        )
        val same = RecognizedItem(SceneLabel.Chair, b, 0.5f, views = 2, surfels = found.surfels)
        assertEquals(SceneLabel.Chair, ReplayTap.nameOf(found, listOf(near, same)))
        assertEquals(SceneLabel.Chair, ReplayTap.nameOf(found, listOf(same, near)))
    }

    @Test
    fun `a television on its stand does not name the stand and the set boxed together`() {
        // One region: a 1.8 m stand and the set on it, 1.2 m high in all; the set is 30 % of its points.
        val found = standWithSet()
        assertNull(ReplayTap.nameOf(found, listOf(setOn(found))))
    }

    @Test
    fun `two recognised objects in the tapped box name it the same whatever their order`() {
        val found = standWithSet()
        val tv = setOn(found)
        val table = RecognizedItem(SceneLabel.Table, found.box, 0.6f, views = 3, surfels = found.surfels)
        assertEquals(SceneLabel.Table, ReplayTap.nameOf(found, listOf(tv, table)))
        assertEquals(SceneLabel.Table, ReplayTap.nameOf(found, listOf(table, tv)))
        // The same points both, one fitted on the box and the other half a metre off it: the
        // footprint breaks the tie.
        val b = found.box
        val off = RecognizedItem(
            SceneLabel.Bench,
            ScanBox(b.centerX + 0.5f, b.centerZ, b.baseY, b.yaw, b.length, b.depth, b.height),
            0.9f, views = 5, surfels = found.surfels,
        )
        assertEquals(SceneLabel.Table, ReplayTap.nameOf(found, listOf(off, table)))
        assertEquals(SceneLabel.Table, ReplayTap.nameOf(found, listOf(table, off)))
    }

    // ── Its sizes ─────────────────────────────────────────────────────────────

    @Test
    fun `the sizes read feet and inches in US English, metres elsewhere with the language's decimal mark`() {
        val sofa = ScanBox(0f, 0f, 0f, 0f, length = 1.4f, depth = 0.85f, height = 0.9f)
        assertEquals(listOf("4 ft 7 in", "2 ft 9 in", "2 ft 11 in"), ReplayTap.sizes(sofa, Locale.US))
        assertEquals(listOf("1,40 m", "85 cm", "90 cm"), ReplayTap.sizes(sofa, Locale.FRANCE))
        assertEquals(listOf("1.40 m", "85 cm", "90 cm"), ReplayTap.sizes(sofa, Locale.UK))
    }

    // ── The gestures ──────────────────────────────────────────────────────────

    @Test
    fun `with a box picked, a drag still turns the room, a pinch zooms and two fingers pan`() {
        fun picked(): ArDebugOrbitCamera {
            val camera = ArDebugOrbitCamera(drift = true)
            camera.setViewport(1080, 2400)
            camera.home = OrbitPose(azimuthDegrees = 60f, elevationDegrees = 30f, distance = 5f)
            camera.snapTo(camera.home)
            // What a tap that finds a box does to the camera.
            camera.hold()
            camera.steady = true
            return camera
        }
        val turned = picked()
        val before = turned.pose
        turned.grabBegin(500, 1200, strafe = false)
        repeat(10) { turned.grabUpdate(500 + (it + 1) * 30, 1200) }
        turned.grabEnd()
        assertTrue("the drag turned it", abs(turned.pose.azimuthDegrees - before.azimuthDegrees) > 3f)

        val zoomed = picked()
        zoomed.scrollBegin(0, 0, 100f)
        repeat(10) { zoomed.scrollUpdate(0, 0, 100f, 160f) }
        assertTrue("the pinch zoomed in", zoomed.pose.distance < before.distance - 0.1f)

        val panned = picked()
        panned.grabBegin(500, 1200, strafe = true)
        repeat(10) { panned.grabUpdate(500 + (it + 1) * 30, 1200) }
        panned.grabEnd()
        val moved = panned.pose.target
        assertTrue("two fingers moved the pivot", abs(moved.x - before.target.x) + abs(moved.z - before.target.z) > 1e-2f)

        // And the picked box keeps the turntable still.
        val still = picked().apply { repeat(240) { update(1f / 60f) } }
        assertEquals(before.azimuthDegrees, still.pose.azimuthDegrees, 1e-2f)
    }

    // ── The replay of a scan the app fused itself ─────────────────────────────

    @Test
    fun `in the fused synthetic room, the sofa is named, the cabinet is boxed unnamed, and both are measured`() {
        val cloud = fusedRoom.cloud
        val objects = fusedRoom.objects
        val eye = Vec3(0f, 1.4f, 0.2f)
        fun tap(at: Vec3, items: List<RecognizedItem>): ReplayTapAnswer? {
            val started = System.nanoTime()
            val pick = objects.pick(eye, (at - eye).normalized())
            val picked = System.nanoTime()
            val answer = ReplayTap.answer(cloud, pick, items)
            val answered = System.nanoTime()
            println("replay tap at $at: pick ${(picked - started) / NANOS_PER_MS} ms, answer ${(answered - picked) / NANOS_PER_US} µs")
            return answer
        }
        // As recognition boxes them (#4763): the sofa and the table, not the cabinet.
        val items = listOf(
            RecognizedItem(SceneLabel.Sofa, ScanBox(-1f, -1.375f, 0f, 0f, 1.4f, 0.85f, 0.9f), 0.7f, views = 4, surfels = intArrayOf(0)),
            RecognizedItem(SceneLabel.Table, ScanBox(0.8f, 0.05f, 0f, 0f, 1f, 0.7f, 0.76f), 0.6f, views = 3, surfels = intArrayOf(0)),
        )
        val sofa = tap(Vec3(-1f, 0.45f, -1.2f), items)!!
        assertEquals(SceneLabel.Sofa, sofa.label)
        assertEquals(1.4f, sofa.box.length, FUSED_M)
        assertEquals(0.85f, sofa.box.depth, FUSED_M)
        assertFalse("the scan went round the sofa's front and end", sofa.oneSided)
        val cabinet = tap(Vec3(1.55f, 0.8f, 1.1f), items)!!
        assertNull(cabinet.label)
        assertEquals(1.6f, cabinet.box.height, FUSED_M)
        assertNull("the left wall", tap(Vec3(-2f, 1.5f, 0.3f), items))
    }

    // ── Scenes ────────────────────────────────────────────────────────────────

    private class Piece(
        val name: String,
        val x: Float,
        val z: Float,
        val yawDeg: Float,
        val length: Float,
        val depth: Float,
        val height: Float,
    )

    /** A box's faces as a scan sees them: [Front] towards −v, [Left] towards −u. */
    private enum class Face { Top, Front, Back, Left, Right }

    /** A scan drawn by hand: every surface sampled finer than a voxel, with its normal. */
    private class TapScene {
        private var positions = FloatArray(INITIAL_FLOATS)
        private var normals = FloatArray(INITIAL_FLOATS)
        private var floats = 0
        val count: Int get() = floats / 3

        /** A room centred on the origin, floor at y = 0: every face looks in. */
        fun room(halfX: Float, halfZ: Float, height: Float) {
            rect(Vec3(-halfX, 0f, -halfZ), Vec3(2 * halfX, 0f, 0f), Vec3(0f, 0f, 2 * halfZ), Vec3.Up)
            rect(Vec3(-halfX, height, -halfZ), Vec3(2 * halfX, 0f, 0f), Vec3(0f, 0f, 2 * halfZ), Vec3(0f, -1f, 0f))
            rect(Vec3(-halfX, 0f, -halfZ), Vec3(2 * halfX, 0f, 0f), Vec3(0f, height, 0f), Vec3(0f, 0f, 1f))
            rect(Vec3(-halfX, 0f, halfZ), Vec3(2 * halfX, 0f, 0f), Vec3(0f, height, 0f), Vec3(0f, 0f, -1f))
            rect(Vec3(-halfX, 0f, -halfZ), Vec3(0f, 0f, 2 * halfZ), Vec3(0f, height, 0f), Vec3(1f, 0f, 0f))
            rect(Vec3(halfX, 0f, -halfZ), Vec3(0f, 0f, 2 * halfZ), Vec3(0f, height, 0f), Vec3(-1f, 0f, 0f))
        }

        /**
         * A box standing on the floor, length along its yaw: the [faces] a scan saw of it, its top
         * only over the [topShare] of its depth nearest its front.
         */
        fun box(
            x: Float,
            z: Float,
            yawDeg: Float,
            length: Float,
            depth: Float,
            height: Float,
            faces: Set<Face> = Face.entries.toSet(),
            topShare: Float = 1f,
        ) {
            val yaw = Math.toRadians(yawDeg.toDouble()).toFloat()
            val u = Vec3(cos(yaw), 0f, sin(yaw))
            val v = Vec3(-sin(yaw), 0f, cos(yaw))
            val corner = Vec3(x, 0f, z) - u * (length / 2) - v * (depth / 2)
            val up = Vec3(0f, height, 0f)
            if (Face.Top in faces) rect(corner + up, u * length, v * (depth * topShare), Vec3.Up)
            if (Face.Front in faces) rect(corner, u * length, up, v * -1f)
            if (Face.Back in faces) rect(corner + v * depth, u * length, up, v)
            if (Face.Left in faces) rect(corner, v * depth, up, u * -1f)
            if (Face.Right in faces) rect(corner + u * length, v * depth, up, u)
        }

        /** One point with no telling normal: a leaf. */
        fun point(p: Vec3) = add(p, Vec3.Up)

        /** A flat patch from [origin] along [u] and [v], looking along [normal]. */
        fun rect(origin: Vec3, u: Vec3, v: Vec3, normal: Vec3) {
            val nu = (u.length() / STEP).toInt()
            val nv = (v.length() / STEP).toInt()
            for (a in 0..nu) for (b in 0..nv) add(origin + u * (a.toFloat() / nu) + v * (b.toFloat() / nv), normal)
        }

        private fun add(p: Vec3, normal: Vec3) {
            if (floats + 3 > positions.size) {
                positions = positions.copyOf(positions.size * 2)
                normals = normals.copyOf(normals.size * 2)
            }
            positions[floats] = p.x
            positions[floats + 1] = p.y
            positions[floats + 2] = p.z
            normals[floats] = normal.x
            normals[floats + 1] = normal.y
            normals[floats + 2] = normal.z
            floats += 3
        }

        fun cloud() = DenseCloud(positions.copyOf(floats), IntArray(count) { GREY }, normals.copyOf(floats))
    }

    private class Scanned(val cloud: DenseCloud) {
        val objects: ScanObjects = ScanObjects.of(cloud)
    }

    private companion object {
        /** A tape's reading off the real thing: the box is good to 2 cm when the scan saw it all round. */
        const val TAPE_M = 0.02f
        const val VOXEL = 0.02f
        const val STEP = 0.012f
        const val INITIAL_FLOATS = 1 shl 16
        const val GREY = 0xFF808080.toInt()
        const val HALF_ROOM = 2f
        const val ROOM_HEIGHT = 2.6f

        /** What depth at 160 × 90 leaves of a metre once fused: a few voxels. */
        const val FUSED_M = 0.08f
        const val NANOS_PER_MS = 1_000_000L
        const val NANOS_PER_US = 1_000L

        /** Where the taps are cast from: head height, in the middle of the room. */
        val EYE = Vec3(0f, 1.5f, 0f)

        fun toward(point: Vec3): Vec3 = (point - EYE).normalized()

        /** A 4 × 4 m room with a cube and a turned box in it. */
        val furnished: Scanned by lazy {
            val scene = TapScene()
            scene.room(HALF_ROOM, HALF_ROOM, ROOM_HEIGHT)
            scene.box(-1f, -0.9f, 0f, 0.6f, 0.6f, 0.6f)
            scene.box(0.9f, 0.8f, 30f, 0.9f, 0.5f, 0.7f)
            Scanned(scene.cloud())
        }

        /** The demo's synthetic room, fused from its depth frames as the app fuses a scan. */
        val fusedRoom: Scanned by lazy {
            val fusion = DenseFusion()
            RerunSyntheticRoom.frames().forEach { fusion.add(DepthBackProjection.project(it)) }
            Scanned(fusion.cloud())
        }

        fun cube(): ScanPick.Found = furnished.objects.pick(EYE, toward(Vec3(-1f, 0.6f, -0.9f))) as ScanPick.Found

        fun turned(): ScanPick.Found = furnished.objects.pick(EYE, toward(Vec3(0.9f, 0.7f, 0.8f))) as ScanPick.Found

        fun chairAt(found: ScanPick.Found) = RecognizedItem(SceneLabel.Chair, found.box, 0.8f, views = 3, surfels = found.surfels)

        /** A tap that grew one region round a TV stand and the set on it: 1.8 × 0.45 × 1.2 m, 1,000 points. */
        fun standWithSet() = ScanPick.Found(ScanBox(0f, 0f, 0f, 0f, 1.8f, 0.45f, 1.2f), IntArray(1_000) { it })

        /** The set on [found]'s stand as recognition boxes it: 1.2 m wide, 0.5 m up, 30 % of the points. */
        fun setOn(found: ScanPick.Found) = RecognizedItem(
            SceneLabel.Television,
            ScanBox(found.box.centerX, found.box.centerZ, 0.5f, 0f, 1.2f, 0.1f, 0.7f),
            0.9f, views = 4, surfels = found.surfels.copyOfRange(0, found.surfels.size * 3 / 10),
        )
    }
}

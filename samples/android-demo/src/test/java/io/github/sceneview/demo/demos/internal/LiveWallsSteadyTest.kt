package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The live walls hold still while ARCore refits its planes. A wall a plane stops holding up for a
 * few frames stays, under the same id, and goes only after half a second with no plane under it;
 * and a plane refit back and forth across one of the rules' thresholds keeps the verdict it had,
 * wall or not, instead of the wall going and coming back at each refit.
 */
class LiveWallsSteadyTest {
    private val floorY = -1.4f

    /** A vertical plane from (x0, z0) to (x1, z1), [bottom] to [top] metres above the floor. */
    private fun plane(
        id: Int,
        x0: Float,
        z0: Float,
        x1: Float,
        z1: Float,
        bottom: Float = 0.1f,
        top: Float = 2f,
    ) = LivePlane(
        id,
        LivePlane.Kind.Wall,
        floatArrayOf(
            x0, floorY + bottom, z0, x1, floorY + bottom, z1,
            x1, floorY + top, z1, x0, floorY + top, z0,
        ),
    )

    /** A vertical plane [length] long, centred on ([cx], [cz]), [degrees] off the x axis. */
    private fun turned(id: Int, cx: Float, cz: Float, length: Float, degrees: Float): LivePlane {
        val a = degrees * PI.toFloat() / 180f
        val hx = cos(a) * length / 2f
        val hz = sin(a) * length / 2f
        return plane(id, cx - hx, cz - hz, cx + hx, cz + hz)
    }

    // The room: a front wall 4 m long and a left wall 3.6 m long, both seen up to 2.3 m.
    private val front = plane(1, -2f, -2.6f, 2f, -2.6f, top = 2.3f)
    private val left = plane(2, -2f, -2.6f, -2f, 1f, top = 2.3f)

    /** [a] then [b], back and forth, [times] times each: a plane refit across a threshold and back. */
    private fun alternate(a: List<LivePlane>, b: List<LivePlane>, times: Int = 6) =
        List(times * 2) { if (it % 2 == 0) a else b }

    /** The ids of the walls one source shows after each of [frames], fed [step] seconds apart. */
    private fun idsOver(frames: List<List<LivePlane>>, step: Float): List<Set<Int>> {
        val source = PlaneWallSource()
        return frames.mapIndexed { i, planes ->
            source.update(planes, floorY, time = 1f + i * step).walls.mapTo(HashSet()) { it.id }
        }
    }

    /** [frames], [step] seconds apart, show [walls] walls from the first and never another set. */
    private fun assertSteady(what: String, frames: List<List<LivePlane>>, step: Float, walls: Int) {
        val ids = idsOver(frames, step)
        assertEquals("$what: walls at first", walls, ids.first().size)
        ids.forEachIndexed { i, shown -> assertEquals("$what: walls after refit $i", ids.first(), shown) }
    }

    @Test
    fun `a wall no plane holds up for a few frames stays, under the same id`() {
        val piece = plane(24, -1f, -1f, 0.5f, -1f, top = 1.8f)
        val held = listOf(front, left, piece)
        val dropped = listOf(front, left)
        // At 30 frames a second: ARCore drops the plane for 5 frames, then has it again.
        val frames = List(10) { held } + List(5) { dropped } + List(10) { held }
        assertSteady("a plane dropped for 5 frames", frames, step = 1f / 30f, walls = 3)
        // Dropped for good: the wall goes, even with no plane changing after it went.
        val ids = idsOver(List(10) { held } + List(30) { dropped }, step = 1f / 30f)
        assertEquals("a second after its plane went", 2, ids.last().size)
    }

    @Test
    fun `a plane refit across the seen height's threshold keeps its wall`() {
        // Seen over 0.40 m of its height, the survey's least, then 3 cm either side of it.
        val span = WallRules.MIN_SPAN_M
        fun piece(seen: Float) = listOf(front, left, plane(24, -1f, -1f, 0.5f, -1f, bottom = 1.8f - seen, top = 1.8f))
        // A refit a second apart, longer than a wall waits for its plane.
        assertSteady("seen height", alternate(piece(span + 0.03f), piece(span - 0.03f)), step = 1f, walls = 3)
    }

    @Test
    fun `a plane refit across the top's threshold keeps its wall`() {
        val top = WallRules.MIN_TOP_M
        fun piece(at: Float) = listOf(front, left, plane(24, -1f, -1f, 0.5f, -1f, top = at))
        assertSteady("top", alternate(piece(top + 0.03f), piece(top - 0.03f)), step = 1f, walls = 3)
    }

    @Test
    fun `a wall's top is judged at its very edge, first and once shown`() {
        fun piece(at: Float) = listOf(front, left, plane(24, -1f, -1f, 0.5f, -1f, top = at))
        // A first verdict takes standing height as it is: a plane up to 1.30 m exactly is a wall.
        assertEquals("up to 1.30 m exactly, at first", 3, idsOver(listOf(piece(WallRules.MIN_TOP_M)), step = 1f).single().size)
        // Shown, it keeps standing 6 cm under it, the noise and the hold: down to 1.24 m.
        val shown = idsOver(listOf(piece(1.33f), piece(1.245f), piece(1.245f), piece(1.235f), piece(1.235f)), step = 1f)
        assertEquals("shown, then refit to 1.245 m, then to 1.235 m", listOf(3, 3, 3, 2, 2), shown.map { it.size })
    }

    @Test
    fun `a wall whose plane ARCore merges into another keeps its verdict`() {
        // Shown up to 1.33 m, refit to 1.27 m: held. ARCore then merges it into a plane of
        // another id, the same outline: the wall it was still stands, held as before.
        fun piece(id: Int, at: Float) = listOf(front, left, plane(id, -1f, -1f, 0.5f, -1f, top = at))
        val frames = listOf(piece(24, 1.33f), piece(24, 1.27f)) + List(4) { piece(25, 1.27f) }
        assertSteady("merged under another id", frames, step = 1f, walls = 3)
        // A plane that never stood is no wall for being merged: short of standing height, it stays out.
        val ids = idsOver(listOf(piece(24, 1.27f), piece(25, 1.27f)), step = 1f)
        assertEquals("never shown", listOf(2, 2), ids.map { it.size })
    }

    @Test
    fun `the walls follow the floor when it moves under the same planes`() {
        val source = PlaneWallSource()
        assertEquals(2, source.update(listOf(front, left), floorY, time = 1f).walls.size)
        // The same planes, the floor found 1.1 m higher: they rise short of standing height.
        val moved = source.update(listOf(front, left), floorY + 1.1f, time = 2f)
        assertEquals("the floor the snapshot stands on", floorY + 1.1f, moved.floorY, 0f)
        assertEquals("walls over the new floor", 0, moved.walls.size)
    }

    @Test
    fun `a plane refit across the length's threshold keeps its wall`() {
        val seen = WallRules.MIN_SEEN_M
        fun piece(length: Float) = listOf(front, left, plane(24, -1f, -1f, -1f + length, -1f, top = 1.8f))
        assertSteady("length", alternate(piece(seen + 0.03f), piece(seen - 0.03f)), step = 1f, walls = 3)
    }

    @Test
    fun `a wall refit across square with the room keeps its verdict`() {
        // Alone along its direction, 0.8 m long: a wall on the room's square, short furniture off it.
        val snap = WallRules.SNAP_DEGREES
        fun piece(off: Float) = listOf(front, turned(30, 1.5f, -1.5f, 0.8f, 90f + off))
        assertSteady("square first", alternate(piece(snap - 1f), piece(snap + 1f)), step = 1f, walls = 2)
        assertSteady("askew first", alternate(piece(snap + 1f), piece(snap - 1f)), step = 1f, walls = 1)
    }

    @Test
    fun `a plane refit across how far furniture stands before a wall keeps its verdict`() {
        // 1.5 m long, up to 1.6 m: furniture when it stands close enough before the front wall.
        val reach = WallRules.BEHIND_MAX_M
        fun piece(across: Float) = listOf(front, left, plane(24, -1f, -2.6f + across, 0.5f, -2.6f + across, top = 1.6f))
        assertSteady("a wall first", alternate(piece(reach + 0.02f), piece(reach - 0.02f)), step = 1f, walls = 3)
        assertSteady("furniture first", alternate(piece(reach - 0.02f), piece(reach + 0.02f)), step = 1f, walls = 2)
    }

    @Test
    fun `a panel standing on the floor just short of a wall's height is never a wall`() {
        // A cabinet's side 1.2 m long, foot on the floor, more than a metre before the front wall,
        // so nothing says it is furniture before it. ARCore refits its top a few centimetres
        // either way, never up to standing height: with nothing seen above it, it is never a wall.
        val top = WallRules.MIN_TOP_M
        val tops = listOf(-0.05f, -0.03f, -0.005f, -0.04f, -0.01f, -0.02f, -0.005f, -0.05f).map { top + it }
        val frames = tops.map { listOf(front, left, plane(24, -1f, -1f, 0.2f, -1f, top = it)) }
        assertSteady("a panel short of standing height", frames, step = 0.2f, walls = 2)
    }

    @Test
    fun `the walls keep their ids and count through two seconds of tracking lost`() {
        val source = PlaneWallSource()
        var time = 1f
        fun ids(planes: List<LivePlane>, tracking: Boolean = true): Set<Int> {
            val shown = source.update(planes, floorY, time, tracking).walls.mapTo(HashSet()) { it.id }
            time += 1f / 30f
            return shown
        }
        val before = List(10) { ids(listOf(front, left)) }.last()
        assertEquals("walls before the loss", 2, before.size)
        // Two seconds at 30 frames a second with tracking lost: ARCore holds up no plane.
        repeat(60) { assertEquals("walls with tracking lost, frame $it", before, ids(emptyList(), tracking = false)) }
        // Tracking found again, the planes a few frames later, under new ids.
        repeat(5) { assertEquals("walls as tracking comes back, frame $it", before, ids(emptyList())) }
        val again = listOf(LivePlane(11, front.kind, front.polygon), LivePlane(12, left.kind, left.polygon))
        repeat(10) { assertEquals("walls with the planes back, frame $it", before, ids(again)) }
        // Tracked again, a wall no plane holds up goes as before: half a second after its plane.
        val after = List(30) { ids(again.take(1)) }.last()
        assertEquals("a second after a plane went for good", 1, after.size)
        assertTrue("the wall left keeps its id", before.containsAll(after))
    }

    @Test
    fun `the walls counted in a recording follow its planes`() {
        val piece = plane(24, -1f, -1f, 0.5f, -1f, top = 1.8f)
        assertEquals(2, PlaneWallSource.wallCount(listOf(front, left), floorY))
        assertEquals(2, PlaneWallSource.wallCount(listOf(front, left), floorY))
        assertEquals(3, PlaneWallSource.wallCount(listOf(front, left, piece), floorY))
        // The same planes over a floor 1.1 m higher rise short of standing height.
        assertEquals(0, PlaneWallSource.wallCount(listOf(front, left), floorY + 1.1f))
        assertEquals(2, PlaneWallSource.wallCount(listOf(front, left), floorY))
    }
}

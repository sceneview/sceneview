package io.github.sceneview.demo.demos.internal

import dev.romainguy.kotlin.math.Quaternion
import dev.romainguy.kotlin.math.cross
import dev.romainguy.kotlin.math.dot
import dev.romainguy.kotlin.math.length
import dev.romainguy.kotlin.math.normalize
import io.github.sceneview.math.Direction
import io.github.sceneview.math.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pins the stroke mesh and the drawing model behind
 * [io.github.sceneview.demo.demos.FingerDrawingDemo] and its AR mode.
 *
 * A painted stroke fails in ways a compile never sees: a frame that flips pinches the tube
 * flat for one ring, a seam wound the wrong way lights the paint from inside, a spline that
 * misses a sample draws beside the finger. None of it throws. The generator is pure Kotlin
 * precisely so those properties are checked here, in milliseconds, with no Filament engine —
 * and so the AR mode, which no emulator can run, has its placement arithmetic proven before
 * a phone is ever pointed at a room.
 */
class FingerDrawingTest {

    private val towardsViewer = Direction(z = -1f)
    private val spacing = 0.01f

    /** A circle in the XY plane — the stroke that turns through every direction. */
    private fun circle(samples: Int = 48, radius: Float = 1f, thickness: Float = 0.05f) =
        (0 until samples).map { i ->
            val angle = i / samples.toFloat() * (2f * PI.toFloat()) * 0.95f
            StrokePoint(Position(radius * cos(angle), radius * sin(angle), 0f), thickness)
        }

    /** A stroke that leaves its plane, as an AR stroke drawn while walking does. */
    private fun helix(samples: Int = 60) = (0 until samples).map { i ->
        val angle = i / 10f
        StrokePoint(Position(cos(angle), i * 0.08f, sin(angle)), 0.04f + 0.02f * sin(i / 5f))
    }

    private fun zigzag() = listOf(
        StrokePoint(Position(0f, 0f, 0f), 0.05f),
        StrokePoint(Position(1f, 1f, 0f), 0.02f),
        StrokePoint(Position(2f, 0f, 0f), 0.06f),
        StrokePoint(Position(3f, 1f, 0f), 0.03f),
        StrokePoint(Position(4f, 0f, 0f), 0.05f),
    )

    private val paths: List<List<StrokePoint>> by lazy { listOf(circle(), helix(), zigzag()) }

    // ── Input ─────────────────────────────────────────────────────────────────

    @Test
    fun `a slow finger paints at full thickness and a fast one thins it, never below the floor`() {
        assertEquals(1f, FingerDrawing.widthFactorForSpeed(0f), 1e-6f)
        assertEquals(1f, FingerDrawing.widthFactorForSpeed(FingerDrawing.SLOW_SPEED_DP_PER_SECOND), 1e-6f)
        assertEquals(
            FingerDrawing.FAST_WIDTH_FACTOR,
            FingerDrawing.widthFactorForSpeed(FingerDrawing.FAST_SPEED_DP_PER_SECOND),
            1e-6f,
        )
        assertEquals(
            FingerDrawing.FAST_WIDTH_FACTOR,
            FingerDrawing.widthFactorForSpeed(50_000f),
            1e-6f,
        )
        var previous = 1f
        for (speed in 0..3_000 step 50) {
            val factor = FingerDrawing.widthFactorForSpeed(speed.toFloat())
            assertTrue("thickness grew with speed at $speed dp/s", factor <= previous + 1e-6f)
            previous = factor
        }
    }

    @Test
    fun `speed is measured in dp, and two events on one timestamp do not read as a flick`() {
        // 300 px in 100 ms on a 3x screen is 100 dp in 0.1 s.
        assertEquals(1_000f, FingerDrawing.speedDpPerSecond(300f, 0f, 100L, 3f), 1e-2f)
        assertEquals(1_000f, FingerDrawing.speedDpPerSecond(180f, 240f, 100L, 3f), 1e-2f)
        assertEquals(0f, FingerDrawing.speedDpPerSecond(300f, 0f, 0L, 3f), 0f)
        assertEquals(0f, FingerDrawing.speedDpPerSecond(300f, 0f, -5L, 3f), 0f)
    }

    @Test
    fun `the thickness slider is a diameter on screen`() {
        // 12 dp across, 0.01 units per dp, finger at rest: a 0.06 radius.
        assertEquals(0.06f, FingerDrawing.radiusFor(12f, 0.01f, 0f), 1e-6f)
        assertEquals(
            0.06f * FingerDrawing.FAST_WIDTH_FACTOR,
            FingerDrawing.radiusFor(12f, 0.01f, 10_000f),
            1e-6f,
        )
    }

    @Test
    fun `a touch ray lands on the plane facing the camera`() {
        val eye = Position(0f, 0f, 4f)
        val forward = Direction(z = -1f)
        val ray = normalize(Direction(0.25f, -0.1f, -1f))
        val hit = requireNotNull(FingerDrawingPlacement.planeHit(eye, ray, Position(), forward))
        assertEquals("the hit is on the plane", 0f, hit.z, 1e-5f)
        assertEquals(1f, hit.x, 1e-5f)
        assertEquals(-0.4f, hit.y, 1e-5f)
    }

    @Test
    fun `a ray that cannot reach the plane paints nothing`() {
        val eye = Position(0f, 0f, 4f)
        val forward = Direction(z = -1f)
        val along = FingerDrawingPlacement.planeHit(eye, Direction(x = 1f), Position(), forward)
        val away = FingerDrawingPlacement.planeHit(eye, Direction(z = 1f), Position(), forward)
        assertNull("ray along the plane", along)
        assertNull("ray away from the plane", away)
    }

    @Test
    fun `AR paint sits at the fixed depth in front of the phone wherever the finger is`() {
        // The phone held anywhere, looking anywhere: the painted point is always
        // AR_PAINT_DEPTH_METERS along the view axis, whatever corner of the screen is touched.
        val eye = Position(0.4f, 1.5f, -0.2f)
        val forward = normalize(Direction(0.3f, -0.2f, -1f))
        val planePoint = eye + forward * FingerDrawingPlacement.AR_PAINT_DEPTH_METERS
        val right = normalize(cross(forward, Direction(y = 1f)))
        val up = cross(right, forward)
        for ((sx, sy) in listOf(0f to 0f, 0.3f to 0.5f, -0.3f to -0.5f, 0.28f to -0.6f)) {
            val ray = normalize(forward + right * sx + up * sy)
            val hit = requireNotNull(FingerDrawingPlacement.planeHit(eye, ray, planePoint, forward))
            assertEquals(
                "depth along the view axis at ($sx, $sy)",
                FingerDrawingPlacement.AR_PAINT_DEPTH_METERS,
                dot(hit - eye, forward),
                1e-5f,
            )
        }
    }

    @Test
    fun `one dp on the painted surface is measured between two hits`() {
        assertEquals(
            0.005f,
            FingerDrawingPlacement.unitsPerDp(Position(0f, 0f, 0f), Position(0.3f, 0.4f, 0f), 100f),
            1e-6f,
        )
        assertEquals(0f, FingerDrawingPlacement.unitsPerDp(Position(), Position(x = 1f), 0f), 0f)
    }

    @Test
    fun `a point stored in its anchor comes back where it was painted`() {
        // 90° about Y: the anchor's local +X is the world's -Z.
        val half = sqrt(0.5f)
        val rotation = Quaternion(0f, half, 0f, half)
        val anchor = Position(1f, 2f, 3f)

        val local = FingerDrawingPlacement.worldToAnchor(Position(1f, 2f, 2f), anchor, rotation)
        assertEquals(1f, local.x, 1e-5f)
        assertEquals(0f, local.y, 1e-5f)
        assertEquals(0f, local.z, 1e-5f)

        // And back: anchor rotation applied to the local point, plus the anchor position.
        val world = Position(-0.7f, 0.2f, 1.9f)
        val stored = FingerDrawingPlacement.worldToAnchor(world, anchor, rotation)
        val restored = Position(stored.z, stored.y, -stored.x) + anchor
        assertEquals(world.x, restored.x, 1e-5f)
        assertEquals(world.y, restored.y, 1e-5f)
        assertEquals(world.z, restored.z, 1e-5f)

        val direction = FingerDrawingPlacement.worldDirectionToAnchor(Direction(z = -1f), rotation)
        assertEquals(1f, direction.x, 1e-5f)
        assertEquals(1f, length(direction), 1e-5f)
    }

    @Test
    fun `an anchor that is not rotated only shifts the point`() {
        val local = FingerDrawingPlacement.worldToAnchor(
            Position(1.5f, 0.5f, -2f),
            Position(1f, 1f, 1f),
            Quaternion(0f, 0f, 0f, 1f),
        )
        assertEquals(0.5f, local.x, 1e-6f)
        assertEquals(-0.5f, local.y, 1e-6f)
        assertEquals(-3f, local.z, 1e-6f)
    }

    @Test
    fun `paint speed is measured on the painted surface, so a still finger on a moving phone is fast`() {
        // 0.3 m in half a second, on a surface where one dp is a millimetre: 600 dp/s.
        val speed = FingerDrawing.speedDpPerSecond(
            from = Position(0f, 0f, 0f),
            to = Position(0.3f, 0f, 0f),
            unitsPerDp = 0.001f,
            dtSeconds = 0.5f,
        )
        assertEquals(600f, speed, 1e-2f)
        assertEquals(0f, FingerDrawing.speedDpPerSecond(Position(), Position(x = 1f), 0.001f, 0f), 0f)
        assertEquals(0f, FingerDrawing.speedDpPerSecond(Position(), Position(x = 1f), 0f, 1f), 0f)
    }

    @Test
    fun `the 3D plane goes through the orbit pivot while the pivot is in front of the camera`() {
        val pivot = Position(0f, 0f, 0f)
        val forward = Direction(z = -1f)
        fun planeFrom(camera: Position) = FingerDrawingPlacement.paintPlanePoint(
            cameraPosition = camera,
            forward = forward,
            pivot = pivot,
            minDepth = 0.5f,
            fallbackDepth = 4f,
        )
        assertEquals(pivot, planeFrom(Position(0f, 0f, 4f)))
        // Panned sideways: still the pivot's plane, so strokes from every angle cross there.
        assertEquals(pivot, planeFrom(Position(3f, 1f, 4f)))
    }

    @Test
    fun `a camera past the pivot paints in front of itself, never on a plane behind its back`() {
        val forward = Direction(z = -1f)
        val camera = Position(0f, 0f, -1f)
        val point = FingerDrawingPlacement.paintPlanePoint(
            cameraPosition = camera,
            forward = forward,
            pivot = Position(),
            minDepth = 0.5f,
            fallbackDepth = 4f,
        )
        assertEquals(Position(0f, 0f, -5f), point)
        // And a touch does land on it.
        val ray = normalize(Direction(0.1f, 0.1f, -1f))
        assertNotNull(FingerDrawingPlacement.planeHit(camera, ray, point, forward))
    }

    @Test
    fun `a stroke hangs from the nearest anchor in reach and asks for a new one out of reach`() {
        val anchors = listOf(tracked(0f), tracked(2f))
        val reach = FingerDrawingPlacement.AR_ANCHOR_REACH_METERS
        assertEquals(0, FingerDrawingPlacement.anchorToReuse(Position(0.2f, 0.1f, 0f), anchors, reach))
        assertEquals(1, FingerDrawingPlacement.anchorToReuse(Position(1.6f, 0f, 0f), anchors, reach))
        assertNull(FingerDrawingPlacement.anchorToReuse(Position(1f, 0f, 0f), anchors, reach))
        assertNull(FingerDrawingPlacement.anchorToReuse(Position(0f, 0f, 0f), emptyList(), reach))
    }

    // ── AR anchors that stop being tracked (#4082, found on a Pixel 4a) ───────

    private fun tracked(x: Float) = AnchorSpot(Position(x, 0f, 0f), AnchorHealth.Tracking)
    private fun paused(x: Float) = AnchorSpot(Position(x, 0f, 0f), AnchorHealth.Paused)
    private fun stopped(x: Float) = AnchorSpot(Position(x, 0f, 0f), AnchorHealth.Stopped)

    @Test
    fun `a stroke never hangs from an anchor that is not tracked, however near`() {
        val reach = FingerDrawingPlacement.AR_ANCHOR_REACH_METERS
        val onIt = Position(0f, 0f, 0f)
        // Back from the background: the only anchor is paused and the pen is right on it.
        assertNull(FingerDrawingPlacement.anchorToReuse(onIt, listOf(paused(0f)), reach))
        assertNull(FingerDrawingPlacement.anchorToReuse(onIt, listOf(stopped(0f)), reach))
    }

    @Test
    fun `a tracked anchor further away wins over a nearer one that is not tracked`() {
        val reach = FingerDrawingPlacement.AR_ANCHOR_REACH_METERS
        val anchors = listOf(paused(0f), tracked(0.5f), stopped(0.1f))
        assertEquals(1, FingerDrawingPlacement.anchorToReuse(Position(0f, 0f, 0f), anchors, reach))
        assertNull(
            "the tracked one is out of reach: a fresh anchor, not the paused one",
            FingerDrawingPlacement.anchorToReuse(Position(-0.5f, 0f, 0f), anchors, reach),
        )
    }

    private fun threeStrokes(): Drawing {
        var drawing = Drawing()
        repeat(3) { drawing = drawing.down(it.toFloat()).penMove(Position(x = it + 0.5f), 0.05f).penUp() }
        return drawing
    }

    private fun stage(
        drawing: Drawing,
        healthOf: Map<Int, AnchorHealth>,
        cameraReady: Boolean = true,
        cameraTracking: Boolean = true,
    ) = ArDrawingStage.of(drawing, healthOf, cameraReady, cameraTracking)

    @Test
    fun `strokes on tracked anchors are counted and the banner has nothing to say`() {
        val stage = stage(threeStrokes(), (0..2).associateWith { AnchorHealth.Tracking })
        assertEquals(ArDrawingStatus.Drawing, stage.status)
        assertEquals(3, stage.visibleStrokeCount)
        assertTrue(stage.lostStrokeIds.isEmpty())
    }

    @Test
    fun `strokes on a paused anchor are not counted and the banner says they are hidden`() {
        // The Pixel 4a case: three strokes, Home, back — "3 strokes" and an empty screen.
        val paused = (0..2).associateWith { AnchorHealth.Paused }
        val allHidden = stage(threeStrokes(), paused)
        assertEquals(ArDrawingStatus.DrawingHidden, allHidden.status)
        assertEquals(0, allHidden.visibleStrokeCount)
        assertTrue("a paused anchor may come back: nothing is removed", allHidden.lostStrokeIds.isEmpty())

        // A new stroke on a fresh anchor is counted; the banner still tells of the others.
        val oneNew = stage(threeStrokes().down(5f), paused)
        assertEquals(ArDrawingStatus.DrawingHidden, oneNew.status)
        assertEquals(1, oneNew.visibleStrokeCount)
    }

    @Test
    fun `strokes on a stopped anchor are lost and neither counted nor announced`() {
        val health = mapOf(0 to AnchorHealth.Stopped, 1 to AnchorHealth.Tracking, 2 to AnchorHealth.Stopped)
        val stage = stage(threeStrokes(), health)
        assertEquals(setOf(0, 2), stage.lostStrokeIds)
        assertEquals(1, stage.visibleStrokeCount)
        assertEquals(ArDrawingStatus.Drawing, stage.status)

        val allGone = stage(threeStrokes(), (0..2).associateWith { AnchorHealth.Stopped })
        assertEquals(0, allGone.visibleStrokeCount)
        assertEquals("the stage is empty again: back to the draw hint", ArDrawingStatus.ReadyToDraw, allGone.status)
    }

    @Test
    fun `the banner asks for tracking before it speaks of the drawing`() {
        val paused = (0..2).associateWith { AnchorHealth.Paused }
        assertEquals(ArDrawingStatus.Starting, stage(threeStrokes(), paused, cameraReady = false).status)
        assertEquals(ArDrawingStatus.FindingPlace, stage(threeStrokes(), paused, cameraTracking = false).status)
        assertEquals(ArDrawingStatus.ReadyToDraw, stage(Drawing(), emptyMap()).status)
        assertEquals(ArDrawingStatus.FindingPlace, stage(Drawing(), emptyMap(), cameraTracking = false).status)
    }

    @Test
    fun `removing strokes by id keeps the others, their order and the id counter`() {
        val drawing = threeStrokes().down(9f)
        val kept = drawing.without(setOf(1, 3))
        assertEquals(listOf(0, 2), kept.strokes.map { it.id })
        assertNull("the stroke under the finger was one of them", kept.active)
        assertEquals(4, kept.down(0f).active!!.id)
        assertSame(drawing, drawing.without(emptySet()))
    }

    // ── Centreline ────────────────────────────────────────────────────────────

    @Test
    fun `the curve passes through every touch sample, in order`() {
        for (path in paths) {
            val line = FingerDrawing.centerline(path, spacing = 0.05f)
            var cursor = 0
            for ((i, sample) in path.withIndex()) {
                val found = (cursor until line.size).firstOrNull {
                    length(line[it].position - sample.position) < 1e-5f
                }
                assertNotNull("sample $i is not on the curve", found)
                cursor = found!!
            }
            assertEquals("the curve ends on the last sample", line.lastIndex, cursor)
        }
    }

    @Test
    fun `the curve is resampled finer than the touch samples`() {
        val path = circle(samples = 24)
        val line = FingerDrawing.centerline(path, spacing = 0.05f)
        assertTrue("${line.size} points for ${path.size} samples", line.size > path.size * 3)
        // And no ring gap is longer than the samples it was built from.
        val widestSample = (1 until path.size).maxOf { length(path[it].position - path[it - 1].position) }
        val widestRing = (1 until line.size).maxOf { length(line[it].position - line[it - 1].position) }
        assertTrue("rings $widestRing apart, samples $widestSample", widestRing < widestSample / 2f)
    }

    @Test
    fun `no visible corner is left between two rings of a curve`() {
        // The point of the spline: a circle drawn from 24 samples turns 15° at each of them
        // when joined by straight lines. Resampled, no two consecutive rings may turn more
        // than a few degrees — that is "no visible segments".
        val line = FingerDrawing.centerline(circle(samples = 24), spacing = 0.02f)
        var sharpest = 1f
        for (i in 1 until line.size - 1) {
            val before = normalize(line[i].position - line[i - 1].position)
            val after = normalize(line[i + 1].position - line[i].position)
            sharpest = minOf(sharpest, dot(before, after))
        }
        assertTrue("sharpest turn has cosine $sharpest", sharpest > cos(Math.toRadians(4.0)).toFloat())
    }

    @Test
    fun `the curve stays near the samples and never loops back`() {
        // Uniform Catmull-Rom overshoots between unevenly spaced samples; the centripetal
        // form must not. Uneven on purpose: a long hop, then two samples almost together.
        val path = listOf(
            StrokePoint(Position(0f, 0f, 0f), 0.05f),
            StrokePoint(Position(2f, 0f, 0f), 0.05f),
            StrokePoint(Position(2.05f, 0.05f, 0f), 0.05f),
            StrokePoint(Position(2.05f, 2f, 0f), 0.05f),
        )
        val line = FingerDrawing.centerline(path, spacing = 0.02f)
        for (point in line) {
            assertTrue("overshoot to ${point.position}", point.position.x in -0.05f..2.2f)
            assertTrue("overshoot to ${point.position}", point.position.y in -0.15f..2.05f)
        }
    }

    @Test
    fun `what is already drawn does not move when the finger goes on`() {
        val path = helix(samples = 30)
        val shorter = FingerDrawing.centerline(path.dropLast(1), spacing = 0.05f)
        val longer = FingerDrawing.centerline(path, spacing = 0.05f)
        // Everything up to two samples before the old end is settled.
        val settledSample = path[path.size - 4]
        val settled = shorter.indexOfFirst { length(it.position - settledSample.position) < 1e-6f }
        assertTrue(settled > 0)
        for (i in 0..settled) {
            assertEquals("ring $i moved", 0f, length(shorter[i].position - longer[i].position), 1e-6f)
        }
    }

    @Test
    fun `thickness between two samples stays within what they asked for`() {
        for (path in paths) {
            val thinnest = path.minOf { it.radius }
            val thickest = path.maxOf { it.radius }
            for (point in FingerDrawing.centerline(path, spacing = 0.02f)) {
                assertTrue("radius ${point.radius} under $thinnest", point.radius >= thinnest * 0.85f)
                assertTrue("radius ${point.radius} over $thickest", point.radius <= thickest * 1.15f)
            }
        }
    }

    @Test
    fun `a single tap is a single point`() {
        val dot = listOf(StrokePoint(Position(1f, 2f, 3f), 0.05f))
        assertSame(dot, FingerDrawing.centerline(dot, spacing))
    }

    @Test
    fun `a stroke keeps its thickness right to both ends`() {
        // #4504: the ends used to taper to half the thickness. A stroke drawn at one speed is
        // one thickness from its first ring to its last.
        val stroke = Drawing()
            .penDown(Position(), radius = 0.02f, colorIndex = 0, frameHint = towardsViewer, spacing = spacing)
            .let { drawing ->
                (1..60).fold(drawing) { d, i -> d.penMove(Position(x = i * 0.01f), targetRadius = 0.02f) }
            }
            .penUp()
            .strokes.single()
        val line = FingerDrawing.line(stroke)
        assertTrue(line.size > 10)
        for ((i, point) in line.withIndex()) {
            assertEquals("ring $i of ${line.size} thinned", 0.02f, point.radius, 1e-6f)
        }
    }

    @Test
    fun `both ends close in a full half-sphere of the stroke's radius`() {
        val radius = 0.02f
        val line = FingerDrawing.centerline(
            (0..100).map { StrokePoint(Position(x = it * 0.01f), radius) },
            spacing = 0.01f,
        )
        val vertices = FingerDrawing.vertices(line, towardsViewer)
        // Start pole one radius behind the first point, end pole one radius past the last.
        val startPole = vertices.first().position
        val endPole = vertices.last().position
        assertEquals(line.first().position.x - radius, startPole.x, 1e-5f)
        assertEquals(line.last().position.x + radius, endPole.x, 1e-5f)
        // Every cap vertex lies on the sphere of the end's radius: a round cap, not a point.
        val perCap = FingerDrawing.CAP_RINGS * FingerDrawing.RADIAL_SEGMENTS
        for (vertex in vertices.take(perCap)) {
            assertEquals(radius, length(vertex.position - line.first().position), 1e-5f)
        }
        for (vertex in vertices.takeLast(perCap)) {
            assertEquals(radius, length(vertex.position - line.last().position), 1e-5f)
        }
    }

    // ── Frames ────────────────────────────────────────────────────────────────

    @Test
    fun `every frame is orthonormal`() {
        for (path in paths) {
            val line = FingerDrawing.centerline(path, spacing = 0.05f)
            for ((i, frame) in FingerDrawing.frames(line, towardsViewer).withIndex()) {
                assertEquals("tangent $i", 1f, length(frame.tangent), 1e-3f)
                assertEquals("normal $i", 1f, length(frame.normal), 1e-3f)
                assertEquals("binormal $i", 1f, length(frame.binormal), 1e-3f)
                assertEquals("normal·tangent $i", 0f, dot(frame.normal, frame.tangent), 1e-3f)
                assertEquals("binormal·tangent $i", 0f, dot(frame.binormal, frame.tangent), 1e-3f)
                assertEquals("binormal·normal $i", 0f, dot(frame.binormal, frame.normal), 1e-3f)
            }
        }
    }

    @Test
    fun `the frame never flips or twists from one ring to the next`() {
        // The ribbon that "se vrille": a frame built from a fixed up vector snaps round when
        // the stroke passes the vertical, and the tube is pinched there. A transported frame
        // turns only as much as the curve does.
        for (path in paths) {
            val line = FingerDrawing.centerline(path, spacing = 0.02f)
            val frames = FingerDrawing.frames(line, towardsViewer)
            for (i in 1 until frames.size) {
                val turn = dot(frames[i - 1].tangent, frames[i].tangent)
                val roll = dot(frames[i - 1].normal, frames[i].normal)
                assertTrue(
                    "ring $i rolls (cos $roll) more than the curve turns (cos $turn)",
                    roll >= turn - 0.02f,
                )
            }
        }
    }

    @Test
    fun `a stroke drawn on the plane keeps one axis on the view direction all along`() {
        // Drawn flat in front of the camera, the cross-section must not rotate at all: its
        // normal starts on the view axis and stays there through a full turn of the circle.
        val line = FingerDrawing.centerline(circle(), spacing = 0.02f)
        for ((i, frame) in FingerDrawing.frames(line, towardsViewer).withIndex()) {
            assertEquals("frame $i left the view axis", 1f, abs(dot(frame.normal, towardsViewer)), 1e-3f)
        }
    }

    @Test
    fun `a hint along the stroke still gives a frame`() {
        val alongHint = (0..5).map { StrokePoint(Position(z = -it * 0.1f), 0.02f) }
        val frames = FingerDrawing.frames(alongHint, towardsViewer)
        assertEquals(6, frames.size)
        for (frame in frames) {
            assertEquals(1f, length(frame.normal), 1e-3f)
            assertEquals(0f, dot(frame.normal, frame.tangent), 1e-3f)
        }
    }

    // ── Mesh ──────────────────────────────────────────────────────────────────

    @Test
    fun `vertex and index counts match the announced grid`() {
        for (path in paths) {
            val line = (FingerDrawing.centerline(path, spacing = 0.05f))
            val vertices = FingerDrawing.vertices(line, towardsViewer)
            assertEquals(FingerDrawing.vertexCount(line.size), vertices.size)
            val indices = FingerDrawing.triangleIndices(line.size)
            assertEquals(FingerDrawing.triangleCount(line.size) * 3, indices.size)
            assertTrue(indices.all { it in vertices.indices })
        }
    }

    @Test
    fun `every normal is a unit vector`() {
        for (path in paths) {
            val line = (FingerDrawing.centerline(path, spacing = 0.05f))
            for ((i, vertex) in FingerDrawing.vertices(line, towardsViewer).withIndex()) {
                assertEquals("normal $i", 1f, length(requireNotNull(vertex.normal)), 1e-3f)
            }
        }
    }

    @Test
    fun `every triangle with an area faces outwards`() {
        // Winding and normals must agree, or the paint is lit from inside on one material
        // side — invisible on a double-sided material until the light moves.
        for (path in paths) {
            val line = (FingerDrawing.centerline(path, spacing = 0.05f))
            val vertices = FingerDrawing.vertices(line, towardsViewer)
            val indices = FingerDrawing.triangleIndices(line.size)
            var checked = 0
            for (t in indices.indices step 3) {
                val a = vertices[indices[t]]
                val b = vertices[indices[t + 1]]
                val c = vertices[indices[t + 2]]
                val face = cross(b.position - a.position, c.position - a.position)
                if (length(face) < 1e-9f) continue
                val shading = a.normal!! + b.normal!! + c.normal!!
                assertTrue("triangle ${t / 3} faces inwards", dot(normalize(face), normalize(shading)) > 0.3f)
                checked++
            }
            assertTrue("only $checked triangles had an area", checked > indices.size / 3 / 2)
        }
    }

    @Test
    fun `each ring sits at its radius around its centreline point`() {
        val line = (FingerDrawing.centerline(helix(), spacing = 0.05f))
        val vertices = FingerDrawing.vertices(line, towardsViewer)
        val firstBodyRing = FingerDrawing.CAP_RINGS
        for ((i, point) in line.withIndex()) {
            val start = (firstBodyRing + i) * FingerDrawing.RADIAL_SEGMENTS
            for (j in 0 until FingerDrawing.RADIAL_SEGMENTS) {
                assertEquals(
                    "ring $i vertex $j",
                    point.radius,
                    length(vertices[start + j].position - point.position),
                    1e-4f,
                )
            }
        }
    }

    @Test
    fun `both ends are closed by a round cap`() {
        val line = listOf(
            StrokePoint(Position(x = 0f), 0.1f),
            StrokePoint(Position(x = 1f), 0.1f),
        )
        val vertices = FingerDrawing.vertices(line, towardsViewer)
        val ring = FingerDrawing.RADIAL_SEGMENTS
        // Start pole: one point, a radius behind the first centre, facing backwards.
        for (j in 0 until ring) {
            assertEquals(-0.1f, vertices[j].position.x, 1e-5f)
            assertEquals(0f, vertices[j].position.y, 1e-5f)
            assertEquals(-1f, vertices[j].normal!!.x, 1e-5f)
        }
        // End pole: a radius past the last centre, facing forwards.
        for (j in 0 until ring) {
            val vertex = vertices[vertices.size - ring + j]
            assertEquals(1.1f, vertex.position.x, 1e-5f)
            assertEquals(1f, vertex.normal!!.x, 1e-5f)
        }
        // Every cap vertex is on the sphere of its end, and its normal is the sphere's.
        for (k in 0 until FingerDrawing.CAP_RINGS * ring) {
            val vertex = vertices[k]
            assertEquals("start cap vertex $k", 0.1f, length(vertex.position), 1e-5f)
            assertEquals(1f, dot(normalize(vertex.position), vertex.normal!!), 1e-4f)
        }
    }

    @Test
    fun `a tap paints a ball`() {
        val center = Position(0.3f, 0.2f, -0.1f)
        val vertices = FingerDrawing.vertices(listOf(StrokePoint(center, 0.05f)), towardsViewer)
        assertEquals(FingerDrawing.vertexCount(1), vertices.size)
        for (vertex in vertices) {
            assertEquals(0.05f, length(vertex.position - center), 1e-5f)
            assertEquals(1f, dot(normalize(vertex.position - center), vertex.normal!!), 1e-3f)
        }
    }

    @Test
    fun `a swelling stroke leans its normals back down the slope`() {
        // Radius doubling along +X: the surface is a cone, so its normals tilt towards -X.
        val cone = (0..10).map { StrokePoint(Position(x = it * 0.1f), 0.1f + it * 0.02f) }
        val vertices = FingerDrawing.vertices(cone, towardsViewer)
        val middleRing = (FingerDrawing.CAP_RINGS + 5) * FingerDrawing.RADIAL_SEGMENTS
        for (j in 0 until FingerDrawing.RADIAL_SEGMENTS) {
            val normal = vertices[middleRing + j].normal!!
            assertEquals("slope 0.2 gives x = -0.2 / sqrt(1.04)", -0.196f, normal.x, 2e-3f)
        }
    }

    // ── Growing in place ──────────────────────────────────────────────────────

    @Test
    fun `padding keeps the vertex count constant while the stroke grows inside its capacity`() {
        val path = helix(samples = 12)
        val capacity = 96
        var previousSize = -1
        for (count in 2..path.size) {
            val line = (FingerDrawing.centerline(path.take(count), spacing = 0.08f))
            assertTrue(line.size <= capacity)
            val vertices = FingerDrawing.vertices(line, towardsViewer, capacity)
            assertEquals(FingerDrawing.vertexCount(capacity), vertices.size)
            if (previousSize >= 0) assertEquals(previousSize, vertices.size)
            previousSize = vertices.size
        }
    }

    @Test
    fun `padding is drawn as nothing`() {
        val line = (FingerDrawing.centerline(zigzag(), spacing = 0.2f))
        val capacity = line.size + 20
        val vertices = FingerDrawing.vertices(line, towardsViewer, capacity)
        val indices = FingerDrawing.triangleIndices(capacity)
        val firstPaddingVertex = FingerDrawing.vertexCount(line.size)
        val pole = vertices[firstPaddingVertex - 1].position
        for (i in firstPaddingVertex until vertices.size) {
            assertEquals("padding vertex $i is off the end pole", 0f, length(vertices[i].position - pole), 1e-6f)
        }
        // The exact mesh and the padded one draw the same surface: every triangle that
        // touches padding has no area.
        for (t in indices.indices step 3) {
            val corners = listOf(indices[t], indices[t + 1], indices[t + 2])
            if (corners.none { it >= firstPaddingVertex }) continue
            val (a, b, c) = corners.map { vertices[it].position }
            assertEquals("padding triangle ${t / 3} has an area", 0f, length(cross(b - a, c - a)), 1e-9f)
        }
        // And the part that is not padding is the exact mesh, vertex for vertex.
        assertEquals(FingerDrawing.vertices(line, towardsViewer), vertices.take(firstPaddingVertex))
    }

    @Test
    fun `capacity grows in steps and always holds the stroke`() {
        var previous = 0
        for (count in 1..FingerDrawing.MAX_RINGS) {
            val capacity = FingerDrawing.capacityFor(count)
            assertTrue("capacity $capacity under $count", capacity >= count)
            assertTrue("capacity shrank at $count", capacity >= previous)
            previous = capacity
        }
        val steps = (1..FingerDrawing.MAX_RINGS).map(FingerDrawing::capacityFor).distinct()
        assertTrue("a stroke re-allocates ${steps.size} times before it is continued", steps.size <= 6)
        assertEquals(2_000, FingerDrawing.capacityFor(2_000))
    }

    // ── Drawing model ─────────────────────────────────────────────────────────

    private fun Drawing.down(x: Float, color: Int = 0) =
        penDown(Position(x = x), radius = 0.05f, colorIndex = color, frameHint = towardsViewer, spacing = spacing)

    @Test
    fun `pen down, move, up leaves one finished stroke`() {
        var drawing = Drawing().down(0f, color = 2)
        assertEquals(1, drawing.strokeCount)
        drawing = drawing.penMove(Position(x = 0.1f), 0.05f).penMove(Position(x = 0.2f), 0.05f).penUp()
        assertNull(drawing.active)
        assertEquals(1, drawing.strokes.size)
        assertEquals(3, drawing.strokes.single().points.size)
        assertEquals(2, drawing.strokes.single().colorIndex)
        assertSame("a second pen-up changes nothing", drawing, drawing.penUp())
    }

    @Test
    fun `a move without a pen down paints nothing`() {
        val drawing = Drawing()
        assertSame(drawing, drawing.penMove(Position(x = 1f), 0.05f))
    }

    @Test
    fun `a finger at rest adds no points`() {
        val down = Drawing().down(0f)
        val jitter = spacing * FingerDrawing.MIN_SAMPLE_SPACINGS * 0.5f
        assertSame(down, down.penMove(Position(x = jitter), 0.05f))
        assertEquals(2, down.penMove(Position(x = jitter * 3f), 0.05f).active!!.points.size)
    }

    @Test
    fun `thickness eases towards the speed instead of jumping to it`() {
        var drawing = Drawing().down(0f)
        val radii = mutableListOf(0.05f)
        for (i in 1..12) {
            drawing = drawing.penMove(Position(x = i * 0.1f), targetRadius = 0.02f)
            radii += drawing.active!!.points.last().radius
        }
        for (i in 1 until radii.size) {
            assertTrue("radius went up at $i", radii[i] < radii[i - 1])
            assertTrue("radius stepped ${radii[i - 1] - radii[i]} at $i", radii[i - 1] - radii[i] <= 0.03f * 0.5f)
        }
        assertEquals(0.02f, radii.last(), 1e-3f)
    }

    @Test
    fun `thickness eases over a length of stroke, however many samples cover it`() {
        fun radiusAfter(samples: Int): Float {
            var drawing = Drawing().down(0f)
            for (i in 1..samples) {
                drawing = drawing.penMove(Position(x = spacing * 32f * i / samples), targetRadius = 0.02f)
            }
            return drawing.active!!.points.last().radius
        }
        // 32 spacings is two easing lengths: 86 % of the way from 0.05 to 0.02.
        val expected = 0.02f + 0.03f * kotlin.math.exp(-2f)
        assertEquals(expected, radiusAfter(samples = 2), 1e-5f)
        assertEquals(expected, radiusAfter(samples = 16), 1e-5f)
    }

    @Test
    fun `jittery speed samples are averaged and a sample with no duration is ignored`() {
        var speed = 0f
        // 600 dp/s measured as 400 and 800 alternately, every 8 ms.
        repeat(100) { speed = FingerDrawing.smoothedSpeed(speed, if (it % 2 == 0) 400f else 800f, 0.008f) }
        assertEquals(600f, speed, 20f)
        assertEquals(speed, FingerDrawing.smoothedSpeed(speed, 50_000f, dtSeconds = 0f), 0f)
        // One time constant covers 63 % of a step.
        assertEquals(632f, FingerDrawing.smoothedSpeed(0f, 1_000f, FingerDrawing.SPEED_SMOOTHING_SECONDS), 1f)
    }

    @Test
    fun `a pen down over an unfinished stroke keeps that stroke`() {
        val drawing = Drawing().down(0f).penMove(Position(x = 0.1f), 0.05f).down(1f)
        assertEquals(1, drawing.strokes.size)
        assertEquals(2, drawing.strokeCount)
        assertTrue(drawing.strokes.single().id != drawing.active!!.id)
    }

    @Test
    fun `undo removes the newest stroke, one at a time, down to an empty stage`() {
        var drawing = Drawing()
        repeat(3) { drawing = drawing.down(it.toFloat()).penMove(Position(x = it + 0.5f), 0.05f).penUp() }
        assertEquals(listOf(0, 1, 2), drawing.strokes.map { it.id })
        drawing = drawing.undo()
        assertEquals(listOf(0, 1), drawing.strokes.map { it.id })
        drawing = drawing.undo().undo()
        assertTrue(drawing.isEmpty)
        assertSame("undo on an empty stage changes nothing", drawing, drawing.undo())
    }

    @Test
    fun `undo while drawing drops the stroke under the finger only`() {
        val drawing = Drawing().down(0f).penUp().down(1f).undo()
        assertNull(drawing.active)
        assertEquals(1, drawing.strokes.size)
    }

    @Test
    fun `clear empties the stage and a stroke drawn after it gets a fresh id`() {
        val cleared = Drawing().down(0f).penUp().down(1f).clear()
        assertTrue(cleared.isEmpty)
        assertEquals(2, cleared.down(0f).active!!.id)
    }

    @Test
    fun `a very long stroke is continued in a new mesh from the same point`() {
        val drawing = longStroke()
        val finished = drawing.strokes.single()
        val continued = drawing.active!!
        assertEquals(finished.points.last(), continued.points.first())
        assertEquals(finished.colorIndex, continued.colorIndex)
        assertTrue(finished.id != continued.id)
        // Each piece knows the sample on the other side of the join.
        assertEquals(continued.points[1], finished.leadOut)
        assertEquals(finished.points[finished.points.size - 2], continued.leadIn)
        // The finished part is as long as a mesh is allowed to be, and no longer.
        val rings = FingerDrawing.line(finished).size
        assertTrue("$rings rings", rings <= FingerDrawing.capacityFor(FingerDrawing.MAX_RINGS))
        assertTrue("split after only $rings rings", rings > FingerDrawing.MAX_RINGS / 2)
    }

    /** One pen-down drawn along a wavy path until it no longer fits one mesh, plus [extra] samples. */
    private fun longStroke(extra: Int = 0, targetRadius: (Int) -> Float = { 0.05f }): Drawing {
        var drawing = Drawing().down(0f)
        var i = 0
        var afterSplit = 0
        while (drawing.strokes.isEmpty() || afterSplit < extra) {
            i++
            if (drawing.strokes.isNotEmpty()) afterSplit++
            val x = i * spacing * 4f
            drawing = drawing.penMove(Position(x = x, y = sin(x * 3f) * 0.3f), targetRadius(i))
        }
        return drawing
    }

    @Test
    fun `the join between two meshes of one stroke does not show`() {
        // The thickness is still changing across the join: the worst case for a seam.
        val drawing = longStroke(extra = 12) { i -> if (i % 40 < 20) 0.05f else 0.02f }
        val finished = drawing.strokes.single()
        val continued = drawing.active!!
        val before = FingerDrawing.line(finished)
        val after = FingerDrawing.line(continued)

        // Same point, same thickness on either side of the join.
        assertEquals(0f, length(before.last().position - after.first().position), 0f)
        assertEquals(finished.points.last().radius, before.last().radius, 0f)
        assertEquals(before.last().radius, after.first().radius, 0f)

        // Same curve: the two pieces are, ring for ring, the stroke one mesh would have drawn.
        val whole = FingerDrawing.centerline(
            points = finished.points + continued.points.drop(1),
            spacing = finished.spacing,
        )
        val joined = before + after.drop(1)
        assertEquals(whole.size, joined.size)
        // Compare the rings around the join.
        val join = before.size - 1
        for (i in join - 20..join + 10) {
            assertEquals("ring $i left the curve", 0f, length(whole[i].position - joined[i].position), 1e-6f)
            assertEquals("ring $i changed thickness", whole[i].radius, joined[i].radius, 1e-6f)
        }

        // Same direction: the stroke does not turn a corner at the join.
        val arriving = normalize(before.last().position - before[before.size - 2].position)
        val leaving = normalize(after[1].position - after.first().position)
        assertTrue("the stroke turns ${dot(arriving, leaving)} at the join", dot(arriving, leaving) > 0.995f)
    }

    @Test
    fun `a long stroke counts as one and undo removes it whole`() {
        val before = Drawing().down(-5f).penUp()
        var drawing = before
        drawing = drawing.down(0f)
        var x = 0f
        while (drawing.strokes.size < 3) {
            x += spacing * 4f
            drawing = drawing.penMove(Position(x = x), 0.05f)
        }
        assertEquals("one tap and one long stroke", 2, drawing.strokeCount)
        assertEquals(before.strokes, drawing.undo().strokes)
        assertNull(drawing.undo().active)

        val lifted = drawing.penUp()
        assertEquals(2, lifted.strokeCount)
        assertEquals(before.strokes, lifted.undo().strokes)
        assertTrue(lifted.undo().undo().isEmpty)
    }

    @Test
    fun `the ring estimate never undercounts the centreline`() {
        for (path in paths) {
            for (ringSpacing in listOf(0.01f, 0.05f, 0.5f)) {
                val stroke = Stroke(0, 0, path, towardsViewer, ringSpacing)
                assertTrue(
                    "estimate under the real count at spacing $ringSpacing",
                    FingerDrawing.ringEstimate(stroke) >= FingerDrawing.line(stroke).size,
                )
            }
        }
    }
}

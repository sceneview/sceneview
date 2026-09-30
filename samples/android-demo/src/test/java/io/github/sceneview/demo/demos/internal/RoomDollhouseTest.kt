package io.github.sceneview.demo.demos.internal

import io.github.sceneview.ar.AutoPlacementState
import io.github.sceneview.ar.FrameEffect
import io.github.sceneview.ar.FrameInput
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the dollhouse of #4075: which recording opens, what of the room is kept, how small it
 * stands and what the screen shows. The AR half needs ARCore, which the emulator cannot run
 * (#2754), so everything it is fed is checked here.
 */
class RoomDollhouseTest {

    private fun session(id: String, createdAt: Long, source: RerunSessionSource = RerunSessionSource.Recorded) =
        RerunStoredSession(
            id = id,
            title = "Room $id",
            createdAt = createdAt,
            source = source,
            duration = 10f,
            pathMetres = 3f,
            points = 100,
            planes = 2,
            photos = 4,
        )

    /** A horizontal square of side [size] centred on ([cx], [cz]) at height [y]. */
    private fun square(
        id: Int,
        kind: DebugPlaneKind,
        y: Float,
        size: Float,
        cx: Float = 0f,
        cz: Float = 0f,
    ): DebugPlane {
        val h = size / 2f
        return DebugPlane(
            id, kind,
            floatArrayOf(cx - h, y, cz - h, cx + h, y, cz - h, cx + h, y, cz + h, cx - h, y, cz + h),
        )
    }

    /**
     * A 4 × 3 m room: floor at -1.3 (under the origin, where the phone started), a ceiling at
     * 1.2, a wall, a path at chest height, and five points — one each on the floor, a table and
     * the ceiling, one under the floor and one far outside through a doorway.
     */
    private fun room(): ArDebugFrame {
        val floor = DebugPlane(
            1, DebugPlaneKind.Floor,
            floatArrayOf(-2f, -1.3f, -1.5f, 2f, -1.3f, -1.5f, 2f, -1.3f, 1.5f, -2f, -1.3f, 1.5f),
        )
        val ceiling = square(2, DebugPlaneKind.Ceiling, 1.2f, 3f)
        val wall = DebugPlane(
            3, DebugPlaneKind.Wall,
            floatArrayOf(-2f, -1.3f, -1.5f, 2f, -1.3f, -1.5f, 2f, 1.2f, -1.5f, -2f, 1.2f, -1.5f),
        )
        return ArDebugFrame(
            time = 12f,
            trail = floatArrayOf(-1f, 0f, 0f, 0f, 0f, 0.5f, 1f, 0f, 0f),
            camera = DebugPose(1f, 0f, 0f),
            mapPoints = floatArrayOf(
                0f, -1.29f, 0f, // floor
                0.5f, -0.55f, 0.5f, // table top
                0f, 1.15f, 0f, // ceiling: over the cutaway
                0f, -1.6f, 0f, // under the floor
                9f, -1f, 0f, // far outside, through a doorway
            ),
            livePoints = floatArrayOf(0f, 0f, 0f),
            planes = listOf(floor, ceiling, wall),
            anchors = emptyList(),
            keyframes = listOf(DebugPose(0f, 0f, 0f)),
            mapPointColors = intArrayOf(1, 2, 3, 4, 5),
        )
    }

    // ─── Which recording opens ───────────────────────────────────────────────────────────────

    @Test
    fun `the requested session opens when it is still kept`() {
        val sessions = listOf(session("new", 300), session("old", 100))
        assertEquals("old", RoomDollhouse.pickSession(sessions, requestedId = "old")?.id)
    }

    @Test
    fun `without a request the newest room recorded on the phone opens, not a newer file`() {
        val sessions = listOf(
            session("file", 400, RerunSessionSource.Scan),
            session("recent", 300),
            session("older", 100),
        )
        assertEquals("recent", RoomDollhouse.pickSession(sessions)?.id)
    }

    @Test
    fun `a deleted request falls back to the newest recording`() {
        val sessions = listOf(session("a", 100), session("b", 200))
        assertEquals("b", RoomDollhouse.pickSession(sessions, requestedId = "gone")?.id)
    }

    @Test
    fun `only opened files still open, newest first`() {
        val sessions = listOf(
            session("rrd", 100, RerunSessionSource.Rrd),
            session("scan", 200, RerunSessionSource.Scan),
        )
        assertEquals("scan", RoomDollhouse.pickSession(sessions)?.id)
    }

    @Test
    fun `nothing kept means nothing to open, never a stock asset`() {
        assertNull(RoomDollhouse.pickSession(emptyList()))
        assertNull(RoomDollhouse.pickSession(emptyList(), requestedId = "any"))
    }

    // ─── What of the room is kept ────────────────────────────────────────────────────────────

    @Test
    fun `the ceiling is cut away, the floor and walls stay`() {
        val cropped = RoomDollhouse.crop(room())
        assertEquals(listOf(1, 3), cropped.planes.map { it.id })
    }

    @Test
    fun `points over the cutaway, under the floor or far outside are dropped, with their colours`() {
        val cropped = RoomDollhouse.crop(room())
        assertEquals(2, cropped.mapPointCount)
        assertArrayEquals(floatArrayOf(0f, -1.29f, 0f, 0.5f, -0.55f, 0.5f), cropped.mapPoints, 1e-6f)
        assertArrayEquals(intArrayOf(1, 2), cropped.mapPointColors)
    }

    @Test
    fun `a miniature has no camera, no live points and no frustums, but keeps the path`() {
        val cropped = RoomDollhouse.crop(room())
        assertNull(cropped.camera)
        assertEquals(0, cropped.livePoints.size)
        assertTrue(cropped.keyframes.isEmpty())
        assertEquals(3, cropped.trailLength)
    }

    @Test
    fun `an uncoloured session stays uncoloured`() {
        val plain = room().let {
            ArDebugFrame(it.time, it.trail, it.camera, it.mapPoints, it.livePoints, it.planes, it.anchors)
        }
        assertNull(RoomDollhouse.crop(plain).mapPointColors)
    }

    // ─── How small it stands ─────────────────────────────────────────────────────────────────

    @Test
    fun `the room is centred on its floor, and its size is measured from the floor up`() {
        val fit = requireFit(RoomDollhouse.fit(RoomDollhouse.crop(room())))
        assertEquals(0f, fit.centerX, 1e-5f)
        assertEquals(0f, fit.centerZ, 1e-5f)
        assertEquals(-1.3f, fit.floorY, 1e-5f)
        assertEquals(4f, fit.width, 1e-5f)
        assertEquals(3f, fit.depth, 1e-5f)
        // The wall reaches 1.2, 2.5 m over the floor.
        assertEquals(2.5f, fit.height, 1e-5f)
    }

    @Test
    fun `a four metre room stands at 1 to 8, half a metre long`() {
        val fit = requireFit(RoomDollhouse.fit(RoomDollhouse.crop(room())))
        assertEquals(8, fit.denominator)
        assertEquals("1:8", fit.label)
        assertTrue(fit.width * fit.scale <= RoomDollhouse.MAX_SIDE_M)
        assertArrayEquals(intArrayOf(50, 38), fit.footprintCentimetres())
    }

    @Test
    fun `the scale is the smallest model maker's scale that fits a table`() {
        assertEquals(1, RoomDollhouse.denominatorFor(0.5f))
        assertEquals(2, RoomDollhouse.denominatorFor(1f))
        assertEquals(6, RoomDollhouse.denominatorFor(3.4f))
        assertEquals(8, RoomDollhouse.denominatorFor(4f))
        assertEquals(10, RoomDollhouse.denominatorFor(6f))
        assertEquals(16, RoomDollhouse.denominatorFor(8f))
        assertEquals(32, RoomDollhouse.denominatorFor(15f))
        assertEquals(100, RoomDollhouse.denominatorFor(80f))
    }

    @Test
    fun `any room from 1 to 30 metres stands between 40 and 60 cm, never a palm wide`() {
        var longest = 1f
        while (longest <= 30f) {
            val onTable = longest / RoomDollhouse.denominatorFor(longest)
            assertTrue("$longest m stands $onTable m", onTable in 0.4f..RoomDollhouse.MAX_SIDE_M)
            longest += 0.05f
        }
    }

    @Test
    fun `a degenerate size still gets a scale`() {
        assertEquals(1, RoomDollhouse.denominatorFor(0f))
        assertEquals(1, RoomDollhouse.denominatorFor(Float.NaN))
    }

    @Test
    fun `a session with nothing in it has no fit`() {
        val empty = ArDebugFrame(0f, FloatArray(0), null, FloatArray(0), FloatArray(0), emptyList(), emptyList())
        assertNull(RoomDollhouse.fit(empty))
    }

    @Test
    fun `points alone are enough to stand a room`() {
        val cloud = ArDebugFrame(
            0f, FloatArray(0), null,
            floatArrayOf(-1f, 0f, -1f, 1f, 1f, 1f), FloatArray(0), emptyList(), emptyList(),
        )
        val fit = requireFit(RoomDollhouse.fit(cloud))
        assertEquals(2f, fit.width, 1e-5f)
        assertEquals(2f, fit.depth, 1e-5f)
    }

    @Test
    fun `points keep one size on the table at any scale`() {
        for (denominator in listOf(10, 12, 20)) {
            val scale = 1f / denominator
            val onTable = RoomDollhouse.styleFor(scale).mapPointRadius * scale
            assertEquals(RoomDollhouse.MINIATURE_POINT_RADIUS_M, onTable, 1e-6f)
        }
    }

    @Test
    fun `the path reads thicker than the points on the table`() {
        for (denominator in listOf(5, 8, 12)) {
            val style = RoomDollhouse.styleFor(1f / denominator)
            val trailOnTable = style.trailRadius / denominator
            assertTrue("1:$denominator trail $trailOnTable m", trailOnTable >= 0.0025f)
            assertTrue(style.trailRadius > style.mapPointRadius)
        }
    }

    @Test
    fun `at real size points keep the replay's floor`() {
        assertEquals(0.004f, RoomDollhouse.styleFor(1f).mapPointRadius, 1e-6f)
    }

    @Test
    fun `the plinth reaches past the room just under its floor`() {
        val fit = DollhouseFit(1f, -1f, 2f, width = 4f, height = 2f, depth = 2f, denominator = 12)
        val base = RoomDollhouse.basePolygon(fit)
        assertEquals(12, base.size)
        val xs = (0 until 4).map { base[it * 3] }
        val ys = (0 until 4).map { base[it * 3 + 1] }
        val zs = (0 until 4).map { base[it * 3 + 2] }
        assertEquals(1f - 2f - RoomDollhouse.BASE_MARGIN_M, xs.min(), 1e-5f)
        assertEquals(1f + 2f + RoomDollhouse.BASE_MARGIN_M, xs.max(), 1e-5f)
        assertEquals(2f - 1f - RoomDollhouse.BASE_MARGIN_M, zs.min(), 1e-5f)
        assertTrue(ys.all { it == -1f - RoomDollhouse.BASE_DROP_M })
    }

    @Test
    fun `the hull keeps the outline and drops what lies inside it`() {
        val hull = RoomDollhouse.convexHull(
            listOf(0f to 0f, 2f to 0f, 2f to 2f, 0f to 2f, 1f to 1f, 1f to 0f, 0f to 0f),
        )
        assertEquals(setOf(0f to 0f, 2f to 0f, 2f to 2f, 0f to 2f), hull.toSet())
        assertEquals(4, hull.size)
    }

    @Test
    fun `the plinth follows a room turned on the session's axes, not the box around it`() {
        // A 2 × 2 m floor turned 45°: its box is 2.83 m wide, its outline a diamond.
        val r = 1.4142135f
        val diamond = DebugPlane(
            1, DebugPlaneKind.Floor,
            floatArrayOf(0f, -1f, -r, r, -1f, 0f, 0f, -1f, r, -r, -1f, 0f),
        )
        val frame = ArDebugFrame(
            time = 1f, trail = FloatArray(0), camera = null, mapPoints = FloatArray(0),
            livePoints = FloatArray(0), planes = listOf(diamond), anchors = emptyList(),
        )
        val fit = requireFit(RoomDollhouse.fit(frame))
        val base = RoomDollhouse.basePolygon(frame, fit)
        assertEquals(12, base.size)
        val far = r + RoomDollhouse.BASE_MARGIN_M
        for (i in 0 until 4) {
            val x = base[i * 3]
            val z = base[i * 3 + 2]
            // Each corner is pushed straight out from the middle, so one of x, z stays 0.
            assertEquals(far, maxOf(kotlin.math.abs(x), kotlin.math.abs(z)), 1e-4f)
            assertEquals(0f, minOf(kotlin.math.abs(x), kotlin.math.abs(z)), 1e-4f)
            assertEquals(fit.floorY - RoomDollhouse.BASE_DROP_M, base[i * 3 + 1], 1e-6f)
        }
    }

    @Test
    fun `a room without planes stands on its box`() {
        val fit = DollhouseFit(0f, 0f, 0f, width = 2f, height = 1f, depth = 2f, denominator = 10)
        val frame = ArDebugFrame(
            time = 1f, trail = FloatArray(0), camera = null, mapPoints = floatArrayOf(0f, 0f, 0f),
            livePoints = FloatArray(0), planes = emptyList(), anchors = emptyList(),
        )
        assertArrayEquals(RoomDollhouse.basePolygon(fit), RoomDollhouse.basePolygon(frame, fit), 0f)
    }

    // ─── What the screen shows ───────────────────────────────────────────────────────────────

    @Test
    fun `the screen waits for the list, then offers to record when it is empty`() {
        assertEquals(DollhouseStage.Loading, stage(sessionsKnown = false))
        assertEquals(DollhouseStage.Empty, stage(hasSession = false))
    }

    @Test
    fun `a session opens in AR, or in 3D without AR or on request`() {
        assertEquals(DollhouseStage.Loading, stage(opened = false))
        assertEquals(DollhouseStage.InRoom, stage())
        assertEquals(DollhouseStage.Preview, stage(arAvailable = false))
        assertEquals(DollhouseStage.Preview, stage(previewChosen = true))
    }

    @Test
    fun `an unreadable session says so instead of loading forever`() {
        assertEquals(DollhouseStage.Failed, stage(opened = false, openFailed = true))
    }

    @Test
    fun `the copy names the scale and the size on the table`() {
        val fit = DollhouseFit(0f, 0f, 0f, width = 4f, height = 2.5f, depth = 3f, denominator = 12)
        assertEquals("Room · 1:12", DollhouseCopy.peek("Room", fit, realSize = false))
        assertEquals("Room · Real size", DollhouseCopy.peek("Room", fit, realSize = true))
        assertEquals("1:12 · 33 × 25 cm on the table", DollhouseCopy.size(fit, realSize = false))
    }

    @Test
    fun `the lit scale toggle and the pill above the dock name the same scale`() {
        val fit = DollhouseFit(0f, 0f, 0f, width = 4f, height = 2.5f, depth = 3f, denominator = 12)
        for (realSize in listOf(false, true)) {
            val toggle = DollhouseCopy.scaleToggle(realSize)
            // One name whatever the state: a toggle, lit when on, like every dock toggle.
            assertEquals(DollhouseCopy.REAL_SIZE, toggle.label)
            assertEquals(realSize, toggle.selected)
            // Lit exactly when the pill says real size; unlit, the pill names the miniature's scale.
            assertEquals(toggle.selected, DollhouseCopy.peek("Room", fit, realSize).endsWith(toggle.label))
        }
        assertEquals("Room · 1:12", DollhouseCopy.peek("Room", fit, DollhouseCopy.scaleToggle(false).selected))
    }

    @Test
    fun `the side the recording started from faces the user`() {
        // The path starts at (-1, 0, 0), a metre to -X of the room's middle.
        val room = requireNotNull(RoomDollhouse.room(room()))
        val o = RoomDollhouse.orientation(room)
        val yaw = Math.toRadians(o.yawDegrees.toDouble())
        val dx = room.frame.trail[0] - room.fit.centerX
        val dz = room.frame.trail[2] - room.fit.centerZ
        // Turned about +Y, the start lies straight towards +Z — the user.
        assertEquals(0.0, dx * kotlin.math.cos(yaw) + dz * kotlin.math.sin(yaw), 1e-4)
        assertTrue(-dx * kotlin.math.sin(yaw) + dz * kotlin.math.cos(yaw) > 0.0)
    }

    @Test
    fun `at real size the room reaches away from where the miniature stood, not around it`() {
        val room = requireNotNull(RoomDollhouse.room(room()))
        val o = RoomDollhouse.orientation(room)
        // The 4 × 3 m room is turned a quarter: its 4 m side runs towards the user, 2 m each way.
        assertEquals(2f, o.front, 0.05f)
        // The miniature stays centred where the user aimed; at real size its near side sits there.
        assertEquals(0f, o.offsetZ(realSize = false, scale = room.fit.scale), 0f)
        assertEquals(-o.front, o.offsetZ(realSize = true, scale = 1f), 0f)
    }

    @Test
    fun `a path starting in the middle of the room names no side, and the room keeps its axes`() {
        val fit = DollhouseFit(0f, 0f, 0f, width = 4f, height = 2.5f, depth = 3f, denominator = 12)
        val frame = ArDebugFrame(
            0f, floatArrayOf(0.1f, 0f, 0.1f), null, FloatArray(0), FloatArray(0), emptyList(), emptyList(),
        )
        val o = RoomDollhouse.orientation(DollhouseRoom(frame, fit))
        assertEquals(0f, o.yawDegrees, 0f)
        assertEquals(1.5f, o.front, 1e-5f)
    }

    @Test
    fun `a pinch reads as the scale the room now stands at`() {
        val fit = DollhouseFit(0f, 0f, 0f, width = 4f, height = 2.5f, depth = 3f, denominator = 12)
        assertEquals("1:12", DollhouseCopy.pinched(fit, 1f, realSize = false))
        assertEquals("1:6", DollhouseCopy.pinched(fit, 2f, realSize = false))
        assertEquals("1:48", DollhouseCopy.pinched(fit, 0.25f, realSize = false))
        assertEquals("50% of real size", DollhouseCopy.pinched(fit, 0.5f, realSize = true))
    }

    @Test
    fun `a recording that kept no surface says so rather than standing an empty plinth`() {
        assertEquals(DollhouseStage.NoSurfaces, stage(hasSurfaces = false))
        assertEquals(DollhouseStage.NoSurfaces, stage(hasSurfaces = false, arAvailable = false))
        // Still read first, and a failure is a failure.
        assertEquals(DollhouseStage.Loading, stage(opened = false, hasSurfaces = false))
        assertEquals(DollhouseStage.Failed, stage(opened = false, openFailed = true, hasSurfaces = false))
    }

    // ─── Surfaces: what a real recording kept ────────────────────────────────────────────────

    @Test
    fun `a path and photos alone are no room`() {
        val pathOnly = ArDebugFrame(
            time = 5f, trail = floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f), camera = null, mapPoints = FloatArray(0),
            livePoints = FloatArray(0), planes = emptyList(), anchors = emptyList(),
        )
        assertFalse(RoomDollhouse.hasSurfaces(pathOnly))
        assertFalse(RoomDollhouse.hasSurfaces(session("bare", 1).copy(planes = 0, points = 0)))
        assertFalse(RoomDollhouse.hasSurfaces(session("noise", 1).copy(planes = 0, points = 29)))
    }

    @Test
    fun `a plane or enough points make a room`() {
        assertTrue(RoomDollhouse.hasSurfaces(room()))
        assertTrue(RoomDollhouse.hasSurfaces(session("plane", 1).copy(planes = 1, points = 0)))
        assertTrue(RoomDollhouse.hasSurfaces(session("cloud", 1).copy(planes = 0, points = 30)))
    }

    @Test
    fun `without a request a recording with surfaces opens before a newer bare path`() {
        val sessions = listOf(
            session("bare", 300).copy(planes = 0, points = 0),
            session("room", 200),
        )
        assertEquals("room", RoomDollhouse.pickSession(sessions)?.id)
        // Asked for by name, the bare one still opens — and says it has no surfaces.
        assertEquals("bare", RoomDollhouse.pickSession(sessions, requestedId = "bare")?.id)
    }

    @Test
    fun `with only bare paths the newest recording opens`() {
        val sessions = listOf(
            session("a", 100).copy(planes = 0, points = 0),
            session("b", 200).copy(planes = 0, points = 0),
        )
        assertEquals("b", RoomDollhouse.pickSession(sessions)?.id)
    }

    @Test
    fun `the recordings to pick from are listed newest first`() {
        val sessions = listOf(session("mid", 200), session("old", 100), session("new", 300, RerunSessionSource.Scan))
        assertEquals(listOf("new", "mid", "old"), RoomDollhouse.choices(sessions).map { it.id })
    }

    @Test
    fun `each recording says how many surfaces it kept`() {
        assertEquals("No surfaces", DollhouseCopy.surfaces(planes = 0, points = 12))
        assertEquals("3 surfaces · 1,240 points", DollhouseCopy.surfaces(planes = 3, points = 1240))
        assertEquals("1 surface · 40 points", DollhouseCopy.surfaces(planes = 1, points = 40))
    }

    // ─── Reset, and coming back to AR ────────────────────────────────────────────────────────

    @Test
    fun `a dismissed placement state never places again - why each AR view gets a fresh one`() {
        // What an AR view leaving the composition does to its state (AutoPlacementScene's
        // DisposableEffect). The dollhouse used to keep that same state for the next AR view.
        val stale = AutoPlacementState()
        stale.dismiss()
        stale.requestPlacement()
        val frame = FrameInput(nowMillis = 1_000L, tracking = true, surfaceAvailable = true)
        assertEquals(FrameEffect.NONE, stale.onFrame(frame))
        assertFalse(stale.hasPlacement)

        val fresh = AutoPlacementState()
        fresh.requestPlacement()
        assertEquals(FrameEffect.PLACE, fresh.onFrame(frame))
    }

    @Test
    fun `resetting the placement alone stands the room right back where it was`() {
        // The old Reset: the next frame with a surface under the aim places again, at once.
        val state = AutoPlacementState()
        state.requestPlacement()
        val frame = FrameInput(nowMillis = 1_000L, tracking = true, surfaceAvailable = true)
        assertEquals(FrameEffect.PLACE, state.onFrame(frame))
        state.resetPlacement(1_100L)
        assertEquals(FrameEffect.PLACE, state.onFrame(frame.copy(nowMillis = 1_116L)))
    }

    @Test
    fun `reset takes a fresh placement state, back to 1 to 12, and holds before standing again`() {
        val placed = DollhouseArControl().toggleRealSize()
        assertTrue(placed.realSize)
        val reset = placed.reset(nowMillis = 10_000L)
        assertEquals(placed.generation + 1, reset.generation)
        assertEquals(placed.sceneKey, reset.sceneKey) // the camera keeps running
        assertFalse(reset.realSize)
        assertFalse(reset.armed(10_000L))
        assertFalse(reset.armed(10_000L + DollhouseArControl.RESET_HOLD_MS - 1))
        assertTrue(reset.armed(10_000L + DollhouseArControl.RESET_HOLD_MS))
    }

    @Test
    fun `two resets in a row are two fresh states`() {
        val once = DollhouseArControl().reset(1_000L)
        val twice = once.reset(1_200L)
        assertTrue(twice.generation > once.generation)
        assertFalse(twice.armed(1_200L + DollhouseArControl.RESET_HOLD_MS - 1))
    }

    @Test
    fun `leaving AR takes a fresh state for the way back, with no hold`() {
        val control = DollhouseArControl().reset(1_000L).toggleRealSize()
        val back = control.leftAr()
        assertEquals(control.generation + 1, back.generation)
        assertFalse(back.realSize)
        assertTrue(back.armed(0L))
    }

    @Test
    fun `restarting the session rebuilds the AR view with a fresh state`() {
        val control = DollhouseArControl()
        val restarted = control.restart()
        assertEquals(control.sceneKey + 1, restarted.sceneKey)
        assertEquals(control.generation + 1, restarted.generation)
        assertTrue(restarted.armed(0L))
    }

    @Test
    fun `the contact shadow ring is pushed out from the plinth, at the height asked`() {
        val square = floatArrayOf(-1f, 0f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, -1f, 0f, 1f)
        val ring = RoomDollhouse.expand(square, margin = 0.5f, y = -0.1f)
        assertEquals(12, ring.size)
        for (i in 0 until 4) {
            val x = ring[i * 3]
            val z = ring[i * 3 + 2]
            // Each corner moves 0.5 m straight out from the middle.
            assertEquals(kotlin.math.sqrt(2f) + 0.5f, kotlin.math.sqrt(x * x + z * z), 1e-4f)
            assertEquals(-0.1f, ring[i * 3 + 1], 0f)
        }
    }

    @Test
    fun `the plinth keeps one thickness on the table at any scale`() {
        for (denominator in listOf(10, 12, 20)) {
            val scale = 1f / denominator
            assertEquals(RoomDollhouse.PLINTH_ON_TABLE_M, RoomDollhouse.plinthThickness(scale) * scale, 1e-6f)
        }
    }

    private fun stage(
        sessionsKnown: Boolean = true,
        hasSession: Boolean = true,
        opened: Boolean = true,
        openFailed: Boolean = false,
        arAvailable: Boolean = true,
        previewChosen: Boolean = false,
        hasSurfaces: Boolean = true,
    ) = dollhouseStage(sessionsKnown, hasSession, opened, openFailed, arAvailable, previewChosen, hasSurfaces)

    private fun requireFit(fit: DollhouseFit?): DollhouseFit {
        assertNotNull("expected the room to stand", fit)
        return fit!!
    }
}

package io.github.sceneview.demo.demos.internal

import io.github.sceneview.math.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Pins the free camera of the Room Scan demo's in-app 3D view (#3950, #4306): it frames the
 * session by itself until touched, a touch hands it to the finger, a double-tap hands it back —
 * and neither the automatic framing nor a gesture can lose the room.
 */
class ArDebugOrbitCameraTest {

    private val fov = 45.0
    private val room = floatArrayOf(-2f, -1.3f, -3f, 2f, 1.1f, 1.5f)

    /** Where [point] lands in the picture of [pose]: x and y in half-views (±1 = the view's edge). */
    private fun project(pose: OrbitPose, lift: Float, aspect: Float, point: FloatArray): Pair<Double, Double> {
        val azimuth = Math.toRadians(pose.azimuthDegrees.toDouble())
        val elevation = Math.toRadians(pose.elevationDegrees.toDouble())
        val back = doubleArrayOf(cos(elevation) * sin(azimuth), sin(elevation), cos(elevation) * cos(azimuth))
        val right = doubleArrayOf(cos(azimuth), 0.0, -sin(azimuth))
        val up = doubleArrayOf(-sin(azimuth) * sin(elevation), cos(elevation), -cos(azimuth) * sin(elevation))
        val rel = doubleArrayOf(
            (point[0] - pose.target.x).toDouble(),
            (point[1] - pose.target.y).toDouble(),
            (point[2] - pose.target.z).toDouble(),
        )
        fun dot(axis: DoubleArray) = rel[0] * axis[0] + rel[1] * axis[1] + rel[2] * axis[2]
        val tanV = tan(Math.toRadians(fov / 2.0))
        val depth = pose.distance - dot(back)
        assertTrue("the point is in front of the lens", depth > 0.0)
        // The pedestal: the lens sits 2·lift half-views lower, so the picture rides that much higher.
        val y = (dot(up) + 2.0 * lift * pose.distance * tanV) / (depth * tanV)
        return dot(right) / (depth * tanV * aspect) to y
    }

    private fun corners(box: FloatArray) = List(8) { i ->
        floatArrayOf(box[if (i and 1 == 0) 0 else 3], box[if (i and 2 == 0) 1 else 4], box[if (i and 4 == 0) 2 else 5])
    }

    /** The share of [band] the box of [pose] fills, after checking that none of it leaves the band. */
    private fun assertFramed(pose: OrbitPose, band: OrbitBand, aspect: Float, box: FloatArray = room): Double {
        var reach = 0.0
        for (corner in corners(box)) {
            val (x, y) = project(pose, band.lift, aspect, corner)
            val across = abs(x) / band.halfWidth
            val along = abs(y - 2.0 * band.lift) / band.halfHeight
            assertTrue("x = $x leaves the band at aspect $aspect", across <= 1.0 + 1e-3)
            assertTrue("y = $y leaves the band at aspect $aspect", along <= 1.0 + 1e-3)
            reach = maxOf(reach, across, along)
        }
        return reach
    }

    // ── Framing ───────────────────────────────────────────────────────────────

    @Test
    fun `home frames a bigger session from further away`() {
        val small = ArDebugFraming.home(floatArrayOf(0f, 0f, 0f, 2f, 0.5f, 2f), 35f, fov, 0.5f)
        val large = ArDebugFraming.home(floatArrayOf(0f, 0f, 0f, 8f, 0.5f, 8f), 35f, fov, 0.5f)

        assertTrue(large.distance > small.distance)
        assertEquals(ArDebugFraming.HOME_ELEVATION, small.elevationDegrees, 1e-4f)
    }

    @Test
    fun `the whole room sits in the clear band, on a tall phone, on its side and in a card`() {
        val views = listOf(
            OrbitBand.STAGE_PORTRAIT to 0.46f,
            OrbitBand.STAGE_LANDSCAPE to 2.1f,
            OrbitBand.CARD to 1.3f,
            OrbitBand.SCAN to 0.46f,
        )
        for ((band, aspect) in views) for (azimuth in listOf(0f, 35f, 120f, 200f, 310f)) {
            for (elevation in listOf(ArDebugFraming.HOME_ELEVATION, ArDebugFraming.MAP_ELEVATION)) {
                val home = ArDebugFraming.home(room, azimuth, fov, aspect, elevation, band)
                val reach = assertFramed(home, band, aspect)
                // Fitted, not merely contained: the room touches the band's edge.
                assertTrue("the room fills its band ($reach at $azimuth°, aspect $aspect)", reach > 0.97)
            }
        }
    }

    @Test
    fun `a terrace-sized scan is framed whole, from as far as it takes`() {
        // 20 × 9 m, as a real outdoor scan reads (#4306): the fit stands past the 40 m the camera
        // used to stop at, which cropped it.
        val terrace = floatArrayOf(-10f, -1.3f, -4.5f, 10f, 1.3f, 4.5f)
        val aspect = 1080f / 2400f
        val band = OrbitBand.STAGE_PORTRAIT
        var furthest = 0f
        for (azimuth in listOf(0f, 35f, 90f, 200f, 310f)) {
            for (elevation in listOf(ArDebugFraming.HOME_ELEVATION, ArDebugFraming.MAP_ELEVATION)) {
                val home = ArDebugFraming.home(terrace, azimuth, fov, aspect, elevation, band)
                val reach = assertFramed(home, band, aspect, terrace)
                assertTrue("the terrace fills its band ($reach at $azimuth°, $elevation°)", reach > 0.97)
                furthest = maxOf(furthest, home.distance)

                // The gestures' limits hold that framing, and a pinch out stops 1.6× past it —
                // with the terrace's far side still in front of the far plane.
                val limits = ArDebugFraming.limits(terrace, home, floorY = terrace[1])
                assertEquals(home.distance, ArDebugFraming.clamp(home, limits, band.lift, fov).distance, 1e-3f)
                assertEquals(home.distance * ArDebugFraming.MAX_ZOOM_OUT, limits.maxDistance, 1e-2f)
                assertTrue(limits.maxDistance + home.distance < ArDebugFraming.FAR_PLANE_M)
            }
        }
        assertTrue("the terrace is framed from $furthest m", furthest > 50f)

        // The camera itself rests on that pose: nothing between the fit and the lens pulls it in.
        val camera = ArDebugOrbitCamera(drift = false, band = band)
        camera.setViewport(1080, 2400)
        val home = ArDebugFraming.home(terrace, 35f, fov, camera.aspect, band = band)
        camera.home = home
        camera.limits = ArDebugFraming.limits(terrace, home, floorY = terrace[1])
        camera.snapTo(home)
        assertEquals(home.distance, camera.pose.distance, 1e-3f)
        assertFramed(camera.pose, band, camera.aspect, terrace)
    }

    @Test
    fun `a room off the origin is framed like one on it`() {
        val moved = FloatArray(6) { room[it] + floatArrayOf(40f, -7f, 12f)[it % 3] }
        val here = ArDebugFraming.home(room, 35f, fov, 0.46f, band = OrbitBand.STAGE_PORTRAIT)
        val there = ArDebugFraming.home(moved, 35f, fov, 0.46f, band = OrbitBand.STAGE_PORTRAIT)

        assertEquals(here.distance, there.distance, 1e-2f)
        assertFramed(there, OrbitBand.STAGE_PORTRAIT, 0.46f, moved)
    }

    @Test
    fun `the band measured between the chrome is the stage the room is fitted into`() {
        // A 2400 px view: the figures end at 620, the timeline starts at 1570.
        val band = OrbitBand.between(top = 620f, bottom = 1570f, viewHeight = 2400f)!!
        // 950 px of 2400, with air kept against the chrome; centred 105 px above the middle.
        assertEquals(950f / 2400f * OrbitBand.MEASURED_FILL, band.halfHeight, 0.006f)
        assertEquals(105f / 2400f, band.lift, 0.006f)

        val pose = ArDebugFraming.home(room, 35f, fov, 1080f / 2400f, band = band)
        assertFramed(pose, band, 1080f / 2400f, room)
        // Every corner of the room is drawn between the two cards, in pixels.
        corners(room).forEach { corner ->
            val y = project(pose, band.lift, 1080f / 2400f, corner).second
            val px = (1.0 - y) / 2.0 * 2400.0
            assertTrue("corner at $px px", px in 620.0..1570.0)
        }
    }

    @Test
    fun `a band that is not measured yet, or leaves no stage, falls back`() {
        assertNull(OrbitBand.between(Float.NaN, 1570f, 2400f))
        assertNull(OrbitBand.between(620f, Float.NaN, 2400f))
        assertNull(OrbitBand.between(620f, 1570f, 0f))
        // A landscape phone: the cards meet, or nearly.
        assertNull(OrbitBand.between(500f, 620f, 1080f))
        assertNull(OrbitBand.between(700f, 620f, 1080f))
    }

    @Test
    fun `a phone on its side keeps the room between the two cards beside it`() {
        // A 2400 x 1080 view: the figures end 880 px from the left — a cutout pushes them in —
        // the timeline starts 760 px from the right, the status bar ends 72 px down and the mode
        // pill starts 640 px down.
        val halfWidth = OrbitBand.halfWidthBeside(cardEnd = 880f, viewWidth = 2400f)!!
        assertEquals((1f - 2f * 880f / 2400f) * OrbitBand.MEASURED_FILL, halfWidth, 0.006f)
        val band = OrbitBand.betweenSides(
            startCardEnd = 880f, endCardStart = 2400f - 760f, top = 72f, bottom = 640f,
            viewWidth = 2400f, viewHeight = 1080f,
        )!!
        // The card that reaches further in decides for both sides, whichever side it is on.
        assertEquals(halfWidth, band.halfWidth, 0f)
        assertEquals(
            band,
            OrbitBand.betweenSides(
                startCardEnd = 760f, endCardStart = 2400f - 880f, top = 72f, bottom = 640f,
                viewWidth = 2400f, viewHeight = 1080f,
            ),
        )

        val pose = ArDebugFraming.home(room, 35f, fov, 2400f / 1080f, band = band)
        assertFramed(pose, band, 2400f / 1080f, room)
        corners(room).forEach { corner ->
            val (x, y) = project(pose, band.lift, 2400f / 1080f, corner)
            val px = (x + 1.0) / 2.0 * 2400.0
            val py = (1.0 - y) / 2.0 * 1080.0
            assertTrue("corner at $px px across", px > 880.0 && px < 2400.0 - 880.0)
            assertTrue("corner at $py px down", py in 72.0..640.0)
        }
    }

    @Test
    fun `cards that are not measured yet, or leave no stage between them, fall back`() {
        assertNull(OrbitBand.halfWidthBeside(Float.NaN, 2400f))
        assertNull(OrbitBand.halfWidthBeside(880f, 0f))
        assertNull(OrbitBand.halfWidthBeside(-1f, 2400f))
        // An upright phone: the figures span the width.
        assertNull(OrbitBand.halfWidthBeside(1040f, 1080f))
        // A short and narrow window (split screen, 700 dp): two 296 dp cards leave no stage, so
        // the replay keeps its stacked layout there rather than put the room under them.
        assertNull(OrbitBand.halfWidthBeside(296f, 700f))
        assertEquals(0.31f, OrbitBand.halfWidthBeside(296f, 900f)!!, 0.006f)
        assertNull(OrbitBand.betweenSides(Float.NaN, 1640f, 72f, 640f, 2400f, 1080f))
        assertNull(OrbitBand.betweenSides(880f, Float.NaN, 72f, 640f, 2400f, 1080f))
        assertNull(OrbitBand.betweenSides(880f, 1640f, 72f, Float.NaN, 2400f, 1080f))
        // The marker under the room is not placed yet: no height is left.
        assertNull(OrbitBand.betweenSides(880f, 1640f, 72f, 0f, 2400f, 1080f))
        // The two cards meet: nothing is left between them.
        assertNull(OrbitBand.betweenSides(1150f, 1250f, 72f, 640f, 2400f, 1080f))
    }

    @Test
    fun `a session still growing leaves room round it`() {
        val whole = ArDebugFraming.home(room, 35f, fov, 0.46f, band = OrbitBand.SCAN)
        val growing = ArDebugFraming.home(
            room, 35f, fov, 0.46f, band = OrbitBand.SCAN, fill = ArDebugFraming.GROWING_FILL,
        )

        assertTrue(growing.distance > whole.distance)
        assertTrue(assertFramed(growing, OrbitBand.SCAN, 0.46f) < ArDebugFraming.GROWING_FILL + 0.02)
    }

    @Test
    fun `a session that barely moved still gets a room-sized view`() {
        val speck = floatArrayOf(1f, 0f, 1f, 1.01f, 0.01f, 1.01f)
        val home = ArDebugFraming.home(speck, 35f, fov, 0.46f)

        assertTrue(home.distance > 1f)
    }

    @Test
    fun `an empty session opens on a room-sized view`() {
        val pose = ArDebugFraming.home(null, 12f, fov, 0.5f)

        assertEquals(ArDebugFraming.DEFAULT_POSE.distance, pose.distance, 1e-4f)
        assertEquals(12f, pose.azimuthDegrees, 1e-4f)
    }

    // ── Limits ────────────────────────────────────────────────────────────────

    @Test
    fun `clamp keeps the camera above the horizon and within zoom range`() {
        val pose = ArDebugFraming.clamp(OrbitPose(elevationDegrees = -80f, distance = 1_000f))

        assertEquals(ArDebugFraming.MIN_ELEVATION, pose.elevationDegrees, 1e-4f)
        assertTrue(ArDebugFraming.MIN_ELEVATION > 0f)
        assertEquals(ArDebugFraming.MAX_DISTANCE, pose.distance, 1e-4f)
        val clamped = ArDebugFraming.clamp(OrbitPose(distance = Float.NaN))
        assertEquals(ArDebugFraming.DEFAULT_POSE.distance, clamped.distance, 1e-4f)
    }

    private fun stageCamera(): ArDebugOrbitCamera {
        val camera = ArDebugOrbitCamera(drift = false, band = OrbitBand.STAGE_PORTRAIT)
        camera.setViewport(1080, 2400)
        val home = ArDebugFraming.home(room, 35f, fov, camera.aspect, band = camera.band)
        camera.home = home
        camera.limits = ArDebugFraming.limits(room, home, floorY = room[1])
        camera.snapTo(home)
        return camera
    }

    private fun lensHeight(camera: ArDebugOrbitCamera): Float = camera.getTransform().position.y

    private fun inside(box: FloatArray, p: Position, slack: Float = 0f) =
        p.x > box[0] + slack && p.x < box[3] - slack && p.y > box[1] + slack && p.y < box[4] - slack &&
            p.z > box[2] + slack && p.z < box[5] - slack

    @Test
    fun `a pinch in stops outside the room, a pinch out stops while it is still a room`() {
        val camera = stageCamera()
        val framed = camera.pose.distance
        camera.scrollBegin(0, 0, 100f)
        repeat(60) { camera.scrollUpdate(0, 0, 100f, 400f) }
        assertFalse(camera.following)
        assertFalse("the eye is not in the middle of the cloud", inside(room, CameraRig.eye(camera.pose)))

        repeat(60) { camera.scrollUpdate(0, 0, 400f, 100f) }
        assertEquals(framed * ArDebugFraming.MAX_ZOOM_OUT, camera.pose.distance, 1e-2f)
    }

    @Test
    fun `no drag puts the lens under the floor or inside the room`() {
        val camera = stageCamera()
        camera.grabBegin(500, 1000, strafe = false)
        // Down as far as it goes, all the way round.
        for (step in 1..80) {
            camera.grabUpdate(500 + step * 40, 1000 + step * 60)
            assertTrue(
                "the lens stays above the floor (step $step: ${lensHeight(camera)})",
                lensHeight(camera) >= room[1] + ArDebugFraming.FLOOR_CLEARANCE_M - 1e-2f,
            )
            assertFalse(inside(room, CameraRig.eye(camera.pose)))
        }
        camera.grabEnd()
    }

    @Test
    fun `a pan cannot take the pivot out of the room`() {
        val camera = stageCamera()
        camera.grabBegin(500, 1000, strafe = true)
        repeat(40) { camera.grabUpdate(500 + it * 300, 1000 - it * 300) }
        camera.grabEnd()

        assertTrue(inside(room, camera.pose.target, slack = -1e-3f))
        assertFalse(inside(room, CameraRig.eye(camera.pose)))
    }

    @Test
    fun `the eye leaves the box along its own line of sight`() {
        val pose = OrbitPose(Position(0f, 0f, 0f), azimuthDegrees = 0f, elevationDegrees = 0f, distance = 1f)
        val box = floatArrayOf(-1f, -1f, -2f, 1f, 1f, 3f)

        assertEquals(3f + ArDebugFraming.EYE_CLEARANCE_M, ArDebugFraming.exitDistance(box, pose), 1e-4f)
        assertEquals(0f, ArDebugFraming.exitDistance(null, pose), 0f)
    }

    // ── Heading ───────────────────────────────────────────────────────────────

    @Test
    fun `the room is seen from the side it was scanned from`() {
        // Scanned from +Z, looking at the far wall: the camera stands on that side, three-quarter.
        val trail = floatArrayOf(0f, 0f, 1.2f, 0.3f, 0f, 1.4f, -0.3f, 0f, 1.3f)
        val front = ArDebugFraming.frontAzimuth(room, trail)
        assertTrue(abs(CameraRig.shortestDelta(0f, front)) < 60f)

        // A walk round the room's middle names no side.
        val centre = floatArrayOf(0f, 0f, -0.75f)
        assertEquals(ArDebugFraming.HOME_AZIMUTH, ArDebugFraming.frontAzimuth(room, centre), 0f)
        assertEquals(ArDebugFraming.HOME_AZIMUTH, ArDebugFraming.frontAzimuth(null, trail), 0f)
        assertEquals(ArDebugFraming.HOME_AZIMUTH, ArDebugFraming.frontAzimuth(room, FloatArray(0)), 0f)
    }

    @Test
    fun `the map squares the walls with the screen by the nearest quarter-turn`() {
        for (current in listOf(-170f, -40f, 0f, 35f, 100f, 400f)) for (yaw in listOf(-30f, 0f, 12f, 44f)) {
            val heading = ArDebugFraming.mapAzimuth(current, yaw)
            assertTrue("never more than an eighth of a turn", abs(heading - current) <= 45f + 1e-3f)
            val off = CameraRig.shortestDelta(yaw, heading)
            assertEquals(0f, off - Math.round(off / 90f) * 90f, 1e-3f)
        }
        assertEquals(35f, ArDebugFraming.mapAzimuth(35f, null), 0f)
    }

    @Test
    fun `the idle sway stays in front of the room`() {
        assertEquals(0f, ArDebugFraming.sway(0f), 0f)
        val camera = ArDebugOrbitCamera(drift = true)
        camera.setViewport(1080, 2400)
        camera.frontAzimuth = 70f
        camera.recenter()
        var widest = 0f
        // Two minutes left alone: the old turntable would have gone round twice.
        repeat(120 * 60) {
            camera.update(1f / 60f)
            if (it > 180) widest = maxOf(widest, abs(camera.pose.azimuthDegrees - 70f))
        }
        assertTrue("sways ($widest)", widest > ArDebugFraming.SWAY_DEGREES * 0.8f)
        assertTrue("never turns its back ($widest)", widest <= ArDebugFraming.SWAY_DEGREES + 0.5f)
    }

    @Test
    fun `recenter comes back by the shortest way round, without a whip`() {
        val camera = ArDebugOrbitCamera(drift = false)
        camera.setViewport(1080, 2400)
        camera.frontAzimuth = 10f
        camera.snapTo(OrbitPose(azimuthDegrees = 350f, elevationDegrees = 30f, distance = 3f))
        camera.recenter()
        var fastest = 0f
        var last = camera.pose.azimuthDegrees
        repeat(240) {
            camera.update(1f / 60f)
            fastest = maxOf(fastest, abs(camera.pose.azimuthDegrees - last) * 60f)
            last = camera.pose.azimuthDegrees
        }
        // 350° → 370°, not back down through 180°.
        assertEquals(370f, camera.pose.azimuthDegrees, 0.1f)

        camera.snapTo(OrbitPose(azimuthDegrees = 190f, elevationDegrees = 30f, distance = 3f))
        camera.recenter()
        last = camera.pose.azimuthDegrees
        repeat(240) {
            camera.update(1f / 60f)
            fastest = maxOf(fastest, abs(camera.pose.azimuthDegrees - last) * 60f)
            last = camera.pose.azimuthDegrees
        }
        assertEquals(0f, CameraRig.shortestDelta(camera.pose.azimuthDegrees, 10f), 0.1f)
        assertTrue("turned at $fastest°/s", fastest <= ArDebugFraming.MAX_TURN_DEGREES_PER_SECOND + 1f)
    }

    @Test
    fun `the map holds still, squared with the room`() {
        val camera = ArDebugOrbitCamera(drift = true)
        camera.setViewport(1080, 2400)
        camera.roomYawDegrees = 12f
        camera.overhead = true
        repeat(600) { camera.update(1f / 60f) }
        val settled = camera.pose.azimuthDegrees
        repeat(600) { camera.update(1f / 60f) }

        assertEquals(settled, camera.pose.azimuthDegrees, 1e-3f)
        val off = CameraRig.shortestDelta(12f, settled)
        assertEquals(0f, off - Math.round(off / 90f) * 90f, 0.1f)
    }

    // ── Following and gestures ────────────────────────────────────────────────

    @Test
    fun `the approach reaches home, at the same pace whatever the frame rate`() {
        val home = OrbitPose(azimuthDegrees = 40f, elevationDegrees = 30f, distance = 4f)
        var at60 = OrbitPose(distance = 2f)
        var at120 = OrbitPose(distance = 2f)
        repeat(30) { at60 = ArDebugFraming.approach(at60, home, 1f / 60f) }
        repeat(60) { at120 = ArDebugFraming.approach(at120, home, 1f / 120f) }
        assertEquals(at60.distance, at120.distance, 1e-3f)

        var pose = at60
        repeat(600) { pose = ArDebugFraming.approach(pose, home, 1f / 60f) }
        assertEquals(home, pose)
    }

    @Test
    fun `following eases to home, a drag takes over, a double-tap gives it back`() {
        val camera = ArDebugOrbitCamera(drift = false)
        camera.setViewport(1080, 2400)
        camera.frontAzimuth = 60f
        camera.recenter()
        camera.home = camera.home.copy(elevationDegrees = 30f, distance = 5f)
        repeat(240) { camera.update(1f / 60f) }
        assertEquals(5f, camera.pose.distance, 1e-3f)
        assertEquals(60f, camera.pose.azimuthDegrees, 1e-2f)
        assertTrue(camera.following)

        camera.grabBegin(500, 1000, strafe = false)
        camera.grabUpdate(600, 1000)
        camera.update(1f / 60f)
        camera.grabEnd()
        assertFalse(camera.following)
        assertTrue(abs(camera.pose.azimuthDegrees - 60f) > 10f)

        // A new home does not move a camera the user holds.
        camera.home = camera.home.copy(distance = 9f)
        repeat(240) { camera.update(1f / 60f) }
        assertTrue(abs(camera.pose.distance - 9f) > 1f)

        camera.doubleTapZoom(0, 0, zoomIn = true)
        assertTrue(camera.following)
        repeat(240) { camera.update(1f / 60f) }
        assertEquals(9f, camera.pose.distance, 1e-2f)
        assertEquals(60f, camera.pose.azimuthDegrees, 1e-2f)
    }

    @Test
    fun `a pinch zooms within range before any scene is known`() {
        val camera = ArDebugOrbitCamera(drift = false)
        camera.scrollBegin(0, 0, 100f)
        repeat(50) { camera.scrollUpdate(0, 0, 100f, 400f) }

        assertEquals(ArDebugFraming.MIN_DISTANCE, camera.pose.distance, 1e-3f)
        assertFalse(camera.following)

        // With nothing to keep whole, a pinch out stays within a room's size.
        repeat(80) { camera.scrollUpdate(0, 0, 400f, 100f) }
        assertEquals(ArDebugFraming.EMPTY_MAX_DISTANCE, camera.pose.distance, 1e-3f)
    }

    @Test
    fun `the viewport aspect follows the surface size, and the band the orientation`() {
        val camera = ArDebugOrbitCamera()
        camera.setViewport(300, 400)
        assertEquals(0.75f, camera.aspect, 1e-4f)

        camera.setViewport(0, 0) // a surface being torn down
        assertEquals(1f, camera.aspect, 1e-4f)

        assertEquals(OrbitBand.STAGE_PORTRAIT, OrbitBand.stage(0.46f))
        assertEquals(OrbitBand.STAGE_LANDSCAPE, OrbitBand.stage(2.1f))
        camera.band = OrbitBand.STAGE_LANDSCAPE
        assertEquals(OrbitBand.STAGE_LANDSCAPE.lift, camera.lift, 0f)
    }
}

package io.github.sceneview.demo.demos.internal

import io.github.sceneview.math.copyColumnsInto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * #4742: the save's orbit flew backwards through the wall behind the phone, and the points it
 * grazed flashed as large white squares on a Pixel 4a. Flown frame by frame through the real view
 * camera — the eye hand-off, the follow ease and the band's lift included — over a scanned 4 × 3 m
 * room with 2.4 m walls, from the middle of it, next to a wall and in a corner, the view now keeps
 * [ScanSavingPath.GUARD_M] from every point (or, the first instants, at least what the phone kept),
 * leaves the room through its open top and ends above the walls looking down at the floor; a room
 * scanned with its ceiling loses it first ([ScanOpenTop]).
 */
class ScanSavingPathTest {

    @Test
    fun `from the middle of the room, facing a wall`() = fly(Start(0f, 1.4f, 0f, yaw = 0.0, pitch = -20.0))

    @Test
    fun `60 cm from a wall, facing it`() = fly(Start(1.4f, 1.4f, 0f, yaw = -90.0, pitch = -10.0))

    @Test
    fun `30 cm from a wall, its back to it`() = fly(Start(1.7f, 1.3f, 0.2f, yaw = 90.0, pitch = -25.0))

    @Test
    fun `in a corner, facing it`() = fly(Start(1.6f, 1.2f, 1.1f, yaw = -135.0, pitch = -15.0))

    @Test
    fun `in a corner, facing the room`() = fly(Start(-1.6f, 1.5f, -1.1f, yaw = -135.0 + 180.0, pitch = -30.0))

    @Test
    fun `under a ceiling, from the middle of the room`() =
        fly(Start(0f, 1.4f, 0f, yaw = 0.0, pitch = -20.0), ROOFED)

    @Test
    fun `under a ceiling, 30 cm from a wall`() =
        fly(Start(1.7f, 1.3f, 0.2f, yaw = 90.0, pitch = -25.0), ROOFED)

    @Test
    fun `a room scanned whole loses its ceiling while it saves, as its dollhouse does`() {
        val open = ScanOpenTop.positions(ROOFED)
        assertEquals(0f, ScanOpenTop.floorOf(ROOFED)!!, 1e-4f)
        val top = (1 until open.size step 3).maxOf { open[it] }
        assertTrue("top at $top m", top <= RoomDollhouse.CUTAWAY_HEIGHT_M)
        // The floor and the walls up to the cut stay.
        assertTrue(open.size > ROOFED.size / 2)
        // Nothing to cut: the same points.
        val low = floatArrayOf(0f, 0f, 0f, 1f, 1.5f, 1f)
        assertTrue(ScanOpenTop.positions(low) === low)
    }

    private class Start(val x: Float, val y: Float, val z: Float, val yaw: Double, val pitch: Double)

    private fun fly(start: Start, room: FloatArray = ROOM) {
        // What is drawn while it saves, and what the eye must keep clear of.
        val drawn = ScanOpenTop.positions(room)
        val top = (1 until drawn.size step 3).maxOf { drawn[it] }
        val phone = pose(start)
        val matrix = FloatArray(16).also { ScanLens.poseMatrix(phone, it) }
        val lensMatrix = FloatArray(16).also {
            EyeHandoff.orbitProjection(verticalFovDegrees = 60.0, aspect = 9f / 20f, out = it)
        }
        val lens = ScanLens().apply {
            update { cameraToWorld, projection ->
                matrix.copyInto(cameraToWorld)
                lensMatrix.copyInto(projection)
            }
        }
        val follow = ScanFollow.poseAt(phone, ScanFollow.headingOf(phone)!!)
        val camera = ArDebugOrbitCamera(
            drift = false,
            band = ScanFollow.BAND,
            approachRate = ScanFollow.APPROACH_RATE,
            maxTurnDegreesPerSecond = ScanFollow.MAX_TURN_DEGREES_PER_SECOND,
        ).apply {
            firstPerson = lens
            setViewport(1080, 2400)
            snapTo(follow)
            assertTrue(inFirstPerson)
            getTransform()
        }
        var now = 0L
        val orbit = ScanSavingOrbit { now }
        lens.invalidate() // the stop tap: the live view no longer sees through the phone

        val startClear = clearance(drawn, start.x, start.y, start.z)
        val floor = min(ScanSavingPath.GUARD_M, startClear) - EASE_SLACK_M
        val m = FloatArray(16)
        var seconds = 0f
        var lowest = Float.MAX_VALUE
        while (seconds < FLIGHT_SECONDS) {
            orbit.poseFor(follow, room, camera)?.let(camera::followTo)
            camera.getTransform().copyColumnsInto(m)
            val x = m[12]
            val y = m[13]
            val z = m[14]
            val clear = clearance(drawn, x, y, z)
            lowest = minOf(lowest, clear)
            val where = "at %.2f s, eye (%.2f, %.2f, %.2f)".format(seconds, x, y, z)
            assertTrue("$where: ${"%.2f".format(clear)} m from the cloud, under ${"%.2f".format(floor)}", clear >= floor)
            if (seconds >= ScanSavingPath.RISE_SECONDS) {
                assertTrue("$where: ${"%.2f".format(clear)} m once risen", clear >= ScanSavingPath.GUARD_M - EASE_SLACK_M)
            }
            // Below the top of the walls drawn, the eye is inside them: it never leaves through one.
            if (y < top) {
                assertTrue("$where: through a wall", x > -HALF_X && x < HALF_X && z > -HALF_Z && z < HALF_Z)
            }
            camera.update(FRAME)
            now += (FRAME * 1e9f).toLong()
            seconds += FRAME
        }

        // On the orbit: above the walls, looking down at the room's floor.
        camera.getTransform().copyColumnsInto(m)
        val eyeY = m[13]
        assertTrue("eye at $eyeY m", eyeY > top + ScanSavingPath.GUARD_M)
        val fx = -m[8]
        val fy = -m[9]
        val fz = -m[10]
        assertTrue("looks down", fy < 0f)
        // The floor's middle is in the picture: the band draws it below the view's middle.
        val tx = -m[12]
        val ty = -eyeY
        val tz = -m[14]
        val cosine = (fx * tx + fy * ty + fz * tz) / sqrt(tx * tx + ty * ty + tz * tz)
        val offAxis = Math.toDegrees(acos(cosine.toDouble()))
        assertTrue("the floor's middle $offAxis° off the view's axis", offAxis < camera.verticalFovDegrees / 2)
        println("start ${"%.2f".format(startClear)} m from the cloud, flight ${"%.2f".format(lowest)} m at least")
    }

    private fun clearance(cloud: FloatArray, x: Float, y: Float, z: Float): Float {
        var least = Float.MAX_VALUE
        var i = 0
        while (i < cloud.size) {
            val dx = x - cloud[i]
            val dy = y - cloud[i + 1]
            val dz = z - cloud[i + 2]
            least = minOf(least, dx * dx + dy * dy + dz * dz)
            i += 3
        }
        return sqrt(least)
    }

    private companion object {
        const val FRAME = 1f / 60f
        const val FLIGHT_SECONDS = 9f

        /** What the follow ease and the band's lift may take off the planned path, metres. */
        const val EASE_SLACK_M = 0.03f

        const val HALF_X = 2f
        const val HALF_Z = 1.5f
        const val WALL_HEIGHT = 2.4f
        const val ROOFED_HEIGHT = 2.6f
        const val SPACING = 0.05f

        /** A scanned 4 × 3 m room: its floor and four 2.4 m walls, a point every 5 cm, no ceiling. */
        val ROOM: FloatArray = room(WALL_HEIGHT, roofed = false)

        /** The QA flat's room ([RerunSyntheticRoom]): 2.6 m walls under a ceiling, scanned whole. */
        val ROOFED: FloatArray = room(ROOFED_HEIGHT, roofed = true)

        fun room(height: Float, roofed: Boolean): FloatArray = buildList {
            val nx = (2 * HALF_X / SPACING).toInt()
            val nz = (2 * HALF_Z / SPACING).toInt()
            val ny = (height / SPACING).toInt()
            for (i in 0..nx) for (k in 0..nz) addAll(listOf(-HALF_X + i * SPACING, 0f, -HALF_Z + k * SPACING))
            if (roofed) {
                for (i in 0..nx) for (k in 0..nz) addAll(listOf(-HALF_X + i * SPACING, height, -HALF_Z + k * SPACING))
            }
            for (j in 1..ny) {
                val y = j * SPACING
                for (i in 0..nx) {
                    addAll(listOf(-HALF_X + i * SPACING, y, -HALF_Z))
                    addAll(listOf(-HALF_X + i * SPACING, y, HALF_Z))
                }
                for (k in 0..nz) {
                    addAll(listOf(-HALF_X, y, -HALF_Z + k * SPACING))
                    addAll(listOf(HALF_X, y, -HALF_Z + k * SPACING))
                }
            }
        }.toFloatArray()

        /** The phone at the start's spot, yawed about +Y then pitched about its own X. */
        fun pose(start: Start): DebugPose {
            val sy = sin(Math.toRadians(start.yaw / 2)).toFloat()
            val cy = cos(Math.toRadians(start.yaw / 2)).toFloat()
            val sx = sin(Math.toRadians(start.pitch / 2)).toFloat()
            val cx = cos(Math.toRadians(start.pitch / 2)).toFloat()
            return DebugPose(start.x, start.y, start.z, cy * sx, cx * sy, -sy * sx, cy * cx)
        }
    }
}

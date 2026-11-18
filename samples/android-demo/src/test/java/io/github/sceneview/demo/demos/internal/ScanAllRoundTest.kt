package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * The circle round the red button: the share of a full turn the phone has faced. Turning is the
 * only thing that fills it, a full turn always does, and a whip round fills nothing.
 */
class ScanAllRoundTest {

    /** The phone at the origin, turned [yawDegrees] about the vertical and tilted [pitchDegrees] down. */
    private fun facing(yawDegrees: Float, pitchDegrees: Float = 0f): DebugPose {
        val yaw = Math.toRadians(yawDegrees.toDouble()) / 2.0
        val pitch = Math.toRadians(-pitchDegrees.toDouble()) / 2.0
        // q = yaw about Y, then pitch about the phone's own X.
        val (ys, yc) = sin(yaw) to cos(yaw)
        val (ps, pc) = sin(pitch) to cos(pitch)
        return DebugPose(0f, 1.4f, 0f, qx = (yc * ps).toFloat(), qy = (ys * pc).toFloat(), qz = (-ys * ps).toFloat(), qw = (yc * pc).toFloat())
    }

    /** Turns [degrees] in [seconds] from [fromDegrees], at 30 frames a second. */
    private fun ScanAllRound.turn(fromDegrees: Float, degrees: Float, seconds: Float, startAt: Float = 0f, pitch: Float = 0f): Float {
        val frames = (seconds * FPS).toInt()
        for (i in 0..frames) update(startAt + i / FPS, facing(fromDegrees + degrees * i / frames, pitch))
        return startAt + seconds
    }

    @Test
    fun `nothing is faced before the phone turns`() {
        val round = ScanAllRound()
        assertEquals(0f, round.share, 0f)
        round.update(0f, facing(0f))
        assertEquals(0, round.count)
    }

    @Test
    fun `a slow full turn fills the circle, and half a turn half of it`() {
        val half = ScanAllRound()
        half.turn(fromDegrees = 0f, degrees = 179f, seconds = 10f)
        assertEquals(0.5f, half.share, 0.09f)
        val full = ScanAllRound()
        full.turn(fromDegrees = 0f, degrees = 360f, seconds = 20f)
        assertEquals(1f, full.share, 0f)
    }

    @Test
    fun `turning back over what was faced adds nothing`() {
        val round = ScanAllRound()
        var t = round.turn(fromDegrees = 0f, degrees = 90f, seconds = 5f)
        val after = round.count
        t = round.turn(fromDegrees = 90f, degrees = -90f, seconds = 5f, startAt = t)
        round.turn(fromDegrees = 0f, degrees = 90f, seconds = 5f, startAt = t)
        assertEquals(after, round.count)
    }

    @Test
    fun `a whip round fills nothing`() {
        // A full turn in one second: 30° every 83 ms, under the time a sector takes to count.
        val round = ScanAllRound()
        round.turn(fromDegrees = 15f, degrees = 360f, seconds = 1f)
        assertTrue("faced ${round.count}", round.count <= 1)
    }

    @Test
    fun `a phone held looking down still turns`() {
        // A real room scan held the phone some 30° down, and often 50°: the turn counts all the same.
        val round = ScanAllRound()
        round.turn(fromDegrees = 0f, degrees = 360f, seconds = 20f, pitch = 50f)
        assertEquals(1f, round.share, 0f)
    }

    @Test
    fun `a phone pointed straight down faces nothing`() {
        val round = ScanAllRound()
        round.turn(fromDegrees = 0f, degrees = 360f, seconds = 20f, pitch = 88f)
        assertEquals(0, round.count)
    }

    @Test
    fun `a gap in the frames is not time spent facing`() {
        val round = ScanAllRound()
        round.update(0f, facing(0f))
        // Ten seconds without a frame (tracking lost), then one frame: 0.1 s at most counts.
        round.update(10f, facing(0f))
        assertEquals(0, round.count)
        round.update(10.1f, facing(0f))
        assertEquals(1, round.count)
    }

    @Test
    fun `a clock that goes back starts another turn`() {
        val round = ScanAllRound()
        round.turn(fromDegrees = 0f, degrees = 360f, seconds = 20f)
        assertEquals(ScanAllRound.SECTORS, round.count)
        round.update(1f, facing(0f))
        assertEquals(0, round.count)
    }

    private companion object {
        const val FPS = 30f
    }
}

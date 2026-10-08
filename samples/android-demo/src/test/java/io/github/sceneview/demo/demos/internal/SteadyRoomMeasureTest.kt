package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Pins the room figure a live scan shows: what ARCore finds for a frame or two and takes back
 * never reaches the screen, and what it keeps finding does within [SteadyRoomMeasure.CHANGE_HOLD_SECONDS].
 */
class SteadyRoomMeasureTest {

    private val hold = SteadyRoomMeasure.CHANGE_HOLD_SECONDS

    /** One camera frame at 30 fps. */
    private val frame = 1f / 30f

    private fun room(width: Float, depth: Float) = RoomMeasure(FloatArray(8), width, depth, yaw = 0f)

    @Test
    fun `no room found shows none, and the first one shows at once`() {
        val steady = SteadyRoomMeasure()
        assertNull(steady.update(null, 0f))
        val first = room(3.4f, 4.1f)
        assertSame(first, steady.update(first, frame))
    }

    @Test
    fun `a figure that jumps for a few frames and comes back never shows`() {
        val steady = SteadyRoomMeasure()
        val found = room(3.4f, 4.1f)
        steady.update(found, 0f)
        // A plane merged across the doorway, then split again.
        val spike = room(6.9f, 4.6f)
        for (i in 1..5) assertSame(found, steady.update(spike, i * frame))
        assertSame(found, steady.update(found, 6 * frame))
        // The spike left no clock running: the same jump later starts its wait over.
        assertSame(found, steady.update(spike, 6 * frame + hold))
    }

    @Test
    fun `a room lost for a few frames stays shown`() {
        val steady = SteadyRoomMeasure()
        val found = room(3.4f, 4.1f)
        steady.update(found, 0f)
        for (i in 1..5) assertSame(found, steady.update(null, i * frame))
        assertSame(found, steady.update(found, 6 * frame))
    }

    @Test
    fun `a room lost for good is shown as lost`() {
        val steady = SteadyRoomMeasure()
        steady.update(room(3.4f, 4.1f), 0f)
        steady.update(null, 1f)
        assertNull(steady.update(null, 1f + hold))
        // And the next room found shows at once, as the first did.
        val next = room(2f, 2f)
        assertSame(next, steady.update(next, 2f + hold))
    }

    @Test
    fun `a new wall shows once it has held`() {
        val steady = SteadyRoomMeasure()
        val before = room(3.4f, 2f)
        val after = room(3.4f, 4.1f)
        steady.update(before, 0f)
        assertSame(before, steady.update(after, 1f))
        assertSame(before, steady.update(after, 1f + hold - frame))
        assertSame(after, steady.update(after, 1f + hold))
    }

    @Test
    fun `a room being refined is followed without delay`() {
        val steady = SteadyRoomMeasure()
        steady.update(room(3.4f, 4.1f), 0f)
        val refined = room(3.5f, 4.2f)
        assertSame(refined, steady.update(refined, frame))
    }

    @Test
    fun `a room that keeps growing never trails by more than the hold`() {
        val steady = SteadyRoomMeasure()
        steady.update(room(2f, 2f), 0f)
        // Walking along a wall: its plane regrows by 30 cm five times a second, so no figure
        // ever holds still for the hold.
        var shown: RoomMeasure? = null
        var time = 0f
        var depth = 2f
        var trailedSince = Float.NaN
        while (time < 4f) {
            time += 0.2f
            depth += 0.3f
            shown = steady.update(room(2f, depth), time)
            val trails = depth - shown!!.depth > SteadyRoomMeasure.CHANGE_TOLERANCE_M
            if (!trails) trailedSince = Float.NaN else if (trailedSince.isNaN()) trailedSince = time
            if (trails) assertEquals(0f, time - trailedSince, hold)
        }
        // Four regrowths fit in one hold; the fifth is the margin for float sums.
        assertEquals(depth, shown!!.depth, 0.3f * 5)
    }

    @Test
    fun `a clock that runs back starts over`() {
        val steady = SteadyRoomMeasure()
        steady.update(room(3.4f, 4.1f), 10f)
        // Another scan: its first room shows at once, whatever the last one was.
        val other = room(6f, 5f)
        assertSame(other, steady.update(other, 0.5f))
    }
}

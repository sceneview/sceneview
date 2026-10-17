package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Pins the room figure a live scan shows: what ARCore finds for a frame or two and takes back
 * never reaches the screen, what it keeps finding does within [SteadyRoomMeasure.CHANGE_HOLD_SECONDS],
 * and a room it shrinks for a few seconds keeps its size.
 */
class SteadyRoomMeasureTest {

    private val hold = SteadyRoomMeasure.CHANGE_HOLD_SECONDS
    private val shrinkHold = SteadyRoomMeasure.SHRINK_HOLD_SECONDS

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
        val found = room(3.4f, 4.1f)
        steady.update(found, 0f)
        steady.update(null, 1f)
        // Losing a room is the largest shrink there is: it waits as one.
        assertSame(found, steady.update(null, 1f + hold))
        assertNull(steady.update(null, 1f + shrinkHold))
        // And the next room found shows at once, as the first did.
        val next = room(2f, 2f)
        assertSame(next, steady.update(next, 2f + shrinkHold))
    }

    @Test
    fun `a room that shrinks for a few seconds and comes back keeps its size`() {
        // The Pixel 4a's walk: 3.7 x 3.9 m, then 2.4 x 2.3 m for two and a half seconds while
        // ARCore merged its planes, then 3.7 x 3.9 m again. The readout showed all three.
        val steady = SteadyRoomMeasure()
        val found = room(3.7f, 3.9f)
        val shrunk = room(2.4f, 2.3f)
        steady.update(found, 0f)
        var time = 10f
        while (time < 12.5f) {
            assertSame(found, steady.update(shrunk, time))
            time += frame
        }
        assertSame(found, steady.update(found, time))
        // The shrink left no clock running: the next one starts its wait over.
        assertSame(found, steady.update(shrunk, time + shrinkHold - frame))
    }

    @Test
    fun `a room that stays smaller is believed, later than a room that grew`() {
        val steady = SteadyRoomMeasure()
        val found = room(3.7f, 3.9f)
        val smaller = room(3.7f, 3.1f)
        steady.update(found, 0f)
        assertSame(found, steady.update(smaller, 1f))
        assertSame(found, steady.update(smaller, 1f + hold))
        assertSame(found, steady.update(smaller, 1f + shrinkHold - frame))
        assertSame(smaller, steady.update(smaller, 1f + shrinkHold))
    }

    @Test
    fun `a room named the other way round for a moment does not flip the readout`() {
        // Near a diagonal the walls' direction flips a quarter turn: width and depth trade
        // places, and the room is the same. Nothing grew, so it waits as a shrink does.
        val steady = SteadyRoomMeasure()
        val found = room(3.2f, 4.4f)
        val turned = room(4.4f, 3.2f)
        steady.update(found, 0f)
        for (i in 1..30) assertSame(found, steady.update(turned, i * 0.1f))
        assertSame(found, steady.update(found, 3.1f))
        // Named that way for good, it is.
        assertSame(found, steady.update(turned, 4f))
        assertSame(turned, steady.update(turned, 4f + shrinkHold))
    }

    @Test
    fun `a shrink that turns into a growth waits for the growth alone`() {
        val steady = SteadyRoomMeasure()
        val found = room(3.7f, 3.9f)
        val grown = room(3.7f, 5.2f)
        steady.update(found, 0f)
        steady.update(room(2.4f, 2.3f), 1f)
        // Two seconds into the shrink the far wall is found: its own hold starts there.
        assertSame(found, steady.update(grown, 3f))
        assertSame(found, steady.update(grown, 3f + hold - frame))
        assertSame(grown, steady.update(grown, 3f + hold))
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

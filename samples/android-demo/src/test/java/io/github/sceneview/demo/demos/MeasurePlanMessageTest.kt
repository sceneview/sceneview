package io.github.sceneview.demo.demos

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.sceneview.demo.demos.internal.MeasureFigure
import io.github.sceneview.demo.demos.internal.MeasureMode
import io.github.sceneview.demo.demos.internal.MeasurePlanTap
import io.github.sceneview.demo.demos.internal.MeasureRefusal
import io.github.sceneview.demo.demos.internal.measureFigureOutline
import io.github.sceneview.demo.demos.internal.measurePlanTap
import io.github.sceneview.math.Position
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * #4765: Plan stays greyed until a room is traced, closed and raised, and a tap on it says, in the
 * chip under the reticle, the step still missing — from the board as it stands to the words shown.
 */
@RunWith(RobolectricTestRunner::class)
class MeasurePlanMessageTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private val corners = listOf(0f to 0f, 4f to 0f, 4f to 3f, 0f to 3f).map { (x, z) -> Position(x, -1.2f, z) }

    /** What the chip says on a tap on Plan with [figures] on the board; `null` when Plan opens. */
    private fun said(vararg figures: MeasureFigure<Position>): String? {
        val tap = measurePlanTap(figures.toList(), figures.map { measureFigureOutline(it.mode, it.points) })
        return (tap as? MeasurePlanTap.Refused)?.let { context.getString(measureRefusalMessage(it.reason)) }
    }

    @Test
    fun `a greyed Plan says what the room still needs`() {
        assertEquals("Trace the room, close it and set its height first", said())
        assertEquals("Close the room and set its height first", said(MeasureFigure(MeasureMode.Room, corners)))
        assertEquals(
            "Set the room height first",
            said(MeasureFigure(MeasureMode.Room, corners, closed = true, raising = true)),
        )
        assertEquals(null, said(MeasureFigure(MeasureMode.Room, corners, closed = true, height = 2.5f)))
    }

    @Test
    fun `every refusal says something of its own`() {
        val messages = MeasureRefusal.entries.map { context.getString(measureRefusalMessage(it)) }
        assertEquals(messages.toString(), messages.size, messages.toSet().size)
        assertEquals("Move to the next corner first", context.getString(measureRefusalMessage(MeasureRefusal.SameCorner)))
    }
}

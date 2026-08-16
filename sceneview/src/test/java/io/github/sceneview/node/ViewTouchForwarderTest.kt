package io.github.sceneview.node

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import io.github.sceneview.math.Position
import io.github.sceneview.math.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Pins for `ViewNode`'s touch forwarding (#2845) — the half that can be tested without a Filament
 * engine: the local-hit → view-pixel mapping, and the touch-target state machine.
 *
 * `ViewNode` itself needs a live `Engine`, so it is exercised on device by the demo QA pass; what
 * regresses silently is the math (a Y flip lost, a `pxPerUnits` forgotten) and the stream
 * bookkeeping (a press never cancelled). Both live here.
 */
@RunWith(RobolectricTestRunner::class)
class ViewTouchForwarderTest {

    // ── Mapping ──────────────────────────────────────────────────────────────────────────────────

    /** A 400x200 px view rendered on a 2x1 unit quad (200 px per unit), centred on the node. */
    private val size = Size(x = 2.0f, y = 1.0f, z = 0.0f)
    private val center = Position(0.0f)

    private fun pixelsAt(x: Float, y: Float) = viewTouchPixels(
        localPosition = Position(x = x, y = y, z = 0.0f),
        center = center,
        size = size,
        widthPx = 400,
        heightPx = 200
    )

    @Test
    fun `hit at the node origin maps to the view center`() {
        val point = pixelsAt(0.0f, 0.0f)!!
        assertEquals(200.0f, point.x, 1e-4f)
        assertEquals(100.0f, point.y, 1e-4f)
    }

    @Test
    fun `local Y is flipped because view pixels grow downwards`() {
        // Top-left corner of the quad (-1, +0.5) is the view's (0, 0) pixel.
        val topLeft = pixelsAt(-1.0f, 0.5f)!!
        assertEquals(0.0f, topLeft.x, 1e-4f)
        assertEquals(0.0f, topLeft.y, 1e-4f)

        // Bottom-right corner (+1, -0.5) is the opposite pixel.
        val bottomRight = pixelsAt(1.0f, -0.5f)!!
        assertEquals(400.0f, bottomRight.x, 1e-4f)
        assertEquals(200.0f, bottomRight.y, 1e-4f)
    }

    @Test
    fun `an off-quad hit is not clamped, so the view can un-press itself`() {
        val point = pixelsAt(-1.5f, 0.0f)!!
        assertEquals(-100.0f, point.x, 1e-4f)
    }

    @Test
    fun `a non-centred quad shifts the mapping by its center`() {
        val point = viewTouchPixels(
            localPosition = Position(x = 3.0f, y = 2.0f, z = 0.0f),
            center = Position(x = 3.0f, y = 2.0f, z = 0.0f),
            size = size,
            widthPx = 400,
            heightPx = 200
        )!!
        assertEquals(200.0f, point.x, 1e-4f)
        assertEquals(100.0f, point.y, 1e-4f)
    }

    @Test
    fun `a mirrored quad maps X to the mirrored pixel, because that is where it is drawn`() {
        // invertFrontFaceWinding sets uvOffset.x = 1, and the material shades 1 - uv.x. The pixel
        // column drawn at the quad's left edge is the view's LAST one, so the touch must follow.
        val left = viewTouchPixels(
            localPosition = Position(x = -1.0f, y = 0.5f, z = 0.0f),
            center = center, size = size, widthPx = 400, heightPx = 200, mirrorX = true
        )!!
        assertEquals(400.0f, left.x, 1e-4f)
        assertEquals(0.0f, left.y, 1e-4f)

        // Y is untouched: uvOffset.y stays 0, so the vertical mapping is the unmirrored one.
        val quarter = viewTouchPixels(
            localPosition = Position(x = -0.5f, y = 0.0f, z = 0.0f),
            center = center, size = size, widthPx = 400, heightPx = 200, mirrorX = true
        )!!
        assertEquals(300.0f, quarter.x, 1e-4f)
        assertEquals(100.0f, quarter.y, 1e-4f)
    }

    @Test
    fun `an unmeasured view or quad maps to nothing rather than to NaN`() {
        assertNull(viewTouchPixels(Position(0.0f), center, size, 0, 200))
        assertNull(viewTouchPixels(Position(0.0f), center, size, 400, 0))
        assertNull(viewTouchPixels(Position(0.0f), center, Size(0.0f), 400, 200))
    }

    // ── Stream state machine ─────────────────────────────────────────────────────────────────────

    /** Records what actually reached the embedded view. */
    private class RecordingView(consume: Boolean) : View(RuntimeEnvironment.getApplication()) {
        var consume: Boolean = consume
        val actions = mutableListOf<Int>()
        val points = mutableListOf<Pair<Float, Float>>()

        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            actions += event.actionMasked
            points += event.x to event.y
            return consume
        }
    }

    private fun event(action: Int, x: Float = 0.0f, y: Float = 0.0f): MotionEvent {
        val now = SystemClock.uptimeMillis()
        return MotionEvent.obtain(now, now, action, x, y, 0)
    }

    private fun forwarderOn(view: RecordingView) = ViewTouchForwarder(view)

    @Test
    fun `a consuming view owns the whole stream and gets the picked pixels`() {
        val view = RecordingView(consume = true)
        val forwarder = forwarderOn(view)

        assertTrue(forwarder.onHit(event(MotionEvent.ACTION_DOWN), 10.0f, 20.0f))
        assertTrue(forwarder.onHit(event(MotionEvent.ACTION_MOVE), 11.0f, 21.0f))
        assertTrue(forwarder.onHit(event(MotionEvent.ACTION_UP), 12.0f, 22.0f))

        assertEquals(
            listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP),
            view.actions
        )
        assertEquals(listOf(10.0f to 20.0f, 11.0f to 21.0f, 12.0f to 22.0f), view.points)
        assertFalse("the stream must be released on UP", forwarder.ownsStream)
    }

    @Test
    fun `a view that refuses the DOWN never sees the rest of the gesture`() {
        val view = RecordingView(consume = false)
        val forwarder = forwarderOn(view)

        assertFalse(forwarder.onHit(event(MotionEvent.ACTION_DOWN), 10.0f, 20.0f))
        assertFalse(forwarder.onHit(event(MotionEvent.ACTION_MOVE), 11.0f, 21.0f))

        assertEquals(listOf(MotionEvent.ACTION_DOWN), view.actions)
        assertFalse(forwarder.ownsStream)
    }

    @Test
    fun `a pointer dragged off the quad cancels the press instead of leaving it stuck`() {
        val view = RecordingView(consume = true)
        val forwarder = forwarderOn(view)

        forwarder.onHit(event(MotionEvent.ACTION_DOWN), 10.0f, 20.0f)
        // Ray no longer hits: one CANCEL, then silence — no phantom click on the UP.
        assertTrue(forwarder.onExit(event(MotionEvent.ACTION_MOVE)))
        assertTrue(forwarder.onExit(event(MotionEvent.ACTION_MOVE)))
        assertTrue(forwarder.onExit(event(MotionEvent.ACTION_UP)))

        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL), view.actions)
        assertFalse(forwarder.ownsStream)
    }

    @Test
    fun `a cancelled stream stays swallowed even if the ray comes back on the quad`() {
        val view = RecordingView(consume = true)
        val forwarder = forwarderOn(view)

        forwarder.onHit(event(MotionEvent.ACTION_DOWN), 10.0f, 20.0f)
        forwarder.onExit(event(MotionEvent.ACTION_MOVE))
        assertTrue(
            "the gesture still belongs to the view",
            forwarder.onHit(event(MotionEvent.ACTION_MOVE), 10.0f, 20.0f)
        )
        forwarder.onHit(event(MotionEvent.ACTION_UP), 10.0f, 20.0f)

        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL), view.actions)
        assertFalse(forwarder.ownsStream)
    }

    @Test
    fun `a new DOWN closes a stream left open and starts a clean one`() {
        val view = RecordingView(consume = true)
        val forwarder = forwarderOn(view)

        forwarder.onHit(event(MotionEvent.ACTION_DOWN), 10.0f, 20.0f)
        // The gesture ends off-node without ever coming back: only a new DOWN closes it.
        forwarder.onHit(event(MotionEvent.ACTION_DOWN), 30.0f, 40.0f)

        assertEquals(
            listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_DOWN),
            view.actions
        )
        assertTrue(forwarder.ownsStream)
    }

    @Test
    fun `a DOWN elsewhere releases the captured stream without consuming it`() {
        val view = RecordingView(consume = true)
        val forwarder = forwarderOn(view)

        forwarder.onHit(event(MotionEvent.ACTION_DOWN), 10.0f, 20.0f)
        forwarder.onExit(event(MotionEvent.ACTION_DOWN))

        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL), view.actions)
        assertFalse("a new gesture must not stay captured by the old owner", forwarder.ownsStream)
    }
}

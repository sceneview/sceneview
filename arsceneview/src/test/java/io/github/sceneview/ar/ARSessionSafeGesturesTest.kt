package io.github.sceneview.ar

import android.content.Context
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewConfiguration
import com.google.ar.core.Anchor
import com.google.ar.core.Frame
import com.google.ar.core.HitResult
import com.google.ar.core.exceptions.FatalException
import com.google.ar.core.exceptions.NotTrackingException
import com.google.ar.core.exceptions.SessionPausedException
import io.github.sceneview.gesture.GestureDetector
import io.github.sceneview.node.Node
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.Duration

/**
 * A tap is confirmed by a `Handler` message, `ViewConfiguration.getDoubleTapTimeout()` after the
 * finger lifts. If the ARCore session pauses in that window (the app goes to the background, the
 * screen is left), the app's `onSingleTapConfirmed` runs `HitResult.createAnchor()` on a paused
 * session and ARCore throws `SessionPausedException` (or `FatalException`) straight out of the
 * main-thread `Handler`: an app crash with no SceneView frame the app could have wrapped.
 *
 * The fake [Frame] / [HitResult] below throw exactly what ARCore throws there; the tap goes through
 * the real `GestureDetector` and its `Handler`, as on a device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ARSessionSafeGesturesTest {

    private val context: Context = RuntimeEnvironment.getApplication()

    /** A hit whose `createAnchor()` throws what ARCore throws on a paused or failed session. */
    private class ThrowingHit(private val error: () -> RuntimeException) : HitResult() {
        var attempts = 0
        override fun createAnchor(): Anchor {
            attempts++
            throw error()
        }
    }

    private class FakeFrame(private val hit: HitResult) : Frame() {
        override fun hitTest(xPx: Float, yPx: Float): List<HitResult> = listOf(hit)
    }

    /** What the field apps do: hit-test the tap and anchor on the first hit. */
    private fun tapToAnchor(frame: Frame) = object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent, node: Node?) {
            frame.hitTest(e.x, e.y).firstOrNull()?.createAnchor()
        }
    }

    private fun tap(detector: GestureDetector) {
        val downTime = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, 100f, 200f, 0)
        val up = MotionEvent.obtain(downTime, downTime + 50, MotionEvent.ACTION_UP, 100f, 200f, 0)
        detector.onTouchEvent(down, null)
        detector.onTouchEvent(up, null)
        down.recycle()
        up.recycle()
        // The single tap is confirmed by the detector's Handler once the double-tap window closes.
        shadowOf(Looper.getMainLooper())
            .idleFor(Duration.ofMillis(ViewConfiguration.getDoubleTapTimeout() + 100L))
    }

    @Test
    fun `without the guard a tap on a paused session crashes from the Handler`() {
        val hit = ThrowingHit { SessionPausedException() }
        val detector = GestureDetector(context, tapToAnchor(FakeFrame(hit)))

        assertThrows(SessionPausedException::class.java) { tap(detector) }
        assertEquals(1, hit.attempts)
    }

    @Test
    fun `a tap on a paused session is caught and the app keeps running`() {
        val hit = ThrowingHit { SessionPausedException() }
        val detector = GestureDetector(context, tapToAnchor(FakeFrame(hit)).arSessionSafe())

        tap(detector)

        assertEquals("the tap still reaches the app", 1, hit.attempts)
    }

    @Test
    fun `a FatalException from createAnchor is caught too`() {
        val hit = ThrowingHit { FatalException("session failed") }
        val detector = GestureDetector(context, tapToAnchor(FakeFrame(hit)).arSessionSafe())

        tap(detector)

        assertEquals(1, hit.attempts)
    }

    @Test
    fun `a NotTrackingException from createAnchor is caught too`() {
        val hit = ThrowingHit { NotTrackingException() }
        val detector = GestureDetector(context, tapToAnchor(FakeFrame(hit)).arSessionSafe())

        tap(detector)

        assertEquals(1, hit.attempts)
    }

    @Test
    fun `an app bug that is not an ARCore session error still surfaces`() {
        val hit = ThrowingHit { IllegalArgumentException("app bug") }
        val detector = GestureDetector(context, tapToAnchor(FakeFrame(hit)).arSessionSafe())

        assertThrows(IllegalArgumentException::class.java) { tap(detector) }
    }

    @Test
    fun `wrapping twice keeps a single guard`() {
        val guarded = GestureDetector.SimpleOnGestureListener().arSessionSafe()

        assertSame(guarded, guarded.arSessionSafe())
    }

    @Test
    fun `ARSceneView hands the guarded listener to its gesture detector`() {
        val source = File("src/main/java/io/github/sceneview/ar/ARSceneView.kt")
            .readText()
            .replace(Regex("""\s+"""), "")

        assertTrue(
            "ARSceneView must route onGestureListener through arSessionSafe()",
            source.contains("remember(onGestureListener){onGestureListener?.arSessionSafe()}")
        )
        assertTrue(
            "the raw listener must not reach the gesture detector",
            !source.contains("gestureDetector.listener=onGestureListener}")
        )
    }
}

package io.github.sceneview.ar

import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * One camera, several AR sessions resumed by the same activity: the order in which they run.
 *
 * `ARSceneView` follows the host activity by default, so an outgoing and an incoming AR screen
 * are both resumed for the length of a navigation transition.
 */
class CameraHandoverTest {

    private val handover = CameraHandover<Any>()
    private val outgoing = Any()
    private val incoming = Any()

    @Test
    fun `the first session takes a free camera`() {
        assertNull(handover.claim(outgoing))
        assertSame(outgoing, handover.holder)
    }

    @Test
    fun `a session resuming over a running one makes it pause first`() {
        handover.claim(outgoing)

        assertSame(outgoing, handover.claim(incoming))
        assertSame(incoming, handover.holder)
    }

    @Test
    fun `resuming the running session again pauses nobody`() {
        handover.claim(outgoing)

        assertNull(handover.claim(outgoing))
        assertSame(outgoing, handover.holder)
    }

    @Test
    fun `a cancelled preview hands the camera back to the screen that stays`() {
        // Predictive back from AR screen B previews AR screen A, then the gesture is cancelled:
        // A is disposed and B, still resumed, must run again.
        handover.claim(incoming)
        handover.claim(outgoing)

        handover.release(outgoing)

        assertSame(incoming, handover.holder)
    }

    @Test
    fun `the outgoing screen leaving does not disturb the incoming one`() {
        handover.claim(outgoing)
        handover.claim(incoming)

        handover.release(outgoing)

        assertSame(incoming, handover.holder)
    }

    @Test
    fun `an activity pause empties the queue without a hand back in between`() {
        handover.claim(outgoing)
        handover.claim(incoming)

        // Lifecycle observers are paused in reverse order of registration.
        handover.release(incoming)
        handover.release(outgoing)

        assertNull(handover.holder)
    }

    @Test
    fun `a paused session that resumes again takes the camera back`() {
        handover.claim(outgoing)
        handover.claim(incoming)
        handover.release(outgoing)

        assertSame(incoming, handover.claim(outgoing))
        assertSame(outgoing, handover.holder)
    }

    @Test
    fun `releasing a session that never claimed is a no-op`() {
        handover.claim(outgoing)

        handover.release(incoming)

        assertSame(outgoing, handover.holder)
    }

    @Test
    fun `sessions that are equal are still distinct claims`() {
        val first = Claim("screen")
        val second = Claim("screen")
        handover.claim(first)

        assertSame(first, handover.claim(second))
        handover.release(second)

        assertSame(first, handover.holder)
    }

    private data class Claim(val name: String)
}

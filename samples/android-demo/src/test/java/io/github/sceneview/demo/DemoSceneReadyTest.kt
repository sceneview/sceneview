package io.github.sceneview.demo

import androidx.compose.runtime.mutableStateOf
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the "is there anything on screen?" signal behind the viewport's
 * "Scene ready" accessibility name — [demoSceneReady] and the readiness rule in
 * [FirstFrameState] that feeds it (#3444, #3108).
 *
 * Two bugs, opposite in direction, and the tests below hold both ends open.
 *
 * #3444 shipped a cover that lifted too early: `materials` presented a black viewport
 * for ~10 s while the app said the scene was up, and device QA captured that black
 * frame as a passing screenshot. #3108 then shipped the overcorrection — the fix had
 * been a **cadence streak**, eight presented frames within 250 ms of each other, which
 * quietly assumed a loop that ticks forever. Under render-on-demand a finished scene
 * presents a handful of frames and parks, so the streak could never be paid and the
 * "Still loading…" card sat over a fully drawn model for good.
 *
 * What survives both: readiness is a question about the **backend**, never about the
 * frame rate. Frames are counted, intervals are not, and the `flushAndWait` that turns
 * the count into driver truth is the part only a device can prove.
 */
class DemoSceneReadyTest {

    private val millis = 1_000_000L

    /**
     * A plausible `frameTimeNanos` base. Real timestamps come from the choreographer and are
     * never 0 — starting a test at 0 would only exercise the "there is no previous frame yet"
     * seed twice over.
     */
    private val base = 5_000L * 1_000_000L

    @Test
    fun `a demo with no first-frame state is ready as soon as it is composed`() {
        // AR: the viewport is the camera feed, never the loading cover. A flow that
        // waited on a signal these demos never publish would hang on every one.
        assertTrue(demoSceneReady(null))
    }

    @Test
    fun `a demo that has not presented a frame is not ready`() {
        assertFalse(demoSceneReady(false))
    }

    @Test
    fun `a demo that has presented a frame is ready`() {
        assertTrue(demoSceneReady(true))
    }

    @Test
    fun `a short settle tail followed by a parked loop is a ready scene`() {
        // The #3108 regression, and the reason the cadence streak had to go. Measured on
        // emulator-5554: `model-viewer` and `splat-preview` present 3 frames in 10 s with the
        // model fully drawn, then park because there is nothing left to draw. The old rule
        // wanted 8 frames within 250 ms of each other and could never be paid, so the scaffold
        // put its "Still loading…" card over a finished scene — permanently.
        val rendered = mutableStateOf(false)
        val state = FirstFrameState(rendered)

        state.onFrame(base)
        state.onFrame(base + 3_200 * millis)
        state.onFrame(base + 6_100 * millis)
        // …and then nothing at all, for as long as the user looks at it.

        assertTrue(
            "a parked scene is a ready scene: parking is what the renderer does when there is " +
                "nothing left to draw, and it must never read as progress",
            rendered.value
        )
    }

    @Test
    fun `two presented frames mark the scene rendered whatever the interval`() {
        // The same two frames, at 60 fps and at the 1.5 s warm-up cadence: the rule must not be
        // able to tell them apart, because the frame rate is not what the question is about.
        val fast = mutableStateOf(false)
        FirstFrameState(fast).let { state ->
            state.onFrame(base)
            state.onFrame(base + 16 * millis)
        }
        val slow = mutableStateOf(false)
        FirstFrameState(slow).let { state ->
            state.onFrame(base)
            state.onFrame(base + 1_500 * millis)
        }

        assertTrue(fast.value)
        assertTrue(slow.value)
    }

    @Test
    fun `one presented frame is not enough`() {
        // Filament refuses a new frame while the driver is behind, so the *second* accepted
        // submission is the evidence that the first was drained. One frame proves only that the
        // loop asked — which is exactly what lifted the cover on a black viewport in #3444.
        val rendered = mutableStateOf(false)
        val state = FirstFrameState(rendered)

        state.onFrame(base)

        assertFalse(
            "a single accepted submission says nothing about what the backend has executed",
            rendered.value
        )
    }

    @Test
    fun `rendered never goes back to false once the scene is up`() {
        val rendered = mutableStateOf(false)
        val state = FirstFrameState(rendered)
        state.onFrame(base)
        state.onFrame(base + 16 * millis)
        assertTrue(rendered.value)
        state.onFrame(base + 60_000 * millis) // a long stall afterwards: a pause, not a regression
        assertTrue("the cover must not come back over a scene the user has seen", rendered.value)
    }
}

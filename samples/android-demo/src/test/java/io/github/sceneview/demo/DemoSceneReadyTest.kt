package io.github.sceneview.demo

import androidx.compose.runtime.mutableStateOf
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the "is there anything on screen?" signal behind the viewport's
 * "Scene ready" accessibility name — [demoSceneReady] and the cadence rule in
 * [FirstFrameState] that feeds it (#3444).
 *
 * The bug these pin down is the one that shipped: `materials` presented a black
 * viewport for ~10 s while the app said the scene was up, and device QA captured
 * that black frame as a passing screenshot. Both halves are load-bearing — the
 * cadence rule decides *when* the app stops lying, and the AR pass-through decides
 * whether a QA flow waiting on "Scene ready" hangs on every AR demo.
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
    fun `a sustained 60 fps cadence marks the scene rendered`() {
        val rendered = mutableStateOf(false)
        val state = FirstFrameState(rendered)
        // Seed + 8 on-cadence frames: ~133 ms once the loop really runs.
        repeat(9) { frame -> state.onFrame(base + frame * 16 * millis) }
        assertTrue("8 frames in a row at 60 fps means the driver caught up", rendered.value)
    }

    @Test
    fun `a warming driver presenting a frame per second is not rendered`() {
        // Measured on emulator-5554: 4 frames in the first 6.3 s, ~1.5 s of GPU each
        // while the ToyCar's clearcoat / sheen / transmission variants compile. Every
        // one of those frames was submitted and accepted — none of them was on screen.
        val rendered = mutableStateOf(false)
        val state = FirstFrameState(rendered)
        repeat(20) { frame -> state.onFrame(base + frame * 1_500 * millis) }
        assertFalse("a 1.5 s cadence is the warm-up regime, not a live scene", rendered.value)
    }

    @Test
    fun `one lucky close pair mid warm-up does not lift the cover`() {
        // Why the signal is a streak and not a single interval: a one-interval test
        // lifted the cover at ~3 s with the viewport black until ~10 s.
        val rendered = mutableStateOf(false)
        val state = FirstFrameState(rendered)
        var now = base
        repeat(6) {
            state.onFrame(now)
            now += 1_500 * millis
            state.onFrame(now) // the close pair
            now += 20 * millis
        }
        assertFalse("a stalled driver still emits the odd close pair", rendered.value)
    }

    @Test
    fun `rendered never goes back to false once the scene is up`() {
        val rendered = mutableStateOf(false)
        val state = FirstFrameState(rendered)
        repeat(9) { frame -> state.onFrame(base + frame * 16 * millis) }
        assertTrue(rendered.value)
        state.onFrame(base + 60_000 * millis) // a long stall afterwards: a pause, not a regression
        assertTrue("the cover must not come back over a scene the user has seen", rendered.value)
    }
}

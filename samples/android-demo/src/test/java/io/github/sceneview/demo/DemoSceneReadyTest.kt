package io.github.sceneview.demo

import androidx.compose.runtime.mutableStateOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
 * frame rate. Frames are counted, intervals are not, and the backend fence that turns
 * the count into driver truth is polled, never awaited ([BackendDrainWaitTest], #3799).
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

    @Test
    fun `frames presented before the HDR lands lift the cover but do not make the scene ready`() {
        // #4174: the HDR decodes off the main thread and can land after the geometry. The cover
        // keeps its frame (holding it cost 0.4 to 1.4 s on a Pixel 4a), but the frames before
        // the HDR are neutral-lit, not the demo's picture: "Scene ready", which the render
        // goldens capture on, waits for it.
        val rendered = mutableStateOf(false)
        val state = FirstFrameState(rendered)
        state.holdUntil(landed = false)

        state.onFrame(base)
        state.onFrame(base + 16 * millis)

        assertTrue("the cover lifts on the same frame as without an HDR", rendered.value)
        assertFalse("frames without the HDR are not the scene", state.sceneReady.value)
        assertTrue("the bounded wait starts at the first frame without it", state.waitingOnContent.value)

        state.holdUntil(landed = true)
        state.onFrame(base + 900 * millis)
        assertTrue("the first frame that carries the HDR makes the scene ready", state.sceneReady.value)
    }

    @Test
    fun `an HDR that lands before the second frame is ready with the cover`() {
        val rendered = mutableStateOf(false)
        val state = FirstFrameState(rendered)
        state.holdUntil(landed = false)
        state.onFrame(base)
        assertFalse(rendered.value)

        state.holdUntil(landed = true)
        state.onFrame(base + 1_400 * millis)

        assertTrue(rendered.value)
        assertTrue("same frame as a scene that never held", state.sceneReady.value)
    }

    @Test
    fun `a load the demo saw fail releases Scene ready on a parked scene`() {
        // A failed HDR load never lands. The scene has presented its fallback-lit frames and
        // parked, so no later frame will come to count: the report has to mark it by itself.
        // The expiry alone no longer does (#4459): with nobody reporting a failure the load
        // is only slow, and the stage says "still loading" instead.
        val rendered = mutableStateOf(false)
        val state = FirstFrameState(rendered)
        state.holdUntil(landed = false)
        state.onFrame(base)
        state.onFrame(base + 16 * millis)
        assertFalse(state.sceneReady.value)

        state.contentWaitExpired()
        assertFalse("slow is not ready", state.sceneReady.value)

        state.reportContentFailed()

        assertTrue("a failed load costs a flat scene, never a hang", state.sceneReady.value)
    }

    @Test
    fun `a scene that is not holding is ready with the cover and never starts the content wait`() {
        val rendered = mutableStateOf(false)
        val state = FirstFrameState(rendered)
        state.onFrame(base)
        state.onFrame(base + 16 * millis)
        assertTrue(rendered.value)
        assertTrue(state.sceneReady.value)
        assertFalse(state.waitingOnContent.value)
    }

    @Test
    fun `an environment swap after the scene is up does not bring the cover back`() {
        val rendered = mutableStateOf(false)
        val state = FirstFrameState(rendered)
        state.onFrame(base)
        state.onFrame(base + 16 * millis)
        assertTrue(rendered.value)

        state.holdUntil(landed = false) // the user picked another HDR
        state.onFrame(base + 5_000 * millis)

        assertTrue("an environment swap is not a cold start", rendered.value)
        assertTrue(state.sceneReady.value)
    }

    // --- #4459: "Scene ready" waits for the models, and for the frame that shows them ---------

    /** A fence stand-in the test opens by hand. */
    private class ManualDrain {
        var drained = false
        private val pending = ArrayDeque<() -> Unit>()
        val wait = BackendDrainWait(
            newProbe = {
                object : DrainProbe {
                    override fun isDrained() = drained
                    override fun release() = Unit
                }
            },
            schedule = { _, block -> pending.addLast(block) },
        )
        fun poll() {
            if (pending.isNotEmpty()) pending.removeFirst()()
        }
    }

    @Test
    fun `an environment that landed does not make ready a scene whose models have not`() {
        // The CI captures of #4459: HDR in, cover up, "Scene ready" — and an empty stage, because
        // the HDR was the only thing the signal waited on.
        val state = FirstFrameState(mutableStateOf(false))
        state.holdUntil(landed = true)
        state.holdUntilModels(instancesLoaded = false) { false }

        state.onFrame(base)
        state.onFrame(base + 16 * millis)
        assertFalse("an empty stage is not the demo's picture", state.sceneReady.value)

        state.holdUntilModels(instancesLoaded = true) { false }
        state.onFrame(base + 3_000 * millis)
        assertTrue(state.sceneReady.value)
    }

    @Test
    fun `a model whose textures are still decoding is not ready`() {
        // gltfio hands the instance back before its textures are decoded: the model is in the
        // scene, untextured, for as many frames as that takes.
        var decoding = true
        val state = FirstFrameState(mutableStateOf(false))
        state.holdUntilModels(instancesLoaded = true) { decoding }

        state.onFrame(base)
        state.onFrame(base + 16 * millis)
        assertFalse(state.sceneReady.value)

        decoding = false
        state.onFrame(base + 32 * millis)
        assertTrue(state.sceneReady.value)
    }

    @Test
    fun `content that landed late is ready only once the backend has drawn the frame carrying it`() {
        // `onFrame` reports a frame that was submitted. On a software GL the backend can be
        // seconds behind, so a capture taken on that callback still shows the frame before.
        val cover = ManualDrain().apply { drained = true }
        val content = ManualDrain()
        val state = FirstFrameState(mutableStateOf(false), cover.wait, content.wait)
        state.holdUntilModels(instancesLoaded = false) { false }
        state.onFrame(base)
        state.onFrame(base + 16 * millis)
        assertTrue(state.rendered.value)

        state.holdUntilModels(instancesLoaded = true) { false }
        state.onFrame(base + 2_000 * millis)
        assertFalse("submitted is not drawn", state.sceneReady.value)
        state.onFrame(base + 2_016 * millis)
        content.poll()
        assertFalse("still behind", state.sceneReady.value)

        content.drained = true
        content.poll()
        assertTrue(state.sceneReady.value)
    }

    @Test
    fun `content that was there from the first frame costs no second drain`() {
        val cover = ManualDrain().apply { drained = true }
        val content = ManualDrain() // never drains: must not be asked
        val state = FirstFrameState(mutableStateOf(false), cover.wait, content.wait)
        state.holdUntilModels(instancesLoaded = true) { false }

        state.onFrame(base)
        state.onFrame(base + 16 * millis)

        assertTrue(state.sceneReady.value)
        assertFalse(content.wait.isWaiting)
    }

    @Test
    fun `a wait that expires with a load in flight says still loading and keeps waiting`() {
        val state = FirstFrameState(mutableStateOf(false))
        state.holdUntilModels(instancesLoaded = false) { false }
        state.onFrame(base)
        state.onFrame(base + 16 * millis)
        assertNull(state.contentIssue.value)

        state.contentWaitExpired()

        assertEquals("slow is not lost", DemoContentIssue.Slow, state.contentIssue.value)
        assertFalse("a half-loaded stage is not ready", state.sceneReady.value)
        state.onFrame(base + 31_000 * millis)
        assertEquals(DemoContentIssue.Slow, state.contentIssue.value)
        assertFalse(state.sceneReady.value)

        // The content turning up takes the card down by itself, and the scene is then ready.
        state.holdUntilModels(instancesLoaded = true) { false }
        state.onFrame(base + 40_000 * millis)
        assertNull(state.contentIssue.value)
        assertTrue(state.sceneReady.value)
    }

    @Test
    fun `textures still decoding at the end of the wait are slow, not failed`() {
        var decoding = true
        val state = FirstFrameState(mutableStateOf(false))
        state.holdUntilModels(instancesLoaded = true) { decoding }
        state.onFrame(base)
        state.onFrame(base + 16 * millis)

        state.contentWaitExpired()
        assertEquals(DemoContentIssue.Slow, state.contentIssue.value)
        assertFalse(state.sceneReady.value)

        decoding = false
        state.onFrame(base + 35_000 * millis)
        assertNull(state.contentIssue.value)
        assertTrue(state.sceneReady.value)
    }

    @Test
    fun `a slow scene that parked is ready on the next ask, with no frame`() {
        // The content landed after the last frame the scene presented: nothing calls onFrame.
        val state = FirstFrameState(mutableStateOf(false))
        state.holdUntil(landed = false)
        state.onFrame(base)
        state.onFrame(base + 16 * millis)
        state.contentWaitExpired()
        assertEquals(DemoContentIssue.Slow, state.contentIssue.value)

        state.holdUntil(landed = true)
        state.contentWaitExpired()

        assertNull(state.contentIssue.value)
        assertTrue(state.sceneReady.value)
    }

    @Test
    fun `a wait that expires with everything landed reports nothing`() {
        // The scene parked on the frame before the content landed and never presented another.
        val state = FirstFrameState(mutableStateOf(false))
        state.holdUntil(landed = false)
        state.onFrame(base)
        state.onFrame(base + 16 * millis)
        state.holdUntil(landed = true)

        state.contentWaitExpired()

        assertTrue(state.sceneReady.value)
        assertNull(state.contentIssue.value)
    }

    @Test
    fun `a failure the demo reports is said at once and is not taken back`() {
        val state = FirstFrameState(mutableStateOf(false))
        state.holdUntilModels(instancesLoaded = false) { false }
        state.holdUntil(landed = true)
        state.onFrame(base)
        state.onFrame(base + 16 * millis)

        state.reportContentFailed()

        assertEquals(DemoContentIssue.Failed, state.contentIssue.value)
        assertTrue("the user is told, the screen is not left on its cover", state.sceneReady.value)
        state.onFrame(base + 32 * millis)
        state.contentWaitExpired()
        assertEquals(DemoContentIssue.Failed, state.contentIssue.value)
    }

    @Test
    fun `a failure reported after the still loading card replaces it`() {
        val state = FirstFrameState(mutableStateOf(false))
        state.holdUntilModels(instancesLoaded = false) { false }
        state.onFrame(base)
        state.onFrame(base + 16 * millis)
        state.contentWaitExpired()

        state.reportContentFailed()

        assertEquals(DemoContentIssue.Failed, state.contentIssue.value)
        assertTrue(state.sceneReady.value)
    }

    @Test
    fun `a ready scene no longer asks the model loader on each frame`() {
        // `texturesPending` is `modelLoader.isLoading`: a JNI call, and a ready scene presents
        // frames for as long as it is on screen.
        var asked = 0
        val state = FirstFrameState(mutableStateOf(false))
        state.holdUntilModels(instancesLoaded = true) { asked++; false }
        state.onFrame(base)
        state.onFrame(base + 16 * millis)
        assertTrue(state.sceneReady.value)

        val askedUntilReady = asked
        repeat(100) { state.onFrame(base + (32 + it * 16) * millis) }

        assertEquals(askedUntilReady, asked)
    }
}

package io.github.sceneview

import androidx.compose.runtime.snapshots.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arithmetic of render-on-demand, with no Filament and no device.
 *
 * [FrameRateGate] is the whole of the default's behaviour: everything else in the change is wiring
 * that calls `requestRender()` or reports an active state. What is worth pinning here is not that
 * the gate says "yes" when something moved — that is one line — but the three properties that are
 * invisible in a diff and expensive on a device:
 *
 *  - it starts **dirty**, so a brand-new swap chain is never left blank;
 *  - it owes a **run** of frames after the last change, not one, because Filament finalises texture
 *    uploads and compiles material variants from inside the frame loop;
 *  - the run restarts on every change, so a gesture never gets a partial budget.
 */
class FrameRateGateTest {

    @Test
    fun startsDirtySoANewSwapChainIsNeverLeftBlank() {
        val gate = FrameRateGate()

        assertTrue(
            "a swap chain holds no pixels of its own: a gate that started settled would present " +
                "nothing into a brand-new surface and the view would be black (or transparent " +
                "with isOpaque = false) until something unrelated woke it (#3109)",
            gate.isDirty
        )
        assertTrue(gate.shouldRender(active = false))
    }

    @Test
    fun owesAFullRunOfFramesAfterTheLastChange() {
        val gate = FrameRateGate()
        var presented = 0

        while (gate.shouldRender(active = false)) {
            presented++
            gate.didRender()
            if (presented > SETTLE_FRAMES * 4) break
        }

        assertEquals(
            "the settle tail is what makes 'stops drawing' mean 'stops drawing the finished " +
                "picture': the first frame after a change is routinely not it — the Materials " +
                "demo needs ~4 presented frames over 6.3 s before the ToyCar's clearcoat " +
                "variants are warm, and a one-frame gate would freeze on the untextured one",
            SETTLE_FRAMES,
            presented
        )
        assertTrue(gate.isSettled)
    }

    @Test
    fun anActiveSceneNeverSettles() {
        val gate = FrameRateGate()

        repeat(SETTLE_FRAMES * 3) {
            assertTrue(
                "a finger on the screen, a playing animation or a decoding video must hold full " +
                    "cadence for as long as it lasts, not for one settle budget",
                gate.shouldRender(active = true)
            )
            gate.didRender()
            assertFalse(gate.isSettled)
        }
    }

    @Test
    fun aChangeMidSettleRestartsTheWholeRun() {
        val gate = FrameRateGate()
        repeat(SETTLE_FRAMES - 1) { gate.shouldRender(active = false); gate.didRender() }

        gate.requestRender()
        var presented = 0
        while (gate.shouldRender(active = false)) {
            presented++
            gate.didRender()
            if (presented > SETTLE_FRAMES * 4) break
        }

        assertEquals(
            "a change arriving on the last frame of a settle run must buy a full run, not the one " +
                "frame left over — otherwise the frames a change needs depend on when it landed",
            SETTLE_FRAMES,
            presented
        )
    }

    @Test
    fun requestRenderWritesSnapshotStateOnlyOnTheTransition() {
        val gate = FrameRateGate()
        gate.shouldRender(active = false)   // clears the dirty flag
        assertFalse(gate.isDirty)

        var writes = 0
        val snapshot = Snapshot.takeMutableSnapshot(writeObserver = { writes++ })
        try {
            snapshot.enter { repeat(50) { gate.requestRender() } }
            snapshot.apply().check()
        } finally {
            snapshot.dispose()
        }

        assertEquals(
            "requestRender is called from the touch dispatcher and from every Node transform " +
                "write, so it runs many times per frame on a moving scene. Writing snapshot state " +
                "on each one would invalidate derived state and wake the recomposer at exactly " +
                "the rate this change exists to remove.",
            1,
            writes
        )
    }

    @Test
    fun aPushSourceFiringFromInsideTheLoopNeverLetsTheSceneSettle() {
        val gate = FrameRateGate()

        repeat(SETTLE_FRAMES * 4) {
            // What `cameraNode.transform = manipulator.getTransform()` used to do on every tick:
            // the setter is a push source, so an unchanged rewrite invalidated the gate from
            // inside the very loop the gate is supposed to stop.
            gate.requestRender()
            gate.shouldRender(active = false)
            gate.didRender()
        }

        assertFalse(
            "an invalidation on every tick re-arms the whole settle budget on every tick, so the " +
                "scene renders forever at full cadence while nothing moves — and no test of the " +
                "gate's own arithmetic can catch it, because the cycle closes through a caller. " +
                "Anything the render loop writes per frame must be written only when it changed.",
            gate.isSettled
        )
    }

    // ── RenderInvalidator ────────────────────────────────────────────────────────────────────────

    @Test
    fun anInvalidatorReplaysTheRequestItReceivedBeforeAttach() {
        val invalidator = RenderInvalidator()
        val gate = FrameRateGate()
        gate.shouldRender(active = false)
        assertFalse(gate.isDirty)

        // A caller that edits a material in the same composition pass that creates the scene.
        invalidator.requestRender()
        invalidator.attach(gate)

        assertTrue(
            "dropping a request made before attach would make the escape hatch quietly racy: it " +
                "would work or not depending on composition order",
            gate.isDirty
        )
    }

    @Test
    fun aDetachedInvalidatorStopsReachingTheOldGate() {
        val invalidator = RenderInvalidator()
        val gate = FrameRateGate()
        invalidator.attach(gate)
        gate.shouldRender(active = false)

        invalidator.detach(gate)
        invalidator.requestRender()

        assertFalse("a disposed scene must not be kept awake by a surviving caller", gate.isDirty)
    }

    // ── Pull sources ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun everyPullSourceAloneKeepsTheSceneActive() {
        val none = BooleanArray(7)
        assertFalse(
            isSceneFrameActive(none[0], none[1], none[2], none[3], none[4], none[5], none[6])
        )
        repeat(none.size) { i ->
            val one = none.copyOf().also { it[i] = true }
            assertTrue(
                "pull source #$i must alone be enough to hold the cadence: each one is a state " +
                    "that lasts many frames and sends no event of its own",
                isSceneFrameActive(one[0], one[1], one[2], one[3], one[4], one[5], one[6])
            )
        }
    }

    // ── Cadence vote ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun theVoteAsksForTheDisplayMaximumWhileSomethingMovesAndRestsAfter() {
        assertEquals(
            "max cadence during the gesture and the animation",
            120f,
            frameRateVote(FrameRatePolicy.OnDemand(), active = true, maxRefreshRate = 120f),
            0f
        )
        assertEquals(
            "rest afterwards: 0 means 'no preference', which is what lets a variable refresh rate " +
                "panel drop to its idle mode — it is not a request for zero frames",
            0f,
            frameRateVote(FrameRatePolicy.OnDemand(), active = false, maxRefreshRate = 120f),
            0f
        )
        assertEquals(
            120f,
            frameRateVote(FrameRatePolicy.Continuous(), active = false, maxRefreshRate = 120f),
            0f
        )
        assertEquals(
            "an unknown display gets no vote rather than a guessed number it may not support",
            0f,
            frameRateVote(FrameRatePolicy.Continuous(), active = true, maxRefreshRate = null),
            0f
        )
    }

    @Test
    fun theVoteCarriesTheCapOnEitherMode() {
        assertEquals(
            "a Continuous scene capped at 30 asks the panel for 30, not for its maximum: a " +
                "variable refresh rate panel held at 120 to serve frames the cap forbids is the " +
                "exact power draw the cap exists to avoid",
            30f,
            frameRateVote(FrameRatePolicy.Continuous(maxFps = 30), active = false, 120f),
            0f
        )
        assertEquals(
            "an on-demand scene that woke up is drawing, and it is drawing at its cap",
            30f,
            frameRateVote(FrameRatePolicy.OnDemand(maxFps = 30), active = true, 120f),
            0f
        )
        assertEquals(
            "an on-demand scene at rest still withdraws the vote, cap or no cap",
            0f,
            frameRateVote(FrameRatePolicy.OnDemand(maxFps = 30), active = false, 120f),
            0f
        )
    }

    // ── The maxFps cap, on a display that is not 60 Hz ────────────────────────────────────────
    //
    // #3108 blocker 4. The slack used to be `8_000_000L`, "half a 60 Hz vsync", and every test ran
    // at 60 Hz. On a 120 Hz panel that constant is larger than a whole vsync (8.33 ms), so every
    // vsync cleared the deadline and the cap capped nothing. The emulator is locked at 60 Hz, so
    // this is the only instrument that reaches the 90 and 120 Hz cases; the on-device figures are
    // still owed to a Pixel 9.

    /** Frames presented over one virtual second of [refreshRate] vsyncs under an [fps] cap. */
    private fun presentedInOneSecond(fps: Int, refreshRate: Float): Int {
        val vsync = vsyncPeriodNanos(refreshRate)
        // `withFrameNanos` hands out system uptime, never 0 — and 0 is this function's "no frame
        // presented yet" sentinel, so a test starting at 0 would measure the sentinel, not the cap.
        val base = 1_000_000_000L
        var last = 0L
        var presented = 0
        for (i in 0 until refreshRate.toInt()) {
            val now = base + i * vsync
            if (shouldPresentAtCap(fps, now, last, vsync)) {
                presented++
                last = now
            }
        }
        return presented
    }

    @Test
    fun aCapPhaseLocksOntoARefreshRateItDoesNotDivideEvenly() {
        assertEquals(
            "a strict comparison rejects the vsync at 33.3 ms by a rounding hair and settles at " +
                "one frame per three vsyncs — 20 fps, not the 30 that was asked for",
            30,
            presentedInOneSecond(fps = 30, refreshRate = 60f)
        )
    }

    @Test
    fun aCapIsNeverExceededOnA120HzPanel() {
        // The regression this pins: with the old constant slack this read 120.
        val presented = presentedInOneSecond(fps = 90, refreshRate = 120f)
        assertTrue(
            "maxFps = 90 must never present faster than 90 on a 120 Hz panel — it read $presented",
            presented <= 90
        )
        assertEquals(
            "90 is not reachable on 120 Hz vsyncs; of the two reachable neighbours only 60 " +
                "honours \"never faster than fps\"",
            60,
            presented
        )
    }

    @Test
    fun aCapIsNeverExceededOnA90HzPanel() {
        val presented = presentedInOneSecond(fps = 60, refreshRate = 90f)
        assertTrue(
            "maxFps = 60 must never present faster than 60 on a 90 Hz panel — it read $presented",
            presented <= 60
        )
        assertEquals("one frame every two vsyncs of 90 Hz", 45, presented)
    }

    @Test
    fun a30HzCapHoldsAtBoth60And120Hz() {
        assertEquals("30 divides 60 exactly", 30, presentedInOneSecond(fps = 30, refreshRate = 60f))
        assertEquals(
            "30 divides 120 exactly too — one frame every fourth vsync",
            30,
            presentedInOneSecond(fps = 30, refreshRate = 120f)
        )
    }

    @Test
    fun aCapAtOrAboveTheRefreshRatePresentsEveryVsync() {
        assertEquals(
            "maxFps = 60 on a 60 Hz panel is every vsync, not every other one",
            60,
            presentedInOneSecond(fps = 60, refreshRate = 60f)
        )
        assertEquals(
            "asking for more than the panel can give presents every vsync, never more",
            60,
            presentedInOneSecond(fps = 120, refreshRate = 60f)
        )
    }

    @Test
    fun aPanelThatIsReally59Point94HzStillMeetsA30FpsCap() {
        // Two vsyncs of 59.94 Hz measure 33.37 ms against a 33.33 ms request: a strict `ceil`
        // would round those up to three vsyncs and deliver 20 fps from a 30 fps cap.
        val presented = presentedInOneSecond(fps = 30, refreshRate = 59.94f)
        assertTrue("expected ~30 frames, got $presented", presented in 29..30)
    }

    @Test
    fun anUnknownDisplayFallsBackToAStrictDeadline() {
        assertEquals(0L, vsyncPeriodNanos(null))
        assertEquals(0L, vsyncPeriodNanos(0f))
        // No phase lock available, so the deadline is compared as asked.
        assertTrue(shouldPresentAtCap(30, 1_000_000_000L + 33_400_000L, 1_000_000_000L, 0L))
        assertFalse(shouldPresentAtCap(30, 1_000_000_000L + 33_000_000L, 1_000_000_000L, 0L))
    }

    @Test
    fun theFirstFrameIsNeverHeldBackByACap() {
        assertTrue(shouldPresentAtCap(1, 0L, 0L, vsyncPeriodNanos(60f)))
    }

    @Test
    fun aNonPositiveCapIsRejectedAtConstruction() {
        // The cap arithmetic divides by `maxFps`, so a zero or negative one has no meaning. It is
        // refused where the caller can see it rather than silently treated as "present always" —
        // and on both modes, because the cap is now a property of the type, not a third case.
        assertThrows(IllegalArgumentException::class.java) { FrameRatePolicy.OnDemand(0) }
        assertThrows(IllegalArgumentException::class.java) { FrameRatePolicy.OnDemand(-30) }
        assertThrows(IllegalArgumentException::class.java) { FrameRatePolicy.Continuous(0) }
        assertThrows(IllegalArgumentException::class.java) { FrameRatePolicy.Continuous(-30) }
    }

    @Test
    fun noCapMeansTheDisplaysOwnCadence() {
        assertNull(
            "the default on both modes is `null`, which is 'whatever the panel does' — not a " +
                "number, so nothing has to be updated when panels get faster",
            FrameRatePolicy.OnDemand().maxFps
        )
        assertNull(FrameRatePolicy.Continuous().maxFps)
    }

    @Test
    fun aCapNeverVotesAboveWhatThePanelCanDo() {
        assertEquals(
            "a 240 fps cap on a 120 Hz panel votes 120, not a number the panel cannot honour",
            120f,
            frameRateVote(FrameRatePolicy.Continuous(maxFps = 240), active = true, 120f),
            0f
        )
        assertEquals(
            120f,
            frameRateVote(FrameRatePolicy.OnDemand(maxFps = 240), active = true, 120f),
            0f
        )
    }

    // ── The cap and the mode, composed ───────────────────────────────────────────────────────────
    //
    // The two questions are independent — *when* may a frame be drawn, and *how fast at most* — and
    // the old three-case shape (OnDemand | Continuous | Capped(fps)) could not express their
    // product: asking for a cap silently meant "and draw every frame, forever", so the one
    // combination a battery-conscious caller actually wants — render on demand, and even then never
    // faster than N — was unreachable. These pin the product itself, on [shouldPresentFrame], which
    // is the whole of `SceneView`'s per-frame decision.

    /** Frames presented over one virtual second of [refreshRate] vsyncs, gate always saying yes. */
    private fun presentedInOneSecond(policy: FrameRatePolicy, refreshRate: Float): Int {
        val vsync = vsyncPeriodNanos(refreshRate)
        val base = 1_000_000_000L
        var last = 0L
        var presented = 0
        for (i in 0 until refreshRate.toInt()) {
            val now = base + i * vsync
            if (shouldPresentFrame(policy, now, last, vsync) { true }) {
                presented++
                last = now
            }
        }
        return presented
    }

    @Test
    fun aCapAppliesToAnOnDemandSceneThatWokeUp() {
        assertEquals(
            "a scene that renders on demand and is currently being dragged must still honour its " +
                "cap: 30 fps on a 60 Hz panel is every other vsync",
            30,
            presentedInOneSecond(FrameRatePolicy.OnDemand(maxFps = 30), refreshRate = 60f)
        )
        assertEquals(
            "the same cap on the same scene drawn unconditionally",
            30,
            presentedInOneSecond(FrameRatePolicy.Continuous(maxFps = 30), refreshRate = 60f)
        )
    }

    @Test
    fun anUncappedPolicyPresentsOnEveryVsyncInEitherMode() {
        assertEquals(60, presentedInOneSecond(FrameRatePolicy.OnDemand(), refreshRate = 60f))
        assertEquals(120, presentedInOneSecond(FrameRatePolicy.Continuous(), refreshRate = 120f))
    }

    @Test
    fun aCappedTickNeverConsumesTheGatesInvalidation() {
        // The gate's read is *consuming*: it clears the dirty flag and re-arms the settle window.
        // So the cap has to be asked first. Asked the other way round, a change landing between two
        // capped ticks would be spent on a frame that is never drawn, and the screen would keep
        // showing the old picture until something else invalidated — a dropped frame turning into a
        // stale one, which is the "frozen image" family this whole change is trying to close.
        var pendingChange = true
        var gateReads = 0
        val vsync = vsyncPeriodNanos(60f)
        val lastPresent = 1_000_000_000L

        val presented = shouldPresentFrame(
            policy = FrameRatePolicy.OnDemand(maxFps = 30),
            frameTimeNanos = lastPresent + vsync,   // one vsync too early for a 30 fps cap
            lastPresentNanos = lastPresent,
            vsyncPeriodNanos = vsync
        ) {
            gateReads++
            pendingChange.also { pendingChange = false }
        }

        assertFalse("the cap forbids this tick", presented)
        assertEquals("the gate must not even be asked on a tick the cap forbids", 0, gateReads)
        assertTrue(
            "the invalidation must survive to the first tick the cap allows",
            pendingChange
        )
    }

    // --- The `isLoading` pull source -------------------------------------------------------------
    //
    // Measured on emulator-5554 before the fix: the `materials` and `debug-overlay` demos presented
    // 60 frames/s at rest with ZERO `requestRender()` calls, because `isLoading` was
    // `modelLoader.progress < 1f` and Filament's `asyncGetLoadProgress()` reports 0 — not 1 — for a
    // loader that was never asked to load anything. Neither screen loads a glTF file.

    @Test
    fun aSceneThatNeverStartedAnAsyncLoadIsNotLoading() {
        assertFalse(
            "a loader that was never asked for an async load reports progress 0, which the old " +
                "`progress < 1f` read as \"still loading\" for the lifetime of the view — every " +
                "procedural scene held at full cadence with a correct picture on screen",
            isAsyncLoadPending(loadStarted = false, progress = 0f)
        )
    }

    @Test
    fun aLoadInFlightKeepsTheSceneActive() {
        assertTrue(
            "texture finalisation happens inside the frame loop: parking here renders untextured",
            isAsyncLoadPending(loadStarted = true, progress = 0f)
        )
        assertTrue(isAsyncLoadPending(loadStarted = true, progress = 0.4f))
    }

    @Test
    fun aFinishedLoadStopsKeepingTheSceneActive() {
        assertFalse(isAsyncLoadPending(loadStarted = true, progress = 1f))
    }

    @Test
    fun aProceduralSceneAtRestIsNotFrameActive() {
        // The whole `active` fold, with the terms a `materials`-like scene really has at rest:
        // no touch, a paused camera, no playing animation, nothing loading, nothing mirroring,
        // framing latched. This is the assertion the on-device measurement failed.
        assertFalse(
            isSceneFrameActive(
                gestureInFlight = false,
                cameraMoved = false,
                cameraPending = false,
                hasActiveNode = false,
                isLoading = isAsyncLoadPending(loadStarted = false, progress = 0f),
                isMirroring = false,
                framingPending = false
            )
        )
    }
}

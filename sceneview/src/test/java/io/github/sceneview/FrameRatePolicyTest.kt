package io.github.sceneview

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.reflect.jvm.kotlinFunction

/**
 * Pins the parked half of `SceneView`'s render loop under [FrameRatePolicy.OnDemand] (#3108).
 *
 * The policy's whole value is that an idle 3D screen stops costing anything. Stopping the GPU work
 * is the obvious half; the half that is easy to get wrong — and impossible to see by reading the
 * diff — is that the parked loop must **park** rather than **poll**. A `while (settled) delay(16)`
 * spin renders nothing and still wakes the CPU ~60x a second forever, on a scene that by definition
 * never changes.
 *
 * Both shapes look identical from the outside, so the discriminator here is the virtual clock:
 * [runCurrent] runs only the tasks scheduled at the *current* virtual time and never a delayed one.
 * A parked loop is resumed by the snapshot apply itself and therefore completes under [runCurrent]
 * at virtual time 0; a polling loop is sitting in a `delay` scheduled 16 ms out and cannot. That
 * also states the user-visible property directly: a touch on a settled scene reaches the screen
 * immediately, not "within a frame".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FrameRatePolicyTest {

    /**
     * The production wake condition, verbatim from `SceneView.kt`: a policy that draws
     * unconditionally, or a gate something has invalidated.
     */
    private fun shouldRenderState(
        policy: androidx.compose.runtime.State<FrameRatePolicy>,
        gate: FrameRateGate
    ) = derivedStateOf { policy.value !is FrameRatePolicy.OnDemand || gate.isDirty }

    /**
     * Burns the gate's settle window down the way a rendering loop does, with nothing active.
     *
     * The window is a duration, not a frame count, so this walks a virtual 60 Hz clock rather than
     * counting ticks — the gate reads no clock of its own, it is handed the loop's frame time.
     */
    private fun FrameRateGate.settle() {
        var frameTimeNanos = 1_000_000_000L
        while (!isSettled) {
            shouldRender(active = false, frameTimeNanos = frameTimeNanos)
            didRender(frameTimeNanos)
            frameTimeNanos += vsyncPeriodNanos(60f)
        }
    }

    @Test
    fun returnsImmediatelyWhileTheSceneIsDirty() = runTest {
        val gate = FrameRateGate()
        val policy = mutableStateOf<FrameRatePolicy>(FrameRatePolicy.OnDemand())
        var completed = false

        launch {
            awaitRenderingEnabled(shouldRenderState(policy, gate))
            completed = true
        }
        runCurrent()

        assertTrue(
            "the per-frame hot path must not suspend while the scene is dirty — every frame of " +
                "every moving scene goes through here",
            completed
        )
    }

    @Test
    fun returnsImmediatelyUnderContinuous() = runTest {
        val gate = FrameRateGate()
        gate.settle()
        val policy = mutableStateOf<FrameRatePolicy>(FrameRatePolicy.Continuous())
        var completed = false

        launch {
            awaitRenderingEnabled(shouldRenderState(policy, gate))
            completed = true
        }
        runCurrent()

        assertTrue(
            "FrameRatePolicy.Continuous is the pre-1.0 behaviour verbatim: it must never park, " +
                "however settled the gate is",
            completed
        )
    }

    @Test
    fun parksWhileTheSceneIsSettled() = runTest {
        val gate = FrameRateGate()
        gate.settle()
        val policy = mutableStateOf<FrameRatePolicy>(FrameRatePolicy.OnDemand())
        var completed = false

        val job = launch {
            awaitRenderingEnabled(shouldRenderState(policy, gate))
            completed = true
        }

        // Five virtual seconds of a completely idle scene.
        advanceTimeBy(5_000)
        runCurrent()

        assertFalse("the loop must not render on a settled scene", completed)
        assertTrue("the loop must stay suspended, not complete", job.isActive)

        job.cancel()
    }

    @Test
    fun resumesOnTheSnapshotApplyRatherThanOnAPollTick() = runTest {
        val gate = FrameRateGate()
        gate.settle()
        val policy = mutableStateOf<FrameRatePolicy>(FrameRatePolicy.OnDemand())
        var resumedAtVirtualTime = -1L

        val job = launch {
            awaitRenderingEnabled(shouldRenderState(policy, gate))
            resumedAtVirtualTime = currentTime
        }
        runCurrent()
        assertEquals("must be parked before anything invalidates", -1L, resumedAtVirtualTime)

        gate.requestRender()
        Snapshot.sendApplyNotifications()
        runCurrent()

        assertTrue("the parked loop must resume on requestRender()", job.isCompleted)
        assertEquals(
            "resume must happen on the snapshot apply itself, at virtual time 0. If this reads 16 " +
                "(or the job is still active), the park has regressed to a `delay(16)` poll loop: " +
                "runCurrent() cannot run a delayed continuation, so a spinning implementation is " +
                "still sitting in its next tick here. That loop keeps the CPU waking 60x/s on an " +
                "idle scene, which is the drain #3108 is about.",
            0L,
            resumedAtVirtualTime
        )
    }

    @Test
    fun switchingToContinuousAtRuntimeWakesTheParkedLoop() = runTest {
        val gate = FrameRateGate()
        gate.settle()
        val policy = mutableStateOf<FrameRatePolicy>(FrameRatePolicy.OnDemand())
        var completed = false

        val job = launch {
            awaitRenderingEnabled(shouldRenderState(policy, gate))
            completed = true
        }
        advanceTimeBy(5_000)
        runCurrent()
        assertFalse(completed)

        policy.value = FrameRatePolicy.Continuous()
        Snapshot.sendApplyNotifications()
        runCurrent()

        assertTrue(
            "the policy is read through a state ref so it can be swapped at runtime without " +
                "restarting the loop — a parked loop must notice",
            job.isCompleted
        )
    }

    @Test
    fun unrelatedSnapshotWritesDoNotResumeTheLoop() = runTest {
        val gate = FrameRateGate()
        gate.settle()
        val policy = mutableStateOf<FrameRatePolicy>(FrameRatePolicy.OnDemand())
        val somethingElse = mutableStateOf(0)
        var completed = false

        val job = launch {
            awaitRenderingEnabled(shouldRenderState(policy, gate))
            completed = true
        }
        runCurrent()

        repeat(10) {
            somethingElse.value += 1
            Snapshot.sendApplyNotifications()
            runCurrent()
        }

        assertFalse(
            "an unrelated snapshot write must not restart rendering — the loop wakes on the gate " +
                "and the policy only",
            completed
        )
        assertTrue(job.isActive)

        job.cancel()
    }

    /**
     * A parked loop must still wake for a surface that is owed a frame (#3109).
     *
     * A Filament swap chain is created empty: `SceneRenderer.onNativeWindowChanged` presents
     * nothing into it. So every surface generation — first attach, app foregrounded, foldable
     * folded or unfolded, split-screen resize — starts blank, and a loop that could only be woken
     * by a *scene* change would leave it blank indefinitely: black, or transparent with
     * `isOpaque = false`. This reproduces the production shape: the surface callbacks call
     * `frameRateGate.requestRender()` from the main thread.
     */
    @Test
    fun anOwedFrameWakesTheParkedLoopOnASettledScene() = runTest {
        val gate = FrameRateGate()
        gate.settle()
        val policy = mutableStateOf<FrameRatePolicy>(FrameRatePolicy.OnDemand())
        var resumedAtVirtualTime = -1L

        val job = launch {
            awaitRenderingEnabled(shouldRenderState(policy, gate))
            resumedAtVirtualTime = currentTime
        }
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals("must be parked while nothing is owed", -1L, resumedAtVirtualTime)

        // What onSurfaceReady / onSurfaceResized do when a new swap chain arrives.
        val parkedUntilVirtualTime = currentTime
        gate.requestRender()
        Snapshot.sendApplyNotifications()
        runCurrent()

        assertTrue(
            "a new or resized surface must get a frame even on a settled scene — otherwise a " +
                "fold/unfold or an app return leaves a permanently blank view, which is worse " +
                "than the stale frame the park promises",
            job.isCompleted
        )
        assertEquals(
            "the wake must come from the snapshot apply itself: no virtual time may pass between " +
                "the debt being set and the loop resuming. A later time means the debt is being " +
                "noticed by a poll tick instead.",
            parkedUntilVirtualTime,
            resumedAtVirtualTime
        )
    }

    /**
     * The invalidation is a one-shot, not a licence to spin: once the owed frames have been
     * presented the loop must park again, or [FrameRatePolicy.OnDemand] would stop meaning anything
     * after the first surface event.
     */
    @Test
    fun settlingTheOwedFramesParksTheLoopAgain() = runTest {
        val gate = FrameRateGate()
        val policy = mutableStateOf<FrameRatePolicy>(FrameRatePolicy.OnDemand())
        val shouldRender = shouldRenderState(policy, gate)

        // First pass: the gate starts dirty, so the loop does not park at all.
        var firstPassCompleted = false
        launch {
            awaitRenderingEnabled(shouldRender)
            firstPassCompleted = true
        }
        runCurrent()
        assertTrue("an owed frame must not park", firstPassCompleted)

        // The frames were presented — what the render loop writes back after renderFrame.
        gate.settle()
        Snapshot.sendApplyNotifications()

        var secondPassCompleted = false
        val job = launch {
            awaitRenderingEnabled(shouldRender)
            secondPassCompleted = true
        }
        advanceTimeBy(5_000)
        runCurrent()

        assertFalse(
            "with the settle budget spent, the loop must go back to parking — a surface event " +
                "must not leave the scene rendering forever",
            secondPassCompleted
        )
        assertTrue(job.isActive)

        job.cancel()
    }

    /**
     * Wiring pin, mirroring [SceneAutoFitWiringTest]: the parameter must exist on the public
     * composable *and* on the deprecated `Scene` alias. The alias forwards every other parameter,
     * so a caller still on the old name would otherwise be silently unable to pick a policy.
     *
     * Scope, stated so nobody reads more into a green run than it earns: this pins the
     * *declaration* on both facades, not the forwarding. An alias that declared `frameRatePolicy`
     * and dropped it on the floor would still pass here — proving the value reaches the loop needs
     * a Compose runtime host, which is `SceneAutoFitWiringTest`'s level for every other parameter
     * too.
     */
    @Test
    fun bothComposablesDeclareFrameRatePolicyParameter() {
        val facade = Class.forName("io.github.sceneview.SceneKt")

        listOf("SceneView", "Scene").forEach { functionName ->
            val parameterNames = facade.declaredMethods
                .filter { it.name == functionName }
                .mapNotNull { it.kotlinFunction }
                .flatMap { fn -> fn.parameters.mapNotNull { it.name } }
                .toSet()

            assertTrue(
                "#3108: `$functionName` must declare a `frameRatePolicy` parameter",
                "frameRatePolicy" in parameterNames
            )
            assertFalse(
                "`isRendering` was removed in favour of `frameRatePolicy` — a re-added overload " +
                    "would give callers two ways to say the same thing that disagree",
                "isRendering" in parameterNames
            )
        }
    }

    /**
     * A cap of zero or less describes a scene that never presents a frame, which is a frozen view
     * and never what a caller means. Both policies reject it at construction rather than handing
     * the loop a period of infinity: the failure must land on the line that wrote the policy, not
     * hours later on a black screen.
     *
     * `null` stays legal — it is the documented "the display's own cadence" default.
     */
    @Test
    fun maxFpsMustBeNullOrStrictlyPositive() {
        listOf(0, -1, Int.MIN_VALUE).forEach { invalid ->
            listOf<Pair<String, () -> FrameRatePolicy>>(
                "OnDemand" to { FrameRatePolicy.OnDemand(maxFps = invalid) },
                "Continuous" to { FrameRatePolicy.Continuous(maxFps = invalid) }
            ).forEach { (name, construct) ->
                val thrown = runCatching { construct() }.exceptionOrNull()
                assertTrue(
                    "$name(maxFps = $invalid) must be rejected at construction, got $thrown",
                    thrown is IllegalArgumentException
                )
                assertTrue(
                    "the message must name the offending value so the fix is obvious: $thrown",
                    thrown?.message?.contains("$invalid") == true
                )
            }
        }

        // The documented default and an ordinary cap must keep working.
        assertEquals(null, FrameRatePolicy.OnDemand().maxFps)
        assertEquals(30, FrameRatePolicy.OnDemand(maxFps = 30).maxFps)
        assertEquals(null, FrameRatePolicy.Continuous().maxFps)
        assertEquals(1, FrameRatePolicy.Continuous(maxFps = 1).maxFps)
    }
}

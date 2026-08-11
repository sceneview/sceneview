package io.github.sceneview

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
 * Pins the `isRendering = false` half of `SceneView`'s render loop (#3108).
 *
 * The parameter's whole value is that an idle 3D screen stops costing anything. Stopping the GPU
 * work is the obvious half; the half that is easy to get wrong — and impossible to see by reading
 * the diff — is that the paused loop must **park** rather than **poll**. A
 * `while (!isRendering) delay(16)` spin renders nothing and still wakes the CPU ~60x a second
 * forever, on a scene that by definition never changes.
 *
 * Both shapes look identical from the outside, so the discriminator here is the virtual clock:
 * [runCurrent] runs only the tasks scheduled at the *current* virtual time and never a delayed one.
 * A parked loop is resumed by the snapshot apply itself and therefore completes under
 * [runCurrent] at virtual time 0; a polling loop is sitting in a `delay` scheduled 16 ms out and
 * cannot. That also states the user-visible property directly: resuming rendering is immediate,
 * not "within a frame".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SceneIsRenderingTest {

    @Test
    fun returnsImmediatelyWhileRenderingIsEnabled() = runTest {
        val isRendering = mutableStateOf(true)
        var completed = false

        launch {
            awaitRenderingEnabled(isRendering)
            completed = true
        }
        runCurrent()

        assertTrue(
            "the per-frame hot path must not suspend while isRendering is true — every frame of " +
                "every normal scene goes through here",
            completed
        )
    }

    @Test
    fun parksWhileRenderingIsDisabled() = runTest {
        val isRendering = mutableStateOf(false)
        var completed = false

        val job = launch {
            awaitRenderingEnabled(isRendering)
            completed = true
        }

        // Five virtual seconds of a completely idle scene.
        advanceTimeBy(5_000)
        runCurrent()

        assertFalse("the loop must not render while isRendering is false", completed)
        assertTrue("the loop must stay suspended, not complete", job.isActive)

        job.cancel()
    }

    @Test
    fun resumesOnTheSnapshotApplyRatherThanOnAPollTick() = runTest {
        val isRendering = mutableStateOf(false)
        var resumedAtVirtualTime = -1L

        val job = launch {
            awaitRenderingEnabled(isRendering)
            resumedAtVirtualTime = currentTime
        }
        runCurrent()
        assertEquals("must be parked before the flag flips", -1L, resumedAtVirtualTime)

        isRendering.value = true
        Snapshot.sendApplyNotifications()
        runCurrent()

        assertTrue("the parked loop must resume once isRendering flips back to true", job.isCompleted)
        assertEquals(
            "resume must happen on the snapshot apply itself, at virtual time 0. If this reads 16 " +
                "(or the job is still active), the pause has regressed to a `delay(16)` poll loop: " +
                "runCurrent() cannot run a delayed continuation, so a spinning implementation is " +
                "still sitting in its next tick here. That loop keeps the CPU waking 60x/s on an " +
                "idle scene, which is the drain #3108 is about.",
            0L,
            resumedAtVirtualTime
        )
    }

    @Test
    fun unrelatedSnapshotWritesDoNotResumeTheLoop() = runTest {
        val isRendering = mutableStateOf(false)
        val somethingElse = mutableStateOf(0)
        var completed = false

        val job = launch {
            awaitRenderingEnabled(isRendering)
            completed = true
        }
        runCurrent()

        repeat(10) {
            somethingElse.value += 1
            Snapshot.sendApplyNotifications()
            runCurrent()
        }

        assertFalse(
            "an unrelated recomposition must not restart rendering — the loop wakes on " +
                "isRendering only",
            completed
        )
        assertTrue(job.isActive)

        job.cancel()
    }

    /**
     * Wiring pin, mirroring [SceneAutoFitWiringTest]: the parameter must exist on the public
     * composable *and* on the deprecated `Scene` alias. The alias forwards every other parameter,
     * so a caller still on the old name would otherwise be silently unable to pause rendering.
     */
    @Test
    fun bothComposablesDeclareIsRenderingParameter() {
        val facade = Class.forName("io.github.sceneview.SceneKt")

        listOf("SceneView", "Scene").forEach { functionName ->
            val parameterNames = facade.declaredMethods
                .filter { it.name == functionName }
                .mapNotNull { it.kotlinFunction }
                .flatMap { fn -> fn.parameters.mapNotNull { it.name } }
                .toSet()

            assertTrue(
                "#3108: `$functionName` must declare an `isRendering` parameter",
                "isRendering" in parameterNames
            )
        }
    }
}

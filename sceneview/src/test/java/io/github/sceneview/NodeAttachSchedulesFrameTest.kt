package io.github.sceneview

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateList
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

/**
 * Pins the attach-path half of #3560: a `ModelNode` (or any DSL child node) added to an
 * already-running `Scene`/`SceneView` must schedule a frame the same way a surface resize does.
 *
 * The reporter's repro: with a render-on-demand scene (`isRendering = false`, the documented use
 * of that parameter — see [SceneIsRenderingTest]), a node added via the `content { … }` DSL loads
 * and reports bounds through `onBounds`, but the parked `withFrameNanos` loop never wakes to
 * actually draw it. Only an unrelated surface resize (which sets `needsPresent = true` in
 * `onSurfaceResized`) incidentally unparks the loop and reveals the model.
 *
 * Filament itself cannot run on the JVM, so this reproduces the exact Compose-primitive shape of
 * `SceneView.kt`'s DSL-node sync effect — a `snapshotFlow` collector over a `SnapshotStateList`
 * that now also flips `needsPresent` — without any Filament/Node dependency, mirroring
 * [SceneIsRenderingTest]'s `awaitRenderingEnabled` + `derivedStateOf { isRendering || needsPresent }`
 * harness for the resize path.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NodeAttachSchedulesFrameTest {

    /**
     * The DSL-node sync effect under test, extracted to a plain function: diffs
     * [scopeChildNodes] against what was previously seen and, on any change, marks
     * [needsPresent] — exactly what `SceneView.kt`'s `LaunchedEffect(nodeManager, …)` block does
     * around its `snapshotFlow { scopeChildNodes.toList() }.collect { … }` (see the
     * `needsPresent.value = true` at the end of that collector, #3560).
     */
    private suspend fun syncChildNodesAndScheduleFrame(
        scopeChildNodes: SnapshotStateList<Int>,
        needsPresent: androidx.compose.runtime.MutableState<Boolean>
    ) {
        var prevNodes = emptyList<Int>()
        snapshotFlow { scopeChildNodes.toList() }.collect { newNodes ->
            prevNodes = newNodes
            needsPresent.value = true
        }
    }

    @Test
    fun attachingANodeWakesAParkedLoopWhileRenderingStaysDisabled() = runTest {
        val isRendering = mutableStateOf(false)
        val needsPresent = mutableStateOf(false)
        val shouldRender = derivedStateOf { isRendering.value || needsPresent.value }
        val scopeChildNodes = mutableStateListOf<Int>()

        // The sync effect's own LaunchedEffect — runs concurrently with the render loop, exactly
        // like in `SceneView`. Launched on `backgroundScope` because it never completes
        // (`snapshotFlow` collects forever), same as `SceneView`'s own `LaunchedEffect`.
        backgroundScope.launch { syncChildNodesAndScheduleFrame(scopeChildNodes, needsPresent) }
        runCurrent()
        // The initial emission (the starting, empty content) already schedules one frame —
        // settle it before testing the attach itself, exactly like production settles the debt
        // back to false once the loop presents.
        needsPresent.value = false

        var resumedAtVirtualTime = -1L
        val renderLoop = launch {
            awaitRenderingEnabled(shouldRender)
            resumedAtVirtualTime = currentTime
        }
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(
            "the loop must stay parked until a node is actually attached — nothing has happened yet",
            -1L,
            resumedAtVirtualTime
        )

        // What the Compose DSL does when a caller adds a `ModelNode` to a live `content { … }`:
        // mutate the snapshot list. No resize, no recomposition of `SceneView` itself.
        val parkedUntilVirtualTime = currentTime
        scopeChildNodes.add(1)
        Snapshot.sendApplyNotifications()
        runCurrent()
        // A second apply/runCurrent pass: outside a real Compose runtime nothing auto-propagates
        // a chained state write, so the sync effect's own `needsPresent.value = true` (just run
        // above) needs its own notification before `awaitRenderingEnabled`'s snapshotFlow sees it
        // — same two-hop shape production gets for free from the Recomposer.
        Snapshot.sendApplyNotifications()
        runCurrent()

        assertTrue(
            "attaching a node to the live scene must wake the parked render loop on its own — " +
                "recomposition/snapshotFlow alone is not observed by the withFrameNanos loop unless " +
                "it also flips needsPresent (#3560)",
            renderLoop.isCompleted
        )
        assertEquals(
            "the wake must come from the same snapshot apply that attached the node, at zero " +
                "elapsed virtual time — same shape as a surface resize (SceneIsRenderingTest)",
            parkedUntilVirtualTime,
            resumedAtVirtualTime
        )
        assertFalse("isRendering itself must not be mutated by the attach path", isRendering.value)
    }

    @Test
    fun detachingANodeAlsoWakesTheParkedLoop() = runTest {
        val isRendering = mutableStateOf(false)
        val needsPresent = mutableStateOf(false)
        val shouldRender = derivedStateOf { isRendering.value || needsPresent.value }
        val scopeChildNodes = mutableStateListOf(1, 2)

        backgroundScope.launch { syncChildNodesAndScheduleFrame(scopeChildNodes, needsPresent) }
        runCurrent()
        // The initial emission of the collector (the starting content) already schedules one
        // frame — settle it before testing the removal, exactly like production settles the
        // debt back to false once the loop presents.
        needsPresent.value = false

        var resumedAtVirtualTime = -1L
        launch {
            awaitRenderingEnabled(shouldRender)
            resumedAtVirtualTime = currentTime
        }
        runCurrent()
        assertEquals(-1L, resumedAtVirtualTime)

        scopeChildNodes.removeAt(0)
        Snapshot.sendApplyNotifications()
        runCurrent()
        // Second hop — see the comment in `attachingANodeWakesAParkedLoopWhileRenderingStaysDisabled`.
        Snapshot.sendApplyNotifications()
        runCurrent()

        assertTrue(
            "removing a node changes what is on screen just as much as adding one — the loop must " +
                "wake to actually stop drawing it",
            resumedAtVirtualTime >= 0L
        )
    }

    @Test
    fun noUnrelatedNodeChangeDoesNotLeaveTheLoopSpinning() = runTest {
        val isRendering = mutableStateOf(false)
        val needsPresent = mutableStateOf(false)
        val shouldRender = derivedStateOf { isRendering.value || needsPresent.value }
        val scopeChildNodes = mutableStateListOf<Int>()

        backgroundScope.launch { syncChildNodesAndScheduleFrame(scopeChildNodes, needsPresent) }
        runCurrent()
        // Settle the initial-emission debt, mirroring production's post-present reset.
        needsPresent.value = false

        scopeChildNodes.add(1)
        Snapshot.sendApplyNotifications()
        runCurrent()
        // Settle the debt the attach itself created — this test is about what happens *after*.
        needsPresent.value = false
        Snapshot.sendApplyNotifications()
        runCurrent()

        var completed = false
        val job = launch {
            awaitRenderingEnabled(shouldRender)
            completed = true
        }
        advanceTimeBy(5_000)
        runCurrent()

        assertFalse(
            "once the debt from the attach is settled and nothing else changed, the loop must go " +
                "back to parking, not spin forever",
            completed
        )
        assertTrue(job.isActive)

        job.cancel()
    }
}

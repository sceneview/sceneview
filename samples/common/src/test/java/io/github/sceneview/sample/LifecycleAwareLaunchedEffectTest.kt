package io.github.sceneview.sample

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.testing.TestLifecycleOwner
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Regression tests for [LifecycleAwareLaunchedEffect].
 *
 * The helper is a one-liner — `LaunchedEffect { lifecycle.repeatOnLifecycle(
 * STARTED) { block() } }`. Its entire contract is therefore the behaviour of
 * `repeatOnLifecycle(STARTED)`: cancel `block` on `onStop`, re-run it from the
 * top on `onStart`. These tests drive that exact core against a real
 * [TestLifecycleOwner] (from `androidx.lifecycle:lifecycle-runtime-testing`),
 * so they fail if a future edit changes the target state or drops the
 * `repeatOnLifecycle` wrapper.
 *
 * A `@Composable` cannot be invoked from a plain JUnit test and
 * `:samples:common` ships no `compose-ui-test` runtime. The lifecycle binding
 * the audit (#936) actually cares about is exercised for real here.
 *
 * Acceptance (#972): "the lifecycle restart-from-top test must explicitly
 * assert 'block ran twice across one onStop/onStart cycle'."
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LifecycleAwareLaunchedEffectTest {

    // `TestLifecycleOwner`'s state transitions hop through `Dispatchers.Main`
    // (the `LifecycleRegistry` dispatches observer callbacks there), and
    // `repeatOnLifecycle` itself does `withContext(Dispatchers.Main.immediate)`.
    // Installing one shared test dispatcher as Main keeps every transition on
    // the same single-threaded scheduler the test bodies use, so the
    // interleaving stays deterministic.
    private val mainDispatcher = UnconfinedTestDispatcher()

    @Before
    fun installMain() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun `block runs while STARTED`() = runTest(mainDispatcher) {
        val owner = TestLifecycleOwner(Lifecycle.State.CREATED, mainDispatcher)
        var started = false
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                started = true
                awaitCancellation()
            }
        }

        assertFalse("block must not run before the lifecycle is STARTED", started)
        owner.currentState = Lifecycle.State.STARTED
        assertTrue("block must run once the lifecycle reaches STARTED", started)

        owner.currentState = Lifecycle.State.DESTROYED
        job.join()
    }

    @Test
    fun `block is cancelled on onStop`() = runTest(mainDispatcher) {
        val owner = TestLifecycleOwner(Lifecycle.State.STARTED, mainDispatcher)
        var cancelled = false
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    awaitCancellation()
                } finally {
                    // Cancellation here == the background-drain fix from #936:
                    // a `while(true)` loop stops burning CPU on `onStop`.
                    cancelled = true
                }
            }
        }

        assertFalse("block must still be active while STARTED", cancelled)
        owner.currentState = Lifecycle.State.CREATED // onStop
        assertTrue("block must be cancelled when the lifecycle drops below STARTED", cancelled)

        owner.currentState = Lifecycle.State.DESTROYED
        job.join()
    }

    @Test
    fun `block runs twice across one onStop onStart cycle`() = runTest(mainDispatcher) {
        // The honest UX trade-off the helper's KDoc documents: the body is
        // RESTARTED FROM THE TOP on every foreground, not resumed in place.
        // #972 demands this be asserted explicitly so the doc stays honest.
        val owner = TestLifecycleOwner(Lifecycle.State.STARTED, mainDispatcher)
        var runCount = 0
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Incrementing at the TOP of the block is the point: a
                // restart-from-top helper re-executes this line every onStart.
                runCount++
                awaitCancellation()
            }
        }

        assertEquals("block should have run exactly once after first START", 1, runCount)

        owner.currentState = Lifecycle.State.CREATED // background (onStop)
        assertEquals("backgrounding must not re-run the block", 1, runCount)

        owner.currentState = Lifecycle.State.STARTED // foreground (onStart)
        assertEquals(
            "block must RE-RUN FROM THE TOP on onStart — this is the " +
                "documented restart-from-top trade-off of " +
                "LifecycleAwareLaunchedEffect (#936). If it should resume in " +
                "place, the demo must use LifecyclePausingLaunchedEffect.",
            2,
            runCount,
        )

        owner.currentState = Lifecycle.State.DESTROYED
        job.join()
    }

    @Test
    fun `block does not run again after DESTROYED`() = runTest(mainDispatcher) {
        val owner = TestLifecycleOwner(Lifecycle.State.STARTED, mainDispatcher)
        var runCount = 0
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                runCount++
                awaitCancellation()
            }
        }
        assertEquals(1, runCount)

        owner.currentState = Lifecycle.State.DESTROYED
        job.join()
        assertEquals("repeatOnLifecycle must complete — and never re-run — after DESTROYED", 1, runCount)
    }
}

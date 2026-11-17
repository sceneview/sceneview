package io.github.sceneview

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SceneAnimationFrameCompositionTest {

    @Test
    fun shortDriverAtOneFpsPresentsFinalEditAfterUnsubscription() = runTest {
        val policy = FrameRatePolicy.OnDemand(maxFps = 1)
        val gate = FrameRateGate()
        var lastPresentNanos = 0L
        var editedValue = 0
        var presentedValue = 0
        var presentations = 0
        val driver = mutableStateOf<((Long) -> Unit)?>(null)
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(backgroundScope.coroutineContext + clock)
        backgroundScope.launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val composition = Composition(NoNodes(), recomposer)
        composition.setContent { AnimationFrameInvalidationEffect(gate, driver.value != null) }

        fun recompose() {
            Snapshot.sendApplyNotifications()
            runCurrent()
            clock.sendFrame(0L)
            runCurrent()
        }

        fun tick(now: Long) {
            if (!isSceneRenderingEnabled(policy, gate.isDirty, driver.value != null) &&
                gate.isSettled
            ) return
            driver.value?.invoke(now)
            val presented = shouldPresentFrame(policy, now, lastPresentNanos, vsyncPeriodNanos(60f)) {
                gate.shouldRender(active = driver.value != null, frameTimeNanos = now)
            }
            if (presented) {
                presentedValue = editedValue
                presentations++
                lastPresentNanos = now
                gate.didRender(now)
            }
        }

        try {
            recompose()
            tick(1_000_000_000L)
            tick(2_000_000_000L)
            assertTrue("start with a parked scene just after a presentation", gate.isSettled)
            presentations = 0

            driver.value = { editedValue++ } // Direct edits with no independent invalidation.
            recompose()
            val period = vsyncPeriodNanos(60f)
            repeat(20) { index -> tick(2_000_000_000L + (index + 1) * period) }
            assertEquals(20, editedValue)
            assertEquals("the cap must reject all ticks of this short animation", 0, presentations)

            driver.value = null
            recompose()
            assertTrue("the final edit must remain pending after the driver is cleared", gate.isDirty)
            assertFalse("the scene must not park before presenting the final edit", gate.isSettled)
            repeat(120) { index -> tick(2_000_000_000L + (index + 21) * period) }

            assertEquals("the final direct edit must reach the surface", 20, presentedValue)
            assertEquals("present the pending frame and the minimum settle tail", 2, presentations)
            assertTrue("the scene must park again after its settle tail", gate.isSettled)
        } finally {
            composition.dispose()
            recomposer.cancel()
        }
    }

    private class NoNodes : AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(index: Int, instance: Unit) = Unit
        override fun insertBottomUp(index: Int, instance: Unit) = Unit
        override fun remove(index: Int, count: Int) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun onClear() = Unit
    }
}

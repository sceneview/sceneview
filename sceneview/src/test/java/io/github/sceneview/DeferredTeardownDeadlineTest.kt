package io.github.sceneview

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
class DeferredTeardownDeadlineTest {
    private class Fixture {
        val teardowns = BackendIdleTeardowns()
        val handler = Handler(Looper.getMainLooper())
        var alive = true
        var gap = 66L
        var runs = 0
        var gone = 0
        var warnings = 0
        var polls = 0
        var releases = 0
        var backendReachesFence = true
        val probe = FreshFenceProbe(
            createFence = { SystemClock.uptimeMillis() },
            isReached = { armedAt: Long ->
                polls++
                backendReachesFence && SystemClock.uptimeMillis() > armedAt
            },
            destroyFence = { _: Long -> releases++ },
            now = SystemClock::uptimeMillis,
            onStillBusy = { warnings++ },
        )

        fun defer() = teardowns.defer(
            isEngineAlive = { alive },
            isBackendBusy = probe::isBusy,
            schedule = { _, block -> handler.postDelayed(block, gap) },
            teardown = {
                assertEquals(Looper.getMainLooper(), Looper.myLooper())
                probe.release()
                runs++
            },
            onEngineGone = { gone++ },
        )

        fun advance(ms: Long) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
        }
    }

    @Test
    fun slowMainLooperNeverRunsTheDestroyAtTheOldDeadline() {
        val f = Fixture()
        f.defer()
        f.advance(12_000)

        assertEquals(0, f.runs)
        assertEquals(1, f.warnings)
        assertEquals(1, f.teardowns.size)

        // Explicit engine cleanup, not a deadline-triggered destroy.
        f.teardowns.runPending()
        f.alive = false
        val polls = f.polls
        val releases = f.releases
        f.advance(1_000)
        f.teardowns.runPending()

        assertEquals(1, f.runs)
        assertEquals(polls, f.polls)
        assertEquals(releases, f.releases)
        assertEquals(0, f.teardowns.size)
    }

    @Test
    fun pollingRecoveryReleasesOnceWithoutAnEngineShutdown() {
        val f = Fixture()
        f.defer()
        f.advance(6_000)
        assertEquals(0, f.runs)

        f.gap = 16
        f.advance(200)
        assertEquals(1, f.runs)
        assertEquals(0, f.teardowns.size)
        f.advance(1_000)
        assertEquals(1, f.runs)
    }

    @Test
    fun aRawEngineDestroyAbandonsTheFenceWithoutCallingJniAgain() {
        val f = Fixture()
        f.defer()
        f.advance(6_000)
        f.alive = false
        val polls = f.polls
        val releases = f.releases
        f.advance(1_000)

        assertEquals(0, f.runs)
        assertEquals(1, f.gone)
        assertEquals(polls, f.polls)
        assertEquals(releases, f.releases)
        assertEquals(0, f.teardowns.size)
    }

    @Test
    fun aBackendThatNeverReachesItsFenceStaysPending() {
        val f = Fixture()
        f.backendReachesFence = false
        f.defer()
        f.advance(12_000)
        assertEquals(0, f.runs)
        assertEquals(1, f.warnings)
        f.alive = false
        f.advance(100)
        assertEquals(1, f.gone)
    }

    @Test
    fun aMissingReplacementFenceIsStillBusy() {
        var now = 0L
        var created = 0
        val probe = FreshFenceProbe(
            createFence = { if (created++ == 0) 1 else null },
            isReached = { _: Int -> now > 0 },
            destroyFence = {},
            now = { now },
        )
        assertTrue(probe.isBusy())
        now = 6_000
        assertTrue(probe.isBusy())
        now = 12_000
        assertTrue(probe.isBusy())
    }
}

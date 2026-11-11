package io.github.sceneview

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import com.google.android.filament.Engine
import com.google.android.filament.Fence
import io.github.sceneview.node.FilamentWithoutJni
import io.github.sceneview.testing.ShadowFilamentEngine
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Production Engine helpers and Handler scheduling, replacing only Filament's native calls. */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(
    instrumentedPackages = ["com.google.android.filament"],
    shadows = [
        DeferredTeardownDeadlineTest.RecordingEngine::class,
        DeferredTeardownDeadlineTest.RecordingFence::class,
    ],
)
class DeferredTeardownDeadlineTest {
    private lateinit var engine: Engine

    @Before
    fun setUp() {
        armed.clear()
        order.clear()
        nextFence = 0L
        polls = 0
        releases = 0
        reachesFence = true
        failCreation = false
        errorNextWait = false
        backendBusyUntil = 0L
        ShadowLog.clear()
        engine = FilamentWithoutJni.engine()
    }

    @After
    fun cleanUp() {
        if (engine.isValid) engine.destroy()
        advance(2_000)
    }

    private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    /** Advance time without dispatching, then deliver late messages as a janking main thread does. */
    private fun latePoll(gap: Long) {
        ShadowSystemClock.advanceBy(Duration.ofMillis(gap))
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun warnCount() = ShadowLog.getLogsForTag("Sceneview").count {
        it.msg.contains("still pending after 5 s")
    }

    @Test
    fun retiringEngineReleasesRendererThenEngineAndEglDespitePermanentlySlowPolls() {
        for (gap in listOf(66L, 100L, 300L)) {
            if (!engine.isValid) engine = FilamentWithoutJni.engine()
            order.clear()
            engine.destroyRendererWhenBackendIdle(engine.createRenderer())
            // Exactly the disposal path used by rememberEngine, with an observable EGL release.
            engine.destroyWhenBackendIdle { order += "egl" }
            repeat(6) { latePoll(gap) }

            assertEquals("poll gap $gap", listOf("renderer", "engine", "egl"), order)
            assertFalse(engine.isValid)
            val finalPolls = polls
            val finalReleases = releases
            latePoll(6_000)
            assertEquals(finalPolls, polls)
            assertEquals(finalReleases, releases)
            assertEquals(3, order.size)
        }
    }

    @Test
    fun retirementWaitsForDecoderCompletionAndCommandsFromItsRelease() {
        var decoding = true
        engine.destroyRendererWhenBackendIdle(engine.createRenderer())
        engine.whenIdle(isBusy = { decoding }) {
            order += "model-loader"
            backendBusyUntil = SystemClock.uptimeMillis() + 500
        }
        engine.destroyWhenBackendIdle { order += "egl" }
        repeat(3) { latePoll(66) }
        assertEquals(listOf("renderer"), order)
        assertTrue(engine.isValid)

        decoding = false
        repeat(4) { latePoll(66) }
        assertEquals(listOf("renderer", "model-loader"), order)
        assertTrue("the model release queued more backend work", engine.isValid)
        repeat(10) { latePoll(66) }
        assertEquals(listOf("renderer", "model-loader", "engine", "egl"), order)
    }

    @Test
    fun sharedEngineNeverGivesUpButCanRecoverAfterThePollingBackoff() {
        engine.destroyRendererWhenBackendIdle(engine.createRenderer())
        repeat(190) { latePoll(66) }
        assertTrue(order.isEmpty())
        assertEquals(1, warnCount())

        // Keep driving the real scheduler: the occasional short confirmation still sees freshness.
        advance(2_100)
        assertEquals(listOf("renderer"), order)
        val finalPolls = polls
        advance(2_000)
        assertEquals(finalPolls, polls)
    }

    @Test
    fun retirementAfterTheWarningDoesNotRequireCadenceRecovery() {
        engine.destroyRendererWhenBackendIdle(engine.createRenderer())
        repeat(95) { latePoll(66) }
        assertEquals(1, warnCount())
        engine.destroyWhenBackendIdle { order += "egl" }
        repeat(40) { latePoll(300) }
        assertEquals(listOf("renderer", "engine", "egl"), order)
    }

    @Test
    fun pendingResourcesShareOneFencePollAndBackOffAfterOneWarning() {
        reachesFence = false
        repeat(20) { engine.whenBackendIdle { order += "resource" } }
        val initialPolls = polls
        advance(BACKEND_IDLE_POLL_MS)
        assertEquals("one JNI wait for the engine, not twenty", initialPolls + 1, polls)
        advance(6_000)
        assertEquals(1, warnCount())
        val afterWarning = polls
        advance(6_000)
        assertTrue("at most two polls per second after warning", polls - afterWarning <= 12)
        assertTrue(order.isEmpty())
    }

    @Test
    fun retiringEngineWithAnUnreachedFenceNeverDestroysAtADeadline() {
        reachesFence = false
        engine.destroyWhenBackendIdle { order += "egl" }
        advance(12_000)
        assertTrue(order.isEmpty())
        assertTrue(engine.isValid)
        assertEquals(1, warnCount())
        reachesFence = true
        latePoll(1_000)
        assertEquals(listOf("engine", "egl"), order)
    }

    @Test
    fun rawEngineDestructionAbandonsItsFenceWithoutAnyFurtherJni() {
        engine.whenBackendIdle(onEngineGone = { order += "gone" }) { order += "unexpected" }
        engine.destroy()
        val finalPolls = polls
        val finalReleases = releases
        advance(1_000)
        assertEquals(listOf("engine", "gone"), order)
        assertEquals(finalPolls, polls)
        assertEquals(finalReleases, releases)
    }

    @Test
    fun erroredFenceIsReplacedAndTheNextReachedFenceReleasesOnce() {
        errorNextWait = true
        engine.whenBackendIdle { order += "resource" }
        assertEquals(2L, nextFence)
        assertEquals(1, releases)
        advance(100)
        assertEquals(listOf("resource"), order)
        assertEquals(2, releases)
    }

    @Test
    fun missingReplacementFenceKeepsDeferringUntilCreationRecovers() {
        engine.whenBackendIdle { order += "resource" }
        failCreation = true
        latePoll(66)
        advance(6_000)
        assertTrue(order.isEmpty())
        failCreation = false
        advance(2_100)
        assertEquals(listOf("resource"), order)
    }

    @Test
    fun offMainLooperLogsAndFinishesExternalResourceReleaseOnItsCallingThread() {
        val worker = HandlerThread("teardown-owner").apply { start() }
        val registered = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        try {
            Handler(worker.looper).post {
                try {
                    // Same point as VideoNode: material instance already destroyed before deferral.
                    order += "material"
                    engine.whenBackendIdle {
                        assertEquals(worker.looper, Looper.myLooper())
                        order += listOf("texture", "stream", "surface")
                        finished.countDown()
                    }
                } catch (error: Throwable) {
                    failure.set(error)
                } finally {
                    registered.countDown()
                }
            }
            assertTrue(registered.await(2, TimeUnit.SECONDS))
            shadowOf(worker.looper).idleFor(Duration.ofMillis(100))
            failure.get()?.let { throw it }
            assertTrue(finished.await(2, TimeUnit.SECONDS))
            assertEquals(listOf("material", "texture", "stream", "surface"), order)
            assertTrue(ShadowLog.getLogsForTag("Sceneview").any { it.msg.contains("off main") })
        } finally {
            worker.quitSafely()
            worker.join(2_000)
        }
    }

    @Test
    fun noLooperLogsAndUsesThePreviousSynchronousFallback() {
        val failure = AtomicReference<Throwable?>()
        val worker = Thread {
            try {
                engine.whenIdle(isBusy = { error("No looper must not poll") }) { deferred ->
                    assertFalse(deferred)
                    order += "custom"
                }
                engine.whenBackendIdle { deferred ->
                    assertFalse(deferred)
                    order += "fence"
                }
            } catch (error: Throwable) {
                failure.set(error)
            }
        }
        worker.start()
        worker.join(2_000)
        assertFalse(worker.isAlive)
        failure.get()?.let { throw it }
        assertEquals(listOf("custom", "fence"), order)
        assertEquals(0, polls)
        assertTrue(ShadowLog.getLogsForTag("Sceneview").any { it.msg.contains("off main") })
    }

    @Implements(Engine::class)
    @Suppress("UnusedParameter")
    class RecordingEngine : ShadowFilamentEngine() {
        companion object {
            @JvmStatic @Implementation
            fun nCreateFence(nativeEngine: Long): Long {
                if (failCreation) return 0L
                return (++nextFence).also { armed[it] = maxOf(SystemClock.uptimeMillis(), backendBusyUntil) }
            }

            @JvmStatic @Implementation
            fun nDestroyFence(nativeEngine: Long, nativeFence: Long): Boolean {
                check(armed.remove(nativeFence) != null)
                releases++
                return true
            }

            @JvmStatic @Implementation
            fun nCreateRenderer(nativeEngine: Long): Long = 1L

            @JvmStatic @Implementation
            fun nDestroyRenderer(nativeEngine: Long, nativeRenderer: Long): Boolean {
                order += "renderer"
                return true
            }

            @JvmStatic @Implementation
            fun nDestroyEngine(nativeEngine: Long) {
                order += "engine"
                armed.clear()
            }
        }
    }

    @Implements(Fence::class)
    @Suppress("UnusedParameter")
    class RecordingFence {
        companion object {
            @JvmStatic @Implementation
            fun nWait(nativeFence: Long, mode: Int, timeout: Long): Int {
                assertEquals(0L, timeout)
                polls++
                if (errorNextWait) {
                    errorNextWait = false
                    return Fence.FenceStatus.ERROR.toFilamentNative()
                }
                val reached = reachesFence && SystemClock.uptimeMillis() > armed.getValue(nativeFence)
                return (if (reached) Fence.FenceStatus.CONDITION_SATISFIED else {
                    Fence.FenceStatus.TIMEOUT_EXPIRED
                }).toFilamentNative()
            }
        }
    }

    companion object {
        private val armed = mutableMapOf<Long, Long>()
        private val order = mutableListOf<String>()
        private var nextFence = 0L
        private var polls = 0
        private var releases = 0
        private var reachesFence = true
        private var failCreation = false
        private var errorNextWait = false
        private var backendBusyUntil = 0L
    }
}

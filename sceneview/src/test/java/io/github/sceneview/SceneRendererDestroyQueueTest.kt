package io.github.sceneview

import android.os.Looper
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.snapshots.Snapshot
import com.google.android.filament.Engine
import com.google.android.filament.Renderer
import com.google.android.filament.View
import io.github.sceneview.node.FilamentWithoutJni
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Exercises the real no-swap-chain render path without a native Filament engine or surface. */
@RunWith(RobolectricTestRunner::class)
@Config(instrumentedPackages = ["com.google.android.filament"])
class SceneRendererDestroyQueueTest {
    private lateinit var engine: Engine
    private lateinit var queue: EngineDestroyQueue

    @Before
    fun setUp() {
        engine = FilamentWithoutJni.engine()
        queue = EngineDestroyQueue.of(engine)
    }

    @After
    fun tearDown() {
        queue.drainAll()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun onDemandWithoutASwapChainDrainsAndParksAgain() = runTest {
        val sceneRenderer = renderer()
        val pending = derivedStateOf { queue.hasPending }
        var ticks = 0
        var destroyed = 0
        var updates = 0
        val loop = launch {
            while (true) {
                awaitRenderingEnabled(pending)
                ticks++
                assertFalse(sceneRenderer.renderFrame(
                    frameTimeNanos = ticks * 16_000_000L,
                    shouldPresent = { error("no swap chain: presentation must be skipped") },
                    onBeforeRender = { updates++ },
                ))
                delay(16) // Keep a stranded queue bounded so the regression fails, not hangs.
            }
        }
        try {
            runCurrent()
            assertEquals(0, ticks)
            assertFalse(sceneRenderer.isAttached)
            repeat(2) { batch ->
                queue.enqueueAction { destroyed++ }
                Snapshot.sendApplyNotifications()
                advanceTimeBy(5_000)
                runCurrent()

                assertEquals("a surface-less loop stranded a destroy", batch + 1, destroyed)
                assertEquals(
                    "the loop must drain its grace frames then park",
                    (batch + 1) * EngineDestroyQueue.GRACE_FRAMES,
                    ticks,
                )
                assertEquals(0, queue.size)
                assertFalse(queue.hasPending)
                assertEquals(0, updates)
                assertEquals(0L, sceneRenderer.presentedFrameCount)
            }
        } finally {
            loop.cancel()
        }
    }

    @Test
    fun surfaceLessRenderersSharingAnEngineKeepTheFullGracePeriod() {
        val first = renderer()
        val second = renderer()
        var destroyed = 0
        queue.enqueueAction { destroyed++ }

        repeat(EngineDestroyQueue.GRACE_FRAMES - 1) {
            assertFalse(first.renderFrame(0L) {})
            assertFalse(second.renderFrame(0L) {})
            assertEquals("two renderers must not shorten the grace period", 0, destroyed)
        }
        assertFalse(first.renderFrame(0L) {})
        assertEquals(1, destroyed)
        assertFalse(queue.hasPending)
    }

    /**
     * The shared default engine is never torn down, so a batch left by the last scene waits for
     * the next scene's frames: draining it with no renderer would skip the grace period, and a
     * caller-owned MaterialInstance that outlived its scene could still sample the texture.
     */
    @Test
    fun resourcesLeftByTheLastSceneWaitForTheNextScenesGraceFrames() {
        val scene = renderer()
        var released = 0
        queue.enqueueAction { released++ }
        scene.destroy()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, released)
        val next = renderer()
        repeat(EngineDestroyQueue.GRACE_FRAMES - 1) { next.renderFrame(0L) {} }
        assertEquals(0, released)
        next.renderFrame(0L) {}
        assertEquals(1, released)
        next.destroy()
    }

    @Test
    fun ownedEngineKeepsResourcesQueuedUntilFramesOrEngineTeardown() {
        val scene = renderer()
        var released = 0
        queue.enqueueAction { released++ }
        scene.destroy()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, released)
        assertEquals(1, queue.size)
        queue.drainAll()
        assertEquals(1, released)
    }

    private fun renderer(): SceneRenderer {
        val view = View::class.java.getDeclaredConstructor(Long::class.javaPrimitiveType)
            .apply { isAccessible = true }.newInstance(1L)
        val renderer = Renderer::class.java
            .getDeclaredConstructor(Long::class.javaPrimitiveType, Engine::class.java)
            .apply { isAccessible = true }.newInstance(1L, engine)
        return SceneRenderer(engine, view, renderer)
    }
}

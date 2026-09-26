package io.github.sceneview.node

import android.graphics.SurfaceTexture
import android.view.Surface
import io.github.sceneview.DeferredDestroyQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins the fix for sceneview/sceneview#3734: [ViewNode.destroy] must release its `Surface`/
 * `SurfaceTexture` only AFTER the Filament [com.google.android.filament.Stream] reading from
 * them has actually been destroyed.
 *
 * `Stream`/`Texture` destruction is deferred by [io.github.sceneview.EngineDestroyQueue] across
 * [io.github.sceneview.EngineDestroyQueue.GRACE_FRAMES] rendered frames (#874) — releasing the
 * Surface/SurfaceTexture eagerly, instead of enqueuing it on the same queue, would free them out
 * from under a still-live Stream.
 *
 * A real [com.google.android.filament.Engine]/[com.google.android.filament.Stream] cannot be
 * constructed on the JVM (native JNI), so this exercises the exact FIFO enqueue order
 * `ViewNode.destroy()` uses — texture, then stream, then the surface/surfaceTexture release —
 * against [DeferredDestroyQueue], the Filament-free core [io.github.sceneview.EngineDestroyQueue]
 * is a thin binding over. Real `android.graphics.SurfaceTexture`/`android.view.Surface` instances
 * (via Robolectric) stand in for the Filament resources so the release itself is observable
 * (`Surface.isValid`), not just a logged label.
 */
@RunWith(RobolectricTestRunner::class)
class ViewNodeDestroyOrderTest {

    @Test
    fun surfaceIsNotReleasedBeforeItsGraceFramesElapse() {
        val queue = DeferredDestroyQueue(graceFrames = 3)
        val log = mutableListOf<String>()
        val surfaceTexture = SurfaceTexture(0).also { it.detachFromGLContext() }
        val surface = Surface(surfaceTexture)

        // Mirrors ViewNode.destroy()'s enqueue order: texture, then stream, then the
        // surface/surfaceTexture release that must not run before the stream destroy.
        queue.enqueue { log.add("texture") }
        queue.enqueue { log.add("stream") }
        queue.enqueue {
            surface.release()
            surfaceTexture.release()
            log.add("release")
        }

        assertTrue("surface must start out valid", surface.isValid)

        queue.drain() // frame 1
        queue.drain() // frame 2
        assertTrue(
            "surface must not be released before its grace period elapses",
            surface.isValid
        )
        assertTrue("nothing must have run yet", log.isEmpty())

        queue.drain() // frame 3 — grace period elapsed, all three run in FIFO order
        assertEquals(
            "the release must run strictly after the stream destroy, preserving FIFO order",
            listOf("texture", "stream", "release"),
            log
        )
        assertFalse("surface must be released once its grace period has elapsed", surface.isValid)
    }

    @Test
    fun releaseNeverRunsBeforeTheStreamDestroyEvenIfEnqueuedOutOfOrderInTime() {
        // Regression shape for the bug itself: if the release were called eagerly inside
        // destroy() (the pre-fix behavior) instead of being enqueued on the same deferred queue,
        // it would run on frame 0 — strictly before the queued stream destroy at frame 3. Pinning
        // that the release log entry never precedes "stream" is the actual contract #3734 needs.
        val queue = DeferredDestroyQueue(graceFrames = 3)
        val log = mutableListOf<String>()
        val surfaceTexture = SurfaceTexture(0).also { it.detachFromGLContext() }
        val surface = Surface(surfaceTexture)

        queue.enqueue { log.add("stream") }
        queue.enqueue {
            surface.release()
            surfaceTexture.release()
            log.add("release")
        }

        repeat(3) { queue.drain() }

        val streamIndex = log.indexOf("stream")
        val releaseIndex = log.indexOf("release")
        assertTrue("both actions must have run", streamIndex >= 0 && releaseIndex >= 0)
        assertTrue(
            "release must never precede the stream destroy (#3734)",
            releaseIndex > streamIndex
        )
    }
}

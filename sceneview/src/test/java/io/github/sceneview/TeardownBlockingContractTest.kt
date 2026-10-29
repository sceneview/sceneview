package io.github.sceneview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source contract for the teardown paths field ANR reports caught blocking the main thread in
 * Filament fence waits. The waits themselves are native; what JVM tests can pin is that SceneView
 * no longer asks for them.
 *
 * What it protects: the engine built without the GPU frame-complete metric, both deferred
 * teardowns (renderer and engine, cancelled async loads) polling a fresh fence, and
 * `VideoNode.destroy` draining only on a thread without a looper. It matches source text
 * (whitespace-collapsed), so it is deliberately brittle: renaming or reformatting one of the
 * matched calls fails it, and the matched strings must then be updated along with the code — after
 * checking the new code still does what the assertion message says.
 */
class TeardownBlockingContractTest {

    private fun source(path: String) =
        File("src/main/java/io/github/sceneview/$path").readText().replace(Regex("\\s+"), " ")

    /**
     * `Engine.destroyRenderer` joins the frame-info job thread, parked in
     * `eglClientWaitSyncKHR(FOREVER)` on the last frame's GPU fence unless the engine was built
     * without the GPU frame-complete metric. The key is matched by `FeatureFlagManager` as a
     * string: a typo would silently keep the join.
     */
    @Test
    fun `the engine is built without the GPU frame-complete metric`() {
        assertEquals("engine.frame_info.disable_gpu_complete_metric", DISABLE_GPU_COMPLETE_METRIC)
        val factories = source("SceneFactories.kt")
        assertTrue(
            "createEngine must set the flag",
            factories.contains(".feature(DISABLE_GPU_COMPLETE_METRIC, true)"),
        )
        assertFalse(
            "createEngine must not fall back to Engine.create",
            factories.contains("fun createEngine(eglContext: EGLContext): Engine = Engine.create("),
        )
    }

    /** Both deferred teardowns poll a fresh fence, not one created once at the first poll. */
    @Test
    fun `deferred teardowns poll fresh fences`() {
        val engine = source("Engine.kt")
        assertTrue(engine.contains("val probe = freshFenceProbe()"))
        assertTrue(engine.contains("isBusy = probe::isBusy"))
        assertFalse(engine.contains("isBusy = { isFenceBusy(fence) }"))

        val modelLoader = source("loaders/ModelLoader.kt")
        assertTrue(modelLoader.contains("isBackendBusy = probe::isBusy"))
        assertFalse(modelLoader.contains("engine.createFence().also { fence = it }"))
    }

    /** `VideoNode.destroy` drains the backend only where it has no looper to poll from. */
    @Test
    fun `video node destroy defers its external texture instead of draining`() {
        val video = source("node/VideoNode.kt")
        val destroy = video.substringAfter("override fun destroy()")
        assertTrue(destroy.contains("engine.whenBackendIdle("))
        val drain = destroy.indexOf("drainFramePipeline()")
        val noLooper = destroy.indexOf("Looper.myLooper() == null")
        assertTrue("the drain must stay behind the no-looper check", noLooper in 0 until drain)
        assertEquals("one drain only", drain, destroy.lastIndexOf("drainFramePipeline()"))
    }
}

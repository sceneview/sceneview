package io.github.sceneview

import android.content.ComponentName
import android.content.pm.PackageManager
import android.opengl.EGL14
import android.os.Looper
import android.os.SystemClock
import androidx.startup.InitializationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.sceneview.utils.destroy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [EngineWarmUp] prepares the shared EGL context on a background thread; the thread that builds
 * the engine takes it. On a real GPU driver: whether EGL lets a context created and released on one
 * thread be made current on another, and whether Filament builds an engine on it.
 */
@RunWith(AndroidJUnit4::class)
class EngineWarmUpTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
        }
    }

    private fun awaitReady() {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (!EngineWarmUp.isReady) {
            check(SystemClock.uptimeMillis() < deadline) { "the warm-up never prepared a context" }
            SystemClock.sleep(5)
        }
    }

    @Test
    fun the_manifest_runs_the_warm_up_at_app_startup() {
        val provider = context.packageManager.getProviderInfo(
            ComponentName(context, InitializationProvider::class.java),
            PackageManager.GET_META_DATA,
        )
        assertEquals(
            "androidx.startup",
            provider.metaData?.getString(EngineWarmUpInitializer::class.java.name),
        )
    }

    @Test
    fun the_prepared_context_is_handed_over_current_and_builds_an_engine() {
        EngineWarmUp.start()
        awaitReady()
        runOnMain {
            val eglContext = createEglContext()
            assertFalse("a prepared context is handed over once", EngineWarmUp.isReady)
            assertEquals(eglContext, EGL14.eglGetCurrentContext())

            val engine = createEngine(eglContext)
            val renderer = createRenderer(engine)
            val view = createView(engine)
            val swapChain = engine.createSwapChain(16, 16, 0)
            val scene = engine.createScene()
            view.scene = scene
            if (renderer.beginFrame(swapChain, 0L)) {
                renderer.render(view)
                renderer.endFrame()
            }
            engine.flushAndWait()
            engine.destroySwapChain(swapChain)
            engine.destroyView(view)
            engine.destroyScene(scene)
            engine.destroyRenderer(renderer)
            engine.destroy()
            eglContext.destroy()
        }
    }

    @Test
    fun start_prepares_a_new_context_after_one_was_taken() {
        EngineWarmUp.start()
        awaitReady()
        runOnMain { createEglContext().destroy() }
        assertFalse(EngineWarmUp.isReady)

        EngineWarmUp.start()
        awaitReady()
        assertTrue(EngineWarmUp.isReady)
        runOnMain { createEglContext().destroy() }
    }
}

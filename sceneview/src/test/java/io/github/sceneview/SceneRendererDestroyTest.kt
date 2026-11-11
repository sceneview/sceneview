package io.github.sceneview

import android.graphics.SurfaceTexture
import android.view.Surface
import com.google.android.filament.Engine
import com.google.android.filament.Renderer
import com.google.android.filament.SwapChain
import com.google.android.filament.View
import com.google.android.filament.android.UiHelper
import io.github.sceneview.node.FilamentWithoutJni
import io.github.sceneview.testing.ShadowFilamentEngine
import io.github.sceneview.utils.SurfaceMirrorer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowLog
import java.util.concurrent.atomic.AtomicReference

/** Exercises real destroy/callback wiring with already-created swap chains and no Filament JNI. */
@RunWith(RobolectricTestRunner::class)
@Config(
    instrumentedPackages = ["com.google.android.filament"],
    shadows = [SceneRendererDestroyTest.RecordingEngine::class],
)
class SceneRendererDestroyTest {
    private lateinit var sceneRenderer: SceneRenderer
    private lateinit var mirrorer: SurfaceMirrorer
    private lateinit var backend: RecordingEngine
    private val calls = mutableListOf<String>()

    @Before
    fun setUp() {
        val engine = FilamentWithoutJni.engine()
        backend = Shadow.extract(engine)
        val view = View::class.java.getDeclaredConstructor(Long::class.javaPrimitiveType)
            .apply { isAccessible = true }.newInstance(1L)
        val renderer = Renderer::class.java
            .getDeclaredConstructor(Long::class.javaPrimitiveType, Engine::class.java)
            .apply { isAccessible = true }.newInstance(1L, engine)
        sceneRenderer = SceneRenderer(engine, view, renderer)

        val surface = Surface(SurfaceTexture(0))
        fun chain() = SwapChain::class.java
            .getDeclaredConstructor(Long::class.javaPrimitiveType, Any::class.java)
            .apply { isAccessible = true }.newInstance(1L, surface)
        val mirrorChain = chain()
        val windowChain = chain()
        mirrorer = SurfaceMirrorer().apply { startMirroring(surface) }
        field(mirrorer, "engine").set(mirrorer, engine)
        val mirrors = field(mirrorer, "surfaceMirrors").get(mirrorer) as List<*>
        val mirror = mirrors.single()!!
        field(mirror, "swapChain").set(mirror, mirrorChain)
        sceneRenderer.surfaceMirrorer = mirrorer

        @Suppress("UNCHECKED_CAST")
        val window = field(sceneRenderer, "swapChainRef").get(sceneRenderer)
            as AtomicReference<SwapChain?>
        window.set(windowChain)
        // Use the production callback and UiHelper.detach(), without attaching a native window.
        val callback = SceneRenderer::class.java.getDeclaredMethod(
            "makeRendererCallback", Function0::class.java, Boolean::class.javaPrimitiveType,
        ).apply { isAccessible = true }.invoke(sceneRenderer, { 1 }, false)
            as UiHelper.RendererCallback
        val uiHelper = field(sceneRenderer, "uiHelper").get(sceneRenderer) as UiHelper
        uiHelper.renderCallback = callback
        backend.onDestroy = { calls += if (it === mirrorChain) "mirror" else "window" }
    }

    @Test
    fun destroyCreatesOneScopedBudgetAndRepeatedDestroyDoesNotWait() {
        val budgets = mutableListOf<SurfaceDetachBudget?>()
        backend.onWait = {
            budgets += field(sceneRenderer, "surfaceDetachBudget").get(sceneRenderer)
                as SurfaceDetachBudget?
            true
        }

        sceneRenderer.destroy()
        sceneRenderer.destroy()

        assertEquals(listOf("mirror", "window"), calls)
        assertEquals(2, budgets.size)
        assertNotNull(budgets[0])
        assertSame(budgets[0], budgets[1])
        assertNull(field(sceneRenderer, "surfaceDetachBudget").get(sceneRenderer))
    }

    @Test
    fun slowHostLossDoesNotConsumeTheWindowWaitAfterAHealthyMirrorWait() {
        destroyWithWait(mirrorWaitNanos = 750_000_000L, mirrorCaughtUp = true)
        assertEquals(emptyList<String>(), warnings("SurfaceMirrorer"))
        assertEquals(
            listOf("Surface detached, backend still busy after 250 ms; not waiting longer (#3799)"),
            warnings("SceneRenderer"),
        )
    }

    @Test
    fun exhaustedBudgetStillFlushesTheWindowAndReportsZeroWait() {
        destroyWithWait(mirrorWaitNanos = SURFACE_DETACH_WAIT_NANOS, mirrorCaughtUp = false)
        assertEquals(
            listOf("Surface detached, backend still busy after 0 ms; not waiting longer (#3799)"),
            warnings("SceneRenderer"),
        )
    }

    @Test
    fun mirrorWarningReportsItsActualWait() {
        destroyWithWait(mirrorWaitNanos = 25_000_000L, mirrorCaughtUp = false)
        assertEquals(
            listOf("Mirroring stopped, backend still busy after 25 ms; not waiting longer"),
            warnings("SurfaceMirrorer"),
        )
    }

    private fun destroyWithWait(mirrorWaitNanos: Long, mirrorCaughtUp: Boolean) {
        var now = 0L
        val budget = SurfaceDetachBudget(nanoTime = { now })
        field(sceneRenderer, "surfaceDetachBudget").set(sceneRenderer, budget)
        mirrorer.onHostLost = {
            calls += "host lost"
            now += 10 * SURFACE_DETACH_WAIT_NANOS
        }
        val timeouts = mutableListOf<Long>()
        backend.onWait = { timeout ->
            timeouts += timeout
            calls += "wait"
            now += if (timeouts.size == 1) mirrorWaitNanos else timeout
            timeouts.size == 1 && mirrorCaughtUp
        }

        sceneRenderer.destroy()

        // destroy() and clearing the host both notify onHostLost; neither consumes wait time.
        assertEquals(listOf("mirror", "wait", "host lost", "host lost", "window", "wait"), calls)
        assertEquals(listOf(SURFACE_DETACH_WAIT_NANOS, SURFACE_DETACH_WAIT_NANOS - mirrorWaitNanos), timeouts)
        assertSame(budget, field(sceneRenderer, "surfaceDetachBudget").get(sceneRenderer))
    }

    private fun warnings(tag: String) = ShadowLog.getLogsForTag(tag).map { it.msg }

    private fun field(target: Any, name: String) = target.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }

    @Implements(Engine::class)
    class RecordingEngine : ShadowFilamentEngine() {
        var onDestroy: (SwapChain) -> Unit = {}
        var onWait: (Long) -> Boolean = { true }

        @Implementation
        fun destroySwapChain(swapChain: SwapChain) = onDestroy(swapChain)

        @Implementation
        fun flushAndWait(timeoutNanos: Long): Boolean = onWait(timeoutNanos)
    }
}

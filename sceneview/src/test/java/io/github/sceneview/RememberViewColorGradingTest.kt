package io.github.sceneview

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import com.google.android.filament.ColorGrading
import com.google.android.filament.Engine
import com.google.android.filament.ToneMapper
import com.google.android.filament.View
import io.github.sceneview.node.FilamentWithoutJni
import io.github.sceneview.testing.ShadowFilamentEngine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject

/**
 * `rememberView` / `rememberARView` on an engine that outlives the screen (#5232).
 *
 * `Engine.destroyView` leaves the view's [ColorGrading] (a 3D LUT) alive. When the engine is torn
 * down with the screen its own teardown reclaims it; on a hoisted, shared or app-level engine each
 * exit used to leave the grading `createView` / `createARView` built. The engine here is created
 * once, outside the composition, and every visit is one remember/forget cycle.
 *
 * Filament without JNI (Robolectric): the recording engine hands out real `View` and
 * `ColorGrading` Java objects and logs what is destroyed, in order.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(
    instrumentedPackages = ["com.google.android.filament"],
    shadows = [
        RememberViewColorGradingTest.RecordingEngine::class,
        RememberViewColorGradingTest.RecordingGradingBuilder::class,
    ],
)
class RememberViewColorGradingTest {

    private lateinit var engine: Engine

    @Before
    fun hoistTheEngine() {
        engine = FilamentWithoutJni.engine()
        created.clear()
        destroyed.clear()
        sdkColorGradings.clear()
    }

    @Test
    fun `a rememberView visit leaves no SDK grading behind`() = runTest {
        val view = visit { rememberView(engine) }
        val sdkGrading = view.colorGrading
        assertNotNull(sdkGrading)

        // The view first, then its grading: never a grading under a live view.
        assertEquals(listOf<Any>(view, sdkGrading!!), destroyed)
        assertTrue(sdkColorGradings.isEmpty())
    }

    @Test
    fun `a rememberARView visit leaves no SDK grading behind`() = runTest {
        val view = visit { rememberARView(engine) }

        assertEquals(listOf<Any>(view, created.single()), destroyed)
        assertTrue(sdkColorGradings.isEmpty())
    }

    @Test
    fun `twenty visits on one engine free every grading they made`() = runTest {
        repeat(VISITS) { index ->
            if (index % 2 == 0) visit { rememberView(engine) } else visit { rememberARView(engine) }
        }

        assertEquals(VISITS, created.size)
        assertEquals(VISITS, destroyed.count { it is View })
        assertEquals(created, destroyed.filterIsInstance<ColorGrading>())
        assertTrue(sdkColorGradings.isEmpty())
    }

    @Test
    fun `a grading the app set is the app's, the one it replaced is still freed`() = runTest {
        // An app grading, shared between views and destroyed by the app after them.
        val appGrading = grading()
        var replaced: ColorGrading? = null
        val view = visit {
            rememberView(engine) {
                createView(engine).apply {
                    replaced = colorGrading
                    colorGrading = appGrading
                }
            }
        }

        assertEquals(listOf<Any>(view, replaced!!), destroyed)
        assertFalse(appGrading in destroyed)
    }

    @Test
    fun `a view from another factory is destroyed alone`() = runTest {
        val appGrading = grading()
        val view = visit { rememberView(engine) { engine.createView().apply { colorGrading = appGrading } } }

        assertEquals(listOf<Any>(view), destroyed)
    }

    @Test
    fun `a grading the app already freed is not freed twice`() = runTest {
        // An app that worked around #5232 before the fix: it frees `view.colorGrading` itself.
        val view = visit {
            rememberView(engine).also { view ->
                DisposableEffect(view) { onDispose { engine.destroyColorGrading(view.colorGrading!!) } }
            }
        }

        assertEquals(1, destroyed.count { it is ColorGrading })
        assertEquals(view, destroyed.last())
    }

    @Test
    fun `an engine destroyed before the view leaves throws nothing`() = runTest {
        // The engine went first: its own teardown reclaimed the view and the grading.
        visit(beforeExit = { destroyEngine() }) { rememberView(engine) }

        assertTrue(destroyed.isEmpty())
        assertTrue(sdkColorGradings.isEmpty())
    }

    /** What `Engine.destroy` does to the Java side of the engine. */
    private fun destroyEngine() {
        Engine::class.java.getDeclaredMethod("clearNativeObject")
            .apply { isAccessible = true }
            .invoke(engine)
    }

    private fun grading() = ColorGrading.Builder().build(engine)

    /** Composes [content] on its own, then removes it: one visit of a screen. */
    private fun TestScope.visit(beforeExit: () -> Unit = {}, content: @Composable () -> View): View {
        val clock = BroadcastFrameClock()
        var shown by mutableStateOf(true)
        var view: View? = null
        val recomposer = Recomposer(backgroundScope.coroutineContext + clock)
        backgroundScope.launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val composition = Composition(NoNodes(), recomposer)
        composition.setContent { if (shown) view = content() }
        settle(clock)
        assertTrue("The view is alive while shown", destroyed.none { it === view })
        beforeExit()
        shown = false
        settle(clock)
        composition.dispose()
        recomposer.cancel()
        return view!!
    }

    private var frameTime = 0L

    private fun TestScope.settle(clock: BroadcastFrameClock) {
        flush()
        clock.sendFrame(frameTime++)
        flush()
    }

    private fun TestScope.flush() = repeat(2) {
        Snapshot.sendApplyNotifications()
        runCurrent()
    }

    private class NoNodes : AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(index: Int, instance: Unit) = Unit
        override fun insertBottomUp(index: Int, instance: Unit) = Unit
        override fun remove(index: Int, count: Int) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun onClear() = Unit
    }

    /** Hands out views and logs every view and grading destroyed. */
    @Implements(Engine::class)
    class RecordingEngine : ShadowFilamentEngine() {
        @RealObject
        private lateinit var engine: Engine

        @Implementation
        fun createView(): View = View::class.java.getDeclaredConstructor(Long::class.javaPrimitiveType)
            .apply { isAccessible = true }.newInstance(nextHandle++)

        @Implementation
        fun destroyView(view: View) {
            // Filament reads the engine's handle first: a destroyed engine throws here.
            engine.nativeObject
            destroyed += view
        }

        @Implementation
        fun destroyColorGrading(colorGrading: ColorGrading) {
            // What Filament's own call does to the Java objects: a destroyed engine, or a second
            // call on the same grading, throws from here.
            engine.nativeObject
            colorGrading.nativeObject
            destroyed += colorGrading
            ColorGrading::class.java.getDeclaredMethod("clearNativeObject")
                .apply { isAccessible = true }.invoke(colorGrading)
        }
    }

    /** Builds gradings with a live handle; [created] lists the ones `createView` asked for. */
    @Implements(ColorGrading.Builder::class)
    class RecordingGradingBuilder {
        @RealObject
        private lateinit var builder: ColorGrading.Builder

        /** A tone mapper without JNI has no handle to pass on: nothing to set. */
        @Implementation
        fun toneMapper(@Suppress("UNUSED_PARAMETER") toneMapper: ToneMapper): ColorGrading.Builder = builder

        @Implementation
        fun build(@Suppress("UNUSED_PARAMETER") engine: Engine): ColorGrading =
            ColorGrading::class.java.getDeclaredConstructor(Long::class.javaPrimitiveType)
                .apply { isAccessible = true }.newInstance(nextHandle++)
                .also { grading ->
                    val sdk = Throwable().stackTrace.any {
                        it.methodName == "createView" || it.methodName == "createARView"
                    }
                    if (sdk) created += grading
                }
    }

    private companion object {
        const val VISITS = 20
        var nextHandle = 1L
        val created = mutableListOf<ColorGrading>()
        val destroyed = mutableListOf<Any>()
    }
}

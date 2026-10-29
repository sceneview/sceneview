package io.github.sceneview

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import com.google.android.filament.Engine
import com.google.android.filament.gltfio.FilamentAsset
import io.github.sceneview.model.ModelInstance
import io.github.sceneview.node.FilamentWithoutJni
import io.github.sceneview.node.ModelNode
import io.github.sceneview.node.ModelNodeAfterDestroyTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The [SceneScope.ModelNode] composable over a model the app destroyed.
 *
 * An app destroys a model (`ModelLoader.destroyModel` or `clear`) and a recomposition then reaches
 * the composable: a re-keyed `remember` builds a node over the destroyed model, and a changed
 * `animationName` starts an animation on it. Both used to throw from inside the library's own
 * composable — or, before the node checked its model, read freed memory.
 *
 * The composable runs in a bare [Composition] over a Filament without JNI (Robolectric): the
 * Compose UI test rule cannot host it, see `ModelNodeCenterOriginTest`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(
    instrumentedPackages = ["com.google.android.filament"],
    shadows = [
        ModelNodeAfterDestroyTest.RecordingAsset::class,
        ModelNodeAfterDestroyTest.RecordingAnimator::class,
    ]
)
class ModelNodeComposableAfterDestroyTest {

    private lateinit var engine: Engine

    @Before
    fun createEngineWithoutJni() {
        engine = FilamentWithoutJni.engine()
        ModelNodeAfterDestroyTest.nativeCalls.clear()
    }

    @Test
    fun `a destroyed model shows nothing and throws nothing`() = runTest {
        val destroyed = FilamentWithoutJni.modelInstance(engine).also { destroy(it) }
        val host = Host(this, engine, destroyed)

        assertTrue(host.scope._nodes.isEmpty())
        assertEquals(emptyList<String>(), ModelNodeAfterDestroyTest.nativeCalls)
    }

    @Test
    fun `a re-key onto a destroyed model removes the node`() = runTest {
        val host = Host(this, engine, FilamentWithoutJni.modelInstance(engine))
        val shown = host.scope._nodes.single() as ModelNode

        host.instance = FilamentWithoutJni.modelInstance(engine).also { destroy(it) }
        host.settle()

        assertTrue(host.scope._nodes.isEmpty())
        assertTrue(shown.isDestroyed)
    }

    @Test
    fun `a new animation name on a destroyed model starts nothing`() = runTest {
        val host = Host(this, engine, FilamentWithoutJni.modelInstance(engine), animation = "walk")
        val shown = host.scope._nodes.single() as ModelNode
        assertTrue(shown.playingAnimations.isNotEmpty())

        destroy(shown.modelInstance)
        ModelNodeAfterDestroyTest.nativeCalls.clear()
        host.animation = "run"
        host.settle()

        assertTrue(host.scope._nodes.isEmpty())
        assertEquals(emptyList<String>(), ModelNodeAfterDestroyTest.nativeCalls)
    }

    /** What `AssetLoader.destroyAsset` does to the Java side of the asset. */
    private fun destroy(instance: ModelInstance) {
        FilamentAsset::class.java.getDeclaredMethod("clearNativeObject")
            .apply { isAccessible = true }
            .invoke(instance.asset)
    }

    private class Host(
        private val test: TestScope,
        engine: Engine,
        instance: ModelInstance,
        animation: String? = null,
    ) {
        private val clock = BroadcastFrameClock()
        var instance by mutableStateOf(instance)
        var animation by mutableStateOf(animation)
        val scope = SceneScope(
            engine = engine,
            modelLoader = withoutJni(),
            materialLoader = withoutJni(),
            environmentLoader = withoutJni(),
            _nodes = mutableStateListOf(),
        )

        init {
            val recomposer = Recomposer(test.backgroundScope.coroutineContext + clock)
            test.backgroundScope.launch(clock) { recomposer.runRecomposeAndApplyChanges() }
            Composition(NoNodes(), recomposer).setContent {
                scope.ModelNode(
                    modelInstance = this.instance,
                    autoAnimate = false,
                    animationName = this.animation,
                    scaleToUnits = null,
                )
            }
            settle()
        }

        private var frameTime = 0L

        /** One frame: pending state writes are recomposed, and their effects applied. */
        fun settle() {
            flush()
            clock.sendFrame(frameTime++)
            flush()
        }

        private fun flush() = repeat(2) {
            Snapshot.sendApplyNotifications()
            test.runCurrent()
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

/** A loader the composable under test never calls: only its type is needed. */
private inline fun <reified T> withoutJni(): T {
    val unsafeClass = Class.forName("sun.misc.Unsafe")
    val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
    return unsafeClass.getMethod("allocateInstance", Class::class.java)
        .invoke(unsafe, T::class.java) as T
}

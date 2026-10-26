package io.github.sceneview.node

import com.google.android.filament.Box
import com.google.android.filament.Engine
import com.google.android.filament.gltfio.Animator
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.FilamentAsset
import io.github.sceneview.ExperimentalSceneViewApi
import io.github.sceneview.SceneFrameDispatch
import io.github.sceneview.components.RenderableComponent
import io.github.sceneview.model.Model
import io.github.sceneview.safeDestroyModel
import io.github.sceneview.utils.snapshotNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/**
 * A [ModelNode] whose model was destroyed under it.
 *
 * `rememberModelInstance` destroys the model when it leaves the composition, and the node that
 * shows it can detach later, from a child composition: frames, coroutines and disposals keep
 * reaching the node in between. gltfio zeroes the asset's handle on destroy but not the
 * instance's nor the animator's, so every such call used to end in native code on freed or null
 * memory — a `SIGSEGV` no `try` can catch.
 *
 * What the node does now: the library's own calls (a frame, a disposal, a second destroy, a scene
 * snapshot) do nothing, and an app's calls fail with a Kotlin exception that names the cause.
 *
 * Real `ModelNode`s over a Filament without JNI (Robolectric). The model is destroyed the way
 * `AssetLoader.destroyAsset` leaves it: `clearNativeObject()`. The shadows record every call
 * that would reach native code on the asset or its animator.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    instrumentedPackages = ["com.google.android.filament"],
    shadows = [
        ModelNodeAfterDestroyTest.RecordingAsset::class,
        ModelNodeAfterDestroyTest.RecordingAnimator::class,
        ModelNodeAfterDestroyTest.RecordingAssetLoader::class,
    ]
)
class ModelNodeAfterDestroyTest {

    private lateinit var engine: Engine
    private val dispatch = SceneFrameDispatch()
    private var frameTimeNanos = System.nanoTime() + 1_000_000_000L

    @Before
    fun createEngineWithoutJni() {
        engine = FilamentWithoutJni.engine()
        nativeCalls.clear()
    }

    private fun modelNode() = ModelNode(FilamentWithoutJni.modelInstance(engine))

    private fun frame(roots: List<Node>) {
        frameTimeNanos += 16_000_000L
        dispatch.dispatch(roots, frameTimeNanos)
    }

    /** What `AssetLoader.destroyAsset` does to the Java side of the asset. */
    private fun destroy(model: Model) {
        FilamentAsset::class.java.getDeclaredMethod("clearNativeObject")
            .apply { isAccessible = true }
            .invoke(model)
    }

    private fun assertDestroyedModelError(call: () -> Unit) {
        val error = assertThrows(IllegalStateException::class.java) { call() }
        assertTrue(error.message.orEmpty(), "destroyed" in error.message.orEmpty())
    }

    @Test
    fun `a frame given after the model was destroyed reaches no native object`() {
        val node = modelNode()
        val roots = listOf<Node>(node)
        node.playingAnimations[0] = ModelNode.PlayingAnimation(loop = true)
        frame(roots)
        // The frame does reach the asset and the animator while the model lives.
        assertTrue(nativeCalls.isNotEmpty())

        destroy(node.model)
        nativeCalls.clear()
        frame(roots)
        frame(roots)

        assertEquals(emptyList<String>(), nativeCalls)
        // And the node stops asking for frames: nothing is left to animate.
        assertEquals(0, dispatch.stepCount)
        assertTrue(node.playingAnimations.isEmpty())
    }

    @Test
    fun `the bounds of a destroyed model are a clear error, not a native read`() {
        val node = modelNode()
        destroy(node.model)
        nativeCalls.clear()

        assertDestroyedModelError { node.boundingBox }
        assertDestroyedModelError { node.center }
        assertDestroyedModelError { node.halfExtent }
        assertDestroyedModelError { node.extents }
        assertDestroyedModelError { node.size }
        assertEquals(emptyList<String>(), nativeCalls)
    }

    @Test
    fun `starting an animation on a destroyed model is a clear error`() {
        val node = modelNode()
        destroy(node.model)
        nativeCalls.clear()

        assertDestroyedModelError { node.animationCount }
        assertDestroyedModelError { node.playAnimation(0) }
        assertDestroyedModelError { node.playAnimation("walk") }
        assertDestroyedModelError { node.setAnimationSpeed(0, 2f) }
        assertDestroyedModelError { node.setAnimationSpeed("walk", 2f) }
        assertEquals(emptyList<String>(), nativeCalls)
    }

    @Test
    fun `stopping an animation of a destroyed model does nothing`() {
        // The `ModelNode` composable stops its named animation on dispose, which can come after
        // `rememberModelInstance` destroyed the model.
        val node = modelNode()
        node.playingAnimations[0] = ModelNode.PlayingAnimation()
        destroy(node.model)
        nativeCalls.clear()

        node.stopAnimation("walk")
        node.stopAnimation(0)

        assertEquals(emptyList<String>(), nativeCalls)
    }

    @Test
    fun `a ModelNode cannot be built over a destroyed model`() {
        val instance = FilamentWithoutJni.modelInstance(engine)
        destroy(instance.asset)

        assertDestroyedModelError { ModelNode(instance) }
    }

    @Test
    fun `destroying a destroyed model again touches nothing`() {
        val node = modelNode()
        destroy(node.model)
        nativeCalls.clear()

        assetLoaderWithoutJni().safeDestroyModel(node.model)

        assertEquals(emptyList<String>(), nativeCalls)
    }

    @OptIn(ExperimentalSceneViewApi::class)
    @Test
    fun `a scene snapshot reads nothing from a destroyed model`() {
        val node = modelNode()
        destroy(node.model)
        nativeCalls.clear()

        snapshotNode(node)

        assertEquals(emptyList<String>(), nativeCalls)
    }

    @Test
    fun `the material of a renderable that is gone is a clear error`() {
        // gltfio destroys the components of the asset's entities: the entity of a model's
        // renderable node has no renderable instance any more. Filament hands back a null
        // material for it, and wrapping that null is the native crash.
        val gone = object : RenderableComponent {
            override val engine = this@ModelNodeAfterDestroyTest.engine
            override val entity = 42
        }

        val error = assertThrows(IllegalStateException::class.java) {
            gone.getMaterialInstanceAt(0)
        }
        assertTrue(error.message.orEmpty(), "renderable" in error.message.orEmpty())
    }

    /** An [AssetLoader] that never reached gltfio: only its Java methods are called here. */
    private fun assetLoaderWithoutJni(): AssetLoader {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        return unsafeClass.getMethod("allocateInstance", Class::class.java)
            .invoke(unsafe, AssetLoader::class.java) as AssetLoader
    }

    @Suppress("UnusedParameter") // Robolectric matches the shadowed signature.
    @Implements(FilamentAsset::class)
    class RecordingAsset {
        @Implementation
        fun popRenderable(): Int = 0.also { nativeCalls += "FilamentAsset.popRenderable" }

        @Implementation
        fun getBoundingBox(): Box = Box().also { nativeCalls += "FilamentAsset.getBoundingBox" }

        @Implementation
        fun releaseSourceData() {
            nativeCalls += "FilamentAsset.releaseSourceData"
        }
    }

    @Suppress("UnusedParameter") // Robolectric matches the shadowed signature.
    @Implements(Animator::class)
    class RecordingAnimator {
        @Implementation
        fun getAnimationCount(): Int = 1.also { nativeCalls += "Animator.getAnimationCount" }

        @Implementation
        fun getAnimationName(index: Int): String =
            "walk".also { nativeCalls += "Animator.getAnimationName" }

        @Implementation
        fun getAnimationDuration(index: Int): Float =
            1f.also { nativeCalls += "Animator.getAnimationDuration" }

        @Implementation
        fun applyAnimation(index: Int, time: Float) {
            nativeCalls += "Animator.applyAnimation"
        }

        @Implementation
        fun updateBoneMatrices() {
            nativeCalls += "Animator.updateBoneMatrices"
        }
    }

    @Suppress("UnusedParameter") // Robolectric matches the shadowed signature.
    @Implements(AssetLoader::class)
    class RecordingAssetLoader {
        @Implementation
        fun destroyAsset(asset: FilamentAsset) {
            nativeCalls += "AssetLoader.destroyAsset"
        }
    }

    companion object {
        val nativeCalls = mutableListOf<String>()
    }
}

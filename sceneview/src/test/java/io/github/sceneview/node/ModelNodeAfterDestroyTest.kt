package io.github.sceneview.node

import androidx.compose.ui.geometry.Size
import com.google.android.filament.Box
import com.google.android.filament.Engine
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.View
import com.google.android.filament.gltfio.Animator
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.FilamentInstance
import com.google.android.filament.gltfio.releaseSourceDataIfAlive
import io.github.sceneview.ExperimentalSceneViewApi
import io.github.sceneview.SceneFrameDispatch
import io.github.sceneview.animation.ModelAnimationState
import io.github.sceneview.components.RenderableComponent
import io.github.sceneview.gesture.NodeScreenAnchors
import io.github.sceneview.model.Model
import io.github.sceneview.model.isDestroyed
import io.github.sceneview.model.materialInstances as sceneViewMaterialInstances
import io.github.sceneview.safeDestroyModel
import io.github.sceneview.utils.snapshotNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        ModelNodeAfterDestroyTest.RecordingInstance::class,
        ModelNodeAfterDestroyTest.RecycledRenderableManager::class,
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
        assertDestroyedModelError { node.worldSize }
        assertDestroyedModelError { node.worldBoundingBox }
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

    @Test
    fun `a model reports its destroy, and so do its instance and node`() {
        val node = modelNode()
        assertFalse(node.model.isDestroyed)
        assertFalse(node.modelInstance.isDestroyed)
        assertFalse(node.isModelDestroyed)

        destroy(node.model)

        assertTrue(node.model.isDestroyed)
        assertTrue(node.modelInstance.isDestroyed)
        assertTrue(node.isModelDestroyed)
    }

    @Test
    fun `the animation state of a destroyed model reads idle, without touching the animator`() {
        // `rememberModelAnimationState` polls the animator every frame; a `runCatching` around the
        // read does not catch a native crash.
        val node = modelNode()
        node.playingAnimations[0] = ModelNode.PlayingAnimation(loop = true)
        val state = ModelAnimationState(node)
        state.update(frameTimeNanos)
        assertTrue(state.isPlaying)

        destroy(node.model)
        nativeCalls.clear()
        state.update(frameTimeNanos + 16_000_000L)

        assertEquals(emptyList<String>(), nativeCalls)
        assertFalse(state.isPlaying)
        assertEquals(-1, state.currentAnimationIndex)
    }

    @Test
    fun `the source data of a destroyed model is not released again`() {
        // A resource resolver can destroy the model during its own synchronous load, right before
        // `ModelLoader.createModel` releases the source data.
        val node = modelNode()
        destroy(node.model)
        nativeCalls.clear()

        node.model.releaseSourceDataIfAlive()

        assertEquals(emptyList<String>(), nativeCalls)
    }

    @Test
    fun `the materials of a destroyed instance are a clear error`() {
        val instance = FilamentWithoutJni.modelInstance(engine)
        destroy(instance.asset)

        // Aliased: gltfio's own `FilamentInstance.getMaterialInstances()` member wins over the
        // extension under its own name.
        assertDestroyedModelError { instance.sceneViewMaterialInstances }
    }

    @Test
    fun `the skins and variants of a destroyed model are a clear error, not a native read`() {
        val node = modelNode()
        val target = modelNode()
        // They do reach the instance while the model lives.
        nativeCalls.clear()
        node.skinCount
        node.getJointCount()
        assertEquals(listOf("FilamentInstance.getSkinCount", "FilamentInstance.getJointCountAt"), nativeCalls)

        destroy(node.model)
        nativeCalls.clear()

        assertDestroyedModelError { node.skinCount }
        assertDestroyedModelError { node.skinNames }
        assertDestroyedModelError { node.materialVariantNames }
        assertDestroyedModelError { node.getJointCount() }
        assertDestroyedModelError { node.attachSkin(target) }
        assertDestroyedModelError { node.detachSkin(target) }
        assertEquals(emptyList<String>(), nativeCalls)
    }

    @Test
    fun `the glTF node data of a destroyed model is a clear error, not a native read`() {
        val instance = FilamentWithoutJni.modelInstance(engine)
        val child = object : ModelNode.ChildNode {
            override val entity = 1
            override val modelInstance = instance
        }
        nativeCalls.clear()
        child.extras
        assertEquals(listOf("FilamentAsset.getExtras"), nativeCalls)

        destroy(instance.asset)
        nativeCalls.clear()

        assertDestroyedModelError { child.name }
        assertDestroyedModelError { child.extras }
        assertDestroyedModelError { child.morphTargetNames }
        assertEquals(emptyList<String>(), nativeCalls)
    }

    @Test
    fun `the material of a sub-node of a destroyed model is a clear error`() {
        // gltfio destroyed the entity with the model, and the engine gave its id to another
        // renderable ([RecycledRenderableManager]): the sub-node must not read that one's material.
        val node = modelNode()
        val renderable = node.RenderableNode(node.modelInstance, RECYCLED_ENTITY)
        nativeCalls.clear()
        renderable.getMaterialInstanceAt(0)
        assertEquals(listOf("RenderableManager.getMaterialInstanceAt"), nativeCalls)

        destroy(node.model)
        nativeCalls.clear()

        val error = assertThrows(IllegalStateException::class.java) {
            renderable.getMaterialInstanceAt(0)
        }
        assertTrue(error.message.orEmpty(), "model of this node was destroyed" in error.message.orEmpty())
        assertDestroyedModelError { renderable.materialInstances }
        assertEquals(emptyList<String>(), nativeCalls)
    }

    @Test
    fun `the editing overlay draws nothing for a destroyed model and reads none of it`() {
        // The overlay can outlive the model by a frame: `rememberModelInstance` destroys it first.
        val node = modelNode()
        destroy(node.model)
        nativeCalls.clear()
        val anchors = NodeScreenAnchors()

        anchors.update(node, allocateWithoutJni(View::class.java))

        assertNull(anchors.baseInCanvas(Size(100f, 100f)))
        assertNull(anchors.topInCanvas(Size(100f, 100f)))
        assertEquals(emptyList<String>(), nativeCalls)
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

        @Implementation
        fun getName(entity: Int): String? = null.also { nativeCalls += "FilamentAsset.getName" }

        @Implementation
        fun getExtras(entity: Int): String? = null.also { nativeCalls += "FilamentAsset.getExtras" }

        @Implementation
        fun getMorphTargetNames(entity: Int): Array<String> =
            emptyArray<String>().also { nativeCalls += "FilamentAsset.getMorphTargetNames" }
    }

    @Suppress("UnusedParameter") // Robolectric matches the shadowed signature.
    @Implements(FilamentInstance::class)
    class RecordingInstance {
        @Implementation
        fun getSkinCount(): Int = 0.also { nativeCalls += "FilamentInstance.getSkinCount" }

        @Implementation
        fun getSkinNames(): Array<String> =
            emptyArray<String>().also { nativeCalls += "FilamentInstance.getSkinNames" }

        @Implementation
        fun getMaterialVariantNames(): Array<String> =
            emptyArray<String>().also { nativeCalls += "FilamentInstance.getMaterialVariantNames" }

        @Implementation
        fun getJointCountAt(skinIndex: Int): Int =
            0.also { nativeCalls += "FilamentInstance.getJointCountAt" }

        @Implementation
        fun attachSkin(skinIndex: Int, target: Int) {
            nativeCalls += "FilamentInstance.attachSkin"
        }

        @Implementation
        fun detachSkin(skinIndex: Int, target: Int) {
            nativeCalls += "FilamentInstance.detachSkin"
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

    /** A renderable manager in which [RECYCLED_ENTITY] names a live renderable of one primitive. */
    @Suppress("UnusedParameter") // Robolectric matches the shadowed signature.
    @Implements(RenderableManager::class)
    class RecycledRenderableManager {
        @Implementation
        fun getInstance(entity: Int): Int = if (entity == RECYCLED_ENTITY) 1 else 0

        @Implementation
        fun getPrimitiveCount(instance: Int): Int = if (instance == 1) 1 else 0

        @Implementation
        fun getMaterialInstanceAt(instance: Int, primitiveIndex: Int): MaterialInstance {
            nativeCalls += "RenderableManager.getMaterialInstanceAt"
            return allocateWithoutJni(MaterialInstance::class.java)
        }
    }

    companion object {
        /** An instance of [type] whose constructor, and so its native side, never ran. */
        fun <T> allocateWithoutJni(type: Class<T>): T {
            val unsafeClass = Class.forName("sun.misc.Unsafe")
            val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
            return type.cast(unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, type))
        }

        const val RECYCLED_ENTITY = 7_001
        val nativeCalls = mutableListOf<String>()
    }
}

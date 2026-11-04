package io.github.sceneview.loaders

import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import com.google.android.filament.gltfio.MaterialProvider
import com.google.android.filament.gltfio.MaterialProvider.MaterialKey
import com.google.android.filament.gltfio.UbershaderProvider
import io.github.sceneview.material.InstanceOwnership
import io.github.sceneview.material.materialOrNull
import io.github.sceneview.material.materialRegistry
import io.github.sceneview.model.Model

/**
 * The material provider [ModelLoader] hands to gltfio: the [UbershaderProvider] does the work,
 * and this records the Material of every instance it creates, for [materialOrNull].
 *
 * Filament 1.77 wraps the instances gltfio returns without their Material, and dropped the native
 * that looked it up, so nothing else can tell which ubershader an instance uses.
 *
 * gltfio takes a provider implemented in Java through JNI callbacks, on the thread that created
 * the `AssetLoader` (it keeps that thread's `JNIEnv`). [ModelLoader] therefore creates its loader
 * on first use, from a create call, which the SDK requires on the main thread, and [attach]es
 * the ubershader it creates there. This class must
 * not declare `getNativeObject()`: gltfio would take it for a native provider.
 *
 * gltfio never frees a Java provider's native shim, so this object is kept alive by a JNI global
 * reference after the loader is destroyed; [detach] drops what it holds.
 */
internal class RecordingMaterialProvider : MaterialProvider {

    private var ubershader: UbershaderProvider? = null

    val ownership = InstanceOwnership<Model> { materialRegistry.forgetHandles(it) }

    private val delegate: UbershaderProvider
        get() = checkNotNull(ubershader) {
            "The material provider of a ModelLoader that has no ubershader yet, or was destroyed"
        }

    /** Hands this the ubershader that does the work; [ModelLoader] does it before any load. */
    fun attach(ubershader: UbershaderProvider) {
        this.ubershader = ubershader
    }

    override fun createMaterialInstance(
        config: MaterialKey,
        uvmap: IntArray,
        label: String?,
        extras: String?
    ): MaterialInstance? {
        val instance = delegate.createMaterialInstance(config, uvmap, label, extras) ?: return null
        // The key and the UV map now hold the constrained values the ubershader picked for this
        // instance, so the same lookup returns its material. A copy keeps gltfio's map as is.
        delegate.getMaterial(config, uvmap.copyOf(), label)?.let { material ->
            materialRegistry.register(instance, material)?.let(ownership::record)
        }
        return instance
    }

    override fun getMaterial(config: MaterialKey, uvmap: IntArray, label: String?): Material? =
        delegate.getMaterial(config, uvmap, label)

    override fun getMaterials(): Array<Material> = ubershader?.materials ?: emptyArray()

    override fun needsDummyData(attrib: Int): Boolean = delegate.needsDummyData(attrib)

    // ModelLoader destroys the ubershader itself.
    override fun destroyMaterials() = Unit

    override fun destroy() = Unit

    /** Forgets every recorded instance and lets go of the ubershader. */
    fun detach() {
        ownership.releaseAll()
        ubershader = null
    }
}

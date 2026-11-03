package io.github.sceneview.material

import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * The [Material] this instance was created from, or `null` when SceneView cannot tell.
 *
 * Use it instead of `materialInstance.material` (`getMaterial()`). Since Filament 1.77, a
 * [MaterialInstance] that Filament wraps from a native pointer no longer knows its [Material]:
 * `getMaterial()` returns `null` for every glTF instance (`ModelInstance.getMaterialInstances()`,
 * `RenderableManager.getMaterialInstanceAt`, the ubershader instances), and calling
 * `hasParameter` or `blendingMode` on it throws a `NullPointerException`. SceneView records the
 * Material of every instance its [io.github.sceneview.loaders.ModelLoader] and
 * [io.github.sceneview.loaders.MaterialLoader] create, and this reads that record.
 *
 * ```kotlin
 * modelInstance.getMaterialInstances()
 *     .filter { it.materialOrNull?.hasParameter("emissiveStrength") == true }
 *     .forEach { it.setParameter("emissiveStrength", 4f) }
 * ```
 *
 * `null` means an instance created outside SceneView, or one already destroyed.
 */
val MaterialInstance.materialOrNull: Material?
    get() = runCatching { material }.getOrNull() ?: materialRegistry.materialOf(this)

/** The process-wide record behind [materialOrNull]. */
internal val materialRegistry = NativeHandleRegistry<MaterialInstance, Material>(
    instanceHandle = { it.nativeObject },
    materialHandle = { it.nativeObject },
)

/**
 * Maps native material-instance handles to the material each was created from.
 *
 * Keyed on the native handle, not on the wrapper: Filament builds a new Java wrapper each time an
 * instance is read back, and those wrappers do not override `equals`. A handle whose wrapper is
 * already destroyed reads as no handle (Filament's getters throw then), and a recorded material
 * that has been destroyed since is never handed out.
 *
 * Filament-free so a JVM test can pin it: Filament wrappers cannot be built without the native
 * library.
 */
internal class NativeHandleRegistry<I : Any, M : Any>(
    private val instanceHandle: (I) -> Long,
    private val materialHandle: (M) -> Long,
) {
    private val byInstance = ConcurrentHashMap<Long, M>()

    private fun <T> handleOf(item: T, read: (T) -> Long): Long? =
        runCatching { read(item) }.getOrNull()?.takeIf { it != 0L }

    /** Records that [instance] was created from [material]. Returns the instance's handle. */
    fun register(instance: I, material: M): Long? =
        handleOf(instance, instanceHandle)?.also { byInstance[it] = material }

    /** The live material recorded for [instance], or `null`. */
    fun materialOf(instance: I): M? {
        val handle = handleOf(instance, instanceHandle) ?: return null
        return byInstance[handle]?.takeIf { handleOf(it, materialHandle) != null }
    }

    fun forget(instance: I) {
        handleOf(instance, instanceHandle)?.let { byInstance.remove(it) }
    }

    fun forgetHandles(handles: Iterable<Long>) {
        handles.forEach { byInstance.remove(it) }
    }

    /** Forgets every instance recorded against [material], before it is destroyed. */
    fun forgetMaterial(material: M) {
        val handle = handleOf(material, materialHandle)
        byInstance.values.removeIf { it === material || (handle != null && handleOf(it, materialHandle) == handle) }
    }

    internal val size: Int get() = byInstance.size
}

/**
 * Which recorded instance handles belong to which owner (a glTF asset), so they are forgotten
 * when the owner is destroyed.
 *
 * gltfio creates an asset's material instances while it builds the asset, before the asset object
 * exists: each handle is [record]ed as pending, then [claim]ed by the asset once it is returned.
 */
internal class InstanceOwnership<K : Any>(private val forget: (Collection<Long>) -> Unit) {
    private val pending = mutableListOf<Long>()
    private val owned = IdentityHashMap<K, MutableList<Long>>()

    @Synchronized
    fun record(handle: Long) {
        pending += handle
    }

    /** Hands the pending handles to [owner]; a `null` owner (a failed load) forgets them. */
    @Synchronized
    fun claim(owner: K?) {
        if (pending.isEmpty()) return
        if (owner == null) forget(pending.toList()) else owned.getOrPut(owner) { mutableListOf() } += pending
        pending.clear()
    }

    @Synchronized
    fun release(owner: K) {
        owned.remove(owner)?.let(forget)
    }

    @Synchronized
    fun releaseAll() {
        forget(pending.toList())
        pending.clear()
        owned.values.forEach(forget)
        owned.clear()
    }
}

package io.github.erkko68.filament.gltfio

import io.github.erkko68.filament.Engine
import io.github.erkko68.filament.EntityManager
import io.github.erkko68.filament.NULL
import io.github.erkko68.filament.bytes
import io.github.erkko68.filament.confined
import io.github.erkko68.filament.ffm.FilamentC
import io.github.erkko68.filament.isNullPtr
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

actual class AssetLoader(var nativeHandle: MemorySegment?) {
    actual companion object {
        actual fun create(engine: Engine, materials: MaterialProvider, entities: EntityManager?): AssetLoader {
            val handle = FilamentC.FilaAssetLoader_create(
                engine.nativeHandle, materials.getNativeHandle(), entities?.nativeHandle ?: NULL,
            )
            return AssetLoader(handle)
        }

        actual fun destroy(loader: AssetLoader) {
            FilamentC.FilaAssetLoader_destroy(loader.nativeHandle)
            loader.nativeHandle = null
        }
    }

    actual fun createAsset(buffer: ByteArray): FilamentAsset? = confined { a ->
        val handle = FilamentC.FilaAssetLoader_createAsset(nativeHandle, a.bytes(buffer), buffer.size.toLong())
        handle.takeUnless { it.isNullPtr() }?.let { FilamentAsset(it) }
    }

    actual fun createInstancedAsset(buffer: ByteArray, instances: Array<FilamentInstance>): FilamentAsset? = confined { a ->
        val out = a.allocate(ValueLayout.ADDRESS, instances.size.toLong())
        val handle = FilamentC.FilaAssetLoader_createInstancedAsset(
            nativeHandle, a.bytes(buffer), buffer.size.toLong(), out, instances.size.toLong(),
        )
        if (handle.isNullPtr()) return@confined null
        for (i in instances.indices) instances[i].nativeHandle = out.getAtIndex(ValueLayout.ADDRESS, i.toLong())
        FilamentAsset(handle)
    }

    actual fun createInstance(asset: FilamentAsset): FilamentInstance? {
        val handle = FilamentC.FilaAssetLoader_createInstance(nativeHandle, asset.nativeHandle)
        return handle.takeUnless { it.isNullPtr() }?.let { FilamentInstance(it) }
    }

    actual fun enableDiagnostics(enable: Boolean) = FilamentC.FilaAssetLoader_enableDiagnostics(nativeHandle, enable)

    actual fun destroyAsset(asset: FilamentAsset) {
        FilamentC.FilaAssetLoader_destroyAsset(nativeHandle, asset.nativeHandle)
        asset.nativeHandle = null
    }
}

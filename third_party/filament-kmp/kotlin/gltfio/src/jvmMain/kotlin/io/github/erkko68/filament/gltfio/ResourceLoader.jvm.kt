package io.github.erkko68.filament.gltfio

import io.github.erkko68.filament.Engine
import io.github.erkko68.filament.bytes
import io.github.erkko68.filament.confined
import io.github.erkko68.filament.cstr
import io.github.erkko68.filament.ffm.FilamentC
import io.github.erkko68.filament.isNullPtr
import java.lang.foreign.MemorySegment

actual class ResourceLoader actual constructor(engine: Engine, normalizeSkinningWeights: Boolean) {
    var nativeHandle: MemorySegment? = FilamentC.FilaResourceLoader_create(engine.nativeHandle, normalizeSkinningWeights)
    private val providers = mutableListOf<MemorySegment>()

    init {
        // Auto-register the stb + ktx2 texture providers, matching the Android/native behaviour.
        confined { a ->
            val stb = FilamentC.FilaResourceLoader_createStbProvider(engine.nativeHandle)
            if (!stb.isNullPtr()) {
                FilamentC.FilaResourceLoader_addTextureProvider(nativeHandle, a.cstr("image/jpeg"), stb)
                FilamentC.FilaResourceLoader_addTextureProvider(nativeHandle, a.cstr("image/png"), stb)
                providers.add(stb)
            }
            val ktx2 = FilamentC.FilaResourceLoader_createKtx2Provider(engine.nativeHandle)
            if (!ktx2.isNullPtr()) {
                FilamentC.FilaResourceLoader_addTextureProvider(nativeHandle, a.cstr("image/ktx2"), ktx2)
                providers.add(ktx2)
            }
        }
    }

    actual fun destroy() {
        nativeHandle?.let { FilamentC.FilaResourceLoader_destroy(it) }
        nativeHandle = null
        providers.forEach { FilamentC.FilaResourceLoader_destroyTextureProvider(it) }
        providers.clear()
    }

    actual fun addResourceData(url: String, data: ByteArray): Unit = confined { a ->
        FilamentC.FilaResourceLoader_addResourceData(nativeHandle, a.cstr(url), a.bytes(data), data.size.toLong())
    }

    actual fun hasResourceData(url: String): Boolean = confined { a ->
        FilamentC.FilaResourceLoader_hasResourceData(nativeHandle, a.cstr(url))
    }

    actual fun loadResources(asset: FilamentAsset): Boolean =
        FilamentC.FilaResourceLoader_loadResources(nativeHandle, asset.nativeHandle)

    actual fun asyncBeginLoad(asset: FilamentAsset): Boolean =
        FilamentC.FilaResourceLoader_asyncBeginLoad(nativeHandle, asset.nativeHandle)

    actual fun asyncGetLoadProgress(): Float = FilamentC.FilaResourceLoader_asyncGetLoadProgress(nativeHandle)
    actual fun asyncUpdateLoad() = FilamentC.FilaResourceLoader_asyncUpdateLoad(nativeHandle)
    actual fun asyncCancelLoad() = FilamentC.FilaResourceLoader_asyncCancelLoad(nativeHandle)
    actual fun evictResourceData() = FilamentC.FilaResourceLoader_evictResourceData(nativeHandle)
}

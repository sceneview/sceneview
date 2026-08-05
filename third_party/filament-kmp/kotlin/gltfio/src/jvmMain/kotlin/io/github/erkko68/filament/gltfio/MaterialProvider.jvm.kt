package io.github.erkko68.filament.gltfio

import io.github.erkko68.filament.Engine
import io.github.erkko68.filament.Material
import io.github.erkko68.filament.MaterialInstance
import io.github.erkko68.filament.NULL
import io.github.erkko68.filament.confined
import io.github.erkko68.filament.cstr
import io.github.erkko68.filament.ffm.FilamentC
import io.github.erkko68.filament.isNullPtr
import java.lang.foreign.MemorySegment
import java.lang.foreign.SegmentAllocator
import java.lang.foreign.ValueLayout
import io.github.erkko68.filament.FilamentPlatform
import io.github.erkko68.filament.PlatformGap

actual interface MaterialProvider {
    actual fun createMaterialInstance(config: MaterialKey, uvmap: IntArray, label: String?, extras: String?): MaterialInstance?
    actual fun getMaterial(config: MaterialKey, uvmap: IntArray, label: String?): Material?
    actual fun getMaterials(): Array<Material>
    actual fun needsDummyData(attrib: Int): Boolean
    actual fun destroyMaterials()
    actual fun destroy()

    /** The underlying FilaMaterialProvider* — used by AssetLoader. */
    fun getNativeHandle(): MemorySegment?
}

private fun SegmentAllocator.uvmap(uvmap: IntArray): MemorySegment {
    val seg = allocate(ValueLayout.JAVA_BYTE, 8)
    for (i in 0 until 8) seg.set(ValueLayout.JAVA_BYTE, i.toLong(), uvmap.getOrElse(i) { 0 }.toByte())
    return seg
}

@PlatformGap(platforms = [FilamentPlatform.WEB], behavior = "createMaterialInstance/getMaterial throw — filament.js does not expose the ubershader material provider; use precompiled .filamat materials on web.")
actual class UbershaderProvider actual constructor(engine: Engine) : MaterialProvider {
    private var nativeHandle: MemorySegment? =
        FilamentC.FilaMaterialProvider_createUbershaderProvider(engine.nativeHandle, NULL, 0L)

    actual override fun createMaterialInstance(config: MaterialKey, uvmap: IntArray, label: String?, extras: String?): MaterialInstance? =
        confined { a ->
            val handle = FilamentC.FilaMaterialProvider_createMaterialInstance(
                nativeHandle, config.toNative(a), a.uvmap(uvmap),
                label?.let { a.cstr(it) } ?: NULL, extras?.let { a.cstr(it) } ?: NULL,
            )
            handle.takeUnless { it.isNullPtr() }?.let { MaterialInstance(it) }
        }

    actual override fun getMaterial(config: MaterialKey, uvmap: IntArray, label: String?): Material? =
        confined { a ->
            val handle = FilamentC.FilaMaterialProvider_getMaterial(
                nativeHandle, config.toNative(a), a.uvmap(uvmap), label?.let { a.cstr(it) } ?: NULL,
            )
            handle.takeUnless { it.isNullPtr() }?.let { Material(it) }
        }

    actual override fun getMaterials(): Array<Material> {
        val count = FilamentC.FilaMaterialProvider_getMaterialsCount(nativeHandle).toInt()
        if (count == 0) return emptyArray()
        return confined { a ->
            val out = a.allocate(ValueLayout.ADDRESS, count.toLong())
            FilamentC.FilaMaterialProvider_getMaterials(nativeHandle, out)
            Array(count) { Material(out.getAtIndex(ValueLayout.ADDRESS, it.toLong())) }
        }
    }

    actual override fun needsDummyData(attrib: Int): Boolean = FilamentC.FilaMaterialProvider_needsDummyData(nativeHandle, attrib)
    actual override fun destroyMaterials() = FilamentC.FilaMaterialProvider_destroyMaterials(nativeHandle)

    actual override fun destroy() {
        FilamentC.FilaMaterialProvider_destroy(nativeHandle)
        nativeHandle = null
    }

    override fun getNativeHandle(): MemorySegment? = nativeHandle
}

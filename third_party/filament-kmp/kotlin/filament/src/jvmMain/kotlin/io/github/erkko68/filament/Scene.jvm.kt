package io.github.erkko68.filament

import io.github.erkko68.filament.ffm.FilamentC
import java.lang.foreign.MemorySegment

actual class Scene internal constructor(internal var nativeHandle: MemorySegment?) {
    private var _skybox: Skybox? = null
    private var _indirectLight: IndirectLight? = null

    actual var skybox: Skybox?
        get() = _skybox
        set(value) {
            _skybox = value
            FilamentC.FilaScene_setSkybox(nativeHandle, value?.nativeHandle ?: NULL)
        }

    actual var indirectLight: IndirectLight?
        get() = _indirectLight
        set(value) {
            _indirectLight = value
            FilamentC.FilaScene_setIndirectLight(nativeHandle, value?.nativeHandle ?: NULL)
        }

    actual fun addEntity(entity: Entity) = FilamentC.FilaScene_addEntity(nativeHandle, entity)

    actual fun addEntities(entities: IntArray) {
        confined { arena -> FilamentC.FilaScene_addEntities(nativeHandle, arena.ints(entities), entities.size.toLong()) }
    }

    actual fun removeEntity(entity: Entity) = FilamentC.FilaScene_remove(nativeHandle, entity)
    actual fun remove(entity: Entity) = FilamentC.FilaScene_remove(nativeHandle, entity)

    actual fun removeEntities(entities: IntArray) {
        confined { arena -> FilamentC.FilaScene_removeEntities(nativeHandle, arena.ints(entities), entities.size.toLong()) }
    }

    actual val entityCount: Int get() = FilamentC.FilaScene_getEntityCount(nativeHandle).toInt()
    actual val renderableCount: Int get() = FilamentC.FilaScene_getRenderableCount(nativeHandle).toInt()
    actual val lightCount: Int get() = FilamentC.FilaScene_getLightCount(nativeHandle).toInt()
    actual fun hasEntity(entity: Entity): Boolean = FilamentC.FilaScene_hasEntity(nativeHandle, entity)

    actual fun getEntities(): IntArray = getEntities(null)

    actual fun getEntities(outArray: IntArray?): IntArray {
        val count = entityCount
        val result = if (outArray != null && outArray.size >= count) outArray else IntArray(count)
        if (count > 0) {
            confined { arena ->
                val seg = arena.intArr(count)
                FilamentC.FilaScene_getEntities(nativeHandle, seg, count.toLong())
                val data = seg.toInts()
                System.arraycopy(data, 0, result, 0, count)
            }
        }
        return result
    }

    actual fun forEach(block: (Entity) -> Unit) {
        getEntities().forEach(block)
    }
}

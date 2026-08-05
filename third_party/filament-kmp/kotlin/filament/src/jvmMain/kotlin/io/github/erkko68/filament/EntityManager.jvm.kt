package io.github.erkko68.filament

import io.github.erkko68.filament.ffm.FilamentC
import java.lang.foreign.MemorySegment

actual class EntityManager internal constructor(var nativeHandle: MemorySegment?) {
    actual companion object {
        private val instance: EntityManager by lazy {
            ensureFilamentLoaded()
            EntityManager(FilamentC.FilaEntityManager_get())
        }
        actual fun get(): EntityManager = instance
    }

    actual fun create(): Entity = FilamentC.FilaEntityManager_create(nativeHandle)

    actual fun create(n: Int): IntArray {
        return confined { arena ->
            val seg = arena.intArr(n)
            FilamentC.FilaEntityManager_createArray(nativeHandle, n.toLong(), seg)
            seg.toInts()
        }
    }

    actual fun create(entities: IntArray): IntArray {
        confined { arena ->
            val seg = arena.ints(entities)
            FilamentC.FilaEntityManager_createArray(nativeHandle, entities.size.toLong(), seg)
            val data = seg.toInts()
            System.arraycopy(data, 0, entities, 0, entities.size)
        }
        return entities
    }

    actual fun destroy(entity: Entity) = FilamentC.FilaEntityManager_destroy(nativeHandle, entity)

    actual fun destroy(entities: IntArray) {
        confined { arena -> FilamentC.FilaEntityManager_destroyArray(nativeHandle, entities.size.toLong(), arena.ints(entities)) }
    }

    actual fun isAlive(entity: Entity): Boolean = FilamentC.FilaEntityManager_isAlive(nativeHandle, entity)
}

package io.github.erkko68.filament

import io.github.erkko68.filament.ffm.FilamentC
import java.lang.foreign.MemorySegment

actual class TransformManager internal constructor(internal var nativeHandle: MemorySegment?) {
    actual fun hasComponent(entity: Entity): Boolean = FilamentC.FilaTransformManager_hasComponent(nativeHandle, entity)
    actual fun getInstance(entity: Entity): EntityInstance = FilamentC.FilaTransformManager_getInstance(nativeHandle, entity)

    actual fun create(entity: Entity): EntityInstance = FilamentC.FilaTransformManager_create(nativeHandle, entity)

    actual fun create(entity: Entity, parent: EntityInstance, localTransform: FloatArray?): EntityInstance {
        return confined { arena ->
            FilamentC.FilaTransformManager_createWithParent(nativeHandle, entity, parent, if (localTransform != null) arena.floats(localTransform) else NULL)
        }
    }

    actual fun create(entity: Entity, parent: EntityInstance, localTransform: DoubleArray?): EntityInstance {
        return confined { arena ->
            FilamentC.FilaTransformManager_createWithParentFp64(nativeHandle, entity, parent, if (localTransform != null) arena.doubles(localTransform) else NULL)
        }
    }

    actual fun destroy(entity: Entity) = FilamentC.FilaTransformManager_destroy(nativeHandle, entity)

    actual fun setParent(instance: EntityInstance, newParent: EntityInstance) =
        FilamentC.FilaTransformManager_setParent(nativeHandle, instance, newParent)

    actual fun getParent(instance: EntityInstance): Entity = FilamentC.FilaTransformManager_getParent(nativeHandle, instance)

    actual fun getChildCount(instance: EntityInstance): Int = FilamentC.FilaTransformManager_getChildCount(nativeHandle, instance).toInt()

    actual fun getChildren(instance: EntityInstance, outEntities: IntArray?): IntArray {
        val count = getChildCount(instance)
        val result = outEntities ?: IntArray(count)
        if (count > 0) {
            confined { arena ->
                val seg = arena.intArr(count)
                FilamentC.FilaTransformManager_getChildren(nativeHandle, instance, seg, count.toLong())
                System.arraycopy(seg.toInts(), 0, result, 0, count)
            }
        }
        return result
    }

    actual fun setTransform(instance: EntityInstance, localTransform: FloatArray) {
        confined { arena -> FilamentC.FilaTransformManager_setTransform(nativeHandle, instance, arena.floats(localTransform)) }
    }

    actual fun setTransform(instance: EntityInstance, localTransform: DoubleArray) {
        confined { arena -> FilamentC.FilaTransformManager_setTransformFp64(nativeHandle, instance, arena.doubles(localTransform)) }
    }

    actual fun getTransform(instance: EntityInstance, outLocalTransform: FloatArray?): FloatArray {
        val result = outLocalTransform ?: FloatArray(16)
        confined { arena ->
            val seg = arena.floatArr(16)
            FilamentC.FilaTransformManager_getTransform(nativeHandle, instance, seg)
            System.arraycopy(seg.toFloats(), 0, result, 0, 16)
        }
        return result
    }

    actual fun getTransform(instance: EntityInstance, outLocalTransform: DoubleArray?): DoubleArray {
        val result = outLocalTransform ?: DoubleArray(16)
        confined { arena ->
            val seg = arena.doubleArr(16)
            FilamentC.FilaTransformManager_getTransformFp64(nativeHandle, instance, seg)
            System.arraycopy(seg.toDoubles(), 0, result, 0, 16)
        }
        return result
    }

    actual fun getWorldTransform(instance: EntityInstance, outWorldTransform: FloatArray?): FloatArray {
        val result = outWorldTransform ?: FloatArray(16)
        confined { arena ->
            val seg = arena.floatArr(16)
            FilamentC.FilaTransformManager_getWorldTransform(nativeHandle, instance, seg)
            System.arraycopy(seg.toFloats(), 0, result, 0, 16)
        }
        return result
    }

    actual fun getWorldTransform(instance: EntityInstance, outWorldTransform: DoubleArray?): DoubleArray {
        val result = outWorldTransform ?: DoubleArray(16)
        confined { arena ->
            val seg = arena.doubleArr(16)
            FilamentC.FilaTransformManager_getWorldTransformFp64(nativeHandle, instance, seg)
            System.arraycopy(seg.toDoubles(), 0, result, 0, 16)
        }
        return result
    }

    actual fun openLocalTransformTransaction() = FilamentC.FilaTransformManager_openLocalTransformTransaction(nativeHandle)
    actual fun commitLocalTransformTransaction() = FilamentC.FilaTransformManager_commitLocalTransformTransaction(nativeHandle)

    actual var isAccurateTranslationsEnabled: Boolean
        get() = FilamentC.FilaTransformManager_isAccurateTranslationsEnabled(nativeHandle)
        set(value) { FilamentC.FilaTransformManager_setAccurateTranslationsEnabled(nativeHandle, value) }
}

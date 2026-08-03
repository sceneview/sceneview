package io.github.erkko68.filament.gltfio

import io.github.erkko68.filament.Box
import io.github.erkko68.filament.Engine
import io.github.erkko68.filament.Entity
import io.github.erkko68.filament.confined
import io.github.erkko68.filament.cString
import io.github.erkko68.filament.cstr
import io.github.erkko68.filament.ffm.FilaBox
import io.github.erkko68.filament.ffm.FilamentC
import io.github.erkko68.filament.isNullPtr
import io.github.erkko68.filament.toInts
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import io.github.erkko68.filament.FilamentPlatform
import io.github.erkko68.filament.PlatformGap

actual class FilamentAsset(var nativeHandle: MemorySegment?) {
    actual fun getRoot(): Entity = FilamentC.FilaFilamentAsset_getRoot(nativeHandle)
    actual fun popRenderable(): Entity = FilamentC.FilaFilamentAsset_popRenderable(nativeHandle)

    actual fun popRenderables(entities: IntArray): Int = confined { a ->
        val seg = a.allocate(ValueLayout.JAVA_INT, entities.size.toLong())
        val popped = FilamentC.FilaFilamentAsset_popRenderables(nativeHandle, seg, entities.size.toLong()).toInt()
        for (i in 0 until popped) entities[i] = seg.getAtIndex(ValueLayout.JAVA_INT, i.toLong())
        popped
    }

    private inline fun entityArray(count: Int, fill: (MemorySegment) -> Unit): IntArray {
        if (count == 0) return IntArray(0)
        return confined { a ->
            val seg = a.allocate(ValueLayout.JAVA_INT, count.toLong())
            fill(seg)
            seg.toInts()
        }
    }

    actual fun getEntities(): IntArray =
        entityArray(FilamentC.FilaFilamentAsset_getEntityCount(nativeHandle).toInt()) { FilamentC.FilaFilamentAsset_getEntities(nativeHandle, it) }

    actual fun getLightEntities(): IntArray =
        entityArray(FilamentC.FilaFilamentAsset_getLightEntityCount(nativeHandle).toInt()) { FilamentC.FilaFilamentAsset_getLightEntities(nativeHandle, it) }

    actual fun getRenderableEntities(): IntArray =
        entityArray(FilamentC.FilaFilamentAsset_getRenderableEntityCount(nativeHandle).toInt()) { FilamentC.FilaFilamentAsset_getRenderableEntities(nativeHandle, it) }

    actual fun getCameraEntities(): IntArray =
        entityArray(FilamentC.FilaFilamentAsset_getCameraEntityCount(nativeHandle).toInt()) { FilamentC.FilaFilamentAsset_getCameraEntities(nativeHandle, it) }

    actual fun getEntitiesByName(name: String): IntArray {
        val maxCount = FilamentC.FilaFilamentAsset_getEntityCount(nativeHandle)
        if (maxCount == 0L) return IntArray(0)
        return confined { a ->
            val seg = a.allocate(ValueLayout.JAVA_INT, maxCount)
            val n = FilamentC.FilaFilamentAsset_getEntitiesByName(nativeHandle, a.cstr(name), seg, maxCount).toInt()
            IntArray(n) { seg.getAtIndex(ValueLayout.JAVA_INT, it.toLong()) }
        }
    }

    actual fun getEntitiesByPrefix(prefix: String): IntArray {
        val maxCount = FilamentC.FilaFilamentAsset_getEntityCount(nativeHandle)
        if (maxCount == 0L) return IntArray(0)
        return confined { a ->
            val seg = a.allocate(ValueLayout.JAVA_INT, maxCount)
            val n = FilamentC.FilaFilamentAsset_getEntitiesByPrefix(nativeHandle, a.cstr(prefix), seg, maxCount).toInt()
            IntArray(n) { seg.getAtIndex(ValueLayout.JAVA_INT, it.toLong()) }
        }
    }

    actual fun getFirstEntityByName(name: String): Entity =
        confined { a -> FilamentC.FilaFilamentAsset_getFirstEntityByName(nativeHandle, a.cstr(name)) }

    actual fun getEntityCount(): Int = FilamentC.FilaFilamentAsset_getEntityCount(nativeHandle).toInt()

    @PlatformGap(platforms = [FilamentPlatform.WEB], behavior = "throws at runtime with embind 'unbound types' — the vector return type is unregistered in the web prebuilt.")
    actual fun getAssetInstanceCount(): Int = FilamentC.FilaFilamentAsset_getAssetInstanceCount(nativeHandle).toInt()

    @PlatformGap(platforms = [FilamentPlatform.WEB], behavior = "throws at runtime with embind 'unbound types' — the vector return type is unregistered in the web prebuilt.")
    actual fun getAssetInstances(): Array<FilamentInstance> {
        val count = getAssetInstanceCount()
        if (count == 0) return emptyArray()
        return confined { a ->
            val out = a.allocate(ValueLayout.ADDRESS, count.toLong())
            FilamentC.FilaFilamentAsset_getAssetInstances(nativeHandle, out)
            Array(count) { FilamentInstance(out.getAtIndex(ValueLayout.ADDRESS, it.toLong())) }
        }
    }

    actual fun getBoundingBox(): Box = confined { a ->
        val b = FilamentC.FilaFilamentAsset_getBoundingBox(a, nativeHandle)
        Box(FilaBox.centerX(b), FilaBox.centerY(b), FilaBox.centerZ(b),
            FilaBox.halfExtentX(b), FilaBox.halfExtentY(b), FilaBox.halfExtentZ(b))
    }

    actual fun getName(entity: Entity): String? =
        FilamentC.FilaFilamentAsset_getName(nativeHandle, entity).takeUnless { it.isNullPtr() }?.cString()

    actual fun getExtras(entity: Entity): String? =
        FilamentC.FilaFilamentAsset_getExtras(nativeHandle, entity).takeUnless { it.isNullPtr() }?.cString()

    @PlatformGap(platforms = [FilamentPlatform.WEB], behavior = "returns an empty array — not exposed by filament.js.")
    actual fun getMorphTargetNames(entity: Entity): Array<String> {
        val count = FilamentC.FilaFilamentAsset_getMorphTargetCountAt(nativeHandle, entity).toInt()
        if (count == 0) return emptyArray()
        return Array(count) {
            FilamentC.FilaFilamentAsset_getMorphTargetNameAt(nativeHandle, entity, it.toLong())
                .takeUnless { p -> p.isNullPtr() }?.cString() ?: ""
        }
    }

    actual fun getResourceUris(): Array<String> {
        val count = FilamentC.FilaFilamentAsset_getResourceUriCount(nativeHandle).toInt()
        if (count == 0) return emptyArray()
        return confined { a ->
            val uris = a.allocate(ValueLayout.ADDRESS, count.toLong())
            FilamentC.FilaFilamentAsset_getResourceUris(nativeHandle, uris)
            Array(count) { uris.getAtIndex(ValueLayout.ADDRESS, it.toLong()).let { p -> if (p.isNullPtr()) "" else p.cString() } }
        }
    }

    actual fun releaseSourceData() = FilamentC.FilaFilamentAsset_releaseSourceData(nativeHandle)

    actual fun getEngine(): Engine = Engine(FilamentC.FilaFilamentAsset_getEngine(nativeHandle))

    actual fun getInstance(): FilamentInstance = FilamentInstance(FilamentC.FilaFilamentAsset_getInstance(nativeHandle))
}

package io.github.erkko68.filament.gltfio

import io.github.erkko68.filament.Box
import io.github.erkko68.filament.MaterialInstance
import io.github.erkko68.filament.confined
import io.github.erkko68.filament.cString
import io.github.erkko68.filament.ffm.FilaBox
import io.github.erkko68.filament.ffm.FilamentC
import io.github.erkko68.filament.isNullPtr
import io.github.erkko68.filament.toInts
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import io.github.erkko68.filament.FilamentPlatform
import io.github.erkko68.filament.PlatformGap

actual class FilamentInstance internal constructor(var nativeHandle: MemorySegment?) {
    actual constructor() : this(null)

    actual fun getRoot(): Int = FilamentC.FilaFilamentInstance_getRoot(nativeHandle)

    actual fun getEntities(): IntArray {
        val count = FilamentC.FilaFilamentInstance_getEntityCount(nativeHandle).toInt()
        if (count == 0) return IntArray(0)
        return confined { a ->
            val seg = a.allocate(ValueLayout.JAVA_INT, count.toLong())
            FilamentC.FilaFilamentInstance_getEntities(nativeHandle, seg)
            seg.toInts()
        }
    }

    actual fun getEntityCount(): Int = FilamentC.FilaFilamentInstance_getEntityCount(nativeHandle).toInt()

    actual fun getAnimator(): Animator = Animator(FilamentC.FilaFilamentInstance_getAnimator(nativeHandle))

    actual fun getBoundingBox(): Box = confined { a ->
        val b = FilamentC.FilaFilamentInstance_getBoundingBox(a, nativeHandle)
        Box(FilaBox.centerX(b), FilaBox.centerY(b), FilaBox.centerZ(b),
            FilaBox.halfExtentX(b), FilaBox.halfExtentY(b), FilaBox.halfExtentZ(b))
    }

    actual fun getAsset(): FilamentAsset = FilamentAsset(FilamentC.FilaFilamentInstance_getAsset(nativeHandle))

    actual fun getSkinCount(): Int = FilamentC.FilaFilamentInstance_getSkinCount(nativeHandle).toInt()

    actual fun getSkinNames(): Array<String> {
        val count = getSkinCount()
        if (count == 0) return emptyArray()
        return confined { a ->
            val names = a.allocate(ValueLayout.ADDRESS, count.toLong())
            FilamentC.FilaFilamentInstance_getSkinNames(nativeHandle, names)
            Array(count) { names.getAtIndex(ValueLayout.ADDRESS, it.toLong()).let { p -> if (p.isNullPtr()) "" else p.cString() } }
        }
    }

    actual fun attachSkin(skinIndex: Int, target: Int) = FilamentC.FilaFilamentInstance_attachSkin(nativeHandle, skinIndex.toLong(), target)
    actual fun detachSkin(skinIndex: Int, target: Int) = FilamentC.FilaFilamentInstance_detachSkin(nativeHandle, skinIndex.toLong(), target)

    @PlatformGap(platforms = [FilamentPlatform.WEB], behavior = "returns 0 — filament.js exposes no joint API.")
    actual fun getJointCountAt(skinIndex: Int): Int = FilamentC.FilaFilamentInstance_getJointCountAt(nativeHandle, skinIndex.toLong()).toInt()

    @PlatformGap(platforms = [FilamentPlatform.WEB], behavior = "returns an empty array — filament.js exposes no joint API.")
    actual fun getJointsAt(skinIndex: Int): IntArray {
        val count = getJointCountAt(skinIndex)
        if (count == 0) return IntArray(0)
        return confined { a ->
            val seg = a.allocate(ValueLayout.JAVA_INT, count.toLong())
            FilamentC.FilaFilamentInstance_getJointsAt(nativeHandle, skinIndex.toLong(), seg)
            seg.toInts()
        }
    }

    actual fun applyMaterialVariant(variantIndex: Int) = FilamentC.FilaFilamentInstance_applyMaterialVariant(nativeHandle, variantIndex.toLong())

    @PlatformGap(platforms = [FilamentPlatform.WEB], behavior = "throws at runtime with embind 'unbound types' — the vector return type is unregistered in the web prebuilt.")
    actual fun getMaterialInstances(): Array<MaterialInstance> {
        val count = FilamentC.FilaFilamentInstance_getMaterialInstanceCount(nativeHandle).toInt()
        if (count == 0) return emptyArray()
        return confined { a ->
            val out = a.allocate(ValueLayout.ADDRESS, count.toLong())
            FilamentC.FilaFilamentInstance_getMaterialInstances(nativeHandle, out)
            Array(count) { MaterialInstance(out.getAtIndex(ValueLayout.ADDRESS, it.toLong())) }
        }
    }

    actual fun getMaterialVariantNames(): Array<String> {
        val count = FilamentC.FilaFilamentInstance_getMaterialVariantCount(nativeHandle).toInt()
        if (count == 0) return emptyArray()
        return confined { a ->
            val names = a.allocate(ValueLayout.ADDRESS, count.toLong())
            FilamentC.FilaFilamentInstance_getMaterialVariantNames(nativeHandle, names)
            Array(count) { names.getAtIndex(ValueLayout.ADDRESS, it.toLong()).let { p -> if (p.isNullPtr()) "" else p.cString() } }
        }
    }
}

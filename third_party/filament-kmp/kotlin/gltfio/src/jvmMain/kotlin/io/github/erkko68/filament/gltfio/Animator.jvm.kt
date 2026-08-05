package io.github.erkko68.filament.gltfio

import io.github.erkko68.filament.cString
import io.github.erkko68.filament.ffm.FilamentC
import io.github.erkko68.filament.isNullPtr
import java.lang.foreign.MemorySegment

actual class Animator(var nativeHandle: MemorySegment?) {
    actual fun applyAnimation(index: Int, time: Float) = FilamentC.FilaAnimator_applyAnimation(nativeHandle, index.toLong(), time)
    actual fun applyCrossFade(previousIndex: Int, previousTime: Float, alpha: Float) =
        FilamentC.FilaAnimator_applyCrossFade(nativeHandle, previousIndex.toLong(), previousTime, alpha)
    actual fun updateBoneMatrices() = FilamentC.FilaAnimator_updateBoneMatrices(nativeHandle)
    actual fun resetBoneMatrices() = FilamentC.FilaAnimator_resetBoneMatrices(nativeHandle)

    actual fun getAnimationCount(): Int = FilamentC.FilaAnimator_getAnimationCount(nativeHandle).toInt()
    actual fun getAnimationDuration(index: Int): Float = FilamentC.FilaAnimator_getAnimationDuration(nativeHandle, index.toLong())
    actual fun getAnimationName(index: Int): String? =
        FilamentC.FilaAnimator_getAnimationName(nativeHandle, index.toLong()).takeUnless { it.isNullPtr() }?.cString()
}

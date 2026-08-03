package io.github.erkko68.filament

import io.github.erkko68.filament.ffm.FilamentC
import java.lang.foreign.MemorySegment

actual class MaterialInstance constructor(
    internal val nativeHandle: MemorySegment?
) {
    actual enum class BooleanElement { BOOL, BOOL2, BOOL3, BOOL4 }
    actual enum class IntElement { INT, INT2, INT3, INT4 }
    actual enum class FloatElement { FLOAT, FLOAT2, FLOAT3, FLOAT4, MAT3, MAT4 }

    actual enum class StencilOperation { KEEP, ZERO, REPLACE, INCR_CLAMP, INCR_WRAP, DECR_CLAMP, DECR_WRAP, INVERT }
    actual enum class StencilFace { FRONT, BACK, FRONT_AND_BACK }

    actual companion object {
        @PlatformGap(platforms = [FilamentPlatform.WEB], behavior = "silent no-op — returns the source instance unchanged; filament.js does not expose MaterialInstance duplication.")
        actual fun duplicate(other: MaterialInstance, name: String?): MaterialInstance {
            return confined { arena ->
                MaterialInstance(FilamentC.FilaMaterialInstance_duplicate(other.nativeHandle, if (name != null) arena.cstr(name) else NULL))
            }
        }
    }

    @PlatformGap(platforms = [FilamentPlatform.WEB], behavior = "getter throws UnsupportedOperationException — filament.js does not expose MaterialInstance.getMaterial.")
    actual val material: Material get() = Material(FilamentC.FilaMaterialInstance_getMaterial(nativeHandle))
    actual val name: String get() = FilamentC.FilaMaterialInstance_getName(nativeHandle).let { if (it.isNullPtr()) "" else it.cString() }

    actual fun setParameter(name: String, x: Boolean) = confined { arena -> FilamentC.FilaMaterialInstance_setParameterBool(nativeHandle, arena.cstr(name), x) }
    actual fun setParameter(name: String, x: Float) = confined { arena -> FilamentC.FilaMaterialInstance_setParameterFloat(nativeHandle, arena.cstr(name), x) }
    actual fun setParameter(name: String, x: Int) = confined { arena -> FilamentC.FilaMaterialInstance_setParameterInt(nativeHandle, arena.cstr(name), x) }
    actual fun getConstantBoolean(name: String): Boolean = confined { arena -> FilamentC.FilaMaterialInstance_getConstantBool(nativeHandle, arena.cstr(name)) }
    actual fun getConstantFloat(name: String): Float = confined { arena -> FilamentC.FilaMaterialInstance_getConstantFloat(nativeHandle, arena.cstr(name)) }
    actual fun getConstantInt(name: String): Int = confined { arena -> FilamentC.FilaMaterialInstance_getConstantInt(nativeHandle, arena.cstr(name)) }
    actual fun setParameter(name: String, x: Boolean, y: Boolean) = confined { arena -> FilamentC.FilaMaterialInstance_setParameterBool2(nativeHandle, arena.cstr(name), x, y) }
    actual fun setParameter(name: String, x: Float, y: Float) = confined { arena -> FilamentC.FilaMaterialInstance_setParameterFloat2(nativeHandle, arena.cstr(name), x, y) }
    actual fun setParameter(name: String, x: Int, y: Int) = confined { arena -> FilamentC.FilaMaterialInstance_setParameterInt2(nativeHandle, arena.cstr(name), x, y) }
    actual fun setParameter(name: String, x: Boolean, y: Boolean, z: Boolean) = confined { arena -> FilamentC.FilaMaterialInstance_setParameterBool3(nativeHandle, arena.cstr(name), x, y, z) }
    actual fun setParameter(name: String, x: Float, y: Float, z: Float) = confined { arena -> FilamentC.FilaMaterialInstance_setParameterFloat3(nativeHandle, arena.cstr(name), x, y, z) }
    actual fun setParameter(name: String, x: Int, y: Int, z: Int) = confined { arena -> FilamentC.FilaMaterialInstance_setParameterInt3(nativeHandle, arena.cstr(name), x, y, z) }
    actual fun setParameter(name: String, x: Boolean, y: Boolean, z: Boolean, w: Boolean) = confined { arena -> FilamentC.FilaMaterialInstance_setParameterBool4(nativeHandle, arena.cstr(name), x, y, z, w) }
    actual fun setParameter(name: String, x: Float, y: Float, z: Float, w: Float) = confined { arena -> FilamentC.FilaMaterialInstance_setParameterFloat4(nativeHandle, arena.cstr(name), x, y, z, w) }
    actual fun setParameter(name: String, x: Int, y: Int, z: Int, w: Int) = confined { arena -> FilamentC.FilaMaterialInstance_setParameterInt4(nativeHandle, arena.cstr(name), x, y, z, w) }

    actual fun setParameter(name: String, texture: Texture, sampler: TextureSampler) {
        confined { arena -> FilamentC.FilaMaterialInstance_setParameterTexture(nativeHandle, arena.cstr(name), texture.nativeHandle, sampler.nativeHandle) }
    }

    actual fun setParameter(name: String, type: BooleanElement, v: BooleanArray, offset: Int, count: Int) {
        confined { arena ->
            val sub = BooleanArray(count) { v[offset + it] }
            FilamentC.FilaMaterialInstance_setBooleanParameterArray(nativeHandle, arena.cstr(name), type.ordinal + 1, arena.booleans(sub), count.toLong())
        }
    }
    actual fun setParameter(name: String, type: IntElement, v: IntArray, offset: Int, count: Int) {
        confined { arena ->
            FilamentC.FilaMaterialInstance_setIntParameterArray(nativeHandle, arena.cstr(name), type.ordinal + 1, arena.ints(v.copyOfRange(offset, offset + count)), count.toLong())
        }
    }
    actual fun setParameter(name: String, type: FloatElement, v: FloatArray, offset: Int, count: Int) {
        val elementSize = when (type) {
            FloatElement.FLOAT -> 1
            FloatElement.FLOAT2 -> 2
            FloatElement.FLOAT3 -> 3
            FloatElement.FLOAT4 -> 4
            FloatElement.MAT3 -> 9
            FloatElement.MAT4 -> 16
        }
        confined { arena ->
            FilamentC.FilaMaterialInstance_setFloatParameterArray(nativeHandle, arena.cstr(name), elementSize, arena.floats(v.copyOfRange(offset, offset + count * elementSize)), count.toLong())
        }
    }

    actual fun setParameter(name: String, type: Colors.RgbType, r: Float, g: Float, b: Float) {
        val linear = Colors.toLinear(type, r, g, b)
        setParameter(name, linear[0], linear[1], linear[2])
    }
    actual fun setParameter(name: String, type: Colors.RgbaType, r: Float, g: Float, b: Float, a: Float) {
        val linear = Colors.toLinear(type, r, g, b, a)
        setParameter(name, linear[0], linear[1], linear[2], linear[3])
    }

    actual fun setScissor(left: Int, bottom: Int, width: Int, height: Int) {
        FilamentC.FilaMaterialInstance_setScissor(nativeHandle, left, bottom, width, height)
    }
    actual fun unsetScissor() { FilamentC.FilaMaterialInstance_unsetScissor(nativeHandle) }

    actual fun setPolygonOffset(scale: Float, constant: Float) { FilamentC.FilaMaterialInstance_setPolygonOffset(nativeHandle, scale, constant) }
    actual var maskThreshold: Float
        get() = FilamentC.FilaMaterialInstance_getMaskThreshold(nativeHandle)
        set(value) { FilamentC.FilaMaterialInstance_setMaskThreshold(nativeHandle, value) }
    actual var specularAntiAliasingVariance: Float
        get() = FilamentC.FilaMaterialInstance_getSpecularAntiAliasingVariance(nativeHandle)
        set(value) { FilamentC.FilaMaterialInstance_setSpecularAntiAliasingVariance(nativeHandle, value) }
    actual var specularAntiAliasingThreshold: Float
        get() = FilamentC.FilaMaterialInstance_getSpecularAntiAliasingThreshold(nativeHandle)
        set(value) { FilamentC.FilaMaterialInstance_setSpecularAntiAliasingThreshold(nativeHandle, value) }
    actual var isDoubleSided: Boolean
        get() = FilamentC.FilaMaterialInstance_isDoubleSided(nativeHandle)
        set(value) { FilamentC.FilaMaterialInstance_setDoubleSided(nativeHandle, value) }

    actual var transparencyMode: Material.TransparencyMode
        get() = Material.TransparencyMode.values()[FilamentC.FilaMaterialInstance_getTransparencyMode(nativeHandle)]
        set(value) { FilamentC.FilaMaterialInstance_setTransparencyMode(nativeHandle, value.ordinal) }

    actual var cullingMode: Material.CullingMode
        get() = Material.CullingMode.values()[FilamentC.FilaMaterialInstance_getCullingMode(nativeHandle)]
        set(value) { FilamentC.FilaMaterialInstance_setCullingMode(nativeHandle, value.ordinal) }

    actual fun setCullingMode(colorPassCullingMode: Material.CullingMode, shadowPassCullingMode: Material.CullingMode) {
        FilamentC.FilaMaterialInstance_setCullingModeSeparate(nativeHandle, colorPassCullingMode.ordinal, shadowPassCullingMode.ordinal)
    }

    actual val shadowCullingMode: Material.CullingMode get() = Material.CullingMode.values()[FilamentC.FilaMaterialInstance_getShadowCullingMode(nativeHandle)]

    actual var isColorWriteEnabled: Boolean
        get() = FilamentC.FilaMaterialInstance_isColorWriteEnabled(nativeHandle)
        set(value) { FilamentC.FilaMaterialInstance_setColorWrite(nativeHandle, value) }
    actual var isDepthWriteEnabled: Boolean
        get() = FilamentC.FilaMaterialInstance_isDepthWriteEnabled(nativeHandle)
        set(value) { FilamentC.FilaMaterialInstance_setDepthWrite(nativeHandle, value) }
    actual var isStencilWriteEnabled: Boolean
        get() = FilamentC.FilaMaterialInstance_isStencilWriteEnabled(nativeHandle)
        set(value) { FilamentC.FilaMaterialInstance_setStencilWrite(nativeHandle, value) }

    actual var isDepthCullingEnabled: Boolean
        get() = FilamentC.FilaMaterialInstance_isDepthCullingEnabled(nativeHandle)
        set(value) { FilamentC.FilaMaterialInstance_setDepthCulling(nativeHandle, value) }

    actual var depthFunc: TextureSampler.CompareFunction
        get() = TextureSampler.CompareFunction.values()[FilamentC.FilaMaterialInstance_getDepthFunc(nativeHandle)]
        set(value) { FilamentC.FilaMaterialInstance_setDepthFunc(nativeHandle, value.ordinal) }

    actual fun setStencilCompareFunction(func: TextureSampler.CompareFunction, face: StencilFace) {
        FilamentC.FilaMaterialInstance_setStencilCompareFunction(nativeHandle, func.ordinal, face.native)
    }
    actual fun setStencilCompareFunction(func: TextureSampler.CompareFunction) {
        FilamentC.FilaMaterialInstance_setStencilCompareFunction(nativeHandle, func.ordinal, StencilFace.FRONT_AND_BACK.native)
    }
    actual fun setStencilOpStencilFail(op: StencilOperation, face: StencilFace) {
        FilamentC.FilaMaterialInstance_setStencilOpStencilFail(nativeHandle, op.ordinal, face.native)
    }
    actual fun setStencilOpStencilFail(op: StencilOperation) {
        FilamentC.FilaMaterialInstance_setStencilOpStencilFail(nativeHandle, op.ordinal, StencilFace.FRONT_AND_BACK.native)
    }
    actual fun setStencilOpDepthFail(op: StencilOperation, face: StencilFace) {
        FilamentC.FilaMaterialInstance_setStencilOpDepthFail(nativeHandle, op.ordinal, face.native)
    }
    actual fun setStencilOpDepthFail(op: StencilOperation) {
        FilamentC.FilaMaterialInstance_setStencilOpDepthFail(nativeHandle, op.ordinal, StencilFace.FRONT_AND_BACK.native)
    }
    actual fun setStencilOpDepthStencilPass(op: StencilOperation, face: StencilFace) {
        FilamentC.FilaMaterialInstance_setStencilOpDepthStencilPass(nativeHandle, op.ordinal, face.native)
    }
    actual fun setStencilOpDepthStencilPass(op: StencilOperation) {
        FilamentC.FilaMaterialInstance_setStencilOpDepthStencilPass(nativeHandle, op.ordinal, StencilFace.FRONT_AND_BACK.native)
    }

    actual fun setStencilReferenceValue(value: Int, face: StencilFace) {
        FilamentC.FilaMaterialInstance_setStencilReferenceValue(nativeHandle, value, face.native)
    }
    actual fun setStencilReferenceValue(value: Int) {
        FilamentC.FilaMaterialInstance_setStencilReferenceValue(nativeHandle, value, StencilFace.FRONT_AND_BACK.native)
    }
    actual fun setStencilReadMask(readMask: Int, face: StencilFace) {
        FilamentC.FilaMaterialInstance_setStencilReadMask(nativeHandle, readMask, face.native)
    }
    actual fun setStencilReadMask(readMask: Int) {
        FilamentC.FilaMaterialInstance_setStencilReadMask(nativeHandle, readMask, StencilFace.FRONT_AND_BACK.native)
    }
    actual fun setStencilWriteMask(writeMask: Int, face: StencilFace) {
        FilamentC.FilaMaterialInstance_setStencilWriteMask(nativeHandle, writeMask, face.native)
    }
    actual fun setStencilWriteMask(writeMask: Int) {
        FilamentC.FilaMaterialInstance_setStencilWriteMask(nativeHandle, writeMask, StencilFace.FRONT_AND_BACK.native)
    }
}

private val MaterialInstance.StencilFace.native: Int
    get() = when (this) {
        MaterialInstance.StencilFace.FRONT -> 1
        MaterialInstance.StencilFace.BACK -> 2
        MaterialInstance.StencilFace.FRONT_AND_BACK -> 3
    }

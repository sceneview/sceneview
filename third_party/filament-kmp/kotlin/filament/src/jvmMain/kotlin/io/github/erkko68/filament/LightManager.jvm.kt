package io.github.erkko68.filament

import io.github.erkko68.filament.ffm.FilamentC
import io.github.erkko68.filament.ffm.FilaLightManagerShadowOptions
import io.github.erkko68.filament.ffm.FilaLightManagerVsmShadowOptions
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment

actual class LightManager internal constructor(internal val nativeLightManager: MemorySegment) {
    actual enum class Type { SUN, DIRECTIONAL, POINT, FOCUSED_SPOT, SPOT }

    actual class ShadowOptions actual constructor() {
        // Held for the lifetime of this object; an auto arena frees it when unreachable.
        internal val nativeOptions: MemorySegment = FilaLightManagerShadowOptions.allocate(Arena.ofAuto())
        private val vsm: MemorySegment get() = FilaLightManagerShadowOptions.vsm(nativeOptions)

        init {
            FilaLightManagerShadowOptions.mapSize(nativeOptions, 1024)
            FilaLightManagerShadowOptions.shadowCascades(nativeOptions, 1)
            FilaLightManagerShadowOptions.cascadeSplitPositions(nativeOptions).let { s ->
                s.setFloatAt(0, 0.125f); s.setFloatAt(1, 0.25f); s.setFloatAt(2, 0.50f)
            }
            FilaLightManagerShadowOptions.constantBias(nativeOptions, 0.001f)
            FilaLightManagerShadowOptions.normalBias(nativeOptions, 1.0f)
            FilaLightManagerShadowOptions.shadowFar(nativeOptions, 0.0f)
            FilaLightManagerShadowOptions.shadowNearHint(nativeOptions, 1.0f)
            FilaLightManagerShadowOptions.shadowFarHint(nativeOptions, 100.0f)
            FilaLightManagerShadowOptions.stable(nativeOptions, false)
            FilaLightManagerShadowOptions.lispsm(nativeOptions, false)  // match Android binding + cleaner PCSS (Filament C++ defaults true)
            FilaLightManagerShadowOptions.polygonOffsetConstant(nativeOptions, 0.5f)
            FilaLightManagerShadowOptions.polygonOffsetSlope(nativeOptions, 2.0f)
            FilaLightManagerShadowOptions.screenSpaceContactShadows(nativeOptions, false)
            FilaLightManagerShadowOptions.stepCount(nativeOptions, 8)
            FilaLightManagerShadowOptions.maxShadowDistance(nativeOptions, 0.3f)
            FilaLightManagerVsmShadowOptions.elvsm(vsm, false)
            FilaLightManagerVsmShadowOptions.blurWidth(vsm, 0.0f)
            FilaLightManagerShadowOptions.shadowBulbRadius(nativeOptions, 0.02f)
            // Identity quaternion (x,y,z,w) = (0,0,0,1); only w needs setting (arena zeroed the rest).
            FilaLightManagerShadowOptions.transform(nativeOptions).setFloatAt(3, 1.0f)
        }

        actual var mapSize: Int
            get() = FilaLightManagerShadowOptions.mapSize(nativeOptions)
            set(value) { FilaLightManagerShadowOptions.mapSize(nativeOptions, value) }

        actual var shadowCascades: Int
            get() = FilaLightManagerShadowOptions.shadowCascades(nativeOptions)
            set(value) { FilaLightManagerShadowOptions.shadowCascades(nativeOptions, value) }

        actual var cascadeSplitPositions: FloatArray
            get() = FilaLightManagerShadowOptions.cascadeSplitPositions(nativeOptions).let { s -> FloatArray(3) { s.getFloatAt(it) } }
            set(value) { val s = FilaLightManagerShadowOptions.cascadeSplitPositions(nativeOptions); for (i in 0 until 3.coerceAtMost(value.size)) s.setFloatAt(i, value[i]) }

        actual var constantBias: Float
            get() = FilaLightManagerShadowOptions.constantBias(nativeOptions)
            set(value) { FilaLightManagerShadowOptions.constantBias(nativeOptions, value) }

        actual var normalBias: Float
            get() = FilaLightManagerShadowOptions.normalBias(nativeOptions)
            set(value) { FilaLightManagerShadowOptions.normalBias(nativeOptions, value) }

        actual var shadowFar: Float
            get() = FilaLightManagerShadowOptions.shadowFar(nativeOptions)
            set(value) { FilaLightManagerShadowOptions.shadowFar(nativeOptions, value) }

        actual var shadowNearHint: Float
            get() = FilaLightManagerShadowOptions.shadowNearHint(nativeOptions)
            set(value) { FilaLightManagerShadowOptions.shadowNearHint(nativeOptions, value) }

        actual var shadowFarHint: Float
            get() = FilaLightManagerShadowOptions.shadowFarHint(nativeOptions)
            set(value) { FilaLightManagerShadowOptions.shadowFarHint(nativeOptions, value) }

        actual var stable: Boolean
            get() = FilaLightManagerShadowOptions.stable(nativeOptions)
            set(value) { FilaLightManagerShadowOptions.stable(nativeOptions, value) }

        actual var lispsm: Boolean
            get() = FilaLightManagerShadowOptions.lispsm(nativeOptions)
            set(value) { FilaLightManagerShadowOptions.lispsm(nativeOptions, value) }

        actual var screenSpaceContactShadows: Boolean
            get() = FilaLightManagerShadowOptions.screenSpaceContactShadows(nativeOptions)
            set(value) { FilaLightManagerShadowOptions.screenSpaceContactShadows(nativeOptions, value) }

        actual var stepCount: Int
            get() = FilaLightManagerShadowOptions.stepCount(nativeOptions).toInt()
            set(value) { FilaLightManagerShadowOptions.stepCount(nativeOptions, value.toByte()) }

        actual var maxShadowDistance: Float
            get() = FilaLightManagerShadowOptions.maxShadowDistance(nativeOptions)
            set(value) { FilaLightManagerShadowOptions.maxShadowDistance(nativeOptions, value) }

        actual var elvsm: Boolean
            get() = FilaLightManagerVsmShadowOptions.elvsm(vsm)
            set(value) { FilaLightManagerVsmShadowOptions.elvsm(vsm, value) }

        actual var blurWidth: Float
            get() = FilaLightManagerVsmShadowOptions.blurWidth(vsm)
            set(value) { FilaLightManagerVsmShadowOptions.blurWidth(vsm, value) }

        actual var shadowBulbRadius: Float
            get() = FilaLightManagerShadowOptions.shadowBulbRadius(nativeOptions)
            set(value) { FilaLightManagerShadowOptions.shadowBulbRadius(nativeOptions, value) }

        actual var transform: FloatArray
            get() = FilaLightManagerShadowOptions.transform(nativeOptions).let { s -> FloatArray(4) { s.getFloatAt(it) } }
            set(value) { val s = FilaLightManagerShadowOptions.transform(nativeOptions); for (i in 0 until 4.coerceAtMost(value.size)) s.setFloatAt(i, value[i]) }
    }

    actual object ShadowCascades {
        actual fun computeUniformSplits(splitPositions: FloatArray, cascades: Int) {
            confined { arena ->
                val seg = arena.floatArr(splitPositions.size)
                FilamentC.FilaLightManager_computeUniformSplits(seg, cascades.toByte())
                System.arraycopy(seg.toFloats(), 0, splitPositions, 0, splitPositions.size)
            }
        }
        actual fun computeLogSplits(splitPositions: FloatArray, cascades: Int, near: Float, far: Float) {
            confined { arena ->
                val seg = arena.floatArr(splitPositions.size)
                FilamentC.FilaLightManager_computeLogSplits(seg, cascades.toByte(), near, far)
                System.arraycopy(seg.toFloats(), 0, splitPositions, 0, splitPositions.size)
            }
        }
        actual fun computePracticalSplits(splitPositions: FloatArray, cascades: Int, near: Float, far: Float, lambda: Float) {
            confined { arena ->
                val seg = arena.floatArr(splitPositions.size)
                FilamentC.FilaLightManager_computePracticalSplits(seg, cascades.toByte(), near, far, lambda)
                System.arraycopy(seg.toFloats(), 0, splitPositions, 0, splitPositions.size)
            }
        }
    }

    actual class Builder actual constructor(type: Type) {
        private val nativeBuilder = FilamentC.FilaLightManagerBuilder_create(type.ordinal)

        actual fun lightChannel(channel: Int, enable: Boolean): Builder = apply { FilamentC.FilaLightManagerBuilder_lightChannel(nativeBuilder, channel, enable) }
        actual fun castShadows(enable: Boolean): Builder = apply { FilamentC.FilaLightManagerBuilder_castShadows(nativeBuilder, enable) }
        @PlatformGap(platforms = [FilamentPlatform.WEB], behavior = "silent no-op — upstream embind registers ShadowOptions with an unregisterable mat4f field, so the binding is unreachable; per-light shadow options stay at Filament's defaults on web.")
        actual fun shadowOptions(options: ShadowOptions): Builder = apply { FilamentC.FilaLightManagerBuilder_shadowOptions(nativeBuilder, options.nativeOptions) }
        actual fun castLight(enabled: Boolean): Builder = apply { FilamentC.FilaLightManagerBuilder_castLight(nativeBuilder, enabled) }
        actual fun position(x: Float, y: Float, z: Float): Builder = apply { FilamentC.FilaLightManagerBuilder_position(nativeBuilder, x, y, z) }
        actual fun direction(x: Float, y: Float, z: Float): Builder = apply { FilamentC.FilaLightManagerBuilder_direction(nativeBuilder, x, y, z) }
        actual fun color(linearR: Float, linearG: Float, linearB: Float): Builder = apply { FilamentC.FilaLightManagerBuilder_color(nativeBuilder, linearR, linearG, linearB) }
        actual fun intensity(intensity: Float): Builder = apply { FilamentC.FilaLightManagerBuilder_intensity(nativeBuilder, intensity) }
        actual fun intensity(watts: Float, efficiency: Float): Builder = apply { FilamentC.FilaLightManagerBuilder_intensityEfficiency(nativeBuilder, watts, efficiency) }
        actual fun intensityCandela(intensity: Float): Builder = apply { FilamentC.FilaLightManagerBuilder_intensityCandela(nativeBuilder, intensity) }
        actual fun falloff(radius: Float): Builder = apply { FilamentC.FilaLightManagerBuilder_falloff(nativeBuilder, radius) }
        actual fun spotLightCone(inner: Float, outer: Float): Builder = apply { FilamentC.FilaLightManagerBuilder_spotLightCone(nativeBuilder, inner, outer) }
        actual fun sunAngularRadius(angularRadius: Float): Builder = apply { FilamentC.FilaLightManagerBuilder_sunAngularRadius(nativeBuilder, angularRadius) }
        actual fun sunHaloSize(haloSize: Float): Builder = apply { FilamentC.FilaLightManagerBuilder_sunHaloSize(nativeBuilder, haloSize) }
        actual fun sunHaloFalloff(haloFalloff: Float): Builder = apply { FilamentC.FilaLightManagerBuilder_sunHaloFalloff(nativeBuilder, haloFalloff) }
        actual fun build(engine: Engine, entity: Entity) {
            FilamentC.FilaLightManagerBuilder_build(nativeBuilder, engine.nativeHandle, entity)
            FilamentC.FilaLightManagerBuilder_destroy(nativeBuilder)
        }
    }

    @PlatformGap(platforms = [FilamentPlatform.WEB], behavior = "throws UnsupportedOperationException — not bound in filament.js.")
    actual fun getComponentCount(): Int = FilamentC.FilaLightManager_getComponentCount(nativeLightManager).toInt()
    actual fun hasComponent(entity: Entity): Boolean = FilamentC.FilaLightManager_hasComponent(nativeLightManager, entity)
    actual fun getInstance(entity: Entity): EntityInstance = FilamentC.FilaLightManager_getInstance(nativeLightManager, entity)
    actual fun destroy(entity: Entity) { FilamentC.FilaLightManager_destroy(nativeLightManager, entity) }

    actual fun getType(instance: EntityInstance): Type = Type.values()[FilamentC.FilaLightManager_getType(nativeLightManager, instance)]
    actual fun setDirection(instance: EntityInstance, x: Float, y: Float, z: Float) { FilamentC.FilaLightManager_setDirection(nativeLightManager, instance, x, y, z) }
    actual fun getDirection(instance: EntityInstance, out: FloatArray): FloatArray {
        confined { arena ->
            val seg = arena.floatArr(3)
            FilamentC.FilaLightManager_getDirection(nativeLightManager, instance, seg)
            System.arraycopy(seg.toFloats(), 0, out, 0, 3)
        }
        return out
    }
    actual fun setPosition(instance: EntityInstance, x: Float, y: Float, z: Float) { FilamentC.FilaLightManager_setPosition(nativeLightManager, instance, x, y, z) }
    actual fun getPosition(instance: EntityInstance, out: FloatArray): FloatArray {
        confined { arena ->
            val seg = arena.floatArr(3)
            FilamentC.FilaLightManager_getPosition(nativeLightManager, instance, seg)
            System.arraycopy(seg.toFloats(), 0, out, 0, 3)
        }
        return out
    }
    actual fun setColor(instance: EntityInstance, r: Float, g: Float, b: Float) { FilamentC.FilaLightManager_setColor(nativeLightManager, instance, r, g, b) }
    actual fun getColor(instance: EntityInstance, out: FloatArray): FloatArray {
        confined { arena ->
            val seg = arena.floatArr(3)
            FilamentC.FilaLightManager_getColor(nativeLightManager, instance, seg)
            System.arraycopy(seg.toFloats(), 0, out, 0, 3)
        }
        return out
    }
    actual fun setIntensity(instance: EntityInstance, intensity: Float) { FilamentC.FilaLightManager_setIntensity(nativeLightManager, instance, intensity) }
    actual fun setIntensity(instance: EntityInstance, watts: Float, efficiency: Float) { FilamentC.FilaLightManager_setIntensityEfficiency(nativeLightManager, instance, watts, efficiency) }
    actual fun setIntensityCandela(instance: EntityInstance, intensity: Float) { FilamentC.FilaLightManager_setIntensityCandela(nativeLightManager, instance, intensity) }
    actual fun getIntensity(instance: EntityInstance): Float = FilamentC.FilaLightManager_getIntensity(nativeLightManager, instance)
    actual fun setFalloff(instance: EntityInstance, radius: Float) { FilamentC.FilaLightManager_setFalloff(nativeLightManager, instance, radius) }
    actual fun getFalloff(instance: EntityInstance): Float = FilamentC.FilaLightManager_getFalloff(nativeLightManager, instance)
    actual fun setSpotLightCone(instance: EntityInstance, inner: Float, outer: Float) { FilamentC.FilaLightManager_setSpotLightCone(nativeLightManager, instance, inner, outer) }
    actual fun getInnerConeAngle(instance: EntityInstance): Float = FilamentC.FilaLightManager_getSpotLightInnerCone(nativeLightManager, instance)
    actual fun getOuterConeAngle(instance: EntityInstance): Float = FilamentC.FilaLightManager_getSpotLightOuterCone(nativeLightManager, instance)
    actual fun setSunAngularRadius(instance: EntityInstance, angularRadius: Float) { FilamentC.FilaLightManager_setSunAngularRadius(nativeLightManager, instance, angularRadius) }
    actual fun getSunAngularRadius(instance: EntityInstance): Float = FilamentC.FilaLightManager_getSunAngularRadius(nativeLightManager, instance)
    actual fun setSunHaloSize(instance: EntityInstance, haloSize: Float) { FilamentC.FilaLightManager_setSunHaloSize(nativeLightManager, instance, haloSize) }
    actual fun getSunHaloSize(instance: EntityInstance): Float = FilamentC.FilaLightManager_getSunHaloSize(nativeLightManager, instance)
    actual fun setSunHaloFalloff(instance: EntityInstance, haloFalloff: Float) { FilamentC.FilaLightManager_setSunHaloFalloff(nativeLightManager, instance, haloFalloff) }
    actual fun getSunHaloFalloff(instance: EntityInstance): Float = FilamentC.FilaLightManager_getSunHaloFalloff(nativeLightManager, instance)
    actual fun setShadowCaster(instance: EntityInstance, shadowCaster: Boolean) { FilamentC.FilaLightManager_setShadowCaster(nativeLightManager, instance, shadowCaster) }
    actual fun isShadowCaster(instance: EntityInstance): Boolean = FilamentC.FilaLightManager_isShadowCaster(nativeLightManager, instance)
    actual fun setLightChannel(instance: EntityInstance, channel: Int, enable: Boolean) { FilamentC.FilaLightManager_setLightChannel(nativeLightManager, instance, channel, enable) }
    actual fun getLightChannel(instance: EntityInstance, channel: Int): Boolean = FilamentC.FilaLightManager_getLightChannel(nativeLightManager, instance, channel)
}

package io.github.sceneview.components

import com.google.android.filament.LightManager
import com.google.android.filament.LightManager.ShadowOptions
import io.github.sceneview.EntityInstance

/*
 * Shadow options on Filament 1.77.x (google/filament#10565, fixed upstream in 1.79.0).
 *
 * The generated Java bindings break `LightManager.Builder.shadowOptions` and
 * `LightManager.setShadowOptions` in two ways, and each one makes directional shadows vanish:
 * - `ShadowOptions()` starts with every field at 0 (`mapSize`, `shadowCascades`, the `transform`
 *   quaternion…) instead of Filament's native defaults;
 * - the native side reads the `transform` quaternion in the wrong component order: Java
 *   (x, y, z, w) lands as (w = x, x = y, y = z, z = w), so the identity becomes a 180° turn about
 *   Z and the shadow points away from every receiver.
 *
 * Everything here is correct on both sides of the fix: on a runtime where the bindings work, the
 * no-argument `ShadowOptions()` already holds the native defaults and the read-back matches, so
 * nothing more happens.
 */

/**
 * Writes [options] on [instance] so that the native side ends up holding exactly [options].
 *
 * Writes once, reads the quaternion back (the getter is correct), and if the runtime reordered it
 * writes it again pre-reordered.
 */
internal fun LightManager.writeShadowOptions(instance: EntityInstance, options: ShadowOptions) {
    val wanted = options.getTransform()
    setShadowOptions(instance, options)
    val stored = getShadowOptions(instance).getTransform()
    if (!stored.contentEquals(wanted)) {
        // Broken JNI: Java (x, y, z, w) lands natively as (w = x, x = y, y = z, z = w).
        // Sending (w, x, y, z) therefore stores the quaternion that was asked for.
        val fixed = options.copy().apply { setTransform(wanted[3], wanted[0], wanted[1], wanted[2]) }
        setShadowOptions(instance, fixed)
    }
}

/**
 * [requested] with every field the caller left at its `ShadowOptions()` value replaced by the
 * light's [native] value — what a working `ShadowOptions()` would have meant.
 *
 * On Filament 1.77 the no-argument constructor zeroes everything, so a field still equal to that
 * blank value is indistinguishable from an untouched one and takes the native default. On a
 * runtime where `ShadowOptions()` holds the native defaults this returns [requested] unchanged.
 */
internal fun mergeWithNativeDefaults(requested: ShadowOptions, native: ShadowOptions): ShadowOptions {
    val blank = ShadowOptions()
    fun <T> pick(value: T, blankValue: T, nativeValue: T) = if (value == blankValue) nativeValue else value
    return ShadowOptions().apply {
        mapSize = pick(requested.mapSize, blank.mapSize, native.mapSize)
        shadowCascades = pick(requested.shadowCascades, blank.shadowCascades, native.shadowCascades)
        setCascadeSplitPositions(
            pick(requested.cascadeSplitPositions0, blank.cascadeSplitPositions0, native.cascadeSplitPositions0),
            pick(requested.cascadeSplitPositions1, blank.cascadeSplitPositions1, native.cascadeSplitPositions1),
            pick(requested.cascadeSplitPositions2, blank.cascadeSplitPositions2, native.cascadeSplitPositions2),
        )
        constantBias = pick(requested.constantBias, blank.constantBias, native.constantBias)
        normalBias = pick(requested.normalBias, blank.normalBias, native.normalBias)
        shadowFar = pick(requested.shadowFar, blank.shadowFar, native.shadowFar)
        shadowNearHint = pick(requested.shadowNearHint, blank.shadowNearHint, native.shadowNearHint)
        shadowFarHint = pick(requested.shadowFarHint, blank.shadowFarHint, native.shadowFarHint)
        stable = pick(requested.stable, blank.stable, native.stable)
        lispsm = pick(requested.lispsm, blank.lispsm, native.lispsm)
        polygonOffsetConstant =
            pick(requested.polygonOffsetConstant, blank.polygonOffsetConstant, native.polygonOffsetConstant)
        polygonOffsetSlope = pick(requested.polygonOffsetSlope, blank.polygonOffsetSlope, native.polygonOffsetSlope)
        screenSpaceContactShadows = pick(
            requested.screenSpaceContactShadows, blank.screenSpaceContactShadows, native.screenSpaceContactShadows
        )
        stepCount = pick(requested.stepCount, blank.stepCount, native.stepCount)
        maxShadowDistance = pick(requested.maxShadowDistance, blank.maxShadowDistance, native.maxShadowDistance)
        vsm = ShadowOptions.Vsm().apply {
            elvsm = pick(requested.vsm.elvsm, blank.vsm.elvsm, native.vsm.elvsm)
            blurWidth = pick(requested.vsm.blurWidth, blank.vsm.blurWidth, native.vsm.blurWidth)
        }
        shadowBulbRadius = pick(requested.shadowBulbRadius, blank.shadowBulbRadius, native.shadowBulbRadius)
        val transform = if (requested.getTransform().contentEquals(blank.getTransform())) {
            native.getTransform()
        } else {
            requested.getTransform()
        }
        setTransform(transform)
        penumbraScale = pick(requested.penumbraScale, blank.penumbraScale, native.penumbraScale)
        penumbraRatioScale = pick(requested.penumbraRatioScale, blank.penumbraRatioScale, native.penumbraRatioScale)
        maxPenumbraRatio = pick(requested.maxPenumbraRatio, blank.maxPenumbraRatio, native.maxPenumbraRatio)
        maxSearchRadius = pick(requested.maxSearchRadius, blank.maxSearchRadius, native.maxSearchRadius)
    }
}

/** A field-by-field copy, so a write never mutates the caller's object. */
internal fun ShadowOptions.copy(): ShadowOptions {
    val source = this
    return ShadowOptions().apply {
        mapSize = source.mapSize
        shadowCascades = source.shadowCascades
        setCascadeSplitPositions(
            source.cascadeSplitPositions0, source.cascadeSplitPositions1, source.cascadeSplitPositions2
        )
        constantBias = source.constantBias
        normalBias = source.normalBias
        shadowFar = source.shadowFar
        shadowNearHint = source.shadowNearHint
        shadowFarHint = source.shadowFarHint
        stable = source.stable
        lispsm = source.lispsm
        polygonOffsetConstant = source.polygonOffsetConstant
        polygonOffsetSlope = source.polygonOffsetSlope
        screenSpaceContactShadows = source.screenSpaceContactShadows
        stepCount = source.stepCount
        maxShadowDistance = source.maxShadowDistance
        vsm = ShadowOptions.Vsm().apply {
            elvsm = source.vsm.elvsm
            blurWidth = source.vsm.blurWidth
        }
        shadowBulbRadius = source.shadowBulbRadius
        setTransform(source.getTransform())
        penumbraScale = source.penumbraScale
        penumbraRatioScale = source.penumbraRatioScale
        maxPenumbraRatio = source.maxPenumbraRatio
        maxSearchRadius = source.maxSearchRadius
    }
}

/**
 * The [LightManager.Builder] every `LightNode(type) { … }` block runs on.
 *
 * It holds back `shadowOptions(...)` instead of handing it to the broken binding; the node writes
 * those options itself once the light exists (merged with the native defaults, quaternion checked).
 */
internal class ShadowSafeLightBuilder(type: LightManager.Type) : LightManager.Builder(type) {
    var requestedShadowOptions: ShadowOptions? = null
        private set

    override fun shadowOptions(options: ShadowOptions): LightManager.Builder {
        requestedShadowOptions = options.copy()
        return this
    }
}

package io.github.sceneview.demo.common

import com.google.android.filament.LightManager
import io.github.sceneview.components.LightComponent

/**
 * Tweaks this light's shadow options on top of the values Filament already holds for it.
 *
 * Use this instead of `LightManager.Builder.shadowOptions(LightManager.ShadowOptions().apply {…})`
 * while SceneView runs Filament 1.77.x, whose generated Java bindings (google/filament#10565,
 * fixed in 1.79.0) break that call in two ways, each of which makes directional shadows vanish:
 * - `ShadowOptions()` starts with every field at 0, including `mapSize` and the `transform`
 *   quaternion, instead of the native defaults;
 * - the JNI setter hands the `transform` quaternion to native code in the wrong component order,
 *   so the identity becomes a 180° turn about Z and the shadow points away from the receivers.
 *
 * So: build the light with `castShadows(true)` and no `shadowOptions(...)`, then call this from
 * `nodeApply`. It reads the native options back (the getter is correct, so every untouched field
 * keeps its real default), applies [block], writes them, and reads them once more: if the runtime
 * reordered the quaternion it writes it again pre-reordered, so the native value is the one asked
 * for. On a runtime with the fix the read-back matches and nothing more happens — the helper is
 * correct on both sides of the 1.79 bump.
 */
fun LightComponent.updateShadowOptions(block: LightManager.ShadowOptions.() -> Unit) {
    val instance = lightInstance
    val options = lightManager.getShadowOptions(instance).apply(block)
    val wanted = options.getTransform()
    lightManager.setShadowOptions(instance, options)

    val stored = lightManager.getShadowOptions(instance).getTransform()
    if (!stored.contentEquals(wanted)) {
        // Broken JNI: Java (x, y, z, w) lands natively as (w = x, x = y, y = z, z = w).
        // Sending (w, x, y, z) therefore stores the quaternion that was asked for.
        options.setTransform(wanted[3], wanted[0], wanted[1], wanted[2])
        lightManager.setShadowOptions(instance, options)
    }
}

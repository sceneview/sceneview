package io.github.sceneview.web.bindings

// -----------------------------------------------------------------------
// Helper functions for creating Filament.js array types.
//
// Filament.js uses number[] (JavaScript arrays) for float3, float4, mat4, etc.
// These helpers create the arrays in a Kotlin/JS-friendly way.
//
// NOTE: These MUST NOT be in the @JsModule("filament") file (Filament.kt)
// because non-external declarations are not allowed in @JsModule files.
// -----------------------------------------------------------------------

/**
 * Create a float3 array [x, y, z] for Filament.js API calls.
 *
 * Used by Camera.lookAt, LightManager.Builder.direction/position/color, etc.
 */
fun float3(x: Double, y: Double, z: Double): dynamic {
    val arr = js("[]")
    arr.push(x, y, z)
    return arr
}

/**
 * Create a float4 array [x, y, z, w] for Filament.js API calls.
 *
 * Used by Renderer.setClearOptions, Skybox.setColor, etc.
 */
fun float4(x: Double, y: Double, z: Double, w: Double): dynamic {
    val arr = js("[]")
    arr.push(x, y, z, w)
    return arr
}

/**
 * Create a viewport array [x, y, width, height] for View.setViewport().
 */
fun viewport(x: Int, y: Int, width: Int, height: Int): dynamic {
    val arr = js("[]")
    arr.push(x, y, width, height)
    return arr
}

/**
 * The `Camera$Fov.VERTICAL` direction enum, resolved from the runtime Filament
 * global.
 *
 * `Camera.setProjectionFov(...)` is a raw embind method with a STRICT arity of
 * 5 — it throws `function Camera.setProjectionFov called with 4 arguments,
 * expected 5 args!` if the trailing `fov` direction is omitted, even though the
 * Kotlin binding gives that parameter a `definedExternally` default (which only
 * means "JS supplies the default", NOT "the arg may be dropped at the call
 * site"). So every `setProjectionFov` call MUST pass this explicitly — the same
 * `Filament.Camera$Fov.VERTICAL` the hand-authored sceneview.js passes.
 *
 * A function (not a top-level `val`) so it is resolved lazily on first call,
 * after `Filament.init()` has populated the global — never at module load. The
 * `\$` escapes Kotlin string-template interpolation; the JS identifier is
 * literally `Filament.Camera$Fov.VERTICAL`.
 */
fun fovVertical(): dynamic = js("Filament.Camera\$Fov.VERTICAL")

package io.github.sceneview.web.splat

import io.github.sceneview.web.bindings.Engine
import io.github.sceneview.web.bindings.Entity
import io.github.sceneview.web.bindings.IndexBufferBuilder
import io.github.sceneview.web.bindings.RenderableManagerBuilder
import io.github.sceneview.web.bindings.TextureBuilder
import io.github.sceneview.web.bindings.VertexBufferBuilder
import org.khronos.webgl.Float32Array
import org.khronos.webgl.Uint8Array
import org.khronos.webgl.set

// -----------------------------------------------------------------------
// Lazily-resolved Filament.js accessors for the splat renderer (#2646 P2).
//
// Every static / enum below lives on the runtime `Filament` global, which the
// `external` bindings must NOT capture at module load (they would be
// `undefined` before `Filament.init()` — the #2410 class of bug). Each helper
// is a FUNCTION resolved on first call, the exact pattern of
// `bindings/FilamentHelpers.kt` (`fovVertical()`); `\$` escapes Kotlin string
// interpolation, the JS identifier really contains `$`.
//
// NOT in a `@JsModule` file — non-external declarations are not allowed there.
// -----------------------------------------------------------------------

/** `Filament.Texture.Builder()` — lazy static access, typed via `unsafeCast`. */
internal fun textureBuilder(): TextureBuilder =
    js("Filament.Texture.Builder()").unsafeCast<TextureBuilder>()

/** `Filament.VertexBuffer.Builder()`. */
internal fun vertexBufferBuilder(): VertexBufferBuilder =
    js("Filament.VertexBuffer.Builder()").unsafeCast<VertexBufferBuilder>()

/** `Filament.IndexBuffer.Builder()`. */
internal fun indexBufferBuilder(): IndexBufferBuilder =
    js("Filament.IndexBuffer.Builder()").unsafeCast<IndexBufferBuilder>()

/** `Filament.RenderableManager.Builder(1)` — one primitive (the shared unit quad). */
internal fun renderableManagerBuilder(): RenderableManagerBuilder =
    js("Filament.RenderableManager.Builder(1)").unsafeCast<RenderableManagerBuilder>()

/** A fresh entity from the runtime global (see `SceneView.newEntity` for the rationale). */
internal fun newSplatEntity(): Entity =
    js("Filament.EntityManager.get().create()").unsafeCast<Entity>()

internal fun sampler2d(): dynamic = js("Filament.Texture\$Sampler.SAMPLER_2D")
internal fun formatRgba16f(): dynamic = js("Filament.Texture\$InternalFormat.RGBA16F")
internal fun vertexAttributePosition(): dynamic = js("Filament.VertexAttribute.POSITION")
internal fun attributeTypeFloat3(): dynamic = js("Filament.VertexBuffer\$AttributeType.FLOAT3")
internal fun indexTypeUshort(): dynamic = js("Filament.IndexBuffer\$IndexType.USHORT")
internal fun primitiveTypeTriangles(): dynamic = js("Filament.RenderableManager\$PrimitiveType.TRIANGLES")

/**
 * NEAREST/NEAREST + CLAMP_TO_EDGE sampler for the two per-splat data textures — any
 * filtering would blend the attributes of neighbouring splats into garbage (the Android
 * `MaterialLoader.createSplatInstance` contract).
 */
internal fun nearestClampSampler(): dynamic = js(
    "new Filament.TextureSampler(" +
        "Filament.MinFilter.NEAREST, Filament.MagFilter.NEAREST, Filament.WrapMode.CLAMP_TO_EDGE)"
)

/**
 * Wraps [data] in a `Filament.PixelBuffer(RGBA, FLOAT)` descriptor for `Texture.setImage`.
 * FLOAT32 pixel data into an RGBA16F texture: the backend converts on upload (core
 * WebGL2), which keeps the packing plain-Kotlin testable — the same trade the Android
 * `SplatNode.uploadTextures` makes.
 */
internal fun pixelBufferFloat(data: Float32Array): dynamic {
    val d: dynamic = data
    return js(
        "Filament.PixelBuffer(d, Filament.PixelDataFormat.RGBA, Filament.PixelDataType.FLOAT)"
    )
}

/**
 * A `Filament.Box`-shaped JS object (`{center, halfExtent}`) from the
 * `[cx, cy, cz, hx, hy, hz]` array [SplatWebBuffers.boundingBox] returns.
 */
internal fun filamentBox(box: FloatArray): dynamic {
    val b = js("{}")
    val center = js("[]")
    center.push(box[0], box[1], box[2])
    val halfExtent = js("[]")
    halfExtent.push(box[3], box[4], box[5])
    b["center"] = center
    b["halfExtent"] = halfExtent
    return b
}

/**
 * Decodes the base64 material blob into the `Uint8Array` VIEW
 * `Engine.createMaterial` expects (a bare ArrayBuffer throws an embind
 * BindingError — the same rule as gltfio's `createAsset`, see `loadModel`).
 * Runs once per engine, 22 KB — `window.atob` is plenty.
 */
internal fun base64ToUint8Array(base64: String): Uint8Array {
    // Call `atob` as a resolved function so `base64` is a real Kotlin argument
    // (a `js("atob(base64)")` string hides the use from static analysis).
    val decode = js("atob").unsafeCast<(String) -> String>()
    val binary = decode(base64)
    val bytes = Uint8Array(binary.length)
    for (i in 0 until binary.length) {
        bytes[i] = binary.asDynamic().charCodeAt(i).unsafeCast<Int>().toByte()
    }
    return bytes
}

/** Creates the splat material from the embedded blob ([SPLAT_WEB_MATERIAL_BASE64]). */
internal fun createSplatMaterial(engine: Engine) =
    engine.createMaterial(base64ToUint8Array(SPLAT_WEB_MATERIAL_BASE64))

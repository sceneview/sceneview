package io.github.sceneview.material

import com.google.android.filament.Colors
import com.google.android.filament.MaterialInstance
import com.google.android.filament.MaterialInstance.FloatElement
import com.google.android.filament.Texture
import com.google.android.filament.TextureSampler
import dev.romainguy.kotlin.math.Float2
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Float4
import dev.romainguy.kotlin.math.Mat3
import dev.romainguy.kotlin.math.Mat4
import io.github.sceneview.math.Color
import io.github.sceneview.math.colorOf
import io.github.sceneview.math.toColumnsFloatArray
import io.github.sceneview.texture.TextureSampler2D
import io.github.sceneview.texture.TextureSamplerExternal

const val kMaterialDefaultMetallic = 0.0f
const val kMaterialDefaultRoughness = 0.4f
const val kMaterialDefaultReflectance = 0.5f

fun MaterialInstance.setParameter(name: String, value: Float2) =
    setParameter(name, value.x, value.y)

fun MaterialInstance.setParameter(name: String, value: Float4) =
    setParameter(name, value.x, value.y, value.z, value.w)

fun MaterialInstance.setParameter(name: String, value: Mat3) =
    setParameter(name, FloatElement.FLOAT4, value.toColumnsFloatArray(), 0, 4)

fun MaterialInstance.setParameter(name: String, value: Mat4) =
    setParameter(name, FloatElement.FLOAT4, value.toColumnsFloatArray(), 0, 4)

fun MaterialInstance.setParameter(name: String, value: Float3) =
    setParameter(name, value.x, value.y, value.z)

fun MaterialInstance.setColor(
    name: String,
    color: Color,
    type: Colors.RgbaType = Colors.RgbaType.SRGB
) = setParameter(name, type, color.r, color.g, color.b, color.a)

fun MaterialInstance.setColor(
    name: String,
    color: Int,
    type: Colors.RgbaType = Colors.RgbaType.SRGB
) = setColor(name, colorOf(color), type)

fun MaterialInstance.setColor(
    name: String,
    color: androidx.compose.ui.graphics.Color,
    type: Colors.RgbaType = Colors.RgbaType.SRGB
) = setColor(name, colorOf(color), type)

///////////
// Texture
///////////

fun MaterialInstance.setTexture(
    name: String,
    texture: Texture,
    sampler: TextureSampler = TextureSampler2D()
) = setParameter(name, texture, sampler)

fun MaterialInstance.setExternalTexture(name: String, texture: Texture) =
    setParameter(name, texture, TextureSamplerExternal())

////////////////////
// Scene Semantics
////////////////////

/**
 * Binds the per-pixel semantic label raster on a **Scene Semantics overlay** material instance
 * (created by [io.github.sceneview.loaders.MaterialLoader.createSemanticsOverlayInstance]).
 *
 * [texture] must be a single-channel `R8` [Texture] whose red channel holds a `SemanticLabel`
 * ordinal (`0..11`) scaled to `[0, 1]`. Re-call this — or re-upload into the same [Texture] — on
 * every frame to refresh the overlay (#1868).
 */
fun MaterialInstance.setSemanticsTexture(
    texture: Texture,
    sampler: TextureSampler = TextureSampler2D()
) = setParameter("semanticTexture", texture, sampler)

/**
 * Sets the global blend factor on a **Scene Semantics overlay** material instance — `0f` shows
 * only the camera feed, `1f` shows the fully tinted overlay. Clamped to `[0, 1]` in-shader (#1868).
 */
fun MaterialInstance.setSemanticsOpacity(opacity: Float) = setParameter("opacity", opacity)

///////
// PBR
///////

fun MaterialInstance.setColor(color: Color, type: Colors.RgbaType = Colors.RgbaType.SRGB) =
    setColor("color", color, type)

fun MaterialInstance.setColor(color: Int, type: Colors.RgbaType = Colors.RgbaType.SRGB) =
    setColor(colorOf(color), type)

fun MaterialInstance.setColor(
    color: androidx.compose.ui.graphics.Color,
    type: Colors.RgbaType = Colors.RgbaType.SRGB
) = setColor(colorOf(color), type)

/**
 * The metallic property defines whether the surface is a metallic (conductor) or a non-metallic
 * (dielectric) surface.
 *
 * This property should be used as a binary value, set to either 0 or 1.
 * Intermediate values are only truly useful to create transitions between different types of
 * surfaces when using textures.
 *
 * The default value is 0.
 */
fun MaterialInstance.setMetallic(factor: Float) = setParameter("metallic", factor)

/**
 * The roughness property controls the perceived smoothness of the surface.
 *
 * When roughness is set to 0, the surface is perfectly smooth and highly glossy.
 * The rougher a surface is, the "blurrier" the reflections are.
 *
 * The default value is 0.4.
 */
fun MaterialInstance.setRoughness(factor: Float) = setParameter("roughness", factor)

/**
 * The reflectance property only affects non-metallic surfaces.
 *
 * This property can be used to control the specular intensity. This value is defined between 0 and
 * 1 and represents a remapping of a percentage of reflectance.
 *
 * The default value is 0.5.
 */
fun MaterialInstance.setReflectance(factor: Float) = setParameter("reflectance", factor)

fun MaterialInstance.setTexture(texture: Texture, sampler: TextureSampler = TextureSampler2D()) =
    setTexture("texture", texture, sampler)

fun MaterialInstance.setExternalTexture(texture: Texture) = setExternalTexture("texture", texture)

fun MaterialInstance.setInvertFrontFaceWinding(
    invert: Boolean,
    uvOffsetParamName: String = "uvOffset"
) = setParameter(uvOffsetParamName, if (invert) Float2(1.0f, 0.0f) else Float2(0.0f))
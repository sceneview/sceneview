package io.github.sceneview.core.obj

import io.github.sceneview.core.threemf.ThreeMfUnit

/**
 * A parsed OBJ in Y-up axes and [unit], before conversion to metres.
 *
 * ```kotlin
 * val model = ObjLoader.parse(bytes)
 * val triangles = model.triangleCount
 * ```
 *
 * @property unit Caller-specified length unit; OBJ declares none. Defaults to millimetres in the loader.
 * @property groups Nonempty object/group combinations, in first-face order.
 * @property materials MTL material names mapped to linear RGBA factors; missing names use grey.
 */
class ObjModel(
    val unit: ThreeMfUnit,
    val groups: List<ObjGroup>,
    val materials: Map<String, ObjMaterial>
) {
    /** Total triangles after polygon triangulation. Example: `model.triangleCount`. */
    val triangleCount: Int get() = groups.sumOf { it.triangleCount }
}

/**
 * One OBJ object/group combination, emitted as one GLB mesh with primitives split by material.
 * Vertices are de-indexed so independent position/UV/normal indices and normal seams survive.
 *
 * ```kotlin
 * val group = ObjLoader.parse(bytes).groups.first()
 * val firstPosition = group.positions.take(3)
 * ```
 *
 * @property name Object and group names joined with `/`; ungrouped faces use `default`.
 * @property positions Three floats per corner, nine per triangle, in the caller's units.
 * @property normals Unit normals per corner, supplied by `vn` or computed from adjacent faces.
 * @property textureCoordinates Two floats per corner in OBJ's UV convention, or null without `vt`.
 * Missing UVs in a mixed mesh are zero. Texture images are not loaded yet.
 * @property triangleMaterials One `usemtl` name per triangle, null before the first assignment.
 */
class ObjGroup(
    val name: String,
    val positions: FloatArray,
    val normals: FloatArray,
    val textureCoordinates: FloatArray?,
    val triangleMaterials: List<String?>
) {
    /** Number of triangles. Example: `group.triangleCount`. */
    val triangleCount: Int get() = positions.size / 9
}

/**
 * MTL diffuse colour and opacity, used directly as glTF's linear `baseColorFactor`.
 *
 * ```kotlin
 * val rgba = ObjLoader.parse(bytes, resolver = resolveSibling).materials["paint"]?.baseColorFactor
 * ```
 *
 * @property baseColorFactor Four floats: `Kd` red/green/blue and `d` (or `1 - Tr`) alpha in [0, 1].
 * If both opacity directives occur, the last wins. No sRGB conversion or 8-bit quantisation occurs.
 */
class ObjMaterial(val baseColorFactor: FloatArray)

/**
 * Invalid OBJ geometry or malformed numeric data, with a line number when available.
 * Example: `runCatching { ObjLoader.parse(bytes) }.exceptionOrNull() is ObjParseException`.
 */
class ObjParseException(message: String) : RuntimeException(message)

internal fun objError(message: String): Nothing = throw ObjParseException(message)

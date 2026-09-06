package io.github.sceneview.core.obj

import io.github.sceneview.core.threemf.ThreeMfUnit

/**
 * Reads Wavefront **OBJ + MTL** in pure Kotlin on Android, iOS and web, without dependencies.
 * Positions, UVs, normals, all face index forms, negative indices and simple planar polygons are
 * supported. Each nonempty object/group combination becomes one mesh; `usemtl` splits primitives.
 * MTL `Kd`, `d` and `Tr` supply colours. **Textures are not yet supported**: `map_Kd` logs and skips.
 *
 * ```kotlin
 * // Scope the resolver to the directory containing the OBJ; return null for missing siblings.
 * val glb = ObjLoader.toGlb(objBytes, unit = ThreeMfUnit.MILLIMETER) { relativePath ->
 *     siblingFiles[relativePath]
 * }
 * ```
 *
 * OBJ has no unit metadata. [ThreeMfUnit] is shared with the 3MF loader; millimetres are the default.
 * Conversion scales to metres and keeps Y-up. Android `ModelLoader` automatically detects small
 * OBJ headers, loading grey geometry; call [toGlb] explicitly for MTL colours or another unit.
 */
object ObjLoader {
    /**
     * Conservatively sniff at most 4096 bytes: a complete `v ` line followed by a complete `f ` line,
     * skipping comments and blank lines. Tabs, CRLF and a UTF-8 BOM are accepted. Unknown directives
     * before the first face reject the sniff, so JSON glTF and GLB are never treated as OBJ.
     * Large files whose first face falls outside the prefix require explicit conversion.
     * Example: `if (ObjLoader.isObj(bytes)) ObjLoader.toGlb(bytes) else bytes`.
     */
    fun isObj(bytes: ByteArray): Boolean {
        val limit = minOf(bytes.size, 4096)
        var at = objStart(bytes)
        var vertex = false
        while (at < limit) {
            val start = at
            while (at < limit && bytes[at] != 10.toByte() && bytes[at] != 13.toByte()) at++
            if (at == limit && limit < bytes.size) return false
            val tokens = ObjTokens(bytes.decodeToString(start, at))
            when (val command = tokens.next()) {
                null -> Unit
                "v" -> {
                    repeat(3) { if (tokens.next()?.toFloatOrNull()?.isFinite() != true) return false }
                    vertex = true
                }
                "f" -> return vertex && tokens.next() != null && tokens.next() != null && tokens.next() != null
                "vn", "vt", "o", "g", "mtllib", "usemtl", "s", "l", "p" -> Unit
                else -> return false
            }
            at++
        }
        return false
    }

    /**
     * Parse into de-indexed triangle groups, preserving Y-up coordinates in [unit]. Missing normals
     * are area-weighted smooth normals at shared position indices (across UV/material/group seams).
     * Explicit normals are normalised and retained; smoothing directives `s` are currently ignored.
     * Degenerate or zero normals fall back to +Y. Simple concave polygons use ear clipping.
     *
     * [resolver] receives each `mtllib` path relative to the OBJ's directory, with `/` separators.
     * The caller owns I/O and base-path resolution. Null resolver/results mean grey; resolver
     * exceptions propagate. Multiple whitespace-separated libraries are supported; filenames with
     * spaces must be quoted. Unknown directives are skipped. `map_Kd` never invokes the resolver.
     *
     * Example: `val model = ObjLoader.parse(bytes, ThreeMfUnit.METER) { siblings[it] }`.
     * @throws ObjParseException for invalid geometry, indices or recognised numeric fields.
     */
    fun parse(
        bytes: ByteArray,
        unit: ThreeMfUnit = ThreeMfUnit.Default,
        resolver: ((String) -> ByteArray?)? = null
    ): ObjModel = ObjParser.parse(bytes, unit, resolver)

    /**
     * Produce a self-contained GLB in Y-up metres, with double-sided diffuse materials and alpha
     * blending for `d < 1` or `Tr > 0`. Missing MTL colours use opaque grey; textures are not yet
     * loaded. Uses the shared GLB writer, including aligned JSON/BIN chunks.
     * Example: `val glb = ObjLoader.toGlb(bytes, ThreeMfUnit.INCH) { siblings[it] }`.
     * @throws ObjParseException if [parse] cannot read the geometry or numeric data.
     */
    fun toGlb(
        bytes: ByteArray,
        unit: ThreeMfUnit = ThreeMfUnit.Default,
        resolver: ((String) -> ByteArray?)? = null
    ): ByteArray = toGlb(parse(bytes, unit, resolver))

    /** Convert parsed data with its stored unit. Example: `ObjLoader.toGlb(ObjLoader.parse(bytes))`. */
    fun toGlb(model: ObjModel): ByteArray = ObjGlb.encode(model)
}

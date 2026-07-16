package io.github.sceneview.core.splat

import kotlin.math.exp

/**
 * Parser for INRIA-style 3D Gaussian Splatting **PLY** files (graphdeco-inria/gaussian-splatting).
 *
 * The vertex element carries `x y z` (position), optional `nx ny nz` (normals, ignored), `f_dc_0..2`
 * (SH degree-0 DC color), optional `f_rest_*` (higher SH bands, ignored in P1), `opacity`,
 * `scale_0..2` (log-scale) and `rot_0..3` (quaternion, **scalar-first `w, x, y, z`**).
 *
 * Only `format binary_little_endian 1.0` is supported. Property byte offsets are computed from the
 * header, so reordered properties and extra properties are handled without hardcoded offsets.
 * Activations applied to match [SplatCloud]: `scale = exp(log-scale)`, `color = 0.5 + SH_C0 * f_dc`
 * (clamped), `opacity = sigmoid(raw)`, quaternion reordered to `x, y, z, w` and normalized.
 */
internal object PlyParser {

    private class Property(val name: String, val type: String, val offset: Int)

    fun parse(bytes: ByteArray): SplatCloud {
        val header = parseHeader(bytes)
        val props = header.propsByName

        val ix = header.require("x"); val iy = header.require("y"); val iz = header.require("z")
        val idc0 = header.require("f_dc_0"); val idc1 = header.require("f_dc_1"); val idc2 = header.require("f_dc_2")
        val iopacity = header.require("opacity")
        val is0 = header.require("scale_0"); val is1 = header.require("scale_1"); val is2 = header.require("scale_2")
        // INRIA stores the quaternion scalar-first: rot_0 = w, rot_1 = x, rot_2 = y, rot_3 = z.
        val iw = header.require("rot_0"); val irx = header.require("rot_1")
        val iry = header.require("rot_2"); val irz = header.require("rot_3")

        val count = header.vertexCount
        val stride = header.stride
        val needed = header.dataStart.toLong() + count.toLong() * stride
        if (needed > bytes.size) {
            splatError("PLY: binary data truncated (need $needed bytes, have ${bytes.size})")
        }

        val positions = FloatArray(count * 3)
        val scales = FloatArray(count * 3)
        val rotations = FloatArray(count * 4)
        val colors = FloatArray(count * 3)
        val opacities = FloatArray(count)

        for (i in 0 until count) {
            val base = header.dataStart + i * stride
            positions[i * 3] = readProperty(bytes, base, props[ix])
            positions[i * 3 + 1] = readProperty(bytes, base, props[iy])
            positions[i * 3 + 2] = readProperty(bytes, base, props[iz])

            scales[i * 3] = exp(readProperty(bytes, base, props[is0]))
            scales[i * 3 + 1] = exp(readProperty(bytes, base, props[is1]))
            scales[i * 3 + 2] = exp(readProperty(bytes, base, props[is2]))

            rotations[i * 4] = readProperty(bytes, base, props[irx])
            rotations[i * 4 + 1] = readProperty(bytes, base, props[iry])
            rotations[i * 4 + 2] = readProperty(bytes, base, props[irz])
            rotations[i * 4 + 3] = readProperty(bytes, base, props[iw])
            SplatMath.normalizeQuaternion(rotations, i * 4)

            colors[i * 3] = SplatMath.dcToLinearColor(readProperty(bytes, base, props[idc0]))
            colors[i * 3 + 1] = SplatMath.dcToLinearColor(readProperty(bytes, base, props[idc1]))
            colors[i * 3 + 2] = SplatMath.dcToLinearColor(readProperty(bytes, base, props[idc2]))

            opacities[i] = SplatMath.sigmoid(readProperty(bytes, base, props[iopacity]))
        }
        return SplatCloud(count, positions, scales, rotations, colors, opacities)
    }

    private class Header(
        val vertexCount: Int,
        val stride: Int,
        val dataStart: Int,
        val props: List<Property>,
    ) {
        val propsByName: List<Property> get() = props
        private val nameToIndex = props.withIndex().associate { (i, p) -> p.name to i }
        fun require(name: String): Int =
            nameToIndex[name] ?: splatError("PLY: missing required property '$name'")
    }

    @Suppress("CyclomaticComplexMethod")
    private fun parseHeader(bytes: ByteArray): Header {
        var pos = 0
        var sawPly = false
        var format: String? = null
        var vertexCount = -1
        var currentElement: String? = null
        val vertexProps = ArrayList<Property>()
        var runningOffset = 0
        var sawEndHeader = false

        while (!sawEndHeader) {
            val lineStart = pos
            while (pos < bytes.size && bytes[pos].toInt() != '\n'.code) pos++
            if (pos >= bytes.size) splatError("PLY: header not terminated (no end_header)")
            var lineEnd = pos
            if (lineEnd > lineStart && bytes[lineEnd - 1].toInt() == '\r'.code) lineEnd--
            val line = bytes.decodeToString(lineStart, lineEnd).trim()
            pos++ // consume '\n'

            // A blank header line matches no case below and is simply skipped.
            val tokens = line.split(WHITESPACE)
            when (tokens[0]) {
                "ply" -> sawPly = true
                "format" -> format = tokens.getOrNull(1)
                "comment", "obj_info" -> Unit
                "element" -> {
                    currentElement = tokens.getOrNull(1)
                    if (currentElement == "vertex") {
                        vertexCount = tokens.getOrNull(2)?.toIntOrNull()
                            ?: splatError("PLY: invalid vertex count in '$line'")
                    }
                }
                "property" -> if (currentElement == "vertex") {
                    if (tokens.getOrNull(1) == "list") {
                        splatError("PLY: list properties are unsupported in the vertex element")
                    }
                    val type = tokens.getOrNull(1)
                        ?: splatError("PLY: malformed property line '$line'")
                    val name = tokens.getOrNull(2)
                        ?: splatError("PLY: malformed property line '$line'")
                    vertexProps.add(Property(name, type, runningOffset))
                    runningOffset += typeSize(type)
                }
                "end_header" -> sawEndHeader = true
            }
        }

        if (!sawPly) splatError("PLY: missing 'ply' magic")
        if (format == null) splatError("PLY: missing format line")
        if (format != "binary_little_endian") {
            splatError("PLY: only 'binary_little_endian' is supported, got '$format'")
        }
        if (vertexCount < 0) splatError("PLY: missing 'element vertex' declaration")
        return Header(vertexCount, runningOffset, pos, vertexProps)
    }

    private fun readProperty(bytes: ByteArray, base: Int, p: Property): Float {
        val at = base + p.offset
        return when (p.type) {
            "float", "float32" -> readLeFloat(bytes, at)
            "double", "float64" -> readLeDouble(bytes, at).toFloat()
            "char", "int8" -> bytes[at].toFloat()
            "uchar", "uint8" -> (bytes[at].toInt() and 0xFF).toFloat()
            "short", "int16" -> readLe16Signed(bytes, at).toFloat()
            "ushort", "uint16" -> readLe16(bytes, at).toFloat()
            "int", "int32" -> readLe32(bytes, at).toFloat()
            "uint", "uint32" -> (readLe32(bytes, at).toLong() and 0xFFFFFFFFL).toFloat()
            else -> splatError("PLY: unsupported property type '${p.type}'")
        }
    }

    private fun typeSize(type: String): Int = when (type) {
        "char", "int8", "uchar", "uint8" -> 1
        "short", "int16", "ushort", "uint16" -> 2
        "int", "int32", "uint", "uint32", "float", "float32" -> 4
        "double", "float64" -> 8
        else -> splatError("PLY: unsupported property type '$type'")
    }

    private val WHITESPACE = Regex("\\s+")
}

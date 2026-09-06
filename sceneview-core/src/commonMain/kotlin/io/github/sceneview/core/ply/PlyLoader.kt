package io.github.sceneview.core.ply

import io.github.sceneview.core.threemf.FloatArrayBuilder
import io.github.sceneview.core.threemf.IntArrayBuilder
import io.github.sceneview.core.threemf.ThreeMfGlb
import io.github.sceneview.core.threemf.ThreeMfUnit
import kotlin.math.sqrt

/** Pure-Kotlin ASCII and binary PLY reader for Android, Apple and web. */
object PlyLoader {
    const val DEFAULT_MAX_BYTES: Int = 32 * 1024 * 1024
    const val DEFAULT_MAX_VERTICES: Int = 1_000_000
    const val DEFAULT_MAX_TRIANGLES: Int = 1_000_000

    /** Cheap signature check. Validation is performed by [parse]. */
    fun isPly(bytes: ByteArray): Boolean =
        bytes.startsWith("ply\n") || bytes.startsWith("ply\r\n")

    /** Parse vertices and fan-triangulated faces, assuming millimetres when PLY declares no unit. */
    fun parse(
        bytes: ByteArray,
        unit: ThreeMfUnit = ThreeMfUnit.MILLIMETER,
        maxVertices: Int = DEFAULT_MAX_VERTICES,
        maxTriangles: Int = DEFAULT_MAX_TRIANGLES,
        maxBytes: Int = DEFAULT_MAX_BYTES
    ): PlyModel {
        if (maxVertices <= 0 || maxTriangles <= 0) plyError("PLY limits must be positive")
        if (maxBytes <= 0) plyError("PLY limits must be positive")
        if (bytes.size > maxBytes) plyError("PLY input exceeds $maxBytes bytes")
        if (!isPly(bytes)) plyError("PLY header must start with ply")
        val header = readHeader(bytes)
        val vertexElement = header.elements.firstOrNull { it.name == "vertex" }
            ?: plyError("PLY has no vertex element")
        val faceElement = header.elements.firstOrNull { it.name == "face" }
            ?: plyError("PLY has no face element")
        if (vertexElement.count > maxVertices) plyError("PLY exceeds $maxVertices vertices")
        val builder = PlyBuilder(vertexElement.count, maxTriangles, unit)
        val reader = PlyDataReader(bytes, header.dataOffset, header.format)
        for (element in header.elements) {
            repeat(element.count) {
                when (element.name) {
                    "vertex" -> builder.addVertex(element, reader)
                    "face" -> builder.addFace(element, reader)
                    else -> reader.skip(element)
                }
            }
        }
        reader.finish()
        if (faceElement.count == 0) plyError("PLY contains no faces")
        return builder.build()
    }

    /** Convert PLY to a self-contained GLB, scaling the caller-selected [unit] to metres. */
    fun toGlb(
        bytes: ByteArray,
        unit: ThreeMfUnit = ThreeMfUnit.MILLIMETER,
        maxVertices: Int = DEFAULT_MAX_VERTICES,
        maxTriangles: Int = DEFAULT_MAX_TRIANGLES,
        maxBytes: Int = DEFAULT_MAX_BYTES
    ): ByteArray {
        val model = parse(bytes, unit, maxVertices, maxTriangles, maxBytes)
        return ThreeMfGlb.encodeIndexed(
            model.positions,
            model.indices,
            model.normals,
            unit.meters,
            model.colors,
            "SceneView PLY loader",
            "ply"
        )
    }
}

private enum class PlyFormat { ASCII, LITTLE_ENDIAN, BIG_ENDIAN }

private enum class PlyType(val bytes: Int, val floating: Boolean, val unsigned: Boolean) {
    INT8(1, false, false), UINT8(1, false, true), INT16(2, false, false),
    UINT16(2, false, true), INT32(4, false, false), UINT32(4, false, true),
    FLOAT32(4, true, false), FLOAT64(8, true, false)
}

private data class PlyProperty(val name: String, val type: PlyType, val countType: PlyType? = null)
private data class PlyElement(val name: String, val count: Int, val properties: List<PlyProperty>)
private data class PlyHeader(val format: PlyFormat, val elements: List<PlyElement>, val dataOffset: Int)

private fun readHeader(bytes: ByteArray): PlyHeader {
    var at = 0
    var format: PlyFormat? = null
    val elements = ArrayList<PlyElement>()
    var currentName: String? = null
    var currentCount = 0
    var properties = ArrayList<PlyProperty>()
    while (at < bytes.size) {
        val end = bytes.lineEnd(at)
        val line = bytes.decodeToString(at, end).trimEnd('\r')
        at = if (end < bytes.size) end + 1 else end
        val words = line.splitWhitespace()
        when (words.firstOrNull()) {
            "ply", "comment", "obj_info" -> Unit
            "format" -> {
                if (words.size != 3 || words[2] != "1.0") plyError("Unsupported PLY format line")
                format = when (words[1]) {
                    "ascii" -> PlyFormat.ASCII
                    "binary_little_endian" -> PlyFormat.LITTLE_ENDIAN
                    "binary_big_endian" -> PlyFormat.BIG_ENDIAN
                    else -> plyError("Unsupported PLY encoding ${words[1]}")
                }
            }
            "element" -> {
                currentName?.let { elements += PlyElement(it, currentCount, properties) }
                if (words.size != 3) plyError("Malformed PLY element")
                currentName = words[1]
                currentCount = words[2].toIntOrNull() ?: plyError("Invalid PLY element count")
                if (currentCount < 0) plyError("Invalid PLY element count")
                properties = ArrayList()
            }
            "property" -> properties += parseProperty(words)
            "end_header" -> {
                currentName?.let { elements += PlyElement(it, currentCount, properties) }
                return PlyHeader(format ?: plyError("PLY format is missing"), elements, at)
            }
            else -> plyError("Unknown PLY header directive ${words.firstOrNull()}")
        }
    }
    plyError("PLY end_header is missing")
}

private fun parseProperty(words: List<String>): PlyProperty {
    if (words.getOrNull(1) == "list") {
        if (words.size != 5) plyError("Malformed PLY list property")
        return PlyProperty(words[4], plyType(words[3]), plyType(words[2]))
    }
    if (words.size != 3) plyError("Malformed PLY property")
    return PlyProperty(words[2], plyType(words[1]))
}

private fun plyType(name: String): PlyType = when (name) {
    "char", "int8" -> PlyType.INT8
    "uchar", "uint8" -> PlyType.UINT8
    "short", "int16" -> PlyType.INT16
    "ushort", "uint16" -> PlyType.UINT16
    "int", "int32" -> PlyType.INT32
    "uint", "uint32" -> PlyType.UINT32
    "float", "float32" -> PlyType.FLOAT32
    "double", "float64" -> PlyType.FLOAT64
    else -> plyError("Unsupported PLY property type $name")
}

private class PlyDataReader(
    private val bytes: ByteArray,
    private var at: Int,
    private val format: PlyFormat
) {
    fun scalar(type: PlyType): Double = when (format) {
        PlyFormat.ASCII -> token().toDoubleOrNull() ?: plyError("Invalid PLY number")
        PlyFormat.LITTLE_ENDIAN -> binary(type, littleEndian = true)
        PlyFormat.BIG_ENDIAN -> binary(type, littleEndian = false)
    }

    fun integer(type: PlyType): Long {
        if (type.floating) plyError("PLY list count and indices must be integer")
        val value = scalar(type)
        if (!value.isFinite()) plyError("Invalid PLY integer")
        if (value != value.toLong().toDouble()) plyError("Invalid PLY integer")
        return value.toLong()
    }

    fun values(property: PlyProperty): DoubleArray {
        val countType = property.countType ?: return doubleArrayOf(scalar(property.type))
        val count = integer(countType)
        if (count < 0 || count > Int.MAX_VALUE) plyError("Invalid PLY list count")
        return DoubleArray(count.toInt()) { scalar(property.type) }
    }

    fun skip(element: PlyElement) {
        for (property in element.properties) values(property)
    }

    fun finish() {
        if (format == PlyFormat.ASCII) {
            while (at < bytes.size && bytes[at].toInt().toChar().isWhitespace()) at++
            if (at != bytes.size) plyError("Unexpected data after PLY body")
        } else if (at != bytes.size) {
            plyError("Unexpected data after PLY body")
        }
    }

    private fun token(): String {
        while (at < bytes.size && bytes[at].toInt().toChar().isWhitespace()) at++
        if (at == bytes.size) plyError("Truncated PLY body")
        val start = at
        while (at < bytes.size && !bytes[at].toInt().toChar().isWhitespace()) at++
        if (at - start > 128) plyError("PLY token is too long")
        return bytes.decodeToString(start, at)
    }

    private fun binary(type: PlyType, littleEndian: Boolean): Double {
        if (at > bytes.size - type.bytes) plyError("Truncated PLY binary body")
        var bits = 0UL
        repeat(type.bytes) { index ->
            val source = if (littleEndian) at + index else at + type.bytes - 1 - index
            bits = bits or ((bytes[source].toULong() and 0xffUL) shl (index * 8))
        }
        at += type.bytes
        return when (type) {
            PlyType.INT8 -> bits.toByte().toDouble()
            PlyType.UINT8 -> bits.toUByte().toDouble()
            PlyType.INT16 -> bits.toShort().toDouble()
            PlyType.UINT16 -> bits.toUShort().toDouble()
            PlyType.INT32 -> bits.toInt().toDouble()
            PlyType.UINT32 -> bits.toUInt().toDouble()
            PlyType.FLOAT32 -> Float.fromBits(bits.toInt()).toDouble()
            PlyType.FLOAT64 -> Double.fromBits(bits.toLong())
        }
    }
}

private class PlyBuilder(
    private val declaredVertices: Int,
    private val maxTriangles: Int,
    private val unit: ThreeMfUnit
) {
    private val positions = FloatArrayBuilder(declaredVertices * 3)
    private val vertexNormals = FloatArrayBuilder(declaredVertices * 3)
    private val colors = FloatArrayBuilder(declaredVertices * 4)
    private val indices = IntArrayBuilder()
    private var hasNormals: Boolean? = null
    private var hasColors: Boolean? = null

    fun addVertex(element: PlyElement, reader: PlyDataReader) {
        val values = HashMap<String, Double>()
        for (property in element.properties) {
            val propertyValues = reader.values(property)
            if (property.countType != null) plyError("PLY vertex properties cannot be lists")
            values[property.name] = propertyValues[0]
        }
        addPosition(values)
        addNormal(values)
        addColor(values)
    }

    fun addFace(element: PlyElement, reader: PlyDataReader) {
        var face: DoubleArray? = null
        for (property in element.properties) {
            val values = reader.values(property)
            if (property.name == "vertex_indices" || property.name == "vertex_index") face = values
        }
        val vertices = face ?: plyError("PLY face has no vertex_indices property")
        if (vertices.size < 3) plyError("PLY face must contain at least three vertices")
        val added = vertices.size - 2
        if (indices.size / 3 > maxTriangles - added) plyError("PLY exceeds $maxTriangles triangles")
        for (corner in 1 until vertices.lastIndex) {
            addIndex(vertices[0])
            addIndex(vertices[corner])
            addIndex(vertices[corner + 1])
        }
    }

    fun build(): PlyModel {
        if (positions.size / 3 != declaredVertices) plyError("PLY vertex count mismatch")
        if (indices.size == 0) plyError("PLY contains no triangles")
        val points = positions.toArray()
        val triangles = indices.toArray()
        val normals = if (hasNormals == true) {
            indexedNormals(vertexNormals.toArray(), triangles)
        } else {
            smoothNormals(points, triangles)
        }
        return PlyModel(points, triangles, normals, colors.toArray().takeIf { hasColors == true }, unit)
    }

    private fun addPosition(values: Map<String, Double>) {
        for (name in listOf("x", "y", "z")) {
            val value = values[name]?.toFloat() ?: plyError("PLY vertex is missing $name")
            if (!value.isFinite()) plyError("PLY position must be finite")
            if (!(value * unit.meters).isFinite()) plyError("PLY position overflows in metres")
            positions.add(if (value == 0f) 0f else value)
        }
    }

    private fun addNormal(values: Map<String, Double>) {
        val present = listOf("nx", "ny", "nz").map { values[it] != null }
        if (present.any { it } && !present.all { it }) plyError("PLY vertex normal is incomplete")
        val supplied = present.all { it }
        if (hasNormals != null && hasNormals != supplied) plyError("Inconsistent PLY vertex normals")
        hasNormals = supplied
        if (supplied) {
            val x = values.getValue("nx")
            val y = values.getValue("ny")
            val z = values.getValue("nz")
            val length = sqrt(x * x + y * y + z * z)
            if (length == 0.0 || !length.isFinite()) plyError("PLY normal must be finite and non-zero")
            vertexNormals.add((x / length).toFloat())
            vertexNormals.add((y / length).toFloat())
            vertexNormals.add((z / length).toFloat())
        }
    }

    private fun addColor(values: Map<String, Double>) {
        val present = listOf("red", "green", "blue").map { values[it] != null }
        if (present.any { it } && !present.all { it }) plyError("PLY vertex colour is incomplete")
        val supplied = present.all { it }
        if (hasColors != null && hasColors != supplied) plyError("Inconsistent PLY vertex colours")
        hasColors = supplied
        if (supplied) {
            for (name in listOf("red", "green", "blue", "alpha")) {
                val value = values[name] ?: 255.0
                if (value < 0.0 || value > 255.0) plyError("PLY colour must be in 0..255")
                colors.add((value / 255.0).toFloat())
            }
        }
    }

    private fun addIndex(value: Double) {
        if (!value.isFinite() || value != value.toLong().toDouble()) plyError("Invalid PLY vertex index")
        val index = value.toLong()
        if (index < 0 || index >= declaredVertices) plyError("PLY vertex index out of range")
        indices.add(index.toInt())
    }

    private fun indexedNormals(normals: FloatArray, triangles: IntArray): FloatArray =
        FloatArray(triangles.size * 3) { at ->
            normals[triangles[at / 3] * 3 + at % 3]
        }

    private fun smoothNormals(points: FloatArray, triangles: IntArray): FloatArray {
        val sums = DoubleArray(points.size)
        for (triangle in triangles.indices step 3) {
            val a = triangles[triangle] * 3
            val b = triangles[triangle + 1] * 3
            val c = triangles[triangle + 2] * 3
            val ux = points[b].toDouble() - points[a]
            val uy = points[b + 1].toDouble() - points[a + 1]
            val uz = points[b + 2].toDouble() - points[a + 2]
            val vx = points[c].toDouble() - points[a]
            val vy = points[c + 1].toDouble() - points[a + 1]
            val vz = points[c + 2].toDouble() - points[a + 2]
            for (vertex in intArrayOf(a, b, c)) {
                sums[vertex] += uy * vz - uz * vy
                sums[vertex + 1] += uz * vx - ux * vz
                sums[vertex + 2] += ux * vy - uy * vx
            }
        }
        return FloatArray(triangles.size * 3).also { out ->
            for (corner in triangles.indices) writeNormal(out, corner * 3, sums, triangles[corner] * 3)
        }
    }

    private fun writeNormal(out: FloatArray, at: Int, sums: DoubleArray, source: Int) {
        val length = sqrt(
            sums[source] * sums[source] + sums[source + 1] * sums[source + 1] +
                sums[source + 2] * sums[source + 2]
        )
        if (length > 0.0 && length.isFinite()) {
            repeat(3) { out[at + it] = (sums[source + it] / length).toFloat() }
        } else {
            out[at + 1] = 1f
        }
    }
}

private fun ByteArray.startsWith(text: String): Boolean {
    if (size < text.length) return false
    return text.indices.all { this[it] == text[it].code.toByte() }
}

private fun ByteArray.lineEnd(start: Int): Int {
    var at = start
    while (at < size && this[at] != '\n'.code.toByte()) at++
    return at
}

private fun String.splitWhitespace(): List<String> = trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

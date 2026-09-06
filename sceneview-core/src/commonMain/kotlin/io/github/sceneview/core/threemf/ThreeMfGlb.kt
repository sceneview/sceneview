package io.github.sceneview.core.threemf

import kotlin.math.pow

/**
 * Encodes a parsed [ThreeMfModel] as a self-contained **GLB** (binary glTF 2.0) byte array.
 *
 * This is what makes 3MF a first-class SceneView format for the cost of one converter instead of
 * one loader per renderer: Filament (Android), Filament.js (web) and every other glTF consumer in
 * this SDK already read GLB, so a 3MF becomes an ordinary model the moment it leaves this file.
 *
 * Three conversions happen here, and each is a correctness requirement, not a preference:
 * - **Units.** 3MF is a manufacturing format carrying real-world size; glTF is metres. The root
 *   node scales by [ThreeMfUnit.meters], so a 60 mm print is 0.06 units — the size AR placement
 *   needs to be believable.
 * - **Axes.** 3MF is Z-up (the printer's build plate); glTF is Y-up. The root node rotates −90°
 *   about X, so the print stands upright instead of lying on its back.
 * - **Normals.** 3MF stores none. Faces are de-indexed and given per-face normals: flat shading is
 *   the honest look for a print — a smoothed normal would round over the facets the slicer will
 *   actually extrude.
 */
internal object ThreeMfGlb {

    fun encode(model: ThreeMfModel): ByteArray {
        val builder = GltfBuilder()
        builder.addRootNode(model)
        return builder.toGlb()
    }

<<<<<<< HEAD
    internal class GltfBuilder(private val generator: String = Generator) {
=======
    /**
     * Shared STL path: positions are welded by the parser; per-corner normals preserve valid
     * facet normals and carry smooth repairs. Split only (position, normal) seams for glTF's
     * single index stream. Bake units into positions and preserve axes (STL declares no up axis).
     * Accessors, materials, JSON and aligned GLB assembly remain shared with the flat 3MF path.
     */
    fun encodeIndexed(
        positions: FloatArray,
        indices: IntArray,
        cornerNormals: FloatArray,
        meters: Float
    ): ByteArray {
        val builder = GltfBuilder("SceneView STL loader")
        builder.addIndexedMesh(positions, indices, cornerNormals, meters)
        return builder.toGlb()
    }

    private data class NormalVertex(val position: Int, val x: Float, val y: Float, val z: Float)

    private class GltfBuilder(private val generator: String = Generator) {
>>>>>>> 084ae99b2 (feat(core): STL loader, binary and ASCII, to GLB in memory (#3486))
        private val bin = ByteArrayBuilder()
        private val bufferViews = ArrayList<String>()
        private val accessors = ArrayList<String>()
        private val meshes = ArrayList<String>()
        private val nodes = ArrayList<String>()
        private val materials = ArrayList<String>()

        /** 3MF object id → glTF mesh index, so an object instanced twice is stored once. */
        private val meshIndexByObject = HashMap<Int, Int>()

        /** Packed colour → glTF material index. */
        private val materialIndexByColor = HashMap<Int, Int>()

        /** Shared assembly for Y-up formats: one named mesh/node per group, then a scaled root. */
        fun addMesh(name: String, primitives: List<String>) {
            meshes += """{"name":${name.toJsonString()},"primitives":${primitives.joinToString(",", "[", "]")}}"""
            nodes += """{"name":${name.toJsonString()},"mesh":${meshes.size - 1}}"""
        }

        fun addScaledRoot(name: String, scale: Float) {
            val children = nodes.indices.joinToString(",", "[", "]")
            val scaleJson = floatArrayOf(scale, scale, scale).toJsonArray()
            nodes += """{"name":${name.toJsonString()},"scale":$scaleJson,"children":$children}"""
        }

        /** Positions and supplied normals are already de-indexed; UVs are optional VEC2 values. */
        fun addPrimitive(positions: FloatArray, normals: FloatArray, uv: FloatArray?, material: Int): String {
            val position = addFloatAccessor(positions, withBounds = true)
            val normal = addFloatAccessor(normals, withBounds = false)
            val texture = uv?.let { ",\"TEXCOORD_0\":" + addFloatAccessor(it, false, 2) }.orEmpty()
            return """{"attributes":{"POSITION":$position,"NORMAL":$normal$texture},"material":$material}"""
        }

        fun addRootNode(model: ThreeMfModel) {
            val children = model.items.mapNotNull { item ->
                model.objectsById[item.objectId]?.let { addNode(model, it, item.transform) }
            }
            if (children.isEmpty()) threeMfError("3MF <build> references no printable object")
            val scale = model.unit.meters
            // Column-major: X keeps its axis, 3MF's Y becomes glTF's −Z, 3MF's Z becomes glTF's Y.
            val root = floatArrayOf(
                scale, 0f, 0f, 0f,
                0f, 0f, -scale, 0f,
                0f, scale, 0f, 0f,
                0f, 0f, 0f, 1f
            )
            nodes += """{"name":"3mf","matrix":${root.toJsonArray()},""" +
                """"children":${children.joinToString(",", "[", "]")}}"""
        }

        fun addIndexedMesh(
            source: FloatArray,
            indices: IntArray,
            cornerNormals: FloatArray,
            meters: Float
        ) {
            val vertices = HashMap<NormalVertex, Int>()
            val positions = FloatArrayBuilder()
            val normals = FloatArrayBuilder()
            val renderIndices = IntArray(indices.size)
            for (corner in indices.indices) {
                val at = corner * 3
                val key = NormalVertex(
                    indices[corner], cornerNormals[at], cornerNormals[at + 1], cornerNormals[at + 2]
                )
                renderIndices[corner] = vertices.getOrPut(key) {
                    val index = positions.size / 3
                    repeat(3) { axis ->
                        positions.add(source[key.position * 3 + axis] * meters)
                        normals.add(cornerNormals[at + axis])
                    }
                    index
                }
            }
            val positionAccessor = addFloatAccessor(positions.toArray(), withBounds = true)
            val normalAccessor = addFloatAccessor(normals.toArray(), withBounds = false)
            val indexAccessor = addIndexAccessor(renderIndices)
            meshes += """{"primitives":[{"attributes":{"POSITION":$positionAccessor,"NORMAL":$normalAccessor},""" +
                """"indices":$indexAccessor,"material":${materialIndex(NoColor)}}]}"""
            nodes += """{"name":"stl","mesh":0}"""
        }

        private fun addIndexAccessor(indices: IntArray): Int {
            val byteOffset = bin.size
            for (index in indices) bin.addIntLe(index)
            bufferViews += """{"buffer":0,"byteOffset":$byteOffset,""" +
                """"byteLength":${indices.size * Int.SIZE_BYTES},"target":34963}"""
            accessors += """{"bufferView":${bufferViews.size - 1},"componentType":5125,""" +
                """"count":${indices.size},"type":"SCALAR"}"""
            return accessors.size - 1
        }

        /** Emits the node for [object3mf] placed by [transform], recursing into its components. */
        private fun addNode(
            model: ThreeMfModel,
            object3mf: ThreeMfObject,
            transform: FloatArray,
            depth: Int = 0
        ): Int? {
            if (depth > MAX_COMPONENT_DEPTH) {
                threeMfError("3MF component nesting exceeds $MAX_COMPONENT_DEPTH levels (cycle?)")
            }
            val meshIndex = object3mf.mesh?.let { meshIndex(object3mf, it) }
            val children = object3mf.components.mapNotNull { component ->
                model.objectsById[component.objectId]
                    ?.let { addNode(model, it, component.transform, depth + 1) }
            }
            if (meshIndex == null && children.isEmpty()) return null
            val fields = ArrayList<String>(4)
            object3mf.name?.let { fields += """"name":${it.toJsonString()}""" }
            fields += """"matrix":${transform.toJsonArray()}"""
            meshIndex?.let { fields += """"mesh":$it""" }
            if (children.isNotEmpty()) {
                fields += """"children":${children.joinToString(",", "[", "]")}"""
            }
            nodes += fields.joinToString(",", "{", "}")
            return nodes.size - 1
        }

        private fun meshIndex(object3mf: ThreeMfObject, mesh: ThreeMfMesh): Int =
            meshIndexByObject.getOrPut(object3mf.id) {
                val primitives = groupByColor(mesh, object3mf.color).map { (color, triangles) ->
                    primitive(mesh, triangles, color)
                }
                meshes += """{"primitives":${primitives.joinToString(",", "[", "]")}}"""
                meshes.size - 1
            }

        /**
         * Split a mesh's triangles by resolved colour. glTF carries colour on the material, and a
         * primitive has exactly one material — so one primitive per distinct colour is the whole of
         * 3MF's per-triangle colouring, expressed natively.
         */
        private fun groupByColor(mesh: ThreeMfMesh, objectColor: Int): List<Pair<Int, IntArray>> {
            val colors = mesh.triangleColors
                ?: return listOf(objectColor to IntArray(mesh.triangleCount) { it })
            val groups = LinkedHashMap<Int, IntArrayBuilder>()
            for (triangle in 0 until mesh.triangleCount) {
                val color = colors.getOrElse(triangle) { objectColor }
                groups.getOrPut(color) { IntArrayBuilder() }.add(triangle)
            }
            return groups.map { (color, triangles) -> color to triangles.toArray() }
        }

        /** One glTF primitive: de-indexed positions + flat normals for [triangles]. */
        private fun primitive(mesh: ThreeMfMesh, triangles: IntArray, color: Int): String {
            val vertexCount = triangles.size * 3
            val positions = FloatArray(vertexCount * 3)
            val normals = FloatArray(vertexCount * 3)
            fillFlatShaded(mesh, triangles, positions, normals)
            val positionAccessor = addFloatAccessor(positions, withBounds = true)
            val normalAccessor = addFloatAccessor(normals, withBounds = false)
            return """{"attributes":{"POSITION":$positionAccessor,"NORMAL":$normalAccessor},""" +
                """"material":${materialIndex(color)}}"""
        }

        private fun addFloatAccessor(values: FloatArray, withBounds: Boolean, components: Int = 3): Int {
            val byteOffset = bin.size
            for (value in values) bin.addFloatLe(value)
            bufferViews += """{"buffer":0,"byteOffset":$byteOffset,""" +
                """"byteLength":${values.size * Float.SIZE_BYTES},"target":$ARRAY_BUFFER}"""
            val count = values.size / components
            val bounds = if (withBounds) ""","min":${min(values)},"max":${max(values)}""" else ""
            accessors += """{"bufferView":${bufferViews.size - 1},"componentType":$FLOAT,""" +
                """"count":$count,"type":"VEC$components"$bounds}"""
            return accessors.size - 1
        }

        private fun materialIndex(color: Int): Int = materialIndexByColor.getOrPut(color) {
            val factor = if (color == NoColor) DefaultBaseColor else color.toLinearRgba()
            addMaterial(factor)
        }

        /** Linear RGBA factors also support OBJ's floating-point Kd and fully transparent black. */
        fun addMaterial(factor: FloatArray): Int {
            materials += """{"pbrMetallicRoughness":{"baseColorFactor":${factor.toJsonArray()},""" +
                """"metallicFactor":0,"roughnessFactor":$PrintRoughness},""" +
                // 3MF requires outward-facing counter-clockwise winding, but generated files often
                // get it wrong; a one-sided material would then render the print inside-out.
                """"doubleSided":true${alphaMode(factor[3])}}"""
            return materials.size - 1
        }

        private fun alphaMode(alpha: Float) = if (alpha < 1f) ""","alphaMode":"BLEND"""" else ""

        fun toGlb(): ByteArray {
            val json = buildJson()
            return packGlb(json, bin.toArray())
        }

        private fun buildJson(): String = buildString {
<<<<<<< HEAD
            append("""{"asset":{"version":"2.0","generator":${generator.toJsonString()}},""")
=======
            append("""{"asset":{"version":"2.0","generator":"$generator"},""")
>>>>>>> 084ae99b2 (feat(core): STL loader, binary and ASCII, to GLB in memory (#3486))
            append(""""scene":0,"scenes":[{"nodes":[${nodes.size - 1}]}],""")
            append(""""nodes":${nodes.joinToString(",", "[", "]")},""")
            append(""""meshes":${meshes.joinToString(",", "[", "]")},""")
            append(""""materials":${materials.joinToString(",", "[", "]")},""")
            append(""""accessors":${accessors.joinToString(",", "[", "]")},""")
            append(""""bufferViews":${bufferViews.joinToString(",", "[", "]")},""")
            append(""""buffers":[{"byteLength":${bin.size}}]}""")
        }
    }

    /**
     * De-index [triangles] of [mesh] into [positions] and give each face its own normal.
     *
     * A degenerate triangle (zero-area, which slicers do emit) gets a `+Y` normal instead of a
     * `NaN` one — a single NaN vertex would otherwise blow up the whole primitive's bounding box
     * and make the model invisible.
     */
    private fun fillFlatShaded(
        mesh: ThreeMfMesh,
        triangles: IntArray,
        positions: FloatArray,
        normals: FloatArray
    ) {
        var out = 0
        for (triangle in triangles) {
            val base = triangle * 3
            val a = mesh.indices[base] * 3
            val b = mesh.indices[base + 1] * 3
            val c = mesh.indices[base + 2] * 3
            copyVertex(mesh.positions, a, positions, out)
            copyVertex(mesh.positions, b, positions, out + 3)
            copyVertex(mesh.positions, c, positions, out + 6)
            writeFaceNormal(mesh.positions, a, b, c, normals, out)
            out += 9
        }
    }

    private fun copyVertex(source: FloatArray, from: Int, target: FloatArray, to: Int) {
        target[to] = source[from]
        target[to + 1] = source[from + 1]
        target[to + 2] = source[from + 2]
    }

    private fun writeFaceNormal(
        source: FloatArray,
        a: Int,
        b: Int,
        c: Int,
        normals: FloatArray,
        at: Int
    ) {
        val ux = source[b] - source[a]
        val uy = source[b + 1] - source[a + 1]
        val uz = source[b + 2] - source[a + 2]
        val vx = source[c] - source[a]
        val vy = source[c + 1] - source[a + 1]
        val vz = source[c + 2] - source[a + 2]
        var nx = uy * vz - uz * vy
        var ny = uz * vx - ux * vz
        var nz = ux * vy - uy * vx
        val length = kotlin.math.sqrt(nx * nx + ny * ny + nz * nz)
        if (length > 0f && length.isFinite()) {
            nx /= length
            ny /= length
            nz /= length
        } else {
            nx = 0f
            ny = 1f
            nz = 0f
        }
        for (corner in 0 until 3) {
            normals[at + corner * 3] = nx
            normals[at + corner * 3 + 1] = ny
            normals[at + corner * 3 + 2] = nz
        }
    }

    /** Wrap [json] and [bin] in the GLB container: 12-byte header, JSON chunk, BIN chunk. */
    private fun packGlb(json: String, bin: ByteArray): ByteArray {
        val jsonBytes = json.encodeToByteArray().padTo4(' '.code.toByte())
        val binBytes = bin.padTo4(0)
        val total = GLB_HEADER_SIZE + CHUNK_HEADER_SIZE + jsonBytes.size +
            CHUNK_HEADER_SIZE + binBytes.size
        val out = ByteArrayBuilder(total)
        out.addIntLe(GLB_MAGIC)
        out.addIntLe(GLB_VERSION)
        out.addIntLe(total)
        out.addIntLe(jsonBytes.size)
        out.addIntLe(CHUNK_JSON)
        out.addBytes(jsonBytes)
        out.addIntLe(binBytes.size)
        out.addIntLe(CHUNK_BIN)
        out.addBytes(binBytes)
        return out.toArray()
    }

    private fun ByteArray.padTo4(filler: Byte): ByteArray {
        val padding = (4 - size % 4) % 4
        if (padding == 0) return this
        return copyOf(size + padding).also { for (i in size until it.size) it[i] = filler }
    }

    private fun min(values: FloatArray) = componentBound(values) { a, b -> minOf(a, b) }

    private fun max(values: FloatArray) = componentBound(values) { a, b -> maxOf(a, b) }

    private fun componentBound(values: FloatArray, pick: (Float, Float) -> Float): String {
        if (values.size < 3) return floatArrayOf(0f, 0f, 0f).toJsonArray()
        val bound = FloatArray(3) { values[it] }
        for (i in values.indices) bound[i % 3] = pick(bound[i % 3], values[i])
        return bound.toJsonArray()
    }

    /** sRGB `0xRRGGBBAA` → linear RGBA, the space a glTF `baseColorFactor` is defined in. */
    private fun Int.toLinearRgba(): FloatArray {
        val r = ((this ushr 24) and 0xFF) / 255f
        val g = ((this ushr 16) and 0xFF) / 255f
        val b = ((this ushr 8) and 0xFF) / 255f
        val a = (this and 0xFF) / 255f
        return floatArrayOf(srgbToLinear(r), srgbToLinear(g), srgbToLinear(b), a)
    }

    private fun srgbToLinear(channel: Float): Float =
        if (channel <= 0.04045f) channel / 12.92f else ((channel + 0.055f) / 1.055f).pow(2.4f)

    private fun FloatArray.toJsonArray(): String =
        joinToString(",", "[", "]") { it.toJsonNumber() }

    /**
     * Format a float as a JSON number, identically on every target.
     *
     * `Float.toString()` cannot be used: Kotlin/JS backs a `Float` with a JS double, so `0.001f`
     * prints as `0.001000000047497451` there and `0.001` on the JVM — the same 3MF would produce
     * two different GLBs, and a JSON six times larger in the browser. This rounds to float32's
     * ~7 significant digits and trims, so the output is both stable and compact.
     */
    private fun Float.toJsonNumber(): String {
        if (!isFinite() || this == 0f) return "0"
        val magnitude = kotlin.math.abs(toDouble())
        val exponent = kotlin.math.floor(kotlin.math.log10(magnitude)).toInt()
        if (exponent < -MAX_DECIMALS || exponent > 9) {
            val mantissa = (toDouble() / 10.0.pow(exponent)).toFloat()
            return "${mantissa.toJsonNumber()}e$exponent"
        }
        val decimals = (SIGNIFICANT_DIGITS - 1 - exponent).coerceIn(0, MAX_DECIMALS)
        val scale = POWERS_OF_TEN[decimals]
        val scaled = kotlin.math.round(magnitude * scale.toDouble()).toLong()
        val whole = scaled / scale
        val fraction = (scaled % scale).toString().padStart(decimals, '0').trimEnd('0')
        val sign = if (this < 0f) "-" else ""
        return if (fraction.isEmpty()) "$sign$whole" else "$sign$whole.$fraction"
    }

    private fun String.toJsonString(): String = buildString {
        append('"')
        for (char in this@toJsonString) {
            when {
                char == '"' -> append("\\\"")
                char == '\\' -> append("\\\\")
                char < ' ' -> append("\\u").append(char.code.toString(16).padStart(4, '0'))
                else -> append(char)
            }
        }
        append('"')
    }

    private const val Generator = "SceneView 3MF loader"
    private val DefaultBaseColor = floatArrayOf(0.62f, 0.64f, 0.68f, 1f)

    /** A printed part is matte, never a mirror — written literally so no float formatting applies. */
    private const val PrintRoughness = "0.55"

    private const val SIGNIFICANT_DIGITS = 7
    private const val MAX_DECIMALS = 9
    private val POWERS_OF_TEN = LongArray(MAX_DECIMALS + 1) { exponent ->
        var value = 1L
        repeat(exponent) { value *= 10 }
        value
    }
    private const val MAX_COMPONENT_DEPTH = 32
    private const val FLOAT = 5126
    private const val ARRAY_BUFFER = 34962
    private const val GLB_MAGIC = 0x46546C67 // "glTF"
    private const val GLB_VERSION = 2
    private const val GLB_HEADER_SIZE = 12
    private const val CHUNK_HEADER_SIZE = 8
    private const val CHUNK_JSON = 0x4E4F534A // "JSON"
    private const val CHUNK_BIN = 0x004E4942 // "BIN\0"
}

/** Growable little-endian byte sink for the GLB payload. */
internal class ByteArrayBuilder(initialCapacity: Int = 4096) {
    private var data = ByteArray(maxOf(initialCapacity, 16))
    var size = 0
        private set

    fun addIntLe(value: Int) {
        ensure(4)
        data[size++] = (value and 0xFF).toByte()
        data[size++] = ((value ushr 8) and 0xFF).toByte()
        data[size++] = ((value ushr 16) and 0xFF).toByte()
        data[size++] = ((value ushr 24) and 0xFF).toByte()
    }

    fun addFloatLe(value: Float) = addIntLe(value.toRawBits())

    fun addBytes(bytes: ByteArray) {
        ensure(bytes.size)
        bytes.copyInto(data, size)
        size += bytes.size
    }

    private fun ensure(extra: Int) {
        if (size + extra <= data.size) return
        var capacity = data.size
        while (size + extra > capacity) capacity = capacity shl 1
        data = data.copyOf(capacity)
    }

    fun toArray(): ByteArray = data.copyOf(size)
}

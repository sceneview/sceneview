package io.github.sceneview.demo.demos.internal

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow
import kotlin.math.tan

/**
 * Accumulates the glTF document and its binary chunk for [RerunGlbWriter]: the iOS demo's
 * `GLTFBuilder`, entity for entity. Merged models live in `RerunGlbModel.kt`.
 */
internal class RerunGltfBuilder(private val codec: RerunImageCodec?) {
    val bin = ByteArrayOutputStream()
    val bufferViews = ArrayList<JsonMap>()
    val accessors = ArrayList<JsonMap>()
    val meshes = ArrayList<JsonMap>()
    val materials = ArrayList<JsonMap>()
    val textures = ArrayList<JsonMap>()
    val images = ArrayList<JsonMap>()
    val samplers = ArrayList<JsonMap>()
    val nodes = ArrayList<JsonMap>()
    val cameras = ArrayList<JsonMap>()
    val skins = ArrayList<JsonMap>()
    val animations = ArrayList<JsonMap>()
    val lights = ArrayList<JsonMap>()
    val extensionsUsed = sortedSetOf<String>()
    val extensionsRequired = sortedSetOf<String>()
    val credits = ArrayList<JsonMap>()
    private val namedMaterials = HashMap<String, Int>()
    private var textureSampler: Int? = null
    private var markerMesh: Int? = null

    // Buffers and accessors

    /** Appends [bytes] at the next 4-byte boundary of the binary chunk. */
    fun addBufferView(bytes: ByteArray, template: Map<String, Any?> = emptyMap()): Int {
        padBin()
        val view: JsonMap = LinkedHashMap(template)
        view["buffer"] = 0
        view["byteOffset"] = bin.size()
        view["byteLength"] = bytes.size
        bin.write(bytes)
        bufferViews += view
        return bufferViews.lastIndex
    }

    private fun padBin() {
        while (bin.size() % 4 != 0) bin.write(0)
    }

    /** A tightly packed float accessor; [bounds] adds the `min`/`max` glTF requires on `POSITION`. */
    fun addFloats(vectors: List<FloatArray>, type: String, bounds: Boolean): Int {
        val width = vectors.firstOrNull()?.size ?: 0
        val bytes = ByteBuffer.allocate(vectors.size * width * 4).order(ByteOrder.LITTLE_ENDIAN)
        vectors.forEach { vector -> vector.forEach { bytes.putFloat(it) } }
        val view = addBufferView(bytes.array(), mapOf("target" to ARRAY_BUFFER))
        val accessor = jsonOf(
            "bufferView" to view,
            "componentType" to FLOAT_COMPONENT,
            "count" to vectors.size,
            "type" to type,
        )
        val first = vectors.firstOrNull()
        if (bounds && first != null) {
            val low = first.copyOf()
            val high = first.copyOf()
            for (vector in vectors) {
                for (axis in vector.indices) {
                    low[axis] = minOf(low[axis], vector[axis])
                    high[axis] = maxOf(high[axis], vector[axis])
                }
            }
            accessor["min"] = low.map(::jsonNumber)
            accessor["max"] = high.map(::jsonNumber)
        }
        accessors += accessor
        return accessors.lastIndex
    }

    fun addVec3(values: List<Vec3>, bounds: Boolean): Int =
        addFloats(values.map { floatArrayOf(it.x, it.y, it.z) }, "VEC3", bounds)

    /** Triangle indices, `UNSIGNED_SHORT` when they fit. */
    fun addIndices(indices: IntArray): Int {
        val short = (indices.maxOrNull() ?: 0) < USHORT_MAX
        val bytes = ByteBuffer.allocate(indices.size * if (short) 2 else 4).order(ByteOrder.LITTLE_ENDIAN)
        indices.forEach { if (short) bytes.putShort(it.toShort()) else bytes.putInt(it) }
        val view = addBufferView(bytes.array(), mapOf("target" to ELEMENT_ARRAY_BUFFER))
        accessors += jsonOf(
            "bufferView" to view,
            "componentType" to if (short) UNSIGNED_SHORT else UNSIGNED_INT,
            "count" to indices.size,
            "type" to "SCALAR",
        )
        return accessors.lastIndex
    }

    // Nodes, meshes, materials

    fun addNode(node: JsonMap): Int {
        if ((node["children"] as? List<*>)?.isEmpty() == true) node.remove("children")
        nodes += node
        return nodes.lastIndex
    }

    fun addMesh(name: String, primitive: JsonMap): Int {
        meshes += jsonOf("name" to name, "primitives" to listOf(primitive))
        return meshes.lastIndex
    }

    /** An unlit material, created once per name. */
    fun unlitMaterial(name: String, color: FloatArray, blend: Boolean = false): Int {
        namedMaterials[name]?.let { return it }
        val material = jsonOf(
            "name" to name,
            "pbrMetallicRoughness" to jsonOf(
                "baseColorFactor" to color.map { it.toDouble() },
                "metallicFactor" to 0,
                "roughnessFactor" to 1,
            ),
            "doubleSided" to true,
            "extensions" to jsonOf(UNLIT to jsonOf()),
        )
        if (blend) material["alphaMode"] = "BLEND"
        extensionsUsed += UNLIT
        materials += material
        namedMaterials[name] = materials.lastIndex
        return materials.lastIndex
    }

    // Capture entities

    fun addPoints(points: List<Vec3>, colors: List<RerunExportScene.Rgb>): Int? {
        val positions = ArrayList<Vec3>(points.size)
        val linear = ArrayList<Vec3>(points.size)
        points.forEachIndexed { index, point ->
            if (!point.isFinite()) return@forEachIndexed
            positions += point
            val color = colors.getOrNull(index) ?: RerunExportScene.Rgb.White
            linear += Vec3(SRGB_TO_LINEAR[color.r], SRGB_TO_LINEAR[color.g], SRGB_TO_LINEAR[color.b])
        }
        if (positions.isEmpty()) return null
        val position = addVec3(positions, bounds = true)
        val color = addVec3(linear, bounds = false)
        val material = unlitMaterial("points", floatArrayOf(1f, 1f, 1f, 1f))
        val mesh = addMesh(
            "world/points",
            jsonOf(
                "attributes" to jsonOf("POSITION" to position, "COLOR_0" to color),
                "mode" to MODE_POINTS,
                "material" to material,
            ),
        )
        return addNode(jsonOf("name" to "world/points", "mesh" to mesh))
    }

    fun addCameraPath(path: List<RerunExportScene.CameraSample>): Int? {
        val positions = path.map { it.position }.filter { it.isFinite() }
        if (positions.size < 2) return null
        val position = addVec3(positions, bounds = true)
        val material = unlitMaterial("camera", CAMERA_COLOR)
        val mesh = addMesh(
            "world/camera/path",
            jsonOf("attributes" to jsonOf("POSITION" to position), "mode" to MODE_LINE_STRIP, "material" to material),
        )
        return addNode(jsonOf("name" to "world/camera/path", "mesh" to mesh))
    }

    /** One frustum per keyframe, [FRUSTUM_DEPTH] deep, shaped by the lens (3:4, 60° vertical without one). */
    fun addKeyframes(keyframes: List<RerunExportScene.Keyframe>, lens: RerunExportScene.Lens?): Int? {
        if (keyframes.isEmpty()) return null
        val corners = frustumCorners(lens ?: DEFAULT_LENS)
        val vertices = ArrayList<Vec3>()
        val entries = ArrayList<JsonMap>()
        for (keyframe in keyframes) {
            val pose = keyframe.pose
            val world = corners.map { pose.position + pose.orientation.act(it) }
            if (!pose.position.isFinite() || !world.all { it.isFinite() }) continue
            for (index in 0 until 4) {
                vertices += listOf(pose.position, world[index], world[index], world[(index + 1) % 4])
            }
            entries += jsonOf("time" to keyframe.time, "image" to keyframe.imagePath)
        }
        if (vertices.isEmpty()) return null
        val position = addVec3(vertices, bounds = true)
        val material = unlitMaterial("camera", CAMERA_COLOR)
        val mesh = addMesh(
            "world/camera/keyframes",
            jsonOf("attributes" to jsonOf("POSITION" to position), "mode" to MODE_LINES, "material" to material),
        )
        return addNode(
            jsonOf("name" to "world/camera/keyframes", "mesh" to mesh, "extras" to jsonOf("keyframes" to entries)),
        )
    }

    /** Image rows grow downwards, camera +Y is up, the camera looks down -Z. */
    private fun frustumCorners(lens: RerunExportScene.Lens): List<Vec3> {
        val w = lens.width.toFloat()
        val h = lens.height.toFloat()
        return listOf(0f to 0f, w to 0f, w to h, 0f to h).map { (px, py) ->
            Vec3(
                (px - lens.cx) / lens.fx * FRUSTUM_DEPTH,
                -(py - lens.cy) / lens.fy * FRUSTUM_DEPTH,
                -FRUSTUM_DEPTH,
            )
        }
    }

    /**
     * The polygon as a fan from its centroid, facing up for `horizontal_upward`, down for
     * `horizontal_downward`. Planes with fewer than three finite vertices are skipped.
     */
    fun addPlane(plane: RerunExportScene.Plane): Int? {
        val polygon = plane.polygon
        if (polygon.size < 3 || !polygon.all { it.isFinite() }) return null
        val sum = polygon.fold(Vec3.Zero) { acc, p -> acc + p }
        val n = polygon.size.toFloat()
        val centroid = Vec3(sum.x / n, sum.y / n, sum.z / n)
        val fan = PlaneFan.of(polygon, centroid, plane.kind)
        val positions = listOf(centroid) + polygon
        val attributes = jsonOf(
            "POSITION" to addVec3(positions, bounds = true),
            "NORMAL" to addVec3(List(positions.size) { fan.normal }, bounds = false),
        )
        val name = "world/planes/${plane.id}"
        val texture = plane.texture
        val uvs = texture?.let { textureCoordinates(positions, it) }
        val image = if (texture != null && uvs != null) codec?.transcode(texture.imageData) else null
        val material = if (uvs != null && image != null) {
            attributes["TEXCOORD_0"] = addFloats(uvs, "VEC2", bounds = false)
            texturedMaterial(name, image)
        } else {
            val (tintName, tint) = planeTint(plane.kind)
            unlitMaterial(tintName, tint, blend = true)
        }
        val mesh = addMesh(
            name,
            jsonOf(
                "attributes" to attributes,
                "indices" to addIndices(fan.indices),
                "mode" to MODE_TRIANGLES,
                "material" to material,
            ),
        )
        return addNode(jsonOf("name" to name, "mesh" to mesh, "extras" to jsonOf("kind" to plane.kind)))
    }

    private fun texturedMaterial(name: String, image: RerunImageCodec.Transcoded): Int {
        val view = addBufferView(image.data)
        images += jsonOf("name" to name, "mimeType" to image.mimeType, "bufferView" to view)
        val sampler = textureSampler ?: run {
            // Linear, mipmapped, clamped: the photo does not repeat past the plane.
            samplers += jsonOf("magFilter" to 9729, "minFilter" to 9987, "wrapS" to 33071, "wrapT" to 33071)
            samplers.lastIndex.also { textureSampler = it }
        }
        textures += jsonOf("source" to images.lastIndex, "sampler" to sampler)
        val material = jsonOf(
            "name" to name,
            "pbrMetallicRoughness" to jsonOf(
                "baseColorTexture" to jsonOf("index" to textures.lastIndex),
                "metallicFactor" to 0,
                "roughnessFactor" to 1,
            ),
            "doubleSided" to true,
            "extensions" to jsonOf(UNLIT to jsonOf()),
        )
        if (image.isTranslucent) material["alphaMode"] = "BLEND"
        extensionsUsed += UNLIT
        materials += material
        return materials.lastIndex
    }

    // Anchors

    /**
     * The anchor's node: its pose, and the model named by [RerunExportScene.Anchor.modelName]
     * merged under it when [models] holds its GLB (merged once, cached in [merged]), a small axes
     * marker otherwise.
     */
    fun addAnchor(
        anchor: RerunExportScene.Anchor,
        models: Map<String, ByteArray>,
        merged: MutableMap<String, RerunMergedModel?>,
    ): Int {
        val node = jsonOf("name" to "world/anchors/${anchor.id}")
        if (anchor.position.isFinite()) {
            node["translation"] = listOf(anchor.position.x, anchor.position.y, anchor.position.z).map(::jsonNumber)
        }
        val q = anchor.orientation
        val length = q.length
        if (length.isFinite() && length > 0f) {
            node["rotation"] = listOf(q.x / length, q.y / length, q.z / length, q.w / length).map(::jsonNumber)
        }
        val extras = jsonOf()
        val modelName = anchor.modelName
        var model: RerunMergedModel? = null
        if (modelName != null) {
            extras["model"] = modelName
            model = mergedModel(modelName, models, merged)
            extras["modelEmbedded"] = model != null
        }
        if (model != null && modelName != null) {
            node["children"] = listOf(addNode(modelHolder(modelName, model)))
        } else {
            node["mesh"] = anchorMarker()
        }
        if (extras.isNotEmpty()) node["extras"] = extras
        return addNode(node)
    }

    private fun mergedModel(
        name: String,
        models: Map<String, ByteArray>,
        merged: MutableMap<String, RerunMergedModel?>,
    ): RerunMergedModel? {
        if (merged.containsKey(name)) return merged[name]
        val data = models[name] ?: return null
        return merge(RerunGlbFile.parse(data, name), name).also { merged[name] = it }
    }

    /** Placed the way the demo shows it: longest side [RerunGlbWriter.PLACED_MODEL_SIZE], centred, standing. */
    private fun modelHolder(name: String, model: RerunMergedModel): JsonMap {
        val holder = jsonOf("name" to name, "children" to instantiate(model))
        val bounds = model.bounds ?: return holder
        val (low, high) = bounds
        val extent = maxOf(high.x - low.x, high.y - low.y, high.z - low.z)
        val scale = RerunGlbWriter.PLACED_MODEL_SIZE / extent
        val centre = Vec3((low.x + high.x) / 2f, (low.y + high.y) / 2f, (low.z + high.z) / 2f)
        holder["scale"] = listOf(scale, scale, scale).map(::jsonNumber)
        holder["translation"] = listOf(-centre.x * scale, -low.y * scale, -centre.z * scale).map(::jsonNumber)
        return holder
    }

    /** RGB axes, [MARKER_SIZE] long: +X red, +Y green, +Z blue. */
    private fun anchorMarker(): Int {
        markerMesh?.let { return it }
        val axes = listOf(Vec3(1f, 0f, 0f), Vec3(0f, 1f, 0f), Vec3(0f, 0f, 1f))
        val positions = axes.flatMap { listOf(Vec3.Zero, it * MARKER_SIZE) }
        val colors = axes.flatMap { listOf(it, it) }
        val material = unlitMaterial("anchor", floatArrayOf(1f, 1f, 1f, 1f))
        val mesh = addMesh(
            "anchor",
            jsonOf(
                "attributes" to jsonOf(
                    "POSITION" to addVec3(positions, bounds = true),
                    "COLOR_0" to addVec3(colors, bounds = false),
                ),
                "mode" to MODE_LINES,
                "material" to material,
            ),
        )
        markerMesh = mesh
        return mesh
    }

    // Container

    /** The GLB container: 12-byte header, JSON chunk padded with spaces, BIN chunk padded with zeros. */
    fun glb(title: String, lens: RerunExportScene.Lens?, root: Int): ByteArray {
        padBin()
        val binary = bin.toByteArray()
        val json = RerunJson.write(document(title, lens, root, binary.size)).toByteArray(Charsets.UTF_8)
        val jsonLength = (json.size + 3) / 4 * 4
        val total = GLB_HEADER + CHUNK_HEADER + jsonLength + if (binary.isEmpty()) 0 else CHUNK_HEADER + binary.size
        val out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(GLTF_MAGIC).putInt(2).putInt(total)
        out.putInt(jsonLength).putInt(JSON_CHUNK).put(json)
        repeat(jsonLength - json.size) { out.put(SPACE) }
        if (binary.isNotEmpty()) out.putInt(binary.size).putInt(BIN_CHUNK).put(binary)
        return out.array()
    }

    private fun document(title: String, lens: RerunExportScene.Lens?, root: Int, binaryLength: Int): JsonMap {
        val assetExtras = jsonOf("title" to title, "upAxis" to "Y", "units" to "metres")
        if (lens != null) {
            assetExtras["lens"] = jsonOf(
                "width" to lens.width,
                "height" to lens.height,
                "fx" to jsonNumber(lens.fx),
                "fy" to jsonNumber(lens.fy),
                "cx" to jsonNumber(lens.cx),
                "cy" to jsonNumber(lens.cy),
            )
        }
        if (credits.isNotEmpty()) assetExtras["credits"] = credits
        val document = jsonOf(
            "asset" to jsonOf("version" to "2.0", "generator" to "SceneView", "extras" to assetExtras),
            "scene" to 0,
            "scenes" to listOf(jsonOf("name" to title, "nodes" to listOf(root))),
        )
        val arrays = listOf(
            "nodes" to nodes, "meshes" to meshes, "materials" to materials, "textures" to textures,
            "images" to images, "samplers" to samplers, "accessors" to accessors, "bufferViews" to bufferViews,
            "cameras" to cameras, "skins" to skins, "animations" to animations,
        )
        for ((key, value) in arrays) if (value.isNotEmpty()) document[key] = value
        if (binaryLength > 0) document["buffers"] = listOf(jsonOf("byteLength" to binaryLength))
        if (extensionsUsed.isNotEmpty()) document["extensionsUsed"] = extensionsUsed.toList()
        if (extensionsRequired.isNotEmpty()) document["extensionsRequired"] = extensionsRequired.toList()
        if (lights.isNotEmpty()) document["extensions"] = jsonOf("KHR_lights_punctual" to jsonOf("lights" to lights))
        return document
    }

    /** A plane's fan: its facing normal and triangle indices, wound to face it. */
    private class PlaneFan(val normal: Vec3, val indices: IntArray) {
        companion object {
            fun of(polygon: List<Vec3>, centroid: Vec3, kind: String): PlaneFan {
                // Newell's normal follows the polygon's winding.
                var newell = Vec3.Zero
                for (index in polygon.indices) {
                    val a = polygon[index] - centroid
                    val b = polygon[(index + 1) % polygon.size] - centroid
                    newell += a.cross(b)
                }
                val wanted = when (kind) {
                    "horizontal_upward" -> 1f
                    "horizontal_downward" -> -1f
                    else -> 0f
                }
                val flip = wanted != 0f && newell.y * wanted < 0f
                val length = newell.length()
                var normal = if (length > 0f) {
                    Vec3(newell.x / length, newell.y / length, newell.z / length)
                } else {
                    Vec3(0f, if (wanted < 0f) -1f else 1f, 0f)
                }
                if (flip) normal = normal * -1f
                val indices = IntArray(polygon.size * 3)
                for (index in polygon.indices) {
                    val b = index + 1
                    val c = (index + 1) % polygon.size + 1
                    indices[index * 3 + 1] = if (flip) c else b
                    indices[index * 3 + 2] = if (flip) b else c
                }
                return PlaneFan(normal, indices)
            }
        }
    }

    companion object {
        private const val ARRAY_BUFFER = 34962
        private const val ELEMENT_ARRAY_BUFFER = 34963
        private const val FLOAT_COMPONENT = 5126
        private const val UNSIGNED_SHORT = 5123
        private const val UNSIGNED_INT = 5125
        private const val USHORT_MAX = 65535
        private const val MODE_POINTS = 0
        private const val MODE_LINES = 1
        private const val MODE_LINE_STRIP = 3
        private const val MODE_TRIANGLES = 4
        private const val UNLIT = "KHR_materials_unlit"
        private const val FRUSTUM_DEPTH = 0.12f
        private const val MARKER_SIZE = 0.1f
        private const val GLB_HEADER = 12
        private const val CHUNK_HEADER = 8
        private const val GLTF_MAGIC = 0x46546C67
        private const val JSON_CHUNK = 0x4E4F534A
        private const val BIN_CHUNK = 0x004E4942
        private const val SPACE: Byte = 0x20
        private val CAMERA_COLOR = floatArrayOf(1f, 0.62f, 0.1f, 1f)

        /** 3:4 portrait, 60° vertical field of view. */
        private val DEFAULT_LENS = (2f / tan(Math.PI.toFloat() / 6f)).let { f ->
            RerunExportScene.Lens(width = 3, height = 4, fx = f, fy = f, cx = 1.5f, cy = 2f)
        }

        /** sRGB byte to linear float, glTF's `COLOR_0` space. */
        private val SRGB_TO_LINEAR = FloatArray(256) { byte ->
            val value = byte / 255f
            if (value <= 0.04045f) value / 12.92f else ((value + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()
        }

        /** Untextured planes: a translucent tint per kind (linear RGBA). */
        private fun planeTint(kind: String): Pair<String, FloatArray> = when (kind) {
            "horizontal_upward" -> "plane-floor" to floatArrayOf(0.15f, 0.45f, 1f, 0.35f)
            "horizontal_downward" -> "plane-ceiling" to floatArrayOf(1f, 0.6f, 0.15f, 0.35f)
            "vertical" -> "plane-wall" to floatArrayOf(0.2f, 0.8f, 0.35f, 0.35f)
            else -> "plane-unknown" to floatArrayOf(0.6f, 0.6f, 0.6f, 0.35f)
        }

        /** [RerunExportScene.PlaneTexture]'s formula; `null` for a degenerate edge. */
        fun textureCoordinates(positions: List<Vec3>, texture: RerunExportScene.PlaneTexture): List<FloatArray>? {
            val uu = texture.u.dot(texture.u)
            val vv = texture.v.dot(texture.v)
            val valid = uu > 0f && vv > 0f && uu.isFinite() && vv.isFinite()
            if (!valid) return null
            return positions.map { point ->
                val offset = point - texture.origin
                floatArrayOf(offset.dot(texture.u) / uu, offset.dot(texture.v) / vv)
            }
        }

        /** [value] widened to the exact double: a reader gets back the very float in the buffer. */
        fun jsonNumber(value: Float): Double = value.toDouble()
    }
}

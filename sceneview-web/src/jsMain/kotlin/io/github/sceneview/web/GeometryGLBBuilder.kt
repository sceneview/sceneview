package io.github.sceneview.web

import dev.romainguy.kotlin.math.Float2
import dev.romainguy.kotlin.math.Float3
import io.github.sceneview.geometries.GeometryData
import io.github.sceneview.geometries.generateCube
import io.github.sceneview.geometries.generateCylinder
import io.github.sceneview.geometries.generatePlane
import io.github.sceneview.geometries.generateSphere
import io.github.sceneview.web.nodes.GeometryConfig
import io.github.sceneview.web.nodes.GeometryType
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.DataView
import org.khronos.webgl.Float32Array
import org.khronos.webgl.Uint16Array
import org.khronos.webgl.Uint8Array
import org.khronos.webgl.get
import org.khronos.webgl.set

/**
 * Builds an in-memory GLB (glTF-Binary) from a [GeometryConfig] using the KMP core
 * geometry generators.
 *
 * The generated GLB is loaded through gltfio's UbershaderProvider pipeline,
 * which means geometry nodes get the same PBR material system as loaded models.
 *
 * This approach avoids the need for custom `.filamat` material files and reuses
 * the existing [SceneView.loadModel] infrastructure.
 */
internal object GeometryGLBBuilder {

    /**
     * Generate a GLB [ArrayBuffer] for the given geometry config.
     *
     * The GLB contains a single mesh node with:
     * - Position and normal vertex attributes
     * - A PBR material with the configured baseColorFactor
     * - A translation matching the configured position
     * - A scale matching the configured scale
     */
    fun buildGLB(config: GeometryConfig): ArrayBuffer {
        val geometryData = generateGeometryData(config)
        val vertices = geometryData.vertices
        val indices = geometryData.indices

        val vertexCount = vertices.size
        val indexCount = indices.size

        // Extract positions and normals into flat Float32Arrays
        val positions = Float32Array(vertexCount * 3)
        val normals = Float32Array(vertexCount * 3)

        // Track bounding box
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE

        for (i in vertices.indices) {
            val v = vertices[i]
            val px = v.position.x
            val py = v.position.y
            val pz = v.position.z

            positions[i * 3] = px
            positions[i * 3 + 1] = py
            positions[i * 3 + 2] = pz

            val n = v.normal ?: Float3(0f, 1f, 0f)
            normals[i * 3] = n.x
            normals[i * 3 + 1] = n.y
            normals[i * 3 + 2] = n.z

            if (px < minX) minX = px
            if (py < minY) minY = py
            if (pz < minZ) minZ = pz
            if (px > maxX) maxX = px
            if (py > maxY) maxY = py
            if (pz > maxZ) maxZ = pz
        }

        // Build index buffer
        val indexArray = Uint16Array(indexCount)
        for (i in indices.indices) {
            indexArray[i] = indices[i].toShort()
        }

        // Build the GLB from positions, normals, indices
        return buildGLBFromArrays(
            config = config,
            positions = positions,
            normals = normals,
            indices = indexArray,
            vertexCount = vertexCount,
            indexCount = indexCount,
            bboxMin = floatArrayOf(minX, minY, minZ),
            bboxMax = floatArrayOf(maxX, maxY, maxZ)
        )
    }

    private fun generateGeometryData(config: GeometryConfig): GeometryData {
        return when (config.geometryType) {
            GeometryType.CUBE -> generateCube(
                size = Float3(
                    config.sizeX.toFloat(),
                    config.sizeY.toFloat(),
                    config.sizeZ.toFloat()
                )
            )

            GeometryType.SPHERE -> generateSphere(
                radius = config.radius.toFloat()
            )

            GeometryType.CYLINDER -> generateCylinder(
                radius = config.radius.toFloat(),
                height = config.height.toFloat()
            )

            GeometryType.PLANE -> generatePlane(
                size = Float2(
                    config.sizeX.toFloat(),
                    config.sizeY.toFloat()
                )
            )
        }
    }

    @Suppress("LongMethod") // GLB binary format builder — interleaved header/chunk writes are inherently sequential
    private fun buildGLBFromArrays(
        config: GeometryConfig,
        positions: Float32Array,
        normals: Float32Array,
        indices: Uint16Array,
        vertexCount: Int,
        indexCount: Int,
        bboxMin: FloatArray,
        bboxMax: FloatArray
    ): ArrayBuffer {
        val posBytes = (vertexCount * 3 * 4) // Float32 = 4 bytes
        val normBytes = (vertexCount * 3 * 4)
        val idxBytes = (indexCount * 2) // Uint16 = 2 bytes

        val typeName = config.geometryType.name.lowercase()

        // Build the node transform: translation + scale
        val node = js("{}")
        node.mesh = 0
        node.name = typeName

        // Set translation if non-zero
        if (config.positionX != 0.0 || config.positionY != 0.0 || config.positionZ != 0.0) {
            node.translation = js("[]")
            node.translation.push(config.positionX, config.positionY, config.positionZ)
        }

        // Set scale if non-uniform
        if (config.scaleX != 1.0 || config.scaleY != 1.0 || config.scaleZ != 1.0) {
            node.scale = js("[]")
            node.scale.push(config.scaleX, config.scaleY, config.scaleZ)
        }

        // Set rotation (Euler degrees → quaternion for glTF)
        if (config.rotationX != 0.0 || config.rotationY != 0.0 || config.rotationZ != 0.0) {
            val rx = config.rotationX * kotlin.math.PI / 180.0
            val ry = config.rotationY * kotlin.math.PI / 180.0
            val rz = config.rotationZ * kotlin.math.PI / 180.0
            // Euler XYZ to quaternion
            val cx = kotlin.math.cos(rx / 2); val sx = kotlin.math.sin(rx / 2)
            val cy = kotlin.math.cos(ry / 2); val sy = kotlin.math.sin(ry / 2)
            val cz = kotlin.math.cos(rz / 2); val sz = kotlin.math.sin(rz / 2)
            val qx = sx * cy * cz - cx * sy * sz
            val qy = cx * sy * cz + sx * cy * sz
            val qz = cx * cy * sz - sx * sy * cz
            val qw = cx * cy * cz + sx * sy * sz
            node.rotation = js("[]")
            node.rotation.push(qx, qy, qz, qw)
        }

        // Build glTF JSON
        val gltf = js("{}")
        gltf.asset = js("({version: '2.0', generator: 'SceneView-Web'})")
        gltf.scene = 0
        gltf.scenes = js("([{nodes: [0]}])")

        gltf.nodes = js("[]")
        gltf.nodes.push(node)

        // Mesh with position + normal attributes
        gltf.meshes = js("([{primitives: [{attributes: {POSITION: 0, NORMAL: 1}, indices: 2, material: 0, mode: 4}]}])")

        // Material with the configured color. When `config.unlit` is true we add the
        // `KHR_materials_unlit` extension — Filament.js supports it natively and the
        // shader skips PBR / IBL evaluation entirely, matching `createUnlitColorInstance`
        // on Android and `CustomMaterial.unlit(color:)` on Apple. The pbrMetallicRoughness
        // baseColorFactor is still authoritative for the colour value (the extension just
        // changes how it's lit).
        val material = js("{}")
        material.name = "${typeName}_mat"
        material.pbrMetallicRoughness = js("{}")
        material.pbrMetallicRoughness.baseColorFactor = js("[]")
        material.pbrMetallicRoughness.baseColorFactor.push(config.colorR, config.colorG, config.colorB, config.colorA)
        material.pbrMetallicRoughness.metallicFactor = 0.1
        material.pbrMetallicRoughness.roughnessFactor = 0.6
        // Transparent unlit benefits from being doubleSided (overlay use case),
        // matching the Android `transparent_unlit_colored.mat` definition.
        material.doubleSided = config.unlit && config.colorA < 1.0
        if (config.unlit) {
            material.extensions = js("{}")
            material.extensions["KHR_materials_unlit"] = js("{}")
        }

        gltf.materials = js("[]")
        gltf.materials.push(material)

        // Declare the unlit extension at the glTF root when used (per spec — unused
        // extensions in extensionsUsed are tolerated but using one without declaring
        // breaks loaders that strictly validate.)
        if (config.unlit) {
            gltf.extensionsUsed = js("[]")
            gltf.extensionsUsed.push("KHR_materials_unlit")
        }

        // Accessors: position (VEC3), normal (VEC3), indices (SCALAR)
        val posAccessor = js("{}")
        posAccessor.bufferView = 0
        posAccessor.componentType = 5126 // FLOAT
        posAccessor.count = vertexCount
        posAccessor.type = "VEC3"
        posAccessor.min = js("[]")
        posAccessor.min.push(bboxMin[0], bboxMin[1], bboxMin[2])
        posAccessor.max = js("[]")
        posAccessor.max.push(bboxMax[0], bboxMax[1], bboxMax[2])

        val normAccessor = js("{}")
        normAccessor.bufferView = 1
        normAccessor.componentType = 5126 // FLOAT
        normAccessor.count = vertexCount
        normAccessor.type = "VEC3"

        val idxAccessor = js("{}")
        idxAccessor.bufferView = 2
        idxAccessor.componentType = 5123 // UNSIGNED_SHORT
        idxAccessor.count = indexCount
        idxAccessor.type = "SCALAR"

        gltf.accessors = js("[]")
        gltf.accessors.push(posAccessor, normAccessor, idxAccessor)

        // Buffer views
        val bv0 = js("{}")
        bv0.buffer = 0; bv0.byteOffset = 0; bv0.byteLength = posBytes; bv0.target = 34962

        val bv1 = js("{}")
        bv1.buffer = 0; bv1.byteOffset = posBytes; bv1.byteLength = normBytes; bv1.target = 34962

        val bv2 = js("{}")
        bv2.buffer = 0; bv2.byteOffset = posBytes + normBytes; bv2.byteLength = idxBytes; bv2.target = 34963

        gltf.bufferViews = js("[]")
        gltf.bufferViews.push(bv0, bv1, bv2)

        // Binary data length (padded to 4-byte alignment)
        val binLength = posBytes + normBytes + idxBytes
        val binPad = (4 - (binLength % 4)) % 4
        val binLengthAligned = binLength + binPad

        gltf.buffers = js("[]")
        val buf = js("{}")
        buf.byteLength = binLength // glTF spec: logical buffer size, not padded chunk size
        gltf.buffers.push(buf)

        // Serialize JSON chunk (padded to 4-byte alignment with spaces)
        var jsonStr: String = JSON.stringify(gltf)
        while (jsonStr.length % 4 != 0) jsonStr += " "
        val jsonLength = jsonStr.length

        // Total GLB size: header(12) + JSON chunk header(8) + JSON + BIN chunk header(8) + BIN
        val totalLength = 12 + 8 + jsonLength + 8 + binLengthAligned

        val glb = ArrayBuffer(totalLength)
        val view = DataView(glb)
        var offset = 0

        // GLB header: magic + version + length
        view.setUint32(offset, 0x46546C67, true); offset += 4 // "glTF"
        view.setUint32(offset, 2, true); offset += 4           // version 2
        view.setUint32(offset, totalLength, true); offset += 4

        // JSON chunk: length + type + data
        view.setUint32(offset, jsonLength, true); offset += 4
        view.setUint32(offset, 0x4E4F534A, true); offset += 4 // "JSON"
        for (i in jsonStr.indices) {
            view.setUint8(offset++, jsonStr[i].code.toByte())
        }

        // BIN chunk: length + type + data
        view.setUint32(offset, binLengthAligned, true); offset += 4
        view.setUint32(offset, 0x004E4942, true); offset += 4 // "BIN\0"

        // Copy position data
        val glbBytes = Uint8Array(glb)
        val posBytes8 = Uint8Array(positions.buffer)
        for (i in 0 until posBytes) {
            glbBytes[offset + i] = posBytes8[i]
        }
        offset += posBytes

        // Copy normal data
        val normBytes8 = Uint8Array(normals.buffer)
        for (i in 0 until normBytes) {
            glbBytes[offset + i] = normBytes8[i]
        }
        offset += normBytes

        // Copy index data
        val idxBytes8 = Uint8Array(indices.buffer)
        for (i in 0 until idxBytes) {
            glbBytes[offset + i] = idxBytes8[i]
        }
        offset += idxBytes

        // Pad remaining bytes with zeros
        for (p in 0 until binPad) {
            view.setUint8(offset++, 0.toByte())
        }

        return glb
    }
}

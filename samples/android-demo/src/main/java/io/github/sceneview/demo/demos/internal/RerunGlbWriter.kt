package io.github.sceneview.demo.demos.internal

/**
 * Writes a capture as one glTF 2.0 binary (`.glb`): the file Blender, three.js, Open3D, trimesh
 * and every glTF viewer open. The iOS demo's `RerunGLBWriter`, node for node.
 *
 * One scene, one `world` root node whose children carry the wire format's entity names:
 * - `world/points` — the point cloud, mode `POINTS`, `COLOR_0` as linear float RGB;
 * - `world/dense` — a `.svscan` v2's dense cloud, mode `POINTS`, `COLOR_0` as normalised
 *   `UNSIGNED_BYTE` RGBA and unit `NORMAL`s;
 * - `world/camera/path` — the camera's positions as a `LINE_STRIP`;
 * - `world/camera/keyframes` — one small frustum (`LINES`) per recorded photo, the photo paths
 *   and times in the node's `extras`;
 * - `world/planes/<id>` — the polygon as a triangle fan, textured with the plane's photo
 *   (re-encoded to JPEG, or PNG when it has transparent pixels) or tinted by kind;
 * - `world/anchors/<id>` — the anchor's pose, with the placed model merged under it when
 *   `models` holds its GLB, a small axes marker otherwise.
 *
 * Every material is `KHR_materials_unlit` with metallic 0 / roughness 1 as the lit fallback.
 * Coordinates stay in the session's world space: metres, Y up, as glTF expects.
 */
object RerunGlbWriter {
    /** Largest side of a placed model, in metres — the viewer's `scaleToUnits = 0.3f`. */
    const val PLACED_MODEL_SIZE = 0.3f

    /**
     * Extensions a merged model may use: their index references are re-based on merge. A model
     * that *requires* anything else is replaced by the anchor marker; one that only *uses*
     * something else has it stripped.
     */
    val MERGEABLE_EXTENSIONS: Set<String> = setOf(
        "KHR_draco_mesh_compression",
        "KHR_mesh_quantization",
        "KHR_texture_transform",
        "KHR_texture_basisu",
        "EXT_texture_webp",
        "EXT_texture_avif",
        "KHR_lights_punctual",
        "EXT_mesh_gpu_instancing",
        "KHR_materials_unlit",
        "KHR_materials_emissive_strength",
        "KHR_materials_clearcoat",
        "KHR_materials_sheen",
        "KHR_materials_specular",
        "KHR_materials_transmission",
        "KHR_materials_volume",
        "KHR_materials_ior",
        "KHR_materials_iridescence",
        "KHR_materials_anisotropy",
        "KHR_materials_dispersion",
        "KHR_materials_diffuse_transmission",
        "KHR_materials_pbrSpecularGlossiness",
    )

    /** `models[name]` is not a self-contained glTF 2.0 binary this writer can merge. */
    class InvalidModel(val name: String, val reason: String) : Exception("Model $name: $reason")

    /**
     * The `.glb` file for [scene].
     *
     * @param models GLB files by [RerunExportScene.Anchor.modelName]. Each model is merged once —
     *   its buffers, images, materials and meshes are shared — and its node tree is instanced
     *   under every anchor that names it.
     * @param codec re-encodes plane photos (the capture's WebP) to JPEG or PNG; without one,
     *   planes are tinted by kind instead of textured.
     * @throws InvalidModel when a model in [models] is not a readable GLB or points outside
     *   itself (external buffers or images).
     */
    fun write(
        scene: RerunExportScene,
        models: Map<String, ByteArray> = emptyMap(),
        codec: RerunImageCodec? = null,
    ): ByteArray {
        val gltf = RerunGltfBuilder(codec)
        val world = ArrayList<Int>()
        gltf.addPoints(scene.points, scene.pointColors)?.let { world += it }
        scene.dense?.takeIf { it.count > 0 }?.let { dense -> gltf.addDense(dense)?.let { world += it } }
        gltf.addCameraPath(scene.cameraPath)?.let { world += it }
        gltf.addKeyframes(scene.keyframes, scene.lens)?.let { world += it }
        for (plane in scene.planes) gltf.addPlane(plane)?.let { world += it }
        val merged = HashMap<String, RerunMergedModel?>()
        for (anchor in scene.anchors) world += gltf.addAnchor(anchor, models, merged)
        val root = gltf.addNode(jsonOf("name" to "world", "children" to world))
        return gltf.glb(scene.title, scene.lens, root)
    }
}

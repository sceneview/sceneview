import CoreGraphics
import Foundation
import ImageIO
import simd

/// Writes a capture as one glTF 2.0 binary (`.glb`): the file Blender, three.js, Open3D,
/// trimesh and every glTF viewer open.
///
/// One scene, one `world` root node whose children carry the wire format's entity names:
/// - `world/points` — the point cloud, mode `POINTS`, `COLOR_0` as linear float RGB (glTF's
///   colour space; the capture's sRGB bytes are converted);
/// - `world/dense` — a `.svscan` v2's dense cloud, mode `POINTS`, `COLOR_0` as normalised
///   `UNSIGNED_BYTE` RGBA and unit `NORMAL`s;
/// - `world/camera/path` — the camera's positions as a `LINE_STRIP`;
/// - `world/camera/keyframes` — one small frustum (`LINES`) per recorded photo, the photo
///   paths and times in the node's `extras`;
/// - `world/planes/<id>` — the polygon as a triangle fan, textured with the plane's photo
///   (re-encoded to JPEG, or PNG when it has transparent pixels) or tinted by kind;
/// - `world/anchors/<id>` — the anchor's pose, with the placed model merged under it when
///   `models` holds its GLB, a small axes marker otherwise.
///
/// Every material is `KHR_materials_unlit` with metallic 0 / roughness 1 as the lit fallback.
/// Coordinates stay in the session's world space: metres, Y up, as glTF expects.
enum RerunGLBWriter {
    enum Failure: Error, Equatable {
        /// `models[name]` is not a self-contained glTF 2.0 binary this writer can merge.
        case invalidModel(name: String, reason: String)
    }

    /// Extensions a merged model may use: their index references are re-based on merge.
    /// A model that *requires* anything else is replaced by the anchor marker; one that only
    /// *uses* something else has it stripped.
    /// Largest side of a placed model, in metres — Android's `scaleToUnits = 0.3f`, the
    /// USDZ export's ``RerunUSDZWriter/placedModelSize``.
    static let placedModelSize: Float = 0.3

    static let mergeableExtensions: Set<String> = [
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
    ]

    /// The `.glb` file for `scene`.
    ///
    /// - Parameter models: GLB files by ``RerunExportScene/Anchor/modelName``. Each model is
    ///   merged once — its buffers, images, materials and meshes are shared — and its node
    ///   tree is instanced under every anchor that names it.
    /// - Throws: ``Failure/invalidModel(name:reason:)`` when a model in `models` is not a
    ///   readable GLB or points outside itself (external buffers or images).
    static func data(for scene: RerunExportScene, models: [String: Data] = [:]) throws -> Data {
        var gltf = GLTFBuilder()
        var world: [Int] = []
        if let node = gltf.addPoints(scene.points, colors: scene.pointColors) {
            world.append(node)
        }
        if let dense = scene.dense, dense.count > 0, let node = gltf.addDense(dense) { world.append(node) }
        if let node = gltf.addCameraPath(scene.cameraPath) { world.append(node) }
        if let node = gltf.addKeyframes(scene.keyframes, lens: scene.lens) { world.append(node) }
        for plane in scene.planes {
            if let node = gltf.addPlane(plane) { world.append(node) }
        }
        var merged: [String: MergedModel?] = [:]
        for anchor in scene.anchors {
            world.append(try gltf.addAnchor(anchor, models: models, merged: &merged))
        }
        let root = gltf.addNode(["name": "world", "children": world])
        return try gltf.glb(title: scene.title, lens: scene.lens, root: root)
    }
}

// MARK: - Builder

private typealias JSON = [String: Any]

private let arrayBufferTarget = 34962
private let elementArrayBufferTarget = 34963
private let floatComponent = 5126

/// A model merged into the output: its shared resources are already appended, its nodes,
/// skins and animations are copied per anchor by ``GLTFBuilder/instantiate(_:)``.
private struct MergedModel {
    var nodes: [JSON]
    var roots: [Int]
    var skins: [JSON]
    var animations: [JSON]
    var accessorBase: Int
    var meshBase: Int
    var cameraBase: Int
    var lightBase: Int
    /// The model's axis-aligned bounds in its own scene space, `nil` when it has no readable
    /// `POSITION` bounds.
    var bounds: (min: SIMD3<Float>, max: SIMD3<Float>)?
}

/// The bounds of every mesh under `roots`, through the node transforms, from the `min` / `max`
/// glTF requires on each `POSITION` accessor.
private func modelBounds(_ json: [String: Any], nodes: [[String: Any]], roots: [Int]) -> (min: SIMD3<Float>, max: SIMD3<Float>)? {
    let accessors = json["accessors"] as? [[String: Any]] ?? []
    let meshes = json["meshes"] as? [[String: Any]] ?? []
    var low = SIMD3<Float>(repeating: .infinity)
    var high = SIMD3<Float>(repeating: -.infinity)
    func vector(_ value: Any?) -> SIMD3<Float>? {
        guard let array = value as? [NSNumber], array.count == 3 else { return nil }
        return SIMD3(array[0].floatValue, array[1].floatValue, array[2].floatValue)
    }
    func local(_ node: [String: Any]) -> simd_float4x4 {
        if let matrix = node["matrix"] as? [NSNumber], matrix.count == 16 {
            let m = matrix.map(\.floatValue)
            return simd_float4x4(SIMD4(m[0], m[1], m[2], m[3]), SIMD4(m[4], m[5], m[6], m[7]),
                                 SIMD4(m[8], m[9], m[10], m[11]), SIMD4(m[12], m[13], m[14], m[15]))
        }
        var transform = matrix_identity_float4x4
        if let t = vector(node["translation"]) { transform.columns.3 = SIMD4(t, 1) }
        if let r = node["rotation"] as? [NSNumber], r.count == 4 {
            let rotation = simd_float4x4(simd_quatf(ix: r[0].floatValue, iy: r[1].floatValue,
                                                    iz: r[2].floatValue, r: r[3].floatValue))
            transform = transform * rotation
        }
        if let s = vector(node["scale"]) { transform = transform * simd_float4x4(diagonal: SIMD4(s, 1)) }
        return transform
    }
    func visit(_ index: Int, _ parent: simd_float4x4, depth: Int) {
        guard depth < 64, nodes.indices.contains(index) else { return }
        let node = nodes[index]
        let world = parent * local(node)
        if let mesh = node["mesh"] as? Int, meshes.indices.contains(mesh) {
            for primitive in meshes[mesh]["primitives"] as? [[String: Any]] ?? [] {
                guard let position = (primitive["attributes"] as? [String: Int])?["POSITION"],
                      accessors.indices.contains(position),
                      let a = vector(accessors[position]["min"]), let b = vector(accessors[position]["max"])
                else { continue }
                for corner in 0..<8 {
                    let p = SIMD3(corner & 1 == 0 ? a.x : b.x, corner & 2 == 0 ? a.y : b.y, corner & 4 == 0 ? a.z : b.z)
                    let w = world * SIMD4(p, 1)
                    low = simd_min(low, SIMD3(w.x, w.y, w.z))
                    high = simd_max(high, SIMD3(w.x, w.y, w.z))
                }
            }
        }
        for child in node["children"] as? [Int] ?? [] { visit(child, world, depth: depth + 1) }
    }
    for root in roots { visit(root, matrix_identity_float4x4, depth: 0) }
    guard low.x.isFinite, high.x.isFinite, simd_reduce_max(high - low) > 0 else { return nil }
    return (low, high)
}

private struct GLTFBuilder {
    var bin = Data()
    var bufferViews: [JSON] = []
    var accessors: [JSON] = []
    var meshes: [JSON] = []
    var materials: [JSON] = []
    var textures: [JSON] = []
    var images: [JSON] = []
    var samplers: [JSON] = []
    var nodes: [JSON] = []
    var cameras: [JSON] = []
    var skins: [JSON] = []
    var animations: [JSON] = []
    var lights: [JSON] = []
    var extensionsUsed: Set<String> = []
    var extensionsRequired: Set<String> = []
    var credits: [JSON] = []
    private var namedMaterials: [String: Int] = [:]
    private var textureSampler: Int?
    private var markerMesh: Int?

    // MARK: Buffers and accessors

    /// Appends `bytes` at the next 4-byte boundary of the binary chunk.
    mutating func addBufferView(_ bytes: Data, template: JSON = [:]) -> Int {
        while bin.count % 4 != 0 { bin.append(0) }
        var view = template
        view["buffer"] = 0
        view["byteOffset"] = bin.count
        view["byteLength"] = bytes.count
        bin.append(bytes)
        bufferViews.append(view)
        return bufferViews.count - 1
    }

    /// A tightly packed float accessor; `bounds` adds the `min`/`max` glTF requires on `POSITION`.
    mutating func addFloats(_ vectors: [[Float]], type: String, bounds: Bool) -> Int {
        var bytes = Data(capacity: vectors.count * (vectors.first?.count ?? 0) * 4)
        for vector in vectors {
            for value in vector { bytes.appendLittleEndian(value.bitPattern) }
        }
        let view = addBufferView(bytes, template: ["target": arrayBufferTarget])
        var accessor: JSON = [
            "bufferView": view, "componentType": floatComponent,
            "count": vectors.count, "type": type,
        ]
        if bounds, let first = vectors.first {
            var low = first
            var high = first
            for vector in vectors {
                for axis in vector.indices {
                    low[axis] = min(low[axis], vector[axis])
                    high[axis] = max(high[axis], vector[axis])
                }
            }
            accessor["min"] = low.map(jsonNumber)
            accessor["max"] = high.map(jsonNumber)
        }
        accessors.append(accessor)
        return accessors.count - 1
    }

    mutating func addVec3(_ values: [SIMD3<Float>], bounds: Bool) -> Int {
        addFloats(values.map { [$0.x, $0.y, $0.z] }, type: "VEC3", bounds: bounds)
    }

    /// Triangle indices, `UNSIGNED_SHORT` when they fit.
    mutating func addIndices(_ indices: [UInt32]) -> Int {
        let short = (indices.max() ?? 0) < UInt32(UInt16.max)
        var bytes = Data()
        for index in indices {
            if short {
                bytes.appendLittleEndian(UInt16(index))
            } else {
                bytes.appendLittleEndian(index)
            }
        }
        let view = addBufferView(bytes, template: ["target": elementArrayBufferTarget])
        accessors.append([
            "bufferView": view, "componentType": short ? 5123 : 5125,
            "count": indices.count, "type": "SCALAR",
        ])
        return accessors.count - 1
    }

    // MARK: Nodes, meshes, materials

    mutating func addNode(_ node: JSON) -> Int {
        var node = node
        if let children = node["children"] as? [Int], children.isEmpty {
            node["children"] = nil
        }
        nodes.append(node)
        return nodes.count - 1
    }

    mutating func addMesh(_ name: String, _ primitive: JSON) -> Int {
        meshes.append(["name": name, "primitives": [primitive]])
        return meshes.count - 1
    }

    /// An unlit material, created once per name.
    mutating func unlitMaterial(_ name: String, color: SIMD4<Float>, blend: Bool = false) -> Int {
        if let index = namedMaterials[name] { return index }
        var material: JSON = [
            "name": name,
            "pbrMetallicRoughness": [
                "baseColorFactor": [color.x, color.y, color.z, color.w].map(Double.init),
                "metallicFactor": 0, "roughnessFactor": 1,
            ] as JSON,
            "doubleSided": true,
            "extensions": ["KHR_materials_unlit": JSON()],
        ]
        if blend { material["alphaMode"] = "BLEND" }
        extensionsUsed.insert("KHR_materials_unlit")
        materials.append(material)
        namedMaterials[name] = materials.count - 1
        return materials.count - 1
    }

    // MARK: Capture entities

    mutating func addPoints(_ points: [SIMD3<Float>], colors: [SIMD3<UInt8>]) -> Int? {
        var positions: [SIMD3<Float>] = []
        var linearColors: [SIMD3<Float>] = []
        positions.reserveCapacity(points.count)
        linearColors.reserveCapacity(points.count)
        for (index, point) in points.enumerated() where point.allFinite {
            positions.append(point)
            let color = index < colors.count ? colors[index] : SIMD3<UInt8>(255, 255, 255)
            linearColors.append(SIMD3(
                sRGBToLinear[Int(color.x)], sRGBToLinear[Int(color.y)], sRGBToLinear[Int(color.z)]
            ))
        }
        guard !positions.isEmpty else { return nil }
        let position = addVec3(positions, bounds: true)
        let color = addVec3(linearColors, bounds: false)
        let material = unlitMaterial("points", color: SIMD4(1, 1, 1, 1))
        let mesh = addMesh("world/points", [
            "attributes": ["POSITION": position, "COLOR_0": color], "mode": 0, "material": material,
        ])
        return addNode(["name": "world/points", "mesh": mesh])
    }

    /// A `.svscan` v2's dense cloud as `world/dense`: mode `POINTS`, `POSITION`, `COLOR_0` as
    /// normalised `UNSIGNED_BYTE` `VEC4` (linear, as glTF defines vertex colours) and unit
    /// `NORMAL`s — the layout MeshLab, Open3D and three.js read straight into a coloured cloud.
    mutating func addDense(_ cloud: RerunDenseCloud) -> Int? {
        let kept = (0..<cloud.count).filter { cloud.positions[$0].allFinite }
        guard !kept.isEmpty else { return nil }
        var positions: [SIMD3<Float>] = []
        var normals: [SIMD3<Float>] = []
        var colors = Data(capacity: kept.count * 4)
        positions.reserveCapacity(kept.count)
        normals.reserveCapacity(kept.count)
        for i in kept {
            positions.append(cloud.positions[i])
            let c = cloud.colors[i] == 0 ? 0xFFFF_FFFF : cloud.colors[i]
            for shift: UInt32 in [16, 8, 0] {
                let linear = sRGBToLinear[Int((c >> shift) & 0xFF)]
                colors.append(UInt8(min(max(Int(linear * 255 + 0.5), 0), 255)))
            }
            colors.append(255)
            // A missing or degenerate normal becomes +Y.
            let n = cloud.normals?[i] ?? SIMD3<Float>(0, 1, 0)
            let length = simd_length(n)
            normals.append(length > 1e-6 && length.isFinite ? n / length : SIMD3(0, 1, 0))
        }
        let position = addVec3(positions, bounds: true)
        let colorView = addBufferView(colors, template: ["target": arrayBufferTarget])
        accessors.append([
            "bufferView": colorView, "componentType": 5121, "normalized": true,
            "count": kept.count, "type": "VEC4",
        ])
        let color = accessors.count - 1
        let normal = addVec3(normals, bounds: false)
        let material = unlitMaterial("points", color: SIMD4(1, 1, 1, 1))
        let mesh = addMesh("world/dense", [
            "attributes": ["POSITION": position, "NORMAL": normal, "COLOR_0": color], "mode": 0, "material": material,
        ])
        return addNode(["name": "world/dense", "mesh": mesh])
    }

    mutating func addCameraPath(_ path: [RerunExportScene.CameraSample]) -> Int? {
        let positions = path.map(\.position).filter(\.allFinite)
        guard positions.count >= 2 else { return nil }
        let position = addVec3(positions, bounds: true)
        let material = unlitMaterial("camera", color: cameraColor)
        let mesh = addMesh("world/camera/path", [
            "attributes": ["POSITION": position], "mode": 3, "material": material,
        ])
        return addNode(["name": "world/camera/path", "mesh": mesh])
    }

    /// One frustum per keyframe, `frustumDepth` deep, shaped by the lens (3:4 portrait,
    /// 60° vertical field of view without one).
    mutating func addKeyframes(
        _ keyframes: [RerunExportScene.Keyframe], lens: RerunExportScene.Lens?
    ) -> Int? {
        guard !keyframes.isEmpty else { return nil }
        let lens = lens ?? RerunExportScene.Lens(
            width: 3, height: 4, fx: 2 / tan(.pi / 6), fy: 2 / tan(.pi / 6), cx: 1.5, cy: 2
        )
        let pixelCorners: [SIMD2<Float>] = [
            SIMD2(0, 0), SIMD2(Float(lens.width), 0),
            SIMD2(Float(lens.width), Float(lens.height)), SIMD2(0, Float(lens.height)),
        ]
        // Image rows grow downwards, camera +Y is up, the camera looks down -Z.
        let corners = pixelCorners.map { pixel in
            SIMD3<Float>(
                (pixel.x - lens.cx) / lens.fx * frustumDepth,
                -(pixel.y - lens.cy) / lens.fy * frustumDepth,
                -frustumDepth
            )
        }
        var vertices: [SIMD3<Float>] = []
        var entries: [JSON] = []
        for keyframe in keyframes {
            let pose = keyframe.pose
            let world = corners.map { pose.position + pose.orientation.act($0) }
            guard pose.position.allFinite, world.allSatisfy(\.allFinite) else { continue }
            for index in 0..<4 {
                vertices += [pose.position, world[index], world[index], world[(index + 1) % 4]]
            }
            entries.append(["time": keyframe.time, "image": keyframe.imagePath])
        }
        guard !vertices.isEmpty else { return nil }
        let position = addVec3(vertices, bounds: true)
        let material = unlitMaterial("camera", color: cameraColor)
        let mesh = addMesh("world/camera/keyframes", [
            "attributes": ["POSITION": position], "mode": 1, "material": material,
        ])
        return addNode([
            "name": "world/camera/keyframes", "mesh": mesh, "extras": ["keyframes": entries],
        ])
    }

    /// The polygon as a fan from its centroid, facing up for `horizontal_upward`, down for
    /// `horizontal_downward`. Planes with fewer than three finite vertices are skipped.
    mutating func addPlane(_ plane: RerunExportScene.Plane) -> Int? {
        let polygon = plane.polygon
        guard polygon.count >= 3, polygon.allSatisfy(\.allFinite) else { return nil }
        let centroid = polygon.reduce(SIMD3<Float>.zero, +) / Float(polygon.count)
        // Newell's normal follows the polygon's winding.
        var newell = SIMD3<Float>.zero
        for index in polygon.indices {
            let a = polygon[index] - centroid
            let b = polygon[(index + 1) % polygon.count] - centroid
            newell += simd_cross(a, b)
        }
        let wanted: Float = switch plane.kind {
        case "horizontal_upward": 1
        case "horizontal_downward": -1
        default: 0
        }
        let flip = wanted != 0 && newell.y * wanted < 0
        var normal = simd_length(newell) > 0 ? simd_normalize(newell) : SIMD3(0, wanted < 0 ? -1 : 1, 0)
        if flip { normal = -normal }

        let positions = [centroid] + polygon
        var indices: [UInt32] = []
        for index in 0..<polygon.count {
            let b = UInt32(index + 1)
            let c = UInt32((index + 1) % polygon.count + 1)
            indices += flip ? [0, c, b] : [0, b, c]
        }
        var attributes: JSON = [
            "POSITION": addVec3(positions, bounds: true),
            "NORMAL": addVec3(Array(repeating: normal, count: positions.count), bounds: false),
        ]
        let name = "world/planes/\(plane.id)"
        let material: Int
        if let texture = plane.texture, let uvs = Self.textureCoordinates(positions, texture),
           let image = RerunImageTranscoder.encode(texture.imageData) {
            attributes["TEXCOORD_0"] = addFloats(uvs.map { [$0.x, $0.y] }, type: "VEC2", bounds: false)
            material = texturedMaterial(name, image: image)
        } else {
            let tint = planeTint(plane.kind)
            material = unlitMaterial(tint.name, color: tint.color, blend: true)
        }
        let mesh = addMesh(name, [
            "attributes": attributes, "indices": addIndices(indices), "mode": 4, "material": material,
        ])
        return addNode(["name": name, "mesh": mesh, "extras": ["kind": plane.kind]])
    }

    /// ``RerunExportScene/PlaneTexture``'s formula; `nil` for a degenerate edge.
    static func textureCoordinates(
        _ positions: [SIMD3<Float>], _ texture: RerunExportScene.PlaneTexture
    ) -> [SIMD2<Float>]? {
        let uu = simd_dot(texture.u, texture.u)
        let vv = simd_dot(texture.v, texture.v)
        guard uu > 0, vv > 0, uu.isFinite, vv.isFinite else { return nil }
        return positions.map { point in
            let offset = point - texture.origin
            return SIMD2(simd_dot(offset, texture.u) / uu, simd_dot(offset, texture.v) / vv)
        }
    }

    private mutating func texturedMaterial(_ name: String, image: RerunImageTranscoder.Image) -> Int {
        let view = addBufferView(image.data)
        images.append(["name": name, "mimeType": image.mimeType, "bufferView": view])
        if textureSampler == nil {
            // Linear, mipmapped, clamped: the photo does not repeat past the plane.
            samplers.append(["magFilter": 9729, "minFilter": 9987, "wrapS": 33071, "wrapT": 33071])
            textureSampler = samplers.count - 1
        }
        textures.append(["source": images.count - 1, "sampler": textureSampler ?? 0])
        var material: JSON = [
            "name": name,
            "pbrMetallicRoughness": [
                "baseColorTexture": ["index": textures.count - 1],
                "metallicFactor": 0, "roughnessFactor": 1,
            ] as JSON,
            "doubleSided": true,
            "extensions": ["KHR_materials_unlit": JSON()],
        ]
        if image.isTranslucent { material["alphaMode"] = "BLEND" }
        extensionsUsed.insert("KHR_materials_unlit")
        materials.append(material)
        return materials.count - 1
    }

    // MARK: Anchors and merged models

    mutating func addAnchor(
        _ anchor: RerunExportScene.Anchor,
        models: [String: Data],
        merged: inout [String: MergedModel?]
    ) throws -> Int {
        let name = "world/anchors/\(anchor.id)"
        var node: JSON = ["name": name]
        if anchor.position.allFinite {
            node["translation"] = [anchor.position.x, anchor.position.y, anchor.position.z]
                .map(jsonNumber)
        }
        let quaternion = anchor.orientation.vector
        let length = simd_length(quaternion)
        if length.isFinite, length > 0 {
            // glTF's order is (x, y, z, w), simd_quatf.vector's too.
            let unit = quaternion / length
            node["rotation"] = [unit.x, unit.y, unit.z, unit.w].map(jsonNumber)
        }
        var extras: JSON = [:]
        var model: MergedModel?
        if let modelName = anchor.modelName {
            extras["model"] = modelName
            if let cached = merged[modelName] {
                model = cached
            } else if let data = models[modelName] {
                model = try merge(GLBFile(data, name: modelName), name: modelName)
                merged[modelName] = .some(model)
            }
            extras["modelEmbedded"] = model != nil
        }
        if let model, let modelName = anchor.modelName {
            let roots = instantiate(model)
            var holder: JSON = ["name": modelName, "children": roots]
            // Placed the way the demo shows it: longest side `placedModelSize`, centred over
            // the anchor, standing on it.
            if let bounds = model.bounds {
                let scale = RerunGLBWriter.placedModelSize / simd_reduce_max(bounds.max - bounds.min)
                let centre = (bounds.min + bounds.max) / 2
                holder["scale"] = [scale, scale, scale].map(jsonNumber)
                holder["translation"] = [-centre.x * scale, -bounds.min.y * scale, -centre.z * scale]
                    .map(jsonNumber)
            }
            node["children"] = [addNode(holder)]
        } else {
            node["mesh"] = anchorMarker()
        }
        if !extras.isEmpty { node["extras"] = extras }
        return addNode(node)
    }

    /// RGB axes, `markerSize` long: +X red, +Y green, +Z blue.
    private mutating func anchorMarker() -> Int {
        if let markerMesh { return markerMesh }
        let axes: [SIMD3<Float>] = [SIMD3(1, 0, 0), SIMD3(0, 1, 0), SIMD3(0, 0, 1)]
        let positions = axes.flatMap { [SIMD3<Float>.zero, $0 * markerSize] }
        let colors = axes.flatMap { [$0, $0] }
        let material = unlitMaterial("anchor", color: SIMD4(1, 1, 1, 1))
        let mesh = addMesh("anchor", [
            "attributes": [
                "POSITION": addVec3(positions, bounds: true),
                "COLOR_0": addVec3(colors, bounds: false),
            ],
            "mode": 1, "material": material,
        ])
        markerMesh = mesh
        return mesh
    }

    /// Appends the model's buffers, images, textures, samplers, materials, accessors, meshes,
    /// cameras and lights once. `nil` when it requires an extension outside
    /// ``RerunGLBWriter/mergeableExtensions``.
    mutating func merge(_ file: GLBFile, name: String) throws -> MergedModel? {
        let required = file.json["extensionsRequired"] as? [String] ?? []
        guard required.allSatisfy(RerunGLBWriter.mergeableExtensions.contains) else { return nil }
        let json = stripExtensions(file.json, keeping: RerunGLBWriter.mergeableExtensions) as? JSON
            ?? file.json
        func array(_ key: String) -> [JSON] { json[key] as? [JSON] ?? [] }
        func failure(_ reason: String) -> RerunGLBWriter.Failure {
            .invalidModel(name: name, reason: reason)
        }

        let buffers: [Data?] = array("buffers").enumerated().map { index, buffer in
            if let uri = buffer["uri"] as? String { return decodeDataURI(uri) }
            return index == 0 ? file.bin : nil
        }
        let viewBase = bufferViews.count
        for view in array("bufferViews") {
            guard let buffer = view["buffer"] as? Int, buffers.indices.contains(buffer),
                  let data = buffers[buffer] else {
                throw failure("a buffer view points outside the file")
            }
            let offset = view["byteOffset"] as? Int ?? 0
            let length = view["byteLength"] as? Int ?? -1
            guard offset >= 0, length >= 0, offset + length <= data.count else {
                throw failure("a buffer view is out of range")
            }
            let start = data.startIndex + offset
            _ = addBufferView(data.subdata(in: start..<start + length), template: view)
        }

        let samplerBase = samplers.count
        samplers += array("samplers")
        let imageBase = images.count
        for var image in array("images") {
            if image["bufferView"] != nil {
                shift(&image, "bufferView", by: viewBase)
            } else if let uri = image["uri"] as? String, !uri.hasPrefix("data:") {
                throw failure("image \(uri) is external")
            }
            images.append(image)
        }
        let textureBase = textures.count
        for var texture in array("textures") {
            shift(&texture, "source", by: imageBase)
            shift(&texture, "sampler", by: samplerBase)
            if var extensions = texture["extensions"] as? JSON {
                // KHR_texture_basisu, EXT_texture_webp, EXT_texture_avif: `source` is an image.
                for (key, value) in extensions {
                    guard var extensionObject = value as? JSON else { continue }
                    shift(&extensionObject, "source", by: imageBase)
                    extensions[key] = extensionObject
                }
                texture["extensions"] = extensions
            }
            textures.append(texture)
        }
        let materialBase = materials.count
        materials += array("materials").map { shiftTextureInfos($0, by: textureBase) as? JSON ?? $0 }

        let accessorBase = accessors.count
        for var accessor in array("accessors") {
            shift(&accessor, "bufferView", by: viewBase)
            if var sparse = accessor["sparse"] as? JSON {
                for key in ["indices", "values"] {
                    guard var part = sparse[key] as? JSON else { continue }
                    shift(&part, "bufferView", by: viewBase)
                    sparse[key] = part
                }
                accessor["sparse"] = sparse
            }
            accessors.append(accessor)
        }
        let meshBase = meshes.count
        for var mesh in array("meshes") {
            mesh["primitives"] = (mesh["primitives"] as? [JSON] ?? []).map { source in
                var primitive = source
                if let attributes = primitive["attributes"] as? [String: Int] {
                    primitive["attributes"] = attributes.mapValues { $0 + accessorBase }
                }
                shift(&primitive, "indices", by: accessorBase)
                shift(&primitive, "material", by: materialBase)
                if let targets = primitive["targets"] as? [[String: Int]] {
                    primitive["targets"] = targets.map { $0.mapValues { $0 + accessorBase } }
                }
                if var extensions = primitive["extensions"] as? JSON,
                   var draco = extensions["KHR_draco_mesh_compression"] as? JSON {
                    shift(&draco, "bufferView", by: viewBase)
                    extensions["KHR_draco_mesh_compression"] = draco
                    primitive["extensions"] = extensions
                }
                return primitive
            }
            meshes.append(mesh)
        }
        let cameraBase = cameras.count
        cameras += array("cameras")
        let lightBase = lights.count
        if let extensions = json["extensions"] as? JSON,
           let punctual = extensions["KHR_lights_punctual"] as? JSON,
           let modelLights = punctual["lights"] as? [JSON] {
            lights += modelLights
        }

        let used = (json["extensionsUsed"] as? [String] ?? [])
            .filter(RerunGLBWriter.mergeableExtensions.contains)
        extensionsUsed.formUnion(used)
        extensionsUsed.formUnion(required)
        extensionsRequired.formUnion(required)
        var credit: JSON = ["model": name]
        if let asset = json["asset"] as? JSON { credit["asset"] = asset }
        credits.append(credit)

        let modelNodes = array("nodes")
        var roots: [Int]
        let scenes = array("scenes")
        if scenes.isEmpty {
            let children = Set(modelNodes.flatMap { $0["children"] as? [Int] ?? [] })
            roots = modelNodes.indices.filter { !children.contains($0) }
        } else {
            let sceneIndex = json["scene"] as? Int ?? 0
            let scene = scenes.indices.contains(sceneIndex) ? scenes[sceneIndex] : scenes[0]
            roots = scene["nodes"] as? [Int] ?? []
        }
        roots = roots.filter(modelNodes.indices.contains)
        return MergedModel(
            nodes: modelNodes, roots: roots, skins: array("skins"),
            animations: array("animations"), accessorBase: accessorBase, meshBase: meshBase,
            cameraBase: cameraBase, lightBase: lightBase,
            bounds: modelBounds(json, nodes: modelNodes, roots: roots)
        )
    }

    /// Copies the model's node tree (and the skins and animations that point into it) and
    /// returns the copy's roots. Meshes, accessors and images stay shared.
    mutating func instantiate(_ model: MergedModel) -> [Int] {
        let nodeBase = nodes.count
        let skinBase = skins.count
        for var node in model.nodes {
            if let children = node["children"] as? [Int] {
                node["children"] = children.map { $0 + nodeBase }
            }
            shift(&node, "mesh", by: model.meshBase)
            shift(&node, "camera", by: model.cameraBase)
            shift(&node, "skin", by: skinBase)
            if var extensions = node["extensions"] as? JSON {
                if var light = extensions["KHR_lights_punctual"] as? JSON {
                    shift(&light, "light", by: model.lightBase)
                    extensions["KHR_lights_punctual"] = light
                }
                if var instancing = extensions["EXT_mesh_gpu_instancing"] as? JSON,
                   let attributes = instancing["attributes"] as? [String: Int] {
                    instancing["attributes"] = attributes.mapValues { $0 + model.accessorBase }
                    extensions["EXT_mesh_gpu_instancing"] = instancing
                }
                node["extensions"] = extensions
            }
            nodes.append(node)
        }
        for var skin in model.skins {
            shift(&skin, "inverseBindMatrices", by: model.accessorBase)
            shift(&skin, "skeleton", by: nodeBase)
            if let joints = skin["joints"] as? [Int] { skin["joints"] = joints.map { $0 + nodeBase } }
            skins.append(skin)
        }
        for var animation in model.animations {
            animation["samplers"] = (animation["samplers"] as? [JSON] ?? []).map { source in
                var sampler = source
                shift(&sampler, "input", by: model.accessorBase)
                shift(&sampler, "output", by: model.accessorBase)
                return sampler
            }
            animation["channels"] = (animation["channels"] as? [JSON] ?? []).map { source in
                var channel = source
                if var target = channel["target"] as? JSON {
                    shift(&target, "node", by: nodeBase)
                    channel["target"] = target
                }
                return channel
            }
            animations.append(animation)
        }
        return model.roots.map { $0 + nodeBase }
    }

    // MARK: Container

    /// The GLB container: 12-byte header, JSON chunk padded with spaces, BIN chunk padded
    /// with zeros (omitted when there is no binary data).
    func glb(title: String, lens: RerunExportScene.Lens?, root: Int) throws -> Data {
        var assetExtras: JSON = ["title": title, "upAxis": "Y", "units": "metres"]
        if let lens {
            assetExtras["lens"] = [
                "width": lens.width, "height": lens.height,
                "fx": jsonNumber(lens.fx), "fy": jsonNumber(lens.fy),
                "cx": jsonNumber(lens.cx), "cy": jsonNumber(lens.cy),
            ] as JSON
        }
        if !credits.isEmpty { assetExtras["credits"] = credits }
        var document: JSON = [
            "asset": ["version": "2.0", "generator": "SceneView", "extras": assetExtras] as JSON,
            "scene": 0,
            "scenes": [["name": title, "nodes": [root]] as JSON],
        ]
        let arrays: [(String, [JSON])] = [
            ("nodes", nodes), ("meshes", meshes), ("materials", materials),
            ("textures", textures), ("images", images), ("samplers", samplers),
            ("accessors", accessors), ("bufferViews", bufferViews), ("cameras", cameras),
            ("skins", skins), ("animations", animations),
        ]
        for (key, value) in arrays where !value.isEmpty { document[key] = value }
        var binary = bin
        while binary.count % 4 != 0 { binary.append(0) }
        if !binary.isEmpty { document["buffers"] = [["byteLength": binary.count]] }
        if !extensionsUsed.isEmpty { document["extensionsUsed"] = extensionsUsed.sorted() }
        if !extensionsRequired.isEmpty { document["extensionsRequired"] = extensionsRequired.sorted() }
        if !lights.isEmpty {
            document["extensions"] = ["KHR_lights_punctual": ["lights": lights]]
        }

        var json = try JSONSerialization.data(
            withJSONObject: document, options: [.sortedKeys, .withoutEscapingSlashes]
        )
        while json.count % 4 != 0 { json.append(0x20) }
        let total = 12 + 8 + json.count + (binary.isEmpty ? 0 : 8 + binary.count)
        var glb = Data(capacity: total)
        glb.appendLittleEndian(UInt32(0x4654_6C67))  // "glTF"
        glb.appendLittleEndian(UInt32(2))
        glb.appendLittleEndian(UInt32(total))
        glb.appendLittleEndian(UInt32(json.count))
        glb.appendLittleEndian(UInt32(0x4E4F_534A))  // "JSON"
        glb.append(json)
        if !binary.isEmpty {
            glb.appendLittleEndian(UInt32(binary.count))
            glb.appendLittleEndian(UInt32(0x004E_4942))  // "BIN\0"
            glb.append(binary)
        }
        return glb
    }
}

// MARK: - Styling

private let frustumDepth: Float = 0.12
private let markerSize: Float = 0.1
private let cameraColor = SIMD4<Float>(1, 0.62, 0.1, 1)

/// Untextured planes: a translucent tint per kind (linear RGBA).
private func planeTint(_ kind: String) -> (name: String, color: SIMD4<Float>) {
    switch kind {
    case "horizontal_upward": ("plane-floor", SIMD4(0.15, 0.45, 1, 0.35))
    case "horizontal_downward": ("plane-ceiling", SIMD4(1, 0.6, 0.15, 0.35))
    case "vertical": ("plane-wall", SIMD4(0.2, 0.8, 0.35, 0.35))
    default: ("plane-unknown", SIMD4(0.6, 0.6, 0.6, 0.35))
    }
}

/// sRGB byte to linear float, glTF's `COLOR_0` space.
private let sRGBToLinear: [Float] = (0...255).map { byte in
    let value = Float(byte) / 255
    return value <= 0.04045 ? value / 12.92 : powf((value + 0.055) / 1.055, 2.4)
}

// MARK: - Model files

/// A parsed GLB: its JSON chunk and its optional BIN chunk.
private struct GLBFile {
    var json: JSON
    var bin: Data?

    init(_ data: Data, name: String) throws {
        let bytes = [UInt8](data)
        func failure(_ reason: String) -> RerunGLBWriter.Failure {
            .invalidModel(name: name, reason: reason)
        }
        func word(_ offset: Int) -> Int {
            Int(bytes[offset]) | Int(bytes[offset + 1]) << 8
                | Int(bytes[offset + 2]) << 16 | Int(bytes[offset + 3]) << 24
        }
        guard bytes.count >= 20, word(0) == 0x4654_6C67 else { throw failure("not a GLB file") }
        guard word(4) == 2 else { throw failure("glTF version \(word(4)), expected 2") }
        let length = word(8)
        guard length >= 20, length <= bytes.count else { throw failure("truncated file") }
        var json: JSON?
        var bin: Data?
        var offset = 12
        while offset + 8 <= length {
            let chunkLength = word(offset)
            let type = word(offset + 4)
            let start = offset + 8
            guard chunkLength <= length - start else { throw failure("truncated chunk") }
            let chunk = Data(bytes[start..<start + chunkLength])
            if type == 0x4E4F_534A, json == nil {
                guard let object = try? JSONSerialization.jsonObject(with: chunk) as? JSON else {
                    throw failure("unreadable JSON chunk")
                }
                json = object
            } else if type == 0x004E_4942, bin == nil {
                bin = chunk
            }
            offset = start + chunkLength
        }
        guard let json else { throw failure("no JSON chunk") }
        self.json = json
        self.bin = bin
    }
}

/// Adds `offset` to the integer at `key`, if there is one.
private func shift(_ object: inout JSON, _ key: String, by offset: Int) {
    if let index = object[key] as? Int { object[key] = index + offset }
}

/// Re-bases every texture reference of a material: core and `KHR_materials_*` texture infos
/// all sit under a key ending in `Texture` (`baseColorTexture`, `clearcoatNormalTexture`…).
private func shiftTextureInfos(_ value: Any, by offset: Int) -> Any {
    if let array = value as? [Any] { return array.map { shiftTextureInfos($0, by: offset) } }
    guard var object = value as? JSON else { return value }
    for (key, child) in object {
        var shifted = shiftTextureInfos(child, by: offset)
        if key.hasSuffix("Texture"), var info = shifted as? JSON {
            shift(&info, "index", by: offset)
            shifted = info
        }
        object[key] = shifted
    }
    return object
}

/// Drops every `extensions` entry outside `kept`, at any depth.
private func stripExtensions(_ value: Any, keeping kept: Set<String>) -> Any {
    if let array = value as? [Any] { return array.map { stripExtensions($0, keeping: kept) } }
    guard let object = value as? JSON else { return value }
    var result = JSON()
    for (key, child) in object {
        if key == "extensions", let extensions = child as? JSON {
            let filtered = extensions.filter { kept.contains($0.key) }
                .mapValues { stripExtensions($0, keeping: kept) }
            if !filtered.isEmpty { result[key] = filtered }
        } else {
            result[key] = stripExtensions(child, keeping: kept)
        }
    }
    return result
}

/// The bytes of a base64 `data:` URI.
private func decodeDataURI(_ uri: String) -> Data? {
    guard uri.hasPrefix("data:"), let comma = uri.firstIndex(of: ","),
          uri[..<comma].hasSuffix(";base64") else { return nil }
    return Data(base64Encoded: String(uri[uri.index(after: comma)...]))
}

// MARK: - Plane images

/// Plane photos as core glTF accepts them: JPEG, or PNG when transparency must survive.
/// ImageIO decodes the capture's WebP; core glTF cannot carry WebP without an extension.
enum RerunImageTranscoder {
    struct Image: Sendable, Equatable {
        var data: Data
        var mimeType: String
        /// At least one pixel is not fully opaque: the material blends.
        var isTranslucent: Bool
    }

    /// `nil` when ImageIO cannot decode `data`.
    static func encode(_ data: Data) -> Image? {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil),
              let image = CGImageSourceCreateImageAtIndex(source, 0, nil) else { return nil }
        let translucent = hasTranslucentPixel(image)
        let isJPEG = data.starts(with: [0xFF, 0xD8, 0xFF])
        let isPNG = data.starts(with: [0x89, 0x50, 0x4E, 0x47])
        if isJPEG { return Image(data: data, mimeType: "image/jpeg", isTranslucent: false) }
        if isPNG { return Image(data: data, mimeType: "image/png", isTranslucent: translucent) }
        let type = translucent ? "public.png" : "public.jpeg"
        let output = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(
            output as CFMutableData, type as CFString, 1, nil
        ) else { return nil }
        let options = [kCGImageDestinationLossyCompressionQuality: 0.9] as CFDictionary
        CGImageDestinationAddImage(destination, image, translucent ? nil : options)
        guard CGImageDestinationFinalize(destination) else { return nil }
        return Image(
            data: output as Data,
            mimeType: translucent ? "image/png" : "image/jpeg",
            isTranslucent: translucent
        )
    }

    private static func hasTranslucentPixel(_ image: CGImage) -> Bool {
        switch image.alphaInfo {
        case .none, .noneSkipFirst, .noneSkipLast: return false
        default: break
        }
        let width = image.width
        let height = image.height
        guard width > 0, height > 0,
              let space = CGColorSpace(name: CGColorSpace.sRGB),
              let context = CGContext(
                  data: nil, width: width, height: height, bitsPerComponent: 8,
                  bytesPerRow: width * 4, space: space,
                  bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
              ) else { return false }
        context.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
        guard let pixels = context.data?.assumingMemoryBound(to: UInt8.self) else { return false }
        for index in stride(from: 3, to: width * height * 4, by: 4) where pixels[index] < 255 {
            return true
        }
        return false
    }
}

// MARK: - Helpers

/// `value` widened to the exact double, written in its shortest round-trip form: a reader
/// parsing the JSON gets back the very float in the buffer, which accessor bounds require.
/// `JSONSerialization` alone prints doubles with a last digit off.
private func jsonNumber(_ value: Float) -> Any {
    let exact = Double(value)
    return Decimal(string: exact.description, locale: Locale(identifier: "en_US_POSIX")) ?? exact
}

private extension SIMD3 where Scalar == Float {
    var allFinite: Bool { x.isFinite && y.isFinite && z.isFinite }
}

private extension Data {
    mutating func appendLittleEndian<T: FixedWidthInteger>(_ value: T) {
        Swift.withUnsafeBytes(of: value.littleEndian) { append(contentsOf: $0) }
    }
}

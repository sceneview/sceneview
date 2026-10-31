import Foundation
import simd
import CoreGraphics
import ImageIO

// MARK: - Decoded scene

/// A node's local transform as glTF states it: translation, rotation, scale.
///
/// A node given as a `matrix` is decomposed into these on read, because animation
/// channels target T, R and S separately and need the rest values of the other two.
struct GLTFTransform: Equatable, Sendable {
    var translation: SIMD3<Float> = .zero
    var rotation: simd_quatf = simd_quatf(ix: 0, iy: 0, iz: 0, r: 1)
    var scale: SIMD3<Float> = .one

    static let identity = GLTFTransform()

    /// `T * R * S`, the order glTF composes them in.
    var matrix: simd_float4x4 {
        var t = matrix_identity_float4x4
        t.columns.3 = SIMD4(translation, 1)
        let r = simd_float4x4(rotation)
        let s = simd_float4x4(diagonal: SIMD4(scale, 1))
        return t * r * s
    }

    init() {}

    init(translation: SIMD3<Float>, rotation: simd_quatf, scale: SIMD3<Float>) {
        self.translation = translation
        self.rotation = rotation
        self.scale = scale
    }

    /// Decomposes an affine matrix into T, R, S. Shear is not representable and is
    /// dropped; a negative determinant is folded into the X scale.
    init(matrix m: simd_float4x4) {
        translation = SIMD3(m.columns.3.x, m.columns.3.y, m.columns.3.z)
        var c0 = SIMD3(m.columns.0.x, m.columns.0.y, m.columns.0.z)
        let c1 = SIMD3(m.columns.1.x, m.columns.1.y, m.columns.1.z)
        let c2 = SIMD3(m.columns.2.x, m.columns.2.y, m.columns.2.z)
        var sx = simd_length(c0)
        let sy = simd_length(c1)
        let sz = simd_length(c2)
        if simd_dot(simd_cross(c0, c1), c2) < 0 { sx = -sx; c0 = -c0 }
        scale = SIMD3(sx, sy, sz)
        guard abs(sx) > 0, sy > 0, sz > 0 else { rotation = simd_quatf(ix: 0, iy: 0, iz: 0, r: 1); return }
        let r = simd_float3x3(c0 / abs(sx), c1 / sy, c2 / sz)
        rotation = simd_normalize(simd_quatf(r))
    }
}

struct GLTFNode: Sendable {
    /// Unique within the file — duplicates and unnamed nodes get a suffix, because
    /// animation bind paths address entities by name.
    var name: String
    var parent: Int?
    var children: [Int]
    var mesh: Int?
    var skin: Int?
    var rest: GLTFTransform
}

/// One draw call's worth of vertices, already in RealityKit's conventions: UVs flipped
/// to a bottom-left origin (with `KHR_texture_transform` baked in), triangle lists only.
struct GLTFPrimitive: Sendable {
    var positions: [SIMD3<Float>]
    var normals: [SIMD3<Float>]?
    /// `xyz` tangent + `w` handedness, as glTF stores it.
    var tangents: [SIMD4<Float>]?
    var textureCoordinates: [SIMD2<Float>]?
    var colors: [SIMD4<Float>]?
    /// `influencesPerVertex` joint indices per vertex, into the skin's `joints`.
    var joints: [UInt16]?
    /// Matching weights, normalized to sum to 1 per vertex.
    var weights: [Float]?
    var influencesPerVertex: Int = 0
    var indices: [UInt32]
    var material: Int?
}

struct GLTFMesh: Sendable {
    var name: String
    var primitives: [GLTFPrimitive]
    /// Morph targets are not rendered yet; the base mesh is shown.
    var hasMorphTargets: Bool
}

enum GLTFAlphaMode: String, Sendable {
    case opaque = "OPAQUE", mask = "MASK", blend = "BLEND"
}

/// A texture slot: which image, sampled how.
struct GLTFTextureRef: Hashable, Sendable {
    var image: Int
    var sampler: Int?
}

/// How an image is handed to RealityKit. Channel variants exist because glTF packs
/// scalar maps into one RGB image (G = roughness, B = metallic, R = occlusion) and
/// RealityKit's scalar inputs read a single-channel texture.
enum GLTFImageVariant: Hashable, Sendable {
    case color, normal, red, green, blue, alpha

    var channel: Int? {
        switch self {
        case .red: return 0
        case .green: return 1
        case .blue: return 2
        case .alpha: return 3
        case .color, .normal: return nil
        }
    }
}

struct GLTFImageKey: Hashable, Sendable {
    var image: Int
    var variant: GLTFImageVariant
}

struct GLTFMaterial: Sendable {
    var name: String?
    /// Linear RGBA.
    var baseColorFactor: SIMD4<Float> = .one
    var baseColorTexture: GLTFTextureRef?
    var metallicFactor: Float = 1
    var roughnessFactor: Float = 1
    var metallicRoughnessTexture: GLTFTextureRef?
    var normalTexture: GLTFTextureRef?
    var occlusionTexture: GLTFTextureRef?
    var emissiveFactor: SIMD3<Float> = .zero
    var emissiveTexture: GLTFTextureRef?
    var emissiveStrength: Float = 1
    var alphaMode: GLTFAlphaMode = .opaque
    var alphaCutoff: Float = 0.5
    var doubleSided = false
    var unlit = false

    /// The glTF default material: white, fully metallic, fully rough.
    static let `default` = GLTFMaterial()
}

struct GLTFSkin: Sendable {
    var name: String
    var joints: [Int]
    var inverseBindMatrices: [simd_float4x4]
}

enum GLTFAnimationPath: String, Sendable {
    case translation, rotation, scale
}

enum GLTFInterpolation: String, Sendable {
    case linear = "LINEAR", step = "STEP", cubicSpline = "CUBICSPLINE"
}

struct GLTFAnimationChannel: Sendable {
    var node: Int
    var path: GLTFAnimationPath
    var times: [Float]
    /// Flat output values: 3 or 4 per key, ×3 for cubic spline (in-tangent, value,
    /// out-tangent).
    var values: [Float]
    var interpolation: GLTFInterpolation
}

struct GLTFAnimation: Sendable {
    var name: String
    var channels: [GLTFAnimationChannel]

    var startTime: Float { channels.compactMap { $0.times.first }.min() ?? 0 }
    var endTime: Float { channels.compactMap { $0.times.last }.max() ?? 0 }
}

/// Everything RealityKit needs from a glTF file, decoded off the main thread.
struct GLTFScene: @unchecked Sendable {
    var name: String
    var roots: [Int]
    var nodes: [GLTFNode]
    var meshes: [GLTFMesh]
    var materials: [GLTFMaterial]
    var samplers: [GLTFSamplerDef]
    var skins: [GLTFSkin]
    var animations: [GLTFAnimation]
    /// Decoded images, per variant actually used by a material.
    var images: [GLTFImageKey: CGImage]
    /// Things that were read but cannot be shown yet (morph targets, point/line
    /// primitives), for logs and tests.
    var warnings: [String]

    /// World (scene-root-relative) matrix of every node at rest.
    func restWorldMatrices() -> [simd_float4x4] {
        worldMatrices(locals: nodes.map { $0.rest.matrix })
    }

    /// World matrices from per-node local matrices — parents before children by
    /// walking down from the roots.
    func worldMatrices(locals: [simd_float4x4]) -> [simd_float4x4] {
        var world = locals
        var visited = [Bool](repeating: false, count: nodes.count)
        func visit(_ index: Int, _ parent: simd_float4x4) {
            guard index < nodes.count, !visited[index] else { return }
            visited[index] = true
            world[index] = parent * locals[index]
            for child in nodes[index].children { visit(child, world[index]) }
        }
        for root in roots { visit(root, matrix_identity_float4x4) }
        for index in nodes.indices where !visited[index] && nodes[index].parent == nil {
            visit(index, matrix_identity_float4x4)
        }
        return world
    }
}

// MARK: - Decoding

extension GLTFScene {

    /// Decodes a parsed document into a ``GLTFScene``.
    ///
    /// - Parameter decodeImages: `false` skips image decoding entirely — the geometry
    ///   path (``MeshAsset``) needs no pixels.
    static func decode(_ document: GLTFDocument, decodeImages: Bool = true) throws -> GLTFScene {
        let root = document.root
        let reader = GLTFAccessorReader(document: document)
        var warnings: [String] = []

        // --- Nodes, with unique names and parent links -------------------------
        let nodeDefs = root.nodes ?? []
        var parents = [Int?](repeating: nil, count: nodeDefs.count)
        for (index, node) in nodeDefs.enumerated() {
            for child in node.children ?? [] {
                guard child >= 0, child < nodeDefs.count, child != index else {
                    throw ModelLoadingError.malformed(reason: "node \(index) has an invalid child \(child)")
                }
                guard parents[child] == nil else {
                    throw ModelLoadingError.malformed(reason: "node \(child) has two parents")
                }
                parents[child] = index
            }
        }
        var usedNames: Set<String> = []
        let nodes: [GLTFNode] = nodeDefs.enumerated().map { index, def in
            let base = sanitized(def.name) ?? "node_\(index)"
            var name = base
            var suffix = 1
            while usedNames.contains(name) { name = "\(base)_\(suffix)"; suffix += 1 }
            usedNames.insert(name)
            return GLTFNode(
                name: name, parent: parents[index], children: def.children ?? [],
                mesh: def.mesh, skin: def.skin, rest: restTransform(def)
            )
        }

        // --- Scene roots -------------------------------------------------------
        let sceneIndex = root.scene ?? 0
        let sceneDef = root.scenes?[safe: sceneIndex]
        let roots: [Int] = sceneDef?.nodes?.filter { $0 >= 0 && $0 < nodes.count }
            ?? nodes.indices.filter { nodes[$0].parent == nil }

        // --- Materials ---------------------------------------------------------
        let textures = root.textures ?? []
        func ref(_ info: GLTFTextureInfoDef?) -> GLTFTextureRef? {
            guard let info, let texture = textures[safe: info.index], let image = texture.imageIndex,
                  root.images?[safe: image] != nil else { return nil }
            return GLTFTextureRef(image: image, sampler: texture.sampler)
        }
        var uvTransforms: [GLTFTextureTransformDef?] = []
        let materials: [GLTFMaterial] = (root.materials ?? []).map { def in
            var m = GLTFMaterial()
            m.name = def.name
            let pbr = def.pbrMetallicRoughness
            if let f = pbr?.baseColorFactor, f.count == 4 { m.baseColorFactor = SIMD4(f[0], f[1], f[2], f[3]) }
            m.baseColorTexture = ref(pbr?.baseColorTexture)
            m.metallicFactor = pbr?.metallicFactor ?? 1
            m.roughnessFactor = pbr?.roughnessFactor ?? 1
            m.metallicRoughnessTexture = ref(pbr?.metallicRoughnessTexture)
            m.normalTexture = ref(def.normalTexture)
            m.occlusionTexture = ref(def.occlusionTexture)
            m.emissiveTexture = ref(def.emissiveTexture)
            if let e = def.emissiveFactor, e.count == 3 { m.emissiveFactor = SIMD3(e[0], e[1], e[2]) }
            m.emissiveStrength = def.extensions?.emissiveStrength?.emissiveStrength ?? 1
            m.alphaMode = GLTFAlphaMode(rawValue: def.alphaMode ?? "OPAQUE") ?? .opaque
            m.alphaCutoff = def.alphaCutoff ?? 0.5
            m.doubleSided = def.doubleSided ?? false
            m.unlit = def.extensions?.unlit != nil
            // One UV transform per material, baked into the vertices: the base colour's
            // (it is what the eye reads), else the first textured slot that has one.
            let infos = [pbr?.baseColorTexture, def.emissiveTexture, def.normalTexture,
                         pbr?.metallicRoughnessTexture, def.occlusionTexture]
            uvTransforms.append(infos.lazy.compactMap { $0?.extensions?.textureTransform }.first)
            return m
        }

        // --- Meshes ------------------------------------------------------------
        let meshes: [GLTFMesh] = try (root.meshes ?? []).enumerated().map { meshIndex, def in
            var primitives: [GLTFPrimitive] = []
            for (primitiveIndex, p) in def.primitives.enumerated() {
                let mode = p.mode ?? 4
                guard mode >= 4 && mode <= 6 else {
                    warnings.append("mesh \(meshIndex) primitive \(primitiveIndex): mode \(mode) (points/lines) is not drawn")
                    continue
                }
                guard let positionAccessor = p.attributes["POSITION"] else { continue }
                let positions = try reader.vec3(positionAccessor)
                guard !positions.isEmpty else { continue }

                var raw: [UInt32]
                if let indices = p.indices {
                    raw = try reader.indices(indices)
                } else {
                    raw = (0..<UInt32(positions.count)).map { $0 }
                }
                if let bad = raw.first(where: { Int($0) >= positions.count }) {
                    throw ModelLoadingError.malformed(
                        reason: "mesh \(meshIndex) primitive \(primitiveIndex): index \(bad) ≥ \(positions.count) vertices"
                    )
                }
                let indices = triangleList(raw, mode: mode)
                guard indices.count >= 3 else { continue }

                func attribute<T>(_ name: String, _ read: (Int) throws -> [T]) throws -> [T]? {
                    guard let accessor = p.attributes[name] else { return nil }
                    let values = try read(accessor)
                    return values.count == positions.count ? values : nil
                }
                var primitive = GLTFPrimitive(positions: positions, indices: indices, material: p.material)
                primitive.normals = try attribute("NORMAL", reader.vec3).map { $0.map(safeNormalize) }
                primitive.tangents = try attribute("TANGENT") { try reader.vec4($0) }
                primitive.colors = try attribute("COLOR_0") { try reader.vec4($0, widenVec3: true) }
                if let uvs = try attribute("TEXCOORD_0", reader.vec2) {
                    let transform = p.material.flatMap { uvTransforms[safe: $0] ?? nil }
                    primitive.textureCoordinates = uvs.map { uv in
                        let t = applyTextureTransform(uv, transform)
                        return SIMD2(t.x, 1 - t.y)   // glTF: top-left origin; RealityKit: bottom-left
                    }
                }
                try readSkinning(p, reader: reader, vertexCount: positions.count, into: &primitive)
                primitives.append(primitive)
            }
            let hasMorphs = def.primitives.contains { !($0.targets ?? []).isEmpty }
            if hasMorphs { warnings.append("mesh \(meshIndex): morph targets are not supported yet; the base shape is shown") }
            return GLTFMesh(name: sanitized(def.name) ?? "mesh_\(meshIndex)", primitives: primitives, hasMorphTargets: hasMorphs)
        }

        // --- Skins -------------------------------------------------------------
        let skins: [GLTFSkin] = try (root.skins ?? []).enumerated().map { index, def in
            guard def.joints.allSatisfy({ $0 >= 0 && $0 < nodes.count }) else {
                throw ModelLoadingError.malformed(reason: "skin \(index) names a joint that is not a node")
            }
            var ibms = [simd_float4x4](repeating: matrix_identity_float4x4, count: def.joints.count)
            if let accessor = def.inverseBindMatrices {
                let read = try reader.matrices(accessor)
                guard read.count >= def.joints.count else {
                    throw ModelLoadingError.malformed(reason: "skin \(index) has fewer inverse bind matrices than joints")
                }
                ibms = Array(read.prefix(def.joints.count))
            }
            return GLTFSkin(name: sanitized(def.name) ?? "skin_\(index)", joints: def.joints, inverseBindMatrices: ibms)
        }

        // --- Animations --------------------------------------------------------
        let animations: [GLTFAnimation] = try (root.animations ?? []).enumerated().map { index, def in
            var channels: [GLTFAnimationChannel] = []
            for channel in def.channels {
                guard let node = channel.target.node, node >= 0, node < nodes.count,
                      let path = GLTFAnimationPath(rawValue: channel.target.path),
                      let sampler = def.samplers[safe: channel.sampler] else {
                    if channel.target.path == "weights" {
                        warnings.append("animation \(index): morph-weight channels are not supported yet")
                    }
                    continue
                }
                let times = try reader.floats(sampler.input).values
                let values = try reader.floats(sampler.output).values
                let interpolation = GLTFInterpolation(rawValue: sampler.interpolation ?? "LINEAR") ?? .linear
                let width = (path == .rotation ? 4 : 3) * (interpolation == .cubicSpline ? 3 : 1)
                guard !times.isEmpty, values.count >= times.count * width else {
                    throw ModelLoadingError.malformed(reason: "animation \(index): sampler output is shorter than its input")
                }
                channels.append(GLTFAnimationChannel(
                    node: node, path: path, times: times, values: values, interpolation: interpolation
                ))
            }
            return GLTFAnimation(name: def.name ?? "animation_\(index)", channels: channels)
        }.filter { !$0.channels.isEmpty }

        // --- Images ------------------------------------------------------------
        var images: [GLTFImageKey: CGImage] = [:]
        if decodeImages {
            images = decodeImagesUsed(by: materials, document: document, warnings: &warnings)
        }

        return GLTFScene(
            name: sanitized(sceneDef?.name) ?? "glTF",
            roots: roots, nodes: nodes, meshes: meshes, materials: materials,
            samplers: root.samplers ?? [], skins: skins, animations: animations,
            images: images, warnings: warnings
        )
    }

    // MARK: Helpers

    /// Entity names: no `/` (bind paths split on it in some RealityKit versions), no
    /// empty strings.
    static func sanitized(_ name: String?) -> String? {
        guard let name, !name.isEmpty else { return nil }
        return name.replacingOccurrences(of: "/", with: "_")
    }

    static func restTransform(_ node: GLTFNodeDef) -> GLTFTransform {
        if let m = node.matrix, m.count == 16 {
            return GLTFTransform(matrix: simd_float4x4(
                SIMD4(m[0], m[1], m[2], m[3]), SIMD4(m[4], m[5], m[6], m[7]),
                SIMD4(m[8], m[9], m[10], m[11]), SIMD4(m[12], m[13], m[14], m[15])
            ))
        }
        var t = GLTFTransform()
        if let v = node.translation, v.count == 3 { t.translation = SIMD3(v[0], v[1], v[2]) }
        if let q = node.rotation, q.count == 4 {
            t.rotation = simd_normalize(simd_quatf(ix: q[0], iy: q[1], iz: q[2], r: q[3]))
        }
        if let s = node.scale, s.count == 3 { t.scale = SIMD3(s[0], s[1], s[2]) }
        return t
    }

    /// Strips and fans to a plain triangle list.
    static func triangleList(_ indices: [UInt32], mode: Int) -> [UInt32] {
        switch mode {
        case 5:  // TRIANGLE_STRIP
            guard indices.count >= 3 else { return [] }
            var out: [UInt32] = []
            out.reserveCapacity((indices.count - 2) * 3)
            for i in 0..<(indices.count - 2) {
                if i % 2 == 0 {
                    out += [indices[i], indices[i + 1], indices[i + 2]]
                } else {
                    out += [indices[i + 1], indices[i], indices[i + 2]]
                }
            }
            return out
        case 6:  // TRIANGLE_FAN
            guard indices.count >= 3 else { return [] }
            var out: [UInt32] = []
            out.reserveCapacity((indices.count - 2) * 3)
            for i in 1..<(indices.count - 1) {
                out += [indices[i], indices[i + 1], indices[0]]
            }
            return out
        default:
            return Array(indices.prefix(indices.count - indices.count % 3))
        }
    }

    static func safeNormalize(_ v: SIMD3<Float>) -> SIMD3<Float> {
        let length = simd_length(v)
        return length > 0 ? v / length : SIMD3(0, 1, 0)
    }

    /// `KHR_texture_transform`: scale, then rotate, then offset — in glTF UV space.
    static func applyTextureTransform(_ uv: SIMD2<Float>, _ t: GLTFTextureTransformDef?) -> SIMD2<Float> {
        guard let t else { return uv }
        let scale = t.scale.map { SIMD2($0[safe: 0] ?? 1, $0[safe: 1] ?? 1) } ?? SIMD2(1, 1)
        let offset = t.offset.map { SIMD2($0[safe: 0] ?? 0, $0[safe: 1] ?? 0) } ?? .zero
        let r = t.rotation ?? 0
        let s = uv * scale
        let rotated = SIMD2(cos(r) * s.x + sin(r) * s.y, -sin(r) * s.x + cos(r) * s.y)
        return rotated + offset
    }

    /// JOINTS_n / WEIGHTS_n into flat arrays, weights normalized per vertex.
    private static func readSkinning(
        _ p: GLTFPrimitiveDef, reader: GLTFAccessorReader, vertexCount: Int, into primitive: inout GLTFPrimitive
    ) throws {
        var sets: [([Float], [Float])] = []
        var set = 0
        while let jointsAccessor = p.attributes["JOINTS_\(set)"], let weightsAccessor = p.attributes["WEIGHTS_\(set)"] {
            let joints = try reader.floats(jointsAccessor)
            let weights = try reader.floats(weightsAccessor)
            guard joints.components == 4, weights.components == 4,
                  joints.values.count == vertexCount * 4, weights.values.count == vertexCount * 4 else {
                throw ModelLoadingError.malformed(reason: "JOINTS_\(set)/WEIGHTS_\(set) do not match the vertex count")
            }
            sets.append((joints.values, weights.values))
            set += 1
        }
        guard !sets.isEmpty else { return }
        let perVertex = sets.count * 4
        var joints = [UInt16](repeating: 0, count: vertexCount * perVertex)
        var weights = [Float](repeating: 0, count: vertexCount * perVertex)
        for vertex in 0..<vertexCount {
            var sum: Float = 0
            for (s, (j, w)) in sets.enumerated() {
                for k in 0..<4 {
                    let slot = vertex * perVertex + s * 4 + k
                    joints[slot] = UInt16(clamping: Int(j[vertex * 4 + k]))
                    let weight = max(w[vertex * 4 + k], 0)
                    weights[slot] = weight
                    sum += weight
                }
            }
            let base = vertex * perVertex
            if sum > 0 {
                for k in 0..<perVertex { weights[base + k] /= sum }
            } else {
                weights[base] = 1
            }
        }
        primitive.joints = joints
        primitive.weights = weights
        primitive.influencesPerVertex = perVertex
    }

    // MARK: Images

    private static func decodeImagesUsed(
        by materials: [GLTFMaterial], document: GLTFDocument, warnings: inout [String]
    ) -> [GLTFImageKey: CGImage] {
        var wanted: Set<GLTFImageKey> = []
        for m in materials {
            if let t = m.baseColorTexture {
                wanted.insert(.init(image: t.image, variant: .color))
                if m.alphaMode != .opaque { wanted.insert(.init(image: t.image, variant: .alpha)) }
            }
            if let t = m.metallicRoughnessTexture, !m.unlit {
                wanted.insert(.init(image: t.image, variant: .green))
                wanted.insert(.init(image: t.image, variant: .blue))
            }
            if let t = m.normalTexture, !m.unlit { wanted.insert(.init(image: t.image, variant: .normal)) }
            if let t = m.occlusionTexture, !m.unlit { wanted.insert(.init(image: t.image, variant: .red)) }
            if let t = m.emissiveTexture, !m.unlit { wanted.insert(.init(image: t.image, variant: .color)) }
        }
        let imageIndices = Array(Set(wanted.map(\.image))).sorted()
        guard !imageIndices.isEmpty else { return [:] }

        // Decode each source image once, in parallel — a PBR asset is typically four or
        // five 2K textures and decoding dominates the load.
        let decoded = UnsafeMutableBufferPointer<CGImage?>.allocate(capacity: imageIndices.count)
        decoded.initialize(repeating: nil)
        defer { decoded.deallocate() }
        DispatchQueue.concurrentPerform(iterations: imageIndices.count) { i in
            guard let data = try? document.imageData(imageIndices[i]),
                  let source = CGImageSourceCreateWithData(data as CFData, nil),
                  let image = CGImageSourceCreateImageAtIndex(
                    source, 0, [kCGImageSourceShouldCacheImmediately: true] as CFDictionary
                  ) else { return }
            decoded[i] = image
        }

        var out: [GLTFImageKey: CGImage] = [:]
        for (i, index) in imageIndices.enumerated() {
            guard let image = decoded[i] else {
                warnings.append("image \(index) could not be decoded")
                continue
            }
            for key in wanted where key.image == index {
                if let channel = key.variant.channel {
                    if let single = extractChannel(channel, from: image) { out[key] = single }
                } else {
                    out[key] = image
                }
            }
        }
        return out
    }

    /// One channel of an image as an 8-bit greyscale image.
    static func extractChannel(_ channel: Int, from image: CGImage) -> CGImage? {
        let width = image.width, height = image.height
        guard width > 0, height > 0 else { return nil }
        let space: CGColorSpace = (image.colorSpace?.model == .rgb ? image.colorSpace : nil)
            ?? CGColorSpaceCreateDeviceRGB()
        // RGB channels: skip alpha so colour values are never premultiplied away.
        let info: CGImageAlphaInfo = channel == 3 ? .premultipliedLast : .noneSkipLast
        var rgba = [UInt8](repeating: 0, count: width * height * 4)
        let drawn: Bool = rgba.withUnsafeMutableBytes { buffer in
            guard let context = CGContext(
                data: buffer.baseAddress, width: width, height: height, bitsPerComponent: 8,
                bytesPerRow: width * 4, space: space, bitmapInfo: info.rawValue
            ) else { return false }
            context.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
            return true
        }
        guard drawn else { return nil }
        var gray = [UInt8](repeating: 0, count: width * height)
        for i in 0..<(width * height) { gray[i] = rgba[i * 4 + channel] }
        guard let provider = CGDataProvider(data: Data(gray) as CFData) else { return nil }
        return CGImage(
            width: width, height: height, bitsPerComponent: 8, bitsPerPixel: 8, bytesPerRow: width,
            space: CGColorSpaceCreateDeviceGray(), bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.none.rawValue),
            provider: provider, decode: nil, shouldInterpolate: true, intent: .defaultIntent
        )
    }
}

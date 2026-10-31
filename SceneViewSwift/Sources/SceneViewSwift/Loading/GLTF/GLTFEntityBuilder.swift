#if os(iOS) || os(macOS) || os(visionOS)
import Foundation
import RealityKit
import Metal
import simd
#if canImport(UIKit)
import UIKit
#elseif canImport(AppKit)
import AppKit
#endif

/// Loads glTF 2.0 (`.glb`, `.gltf`) into a RealityKit entity tree.
///
/// Two phases, so the main thread only does what RealityKit insists on:
///
/// 1. **Off the main actor** — ``GLTFDocument`` parses the container and resolves every
///    buffer, ``GLTFScene/decode(_:decodeImages:)`` reads accessors into vertex arrays
///    and decodes the images (in parallel).
/// 2. **On the main actor** — ``GLTFEntityBuilder`` turns that into `TextureResource`s,
///    `PhysicallyBasedMaterial`s, `MeshResource`s (with skeletons for skinned meshes),
///    an `Entity` per node, and one `AnimationResource` per glTF animation, stored on the
///    root so `ModelNode.playAllAnimations()` finds them like it finds a USDZ's.
///
/// The RealityKit mapping follows the approach of GLTFKit2's `GLTFRealityKitLoader`
/// (Warren Moore, MIT licence) — channel-split metallic/roughness/occlusion textures,
/// flipped UVs, skins as `MeshResource.Skeleton`, joint animations as
/// `SampledAnimation<JointTransforms>` — reimplemented here without the dependency.
enum GLTFLoader {

    /// Parses and decodes a glTF file on a background task.
    static func decodeScene(contentsOf url: URL) async throws -> GLTFScene {
        try await Task.detached(priority: .userInitiated) {
            try GLTFScene.decode(GLTFDocument.read(contentsOf: url))
        }.value
    }

    /// The whole load: decode in the background, build on the main actor.
    @MainActor
    static func loadEntity(contentsOf url: URL, unit: ModelUnit? = nil) async throws -> ModelEntity {
        let scene = try await decodeScene(contentsOf: url)
        return try await GLTFEntityBuilder(scene: scene, unit: unit).build()
    }
}

/// Builds RealityKit resources from a decoded ``GLTFScene``.
@MainActor
final class GLTFEntityBuilder {

    private let scene: GLTFScene
    private let unit: ModelUnit?

    private var textures: [GLTFImageKey: TextureResource] = [:]
    private var meshCache: [String: MeshResource] = [:]
    /// The built entity of every node reachable from the scene roots.
    private var entities: [Int: Entity] = [:]
    /// Skinned-mesh entities: node index → (entity, layout).
    private var skinned: [(node: Int, entity: ModelEntity, layout: GLTFSkeletonLayout)] = []
    /// Names from the animation root down to the node container (empty, or the unit
    /// scale entity).
    private var pathPrefix: [String] = []
    private var drawableCount = 0

    /// Animations are sampled at this rate and played with linear tweening between
    /// samples. 60 Hz keeps cubic-spline curves smooth; long clips are capped below.
    static let sampleRate: Float = 60
    static let maxFramesPerAnimation = 6_000

    init(scene: GLTFScene, unit: ModelUnit?) {
        self.scene = scene
        self.unit = unit
    }

    func build() async throws -> ModelEntity {
        let root = ModelEntity()
        root.name = scene.name

        var container: Entity = root
        if let unit, unit != .meters {
            // glTF is metres by spec; an explicit unit is a correction for a file that
            // was exported wrong. A child entity carries it, so `ModelNode.scale(_:)`
            // on the root does not erase it.
            let scaled = Entity()
            scaled.name = "unit_\(unit.rawValue)"
            scaled.scale = SIMD3(repeating: unit.metersPerUnit)
            root.addChild(scaled)
            container = scaled
            pathPrefix = [scaled.name]
        }

        let restWorld = scene.restWorldMatrices()
        for rootNode in scene.roots {
            if let entity = try await makeEntity(node: rootNode, restWorld: restWorld, container: container) {
                container.addChild(entity)
            }
        }
        guard drawableCount > 0 else { throw ModelLoadingError.emptyMesh }

        try buildAnimations(storeIn: root)
        return root
    }

    // MARK: - Nodes

    private func makeEntity(node index: Int, restWorld: [simd_float4x4], container: Entity) async throws -> Entity? {
        guard let node = scene.nodes[safe: index], entities[index] == nil else { return nil }
        let entity: Entity
        if let meshIndex = node.mesh, let mesh = scene.meshes[safe: meshIndex], !mesh.primitives.isEmpty {
            if let skinIndex = node.skin, let skin = scene.skins[safe: skinIndex], !skin.joints.isEmpty,
               mesh.primitives.contains(where: { $0.joints != nil }) {
                // Skinned: the node keeps an empty entity in the hierarchy; the mesh
                // lives at the scene root, where the joints' transforms are expressed.
                entity = Entity()
                let layout = GLTFSkeletonLayout(skin: skin, scene: scene)
                let skinnedEntity = try await makeSkinnedEntity(
                    mesh: mesh, meshIndex: meshIndex, skinIndex: skinIndex, layout: layout,
                    restWorld: restWorld, name: "\(node.name)_skinned"
                )
                container.addChild(skinnedEntity)
                skinned.append((index, skinnedEntity, layout))
            } else {
                let (resource, materials) = try await meshResource(mesh: mesh, key: "mesh_\(meshIndex)", skeleton: nil, remap: nil)
                entity = ModelEntity(mesh: resource, materials: materials)
                drawableCount += 1
            }
        } else {
            entity = Entity()
        }
        entity.name = node.name
        entity.transform = Transform(scale: node.rest.scale, rotation: node.rest.rotation, translation: node.rest.translation)
        entities[index] = entity

        for child in node.children {
            if let childEntity = try await makeEntity(node: child, restWorld: restWorld, container: container) {
                entity.addChild(childEntity)
            }
        }
        return entity
    }

    private func makeSkinnedEntity(
        mesh: GLTFMesh, meshIndex: Int, skinIndex: Int, layout: GLTFSkeletonLayout,
        restWorld: [simd_float4x4], name: String
    ) async throws -> ModelEntity {
        let restLocals = layout.jointLocalMatrices(world: restWorld)
        let joints = layout.jointNodes.indices.map { i in
            MeshResource.Skeleton.Joint(
                name: layout.names[i],
                parentIndex: layout.parentIndices[i],
                inverseBindPoseMatrix: layout.inverseBindMatrices[i],
                restPoseTransform: Transform(matrix: restLocals[i])
            )
        }
        let skeleton = MeshResource.Skeleton(id: "skin_\(skinIndex)", joints: joints)
        let (resource, materials) = try await meshResource(
            mesh: mesh, key: "mesh_\(meshIndex)_skin_\(skinIndex)", skeleton: skeleton, remap: layout.remap
        )
        let entity = ModelEntity(mesh: resource, materials: materials)
        entity.name = name
        drawableCount += 1
        return entity
    }

    // MARK: - Meshes

    private func meshResource(
        mesh: GLTFMesh, key: String, skeleton: MeshResource.Skeleton?, remap: [Int]?
    ) async throws -> (MeshResource, [any RealityKit.Material]) {
        var materials: [any RealityKit.Material] = []
        if let cached = meshCache[key] {
            // A mesh instanced by several nodes: share the MeshResource, rebuild only
            // the (cheap) material list.
            for p in mesh.primitives {
                let material = scene.materials[safe: p.material ?? -1] ?? .default
                materials.append(await makeMaterial(material, vertexColors: p.colors))
            }
            return (cached, materials)
        }
        var parts: [MeshResource.Part] = []

        for (i, p) in mesh.primitives.enumerated() {
            let material = scene.materials[safe: p.material ?? -1] ?? .default
            var part = MeshResource.Part(id: "\(key)_\(i)", materialIndex: parts.count)
            part.positions = MeshBuffers.Positions(p.positions)
            let normals = p.normals ?? Self.smoothNormals(positions: p.positions, indices: p.indices)
            part.normals = MeshBuffers.Normals(normals)
            if let uvs = p.textureCoordinates {
                part.textureCoordinates = MeshBuffers.TextureCoordinates(uvs)
                if material.normalTexture != nil, !material.unlit {
                    let frame = Self.tangentFrame(
                        positions: p.positions, normals: normals, uvs: uvs, indices: p.indices, given: p.tangents
                    )
                    part.tangents = MeshBuffers.Tangents(frame.tangents)
                    part.bitangents = MeshBuffers.Tangents(frame.bitangents)
                }
            }
            part.triangleIndices = MeshBuffers.TriangleIndices(p.indices)

            if let skeleton, let remap, let joints = p.joints, let weights = p.weights, p.influencesPerVertex > 0 {
                let influences = zip(joints, weights).map { joint, weight in
                    MeshJointInfluence(jointIndex: remap[safe: Int(joint)] ?? 0, weight: weight)
                }
                part.jointInfluences = MeshResource.JointInfluences(
                    influences: MeshBuffers.JointInfluences(influences),
                    influencesPerVertex: p.influencesPerVertex
                )
                part.skeletonID = skeleton.id
            }
            parts.append(part)
            materials.append(await makeMaterial(material, vertexColors: p.colors))
        }

        var contents = MeshResource.Contents()
        contents.models = MeshModelCollection([MeshResource.Model(id: key, parts: parts)])
        if let skeleton { contents.skeletons = MeshSkeletonCollection([skeleton]) }
        let resource = try await MeshResource(from: contents)
        meshCache[key] = resource
        return (resource, materials)
    }

    /// Area-weighted smooth normals for a primitive that has none.
    static func smoothNormals(positions: [SIMD3<Float>], indices: [UInt32]) -> [SIMD3<Float>] {
        var normals = [SIMD3<Float>](repeating: .zero, count: positions.count)
        for t in stride(from: 0, to: indices.count - 2, by: 3) {
            let a = Int(indices[t]), b = Int(indices[t + 1]), c = Int(indices[t + 2])
            let n = simd_cross(positions[b] - positions[a], positions[c] - positions[a])
            normals[a] += n; normals[b] += n; normals[c] += n
        }
        return normals.map(GLTFScene.safeNormalize)
    }

    /// Per-vertex tangent and bitangent in RealityKit's (flipped-V) UV space.
    ///
    /// With a `TANGENT` attribute, glTF's bitangent is `cross(N, T) × w` in its own
    /// top-left UV space; flipping V negates it. Without one, the frame is accumulated
    /// from the UV derivatives of each triangle and Gram–Schmidt-orthogonalized.
    static func tangentFrame(
        positions: [SIMD3<Float>], normals: [SIMD3<Float>], uvs: [SIMD2<Float>], indices: [UInt32],
        given: [SIMD4<Float>]?
    ) -> (tangents: [SIMD3<Float>], bitangents: [SIMD3<Float>]) {
        if let given, given.count == positions.count {
            let t = given.map { GLTFScene.safeNormalize(SIMD3($0.x, $0.y, $0.z)) }
            let b = zip(zip(normals, t), given).map { pair, g in -simd_cross(pair.0, pair.1) * (g.w < 0 ? -1 : 1) }
            return (t, b)
        }
        var tan = [SIMD3<Float>](repeating: .zero, count: positions.count)
        var bit = [SIMD3<Float>](repeating: .zero, count: positions.count)
        for t in stride(from: 0, to: indices.count - 2, by: 3) {
            let i0 = Int(indices[t]), i1 = Int(indices[t + 1]), i2 = Int(indices[t + 2])
            let e1 = positions[i1] - positions[i0], e2 = positions[i2] - positions[i0]
            let d1 = uvs[i1] - uvs[i0], d2 = uvs[i2] - uvs[i0]
            let det = d1.x * d2.y - d2.x * d1.y
            guard abs(det) > 1e-12 else { continue }
            let r = 1 / det
            let sdir = (e1 * d2.y - e2 * d1.y) * r
            let tdir = (e2 * d1.x - e1 * d2.x) * r
            tan[i0] += sdir; tan[i1] += sdir; tan[i2] += sdir
            bit[i0] += tdir; bit[i1] += tdir; bit[i2] += tdir
        }
        var outT = [SIMD3<Float>](repeating: .zero, count: positions.count)
        var outB = [SIMD3<Float>](repeating: .zero, count: positions.count)
        for i in positions.indices {
            let n = normals[i]
            var t = tan[i] - n * simd_dot(n, tan[i])
            if simd_length_squared(t) < 1e-12 {
                // No usable UV gradient: any vector perpendicular to the normal.
                t = abs(n.x) < 0.9 ? simd_cross(n, SIMD3(1, 0, 0)) : simd_cross(n, SIMD3(0, 1, 0))
            }
            t = simd_normalize(t)
            let handedness: Float = simd_dot(simd_cross(n, t), bit[i]) < 0 ? -1 : 1
            outT[i] = t
            outB[i] = simd_cross(n, t) * handedness
        }
        return (outT, outB)
    }

    // MARK: - Materials

    private func makeMaterial(_ m: GLTFMaterial, vertexColors: [SIMD4<Float>]?) async -> any RealityKit.Material {
        var tint = m.baseColorFactor
        if let colors = vertexColors, !colors.isEmpty {
            // RealityKit's PBR material has no vertex-colour input: fold the average in,
            // as the STL/PLY path does, rather than drop the colour entirely.
            tint *= colors.reduce(SIMD4<Float>.zero, +) / Float(colors.count)
        }
        let baseTexture = await texture(m.baseColorTexture, .color)
        let alphaTexture = m.alphaMode == .opaque ? nil : await texture(m.baseColorTexture, .alpha)

        if m.unlit {
            var unlit = UnlitMaterial()
            unlit.color = .init(tint: Self.color(tint), texture: baseTexture)
            if m.alphaMode != .opaque {
                unlit.blending = .transparent(opacity: .init(scale: tint.w, texture: alphaTexture))
                if m.alphaMode == .mask { unlit.opacityThreshold = m.alphaCutoff }
            }
            if m.doubleSided { unlit.faceCulling = .none }
            return unlit
        }

        var pbr = PhysicallyBasedMaterial()
        pbr.baseColor = .init(tint: Self.color(SIMD4(tint.x, tint.y, tint.z, 1)), texture: baseTexture)
        pbr.metallic = .init(scale: m.metallicFactor, texture: await texture(m.metallicRoughnessTexture, .blue))
        pbr.roughness = .init(scale: m.roughnessFactor, texture: await texture(m.metallicRoughnessTexture, .green))
        if let normal = await texture(m.normalTexture, .normal) {
            pbr.normal = .init(texture: normal)
        }
        if let occlusion = await texture(m.occlusionTexture, .red) {
            pbr.ambientOcclusion = .init(texture: occlusion)
        }
        // glTF emission is factor × texture, so a zero factor switches it off.
        if simd_reduce_max(m.emissiveFactor) > 0 {
            pbr.emissiveColor = .init(
                color: Self.color(SIMD4(m.emissiveFactor, 1)),
                texture: await texture(m.emissiveTexture, .color)
            )
            pbr.emissiveIntensity = m.emissiveStrength
        }
        switch m.alphaMode {
        case .opaque:
            break
        case .blend:
            pbr.blending = .transparent(opacity: .init(scale: tint.w, texture: alphaTexture))
        case .mask:
            pbr.blending = .transparent(opacity: .init(scale: tint.w, texture: alphaTexture))
            pbr.opacityThreshold = m.alphaCutoff
        }
        if m.doubleSided { pbr.faceCulling = .none }
        return pbr
    }

    private func texture(_ ref: GLTFTextureRef?, _ variant: GLTFImageVariant) async -> PhysicallyBasedMaterial.Texture? {
        guard let ref else { return nil }
        let key = GLTFImageKey(image: ref.image, variant: variant)
        let resource: TextureResource
        if let cached = textures[key] {
            resource = cached
        } else {
            guard let image = scene.images[key] else { return nil }
            let semantic: TextureResource.Semantic
            switch variant {
            case .color: semantic = .color
            case .normal: semantic = .normal
            case .red, .green, .blue, .alpha: semantic = .scalar
            }
            guard let created = try? await TextureResource(image: image, options: .init(semantic: semantic)) else {
                return nil
            }
            textures[key] = created
            resource = created
        }
        return PhysicallyBasedMaterial.Texture(resource, sampler: Self.sampler(scene.samplers[safe: ref.sampler ?? -1]))
    }

    /// A Metal sampler from a glTF sampler — repeat and trilinear by default, as the
    /// spec's defaults say.
    static func sampler(_ def: GLTFSamplerDef?) -> MaterialParameters.Texture.Sampler {
        let descriptor = MTLSamplerDescriptor()
        func address(_ mode: Int?) -> MTLSamplerAddressMode {
            switch mode {
            case 33071: return .clampToEdge
            case 33648: return .mirrorRepeat
            default: return .repeat
            }
        }
        descriptor.sAddressMode = address(def?.wrapS)
        descriptor.tAddressMode = address(def?.wrapT)
        descriptor.magFilter = def?.magFilter == 9728 ? .nearest : .linear
        switch def?.minFilter {
        case 9728: descriptor.minFilter = .nearest; descriptor.mipFilter = .notMipmapped
        case 9729: descriptor.minFilter = .linear; descriptor.mipFilter = .notMipmapped
        case 9984: descriptor.minFilter = .nearest; descriptor.mipFilter = .nearest
        case 9985: descriptor.minFilter = .linear; descriptor.mipFilter = .nearest
        case 9986: descriptor.minFilter = .nearest; descriptor.mipFilter = .linear
        default: descriptor.minFilter = .linear; descriptor.mipFilter = .linear
        }
        descriptor.maxAnisotropy = 8
        return MaterialParameters.Texture.Sampler(descriptor)
    }

    /// A linear-sRGB colour (glTF factors are linear) as the platform colour type.
    static func color(_ rgba: SIMD4<Float>) -> SimpleMaterial.Color {
        func c(_ v: Float) -> CGFloat { CGFloat(min(max(v, 0), 1)) }
        let space = CGColorSpace(name: CGColorSpace.linearSRGB) ?? CGColorSpaceCreateDeviceRGB()
        let components: [CGFloat] = [c(rgba.x), c(rgba.y), c(rgba.z), c(rgba.w)]
        guard let cg = CGColor(colorSpace: space, components: components) else {
            return SimpleMaterial.Color(red: c(rgba.x), green: c(rgba.y), blue: c(rgba.z), alpha: c(rgba.w))
        }
        #if canImport(UIKit)
        return UIColor(cgColor: cg)
        #else
        return NSColor(cgColor: cg) ?? NSColor(red: c(rgba.x), green: c(rgba.y), blue: c(rgba.z), alpha: c(rgba.w))
        #endif
    }

    // MARK: - Animations

    /// One `AnimationResource` per glTF animation, stored on `root`.
    ///
    /// Every animated node gets a `Transform` track bound to its entity (so anything
    /// parented to a joint follows it), and every skinned mesh whose skeleton the
    /// animation reaches gets a `JointTransforms` track computed from the posed scene.
    private func buildAnimations(storeIn root: ModelEntity) throws {
        for animation in scene.animations {
            let animated = scene.animatedNodes(animation).filter { entities[$0] != nil }
            guard !animated.isEmpty else { continue }

            let duration = max(animation.endTime, 0)
            var frameCount = Int((duration * Self.sampleRate).rounded(.up)) + 1
            frameCount = min(max(frameCount, 2), Self.maxFramesPerAnimation)
            let interval = duration > 0 ? duration / Float(frameCount - 1) : 1 / Self.sampleRate

            let sortedAnimated = animated.sorted()
            var nodeFrames: [Int: [Transform]] = [:]
            let reachedSkins = skinned.indices.filter { s in
                skinned[s].layout.jointNodes.contains { joint in
                    sortedAnimated.contains { scene.isDescendant(joint, of: $0) }
                }
            }
            var skinFrames: [Int: [JointTransforms]] = [:]

            for k in 0..<frameCount {
                let t = Float(k) * interval
                let locals = scene.localTransforms(animation: animation, at: t)
                for node in sortedAnimated {
                    let l = locals[node]
                    nodeFrames[node, default: []].append(Transform(scale: l.scale, rotation: l.rotation, translation: l.translation))
                }
                if !reachedSkins.isEmpty {
                    let world = scene.worldMatrices(locals: locals.map(\.matrix))
                    for s in reachedSkins {
                        let matrices = skinned[s].layout.jointLocalMatrices(world: world)
                        skinFrames[s, default: []].append(JointTransforms(matrices.map { Transform(matrix: $0) }))
                    }
                }
            }

            var tracks: [any AnimationDefinition] = []
            for node in sortedAnimated {
                guard let frames = nodeFrames[node], let path = bindPath(to: node) else { continue }
                tracks.append(SampledAnimation(
                    frames: frames, name: "\(animation.name)/\(scene.nodes[node].name)",
                    tweenMode: .linear, frameInterval: interval, bindTarget: path.transform
                ))
            }
            for s in reachedSkins {
                guard let frames = skinFrames[s] else { continue }
                let entry = skinned[s]
                tracks.append(SampledAnimation(
                    jointNames: entry.layout.names, frames: frames, name: "\(animation.name)/\(entry.entity.name)",
                    tweenMode: .linear, frameInterval: interval,
                    bindTarget: entityPath(pathPrefix + [entry.entity.name]).jointTransforms
                ))
            }
            guard !tracks.isEmpty else { continue }
            let resource = try AnimationResource.generate(with: AnimationGroup(group: tracks, name: animation.name))
            resource.store(in: root)
        }
    }

    /// The bind path from the animation root to a node's entity — every name on the way
    /// down, so it resolves however RealityKit walks it.
    private func bindPath(to node: Int) -> BindTarget.EntityPath? {
        var names: [String] = []
        var current: Int? = node
        while let c = current {
            names.insert(scene.nodes[c].name, at: 0)
            current = scene.nodes[c].parent
        }
        return entityPath(pathPrefix + names)
    }

    private func entityPath(_ names: [String]) -> BindTarget.EntityPath {
        var path = BindTarget.entity(names[0])
        for name in names.dropFirst() { path = path.entity(name) }
        return path
    }
}
#endif // os(iOS) || os(macOS) || os(visionOS)

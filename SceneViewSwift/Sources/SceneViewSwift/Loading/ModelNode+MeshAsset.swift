#if os(iOS) || os(macOS) || os(visionOS)
import Foundation
import RealityKit
import simd
#if canImport(UIKit)
import UIKit
#elseif canImport(AppKit)
import AppKit
#endif

extension ModelNode {

    /// Builds a displayable node from parsed geometry.
    ///
    /// The asset is converted to metres first (``MeshAsset/inMeters()``), so the entity
    /// that comes back is at real-world scale: a 210 mm print is 0.21 along its long
    /// axis and stands 21 cm tall when anchored in AR, with no further scaling. That is
    /// the whole point of ``ModelUnit`` — a viewer that shows a shape but lies about its
    /// size is the thing every existing STL viewer already does.
    ///
    /// ```swift
    /// let asset = try MeshAsset.load(contentsOf: url, unit: .millimeters)
    /// let node = try await ModelNode(asset)
    /// print(asset.boundsInMeters!.extents)   // real size, before rendering anything
    /// ```
    ///
    /// - Parameters:
    ///   - asset: Parsed geometry, in whatever unit it was read with.
    ///   - enableCollision: Generate collision shapes so taps hit the model.
    /// - Throws: ``ModelLoadingError/emptyMesh`` when nothing drawable is left, or the
    ///   error RealityKit raises while generating the mesh.
    @MainActor
    public init(_ asset: MeshAsset, enableCollision: Bool = true) async throws {
        let metric = asset.inMeters()
        var descriptors: [MeshDescriptor] = []
        var materials: [any RealityKit.Material] = []

        for part in metric.parts {
            let part = part.generatingNormals()
            guard !part.positions.isEmpty, part.indices.count >= 3 else { continue }

            var descriptor = MeshDescriptor(name: part.name)
            descriptor.positions = MeshBuffers.Positions(part.positions)
            if let normals = part.normals, normals.count == part.positions.count {
                descriptor.normals = MeshBuffers.Normals(normals)
            }
            if let uvs = part.textureCoordinates, uvs.count == part.positions.count {
                descriptor.textureCoordinates = MeshBuffers.TextureCoordinates(uvs)
            }
            descriptor.primitives = .triangles(part.indices)
            descriptor.materials = .allFaces(UInt32(materials.count))

            descriptors.append(descriptor)
            materials.append(await ModelNode.material(for: part))
        }

        guard !descriptors.isEmpty else { throw ModelLoadingError.emptyMesh }

        let mesh = try MeshResource.generate(from: descriptors)
        let entity = ModelEntity(mesh: mesh, materials: materials)
        entity.name = metric.parts.first?.name ?? "mesh"
        if enableCollision {
            entity.generateCollisionShapes(recursive: true)
            entity.makeInputTargetable()
        }
        self.init(entity)
    }

    /// Builds the RealityKit material for one part.
    ///
    /// Physically-based rather than `SimpleMaterial` for the same reason
    /// ``GeometryNode/defaultMaterial(color:unlit:)`` is: `SimpleMaterial` ignores the
    /// image-based lighting the scene already sets up, and an unlit grey STL looks like
    /// a rendering bug.
    ///
    /// Per-vertex colours are folded into the tint. RealityKit's `MeshDescriptor` has no
    /// vertex-colour channel, so a coloured PLY scan would otherwise come back uniformly
    /// grey; averaging is a lossy but honest approximation, and the full array stays on
    /// ``MeshGeometry/colors`` for callers that need it.
    @MainActor
    private static func material(for part: MeshGeometry) async -> any RealityKit.Material {
        var pbr = PhysicallyBasedMaterial()
        var tint = SIMD4<Float>(1, 1, 1, 1)

        if let baseColor = part.material?.baseColor {
            tint = baseColor
        }
        if let colors = part.colors, !colors.isEmpty {
            let sum = colors.reduce(SIMD4<Float>.zero, +)
            let average = sum / Float(colors.count)
            tint *= average
        }

        pbr.baseColor = .init(tint: color(from: tint))
        pbr.metallic = .init(floatLiteral: part.material?.metallic ?? 0)
        pbr.roughness = .init(floatLiteral: part.material?.roughness ?? 0.5)

        if let textureURL = part.material?.baseColorTextureURL,
           FileManager.default.fileExists(atPath: textureURL.path),
           let texture = try? await TextureResource(contentsOf: textureURL) {
            pbr.baseColor = .init(tint: color(from: tint), texture: .init(texture))
        }
        if tint.w < 1 {
            pbr.blending = .transparent(opacity: .init(floatLiteral: tint.w))
        }
        return pbr
    }

    /// Clamps and converts a linear RGBA vector into the platform colour type.
    private static func color(from rgba: SIMD4<Float>) -> SimpleMaterial.Color {
        func clamp(_ value: Float) -> CGFloat { CGFloat(Swift.min(Swift.max(value, 0), 1)) }
        return SimpleMaterial.Color(
            red: clamp(rgba.x),
            green: clamp(rgba.y),
            blue: clamp(rgba.z),
            alpha: clamp(rgba.w)
        )
    }
}
#endif // os(iOS) || os(macOS) || os(visionOS)

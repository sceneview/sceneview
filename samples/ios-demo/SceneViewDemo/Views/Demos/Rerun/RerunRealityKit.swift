import Metal
import RealityKit

/// RealityKit resources shared by the Rerun replay (iOS only) and the Real-World Scan demo
/// (iOS and macOS). Kept outside `RerunReplayStage.swift`'s `#if os(iOS)` so both platforms
/// build the same mesh from the same shared geometry.
enum RerunRealityKit {
    /// Point-exact sampling: one texel per point, never blended with its neighbours.
    /// Computed, not stored: the sampler type is not `Sendable`, so a shared static would
    /// need an actor, and building one is cheap.
    static var nearest: MaterialParameters.Texture.Sampler {
        let descriptor = MTLSamplerDescriptor()
        descriptor.minFilter = .nearest
        descriptor.magFilter = .nearest
        descriptor.mipFilter = .notMipmapped
        descriptor.sAddressMode = .clampToEdge
        descriptor.tAddressMode = .clampToEdge
        return MaterialParameters.Texture.Sampler(descriptor)
    }

    /// RealityKit samples textures from the bottom-left; the shared geometry writes them from
    /// the top-left like Filament, so v is flipped here, once.
    static func resource(_ mesh: RerunMesh) -> MeshResource? {
        guard !mesh.isEmpty else { return nil }
        var descriptor = MeshDescriptor()
        descriptor.positions = MeshBuffers.Positions(mesh.positions)
        if mesh.uvs.count == mesh.positions.count {
            descriptor.textureCoordinates = MeshBuffers.TextureCoordinates(mesh.uvs.map { SIMD2($0.x, 1 - $0.y) })
        }
        descriptor.primitives = .triangles(mesh.indices)
        return try? MeshResource.generate(from: [descriptor])
    }
}

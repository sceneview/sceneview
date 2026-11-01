import Foundation
import simd

extension GLTFScene {

    /// Every drawn primitive as a ``MeshGeometry`` in world space at the rest pose —
    /// the flat view `MeshAsset` exposes (sizes, collision). UVs are dropped because
    /// textures are not part of that view; base colour, metallic and roughness factors
    /// are kept.
    func flattenedGeometry() -> [MeshGeometry] {
        let world = restWorldMatrices()
        var parts: [MeshGeometry] = []
        var visited = Set<Int>()
        func visit(_ index: Int) {
            guard let node = nodes[safe: index], visited.insert(index).inserted else { return }
            if let mesh = node.mesh.flatMap({ meshes[safe: $0] }) {
                // A skinned mesh is posed by its joints, not by its node (glTF spec).
                let m = node.skin == nil ? world[index] : matrix_identity_float4x4
                let normalMatrix = simd_float3x3(
                    SIMD3(m.columns.0.x, m.columns.0.y, m.columns.0.z),
                    SIMD3(m.columns.1.x, m.columns.1.y, m.columns.1.z),
                    SIMD3(m.columns.2.x, m.columns.2.y, m.columns.2.z)
                ).inverse.transpose
                for (i, p) in mesh.primitives.enumerated() where !p.indices.isEmpty {
                    let material = materials[safe: p.material ?? -1] ?? .default
                    parts.append(MeshGeometry(
                        name: "\(node.name)_\(i)",
                        positions: p.positions.map { v in
                            let w = m * SIMD4(v, 1)
                            return SIMD3(w.x, w.y, w.z)
                        },
                        normals: p.normals?.map { GLTFScene.safeNormalize(normalMatrix * $0) },
                        colors: p.colors,
                        indices: p.indices,
                        material: MeshMaterialDescription(
                            name: material.name,
                            baseColor: material.baseColorFactor,
                            metallic: material.metallicFactor,
                            roughness: material.roughnessFactor
                        )
                    ))
                }
            }
            node.children.forEach(visit)
        }
        roots.forEach(visit)
        return parts
    }
}

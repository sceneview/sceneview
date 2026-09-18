import SwiftUI
import RealityKit
import SceneViewSwift

/// `MeshNode.fromVertices` — geometry from raw positions and triangle indices.
///
/// Two meshes, two ways to light them: the pyramid passes its own normals, the
/// diamond passes `nil` and lets the node compute them.
struct CustomMeshDemo: View {
    var body: some View {
        DemoScaffold("Custom Mesh") {
            SceneView { root in
                let pyramid = try? MeshNode.fromVertices(
                    positions: [
                        [-0.15, -0.15, -0.15], [0.15, -0.15, -0.15],   // base
                        [0.15, -0.15, 0.15], [-0.15, -0.15, 0.15],
                        [0, 0.2, 0],                                   // apex
                    ],
                    normals: [[0, -1, 0], [0, -1, 0], [0, -1, 0], [0, -1, 0], [0, 1, 0]],
                    indices: [
                        0, 1, 2, 0, 2, 3,                              // base, two triangles
                        0, 1, 4, 1, 2, 4, 2, 3, 4, 3, 0, 4,            // four sides
                    ],
                    material: .pbr(color: .systemTeal, metallic: 0.7, roughness: 0.25)
                )
                let diamond = try? MeshNode.fromVertices(
                    positions: [
                        [0.15, 0, -0.15], [0.15, 0, 0.15],             // equator
                        [-0.15, 0, 0.15], [-0.15, 0, -0.15],
                        [0, 0.2, 0], [0, -0.2, 0],                     // top and bottom apex
                    ],
                    normals: nil,
                    indices: [
                        0, 1, 4, 1, 2, 4, 2, 3, 4, 3, 0, 4,            // top faces
                        1, 0, 5, 2, 1, 5, 3, 2, 5, 0, 3, 5,            // bottom faces
                    ],
                    material: .pbr(color: .systemPink, metallic: 0.9, roughness: 0.1)
                )

                let meshes: [(name: String, color: SimpleMaterial.Color, x: Float, node: MeshNode?)] = [
                    ("Pyramid", .systemTeal, -0.35, pyramid),
                    ("Diamond", .systemPink, 0.35, diamond),
                ]
                for mesh in meshes {
                    guard let node = mesh.node else { continue }
                    root.addChild(node.position([mesh.x, 0, 0]).entity)

                    let label = TextNode(text: mesh.name, fontSize: 0.04, color: mesh.color, depth: 0.005)
                        .centered()
                        .position([mesh.x, -0.3, 0])
                    root.addChild(label.entity)
                }
            }
            .cameraControls(.orbit)
            // Metallic PBR needs an IBL to have anything to reflect (#2114) — lit by
            // the studio HDR, not drawn in front of it.
            .environment(.custom(name: "Studio", hdrFile: "studio.hdr", showSkybox: false))
        } accessory: {
            DemoHint("MeshNode.fromVertices — positions, normals, indices")
        }
    }
}

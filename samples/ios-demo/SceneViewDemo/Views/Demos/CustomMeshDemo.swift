import SwiftUI
import RealityKit
import SceneViewSwift

/// Custom mesh from raw vertex data -- a colored pyramid and a diamond.
struct CustomMeshDemo: View {
    var body: some View {
        ZStack {
            SceneView { root in
                // -- Pyramid from raw vertices --
                if let pyramid = try? MeshNode.fromVertices(
                    positions: [
                        // Base
                        SIMD3<Float>(-0.15, -0.15, -0.15),
                        SIMD3<Float>(0.15, -0.15, -0.15),
                        SIMD3<Float>(0.15, -0.15, 0.15),
                        SIMD3<Float>(-0.15, -0.15, 0.15),
                        // Apex
                        SIMD3<Float>(0, 0.2, 0),
                    ],
                    normals: [
                        SIMD3<Float>(0, -1, 0),
                        SIMD3<Float>(0, -1, 0),
                        SIMD3<Float>(0, -1, 0),
                        SIMD3<Float>(0, -1, 0),
                        SIMD3<Float>(0, 1, 0),
                    ],
                    indices: [
                        // Base (2 triangles)
                        0, 1, 2, 0, 2, 3,
                        // Front face
                        0, 1, 4,
                        // Right face
                        1, 2, 4,
                        // Back face
                        2, 3, 4,
                        // Left face
                        3, 0, 4,
                    ],
                    material: .pbr(color: .systemTeal, metallic: 0.7, roughness: 0.25)
                ) {
                    pyramid.entity.position = .init(x: -0.35, y: 0, z: -2)
                    root.addChild(pyramid.entity)
                }

                // Label
                let pyramidLabel = TextNode(text: "Pyramid", fontSize: 0.04, color: .systemTeal, depth: 0.005)
                    .centered()
                    .position(.init(x: -0.35, y: -0.3, z: -2))
                root.addChild(pyramidLabel.entity)

                // -- Diamond (double pyramid) --
                if let diamond = try? MeshNode.fromVertices(
                    positions: [
                        // Equator ring
                        SIMD3<Float>(0.15, 0, -0.15),
                        SIMD3<Float>(0.15, 0, 0.15),
                        SIMD3<Float>(-0.15, 0, 0.15),
                        SIMD3<Float>(-0.15, 0, -0.15),
                        // Top apex
                        SIMD3<Float>(0, 0.2, 0),
                        // Bottom apex
                        SIMD3<Float>(0, -0.2, 0),
                    ],
                    normals: nil,
                    indices: [
                        // Top faces
                        0, 1, 4,
                        1, 2, 4,
                        2, 3, 4,
                        3, 0, 4,
                        // Bottom faces
                        1, 0, 5,
                        2, 1, 5,
                        3, 2, 5,
                        0, 3, 5,
                    ],
                    material: .pbr(color: .systemPink, metallic: 0.9, roughness: 0.1)
                ) {
                    diamond.entity.position = .init(x: 0.35, y: 0, z: -2)
                    root.addChild(diamond.entity)
                }

                // Label
                let diamondLabel = TextNode(text: "Diamond", fontSize: 0.04, color: .systemPink, depth: 0.005)
                    .centered()
                    .position(.init(x: 0.35, y: -0.3, z: -2))
                root.addChild(diamondLabel.entity)
            }
            .cameraControls(.orbit)
            // Both the pyramid and the diamond use a metallic/rough `.pbr()`
            // material — with no IBL they have nothing to reflect and render
            // as flat silhouettes despite being built from raw PBR vertex
            // data. Same `.studio` preset as ModelViewerDemo (#2114).
            .environment(.studio)
            .ignoresSafeArea()

            VStack {
                Spacer()
                Text("MeshNode.fromVertices -- raw vertex data")
                    .font(.caption)
                    .foregroundStyle(.white.opacity(0.6))
                    .padding(.bottom, 12)
            }
        }
        .background(Color.black)
    }
}

import SwiftUI
import RealityKit
import SceneViewSwift

/// Showcases all 5 geometry types: cube, sphere, cylinder, cone, plane.
/// Named `GeometryDemo` to mirror the Android demo of the same name.
struct GeometryDemo: View {
    var body: some View {
        ZStack {
            SceneView { root in
                // Row of all geometry types
                let shapes: [(GeometryNode, Float)] = [
                    (GeometryNode.cube(
                        size: 0.2,
                        material: .pbr(color: .systemBlue, metallic: 0.6, roughness: 0.3),
                        cornerRadius: 0.015
                    ), -0.6),
                    (GeometryNode.sphere(
                        radius: 0.12,
                        material: .pbr(color: .systemRed, metallic: 0.8, roughness: 0.15)
                    ), -0.3),
                    (GeometryNode.cylinder(radius: 0.1, height: 0.25, color: .systemGreen), 0.0),
                    (GeometryNode.cone(height: 0.25, radius: 0.12, color: .systemOrange), 0.3),
                    // `GeometryNode.plane` builds the mesh in the XZ plane (flat,
                    // normal pointing +Y) to match the Android convention. From
                    // this demo's downward-tilted orbit camera it renders
                    // edge-on (invisible), so we stand it upright via a +90°
                    // rotation around X — the plane now faces +Z toward the
                    // camera. Library default stays XZ; call `.rotation(...)` to
                    // re-orient a plane for a camera-facing layout (issue #1058).
                    (GeometryNode.plane(width: 0.25, depth: 0.25, color: .systemPurple)
                        .rotation(angle: .pi / 2, axis: [1, 0, 0]), 0.6),
                ]

                for (node, x) in shapes {
                    node.entity.position = .init(x: x, y: 0, z: -2)
                    root.addChild(node.entity)
                }

                // Labels beneath
                let labels = ["Cube", "Sphere", "Cylinder", "Cone", "Plane"]
                let xOffsets: [Float] = [-0.6, -0.3, 0.0, 0.3, 0.6]
                let labelColors: [UIColor] = [.systemBlue, .systemRed, .systemGreen, .systemOrange, .systemPurple]
                for i in 0..<labels.count {
                    let label = TextNode(text: labels[i], fontSize: 0.04, color: labelColors[i], depth: 0.005)
                        .centered()
                        .position(.init(x: xOffsets[i], y: -0.25, z: -2))
                    root.addChild(label.entity)
                }
            }
            .cameraControls(.orbit)
            // The cube and sphere use `.pbr(metallic:roughness:)` — this
            // demo's own caption below claims "PBR materials," but with no
            // IBL those metallic/rough surfaces have nothing to reflect and
            // render flat, undercutting the claim. Same `.studio` preset as
            // ModelViewerDemo (#2114).
            .environment(.studio)
            .ignoresSafeArea()

            VStack {
                Spacer()
                Text("5 built-in geometry types with PBR materials")
                    .font(.caption)
                    .foregroundStyle(.white.opacity(0.6))
                    .padding(.bottom, 12)
            }
        }
        .background(Color.black)
    }
}

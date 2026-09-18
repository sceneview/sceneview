import SwiftUI
import RealityKit
import SceneViewSwift

/// `GeometryNode` — the five built-in primitives, one call each.
/// Named `GeometryDemo` to mirror the Android demo of the same name.
struct GeometryDemo: View {
    var body: some View {
        DemoScaffold("Geometry Primitives") {
            SceneView { root in
                let shapes: [(name: String, color: SimpleMaterial.Color, node: GeometryNode)] = [
                    ("Cube", .systemBlue,
                     .cube(size: 0.2, material: .pbr(color: .systemBlue, metallic: 0.6, roughness: 0.3),
                           cornerRadius: 0.015)),
                    ("Sphere", .systemRed,
                     .sphere(radius: 0.12, material: .pbr(color: .systemRed, metallic: 0.8, roughness: 0.15))),
                    ("Cylinder", .systemGreen, .cylinder(radius: 0.1, height: 0.25, color: .systemGreen)),
                    ("Cone", .systemOrange, .cone(height: 0.25, radius: 0.12, color: .systemOrange)),
                    // A plane is built flat on XZ (normal +Y), as on Android: seen
                    // from the front it is edge-on, so stand it up to face +Z (#1058).
                    ("Plane", .systemPurple,
                     GeometryNode.plane(width: 0.25, depth: 0.25, color: .systemPurple)
                        .rotation(angle: .pi / 2, axis: [1, 0, 0])),
                ]
                for (index, shape) in shapes.enumerated() {
                    let x = (Float(index) - Float(shapes.count - 1) / 2) * 0.32
                    shape.node.entity.position = [x, 0, 0]
                    root.addChild(shape.node.entity)

                    let label = TextNode(text: shape.name, fontSize: 0.04, color: shape.color, depth: 0.005)
                        .centered()
                        .position([x, -0.25, 0])
                    root.addChild(label.entity)
                }
            }
            .cameraControls(.orbit)
            // The metallic cube and sphere need an IBL to have anything to reflect
            // (#2114) — lit by the studio HDR, not drawn in front of it: coloured
            // labels over a photo are unreadable.
            .environment(.custom(name: "Studio", hdrFile: "studio.hdr", showSkybox: false))
        } accessory: {
            DemoHint("Five primitives, one call each — drag to orbit")
        }
    }
}

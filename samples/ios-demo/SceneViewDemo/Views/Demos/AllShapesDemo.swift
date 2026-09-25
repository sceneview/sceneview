import SwiftUI
import RealityKit
import SceneViewSwift

/// `GeometryNode` — the five built-in primitives, one call each.
/// Named `GeometryDemo` to mirror the Android demo of the same name.
struct GeometryDemo: View {
    var body: some View {
        DemoScaffold("Geometry Primitives") {
            SceneView { root in
                let shapes: [(name: String, node: GeometryNode)] = [
                    ("Cube",
                     .cube(size: 0.2, material: .pbr(color: .systemBlue, metallic: 0.6, roughness: 0.3),
                           cornerRadius: 0.015)),
                    ("Sphere",
                     .sphere(radius: 0.12, material: .pbr(color: .systemRed, metallic: 0.8, roughness: 0.15))),
                    ("Cylinder", .cylinder(radius: 0.1, height: 0.25, color: .systemGreen)),
                    ("Cone", .cone(height: 0.25, radius: 0.12, color: .systemOrange)),
                    // A plane is built flat on XZ (normal +Y), as on Android: seen
                    // from the front it is edge-on, so stand it up to face +Z (#1058).
                    ("Plane",
                     GeometryNode.plane(width: 0.25, depth: 0.25, color: .systemPurple)
                        .rotation(angle: .pi / 2, axis: [1, 0, 0])),
                ]
                // Two rows (three over two) rather than one: in a portrait
                // frame a single row of five left the names a few points tall
                // (#3788). Each name sits under its shape in one light neutral,
                // readable on the dark stage in both app themes.
                let columns = 3
                for (index, shape) in shapes.enumerated() {
                    let row = index / columns
                    let inRow = row == 0 ? columns : shapes.count - columns
                    let x = (Float(index % columns) - Float(inRow - 1) / 2) * 0.46
                    let y: Float = row == 0 ? 0.3 : -0.36
                    shape.node.entity.position = [x, y, 0]
                    root.addChild(shape.node.entity)

                    let label = TextNode(text: shape.name, fontSize: 0.075,
                                         color: UIColor(SceneViewTokens.Glass.onGlass), depth: 0.005)
                        .centered()
                        .position([x, y - 0.24, 0])
                    root.addChild(label.entity)
                }
            }
            .cameraControls(.orbit)
            // The metallic cube and sphere need an IBL to have anything to reflect
            // (#2114) — lit by the studio HDR, not drawn in front of it: coloured
            // labels over a photo are unreadable.
            .environment(.custom(name: "Studio", hdrFile: "studio.hdr", showSkybox: false))
        } accessory: {
            DemoHint("Five built-in shapes, each made with one call — drag to orbit")
        }
    }
}

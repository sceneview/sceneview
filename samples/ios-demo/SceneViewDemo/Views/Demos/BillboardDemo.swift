import SwiftUI
import RealityKit
import SceneViewSwift

/// `BillboardNode` — a label that turns to face the camera, wherever you orbit.
struct BillboardDemo: View {
    var body: some View {
        DemoScaffold("Billboard") {
            SceneView { root in
                let items: [(label: String, color: SimpleMaterial.Color, x: Float, shape: GeometryNode)] = [
                    ("Player 1", .systemBlue, -0.5,
                     .cube(size: 0.2, color: .systemBlue, cornerRadius: 0.02)),
                    ("Treasure", .systemYellow, 0,
                     .sphere(radius: 0.12, material: .pbr(color: .systemYellow, metallic: 0.9, roughness: 0.1))),
                    ("Enemy", .systemRed, 0.5,
                     .cone(height: 0.25, radius: 0.12, color: .systemRed)),
                ]
                for item in items {
                    item.shape.entity.position = [item.x, 0, 0]
                    root.addChild(item.shape.entity)

                    // The shape stays put; only its label turns with the camera.
                    let label = BillboardNode.text(item.label, fontSize: 0.05, color: item.color)
                        .position([item.x, 0.28, 0])
                    root.addChild(label.entity)
                }
            }
            .cameraControls(.orbit)
            // The metallic "Treasure" sphere needs an IBL to have anything to reflect
            // (#2114) — lit by the studio HDR, not drawn in front of it: coloured
            // labels over a photo are unreadable.
            .environment(.custom(name: "Studio", hdrFile: "studio.hdr", showSkybox: false))
        } accessory: {
            DemoHint("Drag to orbit — the labels keep facing you")
        }
    }
}

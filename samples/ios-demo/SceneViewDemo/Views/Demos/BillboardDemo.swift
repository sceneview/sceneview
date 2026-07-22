import SwiftUI
import RealityKit
import SceneViewSwift

/// Billboard nodes that always face the camera -- labels floating above shapes.
struct BillboardDemo: View {
    var body: some View {
        ZStack {
            SceneView { root in
                // Three shapes with billboard labels above them
                let items: [(String, UIColor, Float, GeometryNode)] = [
                    ("Player 1", .systemBlue, -0.5,
                     GeometryNode.cube(size: 0.2, color: .systemBlue, cornerRadius: 0.02)),
                    ("Treasure", .systemYellow, 0.0,
                     GeometryNode.sphere(radius: 0.12, material: .pbr(color: .systemYellow, metallic: 0.9, roughness: 0.1))),
                    ("Enemy", .systemRed, 0.5,
                     GeometryNode.cone(height: 0.25, radius: 0.12, color: .systemRed)),
                ]

                for (label, color, xPos, shape) in items {
                    shape.entity.position = .init(x: xPos, y: -0.1, z: -2)
                    root.addChild(shape.entity)

                    // Billboard text that always faces camera
                    let billboard = BillboardNode.text(label, fontSize: 0.04, color: color)
                        .position(.init(x: xPos, y: 0.25, z: -2))
                    root.addChild(billboard.entity)
                }

                // A larger billboard title at top
                let title = BillboardNode.text("Always Facing You", fontSize: 0.06, color: .white)
                    .position(.init(x: 0, y: 0.55, z: -2))
                root.addChild(title.entity)
            }
            .cameraControls(.orbit)
            // The "Treasure" sphere uses a metallic/rough `.pbr()` material —
            // meant to read as shiny and valuable — but with no IBL it has
            // nothing to reflect and renders flat. Same `.studio` preset as
            // ModelViewerDemo (#2114).
            .environment(.studio)
            .ignoresSafeArea()

            VStack {
                Spacer()
                Text("Orbit the camera -- labels always face you")
                    .font(.caption)
                    .foregroundStyle(.white.opacity(0.6))
                    .padding(.bottom, 12)
            }
        }
        .background(Color.black)
    }
}

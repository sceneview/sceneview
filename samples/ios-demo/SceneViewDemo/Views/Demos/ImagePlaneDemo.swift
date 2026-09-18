import SwiftUI
import RealityKit
import SceneViewSwift

/// `ImageNode` — flat pictures standing in 3D space, hung here as a curved
/// gallery wall. Named `ImageDemo` to mirror the Android demo of the same name.
struct ImageDemo: View {
    var body: some View {
        DemoScaffold("Image Planes") {
            SceneView { root in
                let frames: [(name: String, color: SimpleMaterial.Color)] = [
                    ("Red", .systemRed), ("Orange", .systemOrange), ("Yellow", .systemYellow),
                    ("Green", .systemGreen), ("Blue", .systemBlue), ("Purple", .systemPurple),
                ]
                let wallRadius: Float = 1.5
                for (index, frame) in frames.enumerated() {
                    // Spread evenly around the middle of the wall, each plane
                    // turned to face the centre of the curve.
                    let angle = (Float(index) - Float(frames.count - 1) / 2) * 0.35
                    let picture = ImageNode.color(frame.color, width: 0.3, height: 0.3)
                        .position([sin(angle) * wallRadius, 0.05, (1 - cos(angle)) * wallRadius])
                        .rotation(angle: -angle, axis: [0, 1, 0])
                    root.addChild(picture.entity)

                    // A child of the plane: the caption hangs under it and turns with it.
                    let caption = TextNode(text: frame.name, fontSize: 0.04, color: frame.color, depth: 0.004)
                        .centered()
                        .position([0, -0.24, 0])
                    picture.entity.addChild(caption.entity)
                }
            }
            .cameraControls(.orbit)
        } accessory: {
            DemoHint("ImageNode.color — swap in ImageNode.load for a picture")
        }
    }
}

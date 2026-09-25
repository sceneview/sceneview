import SwiftUI
import RealityKit
import SceneViewSwift

/// `ImageNode` — flat pictures standing in 3D space, hung here as a curved
/// gallery wall. Named `ImageDemo` to mirror the Android demo of the same name.
///
/// The pictures are the app's own bundled model portraits (`model_thumb_*` in
/// the asset catalog), loaded with `ImageNode.load` — real images, not
/// colour swatches (#3788).
struct ImageDemo: View {
    /// Asset-catalog image and caption for each frame, left to right, top row first.
    private static let pictures: [(asset: String, caption: String)] = [
        ("model_thumb_khronos_fox", "Fox"),
        ("model_thumb_khronos_damaged_helmet", "Helmet"),
        ("model_thumb_khronos_lantern", "Lantern"),
        ("model_thumb_khronos_toy_car", "Toy Car"),
        ("model_thumb_shiba", "Shiba"),
        ("model_thumb_animated_butterfly", "Butterfly"),
    ]
    private static let columns = 3
    private static let pictureSize: Float = 0.45

    /// The loaded frames, in `pictures` order. Empty until the `.task` lands.
    @State private var frames: [(node: ImageNode, caption: String)] = []

    var body: some View {
        DemoScaffold("Image Planes") {
            SceneView { root in
                let wallRadius: Float = 1.6
                let rows = (frames.count + Self.columns - 1) / Self.columns
                for (index, frame) in frames.enumerated() {
                    let row = index / Self.columns
                    let column = index % Self.columns
                    // Spread each row around the middle of the wall, every plane
                    // turned to face the centre of the curve.
                    let angle = (Float(column) - Float(Self.columns - 1) / 2) * 0.36
                    let y = (Float(rows - 1) / 2 - Float(row)) * 0.72
                    let picture = frame.node
                        .position([sin(angle) * wallRadius, y, (1 - cos(angle)) * wallRadius])
                        .rotation(angle: -angle, axis: [0, 1, 0])
                    root.addChild(picture.entity)

                    // A light mat behind the picture, so a dark photo still reads
                    // as a framed print against the dark stage.
                    let mat = ImageNode.color(UIColor(SceneViewTokens.Glass.onGlass),
                                              width: Self.pictureSize + 0.03,
                                              height: Self.pictureSize + 0.03)
                        .position([0, 0, -0.004])
                    picture.entity.addChild(mat.entity)

                    // A child of the plane: the caption hangs under it and turns with it.
                    let caption = TextNode(text: frame.caption, fontSize: 0.07,
                                           color: UIColor(SceneViewTokens.Glass.onGlass), depth: 0.004)
                        .centered()
                        .position([0, -Self.pictureSize / 2 - 0.09, 0])
                    picture.entity.addChild(caption.entity)
                }
            }
            .cameraControls(.orbit)
            .contentID(frames.count)
            .task { await loadFrames() }
        } accessory: {
            DemoHint("Six pictures hung on a curved wall — drag to orbit")
        }
    }

    private func loadFrames() async {
        var loaded: [(node: ImageNode, caption: String)] = []
        for picture in Self.pictures {
            guard let node = try? await ImageNode.load(picture.asset,
                                                       width: Self.pictureSize,
                                                       height: Self.pictureSize) else { continue }
            loaded.append((node, picture.caption))
        }
        frames = loaded
    }
}

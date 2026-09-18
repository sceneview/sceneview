import SwiftUI
import RealityKit
import SceneViewSwift

/// `TextNode` — real 3D text: sized in metres, extruded in metres.
///
/// The strip changes the extrusion so the depth is something you see, not a
/// number in a call. Orbit to look at the side of the letters.
struct TextDemo: View {
    enum Extrusion: String, CaseIterable {
        case flat = "Flat", raised = "Raised", deep = "Deep"

        /// Depth as a share of the font size.
        var ratio: Float {
            switch self {
            case .flat: 0.02
            case .raised: 0.2
            case .deep: 0.6
            }
        }
    }

    @State private var extrusion = Extrusion.raised

    var body: some View {
        DemoScaffold("3D Text") {
            SceneView { root in
                let lines: [(text: String, size: Float, color: SimpleMaterial.Color, y: Float)] = [
                    ("SceneView", 0.12, .white, 0.14),
                    ("3D text for SwiftUI", 0.05, .systemBlue, 0),
                    ("sized and extruded in metres", 0.032, .lightGray, -0.09),
                ]
                for line in lines {
                    // RealityKit text grows rightwards from its origin;
                    // `centered()` puts the middle of the line on the position.
                    let node = TextNode(text: line.text, fontSize: line.size, color: line.color,
                                        depth: line.size * extrusion.ratio)
                        .centered()
                        .position([0, line.y, 0])
                    root.addChild(node.entity)
                }
            }
            .contentID(extrusion)
            .cameraControls(.orbit)
            // A three-quarter view: head-on, an extrusion is invisible.
            .cameraOrbit(azimuth: 0.5, elevation: 0.2)
        } accessory: {
            DemoOptionStrip(Extrusion.allCases, selection: $extrusion) { $0.rawValue }
        }
    }
}

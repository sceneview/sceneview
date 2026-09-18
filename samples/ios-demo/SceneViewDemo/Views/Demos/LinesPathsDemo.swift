import SwiftUI
import RealityKit
import SceneViewSwift

/// `PathNode` and `LineNode` — polylines in metres: a closed triangle, a
/// circle, a grid, an axis gizmo, and a helix sampled point by point.
struct LinesPathsDemo: View {
    var body: some View {
        DemoScaffold("Lines & Paths") {
            SceneView { root in
                let triangle = PathNode(
                    points: [[0.4, 0.3, 0], [0.2, -0.05, 0], [0.6, -0.05, 0]],
                    closed: true, thickness: 0.005, color: .systemCyan
                )
                let circle = PathNode.circle(center: [0.4, -0.3, 0], radius: 0.25, segments: 48,
                                             thickness: 0.004, color: .systemYellow)
                let floor = PathNode.grid(size: 1.5, divisions: 8, thickness: 0.002,
                                          color: .init(white: 0.35, alpha: 1))
                    .position([0, -0.4, 0])
                for path in [triangle, circle, floor] {
                    root.addChild(path.entity)
                }

                for line in LineNode.axisGizmo(at: [0, -0.35, 0.5], length: 0.15, thickness: 0.004) {
                    root.addChild(line.entity)
                }

                // A helix is a function, not a list: sample it, one bead per step.
                let steps = 60
                for step in 0..<steps {
                    let progress = Float(step) / Float(steps)
                    let turn = progress * 4 * .pi
                    let bead = GeometryNode.sphere(
                        radius: 0.012,
                        color: .init(hue: CGFloat(0.55 + progress * 0.3), saturation: 0.9, brightness: 1, alpha: 1)
                    )
                    bead.entity.position = [cos(turn) * 0.3 - 0.5, progress * 0.8 - 0.4, sin(turn) * 0.3]
                    root.addChild(bead.entity)
                }
            }
            .cameraControls(.orbit)
        } accessory: {
            DemoHint("PathNode · LineNode — triangle, circle, grid, axes, helix")
        }
    }
}

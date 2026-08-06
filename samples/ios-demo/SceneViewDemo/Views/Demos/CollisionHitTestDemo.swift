import SwiftUI
import RealityKit
import SceneViewSwift

/// Collision-based hit testing demo.
///
/// Mirrors the Android `CollisionDemo` — five shapes (cubes and spheres)
/// are placed in a row. Tapping a shape highlights it; the "Reset Colors"
/// overlay button clears all highlights.
///
/// The demo uses `SceneView.onEntityTapped` which resolves to
/// `SpatialTapGesture().targetedToAnyEntity()` under the hood (see
/// `SceneView.swift`, `tapGesture`). Each `GeometryNode` calls
/// `generateCollisionShapes(recursive: false)` at construction time — but a
/// collision shape alone is NOT enough, which is why this demo's taps did
/// nothing at all until v4.27.0. `targetedToAnyEntity()` also requires an
/// `InputTargetComponent`, and nothing in the package set one; `SceneView`
/// now applies it to the whole content subtree during `buildContent`.
struct CollisionHitTestDemo: View {
    @State private var highlightedIndices: Set<Int> = []
    @State private var sceneKey = UUID()

    private struct ShapeSpec {
        let index: Int
        let isSphere: Bool
        let x: Float
    }

    private let shapes: [ShapeSpec] = [
        ShapeSpec(index: 0, isSphere: false, x: -0.60),
        ShapeSpec(index: 1, isSphere: true,  x: -0.30),
        ShapeSpec(index: 2, isSphere: false, x:  0.00),
        ShapeSpec(index: 3, isSphere: true,  x:  0.30),
        ShapeSpec(index: 4, isSphere: false, x:  0.60),
    ]

    /// Default color — SceneView primary blue.
    private static let defaultColor = UIColor(red: 0.24, green: 0.48, blue: 1.0, alpha: 1.0)
    /// Highlighted color — SceneView accent purple.
    private static let highlightColor = UIColor(red: 0.56, green: 0.25, blue: 0.94, alpha: 1.0)

    var body: some View {
        ZStack {
            SceneView { root in
                buildScene(root: root)
            }
            .onEntityTapped { entity in
                guard let idxStr = entity.name.components(separatedBy: "_").last,
                      let idx = Int(idxStr) else { return }
                if highlightedIndices.contains(idx) {
                    highlightedIndices.remove(idx)
                } else {
                    highlightedIndices.insert(idx)
                }
                // Swap material on the entity in-place.
                if let modelEntity = entity as? ModelEntity {
                    let color = highlightedIndices.contains(idx)
                        ? Self.highlightColor
                        : Self.defaultColor
                    let material = UnlitMaterial(color: color)
                    modelEntity.model?.materials = [material]
                }
                #if os(iOS)
                SceneViewHaptic.shared.light()
                #endif
            }
            .cameraControls(.orbit)
            .environment(.studio)
            .id(sceneKey)
            .ignoresSafeArea()
            .background(Color.black)

            // On-screen "Reset Colors" overlay button — mirrors Android's
            // SceneActionBar CTA so the user always has a way to clear highlights
            // without opening the settings sheet.
            VStack {
                Spacer()
                HStack {
                    Text("Tap a shape to highlight it")
                        .font(.caption)
                        .foregroundStyle(.white.opacity(0.6))
                    Spacer()
                    Button {
                        highlightedIndices.removeAll()
                        sceneKey = UUID()  // force scene rebuild to reset materials
                        #if os(iOS)
                        SceneViewHaptic.shared.medium()
                        #endif
                    } label: {
                        Label("Reset Colors", systemImage: "paintbrush.fill")
                            .font(.caption.weight(.semibold))
                            .padding(.horizontal, 12)
                            .padding(.vertical, 6)
                            .background(.ultraThinMaterial)
                            .clipShape(Capsule())
                    }
                    .foregroundStyle(.white)
                    .accessibilityLabel("Reset all shape highlights")
                }
                .padding()
            }
        }
    }

    // MARK: - Scene building

    /// Builds the five shapes from the spec list. Called on initial layout
    /// and whenever `sceneKey` changes (Reset).
    private func buildScene(root: Entity) {
        for spec in shapes {
            let color = highlightedIndices.contains(spec.index)
                ? Self.highlightColor
                : Self.defaultColor
            let node: GeometryNode
            if spec.isSphere {
                node = GeometryNode.sphere(radius: 0.15, color: color, unlit: true)
            } else {
                node = GeometryNode.cube(size: 0.25, color: color, unlit: true)
            }
            // Name encodes the spec index so onEntityTapped can look it up.
            node.entity.name = "shape_\(spec.index)"
            node.entity.position = SIMD3<Float>(spec.x, 0, -2)
            root.addChild(node.entity)
        }
    }
}

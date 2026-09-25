import SwiftUI
import RealityKit
import SceneViewSwift

/// Collision-based hit testing demo.
///
/// Mirrors the Android `PickingAndCollisionDemo` — five shapes (cubes and
/// spheres) in a two-row zig-zag. Tapping a shape highlights it; the dock's Clear (and
/// the sheet's Reset) clears all highlights.
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
        let position: SIMD3<Float>
    }

    /// Android's `PickingLayout` zig-zag (#3329), with a little more air:
    /// cubes on a low row, spheres on a high one, so neighbours never touch.
    /// The old single row at x = ±0.6 read as one blue block and lost its
    /// outer shapes past the edges of a portrait screen (#3787).
    private static let cubeEdge: Float = 0.22
    private static let sphereRadius: Float = 0.13
    private static let columnSpacing: Float = 0.27
    private let shapes: [ShapeSpec] = (0..<5).map { index in
        let isSphere = index % 2 == 1
        return ShapeSpec(
            index: index,
            isSphere: isSphere,
            position: [Float(index - 2) * CollisionHitTestDemo.columnSpacing,
                       isSphere ? 0 : -0.32,
                       -2]
        )
    }

    /// Lit PBR, as on Android: an unlit fill has no shaded side, so a cube and a
    /// sphere of the same colour were indistinguishable. Resting shapes cycle
    /// through the brand ramp; a picked shape turns `info` orange, grows 15 %
    /// and takes a metallic sheen.
    private static func material(index: Int, picked: Bool) -> PhysicallyBasedMaterial {
        let ramp = SceneViewTokens.Stage.shapeRamp
        var material = PhysicallyBasedMaterial()
        material.baseColor = .init(tint: picked ? SceneViewTokens.Stage.shapePicked : ramp[index % ramp.count])
        material.metallic = .init(floatLiteral: picked ? 0.6 : 0.15)
        material.roughness = .init(floatLiteral: picked ? 0.2 : 0.35)
        return material
    }

    private static let pickedScale: Float = 1.15

    var body: some View {
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
            // Swap material and scale on the entity in place.
            if let modelEntity = entity as? ModelEntity {
                let picked = highlightedIndices.contains(idx)
                modelEntity.model?.materials = [Self.material(index: idx, picked: picked)]
                modelEntity.scale = SIMD3(repeating: picked ? Self.pickedScale : 1)
            }
            #if os(iOS)
            SceneViewHaptic.shared.light()
            #endif
        }
        .cameraControls(.orbit)
        .environment(.studio)
        // Reset rebuilds the shapes under the same `RealityView`; a
        // `.id(_:)` re-key would discard the renderer and intermittently
        // leave the viewport black on iOS 26 Simulator (#3008).
        .contentID(sceneKey)
        // The scaffold carries the back button, the identity pill, the
        // legend and Reset — this screen used to draw none of them, so a
        // deep link landed on a scene with no way out (#3766 P2 §3, §5).
        .demoChrome(
            dock: [
                DockItem(icon: "paintbrush", label: "Clear",
                         enabled: !highlightedIndices.isEmpty) { resetHighlights() }
            ],
            onReset: resetHighlights,
            accessory: { DemoHint(hint) }
        )
    }

    /// The pill reports the pick count, like the Android card's "n / 5 shapes lit".
    private var hint: String {
        highlightedIndices.isEmpty
            ? "Tap a shape to pick it"
            : "\(highlightedIndices.count) of \(shapes.count) picked — tap again to release"
    }

    /// Clears every highlight — the dock's Clear and the sheet's Reset.
    private func resetHighlights() {
        highlightedIndices.removeAll()
        sceneKey = UUID()  // rebuild the content to reset materials
        #if os(iOS)
        SceneViewHaptic.shared.medium()
        #endif
    }

    // MARK: - Scene building

    /// Builds the five shapes from the spec list. Called on initial layout
    /// and whenever `sceneKey` changes (Reset).
    private func buildScene(root: Entity) {
        for spec in shapes {
            let picked = highlightedIndices.contains(spec.index)
            let material = GeometryMaterial.custom(Self.material(index: spec.index, picked: picked))
            let node: GeometryNode
            if spec.isSphere {
                node = GeometryNode.sphere(radius: Self.sphereRadius, material: material)
            } else {
                node = GeometryNode.cube(size: Self.cubeEdge, material: material)
            }
            // Name encodes the spec index so onEntityTapped can look it up.
            node.entity.name = "shape_\(spec.index)"
            node.entity.position = spec.position
            node.entity.scale = SIMD3(repeating: picked ? Self.pickedScale : 1)
            root.addChild(node.entity)
        }
    }
}

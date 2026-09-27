#if os(iOS)
import Combine
import SwiftUI
import RealityKit
import ARKit
import SceneViewSwift

/// AR face anchor accessories demo — mirrors Android's `AugmentedFaceDemo.kt`
/// (#910) as far as ARKit allows: the face ANCHOR is tracked, not a mesh.
///
/// Uses ARKit's `ARFaceTrackingConfiguration` (TrueDepth front camera) via
/// `ARSceneView(faceTracking: true)` to detect and track the user's face.
/// An `AnchorEntity(.face)` locks a ring of coloured spheres to the face pose.
///
/// ### iOS vs Android parity note
///
/// Android's `AugmentedFaceDemo` uses ARCore + a morphable face-mesh shader.
/// On iOS, `AnchorEntity(.face)` tracks the full face pose via ARKit's
/// TrueDepth sensor but does NOT expose a morphable mesh through RealityKit's
/// standard API — the ring-of-spheres overlay demonstrates face-pose tracking
/// without requiring a custom mesh shader.
///
/// Requires a device with TrueDepth front camera (iPhone X+).
/// Shows a simulator placeholder on the simulator.
struct ARAugmentedFacesDemo: View {
    @State private var faceAnchor: AnchorEntity?
    /// Whether ARKit is tracking a face right now, driven by the anchor's
    /// `AnchoredStateChanged` event so the caption can say so.
    @State private var faceTracked = false
    @State private var anchoredSubscription: AnyCancellable?

    /// Positions of the ring's spheres in the face anchor's space.
    ///
    /// ARKit's face anchor sits at the centre of the head, behind the nose,
    /// with +Z pointing out of the face toward the camera. The ring used to be
    /// placed at `z = -0.05` — five centimetres *behind* that origin, inside
    /// the skull — so nothing of it read as an accessory in front of the face
    /// (#4014). It now frames the face in the plane of the nose tip, a 12 by
    /// 14 cm oval that clears the cheeks and the chin.
    static func ringPositions(count: Int = 8) -> [SIMD3<Float>] {
        let halfWidth: Float = 0.12
        let halfHeight: Float = 0.14
        let depth: Float = 0.07
        return (0..<count).map { i in
            let angle = Float(i) / Float(count) * 2 * .pi
            return SIMD3<Float>(cos(angle) * halfWidth, sin(angle) * halfHeight, depth)
        }
    }

    /// Sphere radius: 1.8 cm reads at arm's length on an iPad front camera,
    /// where the old 1.1 cm dot was a few pixels.
    static let sphereRadius: Float = 0.018

    var body: some View {
        ZStack {
            #if !targetEnvironment(simulator)
            arSceneView
                .ignoresSafeArea()
            #else
            simulatorPlaceholder
            #endif

            VStack {
                Spacer()
                caption
                    .padding(.bottom, 24)
            }
        }
        .background(Color.black)
    }

    // MARK: - AR view

    #if !targetEnvironment(simulator)
    private var arSceneView: some View {
        ARSceneView(faceTracking: true)
            .onSessionStarted { arView in
                addFaceContent(to: arView)
            }
    }

    private func addFaceContent(to arView: ARView) {
        let anchor = AnchorEntity(.face)

        // A ring of coloured spheres framing the face. Not metallic: a mirror
        // finish reflects an environment the front camera never sees, which
        // left the spheres near-black against a dim room.
        let positions = Self.ringPositions()
        for (i, position) in positions.enumerated() {
            var material = SimpleMaterial()
            material.color = .init(tint: UIColor(
                hue: CGFloat(i) / CGFloat(positions.count),
                saturation: 0.85,
                brightness: 1.0,
                alpha: 1.0
            ))
            material.metallic = .float(0)
            material.roughness = .float(0.35)
            let sphere = ModelEntity(
                mesh: .generateSphere(radius: Self.sphereRadius),
                materials: [material]
            )
            sphere.position = position
            anchor.addChild(sphere)
        }

        arView.scene.addAnchor(anchor)
        anchoredSubscription = AnyCancellable(arView.scene.subscribe(
            to: SceneEvents.AnchoredStateChanged.self,
            on: anchor
        ) { event in
            faceTracked = event.isAnchored
        })
        faceAnchor = anchor
    }
    #endif

    // MARK: - UI

    private var caption: some View {
        Text(faceTracked ? "Face tracked. The ring follows your head"
                         : "Look at the front camera to place the ring")
            .font(.caption2)
            .foregroundStyle(.white.opacity(0.75))
            .padding(.horizontal, 16)
            .padding(.vertical, 6)
            .background(.black.opacity(0.5))
            .clipShape(Capsule())
    }

    // MARK: - Simulator placeholder

    private var simulatorPlaceholder: some View {
        ARUnavailableStage(icon: "face.smiling", message: "Face tracking requires the TrueDepth front camera (iPhone X+).\nRun on a real device to see face anchors in AR.")
    }
}

#endif // os(iOS)

#if os(iOS)
import SwiftUI
import RealityKit
import ARKit
import SceneViewSwift

/// AR Augmented Faces demo — mirrors Android's `AugmentedFaceDemo.kt` (#910).
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

        // Ring of 8 small metallic spheres orbiting the nose bridge
        let count = 8
        let radius: Float = 0.10
        let sphereSize: Float = 0.011
        for i in 0..<count {
            let angle = Float(i) / Float(count) * 2 * .pi
            let x = cos(angle) * radius
            let y = sin(angle) * radius * 0.5  // flatten to an oval
            var material = SimpleMaterial()
            material.color = .init(tint: UIColor(
                hue: CGFloat(i) / CGFloat(count),
                saturation: 0.9,
                brightness: 0.95,
                alpha: 1.0
            ))
            material.metallic = .float(0.8)
            material.roughness = .float(0.2)
            let sphere = ModelEntity(
                mesh: .generateSphere(radius: sphereSize),
                materials: [material]
            )
            sphere.position = SIMD3<Float>(x, y, -0.05)
            anchor.addChild(sphere)
        }

        arView.scene.addAnchor(anchor)
        faceAnchor = anchor
    }
    #endif

    // MARK: - UI

    private var caption: some View {
        Text("Ring tracks your face — TrueDepth front camera")
            .font(.caption2)
            .foregroundStyle(.white.opacity(0.75))
            .padding(.horizontal, 16)
            .padding(.vertical, 6)
            .background(.black.opacity(0.5))
            .clipShape(Capsule())
    }

    // MARK: - Simulator placeholder

    private var simulatorPlaceholder: some View {
        VStack(spacing: 16) {
            Image(systemName: "face.smiling")
                .font(.system(size: 60))
                .foregroundStyle(.secondary)
            Text("AR requires a physical device")
                .font(.headline)
            Text("Face tracking requires the TrueDepth front camera (iPhone X+).\nRun on a real device to see face anchors in AR.")
                .font(.caption)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 40)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color(.systemGroupedBackground))
    }
}

#endif // os(iOS)

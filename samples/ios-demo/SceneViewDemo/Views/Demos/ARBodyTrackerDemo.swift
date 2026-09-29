#if os(iOS)
import SwiftUI
import RealityKit
import ARKit
import os

/// AR body anchor tracking demo — follows a detected body anchor (#910).
///
/// Uses `ARBodyTrackingConfiguration` to detect a person and place a small
/// coloured sphere at the body anchor's root so you can see tracking is live.
///
/// ### Honest-subset note
///
/// ARKit's skeleton does carry a 91-joint hierarchy, but this screen reads the
/// anchor transform only — it does not render or expose per-joint data, so the
/// catalogue no longer claims joint tracking. A real joint demo needs the SDK
/// to surface `ARSkeleton3D` through a node type; until then this is body
/// anchor tracking, named as such.
///
/// Requires a physical iOS device with an A12+ chip and iOS 13+.
struct ARBodyTrackerDemo: View {
    @State private var isTracking = false
    // Seeded from the hardware, not `false`: the banner must never flash on a
    // device that supports body tracking while the view is being made.
    @State private var isSupported = ARBodyTrackingConfiguration.isSupported

    var body: some View {
        ZStack {
            #if !targetEnvironment(simulator)
            BodyTrackingARViewRepresentable(
                isTracking: $isTracking,
                isSupported: $isSupported
            )
            .ignoresSafeArea()
            if !isSupported {
                unsupportedBanner
            }
            #else
            simulatorPlaceholder
            #endif

            VStack {
                if isTracking {
                    HStack(spacing: 6) {
                        Circle()
                            .fill(.green)
                            .frame(width: 8, height: 8)
                        Text("Body detected")
                            .font(.caption)
                            .foregroundStyle(.white)
                    }
                    .padding(.horizontal, 14)
                    .padding(.vertical, 7)
                    .background(.black.opacity(0.6))
                    .clipShape(Capsule())
                    .padding(.top, 60)
                }
                Spacer()
                Text("Point at a person standing 1–4 m away to begin tracking")
                    .font(.caption2)
                    .foregroundStyle(.white.opacity(0.7))
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 24)
                    .padding(.bottom, 28)
            }
        }
        .background(Color.black)
    }

    private var unsupportedBanner: some View {
        VStack {
            HStack {
                Image(systemName: "exclamationmark.triangle.fill")
                    .foregroundStyle(.yellow)
                Text("Body tracking requires iPhone XS / XR or later (A12+)")
                    .font(.caption)
                    .foregroundStyle(.white)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 8)
            .background(.black.opacity(0.7))
            .clipShape(Capsule())
            .padding(.top, 60)
            Spacer()
        }
    }

    private var simulatorPlaceholder: some View {
        ARUnavailableStage(icon: "figure.walk.motion", message: "Body tracking requires a real camera feed and A12+ chip.\nPoint at a person — skeleton joints are tracked at up to 60 fps.")
    }
}

// MARK: - UIViewRepresentable wrapper

#if !targetEnvironment(simulator)
private struct BodyTrackingARViewRepresentable: UIViewRepresentable {
    @Binding var isTracking: Bool
    @Binding var isSupported: Bool

    func makeCoordinator() -> Coordinator {
        Coordinator(isTracking: $isTracking)
    }

    func makeUIView(context: Context) -> ARView {
        let arView = ARView(frame: .zero, cameraMode: .ar, automaticallyConfigureSession: false)
        // The camera composition is stated on the view, as `ARSceneView`
        // does, never inherited from what the process rendered before (#3912).
        arView.environment.background = .cameraFeed()

        // Decided from a local, never from the binding: a `@Binding` written
        // inside `makeUIView` (a view update) is not readable back in the same
        // pass, so `guard isSupported` read the stale `false`, returned before
        // `session.run`, and an iPhone that supports body tracking showed the
        // "requires iPhone XS / XR" banner over a camera that never started.
        let supported = ARBodyTrackingConfiguration.isSupported
        let isSupportedBinding = $isSupported
        DispatchQueue.main.async { isSupportedBinding.wrappedValue = supported }
        guard supported else { return arView }

        let config = ARBodyTrackingConfiguration()
        arView.session.delegate = context.coordinator
        context.coordinator.arView = arView
        arView.session.run(config)
        BodyTrackingLog.log.info("body tracking session running")
        return arView
    }

    func updateUIView(_ uiView: ARView, context: Context) {}

    /// Closing the screen stops the camera: without this the body-tracking
    /// session kept the rear camera until the view happened to be released,
    /// and the next AR screen's session competed with it.
    static func dismantleUIView(_ uiView: ARView, coordinator: Coordinator) {
        uiView.session.pause()
        uiView.session.delegate = nil
    }

    @MainActor
    class Coordinator: NSObject, ARSessionDelegate {
        @Binding var isTracking: Bool
        // Weak to avoid a retain cycle between coordinator ↔ view.
        weak var arView: ARView?
        // Map from ARBodyAnchor identifier → the AnchorEntity placed in the scene.
        private var trackedBodies: [UUID: AnchorEntity] = [:]

        init(isTracking: Binding<Bool>) {
            _isTracking = isTracking
        }

        nonisolated func session(_ session: ARSession, cameraDidChangeTrackingState camera: ARCamera) {
            BodyTrackingLog.log.info("camera tracking state: \(String(describing: camera.trackingState), privacy: .public)")
        }

        nonisolated func session(_ session: ARSession, didFailWithError error: Error) {
            BodyTrackingLog.log.error("body tracking session failed: \(error.localizedDescription, privacy: .public)")
        }

        nonisolated func session(_ session: ARSession, didAdd anchors: [ARAnchor]) {
            for anchor in anchors.compactMap({ $0 as? ARBodyAnchor }) {
                DispatchQueue.main.async { self.addBodyMarker(for: anchor) }
            }
        }

        nonisolated func session(_ session: ARSession, didUpdate anchors: [ARAnchor]) {
            for anchor in anchors.compactMap({ $0 as? ARBodyAnchor }) {
                DispatchQueue.main.async { self.updateBodyMarker(for: anchor) }
            }
        }

        nonisolated func session(_ session: ARSession, didRemove anchors: [ARAnchor]) {
            for anchor in anchors.compactMap({ $0 as? ARBodyAnchor }) {
                DispatchQueue.main.async { self.removeBodyMarker(id: anchor.identifier) }
            }
        }

        private func addBodyMarker(for anchor: ARBodyAnchor) {
            guard let arView else { return }
            // Place a green sphere at the body's hip (root joint).
            let markerEntity = ModelEntity(
                mesh: .generateSphere(radius: 0.06),
                materials: [SimpleMaterial(color: .systemGreen, isMetallic: false)]
            )
            let anchorEntity = AnchorEntity(anchor: anchor)
            anchorEntity.addChild(markerEntity)
            arView.scene.addAnchor(anchorEntity)
            trackedBodies[anchor.identifier] = anchorEntity
            isTracking = anchor.isTracked
        }

        private func updateBodyMarker(for anchor: ARBodyAnchor) {
            isTracking = anchor.isTracked
        }

        private func removeBodyMarker(id: UUID) {
            trackedBodies[id]?.removeFromParent()
            trackedBodies.removeValue(forKey: id)
            if trackedBodies.isEmpty { isTracking = false }
        }
    }
}

private enum BodyTrackingLog {
    static let log = Logger(subsystem: "io.github.sceneview.demo", category: "BodyTracking")
}
#endif

#endif // os(iOS)

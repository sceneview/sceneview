#if os(iOS)
import SwiftUI
import RealityKit
import ARKit

/// AR Body Tracker demo — tracks body skeleton joints in real time (#910).
///
/// Uses `ARBodyTrackingConfiguration` + RealityKit's `BodyTrackedEntity` to detect
/// and follow a full-body skeleton (91 joints). A small coloured sphere is placed at
/// the root hip joint so you can see the tracker is active. Mirrors the Android
/// `ar-body-tracker` demo.
///
/// Requires a physical iOS device with an A12+ chip and iOS 13+.
struct ARBodyTrackerDemo: View {
    @State private var isTracking = false
    @State private var isSupported = false

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
        VStack(spacing: 16) {
            Image(systemName: "figure.walk.motion")
                .font(.system(size: 60))
                .foregroundStyle(.secondary)
            Text("AR requires a physical device")
                .font(.headline)
            Text("Body tracking requires a real camera feed and A12+ chip.\nPoint at a person — skeleton joints are tracked at up to 60 fps.")
                .font(.caption)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 40)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color(.systemGroupedBackground))
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
        isSupported = ARBodyTrackingConfiguration.isSupported
        guard isSupported else { return arView }

        let config = ARBodyTrackingConfiguration()
        arView.session.delegate = context.coordinator
        context.coordinator.arView = arView
        arView.session.run(config)
        return arView
    }

    func updateUIView(_ uiView: ARView, context: Context) {}

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
#endif

#endif // os(iOS)

#if os(iOS)
import SwiftUI
import RealityKit
import ARKit
import SceneViewSwift

/// AR Plane Node demo — visualises every detected ARKit plane with a floating
/// marker cube at its centre (#910).
///
/// Mirrors the Android `ARPlaneNodeDemo` (`arsceneview/…/ARPlaneNodeDemo.kt`):
/// ARKit reports plane anchors via the session delegate; this demo places one
/// translucent box on each plane centre and keeps a "planes detected" counter.
/// Toggling the built-in plane overlay with `showPlaneOverlay` lets the user
/// compare SceneView's visualisation against the cube-per-plane approach.
///
/// ### ARKit parity note
/// ARCore's `rememberDetectedPlanes` helper exposes an `onAdded` / `onUpdated`
/// / `onRemoved` lifecycle. On iOS the equivalent is `ARSessionDelegate`
/// `didAdd` / `didUpdate` / `didRemove anchors`, which `ARSceneView` wires
/// through its `Coordinator`. We intercept them from `onSessionStarted` by
/// subclassing the ARView session delegate — same data, different plumbing.
struct ARPlaneNodeDemo: View {
    @State private var planeCount = 0
    @State private var totalEverDetected = 0
    @State private var capturedARView: ARView?
    @State private var planeMarkers: [UUID: AnchorEntity] = [:]

    var body: some View {
        ZStack {
            #if !targetEnvironment(simulator)
            arSceneView
                .ignoresSafeArea()
            #else
            simulatorPlaceholder
            #endif

            statusPill
                .padding(.top, 8)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        }
        .background(Color.black)
    }

    // MARK: - AR view

    #if !targetEnvironment(simulator)
    private var arSceneView: some View {
        ARSceneView(
            planeDetection: .both,
            showPlaneOverlay: true,
            showCoachingOverlay: true
        )
        .onSessionStarted { arView in
            capturedARView = arView
            // Install a per-plane-anchor delegate via the session delegate
            // so we get didAdd / didUpdate / didRemove callbacks.
            // ARSceneView's Coordinator is already the session delegate; we
            // re-assign a forwarding wrapper that calls the Coordinator and
            // our own handlers.
            let forwarder = PlaneDelegate(
                wrapped: arView.session.delegate,
                onAdded: { anchor in
                    guard let plane = anchor as? ARPlaneAnchor else { return }
                    DispatchQueue.main.async {
                        let marker = makeMarker(for: plane, in: arView)
                        planeMarkers[plane.identifier] = marker
                        arView.scene.addAnchor(marker)
                        planeCount = planeMarkers.count
                        totalEverDetected += 1
                    }
                },
                onUpdated: { anchor in
                    guard let plane = anchor as? ARPlaneAnchor,
                          let marker = planeMarkers[plane.identifier] else { return }
                    DispatchQueue.main.async {
                        marker.transform = Transform(matrix: plane.transform)
                        planeCount = planeMarkers.count
                    }
                },
                onRemoved: { anchor in
                    guard let plane = anchor as? ARPlaneAnchor,
                          let marker = planeMarkers[plane.identifier] else { return }
                    DispatchQueue.main.async {
                        arView.scene.removeAnchor(marker)
                        planeMarkers[plane.identifier] = nil
                        planeCount = planeMarkers.count
                    }
                }
            )
            arView.session.delegate = forwarder
            // Hold the forwarder alive via associated object so ARC doesn't
            // collect it (ARSession holds a weak delegate reference).
            objc_setAssociatedObject(arView, &AssocKey.delegate, forwarder, .OBJC_ASSOCIATION_RETAIN)
        }
    }

    // `objc_setAssociatedObject` needs the address of a global var as its
    // key. Swift 6's strict-concurrency mode flags any `static var` as
    // "non-isolated global shared mutable state" — even though we never
    // mutate this byte, only take its address. `nonisolated(unsafe)` is the
    // canonical opt-out for this exact "used as objc key" idiom.
    private enum AssocKey { nonisolated(unsafe) static var delegate: UInt8 = 0 }

    private func makeMarker(for plane: ARPlaneAnchor, in arView: ARView) -> AnchorEntity {
        let anchor = AnchorEntity(world: plane.transform)
        let extent = plane.planeExtent
        let mesh = MeshResource.generateBox(
            width: max(0.05, min(extent.width, 0.15)),
            height: 0.01,
            depth: max(0.05, min(extent.height, 0.15))
        )
        let material = SimpleMaterial(
            color: .init(red: 0.3, green: 0.6, blue: 1.0, alpha: 0.65),
            isMetallic: false
        )
        let entity = ModelEntity(mesh: mesh, materials: [material])
        anchor.addChild(entity)
        return anchor
    }

    /// Session-delegate forwarder: wraps the existing `ARSceneView` Coordinator
    /// and calls our closures for plane anchors.
    private final class PlaneDelegate: NSObject, ARSessionDelegate {
        private weak var wrapped: ARSessionDelegate?
        private let onAdded: (ARAnchor) -> Void
        private let onUpdated: (ARAnchor) -> Void
        private let onRemoved: (ARAnchor) -> Void

        init(wrapped: ARSessionDelegate?,
             onAdded: @escaping (ARAnchor) -> Void,
             onUpdated: @escaping (ARAnchor) -> Void,
             onRemoved: @escaping (ARAnchor) -> Void) {
            self.wrapped = wrapped
            self.onAdded = onAdded
            self.onUpdated = onUpdated
            self.onRemoved = onRemoved
        }

        func session(_ session: ARSession, didAdd anchors: [ARAnchor]) {
            anchors.forEach { onAdded($0) }
            wrapped?.session?(session, didAdd: anchors)
        }

        func session(_ session: ARSession, didUpdate anchors: [ARAnchor]) {
            anchors.forEach { onUpdated($0) }
            wrapped?.session?(session, didUpdate: anchors)
        }

        func session(_ session: ARSession, didRemove anchors: [ARAnchor]) {
            anchors.forEach { onRemoved($0) }
            wrapped?.session?(session, didRemove: anchors)
        }

        func session(_ session: ARSession, didUpdate frame: ARFrame) {
            wrapped?.session?(session, didUpdate: frame)
        }

        func session(_ session: ARSession, didFailWithError error: Error) {
            wrapped?.session?(session, didFailWithError: error)
        }

        func sessionWasInterrupted(_ session: ARSession) {
            wrapped?.sessionWasInterrupted?(session)
        }

        func sessionInterruptionEnded(_ session: ARSession) {
            wrapped?.sessionInterruptionEnded?(session)
        }
    }
    #endif

    // MARK: - Status pill

    private var statusPill: some View {
        Text(planeCount == 0
             ? "Move to detect planes"
             : "\(planeCount) plane\(planeCount == 1 ? "" : "s") (\(totalEverDetected) ever)")
            .font(.system(.caption, design: .monospaced, weight: .semibold))
            .foregroundColor(.white)
            .padding(.horizontal, 14)
            .padding(.vertical, 7)
            .background(.black.opacity(0.65))
            .clipShape(Capsule())
            .animation(.easeInOut(duration: 0.2), value: planeCount)
    }

    // MARK: - Simulator placeholder

    private var simulatorPlaceholder: some View {
        VStack(spacing: 16) {
            Image(systemName: "rectangle.3.group")
                .font(.system(size: 56))
                .foregroundStyle(.secondary)
            Text("AR Plane Node")
                .font(.headline)
            Text("Plane detection requires a physical device with a rear camera.")
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 40)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}
#endif

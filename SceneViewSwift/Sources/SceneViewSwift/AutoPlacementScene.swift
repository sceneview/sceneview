#if os(iOS)
import SwiftUI
import ARKit
import RealityKit

/// Automatically places the selected content on the first usable detected surface.
/// No tap, plane overlay, or reticle. Existing manual ``ARSceneView`` APIs remain available.
/// Supply assets through the controller; dismissal cancels all tickets and releases anchors.
///
/// `coaching` (default `true`) shows Apple's `ARCoachingOverlayView` while the session starts,
/// searches for the controller's surface (floor or wall) or relocalizes. Hide your own chrome
/// while ``ARPlacementController/isCoachingActive`` is true. Its "Start Over" button resets
/// the placement and the session. Kotlin twin: `AutoPlacementScene(coaching = true)`.
@MainActor
public struct AutoPlacementScene: View {
    @ObservedObject private var controller: ARPlacementController
    private var coaching: Bool
    private var onSessionEvent: ((ARSessionEvent, ARView) -> Void)?

    public init(controller: ARPlacementController,
                coaching: Bool = true,
                onSessionEvent: ((ARSessionEvent, ARView) -> Void)? = nil) {
        self.controller = controller
        self.coaching = coaching
        self.onSessionEvent = onSessionEvent
    }

    public var body: some View {
        ARSceneView(
            planeDetection: controller.alignment == .horizontal ? .horizontal : .vertical,
            showPlaneOverlay: false,
            showCoachingOverlay: coaching,
            showPlacementReticle: false
        )
        .automaticPlacement(controller)
        .onSessionEvent { event, view in onSessionEvent?(event, view) }
    }
}
#endif

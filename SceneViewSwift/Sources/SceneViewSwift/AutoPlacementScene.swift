#if os(iOS)
import SwiftUI
import ARKit
import RealityKit

/// Automatically places the selected content on the first usable detected surface.
/// No tap, plane overlay, or reticle. Existing manual ``ARSceneView`` APIs remain available.
/// Supply assets through the controller; dismissal cancels all tickets and releases anchors.
@MainActor
public struct AutoPlacementScene: View {
    @ObservedObject private var controller: ARPlacementController
    private var onSessionEvent: ((ARSessionEvent, ARView) -> Void)?

    public init(controller: ARPlacementController,
                onSessionEvent: ((ARSessionEvent, ARView) -> Void)? = nil) {
        self.controller = controller
        self.onSessionEvent = onSessionEvent
    }

    public var body: some View {
        ARSceneView(
            planeDetection: controller.alignment == .horizontal ? .horizontal : .vertical,
            showPlaneOverlay: false,
            showCoachingOverlay: false,
            showPlacementReticle: false
        )
        .automaticPlacement(controller)
        .onSessionEvent { event, view in onSessionEvent?(event, view) }
    }
}
#endif

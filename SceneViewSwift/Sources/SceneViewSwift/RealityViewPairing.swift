#if os(iOS)
import RealityKit
import UIKit

/// Finds the `ARView` that backs a SwiftUI `RealityView`, from an invisible marker view laid
/// out as that `RealityView`'s background (``RenderSurfaceResizer``, the anchor of
/// ``SceneView/sceneRecorder(_:)``).
enum RealityViewPairing {
    /// The `ARView` of the `RealityView` `marker` sits behind, and no other.
    ///
    /// The marker is the `RealityView`'s background, so it is laid out in exactly the
    /// same rectangle. The walk climbs from the marker until a container holds a
    /// non-AR `ARView` whose on-screen frame is that rectangle. Two `SceneView`s side by
    /// side or stacked never share a rectangle, so a sibling's surface is never touched.
    /// If two views do share it (one `SceneView` drawn over another), the tie goes to
    /// the first one after the marker in subview order, which is where SwiftUI puts the
    /// view a background belongs to.
    @MainActor
    static func arView(behind marker: UIView, markerFrame: CGRect, in window: UIWindow) -> ARView? {
        var ancestor = marker.superview
        while let container = ancestor {
            var order: [UIView] = []
            collect(container, into: &order, marker: marker)
            let matches = order.enumerated().compactMap { index, view -> (Int, ARView)? in
                guard let arView = view as? ARView,
                      sameRect(arView.convert(arView.bounds, to: window), markerFrame)
                else { return nil }
                return (index, arView)
            }
            if !matches.isEmpty {
                let markerIndex = order.firstIndex(of: marker) ?? -1
                return (matches.first { $0.0 > markerIndex } ?? matches.last!).1
            }
            ancestor = container.superview
        }
        return nil
    }

    /// Depth-first list of the marker and of every non-AR `ARView` under `view`.
    @MainActor
    private static func collect(_ view: UIView, into order: inout [UIView], marker: UIView) {
        if view === marker { order.append(view); return }
        if let arView = view as? ARView {
            // An AR camera view (ARSceneView) manages its own surface.
            if arView.cameraMode == .nonAR { order.append(arView) }
            return
        }
        for sub in view.subviews { collect(sub, into: &order, marker: marker) }
    }

    private static func sameRect(_ a: CGRect, _ b: CGRect) -> Bool {
        abs(a.minX - b.minX) < 1 && abs(a.minY - b.minY) < 1
            && abs(a.width - b.width) < 1 && abs(a.height - b.height) < 1
    }
}
#endif

#if os(iOS) && targetEnvironment(simulator)
import RealityKit
import SwiftUI
import UIKit

/// Keeps a `RealityView`'s Metal render surface the size of the view after a resize (#4182).
///
/// **Simulator only.** The bug has only ever been seen in the iOS 26 Simulator. On a real
/// iPhone (iPhone SE 3, iOS 26.6.1) RealityKit resizes the surface itself, and writing the
/// surface's frame and drawable size from here during the rotation leaves RealityKit with
/// the portrait projection: the landscape scene is stretched about 3x horizontally
/// ((portrait height / landscape height)², the old aspect over the new one). So this file,
/// and the code in ``SceneView`` that uses it, is compiled for the simulator only; device
/// builds keep RealityKit's own resize untouched.
///
/// In the iOS 26 Simulator, the `ARView` that backs a SwiftUI `RealityView` follows its new
/// frame when the device rotates, but the Metal view inside it does not: it keeps the frame and the
/// drawable size it was created with. RealityKit then renders a landscape viewport into the
/// portrait-sized drawable, so after portrait → landscape only the left, portrait-wide strip
/// of the screen shows the scene and the rest stays black. Launching straight into landscape
/// is fine, because the surface is created at the right size. A bare `RealityView` in a
/// minimal app does the same, so the fault is in RealityKit, not in ``SceneView``.
///
/// This invisible marker is the `RealityView`'s background, so it shares its rectangle.
/// Whenever its layout changes it finds the `ARView` laid out in that same rectangle (never a
/// neighbouring `SceneView`'s) and, **only if** the render surface no longer matches the view,
/// resizes the surface and its drawable (keeping the drawable's pixel density), then calls
/// `onResize` so the scene re-writes its camera and RealityKit re-derives the projection.
/// A surface that already matches is left alone, so a RealityKit build that resizes on its
/// own never sees this code do anything.
struct RenderSurfaceResizer: UIViewRepresentable {
    /// The laid-out size of the `RealityView`; a change is what re-runs the check.
    let size: CGSize
    /// Called after a surface was actually resized.
    let onResize: @MainActor () -> Void

    func makeUIView(context: Context) -> Marker {
        let marker = Marker()
        marker.isUserInteractionEnabled = false
        marker.isAccessibilityElement = false
        marker.onResize = onResize
        return marker
    }

    func updateUIView(_ marker: Marker, context: Context) {
        marker.onResize = onResize
        marker.scheduleCheck()
    }

    final class Marker: UIView {
        var onResize: (@MainActor () -> Void)?
        private var checkPending = false

        override func didMoveToWindow() {
            super.didMoveToWindow()
            scheduleCheck()
        }

        override func layoutSubviews() {
            super.layoutSubviews()
            scheduleCheck()
        }

        /// Deferred to the next main-queue turn so the `ARView` already has its new bounds.
        func scheduleCheck() {
            guard !checkPending else { return }
            checkPending = true
            DispatchQueue.main.async { [weak self] in
                guard let self else { return }
                self.checkPending = false
                self.check()
            }
        }

        /// Layout passes a check may wait for the paired `ARView` to take its new frame.
        private var retriesLeft = 0

        private func check() {
            guard let window else { return }
            let markerFrame = convert(bounds, to: window)
            guard markerFrame.width > 0, markerFrame.height > 0 else { return }
            if let arView = pairedRealityView(markerFrame: markerFrame, in: window) {
                retriesLeft = 0
                if Self.fitSurface(of: arView) { onResize?() }
            } else if retriesLeft < 5 {
                // Mid-rotation, the ARView may not have its new frame yet.
                retriesLeft += 1
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.05) { [weak self] in self?.check() }
            } else {
                retriesLeft = 0
            }
        }

        /// The `ARView` of the `RealityView` this marker sits behind, and no other.
        ///
        /// The marker is the `RealityView`'s background, so it is laid out in exactly the
        /// same rectangle. The walk climbs from the marker until a container holds a
        /// non-AR `ARView` whose on-screen frame is that rectangle. Two `SceneView`s side by
        /// side or stacked never share a rectangle, so a sibling's surface is never touched.
        /// If two views do share it (one `SceneView` drawn over another), the tie goes to
        /// the first one after the marker in subview order, which is where SwiftUI puts the
        /// view a background belongs to.
        private func pairedRealityView(markerFrame: CGRect, in window: UIWindow) -> ARView? {
            var ancestor = superview
            while let container = ancestor {
                var order: [UIView] = []
                Self.collect(container, into: &order, marker: self)
                let matches = order.enumerated().compactMap { index, view -> (Int, ARView)? in
                    guard let arView = view as? ARView,
                          Self.sameRect(arView.convert(arView.bounds, to: window), markerFrame)
                    else { return nil }
                    return (index, arView)
                }
                if !matches.isEmpty {
                    let markerIndex = order.firstIndex(of: self) ?? -1
                    return (matches.first { $0.0 > markerIndex } ?? matches.last!).1
                }
                ancestor = container.superview
            }
            return nil
        }

        /// Depth-first list of the marker and of every non-AR `ARView` under `view`.
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

        /// Returns `true` when the surface had to be resized.
        private static func fitSurface(of arView: UIView) -> Bool {
            let bounds = arView.bounds
            guard bounds.width > 0, bounds.height > 0 else { return false }
            var resized = false
            for surface in arView.subviews {
                guard let layer = surface.layer as? CAMetalLayer,
                      surface.frame.size != bounds.size else { continue }
                let old = surface.bounds.size
                let density = old.width > 0 ? layer.drawableSize.width / old.width : layer.contentsScale
                surface.frame = bounds
                layer.drawableSize = CGSize(width: (bounds.width * density).rounded(),
                                            height: (bounds.height * density).rounded())
                resized = true
            }
            return resized
        }
    }
}
#endif

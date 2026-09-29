#if os(iOS)
import RealityKit
import SwiftUI
import UIKit

/// Keeps a `RealityView`'s Metal render surface the size of the view after a resize (#4182).
///
/// On iOS 26 the `ARView` that backs a SwiftUI `RealityView` follows its new frame when the
/// device rotates, but the Metal view inside it does not: it keeps the frame and the
/// drawable size it was created with. RealityKit then renders a landscape viewport into the
/// portrait-sized drawable, so after portrait → landscape only the left, portrait-wide strip
/// of the screen shows the scene and the rest stays black. Launching straight into landscape
/// is fine, because the surface is created at the right size. A bare `RealityView` in a
/// minimal app does the same, so the fault is in RealityKit, not in ``SceneView``.
///
/// This zero-size marker sits behind the `RealityView`. Whenever its layout changes it finds
/// the `ARView` next to it and, **only if** the render surface no longer matches the view,
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

        private func check() {
            guard window != nil else { return }
            // The nearest ancestor holding an ARView holds this RealityView's; a sibling
            // SceneView's surface caught in the same walk only gets the same correction.
            var ancestor = superview
            while let container = ancestor {
                let views = Self.realityViews(in: container)
                if !views.isEmpty {
                    let resized = views.reduce(false) { Self.fitSurface(of: $1) || $0 }
                    if resized { onResize?() }
                    return
                }
                ancestor = container.superview
            }
        }

        private static func realityViews(in view: UIView) -> [ARView] {
            if let arView = view as? ARView {
                // An AR camera view (ARSceneView) manages its own surface.
                return arView.cameraMode == .nonAR ? [arView] : []
            }
            return view.subviews.flatMap(realityViews(in:))
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

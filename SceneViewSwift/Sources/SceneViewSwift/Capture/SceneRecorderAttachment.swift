#if os(iOS)
import RealityKit
import SwiftUI
import UIKit

extension SceneView {
    /// Lets `recorder` take photos and videos of this scene.
    ///
    /// The recorder captures what the scene renders, never the SwiftUI views over it. One
    /// recorder serves one scene at a time: attaching it to another one finishes a recording
    /// running on the first. A recording also finishes when this view leaves the screen.
    ///
    /// ```swift
    /// @StateObject private var recorder = SceneRecorder()
    ///
    /// SceneView { root in … }
    ///     .sceneRecorder(recorder)
    /// ```
    ///
    /// - Parameter recorder: The recorder, or `nil` to detach.
    public func sceneRecorder(_ recorder: SceneRecorder?) -> SceneView {
        var copy = self
        copy.sceneRecorder = recorder
        return copy
    }
}

extension ARSceneView {
    /// Lets `recorder` take photos and videos of this AR scene: the camera feed and the
    /// virtual content together, never the SwiftUI views over it.
    ///
    /// Works with ``cameraExposure(_:)``: the recorded frames carry the same exposure as the
    /// screen. One recorder serves one scene at a time, and a recording finishes when this
    /// view leaves the screen.
    ///
    /// - Parameter recorder: The recorder, or `nil` to detach.
    public func sceneRecorder(_ recorder: SceneRecorder?) -> ARSceneView {
        var copy = self
        copy.sceneRecorder = recorder
        return copy
    }
}

/// The background marker that hands the `ARView` behind a ``SceneView``'s `RealityView` to its
/// ``SceneRecorder``, and takes it back when the view goes away.
struct SceneRecorderAnchor: UIViewRepresentable {
    let recorder: SceneRecorder

    func makeUIView(context: Context) -> Marker {
        let marker = Marker()
        marker.isUserInteractionEnabled = false
        marker.isAccessibilityElement = false
        marker.recorder = recorder
        return marker
    }

    func updateUIView(_ marker: Marker, context: Context) {
        marker.recorder = recorder
        marker.scheduleCheck()
    }

    static func dismantleUIView(_ marker: Marker, coordinator: ()) {
        marker.recorder = nil
    }

    final class Marker: UIView {
        /// Changing it detaches from the previous recorder.
        var recorder: SceneRecorder? {
            didSet {
                guard oldValue !== recorder else { return }
                if let oldValue, let source { oldValue.detach(source) }
                source = nil
                scheduleCheck()
            }
        }
        private var source: ARViewCaptureSource?
        private var checkPending = false
        private var retriesLeft = 5

        override func didMoveToWindow() {
            super.didMoveToWindow()
            if window == nil, let recorder, let source {
                recorder.detach(source)
                self.source = nil
            }
            retriesLeft = 5
            scheduleCheck()
        }

        override func layoutSubviews() {
            super.layoutSubviews()
            scheduleCheck()
        }

        /// Deferred to the next main-queue turn so the `ARView` already has its frame.
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
            guard let recorder, let window else { return }
            let frame = convert(bounds, to: window)
            guard frame.width > 0, frame.height > 0 else { return }
            guard let arView = RealityViewPairing.arView(behind: self, markerFrame: frame, in: window) else {
                // The RealityView may not be laid out yet on the first passes.
                if retriesLeft > 0 {
                    retriesLeft -= 1
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.05) { [weak self] in self?.check() }
                }
                return
            }
            retriesLeft = 5
            if source?.arView === arView { return }
            if let source { recorder.detach(source) }
            let newSource = ARViewCaptureSource(arView: arView)
            source = newSource
            recorder.attach(newSource)
        }
    }
}

extension ARSceneView.Coordinator {
    /// Attaches `recorder` to `arView`, detaching the previous one. Idempotent.
    @MainActor
    func syncRecorder(_ recorder: SceneRecorder?, on arView: ARView) {
        if recorder === sceneRecorder, captureSource?.arView === arView { return }
        if let sceneRecorder, let captureSource { sceneRecorder.detach(captureSource) }
        sceneRecorder = recorder
        captureSource = nil
        guard let recorder else { return }
        let source = ARViewCaptureSource(arView: arView)
        captureSource = source
        recorder.attach(source)
    }
}
#endif

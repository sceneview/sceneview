#if os(iOS)
import RealityKit
import SwiftUI
import UIKit

extension SceneView {
    /// Lets `recorder` take photos and videos of this scene.
    ///
    /// The recorder captures what the scene renders, never the SwiftUI views over it. One
    /// recorder serves one scene at a time: attaching it to another one finishes a recording
    /// running on the first, which takes the recorder back once the other one lets it go
    /// (leaves the screen or drops the recorder). A recording also finishes when this view
    /// leaves the screen.
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
    /// Works with ``cameraExposure(_:)`` (see ``SceneRecorder`` for the one case where the
    /// video misses it). One recorder serves one scene at a time; a scene it left takes it
    /// back once the other one lets it go. A recording finishes when
    /// this view leaves the window; the recorder is attached again when it comes back.
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
        private(set) var source: ARViewCaptureSource?
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
            // Kept while another scene that took the recorder since holds it (it may be
            // recording); taken back once that scene let it go.
            if let source, source.arView === arView, recorder.isUsing(source) || recorder.isAttached { return }
            if let source { recorder.detach(source) }
            let newSource = ARViewCaptureSource(arView: arView)
            source = newSource
            recorder.attach(newSource)
        }
    }
}

extension ARSceneView.Coordinator {
    /// Attaches `recorder` to `arView`, detaching the previous one. Idempotent. A recorder
    /// another scene took since stays there while that scene holds it (it may be recording,
    /// and this runs on every render); it is taken back once that scene let it go.
    ///
    /// While attached, a hidden subview of `arView` watches its window: leaving the window
    /// detaches the recorder (finishing a recording), coming back attaches it again.
    @MainActor
    func syncRecorder(_ recorder: SceneRecorder?, on arView: ARView) {
        if recorder === sceneRecorder {
            if recorderWindowWatcher?.isOffWindow == true { return }
            if let recorder, let captureSource, captureSource.arView === arView,
               recorder.isUsing(captureSource) || recorder.isAttached {
                return
            }
        }
        if let sceneRecorder, let captureSource { sceneRecorder.detach(captureSource) }
        sceneRecorder = recorder
        captureSource = nil
        guard let recorder else {
            recorderWindowWatcher?.removeFromSuperview()
            recorderWindowWatcher = nil
            return
        }
        if recorderWindowWatcher?.isOffWindow != true { attachSource(to: recorder, on: arView) }
        if recorderWindowWatcher?.superview !== arView {
            recorderWindowWatcher?.removeFromSuperview()
            let watcher = WindowWatcher()
            watcher.onWindowChange = { [weak self, weak arView] inWindow in
                guard let self, let arView else { return }
                self.recorderWindowChanged(inWindow: inWindow, arView: arView)
            }
            arView.addSubview(watcher)
            recorderWindowWatcher = watcher
        }
    }

    @MainActor
    private func attachSource(to recorder: SceneRecorder, on arView: ARView) {
        let source = ARViewCaptureSource(arView: arView)
        captureSource = source
        recorder.attach(source)
    }

    @MainActor
    private func recorderWindowChanged(inWindow: Bool, arView: ARView) {
        guard let sceneRecorder else { return }
        if inWindow {
            if captureSource == nil { attachSource(to: sceneRecorder, on: arView) }
        } else if let captureSource {
            sceneRecorder.detach(captureSource)
            self.captureSource = nil
        }
    }
}

/// A hidden, zero-size subview that reports when its superview leaves or re-enters a window.
/// A view never put in a window is not reported as leaving one.
final class WindowWatcher: UIView {
    var onWindowChange: ((_ inWindow: Bool) -> Void)?
    private var hadWindow = false

    /// Left a window it was in and not back yet.
    var isOffWindow: Bool { hadWindow && window == nil }

    init() {
        super.init(frame: .zero)
        isHidden = true
        isUserInteractionEnabled = false
        isAccessibilityElement = false
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("init(coder:) is not supported") }

    override func didMoveToWindow() {
        super.didMoveToWindow()
        if window != nil {
            hadWindow = true
            onWindowChange?(true)
        } else if hadWindow {
            onWindowChange?(false)
        }
    }
}
#endif

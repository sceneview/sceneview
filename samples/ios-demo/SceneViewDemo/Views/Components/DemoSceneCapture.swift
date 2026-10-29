import AVKit
import SwiftUI
import SceneViewSwift

/// The shared **Take photo** and **Record video** actions of a demo that hands its scene
/// to the SDK's ``SceneRecorder`` — the iOS twin of the Android demo's capture actions.
///
/// A demo creates one (`@StateObject`), passes ``recorder`` to its scene with
/// `.sceneRecorder(_:)` and puts the capture in the scaffold's environment with
/// ``SwiftUI/View/demoSceneCapture(_:)``. The settings sheet then lists *Take photo* and
/// *Record video* instead of the ReplayKit screen recording, the identity row carries a
/// *Stop · m:ss* pill while a recording runs, and a finished photo or video is offered to
/// **Share** (or **Play**) in a toast above the dock.
///
/// Captures hold the scene only — never the demo's chrome — with the *Made with
/// SceneView* watermark in the bottom-left corner. Videos stop by themselves after a minute
/// and carry no sound.
@MainActor
final class DemoSceneCapture: ObservableObject {
    /// A shareable clip, not a whole session left running.
    static let recordLimit: TimeInterval = 60

    struct Toast: Identifiable, Equatable {
        enum Action: Equatable {
            case share(URL)
            case play(URL)
        }

        let id = UUID()
        let message: String
        let action: Action?
    }

    let recorder = SceneRecorder()
    @Published private(set) var toast: Toast?
    /// The video the Play action opened.
    @Published var playing: URL?

    private var toastTimer: Task<Void, Never>?

    init() {
        recorder.overlay = Self.watermark("Made with SceneView")
        // Every end of a recording lands here: the Stop pill, the sheet row, and the
        // recorder stopping by itself at the limit.
        recorder.onRecordingFinished = { [weak self] recording in
            guard let self else { return }
            if let recording {
                self.show(Toast(
                    message: recording.reachedMaxDuration
                        ? "Stopped at the \(Self.formatElapsed(Self.recordLimit)) limit"
                        : "Video saved on this device",
                    action: .play(recording.url)
                ))
            } else {
                self.show(Toast(message: "Could not record or open the video on this device", action: nil))
            }
        }
    }

    func takePhoto() {
        Task {
            if let url = await recorder.savePhoto(format: .jpeg) {
                show(Toast(message: "Photo saved on this device", action: .share(url)))
            } else {
                show(Toast(message: "Could not take the photo on this device", action: nil))
            }
        }
    }

    func toggleRecording() {
        if recorder.isRecording {
            Task { await recorder.stopRecording() }
        } else if recorder.startRecording(settings: VideoSettings(maxDuration: Self.recordLimit)) {
            dismissToast()
        } else {
            show(Toast(message: "Could not record or open the video on this device", action: nil))
        }
    }

    func dismissToast() {
        toastTimer?.cancel()
        toast = nil
    }

    private func show(_ toast: Toast) {
        self.toast = toast
        toastTimer?.cancel()
        // Snackbar durations: long with an action, short without.
        let seconds: UInt64 = toast.action == nil ? 4 : 10
        toastTimer = Task { [weak self] in
            try? await Task.sleep(nanoseconds: seconds * 1_000_000_000)
            guard !Task.isCancelled, let self, self.toast?.id == toast.id else { return }
            self.toast = nil
        }
    }

    /// `m:ss`.
    static func formatElapsed(_ seconds: TimeInterval) -> String {
        let whole = Int(seconds)
        return String(format: "%d:%02d", whole / 60, whole % 60)
    }

    /// `label` in the bottom-left corner, sized to the capture's short edge (4 %), so a
    /// 1080 × 1920 video and a full-resolution photo carry text of the same size.
    static func watermark(_ label: String) -> CaptureOverlay {
        CaptureOverlay { _, size in
            let shortEdge = min(size.width, size.height)
            let font = UIFont.boldSystemFont(ofSize: shortEdge * 0.04)
            let shadow = NSShadow()
            shadow.shadowColor = UIColor.black.withAlphaComponent(0.6)
            shadow.shadowBlurRadius = font.pointSize / 6
            shadow.shadowOffset = .zero
            let margin = shortEdge * 0.04
            // The baseline sits one margin above the bottom edge.
            let origin = CGPoint(x: margin, y: size.height - margin - font.ascender)
            (label as NSString).draw(at: origin, withAttributes: [
                .font: font,
                .foregroundColor: UIColor.white,
                .shadow: shadow,
            ])
        }
    }
}

private struct DemoSceneCaptureKey: EnvironmentKey {
    static let defaultValue: DemoSceneCapture? = nil
}

extension EnvironmentValues {
    /// The demo's scene capture, when its scene is wired to a ``SceneRecorder``.
    var demoSceneCapture: DemoSceneCapture? {
        get { self[DemoSceneCaptureKey.self] }
        set { self[DemoSceneCaptureKey.self] = newValue }
    }
}

extension View {
    /// Gives the demo scaffold the photo and video actions of `capture`.
    func demoSceneCapture(_ capture: DemoSceneCapture) -> some View {
        environment(\.demoSceneCapture, capture)
    }
}

/// The *Take photo* and *Record video* rows of the settings sheet.
struct DemoCaptureRows: View {
    @ObservedObject var capture: DemoSceneCapture
    @ObservedObject private var recorder: SceneRecorder
    @Environment(\.dismiss) private var dismiss
    @Environment(\.analyticsSampleId) private var analyticsSampleId

    init(capture: DemoSceneCapture) {
        self.capture = capture
        _recorder = ObservedObject(wrappedValue: capture.recorder)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            if !recorder.isRecording {
                DemoSheetRow(icon: "camera", title: "Take photo") {
                    if let analyticsSampleId { DemoAnalytics.shared.interaction(analyticsSampleId, "photo") }
                    // The sheet closes first, as on Android; the photo holds the scene only anyway.
                    dismiss()
                    capture.takePhoto()
                }
                .accessibilityIdentifier("demo-photo")
            }
            DemoSheetRow(icon: recorder.isRecording ? "stop.circle.fill" : "record.circle",
                         title: recorder.isRecording ? "Stop recording" : "Record video") {
                let starting = !recorder.isRecording
                if let analyticsSampleId {
                    DemoAnalytics.shared.interaction(analyticsSampleId, starting ? "record_start" : "record_stop")
                }
                capture.toggleRecording()
                if starting { dismiss() }
            }
            .accessibilityIdentifier("demo-record")
        }
    }
}

/// The identity row's *Stop · m:ss* pill while a scene recording runs.
struct DemoCaptureStopPill: View {
    @ObservedObject var capture: DemoSceneCapture
    @ObservedObject private var recorder: SceneRecorder

    init(capture: DemoSceneCapture) {
        self.capture = capture
        _recorder = ObservedObject(wrappedValue: capture.recorder)
    }

    var body: some View {
        if recorder.isRecording {
            let elapsed = DemoSceneCapture.formatElapsed(recorder.elapsedTime)
            Button {
                capture.toggleRecording()
            } label: {
                GlassPill {
                    // `danger` is a glyph colour (DESIGN.md Status): the dot, never the text.
                    Circle().fill(SceneViewTokens.HomeColor.danger)
                        .frame(width: 8, height: 8)
                        .accessibilityHidden(true)
                    Text("Stop \u{00B7} \(elapsed)")
                        .font(SceneViewTokens.TypeScale.chromeCaption.weight(.semibold).monospacedDigit())
                        .foregroundStyle(SceneViewTokens.Glass.onGlass)
                }
                .frame(minHeight: SceneViewTokens.Layout.touchTarget)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Stop recording, \(elapsed)")
            .accessibilityIdentifier("demo-record-stop")
        }
    }
}

/// The capture result above the dock: a message, and **Share** or **Play**.
struct DemoCaptureToast: View {
    @Environment(\.demoSceneCapture) private var capture

    var body: some View {
        if let capture {
            DemoCaptureToastContent(capture: capture)
        }
    }
}

private struct DemoCaptureToastContent: View {
    @ObservedObject var capture: DemoSceneCapture

    var body: some View {
        ZStack {
            if let toast = capture.toast {
                HStack(spacing: SceneViewTokens.Space.sm) {
                    Text(toast.message)
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(SceneViewTokens.Glass.onGlass)
                        .fixedSize(horizontal: false, vertical: true)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.vertical, SceneViewTokens.Space.sm)
                    switch toast.action {
                    case let .share(url):
                        ShareLink(item: url) { actionLabel("Share") }
                            .accessibilityIdentifier("demo-capture-share")
                    case let .play(url):
                        Button {
                            capture.playing = url
                        } label: { actionLabel("Play") }
                            .accessibilityIdentifier("demo-capture-play")
                    case nil:
                        EmptyView()
                    }
                }
                .padding(.leading, SceneViewTokens.Space.md)
                .padding(.trailing, SceneViewTokens.Space.xs)
                .padding(.vertical, SceneViewTokens.Space.xs)
                .frame(minHeight: SceneViewTokens.Layout.touchTarget)
                .glassBackground(in: RoundedRectangle(cornerRadius: SceneViewTokens.Radius.lg, style: .continuous))
                .accessibilityElement(children: .contain)
                .accessibilityIdentifier("demo-capture-toast")
                .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .animation(SceneViewTokens.Spring.animation, value: capture.toast)
        .sheet(item: Binding(
            get: { capture.playing.map(PlayedVideo.init) },
            set: { capture.playing = $0?.url }
        )) { video in
            VideoPlayer(player: AVPlayer(url: video.url))
                .ignoresSafeArea()
                .presentationDragIndicator(.visible)
        }
    }

    private func actionLabel(_ title: String) -> some View {
        Text(title)
            .font(.subheadline.weight(.semibold))
            .padding(.horizontal, SceneViewTokens.Space.sm)
            .frame(minHeight: SceneViewTokens.Layout.touchTarget)
            .contentShape(Rectangle())
    }
}

private struct PlayedVideo: Identifiable {
    let url: URL
    var id: URL { url }
}

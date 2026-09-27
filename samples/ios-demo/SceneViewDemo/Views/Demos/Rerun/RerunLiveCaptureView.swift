#if os(iOS)

import ARKit
import RealityKit
import SceneViewSwift
import simd
import SwiftUI

// The "Record your own" screen of the Rerun showcase: the live AR camera, one Record / Stop
// control and the capture's counters. Every frame goes through `RerunCaptureRecorder`; on
// Stop the finished pack is handed to the host, which replays it like the bundled room.
//
// The screen draws no navigation chrome: the host owns the title row above (the top
// ~110 pt stay free) and the dock below (the bottom ~140 pt stay free).

/// Records a live AR session into a ``RerunCapturePack``.
///
/// The AR session runs only while the view is on screen; leaving it mid-recording discards
/// the recording. Tapping a detected surface places a Shiba facing the camera, logged as
/// an anchor while recording.
struct RerunLiveCaptureView: View {
    private let onFinish: (RerunCapturePack) -> Void
    @State private var model = RerunLiveCaptureModel()
    @State private var isOnScreen = false

    /// `onFinish` receives the capture when the user stops a recording that holds at least
    /// one camera pose.
    init(onFinish: @escaping (RerunCapturePack) -> Void) {
        self.onFinish = onFinish
    }

    var body: some View {
        #if targetEnvironment(simulator)
        RerunLiveCaptureSimulatorCard()
        #else
        ZStack {
            if isOnScreen {
                ARSceneView(
                    planeDetection: .both,
                    showPlaneOverlay: true,
                    showCoachingOverlay: true,
                    onTapOnPlane: { position, arView in
                        model.place(at: position, in: arView)
                    }
                )
                .onFrame { frame, _ in
                    model.handle(frame)
                }
                .ignoresSafeArea()
            } else {
                SceneViewTokens.Stage.background.ignoresSafeArea()
            }

            VStack(spacing: SceneViewTokens.Space.sm) {
                Spacer(minLength: CaptureTokens.topReserve)
                if let hint = model.hint {
                    CaptureHint(text: hint)
                }
                CaptureCard(model: model) {
                    model.toggleRecording(onFinish: onFinish)
                }
            }
            .frame(maxWidth: CaptureTokens.maxWidth)
            .padding(.horizontal, SceneViewTokens.Chrome.margin)
            .padding(.bottom, CaptureTokens.bottomReserve)
        }
        .task { await model.loadModel() }
        .onAppear { isOnScreen = true }
        .onDisappear {
            isOnScreen = false
            model.discardRecording()
        }
        #endif
    }
}

// MARK: - Model

/// The screen's state: the recorder while recording, throttled counters for the UI, and the
/// placed models. Driven on the main actor — ARKit delivers `ARSceneView`'s frames on the
/// main queue.
@MainActor
@Observable
final class RerunLiveCaptureModel {
    enum Phase: Equatable {
        case idle
        case recording
        /// Stopped; the pack is being assembled off the main actor.
        case saving
    }

    private(set) var phase: Phase = .idle
    /// The recorder's counters, refreshed at most four times a second.
    private(set) var stats = RerunCaptureRecorder.Stats()
    /// One line of guidance above the card, `nil` for none.
    private(set) var hint: String? = RerunLiveCaptureModel.idleHint

    static let idleHint = "Tap Record, then walk slowly around the room. Tap a surface to place a Shiba."

    @ObservationIgnored private var recorder: RerunCaptureRecorder?
    @ObservationIgnored private var lastStatsRefresh: TimeInterval = -.infinity
    @ObservationIgnored private var cameraPosition: SIMD3<Float>?
    @ObservationIgnored private var shiba: ModelNode?
    @ObservationIgnored private var placed: [AnchorEntity] = []
    @ObservationIgnored private var saveTask: Task<Void, Never>?

    private static let statsInterval: TimeInterval = 0.25
    /// The placed Shiba's longest side, metres.
    private static let shibaSize: Float = 0.35

    /// Loads the Shiba once; placement stays anchor-only when the model is missing.
    func loadModel() async {
        guard shiba == nil else { return }
        shiba = try? await ModelNode.load("shiba")
            .scaleToUnits(Self.shibaSize)
            .withGroundingShadow()
    }

    func handle(_ frame: ARFrame) {
        cameraPosition = RerunCaptureMath.translation(of: frame.camera.transform)
        guard phase == .recording, recorder != nil else { return }
        recorder?.add(RerunPixelBufferFrame(frame))
        guard let recorder else { return }
        if frame.timestamp - lastStatsRefresh >= Self.statsInterval || recorder.isFull {
            lastStatsRefresh = frame.timestamp
            stats = recorder.stats
            hint = recorder.stats.isPhotoLimitReached
                ? "Photo limit reached. The path and points keep recording."
                : nil
        }
    }

    /// Record when idle, stop and hand the pack over when recording.
    func toggleRecording(onFinish: @escaping (RerunCapturePack) -> Void) {
        switch phase {
        case .idle: start()
        case .recording: stop(onFinish: onFinish)
        case .saving: break
        }
    }

    private func start() {
        // What is on screen is what gets recorded: models placed before Record go.
        for anchor in placed { anchor.removeFromParent() }
        placed.removeAll()
        recorder = RerunCaptureRecorder()
        stats = RerunCaptureRecorder.Stats()
        lastStatsRefresh = -.infinity
        hint = nil
        phase = .recording
        SceneViewHaptic.shared.medium()
    }

    private func stop(onFinish: @escaping (RerunCapturePack) -> Void) {
        guard let snapshot = recorder else { return }
        recorder = nil
        stats = snapshot.stats
        phase = .saving
        SceneViewHaptic.shared.medium()
        saveTask = Task { [weak self] in
            let pack = await Task.detached(priority: .userInitiated) { snapshot.finish() }.value
            guard let self, !Task.isCancelled else { return }
            self.phase = .idle
            if snapshot.hasContent {
                self.hint = Self.idleHint
                onFinish(pack)
            } else {
                self.hint = "Nothing recorded yet. Move the phone slowly until tracking settles, then record again."
                SceneViewHaptic.shared.error()
            }
        }
    }

    /// Drops a recording in progress (the screen left). A save already under way completes.
    func discardRecording() {
        guard phase == .recording else { return }
        recorder = nil
        phase = .idle
        stats = RerunCaptureRecorder.Stats()
        hint = Self.idleHint
    }

    /// Places a Shiba at `position`, turned to face the camera, and logs it as an anchor
    /// while recording.
    func place(at position: SIMD3<Float>, in arView: ARView) {
        guard phase != .saving else { return }
        let target = cameraPosition ?? position + SIMD3(0, 0, 1)
        let orientation = RerunCaptureMath.facing(from: position, toward: target)
        if let shiba {
            let anchor = AnchorNode.world(position: position)
            anchor.entity.orientation = orientation
            let copy = shiba.entity.clone(recursive: true)
            for animation in copy.availableAnimations {
                copy.playAnimation(animation.repeat())
            }
            anchor.entity.addChild(copy)
            arView.scene.addAnchor(anchor.entity)
            placed.append(anchor.entity)
        }
        if phase == .recording, recorder?.addAnchor(position: position, orientation: orientation) != nil {
            stats = recorder?.stats ?? stats
        }
        SceneViewHaptic.shared.light()
    }
}

// MARK: - ARKit adapter

extension RerunPixelBufferFrame {
    /// The recorder's view of an `ARFrame`. The frame is read synchronously inside
    /// `RerunCaptureRecorder.add(_:)` and not retained past it.
    init(_ frame: ARFrame) {
        let camera = frame.camera
        var isNormal = false
        if case .normal = camera.trackingState { isNormal = true }
        self.init(
            timestamp: frame.timestamp,
            cameraTransform: camera.transform,
            sensorLens: RerunPinhole(
                intrinsics: camera.intrinsics,
                width: Int(camera.imageResolution.width),
                height: Int(camera.imageResolution.height)
            ),
            isTrackingNormal: isNormal,
            image: frame.capturedImage,
            points: { frame.rawFeaturePoints?.points ?? [] },
            planeList: { frame.anchors.compactMap { ($0 as? ARPlaneAnchor).map(RerunCapturePlane.init) } }
        )
    }
}

extension RerunCapturePlane {
    init(_ anchor: ARPlaneAnchor) {
        var isCeiling = false
        if case .ceiling = anchor.classification { isCeiling = true }
        let normal = anchor.transform.columns.1
        self.init(
            identifier: anchor.identifier,
            kind: .of(
                isHorizontal: anchor.alignment == .horizontal,
                isVertical: anchor.alignment == .vertical,
                worldNormal: SIMD3(normal.x, normal.y, normal.z),
                isCeiling: isCeiling
            ),
            polygon: RerunCaptureMath.worldPolygon(
                boundary: anchor.geometry.boundaryVertices,
                anchorTransform: anchor.transform
            )
        )
    }
}

// MARK: - Chrome

/// Layout of the capture chrome: the AR Overlay Card of `DESIGN.md` over the camera.
private enum CaptureTokens {
    /// Free space kept for the host's title row and dock.
    static let topReserve: CGFloat = 110
    static let bottomReserve: CGFloat = 140
    static let maxWidth: CGFloat = 480
    /// The shutter: one touch target plus a ring of breathing room.
    static let shutterSize: CGFloat = SceneViewTokens.Layout.touchTarget + SceneViewTokens.Space.sm
    static let shutterRing: CGFloat = 3
    static let stopGlyph: CGFloat = 20
    static let liveDot: CGFloat = 8
    /// `shadow-lg` dark — 0 12px 40px rgba(0,0,0,0.5).
    static let shadow = Color.black.opacity(0.5)
    static let shadowRadius: CGFloat = 20
    static let shadowY: CGFloat = 12
    /// Recording is `danger` red, and only recording.
    static let recording = SceneViewTokens.HomeColor.danger
}

private struct CaptureCard: View {
    let model: RerunLiveCaptureModel
    let onShutter: () -> Void
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        VStack(spacing: SceneViewTokens.Space.md) {
            HStack(alignment: .center, spacing: SceneViewTokens.Space.md) {
                VStack(alignment: .leading, spacing: SceneViewTokens.Space.xs) {
                    status
                    counters
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                shutter
            }
            HStack(spacing: SceneViewTokens.Space.xs) {
                Image(systemName: "lock.fill")
                    .imageScale(.small)
                    .accessibilityHidden(true)
                Text("Everything stays on your iPhone.")
            }
            .font(SceneViewTokens.TypeScale.captionRegular)
            .foregroundStyle(SceneViewTokens.ARChrome.onScrimDim)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(SceneViewTokens.Space.md)
        .background(
            RoundedRectangle(cornerRadius: SceneViewTokens.Radius.lg, style: .continuous)
                .fill(SceneViewTokens.ARChrome.scrim(scheme))
        )
        .overlay(
            RoundedRectangle(cornerRadius: SceneViewTokens.Radius.lg, style: .continuous)
                .strokeBorder(SceneViewTokens.ARChrome.border(scheme), lineWidth: SceneViewTokens.ARChrome.borderWidth)
        )
        .shadow(color: CaptureTokens.shadow, radius: CaptureTokens.shadowRadius, y: CaptureTokens.shadowY)
    }

    private var status: some View {
        HStack(spacing: SceneViewTokens.Space.sm) {
            if model.phase == .recording {
                Circle()
                    .fill(CaptureTokens.recording)
                    .frame(width: CaptureTokens.liveDot, height: CaptureTokens.liveDot)
                    .accessibilityHidden(true)
            }
            Text(Self.clock(model.stats.duration))
                .font(SceneViewTokens.TypeScale.card)
                .monospacedDigit()
                .foregroundStyle(SceneViewTokens.ARChrome.onScrim)
            Text(statusLabel)
                .font(SceneViewTokens.TypeScale.captionSemibold)
                .foregroundStyle(SceneViewTokens.ARChrome.onScrimDim)
        }
        .accessibilityElement(children: .combine)
    }

    private var statusLabel: String {
        switch model.phase {
        case .idle: "Ready"
        case .recording: "Recording"
        case .saving: "Saving"
        }
    }

    private var counters: some View {
        let stats = model.stats
        return HStack(spacing: SceneViewTokens.Space.md) {
            Counter(value: String(format: "%.1f m", stats.pathLength), label: "path")
            Counter(value: "\(stats.planes)", label: stats.planes == 1 ? "plane" : "planes")
            Counter(value: stats.points.formatted(), label: "points")
            Counter(value: "\(stats.keyframes)", label: stats.keyframes == 1 ? "photo" : "photos")
        }
        .accessibilityElement(children: .combine)
    }

    private var shutter: some View {
        Button(action: onShutter) {
            ZStack {
                Circle()
                    .strokeBorder(
                        model.phase == .recording ? CaptureTokens.recording : SceneViewTokens.ARChrome.onScrim,
                        lineWidth: CaptureTokens.shutterRing
                    )
                switch model.phase {
                case .idle:
                    Circle()
                        .fill(SceneViewTokens.ARChrome.onScrim)
                        .padding(CaptureTokens.shutterRing + SceneViewTokens.Space.xs)
                case .recording:
                    RoundedRectangle(cornerRadius: SceneViewTokens.Space.xs, style: .continuous)
                        .fill(CaptureTokens.recording)
                        .frame(width: CaptureTokens.stopGlyph, height: CaptureTokens.stopGlyph)
                case .saving:
                    ProgressView()
                        .tint(SceneViewTokens.ARChrome.onScrim)
                }
            }
            .frame(width: CaptureTokens.shutterSize, height: CaptureTokens.shutterSize)
            .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .disabled(model.phase == .saving)
        .accessibilityLabel(model.phase == .recording ? "Stop recording" : "Start recording")
    }

    /// `m:ss`.
    static func clock(_ seconds: TimeInterval) -> String {
        let whole = max(0, Int(seconds))
        return String(format: "%d:%02d", whole / 60, whole % 60)
    }
}

private struct Counter: View {
    let value: String
    let label: String

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(value)
                .font(SceneViewTokens.TypeScale.bodySemibold)
                .monospacedDigit()
                .foregroundStyle(SceneViewTokens.ARChrome.onScrim)
                .lineLimit(1)
                .minimumScaleFactor(0.8)
            Text(label)
                .font(SceneViewTokens.TypeScale.captionRegular)
                .foregroundStyle(SceneViewTokens.ARChrome.onScrimDim)
        }
    }
}

private struct CaptureHint: View {
    let text: String
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        Text(text)
            .font(SceneViewTokens.TypeScale.captionRegular)
            .foregroundStyle(SceneViewTokens.ARChrome.onScrim)
            .multilineTextAlignment(.center)
            .padding(.horizontal, SceneViewTokens.Space.md)
            .padding(.vertical, SceneViewTokens.Space.sm)
            .background(
                Capsule().fill(SceneViewTokens.ARChrome.scrim(scheme))
            )
            .overlay(
                Capsule().strokeBorder(SceneViewTokens.ARChrome.border(scheme), lineWidth: SceneViewTokens.ARChrome.borderWidth)
            )
            .transition(.opacity)
    }
}

/// The simulator has no camera: say so, and point at the recorded replay instead.
private struct RerunLiveCaptureSimulatorCard: View {
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        ZStack {
            SceneViewTokens.Stage.background.ignoresSafeArea()
            VStack(alignment: .leading, spacing: SceneViewTokens.Space.sm) {
                Image(systemName: "camera.fill")
                    .font(SceneViewTokens.TypeScale.card)
                    .foregroundStyle(SceneViewTokens.ARChrome.onScrimDim)
                    .accessibilityHidden(true)
                Text("Live AR needs an iPhone camera.")
                    .font(SceneViewTokens.TypeScale.card)
                    .foregroundStyle(SceneViewTokens.ARChrome.onScrim)
                Text("Tap 3D to watch a real recorded room rebuild itself.")
                    .font(SceneViewTokens.TypeScale.body)
                    .foregroundStyle(SceneViewTokens.ARChrome.onScrimDim)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(SceneViewTokens.Space.md)
            .background(
                RoundedRectangle(cornerRadius: SceneViewTokens.Radius.lg, style: .continuous)
                    .fill(SceneViewTokens.ARChrome.scrim(scheme))
            )
            .overlay(
                RoundedRectangle(cornerRadius: SceneViewTokens.Radius.lg, style: .continuous)
                    .strokeBorder(SceneViewTokens.ARChrome.border(scheme), lineWidth: SceneViewTokens.ARChrome.borderWidth)
            )
            .shadow(color: CaptureTokens.shadow, radius: CaptureTokens.shadowRadius, y: CaptureTokens.shadowY)
            .frame(maxWidth: CaptureTokens.maxWidth)
            .padding(.horizontal, SceneViewTokens.Chrome.margin)
            .padding(.top, CaptureTokens.topReserve)
            .padding(.bottom, CaptureTokens.bottomReserve)
            .accessibilityElement(children: .combine)
        }
    }
}

#endif

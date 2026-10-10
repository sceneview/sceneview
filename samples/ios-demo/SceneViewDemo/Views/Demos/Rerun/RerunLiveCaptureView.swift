#if os(iOS)

import ARKit
import RealityKit
import SceneViewSwift
import simd
import SwiftUI

// The Record screen of Room Scan: the live AR camera and one Record / Stop control. Every frame
// goes through `RerunCaptureRecorder`; on Stop the finished pack is handed to the host, which
// replays it like the bundled room.
//
// The camera keeps the screen (#4379). What the recorder has kept is drawn over it as it grows
// — the path walked, every voxel the feature points filled, the planes tracked now — and that
// drawing is the readout. A scan in progress says one line, in the host's title row
// (`RerunCaptureStatusLine`); its counts are rows of the host's settings sheet
// (`RerunCaptureSettings`). All of it is the recorder's own data.
//
// The screen draws no navigation chrome: the host owns the title row above and the dock below,
// and says how much of the window they take.

/// Records a live AR session into a ``RerunCapturePack``.
///
/// The AR session runs only while the view is on screen; leaving it mid-recording discards
/// the recording. Tapping a detected surface places a Shiba facing the camera, logged as
/// an anchor while recording.
struct RerunLiveCaptureView: View {
    private let topInset: CGFloat
    private let bottomInset: CGFloat
    private let onFinish: (RerunCapturePack) -> Void
    private let onStatusChange: (RerunLiveStatus) -> Void
    @State private var model = RerunLiveCaptureModel()
    @State private var isOnScreen = false

    /// `topInset` and `bottomInset` are what the host's title row and dock take of the window.
    /// `onFinish` receives the capture when the user stops a recording that holds at least
    /// one camera pose. `onStatusChange` hands the host the scan's phase and counts as they
    /// move: it shows them, and puts away whatever would end a recording by accident.
    init(topInset: CGFloat, bottomInset: CGFloat,
         onStatusChange: @escaping (RerunLiveStatus) -> Void = { _ in },
         onFinish: @escaping (RerunCapturePack) -> Void) {
        self.topInset = topInset
        self.bottomInset = bottomInset
        self.onStatusChange = onStatusChange
        self.onFinish = onFinish
    }

    var body: some View {
        #if targetEnvironment(simulator)
        RerunLiveCaptureSimulatorCard(topInset: topInset, bottomInset: bottomInset)
        #else
        ZStack {
            if isOnScreen {
                ARSceneView(
                    planeDetection: .both,
                    // The recorder's planes are drawn instead (`RerunLiveOverlay`): the same
                    // polygons that go into the file, and bold enough to film.
                    showPlaneOverlay: false,
                    showCoachingOverlay: true,
                    onTapOnPlane: { position, arView in
                        model.place(at: position, in: arView)
                    }
                )
                .onFrame { frame, arView in
                    model.handle(frame, in: arView)
                }
                .ignoresSafeArea()
            } else {
                SceneViewTokens.Stage.background.ignoresSafeArea()
            }

            chrome
        }
        .task { await model.loadModel() }
        .onChange(of: model.status, initial: true) { _, status in onStatusChange(status) }
        .onAppear { isOnScreen = true }
        .onDisappear {
            isOnScreen = false
            model.discardRecording()
            onStatusChange(RerunLiveStatus())
        }
        #endif
    }

    /// The shutter and, while there is something to say, one card of copy above it.
    private var chrome: some View {
        VStack(spacing: SceneViewTokens.Space.md) {
            Spacer(minLength: topInset)
            if let hint = model.hint {
                CaptureHint(text: hint, privacy: model.phase == .idle)
            }
            CaptureShutter(phase: model.phase) {
                model.toggleRecording(onFinish: onFinish)
            }
        }
        .frame(maxWidth: CaptureTokens.maxWidth)
        .padding(.horizontal, SceneViewTokens.Chrome.margin)
        .padding(.bottom, bottomInset)
        .animation(SceneViewTokens.Spring.fade, value: model.hint)
    }
}

/// What the host shows of a scan: its phase, and what the recorder holds.
struct RerunLiveStatus: Equatable {
    var phase: RerunLiveCaptureModel.Phase = .idle
    var stats = RerunCaptureRecorder.Stats()
}

// MARK: - Model

/// The screen's state: the recorder while recording, throttled figures for the UI, the live
/// overlay and the placed models. Driven on the main actor — ARKit delivers `ARSceneView`'s
/// frames on the main queue.
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
    /// One line of guidance above the controls, `nil` for none.
    private(set) var hint: String? = RerunLiveCaptureModel.idleHint

    var status: RerunLiveStatus { RerunLiveStatus(phase: phase, stats: stats) }

    static let idleHint = "Tap Record, then walk slowly around the room. Tap a surface to place a Shiba."

    @ObservationIgnored private var recorder: RerunCaptureRecorder?
    @ObservationIgnored private var lastStatsRefresh: TimeInterval = -.infinity
    @ObservationIgnored private var cameraPosition: SIMD3<Float>?
    @ObservationIgnored private var shiba: ModelNode?
    @ObservationIgnored private var placed: [AnchorEntity] = []
    @ObservationIgnored private var saveTask: Task<Void, Never>?
    @ObservationIgnored private let overlay = RerunLiveOverlay()

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

    func handle(_ frame: ARFrame, in arView: ARView) {
        cameraPosition = RerunCaptureMath.translation(of: frame.camera.transform)
        guard phase == .recording, recorder != nil else { return }
        recorder?.add(RerunPixelBufferFrame(frame))
        guard let recorder else { return }
        if frame.timestamp - lastStatsRefresh >= Self.statsInterval || recorder.isFull {
            lastStatsRefresh = frame.timestamp
            stats = recorder.stats
            overlay.attach(to: arView)
            overlay.update(path: recorder.pathPositions, points: recorder.voxelPoints, planes: recorder.currentPlanes)
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
        overlay.clear()
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
                self.overlay.clear()
                self.hint = "Nothing recorded yet. Move the phone slowly until tracking settles, then record again."
                SceneViewHaptic.shared.error()
            }
        }
    }

    /// Drops a recording in progress (the screen left). A save already under way completes.
    func discardRecording() {
        overlay.detach()
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

// MARK: - Live overlay

/// The recorder's data drawn over the camera in world space (``RerunLiveGeometry``): the trail
/// in the replay's newest-trail colour, points in its live-point amber, planes in its floor and
/// wall colours — so the replay that opens on Stop is visibly the same thing.
@MainActor
final class RerunLiveOverlay {
    private let anchor = AnchorEntity(world: .zero)
    private let trail = RerunLiveOverlay.entity(SceneViewTokens.DebugView.trailNew)
    private let horizontalFill = RerunLiveOverlay.entity(SceneViewTokens.DebugView.liveFloorFill)
    private let verticalFill = RerunLiveOverlay.entity(SceneViewTokens.DebugView.liveWallFill)
    private let horizontalOutline = RerunLiveOverlay.entity(SceneViewTokens.DebugView.liveFloorOutline)
    private let verticalOutline = RerunLiveOverlay.entity(SceneViewTokens.DebugView.liveWallOutline)
    private var pointChunks: [ModelEntity] = []
    private var chunkCounts: [Int] = []
    private var pathCount = 0
    private var shownPlanes: [RerunCapturePlane] = []

    init() {
        for entity in [horizontalFill, verticalFill, horizontalOutline, verticalOutline, trail] {
            anchor.addChild(entity)
        }
    }

    func attach(to arView: ARView) {
        guard anchor.scene == nil else { return }
        arView.scene.addAnchor(anchor)
    }

    func detach() {
        anchor.removeFromParent()
    }

    func clear() {
        for entity in [trail, horizontalFill, verticalFill, horizontalOutline, verticalOutline] + pointChunks {
            entity.isEnabled = false
        }
        chunkCounts = chunkCounts.map { _ in 0 }
        pathCount = 0
        shownPlanes = []
    }

    func update(path: [SIMD3<Float>], points: [SIMD3<Float>], planes: [RerunCapturePlane]) {
        if path.count != pathCount {
            pathCount = path.count
            Self.show(RerunLiveGeometry.trail(path), on: trail)
        }
        for (i, range) in RerunLiveGeometry.pointChunks(count: points.count).enumerated() {
            if i == pointChunks.count {
                let chunk = Self.entity(SceneViewTokens.DebugView.livePoint)
                anchor.addChild(chunk)
                pointChunks.append(chunk)
                chunkCounts.append(0)
            }
            guard chunkCounts[i] != range.count else { continue }
            chunkCounts[i] = range.count
            Self.show(RerunLiveGeometry.points(points, range: range), on: pointChunks[i])
        }
        if planes != shownPlanes {
            shownPlanes = planes
            let meshes = RerunLiveGeometry.planes(planes)
            Self.show(meshes.horizontalFill, on: horizontalFill)
            Self.show(meshes.verticalFill, on: verticalFill)
            Self.show(meshes.horizontalOutline, on: horizontalOutline)
            Self.show(meshes.verticalOutline, on: verticalOutline)
        }
    }

    private static func show(_ mesh: RerunMesh, on entity: ModelEntity) {
        guard let resource = RerunStageRenderer.resource(mesh) else {
            entity.isEnabled = false
            return
        }
        entity.model?.mesh = resource
        entity.isEnabled = true
    }

    private static func entity(_ argb: UInt32) -> ModelEntity {
        let entity = ModelEntity(mesh: .generatePlane(width: 0.001, depth: 0.001),
                                 materials: [RerunStageRenderer.flatMaterial(argb)])
        entity.isEnabled = false
        return entity
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

/// Layout of the capture chrome: `DESIGN.md` AR Overlay Cards over the camera.
private enum CaptureTokens {
    static let maxWidth: CGFloat = 480
    /// The shutter: bigger than a touch target, so Record and Stop read on film too.
    static let shutterSize: CGFloat = 76
    static let shutterRing: CGFloat = 4
    static let stopGlyph: CGFloat = 26
    static let recDot: CGFloat = 14
    /// `shadow-lg` dark — 0 12px 40px rgba(0,0,0,0.5).
    static let shadow = Color.black.opacity(0.5)
    static let shadowRadius: CGFloat = 20
    static let shadowY: CGFloat = 12
    /// Recording is `danger` red, and only recording: the dot and the shutter.
    static let recording = SceneViewTokens.HomeColor.danger
}

/// The dark AR card every piece of the capture chrome sits on.
private struct CaptureCard: ViewModifier {
    @Environment(\.colorScheme) private var scheme

    func body(content: Content) -> some View {
        content
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
}

/// A scan in progress says one line: the red dot, "Scanning" and the clock. It rides the host's
/// title row, so it takes nothing more of the camera.
struct RerunCaptureStatusLine: View {
    let status: RerunLiveStatus

    var body: some View {
        GlassPill {
            HStack(spacing: SceneViewTokens.Space.sm) {
                if status.phase == .recording { RecordingDot() }
                Text(status.phase == .saving ? "Saving" : "Scanning")
                Text(RerunFormat.clock(Float(status.stats.duration)))
                    .monospacedDigit()
                    .contentTransition(.numericText())
            }
            .font(SceneViewTokens.TypeScale.card)
            .foregroundStyle(SceneViewTokens.Glass.onGlass)
            .lineLimit(1)
        }
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("rerun-scan-status")
    }
}

/// What the settings sheet reads out of a scan, once: the recorder's counts. Each dot is the
/// colour that figure is drawn in over the camera.
struct RerunCaptureSettings: View {
    let status: RerunLiveStatus

    private typealias Palette = SceneViewTokens.DebugView

    var body: some View {
        let stats = status.stats
        VStack(alignment: .leading, spacing: 0) {
            Text("This scan")
                .font(SceneViewTokens.TypeScale.captionSemibold)
                .foregroundStyle(.secondary)
                .accessibilityAddTraits(.isHeader)
            RerunSheetFigure(label: "Path walked", value: RerunFormat.distance(stats.pathLength)) {
                RerunLayerDot(color: Palette.trailNew)
            }
            RerunSheetFigure(label: "Points", value: RerunFormat.count(stats.points)) {
                RerunLayerDot(color: Palette.livePoint)
            }
            RerunSheetFigure(label: stats.planes == 1 ? "Surface" : "Surfaces", value: "\(stats.planes)") {
                RerunLayerDot(color: Palette.liveFloorOutline)
            }
            RerunSheetFigure(label: stats.keyframes == 1 ? "Photo" : "Photos", value: "\(stats.keyframes)") {
                Image(systemName: "photo").foregroundStyle(.secondary)
            }
        }
        .animation(SceneViewTokens.Spring.animation, value: stats)
        .accessibilityIdentifier("rerun-scan-counts")
    }
}

/// A slow red pulse — the one thing on screen that says "recording" before any figure moves.
private struct RecordingDot: View {
    @State private var dim = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        Circle()
            .fill(CaptureTokens.recording)
            .frame(width: CaptureTokens.recDot, height: CaptureTokens.recDot)
            .opacity(dim ? 0.35 : 1)
            .onAppear {
                guard !reduceMotion else { return }
                withAnimation(.easeInOut(duration: 0.8).repeatForever(autoreverses: true)) { dim = true }
            }
            .accessibilityHidden(true)
    }
}

/// The shutter, alone over the camera: Record when idle, Stop while recording.
private struct CaptureShutter: View {
    let phase: RerunLiveCaptureModel.Phase
    let action: () -> Void
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        Button(action: action) {
            ZStack {
                Circle()
                    .fill(SceneViewTokens.ARChrome.scrim(scheme))
                Circle()
                    .strokeBorder(
                        phase == .recording ? CaptureTokens.recording : SceneViewTokens.ARChrome.onScrim,
                        lineWidth: CaptureTokens.shutterRing
                    )
                switch phase {
                case .idle:
                    Circle()
                        .fill(CaptureTokens.recording)
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
        .buttonStyle(PressScaleButtonStyle(scale: SceneViewTokens.Spring.chromePressScale))
        .disabled(phase == .saving)
        .shadow(color: CaptureTokens.shadow, radius: CaptureTokens.shadowRadius, y: CaptureTokens.shadowY)
        .accessibilityLabel(phase == .recording ? "Stop recording" : "Start recording")
        .accessibilityIdentifier("rerun-shutter")
    }
}

/// One card of copy above the shutter. The idle copy ends on the privacy line.
private struct CaptureHint: View {
    let text: String
    var privacy = false
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        VStack(spacing: SceneViewTokens.Space.xs) {
            Text(text)
                .font(SceneViewTokens.TypeScale.bodySemibold)
                .foregroundStyle(SceneViewTokens.ARChrome.onScrim)
                .multilineTextAlignment(.center)
            if privacy {
                Label("Everything stays on your iPhone.", systemImage: "lock.fill")
                    .font(SceneViewTokens.TypeScale.captionSemibold)
                    .foregroundStyle(SceneViewTokens.ARChrome.onScrimDim)
            }
        }
        .padding(.horizontal, SceneViewTokens.Space.md)
        .padding(.vertical, SceneViewTokens.Space.sm)
        .background(
            RoundedRectangle(cornerRadius: SceneViewTokens.Radius.lg, style: .continuous)
                .fill(SceneViewTokens.ARChrome.scrim(scheme))
        )
        .overlay(
            RoundedRectangle(cornerRadius: SceneViewTokens.Radius.lg, style: .continuous)
                .strokeBorder(SceneViewTokens.ARChrome.border(scheme), lineWidth: SceneViewTokens.ARChrome.borderWidth)
        )
        .transition(.opacity)
    }
}

/// The simulator has no camera: say so, and point at the recorded replay instead.
private struct RerunLiveCaptureSimulatorCard: View {
    let topInset: CGFloat
    let bottomInset: CGFloat

    var body: some View {
        ZStack {
            SceneViewTokens.Stage.background.ignoresSafeArea()
            VStack(alignment: .leading, spacing: SceneViewTokens.Space.sm) {
                Image(systemName: "camera.fill")
                    .font(SceneViewTokens.TypeScale.card)
                    .foregroundStyle(SceneViewTokens.ARChrome.onScrimDim)
                    .accessibilityHidden(true)
                Text("Recording needs an iPhone camera.")
                    .font(SceneViewTokens.TypeScale.card)
                    .foregroundStyle(SceneViewTokens.ARChrome.onScrim)
                Text("Go back to Sessions to watch the sample session, or open a scan file.")
                    .font(SceneViewTokens.TypeScale.body)
                    .foregroundStyle(SceneViewTokens.ARChrome.onScrimDim)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(SceneViewTokens.Space.md)
            .modifier(CaptureCard())
            .frame(maxWidth: CaptureTokens.maxWidth)
            .padding(.horizontal, SceneViewTokens.Chrome.margin)
            .padding(.top, topInset)
            .padding(.bottom, bottomInset)
            .accessibilityElement(children: .combine)
        }
    }
}

#endif

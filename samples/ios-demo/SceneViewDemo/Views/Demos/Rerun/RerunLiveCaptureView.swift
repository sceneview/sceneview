#if os(iOS)

import ARKit
import RealityKit
import SceneViewSwift
import simd
import SwiftUI

// The "Record your own" screen of the Rerun showcase: the live AR camera, one Record / Stop
// control and the capture's figures. Every frame goes through `RerunCaptureRecorder`; on
// Stop the finished pack is handed to the host, which replays it like the bundled room.
//
// Built to be filmed: the launch video shoots the phone over a shoulder, in one take, from
// about a metre. So the figures are big and white on a near-opaque scrim, and what the
// recorder has kept is drawn over the camera as it grows — the path walked, every voxel the
// feature points filled, the planes tracked now. All of it is the recorder's own data.
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
    private let onRecordingChange: (Bool) -> Void
    @State private var model = RerunLiveCaptureModel()
    @State private var isOnScreen = false

    /// `onFinish` receives the capture when the user stops a recording that holds at least
    /// one camera pose. `onRecordingChange` tells the host when a recording starts and when it
    /// is over (saved or discarded), so it can put away whatever would end it by accident.
    init(onRecordingChange: @escaping (Bool) -> Void = { _ in },
         onFinish: @escaping (RerunCapturePack) -> Void) {
        self.onRecordingChange = onRecordingChange
        self.onFinish = onFinish
    }

    var body: some View {
        #if targetEnvironment(simulator)
        RerunLiveCaptureSimulatorCard()
        #else
        ZStack {
            if isOnScreen {
                ARSceneView(
                    configuration: ARSessionConfiguration(
                        planeDetection: .both,
                        frameSemantics: model.supportsLiDAR ? [.sceneDepth] : []
                    ),
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

            VStack(spacing: SceneViewTokens.Space.sm) {
                CaptureReadout(model: model)
                    .padding(.top, CaptureTokens.topReserve)
                Spacer(minLength: SceneViewTokens.Space.md)
                if let hint = model.hint {
                    CaptureHint(text: hint)
                }
                CaptureControls(model: model) {
                    model.toggleRecording(onFinish: onFinish)
                }
            }
            .frame(maxWidth: CaptureTokens.maxWidth)
            .padding(.horizontal, SceneViewTokens.Chrome.margin)
            .padding(.bottom, CaptureTokens.bottomReserve)
            .animation(SceneViewTokens.Spring.fade, value: model.hint)
        }
        .task { await model.loadModel() }
        .onChange(of: model.phase) { _, phase in onRecordingChange(phase != .idle) }
        .onAppear { isOnScreen = true }
        .onDisappear {
            isOnScreen = false
            model.discardRecording()
        }
        #endif
    }
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

    /// A LiDAR iPhone or iPad: its scene depth is fused into a dense 2 cm surfel map while
    /// recording (`.svscan` v2, tier `lidar`), like Android's raw-depth scan.
    let supportsLiDAR = ARWorldTrackingConfiguration.supportsFrameSemantics(.sceneDepth)
    /// Surfels in the dense map, refreshed with `stats`.
    private(set) var denseCount = 0
    var denseIsFull: Bool { denseCount >= RerunDenseCloud.maxPoints }
    // The dense map: one fusion at a time off the main actor; a depth frame that arrives while it
    // runs is skipped. Depth is sampled at most every `depthInterval` (Android's 10 Hz).
    @ObservationIgnored private var depthWorker: RerunDepthWorker?
    @ObservationIgnored private var depthTask: Task<Void, Never>?
    @ObservationIgnored private var lastDepthTime: TimeInterval = -.infinity
    @ObservationIgnored private var denseAdded = 0
    @ObservationIgnored private var denseKept = 0
    @ObservationIgnored private var denseTotal = 0
    @ObservationIgnored private var recordedTotal = 0
    @ObservationIgnored private var captureID = UUID()

    private static let depthInterval: TimeInterval = 0.1

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
        recordDepthStats(timestamp: frame.timestamp)
        fuseDepthIfDue(frame)
        guard let recorder else { return }
        if frame.timestamp - lastStatsRefresh >= Self.statsInterval || recorder.isFull {
            lastStatsRefresh = frame.timestamp
            stats = recorder.stats
            denseCount = denseTotal
            overlay.attach(to: arView)
            overlay.update(path: recorder.pathPositions, points: recorder.voxelPoints, planes: recorder.currentPlanes)
            hint = recorder.stats.isPhotoLimitReached
                ? "Photo limit reached. The path and points keep recording."
                : nil
        }
    }

    /// Copies `frame`'s LiDAR depth — due, confident, and no fusion running — and fuses it into
    /// the dense map off the main actor. The copy happens here, so no ARKit buffer outlives the
    /// frame.
    private func fuseDepthIfDue(_ frame: ARFrame) {
        guard supportsLiDAR, depthTask == nil, let worker = depthWorker, recorder?.isFull == false,
              denseTotal < RerunDenseCloud.maxPoints,
              frame.timestamp - lastDepthTime >= Self.depthInterval,
              case .normal = frame.camera.trackingState else { return }
        lastDepthTime = frame.timestamp
        guard let depth = RerunDepthFrame(frame) else { return }
        let id = captureID
        depthTask = Task { [weak self] in
            let stats = await worker.add(depth)
            guard let self, self.captureID == id else { return }
            self.denseAdded += stats.added
            self.denseKept += stats.kept
            self.denseTotal = stats.total
            self.depthTask = nil
        }
    }

    /// Logs how far the dense map has grown since the last call, when it has, stamped at
    /// `timestamp` — the frame that learns of it — or the latest frame.
    private func recordDepthStats(timestamp: TimeInterval? = nil) {
        guard denseTotal != recordedTotal else { return }
        recorder?.addDepthStats(timestamp: timestamp, added: denseAdded, kept: denseKept, total: denseTotal)
        recordedTotal = denseTotal
        denseAdded = 0
        denseKept = 0
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
        captureID = UUID()
        depthWorker = supportsLiDAR ? RerunDepthWorker() : nil
        depthTask = nil
        lastDepthTime = -.infinity
        denseCount = 0
        denseAdded = 0
        denseKept = 0
        denseTotal = 0
        recordedTotal = 0
        stats = RerunCaptureRecorder.Stats()
        lastStatsRefresh = -.infinity
        hint = nil
        phase = .recording
        SceneViewHaptic.shared.medium()
    }

    private func stop(onFinish: @escaping (RerunCapturePack) -> Void) {
        guard recorder != nil else { return }
        phase = .saving
        SceneViewHaptic.shared.medium()
        let pendingDepth = depthTask
        let worker = depthWorker
        let id = captureID
        saveTask = Task { [weak self] in
            // The fusion under way finishes first, and its last growth goes on the timeline.
            await pendingDepth?.value
            guard let self, self.captureID == id, self.recorder != nil else { return }
            self.recordDepthStats()
            guard let snapshot = self.recorder else { return }
            self.recorder = nil
            self.stats = snapshot.stats
            let started = Date()
            let dense = await worker?.cloud()
            let denseMs = Int64(Date().timeIntervalSince(started) * 1000)
            // The tier the scan really reached: LiDAR on but no surfel is a sparse scan, said so.
            let hasDense = (dense?.count ?? 0) > 0
            let device = RerunManifest.Device(
                platform: "ios",
                model: Self.hardwareModel,
                tier: hasDense ? RerunManifest.Device.tierLidar : RerunManifest.Device.tierSparse,
                depthSource: hasDense ? RerunManifest.Device.sourceLidar : RerunManifest.Device.sourceFeaturePoints
            )
            let pack = await Task.detached(priority: .userInitiated) {
                snapshot.finish(device: device, dense: dense, denseVoxelM: RerunDenseFusion.voxelM, denseMs: denseMs)
            }.value
            guard !Task.isCancelled else { return }
            self.phase = .idle
            self.denseCount = dense?.count ?? 0
            self.depthWorker = nil
            self.depthTask = nil
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
        captureID = UUID()
        depthTask?.cancel()
        depthTask = nil
        depthWorker = nil
        denseCount = 0
        phase = .idle
        stats = RerunCaptureRecorder.Stats()
        hint = Self.idleHint
    }

    private static var hardwareModel: String {
        var info = utsname()
        uname(&info)
        return withUnsafeBytes(of: &info.machine) { bytes in
            String(decoding: bytes.prefix { $0 != 0 }, as: UTF8.self)
        }
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

extension RerunDepthFrame {
    /// `frame`'s LiDAR scene depth copied out of ARKit, each kept pixel coloured from the camera
    /// image (which the depth map is aligned with), the lens scaled from the camera image to the
    /// depth map. `nil` without scene depth or its confidence map — unfiltered depth is too noisy
    /// to keep, as on Android. Main actor: the buffers are read while the frame is current.
    @MainActor init?(_ frame: ARFrame) {
        guard let scene = frame.sceneDepth, let confidenceMap = scene.confidenceMap else { return nil }
        let depthMap = scene.depthMap
        let w = CVPixelBufferGetWidth(depthMap), h = CVPixelBufferGetHeight(depthMap)
        guard w > 1, h > 1,
              CVPixelBufferGetPixelFormatType(depthMap) == kCVPixelFormatType_DepthFloat32,
              CVPixelBufferGetPixelFormatType(confidenceMap) == kCVPixelFormatType_OneComponent8,
              CVPixelBufferGetWidth(confidenceMap) == w, CVPixelBufferGetHeight(confidenceMap) == h
        else { return nil }
        var depthM = [Float](repeating: 0, count: w * h)
        var confidence = [UInt8](repeating: 0, count: w * h)
        guard CVPixelBufferLockBaseAddress(depthMap, .readOnly) == kCVReturnSuccess else { return nil }
        defer { CVPixelBufferUnlockBaseAddress(depthMap, .readOnly) }
        guard CVPixelBufferLockBaseAddress(confidenceMap, .readOnly) == kCVReturnSuccess else { return nil }
        defer { CVPixelBufferUnlockBaseAddress(confidenceMap, .readOnly) }
        guard let depthBase = CVPixelBufferGetBaseAddress(depthMap),
              let confidenceBase = CVPixelBufferGetBaseAddress(confidenceMap) else { return nil }
        let depthRow = CVPixelBufferGetBytesPerRow(depthMap)
        let confidenceRow = CVPixelBufferGetBytesPerRow(confidenceMap)
        for y in 0..<h {
            let d = depthBase.advanced(by: y * depthRow).assumingMemoryBound(to: Float32.self)
            let c = confidenceBase.advanced(by: y * confidenceRow).assumingMemoryBound(to: UInt8.self)
            for x in 0..<w {
                depthM[y * w + x] = d[x]
                // ARKit's low / medium / high on Android's 0–255 scale: medium and high pass.
                confidence[y * w + x] = RerunDepthBackProjection.confidence(arkitLevel: c[x])
            }
        }
        // Colour only the pixels the back-projection can keep: the others cost a read for nothing.
        var colors = [UInt32](repeating: 0, count: w * h)
        _ = RerunYCbCrImage.withPlanes(of: frame.capturedImage) { image in
            let sx = Float(image.width) / Float(w), sy = Float(image.height) / Float(h)
            for i in 0..<(w * h) {
                let d = depthM[i]
                guard d > 0, d.isFinite, Int(confidence[i]) >= RerunDepthBackProjection.minConfidence else { continue }
                let rgb = image.color(x: Int(Float(i % w) * sx + sx * 0.5), y: Int(Float(i / w) * sy + sy * 0.5))
                colors[i] = 0xFF00_0000 | UInt32(rgb.x) << 16 | UInt32(rgb.y) << 8 | UInt32(rgb.z)
            }
        }
        let camera = frame.camera
        let k = camera.intrinsics
        let kx = Float(w) / Float(camera.imageResolution.width)
        let ky = Float(h) / Float(camera.imageResolution.height)
        self.init(width: w, height: h, depthM: depthM, confidence: confidence, colors: colors,
                  fx: k.columns.0.x * kx, fy: k.columns.1.y * ky, cx: k.columns.2.x * kx, cy: k.columns.2.y * ky,
                  cameraToWorld: camera.transform)
    }
}

/// Owns the dense map: back-projection and fusion run here, off the main actor, one frame at a
/// time.
private actor RerunDepthWorker {
    private let fusion = RerunDenseFusion()

    func add(_ frame: RerunDepthFrame) -> RerunDenseFuseStats {
        fusion.add(RerunDepthBackProjection.project(frame))
    }

    func cloud() -> RerunDenseCloud? { fusion.count > 0 ? fusion.cloud() : nil }
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

/// Layout of the capture chrome: `DESIGN.md` AR Overlay Cards over the camera, sized for a
/// phone filmed from about a metre.
enum CaptureTokens {
    /// Free space kept for the host's title row and dock.
    static let topReserve: CGFloat = 110
    static let bottomReserve: CGFloat = 140
    static let maxWidth: CGFloat = 480
    /// A figure you can read on a phone filmed from a metre: 44 pt digits are ~7 mm tall on
    /// an iPhone 17 Pro, the size of a 30 pt headline on a laptop seen from the same distance.
    static let figure = Font.system(size: 44, weight: .bold, design: .rounded)
    static let figureLabel = Font.system(size: 15, weight: .bold)
    static let clock = Font.system(size: 22, weight: .bold, design: .rounded)
    static let swatch: CGFloat = 10
    /// The shutter: bigger than a touch target, so Record and Stop read on film too.
    static let shutterSize: CGFloat = 76
    static let shutterRing: CGFloat = 4
    static let stopGlyph: CGFloat = 26
    static let recDot: CGFloat = 14
    /// `shadow-lg` dark — 0 12px 40px rgba(0,0,0,0.5).
    static let shadow = Color.black.opacity(0.5)
    static let shadowRadius: CGFloat = 20
    static let shadowY: CGFloat = 12
    /// Recording is `danger` red, and only recording.
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

/// The four figures, big: what the recorder holds, growing as the phone moves. Each swatch is
/// the colour that figure is drawn in over the camera.
private struct CaptureReadout: View {
    let model: RerunLiveCaptureModel

    private typealias Palette = SceneViewTokens.DebugView

    var body: some View {
        let stats = model.stats
        VStack(alignment: .leading, spacing: SceneViewTokens.Space.sm) {
            status
            Text(model.supportsLiDAR
                 ? (model.denseIsFull ? "LiDAR · Dense point limit reached" : "LiDAR · Depth scan")
                 : "No LiDAR · Sparse scan")
                .font(CaptureTokens.figureLabel)
                .foregroundStyle(SceneViewTokens.ARChrome.onScrimDim)
            Grid(alignment: .leading, horizontalSpacing: SceneViewTokens.Space.md, verticalSpacing: SceneViewTokens.Space.sm) {
                GridRow {
                    Figure(value: String(format: "%.1f m", stats.pathLength), label: "Path walked",
                           swatch: Palette.color(Palette.trailNew))
                    Figure(value: (model.supportsLiDAR ? model.denseCount : stats.points).formatted(),
                           label: model.supportsLiDAR ? "Dense points" : "Points",
                           swatch: Palette.color(Palette.livePoint))
                }
                GridRow {
                    Figure(value: "\(stats.planes)", label: stats.planes == 1 ? "Surface" : "Surfaces",
                           swatch: Palette.color(Palette.liveFloorOutline))
                    Figure(value: "\(stats.keyframes)", label: stats.keyframes == 1 ? "Photo" : "Photos",
                           swatch: SceneViewTokens.ARChrome.onScrim)
                }
            }
            .animation(SceneViewTokens.Spring.animation, value: stats)
        }
        .padding(SceneViewTokens.Space.md)
        .frame(maxWidth: .infinity, alignment: .leading)
        .modifier(CaptureCard())
        .accessibilityElement(children: .combine)
    }

    private var status: some View {
        HStack(spacing: SceneViewTokens.Space.sm) {
            if model.phase == .recording {
                RecordingDot()
                Text("REC")
                    .font(CaptureTokens.clock)
                    .foregroundStyle(CaptureTokens.recording)
            } else {
                Text(model.phase == .saving ? "Saving" : "Ready")
                    .font(CaptureTokens.clock)
                    .foregroundStyle(SceneViewTokens.ARChrome.onScrim)
            }
            Spacer(minLength: SceneViewTokens.Space.sm)
            Text(Self.clock(model.stats.duration))
                .font(CaptureTokens.clock)
                .monospacedDigit()
                .foregroundStyle(SceneViewTokens.ARChrome.onScrim)
        }
    }

    /// `m:ss`.
    static func clock(_ seconds: TimeInterval) -> String {
        let whole = max(0, Int(seconds))
        return String(format: "%d:%02d", whole / 60, whole % 60)
    }
}

private struct Figure: View {
    let value: String
    let label: String
    let swatch: Color

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(value)
                .font(CaptureTokens.figure)
                .monospacedDigit()
                .contentTransition(.numericText())
                .foregroundStyle(SceneViewTokens.ARChrome.onScrim)
                .lineLimit(1)
                .minimumScaleFactor(0.6)
            HStack(spacing: SceneViewTokens.Space.xs) {
                Circle()
                    .fill(swatch)
                    .frame(width: CaptureTokens.swatch, height: CaptureTokens.swatch)
                    .accessibilityHidden(true)
                Text(label)
                    .font(CaptureTokens.figureLabel)
                    .foregroundStyle(SceneViewTokens.ARChrome.onScrim)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
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

/// The shutter and the privacy line.
private struct CaptureControls: View {
    let model: RerunLiveCaptureModel
    let onShutter: () -> Void

    var body: some View {
        HStack(spacing: SceneViewTokens.Space.md) {
            VStack(alignment: .leading, spacing: SceneViewTokens.Space.xs) {
                Text(title)
                    .font(SceneViewTokens.TypeScale.title)
                    .foregroundStyle(SceneViewTokens.ARChrome.onScrim)
                Label("Everything stays on your iPhone.", systemImage: "lock.fill")
                    .font(SceneViewTokens.TypeScale.captionSemibold)
                    .foregroundStyle(SceneViewTokens.ARChrome.onScrimDim)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            shutter
        }
        .padding(SceneViewTokens.Space.md)
        .modifier(CaptureCard())
    }

    private var title: String {
        switch model.phase {
        case .idle: "Record"
        case .recording: "Stop to replay"
        case .saving: "Saving…"
        }
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
        .buttonStyle(.plain)
        .disabled(model.phase == .saving)
        .accessibilityLabel(model.phase == .recording ? "Stop recording" : "Start recording")
    }
}

private struct CaptureHint: View {
    let text: String
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        Text(text)
            .font(SceneViewTokens.TypeScale.bodySemibold)
            .foregroundStyle(SceneViewTokens.ARChrome.onScrim)
            .multilineTextAlignment(.center)
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
            .padding(.top, CaptureTokens.topReserve)
            .padding(.bottom, CaptureTokens.bottomReserve)
            .accessibilityElement(children: .combine)
        }
    }
}

#endif

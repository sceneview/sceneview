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
// — the path walked, every voxel the feature points filled, the planes tracked now — and, while
// a scan runs, rebuilt in 3D on a glass card the camera shows through (`ScanStage`). One line
// counts the scan against what it can hold (`ScanHud`) and says so before a limit stops it
// (`ScanNotice`); the rest of the figures are rows of the host's settings sheet
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
    private let sideInset: CGFloat
    private let onFinish: (RerunCapturePack) -> Void
    private let onStatusChange: (RerunLiveStatus) -> Void
    #if DEBUG
    /// QA only (`-rerunState record…`): the screen as a scan in progress leaves it, fed by the
    /// bundled session where the simulator has no camera. Never set in a release build.
    var qa: RerunLiveQAScene?
    @State private var backdrop: CGImage?
    #endif
    @State private var model = RerunLiveCaptureModel()
    @State private var isOnScreen = false
    /// The 3D card takes the row. Back to the small card with every new scan.
    @State private var stageExpanded = false
    /// How to stop is said for the first seconds of a scan, then gives the camera its height back.
    @State private var stopHinting = false
    @Environment(\.verticalSizeClass) private var verticalSizeClass

    /// `topInset` and `bottomInset` are what the host's title row and dock take of the window,
    /// `sideInset` what the window keeps clear on its sides (a phone on its side). `onFinish`
    /// receives the capture when the user stops a recording that holds at least one camera
    /// pose. `onStatusChange` hands the host the scan's phase and counts as they move: it shows
    /// them, and puts away whatever would end a recording by accident.
    init(topInset: CGFloat, bottomInset: CGFloat, sideInset: CGFloat = 0,
         onStatusChange: @escaping (RerunLiveStatus) -> Void = { _ in },
         onFinish: @escaping (RerunCapturePack) -> Void) {
        self.topInset = topInset
        self.bottomInset = bottomInset
        self.sideInset = sideInset
        self.onStatusChange = onStatusChange
        self.onFinish = onFinish
    }

    var body: some View {
        stage
            .onChange(of: model.status, initial: true) { _, status in onStatusChange(status) }
            .onChange(of: model.phase) { _, phase in
                if phase == .idle { stageExpanded = false }
            }
            .task(id: model.phase) {
                stopHinting = model.phase == .recording
                guard stopHinting else { return }
                try? await Task.sleep(for: .seconds(CaptureTokens.stopHintSeconds))
                if !Task.isCancelled { stopHinting = false }
            }
    }

    @ViewBuilder private var stage: some View {
        #if DEBUG
        if let qa { seeded(qa) } else { camera }
        #else
        camera
        #endif
    }

    @ViewBuilder private var camera: some View {
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
        .onAppear { isOnScreen = true }
        .onDisappear {
            isOnScreen = false
            model.discardRecording()
            onStatusChange(RerunLiveStatus())
        }
        #endif
    }

    #if DEBUG
    /// The chrome over the photo the bundled session took at that moment, standing in for the
    /// camera.
    private func seeded(_ scene: RerunLiveQAScene) -> some View {
        ZStack {
            Color.clear
                .overlay {
                    if let backdrop {
                        Image(decorative: backdrop, scale: 1).resizable().scaledToFill()
                    } else {
                        SceneViewTokens.Stage.background
                    }
                }
                .clipped()
                .ignoresSafeArea()

            chrome
        }
        .task {
            let fraction = RerunShowcaseDemo.qaFraction
            let sample = await Task.detached(priority: .userInitiated) {
                RerunLiveQAScene.sample(at: fraction)
            }.value
            guard let sample else { return }
            backdrop = sample.photo.flatMap(RerunReplayMedia.decodeFull)
            model.seed(frame: sample.frame, stats: scene.stats(sample.frame))
        }
    }
    #endif

    /// The scan's line and its 3D card under the title row while a scan runs; the shutter at
    /// the bottom and, while there is something to say, one card of copy above it.
    private var chrome: some View {
        ZStack {
            if model.phase != .idle {
                live.transition(.opacity)
            }
            VStack(spacing: SceneViewTokens.Space.md) {
                Spacer(minLength: topInset)
                if let word, !wordBesideStage {
                    CaptureHint(text: word.text, privacy: word.privacy)
                }
                CaptureShutter(phase: model.phase) {
                    model.toggleRecording(onFinish: onFinish)
                }
            }
            .frame(maxWidth: CaptureTokens.maxWidth)
            .padding(.horizontal, SceneViewTokens.Chrome.margin)
            .padding(.bottom, bottomInset)
        }
        .animation(SceneViewTokens.Spring.fade, value: word?.text)
        .animation(SceneViewTokens.Spring.fade, value: model.phase)
    }

    /// The one card of copy above the shutter: what Record does while idle, how to stop for
    /// the first seconds of a scan, a wait while the scan is packed.
    private var word: (text: String, privacy: Bool)? {
        switch model.phase {
        case .idle:
            guard let hint = model.hint else { return nil }
            return (hint, true)
        case .recording:
            guard stopHinting else { return nil }
            return (RerunScanCopy.stopHint, false)
        case .saving:
            return (RerunScanCopy.finishing, false)
        }
    }

    /// On its side a phone has no room above the shutter that the scan's card does not reach:
    /// while the card is up, the word stands in the trailing column instead.
    private var wordBesideStage: Bool {
        verticalSizeClass == .compact && model.phase != .idle
    }

    /// The scan in progress. Upright, the line and the card share a row until a tap gives the
    /// card the row. On its side a phone has no height for that: the card keeps the leading
    /// edge under the title row, the line rides that row's trailing end in the host's chrome
    /// (`RerunCaptureStatusLine`), and the word and the limit's notice stand under it.
    @ViewBuilder private var live: some View {
        let figures = model.figures
        if verticalSizeClass == .compact {
            HStack(alignment: .top, spacing: SceneViewTokens.Space.md) {
                ScanStage(frame: model.liveFrame, revision: model.revision, expanded: nil)
                    .frame(width: SceneViewTokens.DebugView.compactCardWidth)
                    .padding(.top, topInset)
                Spacer(minLength: 0)
                VStack(alignment: .trailing, spacing: SceneViewTokens.Space.sm) {
                    ScanNoticeSlot(text: figures.notice)
                    if let word {
                        CaptureHint(text: word.text, privacy: word.privacy)
                    }
                }
                .frame(maxWidth: SceneViewTokens.DebugView.compactCardWidth, alignment: .trailing)
                .padding(.top, topInset)
            }
            .padding(.horizontal, SceneViewTokens.Chrome.margin + sideInset)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        } else {
            ScanLive(figures: figures, frame: model.liveFrame, revision: model.revision,
                     expanded: $stageExpanded)
                .frame(maxWidth: CaptureTokens.maxWidth)
                .padding(.horizontal, SceneViewTokens.Chrome.margin)
                .padding(.top, topInset)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        }
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
    /// ``stats`` against what the scan can hold.
    var figures: RerunScanFigures { RerunScanFigures(stats) }

    /// The scan as it stands, for the 3D card; `nil` with no scan. Read it when ``revision``
    /// moves: the arrays are too big to diff.
    @ObservationIgnored private(set) var liveFrame: RerunFrame?
    private(set) var revision = 0

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
        let due = frame.timestamp - lastStatsRefresh >= Self.statsInterval
        // A scan that just stopped at its limit says so on that frame.
        if due || (recorder.isFull && stats != recorder.stats) {
            lastStatsRefresh = frame.timestamp
            stats = recorder.stats
            overlay.attach(to: arView)
            overlay.update(path: recorder.pathPositions, points: recorder.voxelPoints, planes: recorder.currentPlanes)
            show(recorder.liveFrame)
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

    private func show(_ frame: RerunFrame?) {
        liveFrame = frame
        revision += 1
    }

    private func start() {
        // What is on screen is what gets recorded: models placed before Record go.
        for anchor in placed { anchor.removeFromParent() }
        placed.removeAll()
        overlay.clear()
        show(nil)
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
            self.show(nil)
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
        show(nil)
    }

    #if DEBUG
    /// QA only: the screen as a scan holding `frame` and counting `stats` leaves it.
    func seed(frame: RerunFrame, stats: RerunCaptureRecorder.Stats) {
        self.stats = stats
        hint = nil
        phase = .recording
        show(frame)
    }
    #endif

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

// MARK: - QA

#if DEBUG
/// The recording screen's QA states (`-rerunState <raw value>`, debug builds only). The room,
/// its surfaces, the path and the photo behind are the bundled session's at
/// ``RerunShowcaseDemo/qaFraction``; the states that show a limit set the one figure that
/// reaches it, since no scan can be walked on a simulator.
enum RerunLiveQAScene: String, Sendable {
    /// A scan in progress, no limit in sight.
    case steady = "record"
    /// Points past ``RerunScanLimits/nearShare`` of their budget.
    case near = "record-near"
    /// Points at their budget.
    case full = "record-full"
    /// The clock past ``RerunScanLimits/nearShare`` of the time limit.
    case time = "record-time"

    /// The bundled session's frame at `fraction` of its length, and the photo taken there.
    nonisolated static func sample(at fraction: Float) -> (frame: RerunFrame, photo: Data?)? {
        guard let pack = try? RerunPack.loadShowcase() else { return nil }
        let frame = pack.trace.frameAt(pack.trace.duration * fraction)
        return (frame, frame.image.flatMap { pack.bytes(for: $0) })
    }

    func stats(_ frame: RerunFrame) -> RerunCaptureRecorder.Stats {
        var stats = RerunCaptureRecorder.Stats()
        stats.duration = TimeInterval(frame.time)
        stats.keyframes = frame.keyframes.count
        stats.planes = frame.planes.count
        stats.points = frame.mapPointCount
        stats.surfaceArea = RerunCaptureMath.surfaceArea(frame.planes.map(\.polygon))
        switch self {
        case .steady: break
        case .near: stats.points = stats.pointBudget * 85 / 100
        case .full: stats.points = stats.pointBudget
        case .time: stats.duration = stats.durationBudget * 0.84
        }
        return stats
    }
}
#endif

// MARK: - Live 3D card

/// The scan drawn in 3D as it grows, in the replay's own geometry and colours (`RerunGeometry`,
/// `DESIGN.md` "AR Debug View"): the planes, the path, where each photo was taken, the camera
/// where it is now and the points in the colours the camera saw. No ground, no grid: the card
/// is glass, the camera shows through.
///
/// A part is rebuilt when what it was built from changes — a new frame from the recorder, a
/// zoom step of the style.
@MainActor
final class RerunLiveStageRenderer {
    /// The small card's points against the replay's: there the room is a few hundred pixels.
    static let finePointScale: Float = 0.6

    private static let drawn: [RerunLayer] =
        [.planeFloor, .planeWall, .planeOther, .outlineFloor, .outlineWall, .outlineOther]
        + RerunLayer.trailSteps + [.trailHead, .keyframes, .frustum]

    var orbit = RerunOrbitController(drift: false)
    /// The card is small: finer points.
    var fine = false

    private struct Built: Equatable {
        var style: RerunStyle?
        var planes: [RerunPlane] = []
        var trail = 0
        var keyframes = 0
        var camera: RerunPose?
        var points = 0
        var fine = false
    }

    private var viewport = CGSize(width: 1, height: 1)
    private var scale: CGFloat = 3
    private lazy var root = Entity()
    private lazy var camera = PerspectiveCamera()
    private var subscription: EventSubscription?
    private lazy var sortGroup = ModelSortGroup(depthPass: nil)
    private var layers: [RerunLayer: ModelEntity] = [:]
    private var points: ModelEntity?
    private var frame: RerunFrame?
    private var subject: RerunSubject?
    private var built = Built()

    func install(in content: inout RealityViewCameraContent) {
        camera.components.set(PerspectiveCameraComponent(near: 0.02, far: 200,
                                                         fieldOfViewInDegrees: RerunFraming.verticalFov,
                                                         fieldOfViewOrientation: .vertical))
        for layer in Self.drawn {
            layers[layer] = makeEntity(RerunStageRenderer.flatMaterial(RerunStageRenderer.paint(layer)),
                                       order: RerunStageRenderer.order(layer))
        }
        points = makeEntity(Self.pointMaterial(nil), order: RerunStageRenderer.Order.points)
        content.add(root)
        content.add(camera)
        subscription = content.subscribe(to: SceneEvents.Update.self) { [weak self] event in
            MainActor.assumeIsolated { self?.update(Float(event.deltaTime)) }
        }
        update(0)
    }

    func resize(_ size: CGSize, scale: CGFloat) {
        guard size.width > 0, size.height > 0 else { return }
        viewport = size
        self.scale = scale
    }

    /// The scan as it stands now; `nil` clears the card for the next one.
    func show(_ frame: RerunFrame?) {
        self.frame = frame
        subject = frame.flatMap(RerunGeometry.subject)
        guard frame == nil else { return }
        orbit = RerunOrbitController(drift: false)
        built = Built()
        for entity in layers.values { entity.isEnabled = false }
        points?.isEnabled = false
    }

    func recenter() { orbit.recenter() }

    private var heightPixels: Float { Float(viewport.height * scale) }

    /// The margin the room keeps to the card's edges, as shares of the card.
    private var inset: SIMD2<Float> {
        let margin = SceneViewTokens.Space.sm
        return SIMD2(Float(margin / max(viewport.width, 1)), Float(margin / max(viewport.height, 1)))
    }

    private func update(_ delta: Float) {
        guard let frame else { return }
        // The room is framed whole as it grows, until a finger takes the camera.
        let aspect = Float(viewport.width / max(viewport.height, 1))
        let fit = RerunFraming.fit(subject, azimuth: orbit.home.azimuth, elevation: orbit.homeElevation,
                                   aspect: aspect, inset: inset)
        if orbit.following { orbit.home = fit.pose }
        if !orbit.hasFramedContent, subject != nil {
            orbit.hasFramedContent = true
            orbit.snap(to: fit.pose)
        }
        orbit.update(delta: delta)
        let (eye, target) = orbit.eyeAndTarget(lift: fit.lift, heightPixels: heightPixels)
        camera.look(at: target, from: eye, relativeTo: nil)

        sync(frame, style: RerunStyle.forOrbit(distance: orbit.pose.distance, verticalFov: RerunFraming.verticalFov,
                                               heightPixels: heightPixels))
    }

    private func sync(_ frame: RerunFrame, style: RerunStyle) {
        let restyled = built.style != style
        built.style = style
        var out: [RerunLayer: RerunMesh] = [:]
        var touched: [RerunLayer] = []

        if restyled || built.planes != frame.planes {
            built.planes = frame.planes
            RerunGeometry.buildPlanes(frame.planes, style: style, textured: { _ in false }, into: &out)
            touched += [.planeFloor, .planeWall, .planeOther, .outlineFloor, .outlineWall, .outlineOther]
        }
        if restyled || built.trail != frame.trail.count {
            built.trail = frame.trail.count
            RerunGeometry.buildTrail(frame.trail, style: style, into: &out)
            touched += RerunLayer.trailSteps + [.trailHead]
        }
        if restyled || built.camera != frame.camera || built.keyframes != frame.keyframes.count {
            built.camera = frame.camera
            built.keyframes = frame.keyframes.count
            RerunGeometry.buildCamera(frame, style: style, lens: .default, into: &out)
            touched += [.keyframes, .frustum]
        }
        for layer in touched { upload(out[layer], to: layers[layer]) }

        let count = min(frame.mapPointCount, RerunPointAtlas.capacity)
        let grown = built.points != count
        guard let points, grown || restyled || built.fine != fine else { return }
        built.points = count
        built.fine = fine
        // One texel per point: a cloud that grew needs its colours again.
        if grown { points.model?.materials = [Self.pointMaterial(RerunStageRenderer.atlasTexture(frame))] }
        var mesh = RerunMesh()
        let radius = style.mapPointRadius * RerunGeometry.pointScale * (fine ? Self.finePointScale : 1)
        RerunGeometry.addColoredPoints(&mesh, frame.mapPoints, range: 0..<count, radius: radius)
        upload(mesh, to: points)
    }

    private func upload(_ mesh: RerunMesh?, to entity: ModelEntity?) {
        guard let entity else { return }
        guard let mesh, let resource = RerunRealityKit.resource(mesh) else {
            entity.isEnabled = false
            return
        }
        entity.model?.mesh = resource
        entity.isEnabled = true
    }

    private func makeEntity(_ material: some RealityKit.Material, order: Int32) -> ModelEntity {
        let entity = ModelEntity(mesh: .generatePlane(width: 0.001, depth: 0.001), materials: [material])
        entity.components.set(ModelSortGroupComponent(group: sortGroup, order: order))
        entity.isEnabled = false
        root.addChild(entity)
        return entity
    }

    private static func pointMaterial(_ atlas: TextureResource?) -> UnlitMaterial {
        var material = UnlitMaterial(applyPostProcessToneMap: false)
        if let atlas {
            material.color = .init(tint: .white, texture: .init(atlas, sampler: RerunRealityKit.nearest))
        }
        material.faceCulling = .none
        return material
    }
}

// MARK: - Chrome

/// Layout of the capture chrome: `DESIGN.md` AR Overlay Cards over the camera.
private enum CaptureTokens {
    static let maxWidth = SceneViewTokens.ARChrome.cardMaxWidth
    /// The shutter: bigger than a touch target, so Record and Stop read on film too.
    static let shutterSize: CGFloat = 76
    static let shutterRing: CGFloat = 4
    static let stopGlyph: CGFloat = 26
    /// The dot that says "recording" on the scan's line (Android's `ScanDotSize`).
    static let recDot = SceneViewTokens.Space.sm + SceneViewTokens.Space.xs / 2
    /// The glyph that says the 3D card grows (Android's `StageGlyphSize`).
    static let stageGlyph = SceneViewTokens.Space.md
    /// Where the 3D card starts from as a scan begins (Android's `ENTER_SCALE`).
    static let enterScale: CGFloat = 0.92
    /// How long a scan says how to stop (Android's `STOP_HINT_MS`).
    static let stopHintSeconds: Double = 8
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

/// The scan in progress on an upright phone: its line, its 3D card and, once a limit is in
/// play, the word that says so. They arrive together as the scan starts.
private struct ScanLive: View {
    let figures: RerunScanFigures
    let frame: RerunFrame?
    let revision: Int
    @Binding var expanded: Bool
    @State private var entered = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        ScanLiveLayout(grow: expanded ? 1 : 0, worded: figures.notice != nil) {
            ScanHud(figures: figures)
                .opacity(entered ? 1 : 0)
            ScanStage(frame: frame, revision: revision, expanded: expanded) { expanded = $0 }
                .opacity(entered ? 1 : 0)
                .scaleEffect(entered ? 1 : CaptureTokens.enterScale, anchor: .topTrailing)
            ScanNoticeSlot(text: figures.notice)
        }
        .animation(reduceMotion ? nil : SceneViewTokens.Spring.animation, value: expanded)
        .animation(SceneViewTokens.Spring.fade, value: figures.notice)
        .onAppear {
            withAnimation(reduceMotion ? nil : SceneViewTokens.Spring.animation) { entered = true }
        }
    }
}

/// Places the scan's line, its 3D card and the limit's word — in that order — the way Android's
/// `ScanLive` does. Small, the card stands at the trailing end of the line's row, the word
/// under the line beside it. Grown (`grow` 1), it takes the row under both. A line too wide to
/// share its row (large type) sends the card under it at either size.
private struct ScanLiveLayout: Layout {
    var grow: CGFloat
    /// There is a word to place.
    var worded: Bool

    var animatableData: CGFloat {
        get { grow }
        set { grow = newValue }
    }

    private struct Plan {
        var size: CGSize
        var line: CGSize
        var wordWidth: CGFloat
        var card: CGRect
    }

    private func plan(_ full: CGFloat, _ subviews: Subviews) -> Plan {
        let gap = SceneViewTokens.Space.sm
        let line = subviews[0].sizeThatFits(ProposedViewSize(width: full, height: nil))
        let small = (full * SceneViewTokens.DebugView.liveCardShare).rounded()
        let beside = line.width + gap + small <= full
        let share = min(max(grow, 0), 1)
        let width = small + ((full - small) * share).rounded()
        let height = (width / SceneViewTokens.DebugView.liveCardAspect).rounded()
        let wordWidth = beside ? full - small - gap : full
        let word = worded ? subviews[2].sizeThatFits(ProposedViewSize(width: wordWidth, height: nil)) : .zero
        let head = line.height + (word.height > 0 ? gap + word.height : 0)
        let drop = head + gap
        let top = beside ? (drop * share).rounded() : drop
        return Plan(size: CGSize(width: full, height: max(head, top + height)), line: line, wordWidth: wordWidth,
                    card: CGRect(x: full - width, y: top, width: width, height: height))
    }

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        guard subviews.count == 3 else { return .zero }
        return plan(proposal.replacingUnspecifiedDimensions().width, subviews).size
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        guard subviews.count == 3 else { return }
        let plan = plan(bounds.width, subviews)
        let gap = SceneViewTokens.Space.sm
        subviews[0].place(at: bounds.origin, anchor: .topLeading,
                          proposal: ProposedViewSize(width: bounds.width, height: nil))
        subviews[1].place(at: CGPoint(x: bounds.minX + plan.card.minX, y: bounds.minY + plan.card.minY),
                          anchor: .topLeading, proposal: ProposedViewSize(plan.card.size))
        subviews[2].place(at: CGPoint(x: bounds.minX, y: bounds.minY + plan.line.height + gap), anchor: .topLeading,
                          proposal: ProposedViewSize(width: plan.wordWidth, height: nil))
    }
}

/// A scan in progress says one line: the red dot, the clock, and its points against what it can
/// hold — `4.8k points`, then `9.6k / 12k` once the limit is in sight, then `12k · full` in
/// amber. On the AR scrim: it is read over a moving camera.
private struct ScanHud: View {
    let figures: RerunScanFigures
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        let clock = RerunFormat.clock(Float(figures.duration))
        GlassPill {
            HStack(spacing: SceneViewTokens.Space.sm) {
                RecordingDot()
                Text(clock)
                    .font(SceneViewTokens.TypeScale.card)
                    .monospacedDigit()
                    .contentTransition(.numericText())
                    .foregroundStyle(figures.timeFull ? SceneViewTokens.ARChrome.warning : SceneViewTokens.ARChrome.onScrim)
                Text(RerunScanCopy.pointsLine(figures.points, figures.pointBudget))
                    .font(SceneViewTokens.TypeScale.caption)
                    .monospacedDigit()
                    .contentTransition(.numericText())
                    .foregroundStyle(figures.pointsFull ? SceneViewTokens.ARChrome.warning : SceneViewTokens.ARChrome.onScrim)
            }
            .lineLimit(1)
        }
        .environment(\.arChromeGround, SceneViewTokens.ARChrome.scrim(scheme))
        .environment(\.colorScheme, .dark)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Scanning for \(clock), \(RerunScanCopy.pointsSpoken(figures.points, figures.pointBudget))")
        .accessibilityIdentifier("rerun-scan-status")
    }
}

/// The scan's line where the host's title row carries it: a phone on its side.
struct RerunCaptureStatusLine: View {
    let status: RerunLiveStatus

    var body: some View {
        ScanHud(figures: RerunScanFigures(status.stats))
    }
}

/// Where the limit's word stands: nothing while no limit is in play, and the word fades.
private struct ScanNoticeSlot: View {
    let text: String?

    var body: some View {
        ZStack {
            if let text {
                ScanNotice(text: text).transition(.opacity)
            }
        }
        .animation(SceneViewTokens.Spring.fade, value: text)
    }
}

/// The one thing to know mid-scan, in the guidance amber: a limit reached, or in sight.
private struct ScanNotice: View {
    let text: String
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        Text(text)
            .font(SceneViewTokens.TypeScale.caption)
            .foregroundStyle(SceneViewTokens.ARChrome.warning)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.horizontal, SceneViewTokens.Space.md)
            .padding(.vertical, SceneViewTokens.Space.sm)
            .background(
                SceneViewTokens.ARChrome.scrim(scheme),
                in: RoundedRectangle(cornerRadius: SceneViewTokens.Radius.md, style: .continuous)
            )
            .accessibilityIdentifier("rerun-scan-notice")
    }
}

/// The scan growing in 3D while it records, on glass: the camera it is made with shows
/// through, behind the stage's tint, so the points keep the dark ground they are coloured for.
/// The same room the replay opens on once the scan stops.
///
/// `expanded` is `nil` where the card has one size (a phone on its side). Otherwise the small
/// card is one button that grows it, and the grown one turns under a finger and carries the
/// button that brings it back.
private struct ScanStage: View {
    let frame: RerunFrame?
    let revision: Int
    let expanded: Bool?
    var onExpandedChange: (Bool) -> Void = { _ in }

    @Environment(\.displayScale) private var displayScale
    @Environment(\.colorScheme) private var scheme
    @State private var renderer = RerunLiveStageRenderer()
    @State private var lastTranslation: CGSize?

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: SceneViewTokens.Radius.lg, style: .continuous)
        room
            .aspectRatio(SceneViewTokens.DebugView.liveCardAspect, contentMode: .fit)
            .background(SceneViewTokens.DebugView.color(SceneViewTokens.DebugView.liveGlass))
            .clipShape(shape)
            .glassBackground(in: shape)
            .overlay { control }
            // Glass over a camera is dark in both themes, like the rest of the AR chrome.
            .environment(\.colorScheme, .dark)
            .onChange(of: revision, initial: true) { _, _ in renderer.show(frame) }
            .onChange(of: expanded, initial: true) { old, new in
                renderer.fine = new == false
                if old == true, new == false { renderer.recenter() }
            }
            .accessibilityIdentifier("rerun-scan-stage")
    }

    private var room: some View {
        GeometryReader { proxy in
            RealityView { content in
                content.camera = .virtual
                renderer.install(in: &content)
            }
            .onAppear { renderer.resize(proxy.size, scale: displayScale) }
            .onChange(of: proxy.size) { _, size in renderer.resize(size, scale: displayScale) }
        }
        .contentShape(Rectangle())
        .gesture(turn, including: expanded == true ? .all : .subviews)
        .accessibilityElement()
        .accessibilityLabel(RerunScanCopy.stageLabel)
        // Small, the button over the card says it.
        .accessibilityHidden(expanded == false)
    }

    private var turn: some Gesture {
        DragGesture(minimumDistance: 2)
            .onChanged { value in
                let last = lastTranslation ?? .zero
                if lastTranslation == nil { renderer.orbit.dragBegan() }
                lastTranslation = value.translation
                renderer.orbit.dragged(dx: Float((value.translation.width - last.width) * displayScale),
                                       dy: Float((value.translation.height - last.height) * displayScale))
            }
            .onEnded { _ in
                lastTranslation = nil
                renderer.orbit.dragEnded()
            }
    }

    @ViewBuilder private var control: some View {
        switch expanded {
        case .some(false):
            Button {
                onExpandedChange(true)
            } label: {
                Color.clear
                    .contentShape(Rectangle())
                    .overlay(alignment: .bottomTrailing) {
                        Image(systemName: "arrow.up.left.and.arrow.down.right")
                            .font(.system(size: CaptureTokens.stageGlyph, weight: .semibold))
                            .foregroundStyle(SceneViewTokens.Glass.onGlass)
                            .padding(SceneViewTokens.Space.sm)
                    }
            }
            .buttonStyle(.plain)
            .accessibilityLabel(RerunScanCopy.stageLabel)
            .accessibilityHint(RerunScanCopy.stageExpand)
        case .some(true):
            GlassIconButton(icon: "arrow.down.right.and.arrow.up.left", label: RerunScanCopy.stageCollapse) {
                onExpandedChange(false)
            }
            .environment(\.arChromeGround, SceneViewTokens.ARChrome.scrim(scheme))
            .padding(SceneViewTokens.Space.xs)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topTrailing)
        case .none:
            EmptyView()
        }
    }
}

/// What the settings sheet reads out of a scan, once: each figure against what the scan can
/// hold. The dots are the colours those figures are drawn in over the camera.
struct RerunCaptureSettings: View {
    let status: RerunLiveStatus

    private typealias Palette = SceneViewTokens.DebugView

    var body: some View {
        let figures = RerunScanFigures(status.stats)
        VStack(alignment: .leading, spacing: 0) {
            Text(RerunScanCopy.figuresTitle)
                .font(SceneViewTokens.TypeScale.captionSemibold)
                .foregroundStyle(.secondary)
                .accessibilityAddTraits(.isHeader)
            RerunSheetFigure(label: "Points", value: RerunScanCopy.budgeted(figures.points, figures.pointBudget)) {
                RerunLayerDot(color: Palette.livePoint)
            }
            RerunSheetFigure(label: "Surfaces found", value: RerunFormat.area(figures.surfaceMetres2)) {
                RerunLayerDot(color: Palette.liveFloorOutline)
            }
            RerunSheetFigure(label: "Photos", value: RerunScanCopy.budgeted(figures.photos, figures.photoBudget)) {
                Image(systemName: "photo").foregroundStyle(.secondary)
            }
            RerunSheetFigure(label: "Scan type", value: RerunScanCopy.tierSparse) {
                Image(systemName: "circle.dotted").foregroundStyle(.secondary)
            }
        }
        .animation(SceneViewTokens.Spring.animation, value: figures)
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

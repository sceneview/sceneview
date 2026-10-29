import SwiftUI
import RealityKit
import SceneViewSwift

/// **Camera Path** — a keyframed camera move played round a subject.
///
/// Three shots, each one of `CameraPosePath`'s presets:
/// - **Orbit**: one turn round the subject, framed from its measured bounds and
///   SceneView's 60° vertical field of view, so the whole subject stays in frame at
///   every azimuth. Loops.
/// - **Travelling**: a sideways track past the subject, the camera's heading held.
/// - **Dolly**: a push in toward the subject along the line of sight.
///
/// A `CameraPathPlayer` owns the playhead. A `TimelineView` ticks it once per
/// display frame while it plays, and the scene writes `player.pose` to the camera
/// through `.cameraPose(_:)`. The glass timeline bar plays, pauses and scrubs the
/// same playhead, so a scrubbed frame and a played frame at the same time are the
/// same pose.
///
/// The path owns the camera: `cameraGesturesEnabled(false)` keeps a drag from
/// fighting the scripted pose, and `autoCenterContent(false)` keeps the framing
/// pass from re-centring on the subject.
///
/// In QA mode the move starts paused at its first keyframe, so a capture is
/// deterministic.
struct CameraPathDemo: View {
    @StateObject private var player = CameraPathPlayer(timeline: CameraPathStage.placeholder)
    @State private var stage = CameraPathStage()
    @State private var shot: CameraPathShot = .orbit
    @State private var subject: CameraPathSubject = .helmet
    @State private var aspect: Float = CameraPathStage.fallbackAspect

    @AppStorage(DeepLinkRouter.qaModeDefaultsKey) private var qaMode: Bool = false
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.analyticsSampleId) private var analyticsSampleId

    var body: some View {
        ZStack {
            SceneViewTokens.Stage.pipBackdropColor
            CameraPathStageView(stage: stage, player: player)
                .onGeometryChange(for: CGSize.self) { $0.size } action: { size in
                    guard size.width > 0, size.height > 0 else { return }
                    aspect = Float(size.width / size.height)
                }
            if stage.failed.contains(subject) {
                ContentUnavailableView("\(subject.label) could not load",
                                       systemImage: "exclamationmark.triangle")
                    .foregroundStyle(SceneViewTokens.Glass.onGlass)
            } else if !stage.isReady(subject) {
                ProgressView("Preparing the \(subject.label.lowercased())…")
                    .tint(SceneViewTokens.Glass.onGlass)
                    .foregroundStyle(SceneViewTokens.Glass.onGlass)
            }
        }
        .ignoresSafeArea()
        .demoChrome(
            dock: [
                DockItem(icon: "cube", label: "Next subject", caption: subject.label) {
                    subject = subject.next
                },
            ],
            onReset: { reset() },
            accessory: {
                VStack(spacing: SceneViewTokens.Space.sm) {
                    DemoHint(shot.hint)
                    DemoOptionStrip(CameraPathShot.allCases, selection: $shot) { $0.label }
                    CameraPathTimelineBar(player: player, enabled: stage.isReady(subject))
                }
            },
            controls: { controls }
        )
        .task(id: subject) {
            await stage.show(subject, sampleId: analyticsSampleId ?? "camera-path")
        }
        .onChange(of: timelineKey, initial: true) { _, _ in rebuildTimeline() }
        .onAppear { stage.setDark(colorScheme == .dark) }
        .onChange(of: colorScheme) { _, scheme in stage.setDark(scheme == .dark) }
    }

    // MARK: Timeline

    /// Everything the path is built from: a change to any of these rebuilds it.
    private struct TimelineKey: Equatable {
        let shot: CameraPathShot
        let subject: CameraPathSubject
        let aspect: Float
        let ready: Bool
    }

    private var timelineKey: TimelineKey {
        TimelineKey(shot: shot, subject: subject, aspect: aspect, ready: stage.isReady(subject))
    }

    /// Swaps the player's path and starts it from 0 — paused at the first key in
    /// QA mode, and while the subject is still loading, so the first pass is never
    /// round an empty stage.
    private func rebuildTimeline() {
        stage.focus = stage.bounds[subject]?.center ?? CameraPathStage.fallbackBounds.center
        player.timeline = stage.timeline(for: shot, subject: subject, aspect: aspect)
        if stage.isReady(subject) && !qaMode {
            player.restart()
        } else {
            player.pause()
            player.seek(to: 0)
        }
    }

    private func reset() {
        let unchanged = shot == .orbit && subject == .helmet
        shot = .orbit
        subject = .helmet
        // Nothing changed, so the key will not fire: start again from 0 by hand.
        if unchanged { rebuildTimeline() }
    }

    // MARK: Controls sheet

    @ViewBuilder
    private var controls: some View {
        VStack(alignment: .leading, spacing: SceneViewTokens.Space.md) {
            Text("A keyframed camera move, played by a player you can pause and scrub. Each shot is one of the library's presets: an orbit framed from the subject's bounds, a sideways travelling, a dolly in.")
                .font(SceneViewTokens.TypeScale.body)
                .foregroundStyle(SceneViewTokens.HomeColor.onSurfaceDim)
                .fixedSize(horizontal: false, vertical: true)
            Text("Shot")
                .font(SceneViewTokens.TypeScale.bodySemibold)
                .accessibilityAddTraits(.isHeader)
            Picker("Shot", selection: $shot) {
                ForEach(CameraPathShot.allCases) { Text($0.label).tag($0) }
            }
            .pickerStyle(.segmented)
            Text("Subject")
                .font(SceneViewTokens.TypeScale.bodySemibold)
                .accessibilityAddTraits(.isHeader)
            Picker("Subject", selection: $subject) {
                ForEach(CameraPathSubject.allCases) { Text($0.label).tag($0) }
            }
            .pickerStyle(.segmented)
            Text("Damaged Helmet, Toy Car and Lantern: Khronos glTF Sample Assets · CC BY 4.0.")
                .font(SceneViewTokens.TypeScale.captionRegular)
                .foregroundStyle(SceneViewTokens.HomeColor.onSurfaceDim)
        }
    }
}

// MARK: - Shots and subjects

enum CameraPathShot: String, CaseIterable, Identifiable {
    case orbit, travelling, dolly

    var id: String { rawValue }

    var label: String {
        switch self {
        case .orbit: return "Orbit"
        case .travelling: return "Travelling"
        case .dolly: return "Dolly"
        }
    }

    var hint: String {
        switch self {
        case .orbit: return "One turn round the subject, framed from its bounds. Loops."
        case .travelling: return "A sideways track past the subject, heading held."
        case .dolly: return "A push in toward the subject along the line of sight."
        }
    }
}

enum CameraPathSubject: String, CaseIterable, Identifiable {
    case helmet, car, lantern

    var id: String { rawValue }

    var label: String {
        switch self {
        case .helmet: return "Helmet"
        case .car: return "Toy Car"
        case .lantern: return "Lantern"
        }
    }

    var assetName: String {
        switch self {
        case .helmet: return "khronos_damaged_helmet"
        case .car: return "khronos_toy_car"
        case .lantern: return "khronos_lantern"
        }
    }

    var next: CameraPathSubject {
        let all = Self.allCases
        return all[(all.firstIndex(of: self)! + 1) % all.count]
    }
}

// MARK: - Scene

/// The 3D view and its frame clock. Its own view, observing the player, so a
/// playhead tick re-evaluates the scene alone and not the chrome round it.
private struct CameraPathStageView: View {
    let stage: CameraPathStage
    @ObservedObject var player: CameraPathPlayer

    var body: some View {
        let pose = player.pose
        // Any focus distance keeps the eye where the path puts it; the subject's
        // depth along the view keeps the orbit pivot on it, and 1 m or more keeps
        // it inside the camera's radius clamp.
        let depth = simd_dot(stage.focus - pose.position, pose.forward)
        SceneView { root in
            stage.install(in: root)
        }
        .environment(CameraPathStage.environment)
        .cameraControls(.orbit)
        .cameraGesturesEnabled(false)
        .autoCenterContent(false)
        .cameraPose(SceneCameraPose(pose, focusDistance: max(depth, 1)))
        .accessibilityLabel("Camera path stage")
        .background {
            // Once per display frame while playing; idle when paused.
            TimelineView(.animation(paused: !player.isPlaying)) { context in
                Color.clear.onChange(of: context.date) { _, date in
                    player.advanceFrame(at: date.timeIntervalSinceReferenceDate)
                }
            }
        }
    }
}

/// Play / pause, a scrubber and an `m:ss / m:ss` clock on one glass bar — Android's
/// `CameraPathTimeline`. Dims and takes no input while the subject loads.
private struct CameraPathTimelineBar: View {
    @ObservedObject var player: CameraPathPlayer
    let enabled: Bool

    /// Dragging pauses the move so the thumb and the camera stay under the finger;
    /// releasing resumes it only when it was playing before the drag.
    @State private var resumeAfterScrub = false

    var body: some View {
        HStack(spacing: SceneViewTokens.Space.sm) {
            Button { player.toggle() } label: {
                Image(systemName: player.isPlaying ? "pause.fill" : "play.fill")
                    .font(SceneViewTokens.TypeScale.chromeLabel)
                    .frame(width: SceneViewTokens.Glass.iconButtonSize,
                           height: SceneViewTokens.Glass.iconButtonSize)
                    .contentShape(Circle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(player.isPlaying ? "Pause camera path" : "Play camera path")

            Slider(
                value: Binding(get: { Double(player.progress) },
                               set: { player.seek(to: Float($0) * player.durationSeconds) }),
                in: 0...1,
                onEditingChanged: { editing in
                    if editing {
                        if player.isPlaying {
                            resumeAfterScrub = true
                            player.pause()
                        }
                    } else {
                        if resumeAfterScrub { player.play() }
                        resumeAfterScrub = false
                    }
                }
            )
            .tint(SceneViewTokens.Glass.onGlass)
            .disabled(player.durationSeconds <= 0)
            .accessibilityLabel("Camera path timeline")
            .accessibilityValue(clock)

            Text(clock)
                .font(SceneViewTokens.TypeScale.chromeLabel.monospacedDigit())
                .padding(.trailing, SceneViewTokens.Space.sm)
                .accessibilityHidden(true)
        }
        .foregroundStyle(enabled ? SceneViewTokens.Glass.onGlass : SceneViewTokens.Glass.onGlassMuted)
        .padding(SceneViewTokens.Space.xs)
        .glassBackground(in: Capsule(), id: "camera-path-timeline")
        .disabled(!enabled)
    }

    private var clock: String {
        "\(Self.format(player.seconds)) / \(Self.format(player.durationSeconds))"
    }

    /// `m:ss`, rounded down to the second.
    static func format(_ seconds: Float) -> String {
        let whole = seconds.isFinite && seconds > 0 ? Int(seconds) : 0
        return String(format: "%d:%02d", whole / 60, whole % 60)
    }
}

// MARK: - Stage

/// The floor, one pivot per subject and the paths built from their bounds.
@MainActor
@Observable
final class CameraPathStage {
    /// Measured bounds of each loaded subject, standing on the origin.
    private(set) var bounds: [CameraPathSubject: BoundingBox] = [:]
    private(set) var failed: Set<CameraPathSubject> = []
    /// Where the camera's orbit pivot sits: the shown subject's centre.
    @ObservationIgnored var focus: SIMD3<Float> = fallbackBounds.center

    @ObservationIgnored private let floor: ModelEntity
    @ObservationIgnored private var pivots: [CameraPathSubject: Entity] = [:]
    @ObservationIgnored private var loading: Set<CameraPathSubject> = []
    @ObservationIgnored private var shown: CameraPathSubject = .helmet
    @ObservationIgnored private var dark: Bool?

    // Android's constants, value for value.
    static let subjectUnits: Float = 1
    static let orbitSeconds: Float = 16
    static let orbitElevationDegrees: Float = 18
    static let orbitStartAzimuthDegrees: Float = 30
    static let fallbackAspect: Float = 0.47
    // iOS-only shots, in the same register.
    static let travellingSeconds: Float = 8
    static let dollySeconds: Float = 8

    /// A unit box standing on the origin: the framing until a subject is measured.
    static let fallbackBounds = BoundingBox(min: SIMD3(-0.5, 0, -0.5), max: SIMD3(0.5, 1, 0.5))

    /// A one-pose path the player starts on before the first real one is built.
    static let placeholder: any CameraTimeline = CameraPosePath(keyframes: [
        CameraPoseKeyframe(pose: .lookingAt(eye: SIMD3(0, 1, 3), target: SIMD3(0, 0.5, 0)), seconds: 0),
    ])

    /// Studio light, no backdrop: the themed backdrop behind the scene shows through.
    static let environment: SceneEnvironment = {
        var studio = SceneEnvironment.studio
        studio.showSkybox = false
        return studio
    }()

    init() {
        floor = ModelEntity(mesh: .generatePlane(width: Self.floorSize, depth: Self.floorSize),
                            materials: [])
        floor.components.set(ModelSortGroupComponent(group: Self.sortGroup, order: 0))
        for subject in CameraPathSubject.allCases {
            let pivot = Entity()
            pivot.isEnabled = subject == shown
            pivots[subject] = pivot
        }
    }

    func isReady(_ subject: CameraPathSubject) -> Bool { bounds[subject] != nil }

    /// Adds the floor and every subject's pivot to the scene root. The content
    /// closure runs once per `RealityView`; re-adding re-parents.
    func install(in root: Entity) {
        root.addChild(floor)
        for subject in CameraPathSubject.allCases {
            if let pivot = pivots[subject] { root.addChild(pivot) }
        }
    }

    /// Shows one subject, loading it on first use.
    func show(_ subject: CameraPathSubject, sampleId: String) async {
        shown = subject
        for (key, pivot) in pivots { pivot.isEnabled = key == subject }
        guard bounds[subject] == nil, !failed.contains(subject), !loading.contains(subject),
              let pivot = pivots[subject] else { return }
        loading.insert(subject)
        defer { loading.remove(subject) }
        do {
            let node = try await ModelNode.load(subject.assetName)
            _ = node.scaleToUnits(Self.subjectUnits)
            // Stands on the floor: the bounds' bottom centre onto the origin.
            _ = node.centerOrigin(normalized: SIMD3(0, -1, 0))
            pivot.addChild(node.entity)
            let measured = node.entity.visualBounds(relativeTo: pivot)
            if let shadow = Self.contactShadow(footprint: max(measured.extents.x, measured.extents.z)) {
                shadow.position.y = 0.0005
                shadow.components.set(ModelSortGroupComponent(group: Self.sortGroup, order: 1))
                pivot.addChild(shadow)
            }
            bounds[subject] = measured.isEmpty ? Self.fallbackBounds : measured
        } catch is CancellationError {
            // Switched away mid-load: nothing failed, the next visit loads it.
        } catch {
            failed.insert(subject)
            DemoAnalytics.shared.log(.modelLoadFailed(sampleId: sampleId,
                                                      reason: DemoAnalytics.modelLoadReason(for: error)))
        }
    }

    /// The path for one shot round one subject, framed for the view's aspect.
    func timeline(for shot: CameraPathShot, subject: CameraPathSubject, aspect: Float) -> CameraPosePath {
        let box = bounds[subject] ?? Self.fallbackBounds
        let orbit = CameraPosePath.orbit(
            bounds: box,
            aspect: aspect,
            durationSeconds: Self.orbitSeconds,
            elevationDegrees: Self.orbitElevationDegrees,
            startAzimuthDegrees: Self.orbitStartAzimuthDegrees
        )
        let centre = box.center
        // The orbit's fitted distance: the other shots work at the same scale.
        let distance = orbit.start.map { simd_length($0.position - centre) } ?? 3
        switch shot {
        case .orbit:
            return orbit
        case .travelling:
            // Square on to the subject, a quarter of the distance to its left,
            // tracking half the distance to the right: past it, heading held.
            let side = distance * 0.25
            let eye = centre + SIMD3(-side, box.extents.y * 0.25, distance)
            let start = CameraPose.lookingAt(eye: eye, target: SIMD3(eye.x, centre.y, centre.z))
            return .travelling(from: start, distance: side * 2,
                               durationSeconds: Self.travellingSeconds)
        case .dolly:
            // From the orbit's opening angle, 1.6× its distance in to 0.7×.
            let direction = simd_normalize((orbit.start?.position ?? centre + SIMD3(0, 0, 1)) - centre)
            return .dolly(target: centre, from: centre + direction * distance * 1.6,
                          toDistance: distance * 0.7, durationSeconds: Self.dollySeconds)
        }
    }

    /// Floor and grid take the scheme's `Stage.pipFloor` / `Stage.pipGrid`.
    func setDark(_ isDark: Bool) {
        guard isDark != dark else { return }
        dark = isDark
        var material = UnlitMaterial(applyPostProcessToneMap: false)
        if let texture = Self.floorTexture(dark: isDark) {
            let sampler = MTLSamplerDescriptor()
            sampler.minFilter = .linear
            sampler.magFilter = .linear
            sampler.mipFilter = .linear
            // The camera looks along the floor: without anisotropy the grid blurs
            // into the ground a metre out.
            sampler.maxAnisotropy = 8
            material.color = .init(tint: .white, texture: .init(texture, sampler: .init(sampler)))
        } else {
            material.color = .init(tint: SceneViewTokens.Stage.pipFloor(dark: isDark))
        }
        floor.model?.materials = [material]
    }

    // MARK: Floor and shadow

    /// 8 m across, its own colour to 1.6 m from the centre, the backdrop's at 4 m.
    private static let floorSize: Float = 8
    private static let floorFadeStart: Float = 1.6
    private static let gridSpacing: Float = 0.5
    private static let gridLineWidth: Float = 0.016
    /// 1024 px for 8 m: 128 px a metre, the 16 mm grid lines two pixels wide.
    private static let floorTextureSide = 1024

    /// The contact shadow is see-through and lies a hair above the floor: it
    /// always draws after it, whichever is nearer the camera.
    private static let sortGroup = ModelSortGroup(depthPass: nil)

    private static var floorTextures: [Bool: TextureResource] = [:]

    /// The floor and its 50 cm grid, fading at the rim into the backdrop colour —
    /// opaque all the way, so the stage dissolves into the flat backdrop and no
    /// shot shows an edge.
    private static func floorTexture(dark: Bool) -> TextureResource? {
        if let cached = floorTextures[dark] { return cached }
        let side = floorTextureSide
        let rect = CGRect(x: 0, y: 0, width: side, height: side)
        let metres = CGFloat(floorSize)
        let perMetre = CGFloat(side) / metres
        let backdrop = SceneViewTokens.Stage.pipBackdrop(dark: dark)
        guard let srgb = CGColorSpace(name: CGColorSpace.sRGB),
              let context = CGContext(data: nil, width: side, height: side, bitsPerComponent: 8,
                                      bytesPerRow: 0, space: srgb,
                                      bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue),
              let fade = CGGradient(colorsSpace: srgb, colors: [
                  backdrop.withAlphaComponent(0).cgColor, backdrop.withAlphaComponent(0).cgColor,
                  backdrop.withAlphaComponent(0.65).cgColor, backdrop.cgColor,
              ] as CFArray, locations: [0, CGFloat(floorFadeStart * 2) / metres, 0.8, 1])
        else { return nil }
        context.setFillColor(SceneViewTokens.Stage.pipFloor(dark: dark).cgColor)
        context.fill(rect)
        context.setFillColor(SceneViewTokens.Stage.pipGrid(dark: dark).cgColor)
        let spacing = CGFloat(gridSpacing) * perMetre
        let width = CGFloat(gridLineWidth) * perMetre
        var offset = rect.midX.truncatingRemainder(dividingBy: spacing)
        while offset < rect.width {
            context.fill(CGRect(x: offset - width / 2, y: 0, width: width, height: rect.height))
            context.fill(CGRect(x: 0, y: offset - width / 2, width: rect.width, height: width))
            offset += spacing
        }
        let centre = CGPoint(x: rect.midX, y: rect.midY)
        context.drawRadialGradient(fade, startCenter: centre, startRadius: 0,
                                   endCenter: centre, endRadius: rect.width / 2,
                                   options: [.drawsAfterEndLocation])
        guard let image = context.makeImage(),
              let texture = try? TextureResource(image: image, withName: nil,
                                                 options: .init(semantic: .color,
                                                                mipmapsMode: .allocateAndGenerateAll))
        else { return nil }
        floorTextures[dark] = texture
        return texture
    }

    /// Radial black fade, 35 % at the centre to clear at the rim.
    private static let shadowTexture: TextureResource? = {
        let side = 128
        guard let context = CGContext(data: nil, width: side, height: side, bitsPerComponent: 8,
                                      bytesPerRow: 0, space: CGColorSpaceCreateDeviceRGB(),
                                      bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue),
              let gradient = CGGradient(colorsSpace: CGColorSpaceCreateDeviceRGB(),
                                        colors: [UIColor(white: 0, alpha: 0.35).cgColor,
                                                 UIColor(white: 0, alpha: 0).cgColor] as CFArray,
                                        locations: [0, 1]) else { return nil }
        let centre = CGPoint(x: side / 2, y: side / 2)
        context.drawRadialGradient(gradient, startCenter: centre, startRadius: 0,
                                   endCenter: centre, endRadius: CGFloat(side) / 2, options: [])
        guard let image = context.makeImage() else { return nil }
        return try? TextureResource(image: image, withName: nil,
                                    options: .init(semantic: .color, mipmapsMode: .none))
    }()

    /// A soft disc under the subject. The floor is unlit, so RealityKit's
    /// grounding shadow has nothing to land on; this stands in for it.
    private static func contactShadow(footprint: Float) -> ModelEntity? {
        guard let texture = shadowTexture, footprint.isFinite, footprint > 0 else { return nil }
        var material = UnlitMaterial(applyPostProcessToneMap: false)
        material.color = .init(tint: .white, texture: .init(texture))
        material.blending = .transparent(opacity: .init(floatLiteral: 1))
        let size = footprint * 1.3
        return ModelEntity(mesh: .generatePlane(width: size, depth: size), materials: [material])
    }
}

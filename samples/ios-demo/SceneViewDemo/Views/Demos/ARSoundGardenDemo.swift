#if os(iOS)
import SwiftUI
import RealityKit
import ARKit
import AVFoundation
import SceneViewSwift

/// AR demo — **Sound Garden**: four glowing orbs planted on the floor, each playing one part
/// of the same short song (bells, beat, pad, bass). Walking between them remixes the song: the
/// orb you walk up to gets louder, the ones you turn your back to sound muffled, and left and
/// right are heard as *places*. iOS twin of Android's `ARSoundGardenDemo`
/// (`samples/android-demo/.../demos/soundgarden/ARSoundGardenDemo.kt`): same garden, same
/// colours, same song, same distance law.
///
/// Where the platforms differ is who renders the ear. Android mixes the four parts itself
/// (pan, interaural delay, rear low-pass) into one stereo track; here every orb carries a
/// ``SpatialAudioNode`` and RealityKit renders its direction binaurally (HRTF) from the orb's
/// pose relative to the camera, with Android's 3 dB rear gain on top. The distance law is the
/// SDK's, pushed per frame with the real camera distance through
/// ``SpatialAudioNode/updateGain(forDistance:)`` (#4202) — `AudioFalloff.inverse(0.5, 8, 1.3)`
/// on both platforms.
///
/// Every orb pulses with the loudness of its own part and every note it plays sends a shell
/// out of it — the eye finds the sound the ear hears. Both come from an RMS envelope of the
/// decoded stem (``SoundGardenStems``), indexed by the time the parts have been playing.
///
/// The garden plants itself on the lowest tracked floor plane, 1.6 m ahead, facing the user —
/// no tap to learn. Tapping an orb (or its legend chip) mutes its part.
struct ARSoundGardenDemo: View {
    @StateObject private var garden = SoundGardenController()
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        DemoScaffold(
            "Sound Garden",
            dock: [
                DockItem(icon: "arrow.clockwise", label: "Plant the garden again where you look",
                         caption: "Replant", enabled: garden.planted) { garden.replant() },
            ],
            chromeMode: .ar
        ) {
            stage
        } accessory: {
            VStack(spacing: SceneViewTokens.Space.sm) {
                if let text = statusText {
                    status(text)
                }
                if garden.planted {
                    legend
                }
            }
        }
        .task { await garden.load() }
        .onAppear { garden.appeared() }
        .onDisappear { garden.shutdown() }
        .onChange(of: scenePhase) { _, phase in garden.setActive(phase == .active) }
    }

    // MARK: - Stage

    @ViewBuilder private var stage: some View {
        #if targetEnvironment(simulator)
        ARUnavailableStage(icon: "headphones",
                           message: "Run on iPhone or iPad to walk through a song: four orbs, one part each.")
        #else
        ARSceneView(planeDetection: .horizontal, showPlaneOverlay: false, showCoachingOverlay: false)
            .onSessionStarted { arView in
                MainActor.assumeIsolated { garden.attach(to: arView) }
            }
            .onFrame { frame, arView in
                // ARKit delivers `ARSceneView`'s frames on the main queue.
                MainActor.assumeIsolated { garden.update(frame: frame, in: arView) }
            }
        #endif
    }

    // MARK: - Status

    /// Same order as Android's banner: failure, lost tracking, headphones, scanning, then the
    /// two hints once the garden stands.
    private var statusText: String? {
        if garden.stemsFailed { return "The garden’s sounds could not be loaded" }
        if !garden.planted {
            if garden.trackingLimited { return "Move the device slowly to scan the floor…" }
            if !garden.headphones { return "Put on headphones or turn the volume up, then point at the floor" }
            return "Point at the floor. The garden plants itself."
        }
        switch garden.hint {
        case .walk: return "Walk up to an orb: its part gets louder"
        case .turn: return "Turn your back to an orb: it sounds muffled. Tap one to mute it."
        case .none: return nil
        }
    }

    private func status(_ text: String) -> some View {
        HStack(spacing: SceneViewTokens.Space.sm) {
            Image(systemName: garden.planted ? "waveform" : "headphones")
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(garden.stemsFailed ? SceneViewTokens.ARChrome.danger
                                 : (garden.planted || garden.headphones) && !garden.trackingLimited
                                    ? Self.progressAccent : SceneViewTokens.ARChrome.warning)
                .accessibilityHidden(true)
            Text(text)
                .font(SceneViewTokens.TypeScale.bodyMedium)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(SceneViewTokens.Space.md)
        .modifier(PlacementStatusSurface())
        .allowsHitTesting(false)
        .accessibilityIdentifier("sound-garden-status")
    }

    // MARK: - Legend

    /// The four parts as toggles: who is who, and which ones are muted.
    private var legend: some View {
        HStack(spacing: SceneViewTokens.Space.xs) {
            ForEach(SoundGardenOrb.all.indices, id: \.self) { index in
                let orb = SoundGardenOrb.all[index]
                let muted = garden.muted[index]
                Button { garden.toggleMute(index) } label: {
                    HStack(spacing: SceneViewTokens.Space.xs) {
                        Circle().fill(Color(uiColor: orb.color)).frame(width: 10, height: 10)
                        Text(orb.name).font(SceneViewTokens.TypeScale.caption)
                    }
                    .padding(.horizontal, SceneViewTokens.Space.sm)
                    .frame(minHeight: SceneViewTokens.Layout.touchTarget)
                    .opacity(muted ? 0.45 : 1)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(orb.name)
                .accessibilityValue(muted ? "Muted" : "Playing")
                .accessibilityAddTraits(muted ? [] : .isSelected)
                .accessibilityIdentifier("sound-garden-part-\(orb.id)")
            }
        }
        .padding(.horizontal, SceneViewTokens.Space.sm)
        .modifier(PlacementStatusSurface())
    }
}

extension ARSoundGardenDemo {
    /// "In progress" on `ar-scrim`: `primary` dark (#A4C1FF) in both themes, like the other
    /// status accents of `SceneViewTokens.ARChrome`.
    fileprivate static let progressAccent = Color(red: 0xA4 / 255, green: 0xC1 / 255, blue: 0xFF / 255)
}

// MARK: - Garden

/// One orb: the part it plays, where it stands in the garden, the colour it glows in.
struct SoundGardenOrb: Sendable {
    let id: String
    let name: String
    /// Bundled stem, `Audio/<file>`.
    let file: String
    /// In the anchor's frame (metres; +X is the user's right, +Z points at the user).
    let local: SIMD3<Float>
    /// 0xRRGGBB.
    let hex: Int

    var color: UIColor {
        UIColor(red: CGFloat((hex >> 16) & 0xFF) / 255, green: CGFloat((hex >> 8) & 0xFF) / 255,
                blue: CGFloat(hex & 0xFF) / 255, alpha: 1)
    }

    /// Android's `ORBS`, value for value. The bright, short parts stand in front at different
    /// heights, the long ones behind — so the first step forward already changes the balance.
    /// One palette hue per part (`DESIGN.md`): `TintSoft`, `ArOverlay.accentGuidance`,
    /// `TintLight`, `Accent`.
    static let all: [SoundGardenOrb] = [
        SoundGardenOrb(id: "bells", name: "Bells", file: "garden_bells.caf",
                       local: [-0.7, 1.0, 0.45], hex: 0xD2A8FF),
        SoundGardenOrb(id: "beat", name: "Beat", file: "garden_beat.caf",
                       local: [0.7, 0.35, 0.45], hex: 0xF59E0B),
        SoundGardenOrb(id: "pad", name: "Pad", file: "garden_pad.caf",
                       local: [-0.55, 1.35, -0.65], hex: 0xA4C1FF),
        SoundGardenOrb(id: "bass", name: "Bass", file: "garden_bass.caf",
                       local: [0.55, 0.6, -0.65], hex: 0x6446CD),
    ]
}

/// The entities of one orb, built once per planting.
@MainActor
private struct OrbRig {
    /// At the orb's centre; scaled for the bloom.
    let root: Entity
    let core: ModelEntity
    let halo: ModelEntity
    let shell: ModelEntity
    let audio: SpatialAudioNode?
}

/// Owns the anchor, the orbs, the four ``SpatialAudioNode``s and the per-frame update, off the
/// SwiftUI struct so they survive `body` re-evaluation.
@MainActor
final class SoundGardenController: NSObject, ObservableObject {
    enum Hint { case none, walk, turn }

    @Published private(set) var planted = false
    @Published private(set) var stemsFailed = false
    @Published private(set) var trackingLimited = false
    @Published private(set) var headphones = false
    @Published private(set) var muted = [Bool](repeating: false, count: SoundGardenOrb.all.count)
    @Published private(set) var hint: Hint = .none

    /// SDK inverse law, same as Android: unity inside 0.5 m, −7 dB at 1 m, −14 dB at 2 m.
    static let falloff = AudioFalloff.inverse(refDistance: 0.5, maxDistance: 8, rolloffFactor: 1.3)

    static let plantDistance: Float = 1.6
    /// Android's `SpatialVoiceMath.REAR_GAIN`: a part straight behind the listener is 3 dB down.
    /// RealityKit's HRTF already darkens what is behind; this adds the level half of Android's
    /// rear cue, so "turn your back" sounds duller *and* a bit quieter on both platforms.
    nonisolated static let rearGainBehind: Float = 0.708
    private static let bloomStagger: Float = 0.6
    private static let bloomDuration: Float = 0.7
    private static let muteFade: Float = 0.12

    private static let coreRadius: Float = 0.06
    private static let corePulse: Float = 0.18
    private static let haloScaleMin: Float = 1.7
    private static let haloScalePulse: Float = 1.5
    private static let haloAlphaMin: Float = 0.16
    private static let haloAlphaMax: Float = 0.5
    private static let stalkAlpha: Float = 0.55
    private static let ringRadius: Float = 0.14
    private static let mutedCoreAlpha: Float = 0.3
    /// A note's shell grows from the core to ≈ 0.4 m radius and is gone in under a second.
    private static let shellLife: Float = 0.9
    private static let shellScaleMin: Float = 1.2
    private static let shellScaleMax: Float = 7
    private static let shellAlphaMax: Float = 0.32
    private static let hintDuration: UInt64 = 8_000_000_000

    private var audioNodes: [SpatialAudioNode] = []
    private var envelopes: [[Float]] = []
    private var lastOnsets: [[Int]] = []
    private var stemsReady = false
    private var loadStarted = false

    private weak var arView: ARView?
    private var anchor: AnchorEntity?
    private var rigs: [OrbRig] = []
    private var plantedAt: CFTimeInterval = 0
    /// When the parts started, shifted forward by every pause, so `now - playStart` is the
    /// time the parts have actually played.
    private var playStart: CFTimeInterval?
    private var pausedAt: CFTimeInterval?
    private var muteLevel = [Float](repeating: 1, count: SoundGardenOrb.all.count)
    /// Last level written to each source's `SpatialAudioComponent`, so a steady part is not
    /// rewritten 60 times a second.
    private var appliedLevel = [Float](repeating: -1, count: SoundGardenOrb.all.count)
    private var lastTick: CFTimeInterval = 0
    private var hintTask: Task<Void, Never>?
    private var routeObserver: NSObjectProtocol?
    private var previousCategory: AVAudioSession.Category?
    private var tapInstalled = false

    // MARK: Lifecycle

    /// The song is the demo: play through the silent switch while the screen is up.
    func appeared() {
        let session = AVAudioSession.sharedInstance()
        if previousCategory == nil { previousCategory = session.category }
        try? session.setCategory(.playback, mode: .default)
        try? session.setActive(true)
        headphones = Self.headphonesConnected()
        if routeObserver == nil {
            routeObserver = NotificationCenter.default.addObserver(
                forName: AVAudioSession.routeChangeNotification, object: nil, queue: .main
            ) { [weak self] _ in
                MainActor.assumeIsolated { self?.headphones = Self.headphonesConnected() }
            }
        }
        // Back on a garden that is still planted: the song starts again with it.
        if anchor != nil, playStart == nil, !audioNodes.isEmpty {
            for node in audioNodes { node.play() }
            playStart = CACurrentMediaTime()
        }
    }

    func shutdown() {
        hintTask?.cancel()
        for node in audioNodes { node.stop() }
        playStart = nil
        pausedAt = nil
        if let routeObserver { NotificationCenter.default.removeObserver(routeObserver) }
        routeObserver = nil
        let session = AVAudioSession.sharedInstance()
        if let previousCategory { try? session.setCategory(previousCategory) }
        previousCategory = nil
        try? session.setActive(false, options: .notifyOthersOnDeactivation)
    }

    /// Silence with the screen: a garden that keeps singing from the app switcher is a bug.
    /// Pausing and resuming all four together keeps them on the same beat.
    func setActive(_ active: Bool) {
        guard let start = playStart else { return }
        let now = CACurrentMediaTime()
        if !active, pausedAt == nil {
            for node in audioNodes { node.pause() }
            pausedAt = now
        } else if active, let paused = pausedAt {
            playStart = start + (now - paused)
            pausedAt = nil
            for node in audioNodes { node.play() }
        }
    }

    /// Loads the four parts as spatial sources and decodes them once more for the envelopes.
    func load() async {
        guard !loadStarted else { return }
        loadStarted = true
        do {
            var nodes: [SpatialAudioNode] = []
            for orb in SoundGardenOrb.all {
                nodes.append(try await SpatialAudioNode.spatial(
                    named: orb.file, falloff: Self.falloff, loop: true, autoPlay: false))
            }
            let urls = SoundGardenOrb.all.map { Bundle.main.url(forResource: $0.file, withExtension: nil) }
            let analysis = try await Task.detached(priority: .userInitiated) {
                try urls.map { url -> ([Float], [Int]) in
                    guard let url else { throw CocoaError(.fileNoSuchFile) }
                    let envelope = SoundGardenStems.envelope(try SoundGardenStems.decodeMono(url: url))
                    return (envelope, SoundGardenStems.lastOnsets(envelope))
                }
            }.value
            audioNodes = nodes
            envelopes = analysis.map(\.0)
            lastOnsets = analysis.map(\.1)
            stemsReady = true
        } catch {
            stemsFailed = true
        }
    }

    // MARK: AR

    func attach(to arView: ARView) {
        self.arView = arView
        guard !tapInstalled else { return }
        tapInstalled = true
        arView.addGestureRecognizer(UITapGestureRecognizer(target: self, action: #selector(handleTap(_:))))
    }

    @objc private func handleTap(_ recognizer: UITapGestureRecognizer) {
        guard let arView = recognizer.view as? ARView else { return }
        var hit: Entity? = arView.entity(at: recognizer.location(in: arView))
        while let entity = hit {
            if let index = rigs.firstIndex(where: { $0.root === entity }) {
                toggleMute(index)
                return
            }
            hit = entity.parent
        }
    }

    func toggleMute(_ index: Int) {
        guard muted.indices.contains(index) else { return }
        muted[index].toggle()
    }

    func update(frame: ARFrame, in arView: ARView) {
        let tracking = frame.camera.trackingState == .normal
        if trackingLimited == tracking { trackingLimited = !tracking }
        guard tracking else { return }

        let camera = frame.camera.transform
        let cameraPosition = SIMD3<Float>(camera.columns.3.x, camera.columns.3.y, camera.columns.3.z)
        if anchor == nil, stemsReady || stemsFailed {
            plant(frame: frame, camera: camera, in: arView)
        }
        if anchor != nil {
            let facing = Self.facing(forward: -SIMD3(camera.columns.2.x, camera.columns.2.y, camera.columns.2.z),
                                     up: SIMD3(camera.columns.1.x, camera.columns.1.y, camera.columns.1.z))
            tick(listener: cameraPosition, facing: facing)
        }
    }

    func replant() {
        hintTask?.cancel()
        for node in audioNodes { node.stop() }
        playStart = nil
        pausedAt = nil
        if let anchor { arView?.scene.removeAnchor(anchor) }
        anchor = nil
        rigs = []
        planted = false
        hint = .none
    }

    /// Finds the floor and anchors the garden on it, ``plantDistance`` ahead of the user and
    /// turned so its +Z faces them. The lowest horizontal plane at least 30 cm under the camera
    /// wins: the first plane ARKit reports is often a table, and a garden wants the floor.
    private func plant(frame: ARFrame, camera: simd_float4x4, in arView: ARView) {
        let cameraY = camera.columns.3.y
        let floor = frame.anchors
            .compactMap { $0 as? ARPlaneAnchor }
            .filter { $0.alignment == .horizontal && $0.classification != .ceiling
                && $0.transform.columns.3.y < cameraY - 0.3 }
            .min { $0.transform.columns.3.y < $1.transform.columns.3.y }
        guard let floor else { return }

        let facing = Self.facing(forward: -SIMD3(camera.columns.2.x, camera.columns.2.y, camera.columns.2.z),
                                 up: SIMD3(camera.columns.1.x, camera.columns.1.y, camera.columns.1.z))
        let centre = SIMD3<Float>(camera.columns.3.x + facing.x * Self.plantDistance,
                                  floor.transform.columns.3.y,
                                  camera.columns.3.z + facing.y * Self.plantDistance)
        // Rotation about +Y that takes +Z to the direction back towards the user (−facing).
        let yaw = atan2(-facing.x, -facing.y)

        let anchorNode = AnchorNode.world(position: centre)
        anchorNode.entity.orientation = simd_quatf(angle: yaw, axis: [0, 1, 0])
        rigs = SoundGardenOrb.all.enumerated().map { index, orb in
            buildOrb(orb, audio: audioNodes.indices.contains(index) ? audioNodes[index] : nil,
                     under: anchorNode.entity)
        }
        arView.scene.addAnchor(anchorNode.entity)
        anchor = anchorNode.entity
        plantedAt = CACurrentMediaTime()
        muteLevel = muted.map { $0 ? 0 : 1 }
        planted = true
        startHints()

        // All four in one main-actor turn, silent until the bloom opens them: the parts start
        // on the same beat, and each one's level rises with its orb.
        appliedLevel = appliedLevel.map { _ in -1 }
        for rig in rigs { if let audio = rig.audio { Self.setLevel(0, on: audio) } }
        for node in audioNodes { node.play() }
        playStart = CACurrentMediaTime()
    }

    /// The facing direction on the floor plane, from the camera's forward (−Z) and up (+Y).
    /// Android's `SpatialVoiceMath.listenerFrame`: with pitch p, `cos p · forwardₕ + sin p · upₕ`
    /// is the facing direction at every pitch — straight down included, where the top edge of
    /// the screen is what points where the user faces. Returns (x, z), unit length.
    static func facing(forward: SIMD3<Float>, up: SIMD3<Float>) -> SIMD2<Float> {
        let sinPitch = -forward.y
        let cosPitch = max(1 - sinPitch * sinPitch, 0).squareRoot()
        let flat = SIMD2<Float>(forward.x * cosPitch + up.x * sinPitch,
                                forward.z * cosPitch + up.z * sinPitch)
        let length = simd_length(flat)
        return length < 1e-4 ? SIMD2(0, -1) : flat / length
    }

    /// Android's `SpatialVoiceMath.rearGain`: 1 for a source anywhere in front, sliding down to
    /// ``rearGainBehind`` straight behind. `frontness` is −1 (behind) … +1 (ahead).
    nonisolated static func rearGain(frontness: Float) -> Float {
        let behind = min(max(-frontness, 0), 1)
        return 1 - (1 - rearGainBehind) * behind
    }

    private func buildOrb(_ orb: SoundGardenOrb, audio: SpatialAudioNode?, under parent: Entity) -> OrbRig {
        let base = SIMD3<Float>(orb.local.x, 0, orb.local.z)
        // The stalk and the ring on the floor tie each orb to the ground it was planted in —
        // without them a floating sphere has no readable distance on a camera feed.
        // Unlit and translucent like the orb's glow: lit, they read as grey sticks on a dim floor.
        let stalkGlow = Self.glow(orb.color, alpha: Self.stalkAlpha)
        let ring = PathNode.circle(center: base, radius: Self.ringRadius, segments: 40, thickness: 0.004).entity
        let stalk = LineNode(from: base, to: orb.local, thickness: 0.004).entity
        for entity in [ring, stalk] {
            Self.paint(entity, with: stalkGlow)
            parent.addChild(entity)
        }

        let root = Entity()
        root.position = orb.local
        root.scale = .init(repeating: 0.001)
        // Taps on the orb (or anywhere in its halo) mute its part.
        root.components.set(CollisionComponent(shapes: [.generateSphere(radius: Self.coreRadius * 2)]))
        parent.addChild(root)

        let sphere = MeshResource.generateSphere(radius: Self.coreRadius)
        let core = ModelEntity(mesh: sphere, materials: [UnlitMaterial(color: orb.color)])
        core.components.set(OpacityComponent(opacity: 1))
        let halo = ModelEntity(mesh: sphere, materials: [Self.glow(orb.color, alpha: Self.haloAlphaMax)])
        halo.components.set(OpacityComponent(opacity: Self.haloAlphaMin / Self.haloAlphaMax))
        let shell = ModelEntity(mesh: sphere, materials: [Self.glow(orb.color, alpha: Self.shellAlphaMax)])
        shell.components.set(OpacityComponent(opacity: 0))
        shell.scale = .init(repeating: 0.001)
        root.addChild(shell)
        root.addChild(halo)
        root.addChild(core)
        if let audio {
            // The source sits in the orb: RealityKit renders the direction from its pose.
            audio.entity.removeFromParent()
            root.addChild(audio.entity)
        }
        return OrbRig(root: root, core: core, halo: halo, shell: shell, audio: audio)
    }

    private static func glow(_ color: UIColor, alpha: Float) -> UnlitMaterial {
        var material = UnlitMaterial(color: color)
        material.blending = .transparent(opacity: .init(floatLiteral: alpha))
        return material
    }

    /// Replaces the material of every model under `entity` (the SDK's line and path nodes are lit).
    private static func paint(_ entity: Entity, with material: UnlitMaterial) {
        if let model = entity as? ModelEntity { model.model?.materials = [material] }
        for child in entity.children { paint(child, with: material) }
    }

    /// One frame: bloom, mute fades, distance gain from the real camera, pulse and shells.
    /// `facing` is the levelled direction the user faces, (x, z) on the floor plane.
    private func tick(listener: SIMD3<Float>, facing: SIMD2<Float>) {
        let now = CACurrentMediaTime()
        let dt = lastTick == 0 ? 0 : Float(min(max(now - lastTick, 0), 0.1))
        lastTick = now
        let sincePlant = Float(now - plantedAt)
        // What the device has played, minus the output latency so the eye is not early.
        let latency = AVAudioSession.sharedInstance().outputLatency
        let played = playStart.map { start in
            Int(((pausedAt ?? now) - start - latency) * Double(SoundGardenStems.sampleRate))
        } ?? 0

        for (index, rig) in rigs.enumerated() {
            // The song builds up: one orb, then the next, bloomStagger apart.
            let bloom = min(max((sincePlant - Float(index) * Self.bloomStagger) / Self.bloomDuration, 0), 1)
            let target: Float = muted[index] ? 0 : 1
            muteLevel[index] += (target - muteLevel[index]) * min(dt / Self.muteFade, 1)
            let level = bloom * muteLevel[index]

            if let audio = rig.audio {
                let offset = rig.root.position(relativeTo: nil) - listener
                let distance = simd_length(offset)
                audio.updateGain(forDistance: distance)
                // Android's `SpatialVoiceMath.voice`: frontness against the levelled facing.
                let frontness = distance < 1e-3 ? 1 : (offset.x * facing.x + offset.z * facing.y) / distance
                let heard = level * Self.rearGain(frontness: frontness)
                if abs(heard - appliedLevel[index]) > 0.002 {
                    Self.setLevel(heard, on: audio)
                    appliedLevel[index] = heard
                }
            }

            let pulse = envelopes.indices.contains(index)
                ? SoundGardenStems.envelope(envelopes[index], at: played) * level : 0
            let grow = max(Self.easeOutBack(bloom), 0.001)
            rig.root.scale = .init(repeating: grow)
            rig.core.scale = .init(repeating: 1 + Self.corePulse * pulse)
            rig.core.components.set(OpacityComponent(opacity: muted[index] ? Self.mutedCoreAlpha : 1))
            rig.halo.scale = .init(repeating: Self.haloScaleMin + Self.haloScalePulse * pulse)
            let haloAlpha = Self.haloAlphaMin + (Self.haloAlphaMax - Self.haloAlphaMin) * pulse
            rig.halo.components.set(OpacityComponent(opacity: haloAlpha / Self.haloAlphaMax))

            let age = lastOnsets.indices.contains(index)
                ? SoundGardenStems.secondsSinceOnset(lastOnsets[index], at: played) : nil
            let t = age.map { min(max($0 / Self.shellLife, 0), 1) } ?? 1
            let fade = (1 - t) * (1 - t)
            rig.shell.scale = .init(repeating: fade <= 0 ? 0.001
                                    : Self.shellScaleMin + (Self.shellScaleMax - Self.shellScaleMin) * (1 - fade))
            rig.shell.components.set(OpacityComponent(opacity: fade * level))
        }
    }

    /// The part's own level (bloom × mute) on the source, in decibels. The distance law lives
    /// on the playback controller (``SpatialAudioNode/updateGain(forDistance:)``); the two
    /// multiply, like Android's `level × falloff`.
    private static func setLevel(_ level: Float, on audio: SpatialAudioNode) {
        guard var spatial = audio.entity.spatialAudio else { return }
        spatial.gain = Double(20 * log10(min(max(level, 1e-4), 1)))
        audio.entity.spatialAudio = spatial
    }

    private func startHints() {
        hintTask?.cancel()
        hintTask = Task { @MainActor [weak self] in
            self?.hint = .walk
            try? await Task.sleep(nanoseconds: Self.hintDuration)
            guard !Task.isCancelled else { return }
            self?.hint = .turn
            try? await Task.sleep(nanoseconds: Self.hintDuration)
            guard !Task.isCancelled else { return }
            self?.hint = .none
        }
    }

    /// Overshoots a little before settling — a bloom, not a fade.
    private static func easeOutBack(_ t: Float) -> Float {
        let c1: Float = 1.70158
        let c3 = c1 + 1
        let u = t - 1
        return 1 + c3 * u * u * u + c1 * u * u
    }

    /// True while a headset is the output — wired, Bluetooth or USB.
    nonisolated static func headphonesConnected() -> Bool {
        let headsets: Set<AVAudioSession.Port> = [.headphones, .bluetoothA2DP, .bluetoothLE, .bluetoothHFP, .usbAudio]
        return AVAudioSession.sharedInstance().currentRoute.outputs.contains { headsets.contains($0.portType) }
    }
}
#endif

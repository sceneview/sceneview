import SwiftUI
import RealityKit
import Metal
import SceneViewSwift
#if os(macOS)
import AppKit
#endif

/// **Cosmos** — four procedural, real-time space scenes lit by nothing but their own light
/// and a bloom pass: a barred spiral galaxy, a plasma star, a particle-track burst and a
/// vortex flow field. The iOS twin of the Android demo, built from the same seeds
/// (`CosmosMeshes.swift`) — and without a single line of shader source.
///
/// ### The recipe it teaches
///
/// Glow is **additive, unlit, HDR geometry plus bloom**:
///
/// - Radiance above 1.0 is what the bloom pass bleeds from, so every colour is baked in
///   linear HDR into float16 textures and `SceneView.bloom(_:)` turns it into light.
/// - An additive `UnlitMaterial` (`Program.Descriptor.blendMode = .add`) makes overlapping
///   light pile up — thousands of sprites stack into a white-hot core with no sorting at all.
/// - Points are quads and strokes ribbons, laid out once on the CPU facing the scene's camera
///   (`CosmosGlow.swift`) in one `LowLevelMesh` per layer.
///
/// Animation is a handful of per-frame writes — a material tint for the fades, the world
/// transform for the camera path, an index count for the burst's growing tracks.
struct CosmosDemo: View {
    @AppStorage(DeepLinkRouter.qaModeDefaultsKey) private var qaMode: Bool = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.displayScale) private var displayScale

    @State private var engine = CosmosEngine()

    var body: some View {
        GeometryReader { geometry in
            ZStack {
                Color.black
                SceneView { root in
                    engine.install(in: root)
                }
                .autoCenterContent(false)
                .cameraGesturesEnabled(false)
                .cameraPose(CosmosEngine.fixedCamera)
                .bloom(BloomOptions(strength: engine.bloom, levels: 7, resolution: 512, threshold: true))
                if !engine.ready {
                    VStack(spacing: SceneViewTokens.Space.md) {
                        ProgressView().tint(.white)
                        Text("Lighting up the cosmos")
                            .font(.callout)
                            .foregroundStyle(.white.opacity(0.8))
                    }
                }
            }
            .onAppear { engine.viewport = viewport(geometry.size) }
            .onChange(of: geometry.size) { _, size in engine.viewport = viewport(size) }
        }
        .ignoresSafeArea()
        .onAppear {
            engine.frozen = qaMode || reduceMotion
            if engine.frozen { engine.touring = false }
            engine.start()
        }
        .onDisappear { engine.stop() }
        .onChange(of: qaMode) { _, qa in engine.frozen = qa || reduceMotion }
        .onChange(of: reduceMotion) { _, reduce in engine.frozen = qaMode || reduce }
        .demoChrome(
            dock: CosmosSceneKind.allCases.map { kind in
                DockItem(icon: kind.icon, label: kind.label, selected: engine.scene == kind) {
                    engine.select(kind)
                }
            },
            onReset: {
                engine.touring = !engine.frozen
                engine.animating = true
                engine.bloom = CosmosEngine.defaultBloom
            },
            accessory: { DemoHint(engine.scene.caption) }
        ) {
            VStack(alignment: .leading, spacing: SceneViewTokens.Space.md) {
                LabeledSlider(label: "Bloom", value: $engine.bloom, range: 0...1, decimals: 2)
                Toggle("Tour the scenes", isOn: $engine.touring)
                Toggle("Animate", isOn: $engine.animating)
            }
            .tint(SceneViewTheme.primary)
        }
    }

    private func viewport(_ size: CGSize) -> CGSize {
        CGSize(width: size.width * displayScale, height: size.height * displayScale)
    }
}

private extension CosmosSceneKind {
    /// SF Symbols closest to the Android dock's Cyclone / WbSunny / Flare / Waves icons.
    var icon: String {
        switch self {
        case .galaxy: "hurricane"
        case .star: "sun.max.fill"
        case .burst: "sparkle"
        case .flow: "water.waves"
        }
    }
}


// MARK: - Engine

/// Owns the RealityKit side of the demo: the four scenes (built on first use, off the main
/// thread) and the per-refresh tick that animates them.
@MainActor
@Observable
final class CosmosEngine {
    static let defaultBloom: Float = 0.45
    /// Seconds each scene holds the screen before the tour moves on.
    static let tourSeconds: Float = 14
    /// One detonation of the burst, start to afterglow, in seconds.
    static let burstPeriod: Float = 5.5
    /// How long a scene takes to fade in after a switch, in seconds.
    static let revealSeconds: Float = 0.9
    /// Frozen per-scene times for QA captures and reduced motion: each at its most telling moment.
    static let qaTime: [Float] = [6, 3, 1.9, 4]

    /// SceneView's own camera is parked here; the tick moves the whole world instead, so
    /// the Android camera path (`CosmosFraming.pose`) is reproduced exactly.
    static let fixedCamera = SceneCameraPose(azimuth: 0, elevation: 0, distance: 5)

    var scene: CosmosSceneKind = .galaxy
    var touring = true
    var animating = true
    var frozen = false
    /// The Bloom slider: drives the post-process pass and the baked halos alike.
    var bloom: Float = defaultBloom
    private(set) var ready = false

    @ObservationIgnored var viewport = CGSize(width: 1206, height: 2622)
    @ObservationIgnored private let world = Entity()
    @ObservationIgnored private var programs: CosmosPrograms?
    @ObservationIgnored private var starField: GlowEntity?
    @ObservationIgnored private var built: [CosmosSceneKind: CosmosSceneEntities] = [:]
    @ObservationIgnored private var building: Set<CosmosSceneKind> = []
    @ObservationIgnored private var frameLink: CosmosFrameLink?
    @ObservationIgnored private var sceneTime: Float = 0
    @ObservationIgnored private var shownScene: CosmosSceneKind?
    @ObservationIgnored private var lastTick: Date?
    @ObservationIgnored private var loadTask: Task<Void, Never>?
    /// Whether the built flow field faces a landscape viewport; it is rebuilt when that flips.
    @ObservationIgnored private var flowLandscape: Bool?

    func install(in root: Entity) {
        world.name = "cosmos-world"
        root.addChild(world)
        startLoading()
    }

    func select(_ kind: CosmosSceneKind) {
        scene = kind
        touring = false
        ensureBuilt(kind)
    }

    /// Ticks once per display refresh — up to 120 Hz on ProMotion — and resumes loading
    /// whatever a previous `stop()` interrupted.
    func start() {
        if frameLink == nil {
            frameLink = CosmosFrameLink { [weak self] in self?.tick() }
        }
        if world.parent != nil { startLoading() }
    }

    func stop() {
        frameLink?.invalidate()
        frameLink = nil
        loadTask?.cancel()
        loadTask = nil
        for entities in built.values { entities.cancelChurn() }
        lastTick = nil
    }

    /// Pixels per world unit at distance 1 for SceneView's 60° vertical field of view.
    private var focal: Float {
        Float(max(viewport.height, 1)) * 0.5 / CosmosFraming.tanHalfVerticalFov
    }

    private var aspect: Float {
        Float(viewport.width / max(viewport.height, 1))
    }

    // MARK: Loading

    private func startLoading() {
        guard loadTask == nil else { return }
        loadTask = Task { @MainActor [weak self] in
            guard let self else { return }
            do {
                if self.programs == nil {
                    let program = await GlowEntity.additiveProgram()
                    self.programs = CosmosPrograms(additive: program, alpha: await GlowEntity.alphaProgram())
                }
                if self.starField == nil, let program = self.programs?.additive {
                    let focal = self.focal
                    let layer = await Task.detached(priority: .userInitiated) {
                        // The sky surrounds the orbit: every star faces the origin.
                        GlowBuilder.sprites(CosmosMeshes.starField(), view: GlowView(eye: .zero, focal: focal),
                                            minPixels: 1.4, twinkle: 0.3) { -$0 }
                    }.value
                    let field = try await GlowEntity(layer, program: program)
                    self.world.addChild(field.entity)
                    self.starField = field
                }
                // The selected scene first, then the light ones so a switch is instant. The
                // star — a plasma surface computed on the CPU — waits until it is wanted.
                let order = [self.scene] + CosmosSceneKind.allCases.filter { $0 != self.scene && $0 != .star }
                for kind in order where !Task.isCancelled {
                    await self.build(kind)
                }
            } catch {
                NSLog("[Cosmos] setup failed: \(error)")
            }
        }
    }

    private func ensureBuilt(_ kind: CosmosSceneKind) {
        guard programs != nil, built[kind] == nil, !building.contains(kind) else { return }
        Task { @MainActor in await build(kind) }
    }

    private func build(_ kind: CosmosSceneKind) async {
        guard let programs, built[kind] == nil, !building.contains(kind) else { return }
        building.insert(kind)
        defer { building.remove(kind) }
        // Quads face the camera of the scene's resting pose; its sway is a few degrees.
        let time = Self.qaTime[kind.rawValue]
        let view = GlowView(eye: camera(kind, time: time).eye, focal: focal)
        let landscape = aspect > 1
        let started = Date()
        let layers = await Task.detached(priority: .userInitiated) {
            CosmosSceneLayers.build(kind, view: view, time: time)
        }.value
        do {
            let entities = try await CosmosSceneEntities(kind: kind, layers: layers, programs: programs, time: time)
            NSLog("[Cosmos] %@ built in %.2f s", kind.label, Date().timeIntervalSince(started))
            entities.root.isEnabled = false
            world.addChild(entities.root)
            built[kind] = entities
            if kind == .flow { flowLandscape = landscape }
        } catch {
            NSLog("[Cosmos] building \(kind) failed: \(error)")
        }
    }

    // MARK: Tick

    private func tick() {
        let now = Date()
        let dt = lastTick.map { Float(now.timeIntervalSince($0)) } ?? 0
        lastTick = now
        let current = scene
        if shownScene != current {
            shownScene = current
            sceneTime = 0
        } else if animating && !frozen {
            // Clamp a long hitch (backgrounding) so the scene does not jump ahead.
            sceneTime += min(max(dt, 0), 0.1)
        }
        let next = CosmosSceneKind.allCases[(current.rawValue + 1) % CosmosSceneKind.allCases.count]
        if touring && !frozen {
            // Build the tour's next scene a few seconds ahead, so it is ready on cue.
            if sceneTime > Self.tourSeconds - 6 { ensureBuilt(next) }
            if sceneTime > Self.tourSeconds {
                scene = next
                ensureBuilt(next)
                return
            }
        }
        if let flow = built[.flow], let landscape = flowLandscape, landscape != (aspect > 1) {
            // The viewport turned across square: the flow's quads faced the other framing.
            flow.root.removeFromParent()
            built[.flow] = nil
            flowLandscape = nil
            if current == .flow { ensureBuilt(.flow) }
        }
        for (kind, other) in built { other.root.isEnabled = kind == current }
        guard let entities = built[current] else {
            // Still being built: the loading hint stays up over the empty sky, and the
            // scene's clock waits so its reveal plays once it lands.
            if ready { ready = false }
            sceneTime = 0
            ensureBuilt(current)
            return
        }
        if !ready { ready = true }

        let time = frozen ? Self.qaTime[current.rawValue] : sceneTime
        let reveal = frozen ? 1 : CosmosFraming.reveal(sceneTime, duration: Self.revealSeconds)
        let camera = camera(current, time: time)
        placeWorld(camera)

        starField?.setIntensity(reveal * (current == .flow ? 0.35 : 1), time: time)
        entities.update(time: time, reveal: reveal, bloom: bloom, eye: camera.eye, live: !frozen)
    }

    /// Where `kind`'s camera is at `time`, and which way is up on screen. The flow field is
    /// portrait: on a landscape viewport the camera rolls a quarter turn — (x, y) → (−y, x),
    /// as on Android — so its long side runs along the screen's.
    private func camera(_ kind: CosmosSceneKind, time: Float) -> (eye: SIMD3<Float>, up: SIMD3<Float>) {
        let pose = CosmosFraming.pose(kind, time: time, aspect: aspect)
        let eye = CosmosFraming.orbit(pose.distance, pose.elevation, pose.yaw)
        guard kind == .flow, aspect > 1 else { return (eye, SIMD3(0, 1, 0)) }
        return (SIMD3(-eye.y, eye.x, eye.z), SIMD3(-1, 0, 0))
    }

    /// Moves the world so that SceneView's fixed camera sees it from the Android pose.
    private func placeWorld(_ pose: (eye: SIMD3<Float>, up: SIMD3<Float>)) {
        let desired = Self.lookAt(eye: pose.eye, target: .zero, up: pose.up)
        let camera = Self.lookAt(eye: Self.fixedCamera.cameraPosition(), target: Self.fixedCamera.target)
        world.transform = Transform(matrix: camera * desired.inverse)
    }

    /// World matrix of a camera at `eye` looking at `target` (RealityKit looks down −Z).
    private static func lookAt(eye: SIMD3<Float>, target: SIMD3<Float>,
                               up: SIMD3<Float> = SIMD3(0, 1, 0)) -> simd_float4x4 {
        let z = simd_normalize(eye - target)
        let x = simd_normalize(simd_cross(up, z))
        let y = simd_cross(z, x)
        return simd_float4x4(columns: (
            SIMD4(x, 0), SIMD4(y, 0), SIMD4(z, 0), SIMD4(eye, 1)
        ))
    }
}

// MARK: - Scenes

/// A plasma ball of a scene: where it sits, how big, and its view-dependent glow.
struct CosmosSphere: Sendable {
    var center: SIMD3<Float>
    var radius: Float
    var limb: GlowLayer
}

/// The CPU layers of one scene, built off the main thread.
struct CosmosSceneLayers: Sendable {
    /// Galaxy stars, the star's halo, the burst's flash, the flow's backdrop.
    var main: GlowLayer?
    /// Burst tracks, the star's loops, the flow's streamlines.
    var strokes: GlowLayer?
    /// Burst sparks, the flow's glints — or, alpha-blended, the galaxy's dust lanes.
    var dust: GlowLayer?
    /// Baked light spill around the burst's tracks, scaled by the Bloom slider.
    var halo: GlowLayer?
    /// The star's or the flow nuclei's surface, and each ball.
    var plasma: GlowImage?
    var spheres: [CosmosSphere] = []
    /// The star's wide glow, turned towards the eye like the limbs.
    var corona: GlowLayer?

    /// Radiance baked into the burst flash: the envelope's peak (`6 + 0.35`), so the per-frame
    /// tint only ever scales it down.
    static let flashPeak: Float = 6.35
    /// Radiance of the baked halo at a full Bloom slider, and how many stroke widths it spans.
    static let haloPeak: Float = 0.3
    static let haloWidth: Float = 5
    /// Peaks of the star's pulsing, baked in so the tint only scales down (Android's
    /// `1 + 0.08 sin` on the surface and `1 + 0.12 sin` on the halo).
    static let starPulse: Float = 1.08
    static let haloPulse: Float = 1.12
    /// The flow nuclei: the first four vortices, and their radii.
    static let nucleusRadii: [Float] = [0.1, 0.075, 0.06, 0.08]

    /// Each ribbon set's `base`, `dashAmp`, `dashFreq` and `dashSpeed`, as Android sets them.
    static let prominenceDash = GlowBuilder.Dash(base: 0.6, amp: 1.6, freq: 2 * .pi * 3.5, speed: 1.6)
    static let burstDash = GlowBuilder.Dash(base: 0.75, amp: 0.5, freq: 2 * .pi * 3, speed: 7)
    static let flowDash = GlowBuilder.Dash(base: 0.5, amp: 1.3, freq: 2 * .pi * 5, speed: 2.5)
    /// The flow's thousands of faint strokes lean on Android's HDR bloom haze, which the
    /// display-space pass here spreads less: they are lifted to the same on-screen brightness.
    static let flowGain: Float = 1.3

    /// The star's wide blue glow at `r` silhouette radii, fitted to the Android capture's
    /// rings at 1.1, 1.3, 1.6 and 2 radii (minus what the halo sprites already give), and
    /// baked at the halo's pulse peak.
    static func starCorona(_ r: Float) -> SIMD3<Float> {
        guard r > 0.9 else { return .zero }
        let out = max(r - 1, 0)
        let blue = 0.5 * exp(-pow(out / 0.9, 1.3))
        let near = exp(-out / 0.2)
        let color = simd_mix(SIMD3<Float>(0.05, 0.25, 1), SIMD3<Float>(0.45, 0.85, 1), SIMD3(repeating: near))
        return color * (blue * haloPulse)
    }

    /// The star turns 4°/s about its axis, tilted 12° towards the viewer — Android's
    /// `Rotation(y = 4t, x = 12)`.
    static func starOrientation(_ time: Float) -> simd_quatf {
        let deg = Float.pi / 180
        return simd_quatf(angle: 12 * deg, axis: SIMD3(1, 0, 0)) * simd_quatf(angle: 4 * deg * time, axis: SIMD3(0, 1, 0))
    }

    /// The galaxy turns 3.5°/s, clockwise seen from above.
    static func galaxyOrientation(_ time: Float) -> simd_quatf {
        simd_quatf(angle: -3.5 * .pi / 180 * time, axis: SIMD3(0, 1, 0))
    }

    static func build(_ kind: CosmosSceneKind, view: GlowView, time: Float) -> CosmosSceneLayers {
        let facing: (SIMD3<Float>) -> SIMD3<Float> = { view.eye - $0 }
        switch kind {
        case .galaxy:
            // The disc spins about Y, so its sprites lie in the disc plane, facing up.
            let up: (SIMD3<Float>) -> SIMD3<Float> = { _ in SIMD3(0, 1, 0) }
            return CosmosSceneLayers(
                main: GlowBuilder.sprites(CosmosMeshes.galaxy(), view: view, minPixels: 1.0, twinkle: 0.12, facing: up),
                dust: GlowBuilder.sprites(CosmosMeshes.galaxyDust(), view: view, minPixels: 0.5,
                                          profile: .dust, facing: up)
            )
        case .star:
            let surface = Plasma.surface(.star, time: time, gain: starPulse)
            let look = PlasmaLook.star
            let limb = GlowBuilder.Limb(radius: 1, surface: surface.mean * starPulse,
                                        rim: look.rim * starPulse, rimPower: look.rimPower,
                                        exposure: look.exposure)
            // The loops turn with the star: widen them for the eye as the turned star sees it.
            let spunView = GlowView(eye: starOrientation(time).inverse.act(view.eye), focal: view.focal)
            return CosmosSceneLayers(
                main: GlowBuilder.sprites(CosmosMeshes.starHalo(), view: view, minPixels: 1.1,
                                          gain: haloPulse, facing: facing),
                strokes: GlowBuilder.dashedRibbons(CosmosMeshes.prominences(), view: spunView, minPixels: 1.0,
                                                   dash: prominenceDash, tailTaper: 0.2),
                plasma: surface.image,
                spheres: [CosmosSphere(center: .zero, radius: 1,
                                       limb: GlowBuilder.limb(limb, distance: simd_length(view.eye), resolution: 256))],
                corona: GlowBuilder.corona(radius: 1, distance: simd_length(view.eye), extent: 3,
                                           resolution: 256, falloff: starCorona)
            )
        case .burst:
            return CosmosSceneLayers(
                main: GlowBuilder.sprites(CosmosMeshes.burstCore(), view: view, minPixels: 1.1,
                                          gain: flashPeak, facing: facing),
                strokes: GlowBuilder.dashedRibbons(CosmosMeshes.burst(), view: view, minPixels: 1.0,
                                                   dash: burstDash, tailTaper: 0.35),
                dust: GlowBuilder.sprites(CosmosMeshes.burstSparks(), view: view, minPixels: 1.6,
                                          twinkle: 0.6, facing: facing),
                halo: GlowBuilder.ribbons(CosmosMeshes.burst(), view: view, minPixels: 1.0,
                                          dash: .steady(haloPeak), time: time, tailTaper: 0.35, halo: haloWidth)
            )
        case .flow:
            let surface = Plasma.surface(.nucleus, time: time, width: 256, height: 128)
            let look = PlasmaLook.nucleus
            let spheres = zip(CosmosMeshes.flowVortices, nucleusRadii).map { vortex, radius in
                let center = SIMD3(vortex.x, vortex.y, CosmosMeshes.flowDepth(vortex.x, vortex.y) + radius * 0.4)
                let limb = GlowBuilder.Limb(radius: radius, surface: surface.mean, rim: look.rim, rimPower: look.rimPower)
                return CosmosSphere(center: center, radius: radius,
                                    limb: GlowBuilder.limb(limb, distance: simd_length(view.eye - center), resolution: 64))
            }
            return CosmosSceneLayers(
                main: GlowBuilder.sprites(CosmosMeshes.flowBackdrop(), view: view, minPixels: 1.1, facing: facing),
                strokes: GlowBuilder.dashedRibbons(CosmosMeshes.flowField(), view: view, minPixels: 1.0,
                                                   dash: flowDash.scaled(flowGain), tailTaper: 0),
                dust: GlowBuilder.sprites(CosmosMeshes.flowDust(), view: view, minPixels: 1.3,
                                          twinkle: 0.7, facing: facing),
                plasma: surface.image,
                spheres: spheres
            )
        }
    }
}

/// The glow programs every scene draws with.
struct CosmosPrograms {
    /// Light that adds up: stars, strokes, halos.
    let additive: UnlitMaterial.Program
    /// Matter that blocks light: the galaxy's dust.
    let alpha: UnlitMaterial.Program
}

/// The entities of one scene and how each frame drives them.
@MainActor
final class CosmosSceneEntities {
    let kind: CosmosSceneKind
    let root = Entity()
    /// What turns with the star: its surface and its loops.
    private let spin = Entity()
    private var main: GlowEntity?
    private var strokes: GlowEntity?
    private var dust: GlowEntity?
    private var halo: GlowEntity?
    private var balls: [PlasmaEntity] = []
    /// Limb discs, one per ball, each turned towards the eye every frame.
    private var limbs: [(glow: GlowEntity, center: SIMD3<Float>)] = []
    private var corona: GlowEntity?
    /// The star's surface frames, keyed by churn step: 0 is the one built with the scene.
    private var churnFrames: [Int: TextureResource] = [:]
    private var churnBaking: Int?
    private var churnTask: Task<Void, Never>?
    /// The frame the dictionary was last pruned for.
    private var churnFrom = 0
    /// Where the surface is in its own clock; see `churn(time:intensity:)`.
    private var surfaceTime: Float = 0
    /// The scene time `churn` last saw: the surface clock advances by its increments.
    private var churnClock: Float = 0
    private let churnTime: Float

    /// Seconds between two baked frames of the star's surface; the ball cross-fades between them.
    static let churnStep: Float = 1.5

    init(kind: CosmosSceneKind, layers: CosmosSceneLayers, programs: CosmosPrograms, time: Float) async throws {
        self.kind = kind
        churnTime = time
        let program = programs.additive
        if let layer = layers.strokes { strokes = try await GlowEntity(layer, program: program) }
        if let layer = layers.main { main = try await GlowEntity(layer, program: program) }
        if let layer = layers.halo { halo = try await GlowEntity(layer, program: program) }
        if let layer = layers.corona { corona = try await GlowEntity(layer, program: program) }
        if let layer = layers.dust {
            dust = try await GlowEntity(layer, program: kind == .galaxy ? programs.alpha : program)
        }
        if let image = layers.plasma {
            let texture = try await GlowEntity.texture(image)
            if kind == .star { churnFrames[0] = texture }
            for sphere in layers.spheres {
                let ball = PlasmaEntity(texture: texture, radius: sphere.radius,
                                        churn: kind == .star ? program : nil)
                ball.entity.position = sphere.center
                balls.append(ball)
                let limb = try await GlowEntity(sphere.limb, program: program)
                limb.entity.position = sphere.center
                limbs.append((limb, sphere.center))
            }
        }

        switch kind {
        case .star:
            // The surface and its loops turn together; the halo and the limb face the viewer.
            root.addChild(spin)
            for ball in balls { spin.addChild(ball.entity) }
            if let strokes { spin.addChild(strokes.entity) }
            for glow in [main, corona].compactMap({ $0 }) + limbs.map(\.glow) { root.addChild(glow.entity) }
        case .galaxy:
            // Dust has to land on the stars it dims, whatever the depth sort thinks.
            let group = ModelSortGroup()
            main?.drawOrder(0, in: group)
            dust?.drawOrder(1, in: group)
            for glow in [main, dust].compactMap({ $0 }) { root.addChild(glow.entity) }
        case .burst, .flow:
            for glow in [main, halo, strokes, dust].compactMap({ $0 }) { root.addChild(glow.entity) }
            for ball in balls { root.addChild(ball.entity) }
            for limb in limbs { root.addChild(limb.glow.entity) }
        }
    }

    /// - Parameters:
    ///   - eye: where the camera is, in this scene's coordinates.
    ///   - live: the clock runs (not a frozen QA or reduced-motion frame), so the star churns.
    func update(time: Float, reveal: Float, bloom: Float, eye: SIMD3<Float>, live: Bool) {
        strokes?.scrollDashes(time: time)
        switch kind {
        case .galaxy:
            root.orientation = CosmosSceneLayers.galaxyOrientation(time)
            main?.setIntensity(reveal, time: time)
            dust?.setOpacity(reveal)
        case .star:
            let pulse = sin(time * 2.1)
            spin.orientation = CosmosSceneLayers.starOrientation(time)
            spin.scale = SIMD3(repeating: 1 + 0.012 * pulse)
            let surface = reveal * (1 + 0.08 * pulse) / CosmosSceneLayers.starPulse
            churn(time: live ? time : 0, intensity: surface)
            for limb in limbs {
                limb.glow.setIntensity(surface)
                limb.glow.entity.scale = spin.scale
            }
            strokes?.setIntensity(reveal)
            main?.setIntensity(reveal * (1 + 0.12 * pulse) / CosmosSceneLayers.haloPulse)
            corona?.setIntensity(reveal * (1 + 0.12 * pulse) / CosmosSceneLayers.haloPulse)
        case .burst:
            let envelope = CosmosMeshes.burstEnvelope((time / CosmosEngine.burstPeriod).truncatingRemainder(dividingBy: 1))
            strokes?.reveal(upTo: envelope.x)
            strokes?.setIntensity(reveal * envelope.y)
            halo?.reveal(upTo: envelope.x)
            halo?.setIntensity(reveal * envelope.y * bloom)
            dust?.setIntensity(reveal * envelope.y * min(envelope.x, 1), time: time)
            main?.setIntensity(reveal * envelope.z / CosmosSceneLayers.flashPeak)
        case .flow:
            main?.setIntensity(reveal)
            strokes?.setIntensity(reveal)
            dust?.setIntensity(reveal, time: time)
            for ball in balls { ball.setIntensity(reveal) }
            for limb in limbs { limb.glow.setIntensity(reveal) }
        }
        for limb in limbs {
            let toEye = eye - limb.center
            guard simd_length(toEye) > 1e-4 else { continue }
            limb.glow.entity.orientation = simd_quatf(from: SIMD3(0, 0, 1), to: simd_normalize(toEye))
        }
        if let corona, simd_length(eye) > 1e-4 {
            corona.entity.orientation = simd_quatf(from: SIMD3(0, 0, 1), to: simd_normalize(eye))
        }
    }

    /// The star's surface keeps boiling, as Android's shader does with `time · flow`: the
    /// surface is re-baked every `churnStep` seconds in the background, and the ball
    /// cross-fades from one frame to the next. Frame 0 is the build's, so a frozen star and
    /// the first second of a live one show exactly what was built.
    ///
    /// The surface runs on its own clock, `surfaceTime`: it moves by the scene's elapsed time,
    /// never faster, and only fades toward a frame that is baked. A late bake holds the
    /// surface on its current frame, and it resumes the fade from there — it never skips a
    /// frame nor leaps ahead within one. In Low Power Mode or at a serious thermal state the
    /// surface holds too, and nothing is baked.
    private func churn(time: Float, intensity: Float) {
        guard let ball = balls.first, let first = churnFrames[0] else { return }
        let target = max(time, 0)
        if target < churnClock {
            // The clock went back (the tour came round, the scene was picked again): only
            // the build's frame is still on the way.
            cancelChurn()
            churnFrames = [0: first]
            surfaceTime = 0
            churnFrom = -1
        }
        var elapsed = target - churnClock
        churnClock = target
        if !Self.churnHeld {
            // Fade toward frame `step + 1` only once it is there, one boundary at a time.
            while elapsed > 0 {
                let step = Self.churnFrame(surfaceTime)
                guard churnFrames[step + 1] != nil else { break }
                let move = min(elapsed, Float(step + 1) * Self.churnStep - surfaceTime)
                guard move > 0 else { break }
                surfaceTime += move
                elapsed -= move
            }
        }
        let from = Self.churnFrame(surfaceTime)
        let mix = max(0, surfaceTime / Self.churnStep - Float(from))
        let a = churnFrames[from] ?? first
        if let b = churnFrames[from + 1] {
            ball.show(a, b, mix: mix, intensity: intensity)
        } else {
            ball.show(a, a, mix: 0, intensity: intensity)
        }
        if from != churnFrom {
            // Keep the frame on screen and the ones after it.
            churnFrom = from
            churnFrames = churnFrames.filter { $0.key == 0 || $0.key >= from }
        }
        guard time > 0, !Self.churnHeld, churnBaking == nil,
              let wanted = [from + 1, from + 2].first(where: { churnFrames[$0] == nil }) else { return }
        churnBaking = wanted
        let at = churnTime + Float(wanted) * Self.churnStep
        let width = Self.churnWidth
        churnTask = Task { @MainActor [weak self] in
            // Half the build's resolution: a frame is on screen for 1.5 s, mostly mid-fade.
            let image = await Task.detached(priority: .utility) {
                Plasma.surface(.star, time: at, gain: CosmosSceneLayers.starPulse,
                               width: width, height: width / 2).image
            }.value
            guard let self, !Task.isCancelled else { return }
            let texture = try? await GlowEntity.texture(image)
            guard !Task.isCancelled else { return }
            if let texture { self.churnFrames[wanted] = texture }
            self.churnBaking = nil
            self.churnTask = nil
        }
    }

    /// Stops the surface bake in flight; the next `update` starts it again.
    func cancelChurn() {
        churnTask?.cancel()
        churnTask = nil
        churnBaking = nil
    }

    /// Width of a churned surface frame; the build's frame 0 keeps the full 1024.
    static let churnWidth = 512

    /// The frame a surface time sits on or after; a time that landed on a boundary by
    /// summation counts as that boundary's frame despite rounding.
    private static func churnFrame(_ surfaceTime: Float) -> Int {
        Int((surfaceTime / churnStep + 1e-4).rounded(.down))
    }

    /// Low Power Mode or a serious thermal state: the surface holds its frame, nothing is baked.
    private static var churnHeld: Bool {
        let info = ProcessInfo.processInfo
        return info.isLowPowerModeEnabled || info.thermalState.rawValue >= ProcessInfo.ThermalState.serious.rawValue
    }
}

/// Calls `tick` once per display refresh, following ProMotion's rate.
@MainActor
final class CosmosFrameLink {
    private let target: Target
    private let link: CADisplayLink?

    init(_ tick: @escaping @MainActor () -> Void) {
        target = Target(tick)
        #if os(macOS)
        // UIKit's `CADisplayLink(target:selector:)` is unavailable on macOS; AppKit vends the
        // link from the screen it follows (macOS 14+).
        link = (NSScreen.main ?? NSScreen.screens.first)?.displayLink(target: target, selector: #selector(Target.step))
        #else
        link = CADisplayLink(target: target, selector: #selector(Target.step))
        #endif
        link?.preferredFrameRateRange = CAFrameRateRange(minimum: 30, maximum: 120, preferred: 120)
        link?.add(to: .main, forMode: .common)
    }

    func invalidate() { link?.invalidate() }

    /// `CADisplayLink` needs an Objective-C target; it retains it, not the other way round.
    /// The link fires on the main run loop it was added to.
    @MainActor
    private final class Target: NSObject {
        private let tick: @MainActor () -> Void
        init(_ tick: @escaping @MainActor () -> Void) { self.tick = tick }
        @objc func step() { tick() }
    }
}

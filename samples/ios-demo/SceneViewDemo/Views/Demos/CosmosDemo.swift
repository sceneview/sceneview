import SwiftUI
import RealityKit
import Metal
import SceneViewSwift

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
                                            minPixels: 1.4) { -$0 }
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
        let pose = CosmosFraming.pose(kind, time: time, aspect: aspect)
        let view = GlowView(eye: Self.eye(pose), focal: focal)
        let started = Date()
        let layers = await Task.detached(priority: .userInitiated) {
            CosmosSceneLayers.build(kind, view: view, time: time)
        }.value
        do {
            let entities = try await CosmosSceneEntities(kind: kind, layers: layers, programs: programs)
            NSLog("[Cosmos] %@ built in %.2f s", kind.label, Date().timeIntervalSince(started))
            entities.root.isEnabled = false
            world.addChild(entities.root)
            built[kind] = entities
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
        let pose = CosmosFraming.pose(current, time: time, aspect: aspect)
        placeWorld(pose)

        starField?.setIntensity(reveal * (current == .flow ? 0.35 : 1))
        entities.update(time: time, reveal: reveal, bloom: bloom, eye: Self.eye(pose))
    }

    private static func eye(_ pose: (distance: Float, elevation: Float, yaw: Float)) -> SIMD3<Float> {
        SIMD3<Float>(
            pose.distance * cos(pose.elevation) * sin(pose.yaw),
            pose.distance * sin(pose.elevation),
            pose.distance * cos(pose.elevation) * cos(pose.yaw)
        )
    }

    /// Moves the world so that SceneView's fixed camera sees it from the Android pose.
    private func placeWorld(_ pose: (distance: Float, elevation: Float, yaw: Float)) {
        let desired = Self.lookAt(eye: Self.eye(pose), target: .zero)
        let camera = Self.lookAt(eye: Self.fixedCamera.cameraPosition(), target: Self.fixedCamera.target)
        world.transform = Transform(matrix: camera * desired.inverse)
    }

    /// World matrix of a camera at `eye` looking at `target`, Y up (RealityKit looks down −Z).
    private static func lookAt(eye: SIMD3<Float>, target: SIMD3<Float>) -> simd_float4x4 {
        let z = simd_normalize(eye - target)
        let x = simd_normalize(simd_cross(SIMD3<Float>(0, 1, 0), z))
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
                main: GlowBuilder.sprites(CosmosMeshes.galaxy(), view: view, minPixels: 1.0, facing: up),
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
                strokes: GlowBuilder.ribbons(CosmosMeshes.prominences(), view: spunView, minPixels: 1.0,
                                             dash: prominenceDash, time: time, tailTaper: 0.2),
                plasma: surface.image,
                spheres: [CosmosSphere(center: .zero, radius: 1,
                                       limb: GlowBuilder.limb(limb, distance: simd_length(view.eye), resolution: 256))]
            )
        case .burst:
            return CosmosSceneLayers(
                main: GlowBuilder.sprites(CosmosMeshes.burstCore(), view: view, minPixels: 1.1,
                                          gain: flashPeak, facing: facing),
                strokes: GlowBuilder.ribbons(CosmosMeshes.burst(), view: view, minPixels: 1.0,
                                             dash: burstDash, time: time, tailTaper: 0.35),
                dust: GlowBuilder.sprites(CosmosMeshes.burstSparks(), view: view, minPixels: 1.6,
                                          facing: facing),
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
                strokes: GlowBuilder.ribbons(CosmosMeshes.flowField(), view: view, minPixels: 1.0,
                                             dash: flowDash.scaled(flowGain), time: time, tailTaper: 0),
                dust: GlowBuilder.sprites(CosmosMeshes.flowDust(), view: view, minPixels: 1.3, facing: facing),
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

    init(kind: CosmosSceneKind, layers: CosmosSceneLayers, programs: CosmosPrograms) async throws {
        self.kind = kind
        let program = programs.additive
        if let layer = layers.strokes { strokes = try await GlowEntity(layer, program: program) }
        if let layer = layers.main { main = try await GlowEntity(layer, program: program) }
        if let layer = layers.halo { halo = try await GlowEntity(layer, program: program) }
        if let layer = layers.dust {
            dust = try await GlowEntity(layer, program: kind == .galaxy ? programs.alpha : program)
        }
        if let image = layers.plasma {
            let texture = try await GlowEntity.texture(image)
            for sphere in layers.spheres {
                let ball = PlasmaEntity(texture: texture, radius: sphere.radius)
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
            for glow in [main].compactMap({ $0 }) + limbs.map(\.glow) { root.addChild(glow.entity) }
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

    /// - Parameter eye: where the camera is, in this scene's coordinates.
    func update(time: Float, reveal: Float, bloom: Float, eye: SIMD3<Float>) {
        switch kind {
        case .galaxy:
            root.orientation = CosmosSceneLayers.galaxyOrientation(time)
            main?.setIntensity(reveal)
            dust?.setOpacity(reveal)
        case .star:
            let pulse = sin(time * 2.1)
            spin.orientation = CosmosSceneLayers.starOrientation(time)
            spin.scale = SIMD3(repeating: 1 + 0.012 * pulse)
            let surface = reveal * (1 + 0.08 * pulse) / CosmosSceneLayers.starPulse
            for ball in balls { ball.setIntensity(surface) }
            for limb in limbs {
                limb.glow.setIntensity(surface)
                limb.glow.entity.scale = spin.scale
            }
            strokes?.setIntensity(reveal)
            main?.setIntensity(reveal * (1 + 0.12 * pulse) / CosmosSceneLayers.haloPulse)
        case .burst:
            let envelope = CosmosMeshes.burstEnvelope((time / CosmosEngine.burstPeriod).truncatingRemainder(dividingBy: 1))
            strokes?.reveal(upTo: envelope.x)
            strokes?.setIntensity(reveal * envelope.y)
            halo?.reveal(upTo: envelope.x)
            halo?.setIntensity(reveal * envelope.y * bloom)
            dust?.setIntensity(reveal * envelope.y * min(envelope.x, 1))
            main?.setIntensity(reveal * envelope.z / CosmosSceneLayers.flashPeak)
        case .flow:
            main?.setIntensity(reveal)
            strokes?.setIntensity(reveal)
            dust?.setIntensity(reveal)
            for ball in balls { ball.setIntensity(reveal) }
            for limb in limbs { limb.glow.setIntensity(reveal) }
        }
        for limb in limbs {
            let toEye = eye - limb.center
            guard simd_length(toEye) > 1e-4 else { continue }
            limb.glow.entity.orientation = simd_quatf(from: SIMD3(0, 0, 1), to: simd_normalize(toEye))
        }
    }
}

/// Calls `tick` once per display refresh, following ProMotion's rate.
@MainActor
final class CosmosFrameLink {
    private let target: Target
    private let link: CADisplayLink

    init(_ tick: @escaping @MainActor () -> Void) {
        target = Target(tick)
        link = CADisplayLink(target: target, selector: #selector(Target.step))
        link.preferredFrameRateRange = CAFrameRateRange(minimum: 30, maximum: 120, preferred: 120)
        link.add(to: .main, forMode: .common)
    }

    func invalidate() { link.invalidate() }

    /// `CADisplayLink` needs an Objective-C target; it retains it, not the other way round.
    /// The link fires on the main run loop it was added to.
    @MainActor
    private final class Target: NSObject {
        private let tick: @MainActor () -> Void
        init(_ tick: @escaping @MainActor () -> Void) { self.tick = tick }
        @objc func step() { tick() }
    }
}

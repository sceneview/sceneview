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
                .bloom(BloomOptions(strength: engine.bloomStrength, levels: 7, resolution: 512, threshold: true,
                                    thresholdLevel: engine.bloomThreshold))
                .simultaneousGesture(SpatialTapGesture().onEnded { tap in
                    engine.tap(tap.location, in: geometry.size, minRadius: Float(SceneViewTokens.Space.xl))
                })
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
            // `?tab=starlight|spacetime` (or `0|1`, as Android): open on the Star scene's view, once.
            if let tab = DeepLinkRouter.consumeTab(for: "cosmos"),
               let spacetime = ["starlight": false, "0": false, "spacetime": true, "1": true][tab] {
                engine.scene = .star
                engine.setSpacetime(spacetime)
            }
            engine.start()
        }
        .onDisappear { engine.stop() }
        .onChange(of: qaMode) { _, qa in engine.frozen = qa || reduceMotion }
        .onChange(of: reduceMotion) { _, reduce in engine.frozen = qaMode || reduce }
        .demoChrome(
            dock: CosmosSceneKind.allCases.map { kind in
                DockItem(icon: kind.icon, label: kind.label, control: kind.analyticsControl,
                         selected: engine.scene == kind) {
                    engine.select(kind)
                }
            },
            onReset: {
                engine.setSpacetime(false)
                engine.touring = !engine.frozen
                engine.animating = true
                engine.bloom = CosmosEngine.defaultBloom
            },
            accessory: {
                VStack(spacing: SceneViewTokens.Space.sm) {
                    DemoHint(engine.caption)
                    // The Star scene's two views, from its first frame — the voyage included.
                    if engine.scene == .star {
                        SpacetimeModePicker(spacetime: Binding(get: { engine.spacetime },
                                                               set: { engine.setSpacetime($0) }))
                    }
                }
            }
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
    /// What the Star scene's camera looks at; a tap on the planet or the star flies to it.
    private(set) var focus: CosmosFocus = .system
    private(set) var ready = false
    /// The Star scene's Spacetime mode: the star and its planets resting in the wells they dig
    /// into a sheet (`CosmosSpacetime`). Set with `setSpacetime(_:)`.
    private(set) var spacetime = false
    /// How far the bloom has moved to the Spacetime mode's, 0…1, in 1/50 steps: the view
    /// updates its bloom pass only when this changes.
    private(set) var bloomMix: Float = 0
    /// The bloom pass: the slider's strength, eased to the Spacetime mode's soft 0.1 with a
    /// higher threshold, so the lit sheet does not haze over.
    var bloomStrength: Float { CosmosSystem.mix(bloom, Self.spacetimeBloom, bloomMix) }
    var bloomThreshold: Float { CosmosSystem.mix(0.6, 0.8, bloomMix) }
    static let spacetimeBloom: Float = 0.1

    var caption: String {
        if scene != .star { return scene.caption }
        return spacetime ? "Spacetime: every mass bends the fabric" : focus.caption
    }

    @ObservationIgnored var viewport = CGSize(width: 1206, height: 2622)
    /// The ringed world's orbit for the current viewport aspect, and the Star camera's flight.
    @ObservationIgnored private var system = CosmosSystem(aspect: 1206 / 2622)
    @ObservationIgnored private var flight = CosmosFlight()
    @ObservationIgnored private var autopilot = CosmosAutopilot()
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
    /// Where the Spacetime transition is, in seconds of `CosmosSpacetime.Timeline`: 0 is
    /// Starlight, `duration` the settled sheet.
    @ObservationIgnored private var spacetimeProgress: Double = 0
    /// The pose the camera left from to fly to the sheet.
    @ObservationIgnored private var spacetimeFrom: CosmosPose?

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

    /// Enters or leaves the Star scene's Spacetime mode. It holds still: the tour stops.
    func setSpacetime(_ on: Bool) {
        guard on != spacetime else { return }
        spacetime = on
        if on {
            touring = false
            spacetimeFrom = flight.lastPose
        }
    }

    /// A tap at `location` on a `size` viewport, both in points. In the Star scene it flies the
    /// camera to what it lands on — the ringed world first, then the star; a second tap on what
    /// is already in focus, or a tap on empty space, pulls back to the whole system. Any tap
    /// hands the camera to the user until the autopilot's `resume` delay has passed idle.
    func tap(_ location: CGPoint, in size: CGSize, minRadius: Float) {
        guard scene == .star, !spacetime, spacetimeProgress == 0, let pose = flight.lastPose else { return }
        autopilot.touched()
        let hit = system.hit(pose: pose, time: flight.lastTime,
                             width: Float(size.width), height: Float(size.height),
                             x: Float(location.x), y: Float(location.y), minRadius: minRadius)
        let next = (hit == nil || hit == focus) ? .system : hit!
        if next != focus {
            flight.start()
            focus = next
        }
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
        guard built[kind] == nil, !building.contains(kind) else { return }
        building.insert(kind)
        defer { building.remove(kind) }
        guard let (entities, landscape) = await makeEntities(kind) else { return }
        entities.root.isEnabled = false
        world.addChild(entities.root)
        built[kind] = entities
        if kind == .flow { flowLandscape = landscape }
    }

    /// Lays out `kind` for the current viewport, off the main thread; `nil` if it failed.
    private func makeEntities(_ kind: CosmosSceneKind) async -> (CosmosSceneEntities, landscape: Bool)? {
        guard let programs else { return nil }
        // Quads face the camera of the scene's resting pose; its sway is a few degrees.
        let time = Self.qaTime[kind.rawValue]
        let landscape = aspect > 1
        let view = GlowView(eye: camera(kind, time: time, rolled: landscape).eye, focal: focal)
        let started = Date()
        let layers = await Task.detached(priority: .userInitiated) {
            CosmosSceneLayers.build(kind, view: view, time: time)
        }.value
        do {
            let entities = try await CosmosSceneEntities(kind: kind, layers: layers, programs: programs, time: time,
                                                         restEye: view.eye, focal: view.focal, system: system)
            NSLog("[Cosmos] %@ built in %.2f s", kind.label, Date().timeIntervalSince(started))
            return (entities, landscape)
        } catch {
            NSLog("[Cosmos] building \(kind) failed: \(error)")
            return nil
        }
    }

    /// The viewport turned across square: lays the flow out again for the new framing while
    /// the old one stays on screen, then swaps them in one tick — the scene's clock, its
    /// reveal and the tour timer carry on untouched.
    private func rebuildFlow() {
        guard programs != nil, !building.contains(.flow) else { return }
        building.insert(.flow)
        Task { @MainActor in
            defer { building.remove(.flow) }
            guard let (entities, landscape) = await makeEntities(.flow) else { return }
            entities.root.isEnabled = false
            world.addChild(entities.root)
            built[.flow]?.cancelChurn()
            built[.flow]?.root.removeFromParent()
            built[.flow] = entities
            flowLandscape = landscape
            // Frame the successor now, so no frame renders it with the old camera roll.
            if frameLink != nil { tick() }
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
            focus = .system
            flight.reset()
            autopilot.reset()
            // Another scene leaves the Spacetime mode; the Star scene opened on it lands at once
            // if frozen, else plays the way in.
            if current != .star { spacetime = false }
            spacetimeProgress = spacetime && frozen ? CosmosSpacetime.Timeline.duration : 0
            spacetimeFrom = nil
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
        if let landscape = flowLandscape, landscape != (aspect > 1) {
            if current == .flow {
                // The flow's quads face the other framing; it keeps showing, in that framing,
                // until its successor lands.
                rebuildFlow()
            } else if !building.contains(.flow) {
                // Off screen: drop it, the next visit builds it for the viewport it meets.
                built[.flow]?.root.removeFromParent()
                built[.flow] = nil
                flowLandscape = nil
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

        let progress = current == .star ? advanceSpacetime(dt) : 0
        let time = frozen ? (progress > 0 ? CosmosSpacetime.qaTime : Self.qaTime[current.rawValue]) : sceneTime
        let reveal = frozen ? 1 : CosmosFraming.reveal(sceneTime, duration: Self.revealSeconds)
        if abs(system.aspect - aspect) > 1e-4 { system = CosmosSystem(aspect: aspect) }
        let pose: CosmosPose
        if current == .star && progress > 0 {
            pose = spacetimePose(progress: progress, time: time)
        } else if current == .star {
            // Left alone, the camera flies on by itself — ringed world, star, whole system — on
            // the scene's clock, so it pauses with Animate; frozen frames keep their look.
            if animating && !frozen && !spacetime,
               let next = autopilot.advance(min(max(dt, 0), 0.1), flying: flight.flying, focus: focus) {
                flight.start()
                focus = next
            }
            // QA captures and reduced motion show where a flight lands, not a frame of it — once
            // the world is lit for it: until then the camera stays where it is, rather than
            // show the planet with the light of the view it left.
            var target = system.pose(focus, time: time)
            if frozen, let held = flight.lastPose, target != held,
               !entities.prelight(system: system, time: time, eye: target.eye, now: CACurrentMediaTime()) {
                target = held
            }
            pose = flight.advance(now: CACurrentMediaTime(), target: target, instant: frozen)
        } else {
            let camera = camera(current, time: time)
            pose = CosmosPose(eye: camera.eye, target: .zero, up: camera.up)
        }
        flight.record(pose, time: time)
        placeWorld(pose)

        let stars = Float(CosmosSpacetime.Timeline.starField(progress))
        starField?.setIntensity(reveal * (current == .flow ? 0.35 : 1) * stars, time: time)
        let mix = (Float(CosmosSpacetime.Timeline.settle(progress)) * 50).rounded() / 50
        if mix != bloomMix { bloomMix = mix }
        entities.update(time: time, reveal: reveal, bloom: bloom, eye: pose.eye, live: !frozen,
                        system: system, focal: focal, now: CACurrentMediaTime(), spacetime: progress)
    }

    /// Moves the Spacetime transition on by `dt` towards the mode chosen — at once when frozen —
    /// and returns where it is. Back at Starlight, the camera hands over to the system view.
    private func advanceSpacetime(_ dt: Float) -> Double {
        typealias T = CosmosSpacetime.Timeline
        let before = spacetimeProgress
        let target = spacetime ? T.duration : 0
        if frozen {
            spacetimeProgress = target
        } else {
            let step = Double(min(max(dt, 0), 0.1))
            spacetimeProgress = spacetime ? min(before + step, target)
                : max(before - step * T.duration / T.exitSeconds, 0)
        }
        if before > 0, spacetimeProgress == 0 {
            focus = .system
            flight.reset()
            autopilot.reset()
            spacetimeFrom = nil
        }
        return spacetimeProgress
    }

    /// The camera during and in the Spacetime mode: it flies from where it was to the sheet's
    /// fixed pose over the first `Timeline.flight` seconds, and back to the system view on the
    /// way out.
    private func spacetimePose(progress: Double, time: Float) -> CosmosPose {
        typealias T = CosmosSpacetime.Timeline
        let sheet = CosmosSpacetime.pose(aspect: Double(aspect),
                                         tanHalfVertical: Double(CosmosFraming.tanHalfVerticalFov))
        let flown = Float(min(progress / T.flight, 1))
        guard flown < 1 else { return sheet }
        if spacetime {
            if spacetimeFrom == nil { spacetimeFrom = flight.lastPose ?? system.systemPose(time) }
            return CosmosSystem.blend(spacetimeFrom!, sheet, CosmosSystem.easeExpressive(flown))
        }
        return CosmosSystem.blend(sheet, system.systemPose(time), CosmosSystem.easeExpressive(1 - flown))
    }

    /// Where `kind`'s camera is at `time`, and which way is up on screen. The flow field is
    /// portrait: on a landscape viewport the camera rolls a quarter turn — (x, y) → (−y, x),
    /// as on Android — so its long side runs along the screen's. `rolled` defaults to the
    /// framing the built flow was laid out for, which lags the viewport during a rebuild.
    private func camera(_ kind: CosmosSceneKind, time: Float,
                        rolled: Bool? = nil) -> (eye: SIMD3<Float>, up: SIMD3<Float>) {
        let rolled = kind == .flow && (rolled ?? flowLandscape ?? (aspect > 1))
        let pose = CosmosFraming.pose(kind, time: time, aspect: aspect, rolled: rolled)
        let eye = CosmosFraming.orbit(pose.distance, pose.elevation, pose.yaw)
        guard rolled else { return (eye, SIMD3(0, 1, 0)) }
        return (SIMD3(-eye.y, eye.x, eye.z), SIMD3(-1, 0, 0))
    }

    /// Moves the world so that SceneView's fixed camera sees it from the Android pose.
    private func placeWorld(_ pose: CosmosPose) {
        let desired = Self.lookAt(eye: pose.eye, target: pose.target, up: pose.up)
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
    /// The star's magnetic loops ride the same HDR haze on Android, over its bright corona:
    /// measured on the overview and the close-up, half as many of their pixels stood out from
    /// the glow here. They are lifted to Android's count, and the dashes laid with it.
    static let loopGain: Float = 1.8
    static let loopDash = prominenceDash.scaled(loopGain)

    /// The star's wide blue glow at `r` silhouette radii, fitted to the Android capture's
    /// rings at 1.1, 1.3, 1.6 and 2 radii (minus what the halo sprites already give), and
    /// baked at the halo's pulse peak. Past 2 radii it tapers to nothing by 4.8, as the
    /// Android overview's glow does (measured out to 4.5 radii), well inside the quad.
    static func starCorona(_ r: Float) -> SIMD3<Float> {
        guard r > 0.9 else { return .zero }
        let out = max(r - 1, 0)
        let blue = 0.5 * exp(-pow(out / 0.9, 1.3)) * (1 - CosmosSystem.smoothstep(2, 4.8, r))
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
                                                   dash: loopDash, tailTaper: 0.2),
                plasma: surface.image,
                spheres: [CosmosSphere(center: .zero, radius: 1,
                                       limb: GlowBuilder.limb(limb, distance: simd_length(view.eye), resolution: 256))],
                corona: GlowBuilder.corona(radius: 1, distance: simd_length(view.eye), extent: 5,
                                           resolution: 384, falloff: starCorona)
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
    /// The eye the scene's sprites and discs were baked for: the Star camera flies away from it.
    private let restEye: SIMD3<Float>
    /// The Star scene's ringed world, its rings and its orbit trail.
    private var world: CosmosWorld?
    /// The Star scene's Spacetime sheet and small planets, built the first time the mode is
    /// asked for.
    private var spacetimeScene: CosmosSpacetimeScene?
    private var spacetimeTask: Task<Void, Never>?
    /// The eye, as the turned star sees it, and the lens the loops were last laid for; see
    /// `relayLoops`.
    private var loopsEye: SIMD3<Float>
    private var loopsFocal: Float
    private var loopProgram: UnlitMaterial.Program?
    private var loopsTask: Task<Void, Never>?
    /// The last frame's clock and reveal, for a relaid set to take over without a flash.
    private var loopsTime: Float = 0
    private var loopsReveal: Float = 0
    /// A relaid set in the scene but shrunk out of sight, and when it takes over (monotonic
    /// clock): RealityKit draws a mesh uploaded in the last frames only some of the time.
    private var pendingLoops: (glow: GlowEntity, eye: SIMD3<Float>, focal: Float, at: Double)?

    /// How far the eye may move from the loops' layout before they are laid again: a quarter
    /// nearer or farther, or 20° round the star (5 s of its turn).
    static let loopsRelayRatio: Float = 1.25
    static let loopsRelayTurn: Float = 20 * .pi / 180

    /// Seconds between two baked frames of the star's surface; the ball cross-fades between them.
    static let churnStep: Float = 1.5

    init(kind: CosmosSceneKind, layers: CosmosSceneLayers, programs: CosmosPrograms, time: Float,
         restEye: SIMD3<Float>, focal: Float, system: CosmosSystem) async throws {
        self.kind = kind
        churnTime = time
        self.restEye = restEye
        loopsEye = CosmosSceneLayers.starOrientation(time).inverse.act(restEye)
        loopsFocal = focal
        let program = programs.additive
        if kind == .star { loopProgram = program }
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
            // Its world opens on the whole system: lit for that view before its first frame.
            let world = try await CosmosWorld(programs: programs,
                                              light: CosmosWorldLight(system: system, time: time,
                                                                      eye: system.systemPose(time).eye),
                                              time: time)
            root.addChild(world.planet)
            root.addChild(world.trail)
            self.world = world
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

    /// Whether the Star scene's world is lit for a camera at `eye`, starting that bake if not
    /// (see `CosmosWorld.prelight`). Scenes without a world are always ready.
    func prelight(system: CosmosSystem, time: Float, eye: SIMD3<Float>, now: Double) -> Bool {
        world?.prelight(system: system, time: time, eye: eye, now: now) ?? true
    }

    /// - Parameters:
    ///   - eye: where the camera is, in this scene's coordinates.
    ///   - live: the clock runs (not a frozen QA or reduced-motion frame), so the star churns.
    ///   - system, focal, now: the ringed world's orbit, the lens and a monotonic clock in
    ///     seconds, for the Star scene's world.
    ///   - spacetime: where the Star scene's Spacetime transition is, in seconds of
    ///     `CosmosSpacetime.Timeline`; 0 is Starlight.
    func update(time: Float, reveal: Float, bloom: Float, eye: SIMD3<Float>, live: Bool,
                system: CosmosSystem, focal: Float, now: Double, spacetime: Double = 0) {
        strokes?.scrollDashes(time: time)
        switch kind {
        case .galaxy:
            root.orientation = CosmosSceneLayers.galaxyOrientation(time)
            main?.setIntensity(reveal, time: time)
            dust?.setOpacity(reveal)
        case .star:
            typealias T = CosmosSpacetime.Timeline
            let pulse = sin(time * 2.1)
            // Spacetime: the star shrinks to its place in the sheet's well and its halo fades.
            let settle = Float(T.settle(spacetime))
            let size = CosmosSystem.mix(1, CosmosSpacetime.starScale, settle)
            let halo = Float(T.haloAlpha(spacetime))
            spin.orientation = CosmosSceneLayers.starOrientation(time)
            spin.scale = SIMD3(repeating: size * (1 + 0.012 * pulse))
            let surface = reveal * (1 + 0.08 * pulse) / CosmosSceneLayers.starPulse
            churn(time: live ? time : 0, intensity: surface)
            // The camera flies: the limb disc and the corona were sized for the rest distance,
            // so they are resized for the one the eye is at, and the halo — concentric sprites
            // baked facing the rest eye — turns as a whole to face it.
            // A star scaled by `size` seen from `distance` looks as one of radius 1 from `distance / size`.
            let distance = max(simd_length(eye) / size, 1.01)
            let rest = max(simd_length(restEye), 1.01)
            let limbScale = Self.limbSilhouette(distance) / Self.limbSilhouette(rest)
            let coronaScale = size * Self.coronaSilhouette(distance) / Self.coronaSilhouette(rest)
            for limb in limbs {
                limb.glow.setIntensity(surface)
                limb.glow.entity.scale = spin.scale * SIMD3(limbScale, limbScale, 1)
            }
            corona?.entity.scale = SIMD3(repeating: coronaScale)
            if let main {
                main.entity.orientation = simd_quatf(from: simd_normalize(restEye), to: simd_normalize(eye))
                main.entity.scale = SIMD3(repeating: size)
            }
            strokes?.setIntensity(reveal * halo)
            // The loops are laid for the free camera; the sheet's camera does not fly, so they
            // stay as they are while the mode is on and are laid again once it is left.
            if spacetime == 0 {
                relayLoops(eye: eye, time: time, focal: focal, reveal: reveal, now: now)
            }
            main?.setIntensity(reveal * halo * (1 + 0.12 * pulse) / CosmosSceneLayers.haloPulse)
            corona?.setIntensity(reveal * halo * (1 + 0.12 * pulse) / CosmosSceneLayers.haloPulse)
            updateSpacetime(spacetime, time: time, system: system, eye: eye, reveal: reveal, now: now,
                            focal: focal, halo: halo)
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

    /// The Star scene's ringed world, and — while the Spacetime mode shows — the sheet and the
    /// small planets. The ringed world leaves its orbit for its well in the sheet, its rings
    /// turned to lie along the slope, lit from the sheet's sun rather than the star.
    private func updateSpacetime(_ progress: Double, time: Float, system: CosmosSystem, eye: SIMD3<Float>,
                                 reveal: Float, now: Double, focal: Float, halo: Float) {
        typealias T = CosmosSpacetime.Timeline
        guard progress > 0 else {
            spacetimeScene?.root.isEnabled = false
            world?.update(system: system, time: time, eye: eye, reveal: reveal, now: now, focal: focal)
            // Built while Starlight shows, so the tap on Spacetime doesn't wait for it.
            buildSpacetimeScene()
            return
        }
        let settle = Float(T.settle(progress))
        let layout = CosmosSpacetime.Sheet(time: Double(time), weight: T.weight(progress), lift: T.lift(progress))
        let at = CosmosSpacetime.position(.ringed, time: Double(time))
        let rested = SIMD3<Float>(Float(at.x), Float(layout.rest(.ringed, at: at)), Float(at.y))
        let plane = layout.ringPlane(at: at).normal
        let restTilt = simd_quatf(from: SIMD3(0, 1, 0), to: simd_normalize(SIMD3<Float>(plane)))
        let orbit = system.planetPosition(time)
        let center = orbit + (rested - orbit) * settle
        let tilt = simd_slerp(system.tilt, restTilt, settle)
        let light = SIMD3<Float>(CosmosSpacetime.light)
        let sun = center + 1000 * CosmosSystem.slerp(simd_normalize(-orbit), light, settle)
        world?.update(system: system, time: time, eye: eye, reveal: reveal, now: now, focal: focal,
                      placement: CosmosWorldPlacement(center: center, tilt: tilt, sun: sun), trailFade: halo)

        guard let spacetimeScene else {
            buildSpacetimeScene()
            return
        }
        spacetimeScene.root.isEnabled = true
        spacetimeScene.update(time: time, progress: progress, sheet: layout,
                              ringed: (center, tilt.act(SIMD3(0, 1, 0))), now: now)
    }

    /// Builds the sheet and the small planets once, in the background, hidden until the mode shows.
    private func buildSpacetimeScene() {
        guard spacetimeScene == nil, spacetimeTask == nil else { return }
        spacetimeTask = Task { @MainActor [weak self] in
            let started = Date()
            let scene: CosmosSpacetimeScene
            do {
                scene = try await CosmosSpacetimeScene.make()
            } catch {
                NSLog("[Cosmos] spacetime build failed: %@", String(describing: error))
                return
            }
            guard let self else { return }
            NSLog("[Cosmos] spacetime built in %.3f s", Date().timeIntervalSince(started))
            scene.root.isEnabled = false
            self.root.addChild(scene.root)
            self.spacetimeScene = scene
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
            guard let self else { return }
            // The bake has ended, cancelled or not: only now may the next one start, so a
            // clock reset never leaves two plasma bakes running at once.
            defer {
                self.churnBaking = nil
                self.churnTask = nil
            }
            guard !Task.isCancelled else { return }
            let texture = try? await GlowEntity.texture(image)
            guard !Task.isCancelled, let texture else { return }
            self.churnFrames[wanted] = texture
        }
    }

    /// The loops are ribbons laid across the line of sight, at least a pixel wide, for one eye —
    /// Android's shader lays them for the live camera every frame. When the camera has flown
    /// far from the eye they were laid for (the overview is twice the close-up's distance), or
    /// the star has turned them well away from it, they are laid again off the main thread and
    /// swapped in whole, so they read as thin bright filaments from wherever they are seen.
    /// The new set waits in the scene, shrunk out of sight, for `CosmosWorld.trailSettleSeconds`
    /// before it takes over, as the orbit trail's ribbons do.
    private func relayLoops(eye: SIMD3<Float>, time: Float, focal: Float, reveal: Float, now: Double) {
        loopsTime = time
        loopsReveal = reveal
        if let pending = pendingLoops {
            pending.glow.scrollDashes(time: time)
            pending.glow.setIntensity(reveal)
            guard now >= pending.at else { return }
            pending.glow.entity.scale = .one
            strokes?.entity.removeFromParent()
            strokes = pending.glow
            loopsEye = pending.eye
            loopsFocal = pending.focal
            pendingLoops = nil
            NSLog("[Cosmos] loops swapped in")
            return
        }
        guard loopsTask == nil, let loopProgram, focal > 0 else { return }
        // The loops turn with the star: compare eyes as the turned star sees them.
        let seen = CosmosSceneLayers.starOrientation(time).inverse.act(eye)
        let laid = loopsEye
        let ratio = simd_length(seen) / max(simd_length(laid), 1e-3)
        let turn = acos(min(max(simd_dot(simd_normalize(seen), simd_normalize(laid)), -1), 1))
        // Low Power Mode or a hot device: only a flight lays them again, not the star's turn.
        let turned = turn > Self.loopsRelayTurn && !CosmosPowerState.shared.constrained
        let far = abs(log(ratio)) > log(Self.loopsRelayRatio) || turned
        let lens = abs(focal - loopsFocal) > 0.05 * loopsFocal
        guard far || lens else { return }
        loopsTask = Task { @MainActor [weak self] in
            let view = GlowView(eye: seen, focal: focal)
            let started = Date()
            let layer = await Task.detached(priority: .userInitiated) {
                GlowBuilder.dashedRibbons(CosmosMeshes.prominences(), view: view, minPixels: 1.0,
                                          dash: CosmosSceneLayers.loopDash, tailTaper: 0.2)
            }.value
            NSLog("[Cosmos] loops laid again in %.3f s", Date().timeIntervalSince(started))
            guard let self else { return }
            defer { self.loopsTask = nil }
            guard !Task.isCancelled, let fresh = try? await GlowEntity(layer, program: loopProgram),
                  !Task.isCancelled else { return }
            fresh.scrollDashes(time: self.loopsTime)
            fresh.setIntensity(self.loopsReveal)
            fresh.entity.scale = SIMD3(repeating: CosmosWorld.hiddenScale)
            self.spin.addChild(fresh.entity)
            self.pendingLoops = (fresh, seen, focal, CACurrentMediaTime() + CosmosWorld.trailSettleSeconds)
        }
    }

    /// Drops the surface bake in flight. The detached plasma loop cannot be interrupted, so
    /// `churnBaking` stays set until it returns; the next `update` after that bakes again.
    func cancelChurn() {
        churnTask?.cancel()
        loopsTask?.cancel()
        pendingLoops?.glow.entity.removeFromParent()
        pendingLoops = nil
    }

    /// The star's silhouette radius on the plane of its limb disc (`GlowBuilder.limb`) seen
    /// from `distance`: the disc stays in that plane and is scaled across to cover it.
    private static func limbSilhouette(_ distance: Float) -> Float {
        (distance - 1.02) * tan(asin(min(1 / distance, 0.999)))
    }

    /// The star's silhouette radius on the corona's plane through its centre
    /// (`GlowBuilder.corona`) seen from `distance`.
    private static func coronaSilhouette(_ distance: Float) -> Float {
        1 / max(1 - 1 / (distance * distance), 1e-3).squareRoot()
    }

    /// Width of a churned surface frame; the build's frame 0 keeps the full 1024.
    static let churnWidth = 512

    /// The frame a surface time sits on or after; a time that landed on a boundary by
    /// summation counts as that boundary's frame despite rounding.
    private static func churnFrame(_ surfaceTime: Float) -> Int {
        Int((surfaceTime / churnStep + 1e-4).rounded(.down))
    }

    /// Low Power Mode or a serious thermal state: the surface holds its frame, nothing is baked.
    private static var churnHeld: Bool { CosmosPowerState.shared.constrained }
}

/// Low Power Mode or a serious thermal state, read once and then kept current by the two
/// system notifications instead of being queried on every frame.
@MainActor
final class CosmosPowerState {
    static let shared = CosmosPowerState()
    private(set) var constrained = CosmosPowerState.read()
    private var observers: [NSObjectProtocol] = []

    private init() {
        let names = [ProcessInfo.thermalStateDidChangeNotification, Notification.Name.NSProcessInfoPowerStateDidChange]
        observers = names.map { name in
            NotificationCenter.default.addObserver(forName: name, object: nil, queue: .main) { _ in
                MainActor.assumeIsolated { CosmosPowerState.shared.constrained = CosmosPowerState.read() }
            }
        }
    }

    private nonisolated static func read() -> Bool {
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

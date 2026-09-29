import SwiftUI
import RealityKit
import Metal
import SceneViewSwift

/// **Cosmos** — four procedural, real-time space scenes lit by nothing but their own light
/// and a bloom pass: a barred spiral galaxy, a plasma star, a particle-track burst and a
/// vortex flow field. The iOS twin of the Android demo, built from the same seeds and the
/// same shader maths (`CosmosMeshes.swift`, `CosmosShaders.metal`).
///
/// ### The recipe it teaches
///
/// Glow is **additive, unlit, HDR geometry plus bloom**:
///
/// - Radiance above 1.0 is what the bloom pass bleeds from, so every colour is written in
///   linear HDR and `SceneView.bloom(_:)` turns it into light.
/// - An additive `CustomMaterial` (`Program.Descriptor.blendMode = .add`) makes overlapping
///   light pile up — tens of thousands of arm stars stack into a white-hot bulge with no
///   sorting at all.
/// - Points are camera-facing quads and strokes camera-facing ribbons, both expanded in a
///   geometry modifier from one static `LowLevelMesh`.
/// - The star is a sphere with a noise surface shader: domain-warped fbm, ridged filaments
///   and a Fresnel limb.
///
/// Animation is uniforms only — time, the burst's head and fade, the galaxy's spin — so a
/// frame costs a handful of material writes and no buffer upload.
struct CosmosDemo: View {
    @AppStorage(DeepLinkRouter.qaModeDefaultsKey) private var qaMode: Bool = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.displayScale) private var displayScale

    @State private var engine = CosmosEngine()
    @State private var bloom: Float = CosmosEngine.defaultBloom

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
                .bloom(BloomOptions(strength: bloom, levels: 7, threshold: true))
                if !engine.ready {
                    VStack(spacing: 12) {
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
                bloom = CosmosEngine.defaultBloom
            },
            accessory: { DemoHint("Four procedural scenes, lit only by bloom") }
        ) {
            VStack(alignment: .leading, spacing: 16) {
                LabeledSlider(label: "Bloom", value: $bloom, range: 0...1, decimals: 2)
                Toggle("Tour the scenes", isOn: $engine.touring)
                Toggle("Animate", isOn: $engine.animating)
            }
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

/// Owns the RealityKit side of the demo: the materials, the four scenes (built on first
/// use, off the main thread) and the 60 Hz tick that animates them.
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
    static let galaxySpinDegreesPerSecond: Float = 3.5
    static let starSpinDegreesPerSecond: Float = 4
    static let nucleusRadii: [Float] = [0.1, 0.075, 0.06, 0.08]

    /// SceneView's own camera is parked here; the tick moves the whole world instead, so
    /// the Android camera path (`CosmosFraming.pose`) is reproduced exactly.
    static let fixedCamera = SceneCameraPose(azimuth: 0, elevation: 0, distance: 5)

    var scene: CosmosSceneKind = .galaxy
    var touring = true
    var animating = true
    var frozen = false
    private(set) var ready = false

    @ObservationIgnored var viewport = CGSize(width: 1170, height: 2532)
    @ObservationIgnored private let world = Entity()
    @ObservationIgnored private var materials: CosmosMaterials?
    @ObservationIgnored private var starField: CosmosGlow?
    @ObservationIgnored private var built: [CosmosSceneKind: CosmosSceneEntities] = [:]
    @ObservationIgnored private var building: Set<CosmosSceneKind> = []
    @ObservationIgnored private var timer: Timer?
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

    func start() {
        guard timer == nil else { return }
        let t = Timer.scheduledTimer(withTimeInterval: 1.0 / 60.0, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { self?.tick() }
        }
        RunLoop.main.add(t, forMode: .common)
        timer = t
    }

    func stop() {
        timer?.invalidate()
        timer = nil
        loadTask?.cancel()
    }

    // MARK: Loading

    private func startLoading() {
        guard loadTask == nil else { return }
        loadTask = Task { @MainActor [weak self] in
            guard let self else { return }
            do {
                let materials = try await CosmosMaterials.make()
                self.materials = materials
                let stars = await Task.detached(priority: .userInitiated) { CosmosMeshes.starField() }.value
                let field = try CosmosGlow(mesh: stars, material: materials.starField)
                self.world.addChild(field.entity)
                self.starField = field
                // The selected scene first, then the others so a switch is instant.
                let order = [self.scene] + CosmosSceneKind.allCases.filter { $0 != self.scene }
                for kind in order where !Task.isCancelled {
                    await self.build(kind)
                }
            } catch {
                NSLog("[Cosmos] setup failed: \(error)")
            }
        }
    }

    private func ensureBuilt(_ kind: CosmosSceneKind) {
        guard materials != nil, built[kind] == nil, !building.contains(kind) else { return }
        Task { @MainActor in await build(kind) }
    }

    private func build(_ kind: CosmosSceneKind) async {
        guard let materials, built[kind] == nil, !building.contains(kind) else { return }
        building.insert(kind)
        defer { building.remove(kind) }
        let meshes = await Task.detached(priority: .userInitiated) { CosmosSceneMeshes.build(kind) }.value
        do {
            let entities = try CosmosSceneEntities(kind: kind, meshes: meshes, materials: materials)
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
        if touring && !frozen && sceneTime > Self.tourSeconds {
            let next = CosmosSceneKind.allCases[(current.rawValue + 1) % CosmosSceneKind.allCases.count]
            scene = next
            ensureBuilt(next)
            return
        }
        guard let entities = built[current] else { return }
        for (kind, other) in built { other.root.isEnabled = kind == current }
        if !ready { ready = true }

        let time = frozen ? Self.qaTime[current.rawValue] : sceneTime
        let reveal = frozen ? 1 : CosmosFraming.reveal(sceneTime, duration: Self.revealSeconds)
        let height = Float(max(viewport.height, 1))
        let aspect = Float(viewport.width / max(viewport.height, 1))
        placeWorld(CosmosFraming.pose(current, time: time, aspect: aspect))

        starField?.set(SIMD4(time, reveal * (current == .flow ? 0.35 : 1), 1, height))
        entities.update(time: time, reveal: reveal, viewportHeight: height)
    }

    /// Moves the world so that SceneView's fixed camera sees it from the Android pose.
    private func placeWorld(_ pose: (distance: Float, elevation: Float, yaw: Float)) {
        let eye = SIMD3<Float>(
            pose.distance * cos(pose.elevation) * sin(pose.yaw),
            pose.distance * sin(pose.elevation),
            pose.distance * cos(pose.elevation) * cos(pose.yaw)
        )
        let desired = Self.lookAt(eye: eye, target: .zero)
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

// MARK: - Materials

/// The CustomMaterials of the demo, one per Android material instance.
struct CosmosMaterials {
    let starField: CustomMaterial
    let galaxy: CustomMaterial
    let halo: CustomMaterial
    let sparks: CustomMaterial
    let flash: CustomMaterial
    let dust: CustomMaterial
    let backdrop: CustomMaterial
    let burst: CustomMaterial
    let flow: CustomMaterial
    let prominences: CustomMaterial
    let plasma: CustomMaterial

    enum Failure: Error { case noMetal }

    @MainActor
    static func make() async throws -> CosmosMaterials {
        guard let device = MTLCreateSystemDefaultDevice(), let library = device.makeDefaultLibrary() else {
            throw Failure.noMetal
        }
        func additive(_ geometry: String, _ surface: String) async throws -> CustomMaterial {
            var descriptor = CustomMaterial.Program.Descriptor()
            descriptor.lightingModel = .unlit
            descriptor.blendMode = .add
            let program = try await CustomMaterial.Program(
                surfaceShader: CustomMaterial.SurfaceShader(named: surface, in: library),
                geometryModifier: CustomMaterial.GeometryModifier(named: geometry, in: library),
                descriptor: descriptor
            )
            var material = CustomMaterial(program: program)
            material.blending = .transparent(opacity: 1.0)
            material.writesDepth = false
            material.faceCulling = .none
            return material
        }
        var plasma = try CustomMaterial(
            surfaceShader: CustomMaterial.SurfaceShader(named: "cosmosPlasmaSurface", in: library),
            lightingModel: .unlit
        )
        plasma.faceCulling = .back
        return CosmosMaterials(
            starField: try await additive("cosmosStarFieldGeometry", "cosmosSpriteSurface"),
            galaxy: try await additive("cosmosGalaxyGeometry", "cosmosSpriteSurface"),
            halo: try await additive("cosmosSpriteGeometry", "cosmosSpriteSurface"),
            sparks: try await additive("cosmosSparksGeometry", "cosmosSpriteSurface"),
            flash: try await additive("cosmosSpriteGeometry", "cosmosSpriteSurface"),
            dust: try await additive("cosmosDustGeometry", "cosmosSpriteSurface"),
            backdrop: try await additive("cosmosSpriteGeometry", "cosmosSpriteSurface"),
            burst: try await additive("cosmosRibbonGeometry", "cosmosBurstSurface"),
            flow: try await additive("cosmosRibbonGeometry", "cosmosFlowSurface"),
            prominences: try await additive("cosmosRibbonGeometry", "cosmosProminenceSurface"),
            plasma: plasma
        )
    }
}

/// One glowing mesh and its material, whose `custom` float4 the tick rewrites every frame.
@MainActor
final class CosmosGlow {
    let entity: ModelEntity
    private var material: CustomMaterial

    init(mesh: GlowMesh, material: CustomMaterial) throws {
        self.material = material
        entity = ModelEntity(mesh: try Self.upload(mesh), materials: [material])
    }

    init(sphere radius: Float, material: CustomMaterial) {
        self.material = material
        entity = ModelEntity(mesh: .generateSphere(radius: radius), materials: [material])
    }

    func set(_ value: SIMD4<Float>) {
        guard material.custom.value != value else { return }
        material.custom.value = value
        entity.model?.materials = [material]
    }

    /// Copies a `GlowMesh` into a `LowLevelMesh`. The Android layout is kept byte for byte;
    /// the colour and the custom attributes ride in the uv2…uv4 slots RealityKit hands to
    /// a geometry modifier.
    private static func upload(_ mesh: GlowMesh) throws -> MeshResource {
        let sprite = mesh.stride == CosmosMeshes.spriteStride
        let attributes: [LowLevelMesh.Attribute] = sprite
            ? [
                .init(semantic: .position, format: .float3, offset: 0),
                .init(semantic: .uv3, format: .float4, offset: 12),
                .init(semantic: .uv2, format: .float4, offset: 28),
            ]
            : [
                .init(semantic: .position, format: .float3, offset: 0),
                .init(semantic: .uv4, format: .float4, offset: 12),
                .init(semantic: .uv2, format: .float4, offset: 28),
                .init(semantic: .uv3, format: .float4, offset: 44),
            ]
        let descriptor = LowLevelMesh.Descriptor(
            vertexCapacity: mesh.vertexCount,
            vertexAttributes: attributes,
            vertexLayouts: [.init(bufferIndex: 0, bufferStride: mesh.stride * MemoryLayout<Float>.stride)],
            indexCapacity: mesh.indices.count,
            indexType: .uint32
        )
        let low = try LowLevelMesh(descriptor: descriptor)
        low.withUnsafeMutableBytes(bufferIndex: 0) { target in
            mesh.vertices.withUnsafeBytes { target.copyMemory(from: $0) }
        }
        low.withUnsafeMutableIndices { target in
            mesh.indices.withUnsafeBytes { target.copyMemory(from: $0) }
        }
        // Sprites and strokes are expanded on the GPU (and widened to a minimum pixel size),
        // so pad the culling box.
        let pad = SIMD3<Float>(repeating: 0.25)
        low.parts.replaceAll([
            LowLevelMesh.Part(
                indexCount: mesh.indices.count,
                topology: .triangle,
                bounds: BoundingBox(min: mesh.boundsMin - pad, max: mesh.boundsMax + pad)
            ),
        ])
        return try MeshResource(from: low)
    }
}

// MARK: - Scenes

/// The CPU meshes of one scene, built off the main thread.
struct CosmosSceneMeshes: Sendable {
    var main: GlowMesh?
    var strokes: GlowMesh?
    var dust: GlowMesh?

    static func build(_ kind: CosmosSceneKind) -> CosmosSceneMeshes {
        switch kind {
        case .galaxy:
            CosmosSceneMeshes(main: CosmosMeshes.galaxy())
        case .star:
            CosmosSceneMeshes(main: CosmosMeshes.starHalo(), strokes: CosmosMeshes.prominences())
        case .burst:
            CosmosSceneMeshes(main: CosmosMeshes.burstCore(), strokes: CosmosMeshes.burst(), dust: CosmosMeshes.burstSparks())
        case .flow:
            CosmosSceneMeshes(main: CosmosMeshes.flowBackdrop(), strokes: CosmosMeshes.flowField(), dust: CosmosMeshes.flowDust())
        }
    }
}

/// The entities of one scene and how each frame drives them.
@MainActor
final class CosmosSceneEntities {
    let kind: CosmosSceneKind
    let root = Entity()
    /// The galaxy disc or the star — the node that spins.
    private let spinner = Entity()
    private var main: CosmosGlow?
    private var strokes: CosmosGlow?
    private var dust: CosmosGlow?
    private var spheres: [CosmosGlow] = []

    init(kind: CosmosSceneKind, meshes: CosmosSceneMeshes, materials: CosmosMaterials) throws {
        self.kind = kind
        root.addChild(spinner)
        switch kind {
        case .galaxy:
            main = try meshes.main.map { try CosmosGlow(mesh: $0, material: materials.galaxy) }
            main.map { spinner.addChild($0.entity) }
        case .star:
            let star = CosmosGlow(sphere: 1, material: materials.plasma)
            spheres = [star]
            spinner.addChild(star.entity)
            strokes = try meshes.strokes.map { try CosmosGlow(mesh: $0, material: materials.prominences) }
            strokes.map { spinner.addChild($0.entity) }
            main = try meshes.main.map { try CosmosGlow(mesh: $0, material: materials.halo) }
            main.map { root.addChild($0.entity) }
        case .burst:
            strokes = try meshes.strokes.map { try CosmosGlow(mesh: $0, material: materials.burst) }
            dust = try meshes.dust.map { try CosmosGlow(mesh: $0, material: materials.sparks) }
            main = try meshes.main.map { try CosmosGlow(mesh: $0, material: materials.flash) }
            for glow in [strokes, dust, main].compactMap({ $0 }) { root.addChild(glow.entity) }
        case .flow:
            main = try meshes.main.map { try CosmosGlow(mesh: $0, material: materials.backdrop) }
            strokes = try meshes.strokes.map { try CosmosGlow(mesh: $0, material: materials.flow) }
            dust = try meshes.dust.map { try CosmosGlow(mesh: $0, material: materials.dust) }
            for glow in [main, strokes, dust].compactMap({ $0 }) { root.addChild(glow.entity) }
            for (index, radius) in CosmosEngine.nucleusRadii.enumerated() {
                let vortex = CosmosMeshes.flowVortices[index]
                let nucleus = CosmosGlow(sphere: radius, material: materials.plasma)
                nucleus.entity.position = SIMD3(
                    vortex.x, vortex.y, CosmosMeshes.flowDepth(vortex.x, vortex.y) + radius * 0.4
                )
                spheres.append(nucleus)
                root.addChild(nucleus.entity)
            }
        }
    }

    func update(time: Float, reveal: Float, viewportHeight h: Float) {
        let deg = Float.pi / 180
        switch kind {
        case .galaxy:
            spinner.orientation = simd_quatf(angle: -time * CosmosEngine.galaxySpinDegreesPerSecond * deg, axis: [0, 1, 0])
            main?.set(SIMD4(time, reveal, 1, h))
        case .star:
            spinner.orientation = simd_quatf(angle: 12 * deg, axis: [1, 0, 0])
                * simd_quatf(angle: time * CosmosEngine.starSpinDegreesPerSecond * deg, axis: [0, 1, 0])
            spinner.scale = SIMD3(repeating: 1 + 0.012 * sin(time * 2.1))
            spheres.first?.set(SIMD4(time, reveal * (1 + 0.08 * sin(time * 2.1)), 0, 0))
            strokes?.set(SIMD4(time, reveal, 10, h))
            main?.set(SIMD4(time, reveal * (1 + 0.12 * sin(time * 2.1)), 1, h))
        case .burst:
            let envelope = CosmosMeshes.burstEnvelope((time / CosmosEngine.burstPeriod).truncatingRemainder(dividingBy: 1))
            let grown = min(envelope.x, 1)
            strokes?.set(SIMD4(time, reveal * envelope.y, envelope.x, h))
            dust?.set(SIMD4(time, reveal * envelope.y * grown, 0.4 + 0.6 * grown, h))
            main?.set(SIMD4(time, reveal * envelope.z, 1, h))
        case .flow:
            main?.set(SIMD4(time, reveal, 1, h))
            strokes?.set(SIMD4(time, reveal, 10, h))
            dust?.set(SIMD4(time, reveal, 1, h))
            for sphere in spheres { sphere.set(SIMD4(time, 1, 1, 0)) }
        }
    }
}

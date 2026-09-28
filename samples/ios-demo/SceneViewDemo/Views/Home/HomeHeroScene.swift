import SwiftUI
#if os(iOS)
import Combine
import CoreMotion
import Metal
import os
import RealityKit
import SceneViewSwift
#endif

/// How far the home page has scrolled, for the hero stage. Observed by the
/// stage's scroll-driven layout alone (`HeroParallax`, `HeroStretchPadding`,
/// `HeroStageFrame`), so a scrolled pixel re-lays out those three frames and
/// nothing else — not the catalogue, not the stage's body, not the 3D view.
@Observable
@MainActor
final class HomeHeroScroll {
    /// In points, 0 at rest, negative while pulled down. `ShowcaseTab` clamps
    /// it to the stage's height: past that the stage is gone and nothing is
    /// written any more.
    var offset: CGFloat = 0
    /// Whether any of the stage is still in the viewport. Written only when it
    /// flips: it mounts and unmounts the 3D view.
    var onScreen = true
}

/// Keeps the hero's flight — its entities, the helmet, the terrain, the
/// lighting and the flight's clock — for as long as the home screen lives, so
/// the 3D view can come and go (scrolled away, a demo opened, the app in the
/// background, a search) and the flight resumes on the frame it left instead
/// of reloading and replaying its entrance. Android keeps its engine the same
/// way while the stage's lifecycle is parked at CREATED.
///
/// Cheap to create: `ShowcaseTab` holds one in `@State`, and SwiftUI builds a
/// throwaway one each time it re-creates the screen, so the renderer is only
/// built on first use. A memory warning releases it, unless it is mounted.
@MainActor
final class HomeHeroFlightHost {
    #if os(iOS)
    private var built: HomeHeroRenderer?
    nonisolated(unsafe) private var memoryWarning: (any NSObjectProtocol)?

    var renderer: HomeHeroRenderer {
        if let built { return built }
        let renderer = HomeHeroRenderer()
        built = renderer
        if memoryWarning == nil {
            memoryWarning = NotificationCenter.default.addObserver(
                forName: UIApplication.didReceiveMemoryWarningNotification, object: nil, queue: .main
            ) { [weak self] _ in
                MainActor.assumeIsolated { self?.releaseIfUnmounted() }
            }
        }
        return renderer
    }

    private func releaseIfUnmounted() {
        guard let built, !built.isAttached else { return }
        self.built = nil
    }

    deinit {
        if let memoryWarning { NotificationCenter.default.removeObserver(memoryWarning) }
    }
    #endif
}

/// The layer under the home catalogue that carries the dusk sky and the live
/// flight — the iOS twin of Android's `HomeHeroStage` (#3948, #3949).
///
/// Geometry: `height` tall from the top edge of the display, so the sky runs
/// under the status bar and the header (which turns white over it). It covers
/// the header band and the hero band, then bleeds `heroStageBleed` further
/// before fading into the page. It is drawn in the scroll content, so it
/// travels with the band one-for-one; inside it the sky and the flight lag the
/// band by `heroParallax`, which reads as depth, and the stage clips the lag.
/// Pulled down past the top, it stretches instead of coming away from the edge.
///
/// The live 3D view is mounted while `live` (visible tab, foreground, nothing
/// presented over it — see `ShowcaseTab.heroLive`) and while the stage is on
/// screen; between mounts the flight waits in `flight`. It moves only while
/// Reduce Motion is off; otherwise it holds its frame, and once that frame is
/// posed nothing runs per frame.
struct HomeHeroStage: View {
    let height: CGFloat
    let topInset: CGFloat
    /// Where the hero band starts below the content's top edge.
    let restTop: CGFloat
    let live: Bool
    let scroll: HomeHeroScroll
    let flight: HomeHeroFlightHost

    /// How long the 3D view stays mounted after `live` drops. Opening the
    /// hero's demo zooms it out of the hero copy while the home — this stage
    /// included — is still on screen behind it; unmounting RealityKit on the
    /// same frame would blank the valley mid-animation, down to the bare sky.
    /// Only the view goes after the grace: the flight stays in `flight`.
    private static let teardownGrace: Duration = .milliseconds(500)

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var mounted = false

    var body: some View {
        let home = SceneViewTokens.Home.self
        let bandTop = topInset + restTop
        let bandHeight = height - bandTop - home.heroStageBleed
        let bandFraction = bandHeight / (bandHeight + home.heroStageBleed)

        ZStack(alignment: .top) {
            ZStack {
                HomeHeroSky()
                #if os(iOS)
                if mounted && scroll.onScreen {
                    HomeHeroScene(renderer: flight.renderer, moving: live && !reduceMotion, motion: !reduceMotion)
                }
                #endif
            }
            .modifier(HeroParallax(scroll: scroll))

            // The hero's legibility scrim, painted here full-bleed so no card
            // edge cuts the landscape, running on into the page.
            LinearGradient(
                stops: [
                    .init(color: SceneViewTokens.SpatialGalleryColor.stageScrimStart,
                          location: home.heroScrimStart * bandFraction),
                    .init(color: SceneViewTokens.SpatialGalleryColor.stageScrimEnd, location: bandFraction),
                    .init(color: SceneViewTokens.SpatialGalleryColor.stageScrimEnd, location: 1),
                ],
                startPoint: .top,
                endPoint: .bottom
            )
            .frame(height: bandHeight + home.heroStageBleed)
            .modifier(HeroStretchPadding(scroll: scroll, top: bandTop))
            .frame(maxHeight: .infinity, alignment: .top)

            // The stage ends on the page, not on an edge.
            LinearGradient(colors: [SceneViewTokens.HomeColor.surface.opacity(0), SceneViewTokens.HomeColor.surface],
                           startPoint: .top, endPoint: .bottom)
                .frame(height: home.heroStageBleed + home.gridGutter)
                .frame(maxHeight: .infinity, alignment: .bottom)
        }
        .frame(maxWidth: .infinity)
        .modifier(HeroStageFrame(scroll: scroll, height: height, topInset: topInset))
        .allowsHitTesting(false)
        .accessibilityHidden(true)
        .task(id: live) {
            guard live else {
                try? await Task.sleep(for: Self.teardownGrace)
                guard !Task.isCancelled else { return }
                mounted = false
                return
            }
            mounted = true
        }
    }
}

// The three places the scroll offset moves the stage, each its own modifier:
// a scroll re-evaluates these bodies, never the stage's or the 3D view's.

/// The sky and the flight lag the band by `heroParallax`.
private struct HeroParallax: ViewModifier {
    let scroll: HomeHeroScroll

    func body(content: Content) -> some View {
        content.offset(y: max(scroll.offset, 0) * SceneViewTokens.Home.heroParallax)
    }
}

/// The scrim stays on the band while a pull-down stretches the stage above it.
private struct HeroStretchPadding: ViewModifier {
    let scroll: HomeHeroScroll
    let top: CGFloat

    func body(content: Content) -> some View {
        content.padding(.top, top + max(-scroll.offset, 0))
    }
}

/// The stage's height, grown by a pull-down, pinned to the display's top edge.
private struct HeroStageFrame: ViewModifier {
    let scroll: HomeHeroScroll
    let height: CGFloat
    let topInset: CGFloat

    func body(content: Content) -> some View {
        let stretch = max(-scroll.offset, 0)
        content
            .frame(height: height + stretch)
            .clipped()
            .offset(y: -topInset - stretch)
    }
}

/// The dusk sky behind the transparent flight: `hero-sky-*`, with the sun's
/// glow where the disc sits in the flight — the sky is warmest around it.
private struct HomeHeroSky: View {
    var body: some View {
        let home = SceneViewTokens.Home.self
        let colors = SceneViewTokens.HomeColor.self
        LinearGradient(
            stops: [
                .init(color: colors.heroSkyTop, location: 0),
                .init(color: colors.heroSkyDusk, location: home.heroSkyHorizon * 0.45),
                .init(color: colors.heroSkyHorizon, location: home.heroSkyHorizon),
                .init(color: colors.heroSkyGround, location: home.heroSkyHorizon + 0.1),
                .init(color: colors.heroSkyTop, location: 1),
            ],
            startPoint: .top,
            endPoint: .bottom
        )
        .overlay {
            GeometryReader { proxy in
                RadialGradient(
                    stops: [
                        .init(color: colors.heroSkyHorizon.opacity(0.7), location: 0),
                        .init(color: colors.heroSkyHorizon.opacity(0.25), location: 0.45),
                        .init(color: colors.heroSkyHorizon.opacity(0), location: 1),
                    ],
                    center: UnitPoint(x: home.heroSunX, y: home.heroSkyHorizon * 0.82),
                    startRadius: 0,
                    endRadius: proxy.size.width * 0.6
                )
            }
        }
    }
}

#if os(iOS)
/// The live flight: a valley of flat-shaded ridges streaming under a camera
/// that sways and rolls, the sun low ahead, and the Model Viewer's helmet
/// turning beside the camera — Android's `HomeHeroScene`, on RealityKit.
///
/// SceneViewSwift's `SceneView` owns its camera (a fixed 60° field of view)
/// and has no per-frame hook, and the flight needs both, so this hosts its own
/// `RealityView`, like the Rerun replay stage and the library itself do. The
/// background is left clear: the sky is `HomeHeroSky`, underneath.
///
/// Only the view: the renderer it shows outlives it (`HomeHeroFlightHost`).
private struct HomeHeroScene: View {
    let renderer: HomeHeroRenderer
    let moving: Bool
    let motion: Bool

    var body: some View {
        RealityView { content in
            content.camera = .virtual
            renderer.install(in: &content)
        }
        .onAppear { renderer.set(moving: moving, motion: motion) }
        .onChange(of: moving) { _, value in renderer.set(moving: value, motion: motion) }
        .onChange(of: motion) { _, value in renderer.set(moving: moving, motion: value) }
        .onDisappear { renderer.detach() }
        .allowsHitTesting(false)
    }
}

@MainActor
final class HomeHeroRenderer {
    /// Android's 28 mm lens on Filament's 24 mm-tall sensor.
    static let verticalFov: Float = 46.4
    static let sunDiscCentre = SIMD3<Float>(-10, 10.5, -74)
    static let sunDiscRadius: Float = 2.6
    /// The disc as the tone-mapped frame shows it: its emissive clips to cream.
    static let sunDiscColor = UIColor(red: 1, green: 0.99, blue: 0.95, alpha: 1)
    /// RealityKit lux for Android's 95 000-lux sun, same key-to-IBL ratio as
    /// SceneViewSwift's 10 000-lux default against an untouched IBL.
    static let sunIntensity: Float = 95_000
    static let sunColor = UIColor(red: 1, green: 0.62, blue: 0.38, alpha: 1)
    /// Android renders the flight at 30 fps; the terrain's fog is reshaded at
    /// that rate too, while the camera follows the display.
    static let shadeInterval: Double = 1.0 / 30
    static let terrain = HeroTerrainSpec.full
    /// Texels per row of the per-facet colour texture.
    static let textureWidth = 128

    /// Whether a `RealityView` shows this renderer right now.
    var isAttached: Bool { attachments > 0 }

    private var moving = false
    private var motion = true
    private var clock = HeroClock()
    private var tilt = HeroTilt()
    private let motionManager = CMMotionManager()
    private var entranceStart: Double?
    private var terrainStart: Double?
    private var lastPose: HeroFlightPose?
    private var sinceShade: Double = .infinity

    private let root = Entity()
    private let camera = PerspectiveCamera()
    private let helmetHolder = Entity()
    private var helmet: ModelNode?
    private var terrainEntity: ModelEntity?
    /// Stops the per-frame `Update` callback; nil while none is subscribed.
    private var stopUpdates: (() -> Void)?
    private var attachments = 0
    private var built = false
    /// Loads still running (lighting then helmet, terrain): the frame is not
    /// final before they land, so a held flight does not go idle before that.
    private var pendingLoads = 0

    private var shader: HeroTerrainShader?

    init() {
        #if DEBUG
        HeroDebugCounters.rendererBuilt()
        #endif
    }

    /// Puts the flight in a newly made `RealityView`. The first call builds
    /// the scene and starts the loads; later ones re-parent the same entities,
    /// so the flight carries on from the frame it left.
    func install(in content: inout RealityViewCameraContent) {
        content.add(root)
        content.add(camera)
        attachments += 1
        stopUpdates?()
        let subscription = content.subscribe(to: SceneEvents.Update.self) { [weak self] event in
            MainActor.assumeIsolated { self?.update(event.deltaTime) }
        }
        stopUpdates = { subscription.cancel() }
        if !built { build() }
        update(0)
    }

    /// The `RealityView` is gone: no frame work until the next `install`.
    func detach() {
        attachments = max(attachments - 1, 0)
        guard attachments == 0 else { return }
        stopUpdates?()
        stopUpdates = nil
        set(moving: false, motion: motion)
    }

    func set(moving: Bool, motion: Bool) {
        let changed = moving != self.moving || motion != self.motion
        self.moving = moving
        self.motion = motion
        if moving, motionManager.isDeviceMotionAvailable, !motionManager.isDeviceMotionActive {
            motionManager.deviceMotionUpdateInterval = 1.0 / 30
            motionManager.startDeviceMotionUpdates()
        } else if !moving, motionManager.isDeviceMotionActive {
            motionManager.stopDeviceMotionUpdates()
            tilt.reset()
        }
        if changed { wake() }
    }

    private func build() {
        built = true
        camera.components.set(PerspectiveCameraComponent(near: 0.05, far: 200,
                                                         fieldOfViewInDegrees: Self.verticalFov,
                                                         fieldOfViewOrientation: .vertical))
        let sun = DirectionalLight()
        sun.light.color = Self.sunColor
        sun.light.intensity = Self.sunIntensity
        sun.look(at: HeroDusk.sunTravel, from: .zero, relativeTo: nil)
        root.addChild(sun)

        var disc = UnlitMaterial(applyPostProcessToneMap: false)
        disc.color = .init(tint: Self.sunDiscColor)
        let discEntity = ModelEntity(mesh: .generateSphere(radius: Self.sunDiscRadius), materials: [disc])
        discEntity.position = Self.sunDiscCentre
        root.addChild(discEntity)

        helmetHolder.scale = .init(repeating: 0.001)
        root.addChild(helmetHolder)

        // Loaded once the RealityKit view is in the hierarchy: `Entity(named:)`
        // does not resume before that (see git history of `HomeHero`).
        pendingLoads = 2
        Task { @MainActor [weak self] in
            await self?.loadLighting()
            await self?.loadHelmet()
            self?.loadLanded()
        }
        Task { @MainActor [weak self] in
            let spec = Self.terrain
            let faces = await Task.detached(priority: .userInitiated) { HeroTerrain.faces(spec) }.value
            self?.adoptTerrain(faces)
            self?.loadLanded()
        }
    }

    // MARK: Loading

    /// `sunset.hdr` is the 2048×1024 original of Android's `sunset_2k.hdr`
    /// (downscaled to 1024×512 in #934): the same sky and the same light, so
    /// the helmet and the terrain's ambient cube (`HeroDusk`, integrated from
    /// Android's file) agree without the app shipping that sky twice.
    private func loadLighting() async {
        let sunset = SceneEnvironment.custom(name: "Sunset", hdrFile: "sunset.hdr", intensity: 1, showSkybox: false)
        guard let resource = try? await sunset.load() else { return }
        root.components.set(ImageBasedLightComponent(source: .single(resource), intensityExponent: 0))
        helmetHolder.components.set(ImageBasedLightReceiverComponent(imageBasedLight: root))
    }

    private func loadHelmet() async {
        guard helmet == nil, let model = try? await ModelNode.load(HomeHero.heroAssetName) else { return }
        _ = model.scaleToUnits(HeroFlight.helmetUnits)
        _ = model.centerOrigin()
        helmetHolder.addChild(model.entity)
        helmet = model
    }

    private func adoptTerrain(_ faces: [HeroTerrain.Face]) {
        guard terrainEntity == nil, let shader = HeroTerrainShader(faces: faces, width: Self.textureWidth),
              let mesh = shader.mesh(faces) else { return }
        var material = UnlitMaterial(applyPostProcessToneMap: false)
        material.color = .init(tint: .white, texture: .init(shader.texture, sampler: HeroTerrainShader.nearest))
        material.faceCulling = .none
        let entity = ModelEntity(mesh: mesh, materials: [material])
        root.addChild(entity)
        terrainEntity = entity
        self.shader = shader
        lastPose = nil
    }

    private func loadLanded() {
        pendingLoads -= 1
        wake()
    }

    // MARK: Frame

    /// Back to one callback per frame after the flight went idle.
    private func wake() {
        guard stopUpdates == nil, isAttached, let scene = root.scene else { return }
        let subscription = scene.subscribe(to: SceneEvents.Update.self) { [weak self] event in
            MainActor.assumeIsolated { self?.update(event.deltaTime) }
        }
        stopUpdates = { subscription.cancel() }
    }

    private func update(_ delta: Double) {
        #if DEBUG
        HeroDebugCounters.updateFired()
        #endif
        clock.advance(by: delta, moving: moving)
        if moving, let gravity = motionManager.deviceMotion?.gravity {
            tilt.feed(gravityX: gravity.x, gravityZ: gravity.z)
            tilt.update(delta: Float(delta))
        }
        if helmet != nil, entranceStart == nil { entranceStart = clock.seconds }
        if terrainEntity != nil, terrainStart == nil { terrainStart = clock.seconds }

        let pose = HeroFlight.pose(seconds: clock.seconds, period: Self.terrain.period, tilt: tilt.value,
                                   entranceStart: entranceStart, terrainStart: terrainStart, motion: motion)
        sinceShade += delta
        guard pose != lastPose else {
            // A held frame (Reduce Motion, or the flight parked) with nothing
            // left to load is final: stop the per-frame callback until the
            // flight moves again. RealityKit keeps showing the last frame.
            if !moving, pendingLoads == 0 {
                stopUpdates?()
                stopUpdates = nil
            }
            return
        }
        lastPose = pose

        camera.look(at: pose.target, from: pose.eye, upVector: pose.up, relativeTo: nil)
        let terrainOffset = SIMD3<Float>(0, -HeroFlight.terrainRiseUnits * (1 - pose.terrainRise), pose.terrainOffsetZ)
        terrainEntity?.position = terrainOffset
        helmetHolder.position = pose.helmet
        helmetHolder.orientation = simd_quatf(angle: pose.helmetYawDegrees * .pi / 180, axis: [0, 1, 0])
            * simd_quatf(angle: -6 * .pi / 180, axis: [1, 0, 0])
        helmetHolder.scale = .init(repeating: max(pose.helmetEntrance, 0.001))

        if let shader, sinceShade >= Self.shadeInterval || !moving {
            sinceShade = 0
            shader.shade(offset: terrainOffset, eye: pose.eye)
        }
    }
}

/// The terrain's colour, one texel per facet, reshaded as the valley streams
/// through the fog (see `HeroDusk`) and uploaded through a `LowLevelTexture`.
/// Every corner of a facet samples its texel's centre with a nearest filter,
/// so each facet is one flat colour — the flat shading.
///
/// The per-facet pass runs on its own queue into a staging buffer; only the
/// blit into the texture is on the main thread. One pass at a time: a request
/// made while one runs replaces any earlier waiting one, so the texture always
/// ends on the latest pose.
@MainActor
private final class HeroTerrainShader {
    let texture: TextureResource
    private let lowLevel: LowLevelTexture
    private let queue: MTLCommandQueue
    private let staging: [HeroStagingBuffer]
    private var stagingIndex = 0
    private let width: Int
    private let height: Int
    private let pass: HeroFogPass
    private let work = DispatchQueue(label: "io.github.sceneview.demo.hero-terrain", qos: .userInteractive)
    private var running = false
    private var waiting: (offset: SIMD3<Float>, eye: SIMD3<Float>)?
    private var uploaded = false

    static let nearest: MaterialParameters.Texture.Sampler = {
        let descriptor = MTLSamplerDescriptor()
        descriptor.minFilter = .nearest
        descriptor.magFilter = .nearest
        descriptor.mipFilter = .notMipmapped
        descriptor.sAddressMode = .clampToEdge
        descriptor.tAddressMode = .clampToEdge
        return MaterialParameters.Texture.Sampler(descriptor)
    }()

    init?(faces: [HeroTerrain.Face], width: Int) {
        let height = (faces.count + width - 1) / width
        guard let device = MTLCreateSystemDefaultDevice(), let queue = device.makeCommandQueue() else { return nil }
        let descriptor = LowLevelTexture.Descriptor(pixelFormat: .rgba8Unorm_srgb, width: width, height: height,
                                                    textureUsage: [.shaderRead])
        let bytes = width * height * 4
        let staging = (0..<3).compactMap { _ in device.makeBuffer(length: bytes, options: .storageModeShared) }
        guard staging.count == 3, let lowLevel = try? LowLevelTexture(descriptor: descriptor),
              let texture = try? TextureResource(from: lowLevel) else { return nil }
        self.width = width
        self.height = height
        self.queue = queue
        self.staging = staging.map(HeroStagingBuffer.init)
        self.lowLevel = lowLevel
        self.texture = texture
        pass = HeroFogPass(faces: faces)
    }

    /// Three corners per facet, each carrying its facet's texel centre. The
    /// texture's first row is v = 1: RealityKit samples from the bottom left.
    func mesh(_ faces: [HeroTerrain.Face]) -> MeshResource? {
        var positions = [SIMD3<Float>]()
        var normals = [SIMD3<Float>]()
        var uvs = [SIMD2<Float>]()
        positions.reserveCapacity(faces.count * 3)
        normals.reserveCapacity(faces.count * 3)
        uvs.reserveCapacity(faces.count * 3)
        for (i, face) in faces.enumerated() {
            let uv = SIMD2<Float>((Float(i % width) + 0.5) / Float(width),
                                  1 - (Float(i / width) + 0.5) / Float(height))
            positions += [face.a, face.b, face.c]
            normals += [face.normal, face.normal, face.normal]
            uvs += [uv, uv, uv]
        }
        var descriptor = MeshDescriptor(name: "hero-terrain")
        descriptor.positions = MeshBuffers.Positions(positions)
        descriptor.normals = MeshBuffers.Normals(normals)
        descriptor.textureCoordinates = MeshBuffers.TextureCoordinates(uvs)
        descriptor.primitives = .triangles((0..<UInt32(positions.count)).map { $0 })
        return try? MeshResource.generate(from: [descriptor])
    }

    /// Fogs every facet as seen from `eye`, with the strip moved by `offset`,
    /// and uploads the result. The very first pass runs inline, so the terrain
    /// never shows an unshaded texture.
    func shade(offset: SIMD3<Float>, eye: SIMD3<Float>) {
        guard !running else {
            waiting = (offset, eye)
            return
        }
        let target = staging[stagingIndex]
        stagingIndex = (stagingIndex + 1) % staging.count
        guard uploaded else {
            pass.run(offset: offset, eye: eye, into: target)
            upload(target)
            return
        }
        running = true
        let pass = pass
        work.async {
            pass.run(offset: offset, eye: eye, into: target)
            DispatchQueue.main.async {
                MainActor.assumeIsolated { self.finished(target) }
            }
        }
    }

    private func finished(_ target: HeroStagingBuffer) {
        running = false
        upload(target)
        if let next = waiting {
            waiting = nil
            shade(offset: next.offset, eye: next.eye)
        }
    }

    private func upload(_ source: HeroStagingBuffer) {
        uploaded = true
        guard let commands = queue.makeCommandBuffer(), let blit = commands.makeBlitCommandEncoder() else { return }
        let target = lowLevel.replace(using: commands)
        blit.copy(from: source.buffer, sourceOffset: 0, sourceBytesPerRow: width * 4,
                  sourceBytesPerImage: width * height * 4,
                  sourceSize: MTLSize(width: width, height: height, depth: 1),
                  to: target, destinationSlice: 0, destinationLevel: 0,
                  destinationOrigin: MTLOrigin(x: 0, y: 0, z: 0))
        blit.endEncoding()
        commands.commit()
    }
}

/// A staging buffer handed to the shading queue. Safe to send: one pass at a
/// time writes it, and it is blitted only once that pass is done.
private struct HeroStagingBuffer: @unchecked Sendable {
    let buffer: MTLBuffer
}

/// The per-facet fog pass: Filament's height fog, in-scattering and Filmic
/// tone mapping at each facet's centroid. Immutable, so it runs off the main
/// thread. Scalar on purpose: generic SIMD arithmetic is slow in the debug
/// builds QA installs.
private struct HeroFogPass: Sendable {
    /// Facet centroids and lit colours, packed xyz, in facet order.
    let centroids: [Float]
    let lit: [Float]
    /// Linear [0, 1] → 8-bit sRGB.
    let encode: [UInt8]

    init(faces: [HeroTerrain.Face]) {
        var centroids = [Float]()
        var lit = [Float]()
        centroids.reserveCapacity(faces.count * 3)
        lit.reserveCapacity(faces.count * 3)
        for face in faces {
            let c = face.centroid
            let l = HeroDusk.lit(albedo: face.albedo, normal: face.normal)
            centroids += [c.x, c.y, c.z]
            lit += [l.x, l.y, l.z]
        }
        self.centroids = centroids
        self.lit = lit
        encode = (0..<4096).map { i in
            let x = Float(i) / 4095
            let s = x <= 0.0031308 ? 12.92 * x : 1.055 * pow(x, 1 / 2.4) - 0.055
            return UInt8(min(max(s * 255 + 0.5, 0), 255))
        }
    }

    func run(offset: SIMD3<Float>, eye: SIMD3<Float>, into target: HeroStagingBuffer) {
        let count = centroids.count / 3
        let out = target.buffer.contents().bindMemory(to: UInt8.self, capacity: count * 4)

        typealias D = HeroDusk
        let ex = eye.x, ey = eye.y, ez = eye.z
        let sx = D.toSun.x, sy = D.toSun.y, sz = D.toSun.z
        let fr = D.fogColor.x, fg = D.fogColor.y, fb = D.fogColor.z
        let kr = D.sunExposed.x, kg = D.sunExposed.y, kb = D.sunExposed.z
        let density = D.fogDensity * exp(-D.fogHeightFalloff * (ey - D.fogHeight))
        let falloff = D.fogHeightFalloff
        let start = D.fogStart, cutOff = D.fogCutOff, maxOpacity = D.fogMaxOpacity
        let scatterStart = D.inScatteringStart, scatterSize = D.inScatteringSize

        centroids.withUnsafeBufferPointer { c in
            lit.withUnsafeBufferPointer { l in
                encode.withUnsafeBufferPointer { lut in
                    for i in 0..<count {
                        var r = l[i * 3], g = l[i * 3 + 1], b = l[i * 3 + 2]
                        let vx = c[i * 3] + offset.x - ex
                        let vy = c[i * 3 + 1] + offset.y - ey
                        let vz = c[i * 3 + 2] + offset.z - ez
                        let d = (vx * vx + vy * vy + vz * vz).squareRoot()
                        if d > start, d < cutOff {
                            let h = falloff * vy
                            let shape: Float = abs(h) > 1e-4 ? (1 - exp(-h)) / h : 1
                            let perMetre = density * shape
                            let opacity = min(1 - exp(-perMetre * (d - start)), maxOpacity)
                            let keep = 1 - opacity
                            r = r * keep + fr * opacity
                            g = g * keep + fg * opacity
                            b = b * keep + fb * opacity
                            let sunAmount = (vx * sx + vy * sy + vz * sz) / d
                            if sunAmount > 0, d > scatterStart {
                                let scatter = pow(sunAmount, scatterSize) * (1 - exp(-perMetre * (d - scatterStart)))
                                r += kr * scatter
                                g += kg * scatter
                                b += kb * scatter
                            }
                        }
                        out[i * 4] = lut[Int(D.filmic(r) * 4095)]
                        out[i * 4 + 1] = lut[Int(D.filmic(g) * 4095)]
                        out[i * 4 + 2] = lut[Int(D.filmic(b) * 4095)]
                        out[i * 4 + 3] = 255
                    }
                }
            }
        }
    }
}

#if DEBUG
/// What the flight costs, in the log (`HomeHero` category, debug level):
/// renderers built, and RealityKit `Update` callbacks per second. A held or
/// unmounted flight logs nothing — no callback fires.
@MainActor
enum HeroDebugCounters {
    static let log = Logger(subsystem: "io.github.sceneview.demo", category: "HomeHero")
    static var renderers = 0
    static var updates = 0
    static var windowStart = CACurrentMediaTime()

    static func rendererBuilt() {
        renderers += 1
        log.debug("renderer init #\(renderers)")
    }

    static func updateFired() {
        updates += 1
        let now = CACurrentMediaTime()
        guard now - windowStart >= 1 else { return }
        log.debug("update callbacks/s: \(Double(updates) / (now - windowStart), format: .fixed(precision: 1))")
        updates = 0
        windowStart = now
    }
}
#endif
#endif

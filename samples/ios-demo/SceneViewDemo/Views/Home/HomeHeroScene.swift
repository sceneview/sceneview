import SwiftUI
#if os(iOS)
import CoreMotion
import Metal
import RealityKit
import SceneViewSwift
#endif

/// How far the home page has scrolled, in points (0 at rest, negative while
/// pulled down). Observed by the stage alone, so a scrolled pixel redraws the
/// stage and not the whole catalogue.
@Observable
@MainActor
final class HomeHeroScroll {
    var offset: CGFloat = 0
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
/// presented over it — see `ShowcaseTab.heroLive`). The flight moves only
/// while the stage is on screen and Reduce Motion is off; otherwise it holds
/// its frame.
struct HomeHeroStage: View {
    let height: CGFloat
    let topInset: CGFloat
    /// Where the hero band starts below the content's top edge.
    let restTop: CGFloat
    let live: Bool
    let scroll: HomeHeroScroll

    /// How long the 3D view outlives a `live` drop: the iOS 18 zoom transition
    /// morphs the hero into the demo, and disposing RealityKit on the same
    /// frame would blank what is being morphed.
    private static let teardownGrace: Duration = .milliseconds(500)

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var mounted = false

    var body: some View {
        let travel = max(scroll.offset, 0)
        let stretch = max(-scroll.offset, 0)
        let onScreen = travel < height - topInset
        let home = SceneViewTokens.Home.self
        let bandTop = topInset + restTop
        let bandHeight = height - bandTop - home.heroStageBleed
        let bandFraction = bandHeight / (bandHeight + home.heroStageBleed)

        ZStack(alignment: .top) {
            ZStack {
                HomeHeroSky()
                #if os(iOS)
                if mounted {
                    HomeHeroScene(moving: live && onScreen && !reduceMotion, motion: !reduceMotion)
                }
                #endif
            }
            .offset(y: travel * home.heroParallax)

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
            .padding(.top, bandTop + stretch)
            .frame(maxHeight: .infinity, alignment: .top)

            // The stage ends on the page, not on an edge.
            LinearGradient(colors: [SceneViewTokens.HomeColor.surface.opacity(0), SceneViewTokens.HomeColor.surface],
                           startPoint: .top, endPoint: .bottom)
                .frame(height: home.heroStageBleed + home.gridGutter)
                .frame(maxHeight: .infinity, alignment: .bottom)
        }
        .frame(maxWidth: .infinity)
        .frame(height: height + stretch)
        .clipped()
        .offset(y: -topInset - stretch)
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
private struct HomeHeroScene: View {
    let moving: Bool
    let motion: Bool
    @State private var renderer = HomeHeroRenderer()

    var body: some View {
        RealityView { content in
            content.camera = .virtual
            renderer.install(in: &content)
        }
        .onAppear { renderer.set(moving: moving, motion: motion) }
        .onChange(of: moving) { _, value in renderer.set(moving: value, motion: motion) }
        .onChange(of: motion) { _, value in renderer.set(moving: moving, motion: value) }
        .onDisappear { renderer.set(moving: false, motion: motion) }
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
    private var subscription: EventSubscription?
    private var installed = false

    private var shader: HeroTerrainShader?

    func install(in content: inout RealityViewCameraContent) {
        camera.components.set(PerspectiveCameraComponent(near: 0.05, far: 200,
                                                         fieldOfViewInDegrees: Self.verticalFov,
                                                         fieldOfViewOrientation: .vertical))
        content.add(root)
        content.add(camera)
        guard !installed else { return }
        installed = true

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

        subscription = content.subscribe(to: SceneEvents.Update.self) { [weak self] event in
            MainActor.assumeIsolated { self?.update(event.deltaTime) }
        }
        update(0)

        // Loaded once the RealityKit view is in the hierarchy: `Entity(named:)`
        // does not resume before that (see git history of `HomeHero`).
        Task { @MainActor [weak self] in
            await self?.loadLighting()
            await self?.loadHelmet()
        }
        Task { @MainActor [weak self] in
            let spec = Self.terrain
            let faces = await Task.detached(priority: .userInitiated) { HeroTerrain.faces(spec) }.value
            self?.adoptTerrain(faces)
        }
    }

    func set(moving: Bool, motion: Bool) {
        self.moving = moving
        self.motion = motion
        if moving, motionManager.isDeviceMotionAvailable, !motionManager.isDeviceMotionActive {
            motionManager.deviceMotionUpdateInterval = 1.0 / 30
            motionManager.startDeviceMotionUpdates()
        } else if !moving, motionManager.isDeviceMotionActive {
            motionManager.stopDeviceMotionUpdates()
            tilt.reset()
        }
    }

    // MARK: Loading

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

    // MARK: Frame

    private func update(_ delta: Double) {
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
        guard pose != lastPose else { return }
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

/// The terrain's colour, one texel per facet, reshaded on the CPU as the
/// valley streams through the fog (see `HeroDusk`) and uploaded through a
/// `LowLevelTexture`. Every corner of a facet samples its texel's centre with
/// a nearest filter, so each facet is one flat colour — the flat shading.
@MainActor
private final class HeroTerrainShader {
    let texture: TextureResource
    private let lowLevel: LowLevelTexture
    private let queue: MTLCommandQueue
    private let staging: [MTLBuffer]
    private var stagingIndex = 0
    private let width: Int
    private let height: Int
    /// Facet centroids and lit colours, packed xyz, in facet order.
    private let centroids: [Float]
    private let lit: [Float]
    /// Linear [0, 1] → 8-bit sRGB.
    private let encode: [UInt8] = (0..<4096).map { i in
        let x = Float(i) / 4095
        let s = x <= 0.0031308 ? 12.92 * x : 1.055 * pow(x, 1 / 2.4) - 0.055
        return UInt8(min(max(s * 255 + 0.5, 0), 255))
    }

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
        self.staging = staging
        self.lowLevel = lowLevel
        self.texture = texture
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
    /// and uploads the result. Scalar on purpose: this runs every frame, and
    /// generic SIMD arithmetic is slow in the debug builds QA installs.
    func shade(offset: SIMD3<Float>, eye: SIMD3<Float>) {
        let buffer = staging[stagingIndex]
        stagingIndex = (stagingIndex + 1) % staging.count
        let out = buffer.contents().bindMemory(to: UInt8.self, capacity: width * height * 4)

        typealias D = HeroDusk
        let ex = eye.x, ey = eye.y, ez = eye.z
        let sx = D.toSun.x, sy = D.toSun.y, sz = D.toSun.z
        let fr = D.fogColor.x, fg = D.fogColor.y, fb = D.fogColor.z
        let kr = D.sunExposed.x, kg = D.sunExposed.y, kb = D.sunExposed.z
        let density = D.fogDensity * exp(-D.fogHeightFalloff * (ey - D.fogHeight))
        let falloff = D.fogHeightFalloff
        let start = D.fogStart, cutOff = D.fogCutOff, maxOpacity = D.fogMaxOpacity
        let scatterStart = D.inScatteringStart, scatterSize = D.inScatteringSize
        let count = centroids.count / 3

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

        guard let commands = queue.makeCommandBuffer(), let blit = commands.makeBlitCommandEncoder() else { return }
        let target = lowLevel.replace(using: commands)
        blit.copy(from: buffer, sourceOffset: 0, sourceBytesPerRow: width * 4, sourceBytesPerImage: width * height * 4,
                  sourceSize: MTLSize(width: width, height: height, depth: 1),
                  to: target, destinationSlice: 0, destinationLevel: 0,
                  destinationOrigin: MTLOrigin(x: 0, y: 0, z: 0))
        blit.endEncoding()
        commands.commit()
    }
}
#endif

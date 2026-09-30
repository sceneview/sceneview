import Accelerate
import Foundation
import Metal
import RealityKit
import simd

// The ringed world of the Cosmos Star scene on iOS: Android's `cosmos_planet.mat` and
// `cosmos_ring.mat` (#4192) without shader source. RealityKit's stock `UnlitMaterial` has no
// fragment hook, so what those shaders compute per fragment is computed on the CPU per texel,
// into two float16 textures the materials sample:
//
// - the planet: an equirectangular map, in the planet's tilted frame, of the radiance each
//   point of its surface sends to the camera — bands warped by noise, the soft terminator, the
//   atmosphere rim and its forward scattering, the shadow of the rings;
// - the rings: a polar map (radius × angle) of their premultiplied radiance and opacity —
//   ringlets, the Cassini-like gap, forward scattering and the planet's shadow.
//
// Both depend on where the star and the camera are, seen from the planet. They are baked
// again, off the main thread and into buffers allocated once, whenever that has moved: at up
// to `bakeRate` a second while the orbit turns or the camera flies, never while it holds still.
// The planet's spin turns its bands inside the bake, so the light stays on the star's side.

/// The ringed world's colours — Android's `WorldInstances` parameters, linear.
enum CosmosWorldLook {
    /// The blue star's light as it reaches the planet.
    static let sunColor = SIMD3<Float>(0.95, 1.15, 1.55)
    static let bandLight = SIMD3<Float>(0.92, 0.76, 0.54)
    static let bandDark = SIMD3<Float>(0.52, 0.32, 0.18)
    static let atmosphere = SIMD3<Float>(0.22, 0.5, 1.0)
    static let ringColor = SIMD3<Float>(0.8, 0.66, 0.48)
    /// Android's rings reach the screen through Filament's HDR tone map and bloom; here they
    /// are rolled off on the CPU and tone-mapped again, which greys them. They are lifted to
    /// the same on-screen brightness, as the flow's strokes are (`CosmosSceneLayers.flowGain`).
    static let ringGain: Float = 1.45
    /// The orbit trail at its brightest, just behind the planet.
    static let trailColor = SIMD3<Float>(0.3, 0.55, 1.1)
}

/// What a bake depends on, in the planet's tilted frame (`CosmosSystem.tilt`), relative to its
/// centre: where the star is, where the camera is, and how far the surface has turned.
struct CosmosWorldLight: Sendable, Equatable {
    var sun: SIMD3<Float>
    var eye: SIMD3<Float>
    var spin: Float

    init(system: CosmosSystem, time: Float, eye: SIMD3<Float>) {
        let center = system.planetPosition(time)
        let inverse = system.tilt.inverse
        sun = inverse.act(-center)
        self.eye = inverse.act(eye - center)
        spin = CosmosSystem.spinAngle(time)
    }

    /// Whether a bake for `other` would show something this one does not: the star, the
    /// camera or the surface moved by more than a fraction of a degree as seen from the planet.
    func differs(from other: CosmosWorldLight) -> Bool {
        let turn: Float = 0.15 * CosmosSystem.deg
        let sunMoved = acos(min(simd_dot(simd_normalize(sun), simd_normalize(other.sun)), 1)) > turn
        let eyeMoved = simd_length(eye - other.eye) > max(simd_length(eye), 1) * turn
        return sunMoved || eyeMoved || abs(spin - other.spin) > turn
    }
}

/// The CPU half of the planet and ring shaders: pure functions over preallocated buffers.
enum CosmosWorldBake {
    /// The planet's equirectangular map: 1024 texels round the equator keep the bands and
    /// their churn sharp on the follow view, where the planet is ~360 px across.
    static let planetWidth = 1024
    static let planetHeight = 512
    /// The rings' polar map: radius across, angle down. 1024 angles keep the planet's shadow a
    /// crisp band; 256 radii resolve every ringlet.
    static let ringRadii = 256
    static let ringAngles = 1024

    /// `CosmosSystem.ringDensity`, tabulated: the bakes read it a quarter of a million times.
    static let densityTable: [Float] = (0...densitySteps).map { CosmosSystem.ringDensity(Float($0) / Float(densitySteps)) }
    private static let densitySteps = 2048

    static func density(_ x: Float) -> Float {
        guard x > 0, x < 1 else { return 0 }
        let f = x * Float(densitySteps)
        let i = min(Int(f), densitySteps - 1)
        let w = f - Float(i)
        return densityTable[i] + (densityTable[i + 1] - densityTable[i]) * w
    }

    /// The surface's albedo, row-major rgb, over the body's own longitude and latitude: the
    /// latitude bands of `cosmos_planet.mat`, bent by a slow noise so the belts meander, with
    /// finer mottling and paler, calmer poles. Baked once; `time` is the shader's band drift.
    static func albedo(time: Float, width: Int = planetWidth, height: Int = planetHeight) -> [Float] {
        var out = [Float](repeating: 0, count: width * height * 3)
        let t = time * 0.02
        out.withUnsafeMutableBufferPointer { buffer in
            let rows = RowPointer(buffer.baseAddress!)
            DispatchQueue.concurrentPerform(iterations: height) { y in
                let lat = Float.pi / 2 - (Float(y) + 0.5) / Float(height) * .pi
                for x in 0..<width {
                    let lon = (Float(x) + 0.5) / Float(width) * 2 * .pi
                    let q = SIMD3(cos(lat) * sin(lon), sin(lat), cos(lat) * cos(lon))
                    let swirl = Plasma.noise(q * 5 + SIMD3(t, 0, -t)) + 0.5 * Plasma.noise(q * 11 - SIMD3(0, t, t))
                    let bent = q.y + 0.07 * (swirl - 0.75)
                    let bands = 0.5 + 0.5 * sin(bent * 23 + 1.7 * sin(bent * 9))
                    let belts = CosmosSystem.smoothstep(0.25, 0.85, bands)
                    var albedo = simd_mix(CosmosWorldLook.bandDark, CosmosWorldLook.bandLight, SIMD3(repeating: belts))
                    albedo *= 0.85 + 0.3 * Plasma.noise(q * 26)
                    let polar = CosmosSystem.smoothstep(0.75, 0.95, abs(q.y))
                    albedo = simd_mix(albedo, CosmosWorldLook.bandLight * 0.8, SIMD3(repeating: polar))
                    let i = (y * width + x) * 3
                    rows.base[i] = albedo.x
                    rows.base[i + 1] = albedo.y
                    rows.base[i + 2] = albedo.z
                }
            }
        }
        return out
    }

    /// The planet's radiance towards the camera, rgba, into `out` (`planetWidth × planetHeight`
    /// texels, top row first): `cosmos_planet.mat`'s fragment for every point of the sphere.
    static func planet(_ light: CosmosWorldLight, albedo: [Float], tables: CosmosWorldTables,
                       into out: UnsafeMutablePointer<Float>) {
        let width = planetWidth
        let height = planetHeight
        let radius = CosmosSystem.planetRadius
        let inner = CosmosSystem.ringInner
        let span = CosmosSystem.ringOuter - CosmosSystem.ringInner
        let sunColor = CosmosWorldLook.sunColor
        let air = CosmosWorldLook.atmosphere * sunColor
        // The surface has turned by `spin`: the texel at longitude λ shows the body's λ − spin.
        let shift = light.spin / (2 * .pi) * Float(width)
        let rows = RowPointer(out)
        albedo.withUnsafeBufferPointer { albedoBuffer in
            let source = RowPointer(UnsafeMutablePointer(mutating: albedoBuffer.baseAddress!))
            DispatchQueue.concurrentPerform(iterations: height) { y in
                let sinLat = tables.sinLat[y]
                let cosLat = tables.cosLat[y]
                for x in 0..<width {
                    let n = SIMD3(cosLat * tables.sinLon[x], sinLat, cosLat * tables.cosLon[x])
                    let p = n * radius
                    let l = simd_normalize(light.sun - p)
                    let v = simd_normalize(light.eye - p)
                    let nol = simd_dot(n, l)
                    let nov = min(max(simd_dot(n, v), 0), 1)

                    var column = (Float(x) - shift).truncatingRemainder(dividingBy: Float(width))
                    if column < 0 { column += Float(width) }
                    let c0 = min(Int(column), width - 1)
                    let c1 = (c0 + 1) % width
                    let w = column - Float(c0)
                    let a0 = (y * width + c0) * 3
                    let a1 = (y * width + c1) * 3
                    let left = SIMD3<Float>(source.base[a0], source.base[a0 + 1], source.base[a0 + 2])
                    let right = SIMD3<Float>(source.base[a1], source.base[a1 + 1], source.base[a1 + 2])
                    let albedo: SIMD3<Float> = left + (right - left) * w

                    // A soft terminator: the atmosphere carries a little light past it.
                    let day = CosmosSystem.smoothstep(-0.12, 0.55, nol)

                    // Ring shadow: follow the ray to the star to the ring plane (local y = 0)
                    // and read the ring's opacity there.
                    var ringShade: Float = 1
                    if abs(l.y) > 1e-4 {
                        let along = -p.y / l.y
                        if along > 0 {
                            let hx = p.x + l.x * along
                            let hz = p.z + l.z * along
                            let r = (hx * hx + hz * hz).squareRoot()
                            let d = density((r - inner) / span)
                            ringShade = 1 - 0.85 * d
                        }
                    }

                    var radiance = albedo * sunColor * (day * ringShade)
                    // Night side: a faint glow, so the disc still reads as a ball against the dark.
                    radiance += albedo * 0.012
                    // Atmosphere: a Fresnel rim, lit where the star reaches it, and, seen against
                    // the star, a forward-scattering ring round the night side.
                    let edge = 1 - nov
                    let fresnel = edge * edge * edge
                    let litRim = CosmosSystem.smoothstep(-0.35, 0.35, nol)
                    let facing = min(max(-simd_dot(v, l), 0), 1)
                    let f2 = facing * facing
                    let against = f2 * f2 * f2
                    let forward = against * edge * edge * 2.5
                    radiance += air * (fresnel * litRim * ringShade + forward)

                    let shown = GlowBuilder.filmic(radiance)
                    let i = (y * width + x) * 4
                    rows.base[i] = shown.x
                    rows.base[i + 1] = shown.y
                    rows.base[i + 2] = shown.z
                    rows.base[i + 3] = 1
                }
            }
        }
    }

    /// The rings' premultiplied radiance and opacity, into `out` (`ringRadii` across ×
    /// `ringAngles` down): `cosmos_ring.mat`'s fragment for every point of the annulus.
    static func ring(_ light: CosmosWorldLight, into out: UnsafeMutablePointer<Float>) {
        let width = ringRadii
        let height = ringAngles
        let radius = CosmosSystem.planetRadius
        let inner = CosmosSystem.ringInner
        let span = CosmosSystem.ringOuter - CosmosSystem.ringInner
        let lit = CosmosWorldLook.ringColor * CosmosWorldLook.sunColor * CosmosWorldLook.ringGain
        let rows = RowPointer(out)
        DispatchQueue.concurrentPerform(iterations: height) { y in
            let angle = (Float(y) + 0.5) / Float(height) * 2 * .pi
            let ca = cos(angle)
            let sa = sin(angle)
            for x in 0..<width {
                let u = (Float(x) + 0.5) / Float(width)
                let r = inner + u * span
                let p = SIMD3(r * ca, 0, r * sa)
                let l = simd_normalize(light.sun - p)
                let v = simd_normalize(light.eye - p)
                // The planet's shadow: a cylinder of its radius behind it as seen from the star,
                // with a narrow penumbra that opens a little with distance.
                let along = simd_dot(p, l)
                let across = simd_length(p - l * along)
                let penumbra = radius * 0.03 + 0.04 * max(-along, 0)
                let sunlit = CosmosSystem.smoothstep(radius - penumbra, radius + penumbra, across)
                // A faint floor: particles in the shadow still catch light off their neighbours.
                let shadow = along < 0 ? CosmosSystem.mix(0.07, 1, sunlit) : 1
                // Ring particles scatter forward: bright when seen against the light.
                let facing = min(max(-simd_dot(v, l), 0), 1)
                let f2 = facing * facing
                let forward = f2 * f2
                let radiance = GlowBuilder.filmic(lit * (shadow * (0.75 + 1.6 * forward)))
                // Shadowed particles veil less of the sky: the shadow stays a band on the rings.
                let veil = along < 0 ? CosmosSystem.mix(0.55, 1, sunlit) : 1
                let alpha = density(u) * 0.62 * veil
                let i = (y * width + x) * 4
                rows.base[i] = radiance.x * alpha
                rows.base[i + 1] = radiance.y * alpha
                rows.base[i + 2] = radiance.z * alpha
                rows.base[i + 3] = alpha
            }
        }
    }

    /// The trail's fade along its arc, 0 at the planet → 1 at its tail: it starts just behind
    /// the rings, not through them, and dies away quadratically.
    static func trailFade(_ u: Float) -> Float {
        (1 - u) * (1 - u) * CosmosSystem.smoothstep(0.1, 0.22, u)
    }

    /// Rows write only their own texels, hence the unchecked `Sendable`.
    struct RowPointer: @unchecked Sendable {
        let base: UnsafeMutablePointer<Float>
        init(_ base: UnsafeMutablePointer<Float>) { self.base = base }
    }
}

/// Per-row and per-column sines of the planet map, computed once.
struct CosmosWorldTables: Sendable {
    let sinLat: [Float]
    let cosLat: [Float]
    let sinLon: [Float]
    let cosLon: [Float]

    init(width: Int = CosmosWorldBake.planetWidth, height: Int = CosmosWorldBake.planetHeight) {
        let lats: [Float] = (0..<height).map { (row: Int) -> Float in
            let v: Float = (Float(row) + 0.5) / Float(height)
            return Float.pi / 2 - v * Float.pi
        }
        let lons: [Float] = (0..<width).map { (column: Int) -> Float in
            let u: Float = (Float(column) + 0.5) / Float(width)
            return u * 2 * Float.pi
        }
        sinLat = lats.map { sin($0) }
        cosLat = lats.map { cos($0) }
        sinLon = lons.map { sin($0) }
        cosLon = lons.map { cos($0) }
    }
}

// MARK: - RealityKit side

/// A float16 texture baked on the CPU again and again: the scratch it is baked into, the
/// staging buffer it is converted into, and the texture — all allocated once.
final class CosmosBakedTexture: @unchecked Sendable {
    let width: Int
    let height: Int
    /// Float32 rgba, top row first: what a bake writes.
    let scratch: UnsafeMutablePointer<Float>
    private let staging: MTLBuffer
    private let texture: LowLevelTexture
    let resource: TextureResource
    private let mips: Int

    @MainActor
    init(width: Int, height: Int) async throws {
        guard let metal = GlowMetal.shared,
              let staging = metal.device.makeBuffer(length: width * height * 8, options: .storageModeShared)
        else { throw GlowEntity.GlowError.noMetal }
        self.width = width
        self.height = height
        self.staging = staging
        scratch = .allocate(capacity: width * height * 4)
        scratch.initialize(repeating: 0, count: width * height * 4)
        // A mip chain: from the far view the planet is a few dozen pixels across, and its bands
        // and ringlets would shimmer without one.
        mips = Int(log2(Double(max(width, height)))) + 1
        texture = try LowLevelTexture(descriptor: LowLevelTexture.Descriptor(
            pixelFormat: .rgba16Float, width: width, height: height, mipmapLevelCount: mips,
            textureUsage: [.shaderRead]
        ))
        resource = try await TextureResource(from: texture)
    }

    deinit { scratch.deallocate() }

    /// Float32 scratch → float16 staging; off the main thread, while no upload is in flight.
    func convert() {
        let count = width * height * 4
        var src = vImage_Buffer(data: scratch, height: 1, width: vImagePixelCount(count), rowBytes: count * 4)
        var dst = vImage_Buffer(data: staging.contents(), height: 1, width: vImagePixelCount(count), rowBytes: count * 2)
        vImageConvert_PlanarFtoPlanar16F(&src, &dst, 0)
    }

    /// Copies the staging buffer into the texture and rebuilds its mips, on `commandBuffer`.
    @MainActor
    func encodeUpload(_ commandBuffer: MTLCommandBuffer) {
        let target = texture.replace(using: commandBuffer)
        guard let blit = commandBuffer.makeBlitCommandEncoder() else { return }
        blit.copy(from: staging, sourceOffset: 0, sourceBytesPerRow: width * 8,
                  sourceBytesPerImage: width * height * 8,
                  sourceSize: MTLSize(width: width, height: height, depth: 1),
                  to: target, destinationSlice: 0, destinationLevel: 0,
                  destinationOrigin: MTLOrigin(x: 0, y: 0, z: 0))
        if mips > 1 { blit.generateMipmaps(for: target) }
        blit.endEncoding()
    }
}

/// One of the orbit trail's two ribbons, and the view it was last laid for.
@MainActor
final class CosmosTrailRibbon {
    let entity: ModelEntity
    let mesh: LowLevelMesh
    /// The eye it was laid for, in the frame turning with the orbit; nil until laid.
    var laidEye: SIMD3<Float>?
    var laidFocal: Float = 0
    var energy: Float = 1

    init(entity: ModelEntity, mesh: LowLevelMesh) {
        self.entity = entity
        self.mesh = mesh
    }

    /// The eye has moved by more than `CosmosWorld.trailRelayShift` of its distance, or the
    /// lens changed (a rotation, a resize).
    func needsLaying(eye: SIMD3<Float>, focal: Float) -> Bool {
        guard let laidEye else { return true }
        let shift = simd_distance(eye, laidEye) / max(simd_length(laidEye), 1e-3)
        return shift > CosmosWorld.trailRelayShift || abs(focal - laidFocal) > 0.02 * max(laidFocal, 1)
    }
}

/// The ringed world on screen: the planet, its rings and the orbit trail, and how each frame
/// drives them.
@MainActor
final class CosmosWorld {
    /// The planet, at its orbit position in the tilted frame; the rings are its child.
    let planet: ModelEntity
    private let ring: ModelEntity
    /// The orbit trail: a pivot at the star turned with the orbit every frame, carrying two
    /// ribbons laid in the orbit's own frame, facing the camera. One shows; the other is laid
    /// again, off screen, once the camera has moved enough, and takes over once it has settled.
    let trail: Entity

    private var planetMaterial: UnlitMaterial
    private var ringMaterial: UnlitMaterial
    private var trailMaterial: UnlitMaterial
    private let ribbons: [CosmosTrailRibbon]
    /// The ribbon on screen.
    private var shownRibbon = 0
    /// When the ribbon laid off screen takes over, on the monotonic clock.
    private var ribbonSwap: Double?

    private let planetTexture: CosmosBakedTexture
    private let ringTexture: CosmosBakedTexture
    private let albedo: [Float]
    private let tables = CosmosWorldTables()

    private var baked: CosmosWorldLight?
    /// The spin the map on the GPU was baked for.
    private var shownSpin: Float?
    private var baking = false
    private var lastBake: Double = -.infinity
    private var reveal: Float = -1
    private var trailTint: Float = -1

    /// Bakes per second at most, while something moves: the orbit turns 6°/s, so light and
    /// shadow step by under a degree.
    static let bakeRate: Double = 8
    /// The rate under Low Power Mode or a serious thermal state: the orbit moves the light 6°
    /// between two bakes, well inside the terminator's 40° softness.
    static let heldBakeRate: Double = 1
    /// Points along the trail's arc.
    static let trailPoints = 96
    /// The trail's half width in world units, and the least it may be on screen, in pixels.
    static let trailHalfWidth: Float = 0.009
    static let trailMinPixels: Float = 1.2
    /// How far the eye may move, as a share of its distance, before the trail is laid again:
    /// about 3° of turn, which the ribbon's width hides.
    static let trailRelayShift: Float = 0.05
    /// How long a ribbon laid off screen waits before it shows. RealityKit renders a mesh
    /// rewritten in the last frames only some of the time (on the simulator, the trail
    /// rewritten every frame was missing from a third to a half of them), so the ribbon on
    /// screen is never the one being written.
    static let trailSettleSeconds: Double = 0.25

    /// Builds the world lit for `light`: the first frame it shows is already baked.
    init(programs: CosmosPrograms, light: CosmosWorldLight, time: Float) async throws {
        let planetTexture = try await CosmosBakedTexture(width: CosmosWorldBake.planetWidth,
                                                         height: CosmosWorldBake.planetHeight)
        let ringTexture = try await CosmosBakedTexture(width: CosmosWorldBake.ringRadii,
                                                       height: CosmosWorldBake.ringAngles)
        self.planetTexture = planetTexture
        self.ringTexture = ringTexture
        albedo = await Task.detached(priority: .userInitiated) { CosmosWorldBake.albedo(time: time) }.value

        var planetMaterial = UnlitMaterial(applyPostProcessToneMap: true)
        planetMaterial.color = .init(tint: .white, texture: .init(planetTexture.resource, sampler: Self.sampler(wrapU: true)))
        self.planetMaterial = planetMaterial
        let planet = ModelEntity(mesh: try Self.sphere(radius: CosmosSystem.planetRadius), materials: [planetMaterial])
        planet.name = "cosmos-planet"
        self.planet = planet

        // Premultiplied, alpha-blended: a ring in front of the planet hides part of it, which
        // additive light cannot do.
        var ringMaterial = UnlitMaterial(program: programs.alpha)
        ringMaterial.color = .init(tint: .white, texture: .init(ringTexture.resource, sampler: Self.sampler(wrapU: false)))
        ringMaterial.blending = .transparent(opacity: .init(floatLiteral: 1))
        ringMaterial.writesDepth = false
        ringMaterial.faceCulling = .none
        self.ringMaterial = ringMaterial
        let ring = ModelEntity(mesh: try Self.annulus(inner: CosmosSystem.ringInner, outer: CosmosSystem.ringOuter),
                               materials: [ringMaterial])
        planet.addChild(ring)
        self.ring = ring

        var trailMaterial = UnlitMaterial(program: programs.additive)
        let trailTexture = try await GlowEntity.texture(Self.trailImage())
        trailMaterial.color = .init(tint: .white, texture: .init(trailTexture, sampler: Self.sampler(wrapU: false)))
        trailMaterial.blending = .transparent(opacity: .init(floatLiteral: 1))
        trailMaterial.writesDepth = false
        trailMaterial.faceCulling = .none
        self.trailMaterial = trailMaterial
        let trail = Entity()
        trail.name = "cosmos-trail"
        // Hidden until `update` lays its first ribbon.
        trail.isEnabled = false
        var ribbons: [CosmosTrailRibbon] = []
        for _ in 0..<2 {
            let mesh = try Self.trailMesh()
            let entity = ModelEntity(mesh: try await MeshResource(from: mesh), materials: [trailMaterial])
            // Both stay in the scene; the one off screen is shrunk to a point inside the star.
            entity.scale = SIMD3(repeating: Self.hiddenScale)
            trail.addChild(entity)
            ribbons.append(CosmosTrailRibbon(entity: entity, mesh: mesh))
        }
        self.ribbons = ribbons
        self.trail = trail

        await bake(light)
    }

    /// - Parameters:
    ///   - eye: the camera, in scene coordinates.
    ///   - now: a monotonic clock, in seconds, for the bake rate.
    ///   - focal: pixels per world unit at distance 1, for the trail's least width.
    func update(system: CosmosSystem, time: Float, eye: SIMD3<Float>, reveal: Float, now: Double, focal: Float) {
        planet.position = system.planetPosition(time)

        // Low Power Mode or a hot device: the surface holds its turn, as the star's churn
        // does, and the light follows the orbit once a second instead of eight times.
        let held = CosmosPowerState.shared.constrained
        var light = CosmosWorldLight(system: system, time: time, eye: eye)
        if held, let shownSpin { light.spin = shownSpin }
        let rate = held ? Self.heldBakeRate : Self.bakeRate
        if !baking, now - lastBake >= 1 / rate, baked.map({ light.differs(from: $0) }) ?? true {
            lastBake = now
            Task { await self.bake(light) }
        }

        // The bands turn every frame, as Android's shader turns them: the planet carries the
        // last bake round by the spin since, and its light with it — under 1.2° at 9°/s and
        // 8 bakes a second — while the rings, whose map holds the planet's shadow, stay put.
        let turn = held ? 0 : light.spin - (shownSpin ?? light.spin)
        planet.orientation = system.tilt * simd_quatf(angle: turn, axis: SIMD3(0, 1, 0))
        ring.orientation = simd_quatf(angle: -turn, axis: SIMD3(0, 1, 0))

        if abs(reveal - self.reveal) > 1e-4 {
            self.reveal = reveal
            planetMaterial.color.tint = GlowEntity.linearTint(CGFloat(min(max(reveal, 0), 1)))
            planet.model?.materials = [planetMaterial]
            ringMaterial.blending = .transparent(opacity: .init(floatLiteral: min(max(reveal, 0), 1)))
            ring.model?.materials = [ringMaterial]
        }

        let energy = placeTrail(system: system, time: time, eye: eye, focal: focal, now: now)
        let tint = min(max(reveal * system.trailVisibility(eye: eye, time: time) * energy, 0), 1)
        // A new material only once the change would show (under one 8-bit step otherwise).
        if abs(tint - trailTint) > 1.0 / 512 || (tint <= 1e-3) != (trailTint <= 1e-3) {
            trailTint = tint
            trailMaterial.color.tint = GlowEntity.linearTint(CGFloat(tint))
            for ribbon in ribbons { ribbon.entity.model?.materials = [trailMaterial] }
        }
        trail.isEnabled = tint > 1e-3
    }

    /// Bakes both maps for `light` off the main thread, then uploads them in one command buffer.
    /// One bake at a time: the next starts once this one's upload has run on the GPU.
    private func bake(_ light: CosmosWorldLight) async {
        baking = true
        let planetTexture = planetTexture
        let ringTexture = ringTexture
        let albedo = albedo
        let tables = tables
        await Task.detached(priority: .userInitiated) {
            CosmosWorldBake.planet(light, albedo: albedo, tables: tables, into: planetTexture.scratch)
            CosmosWorldBake.ring(light, into: ringTexture.scratch)
            planetTexture.convert()
            ringTexture.convert()
        }.value
        baked = light
        guard let metal = GlowMetal.shared, let commandBuffer = metal.queue.makeCommandBuffer() else {
            baking = false
            return
        }
        planetTexture.encodeUpload(commandBuffer)
        ringTexture.encodeUpload(commandBuffer)
        await withCheckedContinuation { (done: CheckedContinuation<Void, Never>) in
            commandBuffer.addCompletedHandler { _ in done.resume() }
            commandBuffer.commit()
        }
        shownSpin = light.spin
        baking = false
    }

    /// Turns the trail with the orbit, lays a ribbon again once the camera has moved enough
    /// and shows it once it has settled. Returns the shown ribbon's energy (see `layTrail`).
    private func placeTrail(system: CosmosSystem, time: Float, eye: SIMD3<Float>, focal: Float,
                            now: Double) -> Float {
        // orbitPoint(head − s) = frame · R_y(head) · orbitPoint₀(−s): the arc behind the planet
        // is fixed in a frame turning with it, so only the camera moves the ribbon's width.
        let turn = system.frame * simd_quatf(angle: CosmosSystem.orbitAngle(time), axis: SIMD3(0, 1, 0))
        trail.orientation = turn
        let local = turn.inverse.act(eye)
        let shown = ribbons[shownRibbon]
        let hidden = ribbons[1 - shownRibbon]
        if let swap = ribbonSwap {
            if now >= swap {
                hidden.entity.scale = .one
                shown.entity.scale = SIMD3(repeating: Self.hiddenScale)
                shownRibbon = 1 - shownRibbon
                ribbonSwap = nil
            }
        } else if shown.laidEye == nil {
            // The first ribbon shows straight away: the scene fades in from black anyway.
            layTrail(shown, eye: local, focal: focal)
            shown.entity.scale = .one
        } else if shown.needsLaying(eye: local, focal: focal) {
            layTrail(hidden, eye: local, focal: focal)
            ribbonSwap = now + Self.trailSettleSeconds
        }
        return ribbons[shownRibbon].energy
    }

    /// Lays the trail along the orbit behind the planet, in the frame turning with it (planet
    /// at angle 0), widened across the line of sight from `eye` (in that frame), into
    /// `ribbon`'s mesh. Its energy is the share of its light a stroke held at its least width
    /// keeps, at the planet end, where it is brightest.
    private func layTrail(_ ribbon: CosmosTrailRibbon, eye: SIMD3<Float>, focal: Float) {
        let arc = CosmosSystem.trailDegrees * CosmosSystem.deg
        let count = Self.trailPoints
        let radius = CosmosSystem.orbitRadius
        var energy: Float = 1
        ribbon.mesh.replaceUnsafeMutableBytes(bufferIndex: 0) { raw in
            let floats = raw.bindMemory(to: Float.self)
            for i in 0..<count {
                let u = Float(i) / Float(count - 1)
                let a = -u * arc
                let p = SIMD3(radius * cos(a), 0, -radius * sin(a))
                // The orbit runs toward decreasing angle behind the planet.
                let tangent = SIMD3(sin(a), 0, cos(a))
                let toEye = eye - p
                let depth = max(simd_length(toEye), 1e-3)
                var side = simd_cross(tangent, toEye)
                let length = simd_length(side)
                side = length > 1e-6 ? side / length : .zero
                var halfWidth = Self.trailHalfWidth
                let pixels = halfWidth * focal / depth
                if pixels < Self.trailMinPixels {
                    halfWidth = Self.trailMinPixels * depth / focal
                    if i == 0 { energy = pixels / Self.trailMinPixels }
                }
                let o = i * 2 * 5
                let left = p - side * halfWidth
                let right = p + side * halfWidth
                floats[o] = left.x
                floats[o + 1] = left.y
                floats[o + 2] = left.z
                floats[o + 5] = right.x
                floats[o + 6] = right.y
                floats[o + 7] = right.z
                let across = Self.trailAcross(i)
                floats[o + 3] = across.u
                floats[o + 4] = across.top
                floats[o + 8] = across.u
                floats[o + 9] = across.bottom
            }
        }
        ribbon.mesh.replaceUnsafeMutableIndices { raw in Self.writeTrailIndices(raw) }
        ribbon.laidEye = eye
        ribbon.laidFocal = focal
        ribbon.energy = energy
    }

    /// The scale of the ribbon off screen: a speck at the star's centre, hidden by the star.
    private static let hiddenScale: Float = 1e-4

    /// Two triangles per span between arc points.
    private static func writeTrailIndices(_ raw: UnsafeMutableRawBufferPointer) {
        let indices = raw.bindMemory(to: UInt32.self)
        for i in 0..<(trailPoints - 1) {
            let a = UInt32(i * 2)
            let b = a + 2
            let o = i * 6
            indices[o] = a
            indices[o + 1] = a + 1
            indices[o + 2] = b
            indices[o + 3] = a + 1
            indices[o + 4] = b + 1
            indices[o + 5] = b
        }
    }

    /// The trail's texture coordinates at arc point `i`: `u` along the arc, and `v` on the
    /// ribbon's two edges, inset half a texel so the fade row never bleeds in.
    private static func trailAcross(_ i: Int) -> (u: Float, top: Float, bottom: Float) {
        let rows = Float(trailRows)
        return ((Float(i) + 0.5) / Float(trailPoints), 1 - 0.5 / rows, 0.5 / rows)
    }

    // MARK: Meshes

    /// A UV sphere whose texture coordinates follow the bakes' equirectangular layout: texel
    /// (x, y) is longitude (x + ½) / w · 2π from +Z towards +X, latitude from the top row down.
    private static func sphere(radius: Float, stacks: Int = 64, slices: Int = 96) throws -> MeshResource {
        var positions: [SIMD3<Float>] = []
        var normals: [SIMD3<Float>] = []
        var uvs: [SIMD2<Float>] = []
        var indices: [UInt32] = []
        for j in 0...stacks {
            let lat = Float.pi / 2 - Float(j) / Float(stacks) * .pi
            for i in 0...slices {
                let lon = Float(i) / Float(slices) * 2 * .pi
                let n = SIMD3(cos(lat) * sin(lon), sin(lat), cos(lat) * cos(lon))
                positions.append(n * radius)
                normals.append(n)
                // RealityKit's v runs up from the bottom row; the bakes store the top row first.
                uvs.append(SIMD2(Float(i) / Float(slices), 1 - Float(j) / Float(stacks)))
            }
        }
        let row = UInt32(slices + 1)
        for j in 0..<UInt32(stacks) {
            for i in 0..<UInt32(slices) {
                let a = j * row + i
                let b = a + row
                // Counter-clockwise seen from outside.
                indices.append(contentsOf: [a, b, a + 1, a + 1, b, b + 1])
            }
        }
        var descriptor = MeshDescriptor(name: "cosmos-planet")
        descriptor.positions = MeshBuffers.Positions(positions)
        descriptor.normals = MeshBuffers.Normals(normals)
        descriptor.textureCoordinates = MeshBuffers.TextureCoordinates(uvs)
        descriptor.primitives = .triangles(indices)
        return try MeshResource.generate(from: [descriptor])
    }

    /// The rings: a flat annulus in the local XZ plane. `u` runs across it, inner to outer
    /// edge; `v` round it, as the ring bake's rows.
    private static func annulus(inner: Float, outer: Float, segments: Int = 256) throws -> MeshResource {
        var positions: [SIMD3<Float>] = []
        var uvs: [SIMD2<Float>] = []
        var indices: [UInt32] = []
        for i in 0...segments {
            let f = Float(i) / Float(segments)
            let a = f * 2 * .pi
            for (k, r) in [inner, outer].enumerated() {
                positions.append(SIMD3(r * cos(a), 0, r * sin(a)))
                uvs.append(SIMD2(Float(k), 1 - f))
            }
        }
        for i in 0..<UInt32(segments) {
            let a = i * 2
            let b = a + 2
            indices.append(contentsOf: [a, a + 1, b, a + 1, b + 1, b])
        }
        var descriptor = MeshDescriptor(name: "cosmos-ring")
        descriptor.positions = MeshBuffers.Positions(positions)
        descriptor.normals = MeshBuffers.Normals(Array(repeating: SIMD3<Float>(0, 1, 0), count: positions.count))
        descriptor.textureCoordinates = MeshBuffers.TextureCoordinates(uvs)
        descriptor.primitives = .triangles(indices)
        return try MeshResource.generate(from: [descriptor])
    }

    /// A trail ribbon: two vertices per arc point, written whole by `layTrail` each time it is
    /// laid (`u` along the arc, `v` across it, as `GlowBuilder.ribbons` lays its rows).
    private static func trailMesh() throws -> LowLevelMesh {
        let count = trailPoints
        let descriptor = LowLevelMesh.Descriptor(
            vertexCapacity: count * 2,
            vertexAttributes: [
                .init(semantic: .position, format: .float3, offset: 0),
                .init(semantic: .uv0, format: .float2, offset: 12),
            ],
            vertexLayouts: [.init(bufferIndex: 0, bufferStride: 5 * MemoryLayout<Float>.stride)],
            indexCapacity: (count - 1) * 6,
            indexType: .uint32
        )
        let mesh = try LowLevelMesh(descriptor: descriptor)
        mesh.replaceUnsafeMutableIndices { raw in writeTrailIndices(raw) }
        // The whole orbit, rings included: the box holds whatever arc is laid.
        let reach = CosmosSystem.orbitRadius + CosmosSystem.ringOuter
        mesh.parts.replaceAll([
            LowLevelMesh.Part(indexCount: (count - 1) * 6, topology: .triangle,
                              bounds: BoundingBox(min: SIMD3(repeating: -reach), max: SIMD3(repeating: reach))),
        ])
        return mesh
    }

    private static let trailRows = 8

    /// The trail's colour along its arc and its round cross-section, as `GlowBuilder.ribbons`
    /// paints a stroke's row.
    private static func trailImage() -> GlowImage {
        let rows = trailRows
        var image = GlowImage(width: trailPoints, height: rows, cell: rows)
        for x in 0..<trailPoints {
            let u = Float(x) / Float(trailPoints - 1)
            let color = CosmosWorldLook.trailColor * CosmosWorldBake.trailFade(u)
            for y in 0..<rows {
                let across = -1 + 2 * Float(y) / Float(rows - 1)
                let profile = exp(-across * across * 4.5) * (1 - across * across)
                // Alpha 1: the additive blend scales colour by alpha, which would square the profile.
                image.set(x, y, GlowBuilder.filmic(color * profile), alpha: 1)
            }
        }
        return image
    }

    private static func sampler(wrapU: Bool) -> MaterialParameters.Texture.Sampler {
        let descriptor = MTLSamplerDescriptor()
        descriptor.minFilter = .linear
        descriptor.magFilter = .linear
        descriptor.mipFilter = .linear
        descriptor.sAddressMode = wrapU ? .repeat : .clampToEdge
        descriptor.tAddressMode = .clampToEdge
        return .init(descriptor)
    }
}

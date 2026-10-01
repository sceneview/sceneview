import Accelerate
import Foundation
import Metal
import os
import RealityKit
import simd
import SwiftUI

// The Spacetime mode on screen: the sheet, a 39 520-vertex `LowLevelMesh` whose heights and
// shading are filled on the CPU from `CosmosSpacetime`'s field, and the small planets resting
// in their wells. The ringed world stays `CosmosWorld`'s, placed on the sheet by the engine.
//
// RealityKit's stock `UnlitMaterial` has no vertex colour and no fragment hook, so the sheet's
// shade travels in its texture coordinate: `u` indexes a 1024-texel linear ramp from black to
// 1.25 × the base colour. The material skips the post-process tone map, so a ramp texel is
// what reaches the screen — the hollow round the star is black, not tone-mapped grey.
//
// The star's shadow is the one term that needs a pixel's resolution: its penumbra is narrower
// than the grid. As on Android, it is read per fragment from the horizon map baked once
// (`CosmosSpacetime.horizonMap`, 512², ±4): a second part of the same mesh, the inner rings
// again a hair above the sheet, draws the map with premultiplied alpha. Where the map says the
// light is hidden (visibility v) it covers the sheet's shade b with the ambient-only shade b₀:
// b·v + b₀·(1 − v) is Android's per-pixel shade exactly, below the 1.25 ceiling.

/// A sphere over the sheet that shades it: the ray from a vertex towards the light passing
/// within `2 radius` of `center` dims it, fully within `0.4 radius`.
struct CosmosSpacetimeCaster: Sendable, Equatable {
    var center: SIMD3<Float>
    var radius: Float
    var alpha: Float
}

/// The ringed world's rings over the sheet: their plane, and how opaque they are.
struct CosmosSpacetimeRing: Sendable, Equatable {
    var center: SIMD3<Float>
    var normal: SIMD3<Float>
    var alpha: Float
}

/// The sheet's per-vertex fill: heights and brightness from the field, in floats, with vDSP.
/// What does not move — the grid, the window, the star's unit well, its horizon, the ambient
/// occlusion and the spot — is computed once; a fill adds the moving wells, the weight, the
/// lift and the shadows.
final class CosmosSpacetimeFill: @unchecked Sendable {
    let count: Int
    /// The horizon overlay's vertices: the first `overlayRings` rings again, after the sheet's.
    static let overlayRings: Int = {
        // The ring that clears the map's corners, ±4 on both axes.
        let corner = CosmosSpacetime.horizonMapExtent * Float(2).squareRoot()
        return CosmosSpacetime.gridRadii.firstIndex { $0 >= corner }! + 1
    }()
    static var overlayCount: Int { overlayRings * CosmosSpacetime.gridSectors }
    /// How far the overlay sits above the sheet, so the depth test never ties.
    static let overlayLift: Float = 0.002
    /// The horizon map's `u, v` at (x, z). Texel (i, j) is at x, z = `horizonTexel` i, j, and
    /// RealityKit's `v` runs up from the image's last row: (i, j)'s centre is at
    /// u = (i + ½) / n, v = 1 − (j + ½) / n.
    @inline(__always)
    static func horizonUV(_ x: Float, _ z: Float) -> SIMD2<Float> {
        let extent = CosmosSpacetime.horizonMapExtent
        return SIMD2(0.5 + x / (2 * extent), 0.5 - z / (2 * extent))
    }
    let x: UnsafeMutablePointer<Float>
    let z: UnsafeMutablePointer<Float>
    /// The last fill's heights and brightness (`CosmosSpacetime.brightness`).
    let height: UnsafeMutablePointer<Float>
    let brightness: UnsafeMutablePointer<Float>

    private let window: UnsafeMutablePointer<Float>
    private let windowX: UnsafeMutablePointer<Float>
    private let windowZ: UnsafeMutablePointer<Float>
    private let starRaw: UnsafeMutablePointer<Float>
    private let starGx: UnsafeMutablePointer<Float>
    private let starGz: UnsafeMutablePointer<Float>
    /// Ambient occlusion and the spot. The star's horizon is the overlay's, per fragment.
    private let occlusion: UnsafeMutablePointer<Float>
    private let spot: UnsafeMutablePointer<Float>
    private let raw: UnsafeMutablePointer<Float>
    private let gx: UnsafeMutablePointer<Float>
    private let gz: UnsafeMutablePointer<Float>
    /// Per worker: dx, dz, d², 1/√, a temporary — one chunk each.
    private let scratch: [UnsafeMutablePointer<Float>]
    private let buffers: [UnsafeMutablePointer<Float>]

    /// Vertices a worker processes at once: its scratch stays in cache.
    static let chunk = 1024
    static let workers = 2
    /// `CosmosSystem.ringDensity`, tabulated across the ring.
    private static let ringTable: [Float] = (0..<256).map { CosmosSystem.ringDensity((Float($0) + 0.5) / 256) }

    init() {
        let radii = CosmosSpacetime.gridRadii
        let sectors = CosmosSpacetime.gridSectors
        let total = radii.count * sectors
        count = total
        func make() -> UnsafeMutablePointer<Float> {
            let p = UnsafeMutablePointer<Float>.allocate(capacity: total)
            p.initialize(repeating: 0, count: total)
            return p
        }
        x = make(); z = make(); height = make(); brightness = make()
        window = make(); windowX = make(); windowZ = make()
        starRaw = make(); starGx = make(); starGz = make()
        occlusion = make(); spot = make()
        raw = make(); gx = make(); gz = make()
        buffers = [x, z, height, brightness, window, windowX, windowZ, starRaw, starGx, starGz,
                   occlusion, spot, raw, gx, gz]
        scratch = (0..<Self.workers).map { _ in
            let p = UnsafeMutablePointer<Float>.allocate(capacity: Self.chunk * 5)
            p.initialize(repeating: 0, count: Self.chunk * 5)
            return p
        }

        typealias S = CosmosSpacetime
        let star = S.Body.star.spec
        let de = star.depth * star.width
        let tail = 1 / (S.rim * S.rim + star.width * star.width).squareRoot()
        for (i, r) in radii.enumerated() {
            let rd = Double(r)
            let w = S.window(rd)
            let dw = -S.smoothstepSlope(S.windowStart * S.rim, S.rim, rd)
            let e2 = rd * rd + star.width * star.width
            let unitRaw = -de * (1 / e2.squareRoot() - tail)
            let slope = de * pow(e2, -1.5)
            for j in 0..<sectors {
                let k = i * sectors + j
                let a = 2 * Double.pi * Double(j) / Double(sectors)
                let px = rd * cos(a), pz = rd * sin(a)
                x[k] = Float(px)
                z[k] = Float(pz)
                window[k] = Float(w)
                windowX[k] = rd > 0 ? Float(dw * px / rd) : 0
                windowZ[k] = rd > 0 ? Float(dw * pz / rd) : 0
                starRaw[k] = Float(unitRaw)
                starGx[k] = Float(slope * px)
                starGz[k] = Float(slope * pz)
                occlusion[k] = Float(S.ambientOcclusion(rd))
                spot[k] = Float(S.spot(rd))
            }
        }
    }

    deinit {
        for p in buffers { p.deallocate() }
        for p in scratch { p.deallocate() }
    }

    /// Fills the sheet for `time`, `weight` and `lift`, shaded by `casters` and `ring`, and
    /// writes the interleaved vertices (x, y, z, u, v) into `out` when given: the sheet's
    /// `count`, then the overlay's `overlayCount`, whose `u, v` address the horizon map.
    func fill(time: Double, weight: Double, lift: Double, casters: [CosmosSpacetimeCaster],
              ring: CosmosSpacetimeRing?, into out: UnsafeMutablePointer<Float>?) {
        let field = CosmosSpacetime.Field(time: time, weight: weight)
        let sheet = CosmosSpacetime.Sheet(field: field, weight: weight, lift: lift)
        let wells = Array(field.wells.dropFirst())
        let offset = Float(sheet.offset - lift)
        let count = count
        let per = (count + Self.workers - 1) / Self.workers
        // Each worker writes its own slice of `out`.
        nonisolated(unsafe) let out = out
        DispatchQueue.concurrentPerform(iterations: Self.workers) { worker in
            let start = worker * per
            let end = min(start + per, count)
            var s = start
            while s < end {
                let n = min(Self.chunk, end - s)
                chunk(s, n, weight: Float(weight), wells: wells, offset: offset, casters: casters,
                      ring: ring, scratch: scratch[worker], out: out)
                s += n
            }
        }
    }

    private func chunk(_ s: Int, _ n: Int, weight: Float, wells: [SIMD4<Double>], offset: Float,
                       casters: [CosmosSpacetimeCaster], ring: CosmosSpacetimeRing?,
                       scratch: UnsafeMutablePointer<Float>, out: UnsafeMutablePointer<Float>?) {
        let len = vDSP_Length(n)
        let dx = scratch, dz = scratch + Self.chunk, d2 = scratch + 2 * Self.chunk
        let rs = scratch + 3 * Self.chunk, tmp = scratch + 4 * Self.chunk
        let xs = x + s, zs = z + s, rw = self.raw + s, gxp = self.gx + s, gzp = self.gz + s
        var w = weight
        // The star's well: precomputed at unit weight.
        vDSP_vsmul(starRaw + s, 1, &w, rw, 1, len)
        vDSP_vsmul(starGx + s, 1, &w, gxp, 1, len)
        vDSP_vsmul(starGz + s, 1, &w, gzp, 1, len)
        var tail: Float = 0
        var count32 = Int32(n)
        for well in wells {
            var bx = -Float(well.x), bz = -Float(well.y)
            vDSP_vsadd(xs, 1, &bx, dx, 1, len)
            vDSP_vsadd(zs, 1, &bz, dz, 1, len)
            vDSP_vmma(dx, 1, dx, 1, dz, 1, dz, 1, d2, 1, len)
            var e2 = Float(well.w * well.w)
            vDSP_vsadd(d2, 1, &e2, d2, 1, len)
            vvrsqrtf(rs, d2, &count32)
            let de = well.z * well.w
            var minusDe = -Float(de)
            vDSP_vsma(rs, 1, &minusDe, rw, 1, rw, 1, len)
            tail += Float(de / (CosmosSpacetime.rim * CosmosSpacetime.rim + well.w * well.w).squareRoot())
            // ∂raw/∂x = Dε (d² + ε²)^-3/2 dx.
            vDSP_vsq(rs, 1, tmp, 1, len)
            vDSP_vmul(tmp, 1, rs, 1, tmp, 1, len)
            var plusDe = Float(de)
            vDSP_vmul(tmp, 1, dx, 1, dx, 1, len)
            vDSP_vsma(dx, 1, &plusDe, gxp, 1, gxp, 1, len)
            vDSP_vmul(tmp, 1, dz, 1, dz, 1, len)
            vDSP_vsma(dz, 1, &plusDe, gzp, 1, gzp, 1, len)
        }
        vDSP_vsadd(rw, 1, &tail, rw, 1, len)
        // Windowed, offset and lifted.
        let h = height + s
        vDSP_vmul(rw, 1, window + s, 1, h, 1, len)
        var off = offset
        vDSP_vsadd(h, 1, &off, h, 1, len)
        // ∇(raw W) = ∇raw W + raw ∇W.
        vDSP_vmma(gxp, 1, window + s, 1, rw, 1, windowX + s, 1, gxp, 1, len)
        vDSP_vmma(gzp, 1, window + s, 1, rw, 1, windowZ + s, 1, gzp, 1, len)

        typealias S = CosmosSpacetime
        let light = SIMD3<Float>(S.light)
        let ambient = Float(S.ambient), wrap = Float(S.wrap)
        let gain = Float(S.gain / S.flatShade), ceiling = Float(S.ceiling)
        let inner = CosmosSystem.ringInner, across = CosmosSystem.ringOuter - CosmosSystem.ringInner
        let table = Self.ringTable
        let ringDot = ring.map { simd_dot($0.normal, light) } ?? 0
        let ringLit = ring != nil && abs(ringDot) > 1e-4
        for k in 0..<n {
            let i = s + k
            let gxi = gxp[k], gzi = gzp[k]
            let ndl = (light.y - gxi * light.x - gzi * light.z) / (gxi * gxi + gzi * gzi + 1).squareRoot()
            let lambert = max(0, (ndl + wrap) / (1 + wrap))
            var shadow: Float = 1
            if lambert > 0 {
                let p = SIMD3(x[i], h[k], z[i])
                for caster in casters where caster.alpha > 0 {
                    let oc = caster.center - p
                    let along = max(simd_dot(oc, light), 0)
                    let miss = simd_length_squared(oc - along * light)
                    let reach = 2 * caster.radius
                    guard miss < reach * reach else { continue }
                    let f = Self.smoothstep(0.4 * caster.radius, reach, miss.squareRoot())
                    shadow *= 1 - caster.alpha * (1 - f)
                }
                if ringLit, let ring {
                    let t = simd_dot(ring.center - p, ring.normal) / ringDot
                    if t > 0 {
                        let u = (simd_length(p + t * light - ring.center) - inner) / across
                        if u > 0, u < 1 {
                            shadow *= 1 - 0.85 * ring.alpha * table[min(Int(u * 256), 255)]
                        }
                    }
                }
            }
            let shade = (ambient + (1 - ambient) * lambert * shadow) * occlusion[i]
            let b = min(gain * shade, ceiling) * spot[i]
            brightness[i] = b
            if let out {
                let o = out + 5 * i
                o[0] = x[i]
                o[1] = h[k]
                o[2] = z[i]
                o[3] = (0.5 + b / ceiling * 1023) / 1024
                o[4] = 0.5
                if i < Self.overlayCount {
                    let q = out + 5 * (count + i)
                    let uv = Self.horizonUV(x[i], z[i])
                    q[0] = x[i]
                    q[1] = h[k] + Self.overlayLift
                    q[2] = z[i]
                    q[3] = uv.x
                    q[4] = uv.y
                }
            }
        }
    }

    private static func smoothstep(_ a: Float, _ b: Float, _ x: Float) -> Float {
        let t = min(max((x - a) / (b - a), 0), 1)
        return t * t * (3 - 2 * t)
    }

    static var sheetIndexCount: Int { CosmosSpacetime.triangleCount * 3 }
    static var overlayIndexCount: Int { 6 * (overlayRings - 1) * CosmosSpacetime.gridSectors }

    /// The sheet's triangles, ring-major: (a, b, c) and (b, d, c) face up; then the overlay's,
    /// over its own copy of the inner rings.
    static func writeIndices(_ raw: UnsafeMutableRawBufferPointer) {
        let indices = raw.bindMemory(to: UInt16.self)
        let sectors = CosmosSpacetime.gridSectors
        let base = CosmosSpacetime.vertexCount
        var o = 0
        let runs = [(rings: CosmosSpacetime.gridRadii.count, first: 0), (rings: overlayRings, first: base)]
        for run in runs {
            for i in 0..<(run.rings - 1) {
                for j in 0..<sectors {
                    let a = run.first + i * sectors + j
                    let b = run.first + i * sectors + (j + 1) % sectors
                    let c = a + sectors
                    let d = b + sectors
                    indices[o] = UInt16(a); indices[o + 1] = UInt16(b); indices[o + 2] = UInt16(c)
                    indices[o + 3] = UInt16(b); indices[o + 4] = UInt16(d); indices[o + 5] = UInt16(c)
                    o += 6
                }
            }
        }
    }
}

/// The sheet and the small planets, under the Star scene's root.
@MainActor
final class CosmosSpacetimeScene {
    let root = Entity()
    private let sheet: ModelEntity
    private let mesh: LowLevelMesh
    private var sheetMaterial: UnlitMaterial
    private var sheetOpacity: Float = -1
    /// The horizon overlay's material, and how far it hides the light: the sheet's opacity times
    /// the well's depth, Android's `mix(1, horizon, well)`.
    private var overlayMaterial: UnlitMaterial
    private var overlayOpacity: Float = -1
    private let fill: CosmosSpacetimeFill
    /// Two staging buffers: one is filled while the other's copy to the mesh is in flight.
    private let staging: [MTLBuffer]
    private var uploading = [false, false]
    private var nextStaging = 0
    private var filling = false
    private var uploaded = false
    private var lastFill: Double = -.infinity
    private var filled: FillKey?
    private var planets: [Planet]
    /// Fill timing, for the log: a running sum over the last `timingWindow` fills.
    private var fillSeconds: Double = 0
    private var fills = 0

    private struct FillKey: Equatable, Sendable {
        var time: Float
        var weight: Float
        var lift: Float
        var casters: [CosmosSpacetimeCaster]
        var ring: CosmosSpacetimeRing?
    }

    private struct Planet {
        let body: CosmosSpacetime.Body
        let entity: ModelEntity
        var material: UnlitMaterial
        var alpha: Float = -1
    }

    /// Fills a second at most while the scene moves; half that under Low Power Mode or heat.
    static let fillRate: Double = 60
    static let heldFillRate: Double = 30
    private static let timingWindow = 120
    nonisolated private static let signposts = OSLog(subsystem: "io.github.sceneview.demo", category: .pointsOfInterest)

    /// The small planets, in order: the ringed world is `CosmosWorld`'s.
    static let smallBodies: [CosmosSpacetime.Body] = [.ember, .azure, .ochre, .ice, .moonI, .moonO]

    static func make() async throws -> CosmosSpacetimeScene {
        let fill = await Task.detached(priority: .userInitiated) { CosmosSpacetimeFill() }.value
        let ramp = try await GlowEntity.texture(Self.ramp())
        let started = CACurrentMediaTime()
        let horizon = await Task.detached(priority: .userInitiated) { Self.horizonImage() }.value
        NSLog("[Cosmos] spacetime horizon map baked in %.0f ms", 1000 * (CACurrentMediaTime() - started))
        let overlay = try await GlowEntity.texture(horizon)
        var descriptor = UnlitMaterial.Program.Descriptor()
        descriptor.blendMode = .alpha
        descriptor.applyPostProcessToneMap = false
        let program = await UnlitMaterial.Program(descriptor: descriptor)
        var textures: [CosmosSpacetime.Body: TextureResource] = [:]
        for body in smallBodies {
            let image = await Task.detached(priority: .userInitiated) { CosmosSpacetimeLook.bake(body) }.value
            textures[body] = try await GlowEntity.texture(image)
        }
        return try await CosmosSpacetimeScene(fill: fill, ramp: ramp, overlay: overlay, program: program,
                                              textures: textures)
    }

    private init(fill: CosmosSpacetimeFill, ramp: TextureResource, overlay: TextureResource,
                 program: UnlitMaterial.Program,
                 textures: [CosmosSpacetime.Body: TextureResource]) async throws {
        guard let metal = GlowMetal.shared else { throw GlowEntity.GlowError.noMetal }
        self.fill = fill
        let stride = 5 * MemoryLayout<Float>.stride
        let vertexCount = fill.count + CosmosSpacetimeFill.overlayCount
        let length = vertexCount * stride
        staging = try (0..<2).map { _ in
            guard let buffer = metal.device.makeBuffer(length: length, options: .storageModeShared) else {
                throw GlowEntity.GlowError.noMetal
            }
            return buffer
        }
        let sheetIndices = CosmosSpacetimeFill.sheetIndexCount
        let indexCount = sheetIndices + CosmosSpacetimeFill.overlayIndexCount
        let descriptor = LowLevelMesh.Descriptor(
            vertexCapacity: vertexCount,
            vertexAttributes: [
                .init(semantic: .position, format: .float3, offset: 0),
                .init(semantic: .uv0, format: .float2, offset: 12),
            ],
            vertexLayouts: [.init(bufferIndex: 0, bufferStride: stride)],
            indexCapacity: indexCount,
            indexType: .uint16
        )
        let mesh = try LowLevelMesh(descriptor: descriptor)
        mesh.withUnsafeMutableIndices { CosmosSpacetimeFill.writeIndices($0) }
        let rim = Float(CosmosSpacetime.rim)
        let bounds = BoundingBox(min: SIMD3(-rim, -4, -rim), max: SIMD3(rim, 4, rim))
        mesh.parts.replaceAll([
            LowLevelMesh.Part(indexCount: sheetIndices, topology: .triangle, materialIndex: 0, bounds: bounds),
            LowLevelMesh.Part(indexOffset: sheetIndices * MemoryLayout<UInt16>.stride,
                              indexCount: CosmosSpacetimeFill.overlayIndexCount, topology: .triangle,
                              materialIndex: 1, bounds: bounds),
        ])
        self.mesh = mesh

        // Unlit, and past the tone map: the ramp's linear texels are what the screen shows.
        var material = UnlitMaterial(applyPostProcessToneMap: false)
        material.color = .init(tint: .white, texture: .init(ramp, sampler: Self.sampler))
        material.faceCulling = .none
        sheetMaterial = material
        // Premultiplied, past the tone map, drawn after the sheet and writing no depth.
        var overlayMaterial = UnlitMaterial(program: program)
        overlayMaterial.color = .init(tint: .white, texture: .init(overlay, sampler: Self.sampler))
        overlayMaterial.blending = .transparent(opacity: .init(floatLiteral: 1))
        overlayMaterial.writesDepth = false
        overlayMaterial.faceCulling = .none
        self.overlayMaterial = overlayMaterial
        let sheet = ModelEntity(mesh: try await MeshResource(from: mesh), materials: [material, overlayMaterial])
        sheet.name = "cosmos-spacetime-sheet"
        // Nothing to show until the first fill has landed.
        sheet.isEnabled = false
        self.sheet = sheet
        root.addChild(sheet)

        planets = []
        for body in Self.smallBodies {
            guard let texture = textures[body] else { continue }
            var material = UnlitMaterial(applyPostProcessToneMap: true)
            material.color = .init(tint: .white, texture: .init(texture, sampler: Self.sampler))
            let entity = ModelEntity(mesh: try CosmosWorld.sphere(radius: Float(body.spec.radius), stacks: 32, slices: 48),
                                     materials: [material])
            entity.name = "cosmos-spacetime-\(body)"
            entity.isEnabled = false
            root.addChild(entity)
            planets.append(Planet(body: body, entity: entity, material: material))
        }
    }

    /// Places the small planets and fills the sheet, at most `fillRate` times a second and only
    /// when something on it moved.
    /// - Parameters:
    ///   - progress: the transition, in seconds of `CosmosSpacetime.Timeline`.
    ///   - layout: the lifted sheet at `time` and the current weight.
    ///   - ringed: the ringed world as placed this frame, and its rings' normal.
    func update(time: Float, progress: Double, sheet layout: CosmosSpacetime.Sheet,
                ringed: (center: SIMD3<Float>, normal: SIMD3<Float>), now: Double) {
        typealias T = CosmosSpacetime.Timeline
        var casters: [CosmosSpacetimeCaster] = [
            CosmosSpacetimeCaster(center: ringed.center, radius: CosmosSystem.planetRadius, alpha: 1),
        ]
        for index in planets.indices {
            let body = planets[index].body
            let drop = T.drop(body, progress)
            let p = CosmosSpacetime.position(body, time: Double(time))
            let y = layout.rest(body, at: p) + drop.above
            let center = SIMD3<Float>(Float(p.x), Float(y), Float(p.y))
            planets[index].entity.position = center
            setAlpha(Float(drop.alpha), planet: index)
            if drop.alpha > 0 {
                casters.append(CosmosSpacetimeCaster(center: center, radius: Float(body.spec.radius),
                                                     alpha: Float(drop.alpha)))
            }
        }
        let opacity = Float(T.sheetIntensity(progress))
        setSheetOpacity(opacity, well: Float(layout.weight))

        let key = FillKey(time: time, weight: Float(layout.weight), lift: Float(layout.lift), casters: casters,
                          ring: CosmosSpacetimeRing(center: ringed.center, normal: ringed.normal, alpha: 1))
        let rate = CosmosPowerState.shared.constrained ? Self.heldFillRate : Self.fillRate
        // A frame's worth of slack: a 60 Hz display ticks a hair under 1/60 s apart.
        guard opacity > 0, key != filled, !filling, !uploading[nextStaging],
              now - lastFill >= 1 / rate - 0.002 else { return }
        filled = key
        lastFill = now
        filling = true
        let index = nextStaging
        nextStaging = 1 - nextStaging
        let target = staging[index]
        let fill = fill
        // The staging buffer is not drawn from while it is filled: `uploading[index]` is clear.
        nonisolated(unsafe) let out = target.contents().bindMemory(to: Float.self, capacity: (fill.count + CosmosSpacetimeFill.overlayCount) * 5)
        Task { @MainActor [weak self] in
            let seconds = await Task.detached(priority: .userInitiated) { () -> Double in
                let id = OSSignpostID(log: Self.signposts)
                os_signpost(.begin, log: Self.signposts, name: "spacetime.fill", signpostID: id)
                let started = CACurrentMediaTime()
                fill.fill(time: Double(key.time), weight: Double(key.weight), lift: Double(key.lift),
                          casters: key.casters, ring: key.ring,
                          into: out)
                let elapsed = CACurrentMediaTime() - started
                os_signpost(.end, log: Self.signposts, name: "spacetime.fill", signpostID: id)
                return elapsed
            }.value
            guard let self else { return }
            self.filling = false
            self.logFill(seconds)
            self.upload(target, index: index)
        }
    }

    /// Copies a filled staging buffer into the mesh on the GPU: RealityKit swaps the contents
    /// in once the copy has run, so no frame draws a half-written sheet.
    private func upload(_ buffer: MTLBuffer, index: Int) {
        guard let metal = GlowMetal.shared, let commandBuffer = metal.queue.makeCommandBuffer() else { return }
        uploading[index] = true
        let target = mesh.replace(bufferIndex: 0, using: commandBuffer)
        guard let blit = commandBuffer.makeBlitCommandEncoder() else {
            uploading[index] = false
            return
        }
        blit.copy(from: buffer, sourceOffset: 0, to: target, destinationOffset: 0, size: buffer.length)
        blit.endEncoding()
        commandBuffer.addCompletedHandler { [weak self] _ in
            Task { @MainActor in
                guard let self else { return }
                self.uploading[index] = false
                if !self.uploaded {
                    self.uploaded = true
                    self.sheet.isEnabled = self.sheetOpacity > 0
                }
            }
        }
        commandBuffer.commit()
    }

    private func logFill(_ seconds: Double) {
        fillSeconds += seconds
        fills += 1
        if fills == 1 || fills % Self.timingWindow == 0 {
            NSLog("[Cosmos] spacetime fill %.2f ms (mean of %d)", 1000 * fillSeconds / Double(fills), fills)
        }
    }

    private func setSheetOpacity(_ opacity: Float, well: Float) {
        // 1/128 steps: a new material every frame of the fade would be wasted.
        let stepped = opacity >= 1 ? 1 : (opacity * 128).rounded() / 128
        let hide = min(max(stepped * well, 0), 1)
        let hidden = hide >= 1 ? 1 : (hide * 128).rounded() / 128
        guard stepped != sheetOpacity || hidden != overlayOpacity else { return }
        sheetOpacity = stepped
        overlayOpacity = hidden
        sheet.isEnabled = uploaded && stepped > 0
        sheetMaterial.blending = stepped >= 1 ? .opaque : .transparent(opacity: .init(floatLiteral: stepped))
        overlayMaterial.blending = .transparent(opacity: .init(floatLiteral: hidden))
        sheet.model?.materials = [sheetMaterial, overlayMaterial]
    }

    private func setAlpha(_ alpha: Float, planet index: Int) {
        let stepped = alpha >= 1 ? 1 : (alpha * 64).rounded() / 64
        guard stepped != planets[index].alpha else { return }
        planets[index].alpha = stepped
        planets[index].entity.isEnabled = stepped > 0
        planets[index].material.blending = stepped >= 1 ? .opaque : .transparent(opacity: .init(floatLiteral: stepped))
        planets[index].entity.model?.materials = [planets[index].material]
    }

    /// The horizon overlay, premultiplied: opacity 1 − v where the map's visibility is v, colour
    /// the ambient-only shade b₀ (`hiddenBrightness`) × the base colour × that opacity. Row j is
    /// z = texel(j), column i is x = texel(i), as `CosmosSpacetime.horizonMap` lays them out.
    nonisolated static func horizonImage() -> GlowImage {
        typealias S = CosmosSpacetime
        let n = S.horizonMapSize
        let map = S.horizonMap()
        let base = SIMD3<Float>(S.linear(S.baseColor))
        var image = GlowImage(width: n, height: n, cell: 1)
        for j in 0..<n {
            let z = Double(S.horizonTexel(j))
            for i in 0..<n {
                let x = Double(S.horizonTexel(i))
                let cover = 1 - Float(map[j * n + i]) / 255
                let hidden = Float(S.hiddenBrightness(distance: (x * x + z * z).squareRoot()))
                image.set(i, j, base * hidden * cover, alpha: cover)
            }
        }
        return image
    }

    /// Black to 1.25 × the sheet's base colour, linear.
    private static func ramp() -> GlowImage {
        let width = 1024
        var image = GlowImage(width: width, height: 1, cell: 1)
        let base = SIMD3<Float>(CosmosSpacetime.linear(CosmosSpacetime.baseColor))
        for i in 0..<width {
            image.set(i, 0, base * Float(CosmosSpacetime.ceiling) * Float(i) / Float(width - 1), alpha: 1)
        }
        return image
    }

    private static let sampler: MaterialParameters.Texture.Sampler = {
        let descriptor = MTLSamplerDescriptor()
        descriptor.minFilter = .linear
        descriptor.magFilter = .linear
        descriptor.sAddressMode = .clampToEdge
        descriptor.tAddressMode = .clampToEdge
        return .init(descriptor)
    }()
}

/// The small planets' surfaces, baked once in the sheet's fixed frame and lit by its key light.
enum CosmosSpacetimeLook {
    static func albedo(_ body: CosmosSpacetime.Body, normal n: SIMD3<Float>) -> SIMD3<Float> {
        switch body {
        case .ember:
            let mottle = Plasma.noise(n * 5 + SIMD3(1.7, 0, 0))
            return simd_mix(SIMD3(0.85, 0.32, 0.12), SIMD3(0.45, 0.13, 0.05), SIMD3(repeating: mottle * 0.8))
        case .azure:
            let cloud = Plasma.noise(SIMD3(n.x * 4, n.y * 9, n.z * 4) + 3.1)
            return simd_mix(SIMD3(0.18, 0.45, 0.95), SIMD3(0.9, 0.94, 1), SIMD3(repeating: CosmosSystem.smoothstep(0.55, 0.8, cloud)))
        case .ochre:
            let lat = asin(min(max(n.y, -1), 1))
            let band = 0.5 + 0.5 * sin(lat * 14 + 2.2 * Plasma.noise(n * 3 + 7.3))
            return simd_mix(SIMD3(0.80, 0.58, 0.30), SIMD3(0.55, 0.36, 0.18), SIMD3(repeating: band))
        case .ice:
            let frost = Plasma.noise(n * 6 + 11)
            return simd_mix(SIMD3(0.75, 0.88, 0.95), SIMD3(0.55, 0.7, 0.82), SIMD3(repeating: frost * 0.6))
        case .moonI, .moonO:
            let crater = Plasma.noise(n * 7 + (body == .moonI ? 5 : 9))
            return SIMD3(repeating: 0.42 + 0.2 * crater)
        case .star, .ringed:
            return SIMD3(repeating: 0.5)
        }
    }

    /// The equirectangular map `CosmosWorld.sphere` samples: 256 × 128 for a planet, 128 × 64
    /// for a moon.
    static func bake(_ body: CosmosSpacetime.Body) -> GlowImage {
        let moon = body == .moonI || body == .moonO
        let width = moon ? 128 : 256, height = width / 2
        var image = GlowImage(width: width, height: height, cell: 1)
        let light = SIMD3<Float>(CosmosSpacetime.light)
        for y in 0..<height {
            let lat = Float.pi / 2 - (Float(y) + 0.5) / Float(height) * .pi
            for x in 0..<width {
                let lon = (Float(x) + 0.5) / Float(width) * 2 * .pi
                let n = SIMD3(cos(lat) * sin(lon), sin(lat), cos(lat) * cos(lon))
                let lambert = max(0, (simd_dot(n, light) + 0.05) / 1.05)
                let color = albedo(body, normal: n) * (0.03 + 1.25 * lambert * CosmosWorldLook.sunColor)
                image.set(x, y, GlowBuilder.filmic(color), alpha: 1)
            }
        }
        return image
    }
}

/// The Star scene's mode picker: Starlight or Spacetime, above the dock. `DESIGN.md`
/// `mode-pill-*` (`SceneViewTokens.ModePill`): an opaque dark capsule over any stage, in
/// light and dark mode alike — Android's `SpacetimePill`.
struct SpacetimeModePicker: View {
    @Binding var spacetime: Bool
    @Environment(\.analyticsSampleId) private var analyticsSampleId

    private typealias Pill = SceneViewTokens.ModePill

    var body: some View {
        HStack(spacing: 0) {
            segment("Starlight", selected: !spacetime, event: nil) { spacetime = false }
            segment("Spacetime", selected: spacetime, event: "spacetime") { spacetime = true }
        }
        .padding(.horizontal, SceneViewTokens.Space.xs)
        .background(Capsule().fill(Pill.container))
        .overlay(Capsule().strokeBorder(Pill.outline, lineWidth: Pill.outlineWidth))
        .fixedSize()
        .accessibilityElement(children: .contain)
        .accessibilityLabel("Star view")
    }

    /// One segment: a `Pill.segmentHeight` capsule centred in a `Layout.touchTarget` high hit
    /// area, so the whole height of the pill answers the tap.
    ///
    /// `event` is the interaction logged when the segment switches the mode — Android's names:
    /// `spacetime` on the way in; the way back to Starlight logs nothing there either.
    private func segment(
        _ title: String, selected: Bool, event: String?, action: @escaping () -> Void
    ) -> some View {
        Button {
            guard !selected else { return }
            if let analyticsSampleId, let event { DemoAnalytics.shared.interaction(analyticsSampleId, event) }
            action()
        } label: {
            Text(title)
                .font(SceneViewTokens.TypeScale.chromeCaption.weight(.semibold))
                .foregroundStyle(selected ? Pill.onSelected : Pill.onContainer)
                .padding(.horizontal, SceneViewTokens.Space.md)
                .frame(height: Pill.segmentHeight)
                .background(Capsule().fill(selected ? Pill.selectedContainer : .clear))
                .frame(minHeight: SceneViewTokens.Layout.touchTarget)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

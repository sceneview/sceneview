import Foundation
import simd

// The dense half of a `.svscan` v2 (Rerun v2, tier `lidar`): ARKit's LiDAR scene depth
// back-projected into world space, fused into 2 cm surfels, and stored as the
// `dense/points.bin` blob (`RerunSVPC`) of the media archive.
//
// A line-for-line port of the Android demo's `DenseCloud.kt` (`SvpcCodec`,
// `DepthBackProjection`, `DenseFusion`, `DenseSurfels`), so both apps write the same bytes for
// the same cloud and read each other's files. Pure Swift, no ARKit and no RealityKit: the live
// view hands it copies of the depth, confidence and camera colours, off the main actor.

/// A dense coloured cloud: world-space `positions` in metres, `colors` `0xFFRRGGBB` (`0` = none),
/// unit `normals` (or `nil`), `confidences` 0–255 (or `nil`), one per point.
struct RerunDenseCloud: Sendable, Equatable {
    var positions: [SIMD3<Float>]
    var colors: [UInt32]
    var normals: [SIMD3<Float>]?
    var confidences: [UInt8]?

    init(positions: [SIMD3<Float>], colors: [UInt32], normals: [SIMD3<Float>]? = nil, confidences: [UInt8]? = nil) {
        precondition(positions.count == colors.count, "\(positions.count) positions for \(colors.count) colours")
        precondition(normals == nil || normals?.count == colors.count, "one normal per point")
        precondition(confidences == nil || confidences?.count == colors.count, "one confidence per point")
        self.positions = positions
        self.colors = colors
        self.normals = normals
        self.confidences = confidences
    }

    static let empty = RerunDenseCloud(positions: [], colors: [])

    /// 500 k surfels: a room, 6 MB of SVPC (Android's `DenseFusion.MAX_POINTS`).
    static let maxPoints = 500_000

    var count: Int { colors.count }

    /// `[minX, minY, minZ, maxX, maxY, maxZ]`, all zero for an empty cloud.
    func bounds() -> [Float] {
        guard !positions.isEmpty else { return [Float](repeating: 0, count: 6) }
        var b: [Float] = [.greatestFiniteMagnitude, .greatestFiniteMagnitude, .greatestFiniteMagnitude,
                          -.greatestFiniteMagnitude, -.greatestFiniteMagnitude, -.greatestFiniteMagnitude]
        for p in positions {
            for a in 0..<3 {
                let v = p[a]
                if v < b[a] { b[a] = v }
                if v > b[a + 3] { b[a + 3] = v }
            }
        }
        return b
    }
}

/// The `dense/points.bin` blob, SVPC version 1 — Android's `SvpcCodec`, byte for byte.
/// Little-endian, a 32-byte header then one section per attribute (structure of arrays):
///
/// ```
/// 0  "SVPC"            magic
/// 4  u32 version       1
/// 8  u32 count         points
/// 12 u32 flags         bit 0 normals, bit 1 confidence; other bits reserved (0)
/// 16 f32 × 3 origin    metres
/// 28 f32 scale         metres per unit, 0.001 unless the cloud is wider than ±32.767 m
/// 32 i16 × 3 × count   position = origin + q · scale
///    u8 × 3 × count    colour r, g, b (sRGB)
///    u8 × count        confidence 0–255             (flag bit 1)
///    i8 × 2 × count    octahedral unit normal ×127  (flag bit 0)
/// ```
///
/// Encoder rules, so both writers give the same bytes: origin is the centre of the cloud's
/// bounds; scale is a millimetre, or `half extent / 32767` for a wider cloud; a value is rounded
/// half away from zero (`floor(v + 0.5)`), then clamped to ±32767; a normal maps onto the
/// octahedron (lower half folded over), each coordinate `×127`, rounded the same way. A reader
/// skips a blob whose magic or version it does not know.
enum RerunSVPC {
    /// `"SVPC"` read as a little-endian u32.
    static let magic: UInt32 = 0x4350_5653
    static let version: UInt32 = 1
    static let headerBytes = 32
    static let flagNormals: UInt32 = 1
    static let flagConfidence: UInt32 = 2
    /// A millimetre: the step of every cloud that fits ±32.767 m around its centre.
    static let scaleM: Float = 0.001
    static let qMax = 32_767
    private static let octMax = 127

    /// Bytes of a blob of `count` points with the sections `flags` names.
    static func size(count: Int, flags: UInt32) -> Int {
        var perPoint = 6 + 3
        if flags & flagConfidence != 0 { perPoint += 1 }
        if flags & flagNormals != 0 { perPoint += 2 }
        return headerBytes + perPoint * count
    }

    /// `cloud` as an SVPC blob. The same cloud always gives the same bytes, on both platforms.
    static func encode(_ cloud: RerunDenseCloud) -> Data {
        let n = cloud.count
        let b = cloud.bounds()
        let origin: [Float] = [(b[0] + b[3]) * 0.5, (b[1] + b[4]) * 0.5, (b[2] + b[5]) * 0.5]
        let half = max(b[3] - origin[0], b[4] - origin[1], b[5] - origin[2], 0)
        let scale: Float = half <= Float(qMax) * scaleM ? scaleM : half / Float(qMax)
        var flags: UInt32 = 0
        if cloud.normals != nil { flags |= flagNormals }
        if cloud.confidences != nil { flags |= flagConfidence }
        var out = [UInt8]()
        out.reserveCapacity(size(count: n, flags: flags))
        func u32(_ v: UInt32) { withUnsafeBytes(of: v.littleEndian) { out.append(contentsOf: $0) } }
        u32(magic); u32(version); u32(UInt32(n)); u32(flags)
        for v in [origin[0], origin[1], origin[2], scale] { u32(v.bitPattern) }
        for p in cloud.positions {
            for a in 0..<3 {
                let q = UInt16(bitPattern: Int16(quantize((p[a] - origin[a]) / scale, qMax)))
                out.append(UInt8(q & 0xFF))
                out.append(UInt8(q >> 8))
            }
        }
        for c in cloud.colors {
            out.append(UInt8(truncatingIfNeeded: c >> 16))
            out.append(UInt8(truncatingIfNeeded: c >> 8))
            out.append(UInt8(truncatingIfNeeded: c))
        }
        if let confidences = cloud.confidences { out.append(contentsOf: confidences) }
        if let normals = cloud.normals {
            for normal in normals {
                let (u, v) = octEncode(normal)
                out.append(UInt8(bitPattern: u))
                out.append(UInt8(bitPattern: v))
            }
        }
        return Data(out)
    }

    /// The cloud of the SVPC blob `data`, `nil` when it is not one this reader knows.
    static func decode(_ data: Data) -> RerunDenseCloud? {
        let length = data.count
        guard length >= headerBytes else { return nil }
        return data.withUnsafeBytes { (raw: UnsafeRawBufferPointer) -> RerunDenseCloud? in
            func u32(_ at: Int) -> UInt32 { UInt32(littleEndian: raw.loadUnaligned(fromByteOffset: at, as: UInt32.self)) }
            guard u32(0) == magic, u32(4) == version else { return nil }
            let signedCount = Int32(bitPattern: u32(8))
            let flags = u32(12) & (flagNormals | flagConfidence)
            guard signedCount >= 0, Int(signedCount) <= (length - headerBytes) / 9 else { return nil }
            let n = Int(signedCount)
            guard length >= size(count: n, flags: flags) else { return nil }
            let origin = SIMD3(Float(bitPattern: u32(16)), Float(bitPattern: u32(20)), Float(bitPattern: u32(24)))
            let scale = Float(bitPattern: u32(28))
            guard scale > 0, origin.x.isFinite, origin.y.isFinite, origin.z.isFinite else { return nil }
            var at = headerBytes
            var positions = [SIMD3<Float>]()
            positions.reserveCapacity(n)
            for _ in 0..<n {
                var p = SIMD3<Float>()
                for a in 0..<3 {
                    let q = Int16(littleEndian: raw.loadUnaligned(fromByteOffset: at, as: Int16.self))
                    p[a] = origin[a] + Float(q) * scale
                    at += 2
                }
                positions.append(p)
            }
            var colors = [UInt32]()
            colors.reserveCapacity(n)
            for _ in 0..<n {
                colors.append(0xFF00_0000 | UInt32(raw[at]) << 16 | UInt32(raw[at + 1]) << 8 | UInt32(raw[at + 2]))
                at += 3
            }
            var confidences: [UInt8]?
            if flags & flagConfidence != 0 {
                confidences = Array(raw[at..<(at + n)])
                at += n
            }
            var normals: [SIMD3<Float>]?
            if flags & flagNormals != 0 {
                var out = [SIMD3<Float>]()
                out.reserveCapacity(n)
                for _ in 0..<n {
                    out.append(octDecode(Int8(bitPattern: raw[at]), Int8(bitPattern: raw[at + 1])))
                    at += 2
                }
                normals = out
            }
            return RerunDenseCloud(positions: positions, colors: colors, normals: normals, confidences: confidences)
        }
    }

    /// Round half away from zero, then clamp to ±`limit` — Android's `SvpcCodec.quantize`.
    static func quantize(_ value: Float, _ limit: Int) -> Int {
        if value.isNaN { return 0 }
        let r: Float = value >= 0 ? (value + 0.5).rounded(.down) : -((-value + 0.5).rounded(.down))
        return Int(min(max(r, -Float(limit)), Float(limit)))
    }

    /// The unit normal `n` octahedron-mapped to two snorm8 values. A zero vector encodes as
    /// (0, 0), which decodes to +Z.
    static func octEncode(_ n: SIMD3<Float>) -> (Int8, Int8) {
        let l1 = abs(n.x) + abs(n.y) + abs(n.z)
        guard l1 > 0, l1.isFinite else { return (0, 0) }
        var u = n.x / l1
        var v = n.y / l1
        if n.z < 0 {
            let pu = u
            u = (1 - abs(v)) * signNotZero(pu)
            v = (1 - abs(pu)) * signNotZero(v)
        }
        let qu = quantize(min(max(u, -1), 1) * Float(octMax), octMax)
        let qv = quantize(min(max(v, -1), 1) * Float(octMax), octMax)
        return (Int8(qu), Int8(qv))
    }

    /// The unit normal of the snorm8 pair (`u`, `v`).
    static func octDecode(_ u: Int8, _ v: Int8) -> SIMD3<Float> {
        var x = Float(u) / Float(octMax)
        var y = Float(v) / Float(octMax)
        let z = 1 - abs(x) - abs(y)
        if z < 0 {
            let px = x
            x = (1 - abs(y)) * signNotZero(px)
            y = (1 - abs(px)) * signNotZero(y)
        }
        let l = (x * x + y * y + z * z).squareRoot()
        return SIMD3(x / l, y / l, z / l)
    }

    private static func signNotZero(_ v: Float) -> Float { v >= 0 ? 1 : -1 }
}

// MARK: - Back-projection

/// One depth keyframe copied out of ARKit: `depthM` and `confidence` row-major `width` ×
/// `height` (metres, 0–255), `colors` the camera image's colour at each depth pixel
/// (`0xFFRRGGBB`, `0` = none), the lens at the depth map's resolution, and the
/// `cameraToWorld` of the sensor that took it (ARKit's `ARCamera.transform`, not the display's).
struct RerunDepthFrame: Sendable {
    var width: Int
    var height: Int
    var depthM: [Float]
    var confidence: [UInt8]?
    var colors: [UInt32]
    var fx: Float
    var fy: Float
    var cx: Float
    var cy: Float
    var cameraToWorld: simd_float4x4
}

/// Surfels of one `RerunDepthFrame`: world positions, unit world normals, colours, confidences.
struct RerunDenseSamples: Sendable {
    var positions: [SIMD3<Float>] = []
    var normals: [SIMD3<Float>] = []
    var colors: [UInt32] = []
    var confidences: [UInt8] = []
    var count: Int { positions.count }
}

/// Depth → world-space surfels with normals: Android's `DepthBackProjection`.
enum RerunDepthBackProjection {
    /// Confidence past which a pixel is kept: Android's raw-depth threshold. ARKit's
    /// `ARConfidenceLevel` maps low → 0, medium → 128, high → 255, so medium and high pass.
    static let minConfidence = 128
    static let nearM: Float = 0.2
    static let farM: Float = 5
    /// Neighbours further apart in depth than this share of it lie on another surface.
    static let edgeFraction: Float = 0.05
    /// A surface seen more edge-on than this (cosine to the view ray) is too noisy to keep.
    static let minViewCosine: Float = 0.15

    /// ARKit's `ARConfidenceLevel` raw value (0, 1, 2) on Android's 0–255 scale.
    static func confidence(arkitLevel level: UInt8) -> UInt8 {
        switch level {
        case 0: 0
        case 1: 128
        default: 255
        }
    }

    /// The camera-space point of depth pixel (`px`, `py`) at `depth` metres: +X right, +Y up,
    /// -Z forward — ARKit's camera space and ARCore's alike.
    static func cameraPoint(_ px: Int, _ py: Int, _ depth: Float, _ f: RerunDepthFrame) -> SIMD3<Float> {
        SIMD3((Float(px) - f.cx) * depth / f.fx, -(Float(py) - f.cy) * depth / f.fy, -depth)
    }

    /// The surfels of `frame`: every pixel with depth in [`nearM`, `farM`], confidence ≥
    /// `minConfidence` and a normal — from its neighbours, none across a depth jump
    /// (`edgeFraction`) — facing the camera by at least `minViewCosine`.
    static func project(_ frame: RerunDepthFrame, minConfidence: Int = minConfidence) -> RerunDenseSamples {
        let w = frame.width, h = frame.height
        guard w > 0, h > 0, frame.depthM.count >= w * h, frame.colors.count >= w * h,
              frame.fx > 0, frame.fy > 0 else { return RerunDenseSamples() }
        let depth = frame.depthM.map { $0 >= nearM && $0 <= farM ? $0 : 0 }
        var out = RerunDenseSamples()
        let m = frame.cameraToWorld
        let rotation = simd_float3x3(SIMD3(m.columns.0.x, m.columns.0.y, m.columns.0.z),
                                     SIMD3(m.columns.1.x, m.columns.1.y, m.columns.1.z),
                                     SIMD3(m.columns.2.x, m.columns.2.y, m.columns.2.z))
        let translation = SIMD3(m.columns.3.x, m.columns.3.y, m.columns.3.z)
        func neighbour(_ x: Int, _ y: Int, _ d: Float, _ edge: Float, _ inside: Bool) -> Float {
            guard inside else { return 0 }
            let v = depth[y * w + x]
            return v != 0 && abs(v - d) <= edge ? v : 0
        }
        for y in 0..<h {
            for x in 0..<w {
                let i = y * w + x
                let d = depth[i]
                if d == 0 { continue }
                let confidence = frame.confidence.map { Int($0[i]) } ?? 255
                if confidence < minConfidence { continue }
                let edge = edgeFraction * d
                let left = neighbour(x - 1, y, d, edge, x > 0)
                let right = neighbour(x + 1, y, d, edge, x < w - 1)
                let up = neighbour(x, y - 1, d, edge, y > 0)
                let down = neighbour(x, y + 1, d, edge, y < h - 1)
                if (left == 0 && right == 0) || (up == 0 && down == 0) { continue } // no normal
                let p = cameraPoint(x, y, d, frame)
                let px0 = left != 0 ? cameraPoint(x - 1, y, left, frame) : p
                let px1 = right != 0 ? cameraPoint(x + 1, y, right, frame) : p
                let py0 = up != 0 ? cameraPoint(x, y - 1, up, frame) : p
                let py1 = down != 0 ? cameraPoint(x, y + 1, down, frame) : p
                var normal = simd_cross(px1 - px0, py1 - py0)
                let length = simd_length(normal)
                guard length > 0 else { continue }
                normal *= 1 / length
                // Towards the camera, which sits at the camera-space origin.
                let toCamera = p * (-1 / simd_length(p))
                var cosine = simd_dot(normal, toCamera)
                if cosine < 0 {
                    normal *= -1
                    cosine = -cosine
                }
                if cosine < minViewCosine { continue }
                out.positions.append(rotation * p + translation)
                out.normals.append(rotation * normal)
                out.colors.append(frame.colors[i])
                out.confidences.append(UInt8(confidence))
            }
        }
        return out
    }
}

// MARK: - Fusion

/// What one `RerunDenseFusion.add` did: voxels created, samples merged, voxels in all.
struct RerunDenseFuseStats: Sendable, Equatable {
    var added: Int
    var kept: Int
    var total: Int
}

/// The dense map: samples merged into `voxelM` voxels by a primitive open-addressing hash —
/// Android's `DenseFusion`, same key, same hash, same arithmetic, so the same samples give the
/// same cloud in the same order. A voxel seen again averages its position, colour and normal
/// (up to `maxWeight` views) and keeps its best confidence. Capped at `maxPoints` voxels, kept
/// in insertion order. One owner at a time, off the main actor.
final class RerunDenseFusion {
    /// The design's surfel size: 2 cm (Android's `DenseFusion.VOXEL_M`).
    static let voxelM: Float = 0.02
    static let maxWeight = 32
    private static let empty: Int64 = -1

    let voxelM: Float
    let maxPoints: Int
    private(set) var count = 0

    private var keys = [Int64](repeating: RerunDenseFusion.empty, count: 1 << 14)
    private var slots = [Int32](repeating: 0, count: 1 << 14)
    private var px: [Float] = [], py: [Float] = [], pz: [Float] = []
    private var nx: [Float] = [], ny: [Float] = [], nz: [Float] = []
    private var r: [Float] = [], g: [Float] = [], b: [Float] = []
    private var weight: [Int] = [], colorWeight: [Int] = []
    private var confidence: [UInt8] = []

    init(voxelM: Float = RerunDenseFusion.voxelM, maxPoints: Int = RerunDenseCloud.maxPoints) {
        self.voxelM = voxelM
        self.maxPoints = maxPoints
    }

    var isFull: Bool { count >= maxPoints }

    /// Merges `samples` into the map.
    @discardableResult
    func add(_ samples: RerunDenseSamples) -> RerunDenseFuseStats {
        var added = 0
        var kept = 0
        for i in 0..<samples.count {
            let p = samples.positions[i]
            guard p.x.isFinite, p.y.isFinite, p.z.isFinite else { continue }
            let key = Self.voxelKey(p, size: voxelM)
            var index = find(key)
            if index < 0 {
                if count >= maxPoints { continue }
                index = insert(key)
                added += 1
            }
            merge(index, p, samples, i)
            kept += 1
        }
        return RerunDenseFuseStats(added: added, kept: kept, total: count)
    }

    /// The map's first `limit` voxels as a cloud: averaged positions, colours, unit normals.
    func cloud(limit: Int? = nil) -> RerunDenseCloud {
        let n = min(max(limit ?? count, 0), count)
        var positions = [SIMD3<Float>](), normals = [SIMD3<Float>](), colors = [UInt32]()
        positions.reserveCapacity(n); normals.reserveCapacity(n); colors.reserveCapacity(n)
        for i in 0..<n {
            positions.append(SIMD3(px[i], py[i], pz[i]))
            let l = (nx[i] * nx[i] + ny[i] * ny[i] + nz[i] * nz[i]).squareRoot()
            // Opposite views cancelled out: face up.
            normals.append(l > 1e-6 ? SIMD3(nx[i] / l, ny[i] / l, nz[i] / l) : SIMD3(0, 1, 0))
            colors.append(colorWeight[i] == 0 ? 0
                : 0xFF00_0000 | Self.channel(r[i]) << 16 | Self.channel(g[i]) << 8 | Self.channel(b[i]))
        }
        return RerunDenseCloud(positions: positions, colors: colors, normals: normals, confidences: Array(confidence[0..<n]))
    }

    private func merge(_ index: Int, _ p: SIMD3<Float>, _ samples: RerunDenseSamples, _ i: Int) {
        let w = min(weight[index] + 1, Self.maxWeight)
        weight[index] = w
        let k = 1 / Float(w)
        px[index] += (p.x - px[index]) * k
        py[index] += (p.y - py[index]) * k
        pz[index] += (p.z - pz[index]) * k
        let n = samples.normals[i]
        nx[index] += n.x
        ny[index] += n.y
        nz[index] += n.z
        // Keep the summed normal bounded so a long-seen voxel still turns with new views.
        let l = abs(nx[index]) + abs(ny[index]) + abs(nz[index])
        if l > Float(Self.maxWeight) {
            let s = Float(Self.maxWeight) / l
            nx[index] *= s
            ny[index] *= s
            nz[index] *= s
        }
        let c = samples.colors[i]
        if c != 0 {
            let cw = min(colorWeight[index] + 1, Self.maxWeight)
            colorWeight[index] = cw
            let ck = 1 / Float(cw)
            r[index] += (Float((c >> 16) & 0xFF) - r[index]) * ck
            g[index] += (Float((c >> 8) & 0xFF) - g[index]) * ck
            b[index] += (Float(c & 0xFF) - b[index]) * ck
        }
        if samples.confidences[i] > confidence[index] { confidence[index] = samples.confidences[i] }
    }

    private func find(_ key: Int64) -> Int {
        let mask = keys.count - 1
        var slot = Self.mix(key) & mask
        while true {
            let k = keys[slot]
            if k == Self.empty { return -1 }
            if k == key { return Int(slots[slot]) }
            slot = (slot + 1) & mask
        }
    }

    private func insert(_ key: Int64) -> Int {
        if (count + 1) * 2 > keys.count { rehash(keys.count * 2) }
        let mask = keys.count - 1
        var slot = Self.mix(key) & mask
        while keys[slot] != Self.empty { slot = (slot + 1) & mask }
        keys[slot] = key
        slots[slot] = Int32(count)
        px.append(0); py.append(0); pz.append(0)
        nx.append(0); ny.append(0); nz.append(0)
        r.append(0); g.append(0); b.append(0)
        weight.append(0); colorWeight.append(0); confidence.append(0)
        count += 1
        return count - 1
    }

    private func rehash(_ size: Int) {
        let oldKeys = keys, oldSlots = slots
        keys = [Int64](repeating: Self.empty, count: size)
        slots = [Int32](repeating: 0, count: size)
        let mask = size - 1
        for s in oldKeys.indices where oldKeys[s] != Self.empty {
            var slot = Self.mix(oldKeys[s]) & mask
            while keys[slot] != Self.empty { slot = (slot + 1) & mask }
            keys[slot] = oldKeys[s]
            slots[slot] = oldSlots[s]
        }
    }

    /// The voxel of `p` at `size`: 21 bits per axis, Android's `DenseFusion.voxelKey`.
    static func voxelKey(_ p: SIMD3<Float>, size: Float) -> Int64 {
        func cell(_ v: Float) -> Int64 {
            // Kotlin's `Float.toLong()` saturates where Swift's conversion would trap.
            let f = (v / size).rounded(.down)
            return Int64(min(max(f, -9.2e18), 9.2e18)) & 0x1F_FFFF
        }
        return (cell(p.x) << 42) | (cell(p.y) << 21) | cell(p.z)
    }

    private static func mix(_ key: Int64) -> Int {
        var h = key &* -0x61C8_8646_80B5_83EB
        h ^= Int64(bitPattern: UInt64(bitPattern: h) >> 29)
        return Int(Int32(truncatingIfNeeded: h)) & Int(Int32.max)
    }

    private static func channel(_ v: Float) -> UInt32 { UInt32(min(max(Int(v + 0.5), 0), 255)) }
}

// MARK: - Replay surfels

/// The replay's dense layer without a custom shader — Android's `DenseSurfels`: each surfel a
/// square quad pre-expanded on the CPU, lying in the plane its normal gives, its colour one
/// texel of the point atlas read through the unlit material.
enum RerunDenseSurfels {
    /// Half the side of a surfel's square, in voxels: 1.3 voxels wide, so neighbours overlap a
    /// little and a wall reads as a surface, not a grid of tiles.
    static let halfSideVoxels: Float = 0.65

    /// Two triangles and four vertices per surfel of `range` (all by default), in order — the
    /// `k`-th surfel of the range at indices `6k ..< 6k + 6`, textured from texel `i` of the atlas.
    static func mesh(_ cloud: RerunDenseCloud, range: Range<Int>? = nil, voxelM: Float, atlasSize: Int) -> RerunMesh {
        let all = 0..<min(cloud.count, atlasSize * atlasSize)
        let range = (range ?? all).clamped(to: all)
        var mesh = RerunMesh()
        mesh.positions.reserveCapacity(range.count * 4)
        mesh.uvs.reserveCapacity(range.count * 4)
        mesh.indices.reserveCapacity(range.count * 6)
        let half = voxelM * halfSideVoxels
        for i in range {
            let p = cloud.positions[i]
            let normal = cloud.normals?[i] ?? SIMD3(0, 1, 0)
            // Two tangents: any vector not parallel to the normal, crossed twice.
            let axis: SIMD3<Float> = abs(normal.y) < 0.9 ? SIMD3(0, 1, 0) : SIMD3(1, 0, 0)
            var t = simd_cross(axis, normal)
            let tl = simd_length(t)
            t = t / (tl > 1e-6 ? tl : 1) * half
            let bt = simd_cross(normal, t)
            let uv = RerunPointAtlas.uv(of: i, size: atlasSize)
            let a = mesh.vertex(p - t - bt, uv: uv)
            let b = mesh.vertex(p + t - bt, uv: uv)
            let c = mesh.vertex(p + t + bt, uv: uv)
            let d = mesh.vertex(p - t + bt, uv: uv)
            mesh.triangle(a, b, c)
            mesh.triangle(a, c, d)
        }
        return mesh
    }
}

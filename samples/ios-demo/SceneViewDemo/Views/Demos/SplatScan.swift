import Compression
import Foundation
import simd

/// A phone capture read from a Niantic **SPZ** file, reduced to what the iOS demo draws:
/// where each captured point is, its colour, its size and its opacity.
///
/// The Swift twin of the decode half of `sceneview-core`'s `SpzParser.kt` (same formulas, from
/// the reference `load-spz.cc`, MIT). It reads the legacy gzip container, versions 2 and 3 —
/// what Scaniverse, Polycam and the SPZ samples export, and what the bundled
/// `raccoon_family.spz` is. The version 4 container (ZSTD streams) is refused with a readable
/// error: iOS has no ZSTD decoder in the system, and the demo only opens its own bundled file.
///
/// Rotations and spherical-harmonics bands are skipped, not decoded: iOS draws each point as a
/// small solid dot (``SplatPointCloud``), so an orientation would have nothing to act on.
struct SplatScan: Equatable, Sendable {
    /// Points in the file.
    let count: Int
    /// Positions in the file's frame, metres, right-handed Y-up (the SPZ default, RUB) —
    /// the same frame RealityKit uses, so no axis is flipped.
    let positions: [SIMD3<Float>]
    /// Colour of each point, `0xAARRGGBB` with the file's opacity in the alpha byte — the
    /// packing ``RerunPointAtlas`` already reads.
    let colors: [UInt32]
    /// Largest axis of each point's gaussian, metres (`exp(byte / 16 - 10)`).
    let sizes: [Float]
}

enum SplatScanError: Error, Equatable, CustomStringConvertible {
    case notGzip
    case truncated(String)
    case badMagic
    case unsupportedVersion(Int)
    case invalidPointCount(Int)

    var description: String {
        switch self {
        case .notGzip: return "Not an SPZ file this viewer can open (expected a gzip stream)."
        case .truncated(let what): return "The SPZ file is truncated (\(what))."
        case .badMagic: return "The SPZ header is not valid."
        case .unsupportedVersion(let v): return "SPZ version \(v) is not supported (2 and 3 are)."
        case .invalidPointCount(let n): return "The SPZ header claims \(n) points."
        }
    }
}

enum SPZReader {
    /// `NGSP`, little-endian.
    static let magic: UInt32 = 0x5053_474E
    static let headerSize = 16
    /// `colorScale` of the reference encoder: a stored byte `b` is the SH DC term
    /// `(b / 255 - 0.5) / 0.15`.
    static let colorScale: Float = 0.15
    /// Band-0 spherical-harmonics constant: linear colour is `0.5 + SH_C0 * dc`.
    static let shC0: Float = 0.282_094_79

    static func read(_ file: Data) throws -> SplatScan {
        try decode(gunzip(file))
    }

    /// Decodes the uncompressed legacy stream: the 16-byte header, then the attribute arrays
    /// back to back, structure-of-arrays.
    static func decode(_ d: Data) throws -> SplatScan {
        let bytes = [UInt8](d)
        guard bytes.count >= headerSize else { throw SplatScanError.truncated("header") }
        guard le32(bytes, 0) == magic else { throw SplatScanError.badMagic }
        let version = Int(le32(bytes, 4))
        let signedCount = Int(Int32(bitPattern: le32(bytes, 8)))
        let shDegree = Int(bytes[12])
        let fractionalBits = Int(bytes[13])
        guard version == 2 || version == 3 else { throw SplatScanError.unsupportedVersion(version) }
        guard signedCount > 0 else { throw SplatScanError.invalidPointCount(signedCount) }
        guard shDegree <= 3 else { throw SplatScanError.unsupportedVersion(version) }
        let n = signedCount

        let rotationStride = version >= 3 ? 4 : 3
        let shDim = [0, 3, 8, 15][shDegree]
        let positionsAt = headerSize
        let alphasAt = positionsAt + n * 9
        let colorsAt = alphasAt + n
        let scalesAt = colorsAt + n * 3
        let end = scalesAt + n * 3 + n * rotationStride + n * shDim * 3
        guard end <= bytes.count else {
            throw SplatScanError.truncated("need \(end) bytes, have \(bytes.count)")
        }

        let positionScale = 1 / Float(1 << fractionalBits)
        var positions = [SIMD3<Float>](repeating: .zero, count: n)
        var colors = [UInt32](repeating: 0, count: n)
        var sizes = [Float](repeating: 0, count: n)
        for i in 0..<n {
            var p = SIMD3<Float>()
            for axis in 0..<3 {
                let b = positionsAt + i * 9 + axis * 3
                var fixed = Int32(bytes[b]) | Int32(bytes[b + 1]) << 8 | Int32(bytes[b + 2]) << 16
                if fixed & 0x80_0000 != 0 { fixed |= ~0xFF_FFFF } // sign-extend bit 23
                p[axis] = Float(fixed) * positionScale
            }
            positions[i] = p

            var argb = UInt32(bytes[alphasAt + i]) << 24
            for channel in 0..<3 {
                let dc = (Float(bytes[colorsAt + i * 3 + channel]) / 255 - 0.5) / colorScale
                let linear = min(max(0.5 + shC0 * dc, 0), 1)
                argb |= UInt32((linear * 255).rounded()) << UInt32(16 - channel * 8)
            }
            colors[i] = argb

            let largest = max(bytes[scalesAt + i * 3], bytes[scalesAt + i * 3 + 1], bytes[scalesAt + i * 3 + 2])
            sizes[i] = exp(Float(largest) / 16 - 10)
        }
        return SplatScan(count: n, positions: positions, colors: colors, sizes: sizes)
    }

    /// RFC 1952: skips the gzip header, inflates the raw DEFLATE body with the system's
    /// `COMPRESSION_ZLIB` (which is raw DEFLATE), sized by the trailer's `ISIZE`.
    static func gunzip(_ file: Data) throws -> Data {
        let src = [UInt8](file)
        guard src.count >= 18, src[0] == 0x1F, src[1] == 0x8B, src[2] == 8 else { throw SplatScanError.notGzip }
        let flags = src[3]
        var at = 10
        if flags & 0x04 != 0 { // FEXTRA
            guard at + 2 <= src.count else { throw SplatScanError.truncated("gzip extra") }
            at += 2 + Int(src[at]) | Int(src[at + 1]) << 8
        }
        if flags & 0x08 != 0 { at = try skipZeroTerminated(src, from: at) } // FNAME
        if flags & 0x10 != 0 { at = try skipZeroTerminated(src, from: at) } // FCOMMENT
        if flags & 0x02 != 0 { at += 2 } // FHCRC
        guard at < src.count - 8 else { throw SplatScanError.truncated("gzip body") }
        let size = Int(le32(src, src.count - 4))
        guard size > 0 else { throw SplatScanError.truncated("empty gzip stream") }

        var out = [UInt8](repeating: 0, count: size)
        let written = src.withUnsafeBufferPointer { input in
            out.withUnsafeMutableBufferPointer { output in
                compression_decode_buffer(output.baseAddress!, size,
                                          input.baseAddress! + at, src.count - 8 - at,
                                          nil, COMPRESSION_ZLIB)
            }
        }
        guard written == size else { throw SplatScanError.truncated("inflated \(written) of \(size) bytes") }
        return Data(out)
    }

    private static func skipZeroTerminated(_ b: [UInt8], from start: Int) throws -> Int {
        var at = start
        while at < b.count, b[at] != 0 { at += 1 }
        guard at < b.count else { throw SplatScanError.truncated("gzip header") }
        return at + 1
    }

    private static func le32(_ b: [UInt8], _ at: Int) -> UInt32 {
        UInt32(b[at]) | UInt32(b[at + 1]) << 8 | UInt32(b[at + 2]) << 16 | UInt32(b[at + 3]) << 24
    }
}

// MARK: - Framing

/// Orbit home for a capture, from the cloud itself — Android's `scanFraming`: the target is the
/// centroid, and the shot contains the **median half** of the points so the fringe a real
/// capture fades into (grass, ground) spills off the edges instead of pushing the subject away.
struct SplatFraming: Equatable, Sendable {
    let centroid: SIMD3<Float>
    /// Distance from the centroid within which half of the points lie.
    let subjectRadius: Float

    init(_ scan: SplatScan) {
        guard scan.count > 0 else {
            centroid = .zero
            subjectRadius = 1
            return
        }
        var sum = SIMD3<Double>()
        for p in scan.positions { sum += SIMD3<Double>(p) }
        let c = SIMD3<Float>(sum / Double(scan.count))
        var radii = scan.positions.map { simd_distance($0, c) }
        radii.sort()
        centroid = c
        subjectRadius = max(radii[(radii.count - 1) / 2], 1e-4)
    }

    /// Android's `HOME_TILT_RADIANS`: ~3.6° above the target, a slightly-above-eye-line look.
    static let homeTilt: Float = 0.06241
    /// Fraction of the frame's half-width the subject may fill — Android's `FRAME_FILL`.
    static let frameFill: Float = 0.95
    /// SceneViewSwift's orbit camera: 60° vertical field of view.
    static let verticalFOV: Float = .pi / 3

    /// Distance at which a sphere of `radius` fits a portrait viewport of `aspect` (width /
    /// height). Tangent fit — `radius / sin(halfAngle)` — so the silhouette never clips.
    static func distance(radius: Float, aspect: Float) -> Float {
        let tanHalfVertical = tan(verticalFOV / 2)
        let tanHalf = min(aspect, 1) * tanHalfVertical
        let sinHalf = tanHalf / (1 + tanHalf * tanHalf).squareRoot()
        return radius / (sinHalf * frameFill)
    }
}

// MARK: - Point cloud mesh

/// Draws a ``SplatScan`` as solid coloured points: each point is a tetrahedron (readable from
/// every angle) sized from its gaussian, coloured through a one-texel-per-point atlas. This is
/// what RealityKit can draw today — it has no splat primitive and SceneViewSwift has no
/// `SplatNode` yet (#2646) — so the scan reads as a dense point cloud rather than the soft,
/// camera-sorted splats Android renders.
///
/// Points are cut into chunks of ``RerunPointAtlas/capacity`` so each chunk carries its own
/// 128 × 128 atlas; the "Points drawn" control then toggles whole chunks and rebuilds only the
/// last, partial one.
enum SplatPointCloud {
    static var chunkSize: Int { RerunPointAtlas.capacity }

    /// Radius of a point's tetrahedron, in the file's metres: its gaussian's largest axis,
    /// kept within a range where a dot is neither invisible nor a blob.
    static func radius(forSize size: Float) -> Float {
        min(max(size * 2, 0.0015), 0.008)
    }

    static func chunkCount(_ scan: SplatScan) -> Int {
        (scan.count + chunkSize - 1) / chunkSize
    }

    /// Geometry of points `range` (at most one chunk), recentred on `centroid` and scaled by
    /// `scale`; `uv` addresses the chunk's own atlas.
    static func mesh(_ scan: SplatScan, range: Range<Int>, centroid: SIMD3<Float>, scale: Float) -> RerunMesh {
        var mesh = RerunMesh()
        mesh.positions.reserveCapacity(range.count * 4)
        mesh.uvs.reserveCapacity(range.count * 4)
        mesh.indices.reserveCapacity(range.count * 12)
        for (slot, i) in range.enumerated() {
            let p = (scan.positions[i] - centroid) * scale
            RerunGeometry.addTetra(&mesh, p, radius(forSize: scan.sizes[i]) * scale, uv: RerunPointAtlas.uv(of: slot))
        }
        return mesh
    }

    /// RGBA8 atlas of points `range`, opaque: a solid dot has no use for the file's opacity.
    static func atlasPixels(_ scan: SplatScan, range: Range<Int>) -> [UInt8] {
        let opaque = scan.colors[range].map { $0 | 0xFF00_0000 }
        return RerunPointAtlas.pixels(opaque[...], count: range.count)
    }
}

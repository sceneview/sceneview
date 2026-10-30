#if os(iOS) || os(macOS) || os(visionOS)
import RealityKit
import Foundation
import simd

/// A solid tube swept along a polyline — the iOS counterpart of Android's `TubeNode`.
///
/// Where ``LineNode`` and ``PathNode`` draw a path as a chain of separate thin
/// cylinders, a tube is one continuous mesh: the cross-section is carried along the
/// points with parallel-transport frames, so a curve sampled densely reads as a smooth
/// pipe with no gaps or overlaps at its joints, and a closed loop has no visible seam.
///
/// ```swift
/// let ring = (0..<64).map { i -> SIMD3<Float> in
///     let a = Float(i) / 64 * 2 * .pi
///     return [cos(a) * 0.3, 0, sin(a) * 0.3]
/// }
/// let tube = TubeNode(points: ring, radius: 0.01, closed: true,
///                     material: .pbr(color: .systemBlue, roughness: 0.3))
/// root.addChild(tube.entity)
///
/// // Later — same entity, new geometry (a growing trail, a live slider…):
/// tube.update(points: newPoints, radius: 0.015)
/// ```
///
/// Fewer than two distinct points produce no geometry: the entity stays in the scene
/// but is disabled until an ``update(points:radius:)`` gives it a drawable path.
public struct TubeNode: Sendable {
    /// The underlying RealityKit entity.
    public let entity: ModelEntity
    /// Vertices around the cross-section (at least 3).
    public let radialSegments: Int
    /// Whether the last point joins back to the first.
    public let closed: Bool
    /// Whether an open tube's two ends are closed with flat discs.
    public let caps: Bool
    private let material: GeometryMaterial

    /// Creates a tube through `points`.
    ///
    /// - Parameters:
    ///   - points: Centre-line points in local space, in order. Consecutive duplicates
    ///     are ignored; for a closed tube, a last point equal to the first is too.
    ///   - radius: Cross-section radius in meters. Default 0.02, as on Android.
    ///   - radialSegments: Vertices around the cross-section, clamped to at least 3.
    ///     Default 8, as on Android.
    ///   - closed: Joins the last point back to the first with a seamless ring.
    ///   - caps: Closes an open tube's ends with flat discs. Ignored when `closed`.
    ///   - material: Material for the whole tube.
    public init(
        points: [SIMD3<Float>],
        radius: Float = 0.02,
        radialSegments: Int = 8,
        closed: Bool = false,
        caps: Bool = true,
        material: GeometryMaterial = .simple(color: .white)
    ) {
        self.radialSegments = max(3, radialSegments)
        self.closed = closed
        self.caps = caps
        self.material = material
        self.entity = ModelEntity()
        update(points: points, radius: radius)
    }

    /// Rebuilds the tube's mesh in place for new `points` and `radius`.
    ///
    /// The entity, its transform, its children and its material are kept. With fewer
    /// than two distinct points the entity is disabled; the next drawable update
    /// enables it again.
    public func update(points: [SIMD3<Float>], radius: Float) {
        guard let geometry = TubeGeometry(points: points, radius: radius,
                                          radialSegments: radialSegments,
                                          closed: closed, caps: caps),
              let mesh = try? geometry.meshResource(name: "Tube") else {
            entity.isEnabled = false
            return
        }
        if entity.model == nil {
            entity.model = ModelComponent(mesh: mesh, materials: [material.rkMaterial])
        } else {
            entity.model?.mesh = mesh
        }
        entity.isEnabled = true
    }

    // MARK: - Transform helpers

    /// Returns self positioned at the given coordinates.
    @discardableResult
    public func position(_ position: SIMD3<Float>) -> TubeNode {
        entity.position = position
        return self
    }

    /// Returns self with the given orientation.
    @discardableResult
    public func rotation(_ rotation: simd_quatf) -> TubeNode {
        entity.orientation = rotation
        return self
    }
}

// MARK: - Geometry

/// The vertex data of a tube, computed without RealityKit so it can be tested directly.
///
/// Layout: `rings × (radialSegments + 1)` side vertices — each ring repeats its first
/// vertex so the texture's `u` runs 0…1 without wrapping — then, for a capped open tube,
/// one flat disc per end (`radialSegments + 1` rim vertices and a centre). A closed tube
/// repeats its first ring at the end, so the seam ring shares positions but carries
/// `v = 1`.
struct TubeGeometry: Equatable {
    var positions: [SIMD3<Float>] = []
    var normals: [SIMD3<Float>] = []
    var uvs: [SIMD2<Float>] = []
    var indices: [UInt32] = []

    /// `nil` when the path has fewer than two distinct points or `radius` is not positive.
    init?(points: [SIMD3<Float>], radius: Float, radialSegments: Int, closed: Bool, caps: Bool) {
        let segments = max(3, radialSegments)
        var path = Self.deduplicated(points)
        if closed, path.count >= 2, simd_distance(path[0], path[path.count - 1]) < Self.epsilon {
            path.removeLast()
        }
        // Decided after the repeated end point is gone: [A, B, A] is a line, not a loop.
        let isClosed = closed && path.count >= 3
        guard path.count >= 2, radius > 0, radius.isFinite else { return nil }

        let tangents = Self.tangents(path, closed: isClosed)
        let frameNormals = Self.transportedNormals(tangents, closed: isClosed)

        // Ring centres, tangents and normals; a closed tube repeats ring 0 at the end.
        var centres = path
        var ringTangents = tangents
        var ringNormals = frameNormals
        if isClosed {
            centres.append(path[0])
            ringTangents.append(tangents[0])
            ringNormals.append(frameNormals[0])
        }

        var lengths: [Float] = [0]
        for i in 1..<centres.count {
            lengths.append(lengths[i - 1] + simd_distance(centres[i - 1], centres[i]))
        }
        let total = max(lengths.last ?? 1, Self.epsilon)
        let stride = segments + 1

        for (i, centre) in centres.enumerated() {
            let t = ringTangents[i]
            let n = ringNormals[i]
            let b = simd_cross(t, n)
            for j in 0...segments {
                let theta = Float(j) / Float(segments) * 2 * .pi
                let direction = cos(theta) * n + sin(theta) * b
                positions.append(centre + direction * radius)
                normals.append(direction)
                uvs.append(SIMD2(Float(j) / Float(segments), lengths[i] / total))
            }
        }
        for i in 0..<(centres.count - 1) {
            for j in 0..<segments {
                let a = UInt32(i * stride + j)
                let b = a + 1
                let c = UInt32((i + 1) * stride + j + 1)
                let d = c - 1
                indices += [a, b, c, a, c, d]
            }
        }

        guard caps, !isClosed else { return }
        addCap(centre: path[0], tangent: tangents[0], normal: frameNormals[0],
               radius: radius, segments: segments, facingForward: false)
        addCap(centre: path[path.count - 1], tangent: tangents[path.count - 1],
               normal: frameNormals[path.count - 1], radius: radius, segments: segments,
               facingForward: true)
    }

    /// Vertex count of a tube through `pointCount` distinct points.
    static func vertexCount(pointCount: Int, radialSegments: Int, closed: Bool, caps: Bool) -> Int {
        let segments = max(3, radialSegments)
        let rings = closed ? pointCount + 1 : pointCount
        let capVertices = (caps && !closed) ? 2 * (segments + 2) : 0
        return rings * (segments + 1) + capVertices
    }

    /// Triangle count of a tube through `pointCount` distinct points.
    static func triangleCount(pointCount: Int, radialSegments: Int, closed: Bool, caps: Bool) -> Int {
        let segments = max(3, radialSegments)
        let spans = closed ? pointCount : pointCount - 1
        return spans * segments * 2 + ((caps && !closed) ? 2 * segments : 0)
    }

    /// A RealityKit mesh of this geometry.
    func meshResource(name: String) throws -> MeshResource {
        var descriptor = MeshDescriptor(name: name)
        descriptor.positions = MeshBuffers.Positions(positions)
        descriptor.normals = MeshBuffers.Normals(normals)
        descriptor.textureCoordinates = MeshBuffers.TextureCoordinates(uvs)
        descriptor.primitives = .triangles(indices)
        return try MeshResource.generate(from: [descriptor])
    }

    // MARK: Frames

    static let epsilon: Float = 1e-6

    static func deduplicated(_ points: [SIMD3<Float>]) -> [SIMD3<Float>] {
        var result: [SIMD3<Float>] = []
        result.reserveCapacity(points.count)
        for point in points where point.x.isFinite && point.y.isFinite && point.z.isFinite {
            if let last = result.last, simd_distance(last, point) < epsilon { continue }
            result.append(point)
        }
        return result
    }

    /// Unit tangent at each point: the bisector of the two adjacent segments, or the
    /// single segment at an open end.
    static func tangents(_ path: [SIMD3<Float>], closed: Bool) -> [SIMD3<Float>] {
        let n = path.count
        return (0..<n).map { i in
            let hasPrevious = closed || i > 0
            let hasNext = closed || i < n - 1
            var direction = SIMD3<Float>(repeating: 0)
            if hasNext { direction += simd_normalize(path[(i + 1) % n] - path[i]) }
            if hasPrevious { direction += simd_normalize(path[i] - path[(i - 1 + n) % n]) }
            if simd_length(direction) < epsilon {
                // A hairpin: the two segments cancel out. Keep the incoming direction.
                direction = hasPrevious ? path[i] - path[(i - 1 + n) % n] : path[(i + 1) % n] - path[i]
            }
            return simd_normalize(direction)
        }
    }

    /// One normal per tangent, carried along by parallel transport so the cross-section
    /// never spins about the path. On a closed path the twist the loop accumulates is
    /// spread evenly over the rings, so the last ring meets the first without a seam.
    static func transportedNormals(_ tangents: [SIMD3<Float>], closed: Bool) -> [SIMD3<Float>] {
        guard let t0 = tangents.first else { return [] }
        var normals = [initialNormal(for: t0)]
        for i in 1..<max(tangents.count, 1) {
            normals.append(transport(normals[i - 1], from: tangents[i - 1], to: tangents[i]))
        }
        guard closed, tangents.count > 1 else { return normals }

        let back = transport(normals[normals.count - 1], from: tangents[tangents.count - 1], to: t0)
        let twist = atan2(simd_dot(simd_cross(back, normals[0]), t0), simd_dot(back, normals[0]))
        let count = Float(tangents.count)
        for i in 1..<tangents.count {
            let angle = twist * Float(i) / count
            normals[i] = simd_normalize(simd_quatf(angle: angle, axis: tangents[i]).act(normals[i]))
        }
        return normals
    }

    private static func initialNormal(for tangent: SIMD3<Float>) -> SIMD3<Float> {
        let axes: [SIMD3<Float>] = [[1, 0, 0], [0, 1, 0], [0, 0, 1]]
        let axis = axes.min { abs(simd_dot($0, tangent)) < abs(simd_dot($1, tangent)) } ?? [0, 1, 0]
        return simd_normalize(axis - tangent * simd_dot(axis, tangent))
    }

    private static func transport(_ normal: SIMD3<Float>, from t0: SIMD3<Float>,
                                  to t1: SIMD3<Float>) -> SIMD3<Float> {
        var carried = normal
        if simd_length(simd_cross(t0, t1)) > epsilon {
            carried = simd_quatf(from: t0, to: t1).act(normal)
        }
        let projected = carried - t1 * simd_dot(carried, t1)
        return simd_length(projected) > epsilon ? simd_normalize(projected) : initialNormal(for: t1)
    }

    // MARK: Caps

    private mutating func addCap(centre: SIMD3<Float>, tangent: SIMD3<Float>, normal: SIMD3<Float>,
                                 radius: Float, segments: Int, facingForward: Bool) {
        let binormal = simd_cross(tangent, normal)
        let faceNormal = facingForward ? tangent : -tangent
        let centreIndex = UInt32(positions.count)
        positions.append(centre)
        normals.append(faceNormal)
        uvs.append(SIMD2(0.5, 0.5))
        for j in 0...segments {
            let theta = Float(j) / Float(segments) * 2 * .pi
            let direction = cos(theta) * normal + sin(theta) * binormal
            positions.append(centre + direction * radius)
            normals.append(faceNormal)
            uvs.append(SIMD2(0.5 + cos(theta) * 0.5, 0.5 + sin(theta) * 0.5))
        }
        for j in 0..<segments {
            let rim = centreIndex + 1 + UInt32(j)
            indices += facingForward ? [centreIndex, rim, rim + 1] : [centreIndex, rim + 1, rim]
        }
    }
}
#endif

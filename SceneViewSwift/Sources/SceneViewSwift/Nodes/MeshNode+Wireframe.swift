#if os(iOS) || os(macOS) || os(visionOS)
import RealityKit
import Foundation
import simd

// MARK: - Wireframe

/// Wireframes for any triangle mesh.
///
/// RealityKit has no line primitive and no wireframe fill mode (unlike Filament's
/// `PrimitiveType.LINES`), so a wireframe here is geometry: every edge becomes a thin
/// prism of its own. That keeps the wires the same width at every distance in meters
/// (not in pixels), lets them take any ``GeometryMaterial`` — unlit reads as a line —
/// and costs `2 × sides` vertices per edge, so keep wireframes for meshes of a few
/// thousand triangles.
///
/// ```swift
/// let torus = GeometryNode.torus(majorRadius: 0.2, minorRadius: 0.06)
/// if let mesh = torus.entity.model?.mesh {
///     let wire = try MeshNode.wireframe(of: mesh, thickness: 0.002,
///                                       material: .unlit(color: .white))
///     root.addChild(wire.entity)
/// }
/// ```
extension MeshNode {

    /// The wireframe of a RealityKit mesh — every model part, every instance.
    ///
    /// Vertices that share a position (a sphere's UV seam, a box's per-face corners)
    /// are welded first, so a shared edge is drawn once.
    ///
    /// - Parameters:
    ///   - mesh: Any triangle mesh — a primitive's, a loaded model's, a generated one.
    ///   - thickness: Width of each wire, in meters. Default 2 mm.
    ///   - hideQuadDiagonals: Skips the diagonal that splits a quad into two triangles,
    ///     so a cube shows its 12 edges and a finely tessellated sphere or torus its
    ///     rings. An edge counts as a diagonal when it is the strictly longest edge of
    ///     both triangles that share it and they are close to coplanar. On a coarse
    ///     curved mesh some quads are trapezoids whose diagonal is shorter than their
    ///     long side; those diagonals stay visible. Default `true`.
    ///   - material: Material of the wires. Default unlit white.
    /// - Throws: If RealityKit cannot generate the wire mesh, or the mesh has no edges.
    public static func wireframe(
        of mesh: MeshResource,
        thickness: Float = 0.002,
        hideQuadDiagonals: Bool = true,
        material: GeometryMaterial = .unlit(color: .white)
    ) throws -> MeshNode {
        var positions: [SIMD3<Float>] = []
        var triangles: [UInt32] = []
        let contents = mesh.contents
        func append(_ model: MeshResource.Model, transform: simd_float4x4) {
            for part in model.parts {
                guard let partIndices = part.triangleIndices?.elements else { continue }
                let base = UInt32(positions.count)
                positions += part.positions.elements.map { point in
                    let p = transform * SIMD4(point, 1)
                    return SIMD3(p.x, p.y, p.z)
                }
                triangles += partIndices.map { $0 + base }
            }
        }
        let instances = Array(contents.instances)
        if instances.isEmpty {
            for model in contents.models { append(model, transform: matrix_identity_float4x4) }
        } else {
            for instance in instances {
                if let model = contents.models[instance.model] {
                    append(model, transform: instance.transform)
                }
            }
        }
        return try wireframe(positions: positions, triangleIndices: triangles,
                             thickness: thickness, hideQuadDiagonals: hideQuadDiagonals,
                             material: material)
    }

    /// The wireframe of a triangle list — the same `positions` and `indices` you would
    /// pass to ``fromVertices(positions:normals:uvs:indices:material:)``.
    ///
    /// - Parameters:
    ///   - positions: Vertex positions.
    ///   - triangleIndices: Triangle indices, three per triangle. A triangle with an
    ///     index outside `positions`, or a non-finite corner, is skipped.
    ///   - thickness: Width of each wire, in meters. Default 2 mm.
    ///   - hideQuadDiagonals: See ``wireframe(of:thickness:hideQuadDiagonals:material:)``.
    ///   - material: Material of the wires. Default unlit white.
    public static func wireframe(
        positions: [SIMD3<Float>],
        triangleIndices: [UInt32],
        thickness: Float = 0.002,
        hideQuadDiagonals: Bool = true,
        material: GeometryMaterial = .unlit(color: .white)
    ) throws -> MeshNode {
        let welded = WireframeGeometry.weld(positions)
        let edges = WireframeGeometry.edges(triangleIndices: WireframeGeometry.remap(triangleIndices,
                                                                                     with: welded.remap),
                                            positions: welded.positions,
                                            hideQuadDiagonals: hideQuadDiagonals)
        return try wireframe(positions: welded.positions, edges: edges,
                             thickness: thickness, material: material)
    }

    /// Wires along explicit edges: `lineIndices` holds two vertex indices per wire,
    /// like a `LINES` primitive.
    ///
    /// - Parameters:
    ///   - positions: Vertex positions.
    ///   - lineIndices: Vertex index pairs, one pair per wire.
    ///   - thickness: Width of each wire, in meters. Default 2 mm.
    ///   - material: Material of the wires. Default unlit white.
    public static func wireframe(
        positions: [SIMD3<Float>],
        lineIndices: [UInt32],
        thickness: Float = 0.002,
        material: GeometryMaterial = .unlit(color: .white)
    ) throws -> MeshNode {
        let edges = stride(from: 0, to: lineIndices.count - 1, by: 2).map {
            WireframeGeometry.Edge(lineIndices[$0], lineIndices[$0 + 1])
        }
        return try wireframe(positions: positions, edges: edges,
                             thickness: thickness, material: material)
    }

    private static func wireframe(
        positions: [SIMD3<Float>],
        edges: [WireframeGeometry.Edge],
        thickness: Float,
        material: GeometryMaterial
    ) throws -> MeshNode {
        let geometry = WireframeGeometry(positions: positions, edges: edges, thickness: thickness)
        guard !geometry.indices.isEmpty else { throw WireframeGeometry.Failure.noEdges }
        var descriptor = MeshDescriptor(name: "Wireframe")
        descriptor.positions = MeshBuffers.Positions(geometry.positions)
        descriptor.normals = MeshBuffers.Normals(geometry.normals)
        descriptor.primitives = .triangles(geometry.indices)
        let mesh = try MeshResource.generate(from: [descriptor])
        return MeshNode(ModelEntity(mesh: mesh, materials: [material.rkMaterial]))
    }
}

/// Wire prisms for a set of edges, computed without RealityKit so it can be tested.
struct WireframeGeometry {
    /// An undirected edge between two vertex indices.
    struct Edge: Hashable {
        let a: UInt32
        let b: UInt32
        init(_ a: UInt32, _ b: UInt32) {
            self.a = min(a, b)
            self.b = max(a, b)
        }
    }

    enum Failure: Error { case noEdges }

    /// Sides of each wire's cross-section: a square reads as a line from any angle.
    static let sides = 4

    var positions: [SIMD3<Float>] = []
    var normals: [SIMD3<Float>] = []
    var indices: [UInt32] = []

    /// `2 × sides` vertices and `2 × sides` triangles per drawable edge; an edge whose
    /// ends coincide, or that points outside `positions`, is skipped.
    init(positions source: [SIMD3<Float>], edges: [Edge], thickness: Float) {
        let radius = max(thickness, 0) / 2
        guard radius > 0 else { return }
        let k = Self.sides
        for edge in edges {
            guard Int(edge.b) < source.count else { continue }
            let start = source[Int(edge.a)]
            let end = source[Int(edge.b)]
            let axis = end - start
            guard simd_length(axis) > 1e-7 else { continue }
            let t = simd_normalize(axis)
            let n = Self.perpendicular(to: t)
            let b = simd_cross(t, n)
            let base = UInt32(positions.count)
            for centre in [start, end] {
                for j in 0..<k {
                    let theta = (Float(j) + 0.5) / Float(k) * 2 * .pi
                    let direction = cos(theta) * n + sin(theta) * b
                    positions.append(centre + direction * radius)
                    normals.append(direction)
                }
            }
            for j in 0..<k {
                let a = base + UInt32(j)
                let b = base + UInt32((j + 1) % k)
                let c = b + UInt32(k)
                let d = a + UInt32(k)
                indices += [a, b, c, a, c, d]
            }
        }
    }

    private static func perpendicular(to t: SIMD3<Float>) -> SIMD3<Float> {
        let axis: SIMD3<Float> = abs(t.y) < 0.9 ? [0, 1, 0] : [1, 0, 0]
        return simd_normalize(axis - t * simd_dot(axis, t))
    }

    /// Index that points at no vertex: what ``weld(_:)`` gives a non-finite position.
    static let invalidIndex = UInt32.max

    /// Merges vertices closer than 10 µm, so seams and per-face corners share one index.
    /// A non-finite position maps to ``invalidIndex`` and is dropped.
    static func weld(_ positions: [SIMD3<Float>]) -> (positions: [SIMD3<Float>], remap: [UInt32]) {
        var unique: [SIMD3<Float>] = []
        var lookup: [SIMD3<Int64>: UInt32] = [:]
        var remap: [UInt32] = []
        remap.reserveCapacity(positions.count)
        for p in positions {
            // 1e5 × a float up to ~9e13 fits an Int64; anything larger is not a usable vertex.
            guard p.x.isFinite, p.y.isFinite, p.z.isFinite,
                  Swift.max(abs(p.x), abs(p.y), abs(p.z)) < 1e13 else {
                remap.append(invalidIndex)
                continue
            }
            let key = SIMD3<Int64>((p * 1e5).rounded(.toNearestOrEven))
            if let existing = lookup[key] {
                remap.append(existing)
            } else {
                let index = UInt32(unique.count)
                lookup[key] = index
                unique.append(p)
                remap.append(index)
            }
        }
        return (unique, remap)
    }

    /// Triangle indices rewritten through `remap`; a whole triangle is dropped when one
    /// of its indices is outside `remap` or maps to ``invalidIndex``.
    static func remap(_ triangleIndices: [UInt32], with remap: [UInt32]) -> [UInt32] {
        var result: [UInt32] = []
        result.reserveCapacity(triangleIndices.count)
        var i = 0
        while i + 2 < triangleIndices.count {
            let corners = triangleIndices[i...(i + 2)].map { Int($0) < remap.count ? remap[Int($0)] : invalidIndex }
            i += 3
            if !corners.contains(invalidIndex) { result += corners }
        }
        return result
    }

    /// Every edge of a triangle list once, in first-seen order, minus quad diagonals
    /// when asked. Degenerate triangles are ignored.
    static func edges(triangleIndices: [UInt32], positions: [SIMD3<Float>],
                      hideQuadDiagonals: Bool) -> [Edge] {
        var order: [Edge] = []
        var adjacent: [Edge: (count: Int, longestInAll: Bool, normal: SIMD3<Float>, coplanar: Bool)] = [:]
        var i = 0
        while i + 2 < triangleIndices.count {
            let corners = [triangleIndices[i], triangleIndices[i + 1], triangleIndices[i + 2]]
            i += 3
            guard corners.allSatisfy({ Int($0) < positions.count }),
                  Set(corners).count == 3 else { continue }
            let p = corners.map { positions[Int($0)] }
            let normal = simd_cross(p[1] - p[0], p[2] - p[0])
            guard simd_length(normal) > 0 else { continue }
            let unitNormal = simd_normalize(normal)
            let sides = (0..<3).map { Edge(corners[$0], corners[($0 + 1) % 3]) }
            let lengths = (0..<3).map { simd_distance(p[$0], p[($0 + 1) % 3]) }
            for s in 0..<3 {
                let others = lengths.enumerated().filter { $0.offset != s }.map(\.element)
                let longest = others.allSatisfy { lengths[s] > $0 * 1.0001 }
                if let entry = adjacent[sides[s]] {
                    adjacent[sides[s]] = (entry.count + 1, entry.longestInAll && longest, entry.normal,
                                          entry.coplanar && simd_dot(entry.normal, unitNormal) > coplanarCosine)
                } else {
                    order.append(sides[s])
                    adjacent[sides[s]] = (1, longest, unitNormal, true)
                }
            }
        }
        guard hideQuadDiagonals else { return order }
        return order.filter { edge in
            guard let entry = adjacent[edge] else { return true }
            return !(entry.count == 2 && entry.longestInAll && entry.coplanar)
        }
    }

    /// Two triangles within 40° of each other count as one face for diagonal hiding.
    static let coplanarCosine: Float = cos(40 * .pi / 180)
}
#endif

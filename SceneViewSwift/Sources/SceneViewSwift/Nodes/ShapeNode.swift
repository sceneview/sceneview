#if os(iOS) || os(macOS) || os(visionOS)
import RealityKit
import Foundation
#if os(macOS)
import AppKit
#else
import UIKit
#endif

/// A node that renders a 2D polygon extruded or triangulated into 3D geometry.
///
/// Mirrors SceneView Android's `ShapeNode` -- creates flat or extruded shapes
/// from a list of 2D polygon points using RealityKit's `MeshResource`.
///
/// Use this for custom 2D outlines, floor plans, logos, or any arbitrary
/// planar shape that is not covered by the built-in primitives in
/// ``GeometryNode``.
///
/// ```swift
/// // A triangle on the XY plane, facing the camera (+Z)
/// let triangle = ShapeNode(
///     points: [
///         SIMD2<Float>(0, 0.5),
///         SIMD2<Float>(-0.5, -0.5),
///         SIMD2<Float>(0.5, -0.5)
///     ],
///     color: .systemYellow
/// )
/// content.addChild(triangle.entity)
///
/// // An extruded L-shape
/// let lShape = ShapeNode(
///     points: [
///         SIMD2<Float>(0, 0),
///         SIMD2<Float>(0.5, 0),
///         SIMD2<Float>(0.5, 0.2),
///         SIMD2<Float>(0.2, 0.2),
///         SIMD2<Float>(0.2, 0.8),
///         SIMD2<Float>(0, 0.8)
///     ],
///     extrusionDepth: 0.1,
///     color: .blue
/// )
/// content.addChild(lShape.entity)
/// ```
public struct ShapeNode: Sendable {
    /// The underlying RealityKit entity.
    public let entity: ModelEntity

    /// The 2D polygon points that define this shape.
    public let points: [SIMD2<Float>]

    /// World-space position.
    public var position: SIMD3<Float> {
        get { entity.position }
        nonmutating set { entity.position = newValue }
    }

    /// Orientation as a quaternion.
    public var rotation: simd_quatf {
        get { entity.orientation }
        nonmutating set { entity.orientation = newValue }
    }

    /// Scale factor.
    public var scale: SIMD3<Float> {
        get { entity.scale }
        nonmutating set { entity.scale = newValue }
    }

    // MARK: - Initializers

    /// Creates a flat or extruded shape from a polygon defined by 2D points.
    ///
    /// The polygon is triangulated using an ear-clipping algorithm and placed in
    /// the XY plane facing the camera (+Z), mirroring SceneView Android's
    /// `ShapeGeometry`. If `extrusionDepth` is greater than zero, the shape is
    /// extruded symmetrically along the Z axis to create a solid.
    ///
    /// The polygon may be wound either clockwise or counter-clockwise — the
    /// mesh is normalised internally so the faces are always wound correctly
    /// and lit from the outside.
    ///
    /// - Note: Holes and explicit Delaunay interior points are an Android-only
    ///   feature of `ShapeGeometry`; they are not yet supported on iOS. iOS
    ///   triangulates the single outer contour only.
    ///
    /// - Parameters:
    ///   - points: Ordered 2D vertices of the polygon (minimum 3).
    ///     The polygon is automatically closed (last point connects to first).
    ///   - extrusionDepth: Thickness along the Z axis in meters. 0 produces a
    ///     flat shape. Default 0.
    ///   - color: Material color. Default white.
    ///   - isMetallic: Whether the material is metallic. Default false.
    ///     Ignored when `unlit` is `true`.
    ///   - unlit: When `true`, uses a flat unlit material (no lighting response)
    ///     instead of the default physically-based material. Default `false`.
    public init(
        points: [SIMD2<Float>],
        extrusionDepth: Float = 0,
        color: SimpleMaterial.Color = .white,
        isMetallic: Bool = false,
        unlit: Bool = false
    ) {
        self.points = points

        let material: any RealityKit.Material
        if unlit {
            // UnlitMaterial renders a flat fill that ignores scene lighting,
            // honouring the `unlit` contract. `isMetallic` has no meaning for
            // an unlit material and is intentionally ignored on this path.
            material = UnlitMaterial(color: color)
        } else {
            var pbr = PhysicallyBasedMaterial()
            pbr.baseColor = .init(tint: color)
            pbr.metallic = .init(floatLiteral: isMetallic ? 1.0 : 0.0)
            pbr.roughness = .init(floatLiteral: 0.5)
            material = pbr
        }

        if points.count < 3 {
            // Degenerate polygon -- return an empty entity
            let mesh = MeshResource.generatePlane(width: 0.001, depth: 0.001)
            self.entity = ModelEntity(mesh: mesh, materials: [material])
            return
        }

        if extrusionDepth > 0 {
            // Extruded shape: build 3D positions from the 2D polygon
            let mesh = ShapeNode.buildExtrudedMesh(points: points, depth: extrusionDepth)
            if let mesh = mesh {
                self.entity = ModelEntity(mesh: mesh, materials: [material])
            } else {
                // Fallback to flat if extrusion fails
                let flatMesh = ShapeNode.buildFlatMesh(points: points)
                self.entity = ModelEntity(
                    mesh: flatMesh ?? MeshResource.generatePlane(width: 0.001, depth: 0.001),
                    materials: [material]
                )
            }
        } else {
            // Flat shape in the XY plane, facing +Z
            let mesh = ShapeNode.buildFlatMesh(points: points)
            self.entity = ModelEntity(
                mesh: mesh ?? MeshResource.generatePlane(width: 0.001, depth: 0.001),
                materials: [material]
            )
        }

        entity.generateCollisionShapes(recursive: false)
    }

    /// Creates a shape with a ``GeometryMaterial``.
    ///
    /// - Parameters:
    ///   - points: Ordered 2D vertices of the polygon (minimum 3).
    ///   - extrusionDepth: Thickness along the Z axis. Default 0.
    ///   - material: Material to apply.
    public init(
        points: [SIMD2<Float>],
        extrusionDepth: Float = 0,
        material: GeometryMaterial
    ) {
        self.points = points

        if points.count < 3 {
            let mesh = MeshResource.generatePlane(width: 0.001, depth: 0.001)
            self.entity = ModelEntity(mesh: mesh, materials: [material.rkMaterial])
            return
        }

        if extrusionDepth > 0 {
            let mesh = ShapeNode.buildExtrudedMesh(points: points, depth: extrusionDepth)
            self.entity = ModelEntity(
                mesh: mesh ?? MeshResource.generatePlane(width: 0.001, depth: 0.001),
                materials: [material.rkMaterial]
            )
        } else {
            let mesh = ShapeNode.buildFlatMesh(points: points)
            self.entity = ModelEntity(
                mesh: mesh ?? MeshResource.generatePlane(width: 0.001, depth: 0.001),
                materials: [material.rkMaterial]
            )
        }

        entity.generateCollisionShapes(recursive: false)
    }

    // MARK: - Preset shapes

    /// The 2D vertices of a regular polygon, first vertex at angle `-.pi / 2`
    /// (toward -Y) and wound counter-clockwise.
    ///
    /// The single source of truth for the points used by
    /// ``regularPolygon(sides:radius:extrusionDepth:color:)``. Exposed so the
    /// demo gallery and the unit tests can share one polygon definition instead
    /// of hand-copying coordinates.
    ///
    /// - Parameters:
    ///   - sides: Number of sides (clamped to a minimum of 3).
    ///   - radius: Circumscribed radius in meters.
    /// - Returns: `max(sides, 3)` ordered XY vertices.
    public static func regularPolygonPoints(sides: Int, radius: Float = 0.5) -> [SIMD2<Float>] {
        let n = max(sides, 3)
        var pts: [SIMD2<Float>] = []
        for i in 0..<n {
            let angle = Float(i) / Float(n) * 2 * .pi - .pi / 2
            pts.append(SIMD2<Float>(radius * cos(angle), radius * sin(angle)))
        }
        return pts
    }

    /// Creates a regular polygon (e.g. pentagon, hexagon, octagon).
    ///
    /// - Parameters:
    ///   - sides: Number of sides (minimum 3).
    ///   - radius: Circumscribed radius in meters.
    ///   - extrusionDepth: Extrusion depth. Default 0 (flat).
    ///   - color: Material color.
    /// - Returns: A `ShapeNode` with the regular polygon.
    public static func regularPolygon(
        sides: Int,
        radius: Float = 0.5,
        extrusionDepth: Float = 0,
        color: SimpleMaterial.Color = .white
    ) -> ShapeNode {
        ShapeNode(
            points: regularPolygonPoints(sides: sides, radius: radius),
            extrusionDepth: extrusionDepth,
            color: color
        )
    }

    /// The 2D vertices of a star polygon, alternating `outerRadius` /
    /// `innerRadius`, first point at angle `-.pi / 2` (toward -Y) and wound
    /// counter-clockwise.
    ///
    /// The single source of truth for the points used by
    /// ``star(pointCount:outerRadius:innerRadius:extrusionDepth:color:)``.
    /// Exposed so the demo gallery and the unit tests can share one polygon
    /// definition instead of hand-copying coordinates.
    ///
    /// - Parameters:
    ///   - pointCount: Number of star points (clamped to a minimum of 3).
    ///   - outerRadius: Outer radius in meters.
    ///   - innerRadius: Inner radius in meters.
    /// - Returns: `max(pointCount, 3) * 2` ordered XY vertices.
    public static func starPoints(
        pointCount: Int = 5,
        outerRadius: Float = 0.5,
        innerRadius: Float = 0.2
    ) -> [SIMD2<Float>] {
        let n = max(pointCount, 3)
        var pts: [SIMD2<Float>] = []
        for i in 0..<(n * 2) {
            let angle = Float(i) / Float(n * 2) * 2 * .pi - .pi / 2
            let r = i % 2 == 0 ? outerRadius : innerRadius
            pts.append(SIMD2<Float>(r * cos(angle), r * sin(angle)))
        }
        return pts
    }

    /// Creates a star shape.
    ///
    /// - Parameters:
    ///   - pointCount: Number of star points. Default 5.
    ///   - outerRadius: Outer radius in meters.
    ///   - innerRadius: Inner radius in meters.
    ///   - extrusionDepth: Extrusion depth. Default 0 (flat).
    ///   - color: Material color.
    /// - Returns: A `ShapeNode` with the star shape.
    public static func star(
        pointCount: Int = 5,
        outerRadius: Float = 0.5,
        innerRadius: Float = 0.2,
        extrusionDepth: Float = 0,
        color: SimpleMaterial.Color = .yellow
    ) -> ShapeNode {
        ShapeNode(
            points: starPoints(pointCount: pointCount, outerRadius: outerRadius, innerRadius: innerRadius),
            extrusionDepth: extrusionDepth,
            color: color
        )
    }

    // MARK: - Transform helpers

    /// Returns self positioned at the given coordinates.
    @discardableResult
    public func position(_ position: SIMD3<Float>) -> ShapeNode {
        entity.position = position
        return self
    }

    /// Returns self scaled uniformly.
    @discardableResult
    public func scale(_ uniform: Float) -> ShapeNode {
        entity.scale = .init(repeating: uniform)
        return self
    }

    /// Returns self scaled per-axis.
    @discardableResult
    public func scale(_ scale: SIMD3<Float>) -> ShapeNode {
        entity.scale = scale
        return self
    }

    /// Returns self rotated by the given quaternion.
    @discardableResult
    public func rotation(_ rotation: simd_quatf) -> ShapeNode {
        entity.orientation = rotation
        return self
    }

    /// Returns self rotated by angle around axis.
    @discardableResult
    public func rotation(angle: Float, axis: SIMD3<Float>) -> ShapeNode {
        entity.orientation = simd_quatf(angle: angle, axis: axis)
        return self
    }

    /// Returns self with a grounding shadow.
    @discardableResult
    public func withGroundingShadow() -> ShapeNode {
        if #available(iOS 18.0, visionOS 2.0, *) {
            entity.components.set(GroundingShadowComponent(castsShadow: true))
        }
        return self
    }

    // MARK: - Mesh generation

    /// Builds a flat triangulated mesh from a 2D polygon in the XY plane,
    /// facing +Z (mirrors SceneView Android's `ShapeGeometry`).
    private static func buildFlatMesh(points: [SIMD2<Float>]) -> MeshResource? {
        guard points.count >= 3 else { return nil }

        // Normalise to CCW winding ONCE, up front, and build everything from
        // `pts`. With a single always-CCW source the front-cap winding is
        // always correct (no per-input branch). For already-CCW input — every
        // demo preset — `pts == points`, so the mesh is byte-identical.
        var pts = points
        if signedArea(pts) < 0 { pts.reverse() }

        let indices = earClipTriangulate(pts)
        guard !indices.isEmpty else { return nil }

        // Convert 2D points to 3D in the XY plane (Z = 0), facing +Z
        let positions = pts.map { SIMD3<Float>($0.x, $0.y, 0) }
        let normals = [SIMD3<Float>](repeating: SIMD3<Float>(0, 0, 1), count: positions.count)

        // Compute UV coordinates from bounding box
        let minX = pts.map(\.x).min() ?? 0
        let maxX = pts.map(\.x).max() ?? 1
        let minY = pts.map(\.y).min() ?? 0
        let maxY = pts.map(\.y).max() ?? 1
        let rangeX = max(maxX - minX, 1e-6)
        let rangeY = max(maxY - minY, 1e-6)
        let uvs = pts.map { SIMD2<Float>(($0.x - minX) / rangeX, ($0.y - minY) / rangeY) }

        var descriptor = MeshDescriptor(name: "ShapeNode_Flat")
        descriptor.positions = MeshBuffers.Positions(positions)
        descriptor.normals = MeshBuffers.Normals(normals)
        descriptor.textureCoordinates = MeshBuffers.TextureCoordinates(uvs)
        descriptor.primitives = .triangles(indices)

        return try? MeshResource.generate(from: [descriptor])
    }

    /// Builds an extruded mesh from a 2D polygon in the XY plane, extruded
    /// symmetrically along the Z axis (mirrors SceneView Android's `ShapeGeometry`
    /// extrusion). The front face (+Z) faces the camera.
    private static func buildExtrudedMesh(points: [SIMD2<Float>], depth: Float) -> MeshResource? {
        guard points.count >= 3 else { return nil }

        // Normalise to CCW winding ONCE, up front, and build EVERYTHING from
        // `pts` — the triangulation, the front/back caps, and the side loop.
        // With a single always-CCW source, the CCW front-cap winding, the
        // reversed back-cap winding, the outward side-quad normal `(dy, -dx)`,
        // and the side-quad triangle winding are *all* correct for any input,
        // so there is no per-input flip branch (a flip that touched only the
        // normal would contradict the hardcoded winding and render CW inputs
        // inside-out). For already-CCW input — every demo preset — `pts ==
        // points`, so the mesh is byte-identical and there is no visual change.
        var pts = points
        if signedArea(pts) < 0 { pts.reverse() }

        let topIndices = earClipTriangulate(pts)
        guard !topIndices.isEmpty else { return nil }

        let halfDepth = depth / 2
        var positions: [SIMD3<Float>] = []
        var normals: [SIMD3<Float>] = []
        var uvs: [SIMD2<Float>] = []
        var indices: [UInt32] = []

        // Front face (+Z) — faces the camera, same winding as the flat mesh.
        let frontOffset = UInt32(positions.count)
        for p in pts {
            positions.append(SIMD3<Float>(p.x, p.y, halfDepth))
            normals.append(SIMD3<Float>(0, 0, 1))
            uvs.append(p)
        }
        for idx in topIndices {
            indices.append(frontOffset + idx)
        }

        // Back face (-Z) — reversed winding so it faces away from the camera.
        let backOffset = UInt32(positions.count)
        for p in pts {
            positions.append(SIMD3<Float>(p.x, p.y, -halfDepth))
            normals.append(SIMD3<Float>(0, 0, -1))
            uvs.append(p)
        }
        for i in stride(from: 0, to: topIndices.count, by: 3) {
            indices.append(backOffset + topIndices[i + 2])
            indices.append(backOffset + topIndices[i + 1])
            indices.append(backOffset + topIndices[i])
        }

        // Side faces — quads wrapping the XY perimeter, normals pointing outward.
        // `pts` is now guaranteed CCW, so the outward 2D normal of edge
        // (p0 → p1) is `(dy, -dx)` and the quad triangle winding below is the
        // matching outward winding — both hold for every input.
        let n = pts.count
        for i in 0..<n {
            let j = (i + 1) % n
            let p0 = pts[i]
            let p1 = pts[j]

            let edge = SIMD2<Float>(p1.x - p0.x, p1.y - p0.y)
            let edgeLen = max(sqrt(edge.x * edge.x + edge.y * edge.y), 1e-6)
            // Outward in-plane normal (XY), Z component is zero.
            let nx = edge.y / edgeLen
            let ny = -edge.x / edgeLen
            let normal3 = SIMD3<Float>(nx, ny, 0)

            let sideOffset = UInt32(positions.count)

            // Four vertices per side quad (front-top, front-next, back-next, back-top)
            positions.append(SIMD3<Float>(p0.x, p0.y, halfDepth))
            positions.append(SIMD3<Float>(p1.x, p1.y, halfDepth))
            positions.append(SIMD3<Float>(p1.x, p1.y, -halfDepth))
            positions.append(SIMD3<Float>(p0.x, p0.y, -halfDepth))

            for _ in 0..<4 { normals.append(normal3) }
            uvs.append(SIMD2<Float>(0, 1))
            uvs.append(SIMD2<Float>(1, 1))
            uvs.append(SIMD2<Float>(1, 0))
            uvs.append(SIMD2<Float>(0, 0))

            // Two triangles per quad, wound so the front face points outward.
            indices.append(sideOffset)
            indices.append(sideOffset + 2)
            indices.append(sideOffset + 1)
            indices.append(sideOffset)
            indices.append(sideOffset + 3)
            indices.append(sideOffset + 2)
        }

        var descriptor = MeshDescriptor(name: "ShapeNode_Extruded")
        descriptor.positions = MeshBuffers.Positions(positions)
        descriptor.normals = MeshBuffers.Normals(normals)
        descriptor.textureCoordinates = MeshBuffers.TextureCoordinates(uvs)
        descriptor.primitives = .triangles(indices)

        return try? MeshResource.generate(from: [descriptor])
    }

    // MARK: - Ear-clipping triangulation

    /// Simple ear-clipping triangulation for a 2D polygon.
    ///
    /// Returns triangle indices into the original points array.
    /// Handles simple convex and concave polygons without holes.
    ///
    /// `internal` (not `private`) so the unit tests can assert the concave
    /// presets triangulate to `n - 2` triangles with ~0 area deviation.
    static func earClipTriangulate(_ polygon: [SIMD2<Float>]) -> [UInt32] {
        guard polygon.count >= 3 else { return [] }

        // Ensure counter-clockwise winding
        var pts = polygon
        if signedArea(pts) < 0 {
            pts.reverse()
        }

        var remaining = Array(0..<pts.count)
        var triangles: [UInt32] = []

        // Map from reordered indices back to original
        let originalIndices: [UInt32]
        if signedArea(polygon) < 0 {
            originalIndices = (0..<UInt32(polygon.count)).reversed().map { $0 }
        } else {
            originalIndices = (0..<UInt32(polygon.count)).map { $0 }
        }

        var maxIterations = remaining.count * remaining.count
        while remaining.count > 2 && maxIterations > 0 {
            maxIterations -= 1
            var earFound = false

            for i in 0..<remaining.count {
                let prev = remaining[(i + remaining.count - 1) % remaining.count]
                let curr = remaining[i]
                let next = remaining[(i + 1) % remaining.count]

                let a = pts[prev]
                let b = pts[curr]
                let c = pts[next]

                // Check if this is a convex vertex
                if cross2D(a, b, c) <= 0 { continue }

                // Check that no other vertex is inside this triangle
                var isEar = true
                for j in 0..<remaining.count {
                    let idx = remaining[j]
                    if idx == prev || idx == curr || idx == next { continue }
                    if pointInTriangle(pts[idx], a, b, c) {
                        isEar = false
                        break
                    }
                }

                if isEar {
                    triangles.append(originalIndices[prev])
                    triangles.append(originalIndices[curr])
                    triangles.append(originalIndices[next])
                    remaining.remove(at: i)
                    earFound = true
                    break
                }
            }

            if !earFound { break }
        }

        return triangles
    }

    /// Signed area of a 2D polygon. Positive = CCW, negative = CW.
    ///
    /// `internal` so the unit tests can compute expected polygon area for the
    /// triangulation area-deviation guard.
    static func signedArea(_ pts: [SIMD2<Float>]) -> Float {
        var area: Float = 0
        let n = pts.count
        for i in 0..<n {
            let j = (i + 1) % n
            area += pts[i].x * pts[j].y
            area -= pts[j].x * pts[i].y
        }
        return area / 2
    }

    /// 2D cross product of vectors (b-a) and (c-a).
    private static func cross2D(
        _ a: SIMD2<Float>,
        _ b: SIMD2<Float>,
        _ c: SIMD2<Float>
    ) -> Float {
        (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)
    }

    /// Whether a point is inside a triangle (using barycentric coordinates).
    private static func pointInTriangle(
        _ p: SIMD2<Float>,
        _ a: SIMD2<Float>,
        _ b: SIMD2<Float>,
        _ c: SIMD2<Float>
    ) -> Bool {
        let d1 = cross2D(a, b, p)
        let d2 = cross2D(b, c, p)
        let d3 = cross2D(c, a, p)
        let hasNeg = (d1 < 0) || (d2 < 0) || (d3 < 0)
        let hasPos = (d1 > 0) || (d2 > 0) || (d3 > 0)
        return !(hasNeg && hasPos)
    }
}

#endif // os(iOS) || os(macOS) || os(visionOS)

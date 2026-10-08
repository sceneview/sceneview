import Foundation
import simd

/*
 * RerunFrame → triangles, for the replay's 3D stage. The iOS twin of Android's
 * `ArDebugGeometry` and `ReplayGeometry` (#4059): the same builders, sizes and layer split, so
 * the two apps draw the same picture.
 *
 * Pure: positions, uvs and indices in plain arrays — the stage turns each layer into one
 * RealityKit mesh with one flat unlit colour (a gradient is several layers, one per step).
 * Everything is real geometry, never GL lines: widths are screen pixels converted through
 * `RerunStyle.metresPerPixel`.
 */

/// A growable triangle list.
struct RerunMesh: Sendable {
    var positions: [SIMD3<Float>] = []
    /// One per vertex when the mesh is textured; `v = 0` is the image's top row.
    var uvs: [SIMD2<Float>] = []
    var indices: [UInt32] = []

    var isEmpty: Bool { indices.isEmpty }
    var vertexCount: Int { positions.count }
    var triangleCount: Int { indices.count / 3 }

    mutating func clear() {
        positions.removeAll(keepingCapacity: true)
        uvs.removeAll(keepingCapacity: true)
        indices.removeAll(keepingCapacity: true)
    }

    @discardableResult
    mutating func vertex(_ p: SIMD3<Float>) -> UInt32 {
        positions.append(p)
        return UInt32(positions.count - 1)
    }

    @discardableResult
    mutating func vertex(_ p: SIMD3<Float>, uv: SIMD2<Float>) -> UInt32 {
        positions.append(p)
        uvs.append(uv)
        return UInt32(positions.count - 1)
    }

    mutating func triangle(_ a: UInt32, _ b: UInt32, _ c: UInt32) {
        indices.append(a)
        indices.append(b)
        indices.append(c)
    }

    mutating func quad(_ a: UInt32, _ b: UInt32, _ c: UInt32, _ d: UInt32) {
        triangle(a, b, c)
        triangle(a, c, d)
    }
}

/// What the settings sheet's layer rows toggle. The stage (grid, axes) is always on.
enum RerunGroup: Hashable, Sendable, CaseIterable { case trail, points, planes, anchors }

/// Every flat-colour layer the stage draws; each is one mesh with one colour.
enum RerunLayer: Int, CaseIterable, Sendable {
    case gridMinor, gridMajor, axisX, axisY, axisZ
    case planeFloor, planeWall, planeOther, outlineFloor, outlineWall, outlineOther
    case livePoints
    case trail0, trail1, trail2, trail3, trail4, trail5, trail6, trail7, trailHead
    case keyframes, frustum
    case anchors

    static let trailSteps: [RerunLayer] = [.trail0, .trail1, .trail2, .trail3, .trail4, .trail5, .trail6, .trail7]

    var group: RerunGroup? {
        switch self {
        case .gridMinor, .gridMajor, .axisX, .axisY, .axisZ: nil
        case .planeFloor, .planeWall, .planeOther, .outlineFloor, .outlineWall, .outlineOther: .planes
        case .livePoints: .points
        case .anchors: .anchors
        default: .trail
        }
    }
}

enum RerunGeometry {
    // MARK: Constants

    /// Replay frustums are deep enough for their photos to read.
    static let frustumDepth: Float = 0.3
    static let keyframeDepth: Float = 0.2
    /// The photo floor sits this far under the grid, so the grid's lines stay on top.
    static let floorUnderGrid: Float = 0.004
    static let headLength: Float = 0.35
    static let trailMaxPoints = 1200
    static let anchorRing: Float = 0.16
    static let gridCell: Float = 0.5
    static let gridMajorEvery = 2
    static let axisLength: Float = 0.3
    /// Coloured points read as the room's texture, not as markers: a touch larger.
    static let pointScale: Float = 1.25
    static let shadowRadius: Float = 0.22
    static let shadowLift: Float = 0.003

    // MARK: Primitives

    /// A point as a small tetrahedron — four vertices, readable from every angle.
    static func addTetra(_ mesh: inout RerunMesh, _ p: SIMD3<Float>, _ r: Float, uv: SIMD2<Float>? = nil) {
        let s = r * 0.94
        let corners = [SIMD3(s, s, s), SIMD3(s, -s, -s), SIMD3(-s, s, -s), SIMD3(-s, -s, s)]
        var v: [UInt32] = []
        for corner in corners {
            if let uv { v.append(mesh.vertex(p + corner, uv: uv)) } else { v.append(mesh.vertex(p + corner)) }
        }
        mesh.triangle(v[0], v[1], v[2])
        mesh.triangle(v[0], v[3], v[1])
        mesh.triangle(v[0], v[2], v[3])
        mesh.triangle(v[1], v[3], v[2])
    }

    /// A point as an octahedron — rounder, for the few bright live points.
    static func addOcta(_ mesh: inout RerunMesh, _ p: SIMD3<Float>, _ r: Float) {
        let px = mesh.vertex(p + SIMD3(r, 0, 0)), nx = mesh.vertex(p - SIMD3(r, 0, 0))
        let py = mesh.vertex(p + SIMD3(0, r, 0)), ny = mesh.vertex(p - SIMD3(0, r, 0))
        let pz = mesh.vertex(p + SIMD3(0, 0, r)), nz = mesh.vertex(p - SIMD3(0, 0, r))
        mesh.triangle(py, pz, px); mesh.triangle(py, px, nz); mesh.triangle(py, nz, nx); mesh.triangle(py, nx, pz)
        mesh.triangle(ny, px, pz); mesh.triangle(ny, nz, px); mesh.triangle(ny, nx, nz); mesh.triangle(ny, pz, nx)
    }

    /// A thick line: a square prism from `a` to `b`, `radius` in half-width.
    static func addSegment(_ mesh: inout RerunMesh, _ a: SIMD3<Float>, _ b: SIMD3<Float>, _ radius: Float) {
        let axis = b - a
        let length = simd_length(axis)
        guard length >= 1e-6 else { return }
        let (u, v) = perpendiculars(axis / length)
        let su = u * radius, sv = v * radius
        let base = UInt32(mesh.vertexCount)
        for end in [a, b] {
            mesh.vertex(end + su + sv)
            mesh.vertex(end - su + sv)
            mesh.vertex(end - su - sv)
            mesh.vertex(end + su - sv)
        }
        for i: UInt32 in 0..<4 {
            let j = (i + 1) % 4
            mesh.quad(base + i, base + j, base + 4 + j, base + 4 + i)
        }
    }

    /// A flat strip from `a` to `b` in the plane of `normal`, lengthened by `halfWidth` at both
    /// ends so consecutive strips close their corners.
    static func addRibbon(_ mesh: inout RerunMesh, _ a: SIMD3<Float>, _ b: SIMD3<Float>, normal: SIMD3<Float>, halfWidth: Float) {
        let axis = b - a
        let length = simd_length(axis)
        guard length >= 1e-6 else { return }
        let dir = axis / length
        var side = simd_cross(normal, dir)
        if simd_length(side) < 1e-6 { side = perpendiculars(dir).0 }
        side = simd_normalize(side) * halfWidth
        let a2 = a - dir * halfWidth, b2 = b + dir * halfWidth
        mesh.quad(mesh.vertex(a2 - side), mesh.vertex(b2 - side), mesh.vertex(b2 + side), mesh.vertex(a2 + side))
    }

    /// A tube along `path[range]`, `sides`-gon section, parallel-transported so it never twists.
    static func addTube(_ mesh: inout RerunMesh, _ path: [SIMD3<Float>], _ range: Range<Int>, radius: Float, sides: Int = 6) {
        var pts: [SIMD3<Float>] = []
        pts.reserveCapacity(range.count)
        for i in range where pts.isEmpty || simd_distance(path[i], pts[pts.count - 1]) > 1e-3 { pts.append(path[i]) }
        guard pts.count >= 2 else { return }
        var tangent = simd_normalize(pts[1] - pts[0])
        var normal = perpendiculars(tangent).0
        let base = UInt32(mesh.vertexCount)
        for i in pts.indices {
            let next = i < pts.count - 1 ? simd_normalize(pts[i + 1] - pts[i]) : tangent
            var t = next
            if i > 0 {
                let mid = tangent + next
                if simd_length(mid) > 1e-6, simd_length(simd_normalize(mid)) > 0.5 { t = simd_normalize(mid) }
            }
            let projected = normal - t * simd_dot(normal, t)
            normal = simd_length(projected) > 1e-6 ? simd_normalize(projected) : perpendiculars(t).0
            let binormal = simd_cross(t, normal)
            for s in 0..<sides {
                let angle = 2 * Float.pi * Float(s) / Float(sides)
                mesh.vertex(pts[i] + normal * (cos(angle) * radius) + binormal * (sin(angle) * radius))
            }
            tangent = next
        }
        let n = UInt32(sides)
        for i in 0..<UInt32(pts.count - 1) {
            for s in 0..<n {
                let s2 = (s + 1) % n
                let ring = base + i * n, nextRing = ring + n
                mesh.quad(ring + s, ring + s2, nextRing + s2, nextRing + s)
            }
        }
        for end in [0, pts.count - 1] {
            let centre = mesh.vertex(pts[end])
            let ring = base + UInt32(end) * n
            for s in 0..<n { mesh.triangle(centre, ring + s, ring + (s + 1) % n) }
        }
    }

    /// A convex polygon filled as a fan around its centroid.
    static func addFan(_ mesh: inout RerunMesh, _ polygon: [SIMD3<Float>]) {
        guard polygon.count >= 3 else { return }
        let centre = mesh.vertex(polygon.reduce(.zero, +) / Float(polygon.count))
        let first = UInt32(mesh.vertexCount)
        for p in polygon { mesh.vertex(p) }
        let n = UInt32(polygon.count)
        for i in 0..<n { mesh.triangle(centre, first + i, first + (i + 1) % n) }
    }

    /// The outline of a polygon as ribbons lying in its own plane.
    static func addOutline(_ mesh: inout RerunMesh, _ polygon: [SIMD3<Float>], halfWidth: Float) {
        guard polygon.count >= 2 else { return }
        let normal = polygonNormal(polygon)
        for i in polygon.indices {
            addRibbon(&mesh, polygon[i], polygon[(i + 1) % polygon.count], normal: normal, halfWidth: halfWidth)
        }
    }

    /// Corners of the image plane at `depth`: top-left, top-right, bottom-right, bottom-left.
    static func frustumCorners(_ pose: RerunPose, depth: Float, lens: RerunLens) -> [SIMD3<Float>] {
        let hw = lens.halfWidthPerDepth * depth
        let hh = lens.halfHeightPerDepth * depth
        return [
            pose.transform(SIMD3(-hw, hh, -depth)),
            pose.transform(SIMD3(hw, hh, -depth)),
            pose.transform(SIMD3(hw, -hh, -depth)),
            pose.transform(SIMD3(-hw, -hh, -depth)),
        ]
    }

    /// A camera frustum: four side edges, the image rectangle, and an "up" tick over the top edge.
    static func addFrustumEdges(_ mesh: inout RerunMesh, _ pose: RerunPose, depth: Float, edge: Float, lens: RerunLens) {
        let apex = pose.position
        let c = frustumCorners(pose, depth: depth, lens: lens)
        for i in 0..<4 {
            addSegment(&mesh, apex, c[i], edge)
            addSegment(&mesh, c[i], c[(i + 1) % 4], edge)
        }
        let topMid = (c[0] + c[1]) * 0.5
        addSegment(&mesh, topMid, topMid + pose.rotate(SIMD3(0, 1, 0)) * (depth * 0.18), edge)
    }

    /// A flat ring around `centre` in the plane of `normal`.
    static func addRing(_ mesh: inout RerunMesh, centre: SIMD3<Float>, normal: SIMD3<Float>, radius: Float,
                        halfWidth: Float, segments: Int = 32) {
        let (u, v) = perpendiculars(simd_normalize(normal))
        let base = UInt32(mesh.vertexCount)
        for i in 0..<segments {
            let angle = 2 * Float.pi * Float(i) / Float(segments)
            let dir = u * cos(angle) + v * sin(angle)
            mesh.vertex(centre + dir * (radius - halfWidth))
            mesh.vertex(centre + dir * (radius + halfWidth))
        }
        let n = UInt32(segments)
        for i in 0..<n {
            let j = (i + 1) % n
            mesh.quad(base + i * 2, base + i * 2 + 1, base + j * 2 + 1, base + j * 2)
        }
    }

    /// The photo a camera at `pose` took, on its frustum's image plane: texture top row along
    /// the frustum's top edge.
    static func addImageQuad(_ mesh: inout RerunMesh, _ pose: RerunPose, depth: Float, lens: RerunLens) {
        let c = frustumCorners(pose, depth: depth, lens: lens)
        let a = mesh.vertex(c[0], uv: SIMD2(0, 0))
        let b = mesh.vertex(c[1], uv: SIMD2(1, 0))
        let d = mesh.vertex(c[2], uv: SIMD2(1, 1))
        let e = mesh.vertex(c[3], uv: SIMD2(0, 1))
        mesh.quad(a, b, d, e)
    }

    /// A plane's polygon filled with its photo. A floor (`flattenToY`) is laid flat under the grid.
    static func addTexturedPlane(_ mesh: inout RerunMesh, _ polygon: [SIMD3<Float>], texture: RerunPlaneTexture, flattenToY: Float?) {
        guard polygon.count >= 3 else { return }
        func put(_ p: SIMD3<Float>) -> UInt32 {
            mesh.vertex(SIMD3(p.x, flattenToY ?? p.y, p.z), uv: texture.uv(of: p))
        }
        let centre = put(polygon.reduce(.zero, +) / Float(polygon.count))
        let first = UInt32(mesh.vertexCount)
        for p in polygon { _ = put(p) }
        let n = UInt32(polygon.count)
        for i in 0..<n { mesh.triangle(centre, first + i, first + (i + 1) % n) }
    }

    /// Map points `range` as tetrahedra, each carrying its own texel of the colour atlas.
    static func addColoredPoints(_ mesh: inout RerunMesh, _ points: ArraySlice<SIMD3<Float>>, range: Range<Int>, radius: Float) {
        for i in range where i < RerunPointAtlas.capacity {
            addTetra(&mesh, points[points.startIndex + i], radius, uv: RerunPointAtlas.uv(of: i))
        }
    }

    /// A soft contact shadow quad under a placed model, uv 0…1.
    static func addShadow(_ mesh: inout RerunMesh, _ p: SIMD3<Float>, radius: Float) {
        let a = mesh.vertex(p + SIMD3(-radius, 0, -radius), uv: SIMD2(0, 0))
        let b = mesh.vertex(p + SIMD3(radius, 0, -radius), uv: SIMD2(1, 0))
        let c = mesh.vertex(p + SIMD3(radius, 0, radius), uv: SIMD2(1, 1))
        let d = mesh.vertex(p + SIMD3(-radius, 0, radius), uv: SIMD2(0, 1))
        mesh.quad(a, b, c, d)
    }

    // MARK: Helpers

    /// Two unit vectors perpendicular to the unit `dir` and to each other.
    static func perpendiculars(_ dir: SIMD3<Float>) -> (SIMD3<Float>, SIMD3<Float>) {
        let helper: SIMD3<Float> = abs(dir.y) < 0.9 ? SIMD3(0, 1, 0) : SIMD3(1, 0, 0)
        let u = simd_normalize(simd_cross(dir, helper))
        return (u, simd_normalize(simd_cross(dir, u)))
    }

    /// Newell's normal of a polygon — robust to collinear first vertices.
    static func polygonNormal(_ polygon: [SIMD3<Float>]) -> SIMD3<Float> {
        var n = SIMD3<Float>.zero
        for i in polygon.indices {
            let p0 = polygon[i], p1 = polygon[(i + 1) % polygon.count]
            n.x += (p0.y - p1.y) * (p0.z + p1.z)
            n.y += (p0.z - p1.z) * (p0.x + p1.x)
            n.z += (p0.x - p1.x) * (p0.y + p1.y)
        }
        return simd_length(n) < 1e-9 ? SIMD3(0, 1, 0) : simd_normalize(n)
    }

    /// Thins a trail so kept points are at least `minStep` apart (wider for a long walk, at most
    /// ~`maxPoints` kept); the first and last points are always kept.
    static func simplifyTrail(_ trail: [SIMD3<Float>], minStep: Float = 0.01, maxPoints: Int = trailMaxPoints) -> [SIMD3<Float>] {
        guard trail.count > 2 else { return trail }
        let step = max(minStep, RerunStats.pathLength(trail) / Float(maxPoints))
        var out = [trail[0]]
        var last = 0
        for i in 1..<(trail.count - 1) where simd_distance(trail[last], trail[i]) >= step {
            out.append(trail[i])
            last = i
        }
        out.append(trail[trail.count - 1])
        return out
    }

    /// Splits `count` points into `chunks` ranges sharing their boundary point; fewer points
    /// than chunks → fewer ranges, placed at the head end. Ranges are inclusive (`from...to`).
    static func trailChunks(count: Int, chunks: Int) -> [ClosedRange<Int>?] {
        var out = [ClosedRange<Int>?](repeating: nil, count: chunks)
        guard count >= 2 else { return out }
        let segments = count - 1
        let used = min(chunks, segments)
        for k in 0..<used {
            out[chunks - used + k] = (segments * k / used)...(segments * (k + 1) / used)
        }
        return out
    }

    // MARK: Layers

    static func buildTrail(_ trail: [SIMD3<Float>], style: RerunStyle, into out: inout [RerunLayer: RerunMesh]) {
        let simplified = simplifyTrail(trail)
        let n = simplified.count
        let ranges = trailChunks(count: n, chunks: RerunLayer.trailSteps.count)
        for (k, layer) in RerunLayer.trailSteps.enumerated() {
            guard let range = ranges[k] else { continue }
            addTube(&out[layer, default: RerunMesh()], simplified, range.lowerBound..<(range.upperBound + 1), radius: style.trailRadius)
        }
        guard n >= 2 else { return }
        var from = n - 1
        var covered: Float = 0
        while from > 0, covered < headLength {
            covered += simd_distance(simplified[from - 1], simplified[from])
            from -= 1
        }
        addTube(&out[.trailHead, default: RerunMesh()], simplified, from..<n, radius: style.trailHeadRadius, sides: 8)
    }

    static func buildCamera(_ frame: RerunFrame, style: RerunStyle, lens: RerunLens, into out: inout [RerunLayer: RerunMesh]) {
        for pose in frame.keyframes {
            addFrustumEdges(&out[.keyframes, default: RerunMesh()], pose, depth: keyframeDepth, edge: style.keyframeEdge, lens: lens)
        }
        if let camera = frame.camera {
            addFrustumEdges(&out[.frustum, default: RerunMesh()], camera, depth: frustumDepth, edge: style.frustumEdge, lens: lens)
        }
    }

    static func buildLivePoints(_ points: [SIMD3<Float>], style: RerunStyle, into mesh: inout RerunMesh) {
        for p in points { addOcta(&mesh, p, style.livePointRadius) }
    }

    static func fillLayer(_ kind: RerunPlaneKind) -> RerunLayer {
        switch kind { case .floor: .planeFloor; case .wall: .planeWall; default: .planeOther }
    }

    static func outlineLayer(_ kind: RerunPlaneKind) -> RerunLayer {
        switch kind { case .floor: .outlineFloor; case .wall: .outlineWall; default: .outlineOther }
    }

    /// Fills and outlines; a plane drawn as a photo keeps its outline only.
    static func buildPlanes(_ planes: [RerunPlane], style: RerunStyle, textured: (Int) -> Bool,
                            into out: inout [RerunLayer: RerunMesh]) {
        for plane in planes {
            if !textured(plane.id) { addFan(&out[fillLayer(plane.kind), default: RerunMesh()], plane.polygon) }
            addOutline(&out[outlineLayer(plane.kind), default: RerunMesh()], plane.polygon, halfWidth: style.outlineHalfWidth)
        }
    }

    static func buildAnchors(_ anchors: [RerunAnchor], style: RerunStyle, into mesh: inout RerunMesh) {
        for anchor in anchors {
            let up = anchor.pose.rotate(SIMD3(0, 1, 0))
            let centre = anchor.pose.position + up * 0.003
            addRing(&mesh, centre: centre, normal: up, radius: anchorRing, halfWidth: style.anchorHalfWidth)
            addRing(&mesh, centre: centre, normal: up, radius: anchorRing * 0.35, halfWidth: style.anchorHalfWidth * 0.8, segments: 20)
        }
    }

    /// The floor grid at height `y` over `bounds` plus a metre, snapped to the cell so it does
    /// not crawl, and the RGB axis gizmo at the world origin — where the session started.
    static func buildStage(bounds: (SIMD3<Float>, SIMD3<Float>), y: Float, style: RerunStyle, into out: inout [RerunLayer: RerunMesh]) {
        let (lo, hi) = bounds
        let margin: Float = 1
        let minX = Int(((lo.x - margin) / gridCell).rounded(.down)), maxX = Int(((hi.x + margin) / gridCell).rounded(.up))
        let minZ = Int(((lo.z - margin) / gridCell).rounded(.down)), maxZ = Int(((hi.z + margin) / gridCell).rounded(.up))
        let up = SIMD3<Float>(0, 1, 0)
        for i in minX...maxX {
            let major = i % gridMajorEvery == 0
            addRibbon(&out[major ? .gridMajor : .gridMinor, default: RerunMesh()],
                      SIMD3(Float(i) * gridCell, y, Float(minZ) * gridCell), SIMD3(Float(i) * gridCell, y, Float(maxZ) * gridCell),
                      normal: up, halfWidth: major ? style.gridMajorHalfWidth : style.gridMinorHalfWidth)
        }
        for k in minZ...maxZ {
            let major = k % gridMajorEvery == 0
            addRibbon(&out[major ? .gridMajor : .gridMinor, default: RerunMesh()],
                      SIMD3(Float(minX) * gridCell, y, Float(k) * gridCell), SIMD3(Float(maxX) * gridCell, y, Float(k) * gridCell),
                      normal: up, halfWidth: major ? style.gridMajorHalfWidth : style.gridMinorHalfWidth)
        }
        addSegment(&out[.axisX, default: RerunMesh()], .zero, SIMD3(axisLength, 0, 0), style.axisRadius)
        addSegment(&out[.axisY, default: RerunMesh()], .zero, SIMD3(0, axisLength, 0), style.axisRadius)
        addSegment(&out[.axisZ, default: RerunMesh()], .zero, SIMD3(0, 0, axisLength), style.axisRadius)
    }

    /// The lowest floor plane's height, else 1.3 m under the first camera pose, else 0 — to
    /// the centimetre, so it is a stable key.
    static func floorHeight(_ frame: RerunFrame) -> Float {
        let floors = frame.planes.filter { $0.kind == .floor && $0.polygon.count >= 3 }
        let raw: Float
        if let lowest = floors.map({ $0.polygon.reduce(0) { $0 + $1.y } / Float($0.polygon.count) }).min() {
            raw = lowest
        } else if let first = frame.trail.first {
            raw = first.y - 1.3
        } else {
            raw = 0
        }
        return (raw * 100).rounded() / 100
    }

    /// Bounds of what the camera frames: trail, planes, anchors. Points are left out — one far
    /// outlier would zoom the whole view out.
    static func contentBounds(_ frame: RerunFrame) -> (SIMD3<Float>, SIMD3<Float>)? {
        var lo = SIMD3<Float>(repeating: .greatestFiniteMagnitude)
        var hi = SIMD3<Float>(repeating: -.greatestFiniteMagnitude)
        var any = false
        func add(_ p: SIMD3<Float>) {
            any = true
            lo = simd_min(lo, p)
            hi = simd_max(hi, p)
        }
        frame.trail.forEach(add)
        frame.planes.forEach { $0.polygon.forEach(add) }
        frame.anchors.forEach { add($0.pose.position) }
        return any ? (lo, hi) : nil
    }

    /// Grid extent: the content bounds snapped to the grid.
    static func stageBounds(_ frame: RerunFrame) -> (SIMD3<Float>, SIMD3<Float>) {
        let (lo, hi) = contentBounds(frame) ?? (SIMD3(-1, 0, -2), SIMD3(1, 0, 0.5))
        return (SIMD3((lo.x / gridCell).rounded(.down) * gridCell, 0, (lo.z / gridCell).rounded(.down) * gridCell),
                SIMD3((hi.x / gridCell).rounded(.up) * gridCell, 0, (hi.z / gridCell).rounded(.up) * gridCell))
    }
}

/// One texel per map point: a mesh without per-vertex colour still draws each point in its own
/// colour, sampled nearest from a small texture.
enum RerunPointAtlas {
    static let size = 128
    static var capacity: Int { size * size }
    static let fallback: UInt32 = 0xFFB8_C2D6

    static func uv(of index: Int) -> SIMD2<Float> {
        SIMD2((Float(index % size) + 0.5) / Float(size), (Float(index / size) + 0.5) / Float(size))
    }

    /// RGBA8 bytes, row 0 first; points without colour take `fallback`.
    static func pixels(_ colors: ArraySlice<UInt32>?, count: Int, fallback: UInt32 = fallback) -> [UInt8] {
        var out = [UInt8](repeating: 0, count: size * size * 4)
        for i in 0..<min(count, capacity) {
            var c = colors.map { $0[$0.startIndex + i] } ?? 0
            if c == 0 { c = fallback }
            out[i * 4] = UInt8((c >> 16) & 0xFF)
            out[i * 4 + 1] = UInt8((c >> 8) & 0xFF)
            out[i * 4 + 2] = UInt8(c & 0xFF)
            out[i * 4 + 3] = UInt8((c >> 24) & 0xFF)
        }
        return out
    }
}

/// What the recording screen draws over the camera while it records: the path walked so far,
/// every 3 cm voxel the feature points filled, and the planes tracked now — the recorder's own
/// data, drawn in world space so it grows where it was found.
///
/// Sized to read on a phone filmed from about a metre away (the launch video's over-the-shoulder
/// take), so fixed centimetres rather than screen pixels: a trail you can follow across a table
/// and points you can see pile up on it.
enum RerunLiveGeometry {
    static let trailRadius: Float = 0.012
    /// The newest stretch of the path ends inside the lens; it is left out so the trail never
    /// fills the picture. It shows as soon as the phone has moved on.
    static let trailClearance: Float = 0.3
    static let pointRadius: Float = 0.008
    /// Points are drawn in fixed-size chunks: the cloud only grows, so only the last chunk
    /// is rebuilt as it fills.
    static let pointChunk = 1_500
    static let maxPoints = 30_000
    static let outlineHalfWidth: Float = 0.006

    /// The path minus its last `trailClearance` metres, as one tube; empty until the phone has
    /// walked past the clearance.
    static func trail(_ path: [SIMD3<Float>], clearance: Float = trailClearance) -> RerunMesh {
        var mesh = RerunMesh()
        guard let last = path.last else { return mesh }
        var end = path.count
        while end > 0, simd_distance(path[end - 1], last) < clearance { end -= 1 }
        guard end >= 2 else { return mesh }
        let kept = RerunGeometry.simplifyTrail(Array(path[0..<end]))
        RerunGeometry.addTube(&mesh, kept, 0..<kept.count, radius: trailRadius)
        return mesh
    }

    /// How many chunks `count` points fill, the last possibly partial; capped at `maxPoints`.
    static func pointChunks(count: Int) -> [Range<Int>] {
        let total = min(count, maxPoints)
        return stride(from: 0, to: total, by: pointChunk).map { $0..<min($0 + pointChunk, total) }
    }

    static func points(_ points: [SIMD3<Float>], range: Range<Int>) -> RerunMesh {
        var mesh = RerunMesh()
        for i in range where i < points.count { RerunGeometry.addOcta(&mesh, points[i], pointRadius) }
        return mesh
    }

    /// Fills and outlines, split floor-like (horizontal) and wall-like (everything else).
    static func planes(_ planes: [RerunCapturePlane]) -> (horizontalFill: RerunMesh, verticalFill: RerunMesh,
                                                         horizontalOutline: RerunMesh, verticalOutline: RerunMesh) {
        var hf = RerunMesh(), vf = RerunMesh(), ho = RerunMesh(), vo = RerunMesh()
        for plane in planes where plane.polygon.count >= 3 {
            let horizontal = plane.kind == .horizontalUpward || plane.kind == .horizontalDownward
            if horizontal {
                RerunGeometry.addFan(&hf, plane.polygon)
                RerunGeometry.addOutline(&ho, plane.polygon, halfWidth: outlineHalfWidth)
            } else {
                RerunGeometry.addFan(&vf, plane.polygon)
                RerunGeometry.addOutline(&vo, plane.polygon, halfWidth: outlineHalfWidth)
            }
        }
        return (hf, vf, ho, vo)
    }
}

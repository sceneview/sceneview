import Foundation
import simd

/*
 * The data behind the Rerun showcase: everything an AR session told the app, on one timeline,
 * so the replay can draw any instant of it. The iOS twin of Android's `ArDebugTrace` and
 * `RerunReplay.kt` (#4059) — same rules, same constants — reading the *same* bundled files:
 * a JSON-lines log in the Rerun bridge's wire format, a manifest, and a media archive.
 *
 * Pure Swift (Foundation + simd): the bundled showcase, a capture recorded on the phone and the
 * unit tests all feed the same `RerunTrace`, and the stage draws what `frameAt(_:)` returns.
 */

// MARK: - Pose

/// A rigid pose: translation plus a unit quaternion. Cameras look down their local -Z, local +Y
/// is the photo's up (the wire format's convention on both platforms).
struct RerunPose: Equatable, Sendable {
    var position: SIMD3<Float>
    var rotation: simd_quatf

    init(position: SIMD3<Float>, rotation: simd_quatf = simd_quatf(ix: 0, iy: 0, iz: 0, r: 1)) {
        self.position = position
        self.rotation = rotation
    }

    init(x: Float, y: Float, z: Float, qx: Float = 0, qy: Float = 0, qz: Float = 0, qw: Float = 1) {
        self.init(position: SIMD3(x, y, z), rotation: simd_quatf(ix: qx, iy: qy, iz: qz, r: qw))
    }

    /// Rotates the local vector `v` by this pose's rotation only.
    func rotate(_ v: SIMD3<Float>) -> SIMD3<Float> { rotation.act(v) }

    /// Rotates the local vector `v`, then translates it.
    func transform(_ v: SIMD3<Float>) -> SIMD3<Float> { rotation.act(v) + position }

    /// Where the camera looks.
    var forward: SIMD3<Float> { rotate(SIMD3(0, 0, -1)) }
}

// MARK: - Trace content

/// What kind of surface the session found — each is tinted differently.
enum RerunPlaneKind: String, Sendable {
    case floor = "horizontal_upward"
    case ceiling = "horizontal_downward"
    case wall = "vertical"
    case unknown

    init(wire: String?) { self = wire.flatMap(RerunPlaneKind.init(rawValue:)) ?? .unknown }
}

/// One detected plane at one instant: its boundary as a world-space convex polygon.
struct RerunPlane: Equatable, Sendable {
    var id: Int
    var kind: RerunPlaneKind
    var polygon: [SIMD3<Float>]
}

/// A placed anchor, and when it was first placed.
struct RerunAnchor: Equatable, Sendable {
    var id: Int
    var pose: RerunPose
    var placedAt: Float
}

/// Everything the replay draws at one instant ``time``.
struct RerunFrame: Sendable {
    var time: Float
    /// Camera positions up to ``time``, oldest first.
    var trail: [SIMD3<Float>]
    /// The camera pose at ``time``, `nil` before the first one.
    var camera: RerunPose?
    /// Every map point discovered up to ``time`` — the "map".
    var mapPoints: ArraySlice<SIMD3<Float>>
    /// One `0xFFRRGGBB` colour per map point, `0` for none; `nil` when the session has no colour.
    var mapPointColors: ArraySlice<UInt32>?
    /// The points the session was tracking at ``time``.
    var livePoints: [SIMD3<Float>]
    /// Which observation ``livePoints`` came from, `-1` for none: a cheap change key.
    var liveKey: Int
    var planes: [RerunPlane]
    var anchors: [RerunAnchor]
    /// Past camera poses spaced along the trail, drawn as photo frustums.
    var keyframes: [RerunPose]
    /// The image each keyframe was taken with, same order; empty without images.
    var keyframeImages: [String?]
    /// The camera image in force at ``time``.
    var image: String?

    var mapPointCount: Int { mapPoints.count }
}

// MARK: - Trace

/// The session timeline. Append-only while it is built; times are seconds since the first
/// event. Bounded like Android's: poses thinned by motion and capped, points merged into a
/// voxel map, planes and anchors one snapshot per change.
///
/// Built once (the log parsed, or a capture replayed), then only read — which is why it may
/// cross from the loading task to the main actor.
final class RerunTrace: @unchecked Sendable {
    static let poseMinStep: Float = 0.005
    /// cos(½·1°): a turn smaller than a degree does not extend the trail either.
    static let poseMinTurn: Float = 0.99996
    static let maxPoses = 20_000
    static let pointVoxel: Float = 0.03
    static let maxMapPoints = 12_000
    static let minPointConfidence: Float = 0.2
    static let maxObservations = 6_000
    /// A point cloud older than this no longer counts as "what the camera sees now".
    static let livePointsWindow: Float = 1.5
    static let defaultKeyframeSpacing: Float = 0.6
    static let maxKeyframes = 48

    private var originNanos: Int64?

    private(set) var poseTimes: [Float] = []
    private(set) var poses: [RerunPose] = []

    private(set) var points: [SIMD3<Float>] = []
    private var pointFirstSeen: [Float] = []
    private(set) var pointColors: [UInt32] = []
    private(set) var hasPointColors = false
    private var voxelIndex: [Int64: Int] = [:]

    private var observationTimes: [Float] = []
    private var observations: [[Int]] = []

    private var planeOrder: [Int] = []
    private var planeHistory: [Int: [(Float, RerunPlane)]] = [:]
    private var anchorOrder: [Int] = []
    private var anchorHistory: [Int: [(Float, RerunAnchor)]] = [:]

    private(set) var imageTimes: [Float] = []
    private(set) var imagePaths: [String] = []

    /// Path between two history frustums; a replay with images draws them closer.
    var keyframeSpacing: Float = RerunTrace.defaultKeyframeSpacing

    /// Seconds from the first event to the last one.
    private(set) var duration: Float = 0

    var poseCount: Int { poses.count }
    var mapPointCount: Int { points.count }
    var imageCount: Int { imagePaths.count }
    var isEmpty: Bool { poses.isEmpty && points.isEmpty && planeHistory.isEmpty }

    /// Point-cloud observations recorded, in time order.
    var observationCount: Int { observations.count }

    /// Time (seconds) of observation `index`.
    func observationTime(_ index: Int) -> Float { observationTimes[index] }

    /// The map points observation `index` saw, as indices into the map (the order of
    /// ``RerunFrame/mapPoints``); empty once a long session has forgotten it.
    func observationPoints(_ index: Int) -> [Int] { observations[index] }

    /// Index of the image in force at `time` — the latest at or before it — or -1.
    func imageIndexAt(_ time: Float) -> Int { Self.upperBound(imageTimes, time) - 1 }

    /// Seconds since the first event for an event at `nanos`; the first call sets the origin.
    func secondsOf(_ nanos: Int64) -> Float {
        if originNanos == nil { originNanos = nanos }
        return Float(Double(max(0, nanos - originNanos!)) / 1e9)
    }

    private func touch(_ time: Float) {
        if time > duration { duration = time }
    }

    /// Adds a camera pose; one within 5 mm and 1° of the last kept one only moves time forward.
    func addPose(_ nanos: Int64, _ pose: RerunPose) {
        let t = secondsOf(nanos)
        if let last = poses.last {
            if t <= poseTimes[poses.count - 1] + 1e-6 {
                // Out-of-order or duplicate timestamp: keep the newest pose, never go back.
                poses[poses.count - 1] = pose
                touch(t)
                return
            }
            if !Self.movedEnough(last, pose) {
                touch(t)
                return
            }
        }
        if poses.count >= Self.maxPoses { thinPoses() }
        poseTimes.append(t)
        poses.append(pose)
        touch(t)
    }

    static func movedEnough(_ a: RerunPose, _ b: RerunPose) -> Bool {
        let d = a.position - b.position
        if simd_dot(d, d) >= poseMinStep * poseMinStep { return true }
        // |dot| of two unit quaternions is cos(half the angle between them).
        return abs(simd_dot(a.rotation.vector, b.rotation.vector)) < poseMinTurn
    }

    /// Drops every other pose (keeping the last), halving the trace at the cap.
    private func thinPoses() {
        var keptPoses: [RerunPose] = []
        var keptTimes: [Float] = []
        keptPoses.reserveCapacity(poses.count / 2 + 1)
        for i in poses.indices where i % 2 == 0 || i == poses.count - 1 {
            keptPoses.append(poses[i])
            keptTimes.append(poseTimes[i])
        }
        poses = keptPoses
        poseTimes = keptTimes
    }

    /// Adds one point-cloud observation. Each point merges into the 3 cm voxel map (a voxel keeps
    /// the first colour it is given), and the observation remembers which voxels it saw.
    func addPoints(_ nanos: Int64, positions: [SIMD3<Float>], confidences: [Float]? = nil, colors: [UInt32]? = nil) {
        let t = secondsOf(nanos)
        var seen: [Int] = []
        seen.reserveCapacity(positions.count)
        for (i, p) in positions.enumerated() {
            if let confidences, i < confidences.count, confidences[i] < Self.minPointConfidence { continue }
            guard p.x.isFinite, p.y.isFinite, p.z.isFinite else { continue }
            let key = Self.voxelKey(p)
            var index = voxelIndex[key] ?? -1
            if index < 0, points.count < Self.maxMapPoints {
                index = points.count
                points.append(p)
                pointFirstSeen.append(t)
                pointColors.append(0)
                voxelIndex[key] = index
            }
            guard index >= 0 else { continue }
            seen.append(index)
            if let colors, i < colors.count, colors[i] != 0, pointColors[index] == 0 {
                pointColors[index] = colors[i]
                hasPointColors = true
            }
        }
        if observations.count >= Self.maxObservations {
            // Keep the timeline but forget what the oldest half of the observations saw.
            for i in 0..<(observations.count / 2) { observations[i] = [] }
        }
        observationTimes.append(t)
        observations.append(seen)
        touch(t)
    }

    /// Adds a snapshot of plane `id`. An empty polygon records that the plane went away.
    func addPlane(_ nanos: Int64, id: Int, kind: RerunPlaneKind, polygon: [SIMD3<Float>]) {
        let t = secondsOf(nanos)
        if planeHistory[id] == nil { planeOrder.append(id) }
        var history = planeHistory[id] ?? []
        if let last = history.last?.1, last.kind == kind, last.polygon == polygon { return }
        history.append((t, RerunPlane(id: id, kind: kind, polygon: polygon)))
        planeHistory[id] = history
        touch(t)
    }

    /// Adds (or moves) anchor `id`.
    func addAnchor(_ nanos: Int64, id: Int, pose: RerunPose) {
        let t = secondsOf(nanos)
        if anchorHistory[id] == nil { anchorOrder.append(id) }
        var history = anchorHistory[id] ?? []
        if let last = history.last?.1, last.pose == pose { return }
        history.append((t, RerunAnchor(id: id, pose: pose, placedAt: history.first?.0 ?? t)))
        anchorHistory[id] = history
        touch(t)
    }

    /// Adds the camera image `path` taken at `nanos`.
    func addImage(_ nanos: Int64, path: String) {
        let t = secondsOf(nanos)
        if let last = imageTimes.last, t < last { return } // never back in time
        imageTimes.append(t)
        imagePaths.append(path)
        touch(t)
    }

    /// The whole scene as it stood at `time` (clamped to the trace).
    func frameAt(_ time: Float) -> RerunFrame {
        let t = min(max(time, 0), duration)
        let poseEnd = Self.upperBound(poseTimes, t)
        let trail = poses[0..<poseEnd].map(\.position)
        let camera = poseEnd > 0 ? poses[poseEnd - 1] : nil
        let mapEnd = Self.upperBound(pointFirstSeen, t)

        let liveIndex = latestObservation(t)
        var live: [SIMD3<Float>] = []
        if liveIndex >= 0 {
            for index in observations[liveIndex] where index < mapEnd { live.append(points[index]) }
        }
        let planes = planeOrder.compactMap { id -> RerunPlane? in
            guard let plane = planeHistory[id]?.last(where: { $0.0 <= t })?.1, plane.polygon.count >= 3 else { return nil }
            return plane
        }
        let anchors = anchorOrder.compactMap { anchorHistory[$0]?.last(where: { $0.0 <= t })?.1 }
        let keyframeIndices = keyframes(poseEnd)
        let keyframeImages: [String?] = imagePaths.isEmpty ? [] : keyframeIndices.map { i in
            let index = imageIndexAt(poseTimes[i])
            return index >= 0 ? imagePaths[index] : nil
        }
        let image = imageIndexAt(t)
        return RerunFrame(
            time: t,
            trail: trail,
            camera: camera,
            mapPoints: points[0..<mapEnd],
            mapPointColors: hasPointColors ? pointColors[0..<mapEnd] : nil,
            livePoints: live,
            liveKey: liveIndex,
            planes: planes,
            anchors: anchors,
            keyframes: keyframeIndices.map { poses[$0] },
            keyframeImages: keyframeImages,
            image: image >= 0 ? imagePaths[image] : nil
        )
    }

    /// Indices of the poses among the first `end` spaced ``keyframeSpacing`` apart along the path,
    /// the newest excluded (it is the live frustum). Past ``maxKeyframes`` the spacing widens.
    private func keyframes(_ end: Int) -> [Int] {
        guard end >= 2 else { return [] }
        var length: Float = 0
        for i in 1..<end { length += simd_distance(poses[i - 1].position, poses[i].position) }
        let spacing = max(keyframeSpacing, length / Float(Self.maxKeyframes))
        var out: [Int] = []
        var travelled = spacing // the first pose is a keyframe
        for i in 0..<(end - 1) {
            if i > 0 { travelled += simd_distance(poses[i - 1].position, poses[i].position) }
            if travelled >= spacing {
                out.append(i)
                travelled = 0
            }
        }
        // Never draw a history frustum on top of the live one.
        let head = poses[end - 1].position
        while let last = out.last, simd_distance(poses[last].position, head) < spacing * 0.5 { out.removeLast() }
        return out
    }

    /// Index of the observation in force at `t` — the latest at or before it, if recent — or -1.
    private func latestObservation(_ t: Float) -> Int {
        let lo = Self.upperBound(observationTimes, t)
        guard lo > 0 else { return -1 }
        return t - observationTimes[lo - 1] <= Self.livePointsWindow ? lo - 1 : -1
    }

    /// Packs the voxel of `p` into one key: 21 signed bits per axis.
    /// A coordinate too large for an `Int64` (a damaged file) is clamped instead of trapping.
    static func voxelKey(_ p: SIMD3<Float>) -> Int64 {
        func cell(_ v: Float) -> Int64 {
            let c = (v / pointVoxel).rounded(.down)
            return Int64(c.isFinite ? min(max(c, -0x1p40), 0x1p40) : 0) & 0x1FFFFF
        }
        return (cell(p.x) << 42) | (cell(p.y) << 21) | cell(p.z)
    }

    /// Number of leading entries of the sorted `values` that are `<= t`.
    static func upperBound(_ values: [Float], _ t: Float) -> Int {
        var lo = 0
        var hi = values.count
        while lo < hi {
            let mid = (lo + hi) / 2
            if values[mid] <= t { lo = mid + 1 } else { hi = mid }
        }
        return lo
    }
}

// MARK: - Wire-format log

/// One parsed wire-format event, stamped with the log's own `t` in nanoseconds.
enum RerunEvent {
    case cameraPose(nanos: Int64, pose: RerunPose)
    case points(nanos: Int64, positions: [SIMD3<Float>], confidences: [Float]?, colors: [UInt32]?)
    case plane(nanos: Int64, id: Int, kind: RerunPlaneKind, polygon: [SIMD3<Float>])
    case anchor(nanos: Int64, id: Int, pose: RerunPose)
    case image(nanos: Int64, path: String)

    func apply(to trace: RerunTrace) {
        switch self {
        case let .cameraPose(nanos, pose): trace.addPose(nanos, pose)
        case let .points(nanos, positions, confidences, colors):
            trace.addPoints(nanos, positions: positions, confidences: confidences, colors: colors)
        case let .plane(nanos, id, kind, polygon): trace.addPlane(nanos, id: id, kind: kind, polygon: polygon)
        case let .anchor(nanos, id, pose): trace.addAnchor(nanos, id: id, pose: pose)
        case let .image(nanos, path): trace.addImage(nanos, path: path)
        }
    }
}

/// Reads a session log in the Rerun bridge's wire format — the JSON lines `RerunWireFormat`
/// streams — like Android's `parseArDebugLog`: lines the replay does not draw, and malformed
/// lines, are skipped rather than failing the log (a log cut mid-line must still open).
enum RerunLog {
    static func parse(_ data: Data) -> [RerunEvent] {
        var events: [RerunEvent] = []
        var start = data.startIndex
        let newline = UInt8(ascii: "\n")
        while start < data.endIndex {
            let end = data[start...].firstIndex(of: newline) ?? data.endIndex
            if let event = parseLine(data[start..<end]) { events.append(event) }
            start = end < data.endIndex ? data.index(after: end) : end
        }
        return events
    }

    static func parseLine(_ line: String) -> RerunEvent? { parseLine(Data(line.utf8)) }

    static func parseLine(_ line: Data) -> RerunEvent? {
        guard let first = line.first(where: { $0 != 0x20 && $0 != 0x09 && $0 != 0x0D }), first == UInt8(ascii: "{"),
              let obj = (try? JSONSerialization.jsonObject(with: line)) as? [String: Any],
              let nanos = (obj["t"] as? NSNumber)?.int64Value,
              let type = obj["type"] as? String
        else { return nil }
        let entity = obj["entity"] as? String
        switch type {
        case "camera_pose":
            return pose(obj).map { .cameraPose(nanos: nanos, pose: $0) }
        case "anchor":
            return pose(obj).map { .anchor(nanos: nanos, id: entityId(entity) ?? 0, pose: $0) }
        case "point_cloud":
            guard let positions = vectors(obj["positions"]) else { return nil }
            let confidences = (obj["confidences"] as? [Any])?.compactMap { ($0 as? NSNumber)?.floatValue }
            let colors = (obj["colors"] as? [Any])?.compactMap { entry -> UInt32? in
                guard let rgb = floats(entry), rgb.count == 3 else { return nil }
                // A negative channel marks a point without a colour (as Android reads it).
                return rgb.contains { $0 < 0 } ? 0 : packRGB(rgb)
            }
            return .points(
                nanos: nanos,
                positions: positions,
                confidences: confidences?.count == positions.count ? confidences : nil,
                colors: colors?.count == positions.count ? colors : nil
            )
        case "plane":
            return .plane(
                nanos: nanos,
                id: entityId(entity) ?? 0,
                kind: RerunPlaneKind(wire: obj["kind"] as? String),
                polygon: vectors(obj["polygon"]) ?? []
            )
        case "image":
            guard let path = obj["path"] as? String, !path.trimmingCharacters(in: .whitespaces).isEmpty else { return nil }
            return .image(nanos: nanos, path: path)
        default:
            return nil
        }
    }

    /// `[r, g, b]` in 0…255 → `0xFFRRGGBB`, each channel clamped.
    static func packRGB(_ rgb: [Float]) -> UInt32 {
        func channel(_ v: Float) -> UInt32 { UInt32(min(max(Int(v), 0), 255)) }
        return 0xFF00_0000 | channel(rgb[0]) << 16 | channel(rgb[1]) << 8 | channel(rgb[2])
    }

    /// `world/planes/42` → 42. Non-numeric ids hash to a stable int (Java's `String.hashCode`,
    /// so both platforms agree).
    static func entityId(_ entity: String?) -> Int? {
        guard let last = entity?.split(separator: "/").last.map(String.init), !last.isEmpty else { return nil }
        if let id = Int(last) { return id }
        var hash: Int32 = 0
        for unit in last.utf16 { hash = hash &* 31 &+ Int32(unit) }
        return Int(hash)
    }

    private static func pose(_ obj: [String: Any]) -> RerunPose? {
        guard let t = floats(obj["translation"]), t.count == 3 else { return nil }
        let q = floats(obj["quaternion"]).flatMap { $0.count == 4 ? $0 : nil } ?? [0, 0, 0, 1]
        return RerunPose(x: t[0], y: t[1], z: t[2], qx: q[0], qy: q[1], qz: q[2], qw: q[3])
    }

    private static func floats(_ value: Any?) -> [Float]? {
        guard let array = value as? [Any] else { return nil }
        var out: [Float] = []
        out.reserveCapacity(array.count)
        for entry in array {
            guard let number = entry as? NSNumber else { return nil }
            out.append(number.floatValue)
        }
        return out
    }

    /// `[[x,y,z], …]` → vectors; entries that are not three numbers are dropped.
    private static func vectors(_ value: Any?) -> [SIMD3<Float>]? {
        guard let array = value as? [Any] else { return nil }
        return array.compactMap { entry in
            guard let xyz = floats(entry), xyz.count == 3 else { return nil }
            return SIMD3(xyz[0], xyz[1], xyz[2])
        }
    }
}

extension RerunTrace {
    /// A trace holding the whole log at once — a finished take, for scrubbing.
    static func of(_ events: [RerunEvent], keyframeSpacing: Float = RerunTrace.defaultKeyframeSpacing) -> RerunTrace {
        let trace = RerunTrace()
        trace.keyframeSpacing = keyframeSpacing
        for event in events { event.apply(to: trace) }
        return trace
    }
}

// MARK: - Manifest

/// The camera's lens as frustum proportions: the half-extents of the image plane at one metre.
struct RerunLens: Equatable, Sendable {
    var halfWidthPerDepth: Float
    var halfHeightPerDepth: Float

    /// The generic portrait phone drawn when there are no intrinsics.
    static let `default` = RerunLens(halfWidthPerDepth: 0.46, halfHeightPerDepth: 0.60)

    /// From pinhole intrinsics: an image `width` × `height` pixels, focal lengths `fx`, `fy`.
    static func of(width: Float, height: Float, fx: Float, fy: Float) -> RerunLens? {
        guard min(width, height, fx, fy) > 0 else { return nil }
        return RerunLens(halfWidthPerDepth: width / 2 / fx, halfHeightPerDepth: height / 2 / fy)
    }
}

/// A plane's photo texture: `origin` is the world position of texel (0, 0), `u` and `v` its
/// two full edges in world space.
struct RerunPlaneTexture: Equatable, Sendable {
    var planeId: Int
    var path: String
    var origin: SIMD3<Float>
    var u: SIMD3<Float>
    var v: SIMD3<Float>

    /// Texture coordinates of the world point `p`, unclamped.
    func uv(of p: SIMD3<Float>) -> SIMD2<Float> {
        let d = p - origin
        let uu = simd_dot(u, u)
        let vv = simd_dot(v, v)
        return SIMD2(uu > 1e-9 ? simd_dot(d, u) / uu : 0, vv > 1e-9 ? simd_dot(d, v) / vv : 0)
    }
}

/// Where one photo sits in the media archive.
struct RerunMediaSpan: Equatable, Sendable {
    var offset: Int
    var length: Int
}

/// What the manifest says about a recorded session.
struct RerunManifest: Sendable {
    struct Intrinsics: Equatable, Sendable {
        var width: Float
        var height: Float
        var fx: Float
        var fy: Float
        var cx: Float
        var cy: Float
    }

    var intrinsics: Intrinsics?
    var lens: RerunLens
    var frameRate: Float
    var frameCount: Int
    var floorY: Float?
    var textures: [RerunPlaneTexture]
    var media: [String: RerunMediaSpan]

    func texture(for planeId: Int) -> RerunPlaneTexture? { textures.first { $0.planeId == planeId } }

    /// Parses a manifest; `nil` when it is not one. Unknown keys are ignored.
    static func parse(_ data: Data) -> RerunManifest? {
        guard let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { return nil }
        func float(_ obj: [String: Any]?, _ key: String) -> Float { (obj?[key] as? NSNumber)?.floatValue ?? 0 }
        func vec(_ obj: [String: Any], _ key: String) -> SIMD3<Float>? {
            guard let array = obj[key] as? [NSNumber], array.count == 3 else { return nil }
            return SIMD3(array[0].floatValue, array[1].floatValue, array[2].floatValue)
        }
        let intrinsicsObject = root["intrinsics"] as? [String: Any]
        let intrinsics = intrinsicsObject.map {
            Intrinsics(width: float($0, "width"), height: float($0, "height"), fx: float($0, "fx"),
                       fy: float($0, "fy"), cx: float($0, "cx"), cy: float($0, "cy"))
        }
        let lens = intrinsics.flatMap { RerunLens.of(width: $0.width, height: $0.height, fx: $0.fx, fy: $0.fy) } ?? .default
        let textures = (root["textures"] as? [[String: Any]] ?? []).compactMap { obj -> RerunPlaneTexture? in
            guard let id = (obj["plane"] as? NSNumber)?.intValue, let path = obj["path"] as? String,
                  let origin = vec(obj, "origin"), let u = vec(obj, "u"), let v = vec(obj, "v") else { return nil }
            return RerunPlaneTexture(planeId: id, path: path, origin: origin, u: u, v: v)
        }
        var media: [String: RerunMediaSpan] = [:]
        for obj in root["media"] as? [[String: Any]] ?? [] {
            guard let path = obj["path"] as? String, let offset = (obj["offset"] as? NSNumber)?.intValue,
                  let length = (obj["length"] as? NSNumber)?.intValue, offset >= 0, length > 0 else { continue }
            media[path] = RerunMediaSpan(offset: offset, length: length)
        }
        let rate = float(root, "frameRate")
        return RerunManifest(
            intrinsics: intrinsics,
            lens: lens,
            frameRate: rate > 0 ? rate : 10,
            frameCount: (root["frames"] as? NSNumber)?.intValue ?? 0,
            floorY: (root["floorY"] as? NSNumber)?.floatValue,
            textures: textures,
            media: media
        )
    }
}

// MARK: - Pack

/// A recorded session ready to replay: the trace, its manifest and the media archive the
/// manifest indexes. The bundled showcase and a capture recorded on the phone are both packs.
struct RerunPack: Sendable {
    enum LoadError: Error { case missing(String), unreadableManifest }

    /// Keyframe spacing along a replay's path: close enough for a strip of photos.
    static let replayKeyframeSpacing: Float = 0.38

    var title: String
    var manifest: RerunManifest
    var trace: RerunTrace
    var media: Data
    /// `true` for the session bundled with the app.
    var isShowcase: Bool

    /// Photo `path`'s bytes, `nil` when the manifest does not index it.
    func bytes(for path: String) -> Data? {
        guard let span = manifest.media[path], span.offset + span.length <= media.count else { return nil }
        let start = media.startIndex + span.offset
        return media.subdata(in: start..<(start + span.length))
    }

    static func load(manifest: Data, log: Data, media: Data, title: String, isShowcase: Bool = false) throws -> RerunPack {
        guard let parsed = RerunManifest.parse(manifest) else { throw LoadError.unreadableManifest }
        let trace = RerunTrace.of(RerunLog.parse(log), keyframeSpacing: replayKeyframeSpacing)
        return RerunPack(title: title, manifest: parsed, trace: trace, media: media, isShowcase: isShowcase)
    }

    /// The session bundled with the app — the very files Android ships (`rerun/showcase/`).
    static func loadShowcase(bundle: Bundle = .main) throws -> RerunPack {
        func data(_ name: String, _ ext: String) throws -> Data {
            guard let url = bundle.url(forResource: name, withExtension: ext, subdirectory: "showcase")
                ?? bundle.url(forResource: name, withExtension: ext) else { throw LoadError.missing("\(name).\(ext)") }
            return try Data(contentsOf: url, options: .mappedIfSafe)
        }
        return try load(
            manifest: data("showcase-manifest", "json"),
            log: data("showcase-session", "jsonl"),
            media: data("showcase-media", "bin"),
            title: "Recorded room",
            isShowcase: true
        )
    }
}

// MARK: - Playback

/// The replay's clock: play, pause, scrub, and a loop that holds the last frame a moment.
struct RerunPlayback: Equatable, Sendable {
    static let loopHold: Float = 2.5
    private static let endEpsilon: Float = 0.05

    var duration: Float
    private(set) var cursor: Float = 0
    private(set) var playing = false
    var loops = true
    private var hold: Float = 0

    init(duration: Float) { self.duration = duration }

    /// Scrubbing pauses on the frame the finger is on.
    mutating func scrub(to seconds: Float) {
        playing = false
        hold = 0
        cursor = min(max(seconds, 0), duration)
    }

    mutating func playFromStart() {
        cursor = 0
        hold = 0
        playing = true
    }

    /// Paused → play on (from the start if at the end). Playing → pause.
    mutating func togglePlay() {
        if playing {
            playing = false
        } else {
            if cursor >= duration - Self.endEpsilon { cursor = 0 }
            playing = true
        }
    }

    /// Advances by `delta` seconds; at the end, holds ``loopHold`` then starts over.
    mutating func tick(_ delta: Float) {
        guard playing else { return }
        let step = min(max(delta, 0), 0.25)
        if loops && cursor >= duration {
            hold += step
            if hold >= Self.loopHold {
                hold = 0
                cursor = 0
            }
            return
        }
        let next = cursor + step
        if next < duration {
            cursor = next
        } else if loops {
            cursor = duration
        } else {
            cursor = duration
            playing = false
        }
    }
}

// MARK: - Figures

/// The figures the HUD shows: time, path walked, and what the session has mapped.
struct RerunStats: Equatable, Sendable {
    var time: Float = 0
    var pathMetres: Float = 0
    var mapPoints = 0
    var planes = 0
    var anchors = 0
    var tracking = false

    init() {}

    init(frame: RerunFrame) {
        time = frame.time
        pathMetres = Self.pathLength(frame.trail)
        mapPoints = frame.mapPointCount
        planes = frame.planes.count
        anchors = frame.anchors.count
        tracking = frame.camera != nil
    }

    static func pathLength(_ path: [SIMD3<Float>]) -> Float {
        guard path.count > 1 else { return 0 }
        var length: Float = 0
        for i in 1..<path.count { length += simd_distance(path[i - 1], path[i]) }
        return length
    }
}

/// Text of the replay's chrome, as plain functions so the wording is testable.
enum RerunFormat {
    /// `72.4` → `1:12`. Negative and non-finite inputs read as zero.
    static func clock(_ seconds: Float) -> String {
        let total = seconds.isFinite && seconds > 0 ? Int(seconds) : 0
        return String(format: "%d:%02d", total / 60, total % 60)
    }

    /// `0.42` → `42 cm`, `14.236` → `14.2 m`.
    static func distance(_ metres: Float) -> String {
        if !metres.isFinite || metres <= 0 { return "0 m" }
        if metres < 1 { return "\(Int(metres * 100)) cm" }
        if metres < 100 { return String(format: "%.1f m", metres) }
        return "\(count(Int(metres))) m"
    }

    /// `3812` → `3,812`.
    static func count(_ value: Int) -> String {
        let formatter = NumberFormatter()
        formatter.locale = Locale(identifier: "en_US")
        formatter.numberStyle = .decimal
        return formatter.string(from: NSNumber(value: value)) ?? "\(value)"
    }

    /// For a narrow figure: `812` → `812`, `4_812` → `4.8k`, `12_400` → `12k`.
    static func compactCount(_ value: Int) -> String {
        if value < 1_000 { return "\(max(value, 0))" }
        if value < 10_000 { return String(format: "%.1fk", Float(value / 100) / 10) }
        return "\(value / 1_000)k"
    }
}

/// Which camera frames the filmstrip shows: `slots` evenly spread over `count`, ends included.
func rerunFilmstripFrames(count: Int, slots: Int) -> [Int] {
    guard count > 0, slots > 0 else { return [] }
    if count <= slots { return Array(0..<count) }
    if slots == 1 { return [0] }
    return (0..<slots).map { k in (k * (count - 1) + (slots - 1) / 2) / (slots - 1) }
}

/// Frames per second, averaged over half a second so the figure reads rather than flickers.
struct RerunFpsMeter: Sendable {
    var window: Double = 0.5
    private var windowStart: Double?
    private var frames = 0
    private(set) var fps = 0

    init() {}

    /// Counts a frame at `seconds`; returns `true` when ``fps`` changed.
    mutating func tick(_ seconds: Double) -> Bool {
        guard let start = windowStart else {
            windowStart = seconds
            return false
        }
        frames += 1
        let elapsed = seconds - start
        guard elapsed >= window else { return false }
        let next = Int((Double(frames) / elapsed).rounded())
        frames = 0
        windowStart = seconds
        defer { fps = next }
        return next != fps
    }
}

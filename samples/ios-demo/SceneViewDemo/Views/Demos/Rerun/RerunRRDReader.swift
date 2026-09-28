import CoreGraphics
import Foundation
import ImageIO
import simd

/// Reads a Rerun `.rrd` recording written by ``RerunRRDWriter`` back into the app, so a
/// space exported from the replay opens in the replay again. Pure Swift, like the writer:
/// no Rerun SDK, no protobuf, Arrow or FlatBuffers library.
///
/// The output is a native capture (``RerunCapturePack``) — the manifest, the JSON-lines
/// session and the media archive the recorder produces — so
/// ``RerunPack/load(manifest:log:media:title:isShowcase:)`` replays it exactly like a capture
/// made on the phone. What comes back, entity by entity:
///
/// - `world/camera` — every pose at its time (`camera_pose`), the lens (`Pinhole`, as the
///   manifest's `intrinsics`) and each photo at its time (`image`; the bytes go into the
///   media archive and the manifest indexes them, as the recorder does). The writer adds
///   one pose row per photo so Rerun shows the camera where the photo was taken; those rows
///   are recognised and dropped (see ``pathPoses(_:path:photoTimes:)``), which gives back
///   the path the writer was given.
/// - `world/points` — the coloured map; `world/points/live` — what the camera saw over time,
///   one `point_cloud` per row at its time, coloured from the map.
/// - `world/planes/<id>` — the polygon from the outline (without its closing point), the
///   kind from the outline's colour, and the plane photo when the `Mesh3D` carries one: the
///   texels become a PNG in the media archive and the texture frame (`origin`, `u`, `v`) is
///   fitted back from the vertices and their texture coordinates. A plane whose texture
///   does not map back cleanly — `u` and `v` not orthogonal, or texels other than 8-bit
///   RGB/RGBA — comes back untextured rather than wrongly textured.
/// - `world/anchors/<id>` — each anchor's pose.
/// - `world/dense` — a `.svscan` v2's dense cloud (static `Points3D`: positions, colours, one
///   radius): back to an SVPC blob at `dense/points.bin` and the manifest's `dense` entry, the
///   voxel twice the radius. Rerun's `Points3D` has no normals, so they do not survive the trip.
///
/// Planes and anchors are static, emitted at the start of the session (time 0 of the `time`
/// timeline, the session's first instant). The points replay as they were seen when the file
/// has `world/points/live` (this app's exports since #4093, and Android's since #4080): the
/// map grows and the live points come back, as in the original capture; map points no
/// sighting shows are there from the start. A file without it (an older export, another
/// writer) only has the final map, shown whole from the first frame — nothing pretends the
/// map grew when that history is not in the file; the replay's "live" points (what the
/// camera saw in the last 1.5 s) are then the whole map during the first 1.5 s.
///
/// Not in the file, so not recovered: the seconds the session ran after its last pose,
/// photo or sighting, when each anchor was placed, the lens' original resolution (the
/// `Pinhole` is in photo pixels; its proportions are exact) and plane photos beyond the
/// writer's 512-pixel texture.
///
/// Anything malformed — another magic, a compressed stream, a truncated message, an offset
/// out of range, a component in an unexpected Arrow layout — throws a ``Failure``.
enum RerunRRDReader {
    enum Failure: Error, Equatable {
        /// The data does not start with `RRF2`: not an `.rrd`, or one from before Rerun's
        /// protobuf framing.
        case notAnRRD
        /// An `.rrd` of another major Rerun version than the writer's.
        case unsupportedVersion(major: Int, minor: Int, patch: Int)
        /// An LZ4-compressed stream or chunk. The writer never compresses.
        case compressed
        /// A message serializer other than protobuf.
        case unsupportedSerializer(Int)
        /// The data ends inside a message, or an offset points past its end.
        case truncated
        /// Bytes that are not what the format says they are.
        case malformed(String)
        /// A component the replay needs, in an Arrow layout the writer never emits.
        case unexpectedLayout(String)
        /// A valid `.rrd` with nothing to replay: no camera pose, map point or plane.
        case nothingToReplay
    }

    /// A decoded recording: its name and the capture the replay loads.
    struct Recording: Sendable, Equatable {
        /// The recording's name (`RecordingInfo`), `nil` when the file has none.
        var title: String?
        var pack: RerunCapturePack
    }

    /// The capture an `.rrd` written by ``RerunRRDWriter`` describes.
    static func capturePack(from data: Data) throws -> RerunCapturePack {
        try recording(from: data).pack
    }

    /// The capture and the recording's name.
    static func recording(from data: Data) throws -> Recording {
        try Contents(chunks: RRDStream.chunks(in: data)).recording()
    }

    /// The camera path without the writer's keyframe rows.
    ///
    /// ``RerunRRDWriter`` logs the path's poses and, merged in, one pose per photo at the
    /// photo's time, sorted after the path pose on a tie. The replay places its photo
    /// frustums itself, so those rows would only add poses that were never on the path (and
    /// shift the time of the pose they copy). They are the last rows at each photo time; they
    /// are dropped when what remains retraces `world/camera_path` exactly. Failing that, the
    /// path is matched from its end — a keyframe row never comes after the path pose it
    /// copies. When neither fits (a file from another writer), every row is a pose.
    static func pathPoses(_ rows: [PoseRow], path: [SIMD3<Float>], photoTimes: [Int64]) -> [PoseRow] {
        guard path.count >= 2, rows.count > path.count else { return rows }
        var pending = Dictionary(photoTimes.map { ($0, 1) }, uniquingKeysWith: +)
        var dropped = Set<Int>()
        for index in rows.indices.reversed() {
            if let count = pending[rows[index].time], count > 0 {
                dropped.insert(index)
                pending[rows[index].time] = count - 1
            }
        }
        let kept = rows.indices.filter { !dropped.contains($0) }.map { rows[$0] }
        if kept.count == path.count, zip(kept, path).allSatisfy({ $0.position == $1 }) { return kept }

        var next = path.count - 1
        var matched = [Bool](repeating: false, count: rows.count)
        for index in rows.indices.reversed() where next >= 0 && rows[index].position == path[next] {
            matched[index] = true
            next -= 1
        }
        return next < 0 ? rows.indices.filter { matched[$0] }.map { rows[$0] } : rows
    }

    /// One camera pose on the `time` timeline, in nanoseconds.
    struct PoseRow: Equatable, Sendable {
        var time: Int64
        var position: SIMD3<Float>
        var orientation: simd_quatf
    }
}

// MARK: - Contents → capture

extension RerunRRDReader {
    /// What the chunks say about the space, gathered entity by entity. Static data follows
    /// Rerun's rule: the last value logged wins.
    fileprivate struct Contents {
        struct Photo {
            var time: Int64
            var order: Int
            var data: Data?
            var mediaType: String?
        }

        /// One row of `world/points/live`: what the camera saw at `time`.
        struct Sighting {
            var time: Int64
            var order: Int
            var positions: [Float]
        }

        struct Plane {
            var name: String
            var outline: [SIMD3<Float>]?
            var color: UInt32?
            var meshPositions: [SIMD3<Float>]?
            var meshTexcoords: [SIMD2<Float>]?
            var texels: Data?
            var texelFormat: Chunk.TexelFormat?
        }

        struct Anchor {
            var name: String
            var position: SIMD3<Float>?
            var orientation: simd_quatf?
        }

        var title: String?
        var points: [SIMD3<Float>] = []
        var pointColors: [UInt32]?
        var sightings: [Sighting] = []
        /// `world/dense`: the dense cloud's positions, `0xRRGGBBAA` colours and surfel radius.
        var densePoints: [SIMD3<Float>] = []
        var denseColors: [UInt32]?
        var denseRadius: Float?
        /// Camera rows: time, then whatever of the pose the row carries.
        var poseRows: [(time: Int64, order: Int, position: SIMD3<Float>?, orientation: simd_quatf?)] = []
        var photos: [Photo] = []
        var pinhole: [Float]?
        var resolution: [Float]?
        var path: [SIMD3<Float>] = []
        var planeOrder: [String] = []
        var planes: [String: Plane] = [:]
        var anchorOrder: [String] = []
        var anchors: [String: Anchor] = [:]

        static let planesPrefix = "world/planes/"
        static let anchorsPrefix = "world/anchors/"
        /// What the camera saw over time, one row per observation (``RerunRRDWriter``).
        static let livePoints = "world/points/live"
        /// A `.svscan` v2's dense cloud (``RerunRRDWriter``).
        static let dense = "world/dense"

        /// Whether a chunk on `entity` carries anything the replay uses; other chunks are
        /// skipped before their columns are decoded.
        static func reads(_ entity: String) -> Bool {
            ["__properties", "world/points", livePoints, dense, "world/camera", "world/camera_path"].contains(entity)
                || child(of: planesPrefix, entity) != nil || child(of: anchorsPrefix, entity) != nil
        }

        /// `world/planes/7` → `7`; `nil` for any other path or a deeper one.
        static func child(of prefix: String, _ entity: String) -> String? {
            guard entity.hasPrefix(prefix) else { return nil }
            let name = String(entity.dropFirst(prefix.count))
            return name.isEmpty || name.contains("/") ? nil : name
        }

        init(chunks: [Chunk]) throws {
            var order = 0
            for chunk in chunks {
                let entity = chunk.entityPath
                switch entity {
                case "__properties":
                    if let name = try Self.last(chunk.strings("RecordingInfo:name"))?.first { title = name }

                case "world/points":
                    if let flat = try Self.last(chunk.floatVectors("Points3D:positions", size: 3)) {
                        points = Self.vectors3(flat)
                        pointColors = nil
                    }
                    if let colors = try Self.last(chunk.uint32s("Points3D:colors")) { pointColors = colors }

                case Self.livePoints:
                    let positions = try chunk.floatVectors("Points3D:positions", size: 3)
                    if let times = chunk.times, let positions {
                        for row in 0..<chunk.rowCount {
                            guard let time = times[row], let flat = positions[row], flat.count >= 3 else { continue }
                            sightings.append(Sighting(time: time, order: order, positions: flat))
                            order += 1
                        }
                    }

                case Self.dense:
                    if let flat = try Self.last(chunk.floatVectors("Points3D:positions", size: 3)) {
                        densePoints = Self.vectors3(flat)
                        denseColors = nil
                    }
                    if let colors = try Self.last(chunk.uint32s("Points3D:colors")) { denseColors = colors }
                    if let radius = try Self.last(chunk.floats("Points3D:radii"))?.first { denseRadius = radius }

                case "world/camera":
                    let translations = try chunk.floatVectors("Transform3D:translation", size: 3)
                    let rotations = try chunk.floatVectors("Transform3D:quaternion", size: 4)
                    let blobs = try chunk.blobs("EncodedImage:blob")
                    let mediaTypes = try chunk.strings("EncodedImage:media_type")
                    if let times = chunk.times {
                        for row in 0..<chunk.rowCount {
                            guard let time = times[row] else { continue }
                            let position = translations?[row].flatMap { $0.count >= 3 ? SIMD3($0[0], $0[1], $0[2]) : nil }
                            let orientation = rotations?[row].flatMap { Self.quaternion($0) }
                            if position != nil || orientation != nil {
                                poseRows.append((time, order, position, orientation))
                                order += 1
                            }
                            if let blobs, let blob = blobs[row]?.first {
                                photos.append(Photo(time: time, order: order, data: blob,
                                                    mediaType: mediaTypes?[row]?.first))
                                order += 1
                            }
                        }
                    }
                    if let matrix = try Self.last(chunk.floatVectors("Pinhole:image_from_camera", size: 9)) {
                        pinhole = matrix
                    }
                    if let size = try Self.last(chunk.floatVectors("Pinhole:resolution", size: 2)) { resolution = size }

                case "world/camera_path":
                    if let strip = try Self.last(chunk.strips("LineStrips3D:strips"))?.first { path = strip }

                default:
                    if let name = Self.child(of: Self.planesPrefix, entity) {
                        try readPlane(name, chunk)
                    } else if let name = Self.child(of: Self.anchorsPrefix, entity) {
                        try readAnchor(name, chunk)
                    }
                }
            }
        }

        private mutating func readPlane(_ name: String, _ chunk: Chunk) throws {
            if planes[name] == nil {
                planeOrder.append(name)
                planes[name] = Plane(name: name)
            }
            guard var plane = planes[name] else { return }
            if let strip = try Self.last(chunk.strips("LineStrips3D:strips"))?.first { plane.outline = strip }
            if let color = try Self.last(chunk.uint32s("LineStrips3D:colors"))?.first { plane.color = color }
            if let flat = try Self.last(chunk.floatVectors("Mesh3D:vertex_positions", size: 3)) {
                plane.meshPositions = Self.vectors3(flat)
            }
            if let flat = try Self.last(chunk.floatVectors("Mesh3D:vertex_texcoords", size: 2)) {
                plane.meshTexcoords = stride(from: 0, to: flat.count - 1, by: 2).map { SIMD2(flat[$0], flat[$0 + 1]) }
            }
            if let texels = try Self.last(chunk.blobs("Mesh3D:albedo_texture_buffer"))?.first { plane.texels = texels }
            if let format = try Self.last(chunk.texelFormats("Mesh3D:albedo_texture_format"))?.first {
                plane.texelFormat = format
            }
            planes[name] = plane
        }

        private mutating func readAnchor(_ name: String, _ chunk: Chunk) throws {
            if anchors[name] == nil {
                anchorOrder.append(name)
                anchors[name] = Anchor(name: name)
            }
            guard var anchor = anchors[name] else { return }
            if let flat = try Self.last(chunk.floatVectors("Transform3D:translation", size: 3)), flat.count >= 3 {
                anchor.position = SIMD3(flat[0], flat[1], flat[2])
            }
            if let flat = try Self.last(chunk.floatVectors("Transform3D:quaternion", size: 4)) {
                anchor.orientation = Self.quaternion(flat) ?? anchor.orientation
            }
            anchors[name] = anchor
        }

        // MARK: Capture

        func recording() throws -> Recording {
            let poses = RerunRRDReader.pathPoses(resolvedPoses(), path: path, photoTimes: photos.map(\.time))
            let shots = distinctPhotos()
            let planeEvents: [(entity: String, id: Int, kind: String, polygon: [SIMD3<Float>], texture: Texture?)] =
                planeOrder.compactMap { name in
                    guard let plane = planes[name], var polygon = plane.outline?.filter(Self.isFinite), !polygon.isEmpty
                    else { return nil }
                    if polygon.count >= 2, polygon.first == polygon.last { polygon.removeLast() }
                    let entity = Self.planesPrefix + name
                    let id = RerunLog.entityId(entity) ?? 0
                    return (entity, id, Self.planeKind(color: plane.color), polygon, Texture(plane))
                }
            let anchorEvents: [(entity: String, pose: RerunRRDReader.PoseRow)] = anchorOrder.compactMap { name in
                guard let anchor = anchors[name], let position = anchor.position, Self.isFinite(position) else { return nil }
                let pose = PoseRow(time: 0, position: position, orientation: anchor.orientation ?? Self.identity)
                return (Self.anchorsPrefix + name, pose)
            }
            let mapPoints = points.indices.filter { Self.isFinite(points[$0]) }

            guard !poses.isEmpty || !mapPoints.isEmpty || planeEvents.contains(where: { $0.polygon.count >= 3 }) else {
                throw Failure.nothingToReplay
            }

            // The session starts at 0 on the writer's timeline (seconds since the first event).
            let seen = sightings.sorted { ($0.time, $0.order) < ($1.time, $1.order) }
            let times = poses.map(\.time) + shots.map(\.time) + seen.map(\.time)
            let start = min(0, times.min() ?? 0)
            let end = max(start, times.max() ?? start)

            var media = Data()
            var mediaEntries: [(path: String, offset: Int, length: Int)] = []
            func store(_ bytes: Data, at path: String) {
                mediaEntries.append((path, media.count, bytes.count))
                media.append(bytes)
            }

            var log = ""
            // With the sightings, the map grows as it was seen and the live points come back; the
            // static map only colours them, and gives the points no sighting kept at the start.
            // Without them (an older file, another writer), the whole map is there from the start.
            let colors = pointColors.flatMap { $0.count == points.count ? $0 : nil }
            var mapColors = MapColors(points: points, colors: colors)
            let sightingLines = seen.map { (time: $0.time, line: mapColors.line(time: $0.time, flat: $0.positions)) }
            let unseen = seen.isEmpty ? mapPoints : mapColors.unseen(mapPoints)
            if !unseen.isEmpty {
                var line = "{\"t\":\(start),\"type\":\"point_cloud\",\"entity\":\"world/points\",\"positions\":["
                line += unseen.map { JSONText.vector(points[$0]) }.joined(separator: ",") + "]"
                if let colors {
                    line += ",\"colors\":[" + unseen.map { MapColors.rgb(colors[$0]) }.joined(separator: ",") + "]"
                }
                log += line + "}\n"
            }
            var textures: [String] = []
            for plane in planeEvents {
                log += "{\"t\":\(start),\"type\":\"plane\",\"entity\":\(JSONText.string(plane.entity))"
                log += ",\"kind\":\(JSONText.string(plane.kind)),\"polygon\":["
                log += plane.polygon.map(JSONText.vector).joined(separator: ",") + "]}\n"
                if let texture = plane.texture, plane.polygon.count >= 3 {
                    let path = "planes/plane-\(plane.id).png"
                    store(texture.png, at: path)
                    textures.append("{\"plane\":\(plane.id),\"path\":\(JSONText.string(path))"
                        + ",\"origin\":\(JSONText.vector(texture.origin)),\"u\":\(JSONText.vector(texture.u))"
                        + ",\"v\":\(JSONText.vector(texture.v))}")
                }
            }
            for anchor in anchorEvents {
                log += "{\"t\":\(start),\"type\":\"anchor\",\"entity\":\(JSONText.string(anchor.entity))"
                log += ",\"translation\":\(JSONText.vector(anchor.pose.position))"
                log += ",\"quaternion\":\(JSONText.quaternion(anchor.pose.orientation))}\n"
            }

            // Poses, point sightings and photos in time order; at one instant the pose first and
            // the photo last, like the recorder.
            var photoMedia: [(path: String, bytes: Data)] = []
            var i = 0
            var j = 0
            var k = 0
            while i < poses.count || j < shots.count || k < sightingLines.count {
                let next = min(i < poses.count ? poses[i].time : .max,
                               j < shots.count ? shots[j].time : .max,
                               k < sightingLines.count ? sightingLines[k].time : .max)
                if i < poses.count, poses[i].time == next {
                    let pose = poses[i]
                    log += "{\"t\":\(pose.time),\"type\":\"camera_pose\",\"entity\":\"world/camera\""
                    log += ",\"translation\":\(JSONText.vector(pose.position))"
                    log += ",\"quaternion\":\(JSONText.quaternion(pose.orientation))}\n"
                    i += 1
                } else if k < sightingLines.count, sightingLines[k].time == next {
                    log += sightingLines[k].line + "\n"
                    k += 1
                } else {
                    let shot = shots[j]
                    let path = "frames/" + String(format: "%03d", photoMedia.count) + "." + Self.fileExtension(shot.mediaType)
                    photoMedia.append((path, shot.data))
                    log += "{\"t\":\(shot.time),\"type\":\"image\",\"entity\":\"world/camera/image\""
                    log += ",\"path\":\(JSONText.string(path))}\n"
                    j += 1
                }
            }
            // Photos first in the archive, as in a recorded capture; plane photos after them.
            let planeMedia = mediaEntries.map { entry in (entry.path, media.subdata(in: entry.offset..<(entry.offset + entry.length))) }
            media = Data()
            mediaEntries = []
            for (path, bytes) in photoMedia + planeMedia { store(bytes, at: path) }
            // The dense cloud last, as the recorder appends it after the photos.
            let dense = denseCloud()
            if let dense { store(RerunSVPC.encode(dense), at: RerunManifest.Dense.path) }

            let seconds = (Double(end) - Double(start)) / 1e9 // In Double: hostile times cannot overflow.
            let frameRate = seconds > 0 && photoMedia.count > 1 ? Double(photoMedia.count) / seconds : 10
            var manifest = "{"
            if let dense {
                manifest += "\"version\":2,\"dense\":" + RerunCaptureJSON.denseSection(dense, voxelM: denseVoxelM()) + ","
            }
            if let lens = lens() {
                manifest += "\"intrinsics\":{\"width\":\(lens.width),\"height\":\(lens.height)"
                manifest += ",\"fx\":\(JSONText.number(lens.fx)),\"fy\":\(JSONText.number(lens.fy))"
                manifest += ",\"cx\":\(JSONText.number(lens.cx)),\"cy\":\(JSONText.number(lens.cy))},"
            }
            manifest += "\"frameRate\":\(JSONText.number(Float(frameRate))),\"frames\":\(photoMedia.count)"
            if let floorY = Self.floorY(planeEvents.map { ($0.kind, $0.polygon) }) {
                manifest += ",\"floorY\":\(JSONText.number(floorY))"
            }
            manifest += ",\"textures\":[" + textures.joined(separator: ",") + "],\"media\":["
            manifest += mediaEntries.map { "{\"path\":\(JSONText.string($0.path)),\"offset\":\($0.offset),\"length\":\($0.length)}" }
                .joined(separator: ",")
            manifest += "]}"

            return Recording(
                title: title,
                pack: RerunCapturePack(manifest: Data(manifest.utf8), log: Data(log.utf8), media: media)
            )
        }

        /// `world/dense` as a dense cloud: finite positions, `0xRRGGBBAA` colours back to
        /// `0xAARRGGBB` (white where the file has none), no normals; `nil` when there is none.
        private func denseCloud() -> RerunDenseCloud? {
            let colors = denseColors.flatMap { $0.count == densePoints.count || $0.count == 1 ? $0 : nil }
            var positions: [SIMD3<Float>] = []
            var argb: [UInt32] = []
            for (i, p) in densePoints.enumerated() where Self.isFinite(p) {
                guard positions.count < RerunDenseCloud.maxPoints else { break }
                let rgba = colors.map { $0.count == 1 ? $0[0] : $0[i] } ?? 0xFFFF_FFFF
                positions.append(p)
                argb.append(0xFF00_0000 | (rgba >> 8))
            }
            return positions.isEmpty ? nil : RerunDenseCloud(positions: positions, colors: argb)
        }

        /// The dense surfel size: twice the radius the writer logged, 2 cm without one.
        private func denseVoxelM() -> Float {
            guard let radius = denseRadius, radius.isFinite, radius > 0 else { return RerunDenseFusion.voxelM }
            return radius * 2
        }

        /// Camera rows in time order, each completed with the last position and rotation
        /// seen (Rerun's latest-at), non-finite ones dropped.
        private func resolvedPoses() -> [PoseRow] {
            var position: SIMD3<Float>?
            var orientation = Self.identity
            var out: [PoseRow] = []
            for row in poseRows.sorted(by: { ($0.time, $0.order) < ($1.time, $1.order) }) {
                if let p = row.position { position = p }
                if let q = row.orientation { orientation = q }
                guard let position, Self.isFinite(position), orientation.vector.x.isFinite, orientation.vector.y.isFinite,
                      orientation.vector.z.isFinite, orientation.vector.w.isFinite else { continue }
                out.append(PoseRow(time: row.time, position: position, orientation: orientation))
            }
            return out
        }

        /// The photos in time order, each once: two keyframes that shared a photo logged it twice.
        private func distinctPhotos() -> [(time: Int64, data: Data, mediaType: String?)] {
            var out: [(time: Int64, data: Data, mediaType: String?)] = []
            for photo in photos.sorted(by: { ($0.time, $0.order) < ($1.time, $1.order) }) {
                guard let data = photo.data, !data.isEmpty else { continue }
                if let last = out.last, last.time == photo.time, last.data == data { continue }
                out.append((photo.time, data, photo.mediaType))
            }
            return out
        }

        /// The `Pinhole` in pixels of the photos: column-major `image_from_camera`
        /// (`fx` at 0, `fy` at 4, `cx` at 6, `cy` at 7) and `[width, height]`.
        private func lens() -> RerunPinhole? {
            guard let m = pinhole, m.count >= 9 else { return nil }
            let size = resolution.flatMap { $0.count >= 2 ? $0 : nil } ?? [2 * m[6], 2 * m[7]]
            let values = [m[0], m[4], m[6], m[7], size[0], size[1]]
            guard values.allSatisfy(\.isFinite), m[0] > 0, m[4] > 0, size[0] >= 1, size[1] >= 1,
                  size[0] < 1e6, size[1] < 1e6 else { return nil }
            return RerunPinhole(width: Int(size[0].rounded()), height: Int(size[1].rounded()),
                                fx: m[0], fy: m[4], cx: m[6], cy: m[7])
        }

        /// The recorder's rule: the lowest upward plane of at least 0.2 m², else the lowest
        /// upward plane.
        static func floorY(_ planes: [(kind: String, polygon: [SIMD3<Float>])]) -> Float? {
            let upward = planes.filter { $0.kind == RerunCapturePlaneKind.horizontalUpward.rawValue && $0.polygon.count >= 3 }
                .map(\.polygon)
            let large = upward.filter { RerunCaptureMath.area(of: $0) >= 0.2 }
            return (large.isEmpty ? upward : large).map { $0.map(\.y).reduce(0, +) / Float($0.count) }.min()
        }

        /// The writer tints each plane kind; the tint is how the kind travels.
        static func planeKind(color: UInt32?) -> String {
            let kinds = [RerunCapturePlaneKind.horizontalUpward, .horizontalDownward, .vertical]
            return kinds.first { RerunRRDWriter.planeColor(kind: $0.rawValue) == color }?.rawValue
                ?? RerunCapturePlaneKind.unknown.rawValue
        }

        static func fileExtension(_ mediaType: String?) -> String {
            switch mediaType {
            case "image/png": "png"
            case "image/webp": "webp"
            default: "jpg" // The readers decode by content; JPEG is what the writer emits most.
            }
        }

        static let identity = simd_quatf(ix: 0, iy: 0, iz: 0, r: 1)

        static func quaternion(_ xyzw: [Float]) -> simd_quatf? {
            xyzw.count >= 4 ? simd_quatf(ix: xyzw[0], iy: xyzw[1], iz: xyzw[2], r: xyzw[3]) : nil
        }

        static func vectors3(_ flat: [Float]) -> [SIMD3<Float>] {
            stride(from: 0, to: flat.count - 2, by: 3).map { SIMD3(flat[$0], flat[$0 + 1], flat[$0 + 2]) }
        }

        static func isFinite(_ p: SIMD3<Float>) -> Bool { p.x.isFinite && p.y.isFinite && p.z.isFinite }

        /// The last non-null row of a component, `nil` when the column or every row is missing.
        static func last<T>(_ rows: [T?]?) -> T? {
            rows?.last { $0 != nil } ?? nil
        }
    }

    /// The static map's colours by voxel, so a sighting's points take the colour the map gave
    /// them; it remembers which voxels a sighting has shown.
    fileprivate struct MapColors {
        private let points: [SIMD3<Float>]
        private let colors: [UInt32]?
        private var byVoxel: [Int64: Int] = [:]
        private var sighted = Set<Int64>()

        init(points: [SIMD3<Float>], colors: [UInt32]?) {
            self.points = points
            self.colors = colors
            for (i, p) in points.enumerated() where Contents.isFinite(p) {
                let voxel = RerunTrace.voxelKey(p)
                if byVoxel[voxel] == nil { byVoxel[voxel] = i }
            }
        }

        /// A `point_cloud` line at `time` for `flat` xyz; a point the map has no colour for gets
        /// none (`[-1,-1,-1]`, read back as no colour).
        mutating func line(time: Int64, flat: [Float]) -> String {
            var positions: [String] = []
            var rgb: [String] = []
            for i in 0..<(flat.count / 3) {
                let p = SIMD3(flat[3 * i], flat[3 * i + 1], flat[3 * i + 2])
                guard Contents.isFinite(p) else { continue }
                let voxel = RerunTrace.voxelKey(p)
                sighted.insert(voxel)
                positions.append(JSONText.vector(p))
                rgb.append(byVoxel[voxel].flatMap { colors?[$0] }.map(Self.rgb) ?? "[-1,-1,-1]")
            }
            var line = "{\"t\":\(time),\"type\":\"point_cloud\",\"entity\":\"world/points\",\"positions\":["
            line += positions.joined(separator: ",") + "]"
            if colors != nil { line += ",\"colors\":[" + rgb.joined(separator: ",") + "]" }
            return line + "}"
        }

        /// The map points of `indices` no sighting showed.
        func unseen(_ indices: [Int]) -> [Int] {
            indices.filter { !sighted.contains(RerunTrace.voxelKey(points[$0])) }
        }

        /// Rerun's `0xRRGGBBAA` as the session's `[r, g, b]`.
        static func rgb(_ c: UInt32) -> String { "[\(c >> 24),\(c >> 16 & 0xFF),\(c >> 8 & 0xFF)]" }
    }

    /// A plane photo recovered from a `Mesh3D`: the texels as PNG and the texture frame the
    /// manifest's `textures` entry needs.
    fileprivate struct Texture {
        var png: Data
        var origin: SIMD3<Float>
        var u: SIMD3<Float>
        var v: SIMD3<Float>

        /// The writer's texture coordinate of a vertex `p` is
        /// `(dot(p - origin, u) / |u|², dot(p - origin, v) / |v|²)`. With `u ⊥ v` and the
        /// plane spanned by them, `p = origin + s·u + t·v`: a linear fit of `p` on `(1, s, t)`
        /// over the vertices gives the frame back. The fit is kept only when the writer's own
        /// formula, applied to the fitted frame, gives every vertex its texture coordinate
        /// again — otherwise the plane stays untextured.
        init?(_ plane: Contents.Plane) {
            guard let positions = plane.meshPositions, let uvs = plane.meshTexcoords,
                  positions.count == uvs.count, positions.count >= 3,
                  let texels = plane.texels, let format = plane.texelFormat,
                  let png = Self.png(texels, format: format) else { return nil }

            var normal = simd_double3x3()
            var rhs = [SIMD3<Double>](repeating: .zero, count: 3)
            for (p, uv) in zip(positions, uvs) {
                guard Contents.isFinite(p), uv.x.isFinite, uv.y.isFinite else { return nil }
                let a = SIMD3<Double>(1, Double(uv.x), Double(uv.y))
                normal = normal + simd_double3x3(columns: (a * a.x, a * a.y, a * a.z))
                rhs[0] += a * Double(p.x)
                rhs[1] += a * Double(p.y)
                rhs[2] += a * Double(p.z)
            }
            guard abs(normal.determinant) > 1e-12 else { return nil }
            let inverse = normal.inverse
            let (x, y, z) = (inverse * rhs[0], inverse * rhs[1], inverse * rhs[2])
            let origin = SIMD3<Float>(Float(x[0]), Float(y[0]), Float(z[0]))
            let u = SIMD3<Float>(Float(x[1]), Float(y[1]), Float(z[1]))
            let v = SIMD3<Float>(Float(x[2]), Float(y[2]), Float(z[2]))
            let uu = simd_dot(u, u), vv = simd_dot(v, v)
            guard Contents.isFinite(origin), uu > 1e-12, vv > 1e-12, uu.isFinite, vv.isFinite else { return nil }
            for (p, uv) in zip(positions, uvs) {
                let d = p - origin
                guard simd_distance(SIMD2(simd_dot(d, u) / uu, simd_dot(d, v) / vv), uv) < 1e-3 else { return nil }
            }
            self.png = png
            self.origin = origin
            self.u = u
            self.v = v
        }

        /// Rerun's `ColorModel`: RGB is 2, RGBA 3; `ChannelDatatype.U8` is 6. Anything else
        /// (a `pixel_format` such as NV12, or wider channels) is not a plane photo the writer made.
        static func png(_ texels: Data, format: Chunk.TexelFormat) -> Data? {
            guard format.pixelFormat == nil, format.channelDatatype.map({ $0 == 6 }) ?? true,
                  let channels = [2: 3, 3: 4][format.colorModel ?? 2],
                  format.width > 0, format.height > 0, format.width <= 16_384, format.height <= 16_384,
                  texels.count >= format.width * format.height * channels,
                  let space = CGColorSpace(name: CGColorSpace.sRGB),
                  let provider = CGDataProvider(data: Data(texels.prefix(format.width * format.height * channels)) as CFData)
            else { return nil }
            let alpha: CGImageAlphaInfo = channels == 4 ? .last : .none
            guard let image = CGImage(
                width: format.width, height: format.height, bitsPerComponent: 8, bitsPerPixel: 8 * channels,
                bytesPerRow: format.width * channels, space: space, bitmapInfo: CGBitmapInfo(rawValue: alpha.rawValue),
                provider: provider, decode: nil, shouldInterpolate: false, intent: .defaultIntent
            ) else { return nil }
            let output = NSMutableData()
            guard let destination = CGImageDestinationCreateWithData(output as CFMutableData, "public.png" as CFString, 1, nil)
            else { return nil }
            CGImageDestinationAddImage(destination, image, nil)
            guard CGImageDestinationFinalize(destination) else { return nil }
            return output as Data
        }
    }

    /// The session's JSON, hand-written like ``RerunCaptureJSON`` but at full `Float`
    /// precision: Swift prints the shortest decimal that reads back as the same `Float`, so
    /// the replay sees exactly the values the `.rrd` holds.
    fileprivate enum JSONText {
        static func number(_ v: Float) -> String { v.isFinite ? "\(v)" : "0" }

        static func vector(_ v: SIMD3<Float>) -> String { "[\(number(v.x)),\(number(v.y)),\(number(v.z))]" }

        static func quaternion(_ q: simd_quatf) -> String {
            "[\(number(q.imag.x)),\(number(q.imag.y)),\(number(q.imag.z)),\(number(q.real))]"
        }

        static func string(_ s: String) -> String {
            var out = "\""
            for scalar in s.unicodeScalars {
                switch scalar {
                case "\"": out += "\\\""
                case "\\": out += "\\\\"
                case _ where scalar.value < 0x20: out += String(format: "\\u%04x", scalar.value)
                default: out.unicodeScalars.append(scalar)
                }
            }
            return out + "\""
        }
    }
}

// MARK: - RRF2 stream

extension RerunRRDReader {
    /// The `.rrd` framing: a 12-byte stream header, then messages — a 16-byte header
    /// (kind, length; little-endian u64s) and a protobuf payload.
    fileprivate enum RRDStream {
        static func chunks(in data: Data) throws -> [Chunk] {
            let bytes = Bytes(data)
            try header(bytes, at: 0)
            var position = 12
            var chunks: [Chunk] = []
            while position < bytes.count {
                let kind = try bytes.u64(position)
                let length = try bytes.u64(position + 8)
                position += 16
                guard length <= UInt64(bytes.count - position) else { throw Failure.truncated }
                let payload = try bytes.slice(position, Int(length))
                position += Int(length)
                switch kind {
                case 0:
                    // End of stream. Another stream may follow (concatenated recordings); anything
                    // else is the optional footer, an index the reader does not need.
                    guard position < bytes.count, (try? header(bytes, at: position)) != nil else { return chunks }
                    position += 12
                case 2:
                    chunks += try arrowMessage(payload)
                default:
                    continue // SetStoreInfo, blueprint activation: nothing the replay draws.
                }
            }
            return chunks
        }

        /// `RRF2`, the Rerun version (major, minor, patch, 0), then the options: compression
        /// (0 off, 1 LZ4) and serializer (2 protobuf).
        static func header(_ bytes: Bytes, at position: Int) throws {
            guard bytes.count - position >= 4, bytes.matches("RRF2", at: position) else { throw Failure.notAnRRD }
            guard bytes.count - position >= 12 else { throw Failure.truncated }
            let major = try bytes.u8(position + 4), minor = try bytes.u8(position + 5), patch = try bytes.u8(position + 6)
            guard major == RerunRRDWriter.rerunVersion.major else {
                throw Failure.unsupportedVersion(major: Int(major), minor: Int(minor), patch: Int(patch))
            }
            guard try bytes.u8(position + 8) == 0 else { throw Failure.compressed }
            let serializer = try bytes.u8(position + 9)
            guard serializer == 2 else { throw Failure.unsupportedSerializer(Int(serializer)) }
        }

        /// `rerun.log_msg.v1alpha1.ArrowMsg`: store id (1), compression (2), encoding (4),
        /// the Arrow IPC payload (5).
        static func arrowMessage(_ payload: Bytes) throws -> [Chunk] {
            let message = try ProtoMessage(payload)
            if let store = try message.message(1), store.varint(1) == 2 { return [] } // A blueprint, not the recording.
            switch message.varint(2) ?? 1 {
            case 0, 1: break // Unspecified, none.
            case 2: throw Failure.compressed
            default: throw Failure.malformed("unknown chunk compression")
            }
            guard (message.varint(4) ?? 1) <= 1 else { throw Failure.malformed("chunk encoding is not Arrow IPC") }
            guard let ipc = message.bytes(5) else { throw Failure.malformed("chunk without payload") }
            return try ArrowStream.chunks(in: ipc)
        }
    }

    /// A bounds-checked window on the file's bytes: every read throws
    /// ``Failure/truncated`` rather than trapping, whatever offset the data claims.
    fileprivate struct Bytes {
        private let storage: [UInt8]
        private let start: Int
        let count: Int

        init(_ data: Data) {
            storage = [UInt8](data)
            start = 0
            count = storage.count
        }

        private init(storage: [UInt8], start: Int, count: Int) {
            self.storage = storage
            self.start = start
            self.count = count
        }

        func slice(_ offset: Int, _ length: Int) throws -> Bytes {
            guard offset >= 0, length >= 0, offset <= count, length <= count - offset else { throw Failure.truncated }
            return Bytes(storage: storage, start: start + offset, count: length)
        }

        /// A little-endian unsigned integer of `size` bytes.
        func uint(_ offset: Int, size: Int) throws -> UInt64 {
            guard offset >= 0, size <= 8, offset <= count - size else { throw Failure.truncated }
            var value: UInt64 = 0
            for i in 0..<size { value |= UInt64(storage[start + offset + i]) << (8 * UInt64(i)) }
            return value
        }

        func u8(_ offset: Int) throws -> UInt8 { UInt8(truncatingIfNeeded: try uint(offset, size: 1)) }
        func u16(_ offset: Int) throws -> UInt16 { UInt16(truncatingIfNeeded: try uint(offset, size: 2)) }
        func u32(_ offset: Int) throws -> UInt32 { UInt32(truncatingIfNeeded: try uint(offset, size: 4)) }
        func i32(_ offset: Int) throws -> Int32 { Int32(bitPattern: try u32(offset)) }
        func u64(_ offset: Int) throws -> UInt64 { try uint(offset, size: 8) }
        func i64(_ offset: Int) throws -> Int64 { Int64(bitPattern: try u64(offset)) }

        var data: Data { Data(storage[start..<(start + count)]) }

        func subdata(_ offset: Int, _ length: Int) throws -> Data { try slice(offset, length).data }

        func matches(_ ascii: String, at offset: Int) -> Bool {
            let expected = Array(ascii.utf8)
            guard offset >= 0, offset <= count - expected.count else { return false }
            return storage[(start + offset)..<(start + offset + expected.count)].elementsEqual(expected)
        }
    }

    /// A protobuf message's fields by number; a repeated scalar keeps its last value, as
    /// protobuf merges them.
    fileprivate struct ProtoMessage {
        enum Value {
            case varint(UInt64)
            case fixed(UInt64)
            case bytes(Bytes)
        }

        private var fields: [Int: Value] = [:]

        init(_ bytes: Bytes) throws {
            var position = 0
            while position < bytes.count {
                let key = try Self.varint(bytes, &position)
                guard key >> 3 > 0, key >> 3 <= UInt64(Int32.max) else { throw Failure.malformed("protobuf field number") }
                let number = Int(key >> 3)
                switch key & 7 {
                case 0:
                    fields[number] = .varint(try Self.varint(bytes, &position))
                case 1:
                    fields[number] = .fixed(try bytes.u64(position))
                    position += 8
                case 2:
                    let length = try Self.varint(bytes, &position)
                    guard length <= UInt64(bytes.count - position) else { throw Failure.truncated }
                    fields[number] = .bytes(try bytes.slice(position, Int(length)))
                    position += Int(length)
                case 5:
                    fields[number] = .fixed(UInt64(try bytes.u32(position)))
                    position += 4
                default:
                    throw Failure.malformed("protobuf wire type \(key & 7)")
                }
            }
        }

        static func varint(_ bytes: Bytes, _ position: inout Int) throws -> UInt64 {
            var value: UInt64 = 0
            var shift: UInt64 = 0
            while true {
                guard shift < 64 else { throw Failure.malformed("protobuf varint too long") }
                let byte = try bytes.u8(position)
                position += 1
                value |= UInt64(byte & 0x7F) << shift
                if byte < 0x80 { return value }
                shift += 7
            }
        }

        func varint(_ number: Int) -> UInt64? {
            if case let .varint(value)? = fields[number] { return value }
            return nil
        }

        func bytes(_ number: Int) -> Bytes? {
            if case let .bytes(value)? = fields[number] { return value }
            return nil
        }

        func message(_ number: Int) throws -> ProtoMessage? {
            try bytes(number).map(ProtoMessage.init)
        }
    }

    /// Reads FlatBuffers the standard way: a table starts with a signed offset back to its
    /// vtable, the vtable lists each field's offset in the table (0 when absent), tables,
    /// vectors and strings are reached through unsigned forward offsets.
    fileprivate struct FlatBuffer {
        let bytes: Bytes

        func root() throws -> Int { try indirect(0) }

        func indirect(_ position: Int) throws -> Int {
            let target = position + Int(try bytes.u32(position))
            guard target < bytes.count else { throw Failure.truncated }
            return target
        }

        func field(_ table: Int, _ slot: Int) throws -> Int? {
            let vtable = table - Int(try bytes.i32(table))
            let vtableSize = Int(try bytes.u16(vtable))
            let entry = 4 + 2 * slot
            guard entry + 2 <= vtableSize else { return nil }
            let offset = Int(try bytes.u16(vtable + entry))
            return offset == 0 ? nil : table + offset
        }

        func u8(_ table: Int, _ slot: Int) throws -> UInt8 { try field(table, slot).map(bytes.u8) ?? 0 }
        func i16(_ table: Int, _ slot: Int, default value: Int16 = 0) throws -> Int16 {
            try field(table, slot).map { Int16(bitPattern: try bytes.u16($0)) } ?? value
        }
        func i32(_ table: Int, _ slot: Int) throws -> Int32 { try field(table, slot).map(bytes.i32) ?? 0 }
        func i64(_ table: Int, _ slot: Int) throws -> Int64 { try field(table, slot).map(bytes.i64) ?? 0 }

        func table(_ table: Int, _ slot: Int) throws -> Int? { try field(table, slot).map(indirect) }

        func string(_ table: Int, _ slot: Int) throws -> String? {
            guard let start = try self.table(table, slot) else { return nil }
            let length = Int(try bytes.u32(start))
            return String(decoding: try bytes.subdata(start + 4, length), as: UTF8.self)
        }

        /// A vector's element count and the position of its first element.
        func vector(_ table: Int, _ slot: Int, elementSize: Int) throws -> (count: Int, start: Int)? {
            guard let position = try self.table(table, slot) else { return nil }
            let count = Int(try bytes.u32(position))
            guard count <= max(0, bytes.count - position - 4) / elementSize else { throw Failure.truncated }
            return (count, position + 4)
        }

        func tables(_ table: Int, _ slot: Int) throws -> [Int] {
            guard let vector = try vector(table, slot, elementSize: 4) else { return [] }
            return try (0..<vector.count).map { try indirect(vector.start + 4 * $0) }
        }

        /// A `[KeyValue]` field (Arrow's custom metadata).
        func keyValues(_ table: Int, _ slot: Int) throws -> [String: String] {
            var out: [String: String] = [:]
            for entry in try tables(table, slot) {
                if let key = try string(entry, 0) { out[key] = try string(entry, 1) ?? "" }
            }
            return out
        }
    }
}

// MARK: - Arrow IPC

extension RerunRRDReader {
    /// An Arrow type, as far as the reader tells types apart: the layouts Rerun components
    /// use get their own case, anything else is kept only to be reported.
    fileprivate enum ArrowType: Equatable {
        case null
        case bool
        case int(bits: Int, signed: Bool)
        case float(bits: Int)
        case binary(largeOffsets: Bool)
        case utf8(largeOffsets: Bool)
        case list(largeOffsets: Bool)
        case fixedSizeList(Int)
        case structure
        case fixedSizeBinary(Int)
        /// `duration` or `timestamp`, with its unit in nanoseconds.
        case time(nanosPerUnit: Int64)
        case unsupported(String)

        /// Buffers per array in IPC order (validity first); `nil` for a layout the reader
        /// cannot walk.
        var bufferCount: Int? {
            switch self {
            case .null: 0
            case .bool, .int, .float, .fixedSizeBinary, .time, .list: 2
            case .binary, .utf8: 3
            case .fixedSizeList, .structure: 1
            case .unsupported: nil
            }
        }

        /// `Schema.fbs`' `Type` union: its discriminant and table.
        init(id: UInt8, table: Int?, flat: FlatBuffer) throws {
            func int32(_ slot: Int) throws -> Int { try table.map { Int(try flat.i32($0, slot)) } ?? 0 }
            func int16(_ slot: Int, _ value: Int16) throws -> Int16 { try table.map { try flat.i16($0, slot, default: value) } ?? value }
            func nanos(_ unit: Int16) -> ArrowType {
                switch unit {
                case 0: .time(nanosPerUnit: 1_000_000_000)
                case 1: .time(nanosPerUnit: 1_000_000)
                case 2: .time(nanosPerUnit: 1_000)
                case 3: .time(nanosPerUnit: 1)
                default: .unsupported("time unit \(unit)")
                }
            }
            switch id {
            case 1: self = .null
            case 2:
                let bits = try int32(0)
                let signed = try table.map { try flat.u8($0, 1) != 0 } ?? false
                self = [8, 16, 32, 64].contains(bits) ? .int(bits: bits, signed: signed) : .unsupported("int\(bits)")
            case 3:
                switch try int16(0, 0) {
                case 0: self = .float(bits: 16)
                case 1: self = .float(bits: 32)
                case 2: self = .float(bits: 64)
                default: self = .unsupported("float precision")
                }
            case 4: self = .binary(largeOffsets: false)
            case 5: self = .utf8(largeOffsets: false)
            case 6: self = .bool
            case 10: self = nanos(try int16(0, 0)) // Timestamp; its unit has no default.
            case 12: self = .list(largeOffsets: false)
            case 13: self = .structure
            case 15:
                let width = try int32(0)
                self = width >= 0 ? .fixedSizeBinary(width) : .unsupported("fixed_size_binary")
            case 16:
                let size = try int32(0)
                self = size >= 0 ? .fixedSizeList(size) : .unsupported("fixed_size_list")
            case 18: self = nanos(try int16(0, 1)) // Duration, milliseconds by default.
            case 19: self = .binary(largeOffsets: true)
            case 20: self = .utf8(largeOffsets: true)
            case 21: self = .list(largeOffsets: true)
            default: self = .unsupported("Arrow type \(id)")
            }
        }
    }

    fileprivate struct ArrowField {
        var name: String
        var type: ArrowType
        var children: [ArrowField]
        var metadata: [String: String]

        /// `Field`: name (0), type discriminant (2) and table (3), dictionary (4),
        /// children (5), custom metadata (6).
        init(_ flat: FlatBuffer, table: Int, depth: Int = 0) throws {
            guard depth < 32 else { throw Failure.malformed("Arrow schema nested too deep") }
            name = try flat.string(table, 0) ?? ""
            metadata = try flat.keyValues(table, 6)
            children = try flat.tables(table, 5).map { try ArrowField(flat, table: $0, depth: depth + 1) }
            if try flat.table(table, 4) != nil {
                type = .unsupported("dictionary-encoded")
            } else {
                type = try ArrowType(id: flat.u8(table, 2), table: flat.table(table, 3), flat: flat)
            }
        }
    }

    /// One decoded Arrow array: its buffers (sizes and offsets checked when read) and children.
    fileprivate struct ArrowArray {
        var name: String
        var type: ArrowType
        var length: Int
        var validity: Bytes?
        var offsets: Bytes?
        var values: Bytes?
        var children: [ArrowArray] = []

        var offsetWidth: Int {
            switch type {
            case .binary(true), .utf8(true), .list(true): 8
            default: 4
            }
        }

        func isNull(_ index: Int) throws -> Bool {
            guard let validity else { return false }
            return try validity.u8(index / 8) & (1 << UInt8(index % 8)) == 0
        }

        /// Child (or byte) range of list slot `index`.
        func range(_ index: Int) throws -> Range<Int> {
            guard let offsets else { throw Failure.malformed("\(name): no offsets") }
            let width = offsetWidth
            let lower = Int(truncatingIfNeeded: Int64(bitPattern: try offsets.uint(index * width, size: width)))
            let upper = Int(truncatingIfNeeded: Int64(bitPattern: try offsets.uint((index + 1) * width, size: width)))
            guard lower >= 0, lower <= upper else { throw Failure.malformed("\(name): offsets") }
            return lower..<upper
        }

        func float32(_ index: Int) throws -> Float {
            guard type == .float(bits: 32), let values else { throw Failure.unexpectedLayout(name) }
            return Float(bitPattern: try values.u32(index * 4))
        }

        /// Any integer (or time) value, sign-extended when signed.
        func integer(_ index: Int) throws -> Int64 {
            guard let values else { throw Failure.unexpectedLayout(name) }
            switch type {
            case let .int(bits, signed):
                let raw = try values.uint(index * bits / 8, size: bits / 8)
                guard signed, bits < 64 else { return Int64(bitPattern: raw) }
                let shift = UInt64(64 - bits)
                return Int64(bitPattern: raw << shift) >> shift
            case .time:
                return try values.i64(index * 8)
            default:
                throw Failure.unexpectedLayout(name)
            }
        }

        func byteRange(_ range: Range<Int>) throws -> Data {
            guard let values else { throw Failure.unexpectedLayout(name) }
            return try values.subdata(range.lowerBound, range.count)
        }

        func child(_ name: String) -> ArrowArray? { children.first { $0.name == name } }
    }

    fileprivate struct Schema {
        var fields: [ArrowField]
        var metadata: [String: String]

        /// `Schema`: endianness (0), fields (1), custom metadata (2).
        init(_ flat: FlatBuffer, table: Int) throws {
            guard try flat.i16(table, 0) == 0 else { throw Failure.unexpectedLayout("big-endian Arrow data") }
            fields = try flat.tables(table, 1).map { try ArrowField(flat, table: $0) }
            metadata = try flat.keyValues(table, 2)
        }
    }

    /// The Arrow IPC streaming format: messages prefixed by `0xFFFFFFFF` and a length (or
    /// the length alone, the pre-1.0 form), a flatbuffer `Message`, then its body.
    fileprivate enum ArrowStream {
        static func chunks(in ipc: Bytes) throws -> [Chunk] {
            var position = 0
            var schema: Schema?
            var chunks: [Chunk] = []
            while position < ipc.count {
                var length = Int(try ipc.i32(position))
                position += 4
                if length == -1 {
                    length = Int(try ipc.i32(position))
                    position += 4
                }
                if length == 0 { break } // End of stream.
                guard length > 0 else { throw Failure.malformed("Arrow message length") }
                let flat = FlatBuffer(bytes: try ipc.slice(position, length))
                position += length
                // `Message`: version (0), header type (1), header (2), body length (3).
                let message = try flat.root()
                let bodyLength = try flat.i64(message, 3)
                guard bodyLength >= 0, bodyLength <= Int64(ipc.count - position) else { throw Failure.truncated }
                let body = try ipc.slice(position, Int(bodyLength))
                position += Int(bodyLength)
                guard let header = try flat.table(message, 2) else { throw Failure.malformed("Arrow message without header") }
                switch try flat.u8(message, 1) {
                case 1:
                    schema = try Schema(flat, table: header)
                case 3:
                    guard let schema else { throw Failure.malformed("record batch before its schema") }
                    if let chunk = try Chunk(schema: schema, flat: flat, batch: header, body: body) { chunks.append(chunk) }
                default:
                    continue // Dictionary batches, tensors: nothing the writer emits.
                }
            }
            return chunks
        }
    }

    /// Walks a record batch's `nodes` and `buffers` depth-first against the schema, checking
    /// each buffer is long enough and each offset in range before anything reads it.
    fileprivate struct BatchReader {
        var nodes: [(length: Int, nullCount: Int)]
        var buffers: [Bytes]
        var nodeIndex = 0
        var bufferIndex = 0

        mutating func array(_ field: ArrowField) throws -> ArrowArray {
            guard let bufferCount = field.type.bufferCount else {
                throw Failure.unexpectedLayout("\(field.name): \(field.type)")
            }
            guard nodeIndex < nodes.count, bufferIndex + bufferCount <= buffers.count else {
                throw Failure.malformed("record batch shorter than its schema")
            }
            let node = nodes[nodeIndex]
            nodeIndex += 1
            let own = Array(buffers[bufferIndex..<(bufferIndex + bufferCount)])
            bufferIndex += bufferCount
            let length = node.length

            var array = ArrowArray(name: field.name, type: field.type, length: length)
            if bufferCount > 0, node.nullCount > 0 {
                guard own[0].count >= (length + 7) / 8 else { throw Failure.truncated }
                array.validity = own[0]
            }
            func fixedWidth(_ bytesPerValue: Int) throws {
                guard own[1].count >= length * bytesPerValue else { throw Failure.truncated }
                array.values = own[1]
            }
            func onlyChild() throws -> ArrowArray {
                guard field.children.count == 1 else { throw Failure.malformed("\(field.name): one child expected") }
                return try self.array(field.children[0])
            }

            switch field.type {
            case .null, .unsupported:
                break
            case .bool:
                guard own[1].count >= (length + 7) / 8 else { throw Failure.truncated }
                array.values = own[1]
            case let .int(bits, _), let .float(bits):
                try fixedWidth(bits / 8)
            case .time:
                try fixedWidth(8)
            case let .fixedSizeBinary(width):
                try fixedWidth(width)
            case .binary, .utf8:
                array.offsets = own[1]
                array.values = own[2]
                try Self.checkOffsets(array, limit: own[2].count)
            case .list:
                array.offsets = own[1]
                let child = try onlyChild()
                array.children = [child]
                try Self.checkOffsets(array, limit: child.length)
            case let .fixedSizeList(size):
                let child = try onlyChild()
                guard child.length >= length * size else { throw Failure.malformed("\(field.name): short values") }
                array.children = [child]
            case .structure:
                array.children = try field.children.map { try self.array($0) }
                guard array.children.allSatisfy({ $0.length >= length }) else {
                    throw Failure.malformed("\(field.name): short struct field")
                }
            }
            return array
        }

        /// Offsets never decrease and stay within the child (or byte) count.
        static func checkOffsets(_ array: ArrowArray, limit: Int) throws {
            guard array.length > 0 else { return }
            var previous = 0
            for index in 0..<array.length {
                let range = try array.range(index)
                guard range.lowerBound >= previous, range.upperBound <= limit else {
                    throw Failure.malformed("\(array.name): offsets out of range")
                }
                previous = range.upperBound
            }
        }
    }
}

// MARK: - Chunks

extension RerunRRDReader {
    /// One Rerun chunk: rows of components on one entity, on the `time` timeline or static.
    /// Columns are found by their `rerun:component` metadata (`Points3D:positions`), never
    /// by position.
    fileprivate struct Chunk {
        var entityPath: String
        var rowCount: Int
        /// Nanoseconds on the `time` timeline, one per row (`nil` for a row not on it);
        /// `nil` for a static chunk.
        var times: [Int64?]?
        var columns: [String: ArrowArray]

        /// `nil` for a chunk on an entity the replay does not read: it is not decoded.
        init?(schema: Schema, flat: FlatBuffer, batch: Int, body: Bytes) throws {
            guard let path = schema.metadata["rerun:entity_path"] else { return nil }
            entityPath = path.hasPrefix("/") ? String(path.dropFirst()) : path
            guard Contents.reads(entityPath) else { return nil }

            // `RecordBatch`: length (0), nodes (1), buffers (2), compression (3).
            let length = try flat.i64(batch, 0)
            guard length >= 0, length <= Int64(Int32.max) else { throw Failure.malformed("record batch length") }
            rowCount = Int(length)
            guard try flat.table(batch, 3) == nil else { throw Failure.compressed }
            var nodes: [(length: Int, nullCount: Int)] = []
            if let vector = try flat.vector(batch, 1, elementSize: 16) {
                for index in 0..<vector.count {
                    let count = try flat.bytes.i64(vector.start + 16 * index)
                    let nulls = try flat.bytes.i64(vector.start + 16 * index + 8)
                    guard count >= 0, count <= Int64(Int32.max), nulls >= 0, nulls <= count else {
                        throw Failure.malformed("record batch node")
                    }
                    nodes.append((Int(count), Int(nulls)))
                }
            }
            var buffers: [Bytes] = []
            if let vector = try flat.vector(batch, 2, elementSize: 16) {
                for index in 0..<vector.count {
                    let offset = try flat.bytes.i64(vector.start + 16 * index)
                    let size = try flat.bytes.i64(vector.start + 16 * index + 8)
                    guard offset >= 0, size >= 0, offset <= Int64(body.count), size <= Int64(body.count) else {
                        throw Failure.truncated
                    }
                    buffers.append(try body.slice(Int(offset), Int(size)))
                }
            }

            var reader = BatchReader(nodes: nodes, buffers: buffers)
            var columns: [String: ArrowArray] = [:]
            var times: [Int64?]?
            var hasIndex = false
            for field in schema.fields {
                let array = try reader.array(field)
                guard array.length >= rowCount else { throw Failure.malformed("\(field.name): fewer values than rows") }
                switch field.metadata["rerun:kind"] {
                case "control":
                    continue // Row ids.
                case "index":
                    hasIndex = true
                    if (field.metadata["rerun:index_name"] ?? field.name) == RerunRRDWriter.timeline {
                        times = try Self.nanoseconds(array, rows: rowCount)
                    }
                default:
                    columns[field.metadata["rerun:component"] ?? field.name] = array
                }
            }
            // Rows on another timeline only: none of them has a time on ours.
            if hasIndex, times == nil { times = [Int64?](repeating: nil, count: rowCount) }
            self.times = times
            self.columns = columns
        }

        static func nanoseconds(_ array: ArrowArray, rows: Int) throws -> [Int64?] {
            let perUnit: Int64
            switch array.type {
            case let .time(nanos): perUnit = nanos
            case .int(bits: 64, signed: true): perUnit = 1
            default: throw Failure.unexpectedLayout("\(array.name): not a time index")
            }
            return try (0..<rows).map { row in
                guard try !array.isNull(row) else { return nil }
                let (nanos, overflow) = try array.integer(row).multipliedReportingOverflow(by: perUnit)
                return overflow ? nil : nanos
            }
        }

        // MARK: Components

        /// `list<...>` rows: `read` gets the row's instance range; `nil` for a null row,
        /// `nil` altogether when the chunk has no such component.
        private func rows<T>(_ component: String, check: (ArrowArray) -> Bool,
                             _ read: (ArrowArray, Range<Int>) throws -> T) throws -> [T?]? {
            guard let list = columns[component] else { return nil }
            guard case .list = list.type, let item = list.children.first, check(item) else {
                throw Failure.unexpectedLayout(component)
            }
            return try (0..<rowCount).map { row in try list.isNull(row) ? nil : try read(item, list.range(row)) }
        }

        /// `list<fixed_size_list<float32>[size]>`: each row's instances, flattened.
        func floatVectors(_ component: String, size: Int) throws -> [[Float]?]? {
            try rows(component, check: { $0.type == .fixedSizeList(size) && $0.children.first?.type == .float(bits: 32) }) { item, range in
                guard let floats = item.children.first else { return [] }
                var out: [Float] = []
                out.reserveCapacity(range.count * size)
                for index in (range.lowerBound * size)..<(range.upperBound * size) { out.append(try floats.float32(index)) }
                return out
            }
        }

        /// `list<float32>` (radii): each row's scalars.
        func floats(_ component: String) throws -> [[Float]?]? {
            try rows(component, check: { $0.type == .float(bits: 32) }) { item, range in
                try range.map { try item.float32($0) }
            }
        }

        /// `list<uint32>` (colours, `0xRRGGBBAA`).
        func uint32s(_ component: String) throws -> [[UInt32]?]? {
            try rows(component, check: { if case .int(bits: 32, _) = $0.type { true } else { false } }) { item, range in
                try range.map { UInt32(truncatingIfNeeded: try item.integer($0)) }
            }
        }

        /// `list<utf8>`.
        func strings(_ component: String) throws -> [[String]?]? {
            try rows(component, check: { if case .utf8 = $0.type { true } else { false } }) { item, range in
                try range.map { String(decoding: try item.byteRange(item.range($0)), as: UTF8.self) }
            }
        }

        /// `list<list<uint8>>` (Rerun's `Blob`) or `list<binary>`: each instance's bytes.
        func blobs(_ component: String) throws -> [[Data]?]? {
            let isBlob = { (item: ArrowArray) -> Bool in
                switch item.type {
                case .binary: return true
                case .list: return item.children.first?.type == .int(bits: 8, signed: false)
                default: return false
                }
            }
            return try rows(component, check: isBlob) { item, range in
                try range.map { instance in
                    let bytes = try item.range(instance)
                    if case .binary = item.type { return try item.byteRange(bytes) }
                    guard let child = item.children.first else { return Data() }
                    return try child.byteRange(bytes)
                }
            }
        }

        /// `list<list<fixed_size_list<float32>[3]>>` (`LineStrip3D`): each strip's points.
        func strips(_ component: String) throws -> [[[SIMD3<Float>]]?]? {
            let isStrip = { (item: ArrowArray) -> Bool in
                guard case .list = item.type, let point = item.children.first else { return false }
                return point.type == .fixedSizeList(3) && point.children.first?.type == .float(bits: 32)
            }
            return try rows(component, check: isStrip) { item, range in
                guard let floats = item.children.first?.children.first else { return [] }
                return try range.map { strip in
                    try item.range(strip).map { point in
                        SIMD3(try floats.float32(3 * point), try floats.float32(3 * point + 1), try floats.float32(3 * point + 2))
                    }
                }
            }
        }

        /// Rerun's `ImageFormat` struct, as far as a texture needs it.
        struct TexelFormat: Equatable {
            var width: Int
            var height: Int
            var pixelFormat: Int64?
            var colorModel: Int64?
            var channelDatatype: Int64?
        }

        /// `list<struct<width, height, pixel_format, color_model, channel_datatype>>`.
        func texelFormats(_ component: String) throws -> [[TexelFormat]?]? {
            let isFormat = { (item: ArrowArray) -> Bool in
                item.type == .structure && item.child("width") != nil && item.child("height") != nil
            }
            return try rows(component, check: isFormat) { item, range in
                func value(_ name: String, _ index: Int) throws -> Int64? {
                    guard let child = item.child(name), try !child.isNull(index) else { return nil }
                    return try child.integer(index)
                }
                return try range.compactMap { index in
                    guard try !item.isNull(index), let width = try value("width", index),
                          let height = try value("height", index),
                          width >= 0, height >= 0, width <= Int64(Int32.max), height <= Int64(Int32.max) else { return nil }
                    return TexelFormat(width: Int(width), height: Int(height),
                                       pixelFormat: try value("pixel_format", index),
                                       colorModel: try value("color_model", index),
                                       channelDatatype: try value("channel_datatype", index))
                }
            }
        }
    }
}

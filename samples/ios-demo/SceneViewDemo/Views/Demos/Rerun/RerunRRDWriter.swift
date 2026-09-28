import CoreGraphics
import Foundation
import ImageIO
import simd

/// Writes a ``RerunExportScene`` as a Rerun `.rrd` recording (`rerun space.rrd` opens it),
/// in pure Swift: no Rerun SDK, no protobuf or Arrow library.
///
/// Targets the `.rrd` format of Rerun **0.38.1** (`RRF2` framing, sorbet `0.1.3` chunks),
/// uncompressed, without the optional footer — viewers read such a file with a full scan,
/// the way they read a live stream. What the viewer shows, on the `time` timeline
/// (seconds since the session started):
///
/// - `world` — Y-up, right-handed (`ViewCoordinates` RUB), static.
/// - `world/points` — the coloured map (`Points3D`), static.
/// - `world/points/live` — what the camera saw over time (`Points3D`, one row per point-cloud
///   observation, amber): the replay rebuilds the growing map and its live points from it.
/// - `world/camera` — the pose over time (`Transform3D`), the lens (`Pinhole`, static) and
///   each photo at its time (`EncodedImage`, JPEG or PNG): every photo of the session when
///   the scene has them, else the keyframes'.
/// - `world/camera_path` — the whole path (`LineStrips3D`), static. Not under
///   `world/camera`: a child of a pinhole lives in its 2D image space, and a child of the
///   moving camera would move with it.
/// - `world/planes/<id>` — the outline (`LineStrips3D`) and, when the plane has a photo,
///   the textured polygon (`Mesh3D`, RGB texels, or RGBA when some are transparent), static.
/// - `world/anchors/<id>` — the pose (`Transform3D`) and a small labelled box
///   (`Boxes3D`), static.
enum RerunRRDWriter {
    /// The Rerun release whose format this writer emits.
    static let rerunVersion: (major: UInt8, minor: UInt8, patch: UInt8) = (0, 38, 1)
    /// The timeline every temporal row is indexed on (a duration, in nanoseconds).
    static let timeline = "time"
    /// Sorbet schema version of the chunks (the `sorbet:version` schema metadata).
    static let sorbetVersion = "0.1.3"
    /// The row ids' clock: TUIDs are deterministic, so they count from a fixed instant
    /// (2026-01-01T00:00:00Z) instead of the wall clock.
    static let tuidEpochNanos: UInt64 = 1_767_225_600_000_000_000

    enum Failure: Error, Equatable {
        case pointColorCountMismatch(points: Int, colors: Int)
    }

    static func data(
        for scene: RerunExportScene,
        applicationId: String = "sceneview_ar_replay",
        recordingId: UUID = UUID()
    ) throws -> Data {
        if !scene.pointColors.isEmpty, scene.pointColors.count != scene.points.count {
            throw Failure.pointColorCountMismatch(points: scene.points.count, colors: scene.pointColors.count)
        }
        var ids = RerunTuidSequence(recordingId: recordingId)
        let store = StoreIdentity(applicationId: applicationId, recordingId: recordingId.uuidString.lowercased())

        var out = Data()
        out.append(streamHeader())
        appendMessage(kind: 1, payload: setStoreInfo(store, rowId: ids.next()), to: &out) // SetStoreInfo
        for chunk in chunks(for: scene) {
            appendMessage(kind: 2, payload: arrowMessage(chunk, store: store, ids: &ids), to: &out) // ArrowMsg
        }
        return out
    }

    // MARK: - Framing

    /// `RRF2`, the Rerun version, then the options: compression off, protobuf serializer.
    static func streamHeader() -> Data {
        Data("RRF2".utf8) + Data([rerunVersion.major, rerunVersion.minor, rerunVersion.patch, 0]) + Data([0, 2, 0, 0])
    }

    /// A 16-byte `MessageHeader` (kind, length; little-endian u64s) and the payload.
    static func appendMessage(kind: UInt64, payload: Data, to out: inout Data) {
        RerunArrowIPC.appendLittleEndian(kind, to: &out)
        RerunArrowIPC.appendLittleEndian(UInt64(payload.count), to: &out)
        out.append(payload)
    }

    struct StoreIdentity {
        var applicationId: String
        var recordingId: String
    }

    /// `rerun.common.v1alpha1.StoreId`: a recording (kind 1), its id and application.
    private static func writeStoreId(_ store: StoreIdentity, into proto: inout RerunProtobufWriter) {
        proto.uint64(1, 1)
        proto.string(2, store.recordingId)
        proto.message(3) { $0.string(1, store.applicationId) }
    }

    private static func writeTuid(_ tuid: RerunTuid, into proto: inout RerunProtobufWriter) {
        proto.fixed64(1, tuid.timeNanos)
        proto.fixed64(2, tuid.inc)
    }

    /// `rerun.log_msg.v1alpha1.SetStoreInfo`.
    static func setStoreInfo(_ store: StoreIdentity, rowId: RerunTuid) -> Data {
        var proto = RerunProtobufWriter()
        proto.message(1) { writeTuid(rowId, into: &$0) }
        proto.message(2) { info in
            info.message(2) { writeStoreId(store, into: &$0) }
            info.message(5) { source in
                source.uint64(1, 6) // STORE_SOURCE_KIND_OTHER: `extra` is a UTF-8 string.
                source.message(2) { $0.string(1, "SceneView iOS") }
            }
            info.message(6) { version in
                let bits = UInt32(rerunVersion.major) | UInt32(rerunVersion.minor) << 8 | UInt32(rerunVersion.patch) << 16
                version.int32(1, Int32(bitPattern: bits))
            }
        }
        return proto.data
    }

    /// `rerun.log_msg.v1alpha1.ArrowMsg` carrying one chunk as an uncompressed IPC stream.
    static func arrowMessage(_ chunk: RerunChunk, store: StoreIdentity, ids: inout RerunTuidSequence) -> Data {
        let chunkId = ids.next()
        let rowIds = (0..<chunk.rowCount).map { _ in ids.next() }
        let payload = chunk.arrowIPC(chunkId: chunkId, rowIds: rowIds)

        var proto = RerunProtobufWriter()
        proto.message(1) { writeStoreId(store, into: &$0) }
        proto.uint64(2, 1) // COMPRESSION_NONE
        proto.uint64(3, UInt64(payload.count))
        proto.uint64(4, 1) // ENCODING_ARROW_IPC
        proto.bytes(5, payload)
        proto.message(6) { writeTuid(chunkId, into: &$0) }
        proto.bool(7, chunk.times == nil)
        return proto.data
    }

    // MARK: - Scene → chunks

    static func chunks(for scene: RerunExportScene) -> [RerunChunk] {
        var chunks: [RerunChunk] = []

        if !scene.title.isEmpty {
            chunks.append(RerunChunk(entityPath: "/__properties", components: [
                .strings("RecordingInfo", "name", "Name", [[scene.title]]),
            ]))
        }
        chunks.append(RerunChunk(entityPath: "/world", components: [
            .u8Vectors("ViewCoordinates", "xyz", "ViewCoordinates", [viewCoordinatesRUB], size: 3),
        ]))

        if !scene.points.isEmpty {
            var components: [RerunComponentColumn] = [
                .vectors("Points3D", "positions", "Position3D", [flatten(scene.points)], size: 3),
                .floats("Points3D", "radii", "Radius", [[0.01]]),
            ]
            if !scene.pointColors.isEmpty {
                components.append(.colors("Points3D", [scene.pointColors.map { packedColor($0) }]))
            }
            chunks.append(RerunChunk(entityPath: "/world/points", components: components))
            if let live = liveChunk(for: scene) { chunks.append(live) }
        }

        chunks.append(contentsOf: cameraChunks(for: scene))

        for plane in scene.planes where plane.polygon.count >= 2 {
            var components: [RerunComponentColumn] = [
                .strips("LineStrips3D", [[plane.polygon + [plane.polygon[0]]]]),
                .colors("LineStrips3D", [[planeColor(kind: plane.kind)]]),
            ]
            if let texture = plane.texture, plane.polygon.count >= 3,
               let pixels = RerunImageCodec.rgbPixels(from: texture.imageData, maxDimension: 512) {
                components.append(contentsOf: texturedMesh(polygon: plane.polygon, texture: texture, pixels: pixels))
            }
            chunks.append(RerunChunk(entityPath: "/world/planes/\(plane.id)", components: components))
        }

        for anchor in scene.anchors {
            chunks.append(RerunChunk(entityPath: "/world/anchors/\(anchor.id)", components: [
                .vectors("Transform3D", "translation", "Translation3D", [flatten([anchor.position])], size: 3),
                .vectors("Transform3D", "quaternion", "RotationQuat", [quaternion(anchor.orientation)], size: 4),
                .vectors("Boxes3D", "half_sizes", "HalfSize3D", [[0.05, 0.05, 0.05]], size: 3),
                .colors("Boxes3D", [[packedColor(SIMD3(255, 200, 0))]]),
                .strings("Boxes3D", "labels", "Text", [[anchor.modelName ?? "anchor \(anchor.id)"]]),
            ]))
        }
        return chunks
    }

    /// One row per observation, at its time: the map points it saw. A single amber colour and
    /// radius per row (Rerun repeats them over the row's points), as the replay draws live points.
    private static func liveChunk(for scene: RerunExportScene) -> RerunChunk? {
        let observations = scene.pointObservations.enumerated()
            .map { order, observation in
                (time: observation.time, order: order,
                 points: observation.points.compactMap { scene.points.indices.contains($0) ? scene.points[$0] : nil })
            }
            .filter { !$0.points.isEmpty }
            .sorted { ($0.time, $0.order) < ($1.time, $1.order) }
        guard !observations.isEmpty else { return nil }
        return RerunChunk(
            entityPath: "/world/points/live",
            times: observations.map { nanoseconds($0.time) },
            components: [
                .vectors("Points3D", "positions", "Position3D", observations.map { flatten($0.points) }, size: 3),
                .floats("Points3D", "radii", "Radius", observations.map { _ in [livePointRadius] }),
                .colors("Points3D", observations.map { _ in [livePointColor] }),
            ]
        )
    }

    private static func cameraChunks(for scene: RerunExportScene) -> [RerunChunk] {
        var chunks: [RerunChunk] = []
        let path = scene.cameraPath.sorted { $0.time < $1.time }
        // Every photo when the scene has them, else the keyframes' (#4093).
        let keyframes = (scene.photos.isEmpty ? scene.keyframes : scene.photos).sorted { $0.time < $1.time }

        // Photos, re-encoded to JPEG when Rerun can't decode them (WebP).
        let photos = keyframes.compactMap { keyframe -> (time: Double, photo: RerunImageCodec.Photo)? in
            guard let data = scene.images[keyframe.imagePath],
                  let photo = RerunImageCodec.photo(from: data) else { return nil }
            return (keyframe.time, photo)
        }

        if let pinhole = pinhole(lens: scene.lens, photoSize: photos.first.map { ($0.photo.width, $0.photo.height) }) {
            chunks.append(RerunChunk(entityPath: "/world/camera", components: [
                .vectors("Pinhole", "image_from_camera", "PinholeProjection", [pinhole.matrix], size: 9),
                .vectors("Pinhole", "resolution", "Resolution", [pinhole.resolution], size: 2),
                .u8Vectors("Pinhole", "camera_xyz", "ViewCoordinates", [viewCoordinatesRUB], size: 3),
                .floats("Pinhole", "image_plane_distance", "ImagePlaneDistance", [[0.25]]),
            ]))
        }

        // The path's poses plus each photo's own pose, so the camera sits exactly where the
        // photo was taken at that instant (on a tie, the later row — the photo's — wins).
        let poses = (path.map { (time: $0.time, rank: 0, pose: $0) }
            + keyframes.map { (time: $0.time, rank: 1, pose: $0.pose) })
            .sorted { ($0.time, $0.rank) < ($1.time, $1.rank) }
        if !poses.isEmpty {
            chunks.append(RerunChunk(
                entityPath: "/world/camera",
                times: poses.map { nanoseconds($0.time) },
                components: [
                    .vectors("Transform3D", "translation", "Translation3D", poses.map { flatten([$0.pose.position]) }, size: 3),
                    .vectors("Transform3D", "quaternion", "RotationQuat", poses.map { quaternion($0.pose.orientation) }, size: 4),
                ]
            ))
        }

        if !photos.isEmpty {
            chunks.append(RerunChunk(
                entityPath: "/world/camera",
                times: photos.map { nanoseconds($0.time) },
                components: [
                    .blobs("EncodedImage", "blob", "Blob", photos.map { [$0.photo.data] }),
                    .strings("EncodedImage", "media_type", "MediaType", photos.map { [$0.photo.mediaType] }),
                ]
            ))
        }

        if path.count >= 2 {
            chunks.append(RerunChunk(entityPath: "/world/camera_path", components: [
                .strips("LineStrips3D", [[path.map(\.position)]]),
                .colors("LineStrips3D", [[packedColor(SIMD3(120, 200, 255))]]),
            ]))
        }
        return chunks
    }

    /// Column-major `image_from_camera` and `[width, height]`, scaled to the photos' size.
    private static func pinhole(
        lens: RerunExportScene.Lens?,
        photoSize: (width: Int, height: Int)?
    ) -> (matrix: [Float], resolution: [Float])? {
        var fx: Float, fy: Float, cx: Float, cy: Float, width: Float, height: Float
        if let lens {
            (fx, fy, cx, cy) = (lens.fx, lens.fy, lens.cx, lens.cy)
            (width, height) = (Float(lens.width), Float(lens.height))
            if let photoSize, lens.width > 0, lens.height > 0 {
                let sx = Float(photoSize.width) / width, sy = Float(photoSize.height) / height
                (fx, cx, fy, cy) = (fx * sx, cx * sx, fy * sy, cy * sy)
                (width, height) = (Float(photoSize.width), Float(photoSize.height))
            }
        } else if let photoSize {
            // No recorded lens: a 60° vertical field of view.
            (width, height) = (Float(photoSize.width), Float(photoSize.height))
            fy = height / (2 * tan(Float.pi / 6))
            (fx, cx, cy) = (fy, width / 2, height / 2)
        } else {
            return nil
        }
        return ([fx, 0, 0, 0, fy, 0, cx, cy, 1], [width, height])
    }

    /// A fan from the centroid, UV-mapped with the plane photo, as `Mesh3D` columns.
    private static func texturedMesh(
        polygon: [SIMD3<Float>],
        texture: RerunExportScene.PlaneTexture,
        pixels: RerunImageCodec.Pixels
    ) -> [RerunComponentColumn] {
        let centroid = polygon.reduce(SIMD3<Float>(), +) / Float(polygon.count)
        let vertices = [centroid] + polygon
        let uu = max(simd_dot(texture.u, texture.u), .leastNormalMagnitude)
        let vv = max(simd_dot(texture.v, texture.v), .leastNormalMagnitude)
        let texcoords = vertices.flatMap { point -> [Float] in
            let d = point - texture.origin
            return [simd_dot(d, texture.u) / uu, simd_dot(d, texture.v) / vv]
        }
        let count = UInt32(polygon.count)
        let triangles = (1...count).flatMap { i -> [UInt32] in [0, i, i % count + 1] }
        return [
            .vectors("Mesh3D", "vertex_positions", "Position3D", [flatten(vertices)], size: 3),
            .vectors("Mesh3D", "vertex_texcoords", "Texcoord2D", [texcoords], size: 2),
            .u32Vectors("Mesh3D", "triangle_indices", "TriangleIndices", [triangles], size: 3),
            // The alpha is kept: a plane photo's unseen texels are transparent black, and
            // without it a reopened file draws them as black patches (#4093).
            .blobs("Mesh3D", "albedo_texture_buffer", "ImageBuffer", [[pixels.data]]),
            .imageFormat("Mesh3D", "albedo_texture_format", width: pixels.width, height: pixels.height,
                         rgba: pixels.channels == RerunImageCodec.rgba),
        ]
    }

    // MARK: - Helpers

    /// `ViewCoordinates` RUB (Right, Up, Back): Y up, right-handed — the ARKit world frame,
    /// and the camera frame (looking down -Z, +Y up).
    static let viewCoordinatesRUB: [UInt8] = [3, 1, 6]

    static let livePointRadius: Float = 0.008
    /// `warning` (#F59E0B), the replay's live-point colour.
    static let livePointColor = packedColor(SIMD3(0xF5, 0x9E, 0x0B))

    static func nanoseconds(_ seconds: Double) -> Int64 {
        Int64((seconds * 1e9).rounded())
    }

    /// Rerun's `Color`: `0xRRGGBBAA`.
    static func packedColor(_ rgb: SIMD3<UInt8>, alpha: UInt8 = 255) -> UInt32 {
        UInt32(rgb.x) << 24 | UInt32(rgb.y) << 16 | UInt32(rgb.z) << 8 | UInt32(alpha)
    }

    static func planeColor(kind: String) -> UInt32 {
        switch kind {
        case "horizontal_upward": return packedColor(SIMD3(90, 210, 140))
        case "horizontal_downward": return packedColor(SIMD3(190, 130, 255))
        case "vertical": return packedColor(SIMD3(255, 165, 70))
        default: return packedColor(SIMD3(200, 200, 200))
        }
    }

    static func flatten(_ points: [SIMD3<Float>]) -> [Float] {
        var flat: [Float] = []
        flat.reserveCapacity(points.count * 3)
        for point in points { flat.append(contentsOf: [point.x, point.y, point.z]) }
        return flat
    }

    /// `[x, y, z, w]`, Rerun's `RotationQuat` order.
    static func quaternion(_ q: simd_quatf) -> [Float] {
        [q.imag.x, q.imag.y, q.imag.z, q.real]
    }
}

// MARK: - Chunks

/// One Rerun chunk: rows of components on one entity, on the `time` timeline or static.
struct RerunChunk {
    var entityPath: String
    /// Nanoseconds on ``RerunRRDWriter/timeline``, one per row; `nil` for static data.
    var times: [Int64]?
    var components: [RerunComponentColumn]

    var rowCount: Int { times?.count ?? 1 }

    /// The chunk as a sorbet record batch in an Arrow IPC stream.
    func arrowIPC(chunkId: RerunTuid, rowIds: [RerunTuid]) -> Data {
        var fields = [RerunArrowField(
            name: "rerun.controls.RowId",
            type: .fixedSizeBinary(16),
            nullable: false,
            metadata: [
                "ARROW:extension:metadata": #"{"namespace":"row"}"#,
                "ARROW:extension:name": "rerun.datatypes.TUID",
                "rerun:is_sorted": "true",
                "rerun:kind": "control",
            ]
        )]
        var columns = [RerunArrowArray.fixedSizeBinary(
            rowIds.reduce(into: Data()) { $0.append($1.bigEndianBytes) }, width: 16
        )]
        if let times {
            let sorted = zip(times, times.dropFirst()).allSatisfy { $0 <= $1 }
            fields.append(RerunArrowField(
                name: RerunRRDWriter.timeline,
                type: .durationNanoseconds,
                nullable: true,
                metadata: [
                    "rerun:index_name": RerunRRDWriter.timeline,
                    "rerun:is_sorted": sorted ? "true" : "false",
                    "rerun:kind": "index",
                ]
            ))
            columns.append(.primitive(times))
        }
        for component in components.sorted(by: { $0.fieldName < $1.fieldName }) {
            precondition(component.array.length == rowCount, "\(component.fieldName): one list per row")
            fields.append(component.field)
            columns.append(component.array)
        }
        return RerunArrowIPC.stream(
            fields: fields,
            columns: columns,
            rowCount: rowCount,
            metadata: [
                "rerun:entity_path": entityPath,
                "rerun:id": "chunk_\(chunkId)",
                "sorbet:version": RerunRRDWriter.sorbetVersion,
            ]
        )
    }
}

/// One component column: a list per row, each list holding that row's instances.
struct RerunComponentColumn {
    var archetype: String
    var name: String
    var componentType: String
    /// The type of one instance (the list's `item`).
    var item: RerunArrowField
    var array: RerunArrowArray

    var fieldName: String { "\(archetype):\(name)" }

    var field: RerunArrowField {
        RerunArrowField(name: fieldName, type: .list(item), nullable: true, metadata: [
            "rerun:archetype": "rerun.archetypes.\(archetype)",
            "rerun:component": fieldName,
            "rerun:component_type": "rerun.components.\(componentType)",
            "rerun:kind": "data",
        ])
    }

    private init(_ archetype: String, _ name: String, _ componentType: String,
                 item: RerunArrowField, counts: [Int], values: RerunArrowArray) {
        self.archetype = archetype
        self.name = name
        self.componentType = componentType
        self.item = item
        self.array = .list(counts: counts, child: values)
    }

    /// `fixed_size_list<float32>[size]` instances; each row is a flat list of floats.
    static func vectors(_ archetype: String, _ name: String, _ type: String, _ rows: [[Float]], size: Int) -> Self {
        Self(archetype, name, type,
             item: .item(.fixedSizeList(.item(.float32, nullable: false), size), nullable: true),
             counts: rows.map { $0.count / size },
             values: .fixedSizeList(size: size, child: .primitive(rows.flatMap { $0 })))
    }

    static func u8Vectors(_ archetype: String, _ name: String, _ type: String, _ rows: [[UInt8]], size: Int) -> Self {
        Self(archetype, name, type,
             item: .item(.fixedSizeList(.item(.uint8, nullable: false), size), nullable: true),
             counts: rows.map { $0.count / size },
             values: .fixedSizeList(size: size, child: .primitive(rows.flatMap { $0 })))
    }

    static func u32Vectors(_ archetype: String, _ name: String, _ type: String, _ rows: [[UInt32]], size: Int) -> Self {
        Self(archetype, name, type,
             item: .item(.fixedSizeList(.item(.uint32, nullable: false), size), nullable: true),
             counts: rows.map { $0.count / size },
             values: .fixedSizeList(size: size, child: .primitive(rows.flatMap { $0 })))
    }

    static func floats(_ archetype: String, _ name: String, _ type: String, _ rows: [[Float]]) -> Self {
        Self(archetype, name, type, item: .item(.float32, nullable: true),
             counts: rows.map(\.count), values: .primitive(rows.flatMap { $0 }))
    }

    /// `Color` instances, `0xRRGGBBAA`.
    static func colors(_ archetype: String, _ rows: [[UInt32]]) -> Self {
        Self(archetype, "colors", "Color", item: .item(.uint32, nullable: true),
             counts: rows.map(\.count), values: .primitive(rows.flatMap { $0 }))
    }

    static func strings(_ archetype: String, _ name: String, _ type: String, _ rows: [[String]]) -> Self {
        Self(archetype, name, type, item: .item(.utf8, nullable: true),
             counts: rows.map(\.count), values: .utf8(rows.flatMap { $0 }))
    }

    /// Byte-blob instances (`list<uint8>`): encoded images, raw texture buffers.
    static func blobs(_ archetype: String, _ name: String, _ type: String, _ rows: [[Data]]) -> Self {
        let blobs = rows.flatMap { $0 }
        let bytes = blobs.reduce(into: Data()) { $0.append($1) }
        return Self(archetype, name, type,
                    item: .item(.list(.item(.uint8, nullable: false)), nullable: true),
                    counts: rows.map(\.count),
                    values: .list(counts: blobs.map(\.count),
                                  child: RerunArrowArray(length: bytes.count, buffers: [Data(), bytes])))
    }

    /// `LineStrip3D` instances: each strip a list of 3D points.
    static func strips(_ archetype: String, _ rows: [[[SIMD3<Float>]]]) -> Self {
        let strips = rows.flatMap { $0 }
        let point = RerunArrowField.item(.fixedSizeList(.item(.float32, nullable: false), 3), nullable: false)
        return Self(archetype, "strips", "LineStrip3D",
                    item: .item(.list(point), nullable: true),
                    counts: rows.map(\.count),
                    values: .list(counts: strips.map(\.count),
                                  child: .fixedSizeList(size: 3, child: .primitive(RerunRRDWriter.flatten(strips.flatMap { $0 })))))
    }

    /// One `ImageFormat` instance: RGB, or RGBA when `rgba`, 8 bits per channel
    /// (`pixel_format` null).
    static func imageFormat(_ archetype: String, _ name: String, width: Int, height: Int, rgba: Bool = false) -> Self {
        let fields: [RerunArrowField] = [
            RerunArrowField(name: "width", type: .uint32, nullable: false),
            RerunArrowField(name: "height", type: .uint32, nullable: false),
            RerunArrowField(name: "pixel_format", type: .uint8, nullable: true),
            RerunArrowField(name: "color_model", type: .uint8, nullable: true),
            RerunArrowField(name: "channel_datatype", type: .uint8, nullable: true),
        ]
        let value = RerunArrowArray.structure(length: 1, children: [
            .primitive([UInt32(width)]),
            .primitive([UInt32(height)]),
            .allNull(count: 1, byteWidth: 1),
            .primitive([UInt8(rgba ? 3 : 2)]), // ColorModel.RGBA or ColorModel.RGB
            .primitive([UInt8(6)]), // ChannelDatatype.U8
        ])
        return Self(archetype, name, "ImageFormat", item: .item(.structure(fields), nullable: true),
                    counts: [1], values: value)
    }
}

// MARK: - Row ids

/// A Rerun TUID: 64-bit time and 64-bit counter, stored big-endian so bytes sort by time.
struct RerunTuid: Equatable, Sendable, CustomStringConvertible {
    var timeNanos: UInt64
    var inc: UInt64

    var bigEndianBytes: Data {
        var data = Data()
        withUnsafeBytes(of: timeNanos.bigEndian) { data.append(contentsOf: $0) }
        withUnsafeBytes(of: inc.bigEndian) { data.append(contentsOf: $0) }
        return data
    }

    /// Rerun's text form: time in upper-case hex, counter in lower-case hex.
    var description: String {
        hex(timeNanos).uppercased() + hex(inc)
    }

    private func hex(_ value: UInt64) -> String {
        let digits = String(value, radix: 16)
        return String(repeating: "0", count: 16 - digits.count) + digits
    }
}

/// Deterministic, strictly increasing TUIDs: a fixed clock and a counter seeded from the
/// recording id, so one scene and one recording id always give the same bytes.
struct RerunTuidSequence {
    private var current: RerunTuid

    init(recordingId: UUID) {
        let bytes = withUnsafeBytes(of: recordingId.uuid) { Array($0) }
        let seed = bytes.prefix(8).reduce(UInt64(0)) { $0 << 8 | UInt64($1) }
        // Top bits cleared: room to count without wrapping.
        current = RerunTuid(timeNanos: RerunRRDWriter.tuidEpochNanos, inc: seed & 0x0000_FFFF_FFFF_FFFF)
    }

    mutating func next() -> RerunTuid {
        current.inc += 1
        return current
    }
}

// MARK: - Images

/// ImageIO helpers: photos Rerun can decode, and raw texels for plane textures.
enum RerunImageCodec {
    /// Channels of RGB and RGBA ``Pixels``.
    static let rgb = 3
    static let rgba = 4

    struct Photo {
        var data: Data
        var mediaType: String
        var width: Int
        var height: Int
    }

    struct Pixels {
        var width: Int
        var height: Int
        /// Row-major, 8 bits per channel, top row first: RGB, or RGBA (straight alpha) when
        /// ``channels`` is 4.
        var data: Data
        var channels: Int
    }

    /// JPEG and PNG pass through; anything else ImageIO reads (WebP, HEIC) becomes JPEG,
    /// since Rerun's `EncodedImage` decoders don't all read WebP.
    static func photo(from data: Data) -> Photo? {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil),
              CGImageSourceGetCount(source) > 0,
              let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
              let width = properties[kCGImagePropertyPixelWidth] as? Int,
              let height = properties[kCGImagePropertyPixelHeight] as? Int else { return nil }
        switch CGImageSourceGetType(source) as String? {
        case "public.jpeg":
            return Photo(data: data, mediaType: "image/jpeg", width: width, height: height)
        case "public.png":
            return Photo(data: data, mediaType: "image/png", width: width, height: height)
        default:
            guard let image = CGImageSourceCreateImageAtIndex(source, 0, nil),
                  let jpeg = jpegData(image) else { return nil }
            return Photo(data: jpeg, mediaType: "image/jpeg", width: image.width, height: image.height)
        }
    }

    static func jpegData(_ image: CGImage, quality: Double = 0.9) -> Data? {
        let output = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(output as CFMutableData, "public.jpeg" as CFString, 1, nil)
        else { return nil }
        CGImageDestinationAddImage(destination, image, [kCGImageDestinationLossyCompressionQuality: quality] as CFDictionary)
        guard CGImageDestinationFinalize(destination) else { return nil }
        return output as Data
    }

    /// Decodes `data`, downscaled so its longer side is at most `maxDimension`: RGB, or RGBA
    /// when one of its texels is not opaque. An image already small enough is not resampled.
    static func rgbPixels(from data: Data, maxDimension: Int) -> Pixels? {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil), CGImageSourceGetCount(source) > 0
        else { return nil }
        let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any]
        let upright = (properties?[kCGImagePropertyOrientation] as? Int).map { $0 == 1 } ?? true
        let full = upright ? CGImageSourceCreateImageAtIndex(source, 0, nil) : nil
        let image: CGImage
        if let full, max(full.width, full.height) <= maxDimension {
            image = full
        } else {
            let options: [CFString: Any] = [
                kCGImageSourceCreateThumbnailFromImageAlways: true,
                kCGImageSourceCreateThumbnailWithTransform: true,
                kCGImageSourceThumbnailMaxPixelSize: maxDimension,
            ]
            guard let thumbnail = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else { return nil }
            image = thumbnail
        }
        guard let space = CGColorSpace(name: CGColorSpace.sRGB) else { return nil }
        let (width, height) = (image.width, image.height)
        let hasAlpha: Bool
        switch image.alphaInfo {
        case .none, .noneSkipFirst, .noneSkipLast: hasAlpha = false
        default: hasAlpha = true
        }
        // CoreGraphics only draws 8-bit RGBA premultiplied; straight alpha is recovered below.
        var rgba = [UInt8](repeating: 0, count: width * height * 4)
        let drawn = rgba.withUnsafeMutableBytes { buffer -> Bool in
            guard let context = CGContext(
                data: buffer.baseAddress, width: width, height: height, bitsPerComponent: 8,
                bytesPerRow: width * 4, space: space,
                bitmapInfo: (hasAlpha ? CGImageAlphaInfo.premultipliedLast : .noneSkipLast).rawValue
            ) else { return false }
            context.setBlendMode(.copy)
            context.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
            return true
        }
        guard drawn else { return nil }
        let count = width * height
        let transparent = hasAlpha && (0..<count).contains { rgba[$0 * 4 + 3] != 255 }
        let channels = transparent ? Self.rgba : Self.rgb
        var texels = Data(count: count * channels)
        texels.withUnsafeMutableBytes { (out: UnsafeMutableRawBufferPointer) in
            for pixel in 0..<count {
                let alpha = transparent ? Int(rgba[pixel * 4 + 3]) : 255
                for channel in 0..<3 {
                    let value = Int(rgba[pixel * 4 + channel])
                    out[pixel * channels + channel] = alpha == 255 ? UInt8(value)
                        : alpha == 0 ? 0 : UInt8(min(255, (value * 255 + alpha / 2) / alpha))
                }
                if transparent { out[pixel * channels + 3] = UInt8(alpha) }
            }
        }
        return Pixels(width: width, height: height, data: texels, channels: channels)
    }
}

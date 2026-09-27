import Foundation
import simd

/// The four open formats a replay exports to, in the order the share sheet lists them.
enum RerunExportFormat: String, CaseIterable, Identifiable, Sendable {
    case rrd, glb, usdz, ply

    var id: String { rawValue }
    var fileExtension: String { rawValue }

    var title: String {
        switch self {
        case .rrd: "Rerun recording"
        case .glb: "glTF scene"
        case .usdz: "USDZ scene"
        case .ply: "Point cloud"
        }
    }

    /// Where the file opens — what a user reaches for it with.
    var detail: String {
        switch self {
        case .rrd: "The whole timeline, for the Rerun viewer"
        case .glb: "Blender, three.js, any glTF tool"
        case .usdz: "AR Quick Look, Reality Composer"
        case .ply: "Coloured points for MeshLab, Open3D"
        }
    }

    var icon: String {
        switch self {
        case .rrd: "timeline.selection"
        case .glb: "cube"
        case .usdz: "arkit"
        case .ply: "circle.grid.3x3"
        }
    }
}

/// Turns a replay's pack into a ``RerunExportScene`` and writes it in every format, on the phone.
enum RerunExportAdapter {
    /// The model the demo places on every anchor, as the bundled resource name.
    static let anchorModel = "shiba"

    /// The whole session as the exporters see it: every map point, the full camera path, the
    /// keyframe photos the replay draws in their frustums, the planes as they ended, and the
    /// placed models.
    static func scene(for pack: RerunPack) -> RerunExportScene {
        let trace = pack.trace
        let whole = trace.frameAt(trace.duration)

        let points = Array(whole.mapPoints)
        let colors: [SIMD3<UInt8>] = (0..<points.count).map { i in
            guard let packed = whole.mapPointColors.map({ $0[$0.startIndex + i] }), packed != 0 else {
                return SIMD3(255, 255, 255)
            }
            return SIMD3(UInt8((packed >> 16) & 0xFF), UInt8((packed >> 8) & 0xFF), UInt8(packed & 0xFF))
        }

        let path = zip(trace.poseTimes, trace.poses).map { time, pose in
            RerunExportScene.CameraSample(time: Double(time), position: pose.position, orientation: pose.rotation)
        }

        var keyframes: [RerunExportScene.Keyframe] = []
        var images: [String: Data] = [:]
        let imageTimes = Dictionary(zip(trace.imagePaths, trace.imageTimes), uniquingKeysWith: { first, _ in first })
        for (pose, image) in zip(whole.keyframes, whole.keyframeImages) {
            guard let image, let bytes = pack.bytes(for: image) else { continue }
            let time = Double(imageTimes[image] ?? 0)
            let sample = RerunExportScene.CameraSample(time: time, position: pose.position, orientation: pose.rotation)
            keyframes.append(.init(time: time, imagePath: image, pose: sample))
            images[image] = bytes
        }

        let planes = whole.planes.map { plane in
            let texture = pack.manifest.texture(for: plane.id).flatMap { texture -> RerunExportScene.PlaneTexture? in
                guard let bytes = pack.bytes(for: texture.path) else { return nil }
                return .init(imageData: bytes, origin: texture.origin, u: texture.u, v: texture.v)
            }
            return RerunExportScene.Plane(id: plane.id, kind: plane.kind.rawValue, polygon: plane.polygon, texture: texture)
        }

        let anchors = whole.anchors.map { anchor in
            RerunExportScene.Anchor(id: anchor.id, position: anchor.pose.position,
                                    orientation: anchor.pose.rotation, modelName: anchorModel)
        }

        return RerunExportScene(
            title: pack.title,
            lens: pack.manifest.intrinsics.map { lens in
                .init(width: Int(lens.width), height: Int(lens.height), fx: lens.fx, fy: lens.fy, cx: lens.cx, cy: lens.cy)
            },
            points: points,
            pointColors: colors,
            cameraPath: path,
            keyframes: keyframes,
            images: images,
            planes: planes,
            anchors: anchors
        )
    }

    /// `"Recorded room"` → `recorded-room`.
    static func fileStem(_ title: String) -> String {
        let words = title.lowercased().split { !$0.isLetter && !$0.isNumber }
        return words.isEmpty ? "space" : words.joined(separator: "-")
    }

    /// The bytes of `format` for `scene`. `models` holds the anchor model per format family:
    /// GLB bytes for `.glb`, USDZ bytes for `.usdz`.
    static func data(_ format: RerunExportFormat, scene: RerunExportScene,
                     glbModels: [String: Data], usdzModels: [String: Data]) throws -> Data {
        switch format {
        case .rrd: try RerunRRDWriter.data(for: scene)
        case .glb: try RerunGLBWriter.data(for: scene, models: glbModels)
        case .usdz: try RerunUSDZWriter.data(for: scene, models: usdzModels)
        case .ply: RerunPLYWriter.data(for: scene)
        }
    }

    /// The bundled anchor model in `ext`, keyed by ``anchorModel``; empty when it is missing.
    static func bundledModels(_ ext: String, bundle: Bundle = .main) -> [String: Data] {
        guard let url = bundle.url(forResource: anchorModel, withExtension: ext),
              let data = try? Data(contentsOf: url, options: .mappedIfSafe) else { return [:] }
        return [anchorModel: data]
    }

    /// Writes `format` into a fresh folder under the temporary directory and returns the file.
    static func write(_ format: RerunExportFormat, scene: RerunExportScene, directory: URL) throws -> URL {
        let data = try data(format, scene: scene,
                            glbModels: format == .glb ? bundledModels("glb") : [:],
                            usdzModels: format == .usdz ? bundledModels("usdz") : [:])
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let url = directory.appendingPathComponent(fileStem(scene.title)).appendingPathExtension(format.fileExtension)
        try data.write(to: url, options: .atomic)
        return url
    }

    /// A folder of its own per export, so sharing twice never hands over a half-written file.
    static func freshDirectory() -> URL {
        FileManager.default.temporaryDirectory
            .appendingPathComponent("rerun-export", isDirectory: true)
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
    }
}

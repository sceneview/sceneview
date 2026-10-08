import CoreGraphics
import CoreVideo
import Foundation
import ImageIO
import simd

// The "Record your own" half of the Rerun showcase: turns a live AR session into the same
// three payloads the bundled showcase ships (manifest, JSON-lines session, media archive),
// so the replay screen loads a capture made on the phone exactly like the bundled one.
//
// Pure Swift on purpose — no ARKit, RealityKit or UIKit — so every rule here (the portrait
// pose convention, keyframe spacing, colour sampling, plane bookkeeping, the wire format)
// is unit-tested on macOS. `RerunLiveCaptureView` adapts `ARFrame` to `RerunCaptureFrame`.
//
// Format: `samples/android-demo/src/main/assets/rerun/showcase/` is the reference; the
// Android reader is `RerunReplay.kt`. Pose convention on both platforms: camera-to-world,
// the camera looks down its local -Z, local +Y is the photo's up, photos are portrait.

// MARK: - Output

/// A finished capture: the three payloads of the bundled showcase, in memory.
///
/// `manifest` is `showcase-manifest.json`'s format, `log` the JSON-lines session,
/// `media` the photos concatenated in the order the manifest indexes them.
struct RerunCapturePack: Sendable, Equatable {
    var manifest: Data
    var log: Data
    var media: Data

    static let manifestFileName = "capture-manifest.json"
    static let logFileName = "capture-session.jsonl"
    static let mediaFileName = "capture-media.bin"

    /// Writes the three files into `directory`, creating it when missing. Each file is
    /// replaced atomically, so a reader never sees half a capture file.
    func write(to directory: URL) throws {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        try manifest.write(to: directory.appendingPathComponent(Self.manifestFileName), options: .atomic)
        try log.write(to: directory.appendingPathComponent(Self.logFileName), options: .atomic)
        try media.write(to: directory.appendingPathComponent(Self.mediaFileName), options: .atomic)
    }

    /// Reads a capture written by ``write(to:)``; `nil` when any of the three files is missing.
    static func read(from directory: URL) -> RerunCapturePack? {
        guard
            let manifest = try? Data(contentsOf: directory.appendingPathComponent(manifestFileName)),
            let log = try? Data(contentsOf: directory.appendingPathComponent(logFileName)),
            let media = try? Data(contentsOf: directory.appendingPathComponent(mediaFileName))
        else { return nil }
        return RerunCapturePack(manifest: manifest, log: log, media: media)
    }
}

// MARK: - Lens

/// A pinhole lens in pixels: image `width` × `height`, focal lengths `fx`, `fy`, principal
/// point `cx`, `cy`. Image `+x` is right, `+y` is down.
struct RerunPinhole: Sendable, Equatable {
    var width: Int
    var height: Int
    var fx: Float
    var fy: Float
    var cx: Float
    var cy: Float

    /// From ARKit's `ARCamera.intrinsics` (column-major: `fx` at `[0][0]`, `fy` at `[1][1]`,
    /// `cx` at `[2][0]`, `cy` at `[2][1]`) and `imageResolution`.
    init(intrinsics k: simd_float3x3, width: Int, height: Int) {
        self.init(width: width, height: height,
                  fx: k.columns.0.x, fy: k.columns.1.y, cx: k.columns.2.x, cy: k.columns.2.y)
    }

    init(width: Int, height: Int, fx: Float, fy: Float, cx: Float, cy: Float) {
        self.width = width
        self.height = height
        self.fx = fx
        self.fy = fy
        self.cx = cx
        self.cy = cy
    }

    /// The same lens once the sensor's landscape image is turned 90° clockwise to portrait
    /// and scaled to `portraitWidth` pixels wide. Sensor pixel `(x, y)` becomes portrait
    /// pixel `((H - y)·s, x·s)`, so the axes swap: `fx' = fy·s`, `cx' = (H - cy)·s`,
    /// `fy' = fx·s`, `cy' = cx·s`. Pairs with ``RerunCaptureMath/portraitPose(fromSensor:)``.
    func portrait(width portraitWidth: Int) -> RerunPinhole {
        let s = Float(portraitWidth) / Float(height)
        return RerunPinhole(
            width: portraitWidth,
            height: Int((Float(width) * s).rounded()),
            fx: fy * s,
            fy: fx * s,
            cx: (Float(height) - cy) * s,
            cy: cx * s
        )
    }
}

// MARK: - Geometry

/// The geometry of the capture, as free functions so each rule has its own test.
enum RerunCaptureMath {
    /// Rotation by +90° about the camera's Z axis.
    ///
    /// ARKit's camera frame is the sensor's landscape frame: `+X` runs along the long side of
    /// the phone toward its bottom (Home button side), `+Y` along the short side, and the
    /// captured image's `+x` is camera `+X`, its `+y` camera `-Y`. Held upright in portrait,
    /// camera `+X` points at the floor. The portrait photo is the captured image turned 90°
    /// clockwise, so its up is camera `-X` and its right is camera `+Y`:
    /// `pose' = pose · rotZ(+90°)` gives `X' = +Y`, `Y' = -X`, `Z' = Z`.
    static let portraitRotation = simd_float4x4(
        SIMD4<Float>(0, 1, 0, 0),
        SIMD4<Float>(-1, 0, 0, 0),
        SIMD4<Float>(0, 0, 1, 0),
        SIMD4<Float>(0, 0, 0, 1)
    )

    /// The portrait-photo pose of an ARKit camera transform (camera-to-world). See
    /// ``portraitRotation``.
    static func portraitPose(fromSensor transform: simd_float4x4) -> simd_float4x4 {
        transform * portraitRotation
    }

    /// Where sensor pixel `p` of a `sensorSize` landscape image lands in the portrait image
    /// `portraitWidth` pixels wide — the 90° clockwise turn the photos get.
    static func portraitPixel(fromSensor p: SIMD2<Float>, sensorSize: SIMD2<Float>, portraitWidth: Int) -> SIMD2<Float> {
        let s = Float(portraitWidth) / sensorSize.y
        return SIMD2((sensorSize.y - p.y) * s, p.x * s)
    }

    static func translation(of m: simd_float4x4) -> SIMD3<Float> {
        SIMD3(m.columns.3.x, m.columns.3.y, m.columns.3.z)
    }

    /// The rotation of `m` as a unit quaternion with `w >= 0` (one sign per rotation, so two
    /// logs of the same pose are identical).
    static func orientation(of m: simd_float4x4) -> simd_quatf {
        let r = simd_float3x3(
            SIMD3(m.columns.0.x, m.columns.0.y, m.columns.0.z),
            SIMD3(m.columns.1.x, m.columns.1.y, m.columns.1.z),
            SIMD3(m.columns.2.x, m.columns.2.y, m.columns.2.z)
        )
        let q = simd_normalize(simd_quatf(r))
        return q.real < 0 ? simd_quatf(vector: -q.vector) : q
    }

    /// The angle, in radians, of the rotation that takes `a` to `b`.
    static func angle(between a: simd_quatf, and b: simd_quatf) -> Float {
        let dot = min(1, abs(simd_dot(a.vector, b.vector)))
        return 2 * acos(dot)
    }

    /// A yaw-only orientation whose local `+Z` points from `position` toward `target` on the
    /// horizontal plane — how a placed model turns to face the person who placed it.
    static func facing(from position: SIMD3<Float>, toward target: SIMD3<Float>) -> simd_quatf {
        let d = SIMD2(target.x - position.x, target.z - position.z)
        guard simd_length_squared(d) > 1e-8 else { return simd_quatf(ix: 0, iy: 0, iz: 0, r: 1) }
        return simd_quatf(angle: atan2(d.x, d.y), axis: SIMD3(0, 1, 0))
    }

    /// ARKit's plane boundary (anchor-local, `geometry.boundaryVertices`) in world space.
    static func worldPolygon(boundary: [SIMD3<Float>], anchorTransform: simd_float4x4) -> [SIMD3<Float>] {
        boundary.map { v in
            let w = anchorTransform * SIMD4(v.x, v.y, v.z, 1)
            return SIMD3(w.x, w.y, w.z)
        }
    }

    /// Area of a planar polygon, square metres — whatever its tilt and winding. Fewer than
    /// three corners, or a corner that is not a number, is no surface: `0` (Android's
    /// `ArDebugStats.polygonArea`).
    static func area(of polygon: [SIMD3<Float>]) -> Float {
        guard polygon.count >= 3 else { return 0 }
        var sum = SIMD3<Float>(repeating: 0)
        for i in 0..<polygon.count {
            sum += simd_cross(polygon[i], polygon[(i + 1) % polygon.count])
        }
        let area = simd_length(sum) / 2
        return area.isFinite ? area : 0
    }

    /// How much surface `polygons` cover together, square metres. An area, not a count:
    /// ARKit's planes are born, merge and are dropped, so their number goes up and down over
    /// one room while what they cover only grows.
    static func surfaceArea(_ polygons: [[SIMD3<Float>]]) -> Float {
        polygons.reduce(0) { $0 + area(of: $1) }
    }
}

/// Projects world points into one camera's image. The inverse pose is computed once, so
/// a few hundred feature points per frame cost a matrix-vector product each.
struct RerunProjector: Sendable {
    let worldToCamera: simd_float4x4
    let lens: RerunPinhole

    /// Points closer than this in front of the camera are dropped.
    static let nearPlane: Float = 0.05

    /// `cameraToWorld` in the wire format's convention: the camera looks down its local -Z,
    /// image `+y` is camera `-Y`.
    init(cameraToWorld: simd_float4x4, lens: RerunPinhole) {
        worldToCamera = cameraToWorld.inverse
        self.lens = lens
    }

    /// The pixel `world` lands on, `nil` behind the camera or outside the image.
    func project(_ world: SIMD3<Float>) -> SIMD2<Float>? {
        let p = worldToCamera * SIMD4(world.x, world.y, world.z, 1)
        let depth = -p.z
        guard depth > Self.nearPlane else { return nil }
        let u = lens.cx + lens.fx * p.x / depth
        let v = lens.cy - lens.fy * p.y / depth
        guard u >= 0, v >= 0, u < Float(lens.width), v < Float(lens.height) else { return nil }
        return SIMD2(u, v)
    }
}

// MARK: - Colour

/// ARKit's camera image: bi-planar 4:2:0 YCbCr (`420f`, BT.601 full range) — a full-size
/// luma plane and a half-size plane of interleaved Cb, Cr.
///
/// A non-owning view of the two planes: valid only inside
/// ``withPlanes(of:_:)`` (or while the caller keeps the memory alive in a test).
struct RerunYCbCrImage {
    let luma: UnsafePointer<UInt8>
    let lumaBytesPerRow: Int
    let chroma: UnsafePointer<UInt8>
    let chromaBytesPerRow: Int
    let width: Int
    let height: Int
    /// `false` for video range (`420v`: Y in 16…235, CbCr in 16…240).
    var fullRange: Bool = true

    /// BT.601 YCbCr → sRGB bytes. Full range: `R = Y + 1.402·Cr'`,
    /// `G = Y - 0.344136·Cb' - 0.714136·Cr'`, `B = Y + 1.772·Cb'` with `C' = C - 128`.
    static func rgb(y: UInt8, cb: UInt8, cr: UInt8, fullRange: Bool = true) -> SIMD3<UInt8> {
        var yy = Float(y)
        var cbf = Float(cb) - 128
        var crf = Float(cr) - 128
        if !fullRange {
            yy = (yy - 16) * (255 / 219)
            cbf *= 255 / 224
            crf *= 255 / 224
        }
        return SIMD3(
            clampByte(yy + 1.402 * crf),
            clampByte(yy - 0.344136 * cbf - 0.714136 * crf),
            clampByte(yy + 1.772 * cbf)
        )
    }

    private static func clampByte(_ v: Float) -> UInt8 {
        UInt8(max(0, min(255, v.rounded())))
    }

    /// The colour of pixel (`x`, `y`), clamped to the image.
    func color(x: Int, y: Int) -> SIMD3<UInt8> {
        let xi = max(0, min(width - 1, x))
        let yi = max(0, min(height - 1, y))
        let c = (yi / 2) * chromaBytesPerRow + (xi / 2) * 2
        return Self.rgb(y: luma[yi * lumaBytesPerRow + xi], cb: chroma[c], cr: chroma[c + 1], fullRange: fullRange)
    }

    /// The colour at a continuous pixel position (the pixel it falls in), `nil` outside.
    func color(at p: SIMD2<Float>) -> SIMD3<UInt8>? {
        guard p.x >= 0, p.y >= 0, p.x < Float(width), p.y < Float(height) else { return nil }
        return color(x: Int(p.x), y: Int(p.y))
    }

    /// The image turned 90° clockwise and scaled to `outWidth` × `outHeight`, as RGBX bytes
    /// (4 per pixel, the fourth unused) — the portrait photo. Luma is a 2×2 box average,
    /// chroma the nearest sample.
    func portraitRGBX(width outWidth: Int, height outHeight: Int) -> [UInt8] {
        // Portrait (x', y') samples sensor (y' / sy, H - x' / sx): column x' walks the sensor
        // rows bottom to top, row y' walks the sensor columns left to right.
        let sx = Float(outWidth) / Float(height)
        let sy = Float(outHeight) / Float(width)
        let sensorRow = (0..<outWidth).map { x in
            max(0, min(height - 2, Int(Float(height) - (Float(x) + 0.5) / sx)))
        }
        let sensorColumn = (0..<outHeight).map { y in
            max(0, min(width - 2, Int((Float(y) + 0.5) / sy)))
        }
        var out = [UInt8](repeating: 255, count: outWidth * outHeight * 4)
        out.withUnsafeMutableBufferPointer { dst in
            for y in 0..<outHeight {
                let col = sensorColumn[y]
                let chromaCol = (col / 2) * 2
                for x in 0..<outWidth {
                    let row = sensorRow[x]
                    let l = row * lumaBytesPerRow + col
                    let lumaSum = Int(luma[l]) + Int(luma[l + 1])
                        + Int(luma[l + lumaBytesPerRow]) + Int(luma[l + lumaBytesPerRow + 1])
                    let c = (row / 2) * chromaBytesPerRow + chromaCol
                    let rgb = Self.rgb(y: UInt8(lumaSum / 4), cb: chroma[c], cr: chroma[c + 1], fullRange: fullRange)
                    let o = (y * outWidth + x) * 4
                    dst[o] = rgb.x
                    dst[o + 1] = rgb.y
                    dst[o + 2] = rgb.z
                }
            }
        }
        return out
    }

    /// Runs `body` with the planes of a bi-planar 4:2:0 pixel buffer, locked read-only for
    /// the duration. `nil` when the buffer is not bi-planar 4:2:0 YCbCr.
    static func withPlanes<R>(of buffer: CVPixelBuffer, _ body: (RerunYCbCrImage) -> R) -> R? {
        let format = CVPixelBufferGetPixelFormatType(buffer)
        let fullRange: Bool
        switch format {
        case kCVPixelFormatType_420YpCbCr8BiPlanarFullRange: fullRange = true
        case kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange: fullRange = false
        default: return nil
        }
        guard CVPixelBufferGetPlaneCount(buffer) == 2 else { return nil }
        CVPixelBufferLockBaseAddress(buffer, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(buffer, .readOnly) }
        guard
            let lumaBase = CVPixelBufferGetBaseAddressOfPlane(buffer, 0),
            let chromaBase = CVPixelBufferGetBaseAddressOfPlane(buffer, 1)
        else { return nil }
        let image = RerunYCbCrImage(
            luma: UnsafePointer(lumaBase.assumingMemoryBound(to: UInt8.self)),
            lumaBytesPerRow: CVPixelBufferGetBytesPerRowOfPlane(buffer, 0),
            chroma: UnsafePointer(chromaBase.assumingMemoryBound(to: UInt8.self)),
            chromaBytesPerRow: CVPixelBufferGetBytesPerRowOfPlane(buffer, 1),
            width: CVPixelBufferGetWidthOfPlane(buffer, 0),
            height: CVPixelBufferGetHeightOfPlane(buffer, 0),
            fullRange: fullRange
        )
        return body(image)
    }
}

/// JPEG encoding through ImageIO. iOS has no WebP encoder; the readers decode by content,
/// so a `.jpg` in the media archive is read exactly like the showcase's `.webp`.
enum RerunJPEG {
    /// Encodes RGBX bytes (4 per pixel, the fourth ignored), `nil` when ImageIO refuses.
    static func encode(rgbx: [UInt8], width: Int, height: Int, quality: Double) -> Data? {
        guard width > 0, height > 0, rgbx.count >= width * height * 4 else { return nil }
        let bitmapInfo = CGBitmapInfo(rawValue: CGImageAlphaInfo.noneSkipLast.rawValue)
        guard
            let provider = CGDataProvider(data: Data(rgbx) as CFData),
            let image = CGImage(
                width: width, height: height, bitsPerComponent: 8, bitsPerPixel: 32,
                bytesPerRow: width * 4, space: CGColorSpace(name: CGColorSpace.sRGB)!,
                bitmapInfo: bitmapInfo, provider: provider, decode: nil,
                shouldInterpolate: false, intent: .defaultIntent
            )
        else { return nil }
        let data = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(data, "public.jpeg" as CFString, 1, nil) else {
            return nil
        }
        let options = [kCGImageDestinationLossyCompressionQuality: quality] as CFDictionary
        CGImageDestinationAddImage(destination, image, options)
        guard CGImageDestinationFinalize(destination) else { return nil }
        return data as Data
    }
}

// MARK: - Input

/// The wire format's plane kinds.
enum RerunCapturePlaneKind: String, Sendable {
    case horizontalUpward = "horizontal_upward"
    case horizontalDownward = "horizontal_downward"
    case vertical
    case unknown

    /// From ARKit's alignment and classification: a horizontal plane faces down when it is
    /// a ceiling or its world normal (the anchor's local `+Y`) points down.
    static func of(isHorizontal: Bool, isVertical: Bool, worldNormal: SIMD3<Float>, isCeiling: Bool) -> Self {
        if isHorizontal { return isCeiling || worldNormal.y < 0 ? .horizontalDownward : .horizontalUpward }
        if isVertical { return .vertical }
        return .unknown
    }
}

/// One detected plane as the session sees it now.
struct RerunCapturePlane: Sendable, Equatable {
    /// ARKit's anchor identifier; the recorder maps it to a small stable int.
    var identifier: UUID
    var kind: RerunCapturePlaneKind
    /// The boundary in world space, in order (``RerunCaptureMath/worldPolygon(boundary:anchorTransform:)``).
    var polygon: [SIMD3<Float>]
}

/// One AR frame, as much of it as the recorder reads. Implemented over `ARFrame` by the
/// live view and by plain values in the tests; the expensive parts (planes, colours, the
/// photo) are methods so the recorder only pays for them when it needs them.
protocol RerunCaptureFrame {
    /// Seconds, on a clock that only moves forward (`ARFrame.timestamp`).
    var timestamp: TimeInterval { get }
    /// ARKit's `ARCamera.transform`: camera-to-world in the sensor's landscape frame.
    var cameraTransform: simd_float4x4 { get }
    /// The captured image's lens, landscape (`ARCamera.intrinsics`, `imageResolution`).
    var sensorLens: RerunPinhole { get }
    /// Whether tracking is `.normal`; limited frames move no pose, take no photo or point.
    var isTrackingNormal: Bool { get }
    /// `ARFrame.rawFeaturePoints`, world space.
    func featurePoints() -> [SIMD3<Float>]
    /// The planes the session tracks now.
    func planes() -> [RerunCapturePlane]
    /// Runs `body` with a colour lookup by sensor pixel (`nil` outside the image).
    func withColorLookup<R>(_ body: (_ color: (SIMD2<Float>) -> SIMD3<UInt8>?) -> R) -> R
    /// The captured image turned to portrait, scaled to `width` × `height`, as JPEG.
    func portraitJPEG(width: Int, height: Int, quality: Double) -> Data?
}

/// A ``RerunCaptureFrame`` over a captured pixel buffer — what the live view builds from
/// an `ARFrame`, and what the tests build from a synthetic buffer.
struct RerunPixelBufferFrame: RerunCaptureFrame {
    var timestamp: TimeInterval
    var cameraTransform: simd_float4x4
    var sensorLens: RerunPinhole
    var isTrackingNormal: Bool
    var image: CVPixelBuffer?
    var points: () -> [SIMD3<Float>]
    var planeList: () -> [RerunCapturePlane]

    func featurePoints() -> [SIMD3<Float>] { points() }

    func planes() -> [RerunCapturePlane] { planeList() }

    func withColorLookup<R>(_ body: ((SIMD2<Float>) -> SIMD3<UInt8>?) -> R) -> R {
        if let image, let result = RerunYCbCrImage.withPlanes(of: image, { planes in body { planes.color(at: $0) } }) {
            return result
        }
        return body { _ in nil }
    }

    func portraitJPEG(width: Int, height: Int, quality: Double) -> Data? {
        guard let image else { return nil }
        let rgbx = RerunYCbCrImage.withPlanes(of: image) { $0.portraitRGBX(width: width, height: height) }
        return rgbx.flatMap { RerunJPEG.encode(rgbx: $0, width: width, height: height, quality: quality) }
    }
}

// MARK: - Recorder

/// Accumulates a live AR session into a ``RerunCapturePack``.
///
/// Feed it every frame (``add(_:)``) and every placed model (``addAnchor(position:orientation:)``),
/// read ``stats`` for the UI, call ``finish()`` once. A value type, driven from the main
/// actor by the view; ``finish()`` can run on any thread.
///
/// What it keeps, and its bounds (``Configuration``):
/// - **Poses** thinned by motion — a pose within 5 mm and 1° of the last kept one only
///   moves the clock (Android's `ArDebugTrace`) — capped at 20 000, halved when full
///   (keyframe poses always stay).
/// - **Photos** on the first frame, then every 15 cm of travel or 10° of turn, portrait
///   480 px wide, JPEG 0.6: at most 300 photos or 24 MB, then the path goes on without them.
/// - **Feature points** every 0.2 s, coloured from the camera image, at most 400 per
///   observation and 150 000 in all. ``Stats/points`` counts the 3 cm voxels they fill, up
///   to the 12 000 the opened scan keeps (``RerunTrace/maxMapPoints``): the figure on screen
///   is the scan's, and ``Stats/pointBudget`` is what it counts against.
/// - **Planes** re-logged every 0.5 s when their boundary moved 2 cm or changed shape; a
///   plane ARKit drops is logged with an empty polygon.
/// - A capture stops growing at 5 minutes (``isFull``).
///
/// Every bound is announced before it is hit: ``RerunScanFigures`` reads them off ``stats``.
///
/// LiDAR scene meshes are not recorded: the wire format has no mesh event.
struct RerunCaptureRecorder: Sendable {
    struct Configuration: Sendable {
        var poseMinStep: Float = 0.005
        var poseMinTurnDegrees: Float = 1
        var maxPoses = 20_000
        var keyframeTravel: Float = 0.15
        var keyframeTurnDegrees: Float = 10
        /// Never two photos closer in time than this, however fast the phone moves.
        var keyframeMinInterval: TimeInterval = 0.1
        var maxKeyframes = 300
        var maxMediaBytes = 24 * 1024 * 1024
        /// Portrait photo width; the height follows the sensor's aspect (640 for 4:3).
        var photoWidth = 480
        var jpegQuality = 0.6
        var pointInterval: TimeInterval = 0.2
        var maxPointsPerObservation = 400
        var maxPoints = 150_000
        var pointVoxel: Float = 0.03
        /// The points the live count and the live cloud run to: what the opened scan keeps.
        /// More would be a figure on screen the replay never shows.
        var maxVoxels = RerunTrace.maxMapPoints
        var planeInterval: TimeInterval = 0.5
        var planeMinChange: Float = 0.02
        var maxDuration: TimeInterval = 300
    }

    /// What the recording screen shows while recording.
    struct Stats: Sendable, Equatable {
        var duration: TimeInterval = 0
        /// Metres walked, along the thinned camera path.
        var pathLength: Float = 0
        var keyframes = 0
        /// Planes tracked now.
        var planes = 0
        /// Distinct 3 cm voxels the feature points filled.
        var points = 0
        /// The most points this scan can hold: ``Configuration/maxVoxels``, or fewer once
        /// the observation cap (``Configuration/maxPoints``) leaves no room to reach it.
        var pointBudget = RerunScanLimits.pointBudget
        /// Square metres the planes tracked now cover.
        var surfaceArea: Float = 0
        /// The most photos this scan can hold: ``Configuration/maxKeyframes``, or the
        /// photos taken once the media cap stopped them first.
        var photoBudget = RerunScanLimits.photoBudget
        /// Where the whole scan stops (``Configuration/maxDuration``), seconds.
        var durationBudget: TimeInterval = RerunScanLimits.duration
        var anchors = 0
        /// The photo cap was reached; poses, points and planes go on.
        var isPhotoLimitReached = false
    }

    let configuration: Configuration
    private(set) var stats = Stats()

    private struct PoseSample: Sendable {
        var t: Int64
        var position: SIMD3<Float>
        var orientation: simd_quatf
        /// A photo was taken from this pose; thinning never drops it.
        var pinned: Bool
    }

    private struct MediaEntry: Sendable {
        var path: String
        var offset: Int
        var length: Int
    }

    private var origin: TimeInterval?
    private var lastTimestamp: TimeInterval = -.infinity
    private var lastNanos: Int64 = 0
    private var poses: [PoseSample] = []
    /// Image, point-cloud, plane and anchor lines, in time order.
    private var events: [(t: Int64, line: String)] = []
    private var media = Data()
    private var mediaEntries: [MediaEntry] = []
    private var photoLens: RerunPinhole?
    private var lastKeyframe: (position: SIMD3<Float>, orientation: simd_quatf, time: TimeInterval)?
    private var lastPointsTime: TimeInterval = -.infinity
    private var pointsLogged = 0
    private var voxels = Set<Int64>()
    /// The first point that filled each voxel, for the live view to draw the cloud growing.
    private(set) var voxelPoints: [SIMD3<Float>] = []
    /// The camera's colour under each of ``voxelPoints``, `0xFFRRGGBB`, same order.
    private(set) var voxelColors: [UInt32] = []
    private var lastPlanesTime: TimeInterval = -.infinity
    private var planeIds: [UUID: Int] = [:]
    private var livePlanes: [UUID: RerunCapturePlane] = [:]
    private var nextAnchorId = 1

    init(configuration: Configuration = Configuration()) {
        self.configuration = configuration
        stats.pointBudget = configuration.maxVoxels
        stats.photoBudget = configuration.maxKeyframes
        stats.durationBudget = configuration.maxDuration
    }

    /// The capture reached ``Configuration/maxDuration``; frames are ignored from here on.
    var isFull: Bool { stats.duration >= configuration.maxDuration }

    /// Whether anything worth replaying was recorded: at least one camera pose.
    var hasContent: Bool { !poses.isEmpty }

    /// The recorded camera path so far, oldest first — what the live view draws as the trail.
    var pathPositions: [SIMD3<Float>] { poses.map(\.position) }

    /// The planes tracked now, in their stable id order.
    var currentPlanes: [RerunCapturePlane] {
        livePlanes.values.sorted { (planeIds[$0.identifier] ?? 0) < (planeIds[$1.identifier] ?? 0) }
    }

    /// The scan as it stands, in the shape the replay draws: the path walked, the camera
    /// where it is now and where it took each photo, the points kept with their colours and
    /// the surfaces tracked. What
    /// the recording screen rebuilds on glass — the recorder's own state, nothing estimated.
    var liveFrame: RerunFrame {
        RerunFrame(
            time: Float(stats.duration),
            trail: pathPositions,
            camera: poses.last.map { RerunPose(position: $0.position, rotation: $0.orientation) },
            mapPoints: voxelPoints[...],
            mapPointColors: voxelColors[...],
            livePoints: [],
            liveKey: -1,
            planes: currentPlanes.map {
                RerunPlane(
                    id: planeIds[$0.identifier] ?? 0,
                    kind: RerunPlaneKind(wire: $0.kind.rawValue),
                    polygon: $0.polygon
                )
            },
            anchors: [],
            // A pose is pinned when a photo was taken from it.
            keyframes: poses.filter(\.pinned).map { RerunPose(position: $0.position, rotation: $0.orientation) },
            keyframeImages: [],
            image: nil
        )
    }

    // MARK: Frames

    /// Records one frame. Frames that do not move the clock forward are ignored.
    mutating func add<F: RerunCaptureFrame>(_ frame: F) {
        let time = frame.timestamp
        guard time > lastTimestamp, !isFull else { return }
        lastTimestamp = time
        if origin == nil { origin = time }
        let t = nanos(time)
        stats.duration = time - (origin ?? time)

        let pose = RerunCaptureMath.portraitPose(fromSensor: frame.cameraTransform)
        let position = RerunCaptureMath.translation(of: pose)
        let orientation = RerunCaptureMath.orientation(of: pose)

        if frame.isTrackingNormal, position.x.isFinite, position.y.isFinite, position.z.isFinite {
            addPose(t: t, position: position, orientation: orientation, force: false)
            addKeyframeIfDue(frame, t: t, time: time, position: position, orientation: orientation)
            if time - lastPointsTime >= configuration.pointInterval {
                lastPointsTime = time
                addPoints(frame, t: t)
            }
        }
        if time - lastPlanesTime >= configuration.planeInterval {
            lastPlanesTime = time
            updatePlanes(frame.planes(), t: t)
        }
    }

    private func nanos(_ time: TimeInterval) -> Int64 {
        Int64(((time - (origin ?? time)) * 1e9).rounded())
    }

    private mutating func addPose(t: Int64, position: SIMD3<Float>, orientation: simd_quatf, force: Bool) {
        if let last = poses.last {
            if t <= last.t {
                // Same instant: the pose is already there; a photo pins it.
                if force { poses[poses.count - 1].pinned = true }
                return
            }
            let step = simd_distance(last.position, position)
            let turn = RerunCaptureMath.angle(between: last.orientation, and: orientation)
            let movedEnough = step >= configuration.poseMinStep
                || turn >= configuration.poseMinTurnDegrees * .pi / 180
            guard force || movedEnough else { return }
            stats.pathLength += step
        }
        if poses.count >= configuration.maxPoses { thinPoses() }
        poses.append(PoseSample(t: t, position: position, orientation: orientation, pinned: force))
        lastNanos = max(lastNanos, t)
    }

    /// Drops every other unpinned pose, keeping the last.
    private mutating func thinPoses() {
        var kept: [PoseSample] = []
        kept.reserveCapacity(poses.count / 2 + 1)
        for (i, pose) in poses.enumerated() where pose.pinned || i % 2 == 0 || i == poses.count - 1 {
            kept.append(pose)
        }
        poses = kept
    }

    private mutating func addKeyframeIfDue<F: RerunCaptureFrame>(
        _ frame: F, t: Int64, time: TimeInterval, position: SIMD3<Float>, orientation: simd_quatf
    ) {
        guard !stats.isPhotoLimitReached else { return }
        if let last = lastKeyframe {
            guard time - last.time >= configuration.keyframeMinInterval else { return }
            let travel = simd_distance(last.position, position)
            let turn = RerunCaptureMath.angle(between: last.orientation, and: orientation)
            guard travel >= configuration.keyframeTravel
                || turn >= configuration.keyframeTurnDegrees * .pi / 180 else { return }
        }
        let lens = frame.sensorLens.portrait(width: configuration.photoWidth)
        guard let jpeg = frame.portraitJPEG(width: lens.width, height: lens.height, quality: configuration.jpegQuality),
              !jpeg.isEmpty else { return }
        guard mediaEntries.count < configuration.maxKeyframes,
              media.count + jpeg.count <= configuration.maxMediaBytes else {
            stats.isPhotoLimitReached = true
            // Stopped by the media cap: the photos taken are all this scan will hold.
            stats.photoBudget = mediaEntries.count
            return
        }
        addPose(t: t, position: position, orientation: orientation, force: true)
        let path = String(format: "frames/%03d.jpg", mediaEntries.count)
        mediaEntries.append(MediaEntry(path: path, offset: media.count, length: jpeg.count))
        media.append(jpeg)
        if photoLens == nil { photoLens = lens }
        lastKeyframe = (position, orientation, time)
        appendEvent(t: t, RerunCaptureJSON.image(t: t, path: path))
        stats.keyframes = mediaEntries.count
        if mediaEntries.count >= configuration.maxKeyframes { stats.isPhotoLimitReached = true }
    }

    private mutating func addPoints<F: RerunCaptureFrame>(_ frame: F, t: Int64) {
        guard pointsLogged < configuration.maxPoints else { return }
        let all = frame.featurePoints()
        guard !all.isEmpty else { return }
        let budget = min(configuration.maxPointsPerObservation, configuration.maxPoints - pointsLogged)
        let stride = max(1, (all.count + budget - 1) / budget)
        let projector = RerunProjector(cameraToWorld: frame.cameraTransform, lens: frame.sensorLens)
        var positions: [SIMD3<Float>] = []
        var colors: [SIMD3<UInt8>] = []
        frame.withColorLookup { color in
            var i = 0
            while i < all.count, positions.count < budget {
                let p = all[i]
                i += stride
                guard p.x.isFinite, p.y.isFinite, p.z.isFinite,
                      let pixel = projector.project(p),
                      let c = color(pixel) else { continue }
                positions.append(p)
                colors.append(c)
            }
        }
        guard !positions.isEmpty else { return }
        pointsLogged += positions.count
        for (p, c) in zip(positions, colors) where voxels.count < configuration.maxVoxels {
            if voxels.insert(Self.voxelKey(p, size: configuration.pointVoxel)).inserted {
                voxelPoints.append(p)
                voxelColors.append(0xFF00_0000 | UInt32(c.x) << 16 | UInt32(c.y) << 8 | UInt32(c.z))
            }
        }
        stats.points = voxels.count
        // Every observation still to come fills one new voxel at most.
        stats.pointBudget = min(
            configuration.maxVoxels,
            voxels.count + max(0, configuration.maxPoints - pointsLogged)
        )
        appendEvent(t: t, RerunCaptureJSON.pointCloud(t: t, positions: positions, colors: colors))
    }

    /// Packs the voxel of `p` into one key: 21 signed bits per axis (Android's `voxelKey`).
    static func voxelKey(_ p: SIMD3<Float>, size: Float) -> Int64 {
        let mask: Int64 = 0x1FFFFF
        let ix = Int64(floor(p.x / size)) & mask
        let iy = Int64(floor(p.y / size)) & mask
        let iz = Int64(floor(p.z / size)) & mask
        return (ix << 42) | (iy << 21) | iz
    }

    private mutating func updatePlanes(_ planes: [RerunCapturePlane], t: Int64) {
        var seen = Set<UUID>()
        var changed: [(id: Int, plane: RerunCapturePlane)] = []
        for var plane in planes {
            plane.polygon = plane.polygon.filter { $0.x.isFinite && $0.y.isFinite && $0.z.isFinite }
            guard plane.polygon.count >= 3 else { continue }
            seen.insert(plane.identifier)
            if let last = livePlanes[plane.identifier],
               !Self.changedMaterially(from: last, to: plane, threshold: configuration.planeMinChange) { continue }
            let id = planeIds[plane.identifier] ?? {
                let next = planeIds.count + 1
                planeIds[plane.identifier] = next
                return next
            }()
            livePlanes[plane.identifier] = plane
            changed.append((id, plane))
        }
        for (uuid, plane) in livePlanes where !seen.contains(uuid) {
            livePlanes[uuid] = nil
            changed.append((planeIds[uuid] ?? 0, RerunCapturePlane(identifier: uuid, kind: plane.kind, polygon: [])))
        }
        for (id, plane) in changed.sorted(by: { $0.id < $1.id }) {
            appendEvent(t: t, RerunCaptureJSON.plane(t: t, id: id, kind: plane.kind.rawValue, polygon: plane.polygon))
        }
        stats.planes = livePlanes.count
        stats.surfaceArea = RerunCaptureMath.surfaceArea(livePlanes.values.map(\.polygon))
    }

    /// A plane is re-logged when its kind or vertex count changed, or a vertex moved more
    /// than `threshold` metres.
    static func changedMaterially(from a: RerunCapturePlane, to b: RerunCapturePlane, threshold: Float) -> Bool {
        guard a.kind == b.kind, a.polygon.count == b.polygon.count else { return true }
        let limit = threshold * threshold
        for (p, q) in zip(a.polygon, b.polygon) where simd_distance_squared(p, q) > limit { return true }
        return false
    }

    // MARK: Anchors

    /// Logs a model placed at `position` with `orientation`, at the latest frame's time.
    /// Returns its id (1, 2, 3…), `nil` before the first frame.
    @discardableResult
    mutating func addAnchor(position: SIMD3<Float>, orientation: simd_quatf) -> Int? {
        guard origin != nil else { return nil }
        let id = nextAnchorId
        nextAnchorId += 1
        let t = max(lastNanos, nanos(lastTimestamp))
        appendEvent(t: t, RerunCaptureJSON.anchor(t: t, id: id, position: position, orientation: orientation))
        stats.anchors = id
        return id
    }

    private mutating func appendEvent(t: Int64, _ line: String) {
        let time = max(t, events.last?.t ?? t)
        events.append((time, line))
        lastNanos = max(lastNanos, time)
    }

    // MARK: Finish

    /// The floor's height: the lowest upward plane of at least 0.2 m², else the lowest
    /// upward plane, `nil` with none.
    var floorY: Float? {
        let upward = livePlanes.values.filter { $0.kind == .horizontalUpward }
        func height(_ p: RerunCapturePlane) -> Float { p.polygon.map(\.y).reduce(0, +) / Float(p.polygon.count) }
        let large = upward.filter { RerunCaptureMath.area(of: $0.polygon) >= 0.2 }
        return (large.isEmpty ? upward : large).map(height).min()
    }

    /// The capture as the three payloads the replay reads. Does not change the recorder.
    func finish() -> RerunCapturePack {
        var log = ""
        log.reserveCapacity(poses.count * 128 + events.count * 256)
        var i = 0
        var j = 0
        while i < poses.count || j < events.count {
            // At the same instant the pose comes first, then what was seen from it.
            if j >= events.count || (i < poses.count && poses[i].t <= events[j].t) {
                let p = poses[i]
                log += RerunCaptureJSON.cameraPose(t: p.t, position: p.position, orientation: p.orientation)
                i += 1
            } else {
                log += events[j].line
                j += 1
            }
        }
        return RerunCapturePack(manifest: Data(manifestJSON().utf8), log: Data(log.utf8), media: media)
    }

    private func manifestJSON() -> String {
        typealias J = RerunCaptureJSON
        var s = "{"
        if let lens = photoLens {
            s += "\"intrinsics\":{\"width\":\(lens.width),\"height\":\(lens.height)"
            s += ",\"fx\":\(J.number(lens.fx, 2)),\"fy\":\(J.number(lens.fy, 2))"
            s += ",\"cx\":\(J.number(lens.cx, 2)),\"cy\":\(J.number(lens.cy, 2))},"
        }
        let frameRate = stats.duration > 0 && mediaEntries.count > 1
            ? Float(Double(mediaEntries.count) / stats.duration) : 10
        s += "\"frameRate\":\(J.number(frameRate, 2)),\"frames\":\(mediaEntries.count)"
        if let floorY { s += ",\"floorY\":\(J.number(floorY, 3))" }
        s += ",\"textures\":[],\"media\":["
        s += mediaEntries.map { "{\"path\":\"\($0.path)\",\"offset\":\($0.offset),\"length\":\($0.length)}" }
            .joined(separator: ",")
        s += "]}"
        return s
    }
}

// MARK: - Scan figures

/// The budgets a scan counts against, and when one is in sight (Android's `ScanLimits`).
///
/// iOS records one kind of scan — ARKit's feature points, the "sparse" tier — so its point
/// budget is the sparse one on both platforms: the 12 000 points of 3 cm the opened scan keeps
/// (``RerunTrace/maxMapPoints``, Android's `ArDebugTrace.MAX_MAP_POINTS`). Android's 500 000
/// belongs to its depth tier, which iOS does not record.
enum RerunScanLimits {
    /// A budget is announced once this much of it is spent.
    static let nearShare: Float = 0.8

    /// The points a scan can hold.
    static let pointBudget = RerunTrace.maxMapPoints

    /// The photos a scan can hold (Android's `KeyframeGate.MAX_PHOTOS`).
    static let photoBudget = 300

    /// Where a scan stops recording, seconds. iOS only: Android's scan has no time limit.
    static let duration: TimeInterval = 300

    /// `count` has spent `budget`. No budget, nothing to spend.
    static func isFull(_ count: Int, _ budget: Int) -> Bool { budget > 0 && count >= budget }

    /// `count` is within sight of `budget`: from ``nearShare`` of it on, full included.
    static func isNear(_ count: Int, _ budget: Int) -> Bool {
        budget > 0 && Float(count) >= Float(budget) * nearShare
    }
}

/// What a scan in progress holds, each figure with the budget it counts against (Android's
/// `ScanFigures`). Read off the recorder, never estimated.
struct RerunScanFigures: Equatable, Sendable {
    var points: Int
    var pointBudget: Int
    /// Square metres of surface found.
    var surfaceMetres2: Float
    var photos: Int
    var photoBudget = RerunScanLimits.photoBudget
    /// Seconds recorded, and where the scan stops. iOS only.
    var duration: TimeInterval = 0
    var durationBudget: TimeInterval = RerunScanLimits.duration

    var pointsFull: Bool { RerunScanLimits.isFull(points, pointBudget) }
    var photosFull: Bool { RerunScanLimits.isFull(photos, photoBudget) }
    var timeFull: Bool { durationBudget > 0 && duration >= durationBudget }
    var timeNear: Bool {
        durationBudget > 0 && Float(duration) >= Float(durationBudget) * RerunScanLimits.nearShare
    }

    /// The one thing to know mid-scan, `nil` while no limit is in play.
    var notice: String? {
        RerunScanCopy.limitNotice(
            pointsFull: pointsFull, photosFull: photosFull,
            timeNear: timeNear, timeFull: timeFull, durationBudget: durationBudget
        )
    }
}

extension RerunScanFigures {
    init(_ stats: RerunCaptureRecorder.Stats) {
        self.init(
            points: stats.points,
            pointBudget: stats.pointBudget,
            surfaceMetres2: stats.surfaceArea,
            photos: stats.keyframes,
            photoBudget: stats.photoBudget,
            duration: stats.duration,
            durationBudget: stats.durationBudget
        )
    }
}

/// The recording screen's words, as plain values so the wording is testable. The same
/// strings as Android's `ScanCopy`, where Android has them.
enum RerunScanCopy {
    static let stopHint = "Tap to stop and open your scan in 3D"
    static let finishing = "Building your scan…"
    static let photosFull = "Photo limit reached — points and path keep recording."
    static let pointsFull = "Point limit reached — new areas add no more points."
    static let scanFull = "Point and photo limits reached — tap stop to open your scan."
    /// iOS only: the scan stops recording at its time limit.
    static let timeFull = "Time limit reached — tap stop to open your scan."
    static let tierSparse = "Sparse scan"
    /// The settings sheet's heading over the scan's figures.
    static let figuresTitle = "This scan"
    /// The 3D card, and the two things a tap does to it.
    static let stageLabel = "Your scan in 3D"
    static let stageExpand = "Enlarge"
    static let stageCollapse = "Shrink the 3D view"

    /// iOS only: said from ``RerunScanLimits/nearShare`` of the time limit on.
    static func timeNear(_ budget: TimeInterval) -> String {
        "Time limit at \(RerunFormat.clock(Float(budget))) — the scan stops recording there."
    }

    /// What to say under the line that counts the scan. A scan that stopped says so before
    /// anything else; a spent budget comes before one that is only in sight.
    static func limitNotice(
        pointsFull: Bool, photosFull: Bool,
        timeNear: Bool = false, timeFull: Bool = false,
        durationBudget: TimeInterval = RerunScanLimits.duration
    ) -> String? {
        if timeFull { return Self.timeFull }
        if pointsFull && photosFull { return scanFull }
        if pointsFull { return Self.pointsFull }
        if photosFull { return Self.photosFull }
        if timeNear { return Self.timeNear(durationBudget) }
        return nil
    }

    /// The points on the recording line: `4.8k points`, then `9.6k / 12k` once the budget is
    /// in sight, then `12k · full` — a count that stopped must not read as a frozen screen.
    static func pointsLine(_ points: Int, _ budget: Int) -> String {
        if RerunScanLimits.isFull(points, budget) { return "\(RerunFormat.compactCount(budget)) · full" }
        if RerunScanLimits.isNear(points, budget) {
            return "\(RerunFormat.compactCount(points)) / \(RerunFormat.compactCount(budget))"
        }
        return "\(RerunFormat.compactCount(points)) \(points == 1 ? "point" : "points")"
    }

    /// The same, for VoiceOver: whole figures, whole words.
    static func pointsSpoken(_ points: Int, _ budget: Int) -> String {
        if RerunScanLimits.isFull(points, budget) { return "point limit of \(RerunFormat.count(budget)) reached" }
        if RerunScanLimits.isNear(points, budget) {
            return "\(RerunFormat.count(points)) of \(RerunFormat.count(budget)) points"
        }
        return "\(RerunFormat.count(points)) \(points == 1 ? "point" : "points")"
    }

    /// A settings row's figure against its budget: `42 of 300`, `12k of 12k · full`.
    static func budgeted(_ count: Int, _ budget: Int) -> String {
        let line = "\(figure(min(count, budget))) of \(figure(budget))"
        return RerunScanLimits.isFull(count, budget) ? line + " · full" : line
    }

    /// `3,812` while it fits a row, `246k` beyond.
    static func figure(_ value: Int) -> String {
        value < 10_000 ? RerunFormat.count(max(value, 0)) : RerunFormat.compactCount(value)
    }
}

// MARK: - Wire format

/// The showcase's JSON lines, hand-written like `RerunWireFormat` so the output is
/// byte-stable, with the showcase's precision: millimetres for positions, 4 decimals for
/// quaternions, integer nanoseconds for `t`.
enum RerunCaptureJSON {
    /// `v` rounded to `decimals`, shortest form; non-finite values become `0`.
    static func number(_ v: Float, _ decimals: Int) -> String {
        guard v.isFinite else { return "0" }
        let scale = pow(10, Double(decimals))
        let r = (Double(v) * scale).rounded() / scale
        return r == 0 ? "0.0" : String(r)
    }

    private static func header(_ t: Int64, _ type: String, _ entity: String) -> String {
        "{\"t\":\(t),\"type\":\"\(type)\",\"entity\":\"\(entity)\""
    }

    private static func vec(_ v: SIMD3<Float>) -> String {
        "[\(number(v.x, 3)),\(number(v.y, 3)),\(number(v.z, 3))]"
    }

    private static func quat(_ q: simd_quatf) -> String {
        "[\(number(q.imag.x, 4)),\(number(q.imag.y, 4)),\(number(q.imag.z, 4)),\(number(q.real, 4))]"
    }

    static func cameraPose(t: Int64, position: SIMD3<Float>, orientation: simd_quatf) -> String {
        header(t, "camera_pose", "world/camera") + ",\"translation\":\(vec(position)),\"quaternion\":\(quat(orientation))}\n"
    }

    static func image(t: Int64, path: String) -> String {
        header(t, "image", "world/camera/image") + ",\"path\":\"\(path)\"}\n"
    }

    static func pointCloud(t: Int64, positions: [SIMD3<Float>], colors: [SIMD3<UInt8>]) -> String {
        var s = header(t, "point_cloud", "world/points")
        s += ",\"positions\":[" + positions.map(vec).joined(separator: ",") + "]"
        s += ",\"colors\":[" + colors.map { "[\($0.x),\($0.y),\($0.z)]" }.joined(separator: ",") + "]}\n"
        return s
    }

    static func plane(t: Int64, id: Int, kind: String, polygon: [SIMD3<Float>]) -> String {
        header(t, "plane", "world/planes/\(id)")
            + ",\"kind\":\"\(kind)\",\"polygon\":[" + polygon.map(vec).joined(separator: ",") + "]}\n"
    }

    static func anchor(t: Int64, id: Int, position: SIMD3<Float>, orientation: simd_quatf) -> String {
        header(t, "anchor", "world/anchors/\(id)")
            + ",\"translation\":\(vec(position)),\"quaternion\":\(quat(orientation))}\n"
    }
}

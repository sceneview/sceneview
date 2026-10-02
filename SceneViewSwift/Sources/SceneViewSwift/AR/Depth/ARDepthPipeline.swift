#if os(iOS)
import ARKit
import CoreVideo
import Foundation
import os
import VideoToolbox

/// Produces the ``ARDepthFrame`` stream of one ``ARSceneView``.
///
/// - LiDAR (`frame.sceneDepth`) is converted on the main thread — 256×192,
///   well under a millisecond — at most ``lidarMaxHz`` times a second.
/// - ML depth is captured on the main thread (one GPU blit of the camera
///   image into a pooled buffer at the model's input size, plus a copy of the
///   anchors), then inferred, fitted and converted on a serial background
///   queue. One inference in flight at most, never a queue: a frame that
///   arrives while the model runs is simply not used. The `ARFrame` itself is
///   never retained.
@MainActor
final class ARDepthPipeline {
    static let lidarMaxHz: Double = 15

    var onDepthFrame: ((ARDepthFrame?) -> Void)?
    var onStateChange: ((DepthSourceState) -> Void)?

    private(set) var source: DepthSource?
    private var sourceIdentity: String?
    private var worker: MLDepthWorker?
    private var state: DepthSourceState?
    private var publishedNonNil = false
    private var lastLidarPublish: TimeInterval = 0
    private var lastSubmit: TimeInterval = 0
    private var lastSubmittedPose: simd_float4x4?
    private var lastMLPublish: TimeInterval?
    private var measuredHz: Double = 0

    /// Installs (or replaces) the source. A source with the same identity is
    /// a no-op, so SwiftUI re-renders do not restart the model.
    func setSource(_ newSource: DepthSource?) {
        let identity = newSource?.identity
        guard identity != sourceIdentity else { return }
        sourceIdentity = identity
        source = newSource
        worker?.cancel()
        worker = newSource?.estimator.map { MLDepthWorker(estimator: $0, benchmark: Self.needsBenchmark(newSource)) }
        state = nil
        lastSubmittedPose = nil
        lastMLPublish = nil
        measuredHz = 0
        if newSource == nil { publish(nil); return }
    }

    private static func needsBenchmark(_ source: DepthSource?) -> Bool {
        if case .auto = source { return true }
        return false
    }

    func tearDown() {
        worker?.cancel()
        worker = nil
        source = nil
        sourceIdentity = nil
    }

    /// Called from `session(_:didUpdate:)` on the main thread.
    func process(_ frame: ARFrame, faceTracking: Bool) {
        guard let source else { return }
        if faceTracking {
            setState(.unavailable(.unsupportedMode))
            return
        }
        let sensorDepth = frame.smoothedSceneDepth ?? frame.sceneDepth
        switch source {
        case .native:
            if let sensorDepth { publishLidar(sensorDepth, frame: frame) } else { noDepth() }
        case .auto:
            if let sensorDepth { publishLidar(sensorDepth, frame: frame) }
            else if worker != nil { processML(frame) }
            else { noDepth() }
        case .ml:
            processML(frame)
        }
    }

    // MARK: - LiDAR

    private func noDepth() {
        setState(.unavailable(.noSource))
        if publishedNonNil { publish(nil) }
    }

    private func publishLidar(_ depth: ARDepthData, frame: ARFrame) {
        setState(.native)
        guard frame.timestamp - lastLidarPublish >= 1 / Self.lidarMaxHz else { return }
        lastLidarPublish = frame.timestamp
        guard let converted = Self.convertLidar(depth, frame: frame) else { return }
        publish(converted)
    }

    static func convertLidar(_ depth: ARDepthData, frame: ARFrame) -> ARDepthFrame? {
        let map = depth.depthMap
        guard CVPixelBufferGetPixelFormatType(map) == kCVPixelFormatType_DepthFloat32 else { return nil }
        CVPixelBufferLockBaseAddress(map, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(map, .readOnly) }
        let w = CVPixelBufferGetWidth(map), h = CVPixelBufferGetHeight(map)
        let rowBytes = CVPixelBufferGetBytesPerRow(map)
        guard let base = CVPixelBufferGetBaseAddress(map) else { return nil }
        var mm = [UInt16](repeating: 0, count: w * h)
        for y in 0..<h {
            let row = (base + y * rowBytes).assumingMemoryBound(to: Float32.self)
            for x in 0..<w {
                let z = row[x]
                if z.isFinite, z > 0 { mm[y * w + x] = UInt16(min(65535, (z * 1000).rounded())) }
            }
        }
        var confidence: [UInt8]?
        if let conf = depth.confidenceMap {
            CVPixelBufferLockBaseAddress(conf, .readOnly)
            defer { CVPixelBufferUnlockBaseAddress(conf, .readOnly) }
            if let cBase = CVPixelBufferGetBaseAddress(conf),
               CVPixelBufferGetWidth(conf) == w, CVPixelBufferGetHeight(conf) == h {
                let cRow = CVPixelBufferGetBytesPerRow(conf)
                var values = [UInt8](repeating: 0, count: w * h)
                for y in 0..<h {
                    let row = (cBase + y * cRow).assumingMemoryBound(to: UInt8.self)
                    // ARConfidenceLevel 0 / 1 / 2 → 0…255.
                    for x in 0..<w { values[y * w + x] = UInt8(min(255, Int(row[x]) * 127 + Int(row[x] / 2))) }
                }
                confidence = values
            }
        }
        return ARDepthFrame(
            timestamp: frame.timestamp, width: w, height: h, millimetres: mm, confidence: confidence,
            intrinsics: scaledIntrinsics(frame.camera.intrinsics, from: frame.camera.imageResolution,
                                         to: (w, h)),
            cameraTransform: frame.camera.transform, source: .lidar)
    }

    nonisolated static func scaledIntrinsics(_ k: simd_float3x3, from resolution: CGSize, to size: (Int, Int)) -> simd_float3x3 {
        let sx = Float(size.0) / Float(resolution.width)
        let sy = Float(size.1) / Float(resolution.height)
        var out = k
        out[0][0] *= sx
        out[2][0] *= sx
        out[1][1] *= sy
        out[2][1] *= sy
        return out
    }

    // MARK: - ML

    private func processML(_ frame: ARFrame) {
        guard let worker, let source else { return }
        switch worker.phase {
        case .idle:
            setState(.preparing)
            worker.prepare()
            return
        case .preparing:
            setState(.preparing)
            return
        case .failed:
            setState(.unavailable(.modelFailed)); return
        case .tooSlow:
            setState(.unavailable(.tooSlow)); return
        case .ready:
            break
        }
        guard case .normal = frame.camera.trackingState else {
            setState(.throttled(.tracking))
            return
        }
        var interval = 1 / source.targetHz
        switch ProcessInfo.processInfo.thermalState {
        case .critical:
            setState(.throttled(.thermal))
            return
        case .serious:
            interval *= 2
        default:
            break
        }
        guard !worker.inFlight, frame.timestamp - lastSubmit >= interval else { return }
        // A still camera sees the same scene: keep the last map.
        if let previous = lastSubmittedPose, lastMLPublish != nil,
           Self.moved(from: previous, to: frame.camera.transform) == false {
            return
        }
        guard let job = worker.capture(frame) else { return }
        lastSubmit = frame.timestamp
        lastSubmittedPose = frame.camera.transform
        worker.run(job) { [weak self] result in
            MainActor.assumeIsolated { self?.receive(result, thermalSerious: interval > 1 / source.targetHz) }
        }
    }

    private func receive(_ result: ARDepthFrame?, thermalSerious: Bool) {
        guard worker != nil else { return }
        guard let result else {
            setState(.throttled(.waitingForAnchors))
            if publishedNonNil { publish(nil) }
            return
        }
        let now = CACurrentMediaTime()
        if let last = lastMLPublish {
            let hz = 1 / max(0.001, now - last)
            measuredHz = measuredHz == 0 ? hz : measuredHz * 0.7 + hz * 0.3
        }
        lastMLPublish = now
        setState(thermalSerious ? .throttled(.thermal) : .ml(hz: (measuredHz * 10).rounded() / 10))
        publish(result)
    }

    static func moved(from a: simd_float4x4, to b: simd_float4x4) -> Bool {
        let translation = simd_distance(SIMD3(a.columns.3.x, a.columns.3.y, a.columns.3.z),
                                        SIMD3(b.columns.3.x, b.columns.3.y, b.columns.3.z))
        let forwardA = -SIMD3(a.columns.2.x, a.columns.2.y, a.columns.2.z)
        let forwardB = -SIMD3(b.columns.2.x, b.columns.2.y, b.columns.2.z)
        let angle = acos(max(-1, min(1, simd_dot(simd_normalize(forwardA), simd_normalize(forwardB)))))
        return translation >= 0.02 || angle >= 2 * .pi / 180
    }

    // MARK: - Publishing

    private func setState(_ new: DepthSourceState) {
        guard new != state else { return }
        state = new
        onStateChange?(new)
    }

    private func publish(_ frame: ARDepthFrame?) {
        publishedNonNil = frame != nil
        onDepthFrame?(frame)
    }
}

/// Everything one ML job needs, copied out of the `ARFrame` on the main thread.
struct MLDepthJob: @unchecked Sendable {
    let image: CVPixelBuffer
    let timestamp: TimeInterval
    let cameraTransform: simd_float4x4
    let intrinsics: simd_float3x3
    let imageResolution: CGSize
    let featurePoints: [SIMD3<Float>]
    let planes: [MLDepthPlane]
}

/// The background half of the ML path. All mutable state below `queue` is
/// only touched on `queue`; `phase` and `inFlight` are read on main and
/// written through `stateLock`.
final class MLDepthWorker: @unchecked Sendable {
    enum Phase { case idle, preparing, ready, failed, tooSlow }

    static let signposter = OSSignposter(subsystem: "io.github.sceneview", category: "mlDepth")
    static let benchmarkFloorMilliseconds: Double = 400
    static let maxFeaturePoints = 600

    let estimator: any MonocularDepthEstimator
    private let benchmark: Bool
    private let queue = DispatchQueue(label: "io.github.sceneview.mldepth", qos: .userInitiated)
    private let stateLock = NSLock()
    private var _phase: Phase = .idle
    private var _inFlight = false
    private var _cancelled = false
    private var smoother = DepthScaleSmoother()
    private var transferSession: VTPixelTransferSession?
    private var pool: CVPixelBufferPool?

    init(estimator: any MonocularDepthEstimator, benchmark: Bool) {
        self.estimator = estimator
        self.benchmark = benchmark
    }

    deinit {
        if let transferSession { VTPixelTransferSessionInvalidate(transferSession) }
    }

    var phase: Phase { stateLock.withLock { _phase } }
    var inFlight: Bool { stateLock.withLock { _inFlight } }
    private var cancelled: Bool { stateLock.withLock { _cancelled } }

    func cancel() { stateLock.withLock { _cancelled = true } }

    func prepare() {
        stateLock.withLock { _phase = .preparing }
        queue.async { [self] in
            let next: Phase
            do {
                try estimator.warmUp()
                if benchmark {
                    let fast = try Self.passesBenchmark(estimator, pool: makeBenchmarkBuffer())
                    next = fast ? .ready : .tooSlow
                } else {
                    next = .ready
                }
            } catch {
                next = .failed
            }
            stateLock.withLock { _phase = next }
        }
    }

    private func makeBenchmarkBuffer() throws -> CVPixelBuffer {
        var buffer: CVPixelBuffer?
        let size = estimator.inputSize
        CVPixelBufferCreate(nil, size.width, size.height, estimator.inputPixelFormat,
                            [kCVPixelBufferIOSurfacePropertiesKey: [:]] as CFDictionary, &buffer)
        guard let buffer else { throw CocoaError(.featureUnsupported) }
        return buffer
    }

    /// p50 of 10 inferences, cached per device model and estimator.
    private static func passesBenchmark(_ estimator: any MonocularDepthEstimator, pool buffer: CVPixelBuffer) throws -> Bool {
        let key = "io.github.sceneview.mldepth.bench.\(deviceModel()).\(estimator.identifier)"
        if let cached = UserDefaults.standard.object(forKey: key) as? Double {
            return cached <= benchmarkFloorMilliseconds
        }
        var times: [Double] = []
        for _ in 0..<10 {
            let start = CACurrentMediaTime()
            _ = try estimator.estimate(buffer)
            times.append((CACurrentMediaTime() - start) * 1000)
        }
        let p50 = times.sorted()[times.count / 2]
        UserDefaults.standard.set(p50, forKey: key)
        return p50 <= benchmarkFloorMilliseconds
    }

    static func deviceModel() -> String {
        var info = utsname()
        uname(&info)
        return withUnsafeBytes(of: &info.machine) { raw in
            String(decoding: raw.prefix { $0 != 0 }, as: UTF8.self)
        }
    }

    // MARK: Capture (main thread)

    func capture(_ frame: ARFrame) -> MLDepthJob? {
        let state = Self.signposter.beginInterval("SV:mlDepth:capture")
        defer { Self.signposter.endInterval("SV:mlDepth:capture", state) }
        guard let image = scaledCopy(frame.capturedImage) else { return nil }
        var points: [SIMD3<Float>] = []
        if let raw = frame.rawFeaturePoints?.points {
            let stride = max(1, raw.count / Self.maxFeaturePoints)
            points.reserveCapacity(min(raw.count, Self.maxFeaturePoints))
            for i in Swift.stride(from: 0, to: raw.count, by: stride) { points.append(raw[i]) }
        }
        let planes: [MLDepthPlane] = frame.anchors.compactMap { anchor in
            guard let plane = anchor as? ARPlaneAnchor else { return nil }
            let vertices = plane.geometry.boundaryVertices
            guard vertices.count >= 3 else { return nil }
            return MLDepthPlane(transform: plane.transform, boundary: vertices.map { SIMD2($0.x, $0.z) })
        }
        stateLock.withLock { _inFlight = true }
        return MLDepthJob(
            image: image, timestamp: frame.timestamp, cameraTransform: frame.camera.transform,
            intrinsics: frame.camera.intrinsics, imageResolution: frame.camera.imageResolution,
            featurePoints: points, planes: planes)
    }

    private func scaledCopy(_ source: CVPixelBuffer) -> CVPixelBuffer? {
        let size = estimator.inputSize
        if transferSession == nil {
            var session: VTPixelTransferSession?
            VTPixelTransferSessionCreate(allocator: nil, pixelTransferSessionOut: &session)
            guard let session else { return nil }
            // Stretch, like the model's own preprocessing: the 4:3 camera
            // image and the 518×392 input have the same aspect within 1 %.
            VTSessionSetProperty(session, key: kVTPixelTransferPropertyKey_ScalingMode,
                                 value: kVTScalingMode_Normal)
            transferSession = session
            let attributes: [CFString: Any] = [
                kCVPixelBufferPixelFormatTypeKey: estimator.inputPixelFormat,
                kCVPixelBufferWidthKey: size.width,
                kCVPixelBufferHeightKey: size.height,
                kCVPixelBufferIOSurfacePropertiesKey: [:] as CFDictionary
            ]
            CVPixelBufferPoolCreate(nil, [kCVPixelBufferPoolMinimumBufferCountKey: 2] as CFDictionary,
                                    attributes as CFDictionary, &pool)
        }
        guard let pool, let transferSession else { return nil }
        var out: CVPixelBuffer?
        CVPixelBufferPoolCreatePixelBuffer(nil, pool, &out)
        guard let out, VTPixelTransferSessionTransferImage(transferSession, from: source, to: out) == noErr else {
            return nil
        }
        return out
    }

    // MARK: Inference + fit (worker queue)

    func run(_ job: MLDepthJob, completion: @escaping @Sendable (ARDepthFrame?) -> Void) {
        queue.async { [self] in
            let result = cancelled ? nil : infer(job)
            stateLock.withLock { _inFlight = false }
            guard !cancelled else { return }
            let publishState = Self.signposter.beginInterval("SV:mlDepth:publish")
            DispatchQueue.main.async {
                completion(result)
                Self.signposter.endInterval("SV:mlDepth:publish", publishState)
            }
        }
    }

    private func infer(_ job: MLDepthJob) -> ARDepthFrame? {
        let inferState = Self.signposter.beginInterval("SV:mlDepth:infer")
        let start = CACurrentMediaTime()
        guard let estimate = try? estimator.estimate(job.image) else {
            Self.signposter.endInterval("SV:mlDepth:infer", inferState)
            return nil
        }
        let inferenceMilliseconds = (CACurrentMediaTime() - start) * 1000
        Self.signposter.endInterval("SV:mlDepth:infer", inferState)

        let fitState = Self.signposter.beginInterval("SV:mlDepth:fit")
        defer { Self.signposter.endInterval("SV:mlDepth:fit", fitState) }
        let intrinsics = ARDepthPipeline.scaledIntrinsics(
            job.intrinsics, from: job.imageResolution, to: (estimate.width, estimate.height))
        let anchors = DepthAnchorProjection.samples(
            estimate: estimate, intrinsics: intrinsics, cameraTransform: job.cameraTransform,
            featurePoints: job.featurePoints, planes: job.planes)

        let kind = estimator.outputKind
        let scaled: DepthScaleSmoother.Output
        if kind == .metric {
            scaled = DepthScaleSmoother.Output(scale: 1, shift: 0, inlierCount: 0, relativeRMSError: 0,
                                               inlierDepthRange: MonocularDepthConversion.validRange, held: false)
        } else {
            let fit = AffineInverseDepthFit.fit(anchors, kind: kind, prior: smoother.prior)
            guard let output = smoother.update(with: fit) else { return nil }
            scaled = output
        }
        let converted = MonocularDepthConversion.convert(
            estimate, kind: kind, scale: scaled.scale, shift: scaled.shift,
            anchorDepthRange: scaled.inlierDepthRange, relativeRMSError: scaled.relativeRMSError)
        return ARDepthFrame(
            timestamp: job.timestamp, width: estimate.width, height: estimate.height,
            millimetres: converted.millimetres, confidence: converted.confidence,
            intrinsics: intrinsics, cameraTransform: job.cameraTransform, source: .ml,
            ml: .init(inferenceMilliseconds: inferenceMilliseconds, anchorCount: anchors.count,
                      inlierCount: scaled.inlierCount, scale: scaled.scale, shift: scaled.shift,
                      relativeRMSError: scaled.relativeRMSError, heldPreviousScale: scaled.held))
    }
}
#endif

import simd

struct MLDepthPlane: Sendable {
    let transform: simd_float4x4
    /// Boundary polygon in the plane's local x/z.
    let boundary: [SIMD2<Float>]
}

/// Projects metric anchors into a relative depth map: feature points
/// directly, planes by casting an 8×6 grid of pixel rays against each plane's
/// boundary polygon. Pure, platform-neutral, unit-tested.
enum DepthAnchorProjection {
    static let planeGrid = (columns: 8, rows: 6)

    /// Image coordinates (`u = 0.5` is the middle of column 0) and depth
    /// (metres along −z) of a world point, or nil when it is behind the camera
    /// or outside the map.
    static func project(_ world: SIMD3<Float>, intrinsics k: simd_float3x3,
                        worldToCamera: simd_float4x4, width: Int, height: Int) -> (u: Float, v: Float, z: Float)? {
        let pc = worldToCamera * SIMD4(world, 1)
        let z = -pc.z
        guard z > 0.05 else { return nil }
        let u = k[0][0] * pc.x / z + k[2][0]
        let v = k[1][1] * (-pc.y) / z + k[2][1]
        guard u >= 0, v >= 0, u < Float(width), v < Float(height) else { return nil }
        return (u, v, z)
    }

    /// Bilinear read at pixel-centre coordinates, or nil outside the centres'
    /// hull. Same convention as Android's `DepthAnchors.sampleBilinear`.
    static func sampleBilinear(_ map: [Float], width: Int, height: Int, u: Float, v: Float) -> Float? {
        let fx = u - 0.5, fy = v - 0.5
        guard fx >= 0, fy >= 0, fx <= Float(width - 1), fy <= Float(height - 1) else { return nil }
        let x0 = max(0, min(Int(fx), width - 2)), y0 = max(0, min(Int(fy), height - 2))
        let ax = fx - Float(x0), ay = fy - Float(y0)
        let x1 = min(x0 + 1, width - 1), y1 = min(y0 + 1, height - 1)
        let top = map[y0 * width + x0] * (1 - ax) + map[y0 * width + x1] * ax
        let bottom = map[y1 * width + x0] * (1 - ax) + map[y1 * width + x1] * ax
        return top * (1 - ay) + bottom * ay
    }

    static func samples(estimate: MonocularDepthEstimate, intrinsics k: simd_float3x3,
                        cameraTransform: simd_float4x4, featurePoints: [SIMD3<Float>],
                        planes: [MLDepthPlane]) -> [DepthAnchorSample] {
        let w = estimate.width, h = estimate.height
        let worldToCamera = cameraTransform.inverse
        var out: [DepthAnchorSample] = []
        out.reserveCapacity(featurePoints.count + planeGrid.columns * planeGrid.rows)
        for point in featurePoints {
            guard let p = project(point, intrinsics: k, worldToCamera: worldToCamera, width: w, height: h),
                  let d = sampleBilinear(estimate.values, width: w, height: h, u: p.u, v: p.v)
            else { continue }
            out.append(DepthAnchorSample(d: d, z: p.z))
        }
        guard !planes.isEmpty else { return out }
        let origin = SIMD3(cameraTransform.columns.3.x, cameraTransform.columns.3.y, cameraTransform.columns.3.z)
        let planeInverses = planes.map { $0.transform.inverse }
        for row in 0..<planeGrid.rows {
            for column in 0..<planeGrid.columns {
                let x = (column * w) / planeGrid.columns + w / (2 * planeGrid.columns)
                let y = (row * h) / planeGrid.rows + h / (2 * planeGrid.rows)
                guard let z = nearestPlaneDepth(
                    pixel: (Float(x) + 0.5, Float(y) + 0.5), intrinsics: k, cameraTransform: cameraTransform,
                    origin: origin, planes: planes, planeInverses: planeInverses) else { continue }
                out.append(DepthAnchorSample(d: estimate.values[y * w + x], z: z))
            }
        }
        return out
    }

    /// Depth of the nearest plane hit along the ray through `pixel`.
    static func nearestPlaneDepth(pixel: (Float, Float), intrinsics k: simd_float3x3,
                                  cameraTransform: simd_float4x4, origin: SIMD3<Float>,
                                  planes: [MLDepthPlane], planeInverses: [simd_float4x4]) -> Float? {
        // Camera-space direction with z = −1, so the ray parameter is the depth.
        let dirCamera = SIMD4<Float>((pixel.0 - k[2][0]) / k[0][0], -(pixel.1 - k[2][1]) / k[1][1], -1, 0)
        let dw = cameraTransform * dirCamera
        let dir = SIMD3(dw.x, dw.y, dw.z)
        var best: Float?
        for (plane, inverse) in zip(planes, planeInverses) {
            let n = SIMD3(plane.transform.columns.1.x, plane.transform.columns.1.y, plane.transform.columns.1.z)
            let p0 = SIMD3(plane.transform.columns.3.x, plane.transform.columns.3.y, plane.transform.columns.3.z)
            let denom = simd_dot(n, dir)
            guard abs(denom) > 1e-5 else { continue }
            let t = simd_dot(n, p0 - origin) / denom
            guard t > 0.05, t < (best ?? .infinity) else { continue }
            let hit = origin + t * dir
            let local = inverse * SIMD4(hit, 1)
            guard contains(plane.boundary, SIMD2(local.x, local.z)) else { continue }
            best = t
        }
        return best
    }

    /// Even-odd point-in-polygon.
    static func contains(_ polygon: [SIMD2<Float>], _ p: SIMD2<Float>) -> Bool {
        var inside = false
        var j = polygon.count - 1
        for i in polygon.indices {
            let a = polygon[i], b = polygon[j]
            if (a.y > p.y) != (b.y > p.y),
               p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x {
                inside.toggle()
            }
            j = i
        }
        return inside
    }
}

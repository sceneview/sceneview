// RerunCaptureRecorderTests.swift
//
// Pure tests for `RerunCaptureRecorder.swift`, the "Record your own" half of the Rerun
// showcase: the portrait pose convention, keyframe spacing, pose thinning, YCbCr colour
// sampling, projection, plane bookkeeping, and the three payloads round-tripped through
// JSONSerialization. No ARKit: frames are plain values.

#if DEBUG

import CoreVideo
import Foundation
import ImageIO
import simd
import XCTest
@testable import SceneViewDemo

final class RerunCaptureRecorderTests: XCTestCase {

    // MARK: - Fixtures

    /// A 4:3 sensor like ARKit's 1920 × 1440, principal point slightly off centre.
    private let sensorLens = RerunPinhole(width: 1920, height: 1440, fx: 1400, fy: 1410, cx: 950, cy: 730)

    /// ARKit's camera transform for a phone held upright in portrait, looking along the world
    /// direction `yawDegrees` away from -Z (counter-clockwise seen from above). ARKit's camera
    /// `+X` runs toward the Home button — the floor for an upright phone — and `+Y` to the
    /// holder's right; the camera looks down its -Z.
    private func uprightPhone(at position: SIMD3<Float>, yawDegrees: Float = 0) -> simd_float4x4 {
        let base = simd_float4x4(
            SIMD4(0, -1, 0, 0),
            SIMD4(1, 0, 0, 0),
            SIMD4(0, 0, 1, 0),
            SIMD4(0, 0, 0, 1)
        )
        var m = simd_float4x4(simd_quatf(angle: yawDegrees * .pi / 180, axis: SIMD3(0, 1, 0))) * base
        m.columns.3 = SIMD4(position, 1)
        return m
    }

    private struct FakeFrame: RerunCaptureFrame {
        var timestamp: TimeInterval
        var cameraTransform: simd_float4x4
        var sensorLens = RerunPinhole(width: 1920, height: 1440, fx: 1400, fy: 1410, cx: 950, cy: 730)
        var isTrackingNormal = true
        var points: [SIMD3<Float>] = []
        var planeList: [RerunCapturePlane] = []
        var color: SIMD3<UInt8>? = SIMD3(200, 100, 50)
        var jpeg: Data? = Data(repeating: 0xAB, count: 1_000)

        func featurePoints() -> [SIMD3<Float>] { points }
        func planes() -> [RerunCapturePlane] { planeList }
        func withColorLookup<R>(_ body: ((SIMD2<Float>) -> SIMD3<UInt8>?) -> R) -> R { body { _ in color } }
        func portraitJPEG(width: Int, height: Int, quality: Double) -> Data? { jpeg }
    }

    /// 1/16 s: exact in binary, so interval arithmetic in the tests has no rounding edge.
    private let dt: TimeInterval = 1.0 / 16

    private func events(_ pack: RerunCapturePack) throws -> [[String: Any]] {
        let text = try XCTUnwrap(String(data: pack.log, encoding: .utf8))
        XCTAssertTrue(text.isEmpty || text.hasSuffix("\n"))
        return try text.split(separator: "\n").map { line in
            try XCTUnwrap(JSONSerialization.jsonObject(with: Data(line.utf8)) as? [String: Any], String(line))
        }
    }

    private func manifest(_ pack: RerunCapturePack) throws -> [String: Any] {
        try XCTUnwrap(JSONSerialization.jsonObject(with: pack.manifest) as? [String: Any])
    }

    private func count(_ events: [[String: Any]], _ type: String) -> Int {
        events.filter { $0["type"] as? String == type }.count
    }

    private func floats(_ any: Any?) -> [Float] {
        (any as? [NSNumber])?.map(\.floatValue) ?? []
    }

    // MARK: - Lens and pose convention

    func testPinholeReadsARKitIntrinsicsColumnMajor() {
        let k = simd_float3x3(SIMD3(1400, 0, 0), SIMD3(0, 1410, 0), SIMD3(950, 730, 1))
        XCTAssertEqual(RerunPinhole(intrinsics: k, width: 1920, height: 1440), sensorLens)
    }

    func testPortraitLensIsTheSensorLensTurnedAndScaled() {
        let p = sensorLens.portrait(width: 480)
        XCTAssertEqual(p.width, 480)
        XCTAssertEqual(p.height, 640)
        XCTAssertEqual(p.fx, 1410 / 3, accuracy: 1e-3)
        XCTAssertEqual(p.fy, 1400 / 3, accuracy: 1e-3)
        XCTAssertEqual(p.cx, (1440 - 730) / 3, accuracy: 1e-3)
        XCTAssertEqual(p.cy, 950 / 3, accuracy: 1e-3)
    }

    func testUprightPhoneGetsAnUprightPortraitPose() {
        let pose = RerunCaptureMath.portraitPose(fromSensor: uprightPhone(at: SIMD3(0, 1.5, 0)))
        // Photo up is world up, photo right is world right, the camera looks down world -Z.
        XCTAssertEqual(simd_distance(SIMD3(pose.columns.1.x, pose.columns.1.y, pose.columns.1.z), SIMD3(0, 1, 0)), 0, accuracy: 1e-6)
        XCTAssertEqual(simd_distance(SIMD3(pose.columns.0.x, pose.columns.0.y, pose.columns.0.z), SIMD3(1, 0, 0)), 0, accuracy: 1e-6)
        XCTAssertEqual(simd_distance(SIMD3(pose.columns.2.x, pose.columns.2.y, pose.columns.2.z), SIMD3(0, 0, 1)), 0, accuracy: 1e-6)
        let q = RerunCaptureMath.orientation(of: pose)
        XCTAssertEqual(q.real, 1, accuracy: 1e-6)
        XCTAssertEqual(RerunCaptureMath.translation(of: pose), SIMD3(0, 1.5, 0))
    }

    func testPointsAboveAndRightOfAnUprightPhoneLandAboveAndRightInThePhoto() throws {
        let pose = RerunCaptureMath.portraitPose(fromSensor: uprightPhone(at: SIMD3(0, 1.5, 0)))
        let lens = sensorLens.portrait(width: 480)
        let projector = RerunProjector(cameraToWorld: pose, lens: lens)

        let ahead = try XCTUnwrap(projector.project(SIMD3(0, 1.5, -2)))
        XCTAssertEqual(ahead.x, lens.cx, accuracy: 1e-3)
        XCTAssertEqual(ahead.y, lens.cy, accuracy: 1e-3)

        let above = try XCTUnwrap(projector.project(SIMD3(0, 1.7, -2)))
        XCTAssertEqual(above.x, lens.cx, accuracy: 1e-3)
        XCTAssertEqual(above.y, lens.cy - lens.fy * 0.1, accuracy: 1e-3) // image +y is down

        let right = try XCTUnwrap(projector.project(SIMD3(0.2, 1.5, -2)))
        XCTAssertEqual(right.x, lens.cx + lens.fx * 0.1, accuracy: 1e-3)
        XCTAssertEqual(right.y, lens.cy, accuracy: 1e-3)
    }

    /// The pose rotation, the lens rotation and the photo's pixel rotation are one and the
    /// same turn: projecting with the sensor's pose and lens then turning the pixel gives the
    /// pixel the portrait pose and lens project to, for an arbitrary camera.
    func testSensorAndPortraitProjectionsAgree() throws {
        var sensor = simd_float4x4(simd_quatf(angle: 0.7, axis: simd_normalize(SIMD3(0.3, 1, -0.4))))
        sensor.columns.3 = SIMD4(0.4, 1.2, -0.3, 1)
        let portrait = RerunCaptureMath.portraitPose(fromSensor: sensor)
        let sensorProjector = RerunProjector(cameraToWorld: sensor, lens: sensorLens)
        let portraitProjector = RerunProjector(cameraToWorld: portrait, lens: sensorLens.portrait(width: 480))
        let forward = -SIMD3(sensor.columns.2.x, sensor.columns.2.y, sensor.columns.2.z)
        let side = SIMD3(sensor.columns.0.x, sensor.columns.0.y, sensor.columns.0.z)
        let up = SIMD3(sensor.columns.1.x, sensor.columns.1.y, sensor.columns.1.z)
        let origin = RerunCaptureMath.translation(of: sensor)
        var checked = 0
        for (a, b) in [(0, 0), (0.3, 0.1), (-0.4, 0.2), (0.2, -0.35), (-0.1, -0.1)] as [(Float, Float)] {
            let world = origin + forward * 1.5 + side * a + up * b
            let s = try XCTUnwrap(sensorProjector.project(world))
            let p = try XCTUnwrap(portraitProjector.project(world))
            let turned = RerunCaptureMath.portraitPixel(
                fromSensor: s, sensorSize: SIMD2(Float(sensorLens.width), Float(sensorLens.height)), portraitWidth: 480
            )
            XCTAssertEqual(turned.x, p.x, accuracy: 1e-2)
            XCTAssertEqual(turned.y, p.y, accuracy: 1e-2)
            checked += 1
        }
        XCTAssertEqual(checked, 5)
    }

    func testProjectionDropsPointsBehindTheCameraOrOutsideTheImage() {
        let pose = RerunCaptureMath.portraitPose(fromSensor: uprightPhone(at: .zero))
        let projector = RerunProjector(cameraToWorld: pose, lens: sensorLens.portrait(width: 480))
        XCTAssertNil(projector.project(SIMD3(0, 0, 1)), "behind")
        XCTAssertNil(projector.project(SIMD3(0, 0, -0.01)), "closer than the near plane")
        XCTAssertNil(projector.project(SIMD3(5, 0, -1)), "far right")
        XCTAssertNil(projector.project(SIMD3(0, -5, -1)), "far below")
        XCTAssertNotNil(projector.project(SIMD3(0, 0, -1)))
    }

    func testFacingTurnsLocalPlusZTowardTheTarget() {
        let front = RerunCaptureMath.facing(from: .zero, toward: SIMD3(0, 1.4, 2))
        XCTAssertEqual(simd_distance(front.act(SIMD3(0, 0, 1)), SIMD3(0, 0, 1)), 0, accuracy: 1e-5)
        let side = RerunCaptureMath.facing(from: SIMD3(1, 0, 1), toward: SIMD3(3, 0.5, 1))
        XCTAssertEqual(simd_distance(side.act(SIMD3(0, 0, 1)), SIMD3(1, 0, 0)), 0, accuracy: 1e-5)
        XCTAssertEqual(side.imag.x, 0, accuracy: 1e-6)
        XCTAssertEqual(side.imag.z, 0, accuracy: 1e-6)
    }

    // MARK: - Colour

    func testYCbCrFullRangeConvertsToSRGB() {
        XCTAssertEqual(RerunYCbCrImage.rgb(y: 128, cb: 128, cr: 128), SIMD3(128, 128, 128))
        XCTAssertEqual(RerunYCbCrImage.rgb(y: 255, cb: 128, cr: 128), SIMD3(255, 255, 255))
        XCTAssertEqual(RerunYCbCrImage.rgb(y: 0, cb: 128, cr: 128), SIMD3(0, 0, 0))
        // BT.601 full-range encodings of pure red, green and blue.
        assertClose(RerunYCbCrImage.rgb(y: 76, cb: 85, cr: 255), SIMD3(254, 0, 0))
        assertClose(RerunYCbCrImage.rgb(y: 150, cb: 44, cr: 21), SIMD3(0, 255, 1))
        assertClose(RerunYCbCrImage.rgb(y: 29, cb: 255, cr: 107), SIMD3(0, 0, 254))
        // Video range: 16 is black, 235 white.
        XCTAssertEqual(RerunYCbCrImage.rgb(y: 16, cb: 128, cr: 128, fullRange: false), SIMD3(0, 0, 0))
        XCTAssertEqual(RerunYCbCrImage.rgb(y: 235, cb: 128, cr: 128, fullRange: false), SIMD3(255, 255, 255))
    }

    private func assertClose(_ a: SIMD3<UInt8>, _ b: SIMD3<UInt8>, tolerance: Int = 2, line: UInt = #line) {
        for i in 0..<3 {
            XCTAssertLessThanOrEqual(abs(Int(a[i]) - Int(b[i])), tolerance, "\(a) vs \(b)", line: line)
        }
    }

    /// A synthetic bi-planar image, padded rows like a real pixel buffer: the left half red,
    /// the right half blue; the top quarter's luma darker.
    private func withSyntheticImage<R>(width: Int = 16, height: Int = 12, _ body: (RerunYCbCrImage) -> R) -> R {
        let lumaStride = width + 8
        let chromaStride = width + 4
        var luma = [UInt8](repeating: 0, count: lumaStride * height)
        var chroma = [UInt8](repeating: 0, count: chromaStride * height / 2)
        for y in 0..<height {
            for x in 0..<width {
                let red = x < width / 2
                luma[y * lumaStride + x] = y < height / 4 ? (red ? 10 : 5) : (red ? 76 : 29)
                if x % 2 == 0, y % 2 == 0 {
                    chroma[(y / 2) * chromaStride + x] = red ? 85 : 255
                    chroma[(y / 2) * chromaStride + x + 1] = red ? 255 : 107
                }
            }
        }
        return luma.withUnsafeBufferPointer { l in
            chroma.withUnsafeBufferPointer { c in
                body(RerunYCbCrImage(
                    luma: l.baseAddress!, lumaBytesPerRow: lumaStride,
                    chroma: c.baseAddress!, chromaBytesPerRow: chromaStride,
                    width: width, height: height
                ))
            }
        }
    }

    func testImageSamplesThePixelAPointFallsIn() {
        withSyntheticImage { image in
            assertClose(image.color(at: SIMD2(2.5, 8.5))!, SIMD3(254, 0, 0))
            assertClose(image.color(at: SIMD2(13.9, 11.9))!, SIMD3(0, 0, 254))
            XCTAssertNil(image.color(at: SIMD2(-0.1, 3)))
            XCTAssertNil(image.color(at: SIMD2(16, 3)))
            XCTAssertNil(image.color(at: SIMD2(3, 12)))
        }
    }

    /// Turning 90° clockwise puts the sensor's left edge at the top and its top edge at the
    /// right — the photo of an upright phone stands the right way up.
    func testPortraitThumbnailIsTheSensorImageTurnedClockwise() {
        withSyntheticImage { image in
            let w = 6, h = 8 // 12 × 16 portrait, halved
            let px = image.portraitRGBX(width: w, height: h)
            func pixel(_ x: Int, _ y: Int) -> SIMD3<UInt8> {
                let o = (y * w + x) * 4
                return SIMD3(px[o], px[o + 1], px[o + 2])
            }
            // Sensor left half (red) → portrait top half; sensor right half (blue) → bottom.
            XCTAssertGreaterThan(pixel(1, 1).x, 200)
            XCTAssertLessThan(pixel(1, 1).z, 30)
            XCTAssertGreaterThan(pixel(1, h - 2).z, 200)
            XCTAssertLessThan(pixel(1, h - 2).x, 30)
            // Sensor top quarter (darker) → portrait right edge.
            XCTAssertLessThan(Int(pixel(w - 1, 1).x) + 60, Int(pixel(0, 1).x))
        }
    }

    func testPixelBufferFrameEncodesAPortraitJPEGAndSamplesColours() throws {
        var buffer: CVPixelBuffer?
        let attributes = [kCVPixelBufferIOSurfacePropertiesKey: [:]] as CFDictionary
        XCTAssertEqual(CVPixelBufferCreate(nil, 1920, 1440, kCVPixelFormatType_420YpCbCr8BiPlanarFullRange,
                                           attributes, &buffer), kCVReturnSuccess)
        let pixelBuffer = try XCTUnwrap(buffer)
        // Sensor left half red, right half blue.
        CVPixelBufferLockBaseAddress(pixelBuffer, [])
        let luma = CVPixelBufferGetBaseAddressOfPlane(pixelBuffer, 0)!.assumingMemoryBound(to: UInt8.self)
        let lumaStride = CVPixelBufferGetBytesPerRowOfPlane(pixelBuffer, 0)
        let chroma = CVPixelBufferGetBaseAddressOfPlane(pixelBuffer, 1)!.assumingMemoryBound(to: UInt8.self)
        let chromaStride = CVPixelBufferGetBytesPerRowOfPlane(pixelBuffer, 1)
        for y in 0..<1440 {
            for x in 0..<1920 { luma[y * lumaStride + x] = x < 960 ? 76 : 29 }
        }
        for y in 0..<720 {
            for x in 0..<960 {
                chroma[y * chromaStride + x * 2] = x < 480 ? 85 : 255
                chroma[y * chromaStride + x * 2 + 1] = x < 480 ? 255 : 107
            }
        }
        CVPixelBufferUnlockBaseAddress(pixelBuffer, [])

        let frame = RerunPixelBufferFrame(
            timestamp: 1, cameraTransform: uprightPhone(at: .zero), sensorLens: sensorLens,
            isTrackingNormal: true, image: pixelBuffer, points: { [] }, planeList: { [] }
        )
        let colors = frame.withColorLookup { color in [color(SIMD2(100, 700)), color(SIMD2(1800, 700)), color(SIMD2(-1, 0))] }
        assertClose(try XCTUnwrap(colors[0]), SIMD3(254, 0, 0))
        assertClose(try XCTUnwrap(colors[1]), SIMD3(0, 0, 254))
        XCTAssertNil(colors[2])

        let jpeg = try XCTUnwrap(frame.portraitJPEG(width: 480, height: 640, quality: 0.6))
        XCTAssertEqual(Array(jpeg.prefix(2)), [0xFF, 0xD8])
        let source = try XCTUnwrap(CGImageSourceCreateWithData(jpeg as CFData, nil))
        let decoded = try XCTUnwrap(CGImageSourceCreateImageAtIndex(source, 0, nil))
        XCTAssertEqual(decoded.width, 480)
        XCTAssertEqual(decoded.height, 640)
        // Top of the portrait photo is the sensor's left: red. Bottom: blue.
        let rgba = try rgbaPixels(of: decoded)
        let top = rgba[(40 * 480 + 240) * 4 ..< (40 * 480 + 240) * 4 + 3].map(Int.init)
        let bottom = rgba[(600 * 480 + 240) * 4 ..< (600 * 480 + 240) * 4 + 3].map(Int.init)
        XCTAssertGreaterThan(top[0], 200)
        XCTAssertLessThan(top[2], 50)
        XCTAssertGreaterThan(bottom[2], 200)
        XCTAssertLessThan(bottom[0], 50)
    }

    private func rgbaPixels(of image: CGImage) throws -> [UInt8] {
        var out = [UInt8](repeating: 0, count: image.width * image.height * 4)
        let drawn: Bool = out.withUnsafeMutableBytes { raw in
            guard let context = CGContext(
                data: raw.baseAddress, width: image.width, height: image.height, bitsPerComponent: 8,
                bytesPerRow: image.width * 4, space: CGColorSpace(name: CGColorSpace.sRGB)!,
                bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
            ) else { return false }
            context.draw(image, in: CGRect(x: 0, y: 0, width: image.width, height: image.height))
            return true
        }
        XCTAssertTrue(drawn)
        return out
    }

    // MARK: - Poses and keyframes

    func testPosesAreThinnedByFiveMillimetresOrOneDegree() throws {
        var recorder = RerunCaptureRecorder()
        var t: TimeInterval = 0
        // Held still, with sub-millimetre jitter: one pose.
        for i in 0..<40 {
            t += dt
            let jitter = Float(i % 2) * 0.0008
            recorder.add(FakeFrame(timestamp: t, cameraTransform: uprightPhone(at: SIMD3(jitter, 1.5, 0)), jpeg: nil))
        }
        XCTAssertEqual(count(try events(recorder.finish()), "camera_pose"), 1)
        // Half a degree: still one pose. One and a half: a second.
        t += dt
        recorder.add(FakeFrame(timestamp: t, cameraTransform: uprightPhone(at: SIMD3(0, 1.5, 0), yawDegrees: 0.5), jpeg: nil))
        XCTAssertEqual(count(try events(recorder.finish()), "camera_pose"), 1)
        t += dt
        recorder.add(FakeFrame(timestamp: t, cameraTransform: uprightPhone(at: SIMD3(0, 1.5, 0), yawDegrees: 1.5), jpeg: nil))
        XCTAssertEqual(count(try events(recorder.finish()), "camera_pose"), 2)
        // Six millimetre steps: every one kept, and the path length adds them up.
        for i in 1...10 {
            t += dt
            recorder.add(FakeFrame(timestamp: t, cameraTransform: uprightPhone(at: SIMD3(Float(i) * 0.006, 1.5, 0),
                                                                              yawDegrees: 1.5), jpeg: nil))
        }
        XCTAssertEqual(count(try events(recorder.finish()), "camera_pose"), 12)
        XCTAssertEqual(recorder.stats.pathLength, 0.06, accuracy: 1e-4)
    }

    func testPoseCapHalvesThePathButKeepsPhotoPoses() throws {
        var config = RerunCaptureRecorder.Configuration()
        config.maxPoses = 20
        var recorder = RerunCaptureRecorder(configuration: config)
        for i in 0..<60 {
            recorder.add(FakeFrame(timestamp: Double(i) * dt, cameraTransform: uprightPhone(at: SIMD3(Float(i) * 0.01, 0, 0))))
        }
        let log = try events(recorder.finish())
        let poseTimes = log.filter { $0["type"] as? String == "camera_pose" }.compactMap { ($0["t"] as? NSNumber)?.int64Value }
        XCTAssertLessThanOrEqual(poseTimes.count, 20)
        for image in log where image["type"] as? String == "image" {
            XCTAssertTrue(poseTimes.contains((image["t"] as! NSNumber).int64Value), "a photo lost its pose")
        }
    }

    func testKeyframeEveryFifteenCentimetres() throws {
        var recorder = RerunCaptureRecorder()
        // 1/64 m per frame (exact in binary): 15 cm is crossed on every 10th frame.
        for i in 0..<100 {
            recorder.add(FakeFrame(timestamp: Double(i) * dt, cameraTransform: uprightPhone(at: SIMD3(Float(i) / 64, 1.5, 0))))
        }
        let log = try events(recorder.finish())
        let imageTimes = log.filter { $0["type"] as? String == "image" }.map { ($0["t"] as! NSNumber).int64Value }
        let expected = stride(from: 0, to: 100, by: 10).map { Int64((Double($0) * dt * 1e9).rounded()) }
        XCTAssertEqual(imageTimes, expected)
        XCTAssertEqual(recorder.stats.keyframes, 10)
    }

    func testKeyframeEveryTenDegrees() throws {
        var recorder = RerunCaptureRecorder()
        // 3° per frame in place: 10° is crossed on every 4th frame.
        for i in 0..<20 {
            recorder.add(FakeFrame(timestamp: Double(i) * dt,
                                   cameraTransform: uprightPhone(at: SIMD3(0, 1.5, 0), yawDegrees: Float(i) * 3)))
        }
        XCTAssertEqual(recorder.stats.keyframes, 5) // frames 0, 4, 8, 12, 16
    }

    func testKeyframesRespectTheMinimumInterval() {
        var recorder = RerunCaptureRecorder()
        // 30 cm per frame but frames 1/32 s apart: at most one photo per 0.1 s.
        for i in 0..<32 {
            recorder.add(FakeFrame(timestamp: Double(i) / 32, cameraTransform: uprightPhone(at: SIMD3(Float(i) * 0.3, 0, 0))))
        }
        XCTAssertEqual(recorder.stats.keyframes, 8) // every 4th frame: 0.125 s
    }

    func testPhotoCapStopsPhotosButNotThePath() throws {
        var config = RerunCaptureRecorder.Configuration()
        config.maxKeyframes = 3
        var recorder = RerunCaptureRecorder(configuration: config)
        for i in 0..<60 {
            recorder.add(FakeFrame(timestamp: Double(i) * dt, cameraTransform: uprightPhone(at: SIMD3(Float(i) * 0.05, 0, 0))))
        }
        XCTAssertEqual(recorder.stats.keyframes, 3)
        XCTAssertTrue(recorder.stats.isPhotoLimitReached)
        let pack = recorder.finish()
        XCTAssertEqual(pack.media.count, 3_000)
        XCTAssertEqual(count(try events(pack), "camera_pose"), 60)
    }

    func testMediaByteCapStopsPhotos() {
        var config = RerunCaptureRecorder.Configuration()
        config.maxMediaBytes = 2_500
        var recorder = RerunCaptureRecorder(configuration: config)
        for i in 0..<30 {
            recorder.add(FakeFrame(timestamp: Double(i) * dt, cameraTransform: uprightPhone(at: SIMD3(Float(i) * 0.2, 0, 0))))
        }
        XCTAssertEqual(recorder.stats.keyframes, 2)
        XCTAssertTrue(recorder.stats.isPhotoLimitReached)
    }

    func testLimitedTrackingRecordsNoPoseNoPhotoNoPoint() throws {
        var recorder = RerunCaptureRecorder()
        for i in 0..<10 {
            recorder.add(FakeFrame(timestamp: Double(i) * dt, cameraTransform: uprightPhone(at: SIMD3(Float(i), 0, 0)),
                                   isTrackingNormal: false, points: [SIMD3(0, 0, -1)]))
        }
        XCTAssertFalse(recorder.hasContent)
        XCTAssertEqual(try events(recorder.finish()).count, 0)
    }

    func testFramesThatDoNotMoveTheClockAreIgnored() throws {
        var recorder = RerunCaptureRecorder()
        recorder.add(FakeFrame(timestamp: 2, cameraTransform: uprightPhone(at: .zero)))
        recorder.add(FakeFrame(timestamp: 2, cameraTransform: uprightPhone(at: SIMD3(1, 0, 0))))
        recorder.add(FakeFrame(timestamp: 1, cameraTransform: uprightPhone(at: SIMD3(2, 0, 0))))
        XCTAssertEqual(count(try events(recorder.finish()), "camera_pose"), 1)
    }

    func testRecordingStopsGrowingAtMaxDuration() {
        var config = RerunCaptureRecorder.Configuration()
        config.maxDuration = 1
        var recorder = RerunCaptureRecorder(configuration: config)
        for i in 0..<40 {
            recorder.add(FakeFrame(timestamp: Double(i) * dt, cameraTransform: uprightPhone(at: SIMD3(Float(i) * 0.01, 0, 0))))
        }
        XCTAssertTrue(recorder.isFull)
        XCTAssertEqual(recorder.stats.duration, 1, accuracy: 1e-9)
    }

    // MARK: - Points

    func testPointsAreSampledEveryFifthOfASecondAndFiltered() throws {
        var recorder = RerunCaptureRecorder()
        let camera = uprightPhone(at: SIMD3(0, 1.5, 0))
        let points: [SIMD3<Float>] = [
            SIMD3(0, 1.5, -2), SIMD3(0.3, 1.2, -2), // in view
            SIMD3(0, 1.5, 2), // behind
            SIMD3(20, 1.5, -2), // outside the image
            SIMD3(.nan, 0, -1),
        ]
        for i in 0..<32 {
            recorder.add(FakeFrame(timestamp: Double(i) * dt, cameraTransform: camera, points: points))
        }
        let clouds = try events(recorder.finish()).filter { $0["type"] as? String == "point_cloud" }
        XCTAssertEqual(clouds.count, 8) // 0.25 s apart: every 4th frame of 32
        for cloud in clouds {
            XCTAssertEqual(cloud["entity"] as? String, "world/points")
            let positions = try XCTUnwrap(cloud["positions"] as? [[NSNumber]])
            let colors = try XCTUnwrap(cloud["colors"] as? [[NSNumber]])
            XCTAssertEqual(positions.count, 2)
            XCTAssertEqual(colors.map { $0.map(\.intValue) }, [[200, 100, 50], [200, 100, 50]])
            XCTAssertEqual(positions[1].map(\.floatValue), [0.3, 1.2, -2])
        }
        XCTAssertEqual(recorder.stats.points, 2, "the same two points fill two voxels, however often seen")
    }

    func testPointsWithoutAColourAreDropped() throws {
        var recorder = RerunCaptureRecorder()
        recorder.add(FakeFrame(timestamp: 0, cameraTransform: uprightPhone(at: .zero), points: [SIMD3(0, 0, -1)], color: nil))
        XCTAssertEqual(count(try events(recorder.finish()), "point_cloud"), 0)
    }

    func testPointsPerObservationAreCapped() throws {
        var config = RerunCaptureRecorder.Configuration()
        config.maxPointsPerObservation = 10
        var recorder = RerunCaptureRecorder(configuration: config)
        let points = (0..<100).map { SIMD3<Float>(Float($0 % 10) * 0.05 - 0.25, Float($0 / 10) * 0.05 - 0.25, -2) }
        recorder.add(FakeFrame(timestamp: 0, cameraTransform: uprightPhone(at: .zero), points: points))
        let cloud = try XCTUnwrap(try events(recorder.finish()).first { $0["type"] as? String == "point_cloud" })
        XCTAssertEqual((cloud["positions"] as? [Any])?.count, 10)
    }

    func testVoxelKeyMergesPointsWithinThreeCentimetres() {
        let a = RerunCaptureRecorder.voxelKey(SIMD3(0.001, 0.001, 0.001), size: 0.03)
        XCTAssertEqual(a, RerunCaptureRecorder.voxelKey(SIMD3(0.029, 0.02, 0.01), size: 0.03))
        XCTAssertNotEqual(a, RerunCaptureRecorder.voxelKey(SIMD3(0.031, 0.001, 0.001), size: 0.03))
        XCTAssertNotEqual(a, RerunCaptureRecorder.voxelKey(SIMD3(-0.001, 0.001, 0.001), size: 0.03))
    }

    // MARK: - Planes

    private func square(at center: SIMD3<Float>, half: Float) -> [SIMD3<Float>] {
        [SIMD3(-half, 0, -half), SIMD3(half, 0, -half), SIMD3(half, 0, half), SIMD3(-half, 0, half)].map { $0 + center }
    }

    func testPlaneKindFromAlignmentAndNormal() {
        XCTAssertEqual(RerunCapturePlaneKind.of(isHorizontal: true, isVertical: false, worldNormal: SIMD3(0, 1, 0), isCeiling: false), .horizontalUpward)
        XCTAssertEqual(RerunCapturePlaneKind.of(isHorizontal: true, isVertical: false, worldNormal: SIMD3(0, -1, 0), isCeiling: false), .horizontalDownward)
        XCTAssertEqual(RerunCapturePlaneKind.of(isHorizontal: true, isVertical: false, worldNormal: SIMD3(0, 1, 0), isCeiling: true), .horizontalDownward)
        XCTAssertEqual(RerunCapturePlaneKind.of(isHorizontal: false, isVertical: true, worldNormal: SIMD3(1, 0, 0), isCeiling: false), .vertical)
        XCTAssertEqual(RerunCapturePlaneKind.of(isHorizontal: false, isVertical: false, worldNormal: SIMD3(0, 1, 0), isCeiling: false), .unknown)
        XCTAssertEqual(RerunCapturePlaneKind.horizontalUpward.rawValue, "horizontal_upward")
    }

    func testWorldPolygonAppliesTheAnchorTransform() {
        var anchor = simd_float4x4(simd_quatf(angle: .pi / 2, axis: SIMD3(0, 1, 0)))
        anchor.columns.3 = SIMD4(1, -1.2, -2, 1)
        let world = RerunCaptureMath.worldPolygon(boundary: [SIMD3(0.5, 0, 0)], anchorTransform: anchor)
        XCTAssertEqual(simd_distance(world[0], SIMD3(1, -1.2, -2.5)), 0, accuracy: 1e-5)
        XCTAssertEqual(RerunCaptureMath.area(of: square(at: .zero, half: 0.5)), 1, accuracy: 1e-6)
    }

    func testPlaneIdsAreStableAndOnlyMaterialChangesAreLogged() throws {
        var recorder = RerunCaptureRecorder()
        let floor = UUID(), table = UUID(), wall = UUID()
        let camera = uprightPhone(at: SIMD3(0, 1.5, 0))
        var floorPolygon = square(at: SIMD3(0, -1.3, -2), half: 1)
        let tablePolygon = square(at: SIMD3(0.5, -0.55, -1.2), half: 0.3)
        func frame(_ step: Int, _ planes: [RerunCapturePlane]) -> FakeFrame {
            FakeFrame(timestamp: Double(step) * 0.5, cameraTransform: camera, planeList: planes, jpeg: nil)
        }
        recorder.add(frame(0, [
            RerunCapturePlane(identifier: floor, kind: .horizontalUpward, polygon: floorPolygon),
            RerunCapturePlane(identifier: table, kind: .horizontalUpward, polygon: tablePolygon),
        ]))
        // 5 mm of drift on the floor: not logged.
        floorPolygon[0].x += 0.005
        recorder.add(frame(1, [
            RerunCapturePlane(identifier: floor, kind: .horizontalUpward, polygon: floorPolygon),
            RerunCapturePlane(identifier: table, kind: .horizontalUpward, polygon: tablePolygon),
        ]))
        // The floor grows 5 cm: logged, same id. The table merges away. A wall appears.
        floorPolygon[1].x += 0.05
        recorder.add(frame(2, [
            RerunCapturePlane(identifier: floor, kind: .horizontalUpward, polygon: floorPolygon),
            RerunCapturePlane(identifier: wall, kind: .vertical,
                              polygon: [SIMD3(-1, -1.3, -3), SIMD3(1, -1.3, -3), SIMD3(1, 1, -3), SIMD3(-1, 1, -3)]),
        ]))
        XCTAssertEqual(recorder.stats.planes, 2)

        let planes = try events(recorder.finish()).filter { $0["type"] as? String == "plane" }
        let summary = planes.map { "\(($0["t"] as! NSNumber).int64Value / 500_000_000) \($0["entity"]!) \(($0["polygon"] as! [Any]).count)" }
        XCTAssertEqual(summary, [
            "0 world/planes/1 4", "0 world/planes/2 4",
            "2 world/planes/1 4", "2 world/planes/2 0", "2 world/planes/3 4",
        ])
        XCTAssertEqual(planes[4]["kind"] as? String, "vertical")
        XCTAssertEqual(planes[3]["kind"] as? String, "horizontal_upward")
        XCTAssertEqual(recorder.floorY ?? .nan, -1.3, accuracy: 1e-4)
    }

    func testPlanesWithFewerThanThreeVerticesAreIgnored() {
        var recorder = RerunCaptureRecorder()
        recorder.add(FakeFrame(timestamp: 0, cameraTransform: uprightPhone(at: .zero),
                               planeList: [RerunCapturePlane(identifier: UUID(), kind: .vertical, polygon: [.zero, SIMD3(1, 0, 0)])]))
        XCTAssertEqual(recorder.stats.planes, 0)
    }

    // MARK: - Anchors

    func testAnchorsNeedAFrameAndGetSequentialIds() throws {
        var recorder = RerunCaptureRecorder()
        XCTAssertNil(recorder.addAnchor(position: .zero, orientation: simd_quatf(ix: 0, iy: 0, iz: 0, r: 1)))
        recorder.add(FakeFrame(timestamp: 3, cameraTransform: uprightPhone(at: .zero)))
        recorder.add(FakeFrame(timestamp: 3.5, cameraTransform: uprightPhone(at: SIMD3(0.1, 0, 0))))
        let yaw = simd_quatf(angle: .pi / 2, axis: SIMD3(0, 1, 0))
        XCTAssertEqual(recorder.addAnchor(position: SIMD3(1.3914, -1.281, -0.867), orientation: yaw), 1)
        XCTAssertEqual(recorder.addAnchor(position: SIMD3(0, 0, -1), orientation: yaw), 2)
        XCTAssertEqual(recorder.stats.anchors, 2)
        let anchors = try events(recorder.finish()).filter { $0["type"] as? String == "anchor" }
        XCTAssertEqual(anchors.map { $0["entity"] as? String }, ["world/anchors/1", "world/anchors/2"])
        XCTAssertEqual((anchors[0]["t"] as? NSNumber)?.int64Value, 500_000_000)
        XCTAssertEqual(floats(anchors[0]["translation"]), [1.391, -1.281, -0.867])
        XCTAssertEqual(floats(anchors[0]["quaternion"]), [0, 0.7071, 0, 0.7071])
    }

    // MARK: - Round trip

    /// A small walk with everything in it, then the three payloads read back the way the
    /// replay reads the bundled showcase.
    private func recordWalk() -> RerunCaptureRecorder {
        var recorder = RerunCaptureRecorder()
        let floor = UUID()
        var floorHalf: Float = 0.4
        for i in 0..<96 {
            let yaw = Float(i) * 0.8
            let camera = uprightPhone(at: SIMD3(Float(i) * 0.012, 1.4, -Float(i) * 0.004), yawDegrees: yaw)
            let forward = -SIMD3(camera.columns.2.x, camera.columns.2.y, camera.columns.2.z)
            let eye = RerunCaptureMath.translation(of: camera)
            let points = (0..<30).map { k -> SIMD3<Float> in
                eye + forward * 2 + SIMD3(Float(k % 6) * 0.1 - 0.25, Float(k / 6) * 0.1 - 0.25, 0)
            }
            if i % 8 == 0 { floorHalf += 0.1 }
            let planes = [RerunCapturePlane(identifier: floor, kind: .horizontalUpward,
                                            polygon: square(at: SIMD3(0, -1.28, -1.5), half: floorHalf))]
            // Photo sizes vary, like real JPEGs.
            recorder.add(FakeFrame(timestamp: 100 + Double(i) * dt, cameraTransform: camera, points: points,
                                   planeList: planes, jpeg: Data(repeating: UInt8(i), count: 700 + i * 13)))
            if i == 50 {
                recorder.addAnchor(position: SIMD3(0.3, -1.28, -1.2),
                                   orientation: RerunCaptureMath.facing(from: SIMD3(0.3, -1.28, -1.2), toward: eye))
            }
        }
        return recorder
    }

    func testFinishRoundTripsThroughTheShowcaseFormat() throws {
        let recorder = recordWalk()
        let pack = recorder.finish()
        let log = try events(pack)
        let root = try manifest(pack)

        // Every line is an event of the showcase's vocabulary, on the showcase's entities.
        let entities = ["camera_pose": "world/camera", "image": "world/camera/image", "point_cloud": "world/points"]
        for event in log {
            let type = try XCTUnwrap(event["type"] as? String)
            let entity = try XCTUnwrap(event["entity"] as? String)
            if let expected = entities[type] {
                XCTAssertEqual(entity, expected)
            } else if type == "plane" {
                XCTAssertTrue(entity.hasPrefix("world/planes/"))
            } else {
                XCTAssertEqual(type, "anchor")
                XCTAssertTrue(entity.hasPrefix("world/anchors/"))
            }
        }
        XCTAssertGreaterThan(count(log, "camera_pose"), 50)
        XCTAssertEqual(count(log, "image"), recorder.stats.keyframes)
        XCTAssertGreaterThan(count(log, "image"), 3)
        XCTAssertEqual(count(log, "point_cloud"), 24) // every 4th of 96 frames
        XCTAssertEqual(count(log, "plane"), 12) // grows every 8 frames, sampled every 8
        XCTAssertEqual(count(log, "anchor"), 1)

        // Times: integer nanoseconds from the first frame, never going back.
        var last: Int64 = -1
        for event in log {
            let number = try XCTUnwrap(event["t"] as? NSNumber)
            XCTAssertEqual(number.doubleValue, number.doubleValue.rounded())
            XCTAssertGreaterThanOrEqual(number.int64Value, last)
            last = number.int64Value
        }
        XCTAssertEqual((log.first?["t"] as? NSNumber)?.int64Value, 0)
        XCTAssertEqual(log.first?["type"] as? String, "camera_pose")
        XCTAssertEqual(last, Int64((95 * dt * 1e9).rounded()))

        // Every photo has a pose at its instant, and a unit quaternion.
        let poseTimes = Set(log.filter { $0["type"] as? String == "camera_pose" }.map { ($0["t"] as! NSNumber).int64Value })
        for pose in log where pose["type"] as? String == "camera_pose" {
            let q = floats(pose["quaternion"])
            XCTAssertEqual(q.count, 4)
            XCTAssertEqual(sqrt(q.map { $0 * $0 }.reduce(0, +)), 1, accuracy: 2e-4)
            XCTAssertEqual(floats(pose["translation"]).count, 3)
        }

        // The manifest indexes every photo; the spans tile the media exactly.
        let media = try XCTUnwrap(root["media"] as? [[String: Any]])
        var offset = 0
        var indexed = Set<String>()
        for entry in media {
            let path = try XCTUnwrap(entry["path"] as? String)
            XCTAssertTrue(path.hasPrefix("frames/") && path.hasSuffix(".jpg"), path)
            XCTAssertEqual(entry["offset"] as? Int, offset)
            let length = try XCTUnwrap(entry["length"] as? Int)
            XCTAssertGreaterThan(length, 0)
            // Each span holds exactly one photo (the fake photos are filled with one byte).
            let bytes = pack.media.subdata(in: offset..<offset + length)
            XCTAssertEqual(Set(bytes).count, 1)
            offset += length
            indexed.insert(path)
        }
        XCTAssertEqual(offset, pack.media.count)
        let images = log.filter { $0["type"] as? String == "image" }
        XCTAssertEqual(Set(images.compactMap { $0["path"] as? String }), indexed)
        XCTAssertEqual(images.count, indexed.count)
        for image in images {
            XCTAssertTrue(poseTimes.contains((image["t"] as! NSNumber).int64Value))
        }

        // The lens is the portrait photo's; no plane photos in a live capture.
        let intrinsics = try XCTUnwrap(root["intrinsics"] as? [String: NSNumber])
        XCTAssertEqual(intrinsics["width"]?.intValue, 480)
        XCTAssertEqual(intrinsics["height"]?.intValue, 640)
        XCTAssertEqual(intrinsics["fx"]?.floatValue ?? 0, 470, accuracy: 0.01)
        XCTAssertEqual(intrinsics["cy"]?.floatValue ?? 0, 316.67, accuracy: 0.01)
        XCTAssertEqual((root["textures"] as? [Any])?.count, 0)
        XCTAssertEqual((root["frames"] as? NSNumber)?.intValue, media.count)
        XCTAssertGreaterThan((root["frameRate"] as? NSNumber)?.floatValue ?? 0, 0)
        XCTAssertEqual((root["floorY"] as? NSNumber)?.floatValue ?? .nan, -1.28, accuracy: 1e-4)
    }

    func testEmptyRecorderFinishesToAnEmptyButValidPack() throws {
        let pack = RerunCaptureRecorder().finish()
        XCTAssertTrue(pack.log.isEmpty)
        XCTAssertTrue(pack.media.isEmpty)
        let root = try manifest(pack)
        XCTAssertNil(root["intrinsics"])
        XCTAssertEqual((root["media"] as? [Any])?.count, 0)
        XCTAssertNil(root["floorY"])
    }

    func testPackWritesAndReadsTheThreeFiles() throws {
        let pack = recordWalk().finish()
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("rerun-capture-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: directory) }
        try pack.write(to: directory)
        let names = try FileManager.default.contentsOfDirectory(atPath: directory.path).sorted()
        XCTAssertEqual(names, ["capture-manifest.json", "capture-media.bin", "capture-session.jsonl"])
        XCTAssertEqual(RerunCapturePack.read(from: directory), pack)
        XCTAssertNil(RerunCapturePack.read(from: directory.appendingPathComponent("missing")))
    }

    func testNumbersUseTheShowcasePrecision() {
        XCTAssertEqual(RerunCaptureJSON.number(-1.28049, 3), "-1.28")
        XCTAssertEqual(RerunCaptureJSON.number(0.93712, 4), "0.9371")
        XCTAssertEqual(RerunCaptureJSON.number(0.00004, 4), "0.0")
        XCTAssertEqual(RerunCaptureJSON.number(2, 3), "2.0")
        XCTAssertEqual(RerunCaptureJSON.number(.nan, 3), "0")
        XCTAssertEqual(
            RerunCaptureJSON.cameraPose(t: 7, position: SIMD3(0, 1.5, -0.25), orientation: simd_quatf(ix: 0, iy: 0, iz: 0, r: 1)),
            "{\"t\":7,\"type\":\"camera_pose\",\"entity\":\"world/camera\",\"translation\":[0.0,1.5,-0.25],\"quaternion\":[0.0,0.0,0.0,1.0]}\n"
        )
    }
}

#endif

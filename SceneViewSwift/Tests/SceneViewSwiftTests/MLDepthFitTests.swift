import XCTest
import simd
@testable import SceneViewSwift

/// The ML depth scale fit `1/z = s·d + t`, its temporal smoother, the
/// relative→metric conversion and the anchor projection. The fit cases come
/// from `Resources/ml-depth-fit-vectors.json`, the vectors Android's
/// `DepthScaleFit` is checked against too.
final class MLDepthFitTests: XCTestCase {

    // MARK: - Shared vectors

    private struct VectorFile: Decodable {
        let cases: [Case]
    }

    private struct Case: Decodable {
        let name: String
        let anchors: [DepthAnchorSample]
        let prior: Prior?
        let expect: Expectation
    }

    private struct Prior: Decodable {
        let scale: Float
        let shift: Float
    }

    private struct Expectation: Decodable {
        let valid: Bool
        let scale: Float?
        let shift: Float?
        let tolerance: Float?
        let minInliers: Int?
        let maxInliers: Int?
        let scaleBetweenDataAndPrior: Bool?
    }

    private func loadVectors() throws -> [Case] {
        let url = try XCTUnwrap(Bundle.module.url(forResource: "ml-depth-fit-vectors", withExtension: "json"))
        return try JSONDecoder().decode(VectorFile.self, from: Data(contentsOf: url)).cases
    }

    func testSharedVectors() throws {
        let cases = try loadVectors()
        XCTAssertGreaterThanOrEqual(cases.count, 7)
        for testCase in cases {
            let prior = testCase.prior.map { (scale: $0.scale, shift: $0.shift) }
            let result = AffineInverseDepthFit.fit(testCase.anchors, prior: prior)
            guard testCase.expect.valid else {
                XCTAssertNil(result, "\(testCase.name): expected no fit")
                continue
            }
            let fit = try XCTUnwrap(result, "\(testCase.name): expected a fit")
            let tolerance = testCase.expect.tolerance ?? 0.001
            if let scale = testCase.expect.scale {
                XCTAssertEqual(fit.scale, scale, accuracy: scale * tolerance + 1e-5, "\(testCase.name) scale")
            }
            if let shift = testCase.expect.shift {
                XCTAssertEqual(fit.shift, shift, accuracy: max(abs(shift), 0.05) * tolerance * 4 + 1e-4,
                               "\(testCase.name) shift")
            }
            if let minimum = testCase.expect.minInliers {
                XCTAssertGreaterThanOrEqual(fit.inlierCount, minimum, "\(testCase.name) inliers")
            }
            if let maximum = testCase.expect.maxInliers {
                XCTAssertLessThanOrEqual(fit.inlierCount, maximum, "\(testCase.name) inliers")
            }
            if testCase.expect.scaleBetweenDataAndPrior == true, let prior, let scale = testCase.expect.scale {
                XCTAssertGreaterThan(fit.scale, min(scale, prior.scale), "\(testCase.name) pulled by prior")
                XCTAssertLessThan(fit.scale, max(scale, prior.scale), "\(testCase.name) not past the prior")
            }
        }
    }

    // MARK: - Fit details

    private func exactAnchors(scale: Float, shift: Float, count: Int, near: Float, far: Float) -> [DepthAnchorSample] {
        (0..<count).map { i in
            let z = near + (far - near) * Float(i) / Float(count - 1)
            return DepthAnchorSample(d: (1 / z - shift) / scale, z: z)
        }
    }

    func testTwelveAnchorsIsTheFloor() {
        let twelve = exactAnchors(scale: 0.8, shift: 0.1, count: 12, near: 0.5, far: 3)
        XCTAssertNotNil(AffineInverseDepthFit.fit(twelve))
        XCTAssertNil(AffineInverseDepthFit.fit(Array(twelve.dropLast())))
    }

    func testDepthRatioFloorIsOnePointFive() {
        XCTAssertNotNil(AffineInverseDepthFit.fit(exactAnchors(scale: 1, shift: 0, count: 20, near: 1, far: 1.5)))
        XCTAssertNil(AffineInverseDepthFit.fit(exactAnchors(scale: 1, shift: 0, count: 20, near: 1, far: 1.45)))
    }

    func testReportsInlierRangeAndResidual() throws {
        let fit = try XCTUnwrap(AffineInverseDepthFit.fit(exactAnchors(scale: 2, shift: 0.2, count: 30, near: 0.6, far: 2.4)))
        XCTAssertEqual(fit.inlierDepthRange.lowerBound, 0.6, accuracy: 1e-5)
        XCTAssertEqual(fit.inlierDepthRange.upperBound, 2.4, accuracy: 1e-5)
        XCTAssertLessThan(fit.relativeRMSError, 1e-4)
    }

    func testMetricOutputIsNeverFitted() throws {
        // 1/z = 0.5·d + 0.1: fits as affine inverse, refused as metric.
        let anchors = (0..<20).map { i -> DepthAnchorSample in
            let z = 0.5 + Float(i) * 0.15
            return DepthAnchorSample(d: (1 / z - 0.1) / 0.5, z: z)
        }
        XCTAssertNotNil(AffineInverseDepthFit.fit(anchors, kind: .affineInverse))
        XCTAssertNil(AffineInverseDepthFit.fit(anchors, kind: .metric))
    }

    // MARK: - Smoother

    private func result(_ scale: Float, _ shift: Float) -> AffineInverseDepthFit.Result {
        AffineInverseDepthFit.Result(scale: scale, shift: shift, inlierCount: 20, relativeRMSError: 0.01,
                                     inlierDepthRange: 0.5...3)
    }

    func testNoOutputBeforeTheFirstValidFit() {
        var smoother = DepthScaleSmoother()
        XCTAssertNil(smoother.update(with: nil))
        XCTAssertNil(smoother.prior)
    }

    func testHoldsFiveFramesThenStops() throws {
        var smoother = DepthScaleSmoother()
        _ = smoother.update(with: result(1, 0.1))
        for frame in 1...5 {
            let held = try XCTUnwrap(smoother.update(with: nil), "frame \(frame) should be held")
            XCTAssertTrue(held.held)
            XCTAssertEqual(held.scale, 1)
        }
        XCTAssertNil(smoother.update(with: nil), "a sixth rejected fit must stop the depth")
        XCTAssertNil(smoother.prior)
    }

    func testAValidFitResetsTheHoldCounter() throws {
        var smoother = DepthScaleSmoother()
        _ = smoother.update(with: result(1, 0.1))
        for _ in 1...4 { _ = smoother.update(with: nil) }
        XCTAssertFalse(try XCTUnwrap(smoother.update(with: result(1, 0.1))).held)
        for _ in 1...5 { XCTAssertNotNil(smoother.update(with: nil)) }
    }

    func testSmoothsLogScaleAndShift() throws {
        var smoother = DepthScaleSmoother(gain: 0.5)
        _ = smoother.update(with: result(1, 0))
        let out = try XCTUnwrap(smoother.update(with: result(4, 0.2)))
        XCTAssertEqual(out.scale, 2, accuracy: 1e-5, "geometric mean in log space")
        XCTAssertEqual(out.shift, 0.1, accuracy: 1e-6)
    }

    // MARK: - Conversion

    func testConversionBoundsAndValues() {
        // 1/z = 1·d + 0 → z = 1/d.
        let values: [Float] = [1, 0.5, 0.25, 10, 0.1, -1]   // 1 m, 2 m, 4 m, 0.1 m, 10 m, behind
        let estimate = MonocularDepthEstimate(width: 6, height: 1, values: values)
        let out = MonocularDepthConversion.convert(estimate, kind: .affineInverse, scale: 1, shift: 0,
                                                   anchorDepthRange: 0.5...3, relativeRMSError: 0)
        XCTAssertEqual(out.millimetres[0], 1000)
        XCTAssertEqual(out.millimetres[1], 2000)
        XCTAssertEqual(out.millimetres[2], 4000, "4 m is inside twice the anchor range")
        XCTAssertEqual(out.millimetres[3], 0, "0.1 m is below the 0.2 m floor")
        XCTAssertEqual(out.millimetres[4], 0, "10 m is past both 8 m and twice the anchors")
        XCTAssertEqual(out.millimetres[5], 0, "s·d + t ≤ ε is invalid")
        XCTAssertEqual(out.confidence[5], 0)
        // Neighbouring pixels 1 m apart are a depth edge: compare confidences on
        // isolated pixels so only the extrapolation factor differs.
        func confidence(_ v: Float) -> UInt8 {
            MonocularDepthConversion.convert(MonocularDepthEstimate(width: 1, height: 1, values: [v]),
                                             kind: .affineInverse, scale: 1, shift: 0,
                                             anchorDepthRange: 0.5...3, relativeRMSError: 0).confidence[0]
        }
        XCTAssertEqual(confidence(1), 255)
        XCTAssertGreaterThan(confidence(1), confidence(0.25), "extrapolated depth is less trusted")
        XCTAssertEqual(out.confidence[0], 0, "a 1 m step to the next pixel is a depth edge")
    }

    func testExtrapolationWindowFollowsAnchors() {
        let estimate = MonocularDepthEstimate(width: 2, height: 1, values: [Float(1 / 0.4), Float(1 / 0.6)])
        let out = MonocularDepthConversion.convert(estimate, kind: .affineInverse, scale: 1, shift: 0,
                                                   anchorDepthRange: 1.0...2.0, relativeRMSError: 0)
        XCTAssertEqual(out.millimetres[0], 0, "0.4 m is below half the nearest anchor")
        XCTAssertEqual(out.millimetres[1], 600)
    }

    // MARK: - Frame and projection

    private func intrinsics(fx: Float, fy: Float, cx: Float, cy: Float) -> simd_float3x3 {
        simd_float3x3(columns: (SIMD3(fx, 0, 0), SIMD3(0, fy, 0), SIMD3(cx, cy, 1)))
    }

    func testProjectionRoundTripsThroughWorldPoint() throws {
        let k = intrinsics(fx: 400, fy: 380, cx: 259, cy: 196)
        let pose = simd_float4x4(simd_quatf(angle: 0.3, axis: simd_normalize(SIMD3<Float>(0.2, 1, 0.1))))
            * simd_float4x4(columns: (SIMD4(1, 0, 0, 0), SIMD4(0, 1, 0, 0), SIMD4(0, 0, 1, 0), SIMD4(0.4, 1.2, -0.3, 1)))
        let world = pose * SIMD4<Float>(0.3, -0.2, -2.0, 1)
        let p = try XCTUnwrap(DepthAnchorProjection.project(
            SIMD3(world.x, world.y, world.z), intrinsics: k, worldToCamera: pose.inverse, width: 518, height: 392))
        XCTAssertEqual(p.z, 2.0, accuracy: 1e-4)
        // Store that depth at the projected pixel and unproject it back.
        let x = Int(p.u), y = Int(p.v)
        var mm = [UInt16](repeating: 0, count: 518 * 392)
        mm[y * 518 + x] = 2000
        let frame = ARDepthFrame(timestamp: 0, width: 518, height: 392, millimetres: mm,
                                 intrinsics: k, cameraTransform: pose, source: .ml)
        let back = try XCTUnwrap(frame.worldPoint(atX: x, y: y))
        // Within one pixel's footprint at 2 m (~5 mm).
        XCTAssertLessThan(simd_distance(back, SIMD3(world.x, world.y, world.z)), 0.01)
        XCTAssertNil(frame.depth(atX: 0, y: 0))
        XCTAssertEqual(frame.source, .ml)
    }

    func testBehindTheCameraDoesNotProject() {
        let k = intrinsics(fx: 400, fy: 400, cx: 259, cy: 196)
        XCTAssertNil(DepthAnchorProjection.project(SIMD3(0, 0, 1), intrinsics: k,
                                                   worldToCamera: matrix_identity_float4x4, width: 518, height: 392))
    }

    func testPlaneGridHitsAFloorInsideItsPolygon() throws {
        // Camera 1.5 m above a floor, looking straight down (camera −z = world −y).
        let lookDown = simd_float4x4(simd_quatf(angle: -.pi / 2, axis: SIMD3(1, 0, 0)))
        var pose = lookDown
        pose.columns.3 = SIMD4(0, 1.5, 0, 1)
        let floor = MLDepthPlane(transform: matrix_identity_float4x4,
                                 boundary: [SIMD2(-2, -2), SIMD2(2, -2), SIMD2(2, 2), SIMD2(-2, 2)])
        let k = intrinsics(fx: 400, fy: 400, cx: 259, cy: 196)
        let estimate = MonocularDepthEstimate(width: 518, height: 392,
                                              values: [Float](repeating: 1 / 1.5, count: 518 * 392))
        let samples = DepthAnchorProjection.samples(estimate: estimate, intrinsics: k, cameraTransform: pose,
                                                    featurePoints: [], planes: [floor])
        XCTAssertEqual(samples.count, 48, "every grid ray lands on the 4 × 4 m floor")
        let centre = try XCTUnwrap(DepthAnchorProjection.nearestPlaneDepth(
            pixel: (259, 196), intrinsics: k, cameraTransform: pose,
            origin: SIMD3(0, 1.5, 0), planes: [floor], planeInverses: [floor.transform.inverse]))
        XCTAssertEqual(centre, 1.5, accuracy: 1e-4)

        let small = MLDepthPlane(transform: matrix_identity_float4x4,
                                 boundary: [SIMD2(-0.1, -0.1), SIMD2(0.1, -0.1), SIMD2(0.1, 0.1), SIMD2(-0.1, 0.1)])
        let few = DepthAnchorProjection.samples(estimate: estimate, intrinsics: k, cameraTransform: pose,
                                                featurePoints: [], planes: [small])
        XCTAssertLessThan(few.count, 48, "rays outside a small polygon miss it")
    }

    func testBilinearSamplingUsesPixelCentres() throws {
        let map: [Float] = [0, 1, 2, 3]   // 2 × 2
        XCTAssertEqual(try XCTUnwrap(DepthAnchorProjection.sampleBilinear(map, width: 2, height: 2, u: 0.5, v: 0.5)), 0)
        XCTAssertEqual(try XCTUnwrap(DepthAnchorProjection.sampleBilinear(map, width: 2, height: 2, u: 1, v: 1)), 1.5,
                       accuracy: 1e-6)
        XCTAssertNil(DepthAnchorProjection.sampleBilinear(map, width: 2, height: 2, u: 0.2, v: 0.5))
    }

    // MARK: - Stats (Android: MlDepthSession STATS_WINDOW = 30)

    func testStatsWindowMedianAndPublishedRate() {
        var window = MLDepthStatsWindow()
        XCTAssertFalse(window.hasPublished)
        XCTAssertEqual(window.publishedHz, 0)
        for ms in [300.0, 100, 200] { window.recordInference(ms) }
        XCTAssertEqual(window.medianInferenceMilliseconds, 200)
        // 5 publishes 0.25 s apart = 4 Hz.
        for i in 0..<5 { window.recordPublish(at: 10 + Double(i) * 0.25) }
        XCTAssertEqual(window.publishedHz, 4)
        // Only the last 30 runs count.
        for _ in 0..<30 { window.recordInference(50) }
        XCTAssertEqual(window.medianInferenceMilliseconds, 50)
        let stats = window.snapshot(MLDepthFitSummary(
            inferenceMilliseconds: 48, anchors: 120, inliers: 90, rmsRelativeError: 0.04, holding: true))
        XCTAssertEqual(stats, MLDepthStats(lastInferenceMs: 48, medianInferenceMs: 50, publishedHz: 4,
                                           anchors: 120, inliers: 90, rmsRelativeError: 0.04, holding: true))
    }

    func testFailedStatesCompareByError() {
        struct Boom: Error { let code: Int }
        XCTAssertEqual(DepthSourceState.failed(Boom(code: 1)), .failed(Boom(code: 1)))
        XCTAssertNotEqual(DepthSourceState.failed(Boom(code: 1)), .failed(Boom(code: 2)))
        XCTAssertNotEqual(DepthSourceState.failed(Boom(code: 1)), .preparing)
        XCTAssertEqual(DepthSourceState.waitingForAnchors(anchors: 3), .waitingForAnchors(anchors: 3))
    }
}

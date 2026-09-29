#if os(iOS) || os(macOS)
import Metal
import XCTest
@testable import SceneViewSwift

/// The bloom pass decides from formats and sizes alone whether it blooms a frame, copies it
/// through, or leaves it. On a device the worst case must be a frame without glow — never a
/// black, garbled or trapped one — so every format it cannot write falls back to the copy.
final class BloomFramePlanTests: XCTestCase {

    private let size = (width: 1206, height: 2622)

    func testSRGBFrameBloomsThroughRawViews() {
        XCTAssertEqual(
            BloomFramePlan.decide(enabled: true, source: .bgra8Unorm_srgb, sourceSize: size,
                                  target: .bgra8Unorm_srgb, targetSize: size),
            .bloom(readAs: .bgra8Unorm, writeAs: .bgra8Unorm))
        XCTAssertEqual(
            BloomFramePlan.decide(enabled: true, source: .rgba8Unorm_srgb, sourceSize: size,
                                  target: .rgba8Unorm_srgb, targetSize: size),
            .bloom(readAs: .rgba8Unorm, writeAs: .rgba8Unorm))
    }

    func testHalfFloatFrameBloomsDirectly() {
        XCTAssertEqual(
            BloomFramePlan.decide(enabled: true, source: .rgba16Float, sourceSize: size,
                                  target: .rgba16Float, targetSize: size),
            .bloom(readAs: .rgba16Float, writeAs: .rgba16Float))
    }

    func testExtendedRangeFramePassesThrough() {
        for format: MTLPixelFormat in [.bgra10_xr, .bgra10_xr_srgb, .bgr10_xr, .bgr10_xr_srgb, .rgb10a2Unorm] {
            XCTAssertEqual(
                BloomFramePlan.decide(enabled: true, source: format, sourceSize: size,
                                      target: format, targetSize: size),
                .passThrough, "\(format.rawValue)")
        }
    }

    func testDisabledBloomPassesThrough() {
        XCTAssertEqual(
            BloomFramePlan.decide(enabled: false, source: .bgra8Unorm_srgb, sourceSize: size,
                                  target: .bgra8Unorm_srgb, targetSize: size),
            .passThrough)
    }

    func testMismatchedFramesAreLeftAlone() {
        // A blit between two formats or two sizes would trap: neither bloom nor copy.
        XCTAssertEqual(
            BloomFramePlan.decide(enabled: true, source: .bgra10_xr, sourceSize: size,
                                  target: .bgra8Unorm, targetSize: size),
            .skip)
        XCTAssertEqual(
            BloomFramePlan.decide(enabled: true, source: .bgra8Unorm, sourceSize: size,
                                  target: .bgra8Unorm, targetSize: (width: 603, height: 1311)),
            .skip)
    }

    func testDegenerateFramePassesThrough() {
        XCTAssertEqual(
            BloomFramePlan.decide(enabled: true, source: .bgra8Unorm, sourceSize: (1, 1),
                                  target: .bgra8Unorm, targetSize: (1, 1)),
            .passThrough)
    }

    func testZeroStrengthIsDisabledAndDefaultsMatchTheDocs() {
        XCTAssertFalse(BloomOptions.disabled.isEnabled)
        XCTAssertFalse(BloomOptions(strength: 0).isEnabled)
        XCTAssertTrue(BloomOptions(strength: 0.45).isEnabled)
        XCTAssertEqual(BloomOptions().thresholdLevel, 0.6)
        XCTAssertEqual(BloomOptions().resolution, 384)
    }
}
#endif

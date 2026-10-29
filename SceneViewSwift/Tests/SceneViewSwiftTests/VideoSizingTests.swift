#if os(iOS)
import XCTest
@testable import SceneViewSwift

/// Mirrors Android's `VideoSizingTest.kt`: the video size a ``SceneRecorder`` picks for a scene,
/// and the ``VideoSettings`` it accepts.
final class VideoSizingTests: XCTestCase {

    private func size(_ w: Int, _ h: Int, _ long: Int = 1920, _ short: Int = 1080,
                      _ supported: (Int, Int) -> Bool = { _, _ in true }) -> VideoSize? {
        chooseVideoSize(sourceWidth: w, sourceHeight: h, maxLongEdge: long, maxShortEdge: short, isSupported: supported)
    }

    func testA16By9PortraitViewRecordsAtExactly1080By1920() {
        XCTAssertEqual(size(1080, 1920), VideoSize(width: 1080, height: 1920))
    }

    func testA20By9PhoneKeepsItsAspectRatioInsideThe1080pBox() {
        XCTAssertEqual(size(1080, 2400), VideoSize(width: 864, height: 1920))
    }

    func testALandscapeViewRecordsALandscapeVideo() {
        XCTAssertEqual(size(2400, 1080), VideoSize(width: 1920, height: 864))
    }

    func testASmallViewIsScaledUpTo1080p() {
        XCTAssertEqual(size(540, 960), VideoSize(width: 1080, height: 1920))
    }

    func testAnIPhoneDrawableKeepsEvenEdges() {
        // iPhone 15 / 16 portrait drawable.
        let fitted = size(1179, 2556)
        XCTAssertEqual(fitted, VideoSize(width: 884, height: 1920))
    }

    func testBothEdgesAreEvenAsH264Requires() throws {
        let fitted = try XCTUnwrap(size(1001, 1999))
        XCTAssertEqual(fitted.width % 2, 0)
        XCTAssertEqual(fitted.height % 2, 0)
    }

    func testASquareViewFitsTheShortEdgeOfTheBox() {
        XCTAssertEqual(size(800, 800), VideoSize(width: 1080, height: 1080))
    }

    func testAnEncoderThatWantsMultiplesOf16GetsThem() {
        XCTAssertEqual(size(1080, 2340) { w, h in w % 16 == 0 && h % 16 == 0 }, VideoSize(width: 880, height: 1920))
    }

    func testAnEncoderWithout1080pStepsDownTo720p() {
        XCTAssertEqual(size(1080, 1920) { w, h in max(w, h) <= 1280 }, VideoSize(width: 720, height: 1280))
    }

    func testARefused4KBoxStepsDownTo1080pBefore720p() {
        XCTAssertEqual(size(2160, 3840, 3840, 2160) { w, h in max(w, h) <= 1920 }, VideoSize(width: 1080, height: 1920))
    }

    func testStepDownsNeverExceedASmallerRequestedShortEdge() {
        var tried: [VideoSize] = []
        _ = size(1920, 1080, 1920, 480) { w, h in tried.append(VideoSize(width: w, height: h)); return false }
        XCTAssertEqual(tried.first, VideoSize(width: 852, height: 480))
        XCTAssertTrue(tried.allSatisfy { min($0.width, $0.height) <= 480 }, "\(tried)")
        XCTAssertTrue(tried.allSatisfy { max($0.width, $0.height) <= 1920 }, "\(tried)")
    }

    func testASmallerRequestedBoxIsHonoured() {
        XCTAssertEqual(size(1080, 1920, 1280, 720), VideoSize(width: 720, height: 1280))
    }

    func testNoSizeWhenTheEncoderSupportsNone() {
        XCTAssertNil(size(1080, 1920) { _, _ in false })
    }

    func testNoSizeBeforeTheViewHasBeenLaidOut() {
        XCTAssertNil(size(0, 1920))
        XCTAssertNil(size(1080, 0))
    }

    func testVideoSettingsRejectImpossibleValues() {
        XCTAssertThrowsError(try VideoSettings(maxLongEdge: 0).validate())
        XCTAssertThrowsError(try VideoSettings(maxLongEdge: 720, maxShortEdge: 1080).validate())
        XCTAssertThrowsError(try VideoSettings(frameRate: 0).validate())
        XCTAssertThrowsError(try VideoSettings(bitRate: 0).validate())
        XCTAssertThrowsError(try VideoSettings(maxDuration: 0).validate())
        XCTAssertThrowsError(try VideoSettings(maxDuration: -1).validate())
        XCTAssertNoThrow(try VideoSettings(maxDuration: 60).validate())
    }

    func testDefaultVideoSettingsAre1080pAt30FpsSilentUnlimited() {
        let settings = VideoSettings()
        XCTAssertEqual(settings.maxLongEdge, 1920)
        XCTAssertEqual(settings.maxShortEdge, 1080)
        XCTAssertEqual(settings.frameRate, 30)
        XCTAssertEqual(settings.bitRate, 12_000_000)
        XCTAssertFalse(settings.recordAudio)
        XCTAssertNil(settings.maxDuration)
    }

    func testPhotoFormats() {
        XCTAssertEqual(PhotoFormat.jpeg.mimeType, "image/jpeg")
        XCTAssertEqual(PhotoFormat.jpeg.fileExtension, "jpg")
        XCTAssertEqual(PhotoFormat.png.mimeType, "image/png")
        XCTAssertEqual(PhotoFormat.png.fileExtension, "png")
    }

    func testFrameCopyKeepsGammaEncodedBytes() {
        // Linear sources (sRGB views, float) get an sRGB destination that re-encodes them;
        // already-encoded sources are copied as they are.
        XCTAssertEqual(FrameTap.destinationFormat(for: .bgra8Unorm_srgb), .bgra8Unorm_srgb)
        XCTAssertEqual(FrameTap.destinationFormat(for: .rgba16Float), .bgra8Unorm_srgb)
        XCTAssertEqual(FrameTap.destinationFormat(for: .bgra8Unorm), .bgra8Unorm)
    }
}
#endif

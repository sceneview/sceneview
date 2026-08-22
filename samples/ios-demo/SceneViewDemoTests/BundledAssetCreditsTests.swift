import XCTest
// Deliberately NOT wrapped in `#if DEBUG` — see BundledAssetPrimBudgetTests for
// why: `ENABLE_TESTABILITY` is Debug-only, so a Release test run must fail to
// COMPILE rather than silently compile to an empty, green file.
@testable import SceneViewDemo

/// Pins the attribution surface for keyless builds (#2966).
///
/// Every demo that streams a `SketchfabSlug` captions it with an author. On a
/// keyless build the bundled fallback is what renders, so the caption has to
/// credit the fallback instead. These tests keep the two halves in step: the
/// registry cannot hand out a fallback the credit table does not know, and the
/// caption cannot borrow the streamed author when the probe measured a
/// fallback.
final class BundledAssetCreditsTests: XCTestCase {

    /// The host app's bundle — where the resolver looks for a fallback.
    private var bundle: Bundle { .main }

    /// A fallback without a credit row would render uncredited on every
    /// App Store build — which is the #2966 defect in its licence-edge form.
    func testEveryDeclaredFallbackHasACredit() {
        let declared = Set(SampleAssets.all.map(\.fallbackBundledPath))
        XCTAssertFalse(declared.isEmpty, "SampleAssets declared no fallbacks")
        for path in declared.sorted() {
            let credit = BundledAssetCredits.credit(forBundledPath: path)
            XCTAssertNotNil(credit, "\(path) has no row in BundledAssetCredits")
            XCTAssertFalse(credit?.author.isEmpty ?? true, "\(path) credit has no author")
            XCTAssertFalse(credit?.license.isEmpty ?? true, "\(path) credit has no licence")
        }
    }

    /// The other direction: a credit row must name a file that ships. A stale
    /// row is harmless today but would let a typo in a future
    /// `fallbackBundledPath` pass the test above against the wrong asset.
    func testEveryCreditNamesAShippedAsset() throws {
        for path in BundledAssetCredits.byPath.keys.sorted() {
            let file = (path as NSString).lastPathComponent
            let name = (file as NSString).deletingPathExtension
            let ext = (file as NSString).pathExtension
            let components = path.split(separator: "/").dropLast()
            let subdirectory = components.isEmpty ? nil : components.joined(separator: "/")
            let url = bundle.url(forResource: name, withExtension: ext, subdirectory: subdirectory)
                ?? bundle.url(forResource: name, withExtension: ext)
            XCTAssertNotNil(url, "BundledAssetCredits names \(path), which is not in the app bundle")
        }
    }

    // MARK: - Caption

    private var monstera: SketchfabSlug {
        try! XCTUnwrap(SampleAssets.all.first { $0.displayName == "Potted Monstera" })
    }

    func testStreamedCaptionCreditsTheSketchfabAuthor() {
        XCTAssertEqual(
            AssetCreditLine.text(slug: monstera, source: .streamed),
            "by ChubbyPanda · CC-BY 4.0"
        )
    }

    /// The App Store shape: the helmet is on screen, so the helmet is credited
    /// and the streamed author is nowhere in the caption.
    func testBundledCaptionCreditsTheFallbackNotTheStreamedAuthor() throws {
        let text = try XCTUnwrap(AssetCreditLine.text(slug: monstera, source: .bundled))
        XCTAssertTrue(text.hasPrefix("Offline stand-in:"), text)
        XCTAssertTrue(text.contains("Damaged Helmet"), text)
        XCTAssertTrue(text.contains("KhronosGroup"), text)
        XCTAssertFalse(text.contains("ChubbyPanda"), "streamed author leaked into the fallback caption: \(text)")
    }

    /// `retro_piano` is CC-BY-NC, so the caption must print the fallback's own
    /// licence rather than the registry's blanket "CC-BY 4.0".
    func testBundledCaptionPrintsTheFallbackLicence() throws {
        let mug = try XCTUnwrap(SampleAssets.all.first { $0.displayName == "Coffee Mug" })
        let text = try XCTUnwrap(AssetCreditLine.text(slug: mug, source: .bundled))
        XCTAssertTrue(text.contains("CC-BY-NC 4.0"), text)
        XCTAssertFalse(text.contains("FrenchBaguette"), text)
    }

    func testStreamingHasNoCaption() {
        XCTAssertNil(AssetCreditLine.text(slug: monstera, source: .streaming))
    }
}

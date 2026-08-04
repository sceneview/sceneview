import XCTest
// Deliberately NOT wrapped in `#if DEBUG` — see BundledAssetPrimBudgetTests for
// why: `ENABLE_TESTABILITY` is Debug-only, so a Release test run must fail to
// COMPILE rather than silently compile to an empty, green file.
@testable import SceneViewDemo

/// Unit tests for ``AssetSourceProbe`` — the rule behind every demo's
/// asset-source pill.
///
/// Port of Android's `AssetSourceProbeTest`, case for case, so a divergence
/// between the two platforms shows up as a failing test rather than as two
/// demos that disagree about what they are showing.
///
/// Pure Foundation: ``SketchfabAssetResolver/isBundledFallback(_:)`` keys on the
/// parent directory NAME, so a synthetic `URL` is enough and nothing touches the
/// disk, the bundle, or RealityKit.
///
/// The defect these pin is #2960's premise: on a keyless build 14 of the 29
/// registry slugs resolve to a bundled USDZ whose subject contradicts the label
/// beside it, and until now iOS showed no cue at all. Every case below carrying
/// `hasAPIKey: true` with a fallback URL is also the Android regression (#2933):
/// a configured key is not evidence that the download succeeded.
final class AssetSourceProbeTests: XCTestCase {

    private let cacheRoot = URL(fileURLWithPath: "/tmp/Caches/sketchfab", isDirectory: true)

    /// What the resolver hands back on the network path.
    private func streamed(_ uid: String) -> URL {
        cacheRoot.appendingPathComponent("\(uid).usdz")
    }

    /// What it hands back on every failure path — `sketchfab/fallback/<uid>.usdz`.
    private func fallback(_ uid: String) -> URL {
        cacheRoot
            .appendingPathComponent(SketchfabAssetResolver.fallbackDirName, isDirectory: true)
            .appendingPathComponent("\(uid).usdz")
    }

    // MARK: - The regression: a KEYED build that fell back

    func testAKeyedBuildThatFellBackReportsBundledNotStreamed() {
        XCTAssertEqual(
            AssetSourceProbe.of(resolvedURL: fallback("car"), hasAPIKey: true, loaded: true),
            .bundled,
            "a configured key says nothing about whether the download succeeded — "
            + "the resolved file came out of fallback/, so the pill must say Offline model"
        )
    }

    func testOneFallenBackSlotOutOfFourSinksTheWholeScene() {
        let urls: [URL?] = [streamed("a"), streamed("b"), fallback("c"), streamed("d")]
        XCTAssertEqual(
            AssetSourceProbe.ofAll(resolvedURLs: urls, hasAPIKey: true, loaded: true),
            .bundled,
            "the verdict is whole-scene and pessimistic: one stand-in in the park "
            + "formation means the pill must not promise a streamed scene"
        )
    }

    func testAFallbackBeatsLoadedTheMeasuredBranchWinsOverEveryGuess() {
        // Same inputs as the streamed case below except the parent directory. If
        // the measured branch is removed, `loaded: true` + a key falls through to
        // `.streamed` and this is the test that catches it.
        XCTAssertEqual(
            AssetSourceProbe.of(resolvedURL: fallback("car"), hasAPIKey: true, loaded: true),
            .bundled
        )
        XCTAssertEqual(
            AssetSourceProbe.of(resolvedURL: streamed("car"), hasAPIKey: true, loaded: true),
            .streamed
        )
    }

    // MARK: - The pre-resolve path: the only place the key is read

    func testNothingResolvedYetWithAKeyReadsStreaming() {
        XCTAssertEqual(
            AssetSourceProbe.of(resolvedURL: nil, hasAPIKey: true, loaded: false),
            .streaming
        )
    }

    func testNothingResolvedYetWithoutAKeyReadsBundled() {
        // Keyless is where this ends regardless: `resolve` short-circuits to the
        // bundled fallback, so claiming "Streaming…" would be a spinner that
        // never lands.
        XCTAssertEqual(
            AssetSourceProbe.of(resolvedURL: nil, hasAPIKey: false, loaded: false),
            .bundled
        )
    }

    func testAResolvedStreamedFileThatHasNotFinishedLoadingStillReadsStreaming() {
        // AR Placement ties `loaded` to the file; Gallery ties it to the parsed
        // ModelNode. This is the Gallery shape — file in hand, parse still running.
        XCTAssertEqual(
            AssetSourceProbe.of(resolvedURL: streamed("car"), hasAPIKey: true, loaded: false),
            .streaming
        )
    }

    func testAResolvedFallbackReportsBundledBeforeTheParseFinishes() {
        // The origin is settled the moment the file is known, so the pill does
        // not wait for the parse to say so: "Offline model" claims an origin,
        // not a finished download.
        XCTAssertEqual(
            AssetSourceProbe.of(resolvedURL: fallback("car"), hasAPIKey: true, loaded: false),
            .bundled
        )
    }

    func testTheKeyGateIsOnLoadedNotOnWhetherAFileResolved() {
        // Pins the parameter contract. A streamed file HAS resolved, yet
        // `loaded: false` still routes through the key branch — so with no key
        // this reads `.bundled`, not `.streaming`. Gallery and Materials reach
        // exactly this state, since their `loaded` is a parsed model.
        XCTAssertEqual(
            AssetSourceProbe.of(resolvedURL: streamed("car"), hasAPIKey: false, loaded: false),
            .bundled
        )
        XCTAssertEqual(
            AssetSourceProbe.of(resolvedURL: streamed("car"), hasAPIKey: true, loaded: false),
            .streaming
        )
    }

    // MARK: - The happy path

    func testEverySlotStreamedAndLoadedReportsStreamed() {
        let urls: [URL?] = [streamed("a"), streamed("b"), streamed("c"), streamed("d")]
        XCTAssertEqual(
            AssetSourceProbe.ofAll(resolvedURLs: urls, hasAPIKey: true, loaded: true),
            .streamed
        )
    }

    func testAPartiallyResolvedKeyedSceneReadsStreamingNotStreamed() {
        let urls: [URL?] = [streamed("a"), nil, streamed("c"), nil]
        XCTAssertEqual(
            AssetSourceProbe.ofAll(resolvedURLs: urls, hasAPIKey: true, loaded: false),
            .streaming
        )
    }

    func testAPartiallyResolvedKeylessSceneReadsBundled() {
        let urls: [URL?] = [streamed("a"), nil, nil, nil]
        XCTAssertEqual(
            AssetSourceProbe.ofAll(resolvedURLs: urls, hasAPIKey: false, loaded: false),
            .bundled
        )
    }

    // MARK: - The single-slot form is the list form, and must stay that way

    func testTheSingleSlotFormAgreesWithTheOneElementListFormEverywhere() {
        let urls: [URL?] = [nil, streamed("car"), fallback("car")]
        for url in urls {
            for hasAPIKey in [true, false] {
                for loaded in [true, false] {
                    XCTAssertEqual(
                        AssetSourceProbe.of(
                            resolvedURL: url, hasAPIKey: hasAPIKey, loaded: loaded
                        ),
                        AssetSourceProbe.ofAll(
                            resolvedURLs: [url], hasAPIKey: hasAPIKey, loaded: loaded
                        ),
                        "of(\(String(describing: url)), key=\(hasAPIKey), loaded=\(loaded)) "
                        + "must equal ofAll of the same single slot — the two forms differ "
                        + "only in arity"
                    )
                }
            }
        }
    }

    // MARK: - The probe reads the directory the resolver actually stages into

    func testTheProbeReadsTheRealStagingDirectoryNameNotAHardcodedString() {
        // If `fallbackDirName` ever changes, `fallback()` above follows it and
        // these tests keep testing the truth. This asserts the constant is what
        // `fallbackBundle(for:)` uses, so the helpers cannot quietly drift into
        // testing nothing.
        XCTAssertEqual(SketchfabAssetResolver.fallbackDirName, "fallback")
        XCTAssertEqual(
            AssetSourceProbe.of(
                resolvedURL: cacheRoot
                    .appendingPathComponent("fallback", isDirectory: true)
                    .appendingPathComponent("car.usdz"),
                hasAPIKey: true,
                loaded: true
            ),
            .bundled
        )
    }

    /// A streamed file sits directly under the cache root, one level above the
    /// staging directory — the discriminator is the PARENT name, so a file whose
    /// own name merely contains "fallback" must not be mistaken for one.
    func testAStreamedFileNamedLikeTheStagingDirectoryIsStillStreamed() {
        XCTAssertFalse(
            SketchfabAssetResolver.isBundledFallback(
                cacheRoot.appendingPathComponent("fallback.usdz")
            )
        )
        XCTAssertTrue(SketchfabAssetResolver.isBundledFallback(fallback("car")))
    }
}

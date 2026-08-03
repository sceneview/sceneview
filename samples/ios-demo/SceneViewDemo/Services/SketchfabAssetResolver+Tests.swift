#if DEBUG
import XCTest
@testable import SceneViewDemo

/// Unit tests for `SketchfabAssetResolver` and `SampleAssets` — the iOS
/// mirror of `SketchfabAssetResolverTest.kt` / `SampleAssetsTest.kt`. None of
/// these tests touch the network or App Store-bundled assets at full size;
/// they validate registry invariants and a handful of resolver behaviours
/// (Unknown error, magic-byte heuristic, retryable-status math).
///
/// The tests deliberately do NOT make a live Sketchfab round-trip. The
/// resolver's `resolve(_:)` path is best exercised by the visual smoke
/// screenshots gated on `SKETCHFAB_API_KEY` in Stage 2 demo migrations.
final class SampleAssetsTests: XCTestCase {

    // ─── Curation invariants ───────────────────────────────────────────────

    func testRegistryIsNonEmpty() {
        XCTAssertFalse(SampleAssets.all.isEmpty,
                       "SampleAssets.all must list at least one curated slug")
    }

    func testEveryEntryIsCCBY40() {
        for slug in SampleAssets.all {
            XCTAssertTrue(
                slug.licenseURL.absoluteString.hasPrefix("https://creativecommons.org/licenses/by/"),
                "Non-CC-BY entry: \(slug.uid) \(slug.licenseURL)"
            )
            XCTAssertTrue(
                slug.licenseURL.absoluteString.contains("/by/4.0/"),
                "CC-BY entry not on v4.0: \(slug.uid) \(slug.licenseURL)"
            )
        }
    }

    func testEveryEntryHasAuthor() {
        for slug in SampleAssets.all {
            XCTAssertFalse(slug.author.isEmpty,
                           "Missing author on \(slug.uid)")
        }
    }

    func testEveryEntryHasFallback() {
        for slug in SampleAssets.all {
            XCTAssertFalse(slug.fallbackBundledPath.isEmpty,
                           "Missing fallback on \(slug.uid)")
        }
    }

    func testScaleToUnitsWithinRealWorldBounds() {
        for slug in SampleAssets.all {
            XCTAssertGreaterThanOrEqual(slug.scaleToUnits, 0.05,
                                        "scaleToUnits too small on \(slug.uid)")
            XCTAssertLessThanOrEqual(slug.scaleToUnits, 5.0,
                                     "scaleToUnits too large on \(slug.uid)")
        }
    }

    func testNoDuplicateUids() {
        let grouped = Dictionary(grouping: SampleAssets.all, by: { $0.uid })
        let dupes = grouped.filter { $0.value.count > 1 }.keys.sorted()
        XCTAssertTrue(dupes.isEmpty, "Duplicate uids in SampleAssets: \(dupes)")
    }

    func testValidateSucceedsOnShippedRegistry() {
        // Calling validate() catches missing-license / uid-format regressions
        // beyond the per-test cases above.
        SampleAssets.validate()
    }

    func testByUIDMatchesAll() {
        for slug in SampleAssets.all {
            XCTAssertEqual(SampleAssets.byUID[slug.uid], slug,
                           "byUID out of sync with all on \(slug.uid)")
        }
    }

    func testByCategoryGroupsEveryEntry() {
        let total = SampleAssets.byCategory.values.reduce(0) { $0 + $1.count }
        XCTAssertEqual(total, SampleAssets.all.count,
                       "byCategory must group every entry exactly once")
    }

    func testEveryStage2CategoryIsRepresented() {
        let expected: Set<String> = [
            "solar", "gallery", "animation",
            "ar_placement", "physics", "materials",
        ]
        let actual = Set(SampleAssets.byCategory.keys)
        let missing = expected.subtracting(actual)
        XCTAssertTrue(missing.isEmpty,
                      "Missing Stage 2 categories: \(missing)")
    }

    // ─── Cross-platform parity (Android registry must agree) ───────────────

    /// The Android `SampleAssets.kt` declares the same uids in the same
    /// categories — fail if a divergence sneaks in. The iOS test reads its
    /// own copy of the registry; the Android equivalent is enforced by
    /// `SampleAssetsTest.kt`. This XCTAssert just locks the count + the
    /// category set so a missing uid would flip both numbers in lock-step.
    func testCrossPlatformParityCountsAreInRange() {
        // The Stage 1 registry ships ~20 curated entries. Allow growth ±10
        // before failing — anything bigger should ride its own review.
        XCTAssertGreaterThanOrEqual(SampleAssets.all.count, 10)
        XCTAssertLessThanOrEqual(SampleAssets.all.count, 40)
    }
}

/// Redirects only the caches directory into a scratch root, so a resolver
/// round trip stages into a throwaway location instead of the app's real
/// cache. Everything else keeps `FileManager`'s stock behaviour.
private final class ScratchCachesFileManager: FileManager {
    private let root: URL

    init(root: URL) {
        self.root = root
        super.init()
    }

    override func url(
        for directory: FileManager.SearchPathDirectory,
        in domain: FileManager.SearchPathDomainMask,
        appropriateFor url: URL?,
        create shouldCreate: Bool
    ) throws -> URL {
        guard directory == .cachesDirectory else {
            return try super.url(for: directory, in: domain,
                                 appropriateFor: url, create: shouldCreate)
        }
        try createDirectory(at: root, withIntermediateDirectories: true)
        return root
    }
}

final class SketchfabAssetResolverTests: XCTestCase {

    // ─── Unknown uid ───────────────────────────────────────────────────────

    func testResolveThrowsUnknownForRogueSlug() async {
        let rogue = SketchfabSlug(
            uid: "0000000000000000000000000000beef",
            displayName: "Rogue",
            author: "test",
            licenseURL: URL(string: "https://creativecommons.org/licenses/by/4.0/")!,
            fallbackBundledPath: "Models/missing.usdz",
            scaleToUnits: 1.0,
            hasBakedAnimation: false,
            category: "gallery"
        )
        let resolver = SketchfabAssetResolver()
        do {
            _ = try await resolver.resolve(rogue)
            XCTFail("expected Unknown error for slug outside SampleAssets")
        } catch SketchfabAssetResolver.Error.unknown(let uid) {
            XCTAssertEqual(uid, rogue.uid)
        } catch {
            XCTFail("expected .unknown, got \(error)")
        }
    }

    // ─── Staged fallback freshness (#2928) ─────────────────────────────────

    /// The staged fallback copy is keyed on `uid`, so before #2928 an app
    /// update that shipped a corrected asset was ignored forever by every
    /// existing install: the target path already existed and was returned
    /// as-is. `stagedCopy(at:matches:)` is what makes a changed bundled asset
    /// re-stage.
    func testStagedCopyDetectsAChangedBundledAsset() async throws {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("staged-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }

        let staged = dir.appendingPathComponent("staged.usdz")
        let sameSize = dir.appendingPathComponent("same.usdz")
        let shrunk = dir.appendingPathComponent("shrunk.usdz")
        let absent = dir.appendingPathComponent("absent.usdz")
        try Data(repeating: 0xAB, count: 4096).write(to: staged)
        try Data(repeating: 0xCD, count: 4096).write(to: sameSize)
        try Data(repeating: 0xEF, count: 2048).write(to: shrunk)

        let resolver = SketchfabAssetResolver()

        // The real #2928 shape: the bundle's asset got smaller -> re-stage.
        let matchesShrunk = await resolver.stagedCopy(at: staged, matches: shrunk)
        XCTAssertFalse(matchesShrunk, "a changed bundled asset must re-stage")

        // Unchanged asset -> keep the staged copy (no needless 14 MB re-copy).
        let matchesSame = await resolver.stagedCopy(at: staged, matches: sameSize)
        XCTAssertTrue(matchesSame, "an unchanged asset must not re-stage")

        // Unstattable file -> treat as stale so the re-staging path runs.
        let matchesAbsent = await resolver.stagedCopy(at: absent, matches: sameSize)
        XCTAssertFalse(matchesAbsent, "a missing staged copy must not count as a match")
    }

    /// The comparator test above proves `stagedCopy(at:matches:)` compares
    /// sizes — it does not prove `fallbackBundle(for:)` *consults* it. Hoisting
    /// the early return back above the bundle lookup fully reinstates #2928 and
    /// still leaves that test green, so this one drives the real round trip:
    /// stage a fallback, ship a different asset in the "next app version", and
    /// assert the caller receives the NEW bytes. That is the user-visible
    /// property — an App Store update carrying a corrected asset must actually
    /// reach an install that already staged the old one.
    func testFallbackBundleReStagesAfterTheBundledAssetChanges() async throws {
        let scratch = FileManager.default.temporaryDirectory
            .appendingPathComponent("roundtrip-\(UUID().uuidString)", isDirectory: true)
        let bundleURL = scratch.appendingPathComponent("Fake.bundle", isDirectory: true)
        let modelsDir = bundleURL.appendingPathComponent("Models", isDirectory: true)
        try FileManager.default.createDirectory(at: modelsDir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: scratch) }

        let bundled = modelsDir.appendingPathComponent("roundtrip_probe.usdz")
        let shipped = Data(repeating: 0xA1, count: 4096)
        let corrected = Data(repeating: 0xB2, count: 2048)
        try shipped.write(to: bundled)

        let resolver = SketchfabAssetResolver(
            bundle: try XCTUnwrap(Bundle(url: bundleURL), "scratch bundle unreadable"),
            fileManager: ScratchCachesFileManager(
                root: scratch.appendingPathComponent("caches", isDirectory: true)
            )
        )
        let slug = SketchfabSlug(
            uid: "00000000000000000000000000c0ffee",
            displayName: "Round Trip",
            author: "test",
            licenseURL: URL(string: "https://creativecommons.org/licenses/by/4.0/")!,
            fallbackBundledPath: "Models/roundtrip_probe.usdz",
            scaleToUnits: 1.0,
            hasBakedAnimation: false,
            category: "gallery"
        )

        let first = try await resolver.fallbackBundle(for: slug)
        XCTAssertEqual(try Data(contentsOf: first), shipped,
                       "first resolve must stage the bundled asset verbatim")

        // The next app version ships a corrected asset under the same name.
        try corrected.write(to: bundled)

        let second = try await resolver.fallbackBundle(for: slug)
        XCTAssertEqual(second.path, first.path,
                       "staging path is keyed on uid and must not move")
        XCTAssertEqual(try Data(contentsOf: second), corrected,
                       "#2928: the staged copy survives an app update, so a resolve "
                       + "after the bundled asset changed must re-stage it — returning "
                       + "the old bytes makes an asset fix inert on every existing install")
    }

    /// Degradation shape when the bundled resource has gone (#2943 §4.1).
    ///
    /// The freshness check moved the bundle lookup ahead of the staged-copy
    /// early return, so an install with a valid staged copy whose bundled
    /// asset was renamed or removed went from *renders the old model* to
    /// *throws*, across every `fallbackBundle` call site. Serving the staged
    /// copy as a last resort restores the previous shape.
    func testFallbackBundleServesTheStagedCopyWhenTheBundledAssetIsGone() async throws {
        let scratch = FileManager.default.temporaryDirectory
            .appendingPathComponent("vanished-\(UUID().uuidString)", isDirectory: true)
        let bundleURL = scratch.appendingPathComponent("Fake.bundle", isDirectory: true)
        let modelsDir = bundleURL.appendingPathComponent("Models", isDirectory: true)
        try FileManager.default.createDirectory(at: modelsDir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: scratch) }

        let bundled = modelsDir.appendingPathComponent("vanishing_probe.usdz")
        // A USDZ is a ZIP archive: the last-resort path runs the staged copy
        // through `boundsAreSane`, which rejects anything without GLB or ZIP
        // magic, so a blob of filler bytes would (correctly) not be served.
        var shipped = Data([0x50, 0x4B, 0x03, 0x04])
        shipped.append(Data(repeating: 0xC3, count: 4092))
        try shipped.write(to: bundled)

        let resolver = SketchfabAssetResolver(
            bundle: try XCTUnwrap(Bundle(url: bundleURL), "scratch bundle unreadable"),
            fileManager: ScratchCachesFileManager(
                root: scratch.appendingPathComponent("caches", isDirectory: true)
            )
        )
        let slug = SketchfabSlug(
            uid: "00000000000000000000000000decade",
            displayName: "Vanishing",
            author: "test",
            licenseURL: URL(string: "https://creativecommons.org/licenses/by/4.0/")!,
            fallbackBundledPath: "Models/vanishing_probe.usdz",
            scaleToUnits: 1.0,
            hasBakedAnimation: false,
            category: "gallery"
        )

        let staged = try await resolver.fallbackBundle(for: slug)
        XCTAssertEqual(try Data(contentsOf: staged), shipped)

        // The asset is renamed/removed while SampleAssets still points at it.
        try FileManager.default.removeItem(at: bundled)

        let served = try await resolver.fallbackBundle(for: slug)
        XCTAssertEqual(try Data(contentsOf: served), shipped,
                       "a vanished bundled resource must degrade to the staged copy "
                       + "rather than throwing at every call site")

        // Degrading is not "serve whatever is on disk": a staged copy that is
        // no longer a valid asset must still throw, matching Android, which
        // gates the same path on a complete GLB.
        try Data([0xFF, 0xFF]).write(to: staged)
        do {
            _ = try await resolver.fallbackBundle(for: slug)
            XCTFail("a truncated staged copy must not be served as a fallback")
        } catch let error as SketchfabAssetResolver.Error {
            XCTAssertEqual(error, .fallbackUnavailable(uid: slug.uid,
                                                       bundledPath: slug.fallbackBundledPath))
        }
    }

    // ─── boundsAreSane heuristic ───────────────────────────────────────────

    func testBoundsAreSaneRejectsEmptyFile() async throws {
        let tmp = FileManager.default.temporaryDirectory
            .appendingPathComponent("empty-\(UUID().uuidString).bin")
        FileManager.default.createFile(atPath: tmp.path, contents: Data(), attributes: nil)
        defer { try? FileManager.default.removeItem(at: tmp) }
        let resolver = SketchfabAssetResolver()
        let slug = SampleAssets.all.first!
        let ok = await resolver.boundsAreSane(at: tmp, slug: slug)
        XCTAssertFalse(ok, "0-byte file should fail bounds sanity")
    }

    func testBoundsAreSaneRejectsRandomJunk() async throws {
        let tmp = FileManager.default.temporaryDirectory
            .appendingPathComponent("junk-\(UUID().uuidString).bin")
        try Data(repeating: 0x42, count: 256).write(to: tmp)
        defer { try? FileManager.default.removeItem(at: tmp) }
        let resolver = SketchfabAssetResolver()
        let slug = SampleAssets.all.first!
        let ok = await resolver.boundsAreSane(at: tmp, slug: slug)
        XCTAssertFalse(ok, "junk bytes should fail magic check")
    }

    func testBoundsAreSaneAcceptsGLBHeader() async throws {
        let tmp = FileManager.default.temporaryDirectory
            .appendingPathComponent("valid-\(UUID().uuidString).glb")
        // Minimal binary glTF header — 'glTF' + version + length placeholder.
        let header: [UInt8] = [
            0x67, 0x6C, 0x54, 0x46,
            0x02, 0x00, 0x00, 0x00,
            0x40, 0x00, 0x00, 0x00,
        ]
        var body = Data(header)
        body.append(Data(repeating: 0x00, count: 64 - body.count))
        try body.write(to: tmp)
        defer { try? FileManager.default.removeItem(at: tmp) }
        let resolver = SketchfabAssetResolver()
        let slug = SampleAssets.all.first!
        let ok = await resolver.boundsAreSane(at: tmp, slug: slug)
        XCTAssertTrue(ok, "valid GLB header should pass sanity")
    }

    func testBoundsAreSaneAcceptsUSDZHeader() async throws {
        let tmp = FileManager.default.temporaryDirectory
            .appendingPathComponent("valid-\(UUID().uuidString).usdz")
        // USDZ is a plain ZIP — first 4 bytes "PK\x03\x04".
        let header: [UInt8] = [0x50, 0x4B, 0x03, 0x04]
        var body = Data(header)
        body.append(Data(repeating: 0x00, count: 64 - body.count))
        try body.write(to: tmp)
        defer { try? FileManager.default.removeItem(at: tmp) }
        let resolver = SketchfabAssetResolver()
        let slug = SampleAssets.all.first!
        let ok = await resolver.boundsAreSane(at: tmp, slug: slug)
        XCTAssertTrue(ok, "valid USDZ ZIP header should pass sanity")
    }

    // ─── prefetchAll unknown category ──────────────────────────────────────

    func testPrefetchAllReturnsZeroForUnknownCategory() async {
        let resolver = SketchfabAssetResolver()
        let count = await resolver.prefetchAll(category: "not-a-real-category")
        XCTAssertEqual(count, 0)
    }
}
#endif

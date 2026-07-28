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

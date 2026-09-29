// HDPackTests.swift
//
// The HD pack's shared contract with Android (`assets/hd-pack/*.json`): the
// bundled manifest decodes, every file is content-addressed, the install step
// refuses a file whose hash does not match, and the pill copy stays pinned.

#if DEBUG

import XCTest
@testable import SceneViewDemo

final class HDPackTests: XCTestCase {

    func testBundledManifestIsContentAddressed() {
        let manifest = HDPackManifest.loadBundled()
        XCTAssertEqual(manifest.version, 1)
        XCTAssertFalse(manifest.assets.isEmpty, "ios.json is not in the bundle or does not decode.")
        for asset in manifest.assets {
            XCTAssertEqual(asset.sha256.count, 64)
            XCTAssertTrue(asset.file.hasPrefix(asset.sha256 + "."), "\(asset.id): file must be <sha256>.<ext>")
            XCTAssertGreaterThan(asset.bytes, 0)
            XCTAssertFalse(asset.license.isEmpty)
            XCTAssertFalse(asset.author.isEmpty)
            XCTAssertEqual(asset.remoteURL.absoluteString,
                           "https://github.com/sceneview/sceneview/releases/download/hd-pack-v1/\(asset.file)")
        }
        XCTAssertNotNil(manifest.asset(id: "flight-helmet"))
    }

    /// HD content v1: the pack lists the Flight Helmet then Museum & Space, in
    /// Android's order, every id and hash once.
    func testBundledManifestListsHDContentV1() {
        let manifest = HDPackManifest.loadBundled()
        XCTAssertEqual(manifest.assets.map(\.id),
                       ["flight-helmet", "apollo11-exterior", "apollo11-interior", "woolly-mammoth", "perseverance"])
        XCTAssertEqual(Set(manifest.assets.map(\.sha256)).count, manifest.assets.count, "A file is listed twice.")
        XCTAssertTrue(manifest.assets.allSatisfy { $0.file.hasSuffix(".usdz") }, "RealityKit loads USDZ only.")
        XCTAssertEqual(manifest.asset(id: "apollo11-interior")?.bytes, 38_346_109)
        XCTAssertEqual(manifest.asset(id: "perseverance")?.author, "NASA/JPL-Caltech")
        XCTAssertEqual(manifest.asset(id: "woolly-mammoth")?.license, "CC0-1.0")
        // The Flight Helmet stays normalised to the viewer's 0.6 m (#4147).
        XCTAssertNil(manifest.asset(id: "flight-helmet")?.scale)
    }

    /// The decoder takes the shared schema as is: extra keys (Android's
    /// notes, a future field) are ignored, a missing required key fails the
    /// whole manifest instead of listing a half-described file.
    func testManifestDecodingToleratesExtraKeysAndRejectsMissingOnes() throws {
        let entry = """
        {"id":"x","title":"X","file":"\(String(repeating: "a", count: 64)).usdz","sha256":"\(String(repeating: "a", count: 64))",
         "bytes":1200000,"license":"CC0-1.0","author":"A","source":"https://example.org","scale":0.01,"poster":"x.webp"}
        """
        let ok = try JSONDecoder().decode(HDPackManifest.self,
                                          from: Data(#"{"version":1,"notes":"n","assets":[\#(entry)]}"#.utf8))
        XCTAssertEqual(ok.assets.first?.id, "x")
        XCTAssertEqual(ok.assets.first?.scale, 0.01)
        XCTAssertEqual(ok.totalBytes, 1_200_000)
        XCTAssertEqual(HDPackFormat.size(ok.totalBytes), "1\u{00A0}MB")
        let noHash = entry.replacingOccurrences(of: #""sha256":"\#(String(repeating: "a", count: 64))","#, with: "")
        XCTAssertThrowsError(try JSONDecoder().decode(HDPackManifest.self,
                                                      from: Data(#"{"version":1,"assets":[\#(noHash)]}"#.utf8)))
    }

    @MainActor
    func testMuseumPillCopy() {
        let manifest = HDPackManifest.loadBundled()
        let expected: [(String, String)] = [
            ("apollo11-exterior", "Apollo 11 Command Module · download 14\u{00A0}MB"),
            ("apollo11-interior", "Apollo 11 Interior · download 38\u{00A0}MB"),
            ("woolly-mammoth", "Woolly Mammoth · download 7\u{00A0}MB"),
            ("perseverance", "Perseverance Rover · download 18\u{00A0}MB"),
        ]
        for (id, label) in expected {
            guard let asset = manifest.asset(id: id) else { return XCTFail("\(id) missing from ios.json") }
            XCTAssertEqual(HDPackPill.label(title: asset.title, state: .missing, bytes: asset.bytes), label)
        }
    }

    func testInstallRejectsAChecksumMismatch() throws {
        let temp = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try Data("not the helmet".utf8).write(to: temp)
        let file = String(repeating: "0", count: 64) + ".usdz"
        XCTAssertThrowsError(try HDPackFiles.install(downloaded: temp, as: file))
        XCTAssertFalse(FileManager.default.fileExists(atPath: HDPackFiles.url(forFile: file).path))
    }

    func testInstallAcceptsAMatchingFileAtomically() throws {
        let payload = Data("hd pack payload".utf8)
        let temp = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try payload.write(to: temp)
        let sha = try HDPackFiles.sha256(of: temp)
        let file = "\(sha).bin"
        defer { try? FileManager.default.removeItem(at: HDPackFiles.url(forFile: file)) }
        try HDPackFiles.install(downloaded: temp, as: file)
        let installed = HDPackFiles.url(forFile: file)
        XCTAssertEqual(try Data(contentsOf: installed), payload)
        let excluded = try installed.resourceValues(forKeys: [.isExcludedFromBackupKey]).isExcludedFromBackup
        XCTAssertEqual(excluded, true)
    }

    @MainActor
    func testPillCopy() {
        // The pill names what the download gets you, from the manifest title.
        XCTAssertEqual(HDPackManifest.loadBundled().asset(id: "flight-helmet")?.title, "Flight Helmet")
        let t = "Flight Helmet"
        let b: Int64 = 51_704_895
        XCTAssertEqual(HDPackPill.label(title: t, state: .downloading(0.349), bytes: b), "Flight Helmet · downloading 34 %")
        XCTAssertEqual(HDPackPill.label(title: t, state: .waitingForWiFi, bytes: b), "Flight Helmet · waiting for Wi-Fi")
        XCTAssertEqual(HDPackPill.label(title: t, state: .waitingForNetwork, bytes: b), "Flight Helmet · waiting for a network")
        XCTAssertEqual(HDPackPill.label(title: t, state: .missing, bytes: b), "Flight Helmet · download 52\u{00A0}MB")
        XCTAssertEqual(HDPackPill.label(title: t, state: .ready, loading: true, bytes: b), "Flight Helmet · loading")
        XCTAssertEqual(HDPackPill.label(title: t, state: .failed, bytes: b), "Flight Helmet · download failed")
        XCTAssertNil(HDPackPill.label(title: t, state: .ready, bytes: b))
    }

    @MainActor
    func testAboutRowCopy() {
        XCTAssertEqual(HDPackSettingsRow.status(for: .ready, constrained: false), "Downloaded")
        XCTAssertEqual(HDPackSettingsRow.status(for: .downloading(0.349), constrained: false), "Downloading 34 %")
        XCTAssertEqual(HDPackSettingsRow.status(for: .waitingForWiFi, constrained: false), "Waiting for Wi-Fi")
        // Low Data Mode is not cellular: it pauses the prefetch, whatever the link.
        XCTAssertEqual(HDPackSettingsRow.status(for: .waitingForWiFi, constrained: true), "Paused · Low Data Mode")
        XCTAssertEqual(HDPackSettingsRow.status(for: .waitingForNetwork, constrained: true), "Waiting for a network")
        XCTAssertEqual(HDPackSettingsRow.status(for: .failed, constrained: false), "Download failed")
        XCTAssertNil(HDPackSettingsRow.status(for: .missing, constrained: false))
    }

    @MainActor
    func testDialogMentionsMobileDataOnlyOnAnExpensiveNetwork() {
        let wifi = HDPackDownloadDialog.message(bytes: 51_704_895, expensive: false)
        XCTAssertEqual(wifi, "Full-resolution models, 52\u{00A0}MB. They stay on this device until you remove them in About.")
        XCTAssertEqual(HDPackDownloadDialog.message(bytes: 51_704_895, expensive: true), wifi + " This uses mobile data.")
    }

    /// Per-model downloads: only the Flight Helmet (52 MB) is prefetched on
    /// its own; a Museum scan comes only from a tap on its pill.
    @MainActor
    func testOnlyTheFlightHelmetIsPrefetched() {
        let manifest = HDPackManifest.loadBundled()
        XCTAssertEqual(HDPackStore.autoPrefetchIDs, ["flight-helmet"])
        let auto = manifest.assets.filter { HDPackStore.autoPrefetchIDs.contains($0.id) }
        XCTAssertEqual(HDPackFormat.size(auto.reduce(0) { $0 + $1.bytes }), "52\u{00A0}MB")
    }

    /// The About row speaks for the prefetched helmet plus whatever the user
    /// asked for; a Museum scan nobody tapped does not keep it "missing".
    @MainActor
    func testPackAssetsFollowWhatTheUserAskedFor() {
        let manifest = HDPackManifest.loadBundled()
        var states = Dictionary(uniqueKeysWithValues: manifest.assets.map { ($0.id, HDAssetState.missing) })
        XCTAssertEqual(HDPackStore.packAssets(of: manifest, states: states).map(\.id), ["flight-helmet"])
        states["flight-helmet"] = .ready
        states["woolly-mammoth"] = .downloading(0.4)
        XCTAssertEqual(HDPackStore.packAssets(of: manifest, states: states).map(\.id), ["flight-helmet", "woolly-mammoth"])
    }

    /// A pill's dialog names its model and states that one file's size.
    @MainActor
    func testPerModelDialogStatesItsOwnSize() throws {
        let interior = try XCTUnwrap(HDPackManifest.loadBundled().asset(id: "apollo11-interior"))
        XCTAssertEqual(HDPackDownloadDialog.title(asset: interior), "Download Apollo 11 Interior?")
        let wifi = HDPackDownloadDialog.message(asset: interior, expensive: false)
        XCTAssertEqual(wifi, "Full-resolution model, 38\u{00A0}MB. It stays on this device until you remove HD scenes in About.")
        XCTAssertEqual(HDPackDownloadDialog.message(asset: interior, expensive: true), wifi + " This uses mobile data.")
    }

    /// "waiting for Wi-Fi" is a tap target only where nothing else can go on
    /// stage (an HD-only model); a helmet waiting for Wi-Fi keeps its stand-in.
    @MainActor
    func testWaitingForWiFiIsTappableOnlyForHDOnlyModels() {
        XCTAssertTrue(HDPackPill.isTappable(.waitingForWiFi, hdOnly: true))
        XCTAssertFalse(HDPackPill.isTappable(.waitingForWiFi, hdOnly: false))
        XCTAssertTrue(HDPackPill.isTappable(.missing, hdOnly: false))
        XCTAssertTrue(HDPackPill.isTappable(.failed, hdOnly: true))
        XCTAssertFalse(HDPackPill.isTappable(.downloading(0.5), hdOnly: true))
        XCTAssertFalse(HDPackPill.isTappable(.waitingForNetwork, hdOnly: true))
        XCTAssertFalse(HDPackPill.isTappable(.missing, loading: true, hdOnly: true))
    }

    /// The last model picked wins the stage, whichever load finishes first.
    func testOnlyTheLatestStageRequestInstalls() {
        var requests = StageRequests()
        let interior = requests.begin()
        let mammoth = requests.begin()
        XCTAssertFalse(requests.isCurrent(interior), "The slow interior load must not land over the mammoth.")
        XCTAssertTrue(requests.isCurrent(mammoth))
    }
}

#endif

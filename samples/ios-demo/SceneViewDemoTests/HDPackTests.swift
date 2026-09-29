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
        XCTAssertEqual(HDPackPill.label(for: .downloading(0.349), bytes: 51_704_895), "HD · downloading 34 %")
        XCTAssertEqual(HDPackPill.label(for: .waitingForWiFi, bytes: 51_704_895), "HD · waiting for Wi-Fi")
        XCTAssertEqual(HDPackPill.label(for: .waitingForNetwork, bytes: 51_704_895), "HD · waiting for a network")
        XCTAssertEqual(HDPackPill.label(for: .missing, bytes: 51_704_895), "HD · download 52 MB")
        XCTAssertEqual(HDPackPill.label(for: .ready, loading: true, bytes: 51_704_895), "HD · loading")
        XCTAssertEqual(HDPackPill.label(for: .failed, bytes: 51_704_895), "HD · download failed")
        XCTAssertNil(HDPackPill.label(for: .ready, bytes: 51_704_895))
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
        XCTAssertEqual(wifi, "Full-resolution models, 52 MB. They stay on this device until you remove them in About.")
        XCTAssertEqual(HDPackDownloadDialog.message(bytes: 51_704_895, expensive: true), wifi + " This uses mobile data.")
    }
}

#endif

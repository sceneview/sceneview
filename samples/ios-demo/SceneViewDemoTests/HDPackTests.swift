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
        XCTAssertEqual(HDPackPill.label(for: .downloading(0.349)), "HD · downloading 34 %")
        XCTAssertEqual(HDPackPill.label(for: .waiting), "HD · waiting for Wi-Fi")
        XCTAssertNil(HDPackPill.label(for: .ready))
        XCTAssertEqual(HDPackSettingsRow.status(for: .ready, metered: false), "Downloaded")
    }
}

#endif

// RerunSessionStoreTests.swift
//
// "Your sessions" (`RerunSessionStore.swift`): a capture saved on the phone lists, reopens
// byte for byte, survives a fresh store on the same directory (an app relaunch) and deletes;
// the `.svscan` scan file round-trips; a `.rrd` the app exported imports back as a session.

#if DEBUG

import XCTest
@testable import SceneViewDemo

final class RerunSessionStoreTests: XCTestCase {

    private var root: URL!

    override func setUpWithError() throws {
        root = FileManager.default.temporaryDirectory
            .appendingPathComponent("rerun-store-tests-\(UUID().uuidString)", isDirectory: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: root)
    }

    /// The bundled showcase as the recorder's three files — a real capture, not a stub.
    private func showcaseCapture() throws -> RerunCapturePack {
        func data(_ name: String, _ ext: String) throws -> Data {
            let url = try XCTUnwrap(
                Bundle.main.url(forResource: name, withExtension: ext, subdirectory: "showcase")
                    ?? Bundle.main.url(forResource: name, withExtension: ext),
                "\(name).\(ext) must ship in the app bundle"
            )
            return try Data(contentsOf: url)
        }
        return RerunCapturePack(
            manifest: try data("showcase-manifest", "json"),
            log: try data("showcase-session", "jsonl"),
            media: try data("showcase-media", "bin")
        )
    }

    func testSavedSessionListsWithItsFiguresAndReopensByteForByte() throws {
        let store = RerunSessionStore(root: root)
        XCTAssertTrue(store.list().isEmpty)
        let capture = try showcaseCapture()
        let saved = try store.save(capture, title: "Living room", source: .recorded)

        XCTAssertEqual(store.list(), [saved])
        XCTAssertEqual(saved.title, "Living room")
        XCTAssertEqual(saved.source, .recorded)
        XCTAssertEqual(saved.photos, 184)
        XCTAssertEqual(saved.duration, 18.3, accuracy: 0.5)
        XCTAssertGreaterThan(saved.pathMetres, 1)
        XCTAssertGreaterThan(saved.points, 1_000)
        XCTAssertGreaterThan(saved.planes, 0)
        XCTAssertEqual(store.capture(for: saved.id), capture)
        XCTAssertNotNil(store.thumbnailURL(for: saved.id), "The first photo becomes the card's thumbnail")
    }

    func testSessionsPersistAcrossAFreshStoreNewestFirst() throws {
        let capture = try showcaseCapture()
        let first = try RerunSessionStore(root: root).save(capture, title: "Old", source: .recorded,
                                                           now: Date(timeIntervalSince1970: 1_000))
        let second = try RerunSessionStore(root: root).save(capture, title: "New", source: .scan,
                                                            now: Date(timeIntervalSince1970: 2_000))
        // A relaunch builds a new store on the same directory.
        let relaunched = RerunSessionStore(root: root)
        XCTAssertEqual(relaunched.list().map(\.id), [second.id, first.id])
    }

    func testDeleteRemovesTheSessionAndItsFiles() throws {
        let store = RerunSessionStore(root: root)
        let saved = try store.save(try showcaseCapture(), title: "Gone", source: .recorded)
        try store.delete(saved.id)
        XCTAssertTrue(store.list().isEmpty)
        XCTAssertNil(store.capture(for: saved.id))
        XCTAssertFalse(FileManager.default.fileExists(atPath: store.directory(for: saved.id).path))
        XCTAssertNoThrow(try store.delete(saved.id), "Deleting twice is harmless")
    }

    func testAHalfWrittenSessionIsSkipped() throws {
        let store = RerunSessionStore(root: root)
        let orphan = store.directory(for: UUID())
        try FileManager.default.createDirectory(at: orphan, withIntermediateDirectories: true)
        try Data("{".utf8).write(to: orphan.appendingPathComponent(RerunSessionStore.infoFileName))
        XCTAssertTrue(store.list().isEmpty)
    }

    func testAnEmptyCaptureIsRefused() throws {
        let store = RerunSessionStore(root: root)
        let capture = try showcaseCapture()
        let empty = RerunCapturePack(manifest: capture.manifest, log: Data(), media: Data())
        XCTAssertThrowsError(try store.save(empty, title: "Nothing", source: .recorded)) { error in
            XCTAssertEqual(error as? RerunSessionStore.Failure, .empty)
        }
        XCTAssertTrue(store.list().isEmpty)
    }

    // MARK: Scan file

    func testScanFileRoundTripsAndImportsAsASession() throws {
        let store = RerunSessionStore(root: root)
        let capture = try showcaseCapture()
        let saved = try store.save(capture, title: "Kitchen: 2/3", source: .recorded)

        let url = try store.scanFile(for: saved)
        defer { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }
        XCTAssertEqual(url.lastPathComponent, "Kitchen- 2-3.svscan")
        XCTAssertEqual(try RerunScanFile.capture(from: Data(contentsOf: url)), capture)

        let imported = try store.importFile(at: url)
        XCTAssertEqual(imported.source, .scan)
        XCTAssertEqual(imported.title, "Kitchen- 2-3")
        XCTAssertEqual(imported.photos, saved.photos)
        XCTAssertEqual(imported.points, saved.points)
        XCTAssertEqual(store.capture(for: imported.id), capture)
        XCTAssertEqual(store.list().count, 2)
    }

    func testScanFileIsAPlainZipOfTheRecorderFiles() throws {
        let capture = try showcaseCapture()
        let files = try RerunUSDZWriter.Archive.read(RerunScanFile.data(for: capture), model: "scan")
        XCTAssertEqual(files.map(\.path), [
            RerunCapturePack.manifestFileName, RerunCapturePack.logFileName, RerunCapturePack.mediaFileName,
        ])
    }

    func testImportRefusesWhatItCannotRead() throws {
        let store = RerunSessionStore(root: root)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let text = root.appendingPathComponent("notes.txt")
        try Data("hello".utf8).write(to: text)
        XCTAssertThrowsError(try store.importFile(at: text)) { error in
            XCTAssertEqual(error as? RerunSessionStore.Failure, .unsupportedFile("notes.txt"))
        }
        let fake = root.appendingPathComponent("fake.svscan")
        try Data("not a zip".utf8).write(to: fake)
        XCTAssertThrowsError(try store.importFile(at: fake)) { error in
            XCTAssertEqual(error as? RerunSessionStore.Failure, .unreadable("fake.svscan"))
        }
        let fakeRRD = root.appendingPathComponent("fake.rrd")
        try Data("RRF2 garbage".utf8).write(to: fakeRRD)
        XCTAssertThrowsError(try store.importFile(at: fakeRRD))
        XCTAssertTrue(store.list().isEmpty)
    }

    // MARK: .rrd

    func testExportedRRDImportsBackAsASession() throws {
        let store = RerunSessionStore(root: root)
        let pack = try RerunPack.loadShowcase()
        let directory = RerunExportAdapter.freshDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let url = try RerunExportAdapter.write(.rrd, scene: RerunExportAdapter.scene(for: pack), directory: directory)

        let imported = try store.importFile(at: url)
        XCTAssertEqual(imported.source, .rrd)
        XCTAssertGreaterThan(imported.points, 1_000)
        XCTAssertGreaterThan(imported.planes, 0)
        let reopened = try XCTUnwrap(store.capture(for: imported.id))
        let replay = try RerunPack.load(manifest: reopened.manifest, log: reopened.log, media: reopened.media,
                                        title: imported.title)
        XCTAssertFalse(replay.trace.isEmpty)
        XCTAssertEqual(replay.trace.poseCount, pack.trace.poseCount)
    }

    func testRecordingTitle() {
        let title = RerunSessionStore.recordingTitle(
            at: Date(timeIntervalSince1970: 1_790_000_000),
            locale: Locale(identifier: "en_US"), timeZone: TimeZone(identifier: "UTC")!
        )
        XCTAssertTrue(title.hasPrefix("Room · "), title)
        XCTAssertTrue(title.contains("2026"), title)
    }
}

#endif

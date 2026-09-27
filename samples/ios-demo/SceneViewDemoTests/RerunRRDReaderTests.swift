// RerunRRDReaderTests.swift
//
// The `.rrd` reader (`RerunRRDReader.swift`): the bundled showcase, exported to `.rrd` and
// read back, replays the same session — path, photos, map, planes, anchors, lens — and a
// file that is not a readable `.rrd` fails with a typed error instead of crashing.

#if DEBUG

import XCTest
import simd
@testable import SceneViewDemo

final class RerunRRDReaderTests: XCTestCase {

    private static let pack: RerunPack? = try? RerunPack.loadShowcase()

    private func showcase() throws -> RerunPack {
        try XCTUnwrap(Self.pack, "The shared rerun/showcase pack must ship in the app bundle")
    }

    /// `scene` → `.rrd` → reader → replay pack.
    private func reread(_ scene: RerunExportScene) throws -> (title: String?, pack: RerunPack) {
        let recording = try RerunRRDReader.recording(from: RerunRRDWriter.data(for: scene))
        let pack = try RerunPack.load(manifest: recording.pack.manifest, log: recording.pack.log,
                                      media: recording.pack.media, title: recording.title ?? "")
        return (recording.title, pack)
    }

    // MARK: - Round trip

    func testShowcaseRoundTripReplaysTheSameSession() throws {
        let original = try showcase()
        let exported = RerunExportAdapter.scene(for: original)
        let (title, pack) = try reread(exported)
        let replayed = RerunExportAdapter.scene(for: pack)

        XCTAssertEqual(title, "Recorded room")

        // The file stops at the last pose or photo: the seconds after them are not in it.
        let lastEvent = max(exported.cameraPath.last?.time ?? 0, exported.keyframes.map(\.time).max() ?? 0)
        XCTAssertEqual(Double(pack.trace.duration), lastEvent, accuracy: 1e-4)
        XCTAssertLessThanOrEqual(pack.trace.duration, original.trace.duration + 1e-4)

        // The path, pose for pose: the writer's per-photo rows are not taken for path poses.
        XCTAssertEqual(replayed.cameraPath.count, exported.cameraPath.count)
        for (a, b) in zip(exported.cameraPath, replayed.cameraPath) {
            XCTAssertEqual(a.time, b.time, accuracy: 1e-5)
            XCTAssertEqual(a.position, b.position)
            XCTAssertEqual(a.orientation.vector, b.orientation.vector)
        }

        // The keyframes, each with the photo the `.rrd` carried (WebP became JPEG on export).
        XCTAssertFalse(exported.keyframes.isEmpty)
        XCTAssertEqual(replayed.keyframes.count, exported.keyframes.count)
        for (a, b) in zip(exported.keyframes, replayed.keyframes) {
            XCTAssertEqual(a.time, b.time, accuracy: 1e-5)
            XCTAssertEqual(a.pose.position, b.pose.position)
            let written = try XCTUnwrap(exported.images[a.imagePath].flatMap(RerunImageCodec.photo(from:))).data
            XCTAssertEqual(replayed.images[b.imagePath], written, b.imagePath)
        }
        let distinctPhotos = Set(exported.images.values.compactMap { RerunImageCodec.photo(from: $0)?.data })
        XCTAssertEqual(pack.trace.imageCount, distinctPhotos.count)

        // The map, bit for bit.
        XCTAssertEqual(replayed.points, exported.points)
        XCTAssertEqual(replayed.pointColors, exported.pointColors)

        XCTAssertEqual(replayed.planes.map(\.id), exported.planes.map(\.id))
        for (a, b) in zip(exported.planes, replayed.planes) {
            XCTAssertEqual(b.kind, a.kind, "plane \(a.id)")
            XCTAssertEqual(b.polygon, a.polygon, "plane \(a.id)")
            XCTAssertEqual(b.texture == nil, a.texture == nil, "plane \(a.id)")
            if let ta = a.texture, let tb = b.texture {
                XCTAssertLessThan(simd_distance(ta.origin, tb.origin), 1e-3, "plane \(a.id)")
                XCTAssertLessThan(simd_distance(ta.u, tb.u), 1e-3, "plane \(a.id)")
                XCTAssertLessThan(simd_distance(ta.v, tb.v), 1e-3, "plane \(a.id)")
            }
        }
        XCTAssertEqual(pack.manifest.floorY, original.manifest.floorY)

        XCTAssertEqual(replayed.anchors.map(\.id), exported.anchors.map(\.id))
        for (a, b) in zip(exported.anchors, replayed.anchors) {
            XCTAssertEqual(b.position, a.position)
            XCTAssertEqual(b.orientation.vector, a.orientation.vector)
        }

        // The Pinhole is in photo pixels: the lens keeps its proportions, not its resolution.
        let lensA = try XCTUnwrap(exported.lens), lensB = try XCTUnwrap(replayed.lens)
        XCTAssertEqual(lensB.fx / Float(lensB.width), lensA.fx / Float(lensA.width), accuracy: 1e-4)
        XCTAssertEqual(lensB.fy / Float(lensB.height), lensA.fy / Float(lensA.height), accuracy: 1e-4)
        XCTAssertEqual(lensB.cx / Float(lensB.width), lensA.cx / Float(lensA.width), accuracy: 1e-4)
        XCTAssertEqual(lensB.cy / Float(lensB.height), lensA.cy / Float(lensA.height), accuracy: 1e-4)
    }

    /// The map is static in a `.rrd`: it is all there from the first frame.
    func testStaticMapIsShownFromTheStart() throws {
        let exported = RerunExportAdapter.scene(for: try showcase())
        let first = try reread(exported).pack.trace.frameAt(0)
        XCTAssertEqual(first.mapPointCount, exported.points.count)
        XCTAssertEqual(first.planes.count, exported.planes.count)
        XCTAssertEqual(first.anchors.count, exported.anchors.count)
    }

    /// Exporting what was read back and reading it again changes nothing.
    func testSecondRoundTripIsLossless() throws {
        let once = RerunExportAdapter.scene(for: try reread(RerunExportAdapter.scene(for: try showcase())).pack)
        let twice = RerunExportAdapter.scene(for: try reread(once).pack)
        XCTAssertEqual(twice.cameraPath, once.cameraPath)
        XCTAssertEqual(twice.points, once.points)
        XCTAssertEqual(twice.pointColors, once.pointColors)
        XCTAssertEqual(twice.keyframes.map(\.imagePath), once.keyframes.map(\.imagePath))
        XCTAssertEqual(twice.images, once.images)
        for (a, b) in zip(once.planes, twice.planes) {
            XCTAssertEqual(b.polygon, a.polygon)
            if let ta = a.texture, let tb = b.texture {
                XCTAssertEqual(RerunImageCodec.rgbPixels(from: tb.imageData, maxDimension: 512)?.rgb,
                               RerunImageCodec.rgbPixels(from: ta.imageData, maxDimension: 512)?.rgb, "plane \(a.id)")
            }
        }
    }

    // MARK: - Failures

    private func rrd() throws -> Data {
        try RerunRRDWriter.data(for: RerunExportAdapter.scene(for: showcase()))
    }

    private func assertFails(_ data: Data, with expected: RerunRRDReader.Failure,
                             file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertThrowsError(try RerunRRDReader.capturePack(from: data), file: file, line: line) { error in
            XCTAssertEqual(error as? RerunRRDReader.Failure, expected, file: file, line: line)
        }
    }

    func testWrongMagicIsNotAnRRD() throws {
        var data = try rrd()
        data[data.startIndex] = UInt8(ascii: "X")
        assertFails(data, with: .notAnRRD)
        assertFails(Data("{\"t\":0}".utf8), with: .notAnRRD)
        assertFails(Data(), with: .notAnRRD)
    }

    func testTruncatedFileFails() throws {
        let data = try rrd()
        assertFails(data.prefix(8), with: .truncated)
        assertFails(data.prefix(data.count / 2), with: .truncated)
        assertFails(data.dropLast(1), with: .truncated)
    }

    func testCompressedStreamIsRefused() throws {
        var data = try rrd()
        data[data.startIndex + 8] = 1 // The LZ4 option.
        assertFails(data, with: .compressed)
    }

    func testOtherMajorVersionIsRefused() throws {
        var data = try rrd()
        data[data.startIndex + 4] = 1
        assertFails(data, with: .unsupportedVersion(major: 1, minor: 38, patch: 1))
    }

    func testHeaderAloneHasNothingToReplay() throws {
        assertFails(try rrd().prefix(12), with: .nothingToReplay)
    }

    /// Random damage anywhere must end in an error or a pack, never a trap.
    func testDamagedFilesNeverCrash() throws {
        var scene = RerunExportAdapter.scene(for: try showcase())
        scene.points = Array(scene.points.prefix(40))
        scene.pointColors = Array(scene.pointColors.prefix(40))
        scene.cameraPath = Array(scene.cameraPath.prefix(12))
        scene.keyframes = []
        scene.images = [:]
        scene.planes = scene.planes.map { var plane = $0; plane.texture = nil; return plane }
        let data = try RerunRRDWriter.data(for: scene)
        var generator = SplitMix64(seed: 0x5CE_1E_1E)
        for run in 0..<2_000 {
            var damaged = data
            if run % 5 == 0 {
                damaged = damaged.prefix(Int(generator.next() % UInt64(damaged.count)))
            } else {
                for _ in 0...(run % 4) {
                    let index = damaged.startIndex + Int(generator.next() % UInt64(damaged.count))
                    damaged[index] = UInt8(truncatingIfNeeded: generator.next())
                }
            }
            _ = try? RerunRRDReader.capturePack(from: damaged)
        }
    }
}

/// A seeded generator, so a failing damage pattern reproduces.
private struct SplitMix64: RandomNumberGenerator {
    var state: UInt64
    init(seed: UInt64) { state = seed }
    mutating func next() -> UInt64 {
        state &+= 0x9E37_79B9_7F4A_7C15
        var z = state
        z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
        z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
        return z ^ (z >> 31)
    }
}

#endif

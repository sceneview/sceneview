// RerunRRDReaderTests.swift
//
// The `.rrd` reader (`RerunRRDReader.swift`): the bundled showcase, exported to `.rrd` and
// read back, replays the same session — path, photos, map, planes, anchors, lens — and a
// file that is not a readable `.rrd` fails with a typed error instead of crashing.

#if DEBUG

import CoreGraphics
import ImageIO
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

    // MARK: - Android-compatible static dense cloud

    private func denseStream(_ chunks: [RerunChunk]) -> Data {
        let id = UUID(uuidString: "00000000-0000-0000-0000-000000000021")!
        var ids = RerunTuidSequence(recordingId: id)
        let store = RerunRRDWriter.StoreIdentity(applicationId: "test", recordingId: id.uuidString.lowercased())
        var data = RerunRRDWriter.streamHeader()
        for chunk in chunks {
            RerunRRDWriter.appendMessage(kind: 2, payload: RerunRRDWriter.arrowMessage(chunk, store: store, ids: &ids), to: &data)
        }
        return data
    }

    private func denseChunk(positions: [Float] = [0, 0, 0, 1, 2, 3],
                            colors: [UInt32] = [0x0A141EFF, 0xFF0000FF],
                            radii: [Float] = [0.015]) -> RerunChunk {
        RerunChunk(entityPath: "/world/dense", components: [
            .vectors("Points3D", "positions", "Position3D", [positions], size: 3),
            .floats("Points3D", "radii", "Radius", [radii]),
            .colors("Points3D", [colors]),
        ])
    }

    private func densePack(_ data: Data) throws -> RerunPack {
        let capture = try RerunRRDReader.capturePack(from: data)
        return try RerunPack.load(manifest: capture.manifest, log: capture.log, media: capture.media, title: "Dense")
    }

    func testSingleDenseChunkLoadsAPackAndPersistsAsV2() throws {
        let capture = try RerunRRDReader.capturePack(from: denseStream([denseChunk()]))
        let scan = try RerunScanFile.capture(from: RerunScanFile.data(for: capture))
        let pack = try RerunPack.load(manifest: scan.manifest, log: scan.log, media: scan.media, title: "Dense")
        XCTAssertTrue(pack.trace.isEmpty)
        let dense = try XCTUnwrap(pack.dense)
        XCTAssertEqual(dense.positions, [SIMD3(0, 0, 0), SIMD3(1, 2, 3)])
        XCTAssertEqual(dense.colors, [SIMD3(10, 20, 30), SIMD3(255, 0, 0)])
        XCTAssertEqual(dense.voxelM / 2, 0.015, accuracy: 1e-7)
        let root = try XCTUnwrap(JSONSerialization.jsonObject(with: scan.manifest) as? [String: Any])
        XCTAssertEqual(root["version"] as? Int, 2)
        XCTAssertEqual(pack.manifest.dense?.path, "dense/points.bin")
    }

    func testDenseAndPosesReplayTogether() throws {
        let camera = RerunChunk(entityPath: "/world/camera", times: [0, 1_000_000_000], components: [
            .vectors("Transform3D", "translation", "Translation3D", [[0, 0, 0], [1, 0, 0]], size: 3),
        ])
        let pack = try densePack(denseStream([denseChunk(), camera]))
        XCTAssertEqual(pack.trace.poses.count, 2)
        XCTAssertEqual(pack.trace.duration, 1, accuracy: 1e-6)
        XCTAssertEqual(pack.dense?.positions.count, 2)
    }

    func testDenseRoundTripKeepsPositionsColorsAndRadius() throws {
        let pack = try densePack(denseStream([denseChunk()]))
        let scene = RerunExportAdapter.scene(for: pack)
        let chunk = try XCTUnwrap(RerunRRDWriter.chunks(for: scene).first { $0.entityPath == "/world/dense" })
        XCTAssertNil(chunk.times, "Android exports one static row without a timeline")
        XCTAssertEqual(chunk.rowCount, 1)
        let reopened = try densePack(RerunRRDWriter.data(for: scene))
        XCTAssertEqual(reopened.dense, pack.dense)
    }

    func testMalformedDenseColumnsFailWithoutCrashing() throws {
        for chunk in [denseChunk(colors: [0xFFFFFFFF]), denseChunk(radii: [0.01, 0.02])] {
            XCTAssertThrowsError(try RerunRRDReader.recording(from: denseStream([chunk]))) { error in
                guard let failure = error as? RerunRRDReader.Failure, case .malformed = failure else {
                    return XCTFail("Expected a typed malformed error, got \(error)")
                }
            }
        }
        XCTAssertThrowsError(try RerunRRDReader.recording(from: denseStream([denseChunk(positions: [], colors: [])]))) {
            XCTAssertEqual($0 as? RerunRRDReader.Failure, .nothingToReplay)
        }
    }

    func testEmptyDenseIsSkippedWhenPosesRemain() throws {
        let camera = RerunChunk(entityPath: "/world/camera", times: [0], components: [
            .vectors("Transform3D", "translation", "Translation3D", [[0, 0, 0]], size: 3),
        ])
        let pack = try densePack(denseStream([denseChunk(positions: [], colors: []), camera]))
        XCTAssertNil(pack.dense)
        XCTAssertEqual(pack.trace.poses.count, 1)
    }

    func testDenseWithoutColorOrRadiusUsesAndroidDefaults() throws {
        let chunk = RerunChunk(entityPath: "/world/dense", components: [
            .vectors("Points3D", "positions", "Position3D", [[0, 0, 0]], size: 3),
        ])
        let dense = try XCTUnwrap(densePack(denseStream([chunk])).dense)
        XCTAssertEqual(dense.colors, [SIMD3(255, 255, 255)])
        XCTAssertEqual(dense.voxelM, 0.02)
    }

    func testDenseOnlyImportIsAcceptedBySessionStore() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let capture = try RerunRRDReader.capturePack(from: denseStream([denseChunk()]))
        let store = RerunSessionStore(root: root)
        let session = try store.save(capture, title: "Dense", source: .rrd)
        XCTAssertEqual(session.points, 2)
        let saved = try XCTUnwrap(store.capture(for: session.id))
        let pack = try RerunPack.load(manifest: saved.manifest, log: saved.log, media: saved.media, title: "Dense")
        XCTAssertEqual(pack.dense?.positions.count, 2)
    }

    func testSVPCMatchesAndroidLayoutAndRejectsTruncation() throws {
        let cloud = RerunDenseCloud(positions: [SIMD3(0, 0, 0)], colors: [SIMD3(10, 20, 30)])
        var expected = Data("SVPC".utf8)
        for value in [UInt32(1), 1, 0, 0, 0, 0, Float(0.001).bitPattern] {
            RerunArrowIPC.appendLittleEndian(value, to: &expected)
        }
        expected.append(contentsOf: [0, 0, 0, 0, 0, 0, 10, 20, 30])
        XCTAssertEqual(cloud.encoded(), expected)
        XCTAssertEqual(RerunDenseCloud.decode(expected, voxelM: 0.02), cloud)
        for count in 0..<expected.count {
            XCTAssertNil(RerunDenseCloud.decode(Data(expected.prefix(count)), voxelM: 0.02))
        }
    }

    // MARK: - Round trip

    func testShowcaseRoundTripReplaysTheSameSession() throws {
        let original = try showcase()
        let exported = RerunExportAdapter.scene(for: original)
        let (title, pack) = try reread(exported)
        let replayed = RerunExportAdapter.scene(for: pack)

        XCTAssertEqual(title, "Recorded room")

        // The file stops at the last pose, photo or sighting: later idle time is not in it.
        let lastEvent = max(exported.cameraPath.last?.time ?? 0, exported.photos.map(\.time).max() ?? 0,
                            exported.pointObservations.map(\.time).max() ?? 0)
        XCTAssertEqual(Double(pack.trace.duration), lastEvent, accuracy: 1e-4)
        XCTAssertLessThanOrEqual(pack.trace.duration, original.trace.duration + 1e-4)

        // The path, pose for pose: the writer's per-photo rows are not taken for path poses.
        XCTAssertEqual(replayed.cameraPath.count, exported.cameraPath.count)
        for (a, b) in zip(exported.cameraPath, replayed.cameraPath) {
            XCTAssertEqual(a.time, b.time, accuracy: 1e-5)
            XCTAssertEqual(a.position, b.position)
            XCTAssertEqual(a.orientation.vector, b.orientation.vector)
        }

        // Every recorded photo retains its timestamp and encoded bytes.
        XCTAssertEqual(pack.trace.imageCount, original.trace.imageCount)
        XCTAssertEqual(replayed.photos.count, exported.photos.count)
        for (a, b) in zip(exported.photos, replayed.photos) {
            XCTAssertEqual(a.time, b.time, accuracy: 1e-5)
            let written = try XCTUnwrap(exported.images[a.imagePath].flatMap(RerunImageCodec.photo(from:))).data
            XCTAssertEqual(replayed.images[b.imagePath], written, b.imagePath)
        }

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
        var exported = RerunExportAdapter.scene(for: try showcase())
        exported.pointObservations = []
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
                XCTAssertEqual(RerunImageCodec.rgbPixels(from: tb.imageData, maxDimension: 512)?.data,
                               RerunImageCodec.rgbPixels(from: ta.imageData, maxDimension: 512)?.data, "plane \(a.id)")
            }
        }
    }

    // MARK: - Round-trip regressions built in code

    /// N = 9 photos with bytes of their own, K < N keyframes, three timed point clouds — one
    /// of their points has no colour — and one textured plane.
    private func recordedPack() throws -> RerunPack {
        var media = Data()
        var entries: [[String: Any]] = []
        func store(_ bytes: Data, at path: String) {
            entries.append(["path": path, "offset": media.count, "length": bytes.count])
            media.append(bytes)
        }
        var log = ""
        for i in 0...8 {
            let time = Int64(i) * 500_000_000
            if i % 2 == 0 {
                log += "{\"t\":\(time),\"type\":\"camera_pose\",\"entity\":\"world/camera\",\"translation\":[\(i),1,0],\"quaternion\":[0,0,0,1]}\n"
            }
            if [0, 4, 8].contains(i) {
                let points = i == 0 ? "[[0,0,0]]" : i == 4 ? "[[0,0,0],[1,0,0],[3,0,0]]" : "[[1,0,0],[2,0,0]]"
                let colors = i == 0 ? "[[255,0,0]]" : i == 4 ? "[[255,0,0],[0,255,0],[-1,-1,-1]]" : "[[0,255,0],[0,0,255]]"
                log += "{\"t\":\(time),\"type\":\"point_cloud\",\"entity\":\"world/points\",\"positions\":\(points),\"colors\":\(colors)}\n"
            }
            let path = "frames/\(i).png"
            store(try framePNG(i), at: path)
            log += "{\"t\":\(time),\"type\":\"image\",\"entity\":\"world/camera/image\",\"path\":\"\(path)\"}\n"
        }
        store(try png(rgba), at: "planes/plane-7.png")
        log += "{\"t\":0,\"type\":\"plane\",\"entity\":\"world/planes/7\",\"kind\":\"horizontal_upward\",\"polygon\":[[0,0,0],[1,0,0],[1,0,1],[0,0,1]]}\n"
        let manifest: [String: Any] = [
            "frameRate": 2, "frames": 9, "media": entries,
            "textures": [["plane": 7, "path": "planes/plane-7.png", "origin": [0,0,0], "u": [1,0,0], "v": [0,0,1]]],
        ]
        return try RerunPack.load(manifest: JSONSerialization.data(withJSONObject: manifest),
                                 log: Data(log.utf8), media: media, title: "Round trip")
    }

    /// The plane photo: an opaque, a transparent, a translucent and an opaque texel. 77 does
    /// not divide 255, so un-premultiplying the translucent one is not exact.
    private let rgba = Data([10, 20, 30, 255, 0, 0, 0, 0, 100, 150, 200, 77, 240, 80, 40, 255])
    private let uncoloured = SIMD3<Float>(3, 0, 0)

    /// Photo `i`: no two photos share their bytes.
    private func framePNG(_ i: Int) throws -> Data {
        try png(Data([UInt8(10 + 20 * i), 20, 30, 255, 40, 50, 60, 255, 70, 80, 90, 255, 240, 80, 40, 255]))
    }

    /// `texels` as a 2 × 2 PNG with straight alpha.
    private func png(_ texels: Data) throws -> Data {
        let provider = try XCTUnwrap(CGDataProvider(data: texels as CFData))
        let space = try XCTUnwrap(CGColorSpace(name: CGColorSpace.sRGB))
        let image = try XCTUnwrap(CGImage(width: 2, height: 2, bitsPerComponent: 8, bitsPerPixel: 32,
                                        bytesPerRow: 8, space: space,
                                        bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.last.rawValue),
                                        provider: provider, decode: nil, shouldInterpolate: false, intent: .defaultIntent))
        let output = NSMutableData()
        let destination = try XCTUnwrap(CGImageDestinationCreateWithData(output as CFMutableData, "public.png" as CFString, 1, nil))
        CGImageDestinationAddImage(destination, image, nil)
        XCTAssertTrue(CGImageDestinationFinalize(destination))
        return output as Data
    }

    /// The exporter writes a point without a colour as white, as Android's does: that is the
    /// one thing a round trip changes in the map.
    private func exportedColors(_ frame: RerunFrame) -> [UInt32]? {
        frame.mapPointColors.map { $0.map { $0 == 0 ? 0xFFFF_FFFF : $0 } }
    }

    func testPointsSurviveARoundTripWithTheirTimestamps() throws {
        let original = try recordedPack().trace
        let exported = RerunExportAdapter.scene(for: try recordedPack())
        XCTAssertEqual(exported.pointObservations.map(\.time), [0, 2, 4])
        let reopened = try reread(exported).pack.trace
        XCTAssertEqual(reopened.observationTimes, original.observationTimes)
        for time in [Float(0), 0.5, 1.6, 2, 2.5, 3.6, 4] {
            let a = original.frameAt(time), b = reopened.frameAt(time)
            XCTAssertEqual(Array(b.mapPoints), Array(a.mapPoints), "map at \(time)")
            XCTAssertEqual(b.livePoints, a.livePoints, "live points at \(time)")
            XCTAssertEqual(b.mapPointColors.map(Array.init), exportedColors(a), "colours at \(time)")
        }
        XCTAssertLessThan(reopened.frameAt(0).mapPointCount, reopened.frameAt(4).mapPointCount)

        // The point no photo coloured arrives with its sighting, and comes back white.
        func colour(of point: SIMD3<Float>, in frame: RerunFrame) throws -> UInt32? {
            guard let index = Array(frame.mapPoints).firstIndex(of: point) else { return nil }
            return Array(try XCTUnwrap(frame.mapPointColors))[index]
        }
        XCTAssertNil(try colour(of: uncoloured, in: reopened.frameAt(1.9)))
        XCTAssertEqual(try colour(of: uncoloured, in: original.frameAt(2)), 0)
        XCTAssertEqual(try colour(of: uncoloured, in: reopened.frameAt(2)), 0xFFFF_FFFF)
    }

    /// A map point no sighting names has no time in the file: it is there from the first
    /// frame, with its colour, and the sighted points still arrive at their own times.
    func testMapPointsNeverSightedAreShownFromTheStart() throws {
        let original = try recordedPack().trace
        var exported = RerunExportAdapter.scene(for: try recordedPack())
        let unseen = SIMD3<Float>(9, 0, 0)
        exported.points.append(unseen)
        exported.pointColors.append(SIMD3(1, 2, 3))
        let reopened = try reread(exported).pack.trace
        for time in [Float(0), 1.6, 2, 3.6, 4] {
            let a = original.frameAt(time), b = reopened.frameAt(time)
            let points = Array(b.mapPoints), colors = Array(try XCTUnwrap(b.mapPointColors))
            let index = try XCTUnwrap(points.firstIndex(of: unseen), "unseen point at \(time)")
            XCTAssertEqual(colors[index], 0xFF01_0203)
            XCTAssertEqual(points.filter { $0 != unseen }, Array(a.mapPoints), "sighted points at \(time)")
            XCTAssertEqual(b.livePoints, a.livePoints, "live points at \(time)")
        }
    }

    /// A sighting of a point the static map does not hold (a file from another writer) has no
    /// map colour to take. The reader marks it `[-1,-1,-1]`, which the log reads as "no
    /// colour", as on Android, instead of clamping it to black.
    func testSightedPointsOutsideTheMapStayUncoloured() throws {
        var scene = RerunExportAdapter.scene(for: try recordedPack())
        scene.pointObservations = []
        let stray = SIMD3<Float>(5, 0, 0)
        let id = UUID()
        var ids = RerunTuidSequence(recordingId: id)
        let store = RerunRRDWriter.StoreIdentity(applicationId: "test", recordingId: id.uuidString.lowercased())
        var data = RerunRRDWriter.streamHeader()
        RerunRRDWriter.appendMessage(kind: 1, payload: RerunRRDWriter.setStoreInfo(store, rowId: ids.next()), to: &data)
        let live = RerunChunk(entityPath: "/world/points/live", times: [1_000_000_000], components: [
            .vectors("Points3D", "positions", "Position3D", [[0, 0, 0, stray.x, stray.y, stray.z]], size: 3),
        ])
        for chunk in RerunRRDWriter.chunks(for: scene) + [live] {
            RerunRRDWriter.appendMessage(kind: 2, payload: RerunRRDWriter.arrowMessage(chunk, store: store, ids: &ids), to: &data)
        }

        let recording = try RerunRRDReader.recording(from: data)
        let pack = try RerunPack.load(manifest: recording.pack.manifest, log: recording.pack.log,
                                      media: recording.pack.media, title: "")
        let frame = pack.trace.frameAt(pack.trace.duration)
        let points = Array(frame.mapPoints)
        let colors = Array(try XCTUnwrap(frame.mapPointColors))
        XCTAssertEqual(points.count, 5, "the map's four points and the stray one")
        XCTAssertEqual(colors[try XCTUnwrap(points.firstIndex(of: stray))], 0, "no colour, not opaque black")
        XCTAssertEqual(colors[try XCTUnwrap(points.firstIndex(of: SIMD3(0, 0, 0)))], 0xFFFF_0000, "the map's own colour")

        let line = "{\"t\":0,\"type\":\"point_cloud\",\"entity\":\"world/points\",\"positions\":[[0,0,0],[1,0,0]],\"colors\":[[-1,-1,-1],[10,20,30]]}"
        guard case .points(_, _, _, let parsed)? = RerunLog.parseLine(line) else { return XCTFail("not a point cloud") }
        XCTAssertEqual(parsed, [0, 0xFF0A_141E])
    }

    func testPlanePhotosRoundTripWithNonBlackStraightRGBA() throws {
        let exported = RerunExportAdapter.scene(for: try recordedPack())
        let reopened = RerunExportAdapter.scene(for: try reread(exported).pack)
        let texture = try XCTUnwrap(reopened.planes.first?.texture)
        let pixels = try XCTUnwrap(RerunImageCodec.rgbPixels(from: texture.imageData, maxDimension: 512))
        XCTAssertEqual(pixels.channels, 4)
        XCTAssertEqual(pixels.width, 2)
        XCTAssertEqual(pixels.height, 2)
        let texels = [UInt8](pixels.data), input = [UInt8](rgba)
        XCTAssertEqual(Array(texels[0..<8]), Array(input[0..<8]), "the opaque and the transparent texel, exactly")
        XCTAssertEqual(Array(texels[12..<16]), Array(input[12..<16]), "the last opaque texel, exactly")

        // The translucent texel keeps its alpha. ImageIO decodes premultiplied, 8 bits per
        // channel, so its colour comes back within two levels of the straight input at alpha
        // 77 — nowhere near the premultiplied (30, 45, 60), nor black.
        XCTAssertEqual(texels[11], 77)
        for channel in 8..<11 {
            XCTAssertLessThanOrEqual(abs(Int(texels[channel]) - Int(input[channel])), 2, "channel \(channel - 8)")
        }

        // Decoding it once more does not drift: a second export writes the same texels.
        let again = RerunExportAdapter.scene(for: try reread(reopened).pack)
        let second = try XCTUnwrap(again.planes.first?.texture)
        XCTAssertEqual(RerunImageCodec.rgbPixels(from: second.imageData, maxDimension: 512)?.data, pixels.data)
    }

    func testEveryRecordedPhotoIsExportedInsteadOfOnlyKeyframes() throws {
        let pack = try recordedPack()
        let exported = RerunExportAdapter.scene(for: pack)
        XCTAssertEqual(pack.trace.imageCount, 9)
        XCTAssertLessThan(exported.keyframes.count, 9)
        XCTAssertEqual(exported.photos.count, 9)
        let reopened = try reread(exported).pack
        XCTAssertEqual(reopened.trace.imageCount, 9)
        XCTAssertEqual(reopened.trace.imageTimes, pack.trace.imageTimes)

        // Each photo comes back at its own time with its own bytes, not merely nine of them.
        let frames = try (0...8).map(framePNG)
        XCTAssertEqual(Set(frames).count, 9)
        XCTAssertEqual(reopened.trace.imageTimes, (0...8).map { Float($0) * 0.5 })
        XCTAssertEqual(reopened.trace.imagePaths.map { reopened.bytes(for: $0) }, frames)
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

    func testPointsOutsideTheVoxelRangeFailWithoutTrapping() throws {
        var scene = RerunExportAdapter.scene(for: try recordedPack())
        scene.points[0] = SIMD3(Float.greatestFiniteMagnitude, 0, 0)
        assertFails(try RerunRRDWriter.data(for: scene), with: .malformed("point outside voxel range"))
    }

    /// The replay floors `coordinate / voxel` into an `Int64`, which traps from 2^63 up and
    /// below -2^63. The reader refuses exactly those coordinates: the nearest `Float` inside
    /// the range still opens, on every axis and at both ends.
    func testVoxelRangeEndsAtTheLastCoordinateThatConverts() throws {
        let voxel = RerunTrace.pointVoxel
        let limit = Float(sign: .plus, exponent: 63, significand: 1) // 2^63, one past Int64.max.
        var above = limit * voxel
        while above / voxel < limit { above = above.nextUp }
        while above.nextDown / voxel >= limit { above = above.nextDown }
        var below = -above
        while below / voxel >= -limit { below = below.nextDown } // -2^63 is Int64.min: it converts.
        while below.nextUp / voxel < -limit { below = below.nextUp }

        for axis in 0..<3 {
            for (refused, accepted) in [(above, above.nextDown), (below, below.nextUp)] {
                var scene = RerunExportAdapter.scene(for: try recordedPack())
                scene.points[0][axis] = refused
                assertFails(try RerunRRDWriter.data(for: scene), with: .malformed("point outside voxel range"))
                scene.points[0][axis] = accepted
                let frame = try reread(scene).pack.trace.frameAt(4)
                XCTAssertTrue(frame.mapPoints.contains(scene.points[0]), "axis \(axis), \(accepted)")
            }
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

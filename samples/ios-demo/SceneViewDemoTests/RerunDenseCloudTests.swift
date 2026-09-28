// RerunDenseCloudTests.swift
//
// Rerun v2's dense cloud on iOS (`RerunDenseCloud.swift`): the SVPC codec, the LiDAR depth
// back-projection, the 2 cm fusion, the replay surfels, the v2 manifest and the dense exports.
// The fixture tests read the very files the Android demo's `SvscanV2FixtureTest` asserts
// against (`samples/android-demo/src/test/resources/rerun/svscan-v2/`): the same literal clouds
// must encode to the same bytes on both platforms, and a v2 scan written by one must open on
// the other.

#if DEBUG

import Foundation
import simd
import XCTest
@testable import SceneViewDemo

final class RerunDenseCloudTests: XCTestCase {

    // MARK: - Shared fixtures (byte parity with Android)

    /// The shared fixture `name`, read from the Android test resources in the repository.
    private func fixture(_ name: String) throws -> Data {
        let url = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .appendingPathComponent("../../android-demo/src/test/resources/rerun/svscan-v2/\(name)")
            .standardizedFileURL
        return try Data(contentsOf: url)
    }

    /// Repeated verbatim in `SvscanV2FixtureTest.kt` — change both or neither.
    private let cloud = RerunDenseCloud(
        positions: [
            SIMD3(0, 0, 0),
            SIMD3(1.25, -0.5, 2),
            SIMD3(-0.123, 0.456, -0.789),
            SIMD3(0.0105, 1.5, -2.25),
            SIMD3(-1, -1, 1),
        ],
        colors: [0xFFFF_0000, 0xFF00_FF00, 0xFF00_00FF, 0, 0xFF80_8080],
        normals: [
            SIMD3(0, 1, 0),
            SIMD3(0, 0, -1),
            SIMD3(0.6, 0, 0.8),
            SIMD3(-0.267261, 0.534522, -0.801784),
            SIMD3(0.57735, -0.57735, 0.57735),
        ],
        confidences: [255, 128, 200, 131, 255]
    )

    /// Wider than ±32.767 m: the scale leaves the millimetre for `half extent / 32767`.
    private let wide = RerunDenseCloud(
        positions: [SIMD3(-50, 0, 0), SIMD3(50, 1, -3), SIMD3(12.345, -2.5, 7.5)],
        colors: [0xFF10_2030, 0xFFFF_FFFF, 0]
    )

    func testTheSharedCloudEncodesToTheAndroidFixtureBytes() throws {
        XCTAssertEqual(RerunSVPC.encode(cloud), try fixture("points.bin"))
    }

    func testACloudPastThirtyTwoMetresEncodesToTheFixtureBytesScaleAndAll() throws {
        let bytes = try fixture("points-wide.bin")
        XCTAssertEqual(RerunSVPC.encode(wide), bytes)
        let back = try XCTUnwrap(RerunSVPC.decode(bytes))
        for (a, b) in zip(wide.positions, back.positions) {
            XCTAssertLessThanOrEqual(simd_reduce_max(simd_abs(a - b)), 50 / 32_767)
        }
    }

    func testTheFixtureDecodesToTheSharedCloudWithinTheCodecPrecision() throws {
        let back = try XCTUnwrap(RerunSVPC.decode(try fixture("points.bin")))
        XCTAssertEqual(back.count, cloud.count)
        for (a, b) in zip(cloud.positions, back.positions) {
            XCTAssertLessThanOrEqual(simd_reduce_max(simd_abs(a - b)), 0.0005)
        }
        // A missing colour (0) is written black, and reads back opaque black.
        XCTAssertEqual(back.colors, [0xFFFF_0000, 0xFF00_FF00, 0xFF00_00FF, 0xFF00_0000, 0xFF80_8080])
        XCTAssertEqual(back.confidences, cloud.confidences)
        for (a, b) in zip(try XCTUnwrap(cloud.normals), try XCTUnwrap(back.normals)) {
            XCTAssertLessThanOrEqual(simd_reduce_max(simd_abs(a - b)), 0.02)
        }
    }

    func testTheSharedScanFileIsTheStoredZipOfItsThreePayloads() throws {
        let capture = RerunCapturePack(
            manifest: try fixture("dense-v2-manifest.json"),
            log: try fixture("dense-v2-log.jsonl"),
            media: try fixture("points.bin")
        )
        XCTAssertEqual(RerunScanFile.data(for: capture), try fixture("dense-v2.svscan"))
    }

    func testTheAndroidScanFileOpensWithItsDeviceDenseCloudAndDenseTimeline() throws {
        let capture = try RerunScanFile.capture(from: try fixture("dense-v2.svscan"))
        let pack = try RerunPack.load(manifest: capture.manifest, log: capture.log, media: capture.media, title: "Android")
        let manifest = pack.manifest
        XCTAssertEqual(manifest.version, 2)
        XCTAssertEqual(manifest.device, RerunManifest.Device(platform: "android", model: "Pixel 8 Pro",
                                                              tier: "depth", depthSource: "arcore_raw_depth"))
        let dense = try XCTUnwrap(manifest.dense)
        XCTAssertEqual(dense.path, RerunManifest.Dense.path)
        XCTAssertEqual(dense.count, 5)
        XCTAssertEqual(dense.voxelM, 0.02)
        XCTAssertTrue(dense.normals)
        XCTAssertEqual(dense.bounds, [-1, -1, -2.25, 1.25, 1.5, 2])
        XCTAssertEqual(manifest.denseMs, 42)
        XCTAssertEqual(pack.dense?.count, 5)
        XCTAssertEqual(pack.trace.denseCountAt(0), 0)
        XCTAssertEqual(pack.trace.denseCountAt(0.3), 3)
        XCTAssertEqual(pack.trace.denseCountAt(0.6), 5)
    }

    func testTheRecorderWritesTheSameDenseSectionAndDepthStatsLinesAsAndroid() throws {
        let manifest = try XCTUnwrap(String(data: try fixture("dense-v2-manifest.json"), encoding: .utf8))
        XCTAssertTrue(manifest.contains("\"dense\":" + RerunCaptureJSON.denseSection(cloud, voxelM: 0.02) + ","))
        let log = try XCTUnwrap(String(data: try fixture("dense-v2-log.jsonl"), encoding: .utf8))
        XCTAssertTrue(log.contains(RerunCaptureJSON.depthStats(t: 100_000_000, added: 3, kept: 40, total: 3)))
        XCTAssertTrue(log.contains(RerunCaptureJSON.depthStats(t: 600_000_000, added: 2, kept: 35, total: 5)))
    }

    // MARK: - Recorder: v1 unchanged, v2 manifest

    private struct Frame: RerunCaptureFrame {
        var timestamp: TimeInterval
        var cameraTransform: simd_float4x4
        var sensorLens = RerunPinhole(width: 1920, height: 1440, fx: 1400, fy: 1410, cx: 950, cy: 730)
        var isTrackingNormal = true

        func featurePoints() -> [SIMD3<Float>] { [] }
        func planes() -> [RerunCapturePlane] { [] }
        func withColorLookup<R>(_ body: ((SIMD2<Float>) -> SIMD3<UInt8>?) -> R) -> R { body { _ in nil } }
        func portraitJPEG(width: Int, height: Int, quality: Double) -> Data? { nil }
    }

    private func recorder(frames: Int) -> RerunCaptureRecorder {
        var recorder = RerunCaptureRecorder()
        for i in 0..<frames {
            var m = matrix_identity_float4x4
            m.columns.3 = SIMD4(Float(i) * 0.01, 1.5, 0, 1)
            recorder.add(Frame(timestamp: 10 + Double(i) / 16, cameraTransform: m))
        }
        return recorder
    }

    func testWithoutADeviceTheManifestStaysV1() throws {
        let pack = recorder(frames: 4).finish()
        let text = try XCTUnwrap(String(data: pack.manifest, encoding: .utf8))
        XCTAssertFalse(text.contains("\"version\""))
        XCTAssertFalse(text.contains("\"device\""))
        XCTAssertEqual(RerunManifest.parse(pack.manifest)?.version, 1)
    }

    func testALiDARCaptureIsAV2ScanWithItsDenseBlobAndTimeline() throws {
        var capturing = recorder(frames: 8)
        capturing.addDepthStats(timestamp: 10 + 2.0 / 16, added: 3, kept: 40, total: 3)
        capturing.addDepthStats(added: 2, kept: 35, total: 5)
        let device = RerunManifest.Device(platform: "ios", model: "iPhone16,1",
                                          tier: RerunManifest.Device.tierLidar, depthSource: RerunManifest.Device.sourceLidar)
        let capture = capturing.finish(device: device, dense: cloud, denseMs: 7)
        let text = try XCTUnwrap(String(data: capture.manifest, encoding: .utf8))
        // Android's key order: version, device, dense, built, then the v1 keys.
        XCTAssertTrue(text.hasPrefix(
            "{\"version\":2,\"device\":{\"platform\":\"ios\",\"model\":\"iPhone16,1\",\"tier\":\"lidar\",\"depthSource\":\"arkit_lidar\"},"
                + "\"dense\":" + RerunCaptureJSON.denseSection(cloud, voxelM: 0.02) + ",\"built\":{\"denseMs\":7},"
        ))
        let pack = try RerunPack.load(manifest: capture.manifest, log: capture.log, media: capture.media, title: "LiDAR")
        XCTAssertEqual(pack.manifest.device, device)
        XCTAssertEqual(pack.manifest.media[RerunManifest.Dense.path]?.length, RerunSVPC.encode(cloud).count)
        XCTAssertEqual(pack.dense?.count, cloud.count)
        XCTAssertEqual(pack.trace.denseCountAt(0.1), 0)
        XCTAssertEqual(pack.trace.denseCountAt(0.2), 3)
        XCTAssertEqual(pack.trace.denseCountAt(pack.trace.duration), 5)
        XCTAssertEqual(try RerunScanFile.capture(from: RerunScanFile.data(for: capture)), capture)
    }

    func testASparseV2ScanCarriesNoDenseKeyAndNoBlob() throws {
        let device = RerunManifest.Device(platform: "ios", model: "iPhone14,7",
                                          tier: RerunManifest.Device.tierSparse, depthSource: RerunManifest.Device.sourceFeaturePoints)
        let base = recorder(frames: 4)
        let capture = base.finish(device: device, dense: .empty)
        let manifest = try XCTUnwrap(RerunManifest.parse(capture.manifest))
        XCTAssertEqual(manifest.version, 2)
        XCTAssertNil(manifest.dense)
        XCTAssertNil(manifest.media[RerunManifest.Dense.path])
        XCTAssertEqual(capture.media, base.finish().media)
        let pack = try RerunPack.load(manifest: capture.manifest, log: capture.log, media: capture.media, title: "Sparse")
        XCTAssertNil(pack.dense)
        XCTAssertEqual(pack.trace.denseCountAt(1), -1)
    }

    // MARK: - SVPC codec

    func testAnSVPCBlobIsAHeaderThenTwelveBytesAPoint() {
        let blob = RerunSVPC.encode(randomCloud(1_000))
        XCTAssertEqual(blob.count, 32 + 12 * 1_000)
        XCTAssertEqual(Array(blob.prefix(4)), Array("SVPC".utf8))
        XCTAssertEqual(RerunSVPC.encode(.empty).count, 32)
        XCTAssertEqual(RerunSVPC.decode(RerunSVPC.encode(.empty))?.count, 0)
    }

    func testAnSVPCBlobRoundTripsToTheMillimetre() throws {
        let cloud = randomCloud(5_000)
        let back = try XCTUnwrap(RerunSVPC.decode(RerunSVPC.encode(cloud)))
        XCTAssertEqual(back.count, cloud.count)
        for (a, b) in zip(cloud.positions, back.positions) {
            XCTAssertLessThanOrEqual(simd_reduce_max(simd_abs(a - b)), 0.0005 + 1e-5)
        }
        XCTAssertEqual(back.colors, cloud.colors)
        XCTAssertEqual(back.confidences, cloud.confidences)
        for (a, b) in zip(try XCTUnwrap(cloud.normals), try XCTUnwrap(back.normals)) {
            XCTAssertGreaterThan(simd_dot(a, b), 0.999)
        }
    }

    func testABlobThatIsNotSVPCv1OrIsCutShortDoesNotDecode() {
        let blob = RerunSVPC.encode(randomCloud(10))
        XCTAssertNotNil(RerunSVPC.decode(blob))
        var badMagic = blob
        badMagic[0] = UInt8(ascii: "X")
        XCTAssertNil(RerunSVPC.decode(badMagic))
        var badVersion = blob
        badVersion[4] = 2
        XCTAssertNil(RerunSVPC.decode(badVersion))
        XCTAssertNil(RerunSVPC.decode(blob.prefix(blob.count - 1)))
        XCTAssertNil(RerunSVPC.decode(blob.prefix(20)))
        XCTAssertNil(RerunSVPC.decode(Data()))
    }

    // MARK: - Back-projection

    private let w = 8, h = 6
    private let color: UInt32 = 0xFF40_80C0

    private func frame(depth: (Int, Int) -> Float, confidence: ((Int, Int) -> UInt8)? = { _, _ in 255 },
                       pose: simd_float4x4 = matrix_identity_float4x4) -> RerunDepthFrame {
        RerunDepthFrame(
            width: w, height: h,
            depthM: (0..<(w * h)).map { depth($0 % w, $0 / w) },
            confidence: confidence.map { c in (0..<(w * h)).map { c($0 % w, $0 / w) } },
            colors: Array(repeating: color, count: w * h),
            fx: 4, fy: 4, cx: 4, cy: 3,
            cameraToWorld: pose
        )
    }

    func testAWallTwoMetresAheadBackProjectsThroughTheLensFacingTheCamera() {
        let samples = RerunDepthBackProjection.project(frame(depth: { _, _ in 2 }))
        XCTAssertEqual(samples.count, w * h)
        XCTAssertEqual(samples.positions[0], SIMD3(-2, 1.5, -2))
        for i in 0..<samples.count {
            XCTAssertEqual(samples.positions[i].z, -2, accuracy: 1e-5)
            XCTAssertEqual(simd_distance(samples.normals[i], SIMD3(0, 0, 1)), 0, accuracy: 1e-5)
            XCTAssertEqual(samples.colors[i], color)
        }
    }

    func testTheCameraPoseMovesThePointsAndTurnsTheNormals() {
        // Half a turn about Y, one metre up: the wall is now behind the start, facing it.
        var pose = simd_float4x4(simd_quatf(angle: .pi, axis: SIMD3(0, 1, 0)))
        pose.columns.3 = SIMD4(0, 1, 0, 1)
        let samples = RerunDepthBackProjection.project(frame(depth: { _, _ in 2 }, pose: pose))
        let centre = 3 * w + 4
        XCTAssertEqual(simd_distance(samples.positions[centre], SIMD3(0, 1, 2)), 0, accuracy: 1e-5)
        XCTAssertEqual(samples.normals[centre].z, -1, accuracy: 1e-5)
    }

    func testLowConfidenceOutOfRangeDepthAndFlyingPixelsAreDropped() {
        let f = frame(
            depth: { x, y in
                switch (x, y) {
                case (0, 0): 0 // no depth
                case (1, 0): 6 // past 5 m
                case (4, 3): 3 // alone a metre behind its neighbours
                default: 2
                }
            },
            confidence: { x, y in x == 7 && y == 5 ? 127 : 128 }
        )
        let samples = RerunDepthBackProjection.project(f)
        XCTAssertEqual(samples.count, w * h - 4)
        XCTAssertTrue(samples.positions.allSatisfy { abs($0.z + 2) < 1e-5 })
        XCTAssertEqual(RerunDepthBackProjection.project(frame(depth: { _, _ in 2 }, confidence: nil)).count, w * h)
    }

    func testARKitConfidenceLevelsMapOntoAndroidsScale() {
        XCTAssertEqual([0, 1, 2].map { RerunDepthBackProjection.confidence(arkitLevel: $0) }, [0, 128, 255])
        XCTAssertLessThan(Int(RerunDepthBackProjection.confidence(arkitLevel: 0)), RerunDepthBackProjection.minConfidence)
        XCTAssertGreaterThanOrEqual(Int(RerunDepthBackProjection.confidence(arkitLevel: 1)), RerunDepthBackProjection.minConfidence)
    }

    // MARK: - Fusion

    private func samples(_ positions: [SIMD3<Float>], _ colors: [UInt32]) -> RerunDenseSamples {
        RerunDenseSamples(positions: positions, normals: positions.map { _ in SIMD3(0, 0, 1) },
                          colors: colors, confidences: colors.map { _ in 200 })
    }

    func testSamplesInOneTwoCentimetreVoxelFuseIntoOneAveragedSurfel() {
        let fusion = RerunDenseFusion()
        let red: UInt32 = 0xFFFF_0000, blue: UInt32 = 0xFF00_00FF
        let stats = fusion.add(samples([SIMD3(0.001, 0.001, 0.001), SIMD3(0.011, 0.009, 0.005)], [red, blue]))
        XCTAssertEqual(stats, RerunDenseFuseStats(added: 1, kept: 2, total: 1))
        let cloud = fusion.cloud()
        XCTAssertEqual(cloud.count, 1)
        XCTAssertEqual(simd_distance(cloud.positions[0], SIMD3(0.006, 0.005, 0.003)), 0, accuracy: 1e-6)
        XCTAssertEqual(cloud.colors[0], 0xFF80_0080)
        // The next voxel over is another surfel; seen again, nothing new.
        XCTAssertEqual(fusion.add(samples([SIMD3(0.021, 0, 0)], [red])).added, 1)
        XCTAssertEqual(fusion.add(samples([SIMD3(0.022, 0.001, 0)], [red])).added, 0)
        XCTAssertEqual(fusion.count, 2)
    }

    func testTheMapIsCappedKeepsInsertionOrderAndSurvivesRehashing() {
        let capped = RerunDenseFusion(maxPoints: 3)
        let line = (0..<10).map { SIMD3<Float>(Float($0) * 0.1, 0, 0) }
        let stats = capped.add(samples(line, Array(repeating: color, count: 10)))
        XCTAssertEqual(stats.added, 3)
        XCTAssertEqual(stats.total, 3)
        XCTAssertEqual(capped.cloud(limit: 2).positions, [SIMD3(0, 0, 0), SIMD3(0.1, 0, 0)])

        let big = RerunDenseFusion()
        let n = 50_000
        let grid = (0..<n).map { SIMD3<Float>(Float($0 % 250) * 0.02 + 0.01, Float($0 / 250) * 0.02 + 0.01, -1) }
        let colors = Array(repeating: color, count: n)
        XCTAssertEqual(big.add(samples(grid, colors)).added, n)
        XCTAssertEqual(big.add(samples(grid, colors)).added, 0)
        XCTAssertEqual(big.count, n)
    }

    func testBackProjectedFramesFuseIntoACloudThatRoundTripsThroughSVPC() throws {
        let fusion = RerunDenseFusion()
        fusion.add(RerunDepthBackProjection.project(frame(depth: { _, _ in 2 })))
        var shifted = matrix_identity_float4x4
        shifted.columns.3.x = 0.005
        fusion.add(RerunDepthBackProjection.project(frame(depth: { _, _ in 2 }, pose: shifted)))
        let cloud = fusion.cloud()
        XCTAssertTrue((1...(w * h)).contains(cloud.count))
        let back = try XCTUnwrap(RerunSVPC.decode(RerunSVPC.encode(cloud)))
        XCTAssertEqual(back.count, cloud.count)
        XCTAssertTrue(try XCTUnwrap(back.confidences).allSatisfy { Int($0) >= RerunDepthBackProjection.minConfidence })
    }

    // MARK: - Replay

    func testEachSurfelIsAQuadInThePlaneOfItsNormalColouredByItsOwnTexel() {
        let cloud = RerunDenseCloud(positions: [SIMD3(0, 0, 0), SIMD3(1, 1, 1)], colors: [color, 0],
                                    normals: [SIMD3(0, 0, 1), SIMD3(0, 1, 0)])
        let mesh = RerunDenseSurfels.mesh(cloud, voxelM: 0.02, atlasSize: RerunDenseReplay.atlasSize)
        XCTAssertEqual(mesh.vertexCount, 8)
        XCTAssertEqual(mesh.indices.count, 12)
        // The first quad lies in z = 0, its corners 0.013 m out: 1.3 voxels wide.
        for v in 0..<4 {
            XCTAssertEqual(mesh.positions[v].z, 0, accuracy: 1e-6)
            XCTAssertEqual(abs(mesh.positions[v].x), 0.013, accuracy: 1e-6)
            XCTAssertEqual(mesh.uvs[v], RerunPointAtlas.uv(of: 0, size: RerunDenseReplay.atlasSize))
        }
        // The second, floor-like, lies in y = 1.
        for v in 4..<8 { XCTAssertEqual(mesh.positions[v].y, 1, accuracy: 1e-6) }
        let atlas = RerunPointAtlas.pixels(cloud.colors[...], count: 2, fallback: 0xFF10_2030, size: RerunDenseReplay.atlasSize)
        XCTAssertEqual(atlas.count, RerunDenseReplay.atlasSize * RerunDenseReplay.atlasSize * 4)
        XCTAssertEqual(Array(atlas[0..<8]), [0x40, 0x80, 0xC0, 0xFF, 0x10, 0x20, 0x30, 0xFF])
    }

    func testTheReplayRevealsTheMapChunkByChunkAsItGrew() throws {
        let count = 17_000
        let cloud = RerunDenseCloud(
            positions: (0..<count).map { SIMD3(Float($0 % 100) * 0.02, Float($0 / 100) * 0.02, -1) },
            colors: Array(repeating: color, count: count)
        )
        let replay = try XCTUnwrap(RerunDenseReplay(cloud, voxelM: 0.02))
        XCTAssertEqual(replay.starts, [0, 8_192, 16_384])
        XCTAssertEqual(replay.chunks.map(\.vertexCount), [8_192 * 4, 8_192 * 4, (count - 16_384) * 4])
        // The last surfel keeps its own texel across chunks.
        XCTAssertEqual(replay.chunks[2].uvs.last, RerunPointAtlas.uv(of: count - 1, size: RerunDenseReplay.atlasSize))
        XCTAssertNotNil(replay.atlas)
        XCTAssertEqual(replay.chunksShown(denseCount: -1), 3)
        XCTAssertEqual(replay.chunksShown(denseCount: 0), 0)
        XCTAssertEqual(replay.chunksShown(denseCount: 1), 1)
        XCTAssertEqual(replay.chunksShown(denseCount: 8_192), 1)
        XCTAssertEqual(replay.chunksShown(denseCount: 8_193), 2)
        XCTAssertEqual(replay.chunksShown(denseCount: count), 3)
        XCTAssertNil(RerunDenseReplay(.empty, voxelM: 0.02))
    }

    // MARK: - Exports

    private func fixturePack() throws -> RerunPack {
        let capture = try RerunScanFile.capture(from: try fixture("dense-v2.svscan"))
        return try RerunPack.load(manifest: capture.manifest, log: capture.log, media: capture.media, title: "Dense")
    }

    func testThePLYOfADenseScanIsItsDenseCloudWithNormals() throws {
        let scene = RerunExportAdapter.scene(for: try fixturePack())
        XCTAssertEqual(scene.denseVoxelM, 0.02)
        let ply = RerunPLYWriter.data(for: scene)
        let header = RerunPLYWriter.header(title: "Dense", count: 5, normals: true)
        XCTAssertTrue(header.contains("property float nx\nproperty float ny\nproperty float nz\nend_header\n"))
        XCTAssertTrue(ply.starts(with: Data(header.utf8)))
        XCTAssertEqual(ply.count, header.utf8.count + 5 * 27)
        XCTAssertEqual(RerunPLYWriter.bytesPerDensePoint, 27)
    }

    func testTheRRDAndGLBOfADenseScanCarryWorldDense() throws {
        let scene = RerunExportAdapter.scene(for: try fixturePack())
        let dense = RerunRRDWriter.chunks(for: scene).filter { $0.entityPath == "/world/dense" }
        XCTAssertEqual(dense.count, 1)
        XCTAssertNil(dense.first?.times)
        let glb = try RerunGLBWriter.data(for: scene)
        XCTAssertNotNil(glb.range(of: Data("\"world/dense\"".utf8)))
        XCTAssertNotNil(glb.range(of: Data("\"NORMAL\"".utf8)))
    }

    // MARK: - Helpers

    /// A deterministic pseudo-random cloud (SplitMix64): unit normals, confidences ≥ 128.
    private func randomCloud(_ n: Int, seed: UInt64 = 7) -> RerunDenseCloud {
        var state = seed
        func next() -> Float {
            state &+= 0x9E37_79B9_7F4A_7C15
            var z = state
            z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
            z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
            return Float((z ^ (z >> 31)) >> 40) / Float(1 << 24)
        }
        var positions: [SIMD3<Float>] = [], normals: [SIMD3<Float>] = []
        var colors: [UInt32] = [], confidences: [UInt8] = []
        for _ in 0..<n {
            positions.append(SIMD3(next() * 10 - 5, next() * 10 - 5, next() * 10 - 5))
            let v = SIMD3(next() * 2 - 1, next() * 2 - 1, next() * 2 - 1)
            normals.append(v / max(simd_length(v), 1e-3))
            colors.append(0xFF00_0000 | UInt32(next() * 0xFF_FFFF))
            confidences.append(UInt8(128 + Int(next() * 127)))
        }
        return RerunDenseCloud(positions: positions, colors: colors, normals: normals, confidences: confidences)
    }
}

#endif

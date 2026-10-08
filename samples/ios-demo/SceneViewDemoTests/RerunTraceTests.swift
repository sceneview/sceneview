// RerunTraceTests.swift
//
// The Rerun showcase's model (`RerunTrace.swift`, `RerunExportAdapter.swift`): the bundled
// session Android ships loads on iOS, the replay's figures only ever grow, the playback
// clock loops, the chrome's wording, and the export scene built from the showcase.

#if DEBUG

import XCTest
import simd
@testable import SceneViewDemo

final class RerunTraceTests: XCTestCase {

    // MARK: Showcase pack

    private static let pack: RerunPack? = try? RerunPack.loadShowcase()

    private func showcase() throws -> RerunPack {
        try XCTUnwrap(Self.pack, "The shared rerun/showcase pack must ship in the app bundle")
    }

    func testShowcaseLoadsTheSessionAndroidShips() throws {
        let pack = try showcase()
        XCTAssertTrue(pack.isShowcase)
        XCTAssertEqual(pack.title, "Recorded room")
        XCTAssertEqual(pack.trace.duration, 18.3, accuracy: 0.5)
        XCTAssertEqual(pack.trace.imageCount, 184)
        XCTAssertGreaterThan(pack.trace.poseCount, 100)
        XCTAssertGreaterThan(pack.trace.mapPointCount, 1_000)
        XCTAssertNotNil(pack.manifest.intrinsics)
        // Every camera frame the filmstrip and the frustums show is in the media blob.
        for path in pack.trace.imagePaths {
            XCTAssertNotNil(pack.bytes(for: path), path)
        }
    }

    func testShowcaseFiguresOnlyGrowAsItPlays() throws {
        let trace = try showcase().trace
        var last = RerunStats()
        for step in 0...20 {
            let stats = RerunStats(frame: trace.frameAt(trace.duration * Float(step) / 20))
            XCTAssertGreaterThanOrEqual(stats.pathMetres, last.pathMetres - 1e-4)
            XCTAssertGreaterThanOrEqual(stats.mapPoints, last.mapPoints)
            XCTAssertGreaterThanOrEqual(stats.anchors, last.anchors)
            last = stats
        }
        XCTAssertTrue(last.tracking)
        XCTAssertGreaterThan(last.pathMetres, 1)
        XCTAssertGreaterThan(last.planes, 0)
        XCTAssertEqual(last.anchors, 2, "The showcase places two shibas")
    }

    func testShowcaseKeyframesCarryTheirPhotos() throws {
        let frame = try showcase().trace.frameAt(try showcase().trace.duration)
        XCTAssertFalse(frame.keyframes.isEmpty)
        XCTAssertEqual(frame.keyframes.count, frame.keyframeImages.count)
        XCTAssertTrue(frame.keyframeImages.contains { $0 != nil })
    }

    // MARK: Export scene

    func testExportSceneHoldsTheWholeSession() throws {
        let pack = try showcase()
        let scene = RerunExportAdapter.scene(for: pack)
        let whole = pack.trace.frameAt(pack.trace.duration)
        XCTAssertEqual(scene.points.count, whole.mapPointCount)
        XCTAssertEqual(scene.pointColors.count, scene.points.count)
        XCTAssertEqual(scene.cameraPath.count, pack.trace.poseCount)
        XCTAssertEqual(scene.anchors.count, 2)
        XCTAssertTrue(scene.anchors.allSatisfy { $0.modelName == RerunExportAdapter.anchorModel })
        XCTAssertEqual(scene.planes.count, whole.planes.count)
        XCTAssertFalse(scene.keyframes.isEmpty)
        for keyframe in scene.keyframes {
            XCTAssertNotNil(scene.images[keyframe.imagePath], keyframe.imagePath)
        }
        XCTAssertNotNil(scene.lens)
    }

    func testExportFileStem() {
        XCTAssertEqual(RerunExportAdapter.fileStem("Recorded room"), "recorded-room")
        XCTAssertEqual(RerunExportAdapter.fileStem("My AR session"), "my-ar-session")
        XCTAssertEqual(RerunExportAdapter.fileStem("  "), "space")
    }

    func testEveryFormatWritesFromTheShowcase() throws {
        let scene = RerunExportAdapter.scene(for: try showcase())
        let directory = RerunExportAdapter.freshDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        for format in RerunExportFormat.allCases {
            let url = try RerunExportAdapter.write(format, scene: scene, directory: directory)
            XCTAssertEqual(url.pathExtension, format.fileExtension)
            let size = try XCTUnwrap(try FileManager.default.attributesOfItem(atPath: url.path)[.size] as? Int)
            XCTAssertGreaterThan(size, 1_000, format.rawValue)
        }
    }

    // MARK: Playback

    func testScrubPausesAndClamps() {
        var playback = RerunPlayback(duration: 10)
        playback.playFromStart()
        playback.scrub(to: 42)
        XCTAssertFalse(playback.playing)
        XCTAssertEqual(playback.cursor, 10)
        playback.scrub(to: -1)
        XCTAssertEqual(playback.cursor, 0)
    }

    func testPlaybackHoldsThenLoops() {
        var playback = RerunPlayback(duration: 1)
        playback.playFromStart()
        for _ in 0..<4 { playback.tick(0.25) }
        XCTAssertEqual(playback.cursor, 1)
        playback.tick(0.25)
        XCTAssertEqual(playback.cursor, 1, "The last frame holds before the loop")
        for _ in 1..<Int(RerunPlayback.loopHold / 0.25) { playback.tick(0.25) }
        XCTAssertEqual(playback.cursor, 0)
        XCTAssertTrue(playback.playing)
    }

    func testTogglePlayAtTheEndRestarts() {
        var playback = RerunPlayback(duration: 2)
        playback.scrub(to: 2)
        playback.togglePlay()
        XCTAssertTrue(playback.playing)
        XCTAssertEqual(playback.cursor, 0)
    }

    // MARK: Wording

    func testFormat() {
        XCTAssertEqual(RerunFormat.clock(72.4), "1:12")
        XCTAssertEqual(RerunFormat.clock(-3), "0:00")
        XCTAssertEqual(RerunFormat.distance(0.42), "42 cm")
        XCTAssertEqual(RerunFormat.distance(14.236), "14.2 m")
        XCTAssertEqual(RerunFormat.count(3_812), "3,812")
        XCTAssertEqual(RerunFormat.compactCount(812), "812")
        XCTAssertEqual(RerunFormat.compactCount(4_812), "4.8k")
        XCTAssertEqual(RerunFormat.compactCount(12_400), "12k")
    }

    /// The same indices Android's `filmstripFrames` picks.
    func testFilmstripFramesMatchAndroid() {
        XCTAssertEqual(rerunFilmstripFrames(count: 184, slots: 5), [0, 46, 92, 137, 183])
        XCTAssertEqual(rerunFilmstripFrames(count: 3, slots: 8), [0, 1, 2])
        XCTAssertEqual(rerunFilmstripFrames(count: 184, slots: 1), [0])
        XCTAssertTrue(rerunFilmstripFrames(count: 0, slots: 5).isEmpty)
        XCTAssertTrue(rerunFilmstripFrames(count: 10, slots: 0).isEmpty)
    }

    // MARK: Room measure (the cases of Android's `RoomMeasureTest`)

    private static let floorY: Float = -1.4
    private static let wallHeight: Float = 2.4

    /// A wall from (x0, z0) to (x1, z1), standing on the floor, turned `degrees` about +Y.
    private func wall(_ id: Int, _ x0: Float, _ z0: Float, _ x1: Float, _ z1: Float, turned degrees: Float) -> RerunPlane {
        let ends = [turn(x0, z0, degrees), turn(x1, z1, degrees)]
        let low = Self.floorY
        let high = Self.floorY + Self.wallHeight
        return RerunPlane(id: id, kind: .wall, polygon: [
            SIMD3(ends[0].x, low, ends[0].y), SIMD3(ends[1].x, low, ends[1].y),
            SIMD3(ends[1].x, high, ends[1].y), SIMD3(ends[0].x, high, ends[0].y),
        ])
    }

    private func turn(_ x: Float, _ z: Float, _ degrees: Float) -> SIMD2<Float> {
        let angle = degrees * .pi / 180
        return SIMD2(x * cos(angle) - z * sin(angle), x * sin(angle) + z * cos(angle))
    }

    /// Four walls of a 3.4 × 4.1 m room, none of them reaching its corners.
    private func room(turned degrees: Float) -> [RerunPlane] {
        [
            wall(1, 0.2, 0, 3.2, 0, turned: degrees),
            wall(2, 3.4, 0.3, 3.4, 3.9, turned: degrees),
            wall(3, 0, 4.1, 3.4, 4.1, turned: degrees),
            wall(4, 0, 0.1, 0, 4, turned: degrees),
        ]
    }

    func testRoomIsMeasuredSquareToItsWalls() throws {
        let measure = try XCTUnwrap(RerunRoomMeasure.of(room(turned: 30), floorY: Self.floorY))
        XCTAssertEqual(measure.yaw * 180 / .pi, 30, accuracy: 0.5)
        XCTAssertEqual(measure.width, 3.4, accuracy: 0.02)
        XCTAssertEqual(measure.depth, 4.1, accuracy: 0.02)
        XCTAssertEqual(measure.summary, "3.4 × 4.1 m · 14 m²")
    }

    /// Walls a quarter turn apart agree on the room's direction: 70° reads as −20°.
    func testRoomYawIsModuloAQuarterTurn() throws {
        let measure = try XCTUnwrap(RerunRoomMeasure.of(room(turned: 70), floorY: Self.floorY))
        XCTAssertEqual(measure.yaw * 180 / .pi, -20, accuracy: 0.5)
        XCTAssertEqual(measure.area, 13.94, accuracy: 0.15)
    }

    func testRoomNeedsTheFloorOrAWall() {
        let height = Self.floorY + 0.45
        let table = RerunPlane(id: 1, kind: .floor, polygon: [
            SIMD3(0, height, 0), SIMD3(1.2, height, 0), SIMD3(1.2, height, 0.8), SIMD3(0, height, 0.8),
        ])
        XCTAssertNil(RerunRoomMeasure.of([table], floorY: Self.floorY), "A table top is not the room's floor")
        XCTAssertNil(RerunRoomMeasure.of([], floorY: Self.floorY))
    }

    func testAStripIsNotARoom() {
        let y = Self.floorY
        let strip = RerunPlane(id: 1, kind: .floor, polygon: [
            SIMD3(0, y, 0), SIMD3(2, y, 0), SIMD3(2, y, 0.4), SIMD3(0, y, 0.4),
        ])
        XCTAssertNil(RerunRoomMeasure.of([strip], floorY: y))
    }

    func testRoomWording() {
        XCTAssertEqual(RerunRoomMeasure.metres(3.43), "3.4 m")
        XCTAssertEqual(RerunRoomMeasure.metres(12.4), "12 m")
        XCTAssertEqual(RerunRoomMeasure.squareMetres(2.5), "2.5 m²")
        XCTAssertEqual(RerunRoomMeasure.squareMetres(13.94), "14 m²")
    }

    /// The sample's room size is read from its own planes, never typed in — and at the instant
    /// both apps' captures stop on, it is the room Android names for the same recording.
    func testShowcaseRoomComesFromItsPlanes() throws {
        let pack = try showcase()
        let whole = pack.trace.frameAt(pack.trace.duration)
        let floorY = RerunGeometry.floorHeight(whole)
        let measure = try XCTUnwrap(RerunRoomMeasure.of(whole.planes, floorY: floorY))
        XCTAssertGreaterThan(measure.width, RerunRoomMeasure.minSide)
        XCTAssertGreaterThan(measure.depth, RerunRoomMeasure.minSide)
        XCTAssertEqual(measure.area, measure.width * measure.depth, accuracy: 0.001)

        let paused = pack.trace.frameAt(pack.trace.duration * RerunShowcaseDemo.qaFraction)
        XCTAssertEqual(RerunRoomMeasure.of(paused.planes, floorY: floorY)?.summary, "3.9 × 3.8 m · 15 m²")
    }

    // MARK: Framing

    private static let box: (SIMD3<Float>, SIMD3<Float>) = (SIMD3(-2, -1.4, -2), SIMD3(2, 1, 2))

    /// On a wide window the height limits the picture: a chrome that takes some of it sends the
    /// camera back until the room fits the band that is left.
    func testAWideWindowFramesTheRoomInItsClearBand() {
        let whole = RerunFraming.home(bounds: Self.box, azimuth: 0, aspect: 2.17)
        let banded = RerunFraming.home(bounds: Self.box, azimuth: 0, aspect: 2.17, band: 0.63)
        XCTAssertGreaterThan(banded.distance, whole.distance * 1.3)
    }

    /// On a tall window the width limits it: the chrome's band changes nothing.
    func testATallWindowIsFramedByItsWidth() {
        let whole = RerunFraming.home(bounds: Self.box, azimuth: 0, aspect: 0.46)
        let banded = RerunFraming.home(bounds: Self.box, azimuth: 0, aspect: 0.46, band: 0.68)
        XCTAssertEqual(banded.distance, whole.distance, accuracy: 0.001)
    }

    /// Seen from above, a room shows more of its near floor than of its far ceiling: it sits
    /// under the picture's middle, and the more so the closer the camera stands.
    func testARoomSeenFromAboveSags() {
        let home = RerunFraming.home(bounds: Self.box, azimuth: RerunFraming.homeAzimuth, aspect: 0.46)
        let sag = RerunFraming.sag(bounds: Self.box, pose: home)
        XCTAssertGreaterThan(sag, 0)
        XCTAssertLessThanOrEqual(sag, RerunFraming.maxSag)

        var far = home
        far.distance *= 4
        XCTAssertLessThan(RerunFraming.sag(bounds: Self.box, pose: far), sag)
        XCTAssertEqual(RerunFraming.sag(bounds: nil, pose: home), 0)
    }

    /// A camera inside the room has no whole room to centre.
    func testNoSagFromInsideTheRoom() {
        var inside = RerunFraming.home(bounds: Self.box, azimuth: 0, aspect: 0.46)
        inside.distance = RerunFraming.minDistance
        XCTAssertEqual(RerunFraming.sag(bounds: Self.box, pose: inside), 0)
    }

    /// A chrome that leaves almost nothing does not send the camera out of the room's reach.
    func testTheBandHasAFloor() {
        let least = RerunFraming.home(bounds: Self.box, azimuth: 0, aspect: 2.17, band: RerunFraming.minBand)
        let none = RerunFraming.home(bounds: Self.box, azimuth: 0, aspect: 2.17, band: 0)
        XCTAssertEqual(none.distance, least.distance, accuracy: 0.001)
    }
}

#endif

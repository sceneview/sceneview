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

    /// A room that is not square to the world, with one tall wall on a side and a path inside.
    private static let room: RerunSubject = {
        let floor: [SIMD3<Float>] = [SIMD3(-2.4, -1.3, -1), SIMD3(1.9, -1.3, -2.2), SIMD3(2.3, -1.3, 1.6), SIMD3(-1.5, -1.3, 2)]
        let wall: [SIMD3<Float>] = [SIMD3(1.9, 1.1, -2.2), SIMD3(2.3, 1.1, 1.6)]
        let path: [SIMD3<Float>] = [SIMD3(0, 0.1, 0), SIMD3(-1, 0.2, 0.6)]
        return RerunSubject(centre: SIMD3(-0.05, -0.1, -0.1), points: floor + wall + path)
    }()
    /// An iPhone held upright and one held sideways.
    private static let tall: Float = 402.0 / 874
    private static let wide: Float = 874.0 / 402
    /// `chrome-margin` as shares of an upright iPhone's width and height.
    private static let inset = SIMD2<Float>(16.0 / 402, 16.0 / 874)
    private static let slack: Float = 0.002

    /// The rectangle `points` take in the picture `fit` shoots, -1 to 1 across the view each way.
    private static func picture(_ points: [SIMD3<Float>], _ fit: (pose: RerunOrbitPose, lift: Float),
                                aspect: Float) -> (left: Float, right: Float, bottom: Float, top: Float) {
        let (eye, target) = RerunOrbitController(pose: fit.pose).eyeAndTarget(lift: fit.lift, heightPixels: 1000)
        let forward = simd_normalize(target - eye)
        let right = simd_normalize(simd_cross(forward, SIMD3(0, 1, 0)))
        let up = simd_cross(right, forward)
        let tanVertical = tan(RerunFraming.verticalFov * .pi / 360)
        var box = (left: Float.greatestFiniteMagnitude, right: -Float.greatestFiniteMagnitude,
                   bottom: Float.greatestFiniteMagnitude, top: -Float.greatestFiniteMagnitude)
        for point in points {
            let ray = point - eye
            let depth = simd_dot(ray, forward)
            let x = simd_dot(ray, right) / (depth * tanVertical * aspect)
            let y = simd_dot(ray, up) / (depth * tanVertical)
            box = (min(box.left, x), max(box.right, x), min(box.bottom, y), max(box.top, y))
        }
        return box
    }

    /// On a tall window the room's sides are what the camera backs away for: it runs from one
    /// margin to the other, cuts nothing, and sits midway up the band the chrome leaves.
    func testATallWindowFitsTheRoomBetweenItsSides() {
        let fit = RerunFraming.fit(Self.room, azimuth: RerunFraming.homeAzimuth, aspect: Self.tall,
                                   band: 0.68, bandLift: 0.03, inset: Self.inset)
        let box = Self.picture(Self.room.points, fit, aspect: Self.tall)
        let side = 1 - 2 * Self.inset.x
        XCTAssertEqual(box.right, side, accuracy: Self.slack)
        XCTAssertEqual(box.left, -side, accuracy: Self.slack)
        XCTAssertLessThan(box.top, 0.06 + 0.68 - 2 * Self.inset.y)
        XCTAssertGreaterThan(box.bottom, 0.06 - 0.68 + 2 * Self.inset.y)
        XCTAssertEqual((box.top + box.bottom) / 2, 0.06, accuracy: Self.slack)
    }

    /// On a wide window the band is what limits the picture: the room runs from its bottom to
    /// its top less the margin, midway between the sides, which have room to spare.
    func testAWideWindowFitsTheRoomInItsClearBand() {
        let inset = SIMD2<Float>(16.0 / 874, 16.0 / 402)
        let fit = RerunFraming.fit(Self.room, azimuth: RerunFraming.homeAzimuth, aspect: Self.wide,
                                   band: 0.64, bandLift: 0.05, inset: inset)
        let box = Self.picture(Self.room.points, fit, aspect: Self.wide)
        let half = 0.64 - 2 * inset.y
        XCTAssertEqual(box.top, 0.1 + half, accuracy: Self.slack)
        XCTAssertEqual(box.bottom, 0.1 - half, accuracy: Self.slack)
        XCTAssertLessThan(box.right, 1 - 2 * inset.x)
        XCTAssertEqual((box.left + box.right) / 2, 0, accuracy: Self.slack)

        // With the chrome put away the whole height is the room's: the camera comes closer.
        let whole = RerunFraming.fit(Self.room, azimuth: RerunFraming.homeAzimuth, aspect: Self.wide, inset: inset)
        XCTAssertLessThan(whole.pose.distance, fit.pose.distance)
        let all = Self.picture(Self.room.points, whole, aspect: Self.wide)
        XCTAssertEqual(all.top, 1 - 2 * inset.y, accuracy: Self.slack)
        XCTAssertEqual(all.bottom, -(1 - 2 * inset.y), accuracy: Self.slack)
    }

    /// An open settings sheet leaves the top of the view: the room is shot whole above it.
    func testAnOpenSheetLiftsTheRoomAboveIt() {
        let fit = RerunFraming.fit(Self.room, azimuth: RerunFraming.homeAzimuth, aspect: Self.tall,
                                   band: 0.41, bandLift: 0.23, inset: Self.inset)
        let box = Self.picture(Self.room.points, fit, aspect: Self.tall)
        XCTAssertLessThanOrEqual(box.top, 0.46 + 0.41 - 2 * Self.inset.y + Self.slack)
        XCTAssertGreaterThanOrEqual(box.bottom, 0.46 - 0.41 + 2 * Self.inset.y - Self.slack)
        XCTAssertLessThanOrEqual(box.right, 1 - 2 * Self.inset.x + Self.slack)
        XCTAssertGreaterThanOrEqual(box.left, -(1 - 2 * Self.inset.x) - Self.slack)
    }

    /// The turntable shows the room from every side, and the map from above: nothing is ever
    /// cut, and the room always reaches one pair of the rectangle's edges.
    func testTheRoomIsHeldWholeFromEverySide() {
        let side = 1 - 2 * Self.inset.x
        let half = 0.68 - 2 * Self.inset.y
        for elevation in [RerunFraming.homeElevation, RerunFraming.mapElevation] {
            for azimuth in stride(from: Float(0), to: 360, by: 15) {
                let fit = RerunFraming.fit(Self.room, azimuth: azimuth, elevation: elevation, aspect: Self.tall,
                                           band: 0.68, bandLift: 0.03, inset: Self.inset)
                let box = Self.picture(Self.room.points, fit, aspect: Self.tall)
                let across = (box.right - box.left) / (2 * side)
                let upward = (box.top - box.bottom) / (2 * half)
                XCTAssertLessThanOrEqual(max(across, upward), 1 + Self.slack, "azimuth \(azimuth), elevation \(elevation)")
                XCTAssertEqual(max(across, upward), 1, accuracy: Self.slack, "azimuth \(azimuth), elevation \(elevation)")
                XCTAssertEqual((box.left + box.right) / 2, 0, accuracy: Self.slack, "azimuth \(azimuth)")
                XCTAssertEqual((box.top + box.bottom) / 2, 0.06, accuracy: Self.slack, "azimuth \(azimuth)")
            }
        }
    }

    /// A chrome that leaves almost nothing does not send the camera out of the room's reach.
    func testTheBandHasAFloor() {
        let least = RerunFraming.fit(Self.room, azimuth: 0, aspect: Self.wide, band: RerunFraming.minBand)
        let none = RerunFraming.fit(Self.room, azimuth: 0, aspect: Self.wide, band: 0)
        XCTAssertEqual(none.pose.distance, least.pose.distance, accuracy: 0.001)
    }

    /// A take where the phone barely moved is framed as a room would be, not from a hand away.
    func testASmallTakeKeepsARoomSizedView() {
        let dot = RerunSubject(centre: .zero, points: [.zero, SIMD3(0.05, 0, 0)])
        let fit = RerunFraming.fit(dot, azimuth: 0, aspect: Self.tall, inset: Self.inset)
        let side = (1 - 2 * Self.inset.x) * tan(RerunFraming.verticalFov * .pi / 360) * Self.tall
        XCTAssertEqual(fit.pose.distance, RerunFraming.minSubjectRadius / side, accuracy: 0.01)
    }

    /// Before anything is known of the room, the default pose stands, lifted into the band.
    func testNothingToFrameKeepsTheDefaultPose() {
        let fit = RerunFraming.fit(nil, azimuth: 10, aspect: Self.tall, band: 0.68, bandLift: 0.03)
        XCTAssertEqual(fit.pose.distance, RerunFraming.defaultPose.distance)
        XCTAssertEqual(fit.pose.azimuth, 10)
        XCTAssertEqual(fit.lift, 0.03)
    }

    /// The sample session on an iPhone held upright: every wall, the floor and the whole path
    /// land inside the margins — under the title row and above the timeline, above the open
    /// sheet, and in the whole view once the chrome is put away — and fill the width.
    func testTheShowcaseRoomIsFramedWhole() throws {
        let pack = try showcase()
        let subject = try XCTUnwrap(RerunGeometry.subject(pack.trace.frameAt(pack.trace.duration)))
        XCTAssertGreaterThan(subject.points.count, 10)
        let paused = pack.trace.frameAt(pack.trace.duration * RerunShowcaseDemo.qaFraction)
        let drawn = paused.trail + paused.planes.flatMap(\.polygon) + paused.anchors.map(\.pose.position)
        let side = 1 - 2 * Self.inset.x
        for (band, bandLift) in [(Float(0.68), Float(0.03)), (0.41, 0.23), (1, 0)] {
            let fit = RerunFraming.fit(subject, azimuth: RerunFraming.homeAzimuth, aspect: Self.tall,
                                       band: band, bandLift: bandLift, inset: Self.inset)
            let half = band - 2 * Self.inset.y
            for points in [subject.points, drawn] {
                let box = Self.picture(points, fit, aspect: Self.tall)
                XCTAssertLessThanOrEqual(box.right, side + Self.slack, "band \(band)")
                XCTAssertGreaterThanOrEqual(box.left, -side - Self.slack, "band \(band)")
                XCTAssertLessThanOrEqual(box.top, 2 * bandLift + half + Self.slack, "band \(band)")
                XCTAssertGreaterThanOrEqual(box.bottom, 2 * bandLift - half - Self.slack, "band \(band)")
            }
            // The take reaches one pair of the rectangle's edges: it fills it, it does not float.
            let box = Self.picture(subject.points, fit, aspect: Self.tall)
            let filled = max((box.right - box.left) / (2 * side), (box.top - box.bottom) / (2 * half))
            XCTAssertEqual(filled, 1, accuracy: Self.slack, "band \(band)")
        }
        // Paused where the capture is taken, the room already runs across most of the width.
        let fit = RerunFraming.fit(subject, azimuth: RerunFraming.homeAzimuth, aspect: Self.tall,
                                   band: 0.68, bandLift: 0.03, inset: Self.inset)
        let now = Self.picture(drawn, fit, aspect: Self.tall)
        XCTAssertGreaterThan((now.right - now.left) / (2 * side), 0.8)
    }
}

#endif

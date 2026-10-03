// RerunTraceTests.swift
//
// The Rerun showcase's model (`RerunTrace.swift`, `RerunExportAdapter.swift`): the bundled
// session Android ships loads on iOS, the replay's figures only ever grow, the playback
// clock loops, the chrome's wording, and the export scene built from the showcase.

#if DEBUG

import XCTest
import simd
import SwiftUI
#if canImport(UIKit)
import UIKit
#endif
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

    // MARK: Visible framing and camera pause

    func testFramingIncludesEachVisibleLayer() throws {
        let bounds = try XCTUnwrap(RerunGeometry.visibleBounds(
            trajectory: [SIMD3(-2, 0, 0)], featurePoints: [SIMD3(0, 3, 0)],
            denseCloud: [SIMD3(0, 0, -4)], planes: [SIMD3(5, 0, 0)],
            mesh: [SIMD3(0, -6, 7)]))
        XCTAssertEqual(bounds.0, SIMD3(-2, -6, -4))
        XCTAssertEqual(bounds.1, SIMD3(5, 3, 7))
    }

    func testReplayHomeFitsTheBoundsInPortraitAndLandscape() {
        let lo = SIMD3<Float>(-2, 0, -2), hi = SIMD3<Float>(2, 3, 2)
        let radius = simd_length(hi - lo) / 2
        for aspect in [Float(0.5), 2, 0.75] {
            let pose = RerunFraming.home(bounds: (lo, hi), azimuth: 0, aspect: aspect,
                                         margin: RerunFraming.replayMargin)
            let halfVertical = RerunFraming.verticalFov * .pi / 360
            let limitingAngle = min(halfVertical, atan(tan(halfVertical) * aspect))
            XCTAssertGreaterThanOrEqual(pose.distance * sin(limitingAngle), radius)
            XCTAssertEqual(pose.target, (lo + hi) / 2)
        }
    }

    #if os(iOS)
    @MainActor
    func testThemedMarksAndChromeMeetContrastRequirements() {
        func luminance(_ argb: UInt32) -> Double {
            let rgb = [16, 8, 0].map { shift -> Double in
                let c = Double((argb >> shift) & 0xFF) / 255
                return c <= 0.04045 ? c / 12.92 : pow((c + 0.055) / 1.055, 2.4)
            }
            return rgb[0] * 0.2126 + rgb[1] * 0.7152 + rgb[2] * 0.0722
        }
        func contrast(_ a: UInt32, _ b: UInt32) -> Double {
            let x = luminance(a), y = luminance(b)
            return (max(x, y) + 0.05) / (min(x, y) + 0.05)
        }
        // The tokens themselves, resolved per theme: a token that moves is measured again.
        func argb(_ color: Color, dark: Bool) -> UInt32 {
            var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
            UIColor(color).resolvedColor(with: UITraitCollection(userInterfaceStyle: dark ? .dark : .light))
                .getRed(&r, green: &g, blue: &b, alpha: &a)
            func byte(_ c: CGFloat) -> UInt32 { UInt32((min(max(c, 0), 1) * 255).rounded()) }
            return 0xFF00_0000 | byte(r) << 16 | byte(g) << 8 | byte(b)
        }
        typealias Tokens = SceneViewTokens.RoomScan
        for dark in [false, true] {
            let ground = argb(Tokens.background, dark: dark)
            // The stage paints with the same colours the chrome's tokens resolve to.
            XCTAssertEqual(Tokens.primary(dark: dark), argb(Tokens.point, dark: dark))
            XCTAssertEqual(Tokens.ink(dark: dark), argb(Tokens.text, dark: dark))
            let marks = RerunLayer.trailSteps + [.trailHead, .livePoints, .outlineFloor,
                                               .outlineWall, .outlineOther, .gridMinor, .gridMajor]
            for layer in marks {
                XCTAssertGreaterThanOrEqual(contrast(RerunStageRenderer.paint(layer, dark: dark), ground), 3,
                                            "Layer \(layer), dark=\(dark)")
            }
            XCTAssertGreaterThanOrEqual(contrast(SceneViewTokens.RoomScan.primary(dark: dark), ground), 3)
            let card = argb(Tokens.card, dark: dark)
            let text = argb(Tokens.text, dark: dark)
            let secondary = argb(Tokens.secondaryText, dark: dark)
            XCTAssertGreaterThanOrEqual(contrast(text, card), 4.5)
            XCTAssertGreaterThanOrEqual(contrast(secondary, card), 4.5)
            // The filmstrip playhead is ink edged with card: over any photograph one of the two
            // tones holds 3:1 as long as they are at least 9:1 apart.
            XCTAssertGreaterThanOrEqual(contrast(text, card), 9)
            // A hidden layer's ring, and the anchors' ink dot, on the card.
            XCTAssertGreaterThanOrEqual(contrast(secondary, card), 3)
            XCTAssertGreaterThanOrEqual(contrast(argb(Tokens.point, dark: dark), card), 3)
        }
    }

    #endif

    func testHiddenLayersDoNotFrameTheRoom() throws {
        var frame = RerunTrace().frameAt(0)
        frame.trail = [SIMD3(-10, 0, 0)]
        frame.mapPoints = [SIMD3(20, 0, 0)][...]
        frame.livePoints = [SIMD3(30, 0, 0)]
        frame.planes = [RerunPlane(id: 1, kind: .wall, polygon: [SIMD3(40, 0, 0)])]
        frame.anchors = [RerunAnchor(id: 1, pose: RerunPose(position: SIMD3(50, 0, 0)), placedAt: 0)]
        for shown in RerunGroup.allCases {
            let hidden = Set(RerunGroup.allCases.filter { $0 != shown })
            let bounds = try XCTUnwrap(RerunGeometry.contentBounds(frame, hidden: hidden))
            switch shown {
            case .trail: XCTAssertEqual(bounds.0.x, -10); XCTAssertEqual(bounds.1.x, -10)
            case .points: XCTAssertEqual(bounds.0.x, 20); XCTAssertEqual(bounds.1.x, 30)
            case .planes: XCTAssertEqual(bounds.0.x, 40); XCTAssertEqual(bounds.1.x, 40)
            case .anchors: XCTAssertEqual(bounds.0.x, 49.7, accuracy: 0.001)
                          XCTAssertEqual(bounds.1.x, 50.3, accuracy: 0.001)
            }
        }
        XCTAssertNil(RerunGeometry.contentBounds(frame, hidden: Set(RerunGroup.allCases)))
    }

    func testCloudOutliersDoNotShrinkTheRoomAndCannotHideStructuredGeometry() throws {
        let room = (0..<100).map { SIMD3<Float>(Float($0 % 10), Float($0 / 10), 0) }
        let stray = [SIMD3<Float>(-10_000, -10_000, -10_000), SIMD3(10_000, 10_000, 10_000)]
        for dense in [false, true] {
            let bounds = try XCTUnwrap(RerunGeometry.visibleBounds(
                featurePoints: dense ? [] : room + stray,
                denseCloud: dense ? room + stray : [], planes: [SIMD3(12, 0, 0)]))
            XCTAssertEqual(bounds.0, .zero)
            XCTAssertEqual(bounds.1, SIMD3(12, 9, 0))
        }
    }

    func testEmptyAndNonFiniteRecordingHasNoBounds() {
        XCTAssertNil(RerunGeometry.contentBounds(RerunTrace().frameAt(0)))
        XCTAssertNil(RerunGeometry.visibleBounds(featurePoints: [SIMD3(.nan, 0, 0)],
                                                mesh: [SIMD3(0, .infinity, 0)]))
    }

    func testCameraPauseRuleForButtonScrubEndAndBackground() {
        var playback = RerunPlayback(duration: 1)
        XCTAssertFalse(playback.cameraAdvances)
        playback.playFromStart()
        XCTAssertTrue(playback.cameraAdvances)
        playback.togglePlay() // Button
        XCTAssertFalse(playback.cameraAdvances)
        playback.togglePlay()
        playback.scrub(to: 0.5)
        XCTAssertFalse(playback.cameraAdvances)
        playback.playFromStart()
        playback.pause() // App inactive/background
        XCTAssertFalse(playback.cameraAdvances)
        playback.playFromStart()
        for _ in 0..<4 { playback.tick(0.25) }
        XCTAssertEqual(playback.cursor, playback.duration)
        XCTAssertTrue(playback.cameraAdvances, "The looping end hold is not a pause")
        var held: Float = 0
        while playback.cursor == playback.duration {
            XCTAssertTrue(playback.cameraAdvances, "The camera keeps moving through the whole hold")
            playback.tick(0.25)
            held += 0.25
        }
        XCTAssertEqual(held, RerunPlayback.loopHold)
        XCTAssertEqual(playback.cursor, 0)
        XCTAssertTrue(playback.cameraAdvances)
        for _ in 0..<4 { playback.tick(0.25) }
        playback.loops = false
        playback.tick(0.1)
        XCTAssertFalse(playback.playing)
        XCTAssertFalse(playback.cameraAdvances)
    }

    func testPausedOrbitHoldsForFiveSecondsButAllowsGesturesAndResumesContinuously() {
        var orbit = RerunOrbitController()
        orbit.playIntro(from: RerunIntro.start(for: orbit.home))
        orbit.update(delta: 0.05)
        let paused = orbit.pose
        for _ in 0..<100 { orbit.update(delta: 0.05, advancing: false) }
        XCTAssertEqual(orbit.pose, paused)
        orbit.update(delta: 0.05)
        XCTAssertEqual(orbit.pose, RerunIntro.pose(from: RerunIntro.start(for: orbit.home),
                                                to: orbit.home, progress: 0.1 / RerunIntro.duration))
        orbit.dragBegan()
        orbit.dragged(dx: 10, dy: 0)
        orbit.dragEnded()
        let dragged = orbit.pose
        XCTAssertNotEqual(dragged, paused)
        for _ in 0..<100 { orbit.update(delta: 0.05, advancing: false) }
        XCTAssertEqual(orbit.pose, dragged)
        orbit.update(delta: 0.05)
        XCTAssertEqual(orbit.pose, dragged, "Resuming must not resurrect gesture inertia")
        orbit.pinched(magnification: 2)
        XCTAssertLessThan(orbit.pose.distance, dragged.distance)
    }

    func testPausedOrbitNeverDriftsButStillReframes() {
        var orbit = RerunOrbitController()
        XCTAssertTrue(orbit.drift)
        for _ in 0..<40 { orbit.update(delta: 0.05) }
        XCTAssertNotEqual(orbit.pose.azimuth, RerunFraming.homeAzimuth, "The turntable drifts while playing")

        // Pause: still from the first frame, with no glide after the drift.
        let held = orbit.pose
        for _ in 0..<100 { orbit.update(delta: 0.05, advancing: false) }
        XCTAssertEqual(orbit.pose, held)

        // A rotation or a layer toggle moves home: the paused camera eases there.
        var wider = orbit.home
        wider.distance *= 2
        wider.target.x += 1
        orbit.home = wider
        orbit.update(delta: 0.05, advancing: false)
        XCTAssertGreaterThan(orbit.pose.distance, held.distance)
        XCTAssertLessThan(orbit.pose.distance, wider.distance)
        for _ in 0..<100 { orbit.update(delta: 0.05, advancing: false) }
        XCTAssertEqual(orbit.pose, orbit.home)
        XCTAssertEqual(orbit.pose.distance, wider.distance)
        XCTAssertEqual(orbit.pose.azimuth, held.azimuth, "Reframing while paused does not turn the room")

        // A drag, then the double tap: back on the framing, from the angle the finger left.
        orbit.dragBegan()
        orbit.dragged(dx: 40, dy: 60)
        orbit.dragEnded()
        orbit.update(delta: 0.05, advancing: false)
        let dragged = orbit.pose
        XCTAssertNotEqual(dragged.elevation, wider.elevation)
        orbit.recenter()
        for _ in 0..<100 { orbit.update(delta: 0.05, advancing: false) }
        XCTAssertEqual(orbit.pose.elevation, wider.elevation)
        XCTAssertEqual(orbit.pose.azimuth, dragged.azimuth)

        // The map view, asked for while paused.
        orbit.overhead = true
        orbit.home.elevation = orbit.homeElevation
        for _ in 0..<100 { orbit.update(delta: 0.05, advancing: false) }
        XCTAssertEqual(orbit.pose.elevation, RerunFraming.mapElevation)
        orbit.overhead = false
        orbit.home.elevation = orbit.homeElevation
        for _ in 0..<100 { orbit.update(delta: 0.05, advancing: false) }
        XCTAssertEqual(orbit.pose.elevation, RerunFraming.homeElevation)

        // Resuming ramps the drift back in instead of lurching.
        let resumed = orbit.pose
        orbit.update(delta: 0.05)
        let turned = abs(RerunOrbitPose.shortestDelta(resumed.azimuth, orbit.pose.azimuth))
        XCTAssertLessThan(turned, RerunOrbitController.driftDegreesPerSecond * 0.05)
        for _ in 0..<60 { orbit.update(delta: 0.05) }
        XCTAssertNotEqual(orbit.pose.azimuth, resumed.azimuth)
    }

    func testRecenterEndsTheEntranceEvenWhilePaused() {
        var orbit = RerunOrbitController()
        orbit.playIntro(from: RerunIntro.start(for: orbit.home))
        orbit.update(delta: 0.05)
        XCTAssertTrue(orbit.introPlaying)
        orbit.recenter()
        XCTAssertFalse(orbit.introPlaying)
        for _ in 0..<100 { orbit.update(delta: 0.05, advancing: false) }
        XCTAssertEqual(orbit.pose, orbit.home)
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
}

#endif

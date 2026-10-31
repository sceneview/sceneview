import XCTest
import Combine
import simd
@testable import SceneViewSwift

#if os(iOS) || os(macOS) || os(visionOS)
/// Keyframed camera paths — the Swift port of Android's `CameraPosePath` /
/// `CameraPathPlayer` tests: interpolation, easing, loop wrap, look-at, presets, player.
@MainActor
final class CameraPathTests: XCTestCase {

    private func assertClose(_ a: SIMD3<Float>, _ b: SIMD3<Float>, accuracy: Float = 1e-4,
                             file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertLessThanOrEqual(simd_length(a - b), accuracy, "\(a) != \(b)", file: file, line: line)
    }

    private func key(_ eye: SIMD3<Float>, _ seconds: Float, target: SIMD3<Float> = .zero,
                     easing: CameraEasing? = nil) -> CameraPoseKeyframe {
        CameraPoseKeyframe(pose: .lookingAt(eye: eye, target: target), seconds: seconds, easing: easing)
    }

    // MARK: - CameraPose

    func testLookingAtFacesTheTargetWithUpKeptUp() {
        let pose = CameraPose.lookingAt(eye: [3, 2, 4], target: [0, 1, 0])
        assertClose(pose.forward, simd_normalize(SIMD3<Float>(-3, -1, -4)))
        XCTAssertEqual(pose.right.y, 0, accuracy: 1e-5, "no roll: right stays horizontal")
        XCTAssertGreaterThan(pose.up.y, 0)
    }

    func testLookingAtStraightDownAndOnTheEyeStayDefined() {
        let down = CameraPose.lookingAt(eye: [0, 5, 0], target: .zero)
        assertClose(down.forward, [0, -1, 0])
        XCTAssertTrue(down.quaternion.vector.x.isFinite)
        let same = CameraPose.lookingAt(eye: [1, 1, 1], target: [1, 1, 1])
        assertClose(same.forward, [0, 0, -1])
    }

    // MARK: - Interpolation

    func testKeysAreSortedAndNonFiniteTimesDropped() {
        let path = CameraPosePath(keyframes: [key([2, 0, 0], 4), key([0, 0, 2], 1),
                                              key([9, 9, 9], .nan), key([1, 0, 1], 2)],
                                  interpolation: .linear)
        XCTAssertEqual(path.keyframes.map(\.seconds), [1, 2, 4])
        XCTAssertEqual(path.durationSeconds, 3)
        assertClose(path.poseAt(0).position, [0, 0, 2], accuracy: 1e-6) // time counts from the first key
        assertClose(path.poseAt(3).position, [2, 0, 0], accuracy: 1e-6)
    }

    func testLinearInterpolatesPositionAndSlerpsRotation() {
        let a = CameraPose(position: [0, 0, 0])
        let b = CameraPose(position: [4, 0, 0], quaternion: simd_quatf(angle: .pi / 2, axis: [0, 1, 0]))
        let path = CameraPosePath(keyframes: [CameraPoseKeyframe(pose: a, seconds: 0),
                                              CameraPoseKeyframe(pose: b, seconds: 2)],
                                  interpolation: .linear)
        let mid = path.poseAt(1)
        assertClose(mid.position, [2, 0, 0])
        XCTAssertEqual(mid.quaternion.angle, .pi / 4, accuracy: 1e-4)
        assertClose(path.poseAt(0.5).position, [1, 0, 0])
    }

    func testCatmullRomPassesThroughEveryKeyAndCurvesBetween() {
        let eyes: [SIMD3<Float>] = [[0, 0, 4], [4, 1, 0], [0, 2, -4], [-4, 1, 0]]
        let keys = eyes.enumerated().map { key($0.element, Float($0.offset) * 2) }
        let smooth = CameraPosePath(keyframes: keys, interpolation: .catmullRom)
        for (index, eye) in eyes.enumerated() {
            assertClose(smooth.poseAt(Float(index) * 2).position, eye, accuracy: 1e-5)
            // Just either side of the key the curve is continuous.
            if index > 0 { assertClose(smooth.poseAt(Float(index) * 2 - 1e-3).position, eye, accuracy: 0.02) }
        }
        let straight = CameraPosePath(keyframes: keys, interpolation: .linear)
        XCTAssertGreaterThan(simd_length(smooth.poseAt(3).position - straight.poseAt(3).position), 0.1,
                             "Catmull-Rom bends where linear cuts the corner")
    }

    func testCatmullRomStopsAtATurnBack() {
        // There and back: the tangent at the far key is zero, so the camera never overshoots it.
        let path = CameraPosePath(keyframes: [key([0, 0, 4], 0), key([4, 0, 4], 1), key([0, 0, 4], 2)])
        for step in 0...20 {
            XCTAssertLessThanOrEqual(path.poseAt(Float(step) / 10).position.x, 4 + 1e-4)
        }
    }

    func testRotationsTakeTheShortestWay() {
        let q = simd_quatf(angle: 0.3, axis: [0, 1, 0])
        let path = CameraPosePath(keyframes: [
            CameraPoseKeyframe(pose: CameraPose(position: .zero, quaternion: q), seconds: 0),
            CameraPoseKeyframe(pose: CameraPose(position: [1, 0, 0], quaternion: simd_quatf(vector: -q.vector)), seconds: 1),
        ], interpolation: .linear)
        // -q is the same rotation: the midpoint must not spin the long way round.
        assertClose(path.poseAt(0.5).forward, CameraPose(quaternion: q).forward)
    }

    // MARK: - Easing

    func testEasingCurvesArePinnedAndShaped() {
        for easing in [CameraEasing.linear, .inOut, .expressive] {
            XCTAssertEqual(easing(0), 0, accuracy: 1e-5)
            XCTAssertEqual(easing(1), 1, accuracy: 1e-5)
        }
        XCTAssertEqual(CameraEasing.inOut(0.5), 0.5, accuracy: 1e-5)
        XCTAssertLessThan(CameraEasing.inOut(0.25), 0.25)
        XCTAssertGreaterThan(CameraEasing.expressive(0.5), 0.8, "fast out, gentle settle")
        XCTAssertEqual(CameraEasing.cubicBezier(0.25, 0.25, 0.75, 0.75)(0.3), 0.3, accuracy: 1e-3)
    }

    func testSpanEasingAndPerKeyEasing() {
        let eased = CameraPosePath(keyframes: [key([0, 0, 4], 0), key([4, 0, 4], 4)],
                                   interpolation: .linear, easing: .inOut)
        XCTAssertLessThan(eased.poseAt(1).position.x, 1, "slow out")
        XCTAssertEqual(eased.poseAt(2).position.x, 2, accuracy: 1e-4)

        let perKey = CameraPosePath(keyframes: [key([0, 0, 4], 0, easing: .expressive), key([4, 0, 4], 4)],
                                    interpolation: .linear, easing: .linear)
        XCTAssertGreaterThan(perKey.poseAt(2).position.x, 3, "the key's own easing wins")

        let overshoot = CameraPosePath(keyframes: [key([0, 0, 4], 0, easing: CameraEasing { _ in 7 }),
                                                   key([4, 0, 4], 4)], interpolation: .linear)
        XCTAssertEqual(overshoot.poseAt(1).position.x, 4, accuracy: 1e-5, "eased output is clamped to 0...1")
    }

    func testPathEasingRetimesTheWholePath() {
        let keys = [key([0, 0, 4], 0), key([2, 0, 4], 2), key([4, 0, 4], 4)]
        let path = CameraPosePath(keyframes: keys, interpolation: .linear, pathEasing: .inOut)
        XCTAssertLessThan(path.poseAt(1).position.x, 1)
        XCTAssertEqual(path.poseAt(2).position.x, 2, accuracy: 1e-4)
        XCTAssertGreaterThan(path.poseAt(3).position.x, 3)
    }

    // MARK: - Loop and clamp

    func testEmptyPathSamplesTheIdentityPose() {
        let path = CameraPosePath(keyframes: [])
        XCTAssertEqual(path.durationSeconds, 0)
        XCTAssertNil(path.start)
        XCTAssertNil(path.end)
        XCTAssertEqual(path.poseAt(5), CameraPose())
        let player = CameraPathPlayer(timeline: path)
        player.play()
        player.advance(1)
        XCTAssertEqual(player.pose, CameraPose())
        XCTAssertEqual(player.progress, 0)
    }

    func testTwoKeysAtTheSameTimeAreACut() {
        let keys = [key([0, 0, 4], 0), key([4, 0, 4], 1), key([8, 0, 4], 1), key([8, 0, 0], 2)]
        for interpolation in [CameraPathInterpolation.linear, .catmullRom] {
            let path = CameraPosePath(keyframes: keys, interpolation: interpolation)
            XCTAssertEqual(path.keyframes.map(\.pose.position.x), [0, 4, 8, 8], "the tie keeps its order")
            assertClose(path.poseAt(1 - 1e-3).position, [4, 0, 4], accuracy: 0.02)
            assertClose(path.poseAt(1).position, [8, 0, 4], accuracy: 1e-5)
            assertClose(path.poseAt(1 + 1e-3).position, [8, 0, 4], accuracy: 0.02)
            for step in 0...40 {
                let p = path.poseAt(Float(step) / 20).position
                XCTAssertTrue(p.x.isFinite && p.y.isFinite && p.z.isFinite, "\(interpolation) at \(step)")
            }
        }
    }

    func testCatmullRomTangentIsCappedAtThreeTimesTheSlowerNeighbour() {
        // 0.1 u/s in, 9.9 u/s out: the central difference (5 u/s) would overshoot the slow span.
        let path = CameraPosePath(keyframes: [key([0, 0, 4], 0), key([0.1, 0, 4], 1), key([10, 0, 4], 2)],
                                  interpolation: .catmullRom)
        XCTAssertEqual(simd_length(path.tangents[1]), 0.3, accuracy: 1e-5)
        XCTAssertGreaterThan(path.tangents[1].x, 0)
        for step in 0...10 {
            let x = path.poseAt(Float(step) / 10).position.x
            XCTAssertGreaterThanOrEqual(x, -1e-5)
            XCTAssertLessThanOrEqual(x, 0.1 + 1e-4, "the slow span never overshoots its far key")
        }
    }

    func testHugeTimesSaturateInsteadOfTrapping() {
        let orbit = CameraPosePath.orbit(center: .zero, extents: [1, 1, 1], aspect: 1,
                                         durationSeconds: 6, turns: 0.5)
        for t: Float in [1e21, -1e21, .greatestFiniteMagnitude, -.greatestFiniteMagnitude] {
            let p = orbit.poseAt(t).position
            XCTAssertTrue(p.x.isFinite && p.y.isFinite && p.z.isFinite, "poseAt(\(t))")
        }
        XCTAssertEqual(CameraPosePath.saturatingInt64(1e30), .max)
        XCTAssertEqual(CameraPosePath.saturatingInt64(-1e30), .min)
        XCTAssertEqual(CameraPosePath.saturatingInt64(.nan), 0)
        XCTAssertEqual(CameraPosePath.saturatingInt64(-3.7), -3)
    }

    func testLoopWrapsAndNonLoopClamps() {
        let keys = [key([0, 0, 4], 0), key([4, 0, 4], 2), key([0, 0, 4], 4)]
        let looping = CameraPosePath(keyframes: keys, interpolation: .linear, loop: true)
        assertClose(looping.poseAt(5).position, looping.poseAt(1).position)
        assertClose(looping.poseAt(-1).position, looping.poseAt(3).position)
        assertClose(looping.poseAt(.infinity).position, looping.poseAt(0).position)

        let once = CameraPosePath(keyframes: keys, interpolation: .linear)
        assertClose(once.poseAt(9).position, [0, 0, 4])
        assertClose(once.poseAt(-3).position, [0, 0, 4])
        XCTAssertTrue(once.isFinished(at: 4))
        XCTAssertFalse(looping.isFinished(at: 40))
    }

    func testClosedLoopIsSmoothAcrossTheSeam() {
        let eyes: [SIMD3<Float>] = [[0, 0, 4], [4, 0, 0], [0, 0, -4], [-4, 0, 0], [0, 0, 4]]
        let keys = eyes.enumerated().map { key($0.element, Float($0.offset)) }
        let path = CameraPosePath(keyframes: keys, loop: true)
        let before = path.poseAt(4 - 1e-3).position
        let after = path.poseAt(1e-3).position
        // The seam key gets the central tangent (+X): an open path would leave it heading
        // diagonally in and out, and z would dip on both sides of the key.
        XCTAssertEqual(before.z, 4, accuracy: 5e-4)
        XCTAssertEqual(after.z, 4, accuracy: 5e-4)
        XCTAssertEqual(before.x, -after.x, accuracy: 1e-4)
        XCTAssertGreaterThan(after.x, 1e-3)
        let open = CameraPosePath(keyframes: keys, loop: false)
        XCTAssertLessThan(open.poseAt(1e-3).position.z, 4 - 1e-3)
    }

    // MARK: - Look-at

    func testLookAtAimsEverySample() {
        let target = SIMD3<Float>(0, 0.5, 0)
        let keys = [key([0, 1, 4], 0, target: [9, 9, 9]), key([4, 2, 0], 3, target: [9, 9, 9])]
        let path = CameraPosePath(keyframes: keys, lookAt: target)
        for t: Float in [0, 0.7, 1.5, 2.9, 3] {
            let pose = path.poseAt(t)
            assertClose(pose.forward, simd_normalize(target - pose.position))
            XCTAssertEqual(pose.right.y, 0, accuracy: 1e-5)
        }
        assertClose(path.start!.forward, simd_normalize(target - [0, 1, 4]))
    }

    // MARK: - Presets

    func testOrbitStartsAtTheAzimuthAndClosesOnAWholeTurn() {
        let center = SIMD3<Float>(1, 0.5, -1)
        let path = CameraPosePath.orbit(center: center, extents: [1, 1, 1], aspect: 0.5,
                                        durationSeconds: 16, elevationDegrees: 18, startAzimuthDegrees: 30)
        var fit = CameraControls()
        fit.elevation = 18 * .pi / 180
        fit.minRadius = 0.01
        fit.maxRadius = .greatestFiniteMagnitude
        let radius = fit.fitRadius(boundsExtents: [1, 1, 1], fovYDegrees: 60, aspect: 0.5)
        let expected = SceneCameraPose(azimuth: 30 * .pi / 180, elevation: 18 * .pi / 180,
                                       distance: radius, target: center).cameraPosition()
        XCTAssertTrue(path.loop)
        XCTAssertEqual(path.durationSeconds, 16)
        XCTAssertEqual(path.keyframes.count, 33)
        assertClose(path.start!.position, expected)
        assertClose(path.end!.position, expected)
        assertClose(path.poseAt(16).position, path.poseAt(0).position)
        for t: Float in [0, 3.3, 8, 12.1] {
            let pose = path.poseAt(t)
            XCTAssertEqual(simd_distance(pose.position, center), radius, accuracy: radius * 0.01)
            assertClose(pose.forward, simd_normalize(center - pose.position), accuracy: 1e-3)
        }
        // A quarter of the way round, positive turns head toward +X then -Z: azimuth 120°.
        let quarter = SceneCameraPose(azimuth: 120 * .pi / 180, elevation: 18 * .pi / 180,
                                      distance: radius, target: center).cameraPosition()
        assertClose(path.poseAt(4).position, quarter, accuracy: 1e-3)
    }

    func testFractionalOrbitCarriesOnAcrossLaps() {
        let path = CameraPosePath.orbit(center: .zero, extents: [1, 1, 1], aspect: 1,
                                        durationSeconds: 6, turns: 0.5)
        let player = CameraPathPlayer(timeline: path)
        player.play()
        player.advance(5.999)
        let endOfLap = player.pose.position
        player.advance(0.002)
        assertClose(player.pose.position, endOfLap, accuracy: 0.01)
        XCTAssertGreaterThan(simd_distance(player.pose.position, path.poseAt(0).position), 0.5,
                             "the second lap starts where the first ended, not at the start")
    }

    func testTravellingSlidesAlongRightAndHoldsRotation() {
        let from = CameraPose.lookingAt(eye: [0, 1, 4], target: [0, 0.5, 0])
        let path = CameraPosePath.travelling(from: from, distance: 2, durationSeconds: 5)
        XCTAssertEqual(path.interpolation, .linear)
        XCTAssertFalse(path.loop)
        assertClose(path.start!.position, from.position)
        assertClose(path.end!.position, from.position + from.right * 2)
        assertClose(path.poseAt(2.5).position, from.position + from.right, accuracy: 1e-3)
        assertClose(path.poseAt(3.7).forward, from.forward)
    }

    func testDollyEndsAtTheDistanceAndKeepsAiming() {
        let target = SIMD3<Float>(0, 0.5, 0)
        let path = CameraPosePath.dolly(target: target, from: [0, 1.5, 6], toDistance: 2, durationSeconds: 4)
        assertClose(path.start!.position, [0, 1.5, 6])
        XCTAssertEqual(simd_distance(path.end!.position, target), 2, accuracy: 1e-4)
        assertClose(path.end!.forward, simd_normalize(target - path.end!.position))
        let tooClose = CameraPosePath.dolly(target: target, from: [0, 0.5, 3], toDistance: -1, durationSeconds: 1)
        XCTAssertEqual(simd_distance(tooClose.end!.position, target), CameraPosePath.minDistance, accuracy: 1e-5)
    }

    // MARK: - Player

    private var line: CameraPosePath {
        CameraPosePath(keyframes: [key([0, 0, 4], 0), key([10, 0, 4], 10)], interpolation: .linear)
    }

    func testPlayerOnlyMovesWhenPlaying() {
        let player = CameraPathPlayer(timeline: line)
        player.advance(1)
        XCTAssertEqual(player.seconds, 0)
        player.play()
        player.advance(1.5)
        XCTAssertEqual(player.seconds, 1.5, accuracy: 1e-5)
        XCTAssertEqual(player.pose.position.x, 1.5, accuracy: 1e-4)
        XCTAssertEqual(player.progress, 0.15, accuracy: 1e-5)
        player.pause()
        player.advance(3)
        XCTAssertEqual(player.seconds, 1.5, accuracy: 1e-5)
        player.toggle()
        XCTAssertTrue(player.isPlaying)
        player.advance(-1)
        player.advance(.nan)
        XCTAssertEqual(player.seconds, 1.5, accuracy: 1e-5, "negative and non-finite steps move nothing")
    }

    func testPlayerSpeedScalesAndReverses() {
        let player = CameraPathPlayer(timeline: line)
        player.speed = 2
        player.play()
        player.advance(1)
        XCTAssertEqual(player.seconds, 2, accuracy: 1e-5)
        player.speed = .infinity
        XCTAssertEqual(player.speed, 2, "a non-finite speed is ignored")
        player.speed = -1
        player.advance(5)
        XCTAssertEqual(player.seconds, 0)
        XCTAssertFalse(player.isPlaying, "a reversed playback stops at the start")
        XCTAssertTrue(player.isFinished)
        player.play()
        XCTAssertEqual(player.seconds, 10, "playing a finished reverse playback restarts from the end")
    }

    func testPlayerStopsAtTheEndAndPlayRestarts() {
        let player = CameraPathPlayer(timeline: line)
        player.play()
        player.advance(25)
        XCTAssertEqual(player.seconds, 10)
        XCTAssertFalse(player.isPlaying)
        XCTAssertTrue(player.isFinished)
        player.play()
        XCTAssertEqual(player.seconds, 0)
        XCTAssertTrue(player.isPlaying)
        player.advance(3)
        player.restart()
        XCTAssertEqual(player.seconds, 0)
    }

    func testPlayerLoopWraps() {
        let player = CameraPathPlayer(timeline: line, loop: true)
        player.play()
        player.advance(23)
        XCTAssertEqual(player.seconds, 3, accuracy: 1e-4)
        XCTAssertTrue(player.isPlaying)
        XCTAssertFalse(player.isFinished)
    }

    func testPlayerSeekClampsOrWrapsAndWorksWhilePaused() {
        let player = CameraPathPlayer(timeline: line)
        player.seek(to: 4)
        XCTAssertEqual(player.seconds, 4)
        XCTAssertEqual(player.pose.position.x, 4, accuracy: 1e-4)
        player.seek(to: 14)
        XCTAssertEqual(player.seconds, 10)
        player.seek(to: .nan)
        XCTAssertEqual(player.seconds, 10)
        player.loop = true
        player.seek(to: 14)
        XCTAssertEqual(player.seconds, 4, accuracy: 1e-4)
        player.seek(to: -1)
        XCTAssertEqual(player.seconds, 9, accuracy: 1e-4)
    }

    func testPlayerFrameClock() {
        let player = CameraPathPlayer(timeline: line)
        player.play()
        player.advanceFrame(at: 100)
        XCTAssertEqual(player.seconds, 0, "the first frame only starts the clock")
        player.advanceFrame(at: 100.5)
        XCTAssertEqual(player.seconds, 0.5, accuracy: 1e-5)
        player.advanceFrame(at: 99)
        XCTAssertEqual(player.seconds, 0.5, accuracy: 1e-5, "a clock going backwards moves nothing")
        player.pause()
        player.play()
        player.advanceFrame(at: 200)
        XCTAssertEqual(player.seconds, 0.5, accuracy: 1e-5, "resume does not jump by the paused time")
    }

    func testPlayerOnASinglePoseTimeline() {
        let player = CameraPathPlayer(timeline: CameraPosePath(keyframes: [key([1, 2, 3], 5)]))
        XCTAssertEqual(player.durationSeconds, 0)
        player.play()
        player.advance(1)
        XCTAssertFalse(player.isPlaying)
        assertClose(player.pose.position, [1, 2, 3])
        XCTAssertEqual(player.progress, 0)
    }

    func testSwappingTheTimelineKeepsThePlayhead() {
        let player = CameraPathPlayer(timeline: line)
        player.seek(to: 8)
        player.timeline = CameraPosePath(keyframes: [key([0, 0, 4], 0), key([1, 0, 4], 5)], loop: true)
        XCTAssertEqual(player.durationSeconds, 5)
        XCTAssertTrue(player.loop)
        XCTAssertEqual(player.seconds, 3, accuracy: 1e-4)
    }

    func testAHugeSpeedSaturatesTheLapCount() {
        let orbit = CameraPosePath.orbit(center: .zero, extents: [1, 1, 1], aspect: 1,
                                         durationSeconds: 6, turns: 0.5)
        let player = CameraPathPlayer(timeline: orbit)
        player.speed = 1e25
        player.play()
        for _ in 0..<3 { player.advance(1.0 / 60) }
        player.speed = -1e25
        for _ in 0..<3 { player.advance(1.0 / 60) }
        XCTAssertTrue((0...player.durationSeconds).contains(player.seconds))
        let p = player.pose.position
        XCTAssertTrue(p.x.isFinite && p.y.isFinite && p.z.isFinite)
    }

    func testANonFiniteSpeedPublishesNothing() {
        let player = CameraPathPlayer(timeline: line)
        var changes = 0
        let watch = player.objectWillChange.sink { changes += 1 }
        player.speed = .nan
        player.speed = -.infinity
        XCTAssertEqual(player.speed, 1)
        XCTAssertEqual(changes, 0)
        player.speed = 2
        XCTAssertEqual(changes, 1)
        watch.cancel()
    }

    func testSwappingTheTimelinePublishesTheNewPose() {
        let player = CameraPathPlayer(timeline: line)
        player.seek(to: 2)
        let before = player.pose.position
        var changes = 0
        let watch = player.objectWillChange.sink { changes += 1 }
        // Same length, still paused at the same playhead: only the pose changes.
        player.timeline = CameraPosePath(keyframes: [key([0, 3, 4], 0), key([10, 3, 4], 10)],
                                         interpolation: .linear)
        XCTAssertGreaterThan(changes, 0, "a timeline swap must reach SwiftUI")
        XCTAssertEqual(player.seconds, 2, accuracy: 1e-5)
        XCTAssertEqual(player.durationSeconds, 10)
        XCTAssertGreaterThan(simd_distance(player.pose.position, before), 1)
        watch.cancel()
    }

    // MARK: - SceneView interop

    func testSceneCameraPoseFollowsAFreePoseExactly() {
        let pose = CameraPose.lookingAt(eye: [1.2, 1.4, 3.1], target: [0, 0.3, 0])
        let orbit = SceneCameraPose(pose, focusDistance: 2.5)
        assertClose(orbit.cameraPosition(), pose.position)
        assertClose(simd_normalize(orbit.target - pose.position), pose.forward)
        XCTAssertEqual(orbit.distance, 2.5)

        var controls = CameraControls()
        controls.follow(pose, focusDistance: 2.5)
        assertClose(controls.cameraPosition(), pose.position)
        assertClose(controls.lookForward(), pose.forward)
        assertClose(controls.cameraPose().forward, pose.forward)
        assertClose(controls.cameraPose().position, pose.position)
    }

    func testAZeroQuaternionFollowsAsTheIdentity() {
        let pose = CameraPose(position: [1, 2, 3], quaternion: simd_quatf(vector: .zero))
        let orbit = SceneCameraPose(pose, focusDistance: 2)
        XCTAssertTrue(orbit.azimuth.isFinite && orbit.elevation.isFinite)
        assertClose(orbit.cameraPosition(), [1, 2, 3], accuracy: 1e-4)
        assertClose(orbit.target, [1, 2, 1], accuracy: 1e-4)
    }

    func testFollowingAPathFrameByFrameMatchesThePath() {
        let center = SIMD3<Float>(0, 0.4, 0)
        let path = CameraPosePath.orbit(center: center, extents: [2, 0.8, 1], aspect: 0.5, durationSeconds: 8)
        var controls = CameraControls()
        for t in stride(from: Float(0), through: 8, by: 0.25) {
            let pose = path.poseAt(t)
            controls.follow(pose, focusDistance: simd_distance(pose.position, center))
            assertClose(controls.cameraPosition(), pose.position, accuracy: 1e-3)
            assertClose(controls.target, center, accuracy: 1e-3)
        }
    }
}
#endif

#if os(iOS)
import XCTest
import RealityKit
@testable import SceneViewSwift

/// How a placed model moves under the fingers, device-free: ARMV's constants and transitions
/// (drag smoothing, glide, twist coast, double tap, grow-in, recenter facing).
@MainActor
final class ARPlacementFeelTests: XCTestCase {

    // MARK: Constants shared with ARMV

    func testConstantsMatchARMV() {
        XCTAssertEqual(ARPlacementFeel.dragSpeed, 18)
        XCTAssertEqual(ARPlacementFeel.appearScale, 0.01)
        XCTAssertEqual(ARInstantPlacement.settleSpeed, 6)

        let move = ARPlacementFeel.moveCoast()
        XCTAssertEqual(move.dimensions, 2)
        XCTAssertEqual(move.minSpeed, 8)
        XCTAssertEqual(move.maxSpeed, 4000)
        XCTAssertEqual(move.minReleaseSpeed, 150)

        let twist = ARPlacementFeel.twistCoast()
        XCTAssertEqual(twist.dimensions, 1)
        XCTAssertEqual(twist.minSpeed, 5)
        XCTAssertEqual(twist.maxSpeed, 900)
        XCTAssertEqual(twist.minReleaseSpeed, 45)

        XCTAssertEqual(ARInertiaTracker.timeConstant, 0.35)
        XCTAssertEqual(ARInertiaTracker.velocityWindow, 0.1)
        XCTAssertEqual(ARInertiaTracker.stoppedGap, 0.04)
        XCTAssertEqual(ARInertiaTracker.minWindow, 0.016)
        XCTAssertEqual(ARInertiaTracker.capacity, 20)
    }

    func testPinchDetentAndDoubleTapConstantsMatchScaleSnap() {
        XCTAssertEqual(ARScaleSnap.min, 0.25)
        XCTAssertEqual(ARScaleSnap.max, 4)
        XCTAssertEqual(ARScaleSnap.window, 0.04)
        XCTAssertEqual(ARScaleSnap.reboundAmplitude, 0.05)
        XCTAssertEqual(ARScaleSnap.reboundDecayMs, 80)
        XCTAssertEqual(ARScaleSnap.reboundPeriodMs, 160)
        XCTAssertEqual(ARScaleSnap.reboundMs, 280)
        XCTAssertEqual(ARScaleSnap.doubleTapMs, 260)
        // The magnetic detent: ±4 % pulls to exactly 100 % and ticks once on entry.
        XCTAssertEqual(ARScaleSnap.step(previousDisplayed: 1.2, raw: 1.039).displayed, 1)
        XCTAssertTrue(ARScaleSnap.step(previousDisplayed: 1.2, raw: 1.039).enteredSnap)
        XCTAssertFalse(ARScaleSnap.step(previousDisplayed: 1, raw: 0.97).enteredSnap, "one tick per entry")
        XCTAssertEqual(ARScaleSnap.step(previousDisplayed: 1, raw: 1.041).displayed, 1.041, accuracy: 0.00001)
    }

    // MARK: Smoothing

    func testDragSmoothingIsFrameRateIndependentAndCapsAStalledFrame() {
        XCTAssertEqual(ARPlacementFeel.smoothing(delta: 1.0 / 60, speed: 18), 1 - exp(-0.3), accuracy: 0.00001)
        XCTAssertEqual(ARPlacementFeel.smoothing(delta: 2, speed: 18), 1 - exp(-1.8), accuracy: 0.00001)
        XCTAssertEqual(ARPlacementFeel.smoothing(delta: 1.0 / 60, speed: 18, reduceMotion: true), 1)
        XCTAssertEqual(ARPlacementFeel.smoothing(delta: 0, speed: 18), 0)
        XCTAssertEqual(ARPlacementFeel.smoothing(delta: .nan, speed: 18), 0)
        // Two 60 Hz frames cover what one 30 Hz frame does.
        let half = ARPlacementFeel.smoothing(delta: 1.0 / 60, speed: 18)
        XCTAssertEqual(1 - (1 - half) * (1 - half), ARPlacementFeel.smoothing(delta: 1.0 / 30, speed: 18), accuracy: 0.00001)
    }

    func testFollowCarriesItsSpeedAndTracksAFingerWithoutDeadband() {
        var follow = ARInstantPlacement.Follow(target: .zero)
        XCTAssertEqual(follow.speed, ARInstantPlacement.settleSpeed)
        XCTAssertEqual(follow.alpha(delta: 1.0 / 60), ARInstantPlacement.Follow.alpha(delta: 1.0 / 60))
        let drag = ARInstantPlacement.Follow(target: .zero, speed: ARPlacementFeel.dragSpeed)
        XCTAssertEqual(drag.alpha(delta: 1.0 / 60), 1 - exp(-0.3), accuracy: 0.00001)
        XCTAssertFalse(follow.retarget([0.005, 0, 0]), "a refinement keeps its deadband")
        follow.track([0.005, 0, 0])
        XCTAssertEqual(follow.target, [0.005, 0, 0], "a finger does not")
        follow.track([.nan, 0, 0])
        XCTAssertEqual(follow.target, [0.005, 0, 0])
    }

    // MARK: Inertia tracker (Android InertiaTracker)

    /// A finger moving at `speed` px/s along x, sampled at 60 Hz for `duration` seconds.
    private func thrown(_ tracker: inout ARInertiaTracker, speed: Float, duration: Double = 0.2,
                        start: Double = 10) -> Double {
        var t = start
        var x: Float = 0
        while t <= start + duration + 1e-9 {
            tracker.addSample(time: t, [x, 0])
            t += 1.0 / 60
            x += speed / 60
        }
        return t - 1.0 / 60
    }

    func testReleaseMeasuresTheVelocityOfTheLastTenthOfASecond() {
        var tracker = ARPlacementFeel.moveCoast()
        // Slow first, then fast: only the end counts.
        var t = 0.0
        var x: Float = 0
        for _ in 0..<30 { tracker.addSample(time: t, [x, 0]); t += 1.0 / 60; x += 100 / 60 }
        for _ in 0..<8 { tracker.addSample(time: t, [x, 0]); t += 1.0 / 60; x += 1200 / 60 }
        XCTAssertTrue(tracker.release(time: t - 1.0 / 60))
        XCTAssertEqual(tracker.velocity[0], 1200, accuracy: 30)
        XCTAssertEqual(tracker.velocity[1], 0, accuracy: 0.001)
        XCTAssertEqual(tracker.speed, tracker.velocity[0], accuracy: 0.001)
    }

    func testAFingerThatRestedBeforeLiftingDoesNotCoast() {
        var tracker = ARPlacementFeel.moveCoast()
        let last = thrown(&tracker, speed: 1000)
        XCTAssertFalse(tracker.release(time: last + 0.05), "rested longer than the stopped gap")
        XCTAssertFalse(tracker.isCoasting)
        var again = ARPlacementFeel.moveCoast()
        let end = thrown(&again, speed: 1000)
        XCTAssertTrue(again.release(time: end + 0.03))
    }

    func testASlowReleaseStopsWhereItIsAndAFastOneIsClamped() {
        var slow = ARPlacementFeel.moveCoast()
        XCTAssertFalse(slow.release(time: thrown(&slow, speed: 120)), "under 150 px/s")
        XCTAssertEqual(slow.speed, 0)

        var fast = ARPlacementFeel.moveCoast()
        XCTAssertTrue(fast.release(time: thrown(&fast, speed: 12_000)))
        XCTAssertEqual(fast.speed, 4000, accuracy: 0.5)

        var oneSample = ARPlacementFeel.moveCoast()
        oneSample.addSample(time: 1, [0, 0])
        XCTAssertFalse(oneSample.release(time: 1))
    }

    func testTheCoastDecaysExactlyWhateverTheFrameRate() {
        func travel(hz: Float, for seconds: Float) -> Float {
            var tracker = ARPlacementFeel.moveCoast()
            _ = tracker.release(time: thrown(&tracker, speed: 1000))
            var total: Float = 0
            for _ in 0..<Int(seconds * hz) { total += tracker.advance(by: 1 / hz).step[0] }
            return total
        }
        let v: Float = 1000
        let expected = v * ARInertiaTracker.timeConstant * (1 - exp(-0.5 / ARInertiaTracker.timeConstant))
        XCTAssertEqual(travel(hz: 30, for: 0.5), expected, accuracy: 15)
        XCTAssertEqual(travel(hz: 120, for: 0.5), travel(hz: 30, for: 0.5), accuracy: 0.5)
    }

    func testTheCoastClampsAStalledFrameToATenthOfASecond() {
        var stalled = ARPlacementFeel.moveCoast()
        XCTAssertTrue(stalled.release(time: thrown(&stalled, speed: 1000)))
        var tenth = ARPlacementFeel.moveCoast()
        XCTAssertTrue(tenth.release(time: thrown(&tenth, speed: 1000)))
        XCTAssertEqual(ARInertiaTracker.maxFrameDelta, 0.1)
        // A 2 s hitch moves the model as far as one 0.1 s frame, not to the end of the glide.
        XCTAssertEqual(stalled.advance(by: 2).step[0], tenth.advance(by: 0.1).step[0], accuracy: 0.0001)
        XCTAssertEqual(stalled.velocity[0], tenth.velocity[0], accuracy: 0.0001)
        XCTAssertTrue(stalled.isCoasting)
        XCTAssertEqual(stalled.advance(by: -1).step, [0, 0], "a negative delta moves nothing")
    }

    func testTheCoastStopsUnderMinSpeed() {
        var tracker = ARPlacementFeel.moveCoast()
        XCTAssertTrue(tracker.release(time: thrown(&tracker, speed: 1000)))
        var frames = 0
        var total: Float = 0
        while frames < 1000 {
            let (step, goesOn) = tracker.advance(by: 1.0 / 60)
            total += step[0]
            frames += 1
            if !goesOn { break }
        }
        XCTAssertFalse(tracker.isCoasting)
        // 1000 → 8 px/s at τ = 0.35 s: τ·ln(125) ≈ 1.69 s.
        XCTAssertEqual(Float(frames) / 60, 0.35 * log(125), accuracy: 0.05)
        XCTAssertLessThan(total, 1000 * 0.35)
        XCTAssertEqual(tracker.advance(by: 1.0 / 60).step, [0, 0])
    }

    func testANewTouchOrSampleStopsACoast() {
        var tracker = ARPlacementFeel.moveCoast()
        let end = thrown(&tracker, speed: 1000)
        XCTAssertTrue(tracker.release(time: end))
        tracker.addSample(time: end + 0.1, [0, 0])
        XCTAssertFalse(tracker.isCoasting)

        var other = ARPlacementFeel.moveCoast()
        XCTAssertTrue(other.release(time: thrown(&other, speed: 1000)))
        other.reset()
        XCTAssertFalse(other.isCoasting)
        XCTAssertFalse(other.release(time: 20), "the samples are gone too")
    }

    func testTheSampleRingKeepsTheNewestTwenty() {
        var tracker = ARPlacementFeel.moveCoast()
        // 2 s of samples overflow the ring several times; the velocity is still right.
        XCTAssertTrue(tracker.release(time: thrown(&tracker, speed: 600, duration: 2)))
        XCTAssertEqual(tracker.velocity[0], 600, accuracy: 1)
    }

    func testTheTwistCoastSpinsInDegreesPerSecond() {
        var tracker = ARPlacementFeel.twistCoast()
        var t = 0.0
        for i in 0..<10 { tracker.addSample(time: t, [Float(i) * 3]); t += 1.0 / 60 }   // 180 °/s
        XCTAssertTrue(tracker.release(time: t - 1.0 / 60))
        XCTAssertEqual(tracker.velocity[0], 180, accuracy: 2)
        var slow = ARPlacementFeel.twistCoast()
        t = 0
        for i in 0..<10 { slow.addSample(time: t, [Float(i) * 0.5]); t += 1.0 / 60 }   // 30 °/s
        XCTAssertFalse(slow.release(time: t - 1.0 / 60), "under 45 °/s")
    }

    // MARK: Glide pointer

    func testTheGlidePointerMovesInPixelsAndEndsOffScreen() {
        let bounds = CGRect(x: 0, y: 0, width: 390, height: 844)
        let next = ARPlacementFeel.glide(pointer: CGPoint(x: 100, y: 100), step: [30, -15], pixelScale: 3, bounds: bounds)
        XCTAssertEqual(next, CGPoint(x: 110, y: 95))
        XCTAssertNil(ARPlacementFeel.glide(pointer: CGPoint(x: 385, y: 100), step: [30, 0], pixelScale: 3, bounds: bounds))
        XCTAssertNil(ARPlacementFeel.glide(pointer: CGPoint(x: 100, y: 2), step: [0, -30], pixelScale: 3, bounds: bounds))
        XCTAssertNil(ARPlacementFeel.glide(pointer: .zero, step: [.nan, 0], pixelScale: 3, bounds: bounds))
    }

    // MARK: Recenter bearing

    func testRecenterFacesTheUserAgainAndKeepsTheTwist() {
        let eye = SIMD3<Float>(0, 1.5, 0)
        XCTAssertEqual(ARPlacementFeel.yawToward([0, 0, -2], eye: eye), 0, accuracy: 0.0001)
        XCTAssertEqual(ARPlacementFeel.yawToward([-2, 0, 0], eye: eye), .pi / 2, accuracy: 0.0001)
        // Recentered to the user's left without a twist, +Z faces them, whatever it faced before.
        let facing = ARPlacementFeel.recenteredRotation(at: [-2, 0, 0], eye: eye, twist: 0)
        let forward = facing.act([0, 0, 1])
        XCTAssertEqual(forward.x, 1, accuracy: 0.0001)
        XCTAssertEqual(forward.z, 0, accuracy: 0.0001)
        // A user twist of 30° rides on top of facing them (ARMV: yawToward + twist).
        let twisted = ARPlacementFeel.recenteredRotation(at: [-2, 0, 0], eye: eye, twist: .pi / 6)
        XCTAssertEqual(abs(simd_dot(twisted.vector, simd_quatf(angle: .pi / 2 + .pi / 6, axis: [0, 1, 0]).vector)), 1,
                       accuracy: 0.0001)
        // Straight ahead: the twist alone.
        let ahead = ARPlacementFeel.recenteredRotation(at: [0, 0, -2], eye: eye, twist: -.pi / 4)
        XCTAssertEqual(abs(simd_dot(ahead.vector, simd_quatf(angle: -.pi / 4, axis: [0, 1, 0]).vector)), 1,
                       accuracy: 0.0001)
        // A non-finite twist is ignored rather than spreading NaN into the pose.
        let safe = ARPlacementFeel.recenteredRotation(at: [-2, 0, 0], eye: eye, twist: .nan)
        XCTAssertTrue(safe.vector.x.isFinite && safe.vector.y.isFinite && safe.vector.w.isFinite)
    }

    // MARK: Double tap (ScaleSnap)

    func testDoubleTapTargetGoesTo100OrBackToTheUsersSize() {
        XCTAssertEqual(ARScaleSnap.doubleTapTarget(current: 1.5, remembered: nil), 1)
        XCTAssertEqual(ARScaleSnap.doubleTapTarget(current: 0.5, remembered: 2), 1)
        XCTAssertEqual(ARScaleSnap.doubleTapTarget(current: 1, remembered: 2), 2)
        XCTAssertEqual(ARScaleSnap.doubleTapTarget(current: 1, remembered: 9), 4, "clamped to the range")
        XCTAssertNil(ARScaleSnap.doubleTapTarget(current: 1, remembered: nil))
        XCTAssertNil(ARScaleSnap.doubleTapTarget(current: 1, remembered: 1.0005))
        XCTAssertEqual(ARScaleSnap.doubleTapTarget(current: 1.0005, remembered: 2), 2, "within ε is 100 %")
    }

    func testDoubleTapEasesOutCubicAndLandsExactly() {
        XCTAssertEqual(ARScaleSnap.doubleTapProgress(from: 2, to: 1, elapsedMs: 0), 2)
        XCTAssertEqual(ARScaleSnap.doubleTapProgress(from: 2, to: 1, elapsedMs: 130), 1.125, accuracy: 0.0001)
        XCTAssertEqual(ARScaleSnap.doubleTapProgress(from: 2, to: 1, elapsedMs: 260), 1)
        XCTAssertEqual(ARScaleSnap.doubleTapProgress(from: 2, to: 1, elapsedMs: 5000), 1)
        XCTAssertEqual(ARScaleSnap.doubleTapProgress(from: 2, to: 1, elapsedMs: -10), 2)
    }

    // MARK: Scale display

    func testDoubleTapTogglesBetween100AndTheUsersSize() {
        var display = ARPlacementFeel.ScaleDisplay()
        display.pinchBegan()
        XCTAssertEqual(display.pinch(ratio: 2), [])
        XCTAssertEqual(display.remembered, 2)

        XCTAssertEqual(display.toggle(), [.scaleSnapped], "landing on 100 % ticks")
        XCTAssertEqual(display.scale, 1)
        XCTAssertEqual(display.shown, 2, "eased from where it was")
        display.step(by: 0.13)
        XCTAssertEqual(display.shown, 1.125, accuracy: 0.001)
        display.step(by: 0.13)
        XCTAssertEqual(display.shown, 1)
        XCTAssertFalse(display.animating)

        XCTAssertEqual(display.toggle(), [], "back to the user's size, no tick")
        XCTAssertEqual(display.scale, 2)
        XCTAssertEqual(display.shown, 1)
        display.step(by: 0.3)
        XCTAssertEqual(display.shown, 2)
    }

    func testDoubleTapWithNowhereToGoPlaysTheRebound() {
        var display = ARPlacementFeel.ScaleDisplay()
        XCTAssertEqual(display.toggle(), [])
        XCTAssertEqual(display.scale, 1)
        XCTAssertNotNil(display.rebound)
        XCTAssertEqual(display.rebound?.fromAbove, false)
        display.step(by: 0.04)
        XCTAssertGreaterThan(display.shown, 1, "continues upward first")
        display.step(by: 0.3)
        XCTAssertEqual(display.shown, 1)
    }

    func testDoubleTapUnderReduceMotionJumps() {
        var display = ARPlacementFeel.ScaleDisplay()
        _ = display.set(3)
        _ = display.toggle(reduceMotion: true)
        XCTAssertEqual(display.shown, 1)
        XCTAssertFalse(display.animating)
    }

    func testPinchIntoTheDetentTicksReboundsAndKeepsTheRememberedSize() {
        var display = ARPlacementFeel.ScaleDisplay()
        display.pinchBegan()
        _ = display.pinch(ratio: 2)
        display.pinchBegan()
        XCTAssertEqual(display.pinch(ratio: 0.51), [.scaleSnapped])
        XCTAssertEqual(display.scale, 1)
        XCTAssertEqual(display.remembered, 2, "the detent is not the user's size")
        XCTAssertEqual(display.rebound?.fromAbove, true)
        display.step(by: 0.04)
        XCTAssertLessThan(display.shown, 1, "shrinking into it overshoots below")
        XCTAssertEqual(display.pinch(ratio: 0.505), [], "one tick per entry")
        XCTAssertEqual(display.pinch(ratio: 10), [.limitReached])
        XCTAssertEqual(display.scale, 4)
        XCTAssertNil(display.rebound)
    }

    func testAPinchStopsTheEaseAndTheRebound() {
        var display = ARPlacementFeel.ScaleDisplay()
        _ = display.set(2)
        _ = display.toggle()
        XCTAssertNotNil(display.ease)
        display.pinchBegan()
        XCTAssertNil(display.ease)
        XCTAssertNil(display.rebound)
        XCTAssertEqual(display.shown, 1)
    }

    func testAnExactScaleIsRememberedAndTicksWhenItCrosses100() {
        var display = ARPlacementFeel.ScaleDisplay()
        XCTAssertEqual(display.set(1.5), [], "leaving 100 % does not tick")
        XCTAssertEqual(display.remembered, 1.5)
        XCTAssertEqual(display.set(1), [.scaleSnapped])
        XCTAssertEqual(display.remembered, 1.5)
        XCTAssertEqual(display.set(0.1), [.limitReached])
        XCTAssertEqual(display.scale, 0.25)
        XCTAssertEqual(display.set(.nan), [])
    }

    func testAFirstPlacementGrowsInFromOnePercent() {
        var display = ARPlacementFeel.ScaleDisplay()
        _ = display.set(2)
        display.beginAppear()
        XCTAssertEqual(display.shown, 0.02, accuracy: 0.00001)
        display.step(by: 1.0 / 60)
        XCTAssertEqual(display.appear, 0.01 + 0.99 * (1 - exp(-0.1)), accuracy: 0.0001)
        for _ in 0..<240 where display.animating { display.step(by: 1.0 / 60) }
        XCTAssertEqual(display.appear, 1)
        XCTAssertEqual(display.shown, 2)
        XCTAssertFalse(display.animating)

        var calm = ARPlacementFeel.ScaleDisplay()
        calm.beginAppear(reduceMotion: true)
        XCTAssertEqual(calm.shown, 1)
    }

    func testResetForgetsTheUsersSize() {
        var display = ARPlacementFeel.ScaleDisplay()
        _ = display.set(2)
        display.beginAppear()
        display.reset()
        XCTAssertEqual(display.scale, 1)
        XCTAssertNil(display.remembered)
        XCTAssertEqual(display.shown, 1)
    }

    // MARK: Controller

    func testATouchStopsBothCoasts() {
        let controller = ARPlacementController()
        var move = ARPlacementFeel.moveCoast()
        XCTAssertTrue(move.release(time: thrown(&move, speed: 1000)))
        var twist = ARPlacementFeel.twistCoast()
        twist.addSample(time: 0, [0])
        twist.addSample(time: 0.05, [10])
        XCTAssertTrue(twist.release(time: 0.05))
        controller.moveCoast = move
        controller.twistCoast = twist
        controller.touchDown()
        XCTAssertFalse(controller.moveCoast.isCoasting)
        XCTAssertFalse(controller.twistCoast.isCoasting)
    }

    func testUnplacedContentIgnoresDoubleTapAndRecenter() {
        let controller = ARPlacementController()
        controller.toggleScale()
        XCTAssertEqual(controller.scale, 1)
        XCTAssertFalse(controller.scaleDisplay.animating)
        XCTAssertFalse(controller.recenter())
    }

    func testAGestureCutShortByRecenterSettlesNothingWhenItsCancelArrives() {
        let pan = ObjectIdentifier(UIPanGestureRecognizer())
        let pinch = ObjectIdentifier(UIPinchGestureRecognizer())
        var active: Set<ObjectIdentifier> = [pan, pinch]
        XCTAssertFalse(ARPlacementController.endGesture(pinch, in: &active), "the pan still runs")
        XCTAssertTrue(ARPlacementController.endGesture(pan, in: &active), "the last one settles")
        // recenter() → cancelGestures() empties the set, then starts a pending move; the
        // `.cancelled` that follows must not discard it.
        active = [pan, pinch]
        active.removeAll()
        XCTAssertFalse(ARPlacementController.endGesture(pan, in: &active))
        XCTAssertFalse(ARPlacementController.endGesture(pinch, in: &active))
        XCTAssertFalse(ARPlacementController.endGesture(pan, in: &active), "ending twice settles nothing")
    }

    func testResetForgetsTheUserTwist() {
        let controller = ARPlacementController()
        controller.userTwist = 0.7
        controller.resetPlacement()
        XCTAssertEqual(controller.userTwist, 0)
        controller.userTwist = 0.7
        controller.dismiss()
        XCTAssertEqual(controller.userTwist, 0)
    }

    func testVelocitiesAreMeasuredInScreenPixels() {
        XCTAssertGreaterThan(ARPlacementController.pixelScale(of: UIView()), 0)
    }

    func testTheDragFollowEasesAtDragSpeed() {
        let controller = ARPlacementController()
        let anchor = AnchorEntity(world: matrix_identity_float4x4)
        anchor.addChild(controller.pivot)
        controller.follow = ARInstantPlacement.Follow(target: [1, 0, 0], speed: ARPlacementFeel.dragSpeed)
        controller.stepFollow(by: 1.0 / 60)
        XCTAssertEqual(controller.pivot.position(relativeTo: nil).x, 1 - exp(-0.3), accuracy: 0.0001)
    }

    func testAttachAddsTheDoubleTap() throws {
        let controller = ARPlacementController()
        let view = ARView(frame: .zero)
        controller.attach(to: view)
        let taps = (view.gestureRecognizers ?? []).compactMap { $0 as? UITapGestureRecognizer }
        let double = try XCTUnwrap(taps.first { $0.numberOfTapsRequired == 2 })
        XCTAssertTrue(controller.gestureRecognizer(double, shouldReceive: UITouch()))
        // The drag and the twist measure their velocity on touch-event time.
        let recognizers = view.gestureRecognizers ?? []
        XCTAssertTrue(recognizers.contains { $0 is ARTimedPanGestureRecognizer })
        XCTAssertTrue(recognizers.contains { $0 is ARTimedRotationGestureRecognizer })
        let before = CACurrentMediaTime()
        XCTAssertGreaterThanOrEqual(ARPlacementController.eventTime(of: double), before,
                                    "no touch event yet: the callback time")
        controller.dismiss()
    }
}
#endif

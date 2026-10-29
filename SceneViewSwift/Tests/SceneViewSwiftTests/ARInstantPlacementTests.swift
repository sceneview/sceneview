#if os(iOS)
import XCTest
import ARKit
import RealityKit
@testable import SceneViewSwift

/// Instant placement rules, device-free. The ARKit half (estimated planes, plane raycasts,
/// plane anchors resolving) needs a device; these pin every decision it feeds.
@MainActor
final class ARInstantPlacementTests: XCTestCase {
    private let eye = SIMD3<Float>(0, 1.5, 0)

    /// A camera at `eye` looking along `forward`, +Y roughly up.
    private func camera(at eye: SIMD3<Float>, looking forward: SIMD3<Float>) -> simd_float4x4 {
        let back = -simd_normalize(forward)
        let reference: SIMD3<Float> = abs(back.y) > 0.99 ? [0, 0, -1] : [0, 1, 0]
        let right = simd_normalize(simd_cross(reference, back))
        let up = simd_cross(back, right)
        return simd_float4x4(SIMD4(right, 0), SIMD4(up, 0), SIMD4(back, 0), SIMD4(eye, 1))
    }

    private func assertClose(_ a: SIMD3<Float>, _ b: SIMD3<Float>, accuracy: Float = 0.0001,
                             file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertLessThan(simd_length(a - b), accuracy, "\(a) != \(b)", file: file, line: line)
    }

    private func xyz(_ v: SIMD4<Float>) -> SIMD3<Float> { SIMD3(v.x, v.y, v.z) }

    // MARK: Defaults

    func testOffByDefaultLikeAndroidAutoPlacement() {
        let controller = ARPlacementController()
        XCTAssertFalse(controller.instantPlacement)
        XCTAssertNil(controller.trackingMethod)
        XCTAssertTrue(ARPlacementController(alignment: .vertical, instantPlacement: true).instantPlacement)
    }

    // MARK: Tracking method — approximate → fullTracking

    func testTrackingMethodFollowsTheSurface() {
        XCTAssertNil(ARInstantPlacement.trackingMethod(hasPlacement: false, onSurface: false))
        XCTAssertNil(ARInstantPlacement.trackingMethod(hasPlacement: false, onSurface: true))
        XCTAssertEqual(ARInstantPlacement.trackingMethod(hasPlacement: true, onSurface: false), .approximate)
        XCTAssertEqual(ARInstantPlacement.trackingMethod(hasPlacement: true, onSurface: true), .fullTracking)
    }

    func testTrackingMethodNamesMatchARCore() {
        // Raw values are stable for persistence and analytics; each state names its ARCore twin.
        XCTAssertEqual(ARPlacementTrackingMethod.approximate.rawValue, "approximate")
        XCTAssertEqual(ARPlacementTrackingMethod.fullTracking.rawValue, "fullTracking")
        XCTAssertEqual(ARPlacementTrackingMethod.approximate.arCoreName, "SCREENSPACE_WITH_APPROXIMATE_DISTANCE")
        XCTAssertEqual(ARPlacementTrackingMethod.fullTracking.arCoreName, "FULL_TRACKING")
        // When the Android sources sit next to this package, the Kotlin side must still use them.
        let kotlin = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("arsceneview/src/main/java/io/github/sceneview/ar/PlacementScene.kt")
        if let source = try? String(contentsOf: kotlin, encoding: .utf8) {
            for method in [ARPlacementTrackingMethod.approximate, .fullTracking] {
                XCTAssertTrue(source.contains(method.arCoreName), method.arCoreName)
            }
        }
    }

    // MARK: Constants shared with Android

    func testConstantsMatchAndroid() {
        XCTAssertEqual(ARInstantPlacement.maxSurfaceDistance, 5)
        XCTAssertEqual(ARInstantPlacement.autoPlaceDelay, 0.6)
        XCTAssertEqual(ARInstantPlacement.settleSpeed, 6)
        XCTAssertEqual(ARInstantPlacement.anchorRetryLimit, 6)
        XCTAssertEqual(ARInstantPlacement.anchorRetryBase, 0.1)
        XCTAssertEqual(ARInstantPlacement.anchorRetryCap, 0.8)
    }

    // MARK: Estimated floor — never mid-air

    func testReachGrowsWithTheModelSoALargeOneNeverSurroundsTheCamera() {
        XCTAssertEqual(ARInstantPlacement.reach(forLargestSide: 0.3), 1.2)
        XCTAssertEqual(ARInstantPlacement.reach(forLargestSide: 2), 2.9, accuracy: 0.0001)
        // A 2 m sofa centred 2.9 m ahead keeps its near edge well away from the phone.
        XCTAssertGreaterThan(ARInstantPlacement.reach(forLargestSide: 2) - 1, 1.5)
        XCTAssertEqual(ARInstantPlacement.reach(forLargestSide: 30), 4)
        XCTAssertEqual(ARInstantPlacement.reach(forLargestSide: .nan), 1.2)
        XCTAssertEqual(ARInstantPlacement.reach(forLargestSide: 0), 1.2)
    }

    func testFloorIsTheLowestSurfaceWellBelowThePhoneElseThePhoneHeightGuess() {
        XCTAssertEqual(ARInstantPlacement.floorHeight(cameraY: 1.5, surfaceHeights: []), 0.2, accuracy: 0.0001)
        // A table at chest height is not the floor.
        XCTAssertEqual(ARInstantPlacement.floorHeight(cameraY: 1.5, surfaceHeights: [1.3]), 0.2, accuracy: 0.0001)
        XCTAssertEqual(ARInstantPlacement.floorHeight(cameraY: 1.5, surfaceHeights: [0.75, 0.02, .nan]), 0.02, accuracy: 0.0001)
    }

    func testFallbackStandsOnTheFloorWithinReachNeverAlongTheRay() {
        let reach: Float = 1.2
        // Looking 45° down from 1.5 m: the ray meets the floor 1.5 m ahead, beyond reach.
        let far = ARInstantPlacement.floorPoint(origin: eye, direction: [0, -1, -1], floorY: 0, reach: reach)
        assertClose(far!, [0, 0, -1.2])
        // Steeply down: the ray meets the floor at 0.15 m; kept at half the reach.
        let near = ARInstantPlacement.floorPoint(origin: eye, direction: [0, -1, -0.1], floorY: 0, reach: reach)
        assertClose(near!, [0, 0, -0.6])
        // Within range: exactly where the ray meets the floor.
        let on = ARInstantPlacement.floorPoint(origin: eye, direction: [0, -1.5, -1], floorY: 0, reach: reach)
        assertClose(on!, [0, 0, -1])
    }

    func testPhoneHeldLevelStillStandsTheModelOnTheFloor() {
        // The review case: a level phone used to leave the model 1 m along a horizontal ray, in mid-air.
        for direction: SIMD3<Float> in [[0, 0, -1], [1, 0.2, 0], [0.3, 0.6, -1]] {
            let point = ARInstantPlacement.floorPoint(origin: eye, direction: direction, floorY: 0.2, reach: 2.9)
            XCTAssertEqual(point!.y, 0.2, accuracy: 0.0001)
            XCTAssertEqual(simd_length(SIMD2(point!.x - eye.x, point!.z - eye.z)), 2.9, accuracy: 0.0001)
        }
    }

    func testStraightDownWaitsForAHeading() {
        XCTAssertNil(ARInstantPlacement.floorPoint(origin: eye, direction: [0, -1, 0], floorY: 0, reach: 1.2))
        XCTAssertNil(ARInstantPlacement.floorPoint(origin: eye, direction: [0, -1, -1], floorY: .nan, reach: 1.2))
    }

    func testDragWhileApproximateFollowsTheEstimatedFloor() {
        // Android `floorUnder`: the finger's ray meets the plane the model stands on.
        let floor = ARInstantPlacement.pointOnContactPlane(rayOrigin: eye, rayDirection: [0.5, -1, -1],
                                                           point: [0, 0.2, -1.3], normal: [0, 1, 0])
        assertClose(floor!, [0.65, 0.2, -1.3])
        XCTAssertNil(ARInstantPlacement.pointOnContactPlane(rayOrigin: eye, rayDirection: [0, 1, -1],
                                                            point: [0, 0.2, -1.3], normal: [0, 1, 0]), "above the horizon")
        XCTAssertNil(ARInstantPlacement.pointOnContactPlane(rayOrigin: eye, rayDirection: [0, -0.1, -1],
                                                            point: [0, 0.2, -1.3], normal: [0, 1, 0]), "beyond 5 m")
        XCTAssertNil(ARInstantPlacement.pointOnContactPlane(rayOrigin: eye, rayDirection: [1, 0, 0],
                                                            point: [0, 0.2, -1.3], normal: [0, 1, 0]), "parallel")
    }

    // MARK: Estimated contact frame

    func testApproximateContactStandsUprightFacingTheCamera() {
        for forward: SIMD3<Float> in [[0, -0.5, -1], [1, -0.3, 0], [-0.4, -1, 0.7]] {
            let cam = camera(at: eye, looking: forward)
            let point = eye + simd_normalize(forward) + SIMD3<Float>(0, -0.5, 0)
            let contact = ARInstantPlacement.approximateContact(point: point, camera: cam)
            assertClose(xyz(contact.columns.3), point)
            assertClose(xyz(contact.columns.1), [0, 1, 0])
            var toCamera = eye - point
            toCamera.y = 0
            assertClose(xyz(contact.columns.2), simd_normalize(toCamera))
        }
    }

    func testApproximateContactLookingStraightDownStillHasAValidFrame() {
        let cam = camera(at: eye, looking: [0, -1, 0])
        let point = eye - SIMD3<Float>(0, 1.3, 0)
        let contact = ARInstantPlacement.approximateContact(point: point, camera: cam)
        assertClose(xyz(contact.columns.1), [0, 1, 0])
        XCTAssertEqual(simd_length(xyz(contact.columns.2)), 1, accuracy: 0.0001)
        XCTAssertEqual(contact.columns.2.y, 0, accuracy: 0.0001)
    }

    func testWallEstimateFacesAlongItsNormal() {
        let cam = camera(at: eye, looking: [0.3, 0, -1])
        let contact = ARInstantPlacement.approximateContact(point: [0, 1.5, -2], camera: cam, wallNormal: [0, 0, 1])
        assertClose(xyz(contact.columns.2), [0, 0, 1])
        assertClose(xyz(contact.columns.1), [0, 1, 0])
    }

    // MARK: Estimated hits

    func testFloorEstimateBelowTheCameraIsAccepted() {
        let cam = camera(at: eye, looking: [0, -1, -1])
        XCTAssertTrue(ARInstantPlacement.acceptsEstimatedHit(point: [0, 0, -1.5], normal: [0, 1, 0],
                                                             camera: cam, alignment: .horizontal))
    }

    func testCeilingsWallsFarAndBehindHitsAreRefusedForFloors() {
        let up = camera(at: eye, looking: [0, 1, -1])
        XCTAssertFalse(ARInstantPlacement.acceptsEstimatedHit(point: [0, 2.5, -1], normal: [0, 1, 0],
                                                              camera: up, alignment: .horizontal), "ceiling underside")
        let ahead = camera(at: eye, looking: [0, 0, -1])
        XCTAssertFalse(ARInstantPlacement.acceptsEstimatedHit(point: [0, 1.5, -2], normal: [0, 0, 1],
                                                              camera: ahead, alignment: .horizontal), "wall")
        let down = camera(at: eye, looking: [0, -0.3, -1])
        XCTAssertFalse(ARInstantPlacement.acceptsEstimatedHit(point: [0, 0, -5.5], normal: [0, 1, 0],
                                                              camera: down, alignment: .horizontal), "beyond 5 m")
        XCTAssertFalse(ARInstantPlacement.acceptsEstimatedHit(point: [0, 0, 1], normal: [0, 1, 0],
                                                              camera: down, alignment: .horizontal), "behind")
        XCTAssertFalse(ARInstantPlacement.acceptsEstimatedHit(point: [0, 1.4, -0.1], normal: [0, 1, 0],
                                                              camera: down, alignment: .horizontal), "closer than 0.25 m")
    }

    func testWallsTakeOnlyNearVerticalEstimates() {
        let ahead = camera(at: eye, looking: [0, 0, -1])
        XCTAssertTrue(ARInstantPlacement.acceptsEstimatedHit(point: [0, 1.5, -2], normal: [0, 0, 1],
                                                             camera: ahead, alignment: .vertical))
        XCTAssertFalse(ARInstantPlacement.acceptsEstimatedHit(point: [0, 0, -1], normal: [0, 1, 0],
                                                              camera: camera(at: eye, looking: [0, -1, -0.5]),
                                                              alignment: .vertical))
        XCTAssertFalse(ARInstantPlacement.acceptsEstimatedHit(point: [.nan, 0, -1], normal: [0, 0, 1],
                                                              camera: ahead, alignment: .vertical))
    }

    // MARK: Refined rotation

    func testFloorRefinementNeverSpinsTheModel() {
        let model = simd_quatf(angle: 0.7, axis: [0, 1, 0])
        let refined = ARInstantPlacement.refinedRotation(alignment: .horizontal, model: model,
                                                         approximateContact: simd_quatf(angle: 0.2, axis: [0, 1, 0]),
                                                         surfaceContact: simd_quatf(angle: 1.9, axis: [0, 1, 0]))
        XCTAssertEqual(refined, model)
    }

    func testWallRefinementKeepsTheUserTwistRelativeToTheWall() {
        let approximate = simd_quatf(angle: 0.3, axis: [0, 1, 0])
        let twist = simd_quatf(angle: 0.5, axis: [0, 0, 1])
        let surface = simd_quatf(angle: -0.2, axis: [0, 1, 0])
        let refined = ARInstantPlacement.refinedRotation(alignment: .vertical, model: approximate * twist,
                                                         approximateContact: approximate, surfaceContact: surface)
        let relative = surface.inverse * refined
        XCTAssertGreaterThan(abs(simd_dot(relative.vector, twist.vector)), 0.9999)
    }

    // MARK: State machine — auto-place delay

    func testNoEstimatedPlacementBeforeTrackingHasRunForTheDelay() {
        var state = ARInstantPlacement.State()
        XCTAssertFalse(state.autoPlaceDue(now: 100), "never tracked")
        state.observe(tracking: true, placed: false, now: 0)
        state.observe(tracking: true, placed: false, now: 0.3)
        XCTAssertFalse(state.autoPlaceDue(now: 0.59))
        XCTAssertTrue(state.autoPlaceDue(now: 0.6))
    }

    func testLosingTrackingBeforePlacementRestartsTheDelay() {
        var state = ARInstantPlacement.State()
        state.observe(tracking: true, placed: false, now: 0)
        state.observe(tracking: false, placed: false, now: 0.5)
        XCTAssertFalse(state.autoPlaceDue(now: 0.7))
        state.observe(tracking: true, placed: false, now: 0.7)
        XCTAssertFalse(state.autoPlaceDue(now: 1.2))
        XCTAssertTrue(state.autoPlaceDue(now: 1.4))
    }

    func testResetRestartsTheDelay() {
        var state = ARInstantPlacement.State()
        state.observe(tracking: true, placed: false, now: 0)
        state.reset()
        XCTAssertFalse(state.autoPlaceDue(now: 5))
        state.observe(tracking: true, placed: false, now: 5)
        XCTAssertFalse(state.autoPlaceDue(now: 5.5))
        XCTAssertTrue(state.autoPlaceDue(now: 5.7))
    }

    // MARK: State machine — landing

    private let plane = UUID()

    func testAnchorRetryBacksOffLikeAndroid() {
        let delays = (1...7).map { ARInstantPlacement.anchorRetryDelay(attempt: $0) }
        XCTAssertEqual(delays, [0.1, 0.2, 0.4, 0.8, 0.8, 0.8, 0.8])
        XCTAssertEqual(ARInstantPlacement.anchorRetryDelay(attempt: 0), 0.1)
        XCTAssertEqual(ARInstantPlacement.anchorRetryDelay(attempt: 1000), 0.8)
    }

    func testEachAnchorGetsThePlacementResolveWindowNotTheBackoff() {
        XCTAssertEqual(ARInstantPlacement.anchorResolveWindow, ARPlacementLifecycle.pendingAnchorTimeout)
        let state = ARInstantPlacement.State()
        XCTAssertEqual(state.step(gesturesActive: false, anchored: false, overSurface: true, now: 3.9, since: 1), .wait)
        XCTAssertEqual(state.step(gesturesActive: false, anchored: true, overSurface: true, now: 1.05, since: 1), .commit)
        XCTAssertEqual(state.step(gesturesActive: false, anchored: false, overSurface: true, now: 4, since: 1), .retry)
    }

    func testLandingWaitsWhileAPinchOrTwistIsInProgress() {
        // Landing mid-gesture would re-parent the pivot under a stale twist baseline.
        let state = ARInstantPlacement.State()
        for anchored in [false, true] {
            for over in [false, true] {
                XCTAssertEqual(state.step(gesturesActive: true, anchored: anchored, overSurface: over,
                                          now: 50, since: 1), .hold)
            }
        }
    }

    func testLosingThePlaneIsNotAFailedAttempt() {
        // The review case: at the edge of a young plane the model drifts in and out of it. That
        // used to count as failures and freeze the placement as approximate after ~2 s.
        var state = ARInstantPlacement.State()
        var now: TimeInterval = 0
        for _ in 0..<50 {
            XCTAssertTrue(state.mayAttemptLanding(on: plane, area: 0.2, now: now))
            XCTAssertEqual(state.step(gesturesActive: false, anchored: true, overSurface: false, now: now + 0.1,
                                      since: now), .lost)
            state.lost(now: now + 0.1)
            XCTAssertFalse(state.mayAttemptLanding(on: plane, area: 0.2, now: now + 0.15), "no anchor per tick")
            now += 0.25
        }
        XCTAssertEqual(state.failures, 0)
        XCTAssertNil(state.abandoned)
        XCTAssertEqual(state.step(gesturesActive: false, anchored: true, overSurface: true, now: now, since: now), .commit)
    }

    func testUnresolvedAnchorsBackOffThenPauseThatPlane() {
        var state = ARInstantPlacement.State()
        var now: TimeInterval = 0
        for attempt in 1..<ARInstantPlacement.anchorRetryLimit {
            XCTAssertEqual(state.attempt, attempt)
            XCTAssertTrue(state.mayAttemptLanding(on: plane, area: 1, now: now))
            now += ARInstantPlacement.anchorResolveWindow
            XCTAssertEqual(state.step(gesturesActive: false, anchored: false, overSurface: true,
                                      now: now, since: 0), .retry)
            XCTAssertFalse(state.failed(now: now, plane: plane, area: 1))
            XCTAssertFalse(state.mayAttemptLanding(on: plane, area: 1, now: now))
            now += ARInstantPlacement.anchorRetryDelay(attempt: attempt)
        }
        XCTAssertEqual(state.step(gesturesActive: false, anchored: false, overSurface: true,
                                  now: now + 3, since: 0), .abandon)
        XCTAssertTrue(state.failed(now: now, plane: plane, area: 1))
        XCTAssertFalse(state.mayAttemptLanding(on: plane, area: 1, now: now + 1), "paused")
    }

    func testAnAbandonedLandingRecoversWithoutAnyDrag() {
        // The review case: a user who never drags must not stay approximate for good.
        func abandoned() -> ARInstantPlacement.State {
            var state = ARInstantPlacement.State()
            for _ in 0..<ARInstantPlacement.anchorRetryLimit { state.failed(now: 10, plane: plane, area: 1) }
            XCTAssertNotNil(state.abandoned)
            return state
        }
        var cooled = abandoned()
        XCTAssertFalse(cooled.mayAttemptLanding(on: plane, area: 1.1, now: 12.9))
        XCTAssertTrue(cooled.mayAttemptLanding(on: plane, area: 1, now: 10 + ARInstantPlacement.abandonCooldown))
        XCTAssertEqual(cooled.attempt, 1, "a fresh round of attempts")
        XCTAssertNil(cooled.abandoned)

        var grown = abandoned()
        XCTAssertTrue(grown.mayAttemptLanding(on: plane, area: 1.3, now: 10.5), "the plane grew under the model")

        var other = abandoned()
        XCTAssertTrue(other.mayAttemptLanding(on: UUID(), area: 0.5, now: 10.5), "ARKit reports another plane")

        var dragged = abandoned()
        dragged.moved()
        XCTAssertTrue(dragged.mayAttemptLanding(on: plane, area: 1, now: 10.5))
    }

    func testResetClearsLandingStateAndTheThrottle() {
        var state = ARInstantPlacement.State()
        for _ in 0..<ARInstantPlacement.anchorRetryLimit { state.failed(now: 0, plane: plane, area: 1) }
        XCTAssertTrue(state.refineTick(now: 0))
        state.reset()
        XCTAssertEqual(state.failures, 0)
        XCTAssertNil(state.abandoned)
        XCTAssertTrue(state.refineTick(now: 0.01))
    }

    func testRefinementRaycastsAreThrottled() {
        var state = ARInstantPlacement.State()
        XCTAssertTrue(state.refineTick(now: 1))
        XCTAssertFalse(state.refineTick(now: 1.05))
        XCTAssertFalse(state.refineTick(now: 1.09))
        XCTAssertTrue(state.refineTick(now: 1.1))
        XCTAssertTrue(state.refineTick(now: 0.5), "a clock that went back does not stall refinement")
    }

    // MARK: Controller — follow handover to gestures

    func testDragOrMoveRightAfterAWallLandingKeepsTheWallFacing() {
        let controller = ARPlacementController(alignment: .vertical)
        let facing = simd_quatf(angle: 0.8, axis: [0, 1, 0])
        controller.pivot.setOrientation(simd_slerp(simd_quatf(angle: 0, axis: [0, 1, 0]), facing, 0.3), relativeTo: nil)
        controller.follow = ARInstantPlacement.Follow(target: [0, 1.5, -2], rotation: facing)
        controller.handFollowToFinger()
        XCTAssertNil(controller.follow)
        XCTAssertGreaterThan(abs(simd_dot(controller.pivot.orientation(relativeTo: nil).vector, facing.vector)), 0.99999,
                             "no partial yaw left behind")
    }

    func testDragWithoutAnEasingRotationKeepsTheCurrentRotation() {
        let controller = ARPlacementController()
        let turned = simd_quatf(angle: 1.1, axis: [0, 1, 0])
        controller.pivot.setOrientation(turned, relativeTo: nil)
        controller.follow = ARInstantPlacement.Follow(target: [0, 0, -1])
        controller.handFollowToFinger()
        XCTAssertNil(controller.follow)
        XCTAssertGreaterThan(abs(simd_dot(controller.pivot.orientation(relativeTo: nil).vector, turned.vector)), 0.99999)
    }

    func testATwistDuringTheEaseRebasesOnTheCurrentRotationWithoutASnap() throws {
        let controller = ARPlacementController(alignment: .vertical)
        let midway = simd_quatf(angle: 0.25, axis: [0, 1, 0])
        controller.pivot.setOrientation(midway, relativeTo: nil)
        controller.follow = ARInstantPlacement.Follow(target: [0, 1.5, -2], rotation: simd_quatf(angle: 0.8, axis: [0, 1, 0]))
        controller.handFollowRotationToTwist()
        XCTAssertGreaterThan(abs(simd_dot(controller.pivot.orientation(relativeTo: nil).vector, midway.vector)), 0.99999,
                             "no snap")
        XCTAssertGreaterThan(abs(simd_dot(controller.twistBaseline.vector, midway.vector)), 0.99999)
        XCTAssertNil(controller.follow?.rotation)
        assertClose(try XCTUnwrap(controller.follow).target, [0, 1.5, -2])
    }

    // MARK: Follow — continuous, interruptible

    func testFollowEasesAtSettleSpeed() {
        XCTAssertEqual(ARInstantPlacement.Follow.alpha(delta: 1.0 / 60), 1 - exp(-0.1), accuracy: 0.00001)
        XCTAssertEqual(ARInstantPlacement.Follow.alpha(delta: 1.0 / 60, reduceMotion: true), 1)
        XCTAssertEqual(ARInstantPlacement.Follow.alpha(delta: -1), 0)
        XCTAssertEqual(ARInstantPlacement.Follow.alpha(delta: .nan), 0)
        // A stalled frame never teleports the model.
        XCTAssertEqual(ARInstantPlacement.Follow.alpha(delta: 5), 1 - exp(-0.6), accuracy: 0.00001)
    }

    func testFollowIgnoresUpdatesUnderOneCentimetre() {
        var follow = ARInstantPlacement.Follow(target: [0, 0, -1])
        XCTAssertFalse(follow.retarget([0.005, 0, -1]))
        assertClose(follow.target, [0, 0, -1])
        XCTAssertFalse(follow.retarget([.nan, 0, -1]))
        XCTAssertTrue(follow.retarget([0.02, 0, -1]))
        assertClose(follow.target, [0.02, 0, -1])
    }

    func testANewTargetBendsThePathWithoutAJumpOrARestart() {
        var follow = ARInstantPlacement.Follow(target: [0, 0, -2])
        var position = SIMD3<Float>(0, 0, -1)
        let alpha = ARInstantPlacement.Follow.alpha(delta: 1.0 / 60)
        for frame in 0..<120 {
            if frame == 10 { follow.retarget([0.5, 0, -1.5]) }
            let next = follow.position(from: position, alpha: alpha)
            // Each frame covers a tenth of what is left, never more: no visible jump.
            XCTAssertLessThanOrEqual(simd_length(next - position), alpha * simd_length(follow.target - position) + 0.00001)
            position = next
        }
        XCTAssertTrue(follow.arrived(at: position, rotation: simd_quatf(angle: 0, axis: [0, 1, 0])))
        assertClose(position, [0.5, 0, -1.5], accuracy: 0.001)
    }

    func testFollowWaitsForItsRotationToo() {
        let target = simd_quatf(angle: 0.4, axis: [0, 0, 1])
        let follow = ARInstantPlacement.Follow(target: .zero, rotation: target)
        XCTAssertFalse(follow.arrived(at: .zero, rotation: simd_quatf(angle: 0, axis: [0, 0, 1])))
        XCTAssertTrue(follow.arrived(at: .zero, rotation: target))
    }
}
#endif

import XCTest
import RealityKit // `BoundingBox` for the #1391 content-bounds union tests
@testable import SceneViewSwift

#if os(iOS) || os(visionOS)
// Test classes run on the main actor: their RealityKit node factories
// (`LightNode.directional`, `node.entity`, …) are `@MainActor`. (#1054)
@MainActor
final class CameraControlsTests: XCTestCase {

    // MARK: - Initialization

    func testDefaultInit() {
        let controls = CameraControls()
        XCTAssertEqual(controls.azimuth, 0.0)
        XCTAssertEqual(controls.elevation, Float.pi / 6, accuracy: 0.001)
        // v4.4.0 BREAKING: orbitRadius default changed from 5.0 to 2.0 so
        // direct constructors of CameraControls see the same default the
        // SceneView uses internally (the old 5.0 was unreachable through
        // any public modifier, producing a misleading split-brain default).
        // minRadius default changed from 0.5 to 1.0 so pinch-in stops
        // before clipping into 1m-extent content under the new true-camera
        // motion (the old 0.5 was safe under the fake-orbit scale hack).
        XCTAssertEqual(controls.orbitRadius, 2.0)
        XCTAssertEqual(controls.minRadius, 1.0)
        XCTAssertEqual(controls.maxRadius, 50.0)
        XCTAssertEqual(controls.sensitivity, 0.005)
        XCTAssertFalse(controls.isAutoRotating)
    }

    func testInitWithMode() {
        let orbit = CameraControls(mode: .orbit)
        XCTAssertEqual(orbit.mode, .orbit)

        let pan = CameraControls(mode: .pan)
        XCTAssertEqual(pan.mode, .pan)

        let fp = CameraControls(mode: .firstPerson)
        XCTAssertEqual(fp.mode, .firstPerson)
    }

    // MARK: - Camera Position

    func testCameraPositionAtDefaults() {
        let controls = CameraControls()
        let pos = controls.cameraPosition()

        // At azimuth=0, elevation=pi/6, radius=5:
        // x = 5 * cos(pi/6) * sin(0) = 0
        // y = 5 * sin(pi/6) = 2.5
        // z = 5 * cos(pi/6) * cos(0) = 5 * 0.866 = 4.33
        XCTAssertEqual(pos.x, 0.0, accuracy: 0.01)
        XCTAssertEqual(pos.y, 2.5, accuracy: 0.01)
        XCTAssertEqual(pos.z, 4.33, accuracy: 0.01)
    }

    func testCameraPositionAtAzimuth90() {
        var controls = CameraControls()
        controls.azimuth = Float.pi / 2  // 90 degrees
        controls.elevation = 0

        let pos = controls.cameraPosition()
        // x = 5 * cos(0) * sin(pi/2) = 5
        // y = 0
        // z = 5 * cos(0) * cos(pi/2) = 0
        XCTAssertEqual(pos.x, 5.0, accuracy: 0.01)
        XCTAssertEqual(pos.y, 0.0, accuracy: 0.01)
        XCTAssertEqual(pos.z, 0.0, accuracy: 0.01)
    }

    func testCameraPositionWithTarget() {
        var controls = CameraControls()
        controls.target = SIMD3<Float>(1, 2, 3)
        controls.azimuth = 0
        controls.elevation = 0

        let pos = controls.cameraPosition()
        // x = 1 + 5 * 1 * 0 = 1
        // y = 2 + 5 * 0 = 2
        // z = 3 + 5 * 1 * 1 = 8
        XCTAssertEqual(pos.x, 1.0, accuracy: 0.01)
        XCTAssertEqual(pos.y, 2.0, accuracy: 0.01)
        XCTAssertEqual(pos.z, 8.0, accuracy: 0.01)
    }

    // MARK: - Drag Handling

    func testHandleDragUpdatesAzimuth() {
        var controls = CameraControls()
        let initialAzimuth = controls.azimuth

        controls.handleDrag(CGSize(width: 100, height: 0))

        // azimuth should decrease (negative direction for width)
        XCTAssertLessThan(controls.azimuth, initialAzimuth)
        XCTAssertEqual(
            controls.azimuth,
            initialAzimuth - 100 * controls.sensitivity,
            accuracy: 0.0001
        )
    }

    func testHandleDragUpdatesElevation() {
        var controls = CameraControls()
        let initialElevation = controls.elevation

        controls.handleDrag(CGSize(width: 0, height: 100))

        // elevation should increase for downward drag
        XCTAssertGreaterThan(controls.elevation, initialElevation)
    }

    func testElevationClampedToAvoidGimbalLock() {
        var controls = CameraControls()

        // Drag a huge amount upward
        controls.handleDrag(CGSize(width: 0, height: 100000))

        let maxElev = Float.pi / 2 - 0.087
        XCTAssertLessThanOrEqual(controls.elevation, maxElev)

        // Drag a huge amount downward
        controls.handleDrag(CGSize(width: 0, height: -200000))
        XCTAssertGreaterThanOrEqual(controls.elevation, -maxElev)
    }

    // MARK: - Pinch Handling

    func testHandlePinchZoomIn() {
        var controls = CameraControls()
        let initialRadius = controls.orbitRadius

        controls.handlePinch(2.0)  // Pinch out = zoom in

        XCTAssertLessThan(controls.orbitRadius, initialRadius)
        XCTAssertEqual(controls.orbitRadius, initialRadius / 2.0, accuracy: 0.01)
    }

    func testHandlePinchZoomOut() {
        var controls = CameraControls()
        let initialRadius = controls.orbitRadius

        controls.handlePinch(0.5)  // Pinch in = zoom out

        XCTAssertGreaterThan(controls.orbitRadius, initialRadius)
    }

    func testPinchClampedToMinRadius() {
        var controls = CameraControls()
        controls.minRadius = 1.0

        // Extreme zoom in
        controls.handlePinch(1000.0)

        XCTAssertGreaterThanOrEqual(controls.orbitRadius, controls.minRadius)
    }

    func testPinchClampedToMaxRadius() {
        var controls = CameraControls()
        controls.maxRadius = 10.0

        // Extreme zoom out
        controls.handlePinch(0.001)

        XCTAssertLessThanOrEqual(controls.orbitRadius, controls.maxRadius)
    }

    // MARK: - Inertia

    func testInertiaDecays() {
        var controls = CameraControls()
        controls.inertiaVelocity = CGSize(width: 100, height: 50)

        let active = controls.applyInertia()
        XCTAssertTrue(active)

        // Velocity should have decreased
        XCTAssertLessThan(abs(controls.inertiaVelocity.width), 100)
        XCTAssertLessThan(abs(controls.inertiaVelocity.height), 50)
    }

    func testInertiaStopsAtThreshold() {
        var controls = CameraControls()
        controls.inertiaVelocity = CGSize(width: 0.005, height: 0.005)

        let active = controls.applyInertia()
        XCTAssertFalse(active)
        XCTAssertEqual(controls.inertiaVelocity.width, 0)
        XCTAssertEqual(controls.inertiaVelocity.height, 0)
    }

    func testInertiaDampingFactor() {
        var controls = CameraControls()
        controls.inertiaDamping = 0.5
        controls.inertiaVelocity = CGSize(width: 10, height: 10)

        controls.applyInertia()

        XCTAssertEqual(controls.inertiaVelocity.width, 5.0, accuracy: 0.01)
        XCTAssertEqual(controls.inertiaVelocity.height, 5.0, accuracy: 0.01)
    }

    // MARK: - Auto Rotation

    func testAutoRotationDisabledByDefault() {
        var controls = CameraControls()
        let initialAzimuth = controls.azimuth

        controls.applyAutoRotation(dt: 1.0)

        XCTAssertEqual(controls.azimuth, initialAzimuth)
    }

    func testAutoRotationEnabled() {
        var controls = CameraControls()
        controls.isAutoRotating = true
        controls.autoRotateSpeed = 1.0
        let initialAzimuth = controls.azimuth

        controls.applyAutoRotation(dt: 0.5)

        XCTAssertEqual(controls.azimuth, initialAzimuth + 0.5, accuracy: 0.001)
    }

    // MARK: - Camera Transform Matrix

    func testCameraTransformIsValid4x4() {
        let controls = CameraControls()
        let transform = controls.cameraTransform()

        // Should be a valid 4x4 matrix (last row = [0, 0, 0, 1] is not
        // guaranteed for a view matrix built this way, but the last column
        // should have w=1)
        XCTAssertEqual(transform.columns.3.w, 1.0, accuracy: 0.001)
    }

    func testSceneRotationIsUnitQuaternion() {
        let controls = CameraControls()
        let rot = controls.sceneRotation()
        let length = sqrt(
            rot.real * rot.real +
            rot.imag.x * rot.imag.x +
            rot.imag.y * rot.imag.y +
            rot.imag.z * rot.imag.z
        )
        XCTAssertEqual(length, 1.0, accuracy: 0.001)
    }

    // MARK: - Fit-to-bounds framing (#1026 / #1041)

    func testFitRadiusFramesBoundingSphere() {
        // A 1m cube at 60° vertical FOV with a square viewport (aspect 1):
        // both FOVs equal, halfFov = 30°, sphereRadius = sqrt(3)/2 ≈ 0.866.
        // distance = 0.866 / sin(30°) = 1.732, × 1.15 margin ≈ 1.99.
        let controls = CameraControls()
        let r = controls.fitRadius(
            boundsExtents: SIMD3<Float>(1, 1, 1),
            fovYDegrees: 60,
            aspect: 1.0,
            margin: 1.15
        )
        XCTAssertEqual(r, 1.99, accuracy: 0.05)
    }

    func testFitRadiusLargerForBiggerContent() {
        // Scaling the box up scales the fit distance linearly.
        let controls = CameraControls()
        let small = controls.fitRadius(
            boundsExtents: SIMD3<Float>(1, 1, 1), fovYDegrees: 60, aspect: 1.0)
        let big = controls.fitRadius(
            boundsExtents: SIMD3<Float>(4, 4, 4), fovYDegrees: 60, aspect: 1.0)
        XCTAssertEqual(big / small, 4.0, accuracy: 0.1)
    }

    func testFitRadiusPortraitNeedsMoreDistanceThanSquare() {
        // A portrait viewport (aspect < 1) has a narrower horizontal FOV,
        // so the camera must sit farther back to fit the same content
        // (#1041 — wide rows of primitives clipped on a phone in portrait).
        let controls = CameraControls(mode: .orbit, maxRadius: 500)
        let square = controls.fitRadius(
            boundsExtents: SIMD3<Float>(2, 1, 1), fovYDegrees: 60, aspect: 1.0)
        let portrait = controls.fitRadius(
            boundsExtents: SIMD3<Float>(2, 1, 1), fovYDegrees: 60, aspect: 0.46)
        XCTAssertGreaterThan(portrait, square)
    }

    func testFitRadiusClampsToRadiusLimits() {
        var controls = CameraControls()
        controls.minRadius = 1.0
        controls.maxRadius = 10.0
        // Huge content clamps to maxRadius.
        let huge = controls.fitRadius(
            boundsExtents: SIMD3<Float>(1000, 1000, 1000), fovYDegrees: 60)
        XCTAssertEqual(huge, 10.0, accuracy: 0.001)
        // Tiny content clamps to minRadius.
        let tiny = controls.fitRadius(
            boundsExtents: SIMD3<Float>(0.001, 0.001, 0.001), fovYDegrees: 60)
        XCTAssertEqual(tiny, 1.0, accuracy: 0.001)
    }

    func testFitRadiusRejectsDegenerateBounds() {
        // Empty / non-finite bounds fall back to the current orbitRadius
        // so an async-loading model never snaps the camera to a bad pose.
        var controls = CameraControls()
        controls.orbitRadius = 3.0
        let zero = controls.fitRadius(
            boundsExtents: SIMD3<Float>(0, 0, 0), fovYDegrees: 60)
        XCTAssertEqual(zero, 3.0, accuracy: 0.001)
        let infinite = controls.fitRadius(
            boundsExtents: SIMD3<Float>(.infinity, .infinity, .infinity),
            fovYDegrees: 60)
        XCTAssertEqual(infinite, 3.0, accuracy: 0.001)
    }

    func testFitRadiusMarginAddsBreathingRoom() {
        let controls = CameraControls()
        let tight = controls.fitRadius(
            boundsExtents: SIMD3<Float>(1, 1, 1), fovYDegrees: 60,
            aspect: 1.0, margin: 1.0)
        let padded = controls.fitRadius(
            boundsExtents: SIMD3<Float>(1, 1, 1), fovYDegrees: 60,
            aspect: 1.0, margin: 1.3)
        XCTAssertEqual(padded / tight, 1.3, accuracy: 0.01)
    }

    // MARK: - Content bounds union (#1391)

    func testUnionOfEmptyArrayIsNil() {
        XCTAssertNil(ContentBounds.union(of: []))
    }

    func testUnionOfSingleBoxRoundTrips() {
        let box = BoundingBox(min: SIMD3<Float>(-1, -2, -3),
                              max: SIMD3<Float>(1, 2, 3))
        let union = ContentBounds.union(of: [box])
        XCTAssertNotNil(union)
        XCTAssertEqual(union!.min, box.min)
        XCTAssertEqual(union!.max, box.max)
    }

    func testUnionOfTwoSeparatedBoxesSpansBoth() {
        // Mirrors `Multi-Model Park`: models placed apart on the X axis.
        // The union must span the full extent, not frame a single box.
        let left = BoundingBox(min: SIMD3<Float>(-2.0, -0.5, -0.5),
                               max: SIMD3<Float>(-1.0, 0.5, 0.5))
        let right = BoundingBox(min: SIMD3<Float>(1.0, -0.5, -0.5),
                                max: SIMD3<Float>(2.0, 0.5, 0.5))
        let union = ContentBounds.union(of: [left, right])
        XCTAssertNotNil(union)
        XCTAssertEqual(union!.min, SIMD3<Float>(-2.0, -0.5, -0.5))
        XCTAssertEqual(union!.max, SIMD3<Float>(2.0, 0.5, 0.5))
        // Centre is the midpoint of the combined span — what the camera
        // pivots on. A single-box frame would centre on -1.5 or +1.5.
        XCTAssertEqual(union!.center.x, 0.0, accuracy: 0.0001)
        // Width spans the full 4 m, not a single 1 m box.
        XCTAssertEqual(union!.extents.x, 4.0, accuracy: 0.0001)
    }

    func testUnionGrowsAsModelsStreamIn() {
        // Each entry simulates one streamed park model landing. The union
        // must strictly grow (or hold) — never shrink — so re-framing
        // dollies the camera out, never frames a stale subset (#1391).
        let book = BoundingBox(min: SIMD3<Float>(-0.5, 0, -0.5),
                               max: SIMD3<Float>(0.5, 1, 0.5))
        let bird = BoundingBox(min: SIMD3<Float>(1.0, 0.5, -0.5),
                               max: SIMD3<Float>(1.3, 0.8, -0.2))
        let figure = BoundingBox(min: SIMD3<Float>(-1.5, 0, -0.5),
                                 max: SIMD3<Float>(-1.0, 1.8, 0.5))
        let afterOne = ContentBounds.union(of: [book])!
        let afterTwo = ContentBounds.union(of: [book, bird])!
        let afterThree = ContentBounds.union(of: [book, bird, figure])!
        XCTAssertGreaterThanOrEqual(afterTwo.extents.x, afterOne.extents.x)
        XCTAssertGreaterThanOrEqual(afterThree.extents.x, afterTwo.extents.x)
        XCTAssertGreaterThanOrEqual(afterThree.extents.y, afterTwo.extents.y)
        // Final union spans every model's extreme corner.
        XCTAssertEqual(afterThree.min, SIMD3<Float>(-1.5, 0, -0.5))
        XCTAssertEqual(afterThree.max, SIMD3<Float>(1.3, 1.8, 0.5))
    }

    func testUnionSkipsEmptyInvertedBox() {
        // RealityKit reports min = +∞, max = -∞ for an entity whose mesh
        // has not populated yet. Such a box must not poison the union.
        let real = BoundingBox(min: SIMD3<Float>(-1, -1, -1),
                               max: SIMD3<Float>(1, 1, 1))
        let empty = BoundingBox(
            min: SIMD3<Float>(repeating: .greatestFiniteMagnitude),
            max: SIMD3<Float>(repeating: -.greatestFiniteMagnitude))
        let union = ContentBounds.union(of: [empty, real, empty])
        XCTAssertNotNil(union)
        XCTAssertEqual(union!.min, real.min)
        XCTAssertEqual(union!.max, real.max)
    }

    func testUnionOfOnlyEmptyBoxesIsNil() {
        let empty = BoundingBox(
            min: SIMD3<Float>(repeating: .greatestFiniteMagnitude),
            max: SIMD3<Float>(repeating: -.greatestFiniteMagnitude))
        XCTAssertNil(ContentBounds.union(of: [empty, empty]))
    }

    func testUnionSkipsNonFiniteBox() {
        let real = BoundingBox(min: SIMD3<Float>(-1, -1, -1),
                               max: SIMD3<Float>(1, 1, 1))
        let nan = BoundingBox(min: SIMD3<Float>(.nan, 0, 0),
                              max: SIMD3<Float>(1, 1, 1))
        let union = ContentBounds.union(of: [nan, real])
        XCTAssertNotNil(union)
        XCTAssertEqual(union!.min, real.min)
        XCTAssertEqual(union!.max, real.max)
    }

    // MARK: - Framing stability tracker (#1391 retry)

    func testStabilityTrackerNeverStableOnFirstSample() {
        // The first valid sample just starts the hold window — a single
        // tick can never be "stable", or a one-model-loaded scene would
        // latch instantly (the #1514 bug).
        var tracker = FramingStabilityTracker(stableHoldSeconds: 2.5)
        XCTAssertFalse(tracker.register(diagonal: 1.0, now: 0.0))
    }

    func testStabilityTrackerNotStableBeforeHoldWindowElapses() {
        // Diagonal unchanged but the hold window has not elapsed yet —
        // must NOT latch. This is the exact scenario #1514 mishandled:
        // two adjacent ticks inside a streaming gap saw an equal diagonal
        // and latched on a partial union.
        var tracker = FramingStabilityTracker(stableHoldSeconds: 2.5)
        _ = tracker.register(diagonal: 1.0, now: 0.0)
        XCTAssertFalse(tracker.register(diagonal: 1.0, now: 0.033))
        XCTAssertFalse(tracker.register(diagonal: 1.0, now: 1.0))
        XCTAssertFalse(tracker.register(diagonal: 1.0, now: 2.4))
    }

    func testStabilityTrackerLatchesAfterSustainedHold() {
        // Once the diagonal holds steady for the full hold window the
        // scene is genuinely settled and the latch fires.
        var tracker = FramingStabilityTracker(stableHoldSeconds: 2.5)
        _ = tracker.register(diagonal: 1.0, now: 0.0)
        XCTAssertFalse(tracker.register(diagonal: 1.0, now: 1.0))
        XCTAssertTrue(tracker.register(diagonal: 1.0, now: 2.5))
    }

    func testStabilityTrackerGrowthResetsHoldTimer() {
        // A streamed model that grows the union must re-arm the hold timer
        // so the camera re-frames for it. This is the core #1391 fix:
        // model #2 lands well after model #1, and the latch must wait.
        var tracker = FramingStabilityTracker(stableHoldSeconds: 2.5)
        _ = tracker.register(diagonal: 1.0, now: 0.0)
        // Model #1 union held steady for 3 s — would have latched...
        XCTAssertTrue(tracker.register(diagonal: 1.0, now: 3.0))
        // ...but then model #2 streams in and grows the union. Re-arm.
        XCTAssertFalse(tracker.register(diagonal: 5.0, now: 5.0))
        // Still streaming — model #3 grows it again at t=30 s.
        XCTAssertFalse(tracker.register(diagonal: 5.0, now: 6.0))
        XCTAssertFalse(tracker.register(diagonal: 8.0, now: 30.0))
        // No more models for 2.5 s — now genuinely stable.
        XCTAssertFalse(tracker.register(diagonal: 8.0, now: 31.0))
        XCTAssertTrue(tracker.register(diagonal: 8.0, now: 32.5))
    }

    func testStabilityTrackerSimulatesMultiModelParkStreaming() {
        // End-to-end: four park models stream in over ~60 s with
        // multi-second gaps. The latch must fire ONLY after the fourth
        // model has landed and held — never on a partial union.
        var tracker = FramingStabilityTracker(stableHoldSeconds: 2.5)
        // t=2 s: tree lands (union diagonal ~3 m).
        XCTAssertFalse(tracker.register(diagonal: 3.0, now: 2.0))
        // t=4 s: still just the tree — #1514 would latch here.
        XCTAssertFalse(tracker.register(diagonal: 3.0, now: 4.0))
        // t=18 s: bench lands, union grows.
        XCTAssertFalse(tracker.register(diagonal: 3.4, now: 18.0))
        // t=39 s: dog lands.
        XCTAssertFalse(tracker.register(diagonal: 3.6, now: 39.0))
        // t=62 s: bird lands — the last model.
        XCTAssertFalse(tracker.register(diagonal: 3.7, now: 62.0))
        // t=63 s: still inside the hold window.
        XCTAssertFalse(tracker.register(diagonal: 3.7, now: 63.0))
        // t=64.5 s: union has held 2.5 s since the bird — latch.
        XCTAssertTrue(tracker.register(diagonal: 3.7, now: 64.5))
    }

    func testStabilityTrackerRejectsNonFiniteDiagonal() {
        var tracker = FramingStabilityTracker()
        XCTAssertFalse(tracker.register(diagonal: .nan, now: 0.0))
        XCTAssertFalse(tracker.register(diagonal: .infinity, now: 0.0))
        XCTAssertFalse(tracker.register(diagonal: 0.0, now: 0.0))
        // A rejected sample must not start the hold window.
        XCTAssertNil(tracker.lastChangeTime)
    }

    func testStabilityTrackerSingleModelLatchesAfterHold() {
        // A single-model demo (Model Viewer): one bounds sample, then the
        // diagonal never changes. It must still latch after the hold
        // window — no regression vs the streamed path, just one extra
        // sustained-hold delay before the camera settles.
        var tracker = FramingStabilityTracker(stableHoldSeconds: 2.5)
        XCTAssertFalse(tracker.register(diagonal: 2.0, now: 0.0))
        XCTAssertFalse(tracker.register(diagonal: 2.0, now: 1.0))
        XCTAssertTrue(tracker.register(diagonal: 2.0, now: 2.5))
    }
}
#endif

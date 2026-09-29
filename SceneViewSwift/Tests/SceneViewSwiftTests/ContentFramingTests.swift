import XCTest
import RealityKit
@testable import SceneViewSwift

#if os(iOS) || os(visionOS)
/// Framing math behind "every model opens correctly framed, and Recenter /
/// Reset put it back" — the iOS QA findings of 2026-09-10.
///
/// The bugs these lock down were all invisible to the type system: a zoom
/// ceiling merged instead of assigned, a stability latch that never re-armed,
/// and a camera authored inside the model file quietly taking over the view.
@MainActor
final class ContentFramingTests: XCTestCase {

    // The values `SceneView` uses when it fits content.
    private let fov: Float = 60
    private let aspect: Float = 0.46

    /// The limit derivation `refreshContentCentering()` applies.
    private func limits(forExtents extents: SIMD3<Float>) -> (min: Float, max: Float) {
        CameraControls.zoomLimits(forContentExtents: extents)!
    }

    // MARK: - Zoom floor (#4009)

    /// Pinching all the way in must leave the camera outside the model: the
    /// floor clears the sphere that circumscribes the bounds, so no vertex of
    /// the geometry can be at or behind the camera, whatever the orbit angle.
    func testZoomFloorKeepsTheCameraOutsideTheContent() {
        let cases: [SIMD3<Float>] = [
            SIMD3(0.6, 0.6, 0.55),   // Damaged Helmet, scaled to 0.6 units
            SIMD3(2.0, 0.8, 0.9),    // a sofa
            SIMD3(0.06, 0.03, 0.06), // a toy car
            SIMD3(30, 12, 30),       // a room-scale scene
        ]
        for extents in cases {
            let sphereRadius = simd_length(extents * 0.5)
            let floor = limits(forExtents: extents).min
            XCTAssertGreaterThan(floor, sphereRadius, "\(extents): the floor sits inside the content")

            var camera = CameraControls()
            (camera.minRadius, camera.maxRadius) = limits(forExtents: extents)
            camera.orbitRadius = camera.fitRadius(
                boundsExtents: extents, fovYDegrees: fov, aspect: 1.43)
            camera.handlePinch(1000) // a wild pinch-in
            let corner = extents * 0.5
            for azimuth in stride(from: Float(0), to: 2 * .pi, by: .pi / 8) {
                camera.azimuth = azimuth
                let eye = camera.cameraPosition()
                // The eye is outside the bounding box on at least one axis.
                let outside = abs(eye.x) > corner.x || abs(eye.y) > corner.y || abs(eye.z) > corner.z
                XCTAssertTrue(outside, "\(extents) az \(azimuth): camera inside the bounds")
            }
        }
    }

    /// The floor must still leave real zoom-in room: on an iPad-landscape
    /// frame the fitted distance is well above it.
    func testZoomFloorLeavesRoomToZoomIn() {
        let extents = SIMD3<Float>(0.6, 0.6, 0.55)
        var camera = CameraControls()
        (camera.minRadius, camera.maxRadius) = limits(forExtents: extents)
        let fit = camera.fitRadius(boundsExtents: extents, fovYDegrees: fov, aspect: 1.43, margin: 1.12)
        XCTAssertGreaterThan(fit, camera.minRadius * 1.5)
    }

    func testZoomLimitsRejectDegenerateBounds() {
        XCTAssertNil(CameraControls.zoomLimits(forContentExtents: .zero))
        XCTAssertNil(CameraControls.zoomLimits(forContentExtents: SIMD3(.nan, 1, 1)))
    }

    // MARK: - Bounds → distance

    func testFitDistanceScalesWithContentSize() {
        var small = CameraControls()
        let smallExtents = SIMD3<Float>(0.06, 0.03, 0.06)
        (small.minRadius, small.maxRadius) = limits(forExtents: smallExtents)
        let smallFit = small.fitRadius(boundsExtents: smallExtents, fovYDegrees: fov, aspect: aspect)

        var large = CameraControls()
        let largeExtents = SIMD3<Float>(30, 12, 30)
        (large.minRadius, large.maxRadius) = limits(forExtents: largeExtents)
        let largeFit = large.fitRadius(boundsExtents: largeExtents, fovYDegrees: fov, aspect: aspect)

        // A 500× bigger subject must be framed from proportionally further
        // away — not clamped to a fixed default that frames neither.
        XCTAssertGreaterThan(largeFit, smallFit * 100)
        // And neither fit may be sitting on a clamp: a fit pinned to a limit
        // is exactly the "opens as an extreme close-up" symptom.
        XCTAssertGreaterThan(smallFit, small.minRadius)
        XCTAssertLessThan(smallFit, small.maxRadius)
        XCTAssertGreaterThan(largeFit, large.minRadius)
        XCTAssertLessThan(largeFit, large.maxRadius)
    }

    /// The zoom limits must be ASSIGNED from the current content, never merged
    /// with the outgoing subject's. Merging (`max(maxRadius, …)`) kept a
    /// room-scale ceiling around a 6 cm model, and the stale floor clamped the
    /// fit above the distance that frames it — "this model opened zoomed in
    /// and will not zoom out".
    func testZoomLimitsDoNotInheritThePreviousSubject() {
        var camera = CameraControls()
        // Previous subject: a 30 m scene.
        let roomExtents = SIMD3<Float>(30, 12, 30)
        (camera.minRadius, camera.maxRadius) = limits(forExtents: roomExtents)
        let staleMin = camera.minRadius
        XCTAssertGreaterThan(staleMin, 1)

        // New subject: a 6 cm toy car.
        let toyExtents = SIMD3<Float>(0.06, 0.03, 0.06)
        (camera.minRadius, camera.maxRadius) = limits(forExtents: toyExtents)
        XCTAssertLessThan(camera.minRadius, staleMin)

        let fit = camera.fitRadius(boundsExtents: toyExtents, fovYDegrees: fov, aspect: aspect)
        XCTAssertLessThan(fit, staleMin, "the fit must not be clamped by the previous subject's floor")
        // The user can still pinch out to see the whole thing with room to spare.
        XCTAssertGreaterThan(camera.maxRadius, fit * 4)
    }

    func testFitIsAzimuthInvariant() {
        var camera = CameraControls()
        let extents = SIMD3<Float>(2, 0.5, 0.4)
        (camera.minRadius, camera.maxRadius) = limits(forExtents: extents)
        let front = camera.fitRadius(boundsExtents: extents, fovYDegrees: fov, aspect: aspect)
        camera.azimuth = .pi / 2
        let side = camera.fitRadius(boundsExtents: extents, fovYDegrees: fov, aspect: aspect)
        XCTAssertEqual(front, side, accuracy: 0.0001)
    }

    // MARK: - A host pose write takes the camera (#4184)

    /// `.cameraPose(_:)` animated frame by frame (the Model Viewer's fly-in)
    /// must not be pulled back to the fit while the bounds settle: a write that
    /// moves the camera takes it, as a drag does, and the fit pass then leaves
    /// it alone unless the content grew materially.
    func testPoseWriteThatMovesTheCameraTakesItFromTheFit() {
        let live = SceneCameraPose(azimuth: 0, elevation: 0.21, distance: 2, target: [0, 0.1, 0])
        var flown = live
        flown.azimuth = 0.3
        flown.distance = 3.1
        XCTAssertTrue(SceneView.poseWriteTakesCamera(requested: flown, live: live))

        // The fit keeps a taken camera over settling bounds…
        XCTAssertTrue(SceneView.fitKeepsTakenCamera(taken: true, diagonal: 1.1, fittedDiagonal: 1))
        // …takes it back when another model lands and the union grows…
        XCTAssertFalse(SceneView.fitKeepsTakenCamera(taken: true, diagonal: 1.3, fittedDiagonal: 1))
        // …and re-fits freely a camera nobody took.
        XCTAssertFalse(SceneView.fitKeepsTakenCamera(taken: false, diagonal: 1.1, fittedDiagonal: 1))
    }

    /// The mirrored-state pattern (`onCameraChanged` → state → `cameraPose`)
    /// hands the live pose straight back: that echo moves nothing, so it must
    /// not take the camera, or a host that merely mirrors it would freeze the
    /// fit for good.
    func testEchoOfTheLivePoseDoesNotTakeTheCamera() {
        let live = SceneCameraPose(azimuth: 1.2, elevation: -0.1, distance: 4, target: [1, 2, 3])
        XCTAssertFalse(SceneView.poseWriteTakesCamera(requested: live, live: live))
        var jitter = live
        jitter.distance += 0.00005
        XCTAssertFalse(SceneView.poseWriteTakesCamera(requested: jitter, live: live))
    }

    // MARK: - Reset restores the initial state

    /// A Recenter / Reset re-arms the framing pass, so the *next* pass over
    /// unchanged bounds must land on exactly the radius the model opened at.
    func testRecenterRestoresTheOpeningFraming() {
        var camera = CameraControls()
        let extents = SIMD3<Float>(0.5958, 0.2338, 0.6)
        (camera.minRadius, camera.maxRadius) = limits(forExtents: extents)
        let opening = camera.fitRadius(boundsExtents: extents, fovYDegrees: fov, aspect: aspect)
        camera.orbitRadius = opening
        camera.target = SIMD3<Float>(0.1, 0.2, -0.3)

        // The user pinches in and orbits away.
        camera.orbitRadius = camera.minRadius
        camera.azimuth = 2.1
        camera.elevation = -0.4
        camera.target = SIMD3<Float>(1, 1, 1)

        // Recenter: authored angles back, then a fresh fit over the same bounds.
        camera.azimuth = 0
        camera.elevation = CameraControls().elevation
        camera.target = SIMD3<Float>(0.1, 0.2, -0.3)
        (camera.minRadius, camera.maxRadius) = limits(forExtents: extents)
        camera.orbitRadius = camera.fitRadius(boundsExtents: extents, fovYDegrees: fov, aspect: aspect)

        XCTAssertEqual(camera.orbitRadius, opening, accuracy: 0.0001)
    }

    /// Re-arming the tracker is what makes the second model re-frame at all:
    /// a latched tracker compares the new bounds against the *old* diagonal.
    func testReArmedStabilityTrackerRequiresAFreshHoldWindow() {
        var tracker = FramingStabilityTracker(epsilon: 0.01, stableHoldSeconds: 2.5)
        XCTAssertFalse(tracker.register(diagonal: 1.0, now: 0))
        XCTAssertFalse(tracker.register(diagonal: 1.0, now: 1))
        XCTAssertTrue(tracker.register(diagonal: 1.0, now: 3))

        // New model → re-arm. The fresh tracker must not latch immediately,
        // even though the diagonal it is handed never moves.
        tracker = FramingStabilityTracker(epsilon: 0.01, stableHoldSeconds: 2.5)
        XCTAssertFalse(tracker.register(diagonal: 0.6, now: 10))
        XCTAssertFalse(tracker.register(diagonal: 0.6, now: 11))
        XCTAssertTrue(tracker.register(diagonal: 0.6, now: 13))
    }

    // MARK: - Bounds accumulation

    func testAccumulatorUnionsBoxesWithoutAllocating() {
        var accumulator = ContentBounds.Accumulator()
        XCTAssertNil(accumulator.result)
        accumulator.add(BoundingBox(min: [-1, 0, -1], max: [0, 1, 0]))
        accumulator.add(BoundingBox(min: [0, -2, 0], max: [2, 0, 3]))
        let union = accumulator.result
        XCTAssertEqual(union?.min ?? .zero, SIMD3<Float>(-1, -2, -1))
        XCTAssertEqual(union?.max ?? .zero, SIMD3<Float>(2, 1, 3))
    }

    func testAccumulatorRejectsEmptyAndNonFiniteBoxes() {
        var accumulator = ContentBounds.Accumulator()
        // RealityKit's "no visual content" box: min = +inf, max = -inf.
        accumulator.add(BoundingBox(
            min: SIMD3<Float>(repeating: .infinity),
            max: SIMD3<Float>(repeating: -.infinity)
        ))
        XCTAssertNil(accumulator.result, "an empty box must not poison the union")
        accumulator.add(BoundingBox(min: [-0.5, -0.5, -0.5], max: [0.5, 0.5, 0.5]))
        XCTAssertEqual(accumulator.result?.extents ?? .zero, SIMD3<Float>(1, 1, 1))
    }
}
#endif

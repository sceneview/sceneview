#if os(iOS)
import XCTest
import ARKit
import RealityKit
@testable import SceneViewSwift

/// Instant placement rules, device-free. The ARKit half (estimated planes, tracked raycasts,
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

    // MARK: Source precedence — Kotlin `placementHitSource`

    func testPlaneAlwaysWins() {
        for instant in [false, true] {
            for estimated in [false, true] {
                XCTAssertEqual(ARInstantPlacement.source(hasPlane: true, instantPlacement: instant,
                                                         hasEstimatedSurface: estimated), .plane)
            }
        }
    }

    func testWithoutInstantPlacementNothingButAPlaneIsPlaced() {
        XCTAssertEqual(ARInstantPlacement.source(hasPlane: false, instantPlacement: false, hasEstimatedSurface: true), .none)
        XCTAssertEqual(ARInstantPlacement.source(hasPlane: false, instantPlacement: false, hasEstimatedSurface: false), .none)
    }

    func testInstantPlacementPrefersTheEstimatedSurfaceThenFallsBackToFixedDistance() {
        XCTAssertEqual(ARInstantPlacement.source(hasPlane: false, instantPlacement: true, hasEstimatedSurface: true), .estimatedSurface)
        XCTAssertEqual(ARInstantPlacement.source(hasPlane: false, instantPlacement: true, hasEstimatedSurface: false), .fixedDistance)
    }

    // MARK: Tracking method — approximate → fullTracking

    func testTrackingMethodFollowsTheSurface() {
        XCTAssertNil(ARInstantPlacement.trackingMethod(hasPlacement: false, onSurface: false))
        XCTAssertNil(ARInstantPlacement.trackingMethod(hasPlacement: false, onSurface: true))
        XCTAssertEqual(ARInstantPlacement.trackingMethod(hasPlacement: true, onSurface: false), .approximate)
        XCTAssertEqual(ARInstantPlacement.trackingMethod(hasPlacement: true, onSurface: true), .fullTracking)
    }

    func testTrackingMethodNamesMatchAndroid() {
        // Raw values are stable for persistence and analytics; Kotlin uses the same two states.
        XCTAssertEqual(ARPlacementTrackingMethod.approximate.rawValue, "approximate")
        XCTAssertEqual(ARPlacementTrackingMethod.fullTracking.rawValue, "fullTracking")
    }

    // MARK: Fixed-distance fallback

    func testFallbackIsOneMetreAlongTheCentreRay() {
        XCTAssertEqual(ARInstantPlacement.approximateDistance, 1)
        assertClose(ARInstantPlacement.fixedDistancePoint(camera: matrix_identity_float4x4), [0, 0, -1])
        let tilted = camera(at: eye, looking: [0, -1, -1])
        let point = ARInstantPlacement.fixedDistancePoint(camera: tilted)
        XCTAssertEqual(simd_length(point - eye), 1, accuracy: 0.0001)
        assertClose(simd_normalize(point - eye), simd_normalize([0, -1, -1]))
    }

    func testApproximateContactStandsUprightFacingTheCamera() {
        for forward: SIMD3<Float> in [[0, -0.5, -1], [1, -0.3, 0], [-0.4, -1, 0.7]] {
            let cam = camera(at: eye, looking: forward)
            let point = ARInstantPlacement.fixedDistancePoint(camera: cam)
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
        let point = ARInstantPlacement.fixedDistancePoint(camera: cam)
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
        XCTAssertFalse(ARInstantPlacement.acceptsEstimatedHit(point: [0, 0, -5], normal: [0, 1, 0],
                                                              camera: down, alignment: .horizontal), "beyond 3 m")
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

    // MARK: Glide

    func testGlideEasesFromTheEstimateToTheSurfaceWithoutAJump() {
        let from = Transform(rotation: simd_quatf(angle: 0, axis: [0, 1, 0]), translation: [0, 0.4, -1])
        let to = Transform(rotation: simd_quatf(angle: 0.6, axis: [0, 1, 0]), translation: [0, 0, -1.6])
        var glide = ARInstantPlacement.Glide(from: from, to: to, duration: ARInstantPlacement.refineDuration)
        assertClose(glide.translation, from.translation)
        XCTAssertFalse(glide.isFinished)
        var previous: Float = 0
        var previousTranslation = glide.translation
        let frame = 1.0 / 60
        while !glide.isFinished {
            glide.advance(by: frame)
            XCTAssertGreaterThanOrEqual(glide.progress, previous)
            // No visible jump: one 60 Hz frame never covers more than a fifth of the way
            // (cubic ease-out peaks at 3 / 18 frames ≈ 16.7 % on the first frame).
            XCTAssertLessThan(simd_length(glide.translation - previousTranslation),
                              0.2 * simd_length(to.translation - from.translation))
            previous = glide.progress
            previousTranslation = glide.translation
        }
        XCTAssertEqual(glide.progress, 1)
        assertClose(glide.translation, to.translation)
        XCTAssertGreaterThan(abs(simd_dot(glide.rotation.vector, to.rotation.vector)), 0.9999)
        XCTAssertEqual(glide.elapsed, ARInstantPlacement.refineDuration, accuracy: 0.0001)
    }

    func testGlideIsEaseOut() {
        var glide = ARInstantPlacement.Glide(from: Transform(), to: Transform(translation: [1, 0, 0]), duration: 1)
        glide.advance(by: 0.5)
        XCTAssertEqual(glide.progress, 0.875, accuracy: 0.0001)
    }

    func testReduceMotionGlideSnaps() {
        let glide = ARInstantPlacement.Glide(from: Transform(), to: Transform(translation: [0, -1, 0]), duration: 0)
        XCTAssertTrue(glide.isFinished)
        assertClose(glide.translation, [0, -1, 0])
    }

    func testGlideIgnoresInvalidTimeSteps() {
        var glide = ARInstantPlacement.Glide(from: Transform(), to: Transform(translation: [1, 0, 0]), duration: 0.3)
        glide.advance(by: -1)
        glide.advance(by: .nan)
        XCTAssertEqual(glide.elapsed, 0)
        glide.advance(by: 10)
        XCTAssertTrue(glide.isFinished)
        XCTAssertEqual(glide.elapsed, 0.3, accuracy: 0.0001)
    }
}
#endif

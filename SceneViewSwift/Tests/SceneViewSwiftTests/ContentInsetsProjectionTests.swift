import XCTest
import simd
@testable import SceneViewSwift

#if os(iOS) || os(macOS) || os(visionOS)
/// The contract of ``SceneView/contentInsets(_:)``, on the **production** math
/// (``ContentInsetsProjection``, ``CameraControls/radiusKeepingZoom(boundsExtents:from:to:margin:clamped:)``):
/// where the optical centre goes, when the image shrinks, what the fit pass
/// fits to, that a tap lands on what is drawn, and that a rotation or a panel
/// keeps the user's zoom.
final class ContentInsetsProjectionTests: XCTestCase {

    // iPhone 17 Pro, in points.
    private let portrait = SIMD2<Float>(402, 874)
    private let landscape = SIMD2<Float>(874, 402)
    private let fov: Float = 60

    private func projection(
        _ size: SIMD2<Float>, top: Float = 0, left: Float = 0, bottom: Float = 0, right: Float = 0
    ) -> ContentInsetsProjection {
        ContentInsetsProjection(
            viewWidth: size.x, viewHeight: size.y, top: top, left: left, bottom: bottom, right: right
        )
    }

    /// NDC (y up) of a point given in view points (y down).
    private func ndc(x: Float, y: Float, in size: SIMD2<Float>) -> SIMD2<Float> {
        SIMD2<Float>(2 * x / size.x - 1, 1 - 2 * y / size.y)
    }

    // MARK: - Shift

    func testNoInsetsIsIdentity() {
        let p = projection(portrait)
        XCTAssertTrue(p.isIdentity)
        XCTAssertEqual(p, .identity)
    }

    func testOpticalCentreLandsAtTheCentreOfTheVisibleRectangle() {
        // Sheet covering the bottom 400 pt, header covering the top 100 pt:
        // the visible rectangle spans y 100...474, centre 287.
        let p = projection(portrait, top: 100, bottom: 400)
        let centre = p.normalizedDeviceCoordinates(
            ofViewPoint: SIMD3<Float>(0, 0, -3), fovYDegrees: fov, aspect: portrait.x / portrait.y
        )
        let expected = ndc(x: 201, y: 287, in: portrait)
        XCTAssertEqual(centre!.x, expected.x, accuracy: 1e-5)
        XCTAssertEqual(centre!.y, expected.y, accuracy: 1e-5)
    }

    func testSideInsetShiftsHorizontally() {
        // A 330 pt panel on the right of a landscape view: visible x 0...544.
        let p = projection(landscape, right: 330)
        let expected = ndc(x: 272, y: 201, in: landscape)
        XCTAssertEqual(p.shift.x, expected.x, accuracy: 1e-5)
        XCTAssertEqual(p.shift.y, 0, accuracy: 1e-6)
    }

    // MARK: - Scale: the visible rectangle is the viewport

    func testBottomSheetScalesByTheHeightItLeaves() {
        XCTAssertEqual(projection(portrait, bottom: 437).scale, 0.5, accuracy: 1e-6)
        XCTAssertEqual(projection(landscape, bottom: 201).scale, 0.5, accuracy: 1e-6)
        XCTAssertEqual(projection(portrait, top: 160, bottom: 277).scale, 0.5, accuracy: 1e-6)
    }

    func testSidePanelOnlySlides() {
        // The vertical field of view still spans the full height.
        XCTAssertEqual(projection(landscape, right: 330).scale, 1, accuracy: 1e-6)
        XCTAssertEqual(projection(portrait, left: 120).scale, 1, accuracy: 1e-6)
    }

    func testVisibleRectangleBehavesLikeAViewOfItsSize() {
        // A point drawn at a given offset from the centre of a view the size
        // of the visible rectangle is drawn at the same offset, in points,
        // from the centre of the visible rectangle.
        let cases: [(SIMD2<Float>, Float, Float, Float, Float)] = [
            (portrait, 0, 0, 437, 0), (portrait, 160, 0, 520, 0),
            (landscape, 0, 0, 201, 0), (landscape, 0, 0, 0, 330),
            (landscape, 40, 200, 100, 330), (portrait, 0, 120, 0, 0),
        ]
        let point = SIMD3<Float>(0.21, -0.34, -2)
        for (size, top, left, bottom, right) in cases {
            let p = projection(size, top: top, left: left, bottom: bottom, right: right)
            let visible = SIMD2<Float>(size.x - left - right, size.y - top - bottom)
            // In a view of the visible size: points from its centre, y up.
            let small = ContentInsetsProjection.identity.normalizedDeviceCoordinates(
                ofViewPoint: point, fovYDegrees: fov, aspect: visible.x / visible.y
            )! * visible / 2
            // In the inset view: points from the centre of the visible rectangle.
            let full = p.normalizedDeviceCoordinates(
                ofViewPoint: point, fovYDegrees: fov, aspect: size.x / size.y
            )!
            let centre = SIMD2<Float>(left + visible.x / 2, top + visible.y / 2)
            let drawn = SIMD2<Float>((full.x + 1) / 2 * size.x - centre.x,
                                     centre.y - (1 - full.y) / 2 * size.y)
            XCTAssertEqual(drawn.x, small.x, accuracy: 1e-2, "\(size)")
            XCTAssertEqual(drawn.y, small.y, accuracy: 1e-2, "\(size)")
        }
    }

    // MARK: - Sanitising

    func testNegativeAndNonFiniteInsetsCountAsZero() {
        XCTAssertTrue(projection(portrait, top: -40, left: .nan, bottom: -.infinity, right: -1).isIdentity)
    }

    func testInsetsLargerThanTheViewKeepATenthVisible() {
        let p = projection(portrait, bottom: 5_000)
        XCTAssertEqual(p.visibleFraction.y, ContentInsetsProjection.minimumVisibleFraction, accuracy: 1e-5)
        XCTAssertTrue(p.scale > 0)
        XCTAssertTrue(p.shift.y.isFinite)
        // Opposite insets are scaled back together and keep their ratio.
        let both = projection(portrait, top: 2_000, bottom: 6_000)
        XCTAssertEqual(both.visibleFraction.y, ContentInsetsProjection.minimumVisibleFraction, accuracy: 1e-5)
        XCTAssertEqual(both.shift.y, 0.9 * 0.5, accuracy: 1e-4)
    }

    func testDegenerateViewIsIdentity() {
        XCTAssertTrue(ContentInsetsProjection(
            viewWidth: 0, viewHeight: 874, top: 0, left: 0, bottom: 300, right: 0
        ).isIdentity)
    }

    // MARK: - Fit frustum

    func testVisibleFrustumWithoutInsetsIsTheViewFrustum() {
        let frustum = ContentInsetsProjection.identity.visibleFrustum(fovYDegrees: fov, aspect: 0.46)
        XCTAssertEqual(frustum, ViewFrustum(fovYDegrees: fov, aspect: 0.46))
    }

    func testVisibleFrustumSeesExactlyTheVisibleRectangle() {
        let aspect = landscape.x / landscape.y
        let p = projection(landscape, top: 30, left: 60, bottom: 150, right: 330)
        let frustum = p.visibleFrustum(fovYDegrees: fov, aspect: aspect)
        let tanY = tan(frustum.fovYDegrees * .pi / 360)
        let tanX = tanY * frustum.aspect
        // The four corners of the symmetric visible frustum project onto the
        // four corners of the visible rectangle (x 60...544, y 30...252).
        let corners: [(SIMD3<Float>, SIMD2<Float>)] = [
            (SIMD3<Float>(-tanX, tanY, -1), ndc(x: 60, y: 30, in: landscape)),
            (SIMD3<Float>(tanX, tanY, -1), ndc(x: 544, y: 30, in: landscape)),
            (SIMD3<Float>(-tanX, -tanY, -1), ndc(x: 60, y: 252, in: landscape)),
            (SIMD3<Float>(tanX, -tanY, -1), ndc(x: 544, y: 252, in: landscape)),
        ]
        for (direction, expected) in corners {
            let got = p.normalizedDeviceCoordinates(ofViewPoint: direction, fovYDegrees: fov, aspect: aspect)!
            XCTAssertEqual(got.x, expected.x, accuracy: 1e-4)
            XCTAssertEqual(got.y, expected.y, accuracy: 1e-4)
        }
    }

    // MARK: - Projection matrix

    func testMatrixProjectsLikeTheClosedForm() {
        let aspect = portrait.x / portrait.y
        let p = projection(portrait, top: 160, bottom: 520)
        let matrix = p.matrix(fovYDegrees: fov, aspect: aspect, near: 0.01)
        for point in [SIMD3<Float>(0, 0, -2), SIMD3<Float>(0.4, -0.7, -3.5), SIMD3<Float>(-1.2, 0.3, -9)] {
            let clip = matrix * SIMD4<Float>(point, 1)
            let expected = p.normalizedDeviceCoordinates(ofViewPoint: point, fovYDegrees: fov, aspect: aspect)!
            XCTAssertEqual(clip.x / clip.w, expected.x, accuracy: 1e-5)
            XCTAssertEqual(clip.y / clip.w, expected.y, accuracy: 1e-5)
        }
    }

    func testMatrixDepthIsReverseZWithAnInfiniteFarPlane() {
        let matrix = ContentInsetsProjection.identity.matrix(fovYDegrees: fov, aspect: 1, near: 0.01)
        func depth(_ z: Float) -> Float {
            let clip = matrix * SIMD4<Float>(0, 0, z, 1)
            return clip.z / clip.w
        }
        XCTAssertEqual(depth(-0.01), 1, accuracy: 1e-6)
        XCTAssertEqual(depth(-1), 0.01, accuracy: 1e-6)
        XCTAssertEqual(depth(-1e6), 0, accuracy: 1e-6)
    }

    func testIdentityMatrixIsThePlainPerspective() {
        let matrix = ContentInsetsProjection.identity.matrix(fovYDegrees: fov, aspect: 0.5, near: 0.01)
        let yScale = 1 / tan(fov * .pi / 360)
        XCTAssertEqual(matrix.columns.0.x, yScale / 0.5, accuracy: 1e-5)
        XCTAssertEqual(matrix.columns.1.y, yScale, accuracy: 1e-5)
        XCTAssertEqual(matrix.columns.2.x, 0)
        XCTAssertEqual(matrix.columns.2.y, 0)
    }

    // MARK: - Hit-testing

    func testTapRayGoesThroughThePointDrawnUnderTheFinger() {
        // Project a world point to the screen, shoot the tap ray back through
        // that pixel: it must pass through the point. This is "touching the
        // ball lands on the ball" under a shifted, scaled projection.
        let aspect = landscape.x / landscape.y
        let p = projection(landscape, top: 20, bottom: 201, right: 330)
        for point in [SIMD3<Float>(0, 0, -2), SIMD3<Float>(0.6, -0.25, -3), SIMD3<Float>(-1.1, 0.8, -7.5)] {
            let screen = p.normalizedDeviceCoordinates(ofViewPoint: point, fovYDegrees: fov, aspect: aspect)!
            let ray = p.viewRayDirection(
                throughNormalizedDeviceCoordinates: screen, fovYDegrees: fov, aspect: aspect
            )
            let along = simd_dot(point, ray)
            XCTAssertLessThan(simd_length(point - ray * along), 1e-4)
            XCTAssertGreaterThan(along, 0)
        }
    }

    func testTapRayWithoutInsetsIsTheUnshiftedRay() {
        let ray = ContentInsetsProjection.identity.viewRayDirection(
            throughNormalizedDeviceCoordinates: SIMD2<Float>(0.5, -0.25), fovYDegrees: fov, aspect: 2
        )
        let tanY = tan(fov * .pi / 360)
        let expected = simd_normalize(SIMD3<Float>(0.5 * tanY * 2, -0.25 * tanY, -1))
        XCTAssertLessThan(simd_length(ray - expected), 1e-6)
    }

    func testPointBehindTheCameraDoesNotProject() {
        XCTAssertNil(ContentInsetsProjection.identity.normalizedDeviceCoordinates(
            ofViewPoint: SIMD3<Float>(0, 0, 1), fovYDegrees: fov, aspect: 1
        ))
    }

    // MARK: - Keeping the user's zoom

    private let extents = SIMD3<Float>(1.2, 0.8, 0.6)
    private var portraitFrustum: ViewFrustum { ViewFrustum(fovYDegrees: fov, aspect: portrait.x / portrait.y) }
    private var landscapeFrustum: ViewFrustum { ViewFrustum(fovYDegrees: fov, aspect: landscape.x / landscape.y) }

    private func fittedCamera(in frustum: ViewFrustum) -> CameraControls {
        var camera = CameraControls(mode: .orbit)
        camera.minRadius = 0.05
        camera.maxRadius = 100
        camera.orbitRadius = camera.fitRadius(
            boundsExtents: extents, fovYDegrees: frustum.fovYDegrees, aspect: frustum.aspect
        )
        return camera
    }

    func testUntouchedCameraLandsOnTheNewFitAfterARotation() {
        let camera = fittedCamera(in: portraitFrustum)
        let rotated = camera.radiusKeepingZoom(boundsExtents: extents, from: portraitFrustum, to: landscapeFrustum)
        let fresh = camera.fitRadius(
            boundsExtents: extents, fovYDegrees: landscapeFrustum.fovYDegrees, aspect: landscapeFrustum.aspect
        )
        XCTAssertEqual(rotated, fresh, accuracy: 1e-4)
        XCTAssertNotEqual(rotated, camera.orbitRadius, accuracy: 1e-3)
    }

    func testRotationKeepsThePinchedZoomRelativeToTheFit() {
        // The regression: a rotation reset the camera to the fit, discarding
        // the pinch. Twice as close before, twice as close after.
        var camera = fittedCamera(in: portraitFrustum)
        camera.orbitRadius *= 0.5
        let rotated = camera.radiusKeepingZoom(boundsExtents: extents, from: portraitFrustum, to: landscapeFrustum)
        let fresh = camera.fitRadius(
            boundsExtents: extents, fovYDegrees: landscapeFrustum.fovYDegrees, aspect: landscapeFrustum.aspect
        )
        XCTAssertEqual(rotated, fresh * 0.5, accuracy: 1e-4)
    }

    func testRotatingBackRestoresTheRadius() {
        var camera = fittedCamera(in: portraitFrustum)
        camera.orbitRadius *= 0.7
        let start = camera.orbitRadius
        camera.orbitRadius = camera.radiusKeepingZoom(
            boundsExtents: extents, from: portraitFrustum, to: landscapeFrustum
        )
        camera.orbitRadius = camera.radiusKeepingZoom(
            boundsExtents: extents, from: landscapeFrustum, to: portraitFrustum
        )
        XCTAssertEqual(camera.orbitRadius, start, accuracy: 1e-4)
    }

    func testOpeningAPanelKeepsTheZoomAndClosingItRestoresIt() {
        // A wide subject in a portrait view is fitted on its width. A
        // half-height sheet halves the image; the visible rectangle is twice
        // as wide for its height, so the camera comes closer to fill it again.
        let aspect = portrait.x / portrait.y
        let open = projection(portrait, bottom: 437).visibleFrustum(fovYDegrees: fov, aspect: aspect)
        var camera = fittedCamera(in: portraitFrustum)
        camera.orbitRadius *= 0.6
        let start = camera.orbitRadius
        camera.orbitRadius = camera.radiusKeepingZoom(boundsExtents: extents, from: portraitFrustum, to: open)
        XCTAssertLessThan(camera.orbitRadius, start * 0.9)
        camera.orbitRadius = camera.radiusKeepingZoom(boundsExtents: extents, from: open, to: portraitFrustum)
        XCTAssertEqual(camera.orbitRadius, start, accuracy: 1e-4)
    }

    func testAPanelThatLeavesTheLimitingAxisAloneLeavesTheRadiusAlone() {
        // The scale already keeps a height-limited subject inside what a bottom
        // sheet leaves of a landscape view: the radius has nothing to add.
        let aspect = landscape.x / landscape.y
        let open = projection(landscape, bottom: 201).visibleFrustum(fovYDegrees: fov, aspect: aspect)
        let camera = fittedCamera(in: landscapeFrustum)
        XCTAssertEqual(
            camera.radiusKeepingZoom(boundsExtents: extents, from: landscapeFrustum, to: open),
            camera.orbitRadius, accuracy: 1e-4
        )
    }

    func testZoomLimitsDoNotFlattenTheRatioButStillClampTheResult() {
        var camera = fittedCamera(in: portraitFrustum)
        camera.maxRadius = camera.orbitRadius * 0.9   // ceiling below both fits
        camera.orbitRadius = camera.maxRadius * 0.5
        let rotated = camera.radiusKeepingZoom(boundsExtents: extents, from: portraitFrustum, to: landscapeFrustum)
        XCTAssertNotEqual(rotated, camera.orbitRadius, accuracy: 1e-4)
        XCTAssertLessThanOrEqual(rotated, camera.maxRadius)
        XCTAssertGreaterThanOrEqual(rotated, camera.minRadius)
    }

    func testRotatingBackRestoresTheRadiusThroughTheZoomFloor() {
        // The landscape fit of a long subject sits below `minRadius`. Seen on
        // screen: portrait -> landscape -> portrait left the model 14 % smaller.
        var camera = fittedCamera(in: portraitFrustum)
        let start = camera.orbitRadius
        let landscapeFit = camera.fitRadius(
            boundsExtents: extents, fovYDegrees: landscapeFrustum.fovYDegrees, aspect: landscapeFrustum.aspect
        )
        camera.minRadius = landscapeFit * 1.2
        let out = camera.radiusFollowingFrustum(boundsExtents: extents, from: portraitFrustum, to: landscapeFrustum)
        XCTAssertEqual(out.applied, camera.minRadius, accuracy: 1e-5)
        XCTAssertLessThan(out.asked, out.applied)
        camera.orbitRadius = out.applied
        let back = camera.radiusFollowingFrustum(
            boundsExtents: extents, from: landscapeFrustum, to: portraitFrustum, previous: out
        )
        XCTAssertEqual(back.applied, start, accuracy: 1e-4)
        XCTAssertEqual(back.asked, back.applied, accuracy: 1e-6)
    }

    func testAPinchWhileClampedDropsWhatTheLimitsCutOff() {
        // The user zoomed in landscape: the way back starts from where they are.
        var camera = fittedCamera(in: portraitFrustum)
        let landscapeFit = camera.fitRadius(
            boundsExtents: extents, fovYDegrees: landscapeFrustum.fovYDegrees, aspect: landscapeFrustum.aspect
        )
        camera.minRadius = landscapeFit * 1.2
        let out = camera.radiusFollowingFrustum(boundsExtents: extents, from: portraitFrustum, to: landscapeFrustum)
        camera.orbitRadius = out.applied * 1.5
        let back = camera.radiusFollowingFrustum(
            boundsExtents: extents, from: landscapeFrustum, to: portraitFrustum, previous: out
        )
        let ratio = camera.radiusKeepingZoom(
            boundsExtents: extents, from: landscapeFrustum, to: portraitFrustum, clamped: false
        )
        XCTAssertEqual(back.applied, ratio, accuracy: 1e-4)
    }

    func testSameFrustumOrDegenerateBoundsKeepTheRadius() {
        let camera = fittedCamera(in: portraitFrustum)
        XCTAssertEqual(
            camera.radiusKeepingZoom(boundsExtents: extents, from: portraitFrustum, to: portraitFrustum),
            camera.orbitRadius
        )
        XCTAssertEqual(
            camera.radiusKeepingZoom(boundsExtents: .zero, from: portraitFrustum, to: landscapeFrustum),
            camera.orbitRadius
        )
    }
}
#endif

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

    /// #2040: the convenience init must not silently re-introduce the
    /// pre-v4.4.0 `minRadius = 0.5` (which clips the camera into geometry).
    /// A caller using the convenience init purely to set `sensitivity` must
    /// get the same `minRadius` as the primary `init(mode:)`.
    func testConvenienceInitMinRadiusMatchesPrimaryInit() {
        let viaConvenience = CameraControls(mode: .orbit, sensitivity: 0.01)
        let viaPrimary = CameraControls(mode: .orbit)
        XCTAssertEqual(viaConvenience.minRadius, viaPrimary.minRadius)
        XCTAssertEqual(viaConvenience.minRadius, 1.0)
    }

    // MARK: - Camera Position

    func testCameraPositionAtDefaults() {
        let controls = CameraControls()
        let pos = controls.cameraPosition()

        // At azimuth=0, elevation=pi/6, radius=2 (the v4.4.0 default — see
        // `testDefaultInit`; the old radius=5 was removed in that breaking change):
        // x = 2 * cos(pi/6) * sin(0) = 0
        // y = 2 * sin(pi/6) = 1.0
        // z = 2 * cos(pi/6) * cos(0) = 2 * 0.866 = 1.732
        XCTAssertEqual(pos.x, 0.0, accuracy: 0.01)
        XCTAssertEqual(pos.y, 1.0, accuracy: 0.01)
        XCTAssertEqual(pos.z, 1.732, accuracy: 0.01)
    }

    func testCameraPositionAtAzimuth90() {
        var controls = CameraControls()
        controls.azimuth = Float.pi / 2  // 90 degrees
        controls.elevation = 0

        let pos = controls.cameraPosition()
        // radius=2 (v4.4.0 default):
        // x = 2 * cos(0) * sin(pi/2) = 2
        // y = 0
        // z = 2 * cos(0) * cos(pi/2) = 0
        XCTAssertEqual(pos.x, 2.0, accuracy: 0.01)
        XCTAssertEqual(pos.y, 0.0, accuracy: 0.01)
        XCTAssertEqual(pos.z, 0.0, accuracy: 0.01)
    }

    func testCameraPositionWithTarget() {
        var controls = CameraControls()
        controls.target = SIMD3<Float>(1, 2, 3)
        controls.azimuth = 0
        controls.elevation = 0

        let pos = controls.cameraPosition()
        // radius=2 (v4.4.0 default):
        // x = 1 + 2 * 1 * 0 = 1
        // y = 2 + 2 * 0 = 2
        // z = 3 + 2 * 1 * 1 = 5
        XCTAssertEqual(pos.x, 1.0, accuracy: 0.01)
        XCTAssertEqual(pos.y, 2.0, accuracy: 0.01)
        XCTAssertEqual(pos.z, 5.0, accuracy: 0.01)
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

    func testFitRadiusFitsProjectedExtentNotBoundingSphere() {
        // A 1m cube at 60° vertical FOV with a square viewport (aspect 1),
        // seen from the default 30° elevation. The cube's sweep about Y is a
        // cylinder of radius hypot(0.5, 0.5) = 0.707 and half-height 0.5; the
        // vertical axis binds at 1.7247, × 1.15 margin = 1.984.
        //
        // The pre-#3383 sphere fit charged sqrt(3)/2 / sin(30°) = 1.732 here,
        // so a square viewport barely moves — the divergence is in portrait
        // (see testFitRadiusMatrixAcrossViewportsAndSubjects).
        let controls = CameraControls()
        let r = controls.fitRadius(
            boundsExtents: SIMD3<Float>(1, 1, 1),
            fovYDegrees: 60,
            aspect: 1.0,
            margin: 1.15
        )
        XCTAssertEqual(r, 1.984, accuracy: 0.01)
    }

    /// The #3383 headline case: a 3 m column in a portrait viewport. The
    /// sphere fit divided half the space diagonal by `sin` of the *smaller*
    /// (horizontal) half-FOV and pushed the camera to 5.90 m; the subject is
    /// bound by the vertical axis alone and 3.00 m frames it exactly. Before
    /// the fix the column filled barely half the height it could.
    func testFitRadiusFillsPortraitViewportWithTallSubject() {
        let controls = CameraControls(mode: .orbit, maxRadius: 500)
        let r = controls.fitRadius(
            boundsExtents: SIMD3<Float>(0.3, 3.0, 0.3),
            fovYDegrees: 60, aspect: 0.46, margin: 1.0)
        XCTAssertEqual(r, 3.0, accuracy: 0.01)
        // Well under the 5.902 the bounding-sphere fit returned.
        XCTAssertLessThan(r, 4.0)
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

    /// `fitRadius`'s `margin:` default was a bare `1.15` literal until
    /// `.framingMargin(_:)` needed to name the same value (#2896). The whole
    /// "existing scenes frame identically" claim rests on the constant being
    /// EQUAL to the literal it replaced — so pin the number itself, not just
    /// the wiring.
    func testDefaultFitMarginIsUnchangedValue() {
        XCTAssertEqual(CameraControls.defaultFitMargin, 1.15, accuracy: 0.0001)
    }

    /// Omitting `margin:` must keep computing exactly what an explicit 1.15
    /// computes. A future edit that redefines `defaultFitMargin` silently
    /// re-frames every auto-framed scene on Apple platforms; this fails first.
    func testFitRadiusDefaultMarginMatchesExplicitDefault() {
        let controls = CameraControls()
        let implicitMargin = controls.fitRadius(
            boundsExtents: SIMD3<Float>(0.4, 1.2, 0.7), fovYDegrees: 60,
            aspect: 0.46)
        let explicitMargin = controls.fitRadius(
            boundsExtents: SIMD3<Float>(0.4, 1.2, 0.7), fovYDegrees: 60,
            aspect: 0.46, margin: 1.15)
        XCTAssertEqual(implicitMargin, explicitMargin, accuracy: 0.0001)
    }

    // MARK: - Per-axis projected fit (#3383)

    /// The three subjects the fit has to tell apart. A bounding sphere cannot:
    /// it sees only `hypot(hx, hy, hz)`, so `wideFlat` and `tallNarrow` — which
    /// need opposite things from a portrait viewport — get the same treatment.
    private static let fitSubjects: [(name: String, extents: SIMD3<Float>)] = [
        ("wide/flat", SIMD3<Float>(4.0, 0.5, 0.5)),
        ("tall/narrow", SIMD3<Float>(0.3, 3.0, 0.3)),
        ("cubic", SIMD3<Float>(1.0, 1.0, 1.0)),
    ]

    /// iPhone portrait, square, and landscape (`width / height`).
    private static let fitViewports: [(name: String, aspect: Float)] = [
        ("portrait", 0.46),
        ("square", 1.0),
        ("landscape", 2.17),
    ]

    /// Viewport × subject, at the default 30° elevation with `margin: 1.0` so
    /// the numbers below are the raw fit. Each was cross-checked against a
    /// 720-sample azimuth sweep of the exact eight-corner fit (worst relative
    /// error 9.5e-06), and the `sphere` column is what shipped before #3383.
    func testFitRadiusMatrixAcrossViewportsAndSubjects() {
        //  subject      viewport     fitted   sphere (pre-#3383)
        let expected: [String: Float] = [
            "wide/flat portrait":   7.9124,  // 7.9125 — horizontal-bound either way
            "wide/flat square":     4.0281,  // 4.0620
            "wide/flat landscape":  3.7411,  // 4.0620
            "tall/narrow portrait": 3.0000,  // 5.9019 — the #3383 headline
            "tall/narrow square":   3.0000,  // 3.0299
            "tall/narrow landscape": 3.0000, // 3.0299
            "cubic portrait":       2.9820,  // 3.3739
            "cubic square":         1.7247,  // 1.7321
            "cubic landscape":      1.7247,  // 1.7321
        ]
        // maxRadius raised so nothing in the matrix hits the clamp.
        let controls = CameraControls(mode: .orbit, maxRadius: 500)
        for (subject, extents) in Self.fitSubjects {
            for (viewport, aspect) in Self.fitViewports {
                let key = "\(subject) \(viewport)"
                let r = controls.fitRadius(
                    boundsExtents: extents, fovYDegrees: 60,
                    aspect: aspect, margin: 1.0)
                XCTAssertEqual(r, expected[key]!, accuracy: 0.005, key)
            }
        }
    }

    /// A vertically-bound subject must cost the same in every viewport: the
    /// vertical FOV does not change with the aspect ratio. The sphere fit
    /// charged the column 5.90 m in portrait against 3.03 m in landscape
    /// purely because it divided by the *smaller* half-FOV.
    func testFitRadiusOfVerticallyBoundSubjectIgnoresAspect() {
        let controls = CameraControls(mode: .orbit, maxRadius: 500)
        let column = SIMD3<Float>(0.3, 3.0, 0.3)
        let fits = Self.fitViewports.map {
            controls.fitRadius(boundsExtents: column, fovYDegrees: 60,
                               aspect: $0.aspect, margin: 1.0)
        }
        for fit in fits {
            XCTAssertEqual(fit, fits[0], accuracy: 0.001)
        }
    }

    /// Auto-rotating content must not clip as it turns broadside, so the fit
    /// reads no ``CameraControls/azimuth`` at all — it frames the box's sweep
    /// about world Y. An "optimisation" that specialises on the current
    /// azimuth fails here before it ships a clipping turntable.
    func testFitRadiusIsInvariantToAzimuth() {
        var controls = CameraControls(mode: .orbit, maxRadius: 500)
        let subject = SIMD3<Float>(4.0, 0.5, 0.5)  // worst case: very wide
        controls.azimuth = 0
        let reference = controls.fitRadius(
            boundsExtents: subject, fovYDegrees: 60, aspect: 0.46, margin: 1.0)
        for step in 1..<16 {
            controls.azimuth = Float(step) * 2 * .pi / 16
            let r = controls.fitRadius(
                boundsExtents: subject, fovYDegrees: 60, aspect: 0.46,
                margin: 1.0)
            XCTAssertEqual(r, reference, accuracy: 0.0001,
                           "azimuth \(controls.azimuth)")
        }
    }

    /// The containment proof, and the reason the matrix numbers above can be
    /// trusted rather than merely pinned: place the camera at the fitted
    /// radius and check all eight box corners against both FOV axes, at 24
    /// azimuths. Nothing may leave the frame (`<= 0`), and the fit must stay
    /// *tight* — the pre-#3383 sphere fit wasted up to 49 % of the frame on
    /// the tall column, which the -0.02 floor rejects.
    func testFitRadiusFramesEveryCornerAtEveryAzimuth() {
        for (subject, extents) in Self.fitSubjects {
            for (viewport, aspect) in Self.fitViewports {
                var controls = CameraControls(mode: .orbit, maxRadius: 500)
                controls.orbitRadius = controls.fitRadius(
                    boundsExtents: extents, fovYDegrees: 60,
                    aspect: aspect, margin: 1.0)
                var tightest = -Float.greatestFiniteMagnitude
                for step in 0..<24 {
                    controls.azimuth = Float(step) * 2 * .pi / 24
                    let overshoot = frustumOvershoot(
                        extents: extents, controls: controls,
                        fovYDegrees: 60, aspect: aspect)
                    XCTAssertLessThanOrEqual(
                        overshoot, 1e-4,
                        "\(subject) \(viewport) clipped at azimuth \(step)")
                    tightest = max(tightest, overshoot)
                }
                XCTAssertGreaterThan(
                    tightest, -0.02,
                    "\(subject) \(viewport) framed looser than it needs")
            }
        }
    }

    /// #3383 must not make any existing scene worse: the per-axis fit is
    /// bounded above by the sphere fit it replaces, so no content is ever
    /// pushed further away than it was in v4.x.
    func testFitRadiusNeverExceedsPreviousBoundingSphereFit() {
        let controls = CameraControls(mode: .orbit, maxRadius: 500)
        var subjects = Self.fitSubjects
        subjects.append((name: "vehicle", extents: SIMD3<Float>(2.2, 1.0, 5.0)))
        subjects.append((name: "helmet", extents: SIMD3<Float>(0.9, 1.1, 0.9)))
        for (subject, extents) in subjects {
            for (viewport, aspect) in Self.fitViewports {
                // The pre-#3383 formula, verbatim: half the space diagonal
                // divided by the sine of the smaller half-FOV.
                let half = extents * 0.5
                let sphereRadius = simd_length(half)
                let fovY = Float(60) * .pi / 180
                let fovX = 2 * atan(tan(fovY / 2) * aspect)
                let sphereFit = sphereRadius / sin(min(fovY, fovX) / 2)
                let fitted = controls.fitRadius(
                    boundsExtents: extents, fovYDegrees: 60,
                    aspect: aspect, margin: 1.0)
                XCTAssertLessThanOrEqual(fitted, sphereFit * 1.0001,
                                         "\(subject) \(viewport)")
            }
        }
    }

    /// ``CameraControls/elevation`` is public and the drag handler's clamp does
    /// not apply to a value set directly, so the fit must survive a straight
    /// overhead pose. The analytic basis avoids the cross product that
    /// degenerates when the eye vector is parallel to world up.
    func testFitRadiusSurvivesPolarElevation() {
        var controls = CameraControls(mode: .orbit, maxRadius: 500)
        let subject = SIMD3<Float>(2.2, 1.0, 5.0)
        var results: [Float] = []
        for elevation in [Float.pi / 2, -Float.pi / 2, Float.pi / 2 - 0.001] {
            controls.elevation = elevation
            let r = controls.fitRadius(
                boundsExtents: subject, fovYDegrees: 60, aspect: 0.46,
                margin: 1.0)
            XCTAssertTrue(r.isFinite, "elevation \(elevation)")
            results.append(r)
        }
        // Straight up, straight down and just-off-vertical all agree.
        for r in results {
            XCTAssertEqual(r, 10.784, accuracy: 0.01)
        }
    }

    /// Worst frustum overshoot across the eight corners of a box centred on
    /// the controls' target, as a fraction of the orbit radius. `<= 0` means
    /// the whole box is inside both FOV axes.
    ///
    /// A corner `v` (relative to the target) is inside at distance `d` iff
    /// `d >= v·back + |v·axis| / tanAxis` on the horizontal and vertical axes.
    private func frustumOvershoot(
        extents: SIMD3<Float>,
        controls: SceneViewSwift.CameraControls,
        fovYDegrees: Float,
        aspect: Float
    ) -> Float {
        let half = extents * 0.5
        let distance = controls.orbitRadius
        // Same basis the renderer uses — read from the production camera pose.
        let back = simd_normalize(controls.cameraPosition() - controls.target)
        let forward = -back
        let right = simd_normalize(simd_cross(forward, SIMD3<Float>(0, 1, 0)))
        let up = simd_cross(right, forward)
        let tanY = tan(fovYDegrees * .pi / 180 / 2)
        let tanX = tanY * aspect

        var worst = -Float.greatestFiniteMagnitude
        for signX in [Float(-1), 1] {
            for signY in [Float(-1), 1] {
                for signZ in [Float(-1), 1] {
                    let corner = SIMD3<Float>(signX * half.x,
                                              signY * half.y,
                                              signZ * half.z)
                    let depth = simd_dot(corner, back)
                    let needX = depth + abs(simd_dot(corner, right)) / tanX
                    let needY = depth + abs(simd_dot(corner, up)) / tanY
                    worst = max(worst, max(needX, needY) - distance)
                }
            }
        }
        return worst / distance
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

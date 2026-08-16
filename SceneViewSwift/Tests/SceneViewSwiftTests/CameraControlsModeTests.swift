import XCTest
@testable import SceneViewSwift

#if os(iOS) || os(visionOS) || os(macOS)
/// Pins per-mode `CameraControls` behaviour — closes #1034.
///
/// Mirrors the per-mode handler split from Android
/// `CameraGestureDetector` (ORBIT / PAN / FREE_FLIGHT). Each test asserts
/// that swapping `mode` redirects the drag / pinch math to the right
/// channel without bleeding into the orbit defaults.
// Test classes run on the main actor: their RealityKit node factories
// (`LightNode.directional`, `node.entity`, …) are `@MainActor`. (#1054)
@MainActor
final class CameraControlsModeTests: XCTestCase {

    // MARK: - drag math is mode-aware

    func testDragOrbitMode_updatesAzimuthAndElevation_butNotTarget() {
        var c = CameraControls(mode: .orbit)
        let initialTarget = c.target
        let initialAz = c.azimuth
        let initialEl = c.elevation

        c.handleDrag(CGSize(width: 100, height: 50))

        XCTAssertNotEqual(c.azimuth, initialAz, "orbit drag must rotate yaw")
        XCTAssertNotEqual(c.elevation, initialEl, "orbit drag must rotate pitch")
        XCTAssertEqual(c.target, initialTarget, "orbit drag must NOT translate target")
    }

    func testDragPanMode_translatesTarget_butNotAzimuthOrElevation() {
        var c = CameraControls(mode: .pan)
        let initialAz = c.azimuth
        let initialEl = c.elevation
        let initialTarget = c.target

        c.handleDrag(CGSize(width: 100, height: 50))

        XCTAssertEqual(c.azimuth, initialAz, "pan drag must NOT rotate yaw")
        XCTAssertEqual(c.elevation, initialEl, "pan drag must NOT rotate pitch")
        XCTAssertNotEqual(c.target, initialTarget, "pan drag must translate target")
        // panSpeed=0.01, width=100 ⇒ ~1 unit X; height=50 (screen-down ⇒ -y world)
        XCTAssertEqual(c.target.x, initialTarget.x + 1.0, accuracy: 0.01)
        XCTAssertEqual(c.target.y, initialTarget.y - 0.5, accuracy: 0.01)
    }

    func testDragFirstPersonMode_updatesAzimuthAndElevation_butNotTarget() {
        var c = CameraControls(mode: .firstPerson)
        let initialTarget = c.target
        let initialAz = c.azimuth

        c.handleDrag(CGSize(width: 200, height: 0))

        XCTAssertNotEqual(c.azimuth, initialAz, "firstPerson drag must rotate yaw")
        XCTAssertEqual(c.target, initialTarget, "firstPerson drag must NOT translate target")
    }

    // MARK: - pinch math is mode-aware

    func testPinchOrbitMode_scalesOrbitRadius_butNotFov() {
        var c = CameraControls(mode: .orbit)
        let initialFov = c.fov
        let initialRadius = c.orbitRadius

        c.handlePinch(2.0)

        XCTAssertLessThan(c.orbitRadius, initialRadius, "orbit pinch must dolly radius")
        XCTAssertEqual(c.fov, initialFov, "orbit pinch must NOT mutate FOV")
    }

    func testPinchPanMode_scalesOrbitRadius_butNotFov() {
        var c = CameraControls(mode: .pan)
        let initialFov = c.fov
        let initialRadius = c.orbitRadius

        c.handlePinch(2.0)

        XCTAssertLessThan(c.orbitRadius, initialRadius, "pan pinch must dolly radius")
        XCTAssertEqual(c.fov, initialFov, "pan pinch must NOT mutate FOV")
    }

    func testPinchFirstPersonMode_scalesFov_butNotOrbitRadius() {
        var c = CameraControls(mode: .firstPerson)
        let initialFov = c.fov
        let initialRadius = c.orbitRadius

        c.handlePinch(2.0)

        XCTAssertLessThan(c.fov, initialFov, "firstPerson pinch out (zoom in) must shrink FOV")
        XCTAssertEqual(c.orbitRadius, initialRadius, "firstPerson pinch must NOT mutate orbit radius")
    }

    func testFirstPersonFovClampedToMinFov() {
        var c = CameraControls(mode: .firstPerson)
        c.minFov = 20.0

        // Extreme zoom-in
        c.handlePinch(1000.0)

        XCTAssertGreaterThanOrEqual(c.fov, c.minFov, "FOV must clamp to minFov")
    }

    func testFirstPersonFovClampedToMaxFov() {
        var c = CameraControls(mode: .firstPerson)
        c.maxFov = 90.0

        // Extreme zoom-out
        c.handlePinch(0.001)

        XCTAssertLessThanOrEqual(c.fov, c.maxFov, "FOV must clamp to maxFov")
    }

    // MARK: - per-mode defaults

    func testFirstPersonFovDefaultMatchesPerspectiveCamera() {
        let c = CameraControls(mode: .firstPerson)
        // Matches the perspCamera default set in SceneView.setupScene.
        XCTAssertEqual(c.fov, 60.0, accuracy: 0.01)
    }

    func testFirstPersonFovRangeMatchesAndroidDefault() {
        let c = CameraControls(mode: .firstPerson)
        // Mirrors Android `FovZoomCameraManipulator.fovRangeDegrees = 10°..120°`.
        XCTAssertEqual(c.minFov, 10.0, accuracy: 0.01)
        XCTAssertEqual(c.maxFov, 120.0, accuracy: 0.01)
    }

    // MARK: - isEnabled gates all modes

    func testHandlePinchRespectsIsEnabled() {
        for mode in [CameraControlMode.orbit, .pan, .firstPerson] {
            var c = CameraControls(mode: mode)
            c.isEnabled = false
            let radius = c.orbitRadius
            let fov = c.fov

            c.handlePinch(2.0)

            XCTAssertEqual(c.orbitRadius, radius, "disabled pinch must no-op (mode=\(mode))")
            XCTAssertEqual(c.fov, fov, "disabled pinch must no-op (mode=\(mode))")
        }
    }

    // MARK: - rotated-camera pan

    /// Pan should translate `target` along the camera-aligned right + up
    /// vectors, NOT the world-aligned x/y axes. Tests Agent 3's pinning
    /// gap: previous `testDragPanMode_translatesTarget` only covered
    /// azimuth=0 (trivial case where camera right vector == world x).
    func testDragPanMode_rotatedCamera_translatesAlongCameraRight() {
        var c = CameraControls(mode: .pan)
        c.azimuth = .pi / 2  // 90° — camera looks along +X axis
        let initialTarget = c.target

        c.handleDrag(CGSize(width: 100, height: 0))

        // With azimuth=π/2: right = (cos(π/2), 0, -sin(π/2)) = (0, 0, -1).
        // Width = 100 px × panSpeed 0.01 ⇒ +1 along right.
        // So target.z should DECREASE by 1, x and y unchanged.
        XCTAssertEqual(c.target.x, initialTarget.x, accuracy: 0.001)
        XCTAssertEqual(c.target.y, initialTarget.y, accuracy: 0.001)
        XCTAssertEqual(c.target.z, initialTarget.z - 1.0, accuracy: 0.01,
                       "rotated-camera pan must follow camera-aligned right vector")
    }

    // MARK: - autoRotation works in all modes

    func testApplyAutoRotation_orbitMode_rotatesAzimuth() {
        var c = CameraControls(mode: .orbit)
        c.isAutoRotating = true
        c.autoRotateSpeed = 1.0
        let initialAz = c.azimuth

        c.applyAutoRotation(dt: 0.5)
        XCTAssertEqual(c.azimuth, initialAz + 0.5, accuracy: 0.001)
    }

    func testApplyAutoRotation_firstPersonMode_rotatesAzimuth() {
        // FirstPerson uses the same azimuth/elevation rotation as orbit
        // (the visual difference is in `applyCamera`'s scale + position
        // handling, not in the rotation math). Auto-rotation must still
        // tick azimuth so the demo's turntable mode works.
        var c = CameraControls(mode: .firstPerson)
        c.isAutoRotating = true
        c.autoRotateSpeed = 1.0
        let initialAz = c.azimuth

        c.applyAutoRotation(dt: 0.5)
        XCTAssertEqual(c.azimuth, initialAz + 0.5, accuracy: 0.001)
    }

    // MARK: - applyInertia mode-gated

    func testApplyInertia_orbitMode_rotatesAzimuthAndElevation() {
        var c = CameraControls(mode: .orbit)
        c.inertiaVelocity = CGSize(width: 100, height: 50)
        let initialAz = c.azimuth
        let initialEl = c.elevation
        let initialTarget = c.target

        let active = c.applyInertia()

        XCTAssertTrue(active)
        XCTAssertNotEqual(c.azimuth, initialAz, "orbit inertia must rotate yaw")
        XCTAssertNotEqual(c.elevation, initialEl, "orbit inertia must rotate pitch")
        XCTAssertEqual(c.target, initialTarget, "orbit inertia must NOT translate target")
    }

    func testApplyInertia_panMode_translatesTarget_notAzimuthElevation() {
        // The Agent 1 MAJOR — applyInertia previously always rotated,
        // injecting ghost rotation when a `.pan` drag ended. Now mode-gated.
        var c = CameraControls(mode: .pan)
        c.inertiaVelocity = CGSize(width: 100, height: 0)
        let initialAz = c.azimuth
        let initialEl = c.elevation
        let initialTarget = c.target

        let active = c.applyInertia()

        XCTAssertTrue(active)
        XCTAssertEqual(c.azimuth, initialAz, "pan inertia must NOT rotate yaw")
        XCTAssertEqual(c.elevation, initialEl, "pan inertia must NOT rotate pitch")
        XCTAssertNotEqual(c.target, initialTarget, "pan inertia must glide target")
    }

    func testApplyInertia_firstPersonMode_rotatesAzimuthAndElevation() {
        var c = CameraControls(mode: .firstPerson)
        c.inertiaVelocity = CGSize(width: 100, height: 50)
        let initialAz = c.azimuth

        let active = c.applyInertia()

        XCTAssertTrue(active)
        XCTAssertNotEqual(c.azimuth, initialAz, "firstPerson inertia must rotate yaw")
    }

    // MARK: - isCustom gate (#1049)

    func testIsCustom_customModes() {
        XCTAssertTrue(CameraControlMode.orbit.isCustom)
        XCTAssertTrue(CameraControlMode.pan.isCustom)
        XCTAssertTrue(CameraControlMode.firstPerson.isCustom)
    }

    func testIsCustom_nativeModes() {
        XCTAssertFalse(CameraControlMode.none.isCustom)
        XCTAssertFalse(CameraControlMode.tilt.isCustom)
        XCTAssertFalse(CameraControlMode.dolly.isCustom)
    }

    /// Regression guard for #3082. `RealityFoundation.CameraControls` is a
    /// struct exposing exactly `.none`, `.tilt`, `.pan`, `.orbit`, `.dolly` —
    /// there is no `.gimbal`, on any SDK. SceneView used to carry a
    /// `CameraControlMode.gimbal` that fell back to the orbit gesture path on
    /// every platform, i.e. a public alias for `.orbit` named after an API
    /// that does not exist. This pins the native set so it cannot grow a
    /// fictional member again: the three native modes below are exactly the
    /// Apple modes SceneView delegates, and every other case is custom.
    func testNativeModesAreExactlyTheThreeDelegatedAppleModes() {
        let native: [CameraControlMode] = [.none, .tilt, .dolly]
        let custom: [CameraControlMode] = [.orbit, .pan, .firstPerson]

        for mode in native {
            XCTAssertFalse(mode.isCustom, "\(mode) must delegate to RealityKit")
        }
        for mode in custom {
            XCTAssertTrue(mode.isCustom, "\(mode) must use SceneView gesture math")
        }

        // Exhaustive: switching over every case must be covered by the two
        // lists above, so adding a case without classifying it fails to build.
        for mode in native + custom {
            switch mode {
            case .orbit, .pan, .firstPerson:
                XCTAssertTrue(custom.contains(mode))
            case .none, .tilt, .dolly:
                XCTAssertTrue(native.contains(mode))
            }
        }
    }

}
#endif

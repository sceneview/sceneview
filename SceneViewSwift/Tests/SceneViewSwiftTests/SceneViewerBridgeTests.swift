import XCTest
import simd
import RealityKit
@testable import SceneViewSwift

#if os(iOS) || os(visionOS) || os(macOS)
/// Pins the parts of the `UIView` bridge that a compiler cannot check and a screenshot
/// cannot either.
///
/// `SceneViewerHostView` is UIKit-only, so none of it compiles on macOS where these run.
/// What is tested here is the half that was extracted precisely because it does: the
/// angle-unit boundary, the orbit convention shared with ``CameraControls``, the pose
/// clamping, and the model identity that decides when a reload happens. Each of those
/// fails silently in the worst way — a swapped conversion still renders a lit model from
/// a plausible angle, a colliding model key silently shows the previous model — so a
/// green build and a good-looking screenshot prove nothing about them.
final class SceneViewerBridgeTests: XCTestCase {

    // MARK: - Angle conversion direction

    func testDegreesToRadians_convertsInTheDirectionItsNameClaims() {
        XCTAssertEqual(SceneViewerAngle.radians(fromDegrees: 180), .pi, accuracy: 1e-6)
        XCTAssertEqual(SceneViewerAngle.radians(fromDegrees: 90), .pi / 2, accuracy: 1e-6)
        XCTAssertEqual(SceneViewerAngle.radians(fromDegrees: 0), 0, accuracy: 1e-9)
        // Negative elevations are the common case looking up at a model.
        XCTAssertEqual(SceneViewerAngle.radians(fromDegrees: -45), -.pi / 4, accuracy: 1e-6)
    }

    func testRadiansToDegrees_convertsInTheDirectionItsNameClaims() {
        XCTAssertEqual(SceneViewerAngle.degrees(fromRadians: .pi), 180, accuracy: 1e-4)
        XCTAssertEqual(SceneViewerAngle.degrees(fromRadians: .pi / 6), 30, accuracy: 1e-4)
    }

    /// The failure mode this guards is a *swap*, not an error term: both directions are
    /// pure multiplications, so a swapped pair still compiles, still produces finite
    /// angles, and still renders a lit model — just from the wrong side. 15° read as
    /// 15 rad is 859°, i.e. 139°.
    func testSwappedConversionWouldBeDetected() {
        let degrees: Float = 15
        XCTAssertNotEqual(
            SceneViewerAngle.radians(fromDegrees: degrees),
            SceneViewerAngle.degrees(fromRadians: degrees),
            accuracy: 1
        )
    }

    func testDegreeRoundTrip_survivesTheEchoTolerance() {
        // The exact path a pose takes: radians → degrees over the bridge → back on the
        // next update. It has to land inside `approximatelyMatches`, or every echo would
        // read as a fresh write and re-pin the camera mid-drag.
        for degrees in [Float](stride(from: -179, through: 179, by: 7)) {
            let radians = SceneViewerAngle.radians(fromDegrees: degrees)
            let roundTripped = SceneViewerAngle.radians(
                fromDegrees: SceneViewerAngle.degrees(fromRadians: radians)
            )
            let a = SceneCameraPose(azimuth: radians, elevation: 0, distance: 4)
            let b = SceneCameraPose(azimuth: roundTripped, elevation: 0, distance: 4)
            XCTAssertTrue(a.approximatelyMatches(b), "round trip broke at \(degrees)°")
        }
    }

    // MARK: - Orbit convention

    /// `SceneCameraPose.cameraPosition()` is used to measure a tap's distance from the
    /// camera. If it disagreed with the convention the renderer actually places the
    /// camera by, that distance would be plausible and wrong — so it is pinned against
    /// `CameraControls`, not against a re-derivation of the same formula.
    func testCameraPosition_agreesWithCameraControls() {
        let cases: [(Float, Float, Float, SIMD3<Float>)] = [
            (0, 0, 4, .zero),
            (.pi / 4, .pi / 6, 2.5, .zero),
            (-1.2, -0.7, 7, SIMD3<Float>(1, 2, -3)),
            (3.0, 1.4, 0.5, SIMD3<Float>(-2, 0.5, 0.25)),
        ]
        for (azimuth, elevation, distance, target) in cases {
            var controls = CameraControls(mode: .orbit)
            controls.azimuth = azimuth
            controls.elevation = elevation
            controls.orbitRadius = distance
            controls.target = target

            let pose = SceneCameraPose(
                azimuth: azimuth,
                elevation: elevation,
                distance: distance,
                target: target
            )
            let expected = controls.cameraPosition()
            let actual = pose.cameraPosition()
            XCTAssertEqual(actual.x, expected.x, accuracy: 1e-5)
            XCTAssertEqual(actual.y, expected.y, accuracy: 1e-5)
            XCTAssertEqual(actual.z, expected.z, accuracy: 1e-5)
        }
    }

    // MARK: - Pose write-through

    func testApplyPose_roundTripsThroughCameraControls() {
        var controls = CameraControls(mode: .orbit)
        let pose = SceneCameraPose(
            azimuth: 1.1,
            elevation: 0.4,
            distance: 3.5,
            target: SIMD3<Float>(0.5, -1, 2)
        )
        controls.apply(pose: pose)
        XCTAssertTrue(controls.pose.approximatelyMatches(pose))
    }

    func testApplyPose_clampsElevationAwayFromTheGimbalPoles() {
        var controls = CameraControls(mode: .orbit)
        controls.apply(pose: SceneCameraPose(azimuth: 0, elevation: .pi, distance: 4))
        XCTAssertEqual(controls.elevation, controls.maxElevation, accuracy: 1e-6)

        controls.apply(pose: SceneCameraPose(azimuth: 0, elevation: -.pi, distance: 4))
        XCTAssertEqual(controls.elevation, controls.minElevation, accuracy: 1e-6)
    }

    /// A clamped write must be *observable*. The host reports `controls.pose` back to the
    /// caller, so the caller learns the value it asked for is not the value on screen —
    /// which only works if the read-back reflects the clamp rather than the request.
    func testClampedPose_isVisibleInTheReadBack() {
        var controls = CameraControls(mode: .orbit)
        let requested = SceneCameraPose(azimuth: 0, elevation: 0, distance: 9_999)
        controls.apply(pose: requested)
        XCTAssertEqual(controls.pose.distance, controls.maxRadius, accuracy: 1e-6)
        XCTAssertFalse(controls.pose.approximatelyMatches(requested))
    }

    func testApplyPose_clampsDistanceIntoTheDollyEnvelope() {
        var controls = CameraControls(mode: .orbit)
        controls.apply(pose: SceneCameraPose(azimuth: 0, elevation: 0, distance: 0))
        XCTAssertEqual(controls.orbitRadius, controls.minRadius, accuracy: 1e-6)
    }

    // MARK: - Model identity

    func testModelKey_isNilOnlyWhenThereIsNoModel() {
        XCTAssertNil(SceneViewerModelRequest.none.key)
        XCTAssertNotNil(SceneViewerModelRequest.asset("a.usdz").key)
    }

    func testModelKey_separatesSourcesThatShareAString() {
        // An asset path and a URL string that look alike must not collide, or switching
        // between them would leave the previous model on screen.
        let asset = SceneViewerModelRequest.asset("models/a.usdz").key
        let url = SceneViewerModelRequest.url(URL(string: "https://x/models/a.usdz")!).key
        XCTAssertNotEqual(asset, url)
    }

    func testModelKey_isStableForTheSameBytes() {
        let data = Data((0..<4096).map { UInt8($0 % 251) })
        let first = SceneViewerModelRequest.bytes(data, fileExtension: "usdz").key
        let second = SceneViewerModelRequest.bytes(Data(data), fileExtension: "usdz").key
        XCTAssertEqual(first, second)
        XCTAssertNotNil(first)
    }

    func testModelKey_changesWhenTheBytesDoAtEitherEnd() {
        var head = Data((0..<4096).map { UInt8($0 % 251) })
        let base = SceneViewerModelRequest.bytes(head, fileExtension: "usdz").key

        head[0] ^= 0xFF
        XCTAssertNotEqual(SceneViewerModelRequest.bytes(head, fileExtension: "usdz").key, base)

        var tail = Data((0..<4096).map { UInt8($0 % 251) })
        tail[tail.count - 1] ^= 0xFF
        XCTAssertNotEqual(SceneViewerModelRequest.bytes(tail, fileExtension: "usdz").key, base)
    }

    func testModelKey_changesWithLength() {
        let short = Data((0..<4096).map { UInt8($0 % 251) })
        let long = short + Data([0])
        XCTAssertNotEqual(
            SceneViewerModelRequest.bytes(short, fileExtension: "usdz").key,
            SceneViewerModelRequest.bytes(long, fileExtension: "usdz").key
        )
    }

    func testModelKey_handlesBuffersShorterThanTheEdgeWindow() {
        // The signature reads 16 bytes from each end; a 4-byte buffer has neither. The
        // short path must still separate two different buffers rather than trap or
        // return the same key.
        let a = SceneViewerModelRequest.bytes(Data([1, 2, 3, 4]), fileExtension: "usdz").key
        let b = SceneViewerModelRequest.bytes(Data([1, 2, 3, 5]), fileExtension: "usdz").key
        XCTAssertNotNil(a)
        XCTAssertNotEqual(a, b)
    }

    func testModelKey_separatesTheFileExtension() {
        let data = Data((0..<64).map { UInt8($0) })
        XCTAssertNotEqual(
            SceneViewerModelRequest.bytes(data, fileExtension: "usdz").key,
            SceneViewerModelRequest.bytes(data, fileExtension: "reality").key
        )
    }

    // MARK: - Gesture targeting

    /// Pins the component that makes entity-targeted SwiftUI gestures fire at all.
    ///
    /// A `CollisionComponent` alone is not enough, and the failure is completely silent:
    /// before this, a tap on a loaded model produced no callback and no diagnostic. Only
    /// the recursion is load-bearing beyond the component itself —
    /// `generateCollisionShapes(recursive:)` puts the shapes on the mesh descendants, and
    /// those are what the hit-test resolves to.
    @MainActor
    func testMakeInputTargetable_reachesTheWholeSubtree() {
        let root = Entity()
        let child = Entity()
        let grandchild = Entity()
        child.addChild(grandchild)
        root.addChild(child)

        XCTAssertNil(root.components[InputTargetComponent.self])

        root.makeInputTargetable()

        XCTAssertNotNil(root.components[InputTargetComponent.self])
        XCTAssertNotNil(child.components[InputTargetComponent.self])
        XCTAssertNotNil(grandchild.components[InputTargetComponent.self])
    }

    // MARK: - Lighting direction

    func testNormalizedDirection_normalises() {
        let lighting = SceneViewerLighting(
            direction: SIMD3<Float>(0, -10, 0),
            intensity: 1_000,
            ambientIntensity: 1,
            castShadows: true
        )
        XCTAssertEqual(simd_length(lighting.normalizedDirection), 1, accuracy: 1e-5)
        XCTAssertEqual(lighting.normalizedDirection.y, -1, accuracy: 1e-5)
    }

    /// A zero or non-finite direction would make `look(at:from:)` produce a NaN
    /// orientation and a light that renders nothing — a black model with no error
    /// anywhere.
    func testNormalizedDirection_fallsBackOnADegenerateDirection() {
        for degenerate in [SIMD3<Float>.zero, SIMD3<Float>(.nan, 0, 0), SIMD3<Float>(.infinity, 0, 0)] {
            let lighting = SceneViewerLighting(
                direction: degenerate,
                intensity: 1_000,
                ambientIntensity: 1,
                castShadows: true
            )
            let direction = lighting.normalizedDirection
            XCTAssertTrue(direction.x.isFinite && direction.y.isFinite && direction.z.isFinite)
            XCTAssertEqual(simd_length(direction), 1, accuracy: 1e-5)
        }
    }
}
#endif

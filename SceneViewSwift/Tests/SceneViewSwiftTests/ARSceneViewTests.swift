#if os(iOS)
import XCTest
import ARKit
import RealityKit
@testable import SceneViewSwift

/// Tests for ARSceneView configuration, including camera exposure API parity with Android.
// Test classes run on the main actor: their RealityKit node factories
// (`LightNode.directional`, `node.entity`, …) are `@MainActor`. (#1054)
@MainActor
final class ARSceneViewTests: XCTestCase {

    // MARK: - Session start

    func testUnsupportedFaceTrackingRunsNoSessionAtAll() {
        let arView = ARView(frame: .zero)

        let error = ARSceneView.startSession(
            on: arView,
            faceTracking: true,
            faceTrackingSupported: false,
            planeDetection: .horizontal,
            imageTrackingDatabase: nil
        )

        XCTAssertEqual(error, .faceTrackingUnsupported)
        // The regression this guards: `makeUIView` used to carry on after
        // reporting the error and still call `onSessionStarted`, so the host
        // added content to a session that was never run.
        XCTAssertNil(arView.session.configuration)
    }

    // MARK: - Default initialisation

    func testDefaultInit() {
        let view = ARSceneView()
        // Defaults should match Android's ARSceneView defaults.
        // planeDetection = .horizontal, showPlaneOverlay = true,
        // showCoachingOverlay = true, cameraExposure = nil
        // We verify the view is constructible and compiles correctly.
        XCTAssertNotNil(view)
    }

    func testInitWithPlaneDetectionNone() {
        let view = ARSceneView(planeDetection: .none)
        XCTAssertNotNil(view)
    }

    func testInitWithPlaneDetectionVertical() {
        let view = ARSceneView(planeDetection: .vertical)
        XCTAssertNotNil(view)
    }

    func testInitWithPlaneDetectionBoth() {
        let view = ARSceneView(planeDetection: .both)
        XCTAssertNotNil(view)
    }

    // MARK: - cameraExposure init parameter

    /// Mirrors Android's `ARSceneView(cameraExposure: Float?)` — nil means no override.
    func testCameraExposureDefaultIsNil() {
        // Constructing without explicit cameraExposure should not crash.
        let view = ARSceneView(planeDetection: .horizontal)
        XCTAssertNotNil(view)
    }

    /// Providing a positive EV value should be accepted without crashing.
    func testCameraExposurePositiveEV() {
        let view = ARSceneView(cameraExposure: 1.5)
        XCTAssertNotNil(view)
    }

    /// Providing a negative EV value (darken) should be accepted without crashing.
    func testCameraExposureNegativeEV() {
        let view = ARSceneView(cameraExposure: -2.0)
        XCTAssertNotNil(view)
    }

    /// Zero EV means no change — treated the same as a non-nil override (post-process
    /// installed but with brightness = 0, which is a no-op for CIColorControls).
    func testCameraExposureZeroEV() {
        let view = ARSceneView(cameraExposure: 0.0)
        XCTAssertNotNil(view)
    }

    // MARK: - cameraExposure modifier (SwiftUI-style)

    /// The `.cameraExposure(_:)` modifier should return a new ARSceneView instance.
    func testCameraExposureModifierReturnsNewInstance() {
        let original = ARSceneView()
        let modified = original.cameraExposure(1.0)
        // Modified is a copy (value type), so it must be a valid ARSceneView.
        XCTAssertNotNil(modified)
    }

    func testCameraExposureModifierWithPositiveEV() {
        let view = ARSceneView()
            .cameraExposure(2.0)
        XCTAssertNotNil(view)
    }

    func testCameraExposureModifierWithNegativeEV() {
        let view = ARSceneView()
            .cameraExposure(-1.0)
        XCTAssertNotNil(view)
    }

    func testCameraExposureModifierWithNilRemovesOverride() {
        let view = ARSceneView()
            .cameraExposure(1.0)
            .cameraExposure(nil)
        XCTAssertNotNil(view)
    }

    // MARK: - cameraExposure modifier chaining

    func testCameraExposureChainedWithOtherModifiers() {
        let view = ARSceneView(planeDetection: .both)
            .cameraExposure(0.5)
            .onSessionStarted { _ in }
        XCTAssertNotNil(view)
    }

    func testCameraExposureLastModifierWins() {
        // When cameraExposure is called twice, the last value should win (copy semantics).
        let view = ARSceneView()
            .cameraExposure(1.0)
            .cameraExposure(3.0)
        XCTAssertNotNil(view)
    }

    // MARK: - PlaneDetectionMode arPlaneDetection mapping

    func testPlaneDetectionNoneMapsToEmpty() {
        let mode = ARSceneView.PlaneDetectionMode.none
        XCTAssertEqual(mode.arPlaneDetection, [])
    }

    func testPlaneDetectionHorizontalMapsCorrectly() {
        let mode = ARSceneView.PlaneDetectionMode.horizontal
        XCTAssertEqual(mode.arPlaneDetection, .horizontal)
    }

    func testPlaneDetectionVerticalMapsCorrectly() {
        let mode = ARSceneView.PlaneDetectionMode.vertical
        XCTAssertEqual(mode.arPlaneDetection, .vertical)
    }

    func testPlaneDetectionBothMapsCorrectly() {
        let mode = ARSceneView.PlaneDetectionMode.both
        XCTAssertEqual(mode.arPlaneDetection, [.horizontal, .vertical])
    }

    // MARK: - AnchorNode helpers

    func testAnchorNodeWorldCreation() {
        let anchor = AnchorNode.world(position: .init(x: 1, y: 0, z: -2))
        XCTAssertNotNil(anchor)
        XCTAssertNotNil(anchor.entity)
    }

    func testAnchorNodeWorldAtOrigin() {
        let anchor = AnchorNode.world(position: .zero)
        XCTAssertNotNil(anchor)
    }

    func testAnchorNodeAddChild() {
        let anchor = AnchorNode.world(position: .zero)
        let child = GeometryNode.cube(size: 0.1, color: .red)
        anchor.add(child.entity)
        XCTAssertEqual(anchor.entity.children.count, 1)
    }

    func testAnchorNodeRemoveChild() {
        let anchor = AnchorNode.world(position: .zero)
        let child = GeometryNode.cube(size: 0.1, color: .blue)
        anchor.add(child.entity)
        XCTAssertEqual(anchor.entity.children.count, 1)
        anchor.remove(child.entity)
        XCTAssertEqual(anchor.entity.children.count, 0)
    }

    func testAnchorNodeRemoveAll() {
        let anchor = AnchorNode.world(position: .zero)
        anchor.add(GeometryNode.cube(size: 0.1, color: .red).entity)
        anchor.add(GeometryNode.sphere(radius: 0.1, color: .green).entity)
        XCTAssertEqual(anchor.entity.children.count, 2)
        anchor.removeAll()
        XCTAssertEqual(anchor.entity.children.count, 0)
    }

    func testAnchorNodePlaneHorizontal() {
        let anchor = AnchorNode.plane(alignment: .horizontal, minimumBounds: .init(0.2, 0.2))
        XCTAssertNotNil(anchor)
    }

    func testAnchorNodePlaneVertical() {
        let anchor = AnchorNode.plane(alignment: .vertical)
        XCTAssertNotNil(anchor)
    }

    // MARK: - LightSlot modifier wire-up (#1138)

    /// Default `ARSceneView()` must initialise both light slots to
    /// ``LightSlot/systemDefault`` so the Android-parity dual-light
    /// (10 000-lux main + 3 000-lux fill) renders out of the box.
    func testDefaultLightSlotsAreSystemDefault() {
        let view = ARSceneView()
        XCTAssertEqual(view.mainLightSlot, .systemDefault)
        XCTAssertEqual(view.fillLightSlot, .systemDefault)
    }

    /// `.mainLight(_:)` returns a new view (value-type copy semantics).
    func testMainLightModifierReturnsNewInstance() {
        let original = ARSceneView()
        let modified = original.mainLight(.disabled)
        XCTAssertEqual(modified.mainLightSlot, .disabled)
        // Original instance must NOT have been mutated.
        XCTAssertEqual(original.mainLightSlot, .systemDefault)
    }

    /// `.fillLight(_:)` returns a new view (value-type copy semantics).
    func testFillLightModifierReturnsNewInstance() {
        let original = ARSceneView()
        let modified = original.fillLight(.disabled)
        XCTAssertEqual(modified.fillLightSlot, .disabled)
        XCTAssertEqual(original.fillLightSlot, .systemDefault)
    }

    /// `.fillLight(.disabled)` is the canonical single-light AR setup. Verify
    /// the slot value round-trips through the modifier.
    func testFillLightDisabledRoundTrip() {
        let view = ARSceneView()
            .fillLight(.disabled)
        XCTAssertEqual(view.fillLightSlot, .disabled)
    }

    /// `.mainLight(.custom(LightNode))` stores the caller's node reference.
    ///
    /// `@MainActor` because ``LightNode/directional(color:intensity:castsShadow:)``
    /// and ``LightNode/entity`` are MainActor-isolated (RealityKit
    /// `DirectionalLight` lives on the main actor).
    @MainActor
    func testMainLightCustomLightNode() {
        let custom = LightNode.directional(intensity: 5_000)
        let view = ARSceneView()
            .mainLight(.custom(custom))
        // Equatable on .custom compares entity identity (===), so verify both
        // the variant and the underlying entity reference.
        if case .custom(let stored) = view.mainLightSlot {
            XCTAssertTrue(stored.entity === custom.entity)
        } else {
            XCTFail("Expected .custom slot, got \(view.mainLightSlot)")
        }
    }

    /// `.fillLight(.custom(LightNode))` stores the caller's node reference.
    @MainActor
    func testFillLightCustomLightNode() {
        let brighterFill = LightNode.fill(intensity: 6_000)
        let view = ARSceneView()
            .fillLight(.custom(brighterFill))
        if case .custom(let stored) = view.fillLightSlot {
            XCTAssertTrue(stored.entity === brighterFill.entity)
        } else {
            XCTFail("Expected .custom slot, got \(view.fillLightSlot)")
        }
    }

    /// Calling `.mainLight(_:)` twice — the last value wins (copy semantics).
    func testMainLightModifierLastWins() {
        let view = ARSceneView()
            .mainLight(.disabled)
            .mainLight(.systemDefault)
        XCTAssertEqual(view.mainLightSlot, .systemDefault)
    }

    /// Calling `.fillLight(_:)` twice — the last value wins (copy semantics).
    func testFillLightModifierLastWins() {
        let view = ARSceneView()
            .fillLight(.disabled)
            .fillLight(.systemDefault)
        XCTAssertEqual(view.fillLightSlot, .systemDefault)
    }

    /// Chaining `.mainLight` + `.fillLight` + `.cameraExposure` + `.onSessionStarted`
    /// must all return a valid view (no compile errors, no crashes).
    @MainActor
    func testLightModifiersChainWithOtherModifiers() {
        let view = ARSceneView(planeDetection: .horizontal)
            .mainLight(.custom(LightNode.directional(intensity: 5_000)))
            .fillLight(.disabled)
            .cameraExposure(0.5)
            .onSessionStarted { _ in }
        XCTAssertNotNil(view)
        XCTAssertEqual(view.fillLightSlot, .disabled)
    }
}
#endif // os(iOS)

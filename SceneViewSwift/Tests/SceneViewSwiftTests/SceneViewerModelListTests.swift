import XCTest
import simd
@testable import SceneViewSwift

#if os(iOS) || os(visionOS) || os(macOS)
/// Pins the model-list rules that decide what is on screen.
///
/// The reconciliation itself is UIKit-only and untestable here, but every decision it
/// makes comes from these pure pieces: which entries are "the same model" as last time,
/// which transform was actually specified, and what a whole list's identity is. Each of
/// them fails silently — the wrong key shows one model where two were asked for, an
/// unspecified scale read as an identity scale flattens a model that authored its own —
/// so a green build and a screenshot of *a* model prove nothing about them.
final class SceneViewerModelListTests: XCTestCase {

    // MARK: - Entry identity

    /// The Flutter case: `loadModel` twice with one path means two models on screen.
    ///
    /// A source-keyed list collapses them into one, and the second call looks like it
    /// silently did nothing. This is the whole reason `SceneViewerModel.identity` exists.
    func testEntryKey_separatesTwoEntriesThatShareASource() {
        let first = makeEntry(identity: "A", assetPath: "models/helmet.usdz")
        let second = makeEntry(identity: "B", assetPath: "models/helmet.usdz")

        XCTAssertNotNil(first)
        XCTAssertNotNil(second)
        XCTAssertNotEqual(first?.key, second?.key)
    }

    /// The mirror case: the same entry re-applied must NOT reload.
    func testEntryKey_isStableForTheSameIdentityAndSource() {
        XCTAssertEqual(
            makeEntry(identity: "A", assetPath: "models/helmet.usdz")?.key,
            makeEntry(identity: "A", assetPath: "models/helmet.usdz")?.key
        )
    }

    /// An identity reused for a different model still reloads.
    ///
    /// Neither bridge does this today — both mint a fresh id per entry — which is exactly
    /// why it needs a test: nothing else would notice if the source dropped out of the key.
    func testEntryKey_changesWhenTheSourceDoesUnderAReusedIdentity() {
        XCTAssertNotEqual(
            makeEntry(identity: "A", assetPath: "models/helmet.usdz")?.key,
            makeEntry(identity: "A", assetPath: "models/robot.usdz")?.key
        )
    }

    /// With no identity, the source alone identifies the entry — the single-model caller's
    /// contract, where re-applying the same configuration must not reload.
    func testEntryKey_fallsBackToTheSourceWhenNoIdentityIsGiven() {
        XCTAssertEqual(
            makeEntry(identity: nil, assetPath: "models/helmet.usdz")?.key,
            makeEntry(identity: nil, assetPath: "models/helmet.usdz")?.key
        )
        XCTAssertNotEqual(
            makeEntry(identity: nil, assetPath: "models/helmet.usdz")?.key,
            makeEntry(identity: nil, assetPath: "models/robot.usdz")?.key
        )
    }

    // MARK: - Entries that must not reach the loader

    func testEntry_isDroppedWhenThereIsNoSource() {
        XCTAssertNil(
            SceneViewerModelEntry.make(
                identity: "A",
                request: .none,
                nodeName: nil,
                scale: nil,
                position: nil,
                animationName: nil,
                autoPlayAllAnimations: false
            )
        )
    }

    /// A refused URL arrives as `nil` from `SceneViewerModelRequest.make`, and must not
    /// become an entry — the scheme allowlist is a security invariant, and an entry built
    /// from a refusal would hand the loader a `file://` the guard just rejected.
    func testEntry_isDroppedWhenTheURLWasRefused() {
        let refused = SceneViewerModelRequest.make(
            assetPath: nil,
            urlString: "file:///etc/passwd",
            bytes: nil,
            bytesFileExtension: "usdz"
        )
        XCTAssertNil(refused, "precondition: the allowlist refuses file://")

        XCTAssertNil(
            SceneViewerModelEntry.make(
                identity: "A",
                request: refused,
                nodeName: nil,
                scale: nil,
                position: nil,
                animationName: nil,
                autoPlayAllAnimations: false
            )
        )
    }

    // MARK: - List identity

    func testListKey_changesWhenAnEntryIsAppended() {
        let first = makeEntry(identity: "A", assetPath: "a.usdz")!
        let second = makeEntry(identity: "B", assetPath: "b.usdz")!

        XCTAssertNotEqual(
            sceneViewerListKey([first]),
            sceneViewerListKey([first, second])
        )
    }

    func testListKey_changesWhenTheListIsCleared() {
        let entry = makeEntry(identity: "A", assetPath: "a.usdz")!
        XCTAssertNotEqual(sceneViewerListKey([entry]), sceneViewerListKey([]))
    }

    /// Order is part of the identity: reordering re-runs the loader rather than leaving
    /// the scene showing the previous arrangement.
    func testListKey_changesWithOrder() {
        let first = makeEntry(identity: "A", assetPath: "a.usdz")!
        let second = makeEntry(identity: "B", assetPath: "b.usdz")!

        XCTAssertNotEqual(
            sceneViewerListKey([first, second]),
            sceneViewerListKey([second, first])
        )
    }

    func testListKey_isStableForAnUnchangedList() {
        let first = makeEntry(identity: "A", assetPath: "a.usdz")!
        let second = makeEntry(identity: "B", assetPath: "b.usdz")!

        XCTAssertEqual(
            sceneViewerListKey([first, second]),
            sceneViewerListKey([first, second])
        )
    }

    // MARK: - "Not specified" versus "specified as the identity"

    /// `ModelNode.scale(_:)` overwrites what the asset authored, so "no scale given" has
    /// to stay distinguishable from "scale 1" — otherwise every model that ships a
    /// non-identity root transform is silently flattened on load.
    func testVector_isNilWhenUnspecified() {
        XCTAssertNil(sceneViewerVector(.nan, .nan, .nan))
    }

    func testVector_isNilWhenOnlyPartlySpecified() {
        XCTAssertNil(sceneViewerVector(2, .nan, .nan))
        XCTAssertNil(sceneViewerVector(.nan, 2, .nan))
        XCTAssertNil(sceneViewerVector(.nan, .nan, 2))
    }

    func testVector_isNilForNonFiniteComponents() {
        XCTAssertNil(sceneViewerVector(.infinity, 1, 1))
    }

    func testVector_survivesTheIdentityValue() {
        XCTAssertEqual(sceneViewerVector(1, 1, 1), SIMD3<Float>(1, 1, 1))
    }

    func testVector_survivesZero() {
        XCTAssertEqual(sceneViewerVector(0, 0, 0), SIMD3<Float>(0, 0, 0))
    }

    /// `SceneViewerModel` starts unspecified, so a caller that sets nothing gets a model
    /// the loader leaves alone.
    func testModel_startsWithNoTransformSpecified() {
        let model = SceneViewerModel()
        XCTAssertNil(sceneViewerVector(model.scaleX, model.scaleY, model.scaleZ))
        XCTAssertNil(sceneViewerVector(model.positionX, model.positionY, model.positionZ))
    }

    func testModel_uniformScaleSetsAllThreeComponents() {
        let model = SceneViewerModel()
        model.setScale(3)
        XCTAssertEqual(sceneViewerVector(model.scaleX, model.scaleY, model.scaleZ), SIMD3<Float>(3, 3, 3))
    }

    // MARK: - Camera control mode

    func testCameraControlMode_mapsEveryWireName() {
        XCTAssertEqual(sceneViewerCameraControlMode("orbit"), .orbit)
        XCTAssertEqual(sceneViewerCameraControlMode("pan"), .pan)
        XCTAssertEqual(sceneViewerCameraControlMode("firstPerson"), .firstPerson)
    }

    /// The string comes from user-authored Dart or JavaScript, so an unrecognised value is
    /// a typo to absorb rather than a reason to render nothing.
    func testCameraControlMode_fallsBackToOrbit() {
        XCTAssertEqual(sceneViewerCameraControlMode(nil), .orbit)
        XCTAssertEqual(sceneViewerCameraControlMode(""), .orbit)
        XCTAssertEqual(sceneViewerCameraControlMode("ORBIT"), .orbit)
        XCTAssertEqual(sceneViewerCameraControlMode("nonsense"), .orbit)
    }

    // MARK: - Helper

    private func makeEntry(identity: String?, assetPath: String) -> SceneViewerModelEntry? {
        SceneViewerModelEntry.make(
            identity: identity,
            request: .asset(assetPath),
            nodeName: nil,
            scale: nil,
            position: nil,
            animationName: nil,
            autoPlayAllAnimations: false
        )
    }
}
#endif

import XCTest
import RealityKit
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

    // MARK: - Camera pose

    /// The regression the agent review caught on #3035: `cameraPoseAuthored == false`
    /// stopped `state.cameraPose` from being *updated*, but the body still handed the
    /// modifier a non-nil default — which `SceneView` applies on first sight, framing a
    /// camera-less bridge at 15° where it used to sit at `CameraControls`' 30°.
    func testRequestedPose_isNilWhenNoCameraIsAuthored() {
        XCTAssertNil(sceneViewerRequestedPose(authored: false, pose: makePose()))
    }

    func testRequestedPose_isThePoseWhenACameraIsAuthored() {
        let pose = makePose()
        XCTAssertEqual(sceneViewerRequestedPose(authored: true, pose: pose), pose)
    }

    /// "Not authored" must survive a pose that happens to equal the host's own default —
    /// the discriminator is the flag, never the value.
    func testRequestedPose_isNilEvenWhenThePoseMatchesTheHostDefault() {
        let hostDefault = SceneCameraPose(
            azimuth: 0,
            elevation: SceneViewerAngle.radians(fromDegrees: 15),
            distance: 4,
            target: .zero
        )
        XCTAssertNil(sceneViewerRequestedPose(authored: false, pose: hostDefault))
    }

    // MARK: - Bytes file extension

    func testBytesFileExtension_keepsAPlainExtension() {
        XCTAssertEqual(sceneViewerBytesFileExtension("usdz"), "usdz")
        XCTAssertEqual(sceneViewerBytesFileExtension("glb"), "glb")
        XCTAssertEqual(sceneViewerBytesFileExtension("USDZ"), "USDZ")
        XCTAssertEqual(sceneViewerBytesFileExtension("usdz2"), "usdz2")
    }

    /// Every separator that could shape a path out of an extension. `appendingPathExtension`
    /// percent-encodes some of these rather than escaping the directory, so this pins the
    /// refusal at the boundary instead of relying on what the URL layer happens to do.
    func testBytesFileExtension_refusesAnythingThatCouldShapeAPath() {
        for hostile in ["../../etc/passwd", "usdz/../x", "us/dz", "usdz.", ".usdz",
                        "usdz%2F", "usdz\u{0000}", "usdz ", "usdz\n", "us-dz", "us_dz"] {
            XCTAssertEqual(
                sceneViewerBytesFileExtension(hostile), "usdz",
                "\(hostile) must be refused, not appended"
            )
        }
    }

    func testBytesFileExtension_refusesEmptyAndOverlong() {
        XCTAssertEqual(sceneViewerBytesFileExtension(""), "usdz")
        XCTAssertEqual(sceneViewerBytesFileExtension(String(repeating: "a", count: 9)), "usdz")
        XCTAssertEqual(sceneViewerBytesFileExtension(String(repeating: "a", count: 8)),
                       String(repeating: "a", count: 8))
    }

    /// Non-ASCII letters satisfy `isLetter`, so the ASCII test is what carries this.
    func testBytesFileExtension_refusesNonASCIILetters() {
        XCTAssertEqual(sceneViewerBytesFileExtension("usdź"), "usdz")
        XCTAssertEqual(sceneViewerBytesFileExtension("ｕｓｄｚ"), "usdz")
    }

    // MARK: - Tap resolution

    /// The bug, pinned. A tap inside a multi-level asset must report the model, not the
    /// mesh RealityKit's gesture actually hit.
    @MainActor
    func testModelRoot_climbsFromADeepMeshToTheModel() {
        let contentRoot = Entity()
        let model = Entity()
        model.name = "black_dragon"
        let skin = Entity()
        skin.name = "skin0"
        let deeper = Entity()
        model.addChild(skin)
        skin.addChild(deeper)
        contentRoot.addChild(model)

        XCTAssertTrue(sceneViewerModelRoot(for: skin, contentRoot: contentRoot) === model)
        XCTAssertTrue(sceneViewerModelRoot(for: deeper, contentRoot: contentRoot) === model)
    }

    /// The walk this replaces stopped at the first *named* ancestor. An unnamed model root
    /// above a named mesh is exactly the shape that made it report the mesh, so the
    /// resolution must not depend on names at all.
    @MainActor
    func testModelRoot_ignoresNamesEntirely() {
        let contentRoot = Entity()
        let model = Entity()          // deliberately unnamed
        let mesh = Entity()
        mesh.name = "skin0"
        model.addChild(mesh)
        contentRoot.addChild(model)

        XCTAssertTrue(sceneViewerModelRoot(for: mesh, contentRoot: contentRoot) === model)
    }

    /// A model that was tapped directly resolves to itself, not to its parent.
    @MainActor
    func testModelRoot_returnsTheModelWhenItIsTappedDirectly() {
        let contentRoot = Entity()
        let model = Entity()
        contentRoot.addChild(model)

        XCTAssertTrue(sceneViewerModelRoot(for: model, contentRoot: contentRoot) === model)
    }

    /// `nil`, not the content root and not the entity itself — this is what lets a bridge
    /// report "no model" rather than inventing a name for something that is not one.
    @MainActor
    func testModelRoot_isNilWhenNothingWasHit() {
        let contentRoot = Entity()
        XCTAssertNil(sceneViewerModelRoot(for: contentRoot, contentRoot: contentRoot))

        let detached = Entity()
        XCTAssertNil(sceneViewerModelRoot(for: detached, contentRoot: contentRoot))

        // A whole tree that hangs somewhere else entirely — the walk must terminate at the
        // root of that tree rather than loop or climb into the content root by accident.
        let otherRoot = Entity()
        let stranger = Entity()
        otherRoot.addChild(stranger)
        XCTAssertNil(sceneViewerModelRoot(for: stranger, contentRoot: contentRoot))
    }

    // MARK: - Node name

    /// The value a tap publishes on every bridge: the file's base name, no extension.
    func testNodeName_isTheFileBaseNameWithoutExtension() {
        XCTAssertEqual(nodeName(.asset("models/robot.glb")), "robot")
        XCTAssertEqual(nodeName(.asset("black_dragon.usdz")), "black_dragon")
        XCTAssertEqual(nodeName(.asset("a/b/c/helmet.gltf")), "helmet")
    }

    /// The bug this derivation exists for. `deletingPathExtension` on a raw URL string cuts
    /// at the *last* dot in the whole string, so an unstripped query publishes
    /// `robot.glb?sig=SIG&v=1` as the tapped node's name.
    func testNodeName_stripsQueryAndFragmentBeforeTheExtension() {
        XCTAssertEqual(
            nodeName(.url(URL(string: "https://cdn.example/models/robot.glb?sig=SIG&v=1.2")!)),
            "robot"
        )
        XCTAssertEqual(
            nodeName(.url(URL(string: "https://cdn.example/models/robot.glb#frag.2")!)),
            "robot"
        )
        XCTAssertEqual(
            nodeName(.url(URL(string: "https://cdn.example/models/robot.glb")!)),
            "robot"
        )
    }

    /// The hardening the security review asked for: a credential must not reach the name
    /// even when the URL has no path for the last-component cut to step over, and even when
    /// the `?` arrives percent-encoded. Both are handled by reducing the URL to its path.
    func testNodeName_neverPublishesCredentialsFromAURL() {
        XCTAssertEqual(
            nodeName(.url(URL(string: "https://user:pa55w0rd@cdn.example/models/robot.glb")!)),
            "robot"
        )
        // No path at all — the authority, credentials included, must not become the name.
        XCTAssertNil(nodeName(.url(URL(string: "https://user:pa55w0rd@cdn.example")!)))
        XCTAssertNil(nodeName(.url(URL(string: "https://user:pa55w0rd@cdn.example/")!)))
        // A percent-encoded query is a real `?` once the path is decoded.
        XCTAssertEqual(
            nodeName(.url(URL(string: "https://cdn.example/robot.glb%3Fsig=SECRET")!)),
            "robot"
        )
    }

    /// An explicit name is a caller's override and must survive verbatim — extension and
    /// all, since the caller chose it rather than a file path.
    func testNodeName_prefersAnExplicitName() {
        XCTAssertEqual(
            sceneViewerNodeName(explicit: "hero", request: .asset("models/robot.glb")),
            "hero"
        )
        XCTAssertEqual(
            sceneViewerNodeName(explicit: "keep.this", request: .asset("models/robot.glb")),
            "keep.this"
        )
    }

    /// `nil`, never `""`. An empty name is indistinguishable from "the asset named it that",
    /// and the bridges map `nil` to the `nodeName: null` Android reports for a miss.
    func testNodeName_isNilWhenNothingCanBeDerived() {
        XCTAssertNil(sceneViewerNodeName(explicit: "", request: .bytes(Data(), fileExtension: "usdz")))
        XCTAssertNil(nodeName(.bytes(Data([1, 2, 3]), fileExtension: "usdz")))
        XCTAssertNil(nodeName(.none))
        XCTAssertNil(nodeName(.asset("")))
        XCTAssertNil(nodeName(.asset("?sig=SIG")))
    }

    /// The entry funnel applies the derivation, so every caller of the host gets it —
    /// including `sceneview-compose`, which sets no name at all.
    func testEntry_carriesTheDerivedNodeName() {
        XCTAssertEqual(makeEntry(identity: "A", assetPath: "models/helmet.usdz")?.nodeName,
                       "helmet")
    }

    // MARK: - Helper

    private func nodeName(_ request: SceneViewerModelRequest) -> String? {
        sceneViewerNodeName(explicit: nil, request: request)
    }

    private func makePose() -> SceneCameraPose {
        SceneCameraPose(
            azimuth: SceneViewerAngle.radians(fromDegrees: 42),
            elevation: SceneViewerAngle.radians(fromDegrees: 21),
            distance: 3,
            target: SIMD3<Float>(1, 2, 3)
        )
    }

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

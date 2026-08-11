import XCTest
import RealityKit
@testable import SceneViewSwift

#if os(iOS) || os(visionOS) || os(macOS)
/// Pins what a tap on a bridge-loaded model reports.
///
/// Both halves fail silently. `sceneViewerModelFileName` writes a name nobody reads until
/// a user taps, and a wrong one is a plausible-looking string — `robot.glb?sig=SIG&v=1`
/// is what a raw `deletingPathExtension` produces for a signed CDN URL, and it reaches an
/// app's label and its analytics before anyone notices it carries a signature.
/// `sceneViewerTappedModelEntity` is the fix for a bug that *looked* like it worked:
/// tapping `black_dragon.usdz` reported `skin0`, a real name, from a real entity, that
/// simply was not the model.
///
/// Both are UIKit-free on purpose, so they run under `swift test` on macOS rather than
/// only inside a simulator app nobody runs per-PR.
final class SceneViewerTapResolutionTests: XCTestCase {

    // MARK: - File name derivation

    func testModelFileName_takesTheLastPathComponentOfAPlainPath() {
        XCTAssertEqual(sceneViewerModelFileName("models/black_dragon.usdz"), "black_dragon.usdz")
    }

    /// The reason the query is stripped *before* the last path component is taken.
    ///
    /// `ModelNode.load(from:)` takes a remote URL and `SceneViewerModel.urlString` is
    /// public, so a signed CDN URL is a real input, not a hypothetical one.
    func testModelFileName_stripsAQueryStringWithItsSignature() {
        XCTAssertEqual(
            sceneViewerModelFileName("https://cdn.example.com/a/robot.glb?sig=SECRET&v=1.2"),
            "robot.glb"
        )
    }

    func testModelFileName_stripsAFragment() {
        XCTAssertEqual(
            sceneViewerModelFileName("https://cdn.example.com/robot.glb#scene1"),
            "robot.glb"
        )
    }

    /// A source with no extension keeps its whole last component — the extension strip
    /// happens at the report, and has nothing to remove here.
    func testModelFileName_keepsAnExtensionlessName() {
        XCTAssertEqual(sceneViewerModelFileName("models/robot"), "robot")
    }

    // MARK: - Resolving a tapped entity

    /// The `black_dragon.usdz` case: `SpatialTapGesture` hands back the deepest hit
    /// entity, several levels inside the asset. The walk must climb past every one of
    /// them and stop at the model root — the only entity the bridge named.
    func testTappedModelEntity_resolvesADeepGrandchildToTheModelRoot() {
        let contentRoot = Entity()
        let modelRoot = Entity()
        modelRoot.name = "black_dragon.usdz"
        let mesh = Entity()
        mesh.name = "skin0"
        let deeper = Entity()
        deeper.name = "skin0_part2"

        contentRoot.addChild(modelRoot)
        modelRoot.addChild(mesh)
        mesh.addChild(deeper)

        XCTAssertIdentical(
            sceneViewerTappedModelEntity(deeper, contentRoot: contentRoot),
            modelRoot
        )
    }

    /// A direct child is already the model root and resolves to itself.
    func testTappedModelEntity_resolvesADirectChildToItself() {
        let contentRoot = Entity()
        let modelRoot = Entity()
        contentRoot.addChild(modelRoot)

        XCTAssertIdentical(
            sceneViewerTappedModelEntity(modelRoot, contentRoot: contentRoot),
            modelRoot
        )
    }

    /// An entity that is not in the content root's tree at all — something else added to
    /// the scene — is not a model, and must not be reported as one.
    func testTappedModelEntity_returnsNilForAnEntityOutsideTheTree() {
        let contentRoot = Entity()
        contentRoot.addChild(Entity())
        let stranger = Entity()

        XCTAssertNil(sceneViewerTappedModelEntity(stranger, contentRoot: contentRoot))
    }

    /// The content root itself is not a model. Without the `current !== contentRoot`
    /// guard this walk would keep climbing to whatever the host attached the root to, and
    /// report *that* as the tapped model.
    func testTappedModelEntity_returnsNilForTheContentRootItself() {
        let sceneRoot = Entity()
        let contentRoot = Entity()
        sceneRoot.addChild(contentRoot)

        XCTAssertNil(sceneViewerTappedModelEntity(contentRoot, contentRoot: contentRoot))
    }
}
#endif

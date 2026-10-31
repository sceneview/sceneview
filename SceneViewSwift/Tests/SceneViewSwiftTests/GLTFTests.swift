import XCTest
import simd
@testable import SceneViewSwift

#if os(iOS) || os(macOS) || os(visionOS)
import RealityKit
#endif

/// The in-house glTF 2.0 importer: containers, accessors, hierarchy, materials,
/// animations, skins, and the refusal of required extensions it does not implement.
final class GLTFTests: XCTestCase {

    private var directory: URL!

    override func setUpWithError() throws {
        try super.setUpWithError()
        directory = try MeshFixtures.makeDirectory()
    }

    override func tearDownWithError() throws {
        if let directory { try? FileManager.default.removeItem(at: directory) }
        directory = nil
        try super.tearDownWithError()
    }

    private func write(_ data: Data, as name: String) throws -> URL {
        let url = directory.appendingPathComponent(name)
        try data.write(to: url)
        return url
    }

    private func scene(_ builder: GLTFBuilder, glb: Bool = true) throws -> GLTFScene {
        try GLTFScene.decode(GLTFDocument.read(data: glb ? builder.glb() : builder.gltf(), baseURL: nil))
    }

    // MARK: - Sniffing and format metadata

    func testSniffsGLBByMagicWhateverTheExtension() throws {
        let url = try write(GLTFBuilder.triangle().glb(), as: "download.bin")
        XCTAssertEqual(try ModelFormat.sniff(contentsOf: url), .glb)
    }

    func testSniffsGLTFJSONByContent() throws {
        let url = try write(GLTFBuilder.triangle().gltf(), as: "model")
        XCTAssertEqual(try ModelFormat.sniff(contentsOf: url), .gltf)
    }

    func testGLBMagicWithVersion1IsNotGLB() {
        var header = Data("glTF".utf8)
        header.append(contentsOf: [1, 0, 0, 0, 0, 0, 0, 0])
        XCTAssertNil(ModelFormat.sniff(header: header, fileSize: header.count))
    }

    func testPlainJSONIsNotGLTF() {
        let header = Data(#"{"name": "settings"}"#.utf8)
        XCTAssertNil(ModelFormat.sniff(header: header, fileSize: header.count))
    }

    func testExtensionsAndUnits() {
        XCTAssertEqual(ModelFormat(fileExtension: ".GLB"), .glb)
        XCTAssertEqual(ModelFormat(fileExtension: "gltf"), .gltf)
        XCTAssertEqual(ModelFormat.glb.loader, .sceneView)
        XCTAssertEqual(ModelFormat.gltf.defaultUnit, .meters)
        XCTAssertTrue(ModelFormat.glb.carriesUnit)
        XCTAssertTrue(ModelFormat.gltf.carriesUnit)
    }

    // MARK: - Containers and accessors

    func testTriangleFromGLBAndFromDataURIAgree() throws {
        for glb in [true, false] {
            let scene = try scene(.triangle(), glb: glb)
            XCTAssertEqual(scene.meshes.count, 1)
            let p = try XCTUnwrap(scene.meshes.first?.primitives.first)
            XCTAssertEqual(p.positions, [SIMD3(0, 0, 0), SIMD3(1, 0, 0), SIMD3(0, 1, 0)])
            XCTAssertEqual(p.indices, [0, 1, 2], "a non-indexed primitive gets sequential indices")
        }
    }

    func testGLTFWithAnExternalBufferNextToIt() throws {
        let builder = GLTFBuilder.triangle()
        _ = try write(builder.bin, as: "triangle.bin")
        let url = try write(builder.gltf(externalBufferName: "triangle.bin"), as: "triangle.gltf")
        let asset = try MeshAsset.load(contentsOf: url)
        XCTAssertEqual(asset.format, .gltf)
        XCTAssertEqual(asset.unit, .meters)
        XCTAssertEqual(asset.triangleCount, 1)
    }

    func testExternalURIsCannotEscapeTheModelFolder() throws {
        let builder = GLTFBuilder.triangle()
        let url = try write(builder.gltf(externalBufferName: "../secret.bin"), as: "evil.gltf")
        XCTAssertThrowsError(try GLTFDocument.read(contentsOf: url)) { error in
            guard case .malformed(let reason) = error as? ModelLoadingError else {
                return XCTFail("expected .malformed, got \(error)")
            }
            XCTAssertTrue(reason.contains("outside"), reason)
        }
    }

    func testNormalizedIntegersAndSparseAccessors() throws {
        var b = GLTFBuilder.triangle()
        // A normalized UNSIGNED_BYTE VEC2 (TEXCOORD_0): 255 → 1.0, 0 → 0.0.
        let uvView = b.view(Data([0, 0, 255, 0, 0, 255, 0, 0]))
        let uv = b.accessor(["bufferView": uvView, "componentType": 5121, "normalized": true, "count": 3, "type": "VEC2"])
        // Sparse POSITION: dense base of zeros (no bufferView) with vertex 2 set to (0, 3, 0).
        let sparseIndex = b.view(Data([2, 0]))
        let sparseValue = b.view(GLTFBuilder.bytes([0, 3, 0]))
        let sparse = b.accessor([
            "componentType": 5126, "count": 3, "type": "VEC3",
            "sparse": ["count": 1,
                       "indices": ["bufferView": sparseIndex, "componentType": 5123],
                       "values": ["bufferView": sparseValue]],
        ])
        b.json["meshes"] = [["primitives": [["attributes": ["POSITION": sparse, "TEXCOORD_0": uv]]]]]

        let p = try XCTUnwrap(try scene(b).meshes.first?.primitives.first)
        XCTAssertEqual(p.positions, [SIMD3(0, 0, 0), SIMD3(0, 0, 0), SIMD3(0, 3, 0)])
        // UVs come back flipped into RealityKit's bottom-left convention: v' = 1 - v.
        XCTAssertEqual(p.textureCoordinates, [SIMD2(0, 1), SIMD2(1, 1), SIMD2(0, 0)])
    }

    func testAccessorThatOverrunsItsBufferThrows() throws {
        var b = GLTFBuilder.triangle()
        b.json["meshes"] = [["primitives": [["attributes": ["POSITION": b.accessor([
            "bufferView": 0, "componentType": 5126, "count": 300, "type": "VEC3",
        ])]]]]]
        XCTAssertThrowsError(try scene(b)) { error in
            guard case .malformed = error as? ModelLoadingError else { return XCTFail("\(error)") }
        }
    }

    // MARK: - Required extensions

    func testDracoRequiredThrowsATypedErrorNamingIt() throws {
        var b = GLTFBuilder.triangle()
        b.json["extensionsUsed"] = ["KHR_draco_mesh_compression"]
        b.json["extensionsRequired"] = ["KHR_draco_mesh_compression"]
        let url = try write(b.glb(), as: "draco.glb")
        XCTAssertThrowsError(try MeshAsset.load(contentsOf: url)) { error in
            XCTAssertEqual(error as? ModelLoadingError, .unsupportedRequiredExtension("KHR_draco_mesh_compression"))
            XCTAssertTrue(
                (error as? ModelLoadingError)?.errorDescription?.contains("KHR_draco_mesh_compression") == true
            )
        }
    }

    func testSupportedRequiredExtensionsAreAccepted() throws {
        var b = GLTFBuilder.triangle()
        b.json["extensionsRequired"] = ["KHR_materials_unlit", "KHR_texture_transform"]
        XCTAssertNoThrow(try scene(b))
    }

    // MARK: - Hierarchy

    func testTwoNodeHierarchyComposesTransforms() throws {
        let scene = try scene(.twoNodeHierarchy())
        XCTAssertEqual(scene.roots, [0])
        XCTAssertEqual(scene.nodes[1].parent, 0)
        let world = scene.restWorldMatrices()
        let origin = world[1] * SIMD4<Float>(0, 0, 0, 1)
        XCTAssertEqual(origin.x, 2, accuracy: 1e-5)
        XCTAssertEqual(origin.y, 1, accuracy: 1e-5)

        // The flat MeshAsset view bakes the same transform in: (1, 0, 0) → (4, 1, 0).
        let parts = scene.flattenedGeometry()
        XCTAssertEqual(parts.count, 1)
        XCTAssertEqual(parts[0].positions[1].x, 4, accuracy: 1e-5)
    }

    func testMatrixTransformDecomposes() {
        let m = simd_float4x4(
            SIMD4(0, 2, 0, 0), SIMD4(-2, 0, 0, 0), SIMD4(0, 0, 2, 0), SIMD4(5, 6, 7, 1)
        )
        let t = GLTFTransform(matrix: m)
        XCTAssertEqual(t.translation, SIMD3(5, 6, 7))
        XCTAssertEqual(t.scale.x, 2, accuracy: 1e-5)
        let back = t.matrix
        for c in 0..<4 { for r in 0..<4 { XCTAssertEqual(back[c][r], m[c][r], accuracy: 1e-4) } }
    }

    // MARK: - Materials and images

    func testTexturedQuadDecodesItsImageAndSampler() throws {
        let scene = try scene(.texturedQuad())
        let material = try XCTUnwrap(scene.materials.first)
        XCTAssertEqual(material.baseColorTexture?.image, 0)
        XCTAssertEqual(material.metallicFactor, 0)
        XCTAssertTrue(material.doubleSided)
        let image = try XCTUnwrap(scene.images[GLTFImageKey(image: 0, variant: .color)])
        XCTAssertEqual(image.width, 2)
        XCTAssertEqual(scene.samplers.first?.wrapS, 33071)
    }

    // MARK: - Animation

    func testLinearTranslationSamplesInBetween() throws {
        let scene = try scene(.translationAnimation())
        let animation = try XCTUnwrap(scene.animations.first)
        XCTAssertEqual(animation.endTime, 1)
        let mid = scene.localTransforms(animation: animation, at: 0.25)
        XCTAssertEqual(mid[0].translation.x, 0.5, accuracy: 1e-5)
        let after = scene.localTransforms(animation: animation, at: 3)
        XCTAssertEqual(after[0].translation.x, 2, accuracy: 1e-5, "clamped past the last key")
    }

    func testStepInterpolationHolds() throws {
        let scene = try scene(.translationAnimation(interpolation: "STEP"))
        let animation = try XCTUnwrap(scene.animations.first)
        XCTAssertEqual(scene.localTransforms(animation: animation, at: 0.9)[0].translation.x, 0)
    }

    // MARK: - Skin

    func testSkinIsLaidOutParentsFirst() throws {
        let scene = try scene(.skinnedStrip())
        let skin = try XCTUnwrap(scene.skins.first)
        XCTAssertEqual(skin.joints, [1, 2])
        let layout = GLTFSkeletonLayout(skin: skin, scene: scene)
        XCTAssertEqual(layout.names, ["Root", "Tip"], "parents before children")
        XCTAssertEqual(layout.parentIndices, [nil, 0])
        XCTAssertEqual(layout.remap, [1, 0], "glTF slot 0 (Tip) is RealityKit joint 1")
        XCTAssertEqual(layout.inverseBindMatrices[1].columns.3.y, -1, accuracy: 1e-6)

        // At the end of "Bend" the tip joint is rotated 90° about Z relative to Root.
        let animation = try XCTUnwrap(scene.animations.first)
        let world = scene.worldMatrices(locals: scene.localTransforms(animation: animation, at: 1).map(\.matrix))
        let local = layout.jointLocalMatrices(world: world)[1]
        let x = local * SIMD4<Float>(1, 0, 0, 0)
        XCTAssertEqual(x.y, 1, accuracy: 1e-4)
        XCTAssertEqual(local.columns.3.y, 1, accuracy: 1e-5)

        let p = try XCTUnwrap(scene.meshes.first?.primitives.first)
        XCTAssertEqual(p.influencesPerVertex, 4)
        XCTAssertEqual(p.joints?.prefix(4), [1, 0, 0, 0])
    }

    // MARK: - RealityKit entity

    #if os(iOS) || os(macOS) || os(visionOS)
    @MainActor
    func testModelNodeLoadsAGLBTriangle() async throws {
        let url = try write(GLTFBuilder.triangle().glb(), as: "triangle.glb")
        let node = try await ModelNode.load(contentsOf: url)
        let bounds = node.entity.visualBounds(relativeTo: nil)
        XCTAssertEqual(bounds.extents.x, 1, accuracy: 1e-3)
        XCTAssertEqual(bounds.extents.y, 1, accuracy: 1e-3)
    }

    @MainActor
    func testTexturedQuadBuildsAPhysicallyBasedMaterial() async throws {
        let url = try write(GLTFBuilder.texturedQuad().glb(), as: "quad.glb")
        let node = try await ModelNode.load(contentsOf: url, enableCollision: false)
        let model = try XCTUnwrap(node.entity.findEntity(named: "Quad") as? ModelEntity)
        let material = try XCTUnwrap(model.model?.materials.first as? PhysicallyBasedMaterial)
        XCTAssertNotNil(material.baseColor.texture, "the embedded PNG becomes the base colour texture")
        XCTAssertEqual(material.faceCulling, .none, "doubleSided")
    }

    @MainActor
    func testHierarchyBecomesEntities() async throws {
        let url = try write(GLTFBuilder.twoNodeHierarchy().glb(), as: "tree.glb")
        let node = try await ModelNode.load(contentsOf: url, enableCollision: false)
        let child = try XCTUnwrap(node.entity.findEntity(named: "Child"))
        XCTAssertEqual(child.parent?.name, "Parent")
        let world = child.position(relativeTo: nil)
        XCTAssertEqual(world.x, 2, accuracy: 1e-4)
        XCTAssertEqual(world.y, 1, accuracy: 1e-4)
    }

    @MainActor
    func testAnimationIsExposedThroughModelNode() async throws {
        let url = try write(GLTFBuilder.translationAnimation().glb(), as: "slide.glb")
        let node = try await ModelNode.load(contentsOf: url, enableCollision: false)
        XCTAssertEqual(node.animationCount, 1)
        XCTAssertEqual(node.animationNames, ["Slide"])
    }

    @MainActor
    func testSkinnedMeshGetsASkeletonAndAJointAnimation() async throws {
        let url = try write(GLTFBuilder.skinnedStrip().glb(), as: "strip.glb")
        let node = try await ModelNode.load(contentsOf: url, enableCollision: false)
        let skinned = try XCTUnwrap(node.entity.findEntity(named: "Strip_skinned") as? ModelEntity)
        let skeleton = try XCTUnwrap(skinned.model?.mesh.contents.skeletons.first)
        XCTAssertEqual(skeleton.joints.map(\.name), ["Root", "Tip"])
        XCTAssertEqual(node.animationCount, 1)
    }

    @MainActor
    func testDracoRequiredFailsTheModelNodeLoadToo() async throws {
        var b = GLTFBuilder.triangle()
        b.json["extensionsRequired"] = ["KHR_draco_mesh_compression"]
        let url = try write(b.glb(), as: "draco.glb")
        do {
            _ = try await ModelNode.load(contentsOf: url)
            XCTFail("a Draco-required file must not load")
        } catch {
            XCTAssertEqual(error as? ModelLoadingError, .unsupportedRequiredExtension("KHR_draco_mesh_compression"))
        }
    }
    #endif
}

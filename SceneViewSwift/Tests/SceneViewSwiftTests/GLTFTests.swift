import XCTest
import simd
@testable import SceneViewSwift

#if os(iOS) || os(macOS) || os(visionOS)
import RealityKit
import ImageIO
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

    // MARK: - Hostile files: typed errors, never a crash or an unbounded allocation

    private func assertMalformed(
        _ builder: GLTFBuilder, file: StaticString = #filePath, line: UInt = #line
    ) {
        XCTAssertThrowsError(try scene(builder), file: file, line: line) { error in
            guard case .malformed? = error as? ModelLoadingError else {
                return XCTFail("expected ModelLoadingError.malformed, got \(error)", file: file, line: line)
            }
        }
    }

    /// The triangle with its POSITION swapped for a hand-written accessor.
    private func triangle(positionAccessor fields: [String: Any]) -> GLTFBuilder {
        var b = GLTFBuilder.triangle()
        let bad = b.accessor(fields)
        b.json["meshes"] = [["primitives": [["attributes": ["POSITION": bad]]]]]
        return b
    }

    func testNegativeAccessorCountThrows() {
        assertMalformed(triangle(positionAccessor: [
            "bufferView": 0, "componentType": 5126, "count": -1, "type": "VEC3",
        ]))
    }

    func testHugeAccessorCountThrowsBeforeAllocating() {
        // View-less: legal, zero-filled, so nothing but the cap bounds the allocation.
        assertMalformed(triangle(positionAccessor: [
            "componentType": 5126, "count": 1 << 40, "type": "VEC3",
        ]))
        // count × components overflows Int.
        assertMalformed(triangle(positionAccessor: [
            "componentType": 5126, "count": Int.max / 2, "type": "MAT4",
        ]))
        // Backed by a view, stride × (count - 1) far past it.
        assertMalformed(triangle(positionAccessor: [
            "bufferView": 0, "componentType": 5126, "count": 1 << 24, "type": "VEC3",
        ]))
    }

    func testHugeSparseCountThrows() {
        var b = GLTFBuilder.triangle()
        let indices = b.view(Data([0, 0, 0, 0]))
        let values = b.view(GLTFBuilder.bytes([0, 0, 0]))
        let bad = b.accessor([
            "bufferView": 0, "componentType": 5126, "count": 3, "type": "VEC3",
            "sparse": ["count": 1 << 40,
                       "indices": ["bufferView": indices, "componentType": 5125],
                       "values": ["bufferView": values]],
        ])
        b.json["meshes"] = [["primitives": [["attributes": ["POSITION": bad]]]]]
        assertMalformed(b)
    }

    func testNegativeBufferViewOffsetOrLengthThrows() {
        var b = GLTFBuilder.triangle()
        b.patchView(0, "byteOffset", -12)
        assertMalformed(b)

        var c = GLTFBuilder.triangle()
        c.patchView(0, "byteLength", -1)
        assertMalformed(c)
    }

    func testNodeCycleThrows() {
        var b = GLTFBuilder.triangle()
        b.json["nodes"] = [
            ["name": "A", "mesh": 0, "children": [1]],
            ["name": "B", "children": [0]],
        ]
        b.json["scenes"] = [["nodes": [0]]]
        assertMalformed(b)
    }

    func testNodeNestingDeeperThanTheCapThrows() {
        var b = GLTFBuilder.triangle()
        let depth = GLTFScene.maxNodeDepth + 2
        b.json["nodes"] = (0..<depth).map { i -> [String: Any] in
            i + 1 < depth ? ["children": [i + 1]] : ["mesh": 0]
        }
        b.json["scenes"] = [["nodes": [0]]]
        assertMalformed(b)
    }

    func testInfiniteKeyframeTimeThrows() {
        var b = GLTFBuilder.triangle()
        let times = b.floats([0, .infinity], type: "SCALAR")
        let values = b.floats([0, 0, 0, 2, 0, 0], type: "VEC3")
        b.json["animations"] = [[
            "samplers": [["input": times, "output": values]],
            "channels": [["sampler": 0, "target": ["node": 0, "path": "translation"]]],
        ]]
        assertMalformed(b)
    }

    func testNaNJointIndexThrows() {
        var b = GLTFBuilder.skinnedStrip()
        // JOINTS_0 as floats (not allowed by the spec, but representable) with a NaN.
        let joints = b.floats([.nan, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0], type: "VEC4")
        var meshes = b.json["meshes"] as! [[String: Any]]
        var primitives = meshes[0]["primitives"] as! [[String: Any]]
        var attributes = primitives[0]["attributes"] as! [String: Any]
        attributes["JOINTS_0"] = joints
        primitives[0]["attributes"] = attributes
        meshes[0]["primitives"] = primitives
        b.json["meshes"] = meshes
        assertMalformed(b)
    }

    func testDracoRequiredThrowsATypedErrorNamingIt() throws {
        var b = GLTFBuilder.triangle()
        b.json["extensionsUsed"] = ["KHR_draco_mesh_compression"]
        b.json["extensionsRequired"] = ["KHR_draco_mesh_compression"]
        let url = try write(b.glb(), as: "draco.glb")
        XCTAssertThrowsError(try MeshAsset.load(contentsOf: url)) { error in
            XCTAssertEqual(error as? UnsupportedModelExtensionError, UnsupportedModelExtensionError(extensionName: "KHR_draco_mesh_compression"))
            XCTAssertTrue(
                (error as? UnsupportedModelExtensionError)?.errorDescription?.contains("KHR_draco_mesh_compression") == true
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

    /// RealityKit lights the surface with `emissiveColor.color` instead of multiplying it
    /// into the emissive texture, so DamagedHelmet's factor [1, 1, 1] used to wash the whole
    /// helmet out to white. The factor must leave the colour black and scale the map.
    @MainActor
    func testEmissiveFactorScalesTheMapInsteadOfLightingTheWholeSurface() async throws {
        let url = try write(GLTFBuilder.emissiveQuad().glb(), as: "glow.glb")
        let node = try await ModelNode.load(contentsOf: url, enableCollision: false)
        let model = try XCTUnwrap(node.entity.findEntity(named: "Quad") as? ModelEntity)
        let material = try XCTUnwrap(model.model?.materials.first as? PhysicallyBasedMaterial)
        XCTAssertNotNil(material.emissiveColor.texture, "the emissive map is kept")
        let linear = try XCTUnwrap(CGColorSpace(name: CGColorSpace.linearSRGB))
        let color = try XCTUnwrap(material.emissiveColor.color.cgColor.converted(to: linear, intent: .defaultIntent, options: nil))
        for c in try XCTUnwrap(color.components).prefix(3) {
            XCTAssertEqual(Double(c), 0, accuracy: 1e-3, "a non-black colour would glow over the black texels")
        }
        XCTAssertEqual(material.emissiveIntensity, 1, accuracy: 1e-6)

        // Factor × strength, and a coloured factor baked into the texels in linear light.
        let plan = try XCTUnwrap(GLTFEntityBuilder.emissive(factor: [0.5, 0.25, 0], strength: 2, hasTexture: true))
        XCTAssertEqual(plan.color, .zero)
        XCTAssertEqual(plan.intensity, 1, accuracy: 1e-6)
        XCTAssertEqual(plan.textureTint, [1, 0.5, 0])
        XCTAssertEqual(GLTFEntityBuilder.emissive(factor: [0.2, 0.2, 0.2], strength: 1, hasTexture: false),
                       .init(color: [0.2, 0.2, 0.2], intensity: 1, textureTint: nil), "no map: the factor is the colour")
        XCTAssertNil(GLTFEntityBuilder.emissive(factor: .zero, strength: 1, hasTexture: true))
        let source = try XCTUnwrap(CGImageSourceCreateWithData(GLTFBuilder.checkerPNG() as CFData, nil))
        let checker = try XCTUnwrap(CGImageSourceCreateImageAtIndex(source, 0, nil))
        let tinted = try XCTUnwrap(GLTFEntityBuilder.tinted(checker, by: [0.5, 1, 0]))
        let bytes = try XCTUnwrap(tinted.dataProvider?.data as Data?)
        XCTAssertEqual(Array(bytes[12..<15]), [188, 255, 0], "white × (0.5, 1, 0) in linear light")
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
    func testEnormousFiniteDurationIsCappedNotTrapped() async throws {
        var b = GLTFBuilder.triangle()
        let times = b.floats([0, 1e30], type: "SCALAR")
        let values = b.floats([0, 0, 0, 2, 0, 0], type: "VEC3")
        b.json["animations"] = [[
            "name": "Forever",
            "samplers": [["input": times, "output": values]],
            "channels": [["sampler": 0, "target": ["node": 0, "path": "translation"]]],
        ]]
        let node = try await ModelNode.load(contentsOf: write(b.glb(), as: "forever.glb"))
        XCTAssertEqual(node.animationCount, 1)
    }

    @MainActor
    func testPlayAllAnimationsSequencesSeveralClips() async throws {
        var b = GLTFBuilder.translationAnimation()
        var animations = b.json["animations"] as! [[String: Any]]
        var second = animations[0]
        second["name"] = "SlideAgain"
        animations.append(second)
        b.json["animations"] = animations
        let node = try await ModelNode.load(contentsOf: write(b.glb(), as: "two-clips.glb"))
        XCTAssertEqual(node.animationCount, 2)
        node.playAllAnimations()
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
            XCTAssertEqual(error as? UnsupportedModelExtensionError, UnsupportedModelExtensionError(extensionName: "KHR_draco_mesh_compression"))
        }
    }
    #endif
}

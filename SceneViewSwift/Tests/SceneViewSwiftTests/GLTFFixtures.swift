import Foundation
import simd
#if canImport(ImageIO)
import ImageIO
import CoreGraphics
import UniformTypeIdentifiers
#endif

/// Builds small glTF 2.0 files in memory — GLB or `.gltf` with a `data:` buffer — so the
/// importer is tested against bytes laid out exactly as the spec says, without binary
/// fixtures in the repository.
struct GLTFBuilder {

    var json: [String: Any] = ["asset": ["version": "2.0", "generator": "SceneViewSwift tests"]]
    private(set) var bin = Data()
    private var views: [[String: Any]] = []
    private var accessors: [[String: Any]] = []

    // MARK: Buffers

    /// Appends raw bytes as a buffer view (4-byte aligned) and returns its index.
    mutating func view(_ bytes: Data, target: Int? = nil) -> Int {
        while bin.count % 4 != 0 { bin.append(0) }
        var v: [String: Any] = ["buffer": 0, "byteOffset": bin.count, "byteLength": bytes.count]
        if let target { v["target"] = target }
        bin.append(bytes)
        views.append(v)
        return views.count - 1
    }

    mutating func accessor(_ fields: [String: Any]) -> Int {
        accessors.append(fields)
        return accessors.count - 1
    }

    mutating func floats(_ values: [Float], type: String, minMax: Bool = false) -> Int {
        let components = ["SCALAR": 1, "VEC2": 2, "VEC3": 3, "VEC4": 4, "MAT4": 16][type]!
        let v = view(Self.bytes(values))
        var a: [String: Any] = ["bufferView": v, "componentType": 5126, "count": values.count / components, "type": type]
        if minMax {
            var lo = [Float](repeating: .infinity, count: components)
            var hi = [Float](repeating: -.infinity, count: components)
            for (i, x) in values.enumerated() {
                lo[i % components] = min(lo[i % components], x)
                hi[i % components] = max(hi[i % components], x)
            }
            a["min"] = lo; a["max"] = hi
        }
        return accessor(a)
    }

    mutating func vec3(_ values: [SIMD3<Float>], minMax: Bool = false) -> Int {
        floats(values.flatMap { [$0.x, $0.y, $0.z] }, type: "VEC3", minMax: minMax)
    }

    mutating func indicesU16(_ values: [UInt16]) -> Int {
        var d = Data()
        for v in values { withUnsafeBytes(of: v.littleEndian) { d.append(contentsOf: $0) } }
        return accessor(["bufferView": view(d, target: 34963), "componentType": 5123, "count": values.count, "type": "SCALAR"])
    }

    static func bytes(_ values: [Float]) -> Data {
        var d = Data()
        for v in values { withUnsafeBytes(of: v.bitPattern.littleEndian) { d.append(contentsOf: $0) } }
        return d
    }

    // MARK: Output

    private func finalJSON(bufferURI: String?) -> Data {
        var j = json
        if !accessors.isEmpty { j["accessors"] = accessors }
        if !views.isEmpty { j["bufferViews"] = views }
        if !bin.isEmpty {
            var b: [String: Any] = ["byteLength": bin.count]
            if let bufferURI { b["uri"] = bufferURI }
            j["buffers"] = [b]
        }
        return try! JSONSerialization.data(withJSONObject: j, options: [.sortedKeys])
    }

    /// The scene as a binary `.glb`.
    func glb() -> Data {
        var jsonChunk = finalJSON(bufferURI: nil)
        while jsonChunk.count % 4 != 0 { jsonChunk.append(0x20) }
        var binChunk = bin
        while binChunk.count % 4 != 0 { binChunk.append(0) }
        let total = 12 + 8 + jsonChunk.count + (binChunk.isEmpty ? 0 : 8 + binChunk.count)

        var out = Data()
        func u32(_ v: UInt32) { withUnsafeBytes(of: v.littleEndian) { out.append(contentsOf: $0) } }
        out.append(contentsOf: Array("glTF".utf8)); u32(2); u32(UInt32(total))
        u32(UInt32(jsonChunk.count)); u32(0x4E4F_534A); out.append(jsonChunk)
        if !binChunk.isEmpty { u32(UInt32(binChunk.count)); u32(0x004E_4942); out.append(binChunk) }
        return out
    }

    /// The scene as JSON `.gltf` with its buffer inline as a base64 `data:` URI — or,
    /// with `externalBufferName`, referencing a sidecar `.bin` the caller writes.
    func gltf(externalBufferName: String? = nil) -> Data {
        finalJSON(bufferURI: externalBufferName ?? "data:application/octet-stream;base64,\(bin.base64EncodedString())")
    }

    // MARK: Scenes

    /// One triangle in the XY plane, 1 m on a side, no material.
    static func triangle() -> GLTFBuilder {
        var b = GLTFBuilder()
        let pos = b.vec3([SIMD3(0, 0, 0), SIMD3(1, 0, 0), SIMD3(0, 1, 0)], minMax: true)
        b.json["meshes"] = [["primitives": [["attributes": ["POSITION": pos]]]]]
        b.json["nodes"] = [["mesh": 0, "name": "Triangle"]]
        b.json["scenes"] = [["nodes": [0]]]
        b.json["scene"] = 0
        return b
    }

    /// A 2 × 2 m quad with UVs, normals and an embedded 2 × 2 PNG base-colour texture.
    static func texturedQuad() -> GLTFBuilder {
        var b = GLTFBuilder()
        let pos = b.vec3([SIMD3(-1, -1, 0), SIMD3(1, -1, 0), SIMD3(1, 1, 0), SIMD3(-1, 1, 0)], minMax: true)
        let nrm = b.vec3(Array(repeating: SIMD3(0, 0, 1), count: 4))
        let uv = b.floats([0, 1, 1, 1, 1, 0, 0, 0], type: "VEC2")
        let idx = b.indicesU16([0, 1, 2, 0, 2, 3])
        let image = b.view(GLTFBuilder.checkerPNG())
        b.json["images"] = [["bufferView": image, "mimeType": "image/png"]]
        b.json["samplers"] = [["magFilter": 9728, "minFilter": 9728, "wrapS": 33071, "wrapT": 33071]]
        b.json["textures"] = [["source": 0, "sampler": 0]]
        b.json["materials"] = [[
            "name": "Checker",
            "pbrMetallicRoughness": ["baseColorTexture": ["index": 0], "metallicFactor": 0, "roughnessFactor": 0.5],
            "doubleSided": true,
        ]]
        b.json["meshes"] = [["primitives": [[
            "attributes": ["POSITION": pos, "NORMAL": nrm, "TEXCOORD_0": uv], "indices": idx, "material": 0,
        ]]]]
        b.json["nodes"] = [["mesh": 0, "name": "Quad"]]
        b.json["scenes"] = [["nodes": [0]]]
        return b
    }

    /// A parent translated by (0, 1, 0) and scaled 2×, with a triangle child translated
    /// by (1, 0, 0) — so the child's world origin is (2, 1, 0).
    static func twoNodeHierarchy() -> GLTFBuilder {
        var b = triangle()
        b.json["nodes"] = [
            ["name": "Parent", "translation": [0, 1, 0], "scale": [2, 2, 2], "children": [1]],
            ["name": "Child", "mesh": 0, "translation": [1, 0, 0]],
        ]
        b.json["scenes"] = [["nodes": [0]]]
        return b
    }

    /// The triangle, translated from x = 0 to x = 2 over one second.
    static func translationAnimation(interpolation: String = "LINEAR") -> GLTFBuilder {
        var b = triangle()
        let times = b.floats([0, 1], type: "SCALAR", minMax: true)
        let values = b.floats([0, 0, 0, 2, 0, 0], type: "VEC3")
        b.json["animations"] = [[
            "name": "Slide",
            "samplers": [["input": times, "output": values, "interpolation": interpolation]],
            "channels": [["sampler": 0, "target": ["node": 0, "path": "translation"]]],
        ]]
        return b
    }

    /// A two-joint skinned strip: root joint at the origin, a child joint 1 m up that
    /// rotates 90° about Z over one second. Vertices at y = 1 are fully bound to the
    /// child joint, vertices at y = 0 to the root.
    static func skinnedStrip() -> GLTFBuilder {
        var b = GLTFBuilder()
        let pos = b.vec3([SIMD3(-0.1, 0, 0), SIMD3(0.1, 0, 0), SIMD3(0.1, 1, 0), SIMD3(-0.1, 1, 0)], minMax: true)
        // JOINTS_0 as unsigned bytes, WEIGHTS_0 as floats.
        let joints = b.view(Data([1, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0]))
        let jointsAccessor = b.accessor(["bufferView": joints, "componentType": 5121, "count": 4, "type": "VEC4"])
        let weights = b.floats([1, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0], type: "VEC4")
        let idx = b.indicesU16([0, 1, 2, 0, 2, 3])
        // The skin's joint list is [Tip, Root] — child first, on purpose — so the
        // inverse bind matrices are translate(0, -1, 0) for Tip, then identity for Root,
        // and JOINTS_0 slot 1 is Root, slot 0 is Tip.
        var ibm: [Float] = []
        ibm += [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, -1, 0, 1]
        ibm += [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1]
        let ibmAccessor = b.floats(ibm, type: "MAT4")
        let times = b.floats([0, 1], type: "SCALAR", minMax: true)
        let s = Float(0.5).squareRoot()
        let rotations = b.floats([0, 0, 0, 1, 0, 0, s, s], type: "VEC4")

        b.json["meshes"] = [["primitives": [[
            "attributes": ["POSITION": pos, "JOINTS_0": jointsAccessor, "WEIGHTS_0": weights], "indices": idx,
        ]]]]
        // Node order deliberately lists the child joint before its parent.
        b.json["nodes"] = [
            ["name": "Strip", "mesh": 0, "skin": 0],
            ["name": "Tip", "translation": [0, 1, 0]],
            ["name": "Root", "children": [1]],
        ]
        b.json["skins"] = [["joints": [1, 2], "inverseBindMatrices": ibmAccessor, "skeleton": 2]]
        b.json["animations"] = [[
            "name": "Bend",
            "samplers": [["input": times, "output": rotations]],
            "channels": [["sampler": 0, "target": ["node": 1, "path": "rotation"]]],
        ]]
        b.json["scenes"] = [["nodes": [0, 2]]]
        return b
    }

    // MARK: Images

    /// A 2 × 2 PNG: red, green / blue, white.
    static func checkerPNG() -> Data {
        #if canImport(ImageIO)
        let pixels: [UInt8] = [255, 0, 0, 255, 0, 255, 0, 255, 0, 0, 255, 255, 255, 255, 255, 255]
        let provider = CGDataProvider(data: Data(pixels) as CFData)!
        let image = CGImage(
            width: 2, height: 2, bitsPerComponent: 8, bitsPerPixel: 32, bytesPerRow: 8,
            space: CGColorSpace(name: CGColorSpace.sRGB)!,
            bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.premultipliedLast.rawValue),
            provider: provider, decode: nil, shouldInterpolate: false, intent: .defaultIntent
        )!
        let out = NSMutableData()
        let dest = CGImageDestinationCreateWithData(out, UTType.png.identifier as CFString, 1, nil)!
        CGImageDestinationAddImage(dest, image, nil)
        CGImageDestinationFinalize(dest)
        return out as Data
        #else
        return Data()
        #endif
    }
}

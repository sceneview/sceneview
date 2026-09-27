import CoreGraphics
import Foundation
import ImageIO
import ModelIO
import simd
import UniformTypeIdentifiers

/// Writes a ``RerunExportScene`` as a `.usdz` that AR Quick Look and RealityKit open: the
/// coloured point cloud, the camera's path, the photo-textured planes and the placed models,
/// at real scale (metres, Y up).
///
/// The package is authored by hand, in pure Swift: RealityKit on iOS 18 cannot export.
/// - The root layer is USDA text (`scene.usda`, first in the archive, as the USDZ spec asks).
/// - Neither AR Quick Look nor RealityKit draws `UsdGeomPoints`, so every map point is a tiny
///   tetrahedron, all merged into one mesh, coloured by its own texel of a small PNG atlas —
///   the Android replay's `PointColorAtlas`. Past ``maxPoints`` the cloud is evenly subsampled.
/// - The camera's path is a thin square tube; each plane a fan from its centroid, showing its
///   photo when the capture has one (matte, cut away where the photo is transparent). Points
///   and path are emissive over a black metallic base: unlit, the colours the camera saw.
/// - A placed model's own USDZ is unpacked into `models/<name>/` and referenced from its
///   anchor, scaled so its largest side is ``placedModelSize`` and standing on the anchor.
/// - The archive is a stored (uncompressed) zip whose every file's data starts on a 64-byte
///   boundary, padded through the local header's extra field.
enum RerunUSDZWriter {
    /// Why an export failed.
    enum Failure: Error, Equatable {
        /// A model's bytes are not a zip archive this writer can read.
        case malformedModel(String)
        /// A model's archive compresses a file: a USDZ never does, so it is not one.
        case compressedModelEntry(model: String, path: String)
        /// The point colour atlas could not be encoded as a PNG.
        case imageEncodingFailed
    }

    /// Points kept at most: four triangles each, so 40 000 triangles for the cloud.
    static let maxPoints = 10_000
    /// Camera samples kept at most along the path tube (eight triangles per segment).
    static let maxPathSamples = 1_000
    /// Half-size of a point's tetrahedron, in metres.
    static let pointRadius: Float = 0.012
    /// Half-width of the camera path's tube, in metres.
    static let pathRadius: Float = 0.006
    /// Largest side of a placed model, in metres — Android's `scaleToUnits = 0.3f`.
    static let placedModelSize: Float = 0.3
    /// Texels per row of the point colour atlas.
    static let atlasWidth = 128
    /// Path of the root layer inside the package.
    static let rootLayerPath = "scene.usda"

    /// The `.usdz` bytes for `scene`. `models` maps an anchor's ``RerunExportScene/Anchor/modelName``
    /// to that model's `.usdz` bytes; an anchor whose model is missing exports as a bare `Xform`.
    static func data(for scene: RerunExportScene, models: [String: Data] = [:]) throws -> Data {
        var files: [(path: String, data: Data)] = []
        var usda = USDAText()
        let root = "world"

        usda.line("#usda 1.0")
        usda.line("(")
        usda.line("    customLayerData = {")
        usda.line("        string creator = \"SceneView Rerun export\"")
        usda.line("        string title = \(quoted(scene.title))")
        usda.line("    }")
        usda.line("    defaultPrim = \"\(root)\"")
        usda.line("    metersPerUnit = 1")
        usda.line("    upAxis = \"Y\"")
        usda.line(")")
        usda.line("")
        usda.open("def Xform \"\(root)\" (\n    kind = \"assembly\"\n)")

        var looks: [Look] = []

        // world/points
        let cloud = pointCloud(scene)
        if !cloud.mesh.isEmpty {
            files.append((path: "textures/points.png", data: try pngData(rgba: cloud.atlas, width: atlasWidth, height: cloud.atlasHeight)))
            looks.append(Look(name: "points", texture: "textures/points.png"))
            usda.mesh(named: identifier("points"), cloud.mesh, material: "/\(root)/Looks/points")
        }

        // world/camera
        let path = tube(through: subsample(scene.cameraPath.map(\.position), max: maxPathSamples), radius: pathRadius)
        if !path.isEmpty {
            looks.append(Look(name: "cameraPath", color: SIMD3(0x00, 0x5B, 0xC1) / 255)) // DESIGN.md `primary`
            usda.open("def Xform \"\(identifier("camera"))\" (\n    kind = \"group\"\n)")
            usda.mesh(named: "path", path, material: "/\(root)/Looks/cameraPath")
            usda.close()
        }

        // world/planes/<id>
        if !scene.planes.isEmpty {
            usda.open("def Xform \"\(identifier("planes"))\" (\n    kind = \"group\"\n)")
            var untextured = false
            var names = Set<String>()
            for plane in scene.planes where plane.polygon.count >= 3 {
                let name = unique(identifier(String(plane.id)), in: &names)
                var material = "/\(root)/Looks/plane"
                var textured = false
                if let texture = plane.texture, let image = textureFile(texture.imageData) {
                    let look = "plane\(name)"
                    let path = "textures/\(look).\(image.ext)"
                    files.append((path: path, data: image.data))
                    looks.append(Look(name: look, photo: path, cutout: image.ext == "png"))
                    material = "/\(root)/Looks/\(look)"
                    textured = true
                } else {
                    untextured = true
                }
                usda.mesh(named: name, fan(plane, textured: textured), material: material)
            }
            if untextured {
                looks.append(Look(name: "plane", color: SIMD3(0x00, 0x5B, 0xC1) / 255, opacity: 0.35))
            }
            usda.close()
        }

        // world/anchors/<id>
        if !scene.anchors.isEmpty {
            var packaged: [String: PackagedModel] = [:]
            var names = Set<String>()
            usda.open("def Xform \"\(identifier("anchors"))\" (\n    kind = \"group\"\n)")
            for anchor in scene.anchors {
                usda.open("def Xform \"\(unique(identifier(String(anchor.id)), in: &names))\"")
                usda.line("float3 xformOp:translate = \(vector(anchor.position))")
                usda.line("quatf xformOp:orient = \(quaternion(anchor.orientation))")
                usda.line("uniform token[] xformOpOrder = [\"xformOp:translate\", \"xformOp:orient\"]")
                if let name = anchor.modelName, let bytes = models[name] {
                    let model: PackagedModel
                    if let known = packaged[name] {
                        model = known
                    } else {
                        model = try package(model: name, bytes: bytes)
                        packaged[name] = model
                        files.append(contentsOf: model.files)
                    }
                    usda.line("")
                    usda.open("def Xform \"\(identifier(name))\" (\n    prepend references = @\(model.rootLayer)@\n)")
                    usda.line("float3 xformOp:scale = \(vector(SIMD3(repeating: model.scale)))")
                    usda.line("float3 xformOp:translate:fit = \(vector(model.offset))")
                    usda.line("uniform token[] xformOpOrder = [\"xformOp:scale\", \"xformOp:translate:fit\"]")
                    usda.close()
                }
                usda.close()
            }
            usda.close()
        }

        if !looks.isEmpty {
            usda.open("def Scope \"Looks\"")
            for look in looks { usda.material(look, under: "/\(root)/Looks") }
            usda.close()
        }
        usda.close()

        files.insert((path: rootLayerPath, data: Data(usda.text.utf8)), at: 0)
        return Archive.write(files)
    }

    // MARK: - Geometry

    /// A triangle mesh, world space, with an optional texture coordinate per vertex.
    struct Mesh: Equatable {
        var points: [SIMD3<Float>] = []
        var uvs: [SIMD2<Float>] = []
        var triangles: [Int32] = []
        var isEmpty: Bool { triangles.isEmpty }
    }

    /// The cloud as tetrahedra, and its colour atlas (RGBA, ``atlasWidth`` × `atlasHeight`).
    static func pointCloud(_ scene: RerunExportScene) -> (mesh: Mesh, atlas: [UInt8], atlasHeight: Int) {
        let kept = subsampledIndices(count: scene.points.count, max: maxPoints)
        guard !kept.isEmpty else { return (Mesh(), [], 0) }
        let height = (kept.count + atlasWidth - 1) / atlasWidth
        var atlas = [UInt8](repeating: 0, count: atlasWidth * height * 4)
        var mesh = Mesh()
        mesh.points.reserveCapacity(kept.count * 4)
        mesh.uvs.reserveCapacity(kept.count * 4)
        mesh.triangles.reserveCapacity(kept.count * 12)
        let s = pointRadius * 0.94
        let corners: [SIMD3<Float>] = [SIMD3(s, s, s), SIMD3(s, -s, -s), SIMD3(-s, s, -s), SIMD3(-s, -s, s)]
        for (slot, index) in kept.enumerated() {
            let colour = index < scene.pointColors.count ? scene.pointColors[index] : SIMD3<UInt8>(200, 200, 200)
            atlas[slot * 4] = colour.x
            atlas[slot * 4 + 1] = colour.y
            atlas[slot * 4 + 2] = colour.z
            atlas[slot * 4 + 3] = 255
            // USD's `st` origin is the image's bottom-left corner.
            let uv = SIMD2<Float>((Float(slot % atlasWidth) + 0.5) / Float(atlasWidth),
                                  1 - (Float(slot / atlasWidth) + 0.5) / Float(height))
            let base = Int32(mesh.points.count)
            for corner in corners {
                mesh.points.append(scene.points[index] + corner)
                mesh.uvs.append(uv)
            }
            mesh.triangles += [base, base + 1, base + 2, base, base + 3, base + 1,
                               base, base + 2, base + 3, base + 1, base + 3, base + 2]
        }
        return (mesh, atlas, height)
    }

    /// A square tube of half-width `radius` through `points`, consecutive duplicates dropped.
    static func tube(through points: [SIMD3<Float>], radius: Float) -> Mesh {
        var path: [SIMD3<Float>] = []
        for p in points where p.x.isFinite && p.y.isFinite && p.z.isFinite {
            if let last = path.last, simd_distance(last, p) < 1e-4 { continue }
            path.append(p)
        }
        guard path.count >= 2 else { return Mesh() }
        var mesh = Mesh()
        var previousSide = SIMD3<Float>(1, 0, 0)
        for (i, p) in path.enumerated() {
            let ahead = path[min(i + 1, path.count - 1)] - path[max(i - 1, 0)]
            let tangent = simd_normalize(ahead)
            var side = simd_cross(tangent, SIMD3(0, 1, 0))
            if simd_length(side) < 1e-3 { side = previousSide } // vertical segment: keep the last frame
            side = simd_normalize(side)
            previousSide = side
            let up = simd_normalize(simd_cross(side, tangent))
            for k in 0..<4 {
                let angle = Float(k) * .pi / 2
                mesh.points.append(p + radius * (cos(angle) * side + sin(angle) * up))
            }
        }
        for i in 0..<(path.count - 1) {
            let a = Int32(i * 4)
            let b = a + 4
            for k in Int32(0)..<4 {
                let k1 = (k + 1) % 4
                mesh.triangles += [a + k, b + k, b + k1, a + k, b + k1, a + k1]
            }
        }
        return mesh
    }

    /// A plane as a two-sided fan from its centroid; texture coordinates from its photo's frame.
    static func fan(_ plane: RerunExportScene.Plane, textured: Bool) -> Mesh {
        let polygon = plane.polygon
        let centroid = polygon.reduce(SIMD3<Float>.zero, +) / Float(polygon.count)
        var mesh = Mesh()
        mesh.points = [centroid] + polygon
        if textured, let texture = plane.texture {
            let uu = max(simd_dot(texture.u, texture.u), 1e-12)
            let vv = max(simd_dot(texture.v, texture.v), 1e-12)
            mesh.uvs = mesh.points.map { p in
                let d = p - texture.origin
                // The frame's v = 0 is the photo's top row; USD's t = 0 is its bottom row.
                return SIMD2(simd_dot(d, texture.u) / uu, 1 - simd_dot(d, texture.v) / vv)
            }
        }
        // Both windings: RealityKit culls back faces whatever `doubleSided` says, and a
        // plane's boundary comes in either order.
        let n = Int32(polygon.count)
        for i in Int32(0)..<n {
            mesh.triangles += [0, 1 + i, 1 + (i + 1) % n]
        }
        for i in Int32(0)..<n {
            mesh.triangles += [0, 1 + (i + 1) % n, 1 + i]
        }
        return mesh
    }

    /// Every index when `count <= max`, else `max` indices evenly spread over `0..<count`.
    static func subsampledIndices(count: Int, max: Int) -> [Int] {
        guard count > max else { return Array(0..<count) }
        return (0..<max).map { Int(Int64($0) * Int64(count) / Int64(max)) }
    }

    private static func subsample<T>(_ values: [T], max: Int) -> [T] {
        subsampledIndices(count: values.count, max: max).map { values[$0] }
    }

    // MARK: - Images

    /// PNG bytes of an sRGB RGBA8 image.
    static func pngData(rgba: [UInt8], width: Int, height: Int) throws -> Data {
        guard let provider = CGDataProvider(data: Data(rgba) as CFData),
              let space = CGColorSpace(name: CGColorSpace.sRGB),
              let image = CGImage(width: width, height: height, bitsPerComponent: 8, bitsPerPixel: 32,
                                  bytesPerRow: width * 4, space: space,
                                  bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.noneSkipLast.rawValue),
                                  provider: provider, decode: nil, shouldInterpolate: false, intent: .defaultIntent),
              let png = encode(image, as: .png, quality: nil)
        else { throw Failure.imageEncodingFailed }
        return png
    }

    /// A texture USDZ accepts: JPEG and PNG pass through, anything else ImageIO decodes
    /// (WebP) is re-encoded — as PNG when it has alpha (a plane photo is transparent where
    /// the camera never saw the plane), else as JPEG. `nil` when the bytes are not an image.
    static func textureFile(_ data: Data) -> (data: Data, ext: String)? {
        if data.starts(with: [0xFF, 0xD8, 0xFF]) { return (data, "jpg") }
        if data.starts(with: [0x89, 0x50, 0x4E, 0x47]) { return (data, "png") }
        guard let source = CGImageSourceCreateWithData(data as CFData, nil),
              let image = CGImageSourceCreateImageAtIndex(source, 0, nil)
        else { return nil }
        let opaque: [CGImageAlphaInfo] = [.none, .noneSkipFirst, .noneSkipLast]
        if opaque.contains(image.alphaInfo) {
            return encode(image, as: .jpeg, quality: 0.85).map { ($0, "jpg") }
        }
        return encode(image, as: .png, quality: nil).map { ($0, "png") }
    }

    private static func encode(_ image: CGImage, as type: UTType, quality: Double?) -> Data? {
        let out = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(out as CFMutableData, type.identifier as CFString, 1, nil)
        else { return nil }
        let options = quality.map { [kCGImageDestinationLossyCompressionQuality: $0] as CFDictionary }
        CGImageDestinationAddImage(destination, image, options)
        guard CGImageDestinationFinalize(destination) else { return nil }
        return out as Data
    }

    // MARK: - Models

    /// A model re-homed into the package: its files, its root layer's path, and the transform
    /// that stands it on its anchor at ``placedModelSize``.
    struct PackagedModel {
        var files: [(path: String, data: Data)]
        var rootLayer: String
        var scale: Float
        var offset: SIMD3<Float>
    }

    /// Unpacks a model's USDZ under `models/<name>/`. Asset paths inside its layers are
    /// relative to the layer, so they keep resolving; the first file is its root layer.
    static func package(model name: String, bytes: Data) throws -> PackagedModel {
        let entries = try Archive.read(bytes, model: name)
        guard let first = entries.first else { throw Failure.malformedModel(name) }
        let folder = "models/\(identifier(name))/"
        let fit = fitting(modelBytes: bytes)
        return PackagedModel(files: entries.map { (path: folder + $0.path, data: $0.data) },
                             rootLayer: folder + first.path, scale: fit.scale, offset: fit.offset)
    }

    /// Scale and offset (in the model's own units) that make the model's largest side
    /// ``placedModelSize`` and put the middle of its base on the origin. Model I/O measures it;
    /// when it cannot, the model keeps its own size and origin.
    static func fitting(modelBytes: Data) -> (scale: Float, offset: SIMD3<Float>) {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("rerun-export-\(UUID().uuidString).usdz")
        defer { try? FileManager.default.removeItem(at: url) }
        guard (try? modelBytes.write(to: url)) != nil else { return (1, .zero) }
        let bounds = MDLAsset(url: url).boundingBox
        let size = bounds.maxBounds - bounds.minBounds
        let largest = max(size.x, size.y, size.z)
        guard largest.isFinite, largest > 0 else { return (1, .zero) }
        let base = SIMD3((bounds.minBounds.x + bounds.maxBounds.x) / 2, bounds.minBounds.y,
                         (bounds.minBounds.z + bounds.maxBounds.z) / 2)
        return (placedModelSize / largest, -base)
    }

    // MARK: - USDA text

    /// A USD prim name: letters, digits and `_`, never starting with a digit
    /// (`world/planes/3` → `_3`).
    static func identifier(_ raw: String) -> String {
        var out = String(raw.unicodeScalars.map { scalar -> Character in
            let ok = scalar.isASCII && (CharacterSet.alphanumerics.contains(scalar) || scalar == "_")
            return ok ? Character(scalar) : "_"
        })
        if out.isEmpty || out.first!.isNumber { out = "_" + out }
        return out
    }

    /// `name`, or `name_2`, `name_3`… when a sibling already took it.
    static func unique(_ name: String, in taken: inout Set<String>) -> String {
        var candidate = name
        var n = 2
        while taken.contains(candidate) { candidate = "\(name)_\(n)"; n += 1 }
        taken.insert(candidate)
        return candidate
    }

    fileprivate static func quoted(_ text: String) -> String {
        var out = "\""
        for c in text {
            switch c {
            case "\\": out += "\\\\"
            case "\"": out += "\\\""
            case "\n", "\r": out += " "
            default: out.append(c)
            }
        }
        return out + "\""
    }

    /// A float with at most five decimals, `.` separated whatever the locale.
    fileprivate static func number(_ value: Float) -> String {
        let scaled = (Double(value.isFinite ? value : 0) * 100_000).rounded()
        if scaled == 0 { return "0" }
        var magnitude = Int64(abs(scaled))
        let whole = magnitude / 100_000
        magnitude %= 100_000
        var text = scaled < 0 ? "-\(whole)" : "\(whole)"
        if magnitude != 0 {
            var digits = 5
            while magnitude % 10 == 0 { magnitude /= 10; digits -= 1 }
            let fraction = String(magnitude)
            text += "." + String(repeating: "0", count: digits - fraction.count) + fraction
        }
        return text
    }

    fileprivate static func vector(_ v: SIMD3<Float>) -> String {
        "(\(number(v.x)), \(number(v.y)), \(number(v.z)))"
    }

    fileprivate static func quaternion(_ q: simd_quatf) -> String {
        let n = simd_length(q.vector) > 0 ? q.normalized : simd_quatf(ix: 0, iy: 0, iz: 0, r: 1)
        return "(\(number(n.real)), \(number(n.imag.x)), \(number(n.imag.y)), \(number(n.imag.z)))"
    }

    /// A material: emissive over a black metallic base (from a texture or a flat colour), or,
    /// for a plane's photo, a matte diffuse texture.
    fileprivate struct Look {
        var name: String
        var texture: String?
        var color: SIMD3<Float>?
        var opacity: Float?
        /// A plane's photo: diffuse, matte.
        var photo = false
        /// Cut the photo away where its alpha is under one half.
        var cutout = false

        init(name: String, texture: String) { self.name = name; self.texture = texture }
        init(name: String, photo: String, cutout: Bool) {
            self.name = name; self.texture = photo; self.photo = true; self.cutout = cutout
        }
        init(name: String, color: SIMD3<Float>, opacity: Float? = nil) {
            self.name = name; self.color = color; self.opacity = opacity
        }
    }

    fileprivate struct USDAText {
        var text = ""
        var depth = 0

        mutating func line(_ s: String) {
            if s.isEmpty { text += "\n"; return }
            let pad = String(repeating: "    ", count: depth)
            for part in s.split(separator: "\n", omittingEmptySubsequences: false) {
                text += pad + part + "\n"
            }
        }

        mutating func open(_ header: String) {
            line(header)
            line("{")
            depth += 1
        }

        mutating func close() {
            depth -= 1
            line("}")
            line("")
        }

        mutating func array(_ declaration: String, _ values: [String], interpolation: String? = nil) {
            let pad = String(repeating: "    ", count: depth)
            text += pad + declaration + " = ["
            text += values.joined(separator: ", ")
            if let interpolation {
                text += "] (\n\(pad)    interpolation = \"\(interpolation)\"\n\(pad))\n"
            } else {
                text += "]\n"
            }
        }

        mutating func mesh(named name: String, _ mesh: Mesh, material: String) {
            open("def Mesh \"\(name)\" (\n    prepend apiSchemas = [\"MaterialBindingAPI\"]\n)")
            var lo = SIMD3<Float>(repeating: .greatestFiniteMagnitude)
            var hi = -lo
            for p in mesh.points { lo = simd_min(lo, p); hi = simd_max(hi, p) }
            line("uniform bool doubleSided = 1")
            line("float3[] extent = [\(vector(lo)), \(vector(hi))]")
            array("int[] faceVertexCounts", Array(repeating: "3", count: mesh.triangles.count / 3))
            array("int[] faceVertexIndices", mesh.triangles.map { String($0) })
            array("point3f[] points", mesh.points.map(vector))
            if !mesh.uvs.isEmpty {
                array("texCoord2f[] primvars:st", mesh.uvs.map { "(\(number($0.x)), \(number($0.y)))" },
                      interpolation: "vertex")
            }
            line("uniform token subdivisionScheme = \"none\"")
            line("rel material:binding = <\(material)>")
            close()
        }

        mutating func material(_ look: Look, under scope: String) {
            let path = "\(scope)/\(look.name)"
            open("def Material \"\(look.name)\"")
            line("token outputs:surface.connect = <\(path)/Surface.outputs:surface>")
            line("")
            open("def Shader \"Surface\"")
            line("uniform token info:id = \"UsdPreviewSurface\"")
            if look.photo {
                // A photo is a diffuse texture: Quick Look fades an emissive one under opacity,
                // and drops it entirely under an opacity threshold.
                line("color3f inputs:diffuseColor.connect = <\(path)/Texture.outputs:rgb>")
                line("float inputs:metallic = 0")
                if look.cutout {
                    // Where the photo is transparent the plane is cut away (masked, no blending).
                    line("float inputs:opacity.connect = <\(path)/Texture.outputs:a>")
                    line("float inputs:opacityThreshold = 0.5")
                }
            } else {
                line("color3f inputs:diffuseColor = (0, 0, 0)")
                if look.texture != nil {
                    line("color3f inputs:emissiveColor.connect = <\(path)/Texture.outputs:rgb>")
                } else if let color = look.color {
                    line("color3f inputs:emissiveColor = \(vector(color))")
                }
                line("float inputs:metallic = 1")
            }
            if let opacity = look.opacity { line("float inputs:opacity = \(number(opacity))") }
            line("float inputs:roughness = 1")
            line("token outputs:surface")
            close()
            if let texture = look.texture {
                open("def Shader \"UV\"")
                line("uniform token info:id = \"UsdPrimvarReader_float2\"")
                line("string inputs:varname = \"st\"")
                line("float2 outputs:result")
                close()
                open("def Shader \"Texture\"")
                line("uniform token info:id = \"UsdUVTexture\"")
                line("asset inputs:file = @\(texture)@")
                line("float2 inputs:st.connect = <\(path)/UV.outputs:result>")
                line("token inputs:sourceColorSpace = \"sRGB\"")
                line("token inputs:wrapS = \"clamp\"")
                line("token inputs:wrapT = \"clamp\"")
                line("float outputs:a")
                line("float3 outputs:rgb")
                close()
            }
            close()
        }
    }
}

extension RerunUSDZWriter {
/// The USDZ container: a zip whose files are stored uncompressed, each file's data starting
/// on a 64-byte boundary, the root layer first.
enum Archive {
    /// Zip bytes for `files`, in order.
    static func write(_ files: [(path: String, data: Data)]) -> Data {
        var out = Data()
        var central = Data()
        for file in files {
            let name = Data(file.path.utf8)
            let crc = CRC32.checksum(file.data)
            let size = UInt32(file.data.count)
            let offset = UInt32(out.count)
            // Pad with one extra-field record (Pixar's usdzip id 0x1986) up to the boundary.
            var pad = (64 - (out.count + 30 + name.count) % 64) % 64
            if pad > 0 && pad < 4 { pad += 64 }
            var extra = Data()
            if pad > 0 {
                extra.appendLittleEndian(UInt16(0x1986))
                extra.appendLittleEndian(UInt16(pad - 4))
                extra.append(Data(count: pad - 4))
            }
            out.appendLittleEndian(UInt32(0x0403_4B50))
            out.appendLittleEndian(UInt16(20)) // version needed
            out.appendLittleEndian(UInt16(0)) // flags
            out.appendLittleEndian(UInt16(0)) // method: stored
            out.appendLittleEndian(UInt16(0)) // time
            out.appendLittleEndian(UInt16(0x21)) // date: 1980-01-01
            out.appendLittleEndian(crc)
            out.appendLittleEndian(size)
            out.appendLittleEndian(size)
            out.appendLittleEndian(UInt16(name.count))
            out.appendLittleEndian(UInt16(extra.count))
            out.append(name)
            out.append(extra)
            out.append(file.data)

            central.appendLittleEndian(UInt32(0x0201_4B50))
            central.appendLittleEndian(UInt16(20)) // version made by
            central.appendLittleEndian(UInt16(20))
            central.appendLittleEndian(UInt16(0))
            central.appendLittleEndian(UInt16(0))
            central.appendLittleEndian(UInt16(0))
            central.appendLittleEndian(UInt16(0x21))
            central.appendLittleEndian(crc)
            central.appendLittleEndian(size)
            central.appendLittleEndian(size)
            central.appendLittleEndian(UInt16(name.count))
            central.appendLittleEndian(UInt16(0)) // extra
            central.appendLittleEndian(UInt16(0)) // comment
            central.appendLittleEndian(UInt16(0)) // disk
            central.appendLittleEndian(UInt16(0)) // internal attributes
            central.appendLittleEndian(UInt32(0)) // external attributes
            central.appendLittleEndian(offset)
            central.append(name)
        }
        let centralOffset = UInt32(out.count)
        out.append(central)
        out.appendLittleEndian(UInt32(0x0605_4B50))
        out.appendLittleEndian(UInt16(0))
        out.appendLittleEndian(UInt16(0))
        out.appendLittleEndian(UInt16(files.count))
        out.appendLittleEndian(UInt16(files.count))
        out.appendLittleEndian(UInt32(central.count))
        out.appendLittleEndian(centralOffset)
        out.appendLittleEndian(UInt16(0))
        return out
    }

    /// The files of a stored zip (a USDZ), in archive order. `model` names it in errors.
    static func read(_ zip: Data, model: String) throws -> [(path: String, data: Data)] {
        let bytes = [UInt8](zip)
        func u16(_ at: Int) throws -> Int {
            guard at >= 0, at + 2 <= bytes.count else { throw Failure.malformedModel(model) }
            return Int(bytes[at]) | Int(bytes[at + 1]) << 8
        }
        func u32(_ at: Int) throws -> Int {
            let low = try u16(at)
            let high = try u16(at + 2)
            return low | high << 16
        }

        // End of central directory: the last 0x06054b50 within the trailing 64 KiB.
        var end = -1
        var i = bytes.count - 22
        while i >= max(0, bytes.count - 22 - 65_535) {
            if bytes[i] == 0x50, bytes[i + 1] == 0x4B, bytes[i + 2] == 0x05, bytes[i + 3] == 0x06 { end = i; break }
            i -= 1
        }
        guard end >= 0 else { throw Failure.malformedModel(model) }
        let count = try u16(end + 10)
        var cursor = try u32(end + 16)
        var found: [(offset: Int, path: String, data: Data)] = []
        for _ in 0..<count {
            guard try u32(cursor) == 0x0201_4B50 else { throw Failure.malformedModel(model) }
            let method = try u16(cursor + 10)
            let size = try u32(cursor + 20)
            let nameLength = try u16(cursor + 28)
            let extraLength = try u16(cursor + 30)
            let commentLength = try u16(cursor + 32)
            let local = try u32(cursor + 42)
            guard cursor + 46 + nameLength <= bytes.count else { throw Failure.malformedModel(model) }
            let path = String(decoding: bytes[(cursor + 46)..<(cursor + 46 + nameLength)], as: UTF8.self)
            cursor += 46 + nameLength + extraLength + commentLength
            if path.hasSuffix("/") { continue }
            guard method == 0 else { throw Failure.compressedModelEntry(model: model, path: path) }
            guard try u32(local) == 0x0403_4B50 else { throw Failure.malformedModel(model) }
            let localName = try u16(local + 26)
            let localExtra = try u16(local + 28)
            let start = local + 30 + localName + localExtra
            guard start + size <= bytes.count else { throw Failure.malformedModel(model) }
            found.append((offset: local, path: path, data: Data(bytes[start..<(start + size)])))
        }
        return found.sorted { $0.offset < $1.offset }.map { (path: $0.path, data: $0.data) }
    }
}

/// CRC-32 (IEEE 802.3), the zip checksum.
enum CRC32 {
    private static let table: [UInt32] = (0..<256).map { n in
        var c = UInt32(n)
        for _ in 0..<8 { c = c & 1 != 0 ? 0xEDB8_8320 ^ (c >> 1) : c >> 1 }
        return c
    }

    static func checksum(_ data: Data) -> UInt32 {
        var crc: UInt32 = 0xFFFF_FFFF
        data.withUnsafeBytes { raw in
            for byte in raw { crc = table[Int((crc ^ UInt32(byte)) & 0xFF)] ^ (crc >> 8) }
        }
        return crc ^ 0xFFFF_FFFF
    }
}
}

private extension Data {
    mutating func appendLittleEndian<T: FixedWidthInteger>(_ value: T) {
        Swift.withUnsafeBytes(of: value.littleEndian) { append(contentsOf: $0) }
    }
}

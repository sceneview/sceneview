import Foundation
import ModelIO
import simd

/// Turns the mesh formats Apple's ModelIO reads — STL, OBJ (+ `.mtl`), PLY — into
/// format-neutral ``MeshGeometry`` parts.
///
/// ModelIO gives back an `MDLAsset`: a tree of `MDLObject`s, each mesh carrying an
/// interleaved, arbitrarily-laid-out vertex buffer plus one `MDLSubmesh` per material,
/// with indices that may be 8-, 16- or 32-bit and triangles that may arrive as quads or
/// strips. This reader flattens all of that into plain Swift arrays, which is what makes
/// the loading path testable off a device and lets a caller measure a print before any
/// RealityKit object exists.
///
/// Deliberately *not* `MeshResource.generate(from: MDLMesh)`: that convenience exists on
/// macOS only, so the same code would not compile for iOS or visionOS.
enum ModelIOMeshReader {

    /// Reads every mesh in the file, with each node's transform baked into its vertices.
    ///
    /// - Parameter url: A local `.stl`, `.obj` or `.ply` file. An OBJ's `.mtl` sidecar is
    ///   picked up by ModelIO when it sits next to the file and the `mtllib` name
    ///   resolves; a missing one is not an error, the parts just come back untextured.
    /// - Returns: One ``MeshGeometry`` per submesh, in file order. Empty when the file
    ///   holds no drawable mesh.
    /// - Throws: ``ModelLoadingError``.
    static func read(contentsOf url: URL) throws -> [MeshGeometry] {
        guard FileManager.default.isReadableFile(atPath: url.path) else {
            throw ModelLoadingError.unreadableFile(url)
        }
        let asset = MDLAsset(url: url)
        var parts: [MeshGeometry] = []
        for index in 0..<asset.count {
            try collect(
                object: asset.object(at: index),
                parentTransform: matrix_identity_float4x4,
                into: &parts
            )
        }
        return parts
    }

    // MARK: - Object tree

    /// Walks one `MDLObject` and its children, composing transforms on the way down.
    private static func collect(
        object: MDLObject,
        parentTransform: simd_float4x4,
        into parts: inout [MeshGeometry]
    ) throws {
        let local = object.transform?.matrix ?? matrix_identity_float4x4
        let world = parentTransform * local

        if let mesh = object as? MDLMesh {
            parts.append(contentsOf: try geometries(from: mesh, transform: world))
        }
        for child in object.children.objects {
            try collect(object: child, parentTransform: world, into: &parts)
        }
    }

    // MARK: - Mesh

    /// Converts one `MDLMesh` into one ``MeshGeometry`` per submesh.
    private static func geometries(
        from mesh: MDLMesh,
        transform: simd_float4x4
    ) throws -> [MeshGeometry] {
        let vertexCount = mesh.vertexCount
        guard vertexCount > 0 else { return [] }

        // No `MDLMesh.addNormals` here, deliberately. It *unwelds* the mesh — measured on
        // a 4-vertex PLY quad, it returns 6 vertices and rewrites the index buffer — so a
        // file's own vertex count stops matching what the loader reports, and any index
        // captured before the call points at the wrong vertex afterwards. A file with no
        // normals gets them from ``MeshGeometry/generatingNormals()`` instead, which
        // keeps shared vertices shared.
        guard var positions = readVector3(mesh, named: MDLVertexAttributePosition, count: vertexCount)
        else {
            throw ModelLoadingError.unreadableGeometry(reason: "mesh has no position attribute")
        }
        var normals = readVector3(mesh, named: MDLVertexAttributeNormal, count: vertexCount)
        let uvs = readVector2(mesh, named: MDLVertexAttributeTextureCoordinate, count: vertexCount)
        let colors = readVector4(mesh, named: MDLVertexAttributeColor, count: vertexCount)

        // Bake the node transform. Normals take the inverse transpose so a non-uniform
        // scale does not tilt them — the classic bug that makes a squashed model light
        // as though it were not squashed.
        if transform != matrix_identity_float4x4 {
            positions = positions.map { point in
                let transformed = transform * SIMD4<Float>(point, 1)
                return SIMD3<Float>(transformed.x, transformed.y, transformed.z)
            }
            let normalMatrix = simd_transpose(simd_inverse(simd_float3x3(
                SIMD3<Float>(transform.columns.0.x, transform.columns.0.y, transform.columns.0.z),
                SIMD3<Float>(transform.columns.1.x, transform.columns.1.y, transform.columns.1.z),
                SIMD3<Float>(transform.columns.2.x, transform.columns.2.y, transform.columns.2.z)
            )))
            normals = normals?.map { normal in
                let rotated = normalMatrix * normal
                let lengthSquared = simd_length_squared(rotated)
                return lengthSquared > 0 ? rotated / lengthSquared.squareRoot() : normal
            }
        }

        let submeshes = (mesh.submeshes as? [MDLSubmesh]) ?? []
        guard !submeshes.isEmpty else { return [] }

        let baseName = mesh.name.isEmpty ? "mesh" : mesh.name
        // One submesh is the overwhelmingly common case (every STL, every PLY, an OBJ
        // with a single material): its indices reference the whole vertex array, so the
        // compaction pass below would copy every vertex to produce an identical array.
        let skipCompaction = submeshes.count == 1

        var parts: [MeshGeometry] = []
        for (index, submesh) in submeshes.enumerated() {
            let triangleIndices = try triangleIndices(of: submesh, vertexCount: vertexCount)
            guard !triangleIndices.isEmpty else { continue }
            let name = submesh.name.isEmpty
                ? (submeshes.count == 1 ? baseName : "\(baseName)-\(index)")
                : submesh.name
            let material = materialDescription(of: submesh.material)

            if skipCompaction {
                parts.append(MeshGeometry(
                    name: name,
                    positions: positions,
                    normals: normals,
                    textureCoordinates: uvs,
                    colors: colors,
                    indices: triangleIndices,
                    material: material
                ))
            } else {
                parts.append(compacted(
                    name: name,
                    positions: positions,
                    normals: normals,
                    uvs: uvs,
                    colors: colors,
                    indices: triangleIndices,
                    material: material
                ))
            }
        }
        return parts
    }

    // MARK: - Indices

    /// Reads a submesh's index buffer as triangles, converting quads and strips.
    ///
    /// ModelIO hands back whatever topology the file used: OBJ quad faces stay quads,
    /// and some exporters emit strips. RealityKit only draws triangles, so the
    /// conversion has to happen somewhere, and doing it here means every downstream
    /// consumer — bounds, volume, RealityKit — sees the same triangle list.
    private static func triangleIndices(
        of submesh: MDLSubmesh,
        vertexCount: Int
    ) throws -> [UInt32] {
        let count = submesh.indexCount
        guard count > 0 else { return [] }

        let map = submesh.indexBuffer.map()
        let raw = map.bytes
        var source = [UInt32]()
        source.reserveCapacity(count)

        switch submesh.indexType {
        case .uInt8:
            guard submesh.indexBuffer.length >= count else {
                throw ModelLoadingError.unreadableGeometry(reason: "index buffer shorter than indexCount")
            }
            for index in 0..<count {
                source.append(UInt32(raw.loadUnaligned(fromByteOffset: index, as: UInt8.self)))
            }
        case .uInt16:
            guard submesh.indexBuffer.length >= count * 2 else {
                throw ModelLoadingError.unreadableGeometry(reason: "index buffer shorter than indexCount")
            }
            for index in 0..<count {
                source.append(UInt32(raw.loadUnaligned(fromByteOffset: index * 2, as: UInt16.self)))
            }
        case .uInt32:
            guard submesh.indexBuffer.length >= count * 4 else {
                throw ModelLoadingError.unreadableGeometry(reason: "index buffer shorter than indexCount")
            }
            for index in 0..<count {
                source.append(raw.loadUnaligned(fromByteOffset: index * 4, as: UInt32.self))
            }
        case .invalid:
            throw ModelLoadingError.unreadableGeometry(reason: "submesh has no index type")
        @unknown default:
            throw ModelLoadingError.unreadableGeometry(reason: "unknown index type")
        }

        var triangles: [UInt32]
        switch submesh.geometryType {
        case .triangles:
            triangles = source
            triangles.removeLast(triangles.count % 3)
        case .quads:
            triangles = []
            triangles.reserveCapacity(source.count / 4 * 6)
            var index = 0
            while index + 3 < source.count {
                let (a, b, c, d) = (source[index], source[index + 1], source[index + 2], source[index + 3])
                triangles.append(contentsOf: [a, b, c, a, c, d])
                index += 4
            }
        case .triangleStrips:
            triangles = []
            guard source.count >= 3 else { return [] }
            triangles.reserveCapacity((source.count - 2) * 3)
            for index in 0..<(source.count - 2) {
                // Alternate winding so every triangle in the strip faces the same way.
                let triangle = index % 2 == 0
                    ? [source[index], source[index + 1], source[index + 2]]
                    : [source[index + 1], source[index], source[index + 2]]
                triangles.append(contentsOf: triangle)
            }
        case .points, .lines, .variableTopology:
            // Nothing to draw as a surface. Not an error: a PLY point cloud is a valid
            // file, it simply has no triangles, and `MeshAsset` reports that as
            // `.emptyMesh` once every submesh has been skipped.
            return []
        @unknown default:
            return []
        }

        // A malformed file can index past the vertex array; RealityKit would trap on it.
        let limit = UInt32(vertexCount)
        if triangles.contains(where: { $0 >= limit }) {
            throw ModelLoadingError.malformed(reason: "triangle index out of range")
        }
        return triangles
    }

    // MARK: - Vertex compaction

    /// Rebuilds a submesh's vertex arrays so they contain only the vertices it uses.
    ///
    /// Without this, an OBJ with ten materials would carry ten copies of the file's
    /// entire vertex array — the mesh's buffer is shared, but each ``MeshGeometry`` is
    /// self-contained by design.
    private static func compacted(
        name: String,
        positions: [SIMD3<Float>],
        normals: [SIMD3<Float>]?,
        uvs: [SIMD2<Float>]?,
        colors: [SIMD4<Float>]?,
        indices: [UInt32],
        material: MeshMaterialDescription?
    ) -> MeshGeometry {
        var remap: [UInt32: UInt32] = [:]
        remap.reserveCapacity(indices.count)
        var newPositions: [SIMD3<Float>] = []
        var newNormals: [SIMD3<Float>]? = normals == nil ? nil : []
        var newUVs: [SIMD2<Float>]? = uvs == nil ? nil : []
        var newColors: [SIMD4<Float>]? = colors == nil ? nil : []
        var newIndices: [UInt32] = []
        newIndices.reserveCapacity(indices.count)

        for original in indices {
            if let existing = remap[original] {
                newIndices.append(existing)
                continue
            }
            let next = UInt32(newPositions.count)
            remap[original] = next
            let source = Int(original)
            newPositions.append(positions[source])
            if let normals { newNormals?.append(normals[source]) }
            if let uvs { newUVs?.append(uvs[source]) }
            if let colors { newColors?.append(colors[source]) }
            newIndices.append(next)
        }

        return MeshGeometry(
            name: name,
            positions: newPositions,
            normals: newNormals,
            textureCoordinates: newUVs,
            colors: newColors,
            indices: newIndices,
            material: material
        )
    }

    // MARK: - Materials

    /// Maps the handful of `MDLMaterial` semantics that OBJ's `.mtl` actually carries.
    static func materialDescription(of material: MDLMaterial?) -> MeshMaterialDescription? {
        guard let material else { return nil }
        var description = MeshMaterialDescription(name: material.name)

        if let baseColor = material.property(with: .baseColor) {
            switch baseColor.type {
            case .float3:
                let value = baseColor.float3Value
                description.baseColor = SIMD4<Float>(value.x, value.y, value.z, 1)
            case .float4:
                description.baseColor = baseColor.float4Value
            case .color:
                if let components = baseColor.color?.components, components.count >= 3 {
                    description.baseColor = SIMD4<Float>(
                        Float(components[0]),
                        Float(components[1]),
                        Float(components[2]),
                        components.count >= 4 ? Float(components[3]) : 1
                    )
                }
            case .texture, .URL, .string:
                description.baseColorTextureURL = textureURL(of: baseColor)
            default:
                break
            }
        }
        if let metallic = material.property(with: .metallic), metallic.type == .float {
            description.metallic = metallic.floatValue
        }
        if let roughness = material.property(with: .roughness), roughness.type == .float {
            description.roughness = roughness.floatValue
        }
        // Nothing at all was recognised — an empty description would only cost the
        // caller a pointless grey material override.
        let isEmpty = description.baseColor == nil
            && description.metallic == nil
            && description.roughness == nil
            && description.baseColorTextureURL == nil
        return isEmpty ? nil : description
    }

    /// The on-disk URL behind a texture property, whichever way ModelIO stored it.
    private static func textureURL(of property: MDLMaterialProperty) -> URL? {
        if let urlTexture = property.textureSamplerValue?.texture as? MDLURLTexture {
            return urlTexture.url
        }
        if let url = property.urlValue { return url }
        if let string = property.stringValue, !string.isEmpty {
            return URL(fileURLWithPath: string)
        }
        return nil
    }

    // MARK: - Buffer reads

    /// How many components an `MDLVertexFormat` holds.
    ///
    /// `MDLVertexFormat` is a bit field — a type in the high bits, the component count
    /// (1…4) in the low nibble — so `float3` and `uchar3` both answer 3. Reading it is
    /// what keeps the conversion below honest, see ``readVector4(_:named:count:)``.
    private static func componentCount(of format: MDLVertexFormat) -> Int {
        let count = Int(format.rawValue & 0xF)
        return (1...4).contains(count) ? count : 0
    }

    /// The native format of a named attribute, or `nil` when the mesh has no such
    /// attribute. `attributeNamed` returns `nil` for an absent name; a present-but-unset
    /// attribute reports `.invalid`, which is equally "not there".
    private static func nativeFormat(_ mesh: MDLMesh, named name: String) -> MDLVertexFormat? {
        guard let attribute = mesh.vertexDescriptor.attributeNamed(name),
              attribute.format != .invalid else { return nil }
        return attribute.format
    }

    /// Reads a 2-component attribute (UVs).
    private static func readVector2(
        _ mesh: MDLMesh,
        named name: String,
        count: Int
    ) -> [SIMD2<Float>]? {
        guard let format = nativeFormat(mesh, named: name) else { return nil }
        let components = componentCount(of: format)
        guard components >= 2 else { return nil }
        // A 3-component UV set (OBJ's `vt u v w`) is read as float3 and truncated —
        // asking ModelIO for float2 would hit the widening bug documented below.
        if components == 2,
           let data = mesh.vertexAttributeData(forAttributeNamed: name, as: .float2) {
            return read(data, count: count, componentCount: 2) { SIMD2<Float>($0[0], $0[1]) }
        }
        if let data = mesh.vertexAttributeData(forAttributeNamed: name, as: .float3) {
            return read(data, count: count, componentCount: 3) { SIMD2<Float>($0[0], $0[1]) }
        }
        return nil
    }

    /// Reads a 3-component attribute (positions, normals).
    private static func readVector3(
        _ mesh: MDLMesh,
        named name: String,
        count: Int
    ) -> [SIMD3<Float>]? {
        guard let format = nativeFormat(mesh, named: name) else { return nil }
        let components = componentCount(of: format)
        guard components >= 3 else { return nil }
        if components == 3,
           let data = mesh.vertexAttributeData(forAttributeNamed: name, as: .float3) {
            return read(data, count: count, componentCount: 3) { SIMD3<Float>($0[0], $0[1], $0[2]) }
        }
        if let data = mesh.vertexAttributeData(forAttributeNamed: name, as: .float4) {
            return read(data, count: count, componentCount: 4) { SIMD3<Float>($0[0], $0[1], $0[2]) }
        }
        return nil
    }

    /// Reads a colour attribute as RGBA, filling in an opaque alpha for an RGB source.
    ///
    /// The component-count dance is not defensive style, it is a measured ModelIO
    /// behaviour: `vertexAttributeData(forAttributeNamed:as:)` converts the *type* but
    /// not the *component count*. Asking a float3 colour attribute for `.float4` returns
    /// a buffer sized and strided for float4 that still holds tightly packed float3 data
    /// — every vertex after the first reads a third of a vertex out of step, so a
    /// red/green/blue/white PLY comes back red/red/white/black. Asking for the attribute
    /// in its own component count is correct; the widening happens here instead.
    private static func readVector4(
        _ mesh: MDLMesh,
        named name: String,
        count: Int
    ) -> [SIMD4<Float>]? {
        guard let format = nativeFormat(mesh, named: name) else { return nil }
        switch componentCount(of: format) {
        case 4:
            guard let data = mesh.vertexAttributeData(forAttributeNamed: name, as: .float4)
            else { return nil }
            return read(data, count: count, componentCount: 4) {
                SIMD4<Float>($0[0], $0[1], $0[2], $0[3])
            }
        case 3:
            guard let data = mesh.vertexAttributeData(forAttributeNamed: name, as: .float3)
            else { return nil }
            return read(data, count: count, componentCount: 3) {
                SIMD4<Float>($0[0], $0[1], $0[2], 1)
            }
        default:
            return nil
        }
    }

    // `loadUnaligned` below: ModelIO lays attributes out at whatever offset the file's
    // layout implies, so a `float3` can legitimately start at byte 12 of a 24-byte
    // stride — fine for 4-byte alignment, not for the 16-byte alignment
    // `SIMD3<Float>` would demand if loaded as a whole value.

    private static func read<Element>(
        _ data: MDLVertexAttributeData,
        count: Int,
        componentCount: Int,
        build: ([Float]) -> Element
    ) -> [Element] {
        var result: [Element] = []
        result.reserveCapacity(count)
        let stride = data.stride
        let elementBytes = componentCount * MemoryLayout<Float>.size
        var components = [Float](repeating: 0, count: componentCount)
        for index in 0..<count {
            let offset = index * stride
            // `bufferSize` is what ModelIO says it mapped; trusting `count` alone reads
            // past the end for a mesh whose attribute buffer is short.
            guard offset + elementBytes <= data.bufferSize else { break }
            for component in 0..<componentCount {
                components[component] = data.dataStart.loadUnaligned(
                    fromByteOffset: offset + component * MemoryLayout<Float>.size,
                    as: Float.self
                )
            }
            result.append(build(components))
        }
        return result
    }
}

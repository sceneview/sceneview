import Foundation
import simd

/// A 3MF file, read into geometry.
///
/// 3MF is the format the 3D-printing world moved to and the one Apple does not read:
/// `MDLAsset` handles OBJ, STL, PLY and USD, Quick Look handles USDZ and Reality, and
/// nothing on the platform opens a `.3mf`. So this is a parser, not a wrapper — a
/// minimal ZIP reader (``ZipArchiveReader``) plus an `XMLParser` over the package's
/// `.model` parts.
///
/// What it covers, which is what slicers and marketplaces actually write:
///
/// - `<model unit>` — every unit the core spec defines, defaulting to millimetres.
/// - `<mesh>` — `<vertices>` and `<triangles>`.
/// - `<components>` — objects placed inside objects, with row-vector transforms.
/// - `<build><item>` — the placements that decide what is shown, with their transforms.
/// - `<basematerials>` and the materials extension's `<colorgroup>`, resolved per
///   triangle so a multi-colour print comes back multi-colour.
/// - Production-extension `p:path` references into other `.model` parts of the package —
///   how Bambu Studio and Orca write project files.
///
/// ```swift
/// let document = try ThreeMFDocument.read(contentsOf: url)
/// print(document.unit, document.parts.count)
///
/// // Or, more usually, just open it like anything else:
/// let node = try await ModelNode.load(contentsOf: url)
/// ```
public struct ThreeMFDocument: Sendable {

    /// The unit the file declares — ``ModelUnit/millimeters`` when it declares none.
    public let unit: ModelUnit

    /// One part per object-and-material combination, in build order, with every
    /// transform along the component chain already baked into the positions.
    public let parts: [MeshGeometry]

    /// Total triangles across every part.
    public var triangleCount: Int { parts.reduce(0) { $0 + $1.triangleCount } }

    /// Reads a `.3mf` file.
    ///
    /// - Throws: ``ModelLoadingError``.
    public static func read(contentsOf url: URL) throws -> ThreeMFDocument {
        guard let data = try? Data(contentsOf: url, options: .mappedIfSafe) else {
            throw ModelLoadingError.unreadableFile(url)
        }
        return try read(data: data)
    }

    /// Reads a 3MF package already in memory.
    public static func read(data: Data) throws -> ThreeMFDocument {
        let archive = try ZipArchiveReader(data: data)
        var loader = ThreeMFLoader(archive: archive)
        return try loader.load()
    }
}

/// Resolves a 3MF package's build items into flat geometry.
///
/// A separate type because the resolution is stateful: parts are parsed on demand and
/// cached, and the component graph has to be walked with a visited set — a package may
/// legally reference the same object many times, and an illegal one may reference itself.
struct ThreeMFLoader {

    private let archive: ZipArchiveReader
    private var parts: [String: ThreeMFModelPart] = [:]

    /// The default location of the root model part. Used when `_rels/.rels` is missing
    /// or names nothing readable — a fallback the whole ecosystem relies on.
    static let conventionalRootPath = "3D/3dmodel.model"

    /// How deep a component chain may nest before it is called a cycle. Real packages
    /// nest two or three levels; the visited set below catches direct recursion, and
    /// this catches a chain that grows without repeating a pair.
    static let maximumComponentDepth = 32

    init(archive: ZipArchiveReader) {
        self.archive = archive
    }

    mutating func load() throws -> ThreeMFDocument {
        let rootPath = rootModelPath()
        let root = try part(at: rootPath)

        var geometries: [MeshGeometry] = []
        for item in root.buildItems {
            try append(
                objectID: item.objectID,
                partPath: normalize(item.path) ?? rootPath,
                transform: item.transform,
                depth: 0,
                visiting: [],
                into: &geometries
            )
        }

        // A package whose `<build>` is empty still has objects, and a viewer that shows
        // nothing for it is indistinguishable from a broken parser. Falling back to every
        // mesh object in the root part is what slicers do.
        if geometries.isEmpty {
            for id in root.objects.keys.sorted() {
                try append(
                    objectID: id,
                    partPath: rootPath,
                    transform: matrix_identity_float4x4,
                    depth: 0,
                    visiting: [],
                    into: &geometries
                )
            }
        }

        guard !geometries.isEmpty else { throw ModelLoadingError.emptyMesh }
        return ThreeMFDocument(unit: root.unit, parts: geometries)
    }

    // MARK: - Package layout

    /// The root `.model` part, from the package relationships when they name one.
    private func rootModelPath() -> String {
        guard let rels = try? archive.contents(named: "_rels/.rels") ?? Data(),
              !rels.isEmpty,
              let target = PackageRelationshipsParser.rootModelTarget(rels),
              let normalized = normalize(target),
              archive.entry(named: normalized) != nil else {
            return Self.conventionalRootPath
        }
        return normalized
    }

    /// Package part names are absolute (`/3D/3dmodel.model`); ZIP entry names are not.
    private func normalize(_ path: String?) -> String? {
        guard var path, !path.isEmpty else { return nil }
        while path.hasPrefix("/") { path.removeFirst() }
        return path.isEmpty ? nil : path
    }

    private mutating func part(at path: String) throws -> ThreeMFModelPart {
        if let cached = parts[path] { return cached }
        guard let data = try archive.contents(named: path) else {
            throw ModelLoadingError.malformed(reason: "3MF package has no part named \(path)")
        }
        let parsed = try ThreeMFModelParser.parse(data)
        parts[path] = parsed
        return parsed
    }

    // MARK: - Resolution

    /// Walks one object, emitting its mesh and recursing into its components.
    ///
    /// `visiting` holds the (part, object) pairs on the current path, so an object that
    /// contains itself — directly or through a chain — is reported instead of recursed
    /// into forever. A *repeated* reference on separate branches is legal and stays legal.
    private mutating func append(
        objectID: Int,
        partPath: String,
        transform: simd_float4x4,
        depth: Int,
        visiting: Set<String>,
        into geometries: inout [MeshGeometry]
    ) throws {
        guard depth <= Self.maximumComponentDepth else {
            throw ModelLoadingError.malformed(reason: "3MF component nesting is too deep")
        }
        let key = "\(partPath)#\(objectID)"
        guard !visiting.contains(key) else {
            throw ModelLoadingError.malformed(reason: "3MF object \(objectID) contains itself")
        }

        let model = try part(at: partPath)
        guard let object = model.objects[objectID] else {
            // A build item pointing at a missing object is a broken package, but the
            // rest of the plate is still worth showing.
            return
        }

        if !object.triangles.isEmpty {
            geometries.append(contentsOf: try meshGeometries(
                of: object,
                in: model,
                transform: transform
            ))
        }

        var nextVisiting = visiting
        nextVisiting.insert(key)
        for component in object.components {
            try append(
                objectID: component.objectID,
                partPath: normalize(component.path) ?? partPath,
                // The component's own transform applies first, then the parent's — the
                // same composition order as a scene graph read from the root down.
                transform: transform * component.transform,
                depth: depth + 1,
                visiting: nextVisiting,
                into: &geometries
            )
        }
    }

    /// Turns one object's mesh into geometry, split by material.
    ///
    /// Triangles are grouped by the colour they resolve to, and each group becomes its
    /// own ``MeshGeometry`` with only the vertices it uses. A single-material object —
    /// the common case — takes the one-group path and keeps its vertex array intact.
    private func meshGeometries(
        of object: ThreeMFObject,
        in model: ThreeMFModelPart,
        transform: simd_float4x4
    ) throws -> [MeshGeometry] {
        let vertexCount = object.vertices.count
        let positions: [SIMD3<Float>]
        if transform == matrix_identity_float4x4 {
            positions = object.vertices
        } else {
            positions = object.vertices.map { vertex in
                let transformed = transform * SIMD4<Float>(vertex, 1)
                return SIMD3<Float>(transformed.x, transformed.y, transformed.z)
            }
        }

        // Group triangles by resolved colour. `nil` is "no material", which is its own
        // group so an object with some painted and some bare triangles keeps both.
        var groups: [ThreeMFColorKey: [UInt32]] = [:]
        var groupOrder: [ThreeMFColorKey] = []
        for triangle in object.triangles {
            guard triangle.v1 >= 0, triangle.v1 < vertexCount,
                  triangle.v2 >= 0, triangle.v2 < vertexCount,
                  triangle.v3 >= 0, triangle.v3 < vertexCount else {
                throw ModelLoadingError.malformed(
                    reason: "3MF object \(object.id) has a triangle index out of range"
                )
            }
            let key = ThreeMFColorKey(
                color: color(for: triangle, of: object, in: model)
            )
            if groups[key] == nil {
                groups[key] = []
                groupOrder.append(key)
            }
            groups[key]?.append(contentsOf: [
                UInt32(triangle.v1), UInt32(triangle.v2), UInt32(triangle.v3)
            ])
        }

        let name = object.name.isEmpty ? "object-\(object.id)" : object.name
        return groupOrder.enumerated().compactMap { index, key in
            guard let indices = groups[key], !indices.isEmpty else { return nil }
            let material = key.color.map {
                MeshMaterialDescription(name: "3mf-\(object.id)-\(index)", baseColor: $0)
            }
            let partName = groupOrder.count == 1 ? name : "\(name)-\(index)"
            guard groupOrder.count > 1 else {
                return MeshGeometry(
                    name: partName,
                    positions: positions,
                    indices: indices,
                    material: material
                )
            }
            return Self.compacted(
                name: partName,
                positions: positions,
                indices: indices,
                material: material
            )
        }
    }

    /// The colour a triangle resolves to: its own `pid`/`p1` when it has them, otherwise
    /// the object's `pid`/`pindex`, otherwise none.
    private func color(
        for triangle: ThreeMFTriangle,
        of object: ThreeMFObject,
        in model: ThreeMFModelPart
    ) -> SIMD4<Float>? {
        let groupID = triangle.propertyGroup ?? object.propertyGroup
        let index = triangle.propertyIndex ?? object.propertyIndex ?? 0
        guard let groupID, let colors = model.propertyGroups[groupID],
              index >= 0, index < colors.count else { return nil }
        return colors[index]
    }

    /// Rebuilds a material group's vertex array so it holds only the vertices it uses.
    private static func compacted(
        name: String,
        positions: [SIMD3<Float>],
        indices: [UInt32],
        material: MeshMaterialDescription?
    ) -> MeshGeometry {
        var remap: [UInt32: UInt32] = [:]
        var newPositions: [SIMD3<Float>] = []
        var newIndices: [UInt32] = []
        newIndices.reserveCapacity(indices.count)
        for original in indices {
            if let existing = remap[original] {
                newIndices.append(existing)
                continue
            }
            let next = UInt32(newPositions.count)
            remap[original] = next
            newPositions.append(positions[Int(original)])
            newIndices.append(next)
        }
        return MeshGeometry(
            name: name,
            positions: newPositions,
            indices: newIndices,
            material: material
        )
    }
}

/// A hashable stand-in for an optional colour, so triangles can be grouped by material.
private struct ThreeMFColorKey: Hashable {
    let red: Float
    let green: Float
    let blue: Float
    let alpha: Float
    let hasColor: Bool

    init(color: SIMD4<Float>?) {
        hasColor = color != nil
        let value = color ?? .zero
        red = value.x
        green = value.y
        blue = value.z
        alpha = value.w
    }

    var color: SIMD4<Float>? {
        hasColor ? SIMD4<Float>(red, green, blue, alpha) : nil
    }
}

/// Reads `_rels/.rels` to find the package's root model part.
private final class PackageRelationshipsParser: NSObject, XMLParserDelegate {

    /// The OPC relationship type every 3MF writer uses for the root model.
    private static let modelRelationshipSuffix = "/3dmodel"

    private var target: String?

    /// The `Target` of the first relationship whose `Type` names the 3D model, or `nil`.
    static func rootModelTarget(_ data: Data) -> String? {
        let delegate = PackageRelationshipsParser()
        let parser = XMLParser(data: data)
        parser.delegate = delegate
        parser.shouldResolveExternalEntities = false
        parser.externalEntityResolvingPolicy = .never
        parser.parse()
        return delegate.target
    }

    func parser(
        _ parser: XMLParser,
        didStartElement elementName: String,
        namespaceURI: String?,
        qualifiedName: String?,
        attributes: [String: String]
    ) {
        guard target == nil,
              elementName.split(separator: ":").last.map(String.init) == "Relationship",
              let type = attributes["Type"],
              type.lowercased().hasSuffix(Self.modelRelationshipSuffix) else { return }
        target = attributes["Target"]
    }
}

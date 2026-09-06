import Foundation
import simd

/// An axis-aligned bounding box over mesh vertices.
///
/// A plain value type rather than RealityKit's `BoundingBox` so that parsing, bounds
/// arithmetic and the unit conversion are all testable without a live RealityKit scene —
/// and so that ``MeshAsset`` stays usable on a background task.
public struct MeshBounds: Sendable, Equatable {
    /// The lower corner.
    public var min: SIMD3<Float>
    /// The upper corner.
    public var max: SIMD3<Float>

    public init(min: SIMD3<Float>, max: SIMD3<Float>) {
        self.min = min
        self.max = max
    }

    /// The tightest box containing every point, or `nil` for an empty sequence.
    public init?(points: some Sequence<SIMD3<Float>>) {
        var iterator = points.makeIterator()
        guard let first = iterator.next() else { return nil }
        var lo = first
        var hi = first
        while let point = iterator.next() {
            lo = simd_min(lo, point)
            hi = simd_max(hi, point)
        }
        self.init(min: lo, max: hi)
    }

    /// Size along each axis.
    public var extents: SIMD3<Float> { max - min }

    /// The box centre.
    public var center: SIMD3<Float> { (min + max) / 2 }

    /// The longest edge — the number a "fits in a 20 cm printer" check compares against.
    public var largestExtent: Float {
        let e = extents
        return Swift.max(e.x, Swift.max(e.y, e.z))
    }

    /// The box containing both boxes.
    public func union(_ other: MeshBounds) -> MeshBounds {
        MeshBounds(min: simd_min(min, other.min), max: simd_max(max, other.max))
    }

    /// This box scaled about the origin — how a unit conversion moves it.
    public func scaled(by factor: Float) -> MeshBounds {
        MeshBounds(min: min * factor, max: max * factor)
    }
}

/// A material as described by a source file, before it becomes a RealityKit material.
///
/// Deliberately small: the properties that OBJ's `.mtl`, PLY's vertex colours and 3MF's
/// `<basematerials>` actually carry, in a form that survives being read off the main
/// thread. ``ModelNode`` turns it into a `PhysicallyBasedMaterial`.
public struct MeshMaterialDescription: Sendable, Equatable {
    /// The name authored in the file, when it has one.
    public var name: String?
    /// Linear-space RGBA base colour, components in `0...1`.
    public var baseColor: SIMD4<Float>?
    /// `0` dielectric … `1` metal.
    public var metallic: Float?
    /// `0` mirror … `1` fully rough.
    public var roughness: Float?
    /// A base-colour texture sitting next to the model file (OBJ `map_Kd`, 3MF texture
    /// part extracted to a temporary file).
    public var baseColorTextureURL: URL?

    public init(
        name: String? = nil,
        baseColor: SIMD4<Float>? = nil,
        metallic: Float? = nil,
        roughness: Float? = nil,
        baseColorTextureURL: URL? = nil
    ) {
        self.name = name
        self.baseColor = baseColor
        self.metallic = metallic
        self.roughness = roughness
        self.baseColorTextureURL = baseColorTextureURL
    }
}

/// One indexed triangle mesh with a single material — a "part" of a loaded file.
///
/// A file yields several of these when it has several materials (an OBJ with three
/// `usemtl` groups, a 3MF with three build items). Vertices are **not** shared between
/// parts: each part carries only the vertices its own indices reference, so a part can
/// be handed to RealityKit, measured, or dropped on its own.
public struct MeshGeometry: Sendable {
    /// The object/group name from the file, or a generated one.
    public var name: String
    /// Vertex positions, in the asset's ``MeshAsset/unit``.
    public var positions: [SIMD3<Float>]
    /// Per-vertex normals. `nil` when the file had none and none were generated.
    public var normals: [SIMD3<Float>]?
    /// Per-vertex UVs.
    public var textureCoordinates: [SIMD2<Float>]?
    /// Per-vertex linear RGBA colours — PLY's `red/green/blue` properties, mostly.
    ///
    /// RealityKit's `MeshDescriptor` has no vertex-colour channel, so these are not
    /// rendered per-vertex; ``ModelNode`` folds their average into the material tint so
    /// a coloured scan does not come back grey. The full array stays here for callers
    /// that want it.
    public var colors: [SIMD4<Float>]?
    /// Triangle indices — always a multiple of 3, always into this part's own arrays.
    public var indices: [UInt32]
    /// The material this part is drawn with, when the file described one.
    public var material: MeshMaterialDescription?

    public init(
        name: String,
        positions: [SIMD3<Float>],
        normals: [SIMD3<Float>]? = nil,
        textureCoordinates: [SIMD2<Float>]? = nil,
        colors: [SIMD4<Float>]? = nil,
        indices: [UInt32],
        material: MeshMaterialDescription? = nil
    ) {
        self.name = name
        self.positions = positions
        self.normals = normals
        self.textureCoordinates = textureCoordinates
        self.colors = colors
        self.indices = indices
        self.material = material
    }

    /// Number of triangles.
    public var triangleCount: Int { indices.count / 3 }

    /// Bounding box of this part, `nil` when it has no vertices.
    public var bounds: MeshBounds? { MeshBounds(points: positions) }

    /// This part with every position multiplied by `factor` — the unit conversion.
    /// Normals are left alone: a uniform positive scale does not change them.
    public func scaled(by factor: Float) -> MeshGeometry {
        guard factor != 1 else { return self }
        var copy = self
        copy.positions = positions.map { $0 * factor }
        return copy
    }

    /// Flat-shaded normals computed from the triangles, for a file that carried none.
    ///
    /// Area-weighted accumulation over the incident triangles: a triangle's face normal
    /// is added to each of its three vertices unnormalized, so larger triangles pull
    /// harder, then each vertex normal is normalized once at the end. Degenerate
    /// triangles contribute a zero-length cross product and therefore nothing at all.
    public func generatingNormals() -> MeshGeometry {
        guard normals == nil, !positions.isEmpty, indices.count >= 3 else { return self }
        var accumulated = [SIMD3<Float>](repeating: .zero, count: positions.count)
        var index = 0
        while index + 2 < indices.count {
            let a = Int(indices[index])
            let b = Int(indices[index + 1])
            let c = Int(indices[index + 2])
            index += 3
            guard a < positions.count, b < positions.count, c < positions.count else { continue }
            let faceNormal = cross(positions[b] - positions[a], positions[c] - positions[a])
            accumulated[a] += faceNormal
            accumulated[b] += faceNormal
            accumulated[c] += faceNormal
        }
        var copy = self
        copy.normals = accumulated.map { vector in
            let lengthSquared = simd_length_squared(vector)
            // A vertex touched only by degenerate triangles has no defined normal;
            // +Y keeps it lit rather than black, and normalize() would return NaN.
            return lengthSquared > 0 ? vector / lengthSquared.squareRoot() : SIMD3<Float>(0, 1, 0)
        }
        return copy
    }
}

/// Everything one model file describes: its parts, the format it came in, and the unit
/// its coordinates are in.
///
/// This is the format-neutral layer. It has no RealityKit dependency, so it parses on a
/// background task, is testable without a simulator scene, and can be measured
/// ("is this print 21 cm tall?") before anything is rendered.
///
/// ```swift
/// let asset = try MeshAsset.load(contentsOf: url)          // sniffs the format
/// print(asset.format, asset.unit, asset.triangleCount)
/// print(asset.boundsInMeters?.extents ?? .zero)            // real-world size
/// ```
///
/// To display it, hand it to ``ModelNode``:
/// `let node = try await ModelNode.load(contentsOf: url)`.
public struct MeshAsset: Sendable {
    /// The format the file was read as.
    public let format: ModelFormat
    /// The unit ``parts`` coordinates are expressed in.
    public let unit: ModelUnit
    /// The mesh parts, in file order.
    public let parts: [MeshGeometry]

    public init(format: ModelFormat, unit: ModelUnit, parts: [MeshGeometry]) {
        self.format = format
        self.unit = unit
        self.parts = parts
    }

    /// Total vertices across every part.
    public var vertexCount: Int { parts.reduce(0) { $0 + $1.positions.count } }

    /// Total triangles across every part.
    public var triangleCount: Int { parts.reduce(0) { $0 + $1.triangleCount } }

    /// Bounding box over every part, in ``unit``.
    public var bounds: MeshBounds? {
        parts.compactMap(\.bounds).reduce(nil) { partial, next in
            partial.map { $0.union(next) } ?? next
        }
    }

    /// Bounding box in metres — the real-world size, unit applied.
    public var boundsInMeters: MeshBounds? { bounds?.scaled(by: unit.metersPerUnit) }

    /// The same asset with every coordinate converted to metres, ready for RealityKit.
    ///
    /// The conversion is baked into the vertex positions rather than left as an entity
    /// scale, so a caller's later `.scale(_:)` is *their* scale and does not silently
    /// destroy the unit correction.
    public func inMeters() -> MeshAsset {
        guard unit != .meters else { return self }
        return MeshAsset(
            format: format,
            unit: .meters,
            parts: parts.map { $0.scaled(by: unit.metersPerUnit) }
        )
    }

    // MARK: - Loading

    /// Reads a mesh file into parts, choosing the reader from the file's own content.
    ///
    /// Handles the formats whose ``ModelFormat/loader`` is ``ModelFormat/Loader/modelIO``
    /// — STL, OBJ and PLY. USD and Reality files
    /// are RealityKit's own business — they are rejected here with
    /// ``ModelLoadingError/unsupportedFormat(fileExtension:)`` and handled by
    /// `ModelNode.load(contentsOf:)`, which is the entry point that covers every format.
    ///
    /// - Parameters:
    ///   - url: A local file URL.
    ///   - unit: The unit the file's coordinates are in. `nil` means "use the unit the
    ///     file declares, and ``ModelFormat/defaultUnit`` when it declares none".
    /// - Throws: ``ModelLoadingError``.
    public static func load(contentsOf url: URL, unit: ModelUnit? = nil) throws -> MeshAsset {
        let format = try ModelFormat.sniff(contentsOf: url)
        return try load(contentsOf: url, format: format, unit: unit)
    }

    /// Reads a mesh file whose format is already known — skips the sniffing read.
    public static func load(
        contentsOf url: URL,
        format: ModelFormat,
        unit: ModelUnit? = nil
    ) throws -> MeshAsset {
        switch format.loader {
        case .modelIO:
            let parts = try ModelIOMeshReader.read(contentsOf: url)
            guard !parts.isEmpty else { throw ModelLoadingError.emptyMesh }
            return MeshAsset(format: format, unit: unit ?? format.defaultUnit, parts: parts)
        case .realityKit:
            throw ModelLoadingError.unsupportedFormat(fileExtension: format.fileExtension)
        }
    }
}

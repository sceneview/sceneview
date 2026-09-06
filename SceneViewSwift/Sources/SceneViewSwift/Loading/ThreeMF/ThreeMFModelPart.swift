import Foundation
import simd

/// One `.model` XML part inside a 3MF container, as parsed.
///
/// A 3MF is an OPC (zip) package, and the Production extension lets a package split its
/// model across several `.model` parts that reference each other by `p:path` — which is
/// how Bambu Studio and Orca write project files. So "the model" is a part, not the
/// document, and ``ThreeMFLoader`` resolves references across parts.
struct ThreeMFModelPart {
    /// `<model unit="…">`, defaulting to millimetres per the core spec.
    var unit: ModelUnit = .millimeters
    /// Objects by their `id`, which is unique within a part (not across parts).
    var objects: [Int: ThreeMFObject] = [:]
    /// `<build><item>` — what the package actually asks to be shown.
    var buildItems: [ThreeMFBuildItem] = []
    /// Property groups by `id`: `<basematerials>` and the materials extension's
    /// `<colorgroup>`, flattened to the colours they resolve to.
    var propertyGroups: [Int: [SIMD4<Float>]] = [:]
}

/// A `<object>`: either a mesh, or a group of `<component>` references, or both.
struct ThreeMFObject {
    var id: Int
    var name: String = ""
    /// `pid` / `pindex` — the object's default property group and index within it.
    var propertyGroup: Int?
    var propertyIndex: Int?
    var vertices: [SIMD3<Float>] = []
    var triangles: [ThreeMFTriangle] = []
    var components: [ThreeMFComponent] = []
}

/// A `<triangle>`, with the optional per-triangle property reference that makes
/// multi-colour prints multi-colour.
struct ThreeMFTriangle {
    var v1: Int
    var v2: Int
    var v3: Int
    /// `pid` — overrides the object's property group for this triangle.
    var propertyGroup: Int?
    /// `p1` — index within the group. The spec also defines `p2`/`p3` for per-vertex
    /// interpolation; a flat `p1` fill is what slicers write and what is read here.
    var propertyIndex: Int?
}

/// A `<component>` — another object placed with a transform, possibly in another part.
struct ThreeMFComponent {
    var objectID: Int
    /// Production-extension `p:path`, e.g. `/3D/Objects/object_1.model`.
    var path: String?
    var transform: simd_float4x4 = matrix_identity_float4x4
}

/// A `<build><item>` — the top-level placement of an object.
struct ThreeMFBuildItem {
    var objectID: Int
    var path: String?
    var transform: simd_float4x4 = matrix_identity_float4x4
}

extension simd_float4x4 {
    /// Parses a 3MF `transform` attribute.
    ///
    /// 3MF writes a 4×3 matrix as twelve numbers in **row-major, row-vector** order —
    /// `m00 m01 m02 m10 m11 m12 m20 m21 m22 m30 m31 m32`, where a point is transformed as
    /// `v' = v · M` and the last row is the translation. `simd_float4x4` is
    /// column-major and multiplies as `M · v`, so the rows of the file become the
    /// **columns** here. Getting this backwards transposes every placed part — the
    /// failure looks like scattered geometry, not like a broken parser.
    init?(threeMFTransform text: String) {
        let values = text.split(whereSeparator: \.isWhitespace).compactMap { Float($0) }
        guard values.count == 12 else { return nil }
        self.init(
            SIMD4<Float>(values[0], values[1], values[2], 0),
            SIMD4<Float>(values[3], values[4], values[5], 0),
            SIMD4<Float>(values[6], values[7], values[8], 0),
            SIMD4<Float>(values[9], values[10], values[11], 1)
        )
    }
}

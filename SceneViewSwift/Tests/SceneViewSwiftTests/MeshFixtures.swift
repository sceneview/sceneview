import Foundation
import simd

/// Minimal, hand-built model files for the loader tests.
///
/// Generated in code rather than checked in as binaries for three reasons: the exact
/// bytes are the thing under test (a binary STL whose header starts with `solid` is a
/// *deliberate* trap, not an accident of some exporter), a reviewer can read the
/// generator and see what the expected numbers are, and the test target needs no
/// `resources:` wiring in `Package.swift`.
enum MeshFixtures {

    /// A scratch directory that is deleted when the test run tears it down.
    static func makeDirectory() throws -> URL {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("sceneview-mesh-fixtures-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }

    // MARK: - Geometry

    /// The 12 triangles of an axis-aligned box from the origin to `size`, wound
    /// counter-clockwise when seen from outside.
    static func boxTriangles(size: SIMD3<Float>) -> [(SIMD3<Float>, SIMD3<Float>, SIMD3<Float>)] {
        let (x, y, z) = (size.x, size.y, size.z)
        let corners = [
            SIMD3<Float>(0, 0, 0), SIMD3<Float>(x, 0, 0),
            SIMD3<Float>(x, y, 0), SIMD3<Float>(0, y, 0),
            SIMD3<Float>(0, 0, z), SIMD3<Float>(x, 0, z),
            SIMD3<Float>(x, y, z), SIMD3<Float>(0, y, z)
        ]
        let quads = [
            (0, 3, 2, 1),  // back  (-z)
            (4, 5, 6, 7),  // front (+z)
            (0, 1, 5, 4),  // bottom
            (2, 3, 7, 6),  // top
            (0, 4, 7, 3),  // left
            (1, 2, 6, 5)   // right
        ]
        return quads.flatMap { quad in
            [
                (corners[quad.0], corners[quad.1], corners[quad.2]),
                (corners[quad.0], corners[quad.2], corners[quad.3])
            ]
        }
    }

    /// The face normal of a triangle, or `+Y` when it is degenerate.
    private static func normal(
        _ triangle: (SIMD3<Float>, SIMD3<Float>, SIMD3<Float>)
    ) -> SIMD3<Float> {
        let raw = cross(triangle.1 - triangle.0, triangle.2 - triangle.0)
        let lengthSquared = simd_length_squared(raw)
        return lengthSquared > 0 ? raw / lengthSquared.squareRoot() : SIMD3<Float>(0, 1, 0)
    }

    // MARK: - STL

    /// A **binary** STL whose 80-byte header begins with the word `solid`.
    ///
    /// This is the trap the format sniffer exists for: a prefix test alone reads this as
    /// an ASCII STL and returns an empty mesh, which is exactly how a viewer ends up
    /// showing nothing for a file every slicer opens. Real exporters do write `solid …`
    /// into the binary header.
    static func binarySTL(
        triangles: [(SIMD3<Float>, SIMD3<Float>, SIMD3<Float>)],
        headerText: String = "solid exported by a tool that should know better"
    ) -> Data {
        var data = Data()
        var header = [UInt8](repeating: 0, count: 80)
        for (index, byte) in Array(headerText.utf8).prefix(80).enumerated() {
            header[index] = byte
        }
        data.append(contentsOf: header)
        appendLittleEndian(UInt32(triangles.count), to: &data)
        for triangle in triangles {
            appendFloats(normal(triangle), to: &data)
            appendFloats(triangle.0, to: &data)
            appendFloats(triangle.1, to: &data)
            appendFloats(triangle.2, to: &data)
            appendLittleEndian(UInt16(0), to: &data)  // attribute byte count
        }
        return data
    }

    /// An ASCII STL — the other half of the format.
    static func asciiSTL(
        triangles: [(SIMD3<Float>, SIMD3<Float>, SIMD3<Float>)],
        name: String = "fixture"
    ) -> Data {
        var text = "solid \(name)\n"
        for triangle in triangles {
            let n = normal(triangle)
            text += "  facet normal \(n.x) \(n.y) \(n.z)\n    outer loop\n"
            for vertex in [triangle.0, triangle.1, triangle.2] {
                text += "      vertex \(vertex.x) \(vertex.y) \(vertex.z)\n"
            }
            text += "    endloop\n  endfacet\n"
        }
        text += "endsolid \(name)\n"
        return Data(text.utf8)
    }

    // MARK: - OBJ

    /// A one-quad OBJ plus its `.mtl` sidecar, written side by side.
    ///
    /// The face is a **quad**, not two triangles: OBJ allows it, ModelIO preserves it,
    /// and RealityKit cannot draw it — so this fixture is what proves the reader
    /// triangulates instead of dropping the face.
    ///
    /// - Returns: The URL of the `.obj`. The `.mtl` sits next to it.
    @discardableResult
    static func writeQuadOBJ(
        in directory: URL,
        name: String = "panel",
        size: SIMD2<Float> = SIMD2(4, 5),
        baseColor: SIMD3<Float> = SIMD3(0.8, 0.2, 0.1)
    ) throws -> URL {
        let mtl = """
        newmtl painted
        Kd \(baseColor.x) \(baseColor.y) \(baseColor.z)
        Ka 0 0 0
        Ns 120

        """
        let obj = """
        # SceneViewSwift test fixture
        mtllib \(name).mtl
        o \(name)
        v 0 0 0
        v \(size.x) 0 0
        v \(size.x) \(size.y) 0
        v 0 \(size.y) 0
        vn 0 0 1
        usemtl painted
        f 1//1 2//1 3//1 4//1

        """
        try Data(mtl.utf8).write(to: directory.appendingPathComponent("\(name).mtl"))
        let url = directory.appendingPathComponent("\(name).obj")
        try Data(obj.utf8).write(to: url)
        return url
    }

    // MARK: - PLY

    /// A binary little-endian PLY with per-vertex `uchar` colours and two triangular
    /// faces — the shape a phone scanner exports.
    static func binaryPLY(
        size: SIMD2<Float> = SIMD2(2, 3),
        colors: [SIMD3<UInt8>] = [
            SIMD3(255, 0, 0), SIMD3(0, 255, 0), SIMD3(0, 0, 255), SIMD3(255, 255, 255)
        ]
    ) -> Data {
        let header = """
        ply
        format binary_little_endian 1.0
        comment SceneViewSwift test fixture
        element vertex 4
        property float x
        property float y
        property float z
        property uchar red
        property uchar green
        property uchar blue
        element face 2
        property list uchar int vertex_indices
        end_header

        """
        var data = Data(header.utf8)
        let vertices = [
            SIMD3<Float>(0, 0, 0),
            SIMD3<Float>(size.x, 0, 0),
            SIMD3<Float>(size.x, size.y, 0),
            SIMD3<Float>(0, size.y, 0)
        ]
        for (vertex, color) in zip(vertices, colors) {
            appendFloats(vertex, to: &data)
            data.append(contentsOf: [color.x, color.y, color.z])
        }
        for face in [[0, 1, 2], [0, 2, 3]] {
            data.append(3)  // uchar list length
            for index in face { appendLittleEndian(Int32(index), to: &data) }
        }
        return data
    }

    // MARK: - Byte helpers

    private static func appendFloats(_ vector: SIMD3<Float>, to data: inout Data) {
        appendLittleEndian(vector.x.bitPattern, to: &data)
        appendLittleEndian(vector.y.bitPattern, to: &data)
        appendLittleEndian(vector.z.bitPattern, to: &data)
    }

    private static func appendLittleEndian<T: FixedWidthInteger>(_ value: T, to data: inout Data) {
        var littleEndian = value.littleEndian
        withUnsafeBytes(of: &littleEndian) { data.append(contentsOf: $0) }
    }
}

import Foundation
import simd

/// Writes a capture's coloured point cloud as a binary little-endian PLY — the file Open3D,
/// CloudCompare, MeshLab, trimesh and Blender open as is.
///
/// One `vertex` element per point: `float x, y, z` (world space, metres, Y up) and
/// `uchar red, green, blue` (the sRGB colour as recorded). No faces.
///
/// A `.svscan` v2 capture with a dense cloud writes that cloud instead of the sparse one — it is
/// the room, the sparse points only its landmarks — each vertex followed by its unit normal,
/// `float nx, ny, nz` (``bytesPerDensePoint`` bytes a vertex), the input MeshLab's and Open3D's
/// surface reconstructions want. Byte for byte Android's `RerunPlyWriter`.
enum RerunPLYWriter {
    /// The `.ply` file for `scene`'s point cloud. A point without a colour (``RerunExportScene/pointColors``
    /// shorter than ``RerunExportScene/points``) is written white.
    static func data(for scene: RerunExportScene) -> Data {
        if let dense = scene.dense, dense.count > 0 { return denseData(title: scene.title, dense) }
        let count = scene.points.count
        var data = Data(header(title: scene.title, count: count).utf8)
        data.reserveCapacity(data.count + count * bytesPerPoint)
        for index in 0..<count {
            let point = scene.points[index]
            let color = index < scene.pointColors.count
                ? scene.pointColors[index]
                : SIMD3<UInt8>(255, 255, 255)
            append(point.x, to: &data)
            append(point.y, to: &data)
            append(point.z, to: &data)
            data.append(contentsOf: [color.x, color.y, color.z])
        }
        return data
    }

    /// The dense `cloud` with its normals (`0, 1, 0` for a cloud without any); a surfel the camera
    /// never coloured is written white.
    private static func denseData(title: String, _ cloud: RerunDenseCloud) -> Data {
        let count = cloud.count
        var data = Data(header(title: title, count: count, normals: true).utf8)
        data.reserveCapacity(data.count + count * bytesPerDensePoint)
        for index in 0..<count {
            let point = cloud.positions[index]
            append(point.x, to: &data)
            append(point.y, to: &data)
            append(point.z, to: &data)
            let c = cloud.colors[index] == 0 ? 0xFFFF_FFFF : cloud.colors[index]
            data.append(contentsOf: [UInt8((c >> 16) & 0xFF), UInt8((c >> 8) & 0xFF), UInt8(c & 0xFF)])
            let normal = cloud.normals?[index] ?? SIMD3<Float>(0, 1, 0)
            append(normal.x, to: &data)
            append(normal.y, to: &data)
            append(normal.z, to: &data)
        }
        return data
    }

    /// Bytes per vertex record: three `float`s and three `uchar`s.
    static let bytesPerPoint = 3 * MemoryLayout<Float>.size + 3

    /// Bytes per dense vertex record: ``bytesPerPoint`` plus three `float` normal components.
    static let bytesPerDensePoint = bytesPerPoint + 3 * MemoryLayout<Float>.size

    /// The ASCII header, `end_header` and its newline included; with `normals`, `nx ny nz` last.
    static func header(title: String, count: Int, normals: Bool = false) -> String {
        // A line break in the title would end the comment line and corrupt the header.
        let safeTitle = title.components(separatedBy: .newlines).joined(separator: " ")
        return """
        ply
        format binary_little_endian 1.0
        comment SceneView capture "\(safeTitle)", Y up, metres
        element vertex \(count)
        property float x
        property float y
        property float z
        property uchar red
        property uchar green
        property uchar blue
        \(normals ? "property float nx\nproperty float ny\nproperty float nz\n" : "")end_header

        """
    }

    private static func append(_ value: Float, to data: inout Data) {
        withUnsafeBytes(of: value.bitPattern.littleEndian) { data.append(contentsOf: $0) }
    }
}

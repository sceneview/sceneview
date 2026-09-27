import Foundation
import simd

/// Writes a capture's coloured point cloud as a binary little-endian PLY — the file Open3D,
/// CloudCompare, MeshLab, trimesh and Blender open as is.
///
/// One `vertex` element per point: `float x, y, z` (world space, metres, Y up) and
/// `uchar red, green, blue` (the sRGB colour as recorded). No faces.
enum RerunPLYWriter {
    /// The `.ply` file for `scene`'s point cloud. A point without a colour (``RerunExportScene/pointColors``
    /// shorter than ``RerunExportScene/points``) is written white.
    static func data(for scene: RerunExportScene) -> Data {
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

    /// Bytes per vertex record: three `float`s and three `uchar`s.
    static let bytesPerPoint = 3 * MemoryLayout<Float>.size + 3

    /// The ASCII header, `end_header` and its newline included.
    static func header(title: String, count: Int) -> String {
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
        end_header

        """
    }

    private static func append(_ value: Float, to data: inout Data) {
        withUnsafeBytes(of: value.bitPattern.littleEndian) { data.append(contentsOf: $0) }
    }
}

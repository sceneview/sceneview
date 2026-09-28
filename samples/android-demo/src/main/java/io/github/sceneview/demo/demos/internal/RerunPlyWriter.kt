package io.github.sceneview.demo.demos.internal

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Writes a capture's coloured point cloud as a binary little-endian PLY — the file Open3D,
 * CloudCompare, MeshLab, trimesh and Blender open as is. Byte for byte the iOS demo's
 * `RerunPLYWriter`.
 *
 * One `vertex` element per point: `float x, y, z` (world space, metres, Y up) and
 * `uchar red, green, blue` (the sRGB colour as recorded). No faces.
 */
object RerunPlyWriter {
    /** Bytes per vertex record: three `float`s and three `uchar`s. */
    const val BYTES_PER_POINT = 15

    /** Foundation's `.newlines`: LF, VT, FF, CR, NEL, LINE SEPARATOR, PARAGRAPH SEPARATOR. */
    private val NEWLINES = charArrayOf(
        Char(0x0A), Char(0x0B), Char(0x0C), Char(0x0D), Char(0x85), Char(0x2028), Char(0x2029),
    )

    /** The `.ply` file for [scene]'s point cloud; a point without a colour is written white. */
    fun write(scene: RerunExportScene): ByteArray {
        val count = scene.points.size
        val header = header(scene.title, count).toByteArray(Charsets.UTF_8)
        val out = ByteBuffer.allocate(header.size + count * BYTES_PER_POINT).order(ByteOrder.LITTLE_ENDIAN)
        out.put(header)
        for (index in 0 until count) {
            val point = scene.points[index]
            val color = scene.pointColors.getOrNull(index) ?: RerunExportScene.Rgb.White
            out.putFloat(point.x).putFloat(point.y).putFloat(point.z)
            out.put(color.r.toByte()).put(color.g.toByte()).put(color.b.toByte())
        }
        return out.array()
    }

    /** The ASCII header, `end_header` and its newline included. */
    fun header(title: String, count: Int): String {
        // A line break in the title would end the comment line and corrupt the header:
        // each newline character is a separator, as in Foundation's `.newlines`.
        val safeTitle = title.split(*NEWLINES).joinToString(" ")
        return "ply\n" +
            "format binary_little_endian 1.0\n" +
            "comment SceneView capture \"$safeTitle\", Y up, metres\n" +
            "element vertex $count\n" +
            "property float x\n" +
            "property float y\n" +
            "property float z\n" +
            "property uchar red\n" +
            "property uchar green\n" +
            "property uchar blue\n" +
            "end_header\n"
    }
}

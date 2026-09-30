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
 *
 * A `.svscan` v2 capture with a dense cloud writes that cloud instead of the sparse one — it is
 * the room, the sparse points only its landmarks — each vertex followed by its unit normal,
 * `float nx, ny, nz` ([BYTES_PER_DENSE_POINT] bytes a vertex), the input MeshLab's and Open3D's
 * surface reconstructions want.
 */
object RerunPlyWriter {
    /** Bytes per vertex record: three `float`s and three `uchar`s. */
    const val BYTES_PER_POINT = 15

    /** Bytes per dense vertex record: [BYTES_PER_POINT] plus three `float` normal components. */
    const val BYTES_PER_DENSE_POINT = 27

    /** Foundation's `.newlines`: LF, VT, FF, CR, NEL, LINE SEPARATOR, PARAGRAPH SEPARATOR. */
    private val NEWLINES = charArrayOf(
        Char(0x0A), Char(0x0B), Char(0x0C), Char(0x0D), Char(0x85), Char(0x2028), Char(0x2029),
    )

    /** The `.ply` file for [scene]'s point cloud; a point without a colour is written white. */
    fun write(scene: RerunExportScene): ByteArray {
        scene.dense?.takeIf { it.count > 0 }?.let { return writeDense(scene.title, it) }
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

    /** The dense cloud [cloud] with its normals (`0, 1, 0` for a cloud without any). */
    private fun writeDense(title: String, cloud: DenseCloud): ByteArray {
        val count = cloud.count
        val header = header(title, count, normals = true).toByteArray(Charsets.UTF_8)
        val out = ByteBuffer.allocate(header.size + count * BYTES_PER_DENSE_POINT).order(ByteOrder.LITTLE_ENDIAN)
        out.put(header)
        val normals = cloud.normals
        for (i in 0 until count) {
            out.putFloat(cloud.positions[i * 3])
                .putFloat(cloud.positions[i * 3 + 1])
                .putFloat(cloud.positions[i * 3 + 2])
            val c = cloud.colors[i].takeIf { it != 0 } ?: WHITE
            out.put((c shr 16).toByte()).put((c shr 8).toByte()).put(c.toByte())
            if (normals != null) {
                out.putFloat(normals[i * 3]).putFloat(normals[i * 3 + 1]).putFloat(normals[i * 3 + 2])
            } else {
                out.putFloat(0f).putFloat(1f).putFloat(0f)
            }
        }
        return out.array()
    }

    private const val WHITE = 0xFFFFFFFF.toInt()

    /** The ASCII header, `end_header` and its newline included; with [normals], `nx ny nz` last. */
    fun header(title: String, count: Int, normals: Boolean = false): String {
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
            (if (normals) "property float nx\nproperty float ny\nproperty float nz\n" else "") +
            "end_header\n"
    }
}

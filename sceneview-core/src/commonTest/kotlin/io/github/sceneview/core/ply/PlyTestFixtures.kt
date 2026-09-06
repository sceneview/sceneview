package io.github.sceneview.core.ply

internal object PlyTestFixtures {
    private val vertices = arrayOf(
        floatArrayOf(0f, 0f, 0f, 0f, 0f, 1f, 255f, 0f, 0f, 255f),
        floatArrayOf(70f, 0f, 0f, 0f, 0f, 1f, 0f, 255f, 0f, 128f),
        floatArrayOf(70f, 40f, 0f, 0f, 0f, 1f, 0f, 0f, 255f, 255f),
        floatArrayOf(0f, 40f, 0f, 0f, 0f, 1f, 255f, 255f, 255f, 255f)
    )

    fun ascii(): ByteArray = buildString {
        append(header("ascii"))
        for (vertex in vertices) {
            append(vertex.take(6).joinToString(" ") { it.plyText() })
            append(" ${vertex[6].toInt()} ${vertex[7].toInt()} ${vertex[8].toInt()}")
            append(" ${vertex[9].toInt()}\n")
        }
        append("4 0 1 2 3\n")
    }.encodeToByteArray()

    fun binary(littleEndian: Boolean): ByteArray {
        val bytes = ArrayList<Byte>()
        bytes.addAll(header(if (littleEndian) "binary_little_endian" else "binary_big_endian").bytes())
        for (vertex in vertices) {
            repeat(6) { bytes.putInt(vertex[it].toBits(), littleEndian) }
            repeat(4) { bytes += vertex[6 + it].toInt().toByte() }
        }
        bytes += 4.toByte()
        repeat(4) { bytes.putInt(it, littleEndian) }
        return bytes.toByteArray()
    }

    private fun header(format: String): String = """
        ply
        format $format 1.0
        comment fixture
        element vertex 4
        property float x
        property float y
        property float z
        property float nx
        property float ny
        property float nz
        property uchar red
        property uchar green
        property uchar blue
        property uchar alpha
        element face 1
        property list uchar int vertex_indices
        end_header
    """.trimIndent() + "\n"

    private fun String.bytes(): Collection<Byte> = encodeToByteArray().toList()

    private fun ArrayList<Byte>.putInt(value: Int, littleEndian: Boolean) {
        repeat(4) { index ->
            val shift = if (littleEndian) index * 8 else (3 - index) * 8
            add((value ushr shift).toByte())
        }
    }
}

/** Stable across Kotlin/JVM and Kotlin/JS (70f is respectively "70.0" and "70"). */
private fun Float.plyText(): String {
    val text = toString()
    return if (text.any { it == '.' || it == 'e' || it == 'E' }) text else "$text.0"
}

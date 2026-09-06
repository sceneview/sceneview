package io.github.sceneview.core.stl

internal object StlTestFixtures {
    // Two non-coplanar faces sharing an edge: area vectors (0,0,2) and (0,1,0).
    val wedge = listOf(
        floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 2f, 0f, 0f, 0f, 1f, 0f),
        floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 1f, 1f, 0f, 0f)
    )

    val box: List<FloatArray> get() {
        val vertices = arrayOf(
            floatArrayOf(0f, 0f, 0f), floatArrayOf(70f, 0f, 0f),
            floatArrayOf(70f, 40f, 0f), floatArrayOf(0f, 40f, 0f),
            floatArrayOf(0f, 0f, 30f), floatArrayOf(70f, 0f, 30f),
            floatArrayOf(70f, 40f, 30f), floatArrayOf(0f, 40f, 30f)
        )
        val indices = intArrayOf(
            0, 2, 1, 0, 3, 2, 4, 5, 6, 4, 6, 7,
            0, 1, 5, 0, 5, 4, 3, 7, 6, 3, 6, 2,
            0, 4, 7, 0, 7, 3, 1, 2, 6, 1, 6, 5
        )
        return List(12) { face ->
            FloatArray(12).also { facet ->
                repeat(3) { vertices[indices[face * 3 + it]].copyInto(facet, 3 + it * 3) }
            }
        }
    }

    fun binary(facets: List<FloatArray> = box, header: String = "SceneView fixture"): ByteArray =
        ByteArray(84 + 50 * facets.size).also { bytes ->
            header.encodeToByteArray().copyInto(bytes, endIndex = minOf(80, header.length))
            putInt(bytes, 80, facets.size)
            facets.forEachIndexed { face, facet ->
                facet.forEachIndexed { i, value -> putInt(bytes, 84 + face * 50 + i * 4, value.toBits()) }
            }
        }

    fun ascii(facets: List<FloatArray> = box): ByteArray = buildString {
        append("solid test part\n")
        for (facet in facets) {
            append("facet normal ${facet[0].stl()} ${facet[1].stl()} ${facet[2].stl()}\nouter loop\n")
            repeat(3) {
                val at = 3 + it * 3
                append("vertex ${facet[at].stl()} ${facet[at + 1].stl()} ${facet[at + 2].stl()}\n")
            }
            append("endloop\nendfacet\n")
        }
        append("endsolid test part\n")
    }.encodeToByteArray()

    fun putInt(bytes: ByteArray, at: Int, value: Int) {
        repeat(4) { bytes[at + it] = (value ushr (it * 8)).toByte() }
    }
}

/**
 * Platform-stable float text: Kotlin/JVM prints 70f as "70.0" but Kotlin/JS prints "70", and the
 * malformed-input test edits the fixture by replacing "70.0" — so the fixture must write it.
 */
private fun Float.stl(): String {
    val text = toString()
    return if (text.any { it == '.' || it == 'e' || it == 'E' }) text else "$text.0"
}

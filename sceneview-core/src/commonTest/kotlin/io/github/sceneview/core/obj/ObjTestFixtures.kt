package io.github.sceneview.core.obj

internal object ObjTestFixtures {
    val Triangle = """
        v 0 0 0
        v 2 0 0
        v 0 3 0
        f 1 2 3
    """.trimIndent()

    val Cube = """
        # A 10 mm cube with outward-wound quads
        o cube
        v 0 0 0
        v 10 0 0
        v 10 10 0
        v 0 10 0
        v 0 0 10
        v 10 0 10
        v 10 10 10
        v 0 10 10
        f 1 4 3 2
        f 5 6 7 8
        f 1 2 6 5
        f 4 8 7 3
        f 1 5 8 4
        f 2 3 7 6
    """.trimIndent().encodeToByteArray()

    val Colored = """
        mtllib materials/paint.mtl
        v 0 0 0
        v 2 0 0
        v 0 3 0
        g red
        usemtl red
        f 1 2 3
        g green
        usemtl green
        f -3 -2 -1
    """.trimIndent().encodeToByteArray()

    val Materials = """
        newmtl red
        Kd 1 0 0
        d 0.5
        map_Kd red.png
        newmtl green
        Kd 0 1 0
        Tr 0.25
    """.trimIndent().encodeToByteArray()
}

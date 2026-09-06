package io.github.sceneview.core.obj

import io.github.sceneview.core.threemf.ThreeMfUnit
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ObjLoaderTest {
    @Test
    fun cubeQuadsBecomeTwelveTrianglesWithOriginalBounds() {
        val model = ObjLoader.parse(ObjTestFixtures.Cube)
        assertEquals(ThreeMfUnit.MILLIMETER, model.unit)
        assertEquals(12, model.triangleCount)
        val group = model.groups.single()
        assertEquals("cube", group.name)
        for (axis in 0 until 3) {
            val values = (axis until group.positions.size step 3).map { group.positions[it] }
            assertEquals(0f, values.min())
            assertEquals(10f, values.max())
        }
        for (at in group.normals.indices step 3) {
            val length = sqrt((0..2).sumOf { group.normals[at + it].toDouble() * group.normals[at + it] })
            assertEquals(1.0, length, 0.000001)
        }
    }

    @Test
    fun negativeIndicesUseTablesAtTheFaceNotAtEndOfFile() {
        val source = ObjTestFixtures.Triangle.replace("f 1 2 3", "f -3 -2 -1") + "\nv 99 99 99"
        val expected = ObjLoader.parse(ObjTestFixtures.Triangle.encodeToByteArray()).groups.single()
        assertContentEquals(expected.positions, ObjLoader.parse(source.encodeToByteArray()).groups.single().positions)
    }

    @Test
    fun allFaceIndexFormsAndIndependentNegativeNormalUvIndices() {
        val vertices = ObjTestFixtures.Triangle.substringBefore("f ")
        val attributes = "vt 0.25 0.75\nvt 1\nvn 0 0 -2\n"
        for (face in listOf("1 2 3", "1/1 2/2 3/1", "1//1 2//1 3//1", "1/-2/-1 2/-1/-1 3/-2/-1")) {
            val group = ObjLoader.parse((vertices + attributes + "f $face").encodeToByteArray()).groups.single()
            assertEquals(1, group.triangleCount)
            if (face.contains("//") || face.contains("/-")) {
                assertContentEquals(floatArrayOf(0f, 0f, -1f, 0f, 0f, -1f, 0f, 0f, -1f), group.normals)
            } else {
                assertEquals(1f, group.normals[2])
            }
            if (!face.contains("//") && face.substringBefore(' ').substringAfter('/', "").isNotEmpty()) {
                assertContentEquals(floatArrayOf(0.25f, 0.75f, 1f, 0f, 0.25f, 0.75f), group.textureCoordinates)
            } else assertNull(group.textureCoordinates)
        }
    }

    @Test
    fun missingNormalsAreSmoothedAcrossUvAndMaterialSeamsWhileExplicitNormalsSurvive() {
        val source = """
            v 0 0 0
            v 1 0 0
            v 0 1 0
            v 0 0 1
            vt 0 0
            vt 1 1
            vn -1 0 0
            usemtl first
            f 1/1 2/1 3/1
            g second
            usemtl second
            f 1/2 4/2 2/2/1
        """.trimIndent()
        val groups = ObjLoader.parse(source.encodeToByteArray()).groups
        val smooth = 1f / sqrt(2f)
        assertEquals(smooth, groups[0].normals[1], 0.000001f)
        assertEquals(smooth, groups[0].normals[2], 0.000001f)
        assertContentEquals(groups[0].normals.copyOfRange(0, 3), groups[1].normals.copyOfRange(0, 3))
        assertContentEquals(floatArrayOf(-1f, 0f, 0f), groups[1].normals.copyOfRange(6, 9))
    }

    @Test
    fun twoGroupsResolveRelativeMtlColoursAndOpacity() {
        val requested = ArrayList<String>()
        val model = ObjLoader.parse(ObjTestFixtures.Colored) {
            requested += it
            if (it == "materials/paint.mtl") ObjTestFixtures.Materials else null
        }
        assertEquals(listOf("materials/paint.mtl"), requested, "texture images must not be resolved")
        assertEquals(listOf("red", "green"), model.groups.map { it.name })
        assertEquals(listOf("red", "green"), model.groups.map { it.triangleMaterials.single() })
        assertContentEquals(floatArrayOf(1f, 0f, 0f, 0.5f), model.materials["red"]?.baseColorFactor)
        assertContentEquals(floatArrayOf(0f, 1f, 0f, 0.75f), model.materials["green"]?.baseColorFactor)
    }

    @Test
    fun missingMtlAndAbsentResolverStillLoad() {
        val models = listOf(
            ObjLoader.parse(ObjTestFixtures.Colored),
            ObjLoader.parse(ObjTestFixtures.Colored) { null }
        )
        for (model in models) {
            assertEquals(2, model.triangleCount)
            assertTrue(model.materials.isEmpty())
            assertTrue(ObjLoader.toGlb(model).isNotEmpty())
        }
    }

    @Test
    fun repeatedGroupNamesMergeWithinObjectsButNotBetweenObjects() {
        val source = ObjTestFixtures.Triangle.substringBefore("f ") + """
            o one
            g shared
            f 1 2 3
            g other
            f 1 2 3
            g shared
            f 1 2 3
            o two
            g shared
            f 1 2 3
        """.trimIndent()
        val groups = ObjLoader.parse(source.encodeToByteArray()).groups
        assertEquals(listOf("one/shared", "one/other", "two/shared"), groups.map { it.name })
        assertEquals(listOf(2, 1, 1), groups.map { it.triangleCount })
    }

    @Test
    fun commentsBomTabsCrLfAndLineContinuation() {
        val source = "\uFEFF# exporter\r\n\r\nv\t0 0 0 # origin\r\nv 2 0 0\rv 0 3 0\nf 1 \\\n2 3"
        assertEquals(1, ObjLoader.parse(source.encodeToByteArray()).triangleCount)
    }

    @Test
    fun concavePolygonPreservesAreaAndWindingInEveryAxisPlane() {
        // U-shaped polygon: 3*3 minus a 1*2 notch = area 7, eight corners = six triangles.
        val points = listOf(0 to 0, 3 to 0, 3 to 3, 2 to 3, 2 to 1, 1 to 1, 1 to 3, 0 to 3)
        for (drop in 0 until 3) for (reverse in listOf(false, true)) {
            val u = (drop + 1) % 3
            val v = (drop + 2) % 3
            val order = if (reverse) points.reversed() else points
            val source = order.joinToString("\n") { (x, y) ->
                val xyz = IntArray(3)
                xyz[u] = x
                xyz[v] = y
                "v ${xyz.joinToString(" ") }"
            } + "\nf 1 2 3 4 5 6 7 8"
            val group = ObjLoader.parse(source.encodeToByteArray()).groups.single()
            assertEquals(6, group.triangleCount)
            var area = 0f
            val p = group.positions
            for (at in p.indices step 9) {
                val cross = (p[at + 3 + u] - p[at + u]) * (p[at + 6 + v] - p[at + v]) -
                    (p[at + 3 + v] - p[at + v]) * (p[at + 6 + u] - p[at + u])
                assertTrue(if (reverse) cross < 0 else cross > 0)
                area += cross / 2
            }
            assertEquals(7f, abs(area))
        }
    }

    @Test
    fun invalidIndicesAndNonFinitePositionsAreRejected() {
        val vertices = ObjTestFixtures.Triangle.substringBefore("f ")
        for (face in listOf("0 2 3", "-4 2 3", "4 2 3", "1/1 2 3", "1//1 2 3", "1/ 2 3", "1 2")) {
            assertFailsWith<ObjParseException>(face) { ObjLoader.parse((vertices + "f $face").encodeToByteArray()) }
        }
        assertFailsWith<ObjParseException> {
            ObjLoader.parse(ObjTestFixtures.Triangle.replace("v 0", "v NaN").encodeToByteArray())
        }
        assertFailsWith<ObjParseException> { ObjLoader.parse("# empty".encodeToByteArray()) }
    }

    @Test
    fun degenerateTrianglesHaveFiniteFallbackNormals() {
        val group = ObjLoader.parse("v 0 0 0\nf 1 1 1".encodeToByteArray()).groups.single()
        assertContentEquals(floatArrayOf(0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f, 0f), group.normals)
    }

    @Test
    fun multipleLibrariesQuotedPathsAndLastOpacityWin() {
        val source = "mtllib first.mtl \"sub dir/second.mtl\"\n" + ObjTestFixtures.Triangle
        val requests = ArrayList<String>()
        val model = ObjLoader.parse(source.encodeToByteArray()) {
            requests += it
            "newmtl paint\nKd 0.2 0.4 0.6\nd 0.7\nTr 0.8\nd -halo 0.3".encodeToByteArray()
        }
        assertEquals(listOf("first.mtl", "sub dir/second.mtl"), requests)
        assertContentEquals(
            floatArrayOf(0.2f, 0.4f, 0.6f, 0.3f),
            assertNotNull(model.materials["paint"]).baseColorFactor
        )
    }

    @Test
    fun sniffIsConservativeBoundedAndAcceptsCommentsAndBom() {
        assertTrue(ObjLoader.isObj(ObjTestFixtures.Cube))
        assertTrue(ObjLoader.isObj(("\uFEFF# comment\r\n\r\n" + ObjTestFixtures.Triangle).encodeToByteArray()))
        assertTrue(ObjLoader.isObj(ObjTestFixtures.Triangle.replace("v ", "v\t").encodeToByteArray()))
        val rejected = listOf(
            "", "f 1 2 3\nv 0 0 0", "vn 0 0 1\nf 1 2 3", "v nope\nf 1 2 3",
            "{\n\"asset\":{},\n v 0 0 0\nf 1 2 3\n}", "glTF\nv 0 0 0\nf 1 2 3",
            "#" + "x".repeat(4096) + "\n" + ObjTestFixtures.Triangle
        )
        for (source in rejected) {
            assertFalse(ObjLoader.isObj(source.encodeToByteArray()), source.take(60))
        }
        val prefix = ObjTestFixtures.Triangle.substringBefore("f ")
        val partial = prefix + "#" + "x".repeat(4096 - prefix.length - 7) + "\nf 1 2 3"
        assertTrue(partial.length > 4096)
        assertFalse(ObjLoader.isObj(partial.encodeToByteArray()), "never accept a truncated face line")
        assertFalse(ObjLoader.isObj(ObjLoader.toGlb(ObjTestFixtures.Cube)))
    }
}

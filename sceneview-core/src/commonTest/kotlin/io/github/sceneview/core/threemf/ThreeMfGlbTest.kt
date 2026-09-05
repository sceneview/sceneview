package io.github.sceneview.core.threemf

import io.github.sceneview.core.splat.readLe32
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The GLB these tests assert on is what every SceneView renderer actually loads, so the checks are
 * the ones a renderer would fail on: container framing, chunk alignment, vertex counts, and the two
 * conversions (millimetres → metres, Z-up → Y-up) that decide whether a print shows up at the right
 * size and the right way up.
 */
class ThreeMfGlbTest {

    @Test
    fun writesAWellFormedGlbContainer() {
        val glb = ThreeMfLoader.toGlb(ThreeMfTestFixtures.CubeThreeMf)

        assertEquals(GlbMagic, readLe32(glb, 0), "GLB magic \"glTF\"")
        assertEquals(2, readLe32(glb, 4), "glTF 2.0")
        assertEquals(glb.size, readLe32(glb, 8), "header length matches the real file size")

        val jsonLength = readLe32(glb, 12)
        assertEquals(JsonChunk, readLe32(glb, 16))
        assertEquals(0, jsonLength % 4, "the JSON chunk is 4-byte aligned")
        val binLength = readLe32(glb, 20 + jsonLength)
        assertEquals(BinChunk, readLe32(glb, 24 + jsonLength))
        assertEquals(0, binLength % 4, "the BIN chunk is 4-byte aligned")
        assertEquals(glb.size, 28 + jsonLength + binLength, "the two chunks fill the file exactly")
    }

    @Test
    fun deIndexesEveryTriangleWithItsOwnFlatNormal() {
        val json = jsonOf(ThreeMfLoader.toGlb(ThreeMfTestFixtures.CubeThreeMf))

        // 12 triangles split into two colour groups of 6 → 18 de-indexed vertices per primitive.
        assertContains(json, """"count":18""")
        assertContains(json, """"POSITION"""")
        assertContains(json, """"NORMAL"""")
    }

    @Test
    fun onePrimitiveAndOneMaterialPerColor() {
        val json = jsonOf(ThreeMfLoader.toGlb(ThreeMfTestFixtures.CubeThreeMf))

        assertEquals(2, json.occurrencesOf(""""attributes":"""), "one primitive per 3MF colour")
        assertEquals(2, json.occurrencesOf(""""pbrMetallicRoughness""""))
        assertContains(json, """"doubleSided":true""")
    }

    @Test
    fun rootNodeConvertsMillimetresToMetresAndZUpToYUp() {
        val json = jsonOf(ThreeMfLoader.toGlb(ThreeMfTestFixtures.CubeThreeMf))

        // Column-major: X stays X at 1 mm = 0.001 m, 3MF's +Y becomes glTF's −Z, +Z becomes +Y.
        assertContains(json, """"matrix":[0.001,0,0,0,0,0,-0.001,0,0,0.001,0,0,0,0,0,1]""")
    }

    @Test
    fun aUnitlessTinyModelStillCarriesItsBuildTranslation() {
        val json = jsonOf(ThreeMfLoader.toGlb(ThreeMfTestFixtures.CubeThreeMf))

        // The build item's 5 mm offset stays in model units under the scaling root node.
        assertContains(json, """"matrix":[1,0,0,0,0,1,0,0,0,0,1,0,5,0,0,1]""")
    }

    @Test
    fun positionAccessorsCarryTheBoundsGltfRequires() {
        val json = jsonOf(ThreeMfLoader.toGlb(ThreeMfTestFixtures.CubeThreeMf))

        assertContains(json, """"min":[0,0,0]""")
        assertContains(json, """"max":[20,20,20]""")
    }

    @Test
    fun anUncoloredModelGetsOneDefaultMaterial() {
        val threeMf = ThreeMfTestFixtures.threeMfOf(ThreeMfTestFixtures.modelXml())
        val json = jsonOf(ThreeMfLoader.toGlb(threeMf))

        assertEquals(1, json.occurrencesOf(""""pbrMetallicRoughness""""))
        assertContains(json, """"count":3""", message = "one triangle → three de-indexed vertices")
    }

    @Test
    fun assembliesBecomeNestedNodesSharingOneMesh() {
        val xml = """<model unit="meter">
              <resources>
                <object id="1"><mesh>
                  <vertices><vertex x="0" y="0" z="0"/><vertex x="1" y="0" z="0"/>
                    <vertex x="0" y="1" z="0"/></vertices>
                  <triangles><triangle v1="0" v2="1" v3="2"/></triangles>
                </mesh></object>
                <object id="2"><components>
                  <component objectid="1" transform="1 0 0 0 1 0 0 0 1 3 0 0"/>
                  <component objectid="1" transform="1 0 0 0 1 0 0 0 1 0 3 0"/>
                </components></object>
              </resources>
              <build><item objectid="2"/></build>
            </model>"""
        val json = jsonOf(ThreeMfLoader.toGlb(ThreeMfTestFixtures.threeMfOf(xml)))

        assertEquals(1, json.occurrencesOf(""""primitives""""), "the instanced mesh is stored once")
        assertEquals(2, json.occurrencesOf(""""mesh":0"""), "both components reference it")
        assertContains(json, """"children"""")
    }

    @Test
    fun degenerateTrianglesDoNotProduceNaNNormals() {
        val xml = """<model unit="millimeter"><resources><object id="1"><mesh>
            <vertices><vertex x="0" y="0" z="0"/><vertex x="0" y="0" z="0"/>
              <vertex x="0" y="0" z="0"/></vertices>
            <triangles><triangle v1="0" v2="1" v3="2"/></triangles>
            </mesh></object></resources><build><item objectid="1"/></build></model>"""
        val json = jsonOf(ThreeMfLoader.toGlb(ThreeMfTestFixtures.threeMfOf(xml)))

        assertTrue("NaN" !in json, "a zero-area face must not poison the bounds with NaN")
    }

    private fun jsonOf(glb: ByteArray): String {
        val length = readLe32(glb, 12)
        return glb.decodeToString(20, 20 + length)
    }

    private fun String.occurrencesOf(token: String): Int =
        split(token).size - 1

    private companion object {
        const val GlbMagic = 0x46546C67
        const val JsonChunk = 0x4E4F534A
        const val BinChunk = 0x004E4942
    }
}

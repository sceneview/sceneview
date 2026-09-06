package io.github.sceneview.core.threemf

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ThreeMfLoaderTest {

    // ── The real, deflate-compressed file ────────────────────────────────────────────────────────

    @Test
    fun parsesARealDeflatedThreeMf() {
        val model = ThreeMfLoader.parse(ThreeMfTestFixtures.CubeThreeMf)

        assertEquals(ThreeMfUnit.MILLIMETER, model.unit)
        assertEquals(1, model.objects.size)
        assertEquals(12, model.triangleCount)
        val mesh = assertNotNull(model.objects.single().mesh)
        assertEquals(8, mesh.vertexCount)
        // The cube spans 0..20 mm on every axis.
        assertEquals(0f, mesh.positions.min())
        assertEquals(20f, mesh.positions.max())
    }

    @Test
    fun readsBuildItemTransform() {
        val item = ThreeMfLoader.parse(ThreeMfTestFixtures.CubeThreeMf).items.single()

        assertEquals(1, item.objectId)
        // "1 0 0 0 1 0 0 0 1 5 0 0" — the translation lands in the 4th column of a column-major
        // matrix, which is exactly where glTF reads it from.
        assertEquals(5f, item.transform[12])
        assertEquals(0f, item.transform[13])
        assertEquals(1f, item.transform[15])
    }

    @Test
    fun resolvesPerTrianglePaletteColors() {
        val mesh = assertNotNull(ThreeMfLoader.parse(ThreeMfTestFixtures.CubeThreeMf).objects.single().mesh)
        val colors = assertNotNull(mesh.triangleColors)

        assertEquals(12, colors.size)
        assertEquals(0x0FB5AEFF, colors[0], "first six triangles use base 0 (teal)")
        assertEquals(0xFFB300FF.toInt(), colors[11], "last six use base 1 (amber)")
    }

    @Test
    fun detectsThreeMfBytes() {
        assertTrue(ThreeMfLoader.isThreeMf(ThreeMfTestFixtures.CubeThreeMf))
        assertFalse(ThreeMfLoader.isThreeMf("not a zip at all".encodeToByteArray()))
        assertFalse(ThreeMfLoader.isThreeMf(ByteArray(0)))
        assertFalse(
            ThreeMfLoader.isThreeMf(ThreeMfTestFixtures.zipOf("readme.txt" to "a plain zip")),
            "a ZIP without a 3D/*.model part is not a 3MF"
        )
    }

    // ── Units, axes, structure ───────────────────────────────────────────────────────────────────

    @Test
    fun readsEveryDeclaredUnit() {
        for (unit in ThreeMfUnit.entries) {
            val xml = ThreeMfTestFixtures.modelXml(attributes = """unit="${unit.id}"""")
            assertEquals(unit, ThreeMfLoader.parse(ThreeMfTestFixtures.threeMfOf(xml)).unit)
        }
    }

    @Test
    fun defaultsToMillimetresWhenUnitIsAbsentOrUnknown() {
        val noUnit = ThreeMfTestFixtures.modelXml(attributes = """xml:lang="en-US"""")
        assertEquals(ThreeMfUnit.MILLIMETER, ThreeMfLoader.parse(ThreeMfTestFixtures.threeMfOf(noUnit)).unit)

        val bogus = ThreeMfTestFixtures.modelXml(attributes = """unit="parsecs"""")
        assertEquals(ThreeMfUnit.MILLIMETER, ThreeMfLoader.parse(ThreeMfTestFixtures.threeMfOf(bogus)).unit)
    }

    @Test
    fun aFileWithNoBuildStillShowsItsObjects() {
        val xml = ThreeMfTestFixtures.modelXml(build = "")
        val model = ThreeMfLoader.parse(ThreeMfTestFixtures.threeMfOf(xml))

        assertEquals(listOf(1), model.items.map { it.objectId })
    }

    @Test
    fun readsComponentAssemblies() {
        val xml = """<?xml version="1.0"?>
            <model unit="centimeter">
              <resources>
                <object id="1"><mesh>
                  <vertices><vertex x="0" y="0" z="0"/><vertex x="1" y="0" z="0"/>
                    <vertex x="0" y="1" z="0"/></vertices>
                  <triangles><triangle v1="0" v2="1" v3="2"/></triangles>
                </mesh></object>
                <object id="2">
                  <components>
                    <component objectid="1" transform="1 0 0 0 1 0 0 0 1 3 4 5"/>
                    <component objectid="1"/>
                  </components>
                </object>
              </resources>
              <build><item objectid="2"/></build>
            </model>"""
        val model = ThreeMfLoader.parse(ThreeMfTestFixtures.threeMfOf(xml))

        assertEquals(ThreeMfUnit.CENTIMETER, model.unit)
        val assembly = assertNotNull(model.objectsById[2])
        assertNull(assembly.mesh, "an assembly object carries no mesh of its own")
        assertEquals(2, assembly.components.size)
        assertEquals(3f, assembly.components[0].transform[12])
        assertEquals(0f, assembly.components[1].transform[12], "a missing transform is the identity")
    }

    @Test
    fun readsMaterialExtensionColorGroups() {
        val xml = ThreeMfTestFixtures.modelXml(
            extra = """<m:colorgroup id="9"><m:color color="#FF0000"/><m:color color="#00FF00"/></m:colorgroup>""",
            objectAttributes = """pid="9" pindex="1""""
        )
        val model = ThreeMfLoader.parse(ThreeMfTestFixtures.threeMfOf(xml))

        assertEquals(0x00FF00FF, model.objects.single().color, "prefixed m:color is read like any other")
    }

    // ── Failure modes ────────────────────────────────────────────────────────────────────────────

    @Test
    fun rejectsSomethingThatIsNotAZip() {
        val failure = assertFailsWith<ThreeMfParseException> {
            ThreeMfLoader.parse("<model/>".encodeToByteArray())
        }
        assertContains(failure.message.orEmpty(), "ZIP")
    }

    @Test
    fun rejectsAZipWithoutAModelPart() {
        val failure = assertFailsWith<ThreeMfParseException> {
            ThreeMfLoader.parse(ThreeMfTestFixtures.zipOf("hello.txt" to "world"))
        }
        assertContains(failure.message.orEmpty(), "3D/3dmodel.model")
    }

    @Test
    fun rejectsAnOutOfRangeTriangleIndex() {
        val xml = """<model unit="millimeter"><resources><object id="1"><mesh>
            <vertices><vertex x="0" y="0" z="0"/></vertices>
            <triangles><triangle v1="0" v2="7" v3="2"/></triangles>
            </mesh></object></resources><build><item objectid="1"/></build></model>"""
        val failure = assertFailsWith<ThreeMfParseException> {
            ThreeMfLoader.parse(ThreeMfTestFixtures.threeMfOf(xml))
        }
        assertContains(failure.message.orEmpty(), "out of range")
    }

    @Test
    fun rejectsAFileWithNoObjects() {
        val xml = """<model unit="millimeter"><resources/><build/></model>"""
        assertFailsWith<ThreeMfParseException> {
            ThreeMfLoader.parse(ThreeMfTestFixtures.threeMfOf(xml))
        }
    }

    @Test
    fun skipsUnknownExtensionsInsteadOfFailing() {
        // A production/slice-extension file must still preview: unknown elements are ignored.
        val xml = ThreeMfTestFixtures.modelXml(
            extra = """<s:slicestack id="4" zbottom="0"><s:slice ztop="1"/></s:slicestack>"""
        )
        assertEquals(1, ThreeMfLoader.parse(ThreeMfTestFixtures.threeMfOf(xml)).triangleCount)
    }
}

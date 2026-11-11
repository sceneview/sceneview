package io.github.sceneview.demo.demos.internal.feel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class MatterCaptionTest {
    private val resources: File get() = sequenceOf(File("src/main/res"), File("samples/android-demo/src/main/res"))
        .first { it.isDirectory }

    private fun strings(file: File): Map<String, String> {
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("string")
        return (0 until nodes.length).associate {
            nodes.item(it).attributes.getNamedItem("name").nodeValue to nodes.item(it).textContent
        }
    }

    @Test fun restShowsCharacterAndOnlyContactOrVisibleRecoveryShowsMillimetres() {
        for (material in FeelMaterial.entries) {
            assertEquals(false, MatterSurfaces.showsDisplacement(material, Indentation(), pressed = false))
            assertEquals(!material.rigid, MatterSurfaces.showsDisplacement(material, Indentation(), pressed = true))
            assertEquals(!material.rigid, MatterSurfaces.showsDisplacement(material, Indentation(1f), pressed = false))
        }
        assertTrue(MatterSurfaces.showsDisplacement(FeelMaterial.Gel, Indentation(0f, -2f), false))
        assertTrue(MatterSurfaces.showsDisplacement(FeelMaterial.Gel, Indentation(-1f), false))
        assertEquals(false, MatterSurfaces.showsDisplacement(FeelMaterial.MemoryFoam, Indentation(.001f), false))
    }

    @Test fun everyExistingMatterLocaleHasSixNamesCharacterCaptionsAndMillimetreReadouts() {
        val base = strings(File(resources, "values/strings_demo_feel.xml"))
        val required = base.keys
        val files = resources.walkTopDown().filter { it.extension == "xml" && it.readText().contains("name=\"demo_feel_") }.toList()
        assertTrue(files.isNotEmpty())
        for (file in files) {
            val translated = strings(file)
            assertTrue("Missing Matter strings in $file", translated.keys.containsAll(required))
            for (key in listOf("foam_rest", "gel_rest", "velvet_rest", "leather_rest", "rigid_rest")) {
                val value = translated.getValue("demo_feel_$key")
                assertTrue(value.contains("·"))
                assertTrue(!value.contains("mm") && !value.contains("%"))
            }
            for (key in listOf("foam_press", "gel_response", "gel_rebound", "smooth_response")) {
                val value = translated.getValue("demo_feel_$key")
                assertTrue(value.contains("%1\$d"))
                assertTrue(value.contains("mm"))
                assertTrue(value.contains("·"))
            }
        }
        assertEquals(listOf("Memory foam", "Gel", "Velvet", "Leather", "Marble", "Brushed steel"),
            listOf("foam", "gel", "velvet", "leather", "marble", "steel").map { base["demo_feel_$it"] })
        assertEquals("Does not move", base["demo_feel_rigid_response"])
        assertTrue(base.values.none { it.contains("Oak") })
    }
}

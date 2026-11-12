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
            assertEquals(false, MatterSurfaces.showsDisplacement(material, Indentation(), pressed = true))
            assertEquals(false, MatterSurfaces.showsDisplacement(material, Indentation(1f), pressed = false))
            assertEquals(!material.rigid, MatterSurfaces.showsDisplacement(material, Indentation(1f), pressed = true))
            assertEquals(!material.rigid, MatterSurfaces.showsDisplacement(material, Indentation(2f), pressed = false))
        }
        assertEquals(false, MatterSurfaces.showsDisplacement(FeelMaterial.Gel, Indentation(0f, -2f), false))
        assertTrue(MatterSurfaces.showsDisplacement(FeelMaterial.Gel, Indentation(-2f), false))
        assertEquals(false, MatterSurfaces.showsDisplacement(FeelMaterial.MemoryFoam, Indentation(.001f), false))
    }

    @Test fun openingRecoveryAndZeroCrossingsNeverSelectAZeroMillimetreCaption() {
        val simulation = FeelSimulation(openingEnabled = true)
        for (now in 0L..12000L step 16L) {
            simulation.advance(now)
            val state = simulation.pads[0]
            if (state.depthMm < 2f) {
                assertEquals(false, MatterSurfaces.showsDisplacement(FeelMaterial.MemoryFoam, state, false))
            }
        }
        for (depth in listOf(-.3f, 0f, .3f)) for (pressed in listOf(false, true)) {
            assertEquals(false, MatterSurfaces.showsDisplacement(FeelMaterial.Gel, Indentation(depth, 4f), pressed))
        }
    }

    @Test fun contactCaptionUsesTheShadersAmplifiedPeakInSceneMillimetres() {
        for (material in FeelMaterial.entries) for (depth in listOf(.1f, .5f, 1f, 3f, 12f, 24f)) {
            val expected = MatterSurfaces.visualDepth(depth, material) / FeelMaterial.WORLD_PER_MM
            assertEquals(expected, MatterSurfaces.captionDepthMm(depth, material), .00001f)
            assertEquals(!material.rigid && expected >= .5f,
                MatterSurfaces.showsDisplacement(material, Indentation(depth), true))
        }
        assertEquals(14f, MatterSurfaces.captionDepthMm(2.4f, FeelMaterial.MemoryFoam), .1f)
        assertEquals(3f, MatterSurfaces.captionDepthMm(1f, FeelMaterial.Leather), .00001f)
        assertEquals(29.64f, MatterSurfaces.captionDepthMm(100f, FeelMaterial.MemoryFoam), 1f)
    }

    @Test fun shallowFoamContactReportsADeeperOldTroughInsteadOfTheNewSmallWell() {
        val simulation = FeelSimulation()
        simulation.begin(FeelMaterial.MemoryFoam, -1f, 0f, .1f, 0f, 0)
        simulation.pads[0] = Indentation(.05f)
        val trail = simulation.trails[0]
        // A far-away deep, overlapping trace has the shader's bounded union depth.
        for (slot in 0..2) {
            trail[slot * 4] = 1f
            trail[slot * 4 + 1] = 0f
            trail[slot * 4 + 2] = 1f
            trail[slot * 4 + 3] = 1f / (.46f * .46f)
        }
        val expected = .22f * (1f - kotlin.math.exp(-3f)) / FeelMaterial.WORLD_PER_MM
        val actual = simulation.captionDepthMm(FeelMaterial.MemoryFoam)
        assertEquals(expected, actual, .2f) // The main well's raised rim slightly lifts the old groove.
        assertTrue(actual > MatterSurfaces.captionDepthMm(.05f, FeelMaterial.MemoryFoam) * 10f)
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

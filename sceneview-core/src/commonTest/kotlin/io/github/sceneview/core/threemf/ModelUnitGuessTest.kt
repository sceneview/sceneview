package io.github.sceneview.core.threemf

import io.github.sceneview.core.stl.StlLoader
import io.github.sceneview.core.stl.StlTestFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** #3543 — a unit-less mesh a few units across is metre-authored, not a 2 mm part. */
class ModelUnitGuessTest {

    @Test
    fun aFewUnitsAcrossReadsAsMetres() {
        assertEquals(ThreeMfUnit.METER, ModelUnitGuess.suggest(2f))
        assertEquals(ThreeMfUnit.METER, ModelUnitGuess.suggest(0.05f))
        assertEquals(ThreeMfUnit.METER, ModelUnitGuess.suggest(50f))
    }

    @Test
    fun printableAndDegenerateExtentsKeepTheMillimetreDefault() {
        // 70 units is the everyday printed part: 70 mm, and nothing is offered.
        assertNull(ModelUnitGuess.suggest(70f))
        assertNull(ModelUnitGuess.suggest(50.001f))
        assertNull(ModelUnitGuess.suggest(0.049f))
        assertNull(ModelUnitGuess.suggest(0f))
        assertNull(ModelUnitGuess.suggest(Float.NaN))
        assertNull(ModelUnitGuess.suggest(Float.POSITIVE_INFINITY))
    }

    @Test
    fun theQuestionIsNeverAskedAboutTheUnitAlreadyLoaded() {
        // 2 m loaded as metres: already the suggestion, so no re-open is offered.
        assertNull(ModelUnitGuess.suggestFromLoaded(2f, ThreeMfUnit.METER))
        // The same file loaded as millimetres is 2 mm on screen — that one is worth asking about.
        assertEquals(ThreeMfUnit.METER, ModelUnitGuess.suggestFromLoaded(0.002f, ThreeMfUnit.MILLIMETER))
        assertNull(ModelUnitGuess.suggestFromLoaded(0.07f, ThreeMfUnit.MILLIMETER))
    }

    @Test
    fun theTwoUnitStlOfTheIssueIsOfferedInMetresAndOpensAtTwoMetres() {
        // 70 x 40 x 30 scaled by 1/35 → 2 x 1.14 x 0.86 units, an export authored in metres.
        val stl = StlTestFixtures.binary(StlTestFixtures.boxOf(1f / 35f))

        // As loaded today: millimetres, so 2 mm across — the "near-invisible dot" of #3543.
        val asMillimetres = StlLoader.parse(stl)
        assertEquals(0.002f, asMillimetres.maxExtentMeters(), 0.00001f)
        assertEquals(
            ThreeMfUnit.METER,
            ModelUnitGuess.suggestFromLoaded(asMillimetres.maxExtentMeters(), ThreeMfUnit.MILLIMETER)
        )

        // Taking the offer opens the same bytes at two metres.
        val asMetres = StlLoader.parse(stl, ThreeMfUnit.METER)
        assertEquals(2f, asMetres.maxExtentMeters(), 0.0001f)
        assertNull(ModelUnitGuess.suggestFromLoaded(asMetres.maxExtentMeters(), ThreeMfUnit.METER))
    }
}

/** Longest side of the parsed bounding box, in metres. */
private fun io.github.sceneview.core.stl.StlModel.maxExtentMeters(): Float {
    var extent = 0f
    repeat(3) { axis ->
        var min = Float.MAX_VALUE
        var max = -Float.MAX_VALUE
        for (vertex in 0 until positions.size / 3) {
            val value = positions[vertex * 3 + axis]
            min = minOf(min, value)
            max = maxOf(max, value)
        }
        extent = maxOf(extent, (max - min) * unit.meters)
    }
    return extent
}

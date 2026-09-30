package io.github.sceneview.demo.ui.home

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The column arithmetic of the home list and the colour each row takes from its picture. */
class HomeListRowTest {

    @Test
    fun a_phone_gets_one_column_and_a_wide_tablet_several() {
        assertEquals(1, homeListColumns(360))
        assertEquals(1, homeListColumns(411))
        assertEquals(1, homeListColumns(600))
        assertEquals(2, homeListColumns(840))
        assertEquals(3, homeListColumns(1280))
    }

    @Test
    fun the_ambient_tint_lands_on_the_scheme_luminance_whatever_the_picture() {
        val seeds = listOf(Color.White, Color.Black, Color(0xFFE2734F), Color(0xFF1E3A8A), Color(0xFFFFEB3B))
        seeds.forEach { seed ->
            val dark = ambientTint(seed, dark = true)
            val light = ambientTint(seed, dark = false)
            assertEquals(AMBIENT_LUMINANCE_DARK, luminance(Triple(dark.red, dark.green, dark.blue)), 0.002f)
            assertEquals(AMBIENT_LUMINANCE_LIGHT, luminance(Triple(light.red, light.green, light.blue)), 0.002f)
        }
    }

    @Test
    fun text_keeps_its_contrast_on_every_tint() {
        val onSurfaceDark = Color(0xFFF3F4F6)
        val onSurfaceVariantDark = Color(0xFFA4ABB7)
        val onSurfaceVariantLight = Color(0xFF3D4654)
        listOf(Color.White, Color(0xFFE2734F), Color(0xFF1E3A8A), Color(0xFF00C853)).forEach { seed ->
            val dark = ambientTint(seed, dark = true)
            val light = ambientTint(seed, dark = false)
            assertTrue(contrast(onSurfaceDark, dark) >= 7f)
            assertTrue(contrast(onSurfaceVariantDark, dark) >= 4.5f)
            assertTrue(contrast(onSurfaceVariantLight, light) >= 4.5f)
        }
    }

    @Test
    fun a_coloured_subject_on_a_grey_floor_reads_as_its_colour() {
        // Nine grey pixels and one orange one: the chroma weighting keeps the hue.
        val grey = 0xFF808080.toInt()
        val orange = 0xFFFF7A1A.toInt()
        val seed = ambientSeed(IntArray(9) { grey } + orange)
        assertTrue(seed.red > seed.blue + 0.05f)
    }

    private fun contrast(a: Color, b: Color): Float {
        val la = luminance(Triple(a.red, a.green, a.blue))
        val lb = luminance(Triple(b.red, b.green, b.blue))
        return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
    }
}

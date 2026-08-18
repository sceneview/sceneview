package io.github.sceneview.demo

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.sceneview.demo.theme.SceneViewDemoTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `background` must be the same tone as `surface` in both schemes.
 *
 * ## The regression this pins (#3237)
 *
 * Material3's `Scaffold` paints its container with `colorScheme.background`, so
 * that role — not `surface` — is what fills the strip behind the status bar on
 * every screen of this app. `lightColorScheme()` / `darkColorScheme()` default
 * every argument you omit to the Material3 **baseline** palette, and `background`
 * was the one surface role this app never passed. It therefore drew `#FEF7FF`
 * light and `#141218` dark, against a page of `#F9F9FF` / `#111318`.
 *
 * Five levels in the red channel is not a colour anyone would notice in a swatch,
 * and is very much a line when it runs the full width of the display. Device QA
 * measured it on a Pixel 7a: rows 0-130 `#fef7ff`, rows 131+ `#f9f9ff`.
 *
 * ## Why this test exists rather than a bigger comment
 *
 * The defect is a *defaulted parameter in a 30-line argument list where every
 * other role is spelled out*, which is the least visible place in Compose for a
 * wrong colour to sit. Nothing catches it: it compiles, it lints clean, the
 * snapshot goldens were recorded with the seam already in them, and it survived
 * two wrong diagnoses — Material You (it survived `dynamicColor = false`) and the
 * XML theme's missing `colorSurface` (binding that changed nothing on screen).
 *
 * The assertion is deliberately relational — `background == surface` — rather
 * than a hardcoded hex. Palette tokens get re-generated; the invariant that the
 * two roles agree is what actually keeps the seam away, and it survives a
 * re-generation that a literal would not.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DemoBackgroundRoleTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `light background is the light surface, and not the M3 baseline`() {
        assertRoles(dark = false, baseline = 0xFFFEF7FF.toInt())
    }

    @Test
    fun `dark background is the dark surface, and not the M3 baseline`() {
        assertRoles(dark = true, baseline = 0xFF141218.toInt())
    }

    private fun assertRoles(dark: Boolean, baseline: Int) {
        var background = Color.Unspecified
        var surface = Color.Unspecified
        var onBackground = Color.Unspecified
        var onSurface = Color.Unspecified
        rule.setContent {
            SceneViewDemoTheme(darkTheme = dark, dynamicColor = false) {
                background = MaterialTheme.colorScheme.background
                surface = MaterialTheme.colorScheme.surface
                onBackground = MaterialTheme.colorScheme.onBackground
                onSurface = MaterialTheme.colorScheme.onSurface
            }
        }
        rule.waitForIdle()

        assertEquals(
            "background must be the same tone as surface — Scaffold paints the " +
                "status-bar strip with background, the page with surface",
            surface.toArgb(),
            background.toArgb(),
        )
        assertEquals(
            "onBackground must follow onSurface for the same reason",
            onSurface.toArgb(),
            onBackground.toArgb(),
        )
        assertNotEquals(
            "background fell through to the Material3 baseline — the scheme is not " +
                "passing it",
            baseline,
            background.toArgb(),
        )
    }
}

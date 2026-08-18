package io.github.sceneview.demo

import android.util.TypedValue
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the XML theme to the same palette Compose draws with.
 *
 * ## The regression this pins (#3237)
 *
 * The demo app carries its palette twice: once in Kotlin, for everything Compose
 * draws, and once in `res/values/colors.xml`, for everything the *platform* draws —
 * the window background, and so the strip behind the status bar before and beneath
 * the first Compose frame.
 *
 * Only the Kotlin copy was complete. `themes.xml` bound four colour roles and never
 * `colorSurface`, so the platform fell through to the Material3 **baseline** —
 * `#FEF7FF` light, `#141218` dark — while every Compose surface used `#F9F9FF` /
 * `#111318`. Device QA measured the result as a five-level seam across the top of
 * every screen, and it was twice misdiagnosed as Material You: it survived
 * `dynamicColor = false` because it had never come from the wallpaper. It came from
 * a colour that existed in `colors.xml` and was named by nobody.
 *
 * A palette entry no style binds is not a palette entry, and nothing in the build
 * says so: unused colour resources do not warn, and a snapshot test renders Compose
 * only, so it never sees the window at all. Hence this test rather than a gate — the
 * assertion needs a resolved theme, which only a device or Robolectric can give.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DemoXmlThemePaletteTest {

    @Test
    fun `light theme binds colorSurface to the SceneView surface, not the M3 baseline`() {
        assertSurfaceBinding(expected = 0xFFF9F9FF.toInt(), baseline = 0xFFFEF7FF.toInt())
    }

    @Test
    @Config(qualifiers = "night")
    fun `dark theme binds colorSurface to the SceneView surface, not the M3 baseline`() {
        assertSurfaceBinding(expected = 0xFF111318.toInt(), baseline = 0xFF141218.toInt())
    }

    @Test
    fun `the window background is the same token as colorSurface`() {
        // The seam was visible *before the first Compose frame*, which is drawn by the
        // window, not by Compose. Binding colorSurface alone would leave that flash.
        val theme = themeOf()
        val surface = resolveColor(theme, com.google.android.material.R.attr.colorSurface)
        val window = resolveColor(theme, android.R.attr.windowBackground)
        assertEquals(
            "android:windowBackground must resolve to the same colour as colorSurface",
            surface,
            window,
        )
    }

    private fun assertSurfaceBinding(expected: Int, baseline: Int) {
        val actual = resolveColor(themeOf(), com.google.android.material.R.attr.colorSurface)
        assertTrue(
            "colorSurface resolved to the Material3 baseline — themes.xml is not binding it",
            actual != baseline,
        )
        assertEquals("colorSurface must be the SceneView surface token", expected, actual)
    }

    private fun themeOf(): android.content.res.Resources.Theme {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        return context.resources.newTheme().apply {
            applyStyle(R.style.Theme_SceneViewDemo, /* force = */ true)
        }
    }

    private fun resolveColor(
        theme: android.content.res.Resources.Theme,
        attr: Int,
    ): Int {
        val value = TypedValue()
        assertTrue(
            "attribute 0x${attr.toString(16)} is not defined by Theme.SceneViewDemo",
            theme.resolveAttribute(attr, value, /* resolveRefs = */ true),
        )
        return value.data
    }
}

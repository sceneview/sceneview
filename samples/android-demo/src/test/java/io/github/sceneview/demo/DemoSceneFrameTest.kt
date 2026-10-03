package io.github.sceneview.demo

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * [demoSceneFrame] in a real composition (#4326): which cover goes to `contentPadding`, which one
 * the camera is fitted for, and what a phone held sideways changes — read from the window the
 * way the demos read it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DemoSceneFrameTest {

    @get:Rule
    val composeRule = createComposeRule()

    // What the scaffold reports: the title row and the controls at rest, then with the settings
    // sheet up.
    private val resting = PaddingValues(top = 80.dp, bottom = 100.dp)
    private val underTheSheet = PaddingValues(top = 80.dp, bottom = 160.dp)

    private class Measured(val frame: DemoSceneFrame, val compactHeight: Boolean, val statusBar: Dp)

    /** The frame of a scene of [width] x [height] under [cover], and what the window said. */
    private fun measure(width: Dp, height: Dp, explicitCover: PaddingValues? = null): Measured {
        var measured: Measured? = null
        composeRule.setContent {
            CompositionLocalProvider(
                LocalDemoSceneCover provides underTheSheet,
                LocalDemoSceneRestingCover provides resting,
            ) {
                BoxWithConstraints(Modifier.requiredSize(width, height)) {
                    measured = Measured(
                        frame = if (explicitCover != null) demoSceneFrame(explicitCover) else demoSceneFrame(),
                        compactHeight = isDemoCompactHeight(),
                        statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding(),
                    )
                }
            }
        }
        composeRule.waitForIdle()
        return checkNotNull(measured)
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun `upright the title row is a band and the camera is fitted for the area at rest`() {
        val measured = measure(width = 400.dp, height = 800.dp)
        assertFalse(measured.compactHeight)
        assertFalse(measured.frame.compactHeight)
        // The live cover, sheet included, is what the lens projects around.
        assertEquals(80.dp, measured.frame.contentPadding.calculateTopPadding())
        assertEquals(160.dp, measured.frame.contentPadding.calculateBottomPadding())
        // The fit is for the resting cover: it does not move with the sheet.
        assertEquals(400f / (800f - 80f - 100f), measured.frame.restingAspect, 0.0001f)
    }

    @Test
    @Config(qualifiers = "w914dp-h411dp-land-xhdpi")
    fun `sideways only the status bar is kept clear at the top, in the padding and in the fit`() {
        val measured = measure(width = 800.dp, height = 400.dp)
        assertTrue(measured.compactHeight)
        assertTrue(measured.frame.compactHeight)
        assertTrue("the title row no longer counts", measured.statusBar < 80.dp)
        assertEquals(measured.statusBar, measured.frame.contentPadding.calculateTopPadding())
        assertEquals(160.dp, measured.frame.contentPadding.calculateBottomPadding())
        assertEquals(
            800f / (400f - measured.statusBar.value - 100f),
            measured.frame.restingAspect,
            0.0001f,
        )
    }

    @Test
    @Config(qualifiers = "w914dp-h411dp-land-xhdpi")
    fun `a demo with a sheet of its own hands its cover over and keeps the scaffold's fit`() {
        // The Model Viewer's Lighting sheet: the scaffold does not know about it.
        val measured = measure(width = 800.dp, height = 400.dp, explicitCover = PaddingValues(bottom = 205.dp))
        assertEquals(205.dp, measured.frame.contentPadding.calculateBottomPadding())
        assertEquals(
            800f / (400f - measured.statusBar.value - 100f),
            measured.frame.restingAspect,
            0.0001f,
        )
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-land-xhdpi")
    fun `a tablet held sideways is not a strip`() {
        val measured = measure(width = 800.dp, height = 600.dp)
        assertFalse(measured.frame.compactHeight)
        assertEquals(80.dp, measured.frame.contentPadding.calculateTopPadding())
        assertEquals(800f / (600f - 80f - 100f), measured.frame.restingAspect, 0.0001f)
    }

    @Test
    @Config(qualifiers = "w914dp-h479dp-land-xhdpi")
    fun `the strip starts under 480 dp of window height`() {
        assertTrue(measure(width = 800.dp, height = 400.dp).frame.compactHeight)
    }

    @Test
    @Config(qualifiers = "w914dp-h480dp-land-xhdpi")
    fun `a window 480 dp tall is not a strip`() {
        assertFalse(measure(width = 800.dp, height = 400.dp).frame.compactHeight)
    }
}

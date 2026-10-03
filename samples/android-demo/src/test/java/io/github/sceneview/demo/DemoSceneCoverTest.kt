package io.github.sceneview.demo

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two rules behind [LocalDemoSceneCover] (#4310): when a demo's scene may be inset by the
 * chrome's bands, and what is reported as covering it.
 *
 * Both were wrong on a phone. In portrait the settings sheet was compared to a fixed reserve it
 * never reached, so the padding handed to the camera was zero and the board stayed under the
 * glass. In landscape the two bands were taller than the window, the scene was laid out with no
 * height, and the first-frame cover ended on "stalled".
 */
class DemoSceneCoverTest {

    // Measured on a Pixel 7a at its default display size.
    private val portraitWindow = 914.dp
    private val landscapeWindow = 411.dp
    private val chromeTop = 104.dp
    private val controlsBand = 272.dp

    @Test
    fun `a portrait phone has room for a scene between the two bands`() {
        assertTrue(demoSceneReserveFits(portraitWindow, chromeTop, controlsBand))
    }

    @Test
    fun `a landscape phone does not, and the scene stays full-frame`() {
        // 411 - 104 - 272 = 35 dp: the strip that used to be the whole viewport.
        assertFalse(demoSceneReserveFits(landscapeWindow, chromeTop, controlsBand))
    }

    @Test
    fun `the reserve is kept down to the least scene height and dropped just under it`() {
        val bands = chromeTop + controlsBand
        assertTrue(demoSceneReserveFits(bands + MIN_RESERVED_SCENE_HEIGHT, chromeTop, controlsBand))
        assertFalse(demoSceneReserveFits(bands + MIN_RESERVED_SCENE_HEIGHT - 1.dp, chromeTop, controlsBand))
    }

    @Test
    fun `a window that has not been measured yet keeps the reserve`() {
        // The first composition must lay out as it always did: a slot that starts full-frame and
        // then shrinks would resize the surface on the second frame.
        assertTrue(demoSceneReserveFits(0.dp, chromeTop, controlsBand))
    }

    @Test
    fun `a full-frame scene is told both bands`() {
        val cover = demoSceneCover(
            chromeTop = chromeTop,
            chromeBottom = controlsBand,
            sheetCover = 0.dp,
            slotTop = 0.dp,
            slotBottom = 0.dp,
        )
        assertEquals(chromeTop, cover.calculateTopPadding())
        assertEquals(controlsBand, cover.calculateBottomPadding())
    }

    @Test
    fun `a sheet shorter than the controls band adds nothing to it`() {
        val cover = demoSceneCover(chromeTop, controlsBand, sheetCover = 200.dp, slotTop = 0.dp, slotBottom = 0.dp)
        assertEquals(controlsBand, cover.calculateBottomPadding())
    }

    @Test
    fun `a sheet taller than the controls band is the bottom cover`() {
        // The sheet at its peek detent on the same phone: it passes the controls by 33 dp, which
        // a reserve fixed in dp reported as zero.
        val cover = demoSceneCover(chromeTop, controlsBand, sheetCover = 305.dp, slotTop = 0.dp, slotBottom = 0.dp)
        assertEquals(305.dp, cover.calculateBottomPadding())
    }

    @Test
    fun `the cover follows the sheet all the way up, past its peek detent`() {
        val cover = demoSceneCover(chromeTop, controlsBand, sheetCover = 640.dp, slotTop = 0.dp, slotBottom = 0.dp)
        assertEquals(640.dp, cover.calculateBottomPadding())
    }

    @Test
    fun `an inset scene is told only what the sheet covers beyond its own bands`() {
        val resting = demoSceneCover(
            chromeTop, controlsBand, sheetCover = 0.dp, slotTop = chromeTop, slotBottom = controlsBand,
        )
        assertEquals(0.dp, resting.calculateTopPadding())
        assertEquals(0.dp, resting.calculateBottomPadding())

        val underSheet = demoSceneCover(
            chromeTop, controlsBand, sheetCover = 305.dp, slotTop = chromeTop, slotBottom = controlsBand,
        )
        assertEquals(0.dp, underSheet.calculateTopPadding())
        assertEquals(33.dp, underSheet.calculateBottomPadding())
    }

    @Test
    fun `the cover is never negative`() {
        val cover = demoSceneCover(
            chromeTop = 40.dp, chromeBottom = 60.dp, sheetCover = 0.dp, slotTop = 104.dp, slotBottom = 272.dp,
        )
        assertEquals(0.dp, cover.calculateTopPadding())
        assertEquals(0.dp, cover.calculateBottomPadding())
    }
}

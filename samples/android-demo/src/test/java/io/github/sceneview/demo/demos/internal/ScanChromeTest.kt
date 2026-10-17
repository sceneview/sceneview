package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [ScanChrome] (#4505): the replay's Camera card, hanging in the 3D view's top corner, and the
 * map's stats card lay over a picked object's box and over its dimensions. What the two leave
 * free for the box, and where the label goes round them.
 */
class ScanChromeTest {

    @Test
    fun `under cards across the whole width, the box and its label have the band below them`() {
        val free = MAP.free(WIDTH, HEIGHT, LABEL_HEIGHT).single()
        assertEquals(MARGIN.toFloat() / WIDTH, free.left, EPS)
        assertEquals(MARGIN.toFloat() / WIDTH, free.right, EPS)
        // The label goes over the box: its height and its gap are kept free with the margin.
        assertEquals((TOP + MARGIN + LABEL_HEIGHT + GAP).toFloat() / HEIGHT, free.top, EPS)
        assertEquals((BOTTOM + MARGIN).toFloat() / HEIGHT, free.bottom, EPS)
    }

    @Test
    fun `with a corner card, the box may stand beside it or under it`() {
        val (beside, under) = SCENE.free(WIDTH, HEIGHT, LABEL_HEIGHT)
        assertEquals(MAP.free(WIDTH, HEIGHT, LABEL_HEIGHT).single(), under)
        // Beside it: up to the cards above the corner card, and clear of its column.
        assertEquals((TOP - CARD_HEIGHT + MARGIN + LABEL_HEIGHT + GAP).toFloat() / HEIGHT, beside.top, EPS)
        assertEquals((CARD_WIDTH + MARGIN).toFloat() / WIDTH, beside.right, EPS)
        assertEquals(under.left, beside.left, EPS)
        assertEquals(under.bottom, beside.bottom, EPS)

        val mirrored = SCENE.copy(card = ScanCard(CARD_WIDTH, CARD_HEIGHT, atLeft = true))
        val atLeft = mirrored.free(WIDTH, HEIGHT, LABEL_HEIGHT).first()
        assertEquals(beside.right, atLeft.left, EPS)
        assertEquals(beside.left, atLeft.right, EPS)

        assertEquals(emptyList<FramingInsets>(), SCENE.free(0, 0, LABEL_HEIGHT))
    }

    @Test
    fun `a label clear of the corner card is centred over its box`() {
        // Under the card's rows altogether.
        assertEquals(390 to 1400, label(SCENE, x = 540f, above = 1500f, below = 1800f))
        // Level with the card, in the columns beside it.
        assertEquals(150 to 600, label(SCENE, x = 300f, above = 700f, below = 1000f))
    }

    @Test
    fun `a label that would lie under the corner card is moved beside it`() {
        val (x, y) = label(SCENE, x = 900f, above = 700f, below = 1000f)
        assertEquals("still over its box", 600, y)
        assertEquals("its right edge a margin from the card", WIDTH - CARD_WIDTH - MARGIN, x + LABEL_WIDTH)

        val mirrored = SCENE.copy(card = ScanCard(CARD_WIDTH, CARD_HEIGHT, atLeft = true))
        assertEquals(CARD_WIDTH + MARGIN to 600, label(mirrored, x = 150f, above = 700f, below = 1000f))
    }

    @Test
    fun `with no room beside the card, or under cards across the width, the label goes under its box`() {
        val narrow = 700
        val (x, y) = SCENE.labelAt(500f, 700f, 1000f, LABEL_WIDTH, LABEL_HEIGHT, narrow, HEIGHT)
        assertEquals(350 to 1000 + GAP, x to y)

        assertEquals(WIDTH - LABEL_WIDTH - MARGIN to 1000 + GAP, label(MAP, x = 900f, above = 700f, below = 1000f))
    }

    private fun label(chrome: ScanChrome, x: Float, above: Float, below: Float) =
        chrome.labelAt(x, above, below, LABEL_WIDTH, LABEL_HEIGHT, WIDTH, HEIGHT)

    private companion object {
        const val WIDTH = 1080
        const val HEIGHT = 2340
        const val TOP = 900
        const val BOTTOM = 500
        const val CARD_WIDTH = 400
        const val CARD_HEIGHT = 500
        const val MARGIN = 40
        const val GAP = 20
        const val LABEL_WIDTH = 300
        const val LABEL_HEIGHT = 80
        const val EPS = 1e-6f

        /** The map: a header and the stats card across the width. */
        val MAP = ScanChrome(top = TOP, bottom = BOTTOM, margin = MARGIN, gap = GAP)

        /** The 3D view: the same cards, the last rows of them only the Camera card's corner. */
        val SCENE = MAP.copy(card = ScanCard(CARD_WIDTH, CARD_HEIGHT))
    }
}

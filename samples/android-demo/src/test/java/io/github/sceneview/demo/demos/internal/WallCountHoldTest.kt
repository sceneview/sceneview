package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The status card's wall count held steady (#4903), fed what a real walk's card showed once a
 * second (frames 0150–0195 of the QA recording): `4 of 4`, a one-second `5`, five seconds of
 * `1 of 4` while the walls were recognised again, `4 of 4`, thirteen seconds of `5`, three of `4`,
 * then `5` again.
 */
class WallCountHoldTest {
    /** The live count at each second from 150 to 195, as the recording showed it (gaps take the next frame's). */
    private val walk: List<Pair<Int, Int>> = buildList {
        for (s in 150..164) add(s to 4)
        add(165 to 5)
        for (s in 166..171) add(s to 1)
        for (s in 172..177) add(s to 4)
        for (s in 178..191) add(s to 5)
        for (s in 192..194) add(s to 4)
        add(195 to 5)
    }

    private fun shown(hold: WallCountHold, samples: List<Pair<Int, Int>>): Map<Int, Int> =
        samples.associate { (second, live) -> second to hold.update(live, second * MS) }

    @Test
    fun `the walk's card never drops to one wall and turns to five walls once five hold`() {
        val hold = WallCountHold(initial = 4)
        val shown = shown(hold, walk)
        // 4 throughout the one-second 5 and the six seconds of 1.
        for (s in 150..179) assertEquals("second $s", 4, shown[s])
        // 5 once it has held two seconds, and kept through the three seconds of 4.
        for (s in 180..195) assertEquals("second $s", 5, shown[s])
        assertEquals(setOf(4, 5), shown.values.toSet())
    }

    @Test
    fun `a count goes up once it has held, to the lowest it held at`() {
        val hold = WallCountHold()
        assertEquals(0, hold.update(2, 0))
        assertEquals(0, hold.update(3, 1_000))
        assertEquals(2, hold.update(3, WallCountHold.RISE_MS))
        // Three from then on: it shows once three has held as long.
        assertEquals(2, hold.update(3, WallCountHold.RISE_MS + 1_000))
        assertEquals(3, hold.update(3, 2 * WallCountHold.RISE_MS + 1_000))
    }

    @Test
    fun `a count goes down only once it has held the longer while`() {
        val hold = WallCountHold(initial = 4)
        assertEquals(4, hold.update(3, 0))
        assertEquals(4, hold.update(3, WallCountHold.DROP_MS - 1))
        assertEquals(3, hold.update(3, WallCountHold.DROP_MS))
    }

    @Test
    fun `the live count back on the shown one starts the wait again`() {
        val hold = WallCountHold(initial = 4)
        hold.update(1, 0)
        hold.update(4, 5_000)
        assertEquals(4, hold.update(1, 6_000))
        assertEquals(4, hold.update(1, 6_000 + WallCountHold.DROP_MS - 1))
        assertEquals(1, hold.update(1, 6_000 + WallCountHold.DROP_MS))
    }

    @Test
    fun `a new capture starts from no wall`() {
        val hold = WallCountHold(initial = 4)
        hold.update(5, 0)
        hold.reset()
        assertEquals(0, hold.shown)
        assertEquals(0, hold.update(0, 1_000))
    }

    private companion object {
        const val MS = 1_000L
    }
}

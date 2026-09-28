package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SurprisePoolTest {

    @Test fun `every Surprise pick is a CC-BY registry entry`() {
        // A uid missing from the registry would be dropped silently, with no credit to show.
        assertEquals(SURPRISE_POOL_UIDS.size, SURPRISE_POOL.size)
        assertEquals("no duplicates", SURPRISE_POOL_UIDS.size, SURPRISE_POOL_UIDS.toSet().size)
        assertTrue(SURPRISE_POOL.all { it.licenseUrl.startsWith("https://creativecommons.org/licenses/by/") })
        assertTrue(SURPRISE_POOL.all { it.author.isNotBlank() })
    }

    @Test fun `the bag shows every model once before any repeats`() {
        val bag = SurpriseBag((1..13).toList(), Random(7))
        repeat(4) {
            val pass = List(13) { bag.next()!! }
            assertEquals((1..13).toSet(), pass.toSet())
        }
    }

    @Test fun `two rolls in a row never show the same model`() {
        for (seed in 0 until 200) {
            val bag = SurpriseBag(listOf("a", "b", "c"), Random(seed))
            var previous: String? = null
            repeat(30) {
                val item = bag.next()
                assertNotEquals("seed $seed", previous, item)
                previous = item
            }
        }
    }

    @Test fun `peek announces the next roll`() {
        val bag = SurpriseBag((1..5).toList(), Random(3))
        repeat(12) {
            val announced = bag.peek()
            assertEquals(announced, bag.next())
        }
    }

    @Test fun `a retry takes a preferred entry from the pass when there is one`() {
        val bag = SurpriseBag((1..6).toList(), Random(1))
        val cached = setOf(4)
        val first = bag.next { it in cached }
        assertEquals(4, first)
        // Nothing left in the pass matches: falls back to the plain next entry.
        val second = bag.next { it in cached }
        assertTrue(second != null && second != 4)
    }

    @Test fun `an empty bag yields nothing`() {
        val bag = SurpriseBag(emptyList<String>())
        assertNull(bag.peek())
        assertNull(bag.next())
    }
}

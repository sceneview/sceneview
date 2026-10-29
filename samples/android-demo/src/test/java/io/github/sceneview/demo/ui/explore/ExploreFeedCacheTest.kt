package io.github.sceneview.demo.ui.explore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** #4622: a reopened gallery starts on the models it last showed, and a refresh never blanks them. */
class ExploreFeedCacheTest {

    private val loaded = FeedsLoad(mapOf("trending" to listOf("helmet")), FeedsStatus.Ready)
    private val failed = FeedsLoad(mapOf("trending" to emptyList<String>()), FeedsStatus.Failed)
    private val empty = FeedsLoad(mapOf("trending" to emptyList<String>()), FeedsStatus.Empty)

    @Test
    fun `a source never loaded has nothing to reopen on`() {
        val cache = ExploreFeedCache<String, String, String>()

        assertNull(cache.get("icosa", animatedOnly = false))
    }

    @Test
    fun `a load that brought models is kept per source and per filter`() {
        val cache = ExploreFeedCache<String, String, String>()

        cache.put("icosa", animatedOnly = false, load = loaded)

        assertEquals(loaded, cache.get("icosa", animatedOnly = false))
        assertNull(cache.get("icosa", animatedOnly = true))
        assertNull(cache.get("polyhaven", animatedOnly = false))
    }

    @Test
    fun `a failed or empty load never replaces the kept models`() {
        val cache = ExploreFeedCache<String, String, String>()
        cache.put("icosa", animatedOnly = false, load = loaded)

        cache.put("icosa", animatedOnly = false, load = failed)
        cache.put("icosa", animatedOnly = false, load = empty)

        assertEquals(loaded, cache.get("icosa", animatedOnly = false))
    }

    @Test
    fun `a refresh that fails keeps the models on screen`() {
        assertEquals(loaded, settleFeeds(shown = loaded, fresh = failed))
        assertEquals(loaded, settleFeeds(shown = loaded, fresh = empty))
    }

    @Test
    fun `a refresh that brings models replaces the page`() {
        val newer = FeedsLoad(mapOf("trending" to listOf("toy car")), FeedsStatus.Ready)

        assertEquals(newer, settleFeeds(shown = loaded, fresh = newer))
    }

    @Test
    fun `with nothing shown yet the load's own outcome stands`() {
        assertEquals(failed, settleFeeds(shown = null, fresh = failed))
        assertEquals(empty, settleFeeds(shown = null, fresh = empty))
        assertEquals(loaded, settleFeeds(shown = null, fresh = loaded))
    }
}

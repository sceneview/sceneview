package io.github.sceneview.demo.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `search`: what a typed query may send, its result buckets, and the once-per-search rule. */
class SearchTelemetryTest {

    private val logged = mutableListOf<AnalyticsEvent>()
    private val logger = SearchLogger { logged += it }

    @Test
    fun `a term is trimmed, collapsed and lowercased`() {
        assertEquals("damaged helmet", SearchTerm.normalize("  Damaged \t  HELMET \n"))
    }

    @Test
    fun `a single character is a keystroke, not a search`() {
        assertNull(SearchTerm.normalize(""))
        assertNull(SearchTerm.normalize("   "))
        assertNull(SearchTerm.normalize(" a "))
        assertEquals("ar", SearchTerm.normalize("AR"))
    }

    @Test
    fun `a long term is cut to 50 characters without a trailing space`() {
        val term = SearchTerm.normalize("word ".repeat(30))!!
        assertTrue(term.length <= SearchTerm.MAX_LENGTH)
        assertEquals(term.trimEnd(), term)
        assertEquals(50, SearchTerm.normalize("x".repeat(80))!!.length)
    }

    @Test
    fun `the 50-character cut never splits an emoji`() {
        // 49 letters then emoji: the 50th character is a whole surrogate pair, kept whole.
        val term = SearchTerm.normalize("a".repeat(49) + "\uD83D\uDE97\uD83D\uDE97")!!
        assertEquals("a".repeat(49) + "\uD83D\uDE97", term)
        // A ZWJ family is one character, like Swift's `Character`: cut before it, not inside it.
        val family = "\uD83D\uDC69\u200D\uD83D\uDC67"
        assertEquals("b".repeat(49) + family, SearchTerm.normalize("b".repeat(49) + family + "zz"))
        // One emoji is one character: a keystroke, like a single letter.
        assertNull(SearchTerm.normalize("\uD83D\uDE97"))
        assertEquals("\uD83D\uDE97\uD83D\uDE97", SearchTerm.normalize("\uD83D\uDE97\uD83D\uDE97"))
    }

    @Test
    fun `anything that looks like contact details is redacted`() {
        listOf(
            "me@example.com",
            "https://example.com/model.glb",
            "www.example.com",
            "call 0612345678",
            "order 12345",
        ).forEach { assertEquals(it, SearchTerm.REDACTED, SearchTerm.normalize(it)) }
    }

    @Test
    fun `a phone number typed with separators is redacted`() {
        listOf(
            "06 12 34 56 78",
            "+33 6 12 34 56 78",
            "06.12.34.56.78",
            "06-12-34-56-78",
            "(06) 12 34 56 78",
            "call +33 (0)6 12 34",
            "123 456",
        ).forEach { assertEquals(it, SearchTerm.REDACTED, SearchTerm.normalize(it)) }
        // Five digits or fewer across the separators stay readable.
        assertEquals("iphone 15 pro", SearchTerm.normalize("iPhone 15 Pro"))
        assertEquals("ps5 2020", SearchTerm.normalize("PS5 2020"))
        // Model names with short numbers stay readable.
        assertEquals("porsche 911", SearchTerm.normalize("Porsche 911"))
        assertEquals("rtx 4090", SearchTerm.normalize("RTX 4090"))
    }

    @Test
    fun `result counts fall in coarse buckets`() {
        assertEquals("error", SearchTerm.resultBucket(null))
        assertEquals("0", SearchTerm.resultBucket(0))
        assertEquals("1", SearchTerm.resultBucket(1))
        assertEquals("2-5", SearchTerm.resultBucket(2))
        assertEquals("2-5", SearchTerm.resultBucket(5))
        assertEquals("6-20", SearchTerm.resultBucket(6))
        assertEquals("6-20", SearchTerm.resultBucket(20))
        assertEquals("21+", SearchTerm.resultBucket(24))
    }

    @Test
    fun `a settled query logs one search event with its surface and bucket`() {
        assertTrue(logger.settled(SearchSurface.Samples, " Cosmos ", 1))
        val event = logged.single()
        assertEquals("search", event.name)
        assertEquals(
            mapOf("search_term" to "cosmos", "surface" to "samples", "result_bucket" to "1"),
            event.params(),
        )
    }

    @Test
    fun `the same search settling twice counts once`() {
        logger.settled(SearchSurface.Models, "helmet", 24)
        assertFalse(logger.settled(SearchSurface.Models, "Helmet ", 24))
        assertEquals(1, logged.size)
    }

    @Test
    fun `going back to a field does not count its last search again`() {
        logger.settled(SearchSurface.Models, "helmet", 24)
        logger.settled(SearchSurface.Samples, "cosmos", 1)
        assertFalse(logger.settled(SearchSurface.Models, "helmet", 24))
        assertFalse(logger.settled(SearchSurface.Samples, "cosmos", 1))
        assertEquals(2, logged.size)
    }

    @Test
    fun `a retry that answers after an error counts`() {
        logger.settled(SearchSurface.Models, "helmet", null)
        // The same failure settling twice still counts once.
        assertFalse(logger.settled(SearchSurface.Models, "helmet", null))
        assertTrue(logger.settled(SearchSurface.Models, "helmet", 24))
        assertFalse(logger.settled(SearchSurface.Models, "helmet", 24))
        assertEquals(listOf("error", "21+"), logged.map { it.params()["result_bucket"] })
    }

    @Test
    fun `a new term, the other field, or a cleared field counts again`() {
        logger.settled(SearchSurface.Models, "helmet", 24)
        logger.settled(SearchSurface.Models, "chair", 3)
        logger.settled(SearchSurface.Samples, "chair", 0)
        logger.cleared(SearchSurface.Samples)
        logger.settled(SearchSurface.Samples, "chair", 0)
        assertEquals(4, logged.size)
    }

    @Test
    fun `a query too short to be a search logs nothing`() {
        assertFalse(logger.settled(SearchSurface.Samples, "a", 12))
        assertTrue(logged.isEmpty())
    }

    @Test
    fun `search parameters fit GA4's limits`() {
        val event = AnalyticsEvent.Search(SearchTerm.normalize("y".repeat(200))!!, SearchSurface.Models, "21+")
        assertTrue(event.name.length <= AnalyticsEvent.MAX_NAME)
        event.params().forEach { (key, value) ->
            assertTrue(key, key.length <= AnalyticsEvent.MAX_NAME)
            assertTrue(key, (value as String).length <= AnalyticsEvent.MAX_VALUE)
        }
    }
}

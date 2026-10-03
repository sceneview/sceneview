package io.github.sceneview.demo.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The top of the Home shows a demo once: header pager, then the Featured banners, then the
 * What's new row. Plain ids on purpose — the rule is about the order of the groups.
 */
class HomeTopSectionsTest {

    @Test
    fun `a pager page does not come back as a banner`() {
        val top = homeTopSections(
            pager = listOf("a", "b"),
            featured = listOf("b", "c", "a", "d"),
            fresh = emptyList(),
        )
        assertEquals(listOf("a", "b"), top.pager)
        assertEquals(listOf("c", "d"), top.featured)
    }

    @Test
    fun `a banner does not come back in what's new, nor does a pager page`() {
        val top = homeTopSections(
            pager = listOf("a"),
            featured = listOf("b", "c"),
            fresh = listOf("c", "e", "a", "f"),
        )
        assertEquals(listOf("b", "c"), top.featured)
        assertEquals(listOf("e", "f"), top.whatsNew)
    }

    @Test
    fun `a group whose demos are all shown above is empty`() {
        val top = homeTopSections(
            pager = listOf("a", "b", "c"),
            featured = listOf("b", "c"),
            fresh = listOf("a", "c"),
        )
        assertTrue(top.featured.isEmpty())
        assertTrue(top.whatsNew.isEmpty())
    }

    @Test
    fun `each group keeps its own order, and an id listed twice is kept once`() {
        val top = homeTopSections(
            pager = listOf("b", "a", "b"),
            featured = listOf("d", "c", "d"),
            fresh = listOf("f", "e"),
        )
        assertEquals(listOf("b", "a"), top.pager)
        assertEquals(listOf("d", "c"), top.featured)
        assertEquals(listOf("f", "e"), top.whatsNew)
    }

    @Test
    fun `nothing above leaves every list as it came`() {
        val top = homeTopSections(pager = emptyList(), featured = listOf("a"), fresh = listOf("b"))
        assertEquals(listOf("a"), top.featured)
        assertEquals(listOf("b"), top.whatsNew)
    }

    @Test
    fun `no demo is listed twice across the three groups`() {
        val top = homeTopSections(
            pager = FEATURED_PAGER_IDS,
            featured = FEATURED_IDS,
            fresh = FEATURED_IDS + listOf("lighting", "fog"),
        )
        val all = top.pager + top.featured + top.whatsNew
        assertEquals(all.distinct(), all)
    }

    @Test
    fun `the whole catalogue does not list a banner a second time`() {
        val top = homeTopSections(pager = listOf("a"), featured = listOf("a", "b"), fresh = emptyList())
        // "a" is a pager page, not a banner: the hero is not a card, the catalogue keeps it.
        assertEquals(listOf("a", "c", "d"), top.catalogue(listOf("a", "b", "c", "d"), filtered = false))
    }

    @Test
    fun `a chip or a search lists every match, banner or not`() {
        val top = homeTopSections(pager = listOf("a"), featured = listOf("a", "b"), fresh = emptyList())
        assertEquals(listOf("b", "c"), top.catalogue(listOf("b", "c"), filtered = true))
    }
}

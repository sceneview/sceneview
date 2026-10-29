package io.github.sceneview.demo.labs

import io.github.sceneview.demo.ALL_DEMOS
import io.github.sceneview.demo.LABS_DEMO_IDS
import io.github.sceneview.demo.XR_ONLY_DEMO_IDS
import io.github.sceneview.demo.labsDemos
import io.github.sceneview.demo.listedDemos
import io.github.sceneview.demo.ui.home.FEATURED_IDS
import io.github.sceneview.demo.ui.home.FEATURED_PAGER_IDS
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SceneView Labs: which demos the catalogue lists with Labs off and on, and how a report
 * joins the programme. The real registry is empty, so a fake Labs id stands in for one.
 */
class LabsTest {

    private class FakeStore(override var joined: Boolean = false, override var enabled: Boolean = false) : LabsStore

    private val fakeLabsId = ALL_DEMOS.first { it.id !in XR_ONLY_DEMO_IDS && it.id !in LABS_DEMO_IDS }.id

    @After
    fun detach() = Labs.attach(FakeStore())

    @Test
    fun `every shipped Labs id is a routable phone demo`() {
        val phoneIds = ALL_DEMOS.map { it.id }.filterNot { it in XR_ONLY_DEMO_IDS }.toSet()
        assertTrue(LABS_DEMO_IDS.all { it in phoneIds })
    }

    @Test
    fun `no Labs demo is pushed by the hero pager or the Featured shelf`() {
        // The pager and the shelf are built from the listed demos: a Labs id there would make
        // the hero change page count when the switch flips, under a user mid-swipe.
        val pushed = FEATURED_PAGER_IDS + FEATURED_IDS
        assertTrue(LABS_DEMO_IDS.none { it in pushed })
    }

    @Test
    fun `a Labs demo is hidden while Labs is off and listed once it is on`() {
        val labsIds = setOf(fakeLabsId)
        val off = listedDemos(ALL_DEMOS, xrDevice = false, labsEnabled = false, labsIds = labsIds).map { it.id }
        val on = listedDemos(ALL_DEMOS, xrDevice = false, labsEnabled = true, labsIds = labsIds).map { it.id }
        assertFalse(fakeLabsId in off)
        assertTrue(fakeLabsId in on)
        assertEquals(on.size - 1, off.size)
    }

    @Test
    fun `the Home card counts Labs demos whether or not Labs is on`() {
        val labs = labsDemos(ALL_DEMOS, xrDevice = false, labsIds = setOf(fakeLabsId))
        assertEquals(listOf(fakeLabsId), labs.map { it.id })
        assertEquals(LABS_DEMO_IDS, labsDemos(ALL_DEMOS, xrDevice = false).map { it.id }.toSet())
    }

    @Test
    fun `access reads the two stored facts`() {
        assertEquals(LabsAccess.NotJoined, labsAccessOf(joined = false, enabled = false))
        assertEquals(LabsAccess.NotJoined, labsAccessOf(joined = false, enabled = true))
        assertEquals(LabsAccess.Off, labsAccessOf(joined = true, enabled = false))
        assertEquals(LabsAccess.On, labsAccessOf(joined = true, enabled = true))
    }

    @Test
    fun `the switch does nothing before joining`() {
        val store = FakeStore()
        Labs.attach(store)
        Labs.setEnabled(true)
        assertEquals(LabsAccess.NotJoined, Labs.access)
        assertFalse(store.enabled)
        assertEquals("not joined", Labs.reportLine())
    }

    @Test
    fun `the first report joins and turns Labs on, later ones leave the switch alone`() {
        val store = FakeStore()
        Labs.attach(store)
        Labs.onReportSent()
        assertEquals(LabsAccess.On, Labs.access)
        assertTrue(store.joined && store.enabled)

        Labs.setEnabled(false)
        Labs.onReportSent()
        assertEquals(LabsAccess.Off, Labs.access)
        assertFalse(store.enabled)
        assertEquals("joined, off", Labs.reportLine())
    }

    @Test
    fun `a stored join survives a new process`() {
        Labs.attach(FakeStore(joined = true, enabled = true))
        assertTrue(Labs.enabled)
        assertEquals("on", Labs.reportLine())
    }
}

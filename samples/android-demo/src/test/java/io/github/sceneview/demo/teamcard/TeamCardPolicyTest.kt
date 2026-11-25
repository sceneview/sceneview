package io.github.sceneview.demo.teamcard

import io.github.sceneview.demo.teamcard.TeamCardPolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The "Your team ships SceneView?" Home card: shown once, never again, never next to another ask. */
class TeamCardPolicyTest {

    private class MemoryStore : TeamCardStore {
        override var launches = 0
        override var samplesOpened = 0
        override var shown = false
        override var retired = false
    }

    private val store = MemoryStore()

    /** A new process on the same store: one cold start. */
    private fun launch(): TeamCardPolicy = TeamCardPolicy(store).also { it.onLaunch() }

    private fun TeamCardPolicy.openSamples(count: Int) = repeat(count) { onSampleOpened() }

    @Test
    fun `hidden before the 5th launch even with many samples opened`() {
        repeat(4) {
            val policy = launch()
            policy.openSamples(3)
            assertEquals(Decision.Hidden, policy.onHome(otherAskThisSession = false))
        }
        assertEquals(4, store.launches)
        assertFalse(store.shown)
    }

    @Test
    fun `hidden at the 5th launch until the 3rd sample is opened - whichever comes later`() {
        repeat(4) { launch() }
        val fifth = launch()
        assertEquals(Decision.Hidden, fifth.onHome(otherAskThisSession = false))
        fifth.openSamples(2)
        assertEquals(Decision.Hidden, fifth.onHome(otherAskThisSession = false))
        fifth.onSampleOpened()
        assertEquals(Decision.ShowFirst, fifth.onHome(otherAskThisSession = false))
    }

    @Test
    fun `samples first, then shown at the 5th launch`() {
        launch().openSamples(MIN_SAMPLES)
        repeat(3) { assertEquals(Decision.Hidden, launch().onHome(otherAskThisSession = false)) }
        assertEquals(Decision.ShowFirst, launch().onHome(otherAskThisSession = false))
    }

    @Test
    fun `stays on Home for the rest of the session it appeared in, logged once`() {
        val policy = reachThreshold()
        assertEquals(Decision.ShowFirst, policy.onHome(otherAskThisSession = false))
        assertEquals(Decision.Showing, policy.onHome(otherAskThisSession = false))
        // The pre-prompt cannot show once the card did (SessionAsks), but even if it had,
        // the card does not vanish under the user's finger.
        assertEquals(Decision.Showing, policy.onHome(otherAskThisSession = true))
        assertTrue(policy.shownThisSession)
    }

    @Test
    fun `shown once ever - never in a later session, even without a dismiss`() {
        assertEquals(Decision.ShowFirst, reachThreshold().onHome(otherAskThisSession = false))
        repeat(10) {
            val next = launch()
            next.openSamples(5)
            assertEquals(Decision.Hidden, next.onHome(otherAskThisSession = false))
        }
    }

    @Test
    fun `dismiss is definitive - gone at once and in every later session`() {
        val policy = reachThreshold()
        assertEquals(Decision.ShowFirst, policy.onHome(otherAskThisSession = false))
        policy.retire()
        assertEquals(Decision.Hidden, policy.onHome(otherAskThisSession = false))
        assertTrue(store.retired)
        assertEquals(Decision.Hidden, launch().onHome(otherAskThisSession = false))
    }

    @Test
    fun `never in a session where the push pre-prompt, the support sheet or the consent asked`() {
        val policy = reachThreshold()
        assertEquals(Decision.Hidden, policy.onHome(otherAskThisSession = true))
        assertFalse("a blocked session does not use up the one showing", store.shown)
        // The next quiet session gets it.
        assertEquals(Decision.ShowFirst, launch().onHome(otherAskThisSession = false))
    }

    @Test
    fun `a blocked session followed by a quiet one shows it there, once`() {
        reachThreshold().onHome(otherAskThisSession = true)
        val quiet = launch()
        assertEquals(Decision.ShowFirst, quiet.onHome(otherAskThisSession = false))
        assertEquals(Decision.Hidden, launch().onHome(otherAskThisSession = false))
    }

    @Test
    fun `the mailto action is the published one`() {
        assertEquals("mailto:hello@sceneview.dev?subject=Our%20app%20ships%20SceneView", TeamCard.MAILTO)
        assertEquals("Our app ships SceneView", TeamCard.SUBJECT)
        assertEquals("hello@sceneview.dev", TeamCard.ADDRESS)
    }

    /** Four earlier launches and three samples, then the 5th launch: the session the card may show in. */
    private fun reachThreshold(): TeamCardPolicy {
        repeat(4) { launch() }
        return launch().also { it.openSamples(MIN_SAMPLES) }
    }

    private companion object {
        const val MIN_SAMPLES = TeamCardPolicy.MIN_SAMPLES_OPENED
    }
}

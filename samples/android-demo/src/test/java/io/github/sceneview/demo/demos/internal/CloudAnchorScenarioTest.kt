package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the QA scenario switch (#3421).
 *
 * A forced state is only useful if it really is the state it is named after. These
 * screenshots are the *only* coverage most of this screen gets — `emulator-5554` cannot
 * run ARCore (#2754) and the interesting states need a live Cloud project and a second
 * phone — so a scenario that has drifted from its name is a capture silently attesting to
 * the wrong thing. Each one is therefore pinned against the card and buttons it must
 * produce, not just against itself.
 */
class CloudAnchorScenarioTest {

    @Test
    fun `every scenario produces a distinct screen`() {
        val states = CloudAnchorScenario.entries.map { it.state() }
        assertEquals(
            "Two scenarios resolve to the same state — one of them is dead weight",
            CloudAnchorScenario.entries.size,
            states.toSet().size,
        )
    }

    @Test
    fun `every scenario has something to say`() {
        CloudAnchorScenario.entries.forEach { scenario ->
            assertTrue(
                "$scenario has a blank status line",
                scenario.state().status().text.isNotBlank(),
            )
        }
    }

    @Test
    fun `Placing is the honest empty first frame`() {
        val state = CloudAnchorScenario.Placing.state()
        assertEquals(CloudAnchorStep.Host, state.step)
        assertFalse(state.anchorPlaced)
        assertEquals(CloudAnchorCard.None, state.card())
        assertEquals("Tap a surface to place the anchor.", state.status().text)
    }

    @Test
    fun `Mapping shows the meter and withholds Host`() {
        val state = CloudAnchorScenario.Mapping.state()
        assertEquals(CloudAnchorCard.RoomMapping(RoomQuality.Insufficient), state.card())
        assertFalse(state.allows(CloudAnchorAction.Host))
    }

    @Test
    fun `ReadyToHost offers Host`() {
        val state = CloudAnchorScenario.ReadyToHost.state()
        assertTrue(state.allows(CloudAnchorAction.Host))
        assertEquals(CloudAnchorCard.RoomMapping(RoomQuality.Good), state.card())
    }

    @Test
    fun `Hosting is in flight and locks its button`() {
        val state = CloudAnchorScenario.Hosting.state()
        assertEquals(CloudAnchorTask.Running, state.host)
        assertFalse(state.allows(CloudAnchorAction.Host))
        assertEquals("Hosting the anchor…", state.status().text)
    }

    @Test
    fun `Hosted shows the sample code with Copy and Share`() {
        val state = CloudAnchorScenario.Hosted.state()
        assertEquals(
            CloudAnchorCard.HostedCode(SAMPLE_CLOUD_ANCHOR_CODE, CLOUD_ANCHOR_TTL_DAYS),
            state.card(),
        )
        assertEquals(
            listOf(
                CloudAnchorAction.ShareCode,
                CloudAnchorAction.CopyCode,
                CloudAnchorAction.Restart,
            ),
            state.actionBar().map { it.action },
        )
    }

    @Test
    fun `the two card scenarios really do produce cards`() {
        listOf(CloudAnchorScenario.ApiKeyMissing, CloudAnchorScenario.ApiKeyRejected)
            .forEach { scenario ->
                assertTrue("$scenario", scenario.state().card() is CloudAnchorCard.Blocked)
                assertTrue("$scenario", scenario.state().actionBar().isEmpty())
            }
    }

    @Test
    fun `NoNetwork keeps the flow on screen`() {
        val state = CloudAnchorScenario.NoNetwork.state()
        // The transient blocker must not tear the screen down — that is the whole
        // distinction between it and the configuration blockers.
        assertNotEquals(CloudAnchorCard.None, state.card())
        assertTrue(state.actionBar().isNotEmpty())
        assertFalse(state.actionBar().first().enabled)
    }

    @Test
    fun `the Resolve scenarios all sit on the Resolve step`() {
        listOf(
            CloudAnchorScenario.ResolveEmpty,
            CloudAnchorScenario.ResolveReady,
            CloudAnchorScenario.Resolving,
            CloudAnchorScenario.Resolved,
            CloudAnchorScenario.ResolveNotFound,
        ).forEach { assertEquals("$it", CloudAnchorStep.Resolve, it.state().step) }
    }

    @Test
    fun `the sample code is long enough to exercise shortening`() {
        assertNotEquals(SAMPLE_CLOUD_ANCHOR_CODE, shortCloudAnchorCode(SAMPLE_CLOUD_ANCHOR_CODE))
    }

    // ── `--es qa_state <name>` parsing ──────────────────────────────────────

    @Test
    fun `a qa_state name is matched regardless of case or separator`() {
        assertEquals(
            CloudAnchorScenario.ResolveNotFound,
            cloudAnchorScenarioOf("resolve_not_found"),
        )
        assertEquals(
            CloudAnchorScenario.ResolveNotFound,
            cloudAnchorScenarioOf("resolve-not-found"),
        )
        assertEquals(
            CloudAnchorScenario.ResolveNotFound,
            cloudAnchorScenarioOf("ResolveNotFound"),
        )
        assertEquals(CloudAnchorScenario.Hosted, cloudAnchorScenarioOf("HOSTED"))
    }

    @Test
    fun `every scenario name round-trips through the parser`() {
        CloudAnchorScenario.entries.forEach { scenario ->
            assertEquals(scenario, cloudAnchorScenarioOf(scenario.name))
        }
    }

    @Test
    fun `an unknown or absent name leaves the demo alone`() {
        // A typo must not silently capture a different screen than the one asked for.
        assertNull(cloudAnchorScenarioOf("hosted!"))
        assertNull(cloudAnchorScenarioOf("nonsense"))
        assertNull(cloudAnchorScenarioOf(""))
        assertNull(cloudAnchorScenarioOf(null))
    }
}

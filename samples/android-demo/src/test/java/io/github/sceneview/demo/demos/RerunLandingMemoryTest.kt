package io.github.sceneview.demo.demos

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import io.github.sceneview.demo.DemoScaffoldTestTags
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.internal.RERUN_REPLAY_INTRO
import io.github.sceneview.demo.demos.internal.RerunSessionSource
import io.github.sceneview.demo.demos.internal.RerunSessionStore
import io.github.sceneview.demo.demos.internal.RerunStoredSession
import io.github.sceneview.demo.theme.SceneViewDemoTheme
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RerunLandingMemoryTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun `a thousand saved scans compose only visible cards and can scroll to the last stable key`() {
        val sessions = (0 until 1_000).map {
            LandingSession(info(it.toString()), null, null, "Recorded", "1 point")
        }
        compose.setContent {
            SceneViewDemoTheme {
                RerunLanding(
                    RerunLandingState(sessions, null, false),
                    RerunLandingActions({}, {}, {}, {}, {}, {}, {}),
                )
            }
        }
        assertTrue(compose.onAllNodesWithTag(SESSION_ROW_TAG).fetchSemanticsNodes().size < 20)
        compose.onNodeWithTag(SESSIONS_LIST_TAG).performScrollToKey("999")
        val visible = compose.onAllNodesWithTag(SESSION_ROW_TAG).fetchSemanticsNodes().size
        assertTrue(visible in 1..19)
        println("landing: $visible composed session cards with 1000 saved scans")
    }

    @Test
    fun `IO loaded card metadata is reused per session file and refreshed after rename`() = runBlocking {
        val store = RerunSessionStore(folder.newFolder())
        val session = info("one")
        val directory = store.directoryOf(session.id).apply { mkdirs() }
        val file = File(directory, RerunSessionStore.INFO)
        file.writeText(session.toJson())
        val cache = RerunLandingCache()
        val first = store.landingSessions(cache, Locale.US).single()
        assertSame(first, store.landingSessions(cache, Locale.US).single())
        assertEquals(sessionOrigin(session), first.origin)
        file.writeText(session.copy(title = "Renamed").toJson())
        val renamed = store.landingSessions(cache, Locale.US).single()
        assertNotSame(first, renamed)
        assertEquals("Renamed", renamed.info.title)
    }

    @Test
    fun `landing keeps scaffold back settings and app actions`() {
        var backs = 0
        compose.setContent {
            SceneViewDemoTheme {
                RerunLandingScreen(
                    { backs++ }, RerunLandingState(emptyList(), null, false),
                    RerunLandingActions({}, {}, {}, {}, {}, {}, {}),
                )
            }
        }
        val backLabel = RuntimeEnvironment.getApplication().getString(R.string.cd_back_button)
        compose.onNodeWithContentDescription(backLabel).performClick()
        assertEquals(1, backs)
        compose.onNodeWithTag(DemoScaffoldTestTags.SETTINGS_FAB).performClick()
        compose.onNodeWithText(RERUN_REPLAY_INTRO).assertIsDisplayed()
        compose.onNodeWithTag(DemoScaffoldTestTags.FEEDBACK_ACTION).assertExists()
        compose.onNodeWithTag(DemoScaffoldTestTags.QA_MODE_ACTION).assertExists()
    }

    @Test
    @Config(qualifiers = "w1200dp-h800dp-mdpi")
    fun `wide landing scrolls when swiping beside the centred card column`() {
        val sessions = (0 until 100).map {
            LandingSession(info(it.toString()), null, null, "Recorded", "1 point")
        }
        compose.setContent {
            SceneViewDemoTheme {
                RerunLanding(RerunLandingState(sessions, null, false),
                    RerunLandingActions({}, {}, {}, {}, {}, {}, {}))
            }
        }
        val list = compose.onNodeWithTag(SESSIONS_LIST_TAG)
        val width = list.fetchSemanticsNode().boundsInRoot.width
        assertTrue("list spans the wide page: $width", width > 1_000f)
        list.performScrollToKey("50")
        val before = compose.onAllNodesWithTag(SESSION_ROW_TAG).fetchSemanticsNodes().map { it.id }
        list.performTouchInput {
            swipe(Offset(40f, height * 0.8f), Offset(40f, height * 0.2f), 500)
        }
        val after = compose.onAllNodesWithTag(SESSION_ROW_TAG).fetchSemanticsNodes().map { it.id }
        assertTrue("side gutter swipe advances the list", before != after)
    }

    @Test
    fun `overlapping IO loads reuse one cached card instance`() = runBlocking {
        val store = RerunSessionStore(folder.newFolder())
        val session = info("one")
        val directory = store.directoryOf(session.id).apply { mkdirs() }
        File(directory, RerunSessionStore.INFO).writeText(session.toJson())
        val cache = RerunLandingCache()
        val cards = coroutineScope {
            (0 until 32).map { async(Dispatchers.IO) { cache.load(store, Locale.US).single() } }.awaitAll()
        }
        cards.forEach { assertSame(cards.first(), it) }
    }

    private fun info(id: String) = RerunStoredSession(id, "Scan $id", 1_000L,
        RerunSessionSource.Recorded, 1f, 1f, 1, 0, 0)
}

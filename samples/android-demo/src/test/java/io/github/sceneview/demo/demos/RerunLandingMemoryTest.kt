package io.github.sceneview.demo.demos

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToKey
import io.github.sceneview.demo.demos.internal.RerunSessionSource
import io.github.sceneview.demo.demos.internal.RerunSessionStore
import io.github.sceneview.demo.demos.internal.RerunStoredSession
import io.github.sceneview.demo.theme.SceneViewDemoTheme
import kotlinx.coroutines.runBlocking
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

    private fun info(id: String) = RerunStoredSession(id, "Scan $id", 1_000L,
        RerunSessionSource.Recorded, 1f, 1f, 1, 0, 0)
}

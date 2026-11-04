package io.github.sceneview.demo.demos

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.sceneview.demo.demos.internal.DenseCloud
import io.github.sceneview.demo.demos.internal.RescanKind
import io.github.sceneview.demo.demos.internal.RescanZone
import io.github.sceneview.demo.demos.internal.ScanCopy
import io.github.sceneview.demo.demos.internal.ScanFixWatch
import io.github.sceneview.demo.demos.internal.ScanReviewCopy
import io.github.sceneview.demo.demos.internal.ScanReviewResult
import io.github.sceneview.demo.demos.internal.ScanReviewTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Review before you leave, the screen's side: the session's states — read once, Fix now set on
 * the tap, the fix watched through a pause, the review left — and the card a user taps.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class ScanReviewSessionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `a review reads once, fix now is set on the tap, the review left shows nothing`() = runTest {
        val session = ScanReviewSession(millis = { testScheduler.currentTime }, nanoTime = { testScheduler.currentTime * NS_PER_MS })
        var reads = 0
        val job = launch {
            session.review {
                reads++
                ScanReviewTest.damaged
            }
        }
        while (session.result == null) {
            Thread.sleep(POLL_MS)
            runCurrent()
        }
        assertEquals(1, reads)
        val found = session.found!!
        assertEquals(2, found.zones.size)
        assertSame(found.zones[0], session.shown)
        // Hold: the view stays on the part being read, whatever the dwell says.
        session.hold()
        advanceTimeBy(DWELL_MS * 2)
        assertEquals(0, session.focus)
        session.next()
        assertEquals(1, session.focus)
        // Fix now: set at once, before the capture scans again.
        session.startFix(found.zones[1], ScanReviewTest.damaged)
        assertSame(found.zones[1], session.fix?.zone)
        job.cancel()
        session.leave()
        assertNull(session.result)
        assertNull(session.shown)
    }

    @Test
    fun `a part fixed, then found again where it was, is set aside`() = runTest {
        val session = ScanReviewSession(millis = { testScheduler.currentTime }, nanoTime = { testScheduler.currentTime * NS_PER_MS })
        suspend fun reviewOf(cloud: DenseCloud): ScanReviewResult {
            val job = launch { session.review { cloud } }
            while (session.result == null) {
                Thread.sleep(POLL_MS)
                runCurrent()
            }
            return session.result!!.also { job.cancel() }
        }
        val first = reviewOf(ScanReviewTest.damaged)
        val wall = first.zones.single { it.kind == RescanKind.MissingWall }
        session.startFix(wall, ScanReviewTest.damaged)
        session.leave()
        // The same capture, nothing added there: the wall is not offered again, and it is said.
        val again = reviewOf(ScanReviewTest.damaged)
        assertTrue(again.zones.joinToString("\n"), again.zones.none { it.kind == RescanKind.MissingWall })
        assertEquals(1, again.setAside)
    }

    @Test
    fun `a pause while Covered is shown waits, and the fix is watched again once it resumes`() = runTest {
        val session = ScanReviewSession(millis = { testScheduler.currentTime }, nanoTime = { testScheduler.currentTime * NS_PER_MS })
        session.startFix(FLOOR, null)
        val fix = session.fix!!
        fix.follow(floorCloud())
        var scanning = true
        var reviews = 0
        val watch = launch { session.watchFix(scanning = { scanning }) { reviews++ } }
        // Done once it has scanned a while: the line says Covered, then a pause in that moment.
        advanceTimeBy((ScanFixWatch.MIN_SECONDS * MS_PER_S).toLong() + ScanReviewSession.FIX_TICK_MS)
        runCurrent()
        scanning = false
        advanceTimeBy(ScanReviewSession.COVERED_HOLD_MS * 3)
        runCurrent()
        assertEquals("not while paused", 0, reviews)
        assertTrue("still watched", watch.isActive)
        scanning = true
        advanceTimeBy(ScanReviewSession.FIX_TICK_MS + ScanReviewSession.COVERED_HOLD_MS + 1)
        runCurrent()
        assertEquals(1, reviews)
    }

    @Test
    fun `the card goes from checking to the parts, fix now names the part, then clear`() {
        var review by mutableStateOf<ScanReviewResult?>(null)
        var checking by mutableStateOf(true)
        var fixed: RescanZone? = null
        val zones = listOf(WALL, FLOOR)
        composeRule.setContent {
            MaterialTheme {
                ScanReviewCard(
                    empty = false,
                    figures = "1,000 points",
                    onKeepScanning = {},
                    onSave = {},
                    review = review,
                    checking = checking,
                    focus = 0,
                    onFixNow = { fixed = it },
                )
            }
        }
        composeRule.onNodeWithText(ScanReviewCopy.CHECKING).assertIsDisplayed()
        composeRule.onNodeWithText(ScanCopy.KEEP_SCANNING).assertIsDisplayed()

        review = ScanReviewResult(zones, 0f, 0f, 0L)
        checking = false
        composeRule.onNodeWithText(ScanReviewCopy.title(2)).assertIsDisplayed()
        composeRule.onNodeWithText(ScanReviewCopy.focus(WALL, 0, 2)).assertIsDisplayed()
        composeRule.onNodeWithTag(SCAN_BUILD_ANYWAY_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(SCAN_NEXT_PART_TAG).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Fix part 1 of 2, Missing wall").performClick()
        assertSame(WALL, fixed)

        review = ScanReviewResult(emptyList(), 0f, 0f, 0L)
        composeRule.onNodeWithText(ScanReviewCopy.CLEAR_TITLE).assertIsDisplayed()
        composeRule.onNodeWithText(ScanReviewCopy.CLEAR_DETAIL).assertIsDisplayed()
    }

    private companion object {
        const val NS_PER_MS = 1_000_000L
        const val MS_PER_S = 1000f
        const val POLL_MS = 20L
        const val DWELL_MS = 3_000L

        val WALL = RescanZone(RescanKind.MissingWall, floatArrayOf(1.85f, 0f, -1.3f, 2.15f, 2.6f, -0.4f), 0.9f, "wall")
        val FLOOR = RescanZone(RescanKind.FloorHole, floatArrayOf(0f, 0f, 0f, 0.6f, 0.05f, 0.6f), 0.36f, "hole")

        /** A floor scanned every 2 cm over [FLOOR]: every cell of it filled in. */
        fun floorCloud(): DenseCloud {
            val side = 30
            val positions = FloatArray(side * side * 3)
            for (i in 0 until side) for (j in 0 until side) {
                val k = (i * side + j) * 3
                positions[k] = 0.01f + i * 0.02f
                positions[k + 1] = 0f
                positions[k + 2] = 0.01f + j * 0.02f
            }
            return DenseCloud(positions, IntArray(side * side))
        }
    }
}

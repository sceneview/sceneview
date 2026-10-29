package io.github.sceneview.demo.support

import androidx.test.core.app.ApplicationProvider
import io.github.sceneview.demo.support.SupportPrompt.MIN_INTERVAL_MILLIS
import io.github.sceneview.demo.support.SupportPrompt.VALUE_MOMENT_MIN_SECONDS
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** [SupportPrompt]'s stored half: the session count, the shown date, the moment of value. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SupportPromptStateTest {

    private val now = 1_800_000_000_000L

    @Before
    fun freshInstall() {
        SupportPrompt.resetForTest(ApplicationProvider.getApplicationContext())
    }

    private fun sessions(count: Int) = repeat(count) { SupportPrompt.onSessionStart() }

    private fun valueMoment() = SupportPrompt.onSampleUsed(VALUE_MOMENT_MIN_SECONDS)

    @Test
    fun `onSessionStart counts cold starts up to the third`() {
        sessions(2)
        valueMoment()
        assertFalse(SupportPrompt.maybeShow(canShow = true, now = now))
        assertTrue("the moment waits for the third session", SupportPrompt.valueMoment)

        sessions(1)
        assertTrue(SupportPrompt.maybeShow(canShow = true, now = now))
    }

    @Test
    fun `a short sample is not a moment of value`() {
        sessions(3)
        SupportPrompt.onSampleUsed(VALUE_MOMENT_MIN_SECONDS - 1)
        assertFalse(SupportPrompt.maybeShow(canShow = true, now = now))
    }

    @Test
    fun `maybeShow consumes the moment and shows`() {
        sessions(3)
        SupportPrompt.onArPlacement()
        assertTrue(SupportPrompt.maybeShow(canShow = true, now = now))
        assertTrue(SupportPrompt.isShown)
        assertFalse("the moment is spent once shown", SupportPrompt.valueMoment)
    }

    @Test
    fun `maybeShow keeps the moment while the caller can't show`() {
        sessions(3)
        valueMoment()
        assertFalse(SupportPrompt.maybeShow(canShow = false, now = now))
        assertFalse(SupportPrompt.isShown)
        assertTrue(SupportPrompt.valueMoment)
    }

    @Test
    fun `maybeShow records the date, so the next ask waits fourteen days`() {
        sessions(3)
        valueMoment()
        assertTrue(SupportPrompt.maybeShow(canShow = true, now = now))
        SupportPrompt.dismiss()

        valueMoment()
        assertFalse(SupportPrompt.maybeShow(canShow = true, now = now + MIN_INTERVAL_MILLIS - 1))
        assertTrue(SupportPrompt.maybeShow(canShow = true, now = now + MIN_INTERVAL_MILLIS))
    }

    @Test
    fun `optOut is stored`() {
        sessions(3)
        valueMoment()
        assertTrue(SupportPrompt.maybeShow(canShow = true, now = now))
        SupportPrompt.optOut()

        valueMoment()
        assertFalse(SupportPrompt.maybeShow(canShow = true, now = now + 10 * MIN_INTERVAL_MILLIS))
    }
}

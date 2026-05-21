package io.github.sceneview.demo.feedback

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Round-trip test for the one-time feedback-onboarding flag. */
@RunWith(RobolectricTestRunner::class)
class FeedbackPrefsTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun `onboarding flag defaults to false then persists once marked`() {
        assertFalse(FeedbackPrefs.hasSeenOnboarding(context))

        FeedbackPrefs.markOnboardingSeen(context)

        assertTrue(FeedbackPrefs.hasSeenOnboarding(context))
    }
}

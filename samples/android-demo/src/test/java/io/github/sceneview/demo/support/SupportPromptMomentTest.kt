package io.github.sceneview.demo.support

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SupportPromptMomentTest {
    @Before
    fun reset() {
        SupportPrompt.resetForTest(ApplicationProvider.getApplicationContext())
        repeat(SupportPrompt.MIN_SESSIONS) { SupportPrompt.onSessionStart() }
    }

    @Test
    fun `closing an AR sample keeps the placement headline`() {
        SupportPrompt.onArPlacement()
        SupportPrompt.onSampleUsed(SupportPrompt.VALUE_MOMENT_MIN_SECONDS)
        SupportPrompt.maybeShow(canShow = true)
        assertEquals(SupportMoment.ArPlacement, SupportPrompt.moment)
    }

    @Test
    fun `manual support consumes the moment before the automatic prompt settles`() {
        SupportPrompt.onArPlacement()
        SupportPrompt.clearValueMoment()
        assertFalse(SupportPrompt.valueMoment)
        assertFalse(SupportPrompt.maybeShow(canShow = true))
    }

    @Test
    fun `a subsequent sample uses the open source headline`() {
        SupportPrompt.onArPlacement()
        SupportPrompt.maybeShow(canShow = true)
        SupportPrompt.dismiss()
        SupportPrompt.onSampleUsed(SupportPrompt.VALUE_MOMENT_MIN_SECONDS)
        assertEquals(SupportMoment.Sample, SupportPrompt.moment)
    }
}

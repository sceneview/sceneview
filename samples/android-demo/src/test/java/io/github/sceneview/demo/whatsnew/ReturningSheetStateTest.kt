package io.github.sceneview.demo.whatsnew

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The What's new sheets closed for good when a sample was opened from them, so back
 * from the sample landed on a bare Home. The host here leaves composition and comes
 * back under a [rememberSaveableStateHolder], the way a NavHost destination does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReturningSheetStateTest {

    @get:Rule
    val rule = createComposeRule()

    private var onHome by mutableStateOf(true)
    private lateinit var sheet: ReturningSheetState

    private fun setUpHost() {
        rule.setContent {
            val holder = rememberSaveableStateHolder()
            if (onHome) {
                holder.SaveableStateProvider("list") { sheet = rememberReturningSheetState() }
            }
        }
    }

    private fun roundTripThroughSample() {
        onHome = false
        rule.waitForIdle()
        onHome = true
        rule.waitForIdle()
    }

    @Test
    fun `back from a sample opened in the sheet shows the sheet again`() {
        setUpHost()
        rule.runOnIdle { sheet.open() }
        rule.runOnIdle {
            sheet.leaveForSample()
            // Hidden while the sample is on screen.
            assertFalse(sheet.isShown)
        }

        roundTripThroughSample()

        rule.runOnIdle { assertTrue(sheet.isShown) }
    }

    @Test
    fun `a dismissed sheet stays closed after a round trip`() {
        setUpHost()
        rule.runOnIdle {
            sheet.open()
            sheet.dismiss()
        }

        roundTripThroughSample()

        rule.runOnIdle { assertFalse(sheet.isShown) }
    }

    @Test
    fun `the sheet reopens once, then a dismiss closes it`() {
        setUpHost()
        rule.runOnIdle {
            sheet.open()
            sheet.leaveForSample()
        }
        roundTripThroughSample()
        rule.runOnIdle { sheet.dismiss() }

        roundTripThroughSample()

        rule.runOnIdle { assertFalse(sheet.isShown) }
    }
}

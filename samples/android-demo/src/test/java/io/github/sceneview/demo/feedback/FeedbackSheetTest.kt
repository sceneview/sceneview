package io.github.sceneview.demo.feedback

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.core.content.IntentCompat
import io.github.sceneview.demo.theme.SceneViewDemoTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class FeedbackSheetTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `both destinations stay disabled until the description has ten non-space characters`() {
        showSheet()
        assertDestinationsEnabled(false)
        composeRule.onNodeWithText(
            "Describe what happened in a sentence (at least 10 characters, excluding spaces).",
        ).assertExists()

        val field = composeRule.onNode(hasSetTextAction())
        field.performTextReplacement(" 12345 6789 ")
        assertDestinationsEnabled(false)
        field.performTextReplacement(" 12345 67890 ")
        assertDestinationsEnabled(true)
        field.performTextReplacement(" \n\t ")
        assertDestinationsEnabled(false)
    }

    @Test
    fun `selected category reaches the GitHub intent body`() {
        var dismissed = false
        showSheet { dismissed = true }
        composeRule.onNodeWithText("Feature request").performClick()
        composeRule.onNode(hasSetTextAction()).performTextReplacement("Please add a new model.")
        composeRule.onNodeWithText("Send to GitHub").performScrollTo().performClick()

        val uri = shadowOf(composeRule.activity).nextStartedActivity.data!!
        assertFalse(uri.queryParameterNames.contains("labels"))
        assertTrue(uri.getQueryParameter("body")!!.contains("| Category | Feature request |"))
        assertTrue(dismissed)
    }

    @Test
    fun `selected category reaches the shared report`() {
        showSheet()
        composeRule.onNodeWithText("Question").performClick()
        composeRule.onNode(hasSetTextAction()).performTextReplacement("How can I open a model?")
        composeRule.onNodeWithText("Share report").performScrollTo().performClick()

        val chooser = shadowOf(composeRule.activity).nextStartedActivity
        val share = IntentCompat.getParcelableExtra(chooser, Intent.EXTRA_INTENT, Intent::class.java)!!
        assertTrue(share.getStringExtra(Intent.EXTRA_TEXT)!!.contains("Category: Question"))
    }

    private fun showSheet(onDismiss: () -> Unit = {}) {
        composeRule.setContent {
            SceneViewDemoTheme {
                BugReportSheet(
                    report = PendingBugReport(BugReportInfo(emptyMap(), emptyList()), screenshot = null),
                    onDismiss = onDismiss,
                )
            }
        }
    }

    private fun assertDestinationsEnabled(enabled: Boolean) {
        listOf("Send to GitHub", "Share report").forEach { label ->
            val button = composeRule.onNodeWithText(label)
            if (enabled) button.assertIsEnabled() else button.assertIsNotEnabled()
        }
    }
}

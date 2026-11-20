package io.github.sceneview.demo.feedback

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.requestFocus
import androidx.core.content.IntentCompat
import io.github.sceneview.demo.theme.SceneViewDemoTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    private val fieldError = SemanticsMatcher.keyIsDefined(SemanticsProperties.Error)
    private val helper = "Add a sentence (at least 10 characters, excluding spaces; CJK characters count as two)."

    @Test
    fun `invalid GitHub tap shows the error and brings the description into focus without sending`() {
        assertInvalidSendFocusesDescription("Send to GitHub")
    }

    @Test
    fun `invalid share tap shows the error and brings the description into focus without sending`() {
        assertInvalidSendFocusesDescription("Share report")
    }

    private fun assertInvalidSendFocusesDescription(destination: String) {
        var dismissed = false
        showSheet { dismissed = true }
        assertDestinationsEnabled()
        val field = composeRule.onNode(hasSetTextAction())
        field.assert(!fieldError)
        composeRule.onNodeWithText(destination).performScrollTo().performClick()

        field.assert(fieldError).assertIsFocused().assertIsDisplayed()
        composeRule.onNodeWithText(helper).assertIsDisplayed()
        assertNull(shadowOf(composeRule.activity).nextStartedActivity)
        assertFalse(dismissed)
        composeRule.onNodeWithText("No app available to send the report").assertDoesNotExist()
        assertDestinationsEnabled()
    }

    @Test
    fun `description shows errors after focus loss and keeps its helper stable across the boundary`() {
        showSheet()
        val field = composeRule.onNode(hasSetTextAction())
        // SetText does not move focus: tap first, as a person does before typing.
        field.performClick().assertIsFocused()
        field.performTextReplacement(" 12345 6789 ")
        field.assert(!fieldError)
        // The sheet lives in its own window: move focus to another control inside it.
        composeRule.onNodeWithText("Bug").requestFocus()
        field.assert(fieldError)

        field.performClick()
        val helperBounds = composeRule.onNodeWithText(helper).fetchSemanticsNode().boundsInRoot
        field.performTextReplacement(" 12345 67890 ")
        field.assert(!fieldError)
        assertEquals(helperBounds, composeRule.onNodeWithText(helper).fetchSemanticsNode().boundsInRoot)
        field.performTextReplacement(" \n\t ")
        field.assert(fieldError)
        assertDestinationsEnabled()
    }

    @Test
    fun `category is an exclusive radio group`() {
        showSheet()
        composeRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup)).assertExists()
        listOf("Bug", "Feature request", "Question").forEach { label ->
            composeRule.onNodeWithText(label).assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
        }
        composeRule.onNodeWithText("Bug").assertIsOn()
        composeRule.onNodeWithText("Question").performClick().assertIsOn()
        composeRule.onNodeWithText("Bug").assertIsOff()
        composeRule.onNodeWithText("Feature request").assertIsOff()
        composeRule.onNodeWithText("Question").performClick().assertIsOn()
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
        assertEquals("Share feedback", chooser.getStringExtra(Intent.EXTRA_TITLE))
    }

    @Test
    fun `a concise CJK sentence can be sent`() {
        showSheet()
        composeRule.onNode(hasSetTextAction()).performTextReplacement("模型加载后黑屏")
        composeRule.onNodeWithText("Send to GitHub").performScrollTo().performClick()

        val uri = shadowOf(composeRule.activity).nextStartedActivity.data!!
        assertTrue(uri.getQueryParameter("body")!!.startsWith("模型加载后黑屏"))
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

    private fun assertDestinationsEnabled() {
        listOf("Send to GitHub", "Share report").forEach { label ->
            composeRule.onNodeWithText(label).assertIsEnabled()
        }
    }
}

package io.github.sceneview.demo.common

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performSemanticsAction
import io.github.sceneview.sample.ui.LabeledSlider
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What a screen reader gets from the shared `LabeledSlider` (#3721).
 *
 * The control used to wrap its label row and its track in `clearAndSetSemantics`, which kept
 * the value from being read twice but replaced everything the `Slider` published with a bare
 * description: no slider role, no `SetProgress` action for TalkBack's swipe up/down, and no
 * disabled state — a switched-off slider was announced as an ordinary, operable one, on all
 * of its ~50 call sites.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LabeledSliderSemanticsTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `an enabled slider can be adjusted by a screen reader`() {
        var value = 0.25f
        rule.setContent {
            LabeledSlider(label = "Density", value = value, onValueChange = { value = it }, valueRange = 0f..1f)
        }

        rule.onNodeWithContentDescription("Density")
            .assertIsEnabled()
            .assert(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
            .performSemanticsAction(SemanticsActions.SetProgress) { it(0.75f) }

        assertEquals(0.75f, value, 1e-4f)
    }

    @Test
    fun `a disabled slider is announced as disabled`() {
        rule.setContent {
            LabeledSlider(
                label = "Environment rotation",
                value = 0f,
                onValueChange = {},
                valueRange = 0f..360f,
                decimals = 0,
                unit = "°",
                enabled = false,
            )
        }

        rule.onNodeWithContentDescription("Environment rotation")
            .assertIsNotEnabled()
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Disabled))
    }

    @Test
    fun `the value is announced once, in the unit the screen shows`() {
        rule.setContent {
            Column {
                LabeledSlider(
                    label = "Start",
                    value = 3.5f,
                    onValueChange = {},
                    valueRange = 0f..20f,
                    decimals = 1,
                    unit = "m",
                )
            }
        }

        // The readout rides the track as its state, replacing the percentage a bare slider
        // would announce…
        rule.onNodeWithContentDescription("Start")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "3.5 m"))
        // …and the label row that draws it is not a second node saying it again.
        assertEquals(0, rule.onAllNodesWithText("3.5 m").fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodesWithText("Start").fetchSemanticsNodes().size)
    }
}

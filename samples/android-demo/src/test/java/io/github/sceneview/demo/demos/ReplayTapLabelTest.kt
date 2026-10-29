package io.github.sceneview.demo.demos

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import io.github.sceneview.demo.demos.internal.ReplayTapAnswer
import io.github.sceneview.demo.demos.internal.ScanBox
import io.github.sceneview.demo.demos.internal.semantic.SceneLabel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The label a tap on a replayed scan puts on a piece of furniture (#4485): its name when scene
 * recognition gave it one, its three sizes in the phone's units, and a word when the scan only saw
 * one side of it — read as TalkBack reads it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReplayTapLabelTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val sofa = ScanBox(0f, 0f, 0f, 0f, length = 1.4f, depth = 0.85f, height = 0.9f)

    /** The label's description for [answer], under the test's locale. */
    private fun described(answer: ReplayTapAnswer): String {
        composeRule.setContent { ReplayTapLabel(answer) }
        composeRule.waitForIdle()
        return composeRule.onNodeWithTag(REPLAY_TAP_LABEL_TAG, useUnmergedTree = true)
            .fetchSemanticsNode()
            .config[SemanticsProperties.ContentDescription]
            .first()
    }

    private fun shown(text: String): Int = composeRule.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().size

    @Test
    @Config(qualifiers = "en-rUS")
    fun `a recognised sofa reads its name, then feet and inches in US English`() {
        assertEquals("Sofa: 4 ft 7 in long, 2 ft 9 in deep, 2 ft 11 in high", described(ReplayTapAnswer(sofa, SceneLabel.Sofa, oneSided = false)))
        assertEquals("the name is on the box", 1, shown("Sofa"))
        assertEquals(1, shown("4 ft 7 in × 2 ft 9 in × 2 ft 11 in"))
    }

    @Test
    @Config(qualifiers = "fr-rFR")
    fun `in French it reads metres and centimetres with a decimal comma`() {
        assertEquals("Sofa: 1,40 m long, 85 cm deep, 90 cm high", described(ReplayTapAnswer(sofa, SceneLabel.Sofa, oneSided = false)))
        assertEquals(1, shown("1,40 m × 85 cm × 90 cm"))
    }

    @Test
    @Config(qualifiers = "en-rGB")
    fun `an object nothing recognised reads its sizes alone, with no name`() {
        assertEquals("Selected object: 1.40 m long, 85 cm deep, 90 cm high", described(ReplayTapAnswer(sofa, null, oneSided = false)))
        assertEquals("no name on the box", 0, shown("Sofa"))
    }

    @Test
    @Config(qualifiers = "en-rUS")
    fun `an object seen from one side says it may measure short`() {
        assertEquals(
            "Sofa: 4 ft 7 in long, 2 ft 9 in deep, 2 ft 11 in high. Seen from one side, may measure short",
            described(ReplayTapAnswer(sofa, SceneLabel.Sofa, oneSided = true)),
        )
        assertEquals(1, shown("Seen from one side, may measure short"))
    }

    @Test
    @Config(qualifiers = "en-rUS")
    fun `an object seen all round says nothing of the sort`() {
        described(ReplayTapAnswer(sofa, null, oneSided = false))
        assertEquals(0, shown("Seen from one side, may measure short"))
    }
}

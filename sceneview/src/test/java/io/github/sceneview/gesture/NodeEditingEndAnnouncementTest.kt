package io.github.sceneview.gesture

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Scale
import io.github.sceneview.node.FilamentWithoutJni
import io.github.sceneview.node.Node
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The badges announce coarse steps and leave with their gesture: the exact result is spoken
 * once, by a polite live region that takes it when the gesture ends.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], instrumentedPackages = ["com.google.android.filament"])
class NodeEditingEndAnnouncementTest {

    @get:Rule(order = 0)
    val registerActivity = object : ExternalResource() {
        override fun before() {
            val context = RuntimeEnvironment.getApplication()
            shadowOf(context.packageManager).addActivityIfNotPresent(
                ComponentName(context.packageName, ComponentActivity::class.java.name),
            )
        }
    }

    @get:Rule(order = 1)
    val composeRule = createComposeRule()

    private lateinit var node: Node
    private lateinit var feedback: NodeEditingFeedbackState

    private val politeLiveRegion =
        SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)
    private val described = SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription)

    @Before
    fun setUp() {
        node = Node(FilamentWithoutJni.engine())
        feedback = NodeEditingFeedbackState(node, scaleBaseline = 1f)
        composeRule.setContent { EditingEndAnnouncement(state = feedback) }
    }

    private fun assertSilent() {
        composeRule.onAllNodes(politeLiveRegion and described).assertCountEquals(0)
    }

    @Test
    fun `silent at rest, even after an application rotation`() {
        assertSilent()

        composeRule.runOnIdle {
            node.rotation = Rotation(y = -22f)
            feedback.refreshRotationFromNode()
        }

        assertSilent()
    }

    @Test
    fun `a twist ending between steps is announced with its exact angle`() {
        composeRule.runOnIdle {
            feedback.onEditingBegin(node, NodeEditingKind.Rotate)
            feedback.onRotationEdited(node, 44f)
        }
        assertSilent()

        composeRule.runOnIdle { feedback.onEditingEnd(node, NodeEditingKind.Rotate) }

        composeRule.onNodeWithContentDescription("Rotation +44 degrees").assert(politeLiveRegion)
    }

    @Test
    fun `a pinch ending between steps is announced with its exact percentage`() {
        composeRule.runOnIdle {
            node.scale = Scale(1.17f)
            feedback.onEditingBegin(node, NodeEditingKind.Scale)
        }
        assertSilent()

        composeRule.runOnIdle { feedback.onEditingEnd(node, NodeEditingKind.Scale) }

        composeRule.onNodeWithContentDescription("Scale 117 percent").assert(politeLiveRegion)
    }

    @Test
    fun `the next gesture clears the announcement so its own end is a change again`() {
        composeRule.runOnIdle {
            feedback.onEditingBegin(node, NodeEditingKind.Rotate)
            feedback.onRotationEdited(node, 44f)
        }
        composeRule.runOnIdle { feedback.onEditingEnd(node, NodeEditingKind.Rotate) }
        composeRule.onNodeWithContentDescription("Rotation +44 degrees").assert(politeLiveRegion)

        composeRule.runOnIdle {
            feedback.onEditingBegin(node, NodeEditingKind.Rotate)
            feedback.onRotationEdited(node, 44f)
        }
        assertSilent()

        composeRule.runOnIdle { feedback.onEditingEnd(node, NodeEditingKind.Rotate) }
        composeRule.onNodeWithContentDescription("Rotation +44 degrees").assert(politeLiveRegion)
    }

    @Test
    fun `a move ending says nothing`() {
        composeRule.runOnIdle { feedback.onEditingBegin(node, NodeEditingKind.Move) }
        composeRule.runOnIdle { feedback.onEditingEnd(node, NodeEditingKind.Move) }

        assertSilent()
    }
    @Test
    fun `a third finger restarting a pinch preserves its captured end value`() {
        composeRule.runOnIdle {
            node.scale = Scale(1.17f)
            feedback.onEditingBegin(node, NodeEditingKind.Scale)
        }
        composeRule.runOnIdle {
            feedback.onEditingEnd(node, NodeEditingKind.Scale)
            node.scale = Scale(1.42f)
            feedback.onEditingBegin(node, NodeEditingKind.Scale)
        }
        composeRule.onNodeWithContentDescription("Scale 117 percent").assert(politeLiveRegion)
    }

    @Test
    fun `an end announcement expires after three seconds`() {
        composeRule.runOnIdle {
            feedback.onEditingBegin(node, NodeEditingKind.Rotate)
            feedback.onRotationEdited(node, 44f)
        }
        composeRule.runOnIdle { feedback.onEditingEnd(node, NodeEditingKind.Rotate) }
        composeRule.onNodeWithContentDescription("Rotation +44 degrees").assert(politeLiveRegion)
        composeRule.mainClock.autoAdvance = false
        composeRule.mainClock.advanceTimeBy(2_000)
        composeRule.onNodeWithContentDescription("Rotation +44 degrees").assert(politeLiveRegion)
        composeRule.mainClock.advanceTimeBy(1_100)
        assertSilent()
    }

    @Test
    fun `simultaneous twist and pinch ends keep both captured values`() {
        composeRule.runOnIdle {
            feedback.onEditingBegin(node, NodeEditingKind.Rotate)
            feedback.onRotationEdited(node, 44f)
            node.scale = Scale(1.17f)
            feedback.onEditingBegin(node, NodeEditingKind.Scale)
        }
        composeRule.runOnIdle {
            feedback.onEditingEnd(node, NodeEditingKind.Rotate)
            feedback.onEditingEnd(node, NodeEditingKind.Scale)
        }
        composeRule.onNodeWithContentDescription("Rotation +44 degrees, Scale 117 percent")
            .assert(politeLiveRegion)
    }

}

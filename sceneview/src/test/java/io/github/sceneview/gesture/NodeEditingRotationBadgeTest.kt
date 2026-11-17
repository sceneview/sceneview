package io.github.sceneview.gesture

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import io.github.sceneview.math.Rotation
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
 * The rotation badge and its polite live region exist only while a twist is active: a
 * selected node at rest, or one being moved or scaled, shows and announces no angle.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], instrumentedPackages = ["com.google.android.filament"])
class NodeEditingRotationBadgeTest {

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

    @Before
    fun setUp() {
        node = Node(FilamentWithoutJni.engine())
        feedback = NodeEditingFeedbackState(node, scaleBaseline = 1f)
        composeRule.setContent {
            RotationBadge(state = feedback, colors = NodeEditingOverlayColors())
        }
    }

    private fun assertNoRotationBadge() {
        composeRule.onAllNodesWithContentDescription("Rotation", substring = true)
            .assertCountEquals(0)
        composeRule.onAllNodes(politeLiveRegion).assertCountEquals(0)
    }

    @Test
    fun `no badge nor announcement at rest, even after an application rotation`() {
        assertNoRotationBadge()

        composeRule.runOnIdle {
            node.rotation = Rotation(y = -22f)
            feedback.refreshRotationFromNode()
        }

        assertNoRotationBadge()
    }

    @Test
    fun `no badge while pressed, moved or scaled`() {
        composeRule.runOnIdle { feedback.onEditingPressed(node) }
        assertNoRotationBadge()

        composeRule.runOnIdle { feedback.onEditingBegin(node, NodeEditingKind.Move) }
        assertNoRotationBadge()

        composeRule.runOnIdle { feedback.onEditingBegin(node, NodeEditingKind.Scale) }
        assertNoRotationBadge()
    }

    @Test
    fun `badge and live region appear during the twist and leave with it`() {
        composeRule.runOnIdle {
            feedback.onEditingBegin(node, NodeEditingKind.Rotate)
            feedback.onRotationEdited(node, 12f)
        }

        composeRule.onNodeWithContentDescription("Rotation +12°").assert(politeLiveRegion)
        composeRule.onAllNodes(politeLiveRegion).assertCountEquals(1)

        composeRule.runOnIdle { feedback.onEditingEnd(node, NodeEditingKind.Rotate) }

        assertNoRotationBadge()
    }
}

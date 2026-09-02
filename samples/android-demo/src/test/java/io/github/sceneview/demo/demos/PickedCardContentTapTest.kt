package io.github.sceneview.demo.demos

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.sceneview.demo.theme.SceneViewDemoTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins #3422: the "Tap me" interaction on the [PickingAndCollisionDemo] card must fire
 * ONLY from the card's `Button`, not from a tap anywhere else on the card.
 *
 * Before the fix, `PickedCardContent` wrapped its content in `Card(onClick = onTap, …)`.
 * A clickable `Card` merges its descendants' semantics (the same mechanism that lets a
 * test — or TalkBack — treat a clickable list row and its label as one target), so
 * `onNodeWithText(title)` resolved to a node that carried the *card's* click action and
 * `performClick()` on it fired `onTap` — i.e. tapping the title counted exactly like
 * tapping the button. Run on pure JVM via Robolectric — no emulator needed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PickedCardContentTapTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun tappingTheButton_invokesOnTap() {
        var tapCount = 0
        composeRule.setContent {
            SceneViewDemoTheme {
                PickedCardContent(
                    title = "Live Compose in 3D",
                    highlighted = 0,
                    total = 5,
                    tapCount = tapCount,
                    containerRole = CardRole.Front,
                    onTap = { tapCount++ },
                )
            }
        }

        composeRule.onNodeWithText("Tap me").assertHasClickAction()
        composeRule.onNodeWithText("Tap me").performClick()

        assertEquals(1, tapCount)
    }

    @Test
    fun tappingTheTitle_hasNoClickAction_andDoesNotInvokeOnTap() {
        var tapCount = 0
        composeRule.setContent {
            SceneViewDemoTheme {
                PickedCardContent(
                    title = "Live Compose in 3D",
                    highlighted = 0,
                    total = 5,
                    tapCount = tapCount,
                    containerRole = CardRole.Front,
                    onTap = { tapCount++ },
                )
            }
        }

        // The regression this pins: the title used to inherit the outer Card's click
        // action through merged semantics. The card is a plain, non-clickable Card now,
        // so the title carries no click action of its own to perform.
        composeRule.onNodeWithText("Live Compose in 3D").assertHasNoClickAction()

        assertEquals(0, tapCount)
    }

    @Test
    fun tappingTheShapesCounter_hasNoClickAction() {
        var tapCount = 0
        composeRule.setContent {
            SceneViewDemoTheme {
                PickedCardContent(
                    title = "Live Compose in 3D",
                    highlighted = 2,
                    total = 5,
                    tapCount = tapCount,
                    containerRole = CardRole.Front,
                    onTap = { tapCount++ },
                )
            }
        }

        // Same regression as the title, pinned on a second piece of card content so a
        // fix that only special-cased the title would not slip through.
        composeRule.onNodeWithText("2 / 5 shapes lit").assertHasNoClickAction()
    }
}

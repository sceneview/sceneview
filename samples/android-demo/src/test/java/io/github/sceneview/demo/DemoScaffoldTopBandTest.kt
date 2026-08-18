package io.github.sceneview.demo

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import io.github.sceneview.demo.theme.SceneViewDemoTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The mirror of [DemoScaffoldBottomBandTest], for the edge that had no owner.
 *
 * ## The regression this pins (#3237)
 *
 * The bottom band had a slot, a reserve and a gate. The top band had none of
 * the three, and every demo negotiated it alone: a survey found 35
 * `.align(Alignment.Top*)` calls across the demos directory in **three mutually
 * incompatible inset conventions** — some applying
 * `windowInsetsPadding(systemBars)`, some a bare `padding(top = 8.dp)`, some
 * nothing at all — inside the same `Box`, in the same app. `safeDrawing` did not
 * appear once in the repository.
 *
 * The consequence was not subtle and it was not one bug. A pill written with no
 * inset sat under the status bar. A pill written *with* one sat a status bar
 * lower than its neighbour, because `Scaffold` hands its body a `PaddingValues`
 * that already covers the bars — so applying them again double-counted. Two
 * demos, both "correct" by their own comment, drew the same pill in two
 * different places.
 *
 * So the two things asserted here are the two things that were missing:
 *
 * 1. **One frame.** The scaffold body calls `consumeWindowInsets(padding)`, so a
 *    child inside `topOverlay` that writes `windowInsetsPadding` gets the same
 *    geometry as one that writes nothing. That is what makes migrating a demo's
 *    overlay into the slot a visual no-op, and it is why the migration could be
 *    done in bulk at all.
 * 2. **Two children stack.** The slot is a Column, so a second banner lands
 *    *below* the first whatever the first one measured — replacing the
 *    `padding(top = 8.dp)` / `padding(top = 56.dp)` pairs that demos were
 *    hand-computing from a pill height at font scale 1.0, and that collided the
 *    moment the scale grew.
 *
 * Both are asserted on measured bounds at three font scales, for the reason the
 * sibling file gives: a clearance defect appears only in the configurations
 * nobody screenshots.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DemoScaffoldTopBandTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun topOverlay_stacksItsChildren_atDefaultFontScale() {
        assertChildrenStack(fontScale = 1.0f)
    }

    @Test
    fun topOverlay_stacksItsChildren_atLargeFontScale() {
        // The scale at which the hand-computed `padding(top = 56.dp)` stacks
        // broke: the first pill grows past the constant the second was offset by.
        assertChildrenStack(fontScale = 1.3f)
    }

    @Test
    fun topOverlay_stacksItsChildren_atMaximumFontScale() {
        assertChildrenStack(fontScale = 2.0f)
    }

    @Test
    fun topOverlay_appliesOneInsetFrame_whicheverSpellingAChildUses() {
        // The root cause, stated as an assertion. `consumeWindowInsets(padding)`
        // on the scaffold body means a child re-applying the system-bar inset
        // lands in the same place as a child that applies nothing. Before it,
        // these two probes were a status bar apart — and both spellings were
        // live in the demos directory, each with a comment explaining why it was
        // the right one.
        composeRule.setContent {
            ScaledDensity(1.0f) {
                SceneViewDemoTheme(darkTheme = false) {
                    DemoScaffold(
                        title = "Top",
                        onBack = {},
                        topOverlay = {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(24.dp)
                                    .testTag(PROBE_PLAIN),
                            )
                        },
                    ) {}
                }
            }
        }
        composeRule.waitForIdle()
        val plain = composeRule.onNodeWithTag(PROBE_PLAIN).getUnclippedBoundsInRoot()
        val slot = composeRule.onNodeWithTag(DemoScaffoldTestTags.TOP_OVERLAY)
            .getUnclippedBoundsInRoot()

        assertTrue(
            "the first child of topOverlay starts at ${plain.top} but the slot itself " +
                "starts at ${slot.top}. The slot owns the gutter and the inset; a child " +
                "that adds neither must sit flush at the slot's content edge.",
            plain.top >= slot.top,
        )
        assertTrue(
            "the top overlay starts at ${slot.top}, at or above the top app bar. The " +
                "scaffold's body padding already clears the bar, so a slot that starts " +
                "there means the padding stopped being applied — every demo's status " +
                "pill would be drawn over the title.",
            slot.top > 0.dp,
        )
    }

    @Test
    fun topOverlay_reservesTheAssetSourceChip() {
        // The top band's mirror of `settingsFabReservedSpace`. The chip is a dot
        // plus a *translated string*, so no constant survives a locale change —
        // the scope carries the measured width instead, and a demo that spends it
        // must end up clear of the chip.
        composeRule.setContent {
            ScaledDensity(1.3f) {
                SceneViewDemoTheme(darkTheme = false) {
                    DemoScaffold(
                        title = "Top",
                        onBack = {},
                        assetSource = AssetSourceState.Bundled,
                        topOverlay = {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(end = assetSourceChipReservedSpace)
                                    .height(32.dp)
                                    .testTag(PROBE_PLAIN),
                            )
                        },
                    ) {}
                }
            }
        }
        composeRule.waitForIdle()
        val overlay = composeRule.onNodeWithTag(PROBE_PLAIN).getUnclippedBoundsInRoot()
        val chip = composeRule
            .onNodeWithTag(DemoScaffoldTestTags.ASSET_SOURCE_CHIP)
            .getUnclippedBoundsInRoot()

        assertTrue(
            "the top overlay ends at ${overlay.right} but the asset-source chip starts " +
                "at ${chip.left} — they overlap by ${overlay.right - chip.left}. The " +
                "reserve is measured from the real chip; if this fails, something " +
                "stopped feeding that measurement into assetSourceChipReservedSpace.",
            overlay.right <= chip.left,
        )
    }

    private fun assertChildrenStack(fontScale: Float) {
        val (first, second) = measureTwoChildren(fontScale)
        assertTrue(
            "at fontScale $fontScale the second top-overlay child starts at " +
                "${second.top} while the first still runs to ${first.bottom} — they " +
                "overlap by ${first.bottom - second.top}. The slot is a Column; if this " +
                "fails, something is positioning a child by hand again instead of " +
                "letting the Column measure the one above it.",
            second.top >= first.bottom,
        )
    }

    private fun measureTwoChildren(fontScale: Float): Pair<DpRect, DpRect> {
        composeRule.setContent {
            ScaledDensity(fontScale) {
                SceneViewDemoTheme(darkTheme = false) {
                    DemoScaffold(
                        title = "Top",
                        onBack = {},
                        topOverlay = {
                            // A realistic first tenant: a status pill whose height
                            // follows its text, which is exactly what the old
                            // `padding(top = 56.dp)` stacks could not anticipate.
                            Text(
                                text = "Tracking lost — move the phone slowly to find " +
                                    "a surface again",
                                modifier = Modifier.testTag(PROBE_FIRST),
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(24.dp)
                                    .testTag(PROBE_SECOND),
                            )
                        },
                    ) {}
                }
            }
        }
        composeRule.waitForIdle()
        return composeRule.onNodeWithTag(PROBE_FIRST).getUnclippedBoundsInRoot() to
            composeRule.onNodeWithTag(PROBE_SECOND).getUnclippedBoundsInRoot()
    }

    @Composable
    private fun ScaledDensity(fontScale: Float, content: @Composable () -> Unit) {
        val density = LocalDensity.current
        CompositionLocalProvider(
            LocalDensity provides Density(density.density, fontScale),
            content = content,
        )
    }

    private companion object {
        const val PROBE_PLAIN = "top-overlay-probe-plain"
        const val PROBE_FIRST = "top-overlay-probe-first"
        const val PROBE_SECOND = "top-overlay-probe-second"
    }
}

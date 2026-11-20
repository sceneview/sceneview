package io.github.sceneview.demo.support

import android.graphics.Bitmap
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.unit.Density
import io.github.sceneview.demo.theme.SceneViewDemoTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The actual sheet body: local prices, selection, fallback, exits and a persistent thank-you. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-notnight-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SupportSheetTest {
    @get:Rule val rule = createComposeRule()

    private val prices = mapOf(
        "support_tiny" to "$0.99", "support_small" to "$2.99",
        "support_medium" to "$9.99", "support_large" to "$24.99",
    )

    @Test fun light() = checkReady(dark = false)

    @Test
    @Config(qualifiers = "w360dp-h800dp-night-xhdpi")
    fun dark() = checkReady(dark = true)

    @Test fun lightWithLargeText() = checkReady(dark = false, fontScale = 1.3f)

    @Test
    @Config(qualifiers = "w360dp-h800dp-night-xhdpi")
    fun darkWithLargeText() = checkReady(dark = true, fontScale = 1.3f)

    private fun checkReady(dark: Boolean, fontScale: Float = 1f) {
        val launches = mutableListOf<SupportTier>()
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                SceneViewDemoTheme(darkTheme = dark) {
                    Surface {
                        SupportSheetContent(
                            mode = SupportSheetMode.Prompt,
                            availability = SupportBilling.Availability.Ready,
                            prices = prices,
                            inFlight = null,
                            outcome = null,
                            onTierClick = launches::add,
                            onOpenCollectiveClick = {}, onNotNow = {}, onOptOut = {}, onDone = {},
                        )
                    }
                }
            }
        }
        rule.onNodeWithText("Tip $0.99").assertIsDisplayed()
        rule.onNodeWithText("Small thanks").assertIsSelected()
        listOf("Small thanks", "Thanks", "Big thanks", "Huge thanks", "Don't ask again", "Not now").forEach { text ->
            val layouts = mutableListOf<TextLayoutResult>()
            rule.onNodeWithText(text, useUnmergedTree = true).assertIsDisplayed()
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue("$text has a layout", layouts.isNotEmpty())
            assertFalse("$text clipped at $fontScale", layouts.single().hasVisualOverflow)
        }
        capture("ready-${if (dark) "dark" else "light"}-$fontScale")
        rule.onNodeWithText("Huge thanks").performClick().assertIsSelected()
        rule.onNodeWithText("Small thanks").assertIsNotSelected()
        assertTrue("Selecting another tier does not launch Play", launches.isEmpty())
        rule.onNodeWithText("Tip $24.99").performClick()
        assertEquals(listOf(SupportTier.Large), launches)
        rule.onNodeWithText("Small thanks").performClick()
        rule.onNodeWithText("Tip $0.99").performClick()
        assertEquals(listOf(SupportTier.Large, SupportTier.Tiny), launches)
    }

    @Test
    fun missingTinyUsesSmallAndKeepsTheLocalizedPrice() {
        var launched: SupportTier? = null
        var links = 0
        rule.setContent {
            SceneViewDemoTheme {
                Surface {
                    SupportSheetContent(
                        mode = SupportSheetMode.Menu,
                        availability = SupportBilling.Availability.Ready,
                        prices = mapOf("support_small" to "R$ 4,99"),
                        inFlight = null, outcome = null,
                        onTierClick = { launched = it },
                        onOpenCollectiveClick = { links++ }, onNotNow = {}, onOptOut = {}, onDone = {},
                    )
                }
            }
        }
        rule.onNodeWithText("Small thanks").assertDoesNotExist()
        rule.onNodeWithText("Big thanks").assertDoesNotExist()
        rule.onNodeWithText("Huge thanks").assertDoesNotExist()
        rule.onNodeWithText("Thanks").assertIsSelected()
        rule.onNodeWithText("Not now").assertDoesNotExist()
        rule.onNodeWithText("Don't ask again").assertDoesNotExist()
        rule.onNodeWithText("Tip R$ 4,99").performClick()
        assertEquals(SupportTier.Small, launched)
        rule.onNodeWithText("Building an app with SceneView? Sponsor as a team →").performClick()
        assertEquals(1, links)
    }

    @Test
    fun unavailableShowsOnlyOpenCollectiveAndReminderExits() {
        var links = 0
        var notNow = 0
        var never = 0
        rule.setContent {
            SceneViewDemoTheme {
                Surface {
                    SupportSheetContent(
                        mode = SupportSheetMode.Prompt,
                        availability = SupportBilling.Availability.Unavailable,
                        prices = prices,
                        inFlight = null,
                        outcome = SupportBilling.Outcome.Failed,
                        onTierClick = { error("No Play action when unavailable") },
                        onOpenCollectiveClick = { links++ },
                        onNotNow = { notNow++ }, onOptOut = { never++ }, onDone = {},
                    )
                }
            }
        }
        rule.onNodeWithText("Small thanks").assertDoesNotExist()
        rule.onNodeWithText("Google Play couldn't complete the payment. Please try again.").assertDoesNotExist()
        rule.onNodeWithText("One-time payment through Google Play.", substring = true).assertDoesNotExist()
        capture("unavailable")
        rule.onNodeWithText("Donate on Open Collective").assertIsDisplayed().performClick()
        rule.onNodeWithText("Not now").performClick()
        rule.onNodeWithText("Don't ask again").performClick()
        assertEquals(listOf(1, 1, 1), listOf(links, notNow, never))
    }

    @Test
    fun successfulPurchaseShowsThanksInTheSheetUntilDone() {
        val outcome = mutableStateOf<SupportBilling.Outcome?>(null)
        var done = 0
        rule.setContent {
            SceneViewDemoTheme {
                Surface {
                    SupportSheetContent(
                        mode = SupportSheetMode.Home,
                        availability = SupportBilling.Availability.Ready,
                        prices = prices, inFlight = null, outcome = outcome.value,
                        onTierClick = { outcome.value = SupportBilling.Outcome.Thanks },
                        onOpenCollectiveClick = {}, onNotNow = {}, onOptOut = {}, onDone = { done++ },
                    )
                }
            }
        }
        rule.onNodeWithText("Keep SceneView shipping.").assertIsDisplayed()
        rule.onNodeWithText("Tip $0.99").performClick()
        rule.onNodeWithText("Thank you! You're part of the next release.").assertIsDisplayed()
        rule.onNodeWithText("Tip $0.99").assertDoesNotExist()
        rule.mainClock.advanceTimeBy(30_000)
        rule.onNodeWithText("Thank you! You're part of the next release.").assertIsDisplayed()
        assertEquals(0, done)
        capture("thanks")
        rule.onNodeWithText("Done").performClick()
        assertEquals(1, done)
    }

    private fun capture(name: String) {
        val image = rule.onRoot().captureToImage().asAndroidBitmap()
        val file = File("build/support-sheet-renders/$name.png")
        requireNotNull(file.parentFile).mkdirs()
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}

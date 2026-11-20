package io.github.sceneview.demo.support

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import com.android.billingclient.api.BillingClient.BillingResponseCode
import io.github.sceneview.demo.R
import io.github.sceneview.demo.theme.SceneViewDemoTheme
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowToast

/** The sheet and its body: local prices, saved selection, fallback, exits and a persistent thank-you. */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34], qualifiers = "w360dp-h800dp-notnight-xhdpi",
    shadows = [ShadowSupportBillingClient::class],
)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SupportSheetTest {
    @get:Rule val rule = createComposeRule()

    @Before fun resetBilling() = resetSupportBillingForTest()

    @After
    fun clearBilling() {
        SupportBilling.qaPrices = null
        SupportBilling.qaUnavailable = false
        SupportBilling.sheetVisible = false
        SupportBilling.clearOutcome()
    }

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
            assertSupportTextFits(text, layouts.single(), fontScale)
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
        rule.setContent {
            SceneViewDemoTheme {
                Surface {
                    SupportSheetContent(
                        mode = SupportSheetMode.Menu,
                        availability = SupportBilling.Availability.Ready,
                        prices = mapOf("support_small" to "R$ 4,99"),
                        inFlight = null, outcome = null,
                        onTierClick = { launched = it },
                        onOpenCollectiveClick = { error("No external payment link alongside Play") },
                        onNotNow = {}, onOptOut = {}, onDone = {},
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
        rule.onNodeWithText("Building an app with SceneView? Sponsor as a team →").assertDoesNotExist()
        rule.onNodeWithText("Donate on Open Collective").assertDoesNotExist()
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


    @Test
    fun selectedTierSurvivesSavedStateRestoration() {
        val restoration = StateRestorationTester(rule)
        var launched: SupportTier? = null
        restoration.setContent {
            SceneViewDemoTheme {
                SupportSheetContent(
                    mode = SupportSheetMode.Home,
                    availability = SupportBilling.Availability.Ready,
                    prices = prices, inFlight = null, outcome = null,
                    onTierClick = { launched = it },
                    onOpenCollectiveClick = {}, onNotNow = {}, onOptOut = {}, onDone = {},
                )
            }
        }
        rule.onNodeWithText("Huge thanks").performClick().assertIsSelected()
        restoration.emulateSavedInstanceStateRestore()
        rule.onNodeWithText("Huge thanks").assertIsSelected()
        rule.onNodeWithText("Small thanks").assertIsNotSelected()
        rule.onNodeWithText("Tip $24.99").performClick()
        assertEquals(SupportTier.Large, launched)
    }

    @Test
    fun longLocalPricesFitEqualHeightTilesWithLargeText() {
        val longPrices = mapOf(
            "support_tiny" to "99,00 ₹", "support_small" to "299,00 ₹",
            "support_medium" to "1 099,00 ₹", "support_large" to "2 499,00 ₹",
        )
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.3f)) {
                SceneViewDemoTheme {
                    Surface {
                        SupportSheetContent(
                            mode = SupportSheetMode.Prompt,
                            availability = SupportBilling.Availability.Ready,
                            prices = longPrices, inFlight = null, outcome = null,
                            onTierClick = {}, onOpenCollectiveClick = {}, onNotNow = {}, onOptOut = {}, onDone = {},
                        )
                    }
                }
            }
        }
        val heights = listOf("Small thanks", "Thanks", "Big thanks", "Huge thanks").map {
            val bounds = rule.onNodeWithText(it).assertIsDisplayed().getUnclippedBoundsInRoot()
            bounds.bottom - bounds.top
        }
        assertEquals("All four tiles have the same height", 1, heights.distinct().size)
        longPrices.values.forEach { price ->
            val layouts = mutableListOf<TextLayoutResult>()
            rule.onNodeWithText(price, useUnmergedTree = true).assertIsDisplayed()
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            assertEquals("$price stays on one line", 1, layout.lineCount)
            assertFalse("$price fits at fontScale 1.3", layout.hasVisualOverflow)
            assertFalse("$price is not ellipsized", layout.isLineEllipsized(0))
        }
        capture("ready-long-price-light-1.3")
    }

    @Test
    fun fullSheetKeepsThanksUntilDoneAndClearsItOnClose() {
        val dismissed = showThanksSheet()
        rule.mainClock.advanceTimeBy(30_000)
        rule.runOnIdle {
            SupportBilling.onPurchasesUpdated(billingResult(BillingResponseCode.ERROR), null)
        }
        rule.onNodeWithText("Thank you! You're part of the next release.").assertIsDisplayed()
        assertEquals(0, dismissed.value)
        rule.onNodeWithText("Done").performClick()
        rule.onNodeWithText("Done").assertDoesNotExist()
        assertEquals(1, dismissed.value)
        assertEquals(null, SupportBilling.outcome)
    }

    @Test
    fun fullSheetBackActsAsDone() {
        val dismissed = showThanksSheet()
        rule.runOnUiThread {
            val dialog = requireNotNull(ShadowDialog.getLatestDialog())
            dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
            dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
        }
        rule.waitForIdle()
        assertEquals(1, dismissed.value)
        assertEquals(null, SupportBilling.outcome)
        rule.onNodeWithText("Done").assertDoesNotExist()
    }

    @Test
    fun fullSheetScrimActsAsDone() {
        val dismissed = showThanksSheet()
        rule.onNode(isDialog()).performTouchInput { click(Offset(1f, 1f)) }
        rule.waitForIdle()
        assertEquals(1, dismissed.value)
        assertEquals(null, SupportBilling.outcome)
    }

    private fun showThanksSheet(): MutableState<Int> {
        val dismissed = mutableStateOf(0)
        val visible = mutableStateOf(true)
        SupportBilling.qaPrices = prices
        rule.setContent {
            SceneViewDemoTheme {
                if (visible.value) SupportSheet(
                    mode = SupportSheetMode.Home,
                    onDismiss = { dismissed.value++; visible.value = false },
                )
            }
        }
        rule.onNodeWithText("Tip $0.99").assertIsDisplayed()
        rule.runOnIdle { SupportBilling.forceOutcome(SupportBilling.Outcome.Thanks) }
        rule.onNodeWithText("Thank you! You're part of the next release.").assertIsDisplayed()
        return dismissed
    }

    @Test
    fun unavailableWithoutBrowserShowsTheExistingMessage() {
        SupportBilling.qaUnavailable = true
        rule.setContent {
            val baseContext = LocalContext.current
            val context = object : ContextWrapper(baseContext) {
                override fun startActivity(intent: Intent) { throw ActivityNotFoundException("No browser") }
            }
            CompositionLocalProvider(LocalContext provides context) {
                SceneViewDemoTheme { SupportSheet(mode = SupportSheetMode.Home, onDismiss = {}) }
            }
        }
        rule.onNodeWithText("Donate on Open Collective").performClick()
        assertEquals(
            ApplicationProvider.getApplicationContext<Context>().getString(
                R.string.about_no_browser, SUPPORT_OPEN_COLLECTIVE_URL,
            ),
            ShadowToast.getTextOfLatestToast(),
        )
    }

    private fun capture(name: String) {
        val image = rule.onRoot().captureToImage().asAndroidBitmap()
        val file = File("build/support-sheet-renders/$name.png")
        requireNotNull(file.parentFile).mkdirs()
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}

/** Check glyph widths: a paragraph may keep its parent's width after Text wraps to its content. */
internal fun assertSupportTextFits(text: String, layout: TextLayoutResult, fontScale: Float) {
    assertFalse("$text clipped vertically at $fontScale", layout.didOverflowHeight)
    repeat(layout.lineCount) { line ->
        assertFalse("$text ellipsized at $fontScale", layout.isLineEllipsized(line))
        val lineWidth = layout.getLineRight(line) - layout.getLineLeft(line)
        // Text measures to whole pixels while its glyph positions retain fractional pixels.
        assertTrue("$text clipped horizontally at $fontScale", lineWidth <= layout.size.width + 1f)
    }
    assertEquals("$text is fully laid out", text.length, layout.getLineEnd(layout.lineCount - 1))
}

package io.github.sceneview.demo.support

import com.android.billingclient.api.BillingClient.BillingResponseCode
import io.github.sceneview.demo.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportSheetStateTest {
    private val prices = SupportTier.entries.reversed().associate { it.productId to "localized ${it.productId}" }

    @Test
    fun `preselects tiny in tier order regardless of Play response order`() {
        val state = supportSheetState(SupportBilling.Availability.Ready, prices, null)
        assertEquals(SupportSheetAction.Tip, state.action)
        assertEquals(SupportTier.entries, state.offers.map { it.tier })
        assertEquals(SupportTier.Tiny, state.selected?.tier)
        assertEquals(prices[SupportTier.Tiny.productId], state.selected?.price)
    }

    @Test
    fun `missing optional products are hidden and smallest returned tier is selected`() {
        SupportTier.entries.forEachIndexed { index, tier ->
            val returned = prices.filterKeys { id -> SupportTier.entries.drop(index).any { it.productId == id } }
            val state = supportSheetState(SupportBilling.Availability.Ready, returned, null)
            assertEquals(tier, state.selected?.tier)
            assertEquals(SupportTier.entries.drop(index), state.offers.map { it.tier })
        }
    }

    @Test
    fun `selection survives updates while its product is still available`() {
        val state = supportSheetState(SupportBilling.Availability.Ready, prices, null, SupportTier.Large)
        assertEquals(SupportTier.Large, state.selected?.tier)
        val reduced = supportSheetState(
            SupportBilling.Availability.Ready, prices - SupportTier.Large.productId, null, SupportTier.Large,
        )
        assertEquals(SupportTier.Tiny, reduced.selected?.tier)
    }

    @Test
    fun `unavailable or no usable product details offers only Open Collective`() {
        listOf(
            supportSheetState(SupportBilling.Availability.Unavailable, prices, SupportBilling.Outcome.Failed),
            supportSheetState(SupportBilling.Availability.Ready, emptyMap(), null),
            supportSheetState(SupportBilling.Availability.Ready, mapOf("support_tiny" to null), null),
            supportSheetState(SupportBilling.Availability.Ready, mapOf("support_tiny" to " "), null),
        ).forEach { state ->
            assertEquals(SupportSheetAction.OpenCollective, state.action)
            assertTrue(state.offers.isEmpty())
            assertNull(state.selected)
        }
    }

    @Test
    fun `connecting waits rather than displaying unavailable amounts`() {
        val state = supportSheetState(SupportBilling.Availability.Connecting, prices, null)
        assertEquals(SupportSheetAction.Loading, state.action)
        assertTrue(state.offers.isEmpty())
    }

    @Test
    fun `completed purchase thanks in the sheet even without prices or a connection`() {
        SupportBilling.Availability.entries.forEach { availability ->
            val state = supportSheetState(availability, emptyMap(), SupportBilling.Outcome.Thanks)
            assertEquals(SupportSheetAction.Thanks, state.action)
            assertTrue(state.offers.isEmpty())
        }
    }

    @Test
    fun `pending stays distinct and a failed purchase keeps the selected retry`() {
        assertEquals(
            SupportSheetAction.Pending,
            supportSheetState(SupportBilling.Availability.Ready, prices, SupportBilling.Outcome.Pending).action,
        )
        val retry = supportSheetState(
            SupportBilling.Availability.Ready, prices, SupportBilling.Outcome.Failed, SupportTier.Medium,
        )
        assertEquals(SupportSheetAction.Tip, retry.action)
        assertEquals(SupportTier.Medium, retry.selected?.tier)
    }

    @Test
    fun `both unsupported billing responses use the fallback`() {
        assertTrue(supportBillingUnavailable(BillingResponseCode.BILLING_UNAVAILABLE))
        assertTrue(supportBillingUnavailable(BillingResponseCode.FEATURE_NOT_SUPPORTED))
        assertFalse(supportBillingUnavailable(BillingResponseCode.ERROR))
        assertFalse(supportBillingUnavailable(BillingResponseCode.USER_CANCELED))
    }

    @Test
    fun `headline follows the moment of value or manual entry`() {
        assertEquals(R.string.support_sheet_title_ar, supportHeadline(SupportSheetMode.Prompt, SupportMoment.ArPlacement))
        assertEquals(R.string.support_sheet_title_sample, supportHeadline(SupportSheetMode.Prompt, SupportMoment.Sample))
        listOf(SupportSheetMode.Home, SupportSheetMode.Menu, SupportSheetMode.About).forEach { mode ->
            SupportMoment.entries.forEach { moment ->
                assertEquals(R.string.support_sheet_title, supportHeadline(mode, moment))
            }
        }
    }
}

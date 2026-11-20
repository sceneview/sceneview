package io.github.sceneview.demo.support

import com.android.billingclient.api.BillingClient.BillingResponseCode
import io.github.sceneview.demo.telemetry.AnalyticsEvent
import io.github.sceneview.demo.telemetry.SupportPromptSource
import io.github.sceneview.demo.telemetry.SupportResult
import org.junit.Assert.assertEquals
import org.junit.Test

class SupportPromptAnalyticsTest {

    @Test
    fun `shown sources preserve the historical event name and values`() {
        assertEquals(
            listOf("auto", "settings", "home", "menu"),
            SupportPromptSource.entries.map {
                val event = AnalyticsEvent.SupportPromptShown(it)
                assertEquals("support_prompt_shown", event.name)
                event.params().getValue("source")
            },
        )
    }

    @Test
    fun `every result keeps its support event name and value`() {
        val expected = listOf(
            "tier_tiny", "tier_small", "tier_medium", "tier_large", "purchase_cancelled",
            "purchase_failed", "purchase_already_owned", "purchase_pending", "open_collective", "not_now", "never", "dismissed",
        )
        assertEquals(
            expected,
            SupportResult.entries.map {
                val event = AnalyticsEvent.SupportPromptResult(it)
                assertEquals("support_prompt_result", event.name)
                event.params().getValue("result")
            },
        )
    }

    @Test
    fun `one display logs one shown event and one result`() {
        SupportResult.entries.forEach { result ->
            val events = mutableListOf<AnalyticsEvent>()
            val session = SupportPromptAnalyticsSession(SupportPromptSource.Auto, events::add)

            session.shown()
            session.shown()
            if (result == SupportResult.NotNow || result == SupportResult.Never) {
                session.close(result)
            } else {
                if (result != SupportResult.Dismissed) session.purchaseResult(result)
                session.close()
            }
            session.purchaseResult(SupportResult.PurchaseFailed)
            session.close(SupportResult.Never)

            assertEquals(
                listOf(
                    AnalyticsEvent.SupportPromptShown(SupportPromptSource.Auto),
                    AnalyticsEvent.SupportPromptResult(result, SupportPromptSource.Auto),
                ),
                events,
            )
        }
    }

    @Test
    fun `a completed tier wins over later billing callbacks`() {
        val events = mutableListOf<AnalyticsEvent>()
        val session = SupportPromptAnalyticsSession(SupportPromptSource.Settings, events::add)
        session.shown()
        session.purchaseResult(SupportResult.PurchaseFailed)
        session.purchaseResult(SupportResult.TierMedium)
        session.purchaseResult(SupportResult.PurchaseCancelled)
        session.close()

        assertEquals(
            AnalyticsEvent.SupportPromptResult(SupportResult.TierMedium, SupportPromptSource.Settings),
            events.last(),
        )
    }

    @Test
    fun `every sheet entry carries its precise source without losing the historical source`() {
        val expected = mapOf(
            SupportSheetMode.Prompt to "prompt",
            SupportSheetMode.Home to "home",
            SupportSheetMode.Menu to "menu",
            SupportSheetMode.About to "about",
        )
        expected.forEach { (mode, entry) ->
            val source = mode.analyticsSource
            val shown = AnalyticsEvent.SupportPromptShown(source).params()
            assertEquals(entry, shown["entry_source"])
            assertEquals(source.value, shown["source"])
            assertEquals(
                mapOf("result" to "dismissed", "entry_source" to entry),
                AnalyticsEvent.SupportPromptResult(SupportResult.Dismissed, source).params(),
            )
            assertEquals(
                mapOf("product_id" to SupportTier.Tiny.productId, "entry_source" to entry),
                AnalyticsEvent.SupportPurchasePending(SupportTier.Tiny.productId, source).params(),
            )
            val events = mutableListOf<AnalyticsEvent>()
            val session = SupportPromptAnalyticsSession(source, events::add)
            session.shown()
            SupportTier.entries.forEach { tier -> session.purchaseLaunched(tier.productId) }
            val launches = events.filterIsInstance<AnalyticsEvent.SupportPurchaseLaunched>()
            assertEquals(SupportTier.entries.size, launches.size)
            launches.zip(SupportTier.entries).forEach { (event, tier) ->
                assertEquals("support_prompt_purchase_launched", event.name)
                assertEquals(mapOf("product_id" to tier.productId, "entry_source" to entry), event.params())
            }
        }
    }

    @Test
    fun `already owned from either Play response path is its own sheet result`() {
        val events = mutableListOf<AnalyticsEvent>()
        val session = SupportPromptAnalyticsSession(SupportPromptSource.Home, events::add)
        session.shown()
        session.purchaseLaunched(SupportTier.Small.productId)
        session.purchaseResult(supportBillingFailure(BillingResponseCode.ITEM_ALREADY_OWNED))
        session.close()
        assertEquals(AnalyticsEvent.SupportPromptResult(SupportResult.PurchaseAlreadyOwned, SupportPromptSource.Home), events.last())
        assertEquals(SupportResult.PurchaseCancelled, supportBillingFailure(BillingResponseCode.USER_CANCELED))
        assertEquals(SupportResult.PurchaseFailed, supportBillingFailure(BillingResponseCode.ERROR))
    }

    @Test
    fun `pending is logged once per attempt and confirmation can still complete it`() {
        val events = mutableListOf<AnalyticsEvent>()
        val session = SupportPromptAnalyticsSession(SupportPromptSource.Menu, events::add)
        val product = SupportTier.Medium.productId
        session.shown()
        session.purchaseLaunched(product)
        session.purchasePending(product)
        session.purchasePending(product)
        session.purchaseResult(SupportResult.TierMedium)
        session.close()
        assertEquals(
            listOf(
                AnalyticsEvent.SupportPromptShown(SupportPromptSource.Menu),
                AnalyticsEvent.SupportPurchaseLaunched(product, SupportPromptSource.Menu),
                AnalyticsEvent.SupportPurchasePending(product, SupportPromptSource.Menu),
                AnalyticsEvent.SupportPromptResult(SupportResult.TierMedium, SupportPromptSource.Menu),
            ),
            events,
        )
        val pending = events.filterIsInstance<AnalyticsEvent.SupportPurchasePending>().single()
        assertEquals("support_prompt_purchase_pending", pending.name)
        assertEquals(mapOf("product_id" to product, "entry_source" to "menu"), pending.params())
    }

    @Test
    fun `closing while pending records pending rather than dismissed`() {
        val events = mutableListOf<AnalyticsEvent>()
        val session = SupportPromptAnalyticsSession(SupportPromptSource.Settings, events::add)
        session.shown()
        session.purchaseLaunched(SupportTier.Large.productId)
        session.purchasePending(SupportTier.Large.productId)
        session.close()
        assertEquals(AnalyticsEvent.SupportPromptResult(SupportResult.PurchasePending, SupportPromptSource.Settings), events.last())
    }

    @Test
    fun `pending arriving after sheet dismissal still logs its source`() {
        val events = mutableListOf<AnalyticsEvent>()
        val session = SupportPromptAnalyticsSession(SupportPromptSource.Auto, events::add)
        val product = SupportTier.Small.productId
        session.shown()
        session.purchaseLaunched(product)
        session.close()
        session.purchasePending(product)
        assertEquals(AnalyticsEvent.SupportPurchasePending(product, SupportPromptSource.Auto), events.last())
        assertEquals(1, events.filterIsInstance<AnalyticsEvent.SupportPromptResult>().size)
    }

    @Test
    fun `retrying an amount logs a new launch and pending event`() {
        val events = mutableListOf<AnalyticsEvent>()
        val session = SupportPromptAnalyticsSession(SupportPromptSource.Home, events::add)
        val product = SupportTier.Small.productId
        session.shown()
        repeat(2) {
            session.purchaseLaunched(product)
            session.purchasePending(product)
        }
        assertEquals(2, events.filterIsInstance<AnalyticsEvent.SupportPurchaseLaunched>().size)
        assertEquals(2, events.filterIsInstance<AnalyticsEvent.SupportPurchasePending>().size)
    }

    @Test
    fun `support event and parameter names meet GA4 limits`() {
        val events = listOf(
            AnalyticsEvent.SupportPromptShown(SupportPromptSource.Home),
            AnalyticsEvent.SupportPromptResult(SupportResult.PurchaseAlreadyOwned),
            AnalyticsEvent.SupportPurchaseLaunched("support_small", SupportPromptSource.Home),
            AnalyticsEvent.SupportPurchasePending("support_small", SupportPromptSource.Home),
        )
        val name = Regex("[a-z][a-z0-9_]{0,39}")
        events.forEach { event ->
            org.junit.Assert.assertTrue(event.name, name.matches(event.name))
            event.params().keys.forEach { org.junit.Assert.assertTrue(it, name.matches(it)) }
        }
    }


    @Test
    fun `Open Collective is one result with its source from every entry`() {
        SupportPromptSource.entries.forEach { source ->
            val events = mutableListOf<AnalyticsEvent>()
            val session = SupportPromptAnalyticsSession(source, events::add)
            session.shown()
            session.purchaseResult(SupportResult.PurchaseFailed)
            session.openCollective()
            session.openCollective()
            session.close()
            val result = events.filterIsInstance<AnalyticsEvent.SupportPromptResult>().single()
            assertEquals(mapOf("result" to "open_collective", "entry_source" to source.entrySource), result.params())
        }
    }

    @Test
    fun `tiny is a completed purchase and keeps its result`() {
        val events = mutableListOf<AnalyticsEvent>()
        val session = SupportPromptAnalyticsSession(SupportPromptSource.Home, events::add)
        session.shown()
        session.purchaseLaunched(SupportTier.Tiny.productId)
        session.purchaseResult(SupportTier.Tiny.analyticsResult)
        session.purchaseResult(SupportResult.PurchaseFailed)
        session.close()
        assertEquals(AnalyticsEvent.SupportPromptResult(SupportResult.TierTiny, SupportPromptSource.Home), events.last())
    }
}

package io.github.sceneview.demo.support

import io.github.sceneview.demo.telemetry.AnalyticsEvent
import io.github.sceneview.demo.telemetry.SupportPromptSource
import io.github.sceneview.demo.telemetry.SupportResult
import org.junit.Assert.assertEquals
import org.junit.Test

class SupportPromptAnalyticsTest {

    @Test
    fun `shown sources use the shared event name and values`() {
        assertEquals(
            listOf("auto", "settings"),
            SupportPromptSource.entries.map {
                val event = AnalyticsEvent.SupportPromptShown(it)
                assertEquals("support_prompt_shown", event.name)
                event.params().getValue("source")
            },
        )
    }

    @Test
    fun `every result uses the shared event name and value`() {
        val expected = listOf(
            "tier_small", "tier_medium", "tier_large", "purchase_cancelled",
            "purchase_failed", "not_now", "never", "dismissed",
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
                    AnalyticsEvent.SupportPromptResult(result),
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
            AnalyticsEvent.SupportPromptResult(SupportResult.TierMedium),
            events.last(),
        )
    }
}

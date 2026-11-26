package io.github.sceneview.demo.support

import io.github.sceneview.demo.telemetry.AnalyticsEvent
import io.github.sceneview.demo.telemetry.SupportPromptSource
import io.github.sceneview.demo.telemetry.SupportResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
        assertEquals(
            listOf("store", "open_collective", "not_now", "never", "dismissed"),
            SupportResult.entries.map {
                val event = AnalyticsEvent.SupportPromptResult(it)
                assertEquals("support_prompt_result", event.name)
                event.params().getValue("result")
            },
        )
    }

    @Test
    fun `no result names an amount or a payment outcome`() {
        val purchaseWords = Regex("tier|purchase|paid|pending|cancel|fail|owned|product|price")
        SupportResult.entries.forEach { assertTrue(it.value, !purchaseWords.containsMatchIn(it.value)) }
        SupportPromptSource.entries.forEach { source ->
            val params = AnalyticsEvent.SupportPromptResult(SupportResult.Store, source).params()
            assertEquals(setOf("result", "entry_source"), params.keys)
        }
    }

    @Test
    fun `one display logs one shown event and one result`() {
        SupportResult.entries.forEach { result ->
            val events = mutableListOf<AnalyticsEvent>()
            val session = SupportPromptAnalyticsSession(SupportPromptSource.Auto, events::add)

            session.shown()
            session.shown()
            when (result) {
                SupportResult.Store -> session.storeOpened().also { session.close() }
                SupportResult.OpenCollective -> session.openCollective()
                SupportResult.Dismissed -> session.close()
                else -> session.close(result)
            }
            session.storeOpened()
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
    fun `opening the Play sheet several times is still one store result`() {
        val events = mutableListOf<AnalyticsEvent>()
        val session = SupportPromptAnalyticsSession(SupportPromptSource.Menu, events::add)
        session.shown()
        repeat(3) { session.storeOpened() }
        session.close()
        assertEquals(
            listOf(
                AnalyticsEvent.SupportPromptShown(SupportPromptSource.Menu),
                AnalyticsEvent.SupportPromptResult(SupportResult.Store, SupportPromptSource.Menu),
            ),
            events,
        )
    }

    @Test
    fun `an explicit answer wins over an earlier visit to the Play sheet`() {
        val events = mutableListOf<AnalyticsEvent>()
        val session = SupportPromptAnalyticsSession(SupportPromptSource.Settings, events::add)
        session.shown()
        session.storeOpened()
        session.close(SupportResult.NotNow)
        assertEquals(AnalyticsEvent.SupportPromptResult(SupportResult.NotNow, SupportPromptSource.Settings), events.last())
    }

    @Test
    fun `nothing is logged before the sheet shows`() {
        val events = mutableListOf<AnalyticsEvent>()
        val session = SupportPromptAnalyticsSession(SupportPromptSource.Home, events::add)
        session.storeOpened()
        session.openCollective()
        session.close()
        assertEquals(emptyList<AnalyticsEvent>(), events)
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
        }
    }

    @Test
    fun `support event and parameter names meet GA4 limits`() {
        val events = listOf(
            AnalyticsEvent.SupportPromptShown(SupportPromptSource.Home),
            AnalyticsEvent.SupportPromptResult(SupportResult.Store, SupportPromptSource.Home),
        )
        val name = Regex("[a-z][a-z0-9_]{0,39}")
        events.forEach { event ->
            assertTrue(event.name, name.matches(event.name))
            event.params().keys.forEach { assertTrue(it, name.matches(it)) }
        }
    }

    @Test
    fun `Open Collective is one result with its source from every entry`() {
        SupportPromptSource.entries.forEach { source ->
            val events = mutableListOf<AnalyticsEvent>()
            val session = SupportPromptAnalyticsSession(source, events::add)
            session.shown()
            session.storeOpened()
            session.openCollective()
            session.openCollective()
            session.close()
            val result = events.filterIsInstance<AnalyticsEvent.SupportPromptResult>().single()
            assertEquals(mapOf("result" to "open_collective", "entry_source" to source.entrySource), result.params())
        }
    }
}

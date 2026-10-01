package io.github.sceneview.demo.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The analytics façade: opt-out gating, the taxonomy's limits, and link classification. */
class DemoAnalyticsTest {

    private class Recorder : DemoAnalytics {
        val events = mutableListOf<AnalyticsEvent>()
        val properties = mutableMapOf<UserProperty, String>()
        val collection = mutableListOf<Boolean>()
        override fun log(event: AnalyticsEvent) {
            events += event
        }
        override fun setUserProperty(property: UserProperty, value: String) {
            properties[property] = value
        }
        override fun setCollectionEnabled(enabled: Boolean) {
            collection += enabled
        }
        var resets = 0
        override fun resetData() {
            resets++
        }
    }

    private val recorder = Recorder()
    private var enabled = true
    private val analytics = GatedDemoAnalytics(recorder) { enabled }

    @Test
    fun `nothing is logged after the opt-out`() {
        analytics.log(AnalyticsEvent.SampleInteraction("cosmos", "galaxy"))
        enabled = false
        analytics.log(AnalyticsEvent.SampleInteraction("cosmos", "star"))
        analytics.setUserProperty(UserProperty.AppTheme, "dark")
        assertEquals(1, recorder.events.size)
        assertTrue(recorder.properties.isEmpty())
    }

    @Test
    fun `the opt-out itself always reaches the sink`() {
        enabled = false
        analytics.setCollectionEnabled(false)
        analytics.resetData()
        assertEquals(listOf(false), recorder.collection)
        assertEquals(1, recorder.resets)
    }

    @Test
    fun `a throwing sink never crashes the caller`() {
        val throwing = GatedDemoAnalytics(
            object : DemoAnalytics {
                override fun log(event: AnalyticsEvent) = error("boom")
                override fun setUserProperty(property: UserProperty, value: String) = error("boom")
                override fun setCollectionEnabled(enabled: Boolean) = error("boom")
                override fun resetData() = error("boom")
            },
        ) { true }
        throwing.log(AnalyticsEvent.PushPromptShown)
        throwing.setUserProperty(UserProperty.NotifEnabled, "true")
        throwing.setCollectionEnabled(false)
        throwing.resetData()
    }

    @Test
    fun `string values are capped at 100 characters, user properties at 36`() {
        analytics.log(AnalyticsEvent.SampleInteraction("model-viewer", "x".repeat(300)))
        assertEquals(100, (recorder.events.single().params()["control"] as String).length)
        analytics.setUserProperty(UserProperty.AppTheme, "y".repeat(80))
        assertEquals(36, recorder.properties.getValue(UserProperty.AppTheme).length)
    }

    @Test
    fun `user property names fit GA4's 24 characters`() {
        UserProperty.entries.forEach {
            assertTrue(it.key, it.key.length <= AnalyticsEvent.MAX_USER_PROPERTY_NAME)
            assertTrue(it.key, it.key.matches(Regex("[a-z][a-z0-9_]*")))
        }
    }

    @Test
    fun `model_load_failed reasons are the codes shared with iOS`() {
        assertEquals(
            listOf("decode_failed", "no_bounds", "asset_missing", "unknown"),
            ModelLoadFailure.entries.map { it.value },
        )
    }

    @Test
    fun `event names and parameters match the shared taxonomy`() {
        val cases = mapOf(
            AnalyticsEvent.ScreenView("cosmos", "Sample") to
                ("screen_view" to mapOf("screen_name" to "cosmos", "screen_class" to "Sample")),
            AnalyticsEvent.SampleOpen("cosmos", "showcase", OpenSource.Push) to
                ("sample_open" to mapOf("sample_id" to "cosmos", "category" to "showcase", "source" to "push")),
            AnalyticsEvent.SampleClose("cosmos", 42) to
                ("sample_close" to mapOf("sample_id" to "cosmos", "duration_s" to 42L)),
            AnalyticsEvent.SampleInteraction("cosmos", "burst") to
                ("sample_interaction" to mapOf("sample_id" to "cosmos", "control" to "burst")),
            AnalyticsEvent.ModelLoadFailed("model-viewer", ModelLoadFailure.NoBounds) to
                ("model_load_failed" to mapOf("sample_id" to "model-viewer", "reason" to "no_bounds")),
            AnalyticsEvent.OutboundLink(LinkTarget.GitHub) to ("outbound_link" to mapOf("target" to "github")),
            AnalyticsEvent.PushPromptShown to ("push_prompt_shown" to emptyMap()),
            AnalyticsEvent.PushPromptResult(PromptResult.NotNow) to
                ("push_prompt_result" to mapOf("result" to "not_now")),
            AnalyticsEvent.PushOpened("launch", "cosmos") to
                ("push_opened" to mapOf("campaign" to "launch", "sample_id" to "cosmos")),
            AnalyticsEvent.SettingsChanged("analytics", "false") to
                ("settings_changed" to mapOf("key" to "analytics", "value" to "false")),
        )
        cases.forEach { (event, expected) ->
            assertEquals(expected.first, event.name)
            assertEquals(expected.second, event.params())
            assertTrue(event.name.length <= AnalyticsEvent.MAX_NAME)
            assertTrue(event.name.matches(Regex("[a-z][a-z0-9_]*")))
        }
    }

    @Test
    fun `outbound links are classified by host`() {
        assertEquals(LinkTarget.GitHub, LinkTarget.of("https://github.com/sceneview/sceneview"))
        assertEquals(LinkTarget.GitHub, LinkTarget.of("https://gist.github.com/x"))
        assertEquals(LinkTarget.Docs, LinkTarget.of("https://sceneview.github.io/playground.html"))
        assertEquals(LinkTarget.Store, LinkTarget.of("market://details?id=com.google.android.aicore"))
        assertEquals(LinkTarget.Store, LinkTarget.of("https://play.google.com/store/apps/details?id=x"))
        assertEquals(LinkTarget.Other, LinkTarget.of("https://opencollective.com/sceneview"))
        assertEquals(LinkTarget.Other, LinkTarget.of("https://github.com.evil.example/"))
    }
}

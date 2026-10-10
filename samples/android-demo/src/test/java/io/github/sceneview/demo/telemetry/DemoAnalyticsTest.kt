package io.github.sceneview.demo.telemetry

import io.github.sceneview.demo.ALL_DEMOS
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DeepLinkRouter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
        val crashKeys = mutableMapOf<CrashKey, String>()
        override fun setCrashKey(key: CrashKey, value: String) {
            crashKeys[key] = value
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
    fun `crash keys follow the same switch, and are cut to a line`() {
        analytics.setCrashKey(CrashKey.GlRenderer, "Google SwiftShader")
        analytics.setCrashKey(CrashKey.GlVersion, "v".repeat(900))
        enabled = false
        analytics.setCrashKey(CrashKey.Abi, "x86_64")
        assertEquals("Google SwiftShader", recorder.crashKeys[CrashKey.GlRenderer])
        assertEquals(200, recorder.crashKeys.getValue(CrashKey.GlVersion).length)
        assertEquals(setOf(CrashKey.GlRenderer, CrashKey.GlVersion), recorder.crashKeys.keys)
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
                override fun setCrashKey(key: CrashKey, value: String) = error("boom")
                override fun setCollectionEnabled(enabled: Boolean) = error("boom")
                override fun resetData() = error("boom")
            },
        ) { true }
        throwing.log(AnalyticsEvent.PushPromptShown)
        throwing.setUserProperty(UserProperty.NotifEnabled, "true")
        throwing.setCrashKey(CrashKey.HeroSurface, "still")
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
                ("sample_open" to mapOf(
                    "sample_id" to "cosmos",
                    "entry_id" to "cosmos",
                    "category" to "showcase",
                    "source" to "push",
                )),
            AnalyticsEvent.SampleClose("cosmos", 42) to
                ("sample_close" to mapOf("sample_id" to "cosmos", "duration_s" to 42L)),
            AnalyticsEvent.SampleInteraction("cosmos", "burst") to
                ("sample_interaction" to mapOf("sample_id" to "cosmos", "control" to "burst")),
            AnalyticsEvent.ModelLoadFailed("model-viewer", ModelLoadFailure.NoBounds) to
                ("model_load_failed" to mapOf("sample_id" to "model-viewer", "reason" to "no_bounds")),
            AnalyticsEvent.ArTrackingReady("ar_view") to ("ar_tracking_ready" to mapOf("sample_id" to "ar_view")),
            AnalyticsEvent.ArSessionFailed("ar-placement", "camera_not_available") to
                ("ar_session_failed" to mapOf("sample_id" to "ar-placement", "reason" to "camera_not_available")),
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

    @Test
    fun `sample open includes received entry id and optional umbrella mode`() {
        assertEquals(
            mapOf(
                "sample_id" to "materials",
                "entry_id" to "texture-streaming",
                "category" to "create",
                "source" to "deeplink",
                "mode" to "inspect",
            ),
            AnalyticsEvent.SampleOpen(
                sampleId = "materials",
                category = "create",
                source = OpenSource.DeepLink,
                entryId = "texture-streaming",
                mode = "inspect",
            ).params(),
        )
        val withoutMode = AnalyticsEvent.SampleOpen("geometry", "create", OpenSource.Home).params()
        assertEquals("geometry", withoutMode["entry_id"])
        assertTrue("mode" !in withoutMode)

        val longValue = "x".repeat(AnalyticsEvent.MAX_VALUE + 20)
        val capped = AnalyticsEvent.SampleOpen(
            sampleId = longValue,
            category = longValue,
            source = OpenSource.Other,
            entryId = longValue,
            mode = longValue,
        ).params()
        capped.forEach { (key, value) ->
            assertTrue(key.matches(Regex("[a-z][a-z0-9_]*")))
            if (value is String) assertTrue(value.length <= AnalyticsEvent.MAX_VALUE)
        }
    }

    @Test
    fun `every demo category has the shared stable slug`() {
        val expected = mapOf(
            DemoCategory.CREATE to "create",
            DemoCategory.DEV_TOOLS to "dev_tools",
            DemoCategory.PLACE_AR to "place_ar",
            DemoCategory.VIEW_3D to "view_3d",
            DemoCategory.UNDERSTAND to "understand",
        )
        ALL_DEMOS.map { it.category }.toSet().forEach { category ->
            assertEquals(expected[category], DemoCategory.slug(category))
            assertTrue(DemoCategory.slug(category) != "unknown")
        }
    }

    @Test
    fun `umbrella initial modes and mode interaction use the shared values`() {
        assertEquals("gallery", initialSampleMode("materials", null))
        assertEquals("inspect", initialSampleMode("materials", 1))
        assertEquals("single_model", initialSampleMode("model-viewer", null))
        assertEquals("multi_model", initialSampleMode("model-viewer", 1))
        assertEquals("image", initialSampleMode("lighting", null))
        assertEquals("studio", initialSampleMode("lighting", 1))
        assertEquals("sun", initialSampleMode("lighting", 2))
        assertEquals("place", initialSampleMode("ar-placement", null))
        assertEquals("wall", initialSampleMode("ar-placement", 1))
        assertEquals("free-pose", initialSampleMode("ar-placement", 2))
        assertEquals("one-call", initialSampleMode("ar-placement", 3))
        assertEquals("terrain", initialSampleMode("ar-geospatial-anchors", null))
        assertEquals("rooftop", initialSampleMode("ar-geospatial-anchors", 1))
        assertEquals("streetscape", initialSampleMode("ar-geospatial-anchors", 2))
        assertEquals("balls", initialSampleMode("rolling-balls", null))
        assertEquals("pendulum", initialSampleMode("rolling-balls", 1))
        assertEquals("rerun", initialSampleMode("ar-rerun", null))
        assertEquals("session-mp4", initialSampleMode("ar-rerun", 1))
        assertEquals("starlight", initialSampleMode("cosmos", 99))
        assertEquals(null, initialSampleMode("geometry", null))

        assertEquals("mode_x", modeControl("x"))
        assertEquals(
            "mode_x",
            AnalyticsEvent.SampleInteraction("materials", modeControl("x")).params()["control"],
        )
        assertEquals(
            AnalyticsEvent.MAX_VALUE,
            (
                AnalyticsEvent.SampleInteraction("materials", modeControl("x".repeat(200)))
                    .params()["control"] as String
            ).length,
        )
    }

    @Test
    fun `mode catalogue and aliased tabs resolve to live cards`() {
        val liveIds = ALL_DEMOS.map { it.id }.toSet()
        assertEquals(DeepLinkRouter.TABBED_DEMOS, SAMPLE_MODES.keys)
        SAMPLE_MODES.keys.forEach { assertTrue(it, it in liveIds) }

        DeepLinkRouter.ALIAS_INITIAL_TAB.forEach { (alias, tab) ->
            val card = DeepLinkRouter.validate(alias)
            assertNotNull(alias, card)
            val resolvedCard = card!!
            val modes = requireNotNull(SAMPLE_MODES[resolvedCard]) { "$alias -> $resolvedCard" }
            assertTrue("$alias tab $tab", tab in modes.indices)
            assertEquals(modes[tab], initialSampleMode(resolvedCard, tab))
        }
    }

    @Test
    fun `pending entry id cannot leak to an unrelated sample`() {
        Telemetry.nextEntryId = "materials" to "texture-streaming"
        assertEquals("texture-streaming", consumeEntryId("materials"))
        Telemetry.nextEntryId = "materials" to "texture-streaming"
        assertEquals("cosmos", consumeEntryId("cosmos"))
        assertEquals(null, Telemetry.nextEntryId)
        Telemetry.nextEntryId = "materials" to "texture-streaming"
        Telemetry.nextEntryId = "materials" to "materials"
        assertEquals("materials", consumeEntryId("materials"))
    }

    @Test
    fun `a recreated sample logs open once and keeps its active time`() {
        var now = 0L
        val first = SampleTelemetrySession(now = { now })
        assertTrue(first.markOpen())
        first.resume()
        now = 1_200L

        val recreated = SampleTelemetrySession(
            now = { now },
            openLogged = true,
            elapsedMillis = first.elapsedMillis(),
        )
        assertFalse(recreated.markOpen())
        recreated.resume()
        now = 3_500L
        recreated.pause()

        assertEquals(3_500L, recreated.elapsedMillis())
        assertFalse(shouldCloseSample(isChangingConfigurations = true))
        assertTrue(shouldCloseSample(isChangingConfigurations = false))
    }
}

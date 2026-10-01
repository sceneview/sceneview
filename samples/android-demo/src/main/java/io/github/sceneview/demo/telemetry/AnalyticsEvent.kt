package io.github.sceneview.demo.telemetry

/**
 * The demo app's analytics taxonomy — every event the app may log, with its exact name and
 * parameters. **Shared word for word with the iOS demo**: rename nothing here without renaming
 * it there, or the two platforms stop being comparable in one dashboard.
 *
 * Event and parameter names are snake_case and at most [MAX_NAME] characters, user-property
 * names at most [MAX_USER_PROPERTY_NAME] (GA4's limits); string values are cut to [MAX_VALUE]
 * characters by [params]. No event carries a model file, a pose, a camera
 * frame, free text typed by the user or anything else that could identify a person — the funnel
 * needs counts, not content.
 *
 * This is demo-app instrumentation. The SceneView SDK itself ships no telemetry
 * (`docs/docs/recipes/measure-ar-funnel.md`); nothing in this package may move into a library.
 */
sealed class AnalyticsEvent(val name: String) {

    /** The key/value pairs sent with [name], with every string value length-capped. */
    fun params(): Map<String, Any> = rawParams().mapValues { (_, value) ->
        if (value is String) value.take(MAX_VALUE) else value
    }

    protected abstract fun rawParams(): Map<String, Any>

    /** Manual screen view: [screenName] is a route id (`home`, `about`…) or a sample id. */
    data class ScreenView(val screenName: String, val screenClass: String) : AnalyticsEvent("screen_view") {
        override fun rawParams() = mapOf("screen_name" to screenName, "screen_class" to screenClass)
    }

    data class SampleOpen(val sampleId: String, val category: String, val source: OpenSource) :
        AnalyticsEvent("sample_open") {
        override fun rawParams() =
            mapOf("sample_id" to sampleId, "category" to category, "source" to source.value)
    }

    data class SampleClose(val sampleId: String, val durationS: Long) : AnalyticsEvent("sample_close") {
        override fun rawParams() = mapOf("sample_id" to sampleId, "duration_s" to durationS)
    }

    /** A named control used inside a sample — `galaxy`, `star`, `settings`… */
    data class SampleInteraction(val sampleId: String, val control: String) : AnalyticsEvent("sample_interaction") {
        override fun rawParams() = mapOf("sample_id" to sampleId, "control" to control)
    }

    data class ModelLoadFailed(val sampleId: String, val reason: ModelLoadFailure) :
        AnalyticsEvent("model_load_failed") {
        override fun rawParams() = mapOf("sample_id" to sampleId, "reason" to reason.value)
    }

    // ── Leaving the app, push, settings ──────────────────────────────────────────────

    data class OutboundLink(val target: LinkTarget, val sampleId: String? = null) : AnalyticsEvent("outbound_link") {
        override fun rawParams() = buildMap<String, Any> {
            put("target", target.value)
            sampleId?.let { put("sample_id", it) }
        }
    }

    data object PushPromptShown : AnalyticsEvent("push_prompt_shown") {
        override fun rawParams() = emptyMap<String, Any>()
    }

    data class PushPromptResult(val result: PromptResult) : AnalyticsEvent("push_prompt_result") {
        override fun rawParams() = mapOf("result" to result.value)
    }

    data class PushOpened(val campaign: String, val sampleId: String) : AnalyticsEvent("push_opened") {
        override fun rawParams() = mapOf("campaign" to campaign, "sample_id" to sampleId)
    }

    data class SettingsChanged(val key: String, val value: String) : AnalyticsEvent("settings_changed") {
        override fun rawParams() = mapOf("key" to key, "value" to value)
    }

    companion object {
        /** GA4's cap on event and parameter names. */
        const val MAX_NAME = 40

        /** GA4's cap on user-property names. */
        const val MAX_USER_PROPERTY_NAME = 24

        /** Firebase's cap on a string parameter value. */
        const val MAX_VALUE = 100
    }
}

/** How a sample was reached, for `sample_open.source`. */
enum class OpenSource(val value: String) {
    Home("home"),
    Search("search"),
    DeepLink("deeplink"),
    Push("push"),
    Other("other"),
}

/** Where an outbound link goes, for `outbound_link.target`. */
enum class LinkTarget(val value: String) {
    GitHub("github"),
    Store("store"),
    Docs("docs"),
    Other("other");

    companion object {
        /** Classifies a URL by host: GitHub, a store listing, the SceneView docs site, or other. */
        fun of(url: String): LinkTarget {
            val lower = url.lowercase()
            val host = lower.substringAfter("://", lower).substringBefore('/').substringBefore('?')
            return when {
                lower.startsWith("market:") -> Store
                host == "github.com" || host.endsWith(".github.com") -> GitHub
                host == "play.google.com" || host == "apps.apple.com" -> Store
                host == "sceneview.github.io" -> Docs
                else -> Other
            }
        }
    }
}

/** Why a model failed to load, for `model_load_failed.reason`. Same codes on iOS. */
enum class ModelLoadFailure(val value: String) {
    /** The file was there but could not be parsed into a model. */
    DecodeFailed("decode_failed"),

    /** The model loaded but has no geometry to frame (empty bounding box). */
    NoBounds("no_bounds"),

    /** The asset or file the sample asked for does not exist. */
    AssetMissing("asset_missing"),

    Unknown("unknown"),
}

/** The answer to the notification pre-prompt, for `push_prompt_result.result`. */
enum class PromptResult(val value: String) {
    Granted("granted"),
    Denied("denied"),
    NotNow("not_now"),
}

/** User properties, set once and updated when they change. Values are the strings listed. */
enum class UserProperty(val key: String) {
    /** `light` | `dark` — the theme the app is showing. */
    AppTheme("app_theme"),

    /** `true` | `false` — the Notifications setting AND the system permission. */
    NotifEnabled("notif_enabled"),
}

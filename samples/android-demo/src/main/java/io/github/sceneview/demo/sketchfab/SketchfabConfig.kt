package io.github.sceneview.demo.sketchfab

import android.util.Log
import io.github.sceneview.demo.BuildConfig
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Configuration for the Sketchfab Data API v3.
 *
 * The API key is injected at build time via [BuildConfig.SKETCHFAB_API_KEY]
 * (populated from the `SKETCHFAB_API_KEY` environment variable or the
 * `sketchfab.api.key` property in `local.properties` — see this module's
 * `build.gradle`). A `SKETCHFAB_API_KEY` env variable at runtime is also
 * checked as a last-resort fallback (useful when running unit tests from a
 * shell that exports the variable but doesn't propagate it to Gradle).
 *
 * TODO V1.1: move to backend proxy via mcp-gateway to avoid bundling the key
 * directly in the Android app binary. End-users should authenticate against
 * the proxy (which holds the master key server-side) so we don't ship a
 * long-lived token that can be extracted from `.apk` files.
 */
object SketchfabConfig {

    /** Base URL of the Sketchfab Data API v3. Always ends with a trailing slash. */
    const val BASE_URL: String = "https://api.sketchfab.com/v3/"

    /**
     * `User-Agent` header sent on every Sketchfab request.
     *
     * Sketchfab fronts its API with **AWS CloudFront + a WAF rule** that
     * returns **HTTP 202 + an empty body + `x-amzn-waf-action: challenge`**
     * (i.e. asks the client to solve a JS / CAPTCHA challenge) whenever the
     * request carries OkHttp's default `User-Agent: okhttp/<version>`
     * — a known scraper / bot signature. The body is empty, so the JSON
     * decoder fails with `Expected start of the object '{', but had 'EOF'
     * instead`, every Explore-tab carousel collapses to "empty", and the
     * user sees a half-rendered Explore tab with no carousels and no
     * error banner (#2191).
     *
     * Send an explicit app-identifying UA so the request reads as a real
     * mobile app instead of an HTTP library, AND so Sketchfab support can
     * reach out if our traffic ever becomes a problem rather than silently
     * shadow-banning the demo. The format follows RFC 7231 — product token
     * + version, with an optional comment describing the platform. The
     * version is read from [BuildConfig.VERSION_NAME] so a future bump
     * cannot drift this constant out of sync.
     *
     * If a CloudFront WAF tightening reinstates the challenge for our app
     * UA in the future, the typed `SketchfabError.WafChallenge` thrown in
     * [SketchfabService.authenticatedGet] surfaces the "Sketchfab
     * unavailable" banner — a honest "this is broken, here's why" beat
     * spoofing a browser to evade a third party's bot mitigation.
     */
    val USER_AGENT: String = "SceneViewDemo/${BuildConfig.VERSION_NAME} (Android; +https://sceneview.github.io)"

    private const val LOG_TAG = "SketchfabConfig"

    /** Guard so the "no key" WARN is logged exactly once per process. */
    private val missingKeyLogged = AtomicBoolean(false)

    /**
     * API key injected at build time, or `null` when missing.
     *
     * Callers should surface [SketchfabService.SketchfabError.MissingApiKey]
     * in that case rather than firing unauthenticated requests.
     *
     * Side-effect: the first read on a debug build with a missing key emits a
     * WARN log pointing at the `local.properties` workaround (#1909). On
     * release builds the log is suppressed — the user-visible
     * `SketchfabDisabledBanner` in `ExploreTabScreen.kt` is the visible signal
     * there, and a Logcat WARN would just be noise for shipped apps.
     */
    val apiKey: String?
        get() {
            val fromBuildConfig = BuildConfig.SKETCHFAB_API_KEY
            if (fromBuildConfig.isNotBlank()) return fromBuildConfig
            val fromEnv = System.getenv("SKETCHFAB_API_KEY")
            val resolved = fromEnv?.takeIf { it.isNotBlank() }
            if (resolved == null && BuildConfig.DEBUG && missingKeyLogged.compareAndSet(false, true)) {
                Log.w(
                    LOG_TAG,
                    "SKETCHFAB_API_KEY is not set — Sketchfab features are disabled. " +
                        "Add `sketchfab.api.key=<token>` to local.properties, or export " +
                        "SKETCHFAB_API_KEY=<token> before Gradle. " +
                        "Get a token at Sketchfab → Settings → Password & API → API Token. See #1909."
                )
            }
            return resolved
        }

    /** Subdirectory under `Context.cacheDir` where downloaded GLB files live. */
    const val CACHE_DIR_NAME: String = "sketchfab"

    /** Maximum cache size on disk, in bytes (500 MB). */
    const val CACHE_MAX_BYTES: Long = 500L * 1024 * 1024

    /**
     * Subdirectory under `Context.cacheDir` for the OkHttp HTTP response cache
     * (JSON metadata only — search / feeds / model detail). Kept separate from
     * [CACHE_DIR_NAME] so the GLB binary LRU and the HTTP LRU don't interfere.
     */
    const val HTTP_CACHE_DIR_NAME: String = "sketchfab-http"

    /**
     * Maximum size of the OkHttp HTTP response cache, in bytes (10 MB). JSON
     * metadata responses are small; this is sized to comfortably hold many
     * feed/search pages so a "basic"-plan key hits the network — and its rate
     * limit — far less often (#2095).
     */
    const val HTTP_CACHE_MAX_BYTES: Long = 10L * 1024 * 1024
}

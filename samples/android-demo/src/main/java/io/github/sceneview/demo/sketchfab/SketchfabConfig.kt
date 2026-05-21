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
                    "SKETCHFAB_API_KEY is not set — Sketchfab features (carousels, search, streamed demos) are disabled. " +
                        "For local builds add `sketchfab.api.key=<your-token>` to local.properties, or export " +
                        "SKETCHFAB_API_KEY=<your-token> before running Gradle. Grab a token at " +
                        "Sketchfab → Settings → Password & API → API Token. See issue #1909."
                )
            }
            return resolved
        }

    /** Subdirectory under `Context.cacheDir` where downloaded GLB files live. */
    const val CACHE_DIR_NAME: String = "sketchfab"

    /** Maximum cache size on disk, in bytes (500 MB). */
    const val CACHE_MAX_BYTES: Long = 500L * 1024 * 1024
}

package io.github.sceneview.demo

import android.net.Uri

/**
 * Parses an incoming intent's data URI and returns the demo id to open,
 * or `null` if the URI is not a valid SceneView deep link.
 *
 * Supported URI shapes:
 *
 * 1. **Custom scheme** — `sceneview://demo/<id>` (intent-filter in
 *    AndroidManifest, no store verification needed). The id is the
 *    last path segment.
 *
 * 2. **Verified App-Links** (future) — `https://sceneview.github.io/open?demo=<id>`.
 *    Pulled from the `demo` query parameter. Will become live once
 *    `/.well-known/assetlinks.json` ships on github.io with the
 *    published Play Store keystore SHA-256.
 *
 * The id must match an entry in [registry] (defaults to [ALL_DEMOS]) —
 * we don't blindly navigate to user-provided strings, both because the
 * demo registry is closed and because that prevents trivial fuzzing of
 * the navigation graph from a hostile QR code. Unknown ids return `null`
 * and the activity falls back to its normal start destination.
 *
 * Pure function: no side effects, no Android framework calls beyond
 * [Uri] accessors. Unit-testable on a plain JVM via Robolectric's
 * `Uri` shadow or by passing already-parsed primitives — see
 * `DeepLinkRouterTest`.
 */
internal object DeepLinkRouter {

    /** Custom URL scheme registered in `AndroidManifest.xml`. */
    const val SCHEME_CUSTOM: String = "sceneview"

    /** Custom URL host. Only `demo` is supported today. */
    const val HOST_CUSTOM: String = "demo"

    /** Hostname for verified App-Links (future). */
    const val HOST_HTTPS: String = "sceneview.github.io"

    /** Path prefix on the App-Links host. */
    const val PATH_HTTPS: String = "/open"

    /** Query parameter name carrying the demo id on the App-Links host. */
    const val QUERY_PARAM: String = "demo"

    /**
     * Query parameter name carrying the optional camera-to-model distance (zoom level),
     * in metres — `sceneview://demo/<id>?cameraDistance=<f>`. See [parseCameraDistance].
     */
    const val QUERY_PARAM_CAMERA_DISTANCE: String = "cameraDistance"

    /** Smallest accepted camera distance, in metres. Anything below resolves to `null`. */
    const val CAMERA_DISTANCE_MIN: Float = 0.05f

    /** Largest accepted camera distance, in metres. Anything above resolves to `null`. */
    const val CAMERA_DISTANCE_MAX: Float = 100f

    fun parse(data: Uri?, registry: List<DemoEntry> = ALL_DEMOS): String? {
        if (data == null) return null
        val candidate = extractCandidate(data) ?: return null
        return validate(candidate, registry)
    }

    /**
     * Validates a raw demo id against [registry]. Used by the QA-channel
     * ingress (`--es demo <id>` from `adb shell am`) so the same allow-list
     * applies whether the id comes from a URL deep-link or from the intent
     * extra. Without this, any app on the device could steer navigation by
     * passing an arbitrary string — same risk as the unvalidated
     * `ar_playback_file` extra fixed in commit `a7dec5e3`. See #958.
     *
     * Returns the candidate iff it is non-blank AND matches a registered
     * demo; otherwise `null`, which the caller treats as "no deep-link".
     */
    fun validate(id: String?, registry: List<DemoEntry> = ALL_DEMOS): String? {
        val candidate = id?.takeIf { it.isNotBlank() } ?: return null
        return if (registry.any { it.id == candidate }) candidate else null
    }

    /**
     * Parses an optional camera-to-model distance (zoom level) from a deep-link URI's
     * `cameraDistance` query parameter — `sceneview://demo/<id>?cameraDistance=<f>`.
     *
     * Used by the device-QA harness to launch a demo at a near or far framing, since
     * Maestro has no pinch gesture (see #1571). Robust by construction: a missing
     * parameter, an unparseable string, a non-finite value (NaN / ±∞), or a value
     * outside `[CAMERA_DISTANCE_MIN, CAMERA_DISTANCE_MAX]` all return `null`, which the
     * caller treats as "keep the demo's default framing". Never throws.
     *
     * Mirrors [validateCameraDistance] — both ingress channels (URL deep link and the
     * `--ef camera_distance` intent extra) apply the identical clamp.
     */
    fun parseCameraDistance(data: Uri?): Float? {
        if (data == null) return null
        val raw = runCatching { data.getQueryParameter(QUERY_PARAM_CAMERA_DISTANCE) }
            .getOrNull() ?: return null
        return validateCameraDistance(raw.toFloatOrNull())
    }

    /**
     * Validates an already-parsed camera distance against the accepted range. Shared by
     * the URL deep-link path ([parseCameraDistance]) and the `--ef camera_distance`
     * intent-extra path so both ingress channels behave identically.
     *
     * Returns [value] iff it is non-null, finite, and within
     * `[CAMERA_DISTANCE_MIN, CAMERA_DISTANCE_MAX]`; otherwise `null`.
     */
    fun validateCameraDistance(value: Float?): Float? {
        if (value == null || !value.isFinite()) return null
        return value.takeIf { it in CAMERA_DISTANCE_MIN..CAMERA_DISTANCE_MAX }
    }

    /**
     * Extracts the raw id token from a URI without validating it against
     * a registry. Exposed so tests can verify the URI parser separately
     * from the registry lookup.
     */
    internal fun extractCandidate(data: Uri): String? {
        val scheme = data.scheme?.lowercase() ?: return null
        return when (scheme) {
            SCHEME_CUSTOM -> {
                if (!data.host.equals(HOST_CUSTOM, ignoreCase = true)) return null
                data.lastPathSegment?.takeIf { it.isNotBlank() }
            }
            "https", "http" -> {
                if (!data.host.equals(HOST_HTTPS, ignoreCase = true)) return null
                if (data.path?.startsWith(PATH_HTTPS) != true) return null
                data.getQueryParameter(QUERY_PARAM)?.takeIf { it.isNotBlank() }
            }
            else -> null
        }
    }
}

package io.github.sceneview.demo.common

import android.content.pm.PackageManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * The one place the demo app talks about the ARCore Cloud API key.
 *
 * Five demos depend on that key — Cloud Anchors, Rooftop, Terrain, Scene Mesh and
 * Streetscape — and every one of them used to carry its own copy of the manifest
 * probe and its own wording for the two ways the key can fail (#3210). Two of
 * them had the probe but no wording at all, so a missing or rejected key looked
 * like a demo that simply did not work. A demo that fails silently teaches
 * nothing, and an AI reading the sample cannot tell a configuration problem from
 * a broken API — so the cause is named here, once, and every Cloud demo reads it.
 *
 * The key can be unusable in exactly two ways, and they are fixed in different
 * places, so the status text must say which one it is:
 *
 * - **Missing** — the build never wired `com.google.android.ar.API_KEY` into the
 *   manifest (a fork without the GitHub secret, a dev machine without
 *   `ARCORE_API_KEY` in `local.properties`). Detected up front by
 *   [rememberHasArcoreApiKey]; fixed on the build side.
 * - **Rejected** — the key is present but ARCore answered `ERROR_NOT_AUTHORIZED`.
 *   Almost always the Google Cloud key restriction: the SHA-1 on it is the upload
 *   certificate, not the Play App Signing certificate that actually signs what
 *   reaches a device (#3185), or billing / the ARCore API is off on the project.
 *   Surfaced by [arcoreApiKeyRejectedMessage]; fixed in the Cloud console.
 *
 * The runbook for both lives in [ARCORE_CLOUD_SETUP_DOC].
 */
const val ARCORE_CLOUD_SETUP_DOC = "samples/android-demo/ARCORE_CLOUD_SETUP.md"

/** Manifest `meta-data` name ARCore reads the Cloud API key from. */
const val ARCORE_API_KEY_META_DATA = "com.google.android.ar.API_KEY"

/** Status text when the build carries no ARCore Cloud API key at all. */
const val ARCORE_API_KEY_MISSING_MESSAGE =
    "ARCore Cloud API key not configured — see $ARCORE_CLOUD_SETUP_DOC"

/**
 * Status text when ARCore rejected the key that *is* present.
 *
 * @param operation what was attempted, as the user would name it — "Hosting",
 *   "Resolve", "Geospatial" — so the banner reads as a complete sentence.
 */
fun arcoreApiKeyRejectedMessage(operation: String): String =
    "$operation failed: ERROR_NOT_AUTHORIZED. The ARCore Cloud API key is rejecting " +
        "this APK. Check SHA-1 + billing + ARCore API restrictions in ARCORE_CLOUD_SETUP.md."

/**
 * Whether the build wired an ARCore Cloud API key into the manifest.
 *
 * Read once per composition: the manifest cannot change while the app runs. When
 * this is `false`, every Cloud call comes back `ERROR_NOT_AUTHORIZED` (or, for
 * Geospatial, silently returns no data), so demos use it to name the cause up
 * front and disable the control that could not succeed.
 */
@Composable
fun rememberHasArcoreApiKey(): Boolean {
    val context = LocalContext.current
    return remember {
        runCatching {
            val ai = context.packageManager.getApplicationInfo(
                context.packageName, PackageManager.GET_META_DATA
            )
            !ai.metaData?.getString(ARCORE_API_KEY_META_DATA).isNullOrBlank()
        }.getOrDefault(false)
    }
}

// The `Earth.EarthState -> banner` mapping used to live here as a single
// `isArcoreApiKeyRejected` boolean. It is now `CloudServiceStatus` and
// `Earth.EarthState?.toCloudServiceStatus` in common/CloudServiceStatus.kt: the
// boolean only ever answered "rejected or not", so `ERROR_RESOURCE_EXHAUSTED`
// and "no network" both had nowhere to go and every demo grew its own copy of
// the rest (#3262).

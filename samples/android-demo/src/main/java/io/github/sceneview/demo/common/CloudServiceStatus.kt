package io.github.sceneview.demo.common

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.getSystemService
import com.google.ar.core.Anchor
import com.google.ar.core.Earth
import io.github.sceneview.demo.DemoBottomOverlayScope

/**
 * Why a Cloud-dependent demo cannot do the one thing it exists to demonstrate,
 * right now.
 *
 * Five demos call an ARCore Cloud endpoint — Cloud Anchor, Rooftop, Terrain,
 * Streetscape, Scene Mesh — and every one of them can fail for the same
 * handful of reasons. Before this type existed each demo answered "why" with
 * its own ad-hoc mix of a nullable `String?` and a `Boolean`, so the same
 * underlying cause (say, `ERROR_NOT_AUTHORIZED`) produced five different
 * sentences, two of which never made it past the Settings sheet (#3262).
 *
 * This is the one place a demo decides *which* of those reasons applies; the
 * wording lives in [message] and the on-screen banner in
 * [CloudServiceStatusBanner], so both stay identical across all five.
 */
sealed class CloudServiceStatus {
    /** Cloud calls can proceed — no banner, controls stay enabled. */
    object Available : CloudServiceStatus()

    /** The build never wired `com.google.android.ar.API_KEY` into the manifest. */
    object ApiKeyMissing : CloudServiceStatus()

    /**
     * ARCore/Google Cloud rejected the key that *is* present — almost always the
     * SHA-1 restriction on the key not matching the certificate that actually
     * signed this APK (#3185).
     */
    data class ApiKeyRejected(val operation: String) : CloudServiceStatus()

    /** The Cloud project's ARCore API quota is exhausted. */
    data class QuotaExhausted(val operation: String) : CloudServiceStatus()

    /** No usable network — every Cloud call needs one and will otherwise hang or fail. */
    object NoNetwork : CloudServiceStatus()

    /**
     * Geospatial has not localised yet: [Earth.getTrackingState] is not `TRACKING`,
     * so there is no VPS lock and therefore no usable lat/lng for a Geospatial call.
     */
    object EarthLocalizing : CloudServiceStatus()

    /** `true` for every reason a demo's host/resolve/place controls should stay disabled. */
    val isUnavailable: Boolean get() = this != Available

    /**
     * The banner tone for [this]. [EarthLocalizing] is [DemoStatusTone.Guidance], not
     * [DemoStatusTone.Blocked]: it is the ordinary "go outside and wait for VPS" state
     * every Geospatial demo already showed in blue/tertiary before this type existed,
     * and it resolves on its own as the user moves — unlike a missing key, a rejected
     * key, an exhausted quota or no network, none of which a tap or a walk fixes.
     */
    val tone: DemoStatusTone
        get() = if (this is EarthLocalizing) DemoStatusTone.Guidance else DemoStatusTone.Blocked
}

/** The complete, one-line sentence a [CloudServiceStatusBanner] shows for [this]. */
fun CloudServiceStatus.message(): String = when (this) {
    CloudServiceStatus.Available -> ""
    CloudServiceStatus.ApiKeyMissing -> ARCORE_API_KEY_MISSING_MESSAGE
    is CloudServiceStatus.ApiKeyRejected -> arcoreApiKeyRejectedMessage(operation)
    is CloudServiceStatus.QuotaExhausted -> arcoreQuotaExhaustedMessage(operation)
    CloudServiceStatus.NoNetwork -> ARCORE_CLOUD_NO_NETWORK_MESSAGE
    CloudServiceStatus.EarthLocalizing ->
        "Waiting for VPS lock — go outside and look at buildings or landmarks."
}

/**
 * Status text for when ARCore reports the Cloud quota is exhausted for this project.
 *
 * @param operation what was attempted, as the user would name it — mirrors
 *   [arcoreApiKeyRejectedMessage] so the two read as one family of sentence.
 */
fun arcoreQuotaExhaustedMessage(operation: String): String =
    "$operation failed: ERROR_RESOURCE_EXHAUSTED. This project's ARCore Cloud quota " +
        "is used up — check quotas for the ARCore API in the Google Cloud Console."

/** Status text for when there is no usable network to reach the ARCore Cloud service. */
const val ARCORE_CLOUD_NO_NETWORK_MESSAGE =
    "No network connection — Cloud features need internet access."

/**
 * Maps a Geospatial [Earth.EarthState] to the [CloudServiceStatus] it represents, or
 * `null` when the state is not itself a Cloud-service failure (e.g. `ENABLED`, or the
 * ordinary not-ready-yet state a demo already shows as "initialising").
 */
fun Earth.EarthState?.toCloudServiceStatus(operation: String): CloudServiceStatus? = when (this) {
    Earth.EarthState.ERROR_NOT_AUTHORIZED -> CloudServiceStatus.ApiKeyRejected(operation)
    Earth.EarthState.ERROR_RESOURCE_EXHAUSTED -> CloudServiceStatus.QuotaExhausted(operation)
    else -> null
}

/**
 * Maps a Cloud Anchor host/resolve result [Anchor.CloudAnchorState] to the
 * [CloudServiceStatus] it represents, or `null` for a state that is not itself a
 * Cloud-service failure (`SUCCESS`, `NONE`, `TASK_IN_PROGRESS`, …).
 */
fun Anchor.CloudAnchorState?.toCloudServiceStatus(operation: String): CloudServiceStatus? = when (this) {
    Anchor.CloudAnchorState.ERROR_NOT_AUTHORIZED -> CloudServiceStatus.ApiKeyRejected(operation)
    Anchor.CloudAnchorState.ERROR_RESOURCE_EXHAUSTED -> CloudServiceStatus.QuotaExhausted(operation)
    else -> null
}

/**
 * The one banner for "this Cloud demo cannot work right now" — lives in the
 * scaffold's `bottomOverlay`, i.e. the main AR view, never only in the Settings
 * sheet (#3262). Renders nothing when [status] is [CloudServiceStatus.Available].
 */
@Composable
fun DemoBottomOverlayScope.CloudServiceStatusBanner(
    status: CloudServiceStatus,
    modifier: Modifier = Modifier,
) {
    if (status == CloudServiceStatus.Available) return
    DemoStatusBanner(text = status.message(), tone = status.tone, modifier = modifier)
}

/**
 * Whether the device currently has a network with internet capability.
 *
 * Read via [ConnectivityManager] rather than assumed: ARCore's own errors for "no
 * network" and "key rejected" both surface as opaque failures on the Cloud call, so
 * checking this up front is the only way a demo can tell the two apart and word the
 * banner correctly (#3262). Requires `ACCESS_NETWORK_STATE`, a normal permission
 * granted at install with no runtime prompt.
 */
@Composable
fun rememberIsNetworkAvailable(): Boolean {
    val context = LocalContext.current
    var isAvailable by remember { mutableStateOf(currentNetworkAvailable(context)) }
    DisposableEffect(context) {
        val connectivityManager = context.getSystemService<ConnectivityManager>()
        if (connectivityManager == null) {
            // No ConnectivityManager on this device/harness — assume online so the
            // banner never blames the network for a different, real failure.
            isAvailable = true
            return@DisposableEffect onDispose {}
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                isAvailable = true
            }

            override fun onLost(network: Network) {
                isAvailable = currentNetworkAvailable(context)
            }

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities,
            ) {
                isAvailable = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatching { connectivityManager.registerNetworkCallback(request, callback) }
        onDispose {
            runCatching { connectivityManager.unregisterNetworkCallback(callback) }
        }
    }
    return isAvailable
}

private fun currentNetworkAvailable(context: Context): Boolean {
    val connectivityManager = context.getSystemService<ConnectivityManager>() ?: return true
    val network = connectivityManager.activeNetwork ?: return false
    val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

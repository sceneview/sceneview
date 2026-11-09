package io.github.sceneview.demo

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import io.github.sceneview.ar.xr.XrFeatures

/**
 * Demos listed only on an Android XR device. They stay in [ALL_DEMOS] — routable by deep link,
 * covered by the registry tests — but the catalogue does not show a headset card on a phone.
 */
internal val XR_ONLY_DEMO_IDS: Set<String> = setOf("ar-xr")

/**
 * Demos reachable only from another demo, never from a catalogue card. They stay in [ALL_DEMOS]
 * — routable by deep link, covered by the registry tests — so the screen that opens them keeps
 * working, but no device lists them.
 *
 * `ar-ml-object-label` is Point & Ask's on-device fallback: on a phone without Google AICore
 * (Pixel 4a and older), Gemini Nano cannot run and Point & Ask's "unavailable" card opens ML Kit
 * object labels through `sceneview://demo/ar-ml-object-label` instead (#2648). The AR catalogue
 * clean-up (wave 1, #4608) took its card away; the route must stay, or that card is a dead end.
 */
internal val FALLBACK_ONLY_DEMO_IDS: Set<String> = setOf("ar-ml-object-label")

/**
 * Whether this device can run the Jetpack XR runtime: the runtime classes are on the classpath
 * ([XrFeatures.isAvailable], which only proves the consumer opted in) **and** the device
 * declares an Android XR system feature. The classpath check alone is true on any phone of an
 * app that bundles `androidx.xr.arcore`.
 */
fun isXrDevice(context: Context): Boolean =
    XrFeatures.isAvailable(context) && XR_SYSTEM_FEATURES.any(context.packageManager::hasSystemFeature)

/**
 * The catalogue's view of [all]: [FALLBACK_ONLY_DEMO_IDS] are always dropped, [XR_ONLY_DEMO_IDS]
 * unless [xrDevice].
 */
fun listedDemos(
    all: List<DemoEntry>,
    xrDevice: Boolean,
): List<DemoEntry> = all.filter { entry ->
    entry.id !in FALLBACK_ONLY_DEMO_IDS && (xrDevice || entry.id !in XR_ONLY_DEMO_IDS)
}

/** [listedDemos] of [ALL_DEMOS] for this device. */
@Composable
fun rememberListedDemos(): List<DemoEntry> {
    val context = LocalContext.current
    val xrDevice = remember { isXrDevice(context) }
    return remember(xrDevice) { listedDemos(ALL_DEMOS, xrDevice) }
}

/** Android XR platform features (`PackageManager.FEATURE_XR_API_*`). */
private val XR_SYSTEM_FEATURES = listOf(
    "android.software.xr.api.spatial",
    "android.software.xr.api.openxr",
)

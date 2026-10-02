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
 * Whether this device can run the Jetpack XR runtime: the runtime classes are on the classpath
 * ([XrFeatures.isAvailable], which only proves the consumer opted in) **and** the device
 * declares an Android XR system feature. The classpath check alone is true on any phone of an
 * app that bundles `androidx.xr.arcore`.
 */
fun isXrDevice(context: Context): Boolean =
    XrFeatures.isAvailable(context) && XR_SYSTEM_FEATURES.any(context.packageManager::hasSystemFeature)

/** The catalogue's view of [all]: [XR_ONLY_DEMO_IDS] are dropped unless [xrDevice]. */
fun listedDemos(all: List<DemoEntry>, xrDevice: Boolean): List<DemoEntry> =
    if (xrDevice) all else all.filterNot { it.id in XR_ONLY_DEMO_IDS }

/** [listedDemos] of [ALL_DEMOS] for this device, resolved once. */
@Composable
fun rememberListedDemos(): List<DemoEntry> {
    val context = LocalContext.current
    return remember { listedDemos(ALL_DEMOS, isXrDevice(context)) }
}

/** Android XR platform features (`PackageManager.FEATURE_XR_API_*`). */
private val XR_SYSTEM_FEATURES = listOf(
    "android.software.xr.api.spatial",
    "android.software.xr.api.openxr",
)

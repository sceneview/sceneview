package io.github.sceneview.demo

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import io.github.sceneview.ar.xr.XrFeatures
import io.github.sceneview.demo.labs.Labs

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
 * SceneView Labs demos: features that still need testing on real phones, shipped in the store
 * app behind the Labs switch (off until the user joins by sending a report, see
 * [io.github.sceneview.demo.labs.Labs]).
 *
 * Like [XR_ONLY_DEMO_IDS] they stay in [ALL_DEMOS] — routable, covered by the registry tests —
 * but Home, the tabs and search only list them while Labs is on, and a deep link to one with
 * Labs off lands on the Labs explainer ([io.github.sceneview.demo.labs.LabsGateBody]).
 *
 * A demo opts in by adding its id here, typically a PR labelled `needs-device` whose feature
 * would otherwise wait for a real-phone review before reaching anyone.
 */
internal val LABS_DEMO_IDS: Set<String> = setOf(
    // Finger Drawing (#4082): the 3D half is proven, the AR half waits for Labs testers.
    "finger-drawing",
    // Pool in the garden (#4571): Night and the dug-in look still need a real lawn.
    "ar-pool",
    // Image Stabilization (#4649): a real effect, but too subtle for the main AR catalogue.
    "ar-image-stabilization",
)

/** Whether this demo is a SceneView Labs experiment — see [LABS_DEMO_IDS]. */
val DemoEntry.isLabs: Boolean
    get() = id in LABS_DEMO_IDS

/**
 * The catalogue's view of [all]: [FALLBACK_ONLY_DEMO_IDS] are always dropped, [XR_ONLY_DEMO_IDS]
 * unless [xrDevice], and the [labsIds] unless [labsEnabled].
 */
fun listedDemos(
    all: List<DemoEntry>,
    xrDevice: Boolean,
    labsEnabled: Boolean = false,
    labsIds: Set<String> = LABS_DEMO_IDS,
): List<DemoEntry> = all.filter { entry ->
    entry.id !in FALLBACK_ONLY_DEMO_IDS &&
        (xrDevice || entry.id !in XR_ONLY_DEMO_IDS) &&
        (labsEnabled || entry.id !in labsIds)
}

/** The Labs demos of [all] this device can list, whether or not Labs is on — the Home card counts them. */
fun labsDemos(
    all: List<DemoEntry>,
    xrDevice: Boolean,
    labsIds: Set<String> = LABS_DEMO_IDS,
): List<DemoEntry> = listedDemos(all, xrDevice, labsEnabled = true, labsIds = labsIds)
    .filter { it.id in labsIds }

/** [listedDemos] of [ALL_DEMOS] for this device, following the Labs switch. */
@Composable
fun rememberListedDemos(): List<DemoEntry> {
    val context = LocalContext.current
    val xrDevice = remember { isXrDevice(context) }
    val labsEnabled = Labs.enabled
    return remember(xrDevice, labsEnabled) { listedDemos(ALL_DEMOS, xrDevice, labsEnabled) }
}

/** [labsDemos] of [ALL_DEMOS] for this device. */
@Composable
fun rememberLabsDemos(): List<DemoEntry> {
    val context = LocalContext.current
    return remember { labsDemos(ALL_DEMOS, isXrDevice(context)) }
}

/** Android XR platform features (`PackageManager.FEATURE_XR_API_*`). */
private val XR_SYSTEM_FEATURES = listOf(
    "android.software.xr.api.spatial",
    "android.software.xr.api.openxr",
)

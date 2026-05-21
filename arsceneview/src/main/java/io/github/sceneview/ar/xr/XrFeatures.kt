package io.github.sceneview.ar.xr

import android.content.Context

/**
 * Runtime availability check for the Jetpack XR Perception runtime
 * (`androidx.xr.runtime`, `androidx.xr.arcore`).
 *
 * SceneView's Jetpack XR layer — hand tracking, face tracking on Android XR
 * headsets and glasses — is opt-in and additive. Mobile-only consumers pay
 * nothing for it: the `androidx.xr.arcore` artifact is `compileOnly` in
 * `arsceneview/build.gradle`, so it is not bundled into the runtime classpath
 * unless the consumer pulls it themselves.
 *
 * Use [isAvailable] to gate any code path that touches XR types. The check
 * uses reflection so it is safe on a stock mobile build where the XR classes
 * are not on the classpath — it returns `false` instead of throwing
 * `ClassNotFoundException`.
 *
 * Tracking issue: [#1738](https://github.com/sceneview/sceneview/issues/1738).
 * Integration approach: `arsceneview/docs/JETPACK-XR-INTEGRATION.md`.
 *
 * Preview status: this API forwards to `androidx.xr.arcore`
 * (`1.0.0-alpha14`). The upstream is alpha and subject to breaking changes;
 * follow-up SceneView versions will track its evolution.
 */
object XrFeatures {

    /**
     * Returns `true` when the consumer has declared `androidx.xr.arcore:arcore`
     * (or any artifact that pulls `androidx.xr.runtime`) on the runtime
     * classpath of the current process, `false` otherwise — for a default
     * `arsceneview/` consumer on a mobile phone the answer is always `false`.
     *
     * This is the recommended gate before invoking any
     * `io.github.sceneview.ar.xr.*` API. Compose example:
     *
     * ```kotlin
     * val context = LocalContext.current
     * val xrAvailable = remember { XrFeatures.isAvailable(context) }
     * if (xrAvailable) {
     *     // Slice 2 will add: XrHandNode(hand = …) { … }
     * } else {
     *     // Fall back to mobile ARCore: AugmentedFaceNode, etc.
     * }
     * ```
     *
     * **Scope of the check.** A `true` answer only proves that the XR runtime
     * *types* are loadable — not that the *device* supports XR. Device-level
     * capability is determined by the upstream `Session.create(activity)`
     * outcome (it returns a `SessionCreateUnsupportedDevice` sealed-variant on
     * a non-XR device). Slices 2 and 3 layer that device check on top of
     * [isAvailable]; for the public API at the foundation slice, classpath
     * presence is the right gate because it captures the consumer's explicit
     * opt-in.
     *
     * The [context] parameter is reserved for future use — once Jetpack XR
     * publishes a richer device-capability surface (e.g. a
     * `PackageManager.FEATURE_XR_*` flag) this method will also consult it.
     */
    fun isAvailable(@Suppress("UNUSED_PARAMETER") context: Context): Boolean =
        xrRuntimeOnClasspath

    private val xrRuntimeOnClasspath: Boolean by lazy {
        try {
            Class.forName(
                "androidx.xr.runtime.Session",
                false,
                XrFeatures::class.java.classLoader,
            )
            true
        } catch (_: ClassNotFoundException) {
            false
        } catch (_: LinkageError) {
            false
        }
    }
}

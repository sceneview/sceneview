package io.github.sceneview.demo.common

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.google.ar.core.TrackingFailureReason
import io.github.sceneview.demo.R

/**
 * Maps ARCore's [TrackingFailureReason] to a short, user-facing message.
 *
 * Ported from Google's
 * [`TrackingStateHelper`](https://github.com/google-ar/arcore-android-sdk/blob/main/samples/persistent_cloud_anchor_java/app/src/main/java/com/google/ar/core/examples/java/common/helpers/TrackingStateHelper.java)
 * in the `arcore-android-sdk` samples — the same five reason codes mapped to
 * SceneView-tone strings so every AR demo speaks the same vocabulary.
 *
 * Returns `null` when there is no tracking failure to surface
 * ([TrackingFailureReason.NONE] or `null`). Callers decide whether to fall
 * back to a context-specific hint (e.g. "Point your camera at a flat
 * surface") or hide their status banner altogether.
 *
 * Backed by string resources (`tracking_failure_*` in `strings.xml`) so the
 * messages can be localised without recompiling the demos.
 *
 * Example:
 * ```
 * onTrackingFailureChanged = { reason -> failure = reason }
 * // later, in the status banner:
 * val text = trackingFailureMessage(failure) ?: "Point your camera at a flat surface"
 * ```
 *
 * Addresses the "no guidance" half of #1615 — when tracking fails indoors,
 * the user used to see no message and no hint about *why*.
 */
fun trackingFailureMessage(context: Context, reason: TrackingFailureReason?): String? {
    val resId = trackingFailureStringRes(reason) ?: return null
    return context.getString(resId)
}

/**
 * Returns the `@StringRes` for [reason], or `null` when there is no tracking
 * failure to surface ([TrackingFailureReason.NONE] / `null`).
 *
 * Exposed so unit tests can pin the mapping without instantiating a
 * [Context] — the JVM `enum` -> `Int` mapping is the bit most likely to
 * regress.
 */
@StringRes
fun trackingFailureStringRes(reason: TrackingFailureReason?): Int? = when (reason) {
    null, TrackingFailureReason.NONE -> null
    TrackingFailureReason.BAD_STATE -> R.string.tracking_failure_bad_state
    TrackingFailureReason.INSUFFICIENT_LIGHT -> R.string.tracking_failure_insufficient_light
    TrackingFailureReason.EXCESSIVE_MOTION -> R.string.tracking_failure_excessive_motion
    TrackingFailureReason.INSUFFICIENT_FEATURES -> R.string.tracking_failure_insufficient_features
    TrackingFailureReason.CAMERA_UNAVAILABLE -> R.string.tracking_failure_camera_unavailable
}

/**
 * Compose-friendly wrapper. Returns `null` when there is no tracking failure
 * to surface, so callers can write
 *
 * ```
 * val trackingHint = trackingFailureMessage(reason)
 * if (trackingHint != null) StatusBanner(text = trackingHint)
 * ```
 */
@Composable
fun trackingFailureMessage(reason: TrackingFailureReason?): String? {
    val resId = trackingFailureStringRes(reason) ?: return null
    return stringResource(resId)
}

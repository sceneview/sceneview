package io.github.sceneview.demo.telemetry

import com.google.ar.core.TrackingFailureReason
import io.github.sceneview.ar.ARSessionFailure

/** `sample_id` of the AR session the AR View tab hosts outside any sample. */
const val AR_VIEW_SAMPLE_ID = "ar_view"

/**
 * The five AR funnel events of `docs/docs/recipes/measure-ar-funnel.md`, fed from ARSceneView's
 * public callbacks. One instance per AR session: "ready" and "first placement" are logged once
 * each; tracking losses every time the reason changes (ARSceneView already de-duplicates).
 */
class ArFunnelLog(private val analytics: () -> DemoAnalytics = { Telemetry.analytics }) {
    var sampleId: String = AR_VIEW_SAMPLE_ID

    private var tracked = false
    private var placedOnce = false

    fun sessionCreated() = analytics().log(AnalyticsEvent.ArSessionCreated(sampleId))

    fun trackingFailureChanged(reason: TrackingFailureReason?) {
        if (reason == null) {
            if (!tracked) analytics().log(AnalyticsEvent.ArTrackingReady(sampleId))
            tracked = true
        } else {
            analytics().log(AnalyticsEvent.ArTrackingLost(sampleId, reason.name.lowercase()))
        }
    }

    fun placed() {
        if (placedOnce) return
        placedOnce = true
        analytics().log(AnalyticsEvent.ArFirstPlacement(sampleId))
    }

    fun sessionFailed(failure: ARSessionFailure) =
        analytics().log(AnalyticsEvent.ArSessionFailed(sampleId, failureReason(failure)))

    companion object {
        /**
         * The failure's type name (`ArCoreNotInstalled`, `CameraNotAvailable`…). Read from the
         * data class's `toString`, whose class name is a string literal: `::class.simpleName`
         * would come out obfuscated in a minified release build.
         */
        fun failureReason(failure: ARSessionFailure): String =
            failure.toString().substringBefore('(').ifBlank { "unknown" }
    }
}

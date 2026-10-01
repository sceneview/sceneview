package io.github.sceneview.demo.telemetry

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.staticCompositionLocalOf
import io.github.sceneview.demo.ALL_DEMOS

/**
 * The sample on screen, for events logged deep inside shared components (the settings sheet,
 * the AR placement session) that do not otherwise know which sample hosts them. Null on the
 * tab host.
 */
val LocalSampleId = staticCompositionLocalOf<String?> { null }

/**
 * Wraps one sample's screen: `screen_view` + `sample_open` when it appears, `sample_close`
 * with the whole seconds spent when it leaves, and [LocalSampleId] for everything inside.
 */
@Composable
fun SampleTelemetry(sampleId: String, content: @Composable () -> Unit) {
    DisposableEffect(sampleId) {
        val source = Telemetry.nextOpenSource
        Telemetry.nextOpenSource = OpenSource.Other
        val category = ALL_DEMOS.firstOrNull { it.id == sampleId }?.category ?: "unknown"
        Telemetry.analytics.log(AnalyticsEvent.ScreenView(screenName = sampleId, screenClass = SCREEN_CLASS_SAMPLE))
        Telemetry.analytics.log(AnalyticsEvent.SampleOpen(sampleId, category, source))
        val openedAt = SystemClock.elapsedRealtime()
        onDispose {
            val seconds = (SystemClock.elapsedRealtime() - openedAt) / MILLIS_PER_SECOND
            Telemetry.analytics.log(AnalyticsEvent.SampleClose(sampleId, seconds))
        }
    }
    CompositionLocalProvider(LocalSampleId provides sampleId, content = content)
}

/** `sample_interaction` from inside a sample; dropped when no sample hosts the caller. */
fun logSampleInteraction(sampleId: String?, control: String) {
    sampleId ?: return
    Telemetry.analytics.log(AnalyticsEvent.SampleInteraction(sampleId, control))
}

/** `model_load_failed`; [reason] is a short code (`decode_failed`, `no_bounds`, `download_failed`…). */
fun logModelLoadFailed(sampleId: String?, reason: String) {
    Telemetry.analytics.log(AnalyticsEvent.ModelLoadFailed(sampleId ?: "unknown", reason))
}

/** `outbound_link` for a URL the app is about to open. */
fun logOutboundLink(url: String, sampleId: String? = null) {
    Telemetry.analytics.log(AnalyticsEvent.OutboundLink(LinkTarget.of(url), sampleId))
}

const val SCREEN_CLASS_SAMPLE = "Sample"
const val SCREEN_CLASS_TAB = "Tab"
private const val MILLIS_PER_SECOND = 1000L

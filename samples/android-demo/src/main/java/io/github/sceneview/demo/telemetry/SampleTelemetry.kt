package io.github.sceneview.demo.telemetry

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.sceneview.demo.ALL_DEMOS
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoSettings

/**
 * The sample on screen, for events logged deep inside shared components (the settings sheet,
 * the model viewer) that do not otherwise know which sample hosts them. Null on the tab host.
 */
val LocalSampleId = staticCompositionLocalOf<String?> { null }

/**
 * Wraps one sample's screen: `screen_view` + `sample_open` when it appears, `sample_close`
 * when it leaves, and [LocalSampleId] for everything inside. `sample_close.duration_s` counts
 * the whole seconds the sample was visible: the clock pauses while the app is in the
 * background (ON_STOP) and resumes with it (ON_START).
 */
@Composable
fun SampleTelemetry(sampleId: String, content: @Composable () -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val initialMode = initialSampleMode(sampleId, DemoSettings.initialTab)
    DisposableEffect(sampleId) {
        val source = Telemetry.nextOpenSource
        Telemetry.nextOpenSource = OpenSource.Other
        val entryId = Telemetry.nextEntryId ?: sampleId
        Telemetry.nextEntryId = null
        val category = ALL_DEMOS.firstOrNull { it.id == sampleId }
            ?.category
            ?.let { DemoCategory.slug(it) }
            ?: "unknown"
        Telemetry.analytics.log(AnalyticsEvent.ScreenView(screenName = sampleId, screenClass = SCREEN_CLASS_SAMPLE))
        Telemetry.analytics.log(AnalyticsEvent.SampleOpen(sampleId, category, source, entryId, initialMode))
        val clock = ActiveClock(SystemClock::elapsedRealtime)
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> clock.pause()
                Lifecycle.Event.ON_START -> clock.resume()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            Telemetry.analytics.log(AnalyticsEvent.SampleClose(sampleId, clock.elapsedMillis() / MILLIS_PER_SECOND))
        }
    }
    CompositionLocalProvider(LocalSampleId provides sampleId, content = content)
}

/**
 * A stopwatch that only counts while running. Starts running; [pause] and [resume] are
 * idempotent, so lifecycle events replayed on registration cannot double-count.
 */
class ActiveClock(private val now: () -> Long) {
    private var accumulated = 0L
    private var runningSince: Long? = now()

    fun pause() {
        val since = runningSince ?: return
        accumulated += now() - since
        runningSince = null
    }

    fun resume() {
        if (runningSince == null) runningSince = now()
    }

    fun elapsedMillis(): Long = accumulated + (runningSince?.let { now() - it } ?: 0L)
}

/** `sample_interaction` from inside a sample; dropped when no sample hosts the caller. */
fun logSampleInteraction(sampleId: String?, control: String) {
    sampleId ?: return
    Telemetry.analytics.log(AnalyticsEvent.SampleInteraction(sampleId, control))
}

/** A user-selected umbrella mode, using the same value as `sample_open.mode`. */
fun logSampleModeChange(sampleId: String?, mode: String) {
    logSampleInteraction(sampleId, modeControl(mode))
}

internal fun modeControl(mode: String): String = "mode_$mode"

/** Initial mode of a catalogue umbrella. The pending tab is only peeked; the demo consumes it. */
internal fun initialSampleMode(sampleId: String, initialTab: Int?): String? {
    val modes = when (sampleId) {
        "materials" -> listOf("gallery", "inspect", "occlusion")
        "model-viewer" -> listOf("single_model", "multi_model")
        "lighting" -> listOf("image", "studio", "sun")
        "ar-placement" -> listOf("floor", "wall")
        "ar-geospatial-anchors" -> listOf("terrain", "rooftop")
        "ar-scene-mesh" -> listOf("mesh", "streetscape")
        "cosmos" -> listOf("starlight", "spacetime")
        else -> return null
    }
    return modes.getOrNull(initialTab ?: 0) ?: modes.first()
}

/** `model_load_failed`, with one of the [ModelLoadFailure] codes shared with iOS. */
fun logModelLoadFailed(sampleId: String?, reason: ModelLoadFailure) {
    Telemetry.analytics.log(AnalyticsEvent.ModelLoadFailed(sampleId ?: "unknown", reason))
}

/** `outbound_link` for a URL the app is about to open. */
fun logOutboundLink(url: String, sampleId: String? = null) {
    Telemetry.analytics.log(AnalyticsEvent.OutboundLink(LinkTarget.of(url), sampleId))
}

const val SCREEN_CLASS_SAMPLE = "Sample"
const val SCREEN_CLASS_TAB = "Tab"
private const val MILLIS_PER_SECOND = 1000L

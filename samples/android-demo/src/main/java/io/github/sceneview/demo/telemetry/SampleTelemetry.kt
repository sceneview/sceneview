package io.github.sceneview.demo.telemetry

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.sceneview.demo.ALL_DEMOS
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoSettings
import io.github.sceneview.demo.demos.AR_PLACEMENT_SURFACE_MODES
import io.github.sceneview.demo.demos.COSMOS_MODES
import io.github.sceneview.demo.demos.LightingRig
import io.github.sceneview.demo.demos.MaterialsMode
import io.github.sceneview.demo.demos.ModelViewerMode
import io.github.sceneview.demo.demos.internal.GeospatialAnchorMode
import io.github.sceneview.demo.fragments.ArCloudAnchorFragment
import io.github.sceneview.demo.fragments.ArGeospatialAnchorsFragment
import io.github.sceneview.demo.fragments.ArPlacementFragment
import io.github.sceneview.demo.fragments.ArRerunFragment
import io.github.sceneview.demo.fragments.ArXrFragment
import io.github.sceneview.demo.fragments.CameraAndGesturesFragment
import io.github.sceneview.demo.fragments.RollingBallsFragment
import io.github.sceneview.demo.fragments.TwoDInThreeDFragment

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
    val activity = LocalContext.current.findActivity()
    val initialMode = initialSampleMode(sampleId, DemoSettings.initialTab)
    val session = rememberSaveable(
        sampleId,
        saver = SampleTelemetrySession.saver(SystemClock::elapsedRealtime),
    ) {
        SampleTelemetrySession(SystemClock::elapsedRealtime)
    }
    DisposableEffect(sampleId, lifecycle, activity) {
        if (session.markOpen()) {
            val source = Telemetry.nextOpenSource
            Telemetry.nextOpenSource = OpenSource.Other
            val entryId = consumeEntryId(sampleId)
            val category = ALL_DEMOS.firstOrNull { it.id == sampleId }
                ?.category
                ?.let { DemoCategory.slug(it) }
                ?: "unknown"
            Telemetry.analytics.log(AnalyticsEvent.ScreenView(screenName = sampleId, screenClass = SCREEN_CLASS_SAMPLE))
            Telemetry.analytics.log(AnalyticsEvent.SampleOpen(sampleId, category, source, entryId, initialMode))
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> session.pause()
                Lifecycle.Event.ON_START -> session.resume()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            session.pause()
            if (shouldCloseSample(activity?.isChangingConfigurations == true)) {
                Telemetry.analytics.log(
                    AnalyticsEvent.SampleClose(sampleId, session.elapsedMillis() / MILLIS_PER_SECOND),
                )
            }
        }
    }
    CompositionLocalProvider(LocalSampleId provides sampleId, content = content)
}

/** One logical sample opening, retained across configuration-change recompositions. */
internal class SampleTelemetrySession(
    private val now: () -> Long,
    private var openLogged: Boolean = false,
    elapsedMillis: Long = 0L,
) {
    private val clock = ActiveClock(now, elapsedMillis = elapsedMillis, startRunning = false)

    fun markOpen(): Boolean {
        if (openLogged) return false
        openLogged = true
        return true
    }

    fun pause() = clock.pause()

    fun resume() = clock.resume()

    fun elapsedMillis(): Long = clock.elapsedMillis()

    companion object {
        fun saver(now: () -> Long): Saver<SampleTelemetrySession, Any> = listSaver(
            save = { listOf(it.openLogged, it.elapsedMillis()) },
            restore = {
                SampleTelemetrySession(
                    now = now,
                    openLogged = it[0] as Boolean,
                    elapsedMillis = it[1] as Long,
                )
            },
        )
    }
}

internal fun shouldCloseSample(isChangingConfigurations: Boolean): Boolean =
    !isChangingConfigurations

/**
 * A stopwatch that only counts while running. Starts running; [pause] and [resume] are
 * idempotent, so lifecycle events replayed on registration cannot double-count.
 */
class ActiveClock(
    private val now: () -> Long,
    elapsedMillis: Long = 0L,
    startRunning: Boolean = true,
) {
    private var accumulated = elapsedMillis
    private var runningSince: Long? = now().takeIf { startRunning }

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

internal fun consumeEntryId(sampleId: String): String {
    val entryId = Telemetry.nextEntryId
        ?.takeIf { (expectedSampleId, _) -> expectedSampleId == sampleId }
        ?.second
        ?: sampleId
    Telemetry.nextEntryId = null
    return entryId
}

internal val SAMPLE_MODES: Map<String, List<String>> = mapOf(
    "materials" to MaterialsMode.entries.map { it.analyticsMode },
    "model-viewer" to ModelViewerMode.entries.map { it.analyticsMode },
    "lighting" to LightingRig.entries.map { it.analyticsMode },
    "ar-placement" to
        AR_PLACEMENT_SURFACE_MODES + ArPlacementFragment.modes.drop(1).map { it.key },
    "ar-geospatial-anchors" to
        GeospatialAnchorMode.entries.map { it.analyticsMode } +
        ArGeospatialAnchorsFragment.modes.drop(1).map { it.key },
    "cosmos" to COSMOS_MODES,
    "camera-gestures" to CameraAndGesturesFragment.modes.map { it.key },
    "rolling-balls" to RollingBallsFragment.modes.map { it.key },
    "ar-rerun" to ArRerunFragment.modes.map { it.key },
    "ar-cloud-anchor" to ArCloudAnchorFragment.modes.map { it.key },
    "ar-xr" to ArXrFragment.modes.map { it.key },
    "two-d-in-three-d" to TwoDInThreeDFragment.modes.map { it.key },
)

/** Initial mode of a catalogue umbrella. The pending tab is only peeked; the demo consumes it. */
internal fun initialSampleMode(sampleId: String, initialTab: Int?): String? {
    val modes = SAMPLE_MODES[sampleId] ?: return null
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

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

package io.github.sceneview.demo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.painterResource
import io.github.sceneview.ar.ArGuidanceCue
import io.github.sceneview.ar.ArTrackingHint
import io.github.sceneview.ar.PlacementSurface
import io.github.sceneview.demo.common.placement.ARCoachingFrame
import io.github.sceneview.demo.common.placement.ARCoachingSample
import io.github.sceneview.demo.common.placement.ARCoachingSamples
import io.github.sceneview.demo.theme.SceneViewDemoTheme
import kotlinx.coroutines.delay

/**
 * Debug-only visual-QA host for the SDK's AR coaching card: renders it without an ARCore
 * session, over a bundled camera stand-in, so every state can be captured on the emulator in
 * light and dark (`adb shell cmd uimode night yes|no`), with its real animations.
 *
 * ```
 * # the whole journey on a loop (the screen recording)
 * adb shell am start -n io.github.sceneview.demo.qa/io.github.sceneview.demo.ARCoachingPreviewActivity
 * # one state, full screen
 * adb shell am start -n io.github.sceneview.demo.qa/io.github.sceneview.demo.ARCoachingPreviewActivity \
 *     --es sample limited-too-fast
 * adb shell am start -n io.github.sceneview.demo.qa/io.github.sceneview.demo.ARCoachingPreviewActivity \
 *     --es cue SCAN --es surface WALL --es hint NONE --ez lingering true
 * ```
 */
class ARCoachingPreviewActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val fixed = fixedSample()
        setContent {
            SceneViewDemoTheme {
                val backdrop = painterResource(R.drawable.qa_backdrop_1)
                if (fixed != null) {
                    ARCoachingFrame(fixed, backdrop = backdrop)
                } else {
                    var step by remember { mutableIntStateOf(0) }
                    LaunchedEffect(Unit) {
                        while (true) {
                            delay(JOURNEY[step].second)
                            step = (step + 1) % JOURNEY.size
                        }
                    }
                    ARCoachingFrame(JOURNEY[step].first, backdrop = backdrop)
                }
            }
        }
    }

    private fun fixedSample(): ARCoachingSample? {
        intent.getStringExtra(EXTRA_SAMPLE)?.let { name -> return ARCoachingSamples.firstOrNull { it.label == name } }
        val cue = intent.getStringExtra(EXTRA_CUE)?.let { runCatching { ArGuidanceCue.valueOf(it) }.getOrNull() }
            ?: return null
        return ARCoachingSample(
            label = cue.name,
            cue = cue,
            surface = intent.getStringExtra(EXTRA_SURFACE)
                ?.let { runCatching { PlacementSurface.valueOf(it) }.getOrNull() } ?: PlacementSurface.SURFACE,
            hint = intent.getStringExtra(EXTRA_HINT)
                ?.let { runCatching { ArTrackingHint.valueOf(it) }.getOrNull() } ?: ArTrackingHint.NONE,
            scanLingering = intent.getBooleanExtra(EXTRA_LINGERING, false),
        )
    }

    private companion object {
        const val EXTRA_SAMPLE = "sample"
        const val EXTRA_CUE = "cue"
        const val EXTRA_SURFACE = "surface"
        const val EXTRA_HINT = "hint"
        const val EXTRA_LINGERING = "lingering"

        /** A session's journey, with roughly the dwell times the real state machine gives. */
        val JOURNEY: List<Pair<ARCoachingSample, Long>> = listOf(
            ARCoachingSample("initializing", ArGuidanceCue.INITIALIZING) to 2_000L,
            ARCoachingSample("scan", ArGuidanceCue.SCAN) to 5_600L,
            ARCoachingSample("scan-lingering", ArGuidanceCue.SCAN, scanLingering = true) to 2_800L,
            ARCoachingSample("surface-found", ArGuidanceCue.SURFACE_FOUND) to 1_000L,
            ARCoachingSample("placed", ArGuidanceCue.NONE) to 2_000L,
            ARCoachingSample("limited", ArGuidanceCue.TRACKING_LIMITED) to 1_500L,
            ARCoachingSample("limited-too-dark", ArGuidanceCue.TRACKING_LIMITED, hint = ArTrackingHint.TOO_DARK)
                to 3_000L,
            ARCoachingSample("relocalizing", ArGuidanceCue.RELOCALIZING) to 3_000L,
            ARCoachingSample("back", ArGuidanceCue.NONE) to 2_000L,
        )
    }
}

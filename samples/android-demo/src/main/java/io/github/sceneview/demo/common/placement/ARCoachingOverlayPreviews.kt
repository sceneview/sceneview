@file:Suppress("MatchingDeclarationName") // the file is the overlay's previews; the sample type serves them

package io.github.sceneview.demo.common.placement

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.tooling.preview.Preview
import io.github.sceneview.ar.ARCoachingOverlay
import io.github.sceneview.ar.ArGuidanceCue
import io.github.sceneview.ar.ArTrackingHint
import io.github.sceneview.ar.PlacementSurface
import io.github.sceneview.demo.theme.SceneViewTokens

/**
 * One moment of the SDK's AR coaching card: a cue, the surface it looks for, the reason chip
 * and the long-scan line. The visual-QA set below walks the whole journey — getting ready,
 * the phone sweep, the found beat, and the three reasons tracking can struggle for.
 */
internal data class ARCoachingSample(
    val label: String,
    val cue: ArGuidanceCue,
    val surface: PlacementSurface = PlacementSurface.SURFACE,
    val hint: ArTrackingHint = ArTrackingHint.NONE,
    val scanLingering: Boolean = false,
)

/** Every state worth looking at, in the order a session meets them. */
internal val ARCoachingSamples = listOf(
    ARCoachingSample("initializing", ArGuidanceCue.INITIALIZING),
    ARCoachingSample("initializing-too-dark", ArGuidanceCue.INITIALIZING, hint = ArTrackingHint.TOO_DARK),
    ARCoachingSample("scan", ArGuidanceCue.SCAN),
    ARCoachingSample("scan-wall", ArGuidanceCue.SCAN, PlacementSurface.WALL),
    ARCoachingSample("scan-lingering", ArGuidanceCue.SCAN, scanLingering = true),
    ARCoachingSample("surface-found", ArGuidanceCue.SURFACE_FOUND),
    ARCoachingSample("wall-found", ArGuidanceCue.SURFACE_FOUND, PlacementSurface.WALL),
    ARCoachingSample("limited", ArGuidanceCue.TRACKING_LIMITED),
    ARCoachingSample("limited-too-fast", ArGuidanceCue.TRACKING_LIMITED, hint = ArTrackingHint.TOO_FAST),
    ARCoachingSample("limited-low-detail", ArGuidanceCue.TRACKING_LIMITED, hint = ArTrackingHint.LOW_DETAIL),
    ARCoachingSample("relocalizing", ArGuidanceCue.RELOCALIZING),
)

/**
 * A full phone screen of the coaching card over a camera stand-in — the visual-QA surface for
 * the overlay. It needs no ARCore session and no Filament, so it renders in `@Preview`, in the
 * Roborazzi goldens and in the debug-only `ARCoachingPreviewActivity` on an emulator.
 *
 * @param backdrop a camera frame stand-in; `null` paints a flat mid-grey feed.
 * @param showLabel draws the sample name in the top corner, for a gallery read at a glance.
 */
@Composable
internal fun ARCoachingFrame(
    sample: ARCoachingSample,
    modifier: Modifier = Modifier,
    backdrop: Painter? = null,
    showLabel: Boolean = false,
) {
    Box(modifier.fillMaxSize().background(CAMERA_FEED_STAND_IN)) {
        if (backdrop != null) {
            Image(
                painter = backdrop,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        ARCoachingOverlay(
            cue = sample.cue,
            surface = sample.surface,
            hint = sample.hint,
            scanLingering = sample.scanLingering,
        )
        if (showLabel) {
            Text(
                text = sample.label,
                style = MaterialTheme.typography.labelSmall,
                color = SceneViewTokens.ArOverlay.onScrim,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(SceneViewTokens.Space.sm)
                    .background(
                        if (isSystemInDarkTheme()) {
                            SceneViewTokens.ArOverlay.scrimDark
                        } else {
                            SceneViewTokens.ArOverlay.scrimLight
                        },
                    )
                    .padding(horizontal = SceneViewTokens.Space.xs),
            )
        }
    }
}

/** A mid-grey "room" so the scrim card reads against something, as it does over a camera. */
private val CAMERA_FEED_STAND_IN = Color(0xFF6B7078)

@Preview(name = "AR coaching — scan (light)", widthDp = 412, heightDp = 915)
@Composable
private fun ARCoachingScanLightPreview() = ARCoachingFrame(ARCoachingSamples[2])

@Preview(
    name = "AR coaching — scan (dark)",
    widthDp = 412,
    heightDp = 915,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun ARCoachingScanDarkPreview() = ARCoachingFrame(ARCoachingSamples[2])

@Preview(name = "AR coaching — too fast (dark)", widthDp = 412, heightDp = 915,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ARCoachingTooFastDarkPreview() = ARCoachingFrame(ARCoachingSamples[8])

@Preview(name = "AR coaching — surface found (light)", widthDp = 412, heightDp = 915)
@Composable
private fun ARCoachingFoundLightPreview() = ARCoachingFrame(ARCoachingSamples[5])

@Preview(name = "AR coaching — landscape", widthDp = 915, heightDp = 412)
@Composable
private fun ARCoachingLandscapePreview() = ARCoachingFrame(ARCoachingSamples[3])

package io.github.sceneview.demo.common.placement

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.tooling.preview.Preview
import io.github.sceneview.ar.ARCoachingOverlay
import io.github.sceneview.ar.ArGuidanceCue
import io.github.sceneview.ar.PlacementSurface
import io.github.sceneview.demo.theme.SceneViewTokens

/**
 * Every visible [ArGuidanceCue] of the SDK's `ARCoachingOverlay`, floor and wall, side by
 * side over a camera stand-in — the visual-QA surface for the coaching overlay. It needs no
 * ARCore session and no Filament, so it renders in `@Preview` and in the debug-only
 * `ARCoachingPreviewActivity` on an emulator, in light and dark.
 *
 * @param backdrop a camera frame stand-in; `null` paints a flat mid-grey feed.
 */
@Composable
internal fun ARCoachingGallery(backdrop: Painter? = null, modifier: Modifier = Modifier) {
    val cues = ArGuidanceCue.entries.filter { it != ArGuidanceCue.NONE }
    Column(modifier.fillMaxSize().background(Color.Black)) {
        cues.forEach { cue ->
            Row(Modifier.fillMaxWidth().weight(1f)) {
                PlacementSurface.entries.forEach { surface ->
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(SceneViewTokens.Space.xs)
                            .clipToBounds()
                            .background(CAMERA_FEED_STAND_IN),
                    ) {
                        if (backdrop != null) {
                            Image(
                                painter = backdrop,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        ARCoachingOverlay(cue = cue, surface = surface)
                        Text(
                            text = "${cue.name} · ${surface.name}",
                            style = MaterialTheme.typography.labelSmall,
                            color = SceneViewTokens.ArOverlay.onScrim,
                            modifier = Modifier
                                .align(Alignment.TopStart)
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
        }
    }
}

/** A mid-grey "room" so the scrim disc reads against something, as it does over a camera. */
private val CAMERA_FEED_STAND_IN = Color(0xFF6B7078)

@Preview(name = "AR coaching — every cue (light)", widthDp = 412, heightDp = 915)
@Composable
private fun ARCoachingGalleryLightPreview() = ARCoachingGallery()

@Preview(
    name = "AR coaching — every cue (dark)",
    widthDp = 412,
    heightDp = 915,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun ARCoachingGalleryDarkPreview() = ARCoachingGallery()

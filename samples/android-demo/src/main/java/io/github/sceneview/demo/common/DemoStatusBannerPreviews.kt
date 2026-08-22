package io.github.sceneview.demo.common

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.sceneview.demo.DemoBottomOverlayScope
import io.github.sceneview.demo.theme.SceneViewDemoTheme
import io.github.sceneview.demo.theme.SceneViewTokens

/**
 * `@Preview` coverage for the AR coaching overlay, [DemoStatusBanner] (#3265).
 *
 * The background is deliberately the hardest case rather than a flat grey: a bright
 * overexposed sky at the top fading to a dark interior at the bottom, which is what
 * a phone actually points at when it is being told to "move around slowly". A pill
 * that stays readable across that gradient is readable over a camera feed.
 *
 * All three tones are shown at once, in the order a demo escalates through them.
 */
@Composable
private fun CameraFeedStandIn(content: @Composable DemoBottomOverlayScope.() -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xFFEDF3FF), // blown-out sky
                        Color(0xFFB8A88C), // sunlit wall
                        Color(0xFF2B2620), // shadowed interior
                    )
                )
            ),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = SceneViewTokens.Space.lg),
            verticalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.sm),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // `settingsFabReservedSpace` stands in at the value a demo with a
            // Settings FAB reports, so the preview shows the real off-centre band.
            DemoBottomOverlayScope(this, 104.dp).content()
        }
    }
}

@Composable
private fun DemoBottomOverlayScope.AllTones() {
    DemoStatusBanner(
        text = "Move your phone slowly to scan the surface",
        tone = DemoStatusTone.Progress,
    )
    DemoStatusBanner(
        text = "Too dark — turn on a light or move somewhere brighter",
        tone = DemoStatusTone.Guidance,
    )
    DemoStatusBanner(
        text = "No ARCore Cloud API key — Geospatial features stay off until one is set",
        tone = DemoStatusTone.Blocked,
    )
}

@Preview(name = "AR status banner — light", showBackground = true, widthDp = 411, heightDp = 500)
@Composable
private fun DemoStatusBannerLightPreview() {
    SceneViewDemoTheme(darkTheme = false, dynamicColor = false) {
        CameraFeedStandIn { AllTones() }
    }
}

@Preview(
    name = "AR status banner — dark",
    showBackground = true,
    widthDp = 411,
    heightDp = 500,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun DemoStatusBannerDarkPreview() {
    SceneViewDemoTheme(darkTheme = true, dynamicColor = false) {
        CameraFeedStandIn { AllTones() }
    }
}

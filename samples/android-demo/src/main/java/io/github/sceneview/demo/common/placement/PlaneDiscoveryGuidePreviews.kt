package io.github.sceneview.demo.common.placement

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import com.google.ar.core.TrackingFailureReason
import io.github.sceneview.ar.PlaneDiscoveryGuideOverlay
import io.github.sceneview.ar.PlaneDiscoveryHandHint
import io.github.sceneview.ar.PlaneDiscoveryHelpCard
import io.github.sceneview.ar.PlaneDiscoveryPhase

/**
 * `@Preview` coverage for every visible [PlaneDiscoveryPhase] of the library's
 * `PlaneDiscoveryGuide` overlay (#2241).
 *
 * The guide is pure Compose UI (no ARCore session, no Filament), so each phase renders
 * deterministically here via the stateless [PlaneDiscoveryGuideOverlay] — the autonomous
 * visual-QA surface for this component ahead of the Sprint-1 PR 5 device pass. The dark
 * background stands in for the live camera feed.
 */
@Composable
private fun CameraFeedStandIn(content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF37474F)),
        content = content,
    )
}

@Preview(name = "Hand hint (3s, no plane)", showBackground = true, widthDp = 420, heightDp = 640)
@Composable
private fun PlaneDiscoveryGuideHandHintPreview() = CameraFeedStandIn {
    PlaneDiscoveryGuideOverlay(phase = PlaneDiscoveryPhase.HAND_HINT)
}

@Preview(name = "Help offered (8s, no plane)", showBackground = true, widthDp = 420, heightDp = 640)
@Composable
private fun PlaneDiscoveryGuideHelpOfferedPreview() = CameraFeedStandIn {
    PlaneDiscoveryGuideOverlay(phase = PlaneDiscoveryPhase.HELP_OFFERED)
}

@Preview(name = "Tracking lost — excessive motion", showBackground = true, widthDp = 420, heightDp = 640)
@Composable
private fun PlaneDiscoveryGuideLostMotionPreview() = CameraFeedStandIn {
    PlaneDiscoveryGuideOverlay(
        phase = PlaneDiscoveryPhase.LOST,
        trackingFailureReason = TrackingFailureReason.EXCESSIVE_MOTION,
    )
}

@Preview(name = "Tracking lost — insufficient light", showBackground = true, widthDp = 420, heightDp = 640)
@Composable
private fun PlaneDiscoveryGuideLostLightPreview() = CameraFeedStandIn {
    PlaneDiscoveryGuideOverlay(
        phase = PlaneDiscoveryPhase.LOST,
        trackingFailureReason = TrackingFailureReason.INSUFFICIENT_LIGHT,
    )
}

@Preview(name = "Built-in help card", showBackground = true, widthDp = 420, heightDp = 640)
@Composable
private fun PlaneDiscoveryHelpCardPreview() = CameraFeedStandIn {
    Box(modifier = Modifier.align(androidx.compose.ui.Alignment.Center)) {
        PlaneDiscoveryHelpCard()
    }
}

@Preview(name = "Hand hint animation (static frame)", showBackground = true, backgroundColor = 0xFF37474F)
@Composable
private fun PlaneDiscoveryHandHintGlyphPreview() {
    PlaneDiscoveryHandHint()
}

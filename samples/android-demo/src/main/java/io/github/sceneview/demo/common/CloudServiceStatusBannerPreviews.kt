@file:Suppress("MaxLineLength") // @Preview annotation strings and uiMode flags are intentionally long

package io.github.sceneview.demo.common

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.sceneview.demo.DemoBottomOverlayScope
import io.github.sceneview.demo.theme.SceneViewDemoTheme

/**
 * `@Preview` coverage for every [CloudServiceStatus] the shared
 * [CloudServiceStatusBanner] renders (#3262) — light and dark for each, so a change to
 * [CloudServiceStatus.message] or [CloudServiceStatus.tone] is visible here without
 * staging the real device/console failure any of the five Cloud demos need to hit it.
 *
 * The dark backing stands in for the live camera feed the banner is always drawn over
 * in the app; `DemoBottomOverlayScope` is constructed directly (its constructor is
 * `internal`, so this only compiles from inside this module) with `0.dp` reserved
 * space — the same as every demo without a Settings FAB.
 */
@Composable
private fun CloudServiceStatusBannerSample(status: CloudServiceStatus) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF37474F)),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            DemoBottomOverlayScope(this, 0.dp).CloudServiceStatusBanner(status)
        }
    }
}

@Preview(showBackground = true, name = "CloudService - API key missing")
@Composable
private fun CloudServiceStatusApiKeyMissingPreview() {
    SceneViewDemoTheme(dynamicColor = false) {
        Surface { CloudServiceStatusBannerSample(CloudServiceStatus.ApiKeyMissing) }
    }
}

@Preview(showBackground = true, name = "CloudService - API key rejected")
@Composable
private fun CloudServiceStatusApiKeyRejectedPreview() {
    SceneViewDemoTheme(dynamicColor = false) {
        Surface { CloudServiceStatusBannerSample(CloudServiceStatus.ApiKeyRejected("Hosting")) }
    }
}

@Preview(showBackground = true, name = "CloudService - Quota exhausted")
@Composable
private fun CloudServiceStatusQuotaExhaustedPreview() {
    SceneViewDemoTheme(dynamicColor = false) {
        Surface { CloudServiceStatusBannerSample(CloudServiceStatus.QuotaExhausted("Resolve")) }
    }
}

@Preview(showBackground = true, name = "CloudService - No network")
@Composable
private fun CloudServiceStatusNoNetworkPreview() {
    SceneViewDemoTheme(dynamicColor = false) {
        Surface { CloudServiceStatusBannerSample(CloudServiceStatus.NoNetwork) }
    }
}

@Preview(showBackground = true, name = "CloudService - Earth localizing")
@Composable
private fun CloudServiceStatusEarthLocalizingPreview() {
    SceneViewDemoTheme(dynamicColor = false) {
        Surface { CloudServiceStatusBannerSample(CloudServiceStatus.EarthLocalizing) }
    }
}

@Preview(showBackground = true, name = "CloudService - Dark API key missing", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun CloudServiceStatusDarkApiKeyMissingPreview() {
    SceneViewDemoTheme(dynamicColor = false) {
        Surface { CloudServiceStatusBannerSample(CloudServiceStatus.ApiKeyMissing) }
    }
}

@Preview(showBackground = true, name = "CloudService - Dark API key rejected", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun CloudServiceStatusDarkApiKeyRejectedPreview() {
    SceneViewDemoTheme(dynamicColor = false) {
        Surface { CloudServiceStatusBannerSample(CloudServiceStatus.ApiKeyRejected("Hosting")) }
    }
}

@Preview(showBackground = true, name = "CloudService - Dark Quota exhausted", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun CloudServiceStatusDarkQuotaExhaustedPreview() {
    SceneViewDemoTheme(dynamicColor = false) {
        Surface { CloudServiceStatusBannerSample(CloudServiceStatus.QuotaExhausted("Resolve")) }
    }
}

@Preview(showBackground = true, name = "CloudService - Dark No network", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun CloudServiceStatusDarkNoNetworkPreview() {
    SceneViewDemoTheme(dynamicColor = false) {
        Surface { CloudServiceStatusBannerSample(CloudServiceStatus.NoNetwork) }
    }
}

@Preview(showBackground = true, name = "CloudService - Dark Earth localizing", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun CloudServiceStatusDarkEarthLocalizingPreview() {
    SceneViewDemoTheme(dynamicColor = false) {
        Surface { CloudServiceStatusBannerSample(CloudServiceStatus.EarthLocalizing) }
    }
}

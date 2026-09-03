@file:Suppress("MaxLineLength") // @Preview annotation strings and uiMode flags are intentionally long

package io.github.sceneview.demo.common

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.sceneview.demo.DemoBottomOverlayScope
import io.github.sceneview.demo.demos.internal.CloudAnchorCard
import io.github.sceneview.demo.demos.internal.CloudAnchorScenario
import io.github.sceneview.demo.demos.internal.card
import io.github.sceneview.demo.demos.internal.state
import io.github.sceneview.demo.demos.internal.status
import io.github.sceneview.demo.theme.SceneViewDemoTheme

/**
 * `@Preview` coverage for the Cloud Anchor demo's card and pill, in every state, light and
 * dark (#3421).
 *
 * These are the only way most of this screen can be *seen* without shipping it: hosting,
 * a hosted code, an expired code and a rejected API key each need a live ARCore Cloud
 * project, a mapped room and a second phone, and `emulator-5554` cannot run ARCore at all
 * (#2754). Each preview is driven by a [CloudAnchorScenario] — the same enum the QA
 * override and the `--es qa_state` extra use — so a preview, a device capture and an
 * emulator screenshot of "Hosted" are all the same state by construction.
 *
 * The dark slab stands in for the live camera feed. It is deliberately *not* the theme's
 * surface: these cards must look identical in light and dark, so a preview that inherited
 * the theme's background would hide exactly the regression they exist to catch.
 */
@Composable
private fun CloudAnchorScenarioSample(scenario: CloudAnchorScenario) {
    val state = scenario.state()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF37474F))
            .padding(vertical = 12.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // `DemoBottomOverlayScope`'s constructor is `internal`, so this only compiles
            // from inside the module; 0.dp matches every demo now that the dock is centred.
            val scope = DemoBottomOverlayScope(this, 0.dp)
            with(scope) {
                DemoStatusBanner(text = state.status().text, tone = state.status().tone)
                if (state.card() != CloudAnchorCard.None) {
                    Column(modifier = Modifier.padding(top = 8.dp)) {
                        DemoBottomOverlayScope(this, 0.dp)
                            .CloudAnchorFlowCard(card = state.card(), onCodeChange = {})
                    }
                }
            }
        }
    }
}

@Composable
private fun CloudAnchorPreview(scenario: CloudAnchorScenario, dark: Boolean) {
    SceneViewDemoTheme(darkTheme = dark, dynamicColor = false) {
        Surface { CloudAnchorScenarioSample(scenario) }
    }
}

@Preview(showBackground = true, name = "CloudAnchor - Placing (light)")
@Composable
private fun CloudAnchorPlacingLightPreview() = CloudAnchorPreview(CloudAnchorScenario.Placing, dark = false)

@Preview(showBackground = true, name = "CloudAnchor - Placing (dark)", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun CloudAnchorPlacingDarkPreview() = CloudAnchorPreview(CloudAnchorScenario.Placing, dark = true)

@Preview(showBackground = true, name = "CloudAnchor - Mapping (light)")
@Composable
private fun CloudAnchorMappingLightPreview() = CloudAnchorPreview(CloudAnchorScenario.Mapping, dark = false)

@Preview(showBackground = true, name = "CloudAnchor - Mapping (dark)", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun CloudAnchorMappingDarkPreview() = CloudAnchorPreview(CloudAnchorScenario.Mapping, dark = true)

@Preview(showBackground = true, name = "CloudAnchor - Ready to host (light)")
@Composable
private fun CloudAnchorReadyLightPreview() = CloudAnchorPreview(CloudAnchorScenario.ReadyToHost, dark = false)

@Preview(showBackground = true, name = "CloudAnchor - Ready to host (dark)", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun CloudAnchorReadyDarkPreview() = CloudAnchorPreview(CloudAnchorScenario.ReadyToHost, dark = true)

@Preview(showBackground = true, name = "CloudAnchor - Hosting (light)")
@Composable
private fun CloudAnchorHostingLightPreview() = CloudAnchorPreview(CloudAnchorScenario.Hosting, dark = false)

@Preview(showBackground = true, name = "CloudAnchor - Hosting (dark)", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun CloudAnchorHostingDarkPreview() = CloudAnchorPreview(CloudAnchorScenario.Hosting, dark = true)

@Preview(showBackground = true, name = "CloudAnchor - Hosted (light)")
@Composable
private fun CloudAnchorHostedLightPreview() = CloudAnchorPreview(CloudAnchorScenario.Hosted, dark = false)

@Preview(showBackground = true, name = "CloudAnchor - Hosted (dark)", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun CloudAnchorHostedDarkPreview() = CloudAnchorPreview(CloudAnchorScenario.Hosted, dark = true)

@Preview(showBackground = true, name = "CloudAnchor - Host failed (light)")
@Composable
private fun CloudAnchorHostFailedLightPreview() = CloudAnchorPreview(CloudAnchorScenario.HostFailed, dark = false)

@Preview(showBackground = true, name = "CloudAnchor - Host failed (dark)", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun CloudAnchorHostFailedDarkPreview() = CloudAnchorPreview(CloudAnchorScenario.HostFailed, dark = true)

@Preview(showBackground = true, name = "CloudAnchor - Resolve empty (light)")
@Composable
private fun CloudAnchorResolveEmptyLightPreview() = CloudAnchorPreview(CloudAnchorScenario.ResolveEmpty, dark = false)

@Preview(showBackground = true, name = "CloudAnchor - Resolve empty (dark)", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun CloudAnchorResolveEmptyDarkPreview() = CloudAnchorPreview(CloudAnchorScenario.ResolveEmpty, dark = true)

@Preview(showBackground = true, name = "CloudAnchor - Resolving (light)")
@Composable
private fun CloudAnchorResolvingLightPreview() = CloudAnchorPreview(CloudAnchorScenario.Resolving, dark = false)

@Preview(showBackground = true, name = "CloudAnchor - Resolving (dark)", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun CloudAnchorResolvingDarkPreview() = CloudAnchorPreview(CloudAnchorScenario.Resolving, dark = true)

@Preview(showBackground = true, name = "CloudAnchor - Resolved (light)")
@Composable
private fun CloudAnchorResolvedLightPreview() = CloudAnchorPreview(CloudAnchorScenario.Resolved, dark = false)

@Preview(showBackground = true, name = "CloudAnchor - Resolved (dark)", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun CloudAnchorResolvedDarkPreview() = CloudAnchorPreview(CloudAnchorScenario.Resolved, dark = true)

@Preview(showBackground = true, name = "CloudAnchor - Code not found (light)")
@Composable
private fun CloudAnchorNotFoundLightPreview() = CloudAnchorPreview(CloudAnchorScenario.ResolveNotFound, dark = false)

@Preview(showBackground = true, name = "CloudAnchor - Code not found (dark)", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun CloudAnchorNotFoundDarkPreview() = CloudAnchorPreview(CloudAnchorScenario.ResolveNotFound, dark = true)

@Preview(showBackground = true, name = "CloudAnchor - API key missing (light)")
@Composable
private fun CloudAnchorApiKeyMissingLightPreview() = CloudAnchorPreview(CloudAnchorScenario.ApiKeyMissing, dark = false)

@Preview(showBackground = true, name = "CloudAnchor - API key missing (dark)", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun CloudAnchorApiKeyMissingDarkPreview() = CloudAnchorPreview(CloudAnchorScenario.ApiKeyMissing, dark = true)

@Preview(showBackground = true, name = "CloudAnchor - No network (light)")
@Composable
private fun CloudAnchorNoNetworkLightPreview() = CloudAnchorPreview(CloudAnchorScenario.NoNetwork, dark = false)

@Preview(showBackground = true, name = "CloudAnchor - No network (dark)", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun CloudAnchorNoNetworkDarkPreview() = CloudAnchorPreview(CloudAnchorScenario.NoNetwork, dark = true)

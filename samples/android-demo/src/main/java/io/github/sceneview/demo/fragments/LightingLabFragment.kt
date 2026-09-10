package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.LightingLabDemo

/**
 * "Lighting Lab" — the *workbench* half of the lighting pair since #3496: one fixed
 * rig over the stage `lighting` also lights, with every knob live on the same frame.
 * It consolidated the retired `dynamic-sky`, `environment`, `reflection-probes` and
 * `post-processing` demos (#2239 Batch 2); #3496 then moved the first two to
 * `lighting`, whose Sun and Image rigs are where those subjects belong. Every old
 * deep-link id stays routable through
 * [io.github.sceneview.demo.DeepLinkRouter.DEMO_ID_ALIASES].
 */
object LightingLabFragment : DemoFragment {
    override val entry: DemoEntry = DemoEntry(
        id = "lighting-lab",
        titleRes = R.string.demo_lighting_lab_title,
        subtitleRes = R.string.demo_lighting_lab_subtitle,
        category = DemoCategory.RENDERING,
        icon = Icons.Filled.WbSunny,
        order = 11,
        tags = setOf(
            "light", "hdr", "ibl", "skybox", "environment",
            "reflection", "exposure", "ssao", "fog", "post-fx",
        ),
        // #3496 replaced the five tabs with one all-live frame.
        updatedIn = "4.35.0",
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        LightingLabDemo(onBack)
    }
}

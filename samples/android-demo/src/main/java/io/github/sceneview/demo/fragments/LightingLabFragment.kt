package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.LightingLabDemo

/**
 * Unified "Lighting Lab" demo — consolidates the retired `dynamic-sky`,
 * `environment`, `reflection-probes`, and `post-processing` demos behind one
 * entry with an internal segmented-button toggle (#2239 Batch 2). The old
 * deep-link ids stay routable through
 * [io.github.sceneview.demo.DeepLinkRouter.DEMO_ID_ALIASES].
 */
object LightingLabFragment : DemoFragment {
    override val entry: DemoEntry = DemoEntry(
        id = "lighting-lab",
        titleRes = R.string.demo_lighting_lab_title,
        subtitleRes = R.string.demo_lighting_lab_subtitle,
        category = DemoCategory.LIGHTING_ENVIRONMENT,
        icon = Icons.Filled.WbSunny,
        order = 7,
        tags = setOf("light", "hdr", "ibl", "skybox", "environment", "reflection", "bloom", "post-fx"),
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        LightingLabDemo(onBack)
    }
}

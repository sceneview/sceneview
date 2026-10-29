package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.LightingDemo

/**
 * Append-only fragment for the `lighting` demo. See [DemoFragment].
 *
 * Three rigs (image-based, studio, sun) over one stage (#3496), plus the reflection
 * probe, contact shading, fog and anti-aliasing switches that used to be the separate
 * `lighting-lab` card (#4603). The retired `lighting-lab`, `movable-light`,
 * `dynamic-sky`, `reflection-probes`, `post-processing` and `fog` ids deep-link here
 * through [io.github.sceneview.demo.DeepLinkRouter.DEMO_ID_ALIASES].
 */
object LightingFragment : DemoFragment {
    override val entry: DemoEntry = DemoEntry(
        id = "lighting",
        titleRes = R.string.demo_lighting_title,
        subtitleRes = R.string.demo_lighting_subtitle,
        category = DemoCategory.CREATE,
        icon = Icons.Filled.Lightbulb,
        order = 4,
        addedIn = "4.0.0",
        updatedIn = "4.57.0",
        tags = setOf(
            "light", "hdr", "ibl", "studio", "key", "sun", "shadow", "pbr",
            "skybox", "environment", "reflection", "exposure", "ssao", "fog", "post-fx",
        ),
        // #3496 rebuilt the screen as a three-rig showcase over a shared stage; #4603
        // folded the Lighting Lab bench into it.
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        LightingDemo(onBack)
    }
}

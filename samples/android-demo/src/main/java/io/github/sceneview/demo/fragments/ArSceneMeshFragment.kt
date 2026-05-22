package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.DemoStatus
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.ARSceneMeshDemo

/** Append-only fragment for the `ar-scene-mesh` demo. See [DemoFragment]. */
object ArSceneMeshFragment : DemoFragment {
    override val entry: DemoEntry = DemoEntry(
        id = "ar-scene-mesh",
        titleRes = R.string.demo_ar_scene_mesh_title,
        subtitleRes = R.string.demo_ar_scene_mesh_subtitle,
        category = DemoCategory.AUGMENTED_REALITY,
        icon = Icons.Filled.GridOn,
        // Requires outdoor location with Street View coverage + Cloud API key.
        status = DemoStatus.Working,
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        ARSceneMeshDemo(onBack)
    }
}

package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.OcclusionMaterialDemo

/** Append-only fragment for the `occlusion-material` demo. See [DemoFragment]. */
object OcclusionMaterialFragment : DemoFragment {
    override val entry: DemoEntry = DemoEntry(
        id = "occlusion-material",
        titleRes = R.string.demo_occlusion_material_title,
        subtitleRes = R.string.demo_occlusion_material_subtitle,
        category = DemoCategory.ADVANCED,
        icon = Icons.Filled.VisibilityOff,
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        OcclusionMaterialDemo(onBack)
    }
}

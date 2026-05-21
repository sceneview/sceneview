package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Hexagon
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.CustomMeshDemo

/** Append-only fragment for the `custom-mesh` demo. See [DemoFragment]. */
object CustomMeshFragment : DemoFragment {
    override val entry: DemoEntry = DemoEntry(
        id = "custom-mesh",
        titleRes = R.string.demo_custom_mesh_title,
        subtitleRes = R.string.demo_custom_mesh_subtitle,
        category = DemoCategory.ADVANCED,
        icon = Icons.Filled.Hexagon,
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        CustomMeshDemo(onBack)
    }
}

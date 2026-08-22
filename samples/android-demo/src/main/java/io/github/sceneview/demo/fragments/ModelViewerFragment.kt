package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.ModelViewerDemo

/** Append-only fragment for the `model-viewer` demo. See [DemoFragment]. */
object ModelViewerFragment : DemoFragment {
    override val entry: DemoEntry = DemoEntry(
        id = "model-viewer",
        titleRes = R.string.demo_model_viewer,
        subtitleRes = R.string.demo_model_viewer_subtitle,
        category = DemoCategory.BASICS_3D,
        icon = Icons.Filled.ViewInAr,
        order = 1,
        tags = setOf("gltf", "glb", "hdr", "ibl", "orbit", "ar", "viewer"),
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        ModelViewerDemo(onBack)
    }
}

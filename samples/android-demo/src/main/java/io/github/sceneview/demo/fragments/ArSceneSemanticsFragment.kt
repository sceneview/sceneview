package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Park
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.ARSceneSemanticsDemo

/** Append-only fragment for the `ar-scene-semantics` demo. See [DemoFragment]. */
object ArSceneSemanticsFragment : DemoFragment {
    override val entry: DemoEntry = DemoEntry(
        id = "ar-scene-semantics",
        titleRes = R.string.demo_ar_scene_semantics_title,
        subtitleRes = R.string.demo_ar_scene_semantics_subtitle,
        category = DemoCategory.AR_UNDERSTANDING,
        icon = Icons.Filled.Park,
        order = 38,
        tags = setOf("ar", "semantics", "segmentation", "labeling", "outdoor"),
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        ARSceneSemanticsDemo(onBack)
    }
}

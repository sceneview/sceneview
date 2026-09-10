package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Palette
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.DemoStatus
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.MaterialsDemo

/** Append-only fragment for the `materials` demo. See [DemoFragment]. */
object MaterialsFragment : DemoFragment {
    override val entry: DemoEntry = DemoEntry(
        id = "materials",
        titleRes = R.string.demo_materials_title,
        subtitleRes = R.string.demo_materials_subtitle,
        category = DemoCategory.GEOMETRY_MATERIALS,
        icon = Icons.Filled.Palette,
        order = 9,
        tags = setOf(
            "pbr", "material", "metallic", "roughness", "clearcoat",
            "sheen", "transmission", "emissive", "occlusion",
        ),
        // #3495 rebuilt the demo as a procedural material studio: nothing is
        // streamed and nothing is downloaded, so there is no network path left to
        // fail. The #2088 known-issue chip stays removed.
        status = DemoStatus.Working,
        // #3495 / #3538 rebuilt the section as a lit PBR studio.
        updatedIn = "4.35.0",
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        MaterialsDemo(onBack)
    }
}

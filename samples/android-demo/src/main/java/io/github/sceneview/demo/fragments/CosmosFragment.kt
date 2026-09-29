package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.CosmosDemo

/** Append-only fragment for the `cosmos` demo. See [DemoFragment]. */
object CosmosFragment : DemoFragment {
    override val entry: DemoEntry = DemoEntry(
        id = "cosmos",
        titleRes = R.string.demo_cosmos_title,
        subtitleRes = R.string.demo_cosmos_subtitle,
        category = DemoCategory.CREATE,
        icon = Icons.Filled.AutoAwesome,
        order = 33,
        tags = setOf("bloom", "emissive", "particles", "procedural", "shader", "galaxy", "space", "custom material"),
        sinceVersion = "4.49.0",
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        CosmosDemo(onBack)
    }
}

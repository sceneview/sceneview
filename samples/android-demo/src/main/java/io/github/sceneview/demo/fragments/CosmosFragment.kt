package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.CosmosDemo

/**
 * Append-only fragment for the `cosmos` demo. See [DemoFragment].
 *
 * Cosmos carries the shared Record action of [io.github.sceneview.demo.DemoScaffold]: the
 * retired `video-recording` link opens it with the Record pill showing (samples step 0).
 */
object CosmosFragment : DemoFragment {
    override val entry: DemoEntry = DemoEntry(
        id = "cosmos",
        titleRes = R.string.demo_cosmos_title,
        subtitleRes = R.string.demo_cosmos_subtitle,
        category = DemoCategory.CREATE,
        icon = Icons.Filled.AutoAwesome,
        order = 1,
        addedIn = "4.49.0",
        updatedIn = "4.51.0",
        tags = setOf(
            "bloom", "emissive", "particles", "procedural", "shader", "galaxy", "space", "custom material",
            // The shared Record action (samples step 0, formerly the `video-recording` card).
            "video", "recording", "mp4", "capture", "encoder",
        ),
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        CosmosDemo(onBack)
    }
}

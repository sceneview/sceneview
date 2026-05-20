package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.VideoDemo

/** Append-only fragment for the `video` demo. See [DemoFragment]. */
object VideoFragment : DemoFragment {
    override val entry: DemoEntry = DemoEntry(
        id = "video",
        titleRes = R.string.demo_video_title,
        subtitleRes = R.string.demo_video_subtitle,
        category = DemoCategory.CONTENT,
        icon = Icons.Filled.VideoLibrary,
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        VideoDemo(onBack)
    }
}

package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.VideoRecordingDemo

/** Append-only fragment for the `video-recording` demo. See [DemoFragment]. */
object VideoRecordingFragment : DemoFragment {
    override val entry: DemoEntry = DemoEntry(
        id = "video-recording",
        titleRes = R.string.demo_video_recording_title,
        subtitleRes = R.string.demo_video_recording_subtitle,
        category = DemoCategory.ADVANCED,
        icon = Icons.Filled.Videocam,
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        VideoRecordingDemo(onBack)
    }
}

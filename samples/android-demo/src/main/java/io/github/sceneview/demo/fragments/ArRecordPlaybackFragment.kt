package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.ARRecordPlaybackDemo

/** Append-only fragment for the `ar-record-playback` demo. See [DemoFragment]. */
object ArRecordPlaybackFragment : DemoFragment {
    override val entry: DemoEntry = DemoEntry(
        id = "ar-record-playback",
        titleRes = R.string.demo_ar_record_playback_title,
        subtitleRes = R.string.demo_ar_record_playback_subtitle,
        category = DemoCategory.DEV_TOOLS,
        icon = Icons.Filled.RadioButtonChecked,
        order = 13,
        tags = setOf("ar", "recording", "playback", "session", "mp4", "replay"),
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        ARRecordPlaybackDemo(onBack)
    }
}

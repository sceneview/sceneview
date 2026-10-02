package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Polyline
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.DemoMode
import io.github.sceneview.demo.DemoModeHost
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.ARRecordPlaybackDemo
import io.github.sceneview.demo.demos.ARRerunDemo

/**
 * Append-only fragment for the `ar-rerun` demo. See [DemoFragment].
 *
 * Samples step 0 added `ar-record-playback` as the Session MP4 mode (`?tab=session-mp4`):
 * the ARCore session recorder and its replay, next to the Rerun stream of a session.
 */
object ArRerunFragment : DemoFragment {
    override val entry: DemoEntry = DemoEntry(
        id = "ar-rerun",
        titleRes = R.string.demo_ar_rerun_title,
        subtitleRes = R.string.demo_ar_rerun_subtitle,
        category = DemoCategory.DEV_TOOLS,
        icon = Icons.Filled.Polyline,
        order = 12,
        addedIn = "4.0.1",
        updatedIn = "4.51.0",
        tags = setOf(
            "ar", "rerun", "replay", "3d", "streaming", "pose", "plane", "point cloud", "debug",
            "recording", "playback", "session", "mp4",
        ),
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        DemoModeHost(
            modes = listOf(
                DemoMode("rerun", R.string.demo_mode_rerun),
                DemoMode("session-mp4", R.string.demo_mode_session_mp4),
            ),
            tabToMode = mapOf(0 to 0, 1 to 1),
        ) { mode ->
            when (mode) {
                1 -> ARRecordPlaybackDemo(onBack)
                else -> ARRerunDemo(onBack)
            }
        }
    }
}

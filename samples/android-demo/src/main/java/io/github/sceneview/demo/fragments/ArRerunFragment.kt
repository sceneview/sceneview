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
 *
 * Shown as "Room Scan" since #4306; the id, the mode keys and the deep link are unchanged.
 *
 * The card shows no mode switch: it opens on its first mode, and Session MP4 is reached by
 * deep link only (`?tab=session-mp4`, or the retired `ar-record-playback` id) — which is how
 * the replay harness and the instrumented playback tests open it.
 */
object ArRerunFragment : DemoFragment {
    val modes = listOf(
        DemoMode("rerun", R.string.demo_mode_rerun),
        DemoMode("session-mp4", R.string.demo_mode_session_mp4),
    )

    override val entry: DemoEntry = DemoEntry(
        id = "ar-rerun",
        titleRes = R.string.demo_ar_rerun_title,
        subtitleRes = R.string.demo_ar_rerun_subtitle,
        category = DemoCategory.DEV_TOOLS,
        icon = Icons.Filled.Polyline,
        order = 12,
        addedIn = "4.0.1",
        updatedIn = "4.52.0",
        tags = setOf(
            "ar", "room", "scan", "mesh", "rerun", "replay", "3d", "streaming", "pose", "plane", "point cloud",
            "debug",
            "recording", "playback", "session",
        ),
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        DemoModeHost(
            modes = modes,
            tabToMode = mapOf(0 to 0, 1 to 1),
            showSwitch = false,
        ) { mode ->
            when (mode) {
                1 -> ARRecordPlaybackDemo(onBack)
                else -> ARRerunDemo(onBack)
            }
        }
    }
}

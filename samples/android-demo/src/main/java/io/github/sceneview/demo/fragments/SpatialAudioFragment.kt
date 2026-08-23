package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.SpatialAudioDemo

/** Append-only fragment for the `spatial-audio` demo. See [DemoFragment]. */
object SpatialAudioFragment : DemoFragment {
    override val entry: DemoEntry = DemoEntry(
        id = "spatial-audio",
        titleRes = R.string.demo_spatial_audio_title,
        subtitleRes = R.string.demo_spatial_audio_subtitle,
        category = DemoCategory.ADVANCED,
        icon = Icons.Filled.VolumeUp,
        order = 14,
        tags = setOf("audio", "sound", "spatial", "3d-audio", "orbit"),
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        SpatialAudioDemo(onBack)
    }
}

package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.soundgarden.ARSoundGardenDemo

/** Append-only fragment for the `ar-sound-garden` demo. See [DemoFragment]. */
object ArSoundGardenFragment : DemoFragment {
    override val entry: DemoEntry = DemoEntry(
        id = "ar-sound-garden",
        titleRes = R.string.demo_ar_sound_garden_title,
        subtitleRes = R.string.demo_ar_sound_garden_subtitle,
        category = DemoCategory.PLACE_AR,
        icon = Icons.Filled.Headphones,
        order = 9,
        tags = setOf("ar", "audio", "spatial audio", "binaural", "headphones", "music", "anchor", "plane"),
        sinceVersion = "4.51.0",
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        ARSoundGardenDemo(onBack)
    }
}

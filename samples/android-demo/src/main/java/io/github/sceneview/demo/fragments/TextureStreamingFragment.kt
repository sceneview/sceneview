package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Texture
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.TextureStreamingDemo

/** Append-only fragment for the `texture-streaming` demo. See [DemoFragment]. */
object TextureStreamingFragment : DemoFragment {
    override val entry: DemoEntry = DemoEntry(
        id = "texture-streaming",
        titleRes = R.string.demo_texture_streaming_title,
        subtitleRes = R.string.demo_texture_streaming_subtitle,
        category = DemoCategory.ADVANCED,
        icon = Icons.Filled.Texture,
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        TextureStreamingDemo(onBack)
    }
}

package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Face
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.ARFaceDemo

/** Append-only fragment for the `ar-face` demo. See [DemoFragment]. */
object ArFaceFragment : DemoFragment {
    override val entry: DemoEntry = DemoEntry(
        id = "ar-face",
        titleRes = R.string.demo_ar_face_title,
        subtitleRes = R.string.demo_ar_face_subtitle,
        category = DemoCategory.AUGMENTED_REALITY,
        icon = Icons.Filled.Face,
        order = 25,
        tags = setOf("ar", "face", "mesh", "tracking", "augmented-faces"),
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        ARFaceDemo(onBack)
    }
}

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
        sourcePath = "src/main/java/io/github/sceneview/demo/demos/ARFaceDemo.kt",
        titleRes = R.string.demo_ar_face_title,
        subtitleRes = R.string.demo_ar_face_subtitle,
        category = DemoCategory.UNDERSTAND,
        icon = Icons.Filled.Face,
        order = 28,
        addedIn = "4.0.1",
        tags = setOf("augmented faces", "ar", "face", "mesh", "tracking", "augmented-faces"),
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        ARFaceDemo(onBack)
    }
}

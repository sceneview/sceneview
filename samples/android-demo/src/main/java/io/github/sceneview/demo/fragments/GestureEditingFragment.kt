package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.GestureEditingDemo

/** Append-only fragment for the `gesture-editing` demo. See [DemoFragment]. */
object GestureEditingFragment : DemoFragment {
    override val entry: DemoEntry = DemoEntry(
        id = "gesture-editing",
        titleRes = R.string.demo_gesture_editing_title,
        subtitleRes = R.string.demo_gesture_editing_subtitle,
        category = DemoCategory.INTERACTION,
        icon = Icons.Filled.OpenWith,
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        GestureEditingDemo(onBack)
    }
}

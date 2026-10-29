package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.ARDepthOfFieldDemo

/** Append-only fragment for the `ar-depth-of-field` demo, "Portrait focus" (#4649). See [DemoFragment]. */
object ArDepthOfFieldFragment : DemoFragment {
    override val entry: DemoEntry = DemoEntry(
        id = "ar-depth-of-field",
        titleRes = R.string.demo_ar_depth_of_field_title,
        subtitleRes = R.string.demo_ar_depth_of_field_subtitle,
        category = DemoCategory.UNDERSTAND,
        icon = Icons.Filled.CenterFocusStrong,
        order = 44,
        addedIn = "4.11.2",
        tags = setOf("ar", "depth", "bokeh", "focus", "portrait", "blur", "post-fx"),
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        ARDepthOfFieldDemo(onBack)
    }
}

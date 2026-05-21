package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.ARPeopleOcclusionDemo

/** Append-only fragment for the `ar-people-occlusion` demo. See [DemoFragment]. */
object ArPeopleOcclusionFragment : DemoFragment {
    override val entry: DemoEntry = DemoEntry(
        id = "ar-people-occlusion",
        titleRes = R.string.demo_ar_people_occlusion_title,
        subtitleRes = R.string.demo_ar_people_occlusion_subtitle,
        category = DemoCategory.AUGMENTED_REALITY,
        icon = Icons.Filled.Accessibility,
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        ARPeopleOcclusionDemo(onBack)
    }
}

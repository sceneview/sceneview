package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pool
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.DemoStatus
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.ARPoolDemo

/** Append-only fragment for the `ar-pool` demo. See [DemoFragment]. */
object ArPoolFragment : DemoFragment {
    override val entry: DemoEntry = DemoEntry(
        id = "ar-pool",
        titleRes = R.string.demo_ar_pool_title,
        subtitleRes = R.string.demo_ar_pool_subtitle,
        category = DemoCategory.PLACE_AR,
        icon = Icons.Filled.Pool,
        order = 21,
        addedIn = "4.57.0",
        tags = setOf("ar", "pool", "occlusion", "water", "garden", "real-size"),
        // Ships unverified on AR hardware: whether the pool reads as dug into a real lawn
        // can only be judged on a phone outdoors. Flip to Working after that pass.
        status = DemoStatus.InReview,
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        ARPoolDemo(onBack)
    }
}

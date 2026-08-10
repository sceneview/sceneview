package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.DemoStatus
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.ARMeasureDemo

/** Append-only fragment for the `ar-measure` demo. See [DemoFragment]. */
object ArMeasureFragment : DemoFragment {
    override val entry: DemoEntry = DemoEntry(
        id = "ar-measure",
        titleRes = R.string.demo_ar_measure_title,
        subtitleRes = R.string.demo_ar_measure_subtitle,
        category = DemoCategory.AUGMENTED_REALITY,
        icon = Icons.Filled.Straighten,
        // Ships unverified on AR hardware: the accuracy figure this demo exists to be
        // honest about has not been measured on a real device yet (AR_MEASURE.md,
        // "Measured error"). Flip to Working once a device pass fills that table in.
        status = DemoStatus.InReview,
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        ARMeasureDemo(onBack)
    }
}

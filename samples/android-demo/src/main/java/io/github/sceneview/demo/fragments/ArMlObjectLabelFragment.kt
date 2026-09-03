package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Label
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.ARMLObjectLabelDemo

/** Append-only fragment for the `ar-ml-object-label` demo. See [DemoFragment]. */
object ArMlObjectLabelFragment : DemoFragment {
    override val entry: DemoEntry = DemoEntry(
        id = "ar-ml-object-label",
        titleRes = R.string.demo_ar_ml_title,
        subtitleRes = R.string.demo_ar_ml_subtitle,
        category = DemoCategory.AR_UNDERSTANDING,
        icon = Icons.Filled.Label,
        order = 39,
        tags = setOf("ar", "ml", "mlkit", "object-detection", "label", "hit-test"),
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        ARMLObjectLabelDemo(onBack)
    }
}

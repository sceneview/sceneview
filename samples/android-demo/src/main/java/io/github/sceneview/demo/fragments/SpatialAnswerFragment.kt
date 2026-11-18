package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.SpatialAnswerDemo

object SpatialAnswerFragment : DemoFragment {
    override val entry = DemoEntry(
        id = "spatial-answer",
        sourcePath = "src/main/java/io/github/sceneview/demo/demos/SpatialAnswerDemo.kt",
        titleRes = R.string.demo_spatial_answer,
        subtitleRes = R.string.demo_spatial_answer_subtitle,
        category = DemoCategory.PLACE_AR,
        icon = Icons.Filled.ViewInAr,
        order = 23,
        addedIn = "4.70.0",
        tags = setOf("spatial", "dimensions", "assistant", "box", "ar"),
    )

    @Composable
    override fun Screen(onBack: () -> Unit) = SpatialAnswerDemo(onBack, io.github.sceneview.demo.DemoSettings.requestedAnswer)
}

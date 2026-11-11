package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.DemoStatus
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.FeelDemo

object FeelFragment : DemoFragment {
    override val entry: DemoEntry = DemoEntry(
        id = "feel",
        titleRes = R.string.demo_feel_title,
        subtitleRes = R.string.demo_feel_subtitle,
        category = DemoCategory.CREATE,
        icon = Icons.Filled.TouchApp,
        order = 2,
        addedIn = "4.68.0",
        status = DemoStatus.InReview,
        tags = setOf("haptics", "touch", "pressure", "material", "foam", "oak", "steel", "spring", "grid"),
    )

    @Composable
    override fun Screen(onBack: () -> Unit) = FeelDemo(onBack)
}

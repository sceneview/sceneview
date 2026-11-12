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
        id = "matter",
        sourcePath = "src/main/java/io/github/sceneview/demo/demos/FeelDemo.kt",
        titleRes = R.string.demo_feel_title,
        subtitleRes = R.string.demo_feel_subtitle,
        category = DemoCategory.PLAY,
        icon = Icons.Filled.TouchApp,
        order = 15,
        addedIn = "4.70.0",
        status = DemoStatus.InReview,
        tags = setOf("haptics", "touch", "pressure", "material", "foam", "gel", "velvet", "leather", "marble", "steel", "spring", "studio", "firmness", "stiffness", "recovery", "matter"),
    )

    @Composable
    override fun Screen(onBack: () -> Unit) = FeelDemo(onBack)
}

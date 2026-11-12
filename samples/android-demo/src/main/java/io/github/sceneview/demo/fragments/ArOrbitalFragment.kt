package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Public
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.OrbitalARDemo

/** Append-only fragment for the `ar-orbital` demo. See [DemoFragment]. */
object ArOrbitalFragment : DemoFragment {
    override val entry: DemoEntry = DemoEntry(
        id = "ar-orbital",
        sourcePath = "src/main/java/io/github/sceneview/demo/demos/OrbitalARDemo.kt",
        titleRes = R.string.demo_ar_orbital_title,
        subtitleRes = R.string.demo_ar_orbital_subtitle,
        category = DemoCategory.PLACE_AR,
        icon = Icons.Filled.Public,
        order = 20,
        addedIn = "4.1.0",
        tags = setOf("orbital ar", "ar", "orbit", "animation", "model", "anchor"),
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        OrbitalARDemo(onBack)
    }
}

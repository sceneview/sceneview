package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Grain
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.ARSurfacesDemo

/**
 * Append-only fragment for the `ar-surfaces` demo. See [DemoFragment].
 *
 * Was `ar-plane-renderer-v2` until #4307 made that renderer the only one; the retired id
 * still resolves through `DeepLinkRouter.DEMO_ID_ALIASES`.
 */
object ArSurfacesFragment : DemoFragment {
    override val entry: DemoEntry = DemoEntry(
        id = "ar-surfaces",
        titleRes = R.string.demo_ar_surfaces_title,
        subtitleRes = R.string.demo_ar_surfaces_subtitle,
        category = DemoCategory.PLACE_AR,
        icon = Icons.Filled.Grain,
        order = 26,
        addedIn = "4.16.0",
        updatedIn = "4.52.0",
        tags = setOf("ar", "plane", "surface", "wall", "floor", "renderer"),
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        ARSurfacesDemo(onBack)
    }
}

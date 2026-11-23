package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.ARPlacementDemo

/**
 * Append-only fragment for the `ar-placement` demo. See [DemoFragment].
 *
 * One screen, one placement: pick a model, open the camera, and the SDK's `AutoPlacementScene`
 * stands it on the floor (or, chosen on the same screen, a TV on the wall). There is no mode
 * switch. The Free pose and One call modes it used to carry were the same placement written
 * three ways (#5262); their links (`ar-pose`, `placement-scene`, `?tab=free-pose`,
 * `?tab=one-call`) open this screen.
 */
object ArPlacementFragment : DemoFragment {
    override val entry: DemoEntry = DemoEntry(
        id = "ar-placement",
        sourcePath = "src/main/java/io/github/sceneview/demo/demos/ARPlacementDemo.kt",
        titleRes = R.string.demo_ar_placement_title,
        subtitleRes = R.string.demo_ar_placement_subtitle,
        category = DemoCategory.PLACE_AR,
        icon = Icons.Filled.ViewInAr,
        order = 19,
        addedIn = "4.0.0",
        updatedIn = "4.73.0",
        tags = setOf(
            "ar placement",
            "ar", "plane", "auto-place", "anchor", "gltf", "model", "floor", "wall", "tv",
            "pose", "transform", "gesture", "tap-to-place", "sceneform",
        ),
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        ARPlacementDemo(onBack)
    }
}

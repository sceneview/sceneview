package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.DemoMode
import io.github.sceneview.demo.DemoModeHost
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.ARPlacementDemo
import io.github.sceneview.demo.demos.ARPoseDemo
import io.github.sceneview.demo.demos.PlacementSceneDemo

/**
 * Append-only fragment for the `ar-placement` demo. See [DemoFragment].
 *
 * Samples step 0 folded two cards in: `ar-pose` is the Free pose mode (`?tab=free-pose`,
 * launch tab 2), and `placement-scene` is the One call mode (`?tab=one-call`, launch tab 3).
 * Launch tabs 0 and 1 stay with [ARPlacementDemo], which reads 1 as the wall
 * (`wall-placement`, `?tab=wall`).
 *
 * One call is not the Place mode twice: Place hand-writes the flow on `ARSceneView`
 * (`TapToPlaceArSession`), One call is the whole screen as one `AutoPlacementScene` call
 * ([PlacementSceneDemo]), the only floor demo of that API.
 */
object ArPlacementFragment : DemoFragment {
    val modes = listOf(
        DemoMode("place", R.string.demo_mode_place),
        DemoMode("free-pose", R.string.demo_mode_free_pose),
        DemoMode("one-call", R.string.demo_mode_one_call),
    )

    override val entry: DemoEntry = DemoEntry(
        id = "ar-placement",
        titleRes = R.string.demo_ar_placement_title,
        subtitleRes = R.string.demo_ar_placement_subtitle,
        category = DemoCategory.PLACE_AR,
        icon = Icons.Filled.ViewInAr,
        order = 17,
        addedIn = "4.0.0",
        updatedIn = "4.51.0",
        tags = setOf(
            "ar", "plane", "auto-place", "anchor", "gltf", "model", "floor", "wall", "tv",
            "pose", "transform", "gesture", "tap-to-place", "sceneform",
        ),
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        DemoModeHost(
            modes = modes,
            tabToMode = mapOf(FREE_POSE_TAB to 1, ONE_CALL_TAB to 2),
            defaultModeReadsTab = true,
        ) { mode ->
            when (mode) {
                1 -> ARPoseDemo(onBack)
                2 -> PlacementSceneDemo(onBack)
                else -> ARPlacementDemo(onBack)
            }
        }
    }
}

/** Launch tab of the Free pose mode; 0 and 1 are the floor and the wall of Place. */
private const val FREE_POSE_TAB = 2

/** Launch tab of the One call mode (`placement-scene`, `?tab=one-call`). */
private const val ONE_CALL_TAB = 3

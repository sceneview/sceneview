package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.saveable.rememberSaveable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.DemoMode
import io.github.sceneview.demo.DemoModeHost
import io.github.sceneview.demo.R
import io.github.sceneview.demo.common.placement.PlaceModeMemory
import io.github.sceneview.demo.demos.ARPlacementDemo
import io.github.sceneview.demo.demos.ARPoseDemo
import io.github.sceneview.demo.demos.ManualPlacementDemo
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
 * ([PlacementSceneDemo]), the only floor demo of that API. Manual is the tap-to-place
 * `PlacementScene` with its reticle ([ManualPlacementDemo]).
 */
object ArPlacementFragment : DemoFragment {
    val modes = listOf(
        DemoMode("place", R.string.demo_mode_place),
        DemoMode("free-pose", R.string.demo_mode_free_pose),
        DemoMode("one-call", R.string.demo_mode_one_call),
        DemoMode("manual", R.string.demo_mode_manual),
    )

    override val entry: DemoEntry = DemoEntry(
        id = "ar-placement",
        titleRes = R.string.demo_ar_placement_title,
        subtitleRes = R.string.demo_ar_placement_subtitle,
        category = DemoCategory.PLACE_AR,
        icon = Icons.Filled.ViewInAr,
        order = 17,
        addedIn = "4.0.0",
        updatedIn = "4.52.0",
        tags = setOf(
            "ar", "plane", "auto-place", "anchor", "gltf", "model", "floor", "wall", "tv",
            "pose", "transform", "gesture", "tap-to-place", "sceneform",
        ),
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        // Held outside the modes, which the host disposes on every switch: what Place had armed
        // survives a trip to Free pose or One call, and "Place" reopens its camera (#4531).
        val placeMemory = rememberSaveable(saver = PlaceModeMemory.Saver) { PlaceModeMemory() }
        DemoModeHost(
            modes = modes,
            tabToMode = mapOf(FREE_POSE_TAB to 1, ONE_CALL_TAB to 2, MANUAL_TAB to 3),
            defaultModeReadsTab = true,
        ) { mode ->
            SideEffect { if (mode != PLACE_MODE) placeMemory.visitedOtherMode = true }
            when (mode) {
                1 -> ARPoseDemo(onBack)
                2 -> PlacementSceneDemo(onBack)
                3 -> ManualPlacementDemo(onBack)
                else -> ARPlacementDemo(onBack, placeMemory)
            }
        }
    }
}

/** Index of the Place mode in [ArPlacementFragment.modes]. */
private const val PLACE_MODE = 0

/** Launch tab of the Free pose mode; 0 and 1 are the floor and the wall of Place. */
private const val FREE_POSE_TAB = 2

/** Launch tab of the One call mode (`placement-scene`, `?tab=one-call`). */
private const val ONE_CALL_TAB = 3

/** Launch tab of the Manual mode (`?tab=manual`): `PlacementScene` and its reticle. */
private const val MANUAL_TAB = 4

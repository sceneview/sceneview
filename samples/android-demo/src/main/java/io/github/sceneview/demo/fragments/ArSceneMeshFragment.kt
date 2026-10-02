package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.DemoStatus
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.ARSceneMeshDemo

/**
 * Append-only fragment for the `ar-scene-mesh` demo — the "Scene Geometry" card.
 * See [DemoFragment].
 *
 * #3463 folded the retired `ar-streetscape` demo in as this card's second mode; samples
 * step 0 moved that mode to `ar-geospatial-anchors`, where the Geospatial anchors live, so
 * this card is the classified mesh alone. The `ar-streetscape` link follows Streetscape.
 */
object ArSceneMeshFragment : DemoFragment {
    override val entry: DemoEntry = DemoEntry(
        id = "ar-scene-mesh",
        titleRes = R.string.demo_ar_scene_mesh_title,
        subtitleRes = R.string.demo_ar_scene_mesh_subtitle,
        category = DemoCategory.UNDERSTAND,
        icon = Icons.Filled.GridOn,
        order = 49,
        addedIn = "4.15.2",
        tags = setOf("ar", "geospatial", "mesh", "building", "classification"),
        // Requires an outdoor location with Street View coverage + a Cloud API key, which
        // no CI device and no default build has, so nobody has verified it outdoors.
        status = DemoStatus.KnownIssue,
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        ARSceneMeshDemo(onBack)
    }
}

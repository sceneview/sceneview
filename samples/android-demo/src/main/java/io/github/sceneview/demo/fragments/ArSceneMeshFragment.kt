package io.github.sceneview.demo.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.DemoStatus
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.ARSceneGeometryDemo

/**
 * Append-only fragment for the `ar-scene-mesh` demo — the "Scene Geometry" card.
 * See [DemoFragment].
 *
 * #3463 folded the retired `ar-streetscape` demo in as this card's second mode. The id is
 * unchanged on purpose: it is a public deep-link surface and iOS ships a screen under the
 * same one. `ar-streetscape` resolves here through
 * [io.github.sceneview.demo.DeepLinkRouter.DEMO_ID_ALIASES] and lands on mode 1.
 */
object ArSceneMeshFragment : DemoFragment {
    override val entry: DemoEntry = DemoEntry(
        id = "ar-scene-mesh",
        titleRes = R.string.demo_ar_scene_mesh_title,
        subtitleRes = R.string.demo_ar_scene_mesh_subtitle,
        category = DemoCategory.AR_UNDERSTANDING,
        icon = Icons.Filled.GridOn,
        order = 36,
        tags = setOf(
            "ar", "geospatial", "streetscape", "mesh", "terrain", "building", "classification",
        ),
        // Requires an outdoor location with Street View coverage + a Cloud API key, which
        // no CI device and no default build has. The `ar-streetscape` half carried the
        // KnownIssue badge before the merge; the merged card keeps it, because the
        // capability that could not be verified is still here.
        status = DemoStatus.KnownIssue,
    )

    @Composable
    override fun Screen(onBack: () -> Unit) {
        ARSceneGeometryDemo(onBack)
    }
}

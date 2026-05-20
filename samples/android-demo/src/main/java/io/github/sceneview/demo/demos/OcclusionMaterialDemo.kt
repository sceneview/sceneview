package io.github.sceneview.demo.demos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.sceneview.SceneView
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.LoadingScrim
import io.github.sceneview.demo.R
import io.github.sceneview.demo.rememberFirstFrameState
import io.github.sceneview.math.Position
import io.github.sceneview.math.Size
import io.github.sceneview.rememberCameraManipulator
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.sample.rememberOcclusionMaterialInstance
import io.github.sceneview.sample.rememberUnlitMaterialInstance

/**
 * Showcase for [`MaterialLoader.createOcclusionInstance()`][io.github.sceneview.loaders.MaterialLoader.createOcclusionInstance]
 * — the SceneView equivalent of RealityKit's `OcclusionMaterial` and Sceneform legacy's
 * `MaterialFactory.makeOcclusionMaterial(...)` (#1776, parity child of #1754).
 *
 * Stage:
 *  - A virtual helmet sits at `z = -2 m`.
 *  - A flat 1 × 1 m plane sits between the camera and the helmet at `z = -1.2 m`.
 *
 * Toggle:
 *  - **Occluder visible ON** — the plane wears a tinted unlit material so the user can SEE
 *    where it is. The helmet behind it draws normally because the plane is opaque-painted
 *    (so it should also occlude — proving the toggle ground truth).
 *  - **Occluder visible OFF** — the plane wears the new occlusion material. The plane
 *    itself is now invisible (zero pixels painted), but its depth value still goes into the
 *    depth buffer, so any helmet fragment behind it fails the depth test and is hidden.
 *
 * Effect: the helmet visibly disappears WHERE the plane is, with no plane painted on top.
 * That's the entire contract — a "ghost wall" that blocks virtual content without ever
 * rendering itself.
 *
 * The whole demo is non-AR (3D `SceneView { }`), so the comparison is reproducible on every
 * device — no ARCore required. For AR scenes that want the same effect against the **live
 * camera depth image**, use
 * [`ARCameraStream.isDepthOcclusionEnabled`][io.github.sceneview.ar.camera.ARCameraStream]
 * instead — that path samples ARCore's per-pixel depth, not a static occluder mesh, and is
 * the right tool when the "occluder" is the user's real-world environment.
 */
@Composable
fun OcclusionMaterialDemo(onBack: () -> Unit) {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)

    // Hoisted so the helmet loads once for the whole demo — re-toggling the occluder
    // never re-parses the GLB.
    val helmetInstance = rememberModelInstance(
        modelLoader,
        "models/khronos_damaged_helmet.glb"
    )

    // Two materials for the in-front plane.
    //
    // 1. `occlusionMaterial` — invisible, depth-writing. The thing this demo exists to
    //    demonstrate. Allocate once and reuse — no parameters to tweak.
    val occlusionMaterial = rememberOcclusionMaterialInstance(materialLoader)
    // 2. `debugVisibleMaterial` — a translucent slate plate that lets the user see WHERE
    //    the plane is when the toggle is ON. Used as a ground-truth visual; not the
    //    feature being demonstrated.
    val debugVisibleMaterial = rememberUnlitMaterialInstance(
        materialLoader,
        Color(0.4f, 0.4f, 0.45f, 1f),
    )

    // UI state — true means "show the debug-visible plate", false means "use the occlusion
    // material". Default `false` so the user opens the demo on the actual feature — see
    // the helmet visibly cut by an invisible plane — before being shown the ground truth.
    var occluderVisible by remember { mutableStateOf(false) }

    val firstFrame = rememberFirstFrameState()

    DemoScaffold(
        title = stringResource(R.string.demo_occlusion_material_title),
        onBack = onBack,
        firstFrameRendered = firstFrame.rendered,
        controls = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.demo_occlusion_material_toggle),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = occluderVisible,
                    onCheckedChange = { occluderVisible = it },
                )
            }
            Text(
                text = stringResource(R.string.demo_occlusion_material_explainer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            SceneView(
                modifier = Modifier.fillMaxSize(),
                onFrame = firstFrame.onFrame,
                engine = engine,
                modelLoader = modelLoader,
                materialLoader = materialLoader,
                environmentLoader = environmentLoader,
                // Static camera — the whole demo is about depth ordering at a fixed
                // viewpoint. No orbit so the user sees the occlusion effect from a
                // single, reproducible angle.
                cameraManipulator = rememberCameraManipulator(
                    orbitHomePosition = Position(0f, 0.1f, 0.5f),
                    targetPosition = Position(0f, 0f, -2f),
                ),
                // The hand-authored helmet + plane positions are meaningful — keep them
                // in world space instead of letting the union bbox auto-centre move
                // them (same reason as CollisionDemo / #1430).
                autoCenterContent = false,
            ) {
                val instance = helmetInstance
                if (instance != null) {
                    // Helmet at z = -2 m — the target whose visibility the occluder
                    // controls.
                    ModelNode(
                        modelInstance = instance,
                        scaleToUnits = 0.4f,
                        centerOrigin = Position(0f, 0f, -2f),
                    )
                }
                // Occluder plane at z = -1.2 m — between the camera and the helmet.
                // Slightly smaller than the helmet's bbox so the user can clearly see
                // the occluded vs. unoccluded silhouette difference.
                PlaneNode(
                    size = Size(x = 0.5f, y = 0.5f, z = 0f),
                    materialInstance =
                        if (occluderVisible) debugVisibleMaterial else occlusionMaterial,
                    position = Position(0f, 0f, -1.2f),
                )
            }
            LoadingScrim(
                loading = helmetInstance == null,
                label = stringResource(R.string.demo_materials_loading),
            )
        }
    }
}

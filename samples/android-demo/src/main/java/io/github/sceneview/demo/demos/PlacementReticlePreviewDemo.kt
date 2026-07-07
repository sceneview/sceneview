package io.github.sceneview.demo.demos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import com.google.android.filament.Skybox
import io.github.sceneview.RenderQuality
import io.github.sceneview.SceneView
import io.github.sceneview.ar.PlacementReticleStyle
import io.github.sceneview.ar.PlacementReticleVisual
import io.github.sceneview.ar.ReticlePhase
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.R
import io.github.sceneview.demo.rememberFirstFrameState
import io.github.sceneview.environment.Environment
import io.github.sceneview.math.Position
import io.github.sceneview.rememberCameraManipulator
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader

/**
 * **AR Placement Reticle Preview** — a *non-AR* [SceneView] that renders the production
 * placement reticle ([PlacementReticleVisual], the one [io.github.sceneview.ar.PlacementScene]
 * shows the user) on a static synthetic floor, so its visual design can be judged and
 * regression-tested on any 3D emulator with **no ARCore session and no physical device**.
 *
 * ### Why this exists
 *
 * The reticle's appearance — ring vs disc, the searching→ready phase change (dim/hollow →
 * bright + centre dot), its size and tint on a surface — is pure Filament geometry + material.
 * It does not depend on ARCore at all; only the *pose* comes from the AR hit-test at runtime.
 * So the exact visual the user sees can be reproduced by rendering the same composable on a
 * hand-placed ground node, and screenshotted on a plain Mac/CI emulator (the emulator's
 * virtualscene camera cannot converge ARCore planes — see the AR-replay honesty gate #1645).
 * This makes reticle regressions catchable off-device.
 *
 * ### Controls
 *
 * - **Ready phase** — flips [ReticlePhase] between SEARCHING (no surface under the reticle →
 *   dim, hollow ring) and READY (surface acquired → bright ring + centre dot). This is the
 *   single most important placement signal and the thing to eyeball here.
 * - **Ring style** — switches [PlacementReticleStyle] between RING (modern default) and DISC
 *   (the legacy filled puck), so the upgrade is directly comparable.
 */
@Composable
fun PlacementReticlePreviewDemo(onBack: () -> Unit) {
    var ready by remember { mutableStateOf(true) }
    var ring by remember { mutableStateOf(true) }

    val engine = rememberEngine()
    val materialLoader = rememberMaterialLoader(engine)

    // Matte dark "floor" the reticle sits on — an unlit disc so no lighting rig is needed and
    // the ring's own tint reads truthfully (same material family as the reticle itself).
    val groundMaterial = remember(materialLoader) {
        materialLoader.createUnlitColorInstance(Color(0xFF_2A_2E_35))
    }

    // A calm neutral backdrop; a mid grey stands in for the "camera feed" behind the reticle
    // without biasing the tint judgement the way a coloured skybox would.
    val skybox = remember(engine) { Skybox.Builder().color(0.10f, 0.11f, 0.13f, 1.0f).build(engine) }
    val environment = remember(skybox) { Environment(skybox = skybox) }

    val firstFrame = rememberFirstFrameState()

    DemoScaffold(
        title = stringResource(R.string.demo_placement_reticle_preview_title),
        onBack = onBack,
        firstFrameRendered = firstFrame.rendered,
        controls = {
            LabeledSwitch("Ready phase (surface acquired)", ready) { ready = it }
            Spacer(Modifier.height(8.dp))
            LabeledSwitch("Ring style (off = legacy disc)", ring) { ring = it }
        }
    ) {
        Box(Modifier.fillMaxSize()) {
            SceneView(
                modifier = Modifier.fillMaxSize(),
                onFrame = firstFrame.onFrame,
                engine = engine,
                materialLoader = materialLoader,
                environment = environment,
                // Mirror ARSceneView's pipeline (bloom/SSAO off) so the unlit reticle alpha
                // reads exactly as it does over the real AR camera feed.
                renderQuality = RenderQuality.Performance,
                autoCenterContent = false,
                cameraManipulator = rememberCameraManipulator(
                    // A natural "standing, phone tilted at the floor" angle — the reticle is
                    // seen in perspective, lying flush on the ground, as in real placement.
                    orbitHomePosition = Position(0f, 0.42f, 0.42f),
                    targetPosition = Position(0f, 0f, 0f),
                ),
            ) {
                // The synthetic floor.
                CylinderNode(
                    radius = 0.6f,
                    height = 0.002f,
                    materialInstance = groundMaterial,
                )
                // The production reticle, flat on the floor at the origin.
                Node(position = Position(0f, 0f, 0f)) {
                    PlacementReticleVisual(
                        materialLoader = materialLoader,
                        phase = if (ready) ReticlePhase.READY else ReticlePhase.SEARCHING,
                        style = if (ring) PlacementReticleStyle.RING else PlacementReticleStyle.DISC,
                    )
                }
            }

            // The coaching line the user reads in the real flow, mirrored here so the whole
            // "point → ready → tap" story is visible in one preview.
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp),
                shape = RoundedCornerShape(24.dp),
                color = Color.Black.copy(alpha = 0.55f)
            ) {
                Text(
                    text = if (ready) "Surface found — tap to place" else "Move your phone to find a surface",
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White
                )
            }
        }
    }
}

@Composable
private fun LabeledSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

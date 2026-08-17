package io.github.sceneview.demo.demos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import com.google.android.filament.LightManager
import com.google.android.filament.Skybox
import io.github.sceneview.RenderQuality
import io.github.sceneview.SceneView
import io.github.sceneview.ar.PlacementReticleStyle
import io.github.sceneview.ar.PlacementReticleVisual
import io.github.sceneview.ar.ReticlePhase
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.R
import io.github.sceneview.demo.SceneViewColors
import io.github.sceneview.demo.common.DemoStatusBanner
import io.github.sceneview.demo.common.DemoStatusTone
import io.github.sceneview.demo.rememberFirstFrameState
import io.github.sceneview.environment.Environment
import io.github.sceneview.environment.rememberHDREnvironment
import io.github.sceneview.math.Direction
import io.github.sceneview.math.Position
import io.github.sceneview.math.Size
import io.github.sceneview.rememberCameraManipulator
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.sample.rememberMaterialInstance

/**
 * **AR Placement Preview** — a *non-AR* [SceneView] that renders the production placement visuals
 * ([PlacementReticleVisual] and the placed-model **contact shadow**) on a static synthetic floor,
 * so the whole "aim → ready → placed" look can be judged and regression-tested on any 3D emulator
 * with **no ARCore session and no physical device**.
 *
 * ### Why this exists
 *
 * The reticle geometry, its searching→ready phase change, and the contact shadow a placed model
 * casts on the floor are pure Filament rendering — only the *pose* comes from ARCore at runtime.
 * So the exact thing the user sees can be reproduced on a hand-placed ground node and screenshotted
 * on a plain Mac/CI emulator (the emulator's virtualscene camera cannot converge ARCore planes —
 * see the AR-replay honesty gate #1645). This makes reticle / grounding regressions catchable
 * off-device — the same technique as [PlaneGridPreviewDemo] (#2224).
 *
 * ### Controls
 *
 * - **Show placed model** — swaps the aiming reticle for a model dropped on the floor with a
 *   directional-light **contact shadow** (what [io.github.sceneview.ar.PlacementScene]'s
 *   `groundShadows = true` renders). This is the "grounded" look to eyeball.
 * - **Ready phase** — (reticle mode) flips [ReticlePhase] between SEARCHING (no surface → dim,
 *   hollow ring) and READY (surface acquired → bright ring + centre dot).
 * - **Ring style** — (reticle mode) switches [PlacementReticleStyle] between RING (modern default)
 *   and DISC (the legacy filled puck).
 */
@Composable
fun PlacementReticlePreviewDemo(onBack: () -> Unit) {
    var placed by remember { mutableStateOf(false) }
    var ready by remember { mutableStateOf(true) }
    var ring by remember { mutableStateOf(true) }

    val engine = rememberEngine()
    val materialLoader = rememberMaterialLoader(engine)
    val modelLoader = rememberModelLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)

    // Matte dark floor for reticle mode — unlit so no lighting rig is needed and the ring's own
    // tint reads truthfully.
    val groundDisc = remember(materialLoader) {
        materialLoader.createUnlitColorInstance(Color(0xFF_2A_2E_35))
    }
    // Lit floor for placed mode — a light neutral grey so the (dark) contact shadow actually
    // reads; a dark floor would swallow the very shadow this preview exists to show.
    val groundPlane = rememberMaterialInstance(materialLoader, Color(0xFF_C4_C8_CE))

    // Reticle mode: a neutral mid-grey backdrop stands in for the camera feed. Placed mode: a
    // studio HDR lights the PBR model (its own IBL); the directional light below casts the shadow.
    val darkSkybox = remember(engine) { Skybox.Builder().color(0.10f, 0.11f, 0.13f, 1.0f).build(engine) }
    val darkEnvironment = remember(darkSkybox) { Environment(skybox = darkSkybox) }
    val litEnvironment = rememberHDREnvironment(
        environmentLoader,
        "environments/studio_2k.hdr",
        createSkybox = true,
    )
    val environment = if (placed) (litEnvironment ?: darkEnvironment) else darkEnvironment

    // The same compact model PlacementSceneDemo drops on a real surface, so this preview mirrors
    // the shipped AR placement one-to-one.
    val modelInstance = rememberModelInstance(modelLoader, "models/khronos_damaged_helmet.glb")

    val firstFrame = rememberFirstFrameState()

    DemoScaffold(
        title = stringResource(R.string.demo_placement_reticle_preview_title),
        onBack = onBack,
        firstFrameRendered = firstFrame.rendered,
        controls = {
            LabeledSwitch("Show placed model (contact shadow)", placed) { placed = it }
            if (!placed) {
                Spacer(Modifier.height(8.dp))
                LabeledSwitch("Ready phase (surface acquired)", ready) { ready = it }
                Spacer(Modifier.height(8.dp))
                LabeledSwitch("Ring style (off = legacy disc)", ring) { ring = it }
            }
        },
        bottomOverlay = {
            // The coaching line the user reads in the real flow, mirrored here so the whole
            // "point → ready → placed" story is visible in one preview.
            //
            // Guidance in every branch: none of the three sentences reports a transient
            // machine state, they all tell the user what to do next with the device or
            // their fingers — move the phone, tap, drag/pinch.
            DemoStatusBanner(
                text = when {
                    placed -> "Placed — drag to move, pinch to rotate & scale"
                    ready -> "Surface found — tap to place"
                    else -> "Move your phone to find a surface"
                },
                tone = DemoStatusTone.Guidance,
            )
        }
    ) {
        Box(Modifier.fillMaxSize()) {
            SceneView(
                modifier = Modifier.fillMaxSize(),
                onFrame = firstFrame.onFrame,
                engine = engine,
                materialLoader = materialLoader,
                modelLoader = modelLoader,
                environment = environment,
                // Reticle mode mirrors ARSceneView's pipeline (bloom/SSAO off) so the unlit reticle
                // alpha reads as over the camera feed; placed mode keeps the default quality so the
                // directional-light contact shadow is actually rendered (Performance drops shadows).
                renderQuality = if (placed) RenderQuality.Default else RenderQuality.Performance,
                autoCenterContent = false,
                cameraManipulator = rememberCameraManipulator(
                    // A natural "standing, phone tilted at the floor" angle — the reticle / model
                    // is seen in perspective, sitting on the ground, as in real placement. Lower &
                    // closer in placed mode so the floor (and the contact shadow on it) is in view.
                    orbitHomePosition = Position(0f, 0.45f, 0.55f),
                    targetPosition = Position(0f, 0.08f, 0f),
                ),
            ) {
                if (placed) {
                    // Sun that casts the contact shadow (ARSceneView's default HDR light is a
                    // shadow-caster; mirror that here). The studio HDR supplies ambient/IBL.
                    LightNode(
                        type = LightManager.Type.DIRECTIONAL,
                        direction = Direction(-0.35f, -1f, -0.4f),
                        apply = {
                            intensity(80_000f)
                            castShadows(true)
                        },
                    )
                    // Floor that receives the shadow.
                    PlaneNode(
                        size = Size(x = 1.4f, y = 0f, z = 1.4f),
                        materialInstance = groundPlane,
                    )
                    // The placed model, lifted by its half-height so it rests ON the floor at y=0
                    // (scaleToUnits 0.3 → ~0.3 m tall → centre at y=0.15), casting a contact shadow.
                    modelInstance?.let { instance ->
                        ModelNode(
                            modelInstance = instance,
                            scaleToUnits = 0.3f,
                            position = Position(y = 0.15f),
                        )
                    }
                } else {
                    // The synthetic floor + the production reticle, flat on it at the origin.
                    CylinderNode(
                        radius = 0.6f,
                        height = 0.002f,
                        materialInstance = groundDisc,
                    )
                    Node(position = Position(0f, 0f, 0f)) {
                        PlacementReticleVisual(
                            materialLoader = materialLoader,
                            phase = if (ready) ReticlePhase.READY else ReticlePhase.SEARCHING,
                            style = if (ring) PlacementReticleStyle.RING else PlacementReticleStyle.DISC,
                        )
                    }
                }
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

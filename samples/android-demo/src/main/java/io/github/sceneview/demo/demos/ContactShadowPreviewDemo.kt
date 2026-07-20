package io.github.sceneview.demo.demos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import io.github.sceneview.SceneView
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.R
import io.github.sceneview.demo.rememberFirstFrameState
import io.github.sceneview.environment.Environment
import io.github.sceneview.environment.rememberHDREnvironment
import io.github.sceneview.math.Direction
import io.github.sceneview.math.Position
import io.github.sceneview.math.Size
import io.github.sceneview.node.ContactShadowContext
import io.github.sceneview.node.CubeNode
import io.github.sceneview.node.Node
import io.github.sceneview.rememberCameraManipulator
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberMaterialLoader

/**
 * **Contact Shadow Preview** — a *non-AR* SceneView showing the procedural contact shadow
 * (#2740 sub-task C) grounding two objects at once: a TV mounted on a wall and a box resting
 * on the floor.
 *
 * ### Why this exists
 *
 * The contact shadow is a pure shader effect — an elliptical gradient drawn from the quad's
 * UVs — so nothing about it depends on ARCore. Like [PlaneGridPreviewDemo] (#2224) and
 * [PlacementReticlePreviewDemo], reproducing the exact geometry + material in a plain
 * `SceneView` makes it visually reviewable on any emulator, with **no ARCore session and no
 * physical AR device**. That matters here: the emulator cannot run ARCore at all on this
 * host (#2754), so without this preview the effect would ship unseen.
 *
 * ### What to look for
 *
 * - **Contact shadows off** — both objects read as *floating*: the TV could be a sticker
 *   hovering in front of the wall, the box could be levitating. This is the A/B that shows
 *   what the effect buys.
 * - **The wall preset vs the floor preset** — a wall shadow is fainter, wider than it is
 *   tall, and pushed *below* the panel, because indoor light comes from the ceiling and
 *   merely grazes a wall. Switching the wall shadow to the `Floor` preset makes it too dark
 *   and too round — it reads as a sticker, which is exactly the failure the contexts exist
 *   to avoid.
 *
 * ### Why not a real shadow map
 *
 * Filament can cast a genuine shadow onto a floor, and does. It cannot usefully do so onto a
 * **wall**: the estimated indoor light points down from the ceiling, nearly parallel to the
 * wall, so a flat-mounted TV casts essentially nothing onto it. The procedural pool is
 * deterministic at any light angle — see `contact_shadow.mat`.
 */
@Composable
fun ContactShadowPreviewDemo(onBack: () -> Unit) {
    var shadowsEnabled by remember { mutableStateOf(true) }
    var intensity by remember { mutableFloatStateOf(ContactShadowContext.Wall.intensity) }
    var wallContext by remember { mutableStateOf(ContactShadowContext.Wall) }

    val engine = rememberEngine()
    val materialLoader = rememberMaterialLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)

    // A neutral room: matte off-white wall, slightly darker floor, so the shadow gradient is
    // the only thing carrying the grounding cue.
    val wallMaterial = remember(materialLoader) {
        materialLoader.createColorInstance(Color(0xFFE8E6E1), metallic = 0f, roughness = 0.9f)
    }
    val floorMaterial = remember(materialLoader) {
        materialLoader.createColorInstance(Color(0xFFCFCBC4), metallic = 0f, roughness = 0.85f)
    }
    val tvBody = remember(materialLoader) {
        materialLoader.createColorInstance(Color(0xFF20242A), metallic = 0f, roughness = 0.8f)
    }
    val tvScreen = remember(materialLoader) {
        materialLoader.createColorInstance(Color(0xFF06080C), metallic = 0f, roughness = 0.15f)
    }
    val boxMaterial = remember(materialLoader) {
        materialLoader.createColorInstance(Color(0xFFB4693C), metallic = 0f, roughness = 0.7f)
    }

    // The room's materials are LIT (PBR), so they need an IBL or they render flat and dark — a
    // coloured skybox alone supplies no irradiance. Studio HDR does the ambient lighting; the
    // light-grey skybox below is the fallback while the HDR is still decoding.
    val litEnvironment = rememberHDREnvironment(
        environmentLoader,
        "environments/studio_2k.hdr",
        createSkybox = true,
    )
    val fallbackSkybox = remember(engine) {
        Skybox.Builder().color(0.72f, 0.73f, 0.75f, 1.0f).build(engine)
    }
    val fallbackEnvironment = remember(fallbackSkybox) { Environment(skybox = fallbackSkybox) }
    val environment = litEnvironment ?: fallbackEnvironment

    val firstFrame = rememberFirstFrameState()

    DemoScaffold(
        title = stringResource(R.string.demo_contact_shadow_preview_title),
        onBack = onBack,
        firstFrameRendered = firstFrame.rendered,
        controls = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = shadowsEnabled,
                        onValueChange = { shadowsEnabled = it },
                    ),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.contact_shadow_toggle),
                    style = MaterialTheme.typography.bodyMedium
                )
                Switch(checked = shadowsEnabled, onCheckedChange = null)
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                stringResource(R.string.contact_shadow_intensity, (intensity * 100).toInt()),
                style = MaterialTheme.typography.labelLarge
            )
            Slider(
                value = intensity,
                onValueChange = { intensity = it },
                valueRange = 0f..1f
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                stringResource(R.string.contact_shadow_wall_preset),
                style = MaterialTheme.typography.labelLarge
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ContactShadowContext.values().forEach { context ->
                    if (context == wallContext) {
                        Button(onClick = { wallContext = context }) { Text(context.name) }
                    } else {
                        OutlinedButton(onClick = {
                            wallContext = context
                            intensity = context.intensity
                        }) { Text(context.name) }
                    }
                }
            }
        }
    ) {
        SceneView(
            modifier = Modifier.fillMaxSize(),
            onFrame = firstFrame.onFrame,
            engine = engine,
            materialLoader = materialLoader,
            environment = environment,
            // Keep the hand-built room where it was authored — auto-centring would reframe the
            // scene and break the deterministic camera below.
            autoCenterContent = false,
            cameraManipulator = rememberCameraManipulator(
                orbitHomePosition = Position(0.6f, 1.5f, 3.2f),
                targetPosition = Position(0f, 1.0f, -1f),
            ),
        ) {
            // Directional key light for shape and specular — deliberately NOT a shadow caster.
            // A real cast shadow on the floor would sit alongside the procedural pool and muddy
            // the A/B: the point of this preview is that the ONLY grounding cue on screen is the
            // contact shadow, so toggling it off must leave the objects visibly floating.
            LightNode(
                type = LightManager.Type.DIRECTIONAL,
                direction = Direction(-0.35f, -1f, -0.4f),
                apply = {
                    intensity(60_000f)
                    castShadows(false)
                },
            )

            // ── The room ──────────────────────────────────────────────────────────────────
            // Floor: an XZ quad (normal +Y).
            PlaneNode(
                size = Size(x = 6f, y = 0f, z = 6f),
                normal = Direction(y = 1f),
                materialInstance = floorMaterial,
            )
            // Back wall: an XY quad (normal +Z) — note the DIFFERENT size shape. `Plane` does
            // not rotate its geometry to match `normal`, so a vertical quad is built in XY.
            PlaneNode(
                size = Size(x = 6f, y = 3f, z = 0f),
                normal = Direction(z = 1f),
                position = Position(x = 0f, y = 1.5f, z = -2f),
                materialInstance = wallMaterial,
            )

            // ── Wall-mounted TV, grounded by a wall-preset contact shadow ─────────────────
            if (shadowsEnabled) {
                ContactShadow(
                    size = Size(x = 2.4f, y = 1.6f, z = 0f),
                    context = wallContext,
                    normal = Direction(z = 1f),
                    intensity = intensity,
                    position = Position(x = 0f, y = 1.3f, z = -1.99f),
                )
            }
            Node(position = Position(x = 0f, y = 1.3f, z = -1.98f)) {
                CubeNode(
                    size = Size(1.26f, 0.74f, 0.04f),
                    position = Position(z = 0.02f),
                    materialInstance = tvBody,
                )
                CubeNode(
                    size = Size(1.20f, 0.68f, 0.01f),
                    position = Position(z = 0.045f),
                    materialInstance = tvScreen,
                )
            }

            // ── Box on the floor, grounded by a floor-preset contact shadow ───────────────
            if (shadowsEnabled) {
                ContactShadow(
                    size = Size(x = 1.4f, y = 0f, z = 1.4f),
                    context = ContactShadowContext.Floor,
                    normal = Direction(y = 1f),
                    intensity = intensity,
                    position = Position(x = 1.5f, y = 0f, z = 0.4f),
                )
            }
            CubeNode(
                size = Size(0.5f, 0.5f, 0.5f),
                position = Position(x = 1.5f, y = 0.25f, z = 0.4f),
                materialInstance = boxMaterial,
            )
        }
    }
}

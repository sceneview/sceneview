package io.github.sceneview.demo.demos

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.google.android.filament.LightManager
import dev.romainguy.kotlin.math.Float3
import io.github.sceneview.SceneView
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.R
import io.github.sceneview.demo.LoadingScrim
import io.github.sceneview.demo.initialDemoMode
import io.github.sceneview.demo.rememberFirstFrameState
import io.github.sceneview.demo.rememberHeroOrbitCameraManipulator
import io.github.sceneview.math.Direction
import io.github.sceneview.math.Position
import io.github.sceneview.math.colorOf
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.sample.rememberMaterialInstance
import io.github.sceneview.sample.rememberUnlitMaterialInstance
import io.github.sceneview.sample.ui.LabeledSlider
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Consolidated **Lighting** demo — issue #1444.
 *
 * Two formerly-separate demos (`lighting` "Light Types" and `movable-light`
 * "Movable Light") are merged here behind a single in-demo mode switch so the
 * Samples tab carries one lighting entry instead of two near-identical cards.
 * No feature is lost — both modes are still fully interactive:
 *
 * - **[LightingMode.Types]** — pick directional / point / spot, scrub the
 *   intensity, and choose a colour preset; a backdrop wall makes the light's
 *   *shape in space* visible.
 * - **[LightingMode.Movable]** — drag anywhere on the scene to orbit a single
 *   point light around the helmet and watch the specular highlights track it.
 *
 * The old `movable-light` deep link still resolves: `DeepLinkRouter` aliases
 * it to `lighting`, so `sceneview://demo/movable-light` keeps working.
 */
private enum class LightingMode { Types, Movable }

@Composable
fun LightingDemo(onBack: () -> Unit) {
    // rememberSaveable so the chosen mode survives configuration changes /
    // process death — consistent with the rest of the demo catalogue.
    var mode by rememberSaveable {
        mutableStateOf(initialDemoMode(LightingMode.entries, LightingMode.Types))
    }

    when (mode) {
        LightingMode.Types -> LightTypesScene(
            onBack = onBack,
            mode = mode,
            onModeChange = { mode = it },
        )
        LightingMode.Movable -> MovableLightScene(
            onBack = onBack,
            mode = mode,
            onModeChange = { mode = it },
        )
    }
}

/** Shared mode switcher rendered at the top of each mode's controls panel. */
@Composable
private fun LightingModeSelector(
    mode: LightingMode,
    onModeChange: (LightingMode) -> Unit,
) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        val labels = listOf(
            LightingMode.Types to stringResource(R.string.demo_lighting_mode_types),
            LightingMode.Movable to stringResource(R.string.demo_lighting_mode_movable),
        )
        labels.forEachIndexed { index, (value, label) ->
            SegmentedButton(
                selected = mode == value,
                onClick = { onModeChange(value) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = labels.size),
            ) {
                Text(label)
            }
        }
    }
}

/**
 * "Light Types" mode — directional, point, and spot lights with interactive
 * controls. Formerly the standalone `LightingDemo`.
 */
@Composable
private fun LightTypesScene(
    onBack: () -> Unit,
    mode: LightingMode,
    onModeChange: (LightingMode) -> Unit,
) {
    data class LightTypeOption(val label: String, val type: LightManager.Type)

    val lightTypes = remember {
        listOf(
            LightTypeOption("Directional", LightManager.Type.DIRECTIONAL),
            LightTypeOption("Point", LightManager.Type.POINT),
            LightTypeOption("Spot", LightManager.Type.FOCUSED_SPOT)
        )
    }

    data class ColorPreset(val label: String, val color: Color, val r: Float, val g: Float, val b: Float)

    val colorPresets = remember {
        listOf(
            ColorPreset("White", Color.White, 1f, 1f, 1f),
            ColorPreset("Warm", Color(0xFFFFCC66), 1f, 0.8f, 0.4f),
            ColorPreset("Blue", Color(0xFF6699FF), 0.4f, 0.6f, 1f),
            ColorPreset("Red", Color(0xFFFF6666), 1f, 0.4f, 0.4f)
        )
    }

    var selectedType by remember { mutableStateOf(lightTypes[0]) }
    // 200 klx default: high enough that the helmet is visibly lit on first-open even
    // before the user touches the slider (110 klx with default IBL still rendered the
    // helmet near-black on Pixel 7a Metal — the PBR helmet materials need ~150 klx of
    // direct light to read clearly against the dark Surface background).
    var intensity by remember { mutableFloatStateOf(200_000f) }
    var selectedColor by remember { mutableStateOf(colorPresets[0]) }

    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)
    val modelInstance = rememberModelInstance(modelLoader, "models/khronos_damaged_helmet.glb")

    // Light position is fixed so swapping types reveals the *type's* signature, not a
    // moving source. Y=1.4, Z=1.0 puts it up-and-forward of the helmet (origin) — close
    // enough that the point falloff visibly fades on the backdrop, far enough that the
    // spot cone projects a clean disc rather than a blown-out wash.
    val lightPosition = remember { Position(0f, 1.4f, 1.0f) }
    // Backdrop wall behind helmet — without this, directional / point / spot all read
    // the same way because the helmet alone gets fully lit by any of them. The wall
    // makes the light's *shape in space* visible: directional = uniform wash, point
    // = radial gradient (1/r²), spot = sharp circular disc with cone edge.
    val backdropMaterial = rememberMaterialInstance(
        materialLoader,
        color = Color(0xFF424448),
        metallic = 0.0f,
        roughness = 0.85f,
    )
    // Bright unlit-looking ball to mark the light position. PBR can't be true
    // self-emissive without an emissive material, but a low-roughness saturated
    // colour with the IBL fallback reads convincingly as a glowing source.
    val sourceMaterial = rememberMaterialInstance(
        materialLoader,
        color = selectedColor.color,
        metallic = 0.0f,
        roughness = 0.0f,
    )

    // Camera orbits the helmet; the model itself stays fixed so the directional /
    // point / spot light hits the exact same surface every frame — otherwise the
    // user can't tell the three light types apart because a spinning helmet sweeps
    // its own surface through the light cone each rotation.
    val cameraManipulator = rememberHeroOrbitCameraManipulator(
        trigger = modelInstance != null,
        radius = 2.0f,
        yHeight = 0.3f,
        durationMillis = 22_000,
    )

    val firstFrame = rememberFirstFrameState()

    DemoScaffold(
        title = stringResource(R.string.demo_lighting_title),
        onBack = onBack,
        firstFrameRendered = firstFrame.rendered,
        controls = {
            LightingModeSelector(mode = mode, onModeChange = onModeChange)
            Spacer(modifier = Modifier.height(12.dp))

            // Light type selector
            Text("Light Type", style = MaterialTheme.typography.labelLarge)
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                lightTypes.forEach { lt ->
                    FilterChip(
                        selected = selectedType == lt,
                        onClick = { selectedType = lt },
                        label = { Text(lt.label) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Intensity slider
            LabeledSlider(
                label = "Intensity",
                value = intensity,
                onValueChange = { intensity = it },
                valueRange = 10_000f..500_000f,
                decimals = 0,
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Color presets
            Text("Color", style = MaterialTheme.typography.labelLarge)
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                colorPresets.forEach { preset ->
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(preset.color, CircleShape)
                            .then(
                                if (selectedColor == preset) {
                                    Modifier.border(3.dp, MaterialTheme.colorScheme.primary, CircleShape)
                                } else Modifier
                            )
                            .clickable { selectedColor = preset }
                            .semantics { contentDescription = "${preset.label} light color" }
                    )
                }
            }
        }
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            SceneView(
                modifier = Modifier.fillMaxSize(),
                onFrame = firstFrame.onFrame,
                engine = engine,
                modelLoader = modelLoader,
                environmentLoader = environmentLoader,
                // Disable the default 110 klx directional main light — otherwise it adds a
                // constant bright directional on top of the user's LightNode and the chip
                // changes (directional vs point vs spot) produce indistinguishable visuals
                // because the helmet is already fully lit by the constant default. The IBL
                // from the default environmentLoader is kept for ambient fill so the helmet
                // is always somewhat visible (neutral_ibl.ktx), and the user's LightNode
                // adds its directional / point / spot contribution on top.
                mainLightNode = null,
                // This demo intentionally composes three nodes off-centre: the helmet at
                // origin, a backdrop wall behind it, and a light-source marker
                // up-and-forward. With the default autoCenterContent the union bounding
                // box of those three is centred — which shifts the helmet far off the
                // HeroOrbit camera's fixed (0,0,0) pivot, leaving the viewport black
                // (#1421). Disable it so each node keeps its authored world position and
                // the camera orbits the helmet exactly as intended.
                autoCenterContent = false,
                cameraManipulator = cameraManipulator,
            ) {
                modelInstance?.let { instance ->
                    ModelNode(
                        modelInstance = instance,
                        scaleToUnits = 0.5f,
                    )
                }

                // Backdrop wall — see [backdropMaterial] for the rationale. Sized
                // 1.6 × 1.2 m: large enough to fill the backdrop behind the
                // 0.5 m helmet from every hero-orbit angle, small enough that it
                // never crams the helmet into a corner (#1466 — the old 3 × 2.4 m
                // quad filled ~⅔ of the viewport with a hard diagonal top edge).
                // Centred on the helmet (y = 0) and pushed back so it never clips
                // into the model while staying inside the orbit radius.
                PlaneNode(
                    materialInstance = backdropMaterial,
                    size = Float3(1.6f, 1.2f, 0f),
                    normal = Direction(z = 1f),
                    position = Position(0f, 0f, -0.9f),
                )

                // Tiny ball at the light source position — only for Point/Spot since
                // a directional light has no localized origin. The colour of the
                // marker tracks the light colour so users tie source ↔ light visually.
                if (selectedType.type != LightManager.Type.DIRECTIONAL) {
                    SphereNode(
                        materialInstance = sourceMaterial,
                        radius = 0.05f,
                        position = lightPosition,
                    )
                }

                LightNode(
                    type = selectedType.type,
                    intensity = intensity,
                    color = colorOf(
                        r = selectedColor.r,
                        g = selectedColor.g,
                        b = selectedColor.b
                    ),
                    // Direction points from lightPosition toward the helmet at origin so
                    // the spot cone hits the helmet front and the wall behind, making the
                    // disc clearly visible. Used for Directional + Spot.
                    direction = Direction(0f, -1.4f, -1.0f),
                    position = lightPosition,
                    apply = {
                        // Spot: very narrow cone (≈11° outer) so the disc on the wall
                        // reads as a sharp circle, not a wide wash. Falloff 4 m keeps
                        // the cone visible all the way to the backdrop wall.
                        // Point: aggressive 2 m falloff so the wall shows the radial
                        // gradient (helmet front bright, wall corners dark).
                        if (selectedType.type == LightManager.Type.FOCUSED_SPOT) {
                            spotLightCone(0.05f, 0.2f)
                            falloff(4f)
                        } else if (selectedType.type == LightManager.Type.POINT) {
                            falloff(2.5f)
                        }
                    }
                )
            }
            LoadingScrim(loading = modelInstance == null, label = "Loading helmet…")
        }
    }
}

/**
 * "Movable Light" mode — drag-to-orbit a single point light. Formerly the
 * standalone `MovableLightDemo`.
 *
 * The user drags anywhere on the scene to move a point light in an orbit
 * (azimuth / elevation, fixed radius) around the PBR helmet model; the
 * specular highlights track the light in real time.
 */
@Composable
private fun MovableLightScene(
    onBack: () -> Unit,
    mode: LightingMode,
    onModeChange: (LightingMode) -> Unit,
) {
    // Spherical-orbit state. Start with the light up-and-to-the-right of the
    // helmet so the user sees a strong highlight on first paint, not a flat
    // helmet they have to discover by dragging.
    var azimuth by remember { mutableFloatStateOf((PI / 4).toFloat()) }      // 45°
    var elevation by remember { mutableFloatStateOf((PI / 6).toFloat()) }    // 30°
    var intensity by remember { mutableFloatStateOf(30_000f) }
    var showLightSource by remember { mutableStateOf(true) }

    // Fixed orbit radius. Kept deliberately small (#1467): the helmet is fit to
    // a 0.6 m bounding cube (≈ 0.35 m diagonal half-extent) and the default
    // camera (`DefaultCameraNode`) sits at z = 2.75 m with a 28 mm lens — its
    // frame was tuned in #1427 to show a 0.6 m origin-placed model with only
    // modest headroom. The old 1.5 m orbit swung the yellow handle far outside
    // the camera frustum for most of the azimuth/elevation sweep, so the user
    // was dragging an invisible target. 0.75 m clears the helmet (no clipping
    // into the model) yet keeps the handle on-screen for the whole orbit, while
    // the moving highlight on the metal stays sharp.
    val orbitRadius = 0.75f
    // Clamp elevation to ±50° so the handle never climbs/drops out of the camera
    // frustum vertically (#1467). At 0.75 m orbit, sin(50°)·0.75 ≈ 0.57 m, well
    // within the vertical FOV. This also keeps the light off the gimbal poles.
    val minElevation = -((PI / 180) * 50).toFloat()
    val maxElevation = ((PI / 180) * 50).toFloat()
    // Drag sensitivity in radians-per-pixel. 0.005 rad/px gives ≈ a half-turn
    // per full screen-width swipe on a typical 1080p phone — feels responsive
    // without being twitchy.
    val sensitivity = 0.005f

    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)
    val modelInstance = rememberModelInstance(modelLoader, "models/khronos_damaged_helmet.glb")

    // Compute Cartesian position from spherical. We do this in the composition
    // so reading `azimuth`/`elevation` triggers a recomposition → the LightNode
    // SideEffect re-pushes the new `position` on the Filament side.
    val lightPos = remember(azimuth, elevation) {
        val cosE = cos(elevation)
        Position(
            x = orbitRadius * cosE * sin(azimuth),
            y = orbitRadius * sin(elevation),
            z = orbitRadius * cosE * cos(azimuth),
        )
    }
    // Direction from light → origin (the helmet centre). Spot/directional
    // ignore this for our chosen Point type but we keep it in case the user
    // experiments — defensive parity with the Light Types mode.
    val lightDir = remember(lightPos) {
        Direction(-lightPos.x, -lightPos.y, -lightPos.z)
    }

    // Yellow unlit material for the marker sphere — see iOS rationale. Unlit
    // = always glowing, regardless of the user-light's position. Helper has
    // a DisposableEffect-backed onDispose → no JNI MaterialInstance leak on
    // recompose / navigate-away (#979).
    val markerMaterial = rememberUnlitMaterialInstance(materialLoader, Color(0xFFFFEB3B))

    val firstFrame = rememberFirstFrameState()

    DemoScaffold(
        title = stringResource(R.string.demo_lighting_title),
        onBack = onBack,
        firstFrameRendered = firstFrame.rendered,
        controls = {
            LightingModeSelector(mode = mode, onModeChange = onModeChange)
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.WbSunny, contentDescription = null)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Intensity: ${intensity.toInt()}",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Slider(
                value = intensity,
                onValueChange = { intensity = it },
                valueRange = 1_000f..100_000f
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Show light source",
                    style = MaterialTheme.typography.labelLarge
                )
                Switch(
                    checked = showLightSource,
                    onCheckedChange = { showLightSource = it }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                "Drag anywhere on the scene to orbit the light around the helmet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                // Capture drag input *before* SceneView's gesture detector
                // sees it. Because `cameraManipulator = null` below disables
                // the camera pinch/drag detector anyway, this is the only
                // consumer of drag events and the gesture stays smooth even
                // on small fast motions.
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        azimuth += dragAmount.x * sensitivity
                        // Screen Y grows downwards → invert so "drag down" =
                        // "light goes down" (matches iOS behaviour).
                        elevation -= dragAmount.y * sensitivity
                        elevation = min(max(elevation, minElevation), maxElevation)
                    }
                }
        ) {
            SceneView(
                modifier = Modifier.fillMaxSize(),
                onFrame = firstFrame.onFrame,
                engine = engine,
                modelLoader = modelLoader,
                environmentLoader = environmentLoader,
                // No camera gestures — fixes the camera so the user's drag is
                // unambiguously interpreted as "move the light".
                cameraManipulator = null,
                // Disable the default 110 klx main directional light so the
                // ONLY light shaping the helmet is the user-controlled one.
                // The IBL from environmentLoader stays on for a tiny bit of
                // ambient fill so the helmet is never pitch-black even if the
                // user drags the light behind it.
                mainLightNode = null,
            ) {
                modelInstance?.let { instance ->
                    ModelNode(
                        modelInstance = instance,
                        scaleToUnits = 0.6f,
                    )
                }

                // User-controlled point light orbiting the helmet at origin.
                LightNode(
                    type = LightManager.Type.POINT,
                    intensity = intensity,
                    direction = lightDir,
                    position = lightPos,
                    color = colorOf(r = 1.0f, g = 0.95f, b = 0.8f),
                    apply = {
                        // 4 m attenuation radius — beyond this the light has
                        // no effect. Comfortably larger than orbitRadius (0.75 m)
                        // so the light always reaches the helmet.
                        falloff(4f)
                    },
                )

                // Yellow marker sphere — the draggable light handle. Only
                // rendered when the toggle is on. Radius 0.09 m (#1467): the
                // old 0.05 m disc was a barely-visible speck at the camera's
                // ≈ 2 m working distance; 0.09 m reads as a clear glowing
                // handle the user can confidently aim a drag at.
                if (showLightSource) {
                    SphereNode(
                        materialInstance = markerMaterial,
                        radius = 0.09f,
                        position = lightPos,
                    )
                }
            }
            LoadingScrim(loading = modelInstance == null, label = "Loading helmet…")
        }
    }
}

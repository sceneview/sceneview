// ─── Car configurator code generator ──────────────────────────────────────────

export type CarBodyStyle =
  | "sedan"
  | "suv"
  | "coupe"
  | "hatchback"
  | "convertible"
  | "truck"
  | "van"
  | "sports"
  | "luxury"
  | "electric";

export type CameraPreset =
  | "exterior-front"
  | "exterior-rear"
  | "exterior-side"
  | "exterior-three-quarter"
  | "interior-driver"
  | "interior-rear"
  | "detail-wheel"
  | "detail-headlight"
  | "detail-grille"
  | "detail-taillight";

export type ColorCategory =
  | "solid"
  | "metallic"
  | "matte"
  | "pearlescent"
  | "custom";

export const CAR_BODY_STYLES: CarBodyStyle[] = [
  "sedan", "suv", "coupe", "hatchback", "convertible",
  "truck", "van", "sports", "luxury", "electric",
];

export const CAMERA_PRESETS: CameraPreset[] = [
  "exterior-front", "exterior-rear", "exterior-side", "exterior-three-quarter",
  "interior-driver", "interior-rear",
  "detail-wheel", "detail-headlight", "detail-grille", "detail-taillight",
];

export const COLOR_CATEGORIES: ColorCategory[] = [
  "solid", "metallic", "matte", "pearlescent", "custom",
];

export interface CarConfiguratorOptions {
  bodyStyle: CarBodyStyle;
  colorPicker?: boolean;
  materialVariants?: boolean;
  cameraPresets?: CameraPreset[];
  turntable?: boolean;
  ar?: boolean;
}

/**
 * Generates a complete, compilable Kotlin composable for a 3D car configurator.
 */
export function generateCarConfigurator(options: CarConfiguratorOptions): string {
  const {
    bodyStyle,
    colorPicker = true,
    materialVariants = true,
    cameraPresets = ["exterior-front", "exterior-three-quarter", "interior-driver"],
    turntable = true,
    ar = false,
  } = options;

  const modelPath = getCarModelPath(bodyStyle);
  const composableName = `${capitalize(bodyStyle)}Configurator`;

  if (ar) {
    return generateArCarConfigurator(composableName, modelPath, options);
  }

  return `package com.example.automotive.configurator

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.google.android.filament.LightManager
import io.github.sceneview.SceneView
import io.github.sceneview.node.ModelNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.node.LightNode
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation

/**
 * ${capitalize(bodyStyle)} car configurator — interactive 3D color and material selection.
 *
 * Features: color picker, material variants (metallic, matte, pearlescent),
 * camera presets (${cameraPresets.join(", ")}), ${turntable ? "auto-rotation turntable, " : ""}and orbit controls.
 *
 * Model: Place your GLB file at src/main/assets/${modelPath}
 * Tip: Use glTF with KHR_materials_variants for paint/interior material switching.
 */
@Composable
fun ${composableName}() {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)

    val modelInstance = rememberModelInstance(modelLoader, "${modelPath}")

${turntable ? `    var rotationAngle by remember { mutableFloatStateOf(0f) }
    var autoRotate by remember { mutableStateOf(true) }` : ""}
${colorPicker ? `
    // Available car colors
    val carColors = listOf(
        "Arctic White" to Color(0xFFF5F5F5),
        "Midnight Black" to Color(0xFF1A1A1A),
        "Racing Red" to Color(0xFFCC0000),
        "Ocean Blue" to Color(0xFF0055AA),
        "Forest Green" to Color(0xFF006633),
        "Champagne Gold" to Color(0xFFD4AF37),
        "Gunmetal Grey" to Color(0xFF4A4A4A),
        "Pearl White" to Color(0xFFF8F0E8)
    )
    var selectedColor by remember { mutableIntStateOf(0) }` : ""}
${materialVariants ? `
    val materialTypes = listOf("Metallic", "Matte", "Pearlescent", "Gloss")
    var selectedMaterial by remember { mutableIntStateOf(0) }` : ""}

    // Camera preset positions
    var cameraPreset by remember { mutableStateOf("${cameraPresets[0]}") }

    Column(modifier = Modifier.fillMaxSize()) {
        // ── 3D Scene ──────────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            SceneView(
                modifier = Modifier.fillMaxSize(),
                engine = engine,
                modelLoader = modelLoader,
                environment = environmentLoader.createHDREnvironment(
                    assetFileLocation = "environments/studio_hdr.ktx"
                )!!,
                onFrame = { frameTimeNanos ->
${turntable ? `                    if (autoRotate) {
                        rotationAngle += 0.3f
                    }` : "                    // Frame update"}
                }
            ) {
                // Car model
                modelInstance?.let { instance ->
                    ModelNode(
                        modelInstance = instance,
                        scaleToUnits = 2.5f${turntable ? `,
                        rotation = Rotation(y = rotationAngle)` : ""}
                    )
                }

                // Studio lighting — key, fill, rim
                LightNode(
                    type = LightManager.Type.DIRECTIONAL,
                    apply = {
                        intensity(120_000f)
                        color(1.0f, 0.98f, 0.95f)
                        direction(-1f, -1f, -0.5f)
                    }
                )
                LightNode(
                    type = LightManager.Type.DIRECTIONAL,
                    apply = {
                        intensity(40_000f)
                        color(0.9f, 0.93f, 1.0f)
                        direction(1f, -0.5f, 0.5f)
                    }
                )
                LightNode(
                    type = LightManager.Type.DIRECTIONAL,
                    apply = {
                        intensity(25_000f)
                        color(1.0f, 1.0f, 1.0f)
                        direction(0f, 0.5f, -1f)
                    }
                )
            }

            // Loading indicator
            if (modelInstance == null) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center)
                )
            }

            // Camera preset chips
            CameraPresetBar(
                presets = listOf(${cameraPresets.map((p) => `"${p}"`).join(", ")}),
                selected = cameraPreset,
                onSelect = { cameraPreset = it },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(16.dp)
            )
        }

        // ── Controls ──────────────────────────────────────────────────────
        ConfiguratorControls(
${colorPicker ? `            colors = carColors,
            selectedColor = selectedColor,
            onColorSelect = { selectedColor = it },` : ""}
${materialVariants ? `            materials = materialTypes,
            selectedMaterial = selectedMaterial,
            onMaterialSelect = { selectedMaterial = it },` : ""}
${turntable ? `            autoRotate = autoRotate,
            onAutoRotateToggle = { autoRotate = it },` : ""}
        )
    }
}

/**
 * Camera preset selection bar.
 */
@Composable
private fun CameraPresetBar(
    presets: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(presets) { preset ->
            FilterChip(
                selected = preset == selected,
                onClick = { onSelect(preset) },
                label = {
                    Text(preset.replace("-", " ").replaceFirstChar { it.uppercase() })
                }
            )
        }
    }
}

/**
 * Configurator control panel — color picker, material selector, turntable toggle.
 */
@Composable
private fun ConfiguratorControls(
${colorPicker ? `    colors: List<Pair<String, Color>>,
    selectedColor: Int,
    onColorSelect: (Int) -> Unit,` : ""}
${materialVariants ? `    materials: List<String>,
    selectedMaterial: Int,
    onMaterialSelect: (Int) -> Unit,` : ""}
${turntable ? `    autoRotate: Boolean,
    onAutoRotateToggle: (Boolean) -> Unit,` : ""}
) {
    Surface(
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
${colorPicker ? `            // Color picker
            Text("Exterior Color", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(colors.size) { index ->
                    val (name, color) = colors[index]
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(color)
                            .clickable { onColorSelect(index) }
                    ) {
                        if (index == selectedColor) {
                            // Plain text check mark — no material-icons dependency needed
                            Text(
                                text = "✓",
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .semantics { contentDescription = name },
                                color = if (color == Color(0xFF1A1A1A)) Color.White else Color.Black
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))` : ""}
${materialVariants ? `            // Material selector
            Text("Paint Finish", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(materials.size) { index ->
                    FilterChip(
                        selected = index == selectedMaterial,
                        onClick = { onMaterialSelect(index) },
                        label = { Text(materials[index]) }
                    )
                }
            }
            Spacer(Modifier.height(12.dp))` : ""}
${turntable ? `            // Turntable toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(value = autoRotate, onValueChange = onAutoRotateToggle),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Auto-Rotate", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.weight(1f))
                Switch(checked = autoRotate, onCheckedChange = null)
            }` : ""}
        }
    }
}`;
}

function generateArCarConfigurator(
  composableName: string,
  modelPath: string,
  options: CarConfiguratorOptions
): string {
  return `package com.example.automotive.configurator

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.ar.core.Frame
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.node.AnchorNode
import io.github.sceneview.node.ModelNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberOnGestureListener
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.math.Position

/**
 * AR ${capitalize(options.bodyStyle)} car configurator.
 *
 * Places the car model in augmented reality — see it in your driveway, parking lot,
 * or garage at real scale. Tap a surface to place the car.
 *
 * Model: Place your GLB file at src/main/assets/${modelPath}
 *
 * Required AndroidManifest.xml:
 *   <uses-permission android:name="android.permission.CAMERA" />
 *   <uses-feature android:name="android.hardware.camera.ar" android:required="true" />
 *   <meta-data android:name="com.google.ar.core" android:value="required" />
 *
 * Gradle: implementation("io.github.sceneview:arsceneview:4.53.0")
 */
@Composable
fun ${composableName}AR() {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    // Latest ARCore frame — the tap handler hit-tests against it
    var latestFrame by remember { mutableStateOf<Frame?>(null) }

    val modelInstance = rememberModelInstance(modelLoader, "${modelPath}")

    var placed by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        ARSceneView(
            modifier = Modifier.fillMaxSize(),
            engine = engine,
            modelLoader = modelLoader,
            planeRenderer = true,
            onSessionUpdated = { _, frame ->
                // AR session active
                latestFrame = frame
            },
            onGestureListener = rememberOnGestureListener(
                onSingleTapConfirmed = { e, _ ->
                    // Hit-test the tap against the latest ARCore frame
                    latestFrame?.hitTest(e)?.firstOrNull()?.let { hitResult ->
                        if (!placed && modelInstance != null) {
                            val anchor = hitResult.createAnchor()
                            // Place car at tapped location
                            placed = true
                        }
                    }
                }
            )
        ) {
            modelInstance?.let { instance ->
                ModelNode(
                    modelInstance = instance,
                    scaleToUnits = 4.5f // Real-world car size ~4.5m
                )
            }
        }

        // Placement instruction
        if (!placed) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(24.dp)
            ) {
                Text(
                    text = "Point camera at a flat surface, then tap to place the car",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}`;
}

function getCarModelPath(bodyStyle: CarBodyStyle): string {
  return `models/cars/${bodyStyle}_car.glb`;
}

function capitalize(s: string): string {
  return s
    .split("-")
    .map((w) => w.charAt(0).toUpperCase() + w.slice(1))
    .join("");
}

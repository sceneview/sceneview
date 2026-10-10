// ─── Dental 3D scanning visualization code generator ─────────────────────────

export type DentalViewType =
  | "full-arch"
  | "single-tooth"
  | "implant"
  | "orthodontic"
  | "crown-bridge"
  | "intraoral-scan"
  | "cbct";

export type DentalFeature =
  | "measurement"
  | "margin-line"
  | "occlusion"
  | "comparison"
  | "shade-matching"
  | "treatment-stages";

export const DENTAL_VIEW_TYPES: DentalViewType[] = [
  "full-arch", "single-tooth", "implant", "orthodontic",
  "crown-bridge", "intraoral-scan", "cbct",
];

export const DENTAL_FEATURES: DentalFeature[] = [
  "measurement", "margin-line", "occlusion",
  "comparison", "shade-matching", "treatment-stages",
];

export interface DentalViewerOptions {
  viewType: DentalViewType;
  features?: DentalFeature[];
  arch?: "upper" | "lower" | "both";
  showRoots?: boolean;
  showNerves?: boolean;
  ar?: boolean;
}

/**
 * Generates a complete, compilable Kotlin composable for dental 3D visualization.
 */
export function generateDentalViewer(options: DentalViewerOptions): string {
  const {
    viewType,
    features = ["measurement"],
    arch = "both",
    showRoots = false,
    showNerves = false,
    ar = false,
  } = options;

  const composableName = `Dental${capitalize(viewType)}Viewer`;

  if (ar) {
    return generateArDentalViewer(composableName, viewType, arch, options);
  }

  const hasMeasurement = features.includes("measurement");
  const hasComparison = features.includes("comparison");
  const hasTreatmentStages = features.includes("treatment-stages");

  return `package com.example.medical.dental

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.android.filament.LightManager
import io.github.sceneview.SceneView
import io.github.sceneview.node.ModelNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.node.LightNode

/**
 * Dental ${viewType.replace("-", " ")} 3D viewer — ${arch} arch.
 *
 * Displays a dental scan or model with orbit controls, pinch-to-zoom, and
 * specialized dental tools (${features.join(", ")}).
 *
 * ## Dental 3D scan workflow
 *
 * 1. **Intraoral scan**: Export from scanner software (iTero, 3Shape, Medit) as STL/PLY
 * 2. **Convert to GLB**: Use Blender or MeshLab (STL/PLY → GLB with vertex colors)
 * 3. **Optimize**: Decimate to < 500K triangles for mobile
 * 4. **Load in SceneView**: Place at src/main/assets/models/dental/
 *
 * ${viewType === "cbct" ? "For CBCT data: segment in 3D Slicer, export mesh as GLB." : ""}
 *
 * Model files:
 * - Primary: src/main/assets/models/dental/${viewType}_${arch}.glb
 * ${showRoots ? `- Roots: src/main/assets/models/dental/${viewType}_roots.glb` : ""}
 * ${showNerves ? `- Nerves: src/main/assets/models/dental/${viewType}_nerves.glb` : ""}
 */
@Composable
fun ${composableName}() {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)

    // Primary dental model
    val dentalModel = rememberModelInstance(
        modelLoader,
        "models/dental/${viewType}_${arch}.glb"
    )

${showRoots ? `    // Root structure overlay
    val rootsModel = rememberModelInstance(
        modelLoader,
        "models/dental/${viewType}_roots.glb"
    )
    var showRootsLayer by remember { mutableStateOf(false) }` : ""}

${showNerves ? `    // Nerve pathway overlay (inferior alveolar, mental nerve, etc.)
    val nervesModel = rememberModelInstance(
        modelLoader,
        "models/dental/${viewType}_nerves.glb"
    )
    var showNervesLayer by remember { mutableStateOf(false) }` : ""}

${hasTreatmentStages ? `    // Treatment stages for orthodontic or progressive views
    var currentStage by remember { mutableIntStateOf(0) }
    val totalStages = 12 // Typical orthodontic treatment stages` : ""}

${hasComparison ? `    // Before/after comparison
    var showComparison by remember { mutableStateOf(false) }
    val comparisonModel = rememberModelInstance(
        modelLoader,
        "models/dental/${viewType}_${arch}_comparison.glb"
    )` : ""}

    var activeTool by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        // ── 3D Dental Scene ───────────────────────────────────────────────
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
                    assetFileLocation = "environments/neutral_hdr.ktx"
                )!!,
            ) {
                // Primary dental scan
                dentalModel?.let { instance ->
                    ModelNode(
                        modelInstance = instance,
                        scaleToUnits = 0.15f // ~15cm dental arch
                    )
                }

${showRoots ? `                // Root structure
                if (showRootsLayer) {
                    rootsModel?.let { instance ->
                        ModelNode(
                            modelInstance = instance,
                            scaleToUnits = 0.15f
                        )
                    }
                }` : ""}

${showNerves ? `                // Nerve pathways
                if (showNervesLayer) {
                    nervesModel?.let { instance ->
                        ModelNode(
                            modelInstance = instance,
                            scaleToUnits = 0.15f
                        )
                    }
                }` : ""}

${hasComparison ? `                // Comparison overlay
                if (showComparison) {
                    comparisonModel?.let { instance ->
                        ModelNode(
                            modelInstance = instance,
                            scaleToUnits = 0.15f
                        )
                    }
                }` : ""}

                // Dental-grade lighting — bright, neutral, multiple angles
                // Mimics dental operatory lighting
                LightNode(
                    type = LightManager.Type.DIRECTIONAL,
                    apply = {
                        intensity(120_000f)
                        color(1.0f, 0.99f, 0.97f) // Slight warm tint like dental LED
                        direction(0f, -1f, -0.5f)
                    }
                )

                LightNode(
                    type = LightManager.Type.DIRECTIONAL,
                    apply = {
                        intensity(80_000f)
                        color(1.0f, 1.0f, 1.0f)
                        direction(0f, 1f, 0.3f)
                    }
                )

                LightNode(
                    type = LightManager.Type.DIRECTIONAL,
                    apply = {
                        intensity(40_000f)
                        color(1.0f, 1.0f, 1.0f)
                        direction(1f, 0f, -0.2f)
                    }
                )
            }

            // Loading state
            if (dentalModel == null) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(8.dp))
                    Text("Loading dental scan...")
                }
            }
        }

        // ── Dental Controls ───────────────────────────────────────────────
        DentalControls(
            activeTool = activeTool,
            onToolSelected = { activeTool = if (activeTool == it) null else it },
${showRoots ? `            showRoots = showRootsLayer,
            onToggleRoots = { showRootsLayer = it },` : ""}
${showNerves ? `            showNerves = showNervesLayer,
            onToggleNerves = { showNervesLayer = it },` : ""}
${hasTreatmentStages ? `            currentStage = currentStage,
            totalStages = totalStages,
            onStageChange = { currentStage = it },` : ""}
${hasComparison ? `            showComparison = showComparison,
            onToggleComparison = { showComparison = it },` : ""}
            features = listOf(${features.map((f) => `"${f}"`).join(", ")})
        )
    }
}

/**
 * Dental viewer control panel.
 */
@Composable
private fun DentalControls(
    activeTool: String?,
    onToolSelected: (String) -> Unit,
${showRoots ? `    showRoots: Boolean,
    onToggleRoots: (Boolean) -> Unit,` : ""}
${showNerves ? `    showNerves: Boolean,
    onToggleNerves: (Boolean) -> Unit,` : ""}
${hasTreatmentStages ? `    currentStage: Int,
    totalStages: Int,
    onStageChange: (Int) -> Unit,` : ""}
${hasComparison ? `    showComparison: Boolean,
    onToggleComparison: (Boolean) -> Unit,` : ""}
    features: List<String>,
) {
    Surface(
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Dental Tools", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                features.forEach { feature ->
                    FilterChip(
                        selected = activeTool == feature,
                        onClick = { onToolSelected(feature) },
                        label = {
                            Text(feature.replace("-", " ").replaceFirstChar { it.uppercase() })
                        }
                    )
                }
            }

${showRoots ? `
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(value = showRoots, onValueChange = onToggleRoots),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Show roots", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.weight(1f))
                Switch(checked = showRoots, onCheckedChange = null)
            }` : ""}

${showNerves ? `
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(value = showNerves, onValueChange = onToggleNerves),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Show nerves", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.weight(1f))
                Switch(checked = showNerves, onCheckedChange = null)
            }` : ""}

${hasTreatmentStages ? `
            Spacer(Modifier.height(8.dp))
            Text("Treatment stage: \${currentStage + 1} / $totalStages", style = MaterialTheme.typography.bodySmall)
            Slider(
                value = currentStage.toFloat(),
                onValueChange = { onStageChange(it.toInt()) },
                valueRange = 0f..(totalStages - 1).toFloat(),
                steps = totalStages - 2
            )` : ""}

${hasComparison ? `
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(value = showComparison, onValueChange = onToggleComparison),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Before / After", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.weight(1f))
                Switch(checked = showComparison, onCheckedChange = null)
            }` : ""}
        }
    }
}`;
}

function generateArDentalViewer(
  composableName: string,
  viewType: DentalViewType,
  arch: string,
  options: DentalViewerOptions
): string {
  return `package com.example.medical.dental

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
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

/**
 * AR dental ${viewType.replace("-", " ")} viewer — ${arch} arch.
 *
 * Places the dental scan in augmented reality for patient consultation.
 * Show patients their dental condition and treatment plan in 3D.
 *
 * Model: Place dental GLB at src/main/assets/models/dental/${viewType}_${arch}.glb
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

    val dentalModel = rememberModelInstance(
        modelLoader,
        "models/dental/${viewType}_${arch}.glb"
    )

    var placed by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        ARSceneView(
            modifier = Modifier.fillMaxSize(),
            engine = engine,
            modelLoader = modelLoader,
            planeRenderer = true,
            onSessionUpdated = { _, frame -> latestFrame = frame },
            onGestureListener = rememberOnGestureListener(
                onSingleTapConfirmed = { e, _ ->
                    // Hit-test the tap against the latest ARCore frame
                    latestFrame?.hitTest(e)?.firstOrNull()?.let { hitResult ->
                        if (!placed && dentalModel != null) {
                            val anchor = hitResult.createAnchor()
                            placed = true
                        }
                    }
                }
            )
        ) {
            dentalModel?.let { instance ->
                ModelNode(
                    modelInstance = instance,
                    scaleToUnits = 0.15f // Life-size dental arch ~15cm
                )
            }
        }

        if (!placed) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(24.dp)
            ) {
                Text(
                    text = "Point camera at a surface, then tap to place dental model",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}`;
}

function capitalize(s: string): string {
  return s
    .split("-")
    .map((w) => w.charAt(0).toUpperCase() + w.slice(1))
    .join("");
}

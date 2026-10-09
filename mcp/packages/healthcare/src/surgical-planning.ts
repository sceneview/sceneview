// ─── Surgical planning 3D visualization code generator ───────────────────────

export type SurgeryType =
  | "orthopedic"
  | "cardiac"
  | "neurosurgery"
  | "maxillofacial"
  | "spinal"
  | "laparoscopic"
  | "ophthalmic"
  | "general";

export type PlanningFeature =
  | "measurement"
  | "annotation"
  | "cross-section"
  | "implant-placement"
  | "trajectory"
  | "comparison";

export const SURGERY_TYPES: SurgeryType[] = [
  "orthopedic", "cardiac", "neurosurgery", "maxillofacial",
  "spinal", "laparoscopic", "ophthalmic", "general",
];

export const PLANNING_FEATURES: PlanningFeature[] = [
  "measurement", "annotation", "cross-section",
  "implant-placement", "trajectory", "comparison",
];

export interface SurgicalPlanningOptions {
  surgeryType: SurgeryType;
  features?: PlanningFeature[];
  implantModel?: boolean;
  preOpComparison?: boolean;
  ar?: boolean;
}

/**
 * Generates a complete, compilable Kotlin composable for surgical planning visualization.
 */
export function generateSurgicalPlanning(options: SurgicalPlanningOptions): string {
  const {
    surgeryType,
    features = ["measurement", "annotation"],
    implantModel = false,
    preOpComparison = false,
    ar = false,
  } = options;

  const composableName = `${capitalize(surgeryType)}SurgicalPlanner`;

  if (ar) {
    return generateArSurgicalPlanning(composableName, surgeryType, options);
  }

  const hasMeasurement = features.includes("measurement");
  const hasAnnotation = features.includes("annotation");
  const hasCrossSection = features.includes("cross-section");

  return `package com.example.medical.surgical

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.android.filament.LightManager
import com.google.ar.core.Frame
import io.github.sceneview.SceneView
import io.github.sceneview.node.ModelNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberOnGestureListener
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.node.LightNode
import io.github.sceneview.math.Position

/**
 * ${capitalize(surgeryType)} surgical planning viewer.
 *
 * Features: ${features.join(", ")}
 * ${implantModel ? "Includes implant placement preview." : ""}
 * ${preOpComparison ? "Includes pre-operative comparison overlay." : ""}
 *
 * ## Usage
 *
 * 1. Load patient-specific 3D model (from DICOM reconstruction)
 * 2. Use measurement tools to plan incision points and distances
 * 3. ${implantModel ? "Position implant models to verify fit" : "Annotate key landmarks"}
 * 4. ${preOpComparison ? "Compare pre-op and post-op models" : "Export plan for review"}
 *
 * Model: Place patient GLB at src/main/assets/models/surgical/${surgeryType}_patient.glb
 * ${implantModel ? `Implant: Place at src/main/assets/models/surgical/${surgeryType}_implant.glb` : ""}
 */
@Composable
fun ${composableName}() {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)

    // Patient anatomy model (from DICOM 3D reconstruction)
    val patientModel = rememberModelInstance(
        modelLoader,
        "models/surgical/${surgeryType}_patient.glb"
    )

${implantModel ? `    // Implant / prosthesis model
    val implantModelInstance = rememberModelInstance(
        modelLoader,
        "models/surgical/${surgeryType}_implant.glb"
    )
    var showImplant by remember { mutableStateOf(true) }` : ""}

${preOpComparison ? `    // Pre-operative comparison model
    val preOpModel = rememberModelInstance(
        modelLoader,
        "models/surgical/${surgeryType}_preop.glb"
    )
    var showPreOp by remember { mutableStateOf(false) }` : ""}

    // Planning tool state
    var activeTool by remember { mutableStateOf<String?>(null) }
${hasMeasurement ? `    var measurements by remember { mutableStateOf(listOf<String>()) }` : ""}
${hasAnnotation ? `    var annotations by remember { mutableStateOf(listOf<String>()) }` : ""}
${hasCrossSection ? `    var crossSectionPosition by remember { mutableFloatStateOf(0.5f) }` : ""}

    Column(modifier = Modifier.fillMaxSize()) {
        // ── 3D Surgical Planning Scene ────────────────────────────────────
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
                onGestureListener = rememberOnGestureListener(
                    onSingleTapConfirmed = { motionEvent, node ->
                        // Handle measurement point placement or annotation
                        when (activeTool) {
${hasMeasurement ? `                            "measurement" -> {
                                // Add measurement point at tap location
                                // In production, project screen coordinates to 3D
                            }` : ""}
${hasAnnotation ? `                            "annotation" -> {
                                // Add annotation at tap location
                            }` : ""}
                            else -> { /* Default: orbit camera */ }
                        }
                    }
                )
            ) {
                // Patient anatomy
                patientModel?.let { instance ->
                    ModelNode(
                        modelInstance = instance,
                        scaleToUnits = 1.0f
                    )
                }

${implantModel ? `                // Implant overlay
                if (showImplant) {
                    implantModelInstance?.let { instance ->
                        ModelNode(
                            modelInstance = instance,
                            scaleToUnits = 1.0f
                        )
                    }
                }` : ""}

${preOpComparison ? `                // Pre-op comparison
                if (showPreOp) {
                    preOpModel?.let { instance ->
                        ModelNode(
                            modelInstance = instance,
                            scaleToUnits = 1.0f
                        )
                    }
                }` : ""}

                // Surgical lighting — bright, shadowless, clinical
                LightNode(
                    type = LightManager.Type.DIRECTIONAL,
                    apply = {
                        intensity(150_000f)
                        color(1.0f, 1.0f, 1.0f)
                        direction(0f, -1f, -0.3f)
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
                        intensity(60_000f)
                        color(1.0f, 1.0f, 1.0f)
                        direction(1f, 0f, 0f)
                    }
                )

                LightNode(
                    type = LightManager.Type.DIRECTIONAL,
                    apply = {
                        intensity(60_000f)
                        color(1.0f, 1.0f, 1.0f)
                        direction(-1f, 0f, 0f)
                    }
                )
            }

            // Loading state
            if (patientModel == null) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(8.dp))
                    Text("Loading patient model...")
                }
            }
        }

        // ── Planning Tools ────────────────────────────────────────────────
        SurgicalToolbar(
            activeTool = activeTool,
            onToolSelected = { activeTool = if (activeTool == it) null else it },
${implantModel ? `            showImplant = showImplant,
            onToggleImplant = { showImplant = it },` : ""}
${preOpComparison ? `            showPreOp = showPreOp,
            onTogglePreOp = { showPreOp = it },` : ""}
${hasCrossSection ? `            crossSectionPosition = crossSectionPosition,
            onCrossSectionChange = { crossSectionPosition = it },` : ""}
            features = listOf(${features.map((f) => `"${f}"`).join(", ")})
        )
    }
}

/**
 * Surgical planning toolbar with tool selection and model toggles.
 */
@Composable
private fun SurgicalToolbar(
    activeTool: String?,
    onToolSelected: (String) -> Unit,
${implantModel ? `    showImplant: Boolean,
    onToggleImplant: (Boolean) -> Unit,` : ""}
${preOpComparison ? `    showPreOp: Boolean,
    onTogglePreOp: (Boolean) -> Unit,` : ""}
${hasCrossSection ? `    crossSectionPosition: Float,
    onCrossSectionChange: (Float) -> Unit,` : ""}
    features: List<String>,
) {
    Surface(
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Planning Tools", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))

            // Tool selection chips
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

${implantModel ? `
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(value = showImplant, onValueChange = onToggleImplant),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Show implant", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.weight(1f))
                Switch(checked = showImplant, onCheckedChange = null)
            }` : ""}

${preOpComparison ? `
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(value = showPreOp, onValueChange = onTogglePreOp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Pre-op overlay", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.weight(1f))
                Switch(checked = showPreOp, onCheckedChange = null)
            }` : ""}

${hasCrossSection ? `
            Text("Cross-section plane", style = MaterialTheme.typography.bodySmall)
            Slider(
                value = crossSectionPosition,
                onValueChange = onCrossSectionChange,
                valueRange = 0f..1f
            )` : ""}
        }
    }
}`;
}

function generateArSurgicalPlanning(
  composableName: string,
  surgeryType: SurgeryType,
  options: SurgicalPlanningOptions
): string {
  return `package com.example.medical.surgical

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
 * AR ${capitalize(surgeryType)} surgical planning viewer.
 *
 * Places the patient's 3D anatomy in augmented reality for immersive surgical planning.
 * Surgeons can walk around the model, examine from all angles, and discuss approach.
 *
 * Model: Place patient GLB at src/main/assets/models/surgical/${surgeryType}_patient.glb
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

    val patientModel = rememberModelInstance(
        modelLoader,
        "models/surgical/${surgeryType}_patient.glb"
    )

${options.implantModel ? `    val implantModelInstance = rememberModelInstance(
        modelLoader,
        "models/surgical/${surgeryType}_implant.glb"
    )
    var showImplant by remember { mutableStateOf(true) }` : ""}

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
                        if (!placed && patientModel != null) {
                            val anchor = hitResult.createAnchor()
                            placed = true
                        }
                    }
                }
            )
        ) {
            patientModel?.let { instance ->
                ModelNode(
                    modelInstance = instance,
                    scaleToUnits = 0.6f
                )
            }

${options.implantModel ? `            if (showImplant) {
                implantModelInstance?.let { instance ->
                    ModelNode(
                        modelInstance = instance,
                        scaleToUnits = 0.6f
                    )
                }
            }` : ""}
        }

        // Controls overlay
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (!placed) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Text(
                        text = "Point at a surface, then tap to place surgical model",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

${options.implantModel ? `            if (placed) {
                Spacer(Modifier.height(8.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Row(
                        modifier = Modifier
                            .toggleable(value = showImplant, onValueChange = { showImplant = it })
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Implant", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.width(8.dp))
                        Switch(checked = showImplant, onCheckedChange = null)
                    }
                }
            }` : ""}
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

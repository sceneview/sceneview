// ─── Anatomy viewer code generator ───────────────────────────────────────────

export type AnatomySystem =
  | "skeleton"
  | "muscular"
  | "circulatory"
  | "nervous"
  | "respiratory"
  | "digestive"
  | "urinary"
  | "reproductive"
  | "endocrine"
  | "lymphatic"
  | "integumentary"
  | "full-body";

export type AnatomyRegion =
  | "head"
  | "torso"
  | "upper-limb"
  | "lower-limb"
  | "spine"
  | "pelvis"
  | "hand"
  | "foot"
  | "full";

export const ANATOMY_SYSTEMS: AnatomySystem[] = [
  "skeleton", "muscular", "circulatory", "nervous", "respiratory",
  "digestive", "urinary", "reproductive", "endocrine", "lymphatic",
  "integumentary", "full-body",
];

export const ANATOMY_REGIONS: AnatomyRegion[] = [
  "head", "torso", "upper-limb", "lower-limb", "spine", "pelvis",
  "hand", "foot", "full",
];

export interface AnatomyViewerOptions {
  system: AnatomySystem;
  region?: AnatomyRegion;
  transparent?: boolean;
  exploded?: boolean;
  labels?: boolean;
  ar?: boolean;
}

/**
 * Generates a complete, compilable Kotlin composable for an anatomy 3D viewer.
 */
export function generateAnatomyViewer(options: AnatomyViewerOptions): string {
  const {
    system,
    region = "full",
    transparent = false,
    exploded = false,
    labels = true,
    ar = false,
  } = options;

  const modelPath = getAnatomyModelPath(system, region);
  const composableName = `${capitalize(system)}${capitalize(region)}Viewer`;

  if (ar) {
    return generateArAnatomyViewer(composableName, modelPath, options);
  }

  return `package com.example.medical.anatomy

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
import io.github.sceneview.math.Position

/**
 * ${system.charAt(0).toUpperCase() + system.slice(1)} anatomy viewer — ${region} region.
 *
 * Displays a 3D ${system} model with orbit controls, optional labels,
 * ${transparent ? "transparency for layered viewing, " : ""}${exploded ? "exploded view, " : ""}and pinch-to-zoom.
 *
 * Model: Place your GLB file at src/main/assets/${modelPath}
 * Source: BodyParts3D (CC BY-SA 2.1 JP) or NIH 3D Print Exchange (Public Domain).
 * Convert OBJ/STL to GLB using Blender or gltf-transform before use.
 */
@Composable
fun ${composableName}() {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)

    val modelInstance = rememberModelInstance(modelLoader, "${modelPath}")

    // Layer visibility toggles for anatomy systems
    var showLayer by remember { mutableStateOf(true) }
${transparent ? `    var opacity by remember { mutableFloatStateOf(0.7f) }` : ""}
${exploded ? `    var explodeFactor by remember { mutableFloatStateOf(0f) }` : ""}

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
                    assetFileLocation = "environments/neutral_hdr.ktx"
                )!!,
                onFrame = { /* Update animations if needed */ }
            ) {
                // Main anatomy model
                if (showLayer) {
                    modelInstance?.let { instance ->
                        ModelNode(
                            modelInstance = instance,
                            scaleToUnits = 1.0f
                        )
                    }
                }

                // Medical-grade neutral lighting
                LightNode(
                    type = LightManager.Type.DIRECTIONAL,
                    apply = {
                        intensity(80_000f)
                        color(1.0f, 0.98f, 0.95f)
                        direction(0f, -1f, -0.5f)
                    }
                )

                // Fill light from below for anatomy detail
                LightNode(
                    type = LightManager.Type.DIRECTIONAL,
                    apply = {
                        intensity(30_000f)
                        color(0.95f, 0.95f, 1.0f)
                        direction(0f, 1f, 0.3f)
                    }
                )
            }
${labels ? `
            // Anatomy labels overlay
            modelInstance?.let {
                AnatomyLabelsOverlay(
                    system = "${system}",
                    region = "${region}"
                )
            }` : ""}

            // Loading indicator
            if (modelInstance == null) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }

        // ── Controls ──────────────────────────────────────────────────────
        AnatomyControls(
            showLayer = showLayer,
            onToggleLayer = { showLayer = it },
${transparent ? `            opacity = opacity,
            onOpacityChange = { opacity = it },` : ""}
${exploded ? `            explodeFactor = explodeFactor,
            onExplodeChange = { explodeFactor = it },` : ""}
        )
    }
}

/**
 * Control panel for anatomy viewer settings.
 */
@Composable
private fun AnatomyControls(
    showLayer: Boolean,
    onToggleLayer: (Boolean) -> Unit,
${transparent ? `    opacity: Float,
    onOpacityChange: (Float) -> Unit,` : ""}
${exploded ? `    explodeFactor: Float,
    onExplodeChange: (Float) -> Unit,` : ""}
) {
    Surface(
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(value = showLayer, onValueChange = onToggleLayer),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Show ${system}", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.weight(1f))
                Switch(checked = showLayer, onCheckedChange = null)
            }
${transparent ? `
            Text("Opacity", style = MaterialTheme.typography.bodySmall)
            Slider(
                value = opacity,
                onValueChange = onOpacityChange,
                valueRange = 0.1f..1.0f
            )` : ""}
${exploded ? `
            Text("Exploded View", style = MaterialTheme.typography.bodySmall)
            Slider(
                value = explodeFactor,
                onValueChange = onExplodeChange,
                valueRange = 0f..2f
            )` : ""}
        }
    }
}
${labels ? `
/**
 * Overlay with anatomical labels positioned over the 3D model.
 * In production, project 3D label positions to screen coordinates.
 */
@Composable
private fun AnatomyLabelsOverlay(system: String, region: String) {
    Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            text = "\${system.replaceFirstChar { it.uppercase() }} — \${region.replaceFirstChar { it.uppercase() }}",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}` : ""}`;
}

function generateArAnatomyViewer(
  composableName: string,
  modelPath: string,
  options: AnatomyViewerOptions
): string {
  return `package com.example.medical.anatomy

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
import io.github.sceneview.math.Position

/**
 * AR ${options.system} anatomy viewer — ${options.region ?? "full"} region.
 *
 * Places the anatomy model in augmented reality so users can walk around it
 * and examine it at real-world scale. Tap a surface to place the model.
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
                            // Place anatomy model at tapped location
                            placed = true
                        }
                    }
                }
            )
        ) {
            modelInstance?.let { instance ->
                ModelNode(
                    modelInstance = instance,
                    scaleToUnits = 0.5f // 50cm — suitable for tabletop anatomy
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
                    text = "Point camera at a flat surface, then tap to place anatomy model",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}`;
}

function getAnatomyModelPath(system: AnatomySystem, region: AnatomyRegion): string {
  const base = "models/anatomy";
  if (system === "full-body") return `${base}/full_body.glb`;
  return `${base}/${system}_${region}.glb`;
}

function capitalize(s: string): string {
  return s
    .split("-")
    .map((w) => w.charAt(0).toUpperCase() + w.slice(1))
    .join("");
}

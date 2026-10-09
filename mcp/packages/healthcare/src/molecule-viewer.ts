// ─── Molecular structure 3D viewer code generator ────────────────────────────

export type MoleculeRepresentation =
  | "ball-and-stick"
  | "space-filling"
  | "ribbon"
  | "wireframe"
  | "surface";

export type MoleculeType =
  | "protein"
  | "dna"
  | "rna"
  | "small-molecule"
  | "antibody"
  | "virus"
  | "enzyme"
  | "custom";

export const MOLECULE_REPRESENTATIONS: MoleculeRepresentation[] = [
  "ball-and-stick", "space-filling", "ribbon", "wireframe", "surface",
];

export const MOLECULE_TYPES: MoleculeType[] = [
  "protein", "dna", "rna", "small-molecule", "antibody", "virus", "enzyme", "custom",
];

export interface MoleculeViewerOptions {
  moleculeType: MoleculeType;
  representation?: MoleculeRepresentation;
  pdbId?: string;
  colorScheme?: "element" | "chain" | "secondary-structure" | "hydrophobicity" | "custom";
  showHydrogens?: boolean;
  animate?: boolean;
  ar?: boolean;
}

/**
 * Generates a complete, compilable Kotlin composable for a molecular 3D viewer.
 */
export function generateMoleculeViewer(options: MoleculeViewerOptions): string {
  const {
    moleculeType,
    representation = "ball-and-stick",
    pdbId,
    colorScheme = "element",
    showHydrogens = false,
    animate = false,
    ar = false,
  } = options;

  const composableName = `${capitalize(moleculeType)}MoleculeViewer`;
  const modelPath = getMoleculeModelPath(moleculeType, pdbId);

  if (ar) {
    return generateArMoleculeViewer(composableName, modelPath, options);
  }

  return `package com.example.medical.molecule

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
import io.github.sceneview.math.Rotation

/**
 * ${moleculeType} molecule viewer — ${representation} representation.
 *
 * Displays a 3D molecular structure with orbit controls and pinch-to-zoom.
 * ${pdbId ? `PDB ID: ${pdbId} — download from RCSB PDB and convert to GLB.` : ""}
 *
 * Workflow for molecular models:
 * 1. Download structure from RCSB PDB (https://www.rcsb.org/) as PDB/mmCIF
 * 2. Convert to GLB using PyMOL, ChimeraX, or mol* (Mol-star)
 * 3. Choose representation: ${representation}
 * 4. Export as GLB with vertex colors for ${colorScheme} coloring
 * 5. Place at src/main/assets/${modelPath}
 *
 * Color scheme: ${colorScheme}
 * ${colorScheme === "element" ? "CPK coloring — C=gray, O=red, N=blue, S=yellow, H=white" : ""}
 * ${colorScheme === "chain" ? "Each polypeptide chain gets a distinct color" : ""}
 * ${colorScheme === "secondary-structure" ? "Alpha helix=red, beta sheet=blue, loop=gray" : ""}
 */
@Composable
fun ${composableName}() {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)

    val modelInstance = rememberModelInstance(modelLoader, "${modelPath}")

    var showHydrogens by remember { mutableStateOf(${showHydrogens}) }
${animate ? `    var autoRotate by remember { mutableStateOf(true) }
    var rotationAngle by remember { mutableFloatStateOf(0f) }` : ""}

    // Representation toggle state
    var currentRepresentation by remember { mutableStateOf("${representation}") }

    Column(modifier = Modifier.fillMaxSize()) {
        // ── 3D Molecule Scene ─────────────────────────────────────────────
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
${animate ? `                onFrame = { frameTimeNanos ->
                    if (autoRotate) {
                        rotationAngle += 0.5f
                    }
                }` : ""}
            ) {
                modelInstance?.let { instance ->
                    ModelNode(
                        modelInstance = instance,
                        scaleToUnits = 0.5f,
${animate ? `                        rotation = Rotation(y = rotationAngle),` : ""}
                    )
                }

                // Bright, even lighting for molecular detail
                LightNode(
                    type = LightManager.Type.DIRECTIONAL,
                    apply = {
                        intensity(120_000f)
                        color(1.0f, 1.0f, 1.0f)
                        direction(0f, -1f, -1f)
                    }
                )

                LightNode(
                    type = LightManager.Type.DIRECTIONAL,
                    apply = {
                        intensity(60_000f)
                        color(1.0f, 1.0f, 1.0f)
                        direction(0f, 1f, 1f)
                    }
                )

                // Rim light for depth
                LightNode(
                    type = LightManager.Type.DIRECTIONAL,
                    apply = {
                        intensity(40_000f)
                        color(0.9f, 0.9f, 1.0f)
                        direction(1f, 0f, 0f)
                    }
                )
            }

            // Loading state
            if (modelInstance == null) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }

        // ── Molecule Controls ─────────────────────────────────────────────
        MoleculeControls(
            representation = currentRepresentation,
            onRepresentationChange = { currentRepresentation = it },
            showHydrogens = showHydrogens,
            onToggleHydrogens = { showHydrogens = it },
${animate ? `            autoRotate = autoRotate,
            onToggleRotate = { autoRotate = it },` : ""}
        )
    }
}

/**
 * Control panel for molecule viewer.
 */
@Composable
private fun MoleculeControls(
    representation: String,
    onRepresentationChange: (String) -> Unit,
    showHydrogens: Boolean,
    onToggleHydrogens: (Boolean) -> Unit,
${animate ? `    autoRotate: Boolean,
    onToggleRotate: (Boolean) -> Unit,` : ""}
) {
    Surface(
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Representation selector
            Text("Representation", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("ball-and-stick", "space-filling", "ribbon", "wireframe", "surface").forEach { rep ->
                    FilterChip(
                        selected = representation == rep,
                        onClick = { onRepresentationChange(rep) },
                        label = { Text(rep.replace("-", " ").replaceFirstChar { it.uppercase() }) }
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(value = showHydrogens, onValueChange = onToggleHydrogens),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Show hydrogens", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.weight(1f))
                Switch(checked = showHydrogens, onCheckedChange = null)
            }
${animate ? `
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(value = autoRotate, onValueChange = onToggleRotate),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Auto-rotate", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.weight(1f))
                Switch(checked = autoRotate, onCheckedChange = null)
            }` : ""}
        }
    }
}`;
}

function generateArMoleculeViewer(
  composableName: string,
  modelPath: string,
  options: MoleculeViewerOptions
): string {
  return `package com.example.medical.molecule

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
 * AR ${options.moleculeType} molecule viewer — ${options.representation ?? "ball-and-stick"} representation.
 *
 * Places a molecular structure in augmented reality for immersive examination.
 * Students can walk around the molecule and observe 3D spatial relationships.
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
            onSessionUpdated = { _, frame -> latestFrame = frame },
            onGestureListener = rememberOnGestureListener(
                onSingleTapConfirmed = { e, _ ->
                    // Hit-test the tap against the latest ARCore frame
                    latestFrame?.hitTest(e)?.firstOrNull()?.let { hitResult ->
                        if (!placed && modelInstance != null) {
                            val anchor = hitResult.createAnchor()
                            placed = true
                        }
                    }
                }
            )
        ) {
            modelInstance?.let { instance ->
                ModelNode(
                    modelInstance = instance,
                    scaleToUnits = 0.3f // 30cm — ideal for tabletop molecular models
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
                    text = "Point at a flat surface, then tap to place the molecule",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}`;
}

function getMoleculeModelPath(type: MoleculeType, pdbId?: string): string {
  const base = "models/molecules";
  if (pdbId) return `${base}/${pdbId.toLowerCase()}.glb`;
  return `${base}/${type.replace("-", "_")}.glb`;
}

function capitalize(s: string): string {
  return s
    .split("-")
    .map((w) => w.charAt(0).toUpperCase() + w.slice(1))
    .join("");
}

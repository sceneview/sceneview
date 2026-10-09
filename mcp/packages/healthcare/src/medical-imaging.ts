// ─── Medical imaging (CT/MRI) 3D viewer code generator ──────────────────────

export type ImagingModality = "ct" | "mri" | "pet" | "ultrasound" | "xray";

export type RenderingMode =
  | "volume"
  | "surface"
  | "mip"
  | "slice";

export const IMAGING_MODALITIES: ImagingModality[] = [
  "ct", "mri", "pet", "ultrasound", "xray",
];

export const RENDERING_MODES: RenderingMode[] = [
  "volume", "surface", "mip", "slice",
];

export interface MedicalImagingOptions {
  modality: ImagingModality;
  renderingMode?: RenderingMode;
  bodyRegion?: string;
  windowing?: boolean;
  segmentation?: boolean;
  ar?: boolean;
}

/**
 * Generates a complete, compilable Kotlin composable for medical imaging 3D visualization.
 *
 * Architecture note: DICOM files cannot be loaded directly into SceneView. The recommended
 * pipeline is:
 * 1. Parse DICOM with dcm4che (JVM) or cornerstone.js (web)
 * 2. Generate 3D surface mesh using marching cubes (e.g., VTK, ITK)
 * 3. Export as GLB
 * 4. Display in SceneView
 *
 * For real-time volume rendering, use a dedicated medical imaging library (VTK, 3D Slicer)
 * and composite the result over the SceneView scene.
 */
export function generateMedicalImaging(options: MedicalImagingOptions): string {
  const {
    modality,
    renderingMode = "surface",
    bodyRegion = "chest",
    windowing = true,
    segmentation = false,
    ar = false,
  } = options;

  const composableName = `${capitalize(modality)}${capitalize(bodyRegion)}Viewer`;

  if (ar) {
    return generateArMedicalImaging(composableName, modality, bodyRegion, options);
  }

  return `package com.example.medical.imaging

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
 * ${modality.toUpperCase()} scan 3D viewer — ${bodyRegion} region, ${renderingMode} rendering.
 *
 * ## DICOM-to-3D Pipeline
 *
 * SceneView renders GLB/glTF models. To visualize ${modality.toUpperCase()} scan data:
 *
 * 1. **Parse DICOM**: Use dcm4che (JVM) to read DICOM series
 *    \`\`\`kotlin
 *    // build.gradle.kts
 *    implementation("org.dcm4che:dcm4che-core:5.31.2")
 *    implementation("org.dcm4che:dcm4che-image:5.31.2")
 *    \`\`\`
 *
 * 2. **Generate 3D mesh**: Apply marching cubes to the voxel data
 *    - Use VTK-Android or ITK for isosurface extraction
 *    - Set threshold (e.g., 300 HU for bone in CT, tissue-specific for MRI)
 *
 * 3. **Export GLB**: Convert the mesh to glTF/GLB format
 *    - Use gltf-transform or meshoptimizer for optimization
 *    - Decimate to < 500K triangles for mobile performance
 *
 * 4. **Display in SceneView**: Load the GLB as shown below
 *
 * Model: Place the generated GLB at src/main/assets/models/imaging/${modality}_${bodyRegion}.glb
 */
@Composable
fun ${composableName}() {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)

    // Load the pre-processed 3D mesh from DICOM data
    val modelInstance = rememberModelInstance(
        modelLoader,
        "models/imaging/${modality}_${bodyRegion}.glb"
    )

${windowing ? `    // Windowing controls (simulated via material color/opacity)
    var windowCenter by remember { mutableFloatStateOf(${getDefaultWindowCenter(modality)}f) }
    var windowWidth by remember { mutableFloatStateOf(${getDefaultWindowWidth(modality)}f) }` : ""}
${segmentation ? `    // Segmentation layer visibility
    var showSegmentation by remember { mutableStateOf(true) }
    val segmentationInstance = rememberModelInstance(
        modelLoader,
        "models/imaging/${modality}_${bodyRegion}_segmentation.glb"
    )` : ""}

    Column(modifier = Modifier.fillMaxSize()) {
        // ── 3D Imaging Scene ──────────────────────────────────────────────
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
                // Primary scan mesh
                modelInstance?.let { instance ->
                    ModelNode(
                        modelInstance = instance,
                        scaleToUnits = 0.8f
                    )
                }
${segmentation ? `
                // Segmentation overlay (colored regions: tumor, vessel, etc.)
                if (showSegmentation) {
                    segmentationInstance?.let { instance ->
                        ModelNode(
                            modelInstance = instance,
                            scaleToUnits = 0.8f
                        )
                    }
                }` : ""}

                // Clinical lighting — neutral white, no color cast
                LightNode(
                    type = LightManager.Type.DIRECTIONAL,
                    apply = {
                        intensity(100_000f)
                        color(1.0f, 1.0f, 1.0f)
                        direction(0f, -1f, -0.8f)
                    }
                )

                LightNode(
                    type = LightManager.Type.DIRECTIONAL,
                    apply = {
                        intensity(50_000f)
                        color(1.0f, 1.0f, 1.0f)
                        direction(0f, 1f, 0.3f)
                    }
                )
            }

            // Loading state
            if (modelInstance == null) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(8.dp))
                    Text("Loading ${modality.toUpperCase()} scan...")
                }
            }
        }

        // ── Imaging Controls ──────────────────────────────────────────────
        ImagingControls(
${windowing ? `            windowCenter = windowCenter,
            onWindowCenterChange = { windowCenter = it },
            windowWidth = windowWidth,
            onWindowWidthChange = { windowWidth = it },` : ""}
${segmentation ? `            showSegmentation = showSegmentation,
            onToggleSegmentation = { showSegmentation = it },` : ""}
            modality = "${modality.toUpperCase()}"
        )
    }
}

/**
 * Control panel for medical imaging viewer.
 */
@Composable
private fun ImagingControls(
${windowing ? `    windowCenter: Float,
    onWindowCenterChange: (Float) -> Unit,
    windowWidth: Float,
    onWindowWidthChange: (Float) -> Unit,` : ""}
${segmentation ? `    showSegmentation: Boolean,
    onToggleSegmentation: (Boolean) -> Unit,` : ""}
    modality: String,
) {
    Surface(
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "$modality Scan Controls",
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(Modifier.height(8.dp))
${windowing ? `
            Text("Window Center (HU)", style = MaterialTheme.typography.bodySmall)
            Slider(
                value = windowCenter,
                onValueChange = onWindowCenterChange,
                valueRange = -1000f..3000f
            )
            Text("Window Width (HU)", style = MaterialTheme.typography.bodySmall)
            Slider(
                value = windowWidth,
                onValueChange = onWindowWidthChange,
                valueRange = 1f..4000f
            )` : ""}
${segmentation ? `
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(value = showSegmentation, onValueChange = onToggleSegmentation),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Show segmentation", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.weight(1f))
                Switch(checked = showSegmentation, onCheckedChange = null)
            }` : ""}
        }
    }
}`;
}

function generateArMedicalImaging(
  composableName: string,
  modality: ImagingModality,
  bodyRegion: string,
  options: MedicalImagingOptions
): string {
  return `package com.example.medical.imaging

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
 * AR ${modality.toUpperCase()} scan viewer — ${bodyRegion} region.
 *
 * Places a 3D reconstruction of ${modality.toUpperCase()} scan data in augmented reality.
 * Medical professionals can examine the reconstruction at real-world scale.
 *
 * Model: Place pre-processed GLB at src/main/assets/models/imaging/${modality}_${bodyRegion}.glb
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

    val modelInstance = rememberModelInstance(
        modelLoader,
        "models/imaging/${modality}_${bodyRegion}.glb"
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
                    scaleToUnits = 0.4f
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
                    text = "Point camera at a surface, then tap to place ${modality.toUpperCase()} scan",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}`;
}

function getDefaultWindowCenter(modality: ImagingModality): number {
  switch (modality) {
    case "ct": return 40;   // Soft tissue
    case "mri": return 500;
    case "pet": return 300;
    default: return 128;
  }
}

function getDefaultWindowWidth(modality: ImagingModality): number {
  switch (modality) {
    case "ct": return 400;  // Soft tissue window
    case "mri": return 1000;
    case "pet": return 600;
    default: return 256;
  }
}

function capitalize(s: string): string {
  return s
    .split("-")
    .map((w) => w.charAt(0).toUpperCase() + w.slice(1))
    .join("");
}

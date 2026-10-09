// ─── AR showroom code generator ───────────────────────────────────────────────

export type ShowroomLocation =
  | "driveway"
  | "parking-lot"
  | "garage"
  | "showroom-floor"
  | "street"
  | "outdoor";

export type ShowroomFeature =
  | "walk-around"
  | "open-doors"
  | "color-swap"
  | "measurements"
  | "comparison"
  | "photo-capture"
  | "night-lighting";

export const SHOWROOM_LOCATIONS: ShowroomLocation[] = [
  "driveway", "parking-lot", "garage", "showroom-floor", "street", "outdoor",
];

export const SHOWROOM_FEATURES: ShowroomFeature[] = [
  "walk-around", "open-doors", "color-swap", "measurements",
  "comparison", "photo-capture", "night-lighting",
];

export interface ArShowroomOptions {
  location: ShowroomLocation;
  features?: ShowroomFeature[];
  realScale?: boolean;
  shadows?: boolean;
}

/**
 * Generates a complete, compilable Kotlin composable for an AR car showroom.
 */
export function generateArShowroom(options: ArShowroomOptions): string {
  const {
    location,
    features = ["walk-around", "color-swap"],
    realScale = true,
    shadows = true,
  } = options;

  const composableName = `${capitalize(location)}ArShowroom`;

  return `package com.example.automotive.showroom

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
import androidx.compose.ui.unit.dp
import com.google.android.filament.LightManager
import com.google.ar.core.Frame
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.node.AnchorNode
import io.github.sceneview.node.ModelNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberOnGestureListener
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.node.LightNode
import io.github.sceneview.math.Position

/**
 * AR car showroom — ${location} placement.
 *
 * Places a full-size car model in augmented reality at the ${location}.
 * Features: ${features.join(", ")}.
 * ${realScale ? "Real-world scale (1:1)." : "Scaled model."} ${shadows ? "Ground plane shadows enabled." : ""}
 *
 * Required AndroidManifest.xml:
 *   <uses-permission android:name="android.permission.CAMERA" />
 *   <uses-feature android:name="android.hardware.camera.ar" android:required="true" />
 *   <meta-data android:name="com.google.ar.core" android:value="required" />
 *
 * Gradle: implementation("io.github.sceneview:arsceneview:4.53.0")
 */
@Composable
fun ${composableName}() {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    // Latest ARCore frame — the tap handler hit-tests against it
    var latestFrame by remember { mutableStateOf<Frame?>(null) }

    val carModel = rememberModelInstance(modelLoader, "models/cars/showroom_car.glb")

    var placed by remember { mutableStateOf(false) }
${features.includes("color-swap") ? `
    val carColors = listOf(
        "Pearl White" to Color(0xFFF8F0E8),
        "Obsidian Black" to Color(0xFF1A1A1A),
        "Velocity Red" to Color(0xFFCC0000),
        "Sapphire Blue" to Color(0xFF0055AA),
        "Titanium Grey" to Color(0xFF6B6B6B)
    )
    var selectedColor by remember { mutableIntStateOf(0) }` : ""}
${features.includes("open-doors") ? `    var doorsOpen by remember { mutableStateOf(false) }` : ""}
${features.includes("night-lighting") ? `    var nightMode by remember { mutableStateOf(false) }` : ""}

    Box(modifier = Modifier.fillMaxSize()) {
        ARSceneView(
            modifier = Modifier.fillMaxSize(),
            engine = engine,
            modelLoader = modelLoader,
            planeRenderer = !placed,
            onSessionUpdated = { _, frame ->
                // AR session active — ${location} tracking
                latestFrame = frame
            },
            onGestureListener = rememberOnGestureListener(
                onSingleTapConfirmed = { e, _ ->
                    // Hit-test the tap against the latest ARCore frame
                    latestFrame?.hitTest(e)?.firstOrNull()?.let { hitResult ->
                        if (!placed && carModel != null) {
                            val anchor = hitResult.createAnchor()
                            // Place car at the tapped ${location} location
                            placed = true
                        }
                    }
                }
            )
        ) {
            // Car model at real-world scale
            carModel?.let { instance ->
                ModelNode(
                    modelInstance = instance,
                    scaleToUnits = ${realScale ? "4.5f // Real 1:1 scale ~4.5m" : "2.0f // Table-top scale"}
                )
            }

${shadows ? `            // Ground shadow light
            LightNode(
                type = LightManager.Type.DIRECTIONAL,
                apply = {
                    intensity(100_000f)
                    color(1.0f, 0.98f, 0.95f)
                    direction(0f, -1f, -0.3f)
                }
            )` : ""}
${features.includes("night-lighting") ? `
            // Night mode accent lighting
            if (nightMode) {
                LightNode(
                    type = LightManager.Type.DIRECTIONAL,
                    apply = {
                        intensity(50_000f)
                        color(0.6f, 0.8f, 1.0f)
                        direction(1f, -0.5f, 0f)
                    }
                )
                LightNode(
                    type = LightManager.Type.DIRECTIONAL,
                    apply = {
                        intensity(50_000f)
                        color(1.0f, 0.6f, 0.2f)
                        direction(-1f, -0.5f, 0f)
                    }
                )
            }` : ""}
        }

        // ── Placement instruction ─────────────────────────────────────────
        if (!placed) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(24.dp)
            ) {
                Text(
                    text = "Point camera at your ${location.replace("-", " ")}, then tap to place the car",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        // ── Controls overlay ──────────────────────────────────────────────
        if (placed) {
            ShowroomControls(
${features.includes("color-swap") ? `                colors = carColors,
                selectedColor = selectedColor,
                onColorSelect = { selectedColor = it },` : ""}
${features.includes("open-doors") ? `                doorsOpen = doorsOpen,
                onToggleDoors = { doorsOpen = it },` : ""}
${features.includes("night-lighting") ? `                nightMode = nightMode,
                onToggleNight = { nightMode = it },` : ""}
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }
}

/**
 * Showroom control panel overlay.
 */
@Composable
private fun ShowroomControls(
${features.includes("color-swap") ? `    colors: List<Pair<String, Color>>,
    selectedColor: Int,
    onColorSelect: (Int) -> Unit,` : ""}
${features.includes("open-doors") ? `    doorsOpen: Boolean,
    onToggleDoors: (Boolean) -> Unit,` : ""}
${features.includes("night-lighting") ? `    nightMode: Boolean,
    onToggleNight: (Boolean) -> Unit,` : ""}
    modifier: Modifier = Modifier
) {
    Surface(
        tonalElevation = 2.dp,
        shape = MaterialTheme.shapes.large,
        modifier = modifier.padding(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
${features.includes("color-swap") ? `            Text("Color", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(colors.size) { index ->
                    val (name, color) = colors[index]
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(color)
                            .clickable { onColorSelect(index) }
                    )
                }
            }
            Spacer(Modifier.height(12.dp))` : ""}
${features.includes("open-doors") ? `            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(value = doorsOpen, onValueChange = onToggleDoors),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Open Doors", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.weight(1f))
                Switch(checked = doorsOpen, onCheckedChange = null)
            }` : ""}
${features.includes("night-lighting") ? `            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(value = nightMode, onValueChange = onToggleNight),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Night Mode", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.weight(1f))
                Switch(checked = nightMode, onCheckedChange = null)
            }` : ""}
${features.includes("measurements") ? `            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { /* Toggle measurement mode */ }) {
                Text("Measure")
            }` : ""}
${features.includes("photo-capture") ? `            Spacer(Modifier.height(8.dp))
            Button(onClick = { /* Capture AR screenshot */ }) {
                Text("Take Photo")
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

// ─── HUD overlay code generator ───────────────────────────────────────────────

export type HudElement =
  | "speedometer"
  | "navigation"
  | "alerts"
  | "fuel-gauge"
  | "temperature"
  | "gear-indicator"
  | "turn-signals"
  | "lane-assist";

export type HudStyle =
  | "minimal"
  | "sport"
  | "luxury"
  | "combat"
  | "eco"
  | "retro";

export const HUD_ELEMENTS: HudElement[] = [
  "speedometer", "navigation", "alerts", "fuel-gauge",
  "temperature", "gear-indicator", "turn-signals", "lane-assist",
];

export const HUD_STYLES: HudStyle[] = [
  "minimal", "sport", "luxury", "combat", "eco", "retro",
];

export interface HudOverlayOptions {
  elements: HudElement[];
  style?: HudStyle;
  nightMode?: boolean;
  units?: "metric" | "imperial";
  ar?: boolean;
}

/**
 * Generates a complete, compilable Kotlin composable for a heads-up display overlay
 * rendered via ViewNode in a SceneView Scene.
 */
export function generateHudOverlay(options: HudOverlayOptions): string {
  const {
    elements,
    style = "minimal",
    nightMode = false,
    units = "metric",
    ar = false,
  } = options;

  const composableName = `${capitalize(style)}HudOverlay`;
  const speedUnit = units === "metric" ? "km/h" : "mph";
  const tempUnit = units === "metric" ? "°C" : "°F";

  if (ar) {
    return generateArHud(composableName, options);
  }

  return `package com.example.automotive.hud

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.sceneview.SceneView
import io.github.sceneview.node.ViewNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberCollisionSystem
import io.github.sceneview.rememberViewNodeManager
import io.github.sceneview.node.LightNode
import io.github.sceneview.math.Position

/**
 * ${capitalize(style)} heads-up display overlay — ${elements.join(", ")}.
 *
 * Renders HUD elements via ViewNode composables inside a SceneView Scene,
 * allowing the overlay to be placed in 3D space (e.g., on a windshield model).
 * ${nightMode ? "Night mode enabled — green-on-black color scheme." : "Standard color scheme."}
 * Units: ${units} (speed in ${speedUnit}, temp in ${tempUnit}).
 *
 * ViewNode renders Compose UI as a texture on a 3D plane — ideal for HUD overlays
 * that need to exist in 3D space alongside car models.
 */
@Composable
fun ${composableName}() {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val collisionSystem = rememberCollisionSystem(engine)
    // Off-screen window hosting the ViewNode's Compose content — the same instance goes to
    // SceneView (viewNodeWindowManager) and to the ViewNode (windowManager).
    val windowManager = rememberViewNodeManager()

    // Simulated vehicle data
    var speed by remember { mutableFloatStateOf(0f) }
${elements.includes("fuel-gauge") ? `    var fuelLevel by remember { mutableFloatStateOf(0.72f) }` : ""}
${elements.includes("temperature") ? `    var engineTemp by remember { mutableFloatStateOf(${units === "metric" ? "90f" : "194f"}) }` : ""}
${elements.includes("gear-indicator") ? `    var currentGear by remember { mutableStateOf("D") }` : ""}
${elements.includes("alerts") ? `    var alerts by remember { mutableStateOf(listOf<String>()) }` : ""}

    val hudColor = ${nightMode ? `Color(0xFF00FF41)` : `Color(0xFF00BFFF)`}
    val bgColor = ${nightMode ? `Color(0xFF0A0A0A)` : `Color(0x80000000)`}

    Box(modifier = Modifier.fillMaxSize()) {
        SceneView(
            modifier = Modifier.fillMaxSize(),
            engine = engine,
            modelLoader = modelLoader,
            collisionSystem = collisionSystem,
            viewNodeWindowManager = windowManager,
            onFrame = { frameTimeNanos ->
                // Simulate speed changes for demo
                speed = (speed + 0.1f).coerceIn(0f, ${units === "metric" ? "220f" : "140f"})
            }
        ) {
            // HUD rendered as a ViewNode in 3D space
            ViewNode(
                windowManager = windowManager,
                position = Position(0f, 0f, -2f)
            ) {
                HudContent(
                    speed = speed,
                    speedUnit = "${speedUnit}",
${elements.includes("fuel-gauge") ? `                    fuelLevel = fuelLevel,` : ""}
${elements.includes("temperature") ? `                    engineTemp = engineTemp,
                    tempUnit = "${tempUnit}",` : ""}
${elements.includes("gear-indicator") ? `                    currentGear = currentGear,` : ""}
${elements.includes("alerts") ? `                    alerts = alerts,` : ""}
                    hudColor = hudColor,
                    bgColor = bgColor
                )
            }

            // Ambient lighting
            LightNode(
                apply = {
                    intensity(50_000f)
                    color(0.9f, 0.95f, 1.0f)
                    direction(0f, -1f, -1f)
                }
            )
        }
    }
}

/**
 * HUD content composable — rendered as a texture on ViewNode.
 */
@Composable
private fun HudContent(
    speed: Float,
    speedUnit: String,
${elements.includes("fuel-gauge") ? `    fuelLevel: Float,` : ""}
${elements.includes("temperature") ? `    engineTemp: Float,
    tempUnit: String,` : ""}
${elements.includes("gear-indicator") ? `    currentGear: String,` : ""}
${elements.includes("alerts") ? `    alerts: List<String>,` : ""}
    hudColor: Color,
    bgColor: Color
) {
    // The ViewNode window is WRAP_CONTENT: fillMaxWidth() here would resolve to the whole
    // display width and put a metres-wide quad in the scene, so the HUD gets an explicit width.
    Column(
        modifier = Modifier
            .width(320.dp)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
${elements.includes("speedometer") ? `        // ── Speedometer ────────────────────────────────────────────
        Text(
            text = "\${speed.toInt()}",
            fontSize = 72.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = hudColor
        )
        Text(
            text = speedUnit,
            fontSize = 18.sp,
            color = hudColor.copy(alpha = 0.7f)
        )
        Spacer(Modifier.height(16.dp))` : ""}
${elements.includes("gear-indicator") ? `        // ── Gear indicator ─────────────────────────────────────────
        Text(
            text = currentGear,
            fontSize = 36.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = hudColor
        )
        Spacer(Modifier.height(8.dp))` : ""}
${elements.includes("navigation") ? `        // ── Navigation ────────────────────────────────────────────
        Surface(
            color = bgColor,
            shape = MaterialTheme.shapes.small
        ) {
            Text(
                text = "→ 500m — Turn right on Main St",
                modifier = Modifier.padding(12.dp),
                color = hudColor,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace
            )
        }
        Spacer(Modifier.height(8.dp))` : ""}
${elements.includes("fuel-gauge") ? `        // ── Fuel gauge ─────────────────────────────────────────────
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("FUEL", color = hudColor, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.width(8.dp))
            LinearProgressIndicator(
                progress = { fuelLevel },
                modifier = Modifier.width(120.dp),
                color = if (fuelLevel < 0.2f) Color.Red else hudColor,
                trackColor = bgColor
            )
            Spacer(Modifier.width(8.dp))
            Text("\${(fuelLevel * 100).toInt()}%", color = hudColor, fontSize = 12.sp)
        }
        Spacer(Modifier.height(8.dp))` : ""}
${elements.includes("temperature") ? `        // ── Engine temperature ──────────────────────────────────────
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("TEMP", color = hudColor, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.width(8.dp))
            Text(
                "\${engineTemp.toInt()}$tempUnit",
                color = if (engineTemp > ${units === "metric" ? "110" : "230"}) Color.Red else hudColor,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace
            )
        }
        Spacer(Modifier.height(8.dp))` : ""}
${elements.includes("alerts") ? `        // ── Alerts ─────────────────────────────────────────────────
        alerts.forEach { alert ->
            Surface(
                color = Color(0x80FF0000),
                shape = MaterialTheme.shapes.small
            ) {
                Text(
                    text = "⚠ \$alert",
                    modifier = Modifier.padding(8.dp),
                    color = Color.White,
                    fontSize = 12.sp
                )
            }
            Spacer(Modifier.height(4.dp))
        }` : ""}
${elements.includes("turn-signals") ? `        // ── Turn signals ──────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("◄", color = hudColor.copy(alpha = 0.3f), fontSize = 24.sp)
            Text("►", color = hudColor.copy(alpha = 0.3f), fontSize = 24.sp)
        }` : ""}
${elements.includes("lane-assist") ? `        // ── Lane assist ──────────────────────────────────────────
        Spacer(Modifier.height(8.dp))
        Canvas(modifier = Modifier.width(200.dp).height(40.dp)) {
            val stroke = Stroke(width = 2f)
            drawLine(hudColor, Offset(20f, size.height), Offset(80f, 0f), strokeWidth = 2f)
            drawLine(hudColor, Offset(size.width - 20f, size.height), Offset(size.width - 80f, 0f), strokeWidth = 2f)
        }` : ""}
    }
}`;
}

function generateArHud(
  composableName: string,
  options: HudOverlayOptions
): string {
  const { elements, style = "minimal", nightMode = false, units = "metric" } = options;
  const speedUnit = units === "metric" ? "km/h" : "mph";

  return `package com.example.automotive.hud

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.node.ViewNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberCollisionSystem
import io.github.sceneview.rememberViewNodeManager
import io.github.sceneview.math.Position

/**
 * AR ${capitalize(style)} HUD overlay.
 *
 * Renders heads-up display elements in augmented reality,
 * projected in 3D space visible through the camera feed.
 *
 * Required AndroidManifest.xml:
 *   <uses-permission android:name="android.permission.CAMERA" />
 *   <uses-feature android:name="android.hardware.camera.ar" android:required="true" />
 *   <meta-data android:name="com.google.ar.core" android:value="required" />
 *
 * Gradle: implementation("io.github.sceneview:arsceneview:4.0.9")
 */
@Composable
fun ${composableName}AR() {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val collisionSystem = rememberCollisionSystem(engine)
    // Off-screen window hosting the ViewNode's Compose content — the same instance goes to
    // ARSceneView (viewNodeWindowManager) and to the ViewNode (windowManager).
    val windowManager = rememberViewNodeManager()

    var speed by remember { mutableFloatStateOf(60f) }
    val hudColor = ${nightMode ? `Color(0xFF00FF41)` : `Color(0xFF00BFFF)`}

    Box(modifier = Modifier.fillMaxSize()) {
        ARSceneView(
            modifier = Modifier.fillMaxSize(),
            engine = engine,
            modelLoader = modelLoader,
            collisionSystem = collisionSystem,
            viewNodeWindowManager = windowManager,
            planeRenderer = false,
            onSessionUpdated = { session, frame ->
                // AR session active
            }
        ) {
            // HUD floating in AR space
            ViewNode(
                windowManager = windowManager,
                position = Position(0f, 0f, -1.5f)
            ) {
                // Explicit width: the ViewNode window is WRAP_CONTENT.
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.width(320.dp).padding(16.dp)
                ) {
                    Text(
                        text = "\${speed.toInt()} ${speedUnit}",
                        fontSize = 48.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = hudColor
                    )
${elements.includes("navigation") ? `                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "→ 500m — Turn right",
                        color = hudColor,
                        fontSize = 16.sp,
                        fontFamily = FontFamily.Monospace
                    )` : ""}
                }
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

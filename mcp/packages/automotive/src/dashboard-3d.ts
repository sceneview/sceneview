// ─── 3D instrument cluster code generator ─────────────────────────────────────

export type GaugeType =
  | "speedometer"
  | "tachometer"
  | "fuel"
  | "temperature"
  | "oil-pressure"
  | "battery"
  | "boost"
  | "odometer";

export type DashboardTheme =
  | "classic"
  | "digital"
  | "sport"
  | "luxury"
  | "electric"
  | "retro";

export const GAUGE_TYPES: GaugeType[] = [
  "speedometer", "tachometer", "fuel", "temperature",
  "oil-pressure", "battery", "boost", "odometer",
];

export const DASHBOARD_THEMES: DashboardTheme[] = [
  "classic", "digital", "sport", "luxury", "electric", "retro",
];

export interface Dashboard3dOptions {
  gauges: GaugeType[];
  theme?: DashboardTheme;
  animated?: boolean;
  interactive?: boolean;
  ar?: boolean;
}

/**
 * Generates a complete, compilable Kotlin composable for a 3D instrument cluster.
 */
export function generateDashboard3d(options: Dashboard3dOptions): string {
  const {
    gauges,
    theme = "classic",
    animated = true,
    interactive = true,
    ar = false,
  } = options;

  const composableName = `${capitalize(theme)}Dashboard3D`;
  // Only these two gauges are drawn as ViewNode faces; the window manager is emitted with them.
  const hasGaugeFaces = gauges.includes("speedometer") || gauges.includes("tachometer");

  if (ar) {
    return generateArDashboard(composableName, options);
  }

  return `package com.example.automotive.dashboard

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.filament.LightManager
import io.github.sceneview.SceneView
import io.github.sceneview.node.ModelNode
import io.github.sceneview.node.ViewNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberViewNodeManager
import io.github.sceneview.node.LightNode
import io.github.sceneview.math.Position
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * ${capitalize(theme)} 3D instrument cluster with animated gauges.
 *
 * Gauges: ${gauges.join(", ")}.
 * ${animated ? "Animated needle/bar transitions. " : ""}${interactive ? "Interactive — tap gauges for detail view." : ""}
 *
 * The dashboard housing is a 3D model rendered in SceneView, while gauge faces
 * are rendered via ViewNode composables for crisp text and smooth animations.
 *
 * Model: Place dashboard housing at src/main/assets/models/dashboard/${theme}_cluster.glb
 */
@Composable
fun ${composableName}() {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)
${hasGaugeFaces ? `    // Off-screen window hosting the gauge faces — the same instance goes to SceneView
    // (viewNodeWindowManager) and to every ViewNode (windowManager).
    val windowManager = rememberViewNodeManager()` : ""}

    // Dashboard housing model
    val dashboardModel = rememberModelInstance(
        modelLoader, "models/dashboard/${theme}_cluster.glb"
    )

    // Simulated vehicle data
${gauges.includes("speedometer") ? `    var speed by remember { mutableFloatStateOf(0f) }` : ""}
${gauges.includes("tachometer") ? `    var rpm by remember { mutableFloatStateOf(800f) }` : ""}
${gauges.includes("fuel") ? `    var fuelLevel by remember { mutableFloatStateOf(0.65f) }` : ""}
${gauges.includes("temperature") ? `    var coolantTemp by remember { mutableFloatStateOf(85f) }` : ""}
${gauges.includes("oil-pressure") ? `    var oilPressure by remember { mutableFloatStateOf(3.5f) }` : ""}
${gauges.includes("battery") ? `    var batteryVoltage by remember { mutableFloatStateOf(12.6f) }` : ""}
${gauges.includes("boost") ? `    var boostPressure by remember { mutableFloatStateOf(0f) }` : ""}
${gauges.includes("odometer") ? `    var odometer by remember { mutableFloatStateOf(45_230f) }` : ""}

${animated ? `    // Animate speed sweep for demo
    val animatedSpeed by animateFloatAsState(
        targetValue = ${gauges.includes("speedometer") ? "speed" : "0f"},
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 100f),
        label = "speedAnimation"
    )
    val animatedRpm by animateFloatAsState(
        targetValue = ${gauges.includes("tachometer") ? "rpm" : "0f"},
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 120f),
        label = "rpmAnimation"
    )` : ""}

    Column(modifier = Modifier.fillMaxSize()) {
        // ── 3D Scene with dashboard ───────────────────────────────────────
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            SceneView(
                modifier = Modifier.fillMaxSize(),
                engine = engine,
                modelLoader = modelLoader,
${hasGaugeFaces ? `                viewNodeWindowManager = windowManager,` : ""}
                environment = environmentLoader.createHDREnvironment(
                    assetFileLocation = "environments/cockpit_hdr.ktx"
                )!!,
                onFrame = { frameTimeNanos ->
${animated && gauges.includes("speedometer") ? `                    // Demo: sweep speed up
                    if (speed < 120f) speed += 0.2f` : "                    // Frame update"}
                }
            ) {
                // Dashboard housing
                dashboardModel?.let { instance ->
                    ModelNode(
                        modelInstance = instance,
                        scaleToUnits = 1.5f
                    )
                }

                // Gauge faces as ViewNodes in 3D space
${gauges.includes("speedometer") ? `                ViewNode(
                    windowManager = windowManager,
                    position = Position(-0.3f, 0.1f, -0.5f)
                ) {
                    GaugeView(
                        label = "SPEED",
                        value = ${animated ? "animatedSpeed" : "speed"},
                        maxValue = 260f,
                        unit = "km/h",
                        theme = "${theme}"
                    )
                }` : ""}
${gauges.includes("tachometer") ? `
                ViewNode(
                    windowManager = windowManager,
                    position = Position(0.3f, 0.1f, -0.5f)
                ) {
                    GaugeView(
                        label = "RPM",
                        value = ${animated ? "animatedRpm" : "rpm"},
                        maxValue = 8000f,
                        unit = "x1000",
                        theme = "${theme}",
                        redZone = 6500f
                    )
                }` : ""}

                // Dashboard ambient lighting
                LightNode(
                    type = LightManager.Type.DIRECTIONAL,
                    apply = {
                        intensity(20_000f)
                        color(0.95f, 0.9f, 0.85f)
                        direction(0f, -1f, -0.3f)
                    }
                )
            }

            // Loading indicator
            if (dashboardModel == null) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }

        // ── Gauge controls ────────────────────────────────────────────────
${interactive ? `        DashboardControls(
${gauges.includes("speedometer") ? `            speed = speed,
            onSpeedChange = { speed = it },` : ""}
${gauges.includes("tachometer") ? `            rpm = rpm,
            onRpmChange = { rpm = it },` : ""}
        )` : ""}
    }
}

/**
 * Circular gauge view composable — rendered as a ViewNode texture.
 */
@Composable
private fun GaugeView(
    label: String,
    value: Float,
    maxValue: Float,
    unit: String,
    theme: String,
    redZone: Float = maxValue * 0.85f
) {
    val gaugeColor = when (theme) {
        "sport" -> Color(0xFFFF4444)
        "luxury" -> Color(0xFFD4AF37)
        "electric" -> Color(0xFF00BFFF)
        "retro" -> Color(0xFFFF8C00)
        else -> Color.White
    }

    // Explicit size, identical for every gauge: the ViewNode window is WRAP_CONTENT, and one
    // window manager sizes all its ViewNodes to the largest content.
    Box(
        modifier = Modifier.size(160.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(8.dp)) {
            val sweepAngle = 270f * (value / maxValue).coerceIn(0f, 1f)
            val startAngle = 135f

            // Background arc
            drawArc(
                color = Color.DarkGray,
                startAngle = startAngle,
                sweepAngle = 270f,
                useCenter = false,
                style = Stroke(width = 8f, cap = StrokeCap.Round)
            )

            // Value arc
            drawArc(
                color = if (value >= redZone) Color.Red else gaugeColor,
                startAngle = startAngle,
                sweepAngle = sweepAngle,
                useCenter = false,
                style = Stroke(width = 8f, cap = StrokeCap.Round)
            )
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = value.toInt().toString(),
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = gaugeColor
            )
            Text(
                text = unit,
                fontSize = 10.sp,
                color = gaugeColor.copy(alpha = 0.7f)
            )
            Text(
                text = label,
                fontSize = 10.sp,
                color = Color.Gray
            )
        }
    }
}

${interactive ? `/**
 * Dashboard control panel for demo input.
 */
@Composable
private fun DashboardControls(
${gauges.includes("speedometer") ? `    speed: Float,
    onSpeedChange: (Float) -> Unit,` : ""}
${gauges.includes("tachometer") ? `    rpm: Float,
    onRpmChange: (Float) -> Unit,` : ""}
) {
    Surface(
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
${gauges.includes("speedometer") ? `            Text("Speed: \${speed.toInt()} km/h", style = MaterialTheme.typography.bodySmall)
            Slider(
                value = speed,
                onValueChange = onSpeedChange,
                valueRange = 0f..260f
            )` : ""}
${gauges.includes("tachometer") ? `            Text("RPM: \${rpm.toInt()}", style = MaterialTheme.typography.bodySmall)
            Slider(
                value = rpm,
                onValueChange = onRpmChange,
                valueRange = 0f..8000f
            )` : ""}
        }
    }
}` : ""}`;
}

function generateArDashboard(
  composableName: string,
  options: Dashboard3dOptions
): string {
  return `package com.example.automotive.dashboard

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
import com.google.ar.core.Frame
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.node.ModelNode
import io.github.sceneview.node.ViewNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberOnGestureListener
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.math.Position

/**
 * AR ${capitalize(options.theme ?? "classic")} 3D instrument cluster.
 *
 * Places an interactive dashboard in augmented reality.
 * Tap a surface to place the instrument cluster.
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

    val dashboardModel = rememberModelInstance(
        modelLoader, "models/dashboard/${options.theme ?? "classic"}_cluster.glb"
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
                        if (!placed && dashboardModel != null) {
                            val anchor = hitResult.createAnchor()
                            placed = true
                        }
                    }
                }
            )
        ) {
            dashboardModel?.let { instance ->
                ModelNode(
                    modelInstance = instance,
                    scaleToUnits = 0.8f
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
                    text = "Point camera at a flat surface, then tap to place the dashboard",
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

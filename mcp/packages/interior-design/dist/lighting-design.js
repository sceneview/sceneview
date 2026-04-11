// ─── Interior lighting design code generator ──────────────────────────────────
export const LIGHT_TYPES = [
    "ambient", "spot", "accent", "pendant", "recessed", "track",
    "sconce", "floor-lamp", "table-lamp", "chandelier", "strip-led", "natural",
];
export const COLOR_TEMPERATURES = [
    "warm-white", "neutral-white", "cool-white", "daylight", "candlelight",
];
const TEMPERATURE_COLORS = {
    "candlelight": { r: 1.0, g: 0.68, b: 0.26, kelvin: 1900 },
    "warm-white": { r: 1.0, g: 0.87, b: 0.68, kelvin: 2700 },
    "neutral-white": { r: 1.0, g: 0.95, b: 0.88, kelvin: 4000 },
    "cool-white": { r: 0.95, g: 0.97, b: 1.0, kelvin: 5000 },
    "daylight": { r: 0.90, g: 0.93, b: 1.0, kelvin: 6500 },
};
/**
 * Generates a complete, compilable Kotlin composable for interior lighting design.
 */
export function generateLightingDesign(options) {
    const { lights, colorTemperature = "warm-white", dimmable = true, roomModel = "models/room/interior_scene.glb", showShadows = true, ar = false, } = options;
    const tempColor = TEMPERATURE_COLORS[colorTemperature];
    const composableName = "LightingDesignScene";
    if (ar) {
        return generateArLightingDesign(composableName, roomModel, options);
    }
    const lightNodes = lights
        .map((light, i) => generateLightNodeCode(light, tempColor, i))
        .join("\n\n");
    return `package com.example.interior.lighting

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.sceneview.SceneView
import io.github.sceneview.node.ModelNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberCollisionSystem
import io.github.sceneview.node.LightNode
import io.github.sceneview.math.Position

/**
 * Interior Lighting Design — ${lights.join(", ")} lights.
 *
 * Color temperature: ${colorTemperature} (~${tempColor.kelvin}K).
 * ${dimmable ? "Includes per-light dimming sliders." : "Fixed intensity."}
 * ${showShadows ? "Shadow casting enabled." : "Shadows disabled."}
 *
 * Model: src/main/assets/${roomModel}
 *
 * Gradle: implementation("io.github.sceneview:sceneview:3.6.0")
 */
@Composable
fun ${composableName}() {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)
    val collisionSystem = rememberCollisionSystem(engine)

    val modelInstance = rememberModelInstance(modelLoader, "${roomModel}")

${dimmable ? lights.map((_, i) => `    var light${i}Intensity by remember { mutableFloatStateOf(1.0f) }`).join("\n") : ""}

    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            SceneView(
                modifier = Modifier.fillMaxSize(),
                engine = engine,
                modelLoader = modelLoader,
                collisionSystem = collisionSystem,
                environment = environmentLoader.createHDREnvironment(
                    assetFileLocation = "environments/interior_hdr.ktx"
                )!!,
            ) {
                // Room model
                modelInstance?.let { instance ->
                    ModelNode(
                        modelInstance = instance,
                        scaleToUnits = 2.0f
                    )
                }

                // ── Light sources ─────────────────────────────────────────
${lightNodes}
            }

            if (modelInstance == null) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }

${dimmable ? `        // ── Dimmer Controls ─────────────────────────────────────────
        Surface(
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
${lights.map((light, i) => `                Text("${capitalize(light)}", style = MaterialTheme.typography.bodySmall)
                Slider(
                    value = light${i}Intensity,
                    onValueChange = { light${i}Intensity = it },
                    valueRange = 0f..2f
                )`).join("\n")}
            }
        }` : ""}
    }
}`;
}
function generateLightNodeCode(light, color, index) {
    const positions = {
        ambient: "0f, 2.5f, 0f",
        spot: "0f, 2.4f, 0f",
        accent: "1.5f, 1.5f, -1.5f",
        pendant: "0f, 2.2f, 0f",
        recessed: "0f, 2.6f, 0f",
        track: "-1f, 2.4f, 0f",
        sconce: "2f, 1.8f, -2f",
        "floor-lamp": "1.5f, 1.4f, 1.5f",
        "table-lamp": "0.8f, 0.9f, -0.5f",
        chandelier: "0f, 2.0f, 0f",
        "strip-led": "0f, 2.5f, -2f",
        natural: "-2f, 2f, 2f",
    };
    const intensities = {
        ambient: 40_000,
        spot: 120_000,
        accent: 60_000,
        pendant: 80_000,
        recessed: 100_000,
        track: 90_000,
        sconce: 50_000,
        "floor-lamp": 60_000,
        "table-lamp": 40_000,
        chandelier: 150_000,
        "strip-led": 30_000,
        natural: 80_000,
    };
    const pos = positions[light] ?? "0f, 2f, 0f";
    const intensity = intensities[light] ?? 80_000;
    return `                // ${capitalize(light)} light
                LightNode(
                    apply = {
                        intensity(${intensity}f)
                        color(${color.r}f, ${color.g}f, ${color.b}f)
                        direction(0f, -1f, 0f)
                    }
                )`;
}
function generateArLightingDesign(composableName, roomModel, options) {
    const { lights, colorTemperature = "warm-white" } = options;
    const tempColor = TEMPERATURE_COLORS[colorTemperature];
    return `package com.example.interior.lighting

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.node.ModelNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberCollisionSystem
import io.github.sceneview.node.LightNode

/**
 * AR Lighting Design — preview lighting in your real room.
 *
 * Lights: ${lights.join(", ")}. Color temperature: ${colorTemperature} (~${tempColor.kelvin}K).
 * Tap a surface to place virtual light fixtures, then adjust intensity.
 *
 * Required AndroidManifest.xml:
 *   <uses-permission android:name="android.permission.CAMERA" />
 *   <uses-feature android:name="android.hardware.camera.ar" android:required="true" />
 *   <meta-data android:name="com.google.ar.core" android:value="required" />
 *
 * Gradle: implementation("io.github.sceneview:arsceneview:3.6.0")
 */
@Composable
fun ${composableName}AR() {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val collisionSystem = rememberCollisionSystem(engine)

    var placed by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        ARSceneView(
            modifier = Modifier.fillMaxSize(),
            engine = engine,
            modelLoader = modelLoader,
            collisionSystem = collisionSystem,
            planeRenderer = true,
            onSessionUpdated = { session, frame -> },
            onTapAR = { hitResult ->
                if (!placed) {
                    val anchor = hitResult.createAnchor()
                    placed = true
                }
            }
        ) {
${lights.map((light) => `            // ${capitalize(light)} light
            LightNode(
                apply = {
                    intensity(80_000f)
                    color(${tempColor.r}f, ${tempColor.g}f, ${tempColor.b}f)
                    direction(0f, -1f, 0f)
                }
            )`).join("\n\n")}
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
                    text = "Tap a surface to preview lighting setup",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}`;
}
function capitalize(s) {
    return s
        .split("-")
        .map((w) => w.charAt(0).toUpperCase() + w.slice(1))
        .join("");
}

// ─── Interior lighting design code generator ──────────────────────────────────

export type LightType =
  | "ambient"
  | "spot"
  | "accent"
  | "pendant"
  | "recessed"
  | "track"
  | "sconce"
  | "floor-lamp"
  | "table-lamp"
  | "chandelier"
  | "strip-led"
  | "natural";

export type ColorTemperature =
  | "warm-white"
  | "neutral-white"
  | "cool-white"
  | "daylight"
  | "candlelight";

export const LIGHT_TYPES: LightType[] = [
  "ambient", "spot", "accent", "pendant", "recessed", "track",
  "sconce", "floor-lamp", "table-lamp", "chandelier", "strip-led", "natural",
];

export const COLOR_TEMPERATURES: ColorTemperature[] = [
  "warm-white", "neutral-white", "cool-white", "daylight", "candlelight",
];

export interface LightingDesignOptions {
  lights: LightType[];
  colorTemperature?: ColorTemperature;
  dimmable?: boolean;
  roomModel?: string;
  showShadows?: boolean;
  ar?: boolean;
}

const TEMPERATURE_COLORS: Record<ColorTemperature, { r: number; g: number; b: number; kelvin: number }> = {
  "candlelight":    { r: 1.0, g: 0.68, b: 0.26, kelvin: 1900 },
  "warm-white":     { r: 1.0, g: 0.87, b: 0.68, kelvin: 2700 },
  "neutral-white":  { r: 1.0, g: 0.95, b: 0.88, kelvin: 4000 },
  "cool-white":     { r: 0.95, g: 0.97, b: 1.0, kelvin: 5000 },
  "daylight":       { r: 0.90, g: 0.93, b: 1.0, kelvin: 6500 },
};

/**
 * Generates a complete, compilable Kotlin composable for interior lighting design.
 */
export function generateLightingDesign(options: LightingDesignOptions): string {
  const {
    lights,
    colorTemperature = "warm-white",
    dimmable = true,
    roomModel = "models/room/interior_scene.glb",
    showShadows = true,
    ar = false,
  } = options;

  const tempColor = TEMPERATURE_COLORS[colorTemperature];
  const composableName = "LightingDesignScene";

  if (ar) {
    return generateArLightingDesign(composableName, options);
  }

  const lightNodes = lights
    .map((light) => generateLightNodeCode(light, tempColor, true, "                    "))
    .join("\n\n");

  return `package com.example.interior.lighting

import androidx.compose.foundation.layout.*
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
import io.github.sceneview.model.restPoseBoundingBox
import kotlin.math.PI

/**
 * Interior Lighting Design — ${lights.join(", ")} lights.
 *
 * Color temperature: ${colorTemperature} (~${tempColor.kelvin}K).
 * ${dimmable ? "Includes per-light dimming sliders." : "Fixed intensity."}
 * ${showShadows ? "Shadow casting enabled." : "Shadows disabled."}
 *
 * Model: src/main/assets/${roomModel}
 *
 * Gradle: implementation("io.github.sceneview:sceneview:4.69.4")
 */
@Composable
fun ${composableName}() {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)

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
                environment = environmentLoader.createHDREnvironment(
                    assetFileLocation = "environments/interior_hdr.ktx"
                )!!,
            ) {
                // Match the fixtures to the room's scaled, bottom-centered bounding box.
                modelInstance?.let { instance ->
                    val roomSize = remember(instance) {
                        // Use the same skinning-corrected bounds as ModelNode.scaleToUnits.
                        val halfExtent = instance.asset.restPoseBoundingBox.halfExtent
                        val largestDimension = 2f * maxOf(halfExtent[0], halfExtent[1], halfExtent[2])
                        val roomScale = if (largestDimension > 0f) 2f / largestDimension else 1f
                        Position(
                            halfExtent[0] * 2f * roomScale,
                            halfExtent[1] * 2f * roomScale,
                            halfExtent[2] * 2f * roomScale
                        )
                    }
                    val roomFalloff = maxOf(roomSize.x, roomSize.y, roomSize.z) * 1.25f
                    ModelNode(
                        modelInstance = instance,
                        scaleToUnits = 2.0f,
                        centerOrigin = Position(0f, -1f, 0f)
                    )

                    // ── Light sources ─────────────────────────────────────
${lightNodes}
                }
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

function generateLightNodeCode(
  light: LightType,
  color: { r: number; g: number; b: number },
  scaledRoom: boolean,
  indent = "                "
): string {
  // Filament has no ambient or area light type; approximate those fixtures with points.
  const types: Record<LightType, "DIRECTIONAL" | "POINT" | "SPOT"> = {
    ambient: "POINT",
    spot: "SPOT",
    accent: "SPOT",
    pendant: "POINT",
    recessed: "SPOT",
    track: "SPOT",
    sconce: "POINT",
    "floor-lamp": "POINT",
    "table-lamp": "POINT",
    chandelier: "POINT",
    "strip-led": "POINT",
    natural: "DIRECTIONAL",
  };

  // Fixture offsets in a reference 4 x 2.7 x 4 m room, relative to its floor center.
  const positions: Record<LightType, [number, number, number]> = {
    ambient: [0, 2.5, 0],
    spot: [0, 2.4, 0],
    accent: [1.5, 1.5, -1.5],
    pendant: [0, 2.2, 0],
    recessed: [0, 2.6, 0],
    track: [-1, 2.4, 0],
    sconce: [1.9, 1.8, -1.9],
    "floor-lamp": [1.5, 1.4, 1.5],
    "table-lamp": [0.8, 0.9, -0.5],
    chandelier: [0, 2, 0],
    "strip-led": [0, 2.5, -1.9],
    natural: [0, 0, 0],
  };

  // Preview illuminance below each fixture, before finite-radius attenuation.
  // One tenth of the legacy presets: 3,000–15,000 lux, around SceneView's 10,000-lux
  // default main light. Preserve fixture ratios; these are previews, not bulb ratings.
  const illuminanceLux: Record<LightType, number> = {
    ambient: 4_000,
    spot: 12_000,
    accent: 6_000,
    pendant: 8_000,
    recessed: 10_000,
    track: 9_000,
    sconce: 5_000,
    "floor-lamp": 6_000,
    "table-lamp": 4_000,
    chandelier: 15_000,
    "strip-led": 3_000,
    natural: 8_000,
  };

  const dimensions = [4, 2.7, 4];
  const axes = ["x", "y", "z"];
  const coordinates = positions[light].map((value, axis) =>
    scaledRoom && value !== 0
      ? `roomSize.${axes[axis]} * (${value}f / ${dimensions[axis]}f)`
      : `${value}f`,
  );
  const pos = coordinates.join(", ");
  const intensity = illuminanceLux[light];
  const type = types[light];

  // Filament: directional intensity is lux; POINT and non-focused SPOT take lumens.
  // Phi = E * h^2 * 4*pi (point) or E * h^2 * pi (SPOT's cone-independent mode).
  // https://google.github.io/filament/main/filament.html#lighting/directlighting/punctuallights
  const lines = [
    `// ${capitalize(light)} light`,
    "LightNode(",
    `    type = LightManager.Type.${type},`,
    ...(type !== "DIRECTIONAL" ? [`    position = Position(${pos}),`] : []),
    "    apply = {",
    ...(type === "DIRECTIONAL"
      ? [`        intensity(${intensity}f) // lux`]
      : [
          `        val height = ${coordinates[1]}`,
          `        // Convert preview lux to lumens at this fixture's floor distance.`,
          `        intensity(${intensity}f * ${type === "POINT" ? "4f * " : ""}PI.toFloat() * height * height)`,
        ]),
    `        color(${color.r}f, ${color.g}f, ${color.b}f)`,
    ...(type !== "DIRECTIONAL" ? [`        falloff(${scaledRoom ? "roomFalloff" : "5f"})`] : []),
    ...(type !== "POINT" ? ["        direction(0f, -1f, 0f)"] : []),
    ...(type === "SPOT" ? ["        spotLightCone(0.5f, 0.8f)"] : []),
    "    }",
    ")",
  ];
  return lines.map((line) => indent + line).join("\n");
}

function generateArLightingDesign(
  composableName: string,
  options: LightingDesignOptions
): string {
  const { lights, colorTemperature = "warm-white" } = options;
  const tempColor = TEMPERATURE_COLORS[colorTemperature];

  return `package com.example.interior.lighting

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.android.filament.LightManager
import com.google.ar.core.Frame
import com.google.ar.core.Anchor
import com.google.ar.core.Plane
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.arcore.createAnchorOrNull
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberOnGestureListener
import io.github.sceneview.math.Position
import kotlin.math.PI

/**
 * AR Lighting Design — preview lighting in your real room.
 *
 * Lights: ${lights.join(", ")}. Color temperature: ${colorTemperature} (~${tempColor.kelvin}K).
 * Tap a horizontal floor plane to place virtual light fixtures.
 * Fixture offsets and falloff are in meters relative to that plane's anchor.
 *
 * Required AndroidManifest.xml:
 *   <uses-permission android:name="android.permission.CAMERA" />
 *   <uses-feature android:name="android.hardware.camera.ar" android:required="true" />
 *   <!-- arsceneview declares "optional": overriding it needs xmlns:tools="http://schemas.android.com/tools" on <manifest> -->
 *   <meta-data android:name="com.google.ar.core" android:value="required" tools:replace="android:value" />
 *
 * Gradle: implementation("io.github.sceneview:arsceneview:4.69.4")
 */
@Composable
fun ${composableName}AR() {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    // Latest ARCore frame — the tap handler hit-tests against it
    var latestFrame by remember { mutableStateOf<Frame?>(null) }

    var anchor by remember { mutableStateOf<Anchor?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        ARSceneView(
            modifier = Modifier.fillMaxSize(),
            engine = engine,
            modelLoader = modelLoader,
            planeRenderer = true,
            onSessionUpdated = { _, frame -> latestFrame = frame },
            onGestureListener = rememberOnGestureListener(
                onSingleTapConfirmed = { e, _ ->
                    // The anchor's local y=0 is the tapped floor, at any world height.
                    if (anchor == null) {
                        anchor = latestFrame?.hitTest(e)?.firstOrNull { hit ->
                            val plane = hit.trackable as? Plane
                            plane?.type == Plane.Type.HORIZONTAL_UPWARD_FACING &&
                                plane.isPoseInPolygon(hit.hitPose)
                        }?.createAnchorOrNull()
                    }
                }
            )
        ) {
            anchor?.let { placedAnchor ->
                AnchorNode(anchor = placedAnchor) {
${lights.map((light) => generateLightNodeCode(light, tempColor, false, "                    ")).join("\n\n")}
                }
            }
        }

        if (anchor == null) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(24.dp)
            ) {
                Text(
                    text = "Tap a floor plane to preview lighting setup",
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

// ─── EV charging station viewer code generator ───────────────────────────────

export type ChargingConnector =
  | "ccs"
  | "chademo"
  | "type2"
  | "tesla"
  | "j1772";

export type StationLayout =
  | "single"
  | "dual"
  | "bank"
  | "canopy";

export const CHARGING_CONNECTORS: ChargingConnector[] = [
  "ccs", "chademo", "type2", "tesla", "j1772",
];

export const STATION_LAYOUTS: StationLayout[] = [
  "single", "dual", "bank", "canopy",
];

export interface EvChargingStationViewerOptions {
  connector?: ChargingConnector;
  layout?: StationLayout;
  /** Show the AR overlay (charge level, bays, ETA). Default: true. */
  overlay?: boolean;
  /** Render as an AR scene (true) or regular 3D scene (false). Default: false. */
  ar?: boolean;
}

/**
 * Generates a complete, compilable Kotlin composable that renders a 3D EV
 * charging station model with an overlay UI showing live charge level,
 * available bays, and estimated time to full.
 *
 * The template mirrors the style of `generateCarConfigurator` and uses
 * current SceneView 3.6.x APIs: `rememberEngine`, `rememberModelLoader`,
 * `rememberModelInstance`, `ModelNode`, `LightNode` with its `type` and the
 * named `apply` parameter, and (when ar=true) `ARSceneView`.
 */
export function generateEvChargingStationViewer(
  options: EvChargingStationViewerOptions = {},
): string {
  const {
    connector = "ccs",
    layout = "single",
    overlay = true,
    ar = false,
  } = options;

  const composableName = `${capitalize(layout)}${capitalize(connector)}ChargingStationViewer`;
  const modelPath = `models/ev/${layout}_${connector}_station.glb`;

  const sceneImports = ar
    ? `import com.google.ar.core.Frame
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.node.AnchorNode
import io.github.sceneview.rememberOnGestureListener`
    : `import io.github.sceneview.SceneView`;

  const sceneOpen = ar
    ? `ARSceneView(
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
                                hitResult.createAnchor()
                                placed = true
                            }
                        }
                    }
                )
            ) {`
    : `SceneView(
                modifier = Modifier.fillMaxSize(),
                engine = engine,
                modelLoader = modelLoader,
                environment = environmentLoader.createHDREnvironment(
                    assetFileLocation = "environments/city_hdr.ktx"
                )!!,
                onFrame = { _ ->
                    // Live tick — update charge level / ETA here.
                }
            ) {`;

  const arState = ar
    ? `    var placed by remember { mutableStateOf(false) }
    // Latest ARCore frame — the tap handler hit-tests against it
    var latestFrame by remember { mutableStateOf<Frame?>(null) }
`
    : "";

  const environmentLoader = ar
    ? ""
    : `    val environmentLoader = rememberEnvironmentLoader(engine)
`;

  const environmentImport = ar
    ? ""
    : `import io.github.sceneview.rememberEnvironmentLoader
`;

  return `package com.example.automotive.ev

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.google.android.filament.LightManager
${sceneImports}
import io.github.sceneview.node.LightNode
import io.github.sceneview.node.ModelNode
import io.github.sceneview.rememberEngine
${environmentImport}import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader

/**
 * ${capitalize(layout)} ${connector.toUpperCase()} EV charging station viewer.
 *
 * Renders a 3D model of an EV charging station${ar ? " in augmented reality" : ""} and
 * overlays a Material 3 status card showing:
 *  - Current charge level (%)
 *  - Bays available vs total
 *  - Estimated time to full
 *
 * Model: Place your GLB file at src/main/assets/${modelPath}
 *
 * Threading: All Filament calls (ModelNode, LightNode, material updates)
 * happen on the main thread via rememberModelInstance — never load models
 * from a background coroutine.
 */
@Composable
fun ${composableName}() {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
${environmentLoader}
    val modelInstance = rememberModelInstance(modelLoader, "${modelPath}")

    // ── Live charging state (wire to your backend / BLE session) ──────────
    var chargeLevelPercent by remember { mutableFloatStateOf(42f) }
    var baysAvailable by remember { mutableIntStateOf(3) }
    val baysTotal = ${layoutToBays(layout)}
    val estimatedMinutesToFull by remember(chargeLevelPercent) {
        mutableIntStateOf(((100f - chargeLevelPercent) * 0.9f).toInt())
    }
${arState}
    Box(modifier = Modifier.fillMaxSize()) {
        ${sceneOpen}
            // Station model — null-safe load.
            modelInstance?.let { instance ->
                ModelNode(
                    modelInstance = instance,
                    scaleToUnits = ${ar ? "2.2f" : "1.8f"}
                )
            }

            // Ambient daylight key light.
            LightNode(
                type = LightManager.Type.DIRECTIONAL,
                apply = {
                    intensity(110_000f)
                    color(1.0f, 0.98f, 0.94f)
                    direction(-0.4f, -1f, -0.3f)
                }
            )

            // Fill light for the side panels and cables.
            LightNode(
                type = LightManager.Type.DIRECTIONAL,
                apply = {
                    intensity(35_000f)
                    color(0.85f, 0.9f, 1.0f)
                    direction(0.6f, -0.4f, 0.7f)
                }
            )
        }

        // Loading indicator while the GLB streams in.
        if (modelInstance == null) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center)
            )
        }
${overlay ? `
        // ── AR overlay card ───────────────────────────────────────────────
        ChargingStatusCard(
            chargeLevelPercent = chargeLevelPercent,
            baysAvailable = baysAvailable,
            baysTotal = baysTotal,
            estimatedMinutesToFull = estimatedMinutesToFull,
            connector = "${connector.toUpperCase()}",
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(16.dp)
        )
` : ""}    }
}
${overlay ? `
/**
 * Charge level, bays available, and ETA overlay.
 * Material 3 card so it matches the host app theme automatically.
 */
@Composable
private fun ChargingStatusCard(
    chargeLevelPercent: Float,
    baysAvailable: Int,
    baysTotal: Int,
    estimatedMinutesToFull: Int,
    connector: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.clip(RoundedCornerShape(16.dp)),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 4.dp,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "$connector Charging",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))

            // Charge level bar.
            Text(
                text = "Charge: \${'$'}{chargeLevelPercent.toInt()}%",
                style = MaterialTheme.typography.bodyMedium,
            )
            LinearProgressIndicator(
                progress = { (chargeLevelPercent / 100f).coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
            )

            Spacer(Modifier.height(8.dp))
            Text(
                text = "Bays: $baysAvailable / $baysTotal available",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "ETA: ~$estimatedMinutesToFull min to full",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
` : ""}`;
}

function layoutToBays(layout: StationLayout): number {
  switch (layout) {
    case "single": return 1;
    case "dual": return 2;
    case "bank": return 6;
    case "canopy": return 8;
  }
}

function capitalize(s: string): string {
  return s
    .split(/[-_]/)
    .map((w) => w.charAt(0).toUpperCase() + w.slice(1))
    .join("");
}

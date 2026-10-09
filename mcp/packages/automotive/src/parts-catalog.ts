// ─── Parts catalog code generator ─────────────────────────────────────────────

export type PartCategory =
  | "engine"
  | "transmission"
  | "suspension"
  | "brakes"
  | "exhaust"
  | "interior"
  | "body"
  | "electrical"
  | "wheels"
  | "cooling";

export type CatalogFeature =
  | "exploded-view"
  | "part-selection"
  | "detail-zoom"
  | "cross-section"
  | "animation"
  | "part-info"
  | "search";

export const PART_CATEGORIES: PartCategory[] = [
  "engine", "transmission", "suspension", "brakes", "exhaust",
  "interior", "body", "electrical", "wheels", "cooling",
];

export const CATALOG_FEATURES: CatalogFeature[] = [
  "exploded-view", "part-selection", "detail-zoom",
  "cross-section", "animation", "part-info", "search",
];

export interface PartsCatalogOptions {
  category: PartCategory;
  features?: CatalogFeature[];
  partNumbers?: boolean;
  pricing?: boolean;
  ar?: boolean;
}

/**
 * Generates a complete, compilable Kotlin composable for a 3D parts catalog explorer.
 */
export function generatePartsCatalog(options: PartsCatalogOptions): string {
  const {
    category,
    features = ["exploded-view", "part-selection", "detail-zoom"],
    partNumbers = true,
    pricing = false,
    ar = false,
  } = options;

  const composableName = `${capitalize(category)}PartsCatalog`;
  const modelPath = `models/parts/${category}_assembly.glb`;

  if (ar) {
    return generateArPartsCatalog(composableName, modelPath, options);
  }

  return `package com.example.automotive.parts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
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
 * ${capitalize(category)} parts catalog — 3D exploded view with part selection.
 *
 * Features: ${features.join(", ")}.
 * ${partNumbers ? "Part numbers displayed." : ""} ${pricing ? "Pricing information included." : ""}
 *
 * Model: Place the ${category} assembly GLB at src/main/assets/${modelPath}
 * Tip: Name each mesh/node in your GLB after the part for automatic part selection.
 */
@Composable
fun ${composableName}() {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)

    val assemblyModel = rememberModelInstance(modelLoader, "${modelPath}")

${features.includes("exploded-view") ? `    var explodeFactor by remember { mutableFloatStateOf(0f) }` : ""}
${features.includes("part-selection") ? `    var selectedPart by remember { mutableStateOf<String?>(null) }` : ""}
${features.includes("cross-section") ? `    var crossSectionEnabled by remember { mutableStateOf(false) }
    var crossSectionPosition by remember { mutableFloatStateOf(0.5f) }` : ""}
${features.includes("search") ? `    var searchQuery by remember { mutableStateOf("") }` : ""}

    // Sample parts data for ${category}
    val parts = remember {
        get${capitalize(category)}Parts()
    }

    Row(modifier = Modifier.fillMaxSize()) {
        // ── 3D Scene ──────────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .weight(2f)
                .fillMaxHeight()
        ) {
            SceneView(
                modifier = Modifier.fillMaxSize(),
                engine = engine,
                modelLoader = modelLoader,
                environment = environmentLoader.createHDREnvironment(
                    assetFileLocation = "environments/neutral_hdr.ktx"
                )!!,
                onFrame = { frameTimeNanos ->
                    // Update exploded view positions
                }
            ) {
                // Assembly model
                assemblyModel?.let { instance ->
                    ModelNode(
                        modelInstance = instance,
                        scaleToUnits = 1.5f
                    )
                }

                // Technical lighting
                LightNode(
                    type = LightManager.Type.DIRECTIONAL,
                    apply = {
                        intensity(80_000f)
                        color(1.0f, 1.0f, 1.0f)
                        direction(-0.5f, -1f, -0.5f)
                    }
                )
                LightNode(
                    type = LightManager.Type.DIRECTIONAL,
                    apply = {
                        intensity(40_000f)
                        color(0.9f, 0.95f, 1.0f)
                        direction(0.5f, -0.5f, 0.5f)
                    }
                )
            }

            // Loading indicator
            if (assemblyModel == null) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }

        // ── Parts list panel ──────────────────────────────────────────────
        Surface(
            tonalElevation = 1.dp,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    "${capitalize(category)} Parts",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(8.dp))
${features.includes("search") ? `                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text("Search parts") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))` : ""}
${features.includes("exploded-view") ? `                Text("Exploded View", style = MaterialTheme.typography.bodySmall)
                Slider(
                    value = explodeFactor,
                    onValueChange = { explodeFactor = it },
                    valueRange = 0f..1f
                )
                Spacer(Modifier.height(8.dp))` : ""}
${features.includes("cross-section") ? `                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(value = crossSectionEnabled, onValueChange = { crossSectionEnabled = it }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Cross-Section", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.weight(1f))
                    Switch(checked = crossSectionEnabled, onCheckedChange = null)
                }
                if (crossSectionEnabled) {
                    Slider(
                        value = crossSectionPosition,
                        onValueChange = { crossSectionPosition = it },
                        valueRange = 0f..1f
                    )
                }
                Spacer(Modifier.height(8.dp))` : ""}

                // Parts list
                LazyColumn {
                    items(parts${features.includes("search") ? `.filter { it.name.contains(searchQuery, ignoreCase = true) }` : ""}) { part ->
                        PartItem(
                            part = part,
                            isSelected = ${features.includes("part-selection") ? "part.id == selectedPart" : "false"},
                            onClick = { ${features.includes("part-selection") ? `selectedPart = part.id` : "/* Part tapped */"} }
                        )
                    }
                }
            }
        }
    }
}

data class PartData(
    val id: String,
    val name: String,
${partNumbers ? `    val partNumber: String,` : ""}
${pricing ? `    val price: String,` : ""}
    val description: String
)

@Composable
private fun PartItem(
    part: PartData,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(part.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
${partNumbers ? `            Text("Part# \${part.partNumber}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)` : ""}
${pricing ? `            Text(part.price, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)` : ""}
        }
    }
}

private fun get${capitalize(category)}Parts(): List<PartData> {
    return ${getPartsForCategory(category, partNumbers, pricing)}
}`;
}

function generateArPartsCatalog(
  composableName: string,
  modelPath: string,
  options: PartsCatalogOptions
): string {
  return `package com.example.automotive.parts

import androidx.compose.foundation.layout.*
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
 * AR ${capitalize(options.category)} parts explorer.
 *
 * Places the ${options.category} assembly in augmented reality for hands-on examination.
 * Tap a surface to place, then interact with individual parts.
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

    val assemblyModel = rememberModelInstance(modelLoader, "${modelPath}")

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
                        if (!placed && assemblyModel != null) {
                            val anchor = hitResult.createAnchor()
                            placed = true
                        }
                    }
                }
            )
        ) {
            assemblyModel?.let { instance ->
                ModelNode(
                    modelInstance = instance,
                    scaleToUnits = 1.0f
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
                    text = "Point camera at a flat surface, then tap to place the ${options.category} assembly",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}`;
}

function getPartsForCategory(category: PartCategory, partNumbers: boolean, pricing: boolean): string {
  const parts: Record<string, Array<{ id: string; name: string; pn: string; price: string; desc: string }>> = {
    engine: [
      { id: "cylinder-head", name: "Cylinder Head", pn: "ENG-CH-001", price: "$1,250", desc: "Aluminum alloy cylinder head" },
      { id: "piston-set", name: "Piston Set", pn: "ENG-PS-004", price: "$680", desc: "Forged pistons with rings" },
      { id: "crankshaft", name: "Crankshaft", pn: "ENG-CS-002", price: "$890", desc: "Forged steel crankshaft" },
      { id: "camshaft", name: "Camshaft", pn: "ENG-CM-003", price: "$420", desc: "DOHC camshaft assembly" },
      { id: "timing-chain", name: "Timing Chain Kit", pn: "ENG-TC-005", price: "$280", desc: "Chain, tensioner, guides" },
    ],
    transmission: [
      { id: "gear-set", name: "Gear Set", pn: "TRN-GS-001", price: "$2,100", desc: "6-speed manual gear set" },
      { id: "clutch-kit", name: "Clutch Kit", pn: "TRN-CK-002", price: "$450", desc: "Disc, pressure plate, bearing" },
      { id: "syncro-set", name: "Synchronizer Set", pn: "TRN-SS-003", price: "$380", desc: "Brass synchronizer rings" },
      { id: "shift-fork", name: "Shift Fork Assembly", pn: "TRN-SF-004", price: "$160", desc: "Chrome-moly shift forks" },
    ],
    brakes: [
      { id: "caliper-front", name: "Front Caliper", pn: "BRK-CF-001", price: "$320", desc: "4-piston front brake caliper" },
      { id: "rotor-front", name: "Front Rotor", pn: "BRK-RF-002", price: "$180", desc: "Ventilated disc rotor 330mm" },
      { id: "pad-set", name: "Brake Pad Set", pn: "BRK-PS-003", price: "$95", desc: "Ceramic compound pads" },
      { id: "brake-line", name: "Brake Line Kit", pn: "BRK-BL-004", price: "$120", desc: "Stainless steel braided lines" },
    ],
    suspension: [
      { id: "strut-front", name: "Front Strut Assembly", pn: "SUS-SF-001", price: "$280", desc: "Gas-charged front strut" },
      { id: "control-arm", name: "Control Arm", pn: "SUS-CA-002", price: "$190", desc: "Forged aluminum control arm" },
      { id: "sway-bar", name: "Sway Bar Kit", pn: "SUS-SB-003", price: "$150", desc: "Adjustable anti-roll bar" },
      { id: "spring-set", name: "Spring Set", pn: "SUS-SP-004", price: "$220", desc: "Progressive rate coil springs" },
    ],
    exhaust: [
      { id: "header", name: "Exhaust Header", pn: "EXH-HD-001", price: "$580", desc: "Stainless steel tubular header" },
      { id: "catalytic", name: "Catalytic Converter", pn: "EXH-CC-002", price: "$450", desc: "High-flow catalyst" },
      { id: "muffler", name: "Muffler", pn: "EXH-MF-003", price: "$280", desc: "Performance muffler" },
    ],
    interior: [
      { id: "seat-assembly", name: "Seat Assembly", pn: "INT-SA-001", price: "$1,800", desc: "Leather sport seat with heating" },
      { id: "steering-wheel", name: "Steering Wheel", pn: "INT-SW-002", price: "$420", desc: "Multi-function steering wheel" },
      { id: "dashboard-trim", name: "Dashboard Trim", pn: "INT-DT-003", price: "$350", desc: "Carbon fiber trim set" },
    ],
    body: [
      { id: "front-bumper", name: "Front Bumper", pn: "BDY-FB-001", price: "$680", desc: "Aerodynamic front bumper" },
      { id: "hood", name: "Hood Assembly", pn: "BDY-HD-002", price: "$520", desc: "Aluminum hood with vents" },
      { id: "fender", name: "Front Fender", pn: "BDY-FF-003", price: "$380", desc: "Steel front fender panel" },
    ],
    electrical: [
      { id: "ecu", name: "ECU Module", pn: "ELC-EC-001", price: "$950", desc: "Engine control unit" },
      { id: "wiring-harness", name: "Wiring Harness", pn: "ELC-WH-002", price: "$420", desc: "Complete engine harness" },
      { id: "alternator", name: "Alternator", pn: "ELC-AL-003", price: "$280", desc: "High-output alternator" },
    ],
    wheels: [
      { id: "alloy-wheel", name: "Alloy Wheel 19\"", pn: "WHL-AW-001", price: "$380", desc: "Forged alloy wheel" },
      { id: "tire", name: "Performance Tire", pn: "WHL-PT-002", price: "$220", desc: "245/40R19 summer tire" },
      { id: "hub-bearing", name: "Hub Bearing", pn: "WHL-HB-003", price: "$140", desc: "Sealed wheel bearing unit" },
    ],
    cooling: [
      { id: "radiator", name: "Radiator", pn: "COL-RD-001", price: "$380", desc: "Aluminum core radiator" },
      { id: "water-pump", name: "Water Pump", pn: "COL-WP-002", price: "$180", desc: "Mechanical water pump" },
      { id: "thermostat", name: "Thermostat", pn: "COL-TH-003", price: "$45", desc: "Engine thermostat valve" },
    ],
  };

  const categoryParts = parts[category] ?? parts.engine;
  const items = categoryParts.map(
    (p) =>
      `        PartData(id = "${kotlinString(p.id)}", name = "${kotlinString(p.name)}"${partNumbers ? `, partNumber = "${kotlinString(p.pn)}"` : ""}${pricing ? `, price = "${kotlinString(p.price)}"` : ""}, description = "${kotlinString(p.desc)}")`
  );

  return `listOf(\n${items.join(",\n")}\n    )`;
}

/** Escapes a value for a Kotlin string literal — a part name like `19"` or a `$` price would otherwise break it. */
function kotlinString(s: string): string {
  return s.replace(/[\\"$]/g, (c) => `\\${c}`);
}

function capitalize(s: string): string {
  return s
    .split("-")
    .map((w) => w.charAt(0).toUpperCase() + w.slice(1))
    .join("");
}

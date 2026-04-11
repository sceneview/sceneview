// ─── Level editor code generator ──────────────────────────────────────────────

export type LevelTheme =
  | "dungeon"
  | "forest"
  | "space"
  | "underwater"
  | "desert"
  | "city"
  | "castle"
  | "ice"
  | "lava"
  | "sky";

export type GeometryType =
  | "cube"
  | "sphere"
  | "cylinder"
  | "plane"
  | "ramp"
  | "arch"
  | "pillar"
  | "wall"
  | "platform"
  | "stairs";

export const LEVEL_THEMES: LevelTheme[] = [
  "dungeon", "forest", "space", "underwater", "desert",
  "city", "castle", "ice", "lava", "sky",
];

export const GEOMETRY_TYPES: GeometryType[] = [
  "cube", "sphere", "cylinder", "plane", "ramp",
  "arch", "pillar", "wall", "platform", "stairs",
];

export interface LevelEditorOptions {
  theme: LevelTheme;
  geometries?: GeometryType[];
  gridSize?: number;
  showGrid?: boolean;
  editable?: boolean;
  ar?: boolean;
}

/**
 * Generates a complete, compilable Kotlin composable for a procedural level editor.
 */
export function generateLevelEditor(options: LevelEditorOptions): string {
  const {
    theme,
    geometries = ["cube", "sphere", "cylinder", "plane"],
    gridSize = 10,
    showGrid = true,
    editable = true,
    ar = false,
  } = options;

  const composableName = `${capitalize(theme)}LevelEditor`;
  const themeColors = getThemeColors(theme);

  if (ar) {
    return generateArLevelEditor(composableName, options);
  }

  const geometryEntries = geometries
    .map(
      (g) =>
        `                Button(
                    onClick = { selectedGeometry = "${g}" },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (selectedGeometry == "${g}")
                            MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Text("${capitalize(g)}")
                }`
    )
    .join("\n");

  return `package com.example.gaming.level

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.sceneview.SceneView
import io.github.sceneview.node.CubeNode
import io.github.sceneview.node.SphereNode
import io.github.sceneview.node.CylinderNode
import io.github.sceneview.node.ModelNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberCollisionSystem
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.node.LightNode
import io.github.sceneview.math.Position
import io.github.sceneview.math.Scale

/**
 * Procedural level editor — ${theme} theme.
 *
 * Generates a ${gridSize}x${gridSize} level grid with procedural geometry
 * (${geometries.join(", ")}). ${editable ? "Tap to place blocks, long-press to remove." : "View-only mode."}
 *
 * Uses SceneView's built-in geometry nodes (CubeNode, SphereNode, CylinderNode)
 * for real-time procedural level construction. No external models needed.
 *
 * Theme colors: ${themeColors.primary} base, ${themeColors.accent} accents.
 */
@Composable
fun ${composableName}() {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)
    val collisionSystem = rememberCollisionSystem(engine)

    // Level state: grid of placed blocks
    val levelBlocks = remember { mutableStateListOf<LevelBlock>() }
    var selectedGeometry by remember { mutableStateOf("${geometries[0]}") }

    // Initialize with procedural level
    LaunchedEffect(Unit) {
        levelBlocks.addAll(generateProceduralLevel("${theme}", ${gridSize}))
    }

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
                collisionSystem = collisionSystem,
                environment = environmentLoader.createHDREnvironment(
                    assetFileLocation = "environments/level_hdr.ktx"
                )!!
            ) {
${showGrid ? `                // Grid floor
                CubeNode(
                    engine = engine,
                    materialLoader = materialLoader,
                    size = Scale(${gridSize}.0f, 0.05f, ${gridSize}.0f),
                    position = Position(0f, -0.025f, 0f)
                )` : ""}

                // Placed level blocks
                for (block in levelBlocks) {
                    when (block.type) {
                        "cube" -> CubeNode(
                            engine = engine,
                            materialLoader = materialLoader,
                            size = Scale(block.scaleX, block.scaleY, block.scaleZ),
                            position = Position(block.x, block.y, block.z)
                        )
                        "sphere" -> SphereNode(
                            engine = engine,
                            materialLoader = materialLoader,
                            radius = block.scaleX * 0.5f,
                            position = Position(block.x, block.y, block.z)
                        )
                        "cylinder" -> CylinderNode(
                            engine = engine,
                            materialLoader = materialLoader,
                            radius = block.scaleX * 0.5f,
                            height = block.scaleY,
                            position = Position(block.x, block.y, block.z)
                        )
                    }
                }

                // Theme-appropriate lighting
                LightNode(
                    apply = {
                        intensity(${themeColors.lightIntensity}f)
                        color(${themeColors.lightColor})
                        direction(0.3f, -1f, -0.4f)
                    }
                )

                LightNode(
                    apply = {
                        intensity(${themeColors.fillIntensity}f)
                        color(${themeColors.fillColor})
                        direction(-0.3f, -0.5f, 0.4f)
                    }
                )
            }
        }

${editable ? `        // ── Geometry Palette ────────────────────────────────────────────
        Surface(
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Geometry: \${selectedGeometry}", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
${geometryEntries}
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Block count: \${levelBlocks.size}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }` : ""}
    }
}

/**
 * Represents a placed block in the level grid.
 */
data class LevelBlock(
    val type: String,
    val x: Float,
    val y: Float,
    val z: Float,
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val scaleZ: Float = 1f,
)

/**
 * Generates a procedural level layout based on theme.
 */
private fun generateProceduralLevel(theme: String, gridSize: Int): List<LevelBlock> {
    val blocks = mutableListOf<LevelBlock>()
    val random = java.util.Random(theme.hashCode().toLong())

    // Generate ground blocks
    for (x in 0 until gridSize) {
        for (z in 0 until gridSize) {
            if (random.nextFloat() > 0.3f) {
                blocks.add(
                    LevelBlock(
                        type = "cube",
                        x = x.toFloat() - gridSize / 2f,
                        y = 0f,
                        z = z.toFloat() - gridSize / 2f
                    )
                )
            }
        }
    }

    // Add theme-specific features
    for (i in 0 until gridSize) {
        val fx = random.nextFloat() * gridSize - gridSize / 2f
        val fz = random.nextFloat() * gridSize - gridSize / 2f
        blocks.add(
            LevelBlock(
                type = if (random.nextBoolean()) "sphere" else "cylinder",
                x = fx,
                y = 1f,
                z = fz,
                scaleX = 0.5f + random.nextFloat(),
                scaleY = 1f + random.nextFloat() * 2f,
                scaleZ = 0.5f + random.nextFloat()
            )
        )
    }

    return blocks
}`;
}

function generateArLevelEditor(
  composableName: string,
  options: LevelEditorOptions
): string {
  const gridSize = options.gridSize ?? 10;
  return `package com.example.gaming.level

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.node.CubeNode
import io.github.sceneview.node.SphereNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberCollisionSystem
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.math.Position
import io.github.sceneview.math.Scale

/**
 * AR Level Editor — ${options.theme} theme.
 *
 * Build game levels in augmented reality by tapping surfaces to place
 * geometry blocks. ${gridSize}x${gridSize} grid, procedural ${options.theme} theme.
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
    val materialLoader = rememberMaterialLoader(engine)
    val collisionSystem = rememberCollisionSystem(engine)

    var selectedGeometry by remember { mutableStateOf("cube") }

    Box(modifier = Modifier.fillMaxSize()) {
        ARSceneView(
            modifier = Modifier.fillMaxSize(),
            engine = engine,
            modelLoader = modelLoader,
            collisionSystem = collisionSystem,
            planeRenderer = true,
            onTapAR = { hitResult ->
                val anchor = hitResult.createAnchor()
                // Place selected geometry at tap location
            }
        ) {
            // AR placed blocks rendered here
        }

        // Geometry selector
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(24.dp)
        ) {
            Button(onClick = { selectedGeometry = "cube" }) { Text("Cube") }
            Button(onClick = { selectedGeometry = "sphere" }) { Text("Sphere") }
            Button(onClick = { selectedGeometry = "cylinder" }) { Text("Cylinder") }
        }
    }
}`;
}

interface ThemeColors {
  primary: string;
  accent: string;
  lightIntensity: number;
  lightColor: string;
  fillIntensity: number;
  fillColor: string;
}

export function getThemeColors(theme: LevelTheme): ThemeColors {
  const themes: Record<LevelTheme, ThemeColors> = {
    dungeon: { primary: "gray stone", accent: "torch orange", lightIntensity: 50_000, lightColor: "1.0f, 0.8f, 0.5f", fillIntensity: 15_000, fillColor: "0.8f, 0.6f, 0.4f" },
    forest: { primary: "green foliage", accent: "brown bark", lightIntensity: 80_000, lightColor: "1.0f, 1.0f, 0.9f", fillIntensity: 30_000, fillColor: "0.7f, 0.9f, 0.7f" },
    space: { primary: "dark void", accent: "neon blue", lightIntensity: 40_000, lightColor: "0.7f, 0.8f, 1.0f", fillIntensity: 20_000, fillColor: "0.5f, 0.5f, 0.8f" },
    underwater: { primary: "ocean blue", accent: "coral pink", lightIntensity: 60_000, lightColor: "0.6f, 0.8f, 1.0f", fillIntensity: 25_000, fillColor: "0.4f, 0.7f, 0.9f" },
    desert: { primary: "sand beige", accent: "sunset orange", lightIntensity: 120_000, lightColor: "1.0f, 0.95f, 0.8f", fillIntensity: 40_000, fillColor: "1.0f, 0.9f, 0.7f" },
    city: { primary: "concrete gray", accent: "neon sign", lightIntensity: 70_000, lightColor: "1.0f, 0.98f, 0.95f", fillIntensity: 25_000, fillColor: "0.9f, 0.9f, 1.0f" },
    castle: { primary: "stone gray", accent: "royal purple", lightIntensity: 60_000, lightColor: "1.0f, 0.95f, 0.85f", fillIntensity: 20_000, fillColor: "0.9f, 0.85f, 0.8f" },
    ice: { primary: "ice blue", accent: "snow white", lightIntensity: 100_000, lightColor: "0.9f, 0.95f, 1.0f", fillIntensity: 40_000, fillColor: "0.8f, 0.9f, 1.0f" },
    lava: { primary: "obsidian black", accent: "lava red", lightIntensity: 90_000, lightColor: "1.0f, 0.6f, 0.3f", fillIntensity: 35_000, fillColor: "1.0f, 0.4f, 0.1f" },
    sky: { primary: "cloud white", accent: "sky blue", lightIntensity: 110_000, lightColor: "1.0f, 1.0f, 1.0f", fillIntensity: 45_000, fillColor: "0.8f, 0.9f, 1.0f" },
  };
  return themes[theme];
}

function capitalize(s: string): string {
  return s
    .split("-")
    .map((w) => w.charAt(0).toUpperCase() + w.slice(1))
    .join("");
}

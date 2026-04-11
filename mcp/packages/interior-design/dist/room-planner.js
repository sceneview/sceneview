// ─── Room planner code generator ──────────────────────────────────────────────
export const ROOM_TYPES = [
    "living-room", "bedroom", "kitchen", "bathroom", "dining-room",
    "office", "studio", "hallway", "garage", "open-plan",
];
export const WALL_STYLES = [
    "standard", "brick", "concrete", "wood-panel", "glass",
];
export const FLOOR_STYLES = [
    "hardwood", "tile", "carpet", "marble", "concrete", "laminate", "vinyl",
];
/**
 * Generates a complete, compilable Kotlin composable for a 3D room layout planner.
 */
export function generateRoomPlanner(options) {
    const { roomType, widthMeters = 4.0, lengthMeters = 5.0, heightMeters = 2.7, wallStyle = "standard", floorStyle = "hardwood", windows = 2, doors = 1, ar = false, } = options;
    const composableName = `${capitalize(roomType)}Planner`;
    if (ar) {
        return generateArRoomPlanner(composableName, options);
    }
    return `package com.example.interior.planner

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.sceneview.SceneView
import io.github.sceneview.node.ModelNode
import io.github.sceneview.node.CubeNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberCollisionSystem
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.node.LightNode
import io.github.sceneview.math.Position
import io.github.sceneview.math.Scale

/**
 * 3D Room Planner — ${roomType} (${widthMeters}m x ${lengthMeters}m x ${heightMeters}m).
 *
 * Procedurally generates walls, floor, and ceiling with configurable dimensions.
 * Wall style: ${wallStyle}, Floor: ${floorStyle}, ${windows} window(s), ${doors} door(s).
 *
 * Uses SceneView's geometry nodes for walls/floor and ModelNode for furniture.
 * Orbit camera with pinch-to-zoom for interior exploration.
 *
 * Gradle: implementation("io.github.sceneview:sceneview:3.6.0")
 */
@Composable
fun ${composableName}() {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)
    val collisionSystem = rememberCollisionSystem(engine)

    // Room dimensions
    val width = ${widthMeters}f   // meters
    val length = ${lengthMeters}f  // meters
    val height = ${heightMeters}f  // meters

    var showWalls by remember { mutableStateOf(true) }
    var showFloor by remember { mutableStateOf(true) }
    var showCeiling by remember { mutableStateOf(false) }

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
                    assetFileLocation = "environments/interior_hdr.ktx"
                )!!,
                onFrame = { /* Update room state if needed */ }
            ) {
                // ── Floor ─────────────────────────────────────────────────
                if (showFloor) {
                    CubeNode(
                        engine = engine,
                        size = Scale(width, 0.02f, length),
                        center = Position(0f, -0.01f, 0f)
                    )
                }

                // ── Walls ─────────────────────────────────────────────────
                if (showWalls) {
                    // Back wall
                    CubeNode(
                        engine = engine,
                        size = Scale(width, height, 0.1f),
                        center = Position(0f, height / 2f, -length / 2f)
                    )
                    // Left wall
                    CubeNode(
                        engine = engine,
                        size = Scale(0.1f, height, length),
                        center = Position(-width / 2f, height / 2f, 0f)
                    )
                    // Right wall
                    CubeNode(
                        engine = engine,
                        size = Scale(0.1f, height, length),
                        center = Position(width / 2f, height / 2f, 0f)
                    )
                }

                // ── Ceiling ───────────────────────────────────────────────
                if (showCeiling) {
                    CubeNode(
                        engine = engine,
                        size = Scale(width, 0.02f, length),
                        center = Position(0f, height, 0f)
                    )
                }

                // ── Interior lighting ─────────────────────────────────────
                // Ceiling downlight
                LightNode(
                    apply = {
                        intensity(120_000f)
                        color(1.0f, 0.95f, 0.9f)
                        direction(0f, -1f, 0f)
                    }
                )

                // Window light simulation
                LightNode(
                    apply = {
                        intensity(60_000f)
                        color(0.95f, 0.98f, 1.0f)
                        direction(0.5f, -0.3f, 0.8f)
                    }
                )
            }

            // Room info overlay
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f),
                shape = MaterialTheme.shapes.small,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp)
            ) {
                Text(
                    text = "${capitalize(roomType)} — \${width}m x \${length}m x \${height}m",
                    modifier = Modifier.padding(8.dp),
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }

        // ── Controls ──────────────────────────────────────────────────────
        RoomPlannerControls(
            showWalls = showWalls,
            onToggleWalls = { showWalls = it },
            showFloor = showFloor,
            onToggleFloor = { showFloor = it },
            showCeiling = showCeiling,
            onToggleCeiling = { showCeiling = it },
        )
    }
}

/**
 * Control panel for room planner layer toggles.
 */
@Composable
private fun RoomPlannerControls(
    showWalls: Boolean,
    onToggleWalls: (Boolean) -> Unit,
    showFloor: Boolean,
    onToggleFloor: (Boolean) -> Unit,
    showCeiling: Boolean,
    onToggleCeiling: (Boolean) -> Unit,
) {
    Surface(
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Walls", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.weight(1f))
                Switch(checked = showWalls, onCheckedChange = onToggleWalls)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Floor", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.weight(1f))
                Switch(checked = showFloor, onCheckedChange = onToggleFloor)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Ceiling", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.weight(1f))
                Switch(checked = showCeiling, onCheckedChange = onToggleCeiling)
            }
        }
    }
}`;
}
function generateArRoomPlanner(composableName, options) {
    const { roomType, widthMeters = 4.0, lengthMeters = 5.0, heightMeters = 2.7, } = options;
    return `package com.example.interior.planner

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.node.AnchorNode
import io.github.sceneview.node.CubeNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberCollisionSystem
import io.github.sceneview.math.Position
import io.github.sceneview.math.Scale

/**
 * AR Room Planner — ${roomType} (${widthMeters}m x ${lengthMeters}m x ${heightMeters}m).
 *
 * Places a miniature 3D room layout in augmented reality. Tap a flat surface
 * to place the room, then walk around it to review the layout.
 * Scale: 1:10 for tabletop placement.
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

    val scale = 0.1f // 1:10 miniature
    val width = ${widthMeters}f * scale
    val length = ${lengthMeters}f * scale
    val height = ${heightMeters}f * scale

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
            // Floor
            CubeNode(
                engine = engine,
                size = Scale(width, 0.002f, length),
                center = Position(0f, 0f, 0f)
            )

            // Walls at 1:10 scale
            CubeNode(
                engine = engine,
                size = Scale(width, height, 0.01f),
                center = Position(0f, height / 2f, -length / 2f)
            )
            CubeNode(
                engine = engine,
                size = Scale(0.01f, height, length),
                center = Position(-width / 2f, height / 2f, 0f)
            )
            CubeNode(
                engine = engine,
                size = Scale(0.01f, height, length),
                center = Position(width / 2f, height / 2f, 0f)
            )
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
                    text = "Tap a flat surface to place your ${roomType.replace("-", " ")} layout",
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

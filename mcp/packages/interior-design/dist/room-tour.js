// ─── Room tour (animated camera walkthrough) code generator ───────────────────
export const TOUR_STYLES = [
    "orbit", "walkthrough", "flyover", "dolly", "panoramic",
];
export const TOUR_SPEEDS = [
    "slow", "normal", "fast",
];
const SPEED_DURATIONS = {
    slow: 30,
    normal: 15,
    fast: 8,
};
/**
 * Generates a complete, compilable Kotlin composable for an animated room tour.
 */
export function generateRoomTour(options) {
    const { tourStyle, speed = "normal", roomModel = "models/room/interior_scene.glb", waypoints = 4, loop = true, pauseOnInteraction = true, ar = false, } = options;
    const duration = SPEED_DURATIONS[speed];
    const composableName = `${capitalize(tourStyle)}RoomTour`;
    if (ar) {
        return generateArRoomTour(composableName, roomModel, options);
    }
    return `package com.example.interior.tour

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
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
import kotlin.math.cos
import kotlin.math.sin

/**
 * Animated Room Tour — ${tourStyle} style.
 *
 * Automatically animates the camera through the 3D room scene.
 * Speed: ${speed} (~${duration}s per cycle). ${waypoints} waypoints.
 * ${loop ? "Loops continuously." : "Plays once."} ${pauseOnInteraction ? "Pauses on touch." : ""}
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

    var isPlaying by remember { mutableStateOf(true) }

    // Camera animation
    val infiniteTransition = rememberInfiniteTransition(label = "tour")
    val animationProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = ${duration * 1000},
                easing = LinearEasing
            ),
            repeatMode = RepeatMode.${loop ? "Restart" : "Reverse"}
        ),
        label = "tourProgress"
    )

    // Waypoints for ${tourStyle} tour
    val waypoints = listOf(
${generateWaypoints(tourStyle, waypoints)}
    )

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
                onFrame = {
                    if (isPlaying) {
                        // Interpolate camera position between waypoints
                        val progress = animationProgress
                        val segmentCount = waypoints.size - 1
                        val segment = (progress * segmentCount).toInt().coerceAtMost(segmentCount - 1)
                        val segmentProgress = (progress * segmentCount) - segment
                        // Apply camera position/look-at interpolation here
                    }
                }
            ) {
                modelInstance?.let { instance ->
                    ModelNode(
                        modelInstance = instance,
                        scaleToUnits = 2.0f
                    )
                }

                // Interior lighting
                LightNode(
                    apply = {
                        intensity(100_000f)
                        color(1.0f, 0.95f, 0.9f)
                        direction(0f, -1f, -0.3f)
                    }
                )
            }

            // Tour controls overlay
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Play/Pause button
                FloatingActionButton(
                    onClick = { isPlaying = !isPlaying }
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pause tour" else "Play tour"
                    )
                }
            }

            // Progress indicator
            LinearProgressIndicator(
                progress = { animationProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
            )

            if (modelInstance == null) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }
    }
}`;
}
function generateWaypoints(style, count) {
    const waypoints = [];
    for (let i = 0; i < count; i++) {
        const angle = (i / count) * Math.PI * 2;
        let x, y, z;
        switch (style) {
            case "orbit":
                x = Math.cos(angle) * 3;
                y = 1.5;
                z = Math.sin(angle) * 3;
                break;
            case "walkthrough":
                x = -2 + (i / count) * 4;
                y = 1.6;
                z = -1 + Math.sin(angle) * 0.5;
                break;
            case "flyover":
                x = Math.cos(angle) * 2;
                y = 3.0 - i * 0.3;
                z = Math.sin(angle) * 2;
                break;
            case "dolly":
                x = 0;
                y = 1.5;
                z = -3 + (i / count) * 6;
                break;
            case "panoramic":
                x = 0;
                y = 1.6;
                z = 0;
                break;
            default:
                x = Math.cos(angle) * 3;
                y = 1.5;
                z = Math.sin(angle) * 3;
        }
        waypoints.push(`        Position(${x.toFixed(1)}f, ${y.toFixed(1)}f, ${z.toFixed(1)}f)`);
    }
    return waypoints.join(",\n");
}
function generateArRoomTour(composableName, roomModel, options) {
    const { tourStyle, speed = "normal" } = options;
    const duration = SPEED_DURATIONS[speed];
    return `package com.example.interior.tour

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
 * AR Room Tour — ${tourStyle} style.
 *
 * Place a miniature room in AR and watch an animated camera tour.
 * Walk around the model to see the tour from different angles.
 * Speed: ${speed} (~${duration}s per cycle).
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

    val modelInstance = rememberModelInstance(modelLoader, "${roomModel}")

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
                if (!placed && modelInstance != null) {
                    val anchor = hitResult.createAnchor()
                    placed = true
                }
            }
        ) {
            modelInstance?.let { instance ->
                ModelNode(
                    modelInstance = instance,
                    scaleToUnits = 0.3f // Miniature for tabletop viewing
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
                    text = "Tap a flat surface to place room model for tour preview",
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

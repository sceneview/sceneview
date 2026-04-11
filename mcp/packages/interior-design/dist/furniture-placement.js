// ─── AR furniture placement code generator ────────────────────────────────────
export const FURNITURE_CATEGORIES = [
    "sofa", "chair", "table", "bed", "desk", "shelf",
    "wardrobe", "cabinet", "lamp", "rug", "plant", "mirror",
];
export const FURNITURE_SIZES = [
    "small", "medium", "large", "custom",
];
const SIZE_SCALES = {
    small: 0.5,
    medium: 1.0,
    large: 1.5,
    custom: 1.0,
};
/**
 * Generates a complete, compilable Kotlin composable for AR furniture placement.
 */
export function generateFurniturePlacement(options) {
    const { category, size = "medium", colorHex, modelPath, rotatable = true, scalable = true, ar = true, } = options;
    const model = modelPath ?? `models/furniture/${category}.glb`;
    const scale = SIZE_SCALES[size] ?? 1.0;
    const composableName = `${capitalize(category)}Placement`;
    if (!ar) {
        return generate3DFurniturePreview(composableName, model, scale, options);
    }
    return `package com.example.interior.furniture

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.node.AnchorNode
import io.github.sceneview.node.ModelNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberCollisionSystem
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation

/**
 * AR Furniture Placement — ${category} (${size}).
 *
 * Place a ${category} in your real room using AR. Tap a surface to place,
 * ${rotatable ? "use two-finger rotate to orient, " : ""}${scalable ? "pinch to resize." : ""}
 * Model: src/main/assets/${model}
 *${colorHex ? ` Color customization: ${colorHex}` : ""}
 *
 * Required AndroidManifest.xml:
 *   <uses-permission android:name="android.permission.CAMERA" />
 *   <uses-feature android:name="android.hardware.camera.ar" android:required="true" />
 *   <meta-data android:name="com.google.ar.core" android:value="required" />
 *
 * Gradle: implementation("io.github.sceneview:arsceneview:3.6.0")
 */
@Composable
fun ${composableName}() {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val collisionSystem = rememberCollisionSystem(engine)

    val modelInstance = rememberModelInstance(modelLoader, "${model}")

    var placed by remember { mutableStateOf(false) }
${rotatable ? `    var rotationY by remember { mutableFloatStateOf(0f) }` : ""}
${scalable ? `    var scaleFactor by remember { mutableFloatStateOf(${scale}f) }` : ""}

    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
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
                        scaleToUnits = ${scale}f${scalable ? " * scaleFactor" : ""}
                    )
                }
            }

            // Loading indicator
            if (modelInstance == null) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center)
                )
            }

            // Placement instruction
            if (!placed && modelInstance != null) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(24.dp)
                ) {
                    Text(
                        text = "Point at the floor and tap to place your ${category}",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }

        // ── Controls ──────────────────────────────────────────────────────
        if (placed) {
            FurnitureControls(
${rotatable ? `                rotationY = rotationY,
                onRotationChange = { rotationY = it },` : ""}
${scalable ? `                scaleFactor = scaleFactor,
                onScaleChange = { scaleFactor = it },` : ""}
            )
        }
    }
}

@Composable
private fun FurnitureControls(
${rotatable ? `    rotationY: Float,
    onRotationChange: (Float) -> Unit,` : ""}
${scalable ? `    scaleFactor: Float,
    onScaleChange: (Float) -> Unit,` : ""}
) {
    Surface(
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
${rotatable ? `            Text("Rotation", style = MaterialTheme.typography.bodySmall)
            Slider(
                value = rotationY,
                onValueChange = onRotationChange,
                valueRange = 0f..360f
            )` : ""}
${scalable ? `            Text("Size", style = MaterialTheme.typography.bodySmall)
            Slider(
                value = scaleFactor,
                onValueChange = onScaleChange,
                valueRange = 0.3f..3.0f
            )` : ""}
        }
    }
}`;
}
function generate3DFurniturePreview(composableName, model, scale, options) {
    return `package com.example.interior.furniture

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

/**
 * 3D Furniture Preview — ${options.category} (${options.size ?? "medium"}).
 *
 * Preview a ${options.category} in 3D with orbit controls before placing in AR.
 * Model: src/main/assets/${model}
 *
 * Gradle: implementation("io.github.sceneview:sceneview:3.6.0")
 */
@Composable
fun ${composableName}Preview() {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)
    val collisionSystem = rememberCollisionSystem(engine)

    val modelInstance = rememberModelInstance(modelLoader, "${model}")

    Box(modifier = Modifier.fillMaxSize()) {
        SceneView(
            modifier = Modifier.fillMaxSize(),
            engine = engine,
            modelLoader = modelLoader,
            collisionSystem = collisionSystem,
            environment = environmentLoader.createHDREnvironment(
                assetFileLocation = "environments/studio_hdr.ktx"
            )!!,
        ) {
            modelInstance?.let { instance ->
                ModelNode(
                    modelInstance = instance,
                    scaleToUnits = ${scale}f
                )
            }

            LightNode(
                apply = {
                    intensity(100_000f)
                    color(1.0f, 0.97f, 0.93f)
                    direction(0f, -1f, -0.5f)
                }
            )
        }

        if (modelInstance == null) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center)
            )
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

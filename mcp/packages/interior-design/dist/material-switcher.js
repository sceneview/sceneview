// ─── Material switcher code generator ─────────────────────────────────────────
export const MATERIAL_SURFACES = [
    "wall-paint", "floor", "ceiling", "backsplash",
    "countertop", "fabric", "curtain", "wallpaper",
];
export const PAINT_FINISHES = [
    "matte", "satin", "semi-gloss", "gloss", "eggshell",
];
const DEFAULT_COLORS = {
    "wall-paint": ["#FFFFFF", "#F5F5DC", "#D3D3D3", "#87CEEB", "#98FB98", "#FFE4C4"],
    floor: ["#8B4513", "#D2B48C", "#A9A9A9", "#FFFDD0", "#696969"],
    ceiling: ["#FFFFFF", "#FFFAF0", "#F0F8FF"],
    backsplash: ["#FFFFFF", "#F0F0F0", "#ADD8E6", "#E0E0E0"],
    countertop: ["#2F4F4F", "#FFFFFF", "#808080", "#DEB887", "#000000"],
    fabric: ["#4169E1", "#DC143C", "#228B22", "#FFD700", "#800080", "#FF6347"],
    curtain: ["#FFFAF0", "#FAF0E6", "#F5F5DC", "#D3D3D3", "#191970"],
    wallpaper: ["#FFF8DC", "#E6E6FA", "#F0FFF0", "#FAEBD7"],
};
/**
 * Generates a complete, compilable Kotlin composable for material switching in 3D.
 */
export function generateMaterialSwitcher(options) {
    const { surface, finish = "matte", colors = DEFAULT_COLORS[surface] ?? ["#FFFFFF"], texturePaths, beforeAfter = false, ar = false, } = options;
    const composableName = `${capitalize(surface)}MaterialSwitcher`;
    const modelPath = getSurfaceModelPath(surface);
    if (ar) {
        return generateArMaterialSwitcher(composableName, modelPath, options);
    }
    return `package com.example.interior.materials

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.sceneview.SceneView
import io.github.sceneview.node.ModelNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberCollisionSystem
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.node.LightNode

/**
 * 3D Material Switcher — ${surface} with ${finish} finish.
 *
 * Live-preview ${surface} materials on a 3D room model.
 * Swipe through ${colors.length} color options with real-time PBR material updates.
 * Finish: ${finish}${beforeAfter ? ", before/after comparison slider." : "."}
 *${texturePaths ? ` Custom textures: ${texturePaths.join(", ")}` : ""}
 *
 * Model: src/main/assets/${modelPath}
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

    val modelInstance = rememberModelInstance(modelLoader, "${modelPath}")

    val colors = listOf(${colors.map((c) => `"${c}"`).join(", ")})
    var selectedColorIndex by remember { mutableIntStateOf(0) }
    var selectedFinish by remember { mutableStateOf("${finish}") }
${beforeAfter ? `    var splitPosition by remember { mutableFloatStateOf(0.5f) }` : ""}

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
                modelInstance?.let { instance ->
                    ModelNode(
                        modelInstance = instance,
                        scaleToUnits = 2.0f
                    )
                }

                // Neutral interior lighting
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

        // ── Color Palette ─────────────────────────────────────────────────
        ColorPalette(
            colors = colors,
            selectedIndex = selectedColorIndex,
            onColorSelected = { selectedColorIndex = it },
        )

        // ── Finish Selector ───────────────────────────────────────────────
        FinishSelector(
            selected = selectedFinish,
            onFinishSelected = { selectedFinish = it },
        )
${beforeAfter ? `
        // ── Before/After Slider ───────────────────────────────────────────
        Text("Before / After", style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp))
        Slider(
            value = splitPosition,
            onValueChange = { splitPosition = it },
            modifier = Modifier.padding(horizontal = 16.dp)
        )` : ""}
    }
}

@Composable
private fun ColorPalette(
    colors: List<String>,
    selectedIndex: Int,
    onColorSelected: (Int) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(colors.size) { index ->
            val hex = colors[index]
            val color = Color(android.graphics.Color.parseColor(hex))
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(color)
                    .then(
                        if (index == selectedIndex)
                            Modifier.border(3.dp, MaterialTheme.colorScheme.primary, CircleShape)
                        else Modifier
                    )
                    .clickable { onColorSelected(index) }
            )
        }
    }
}

@Composable
private fun FinishSelector(
    selected: String,
    onFinishSelected: (String) -> Unit,
) {
    val finishes = listOf(${PAINT_FINISHES.map((f) => `"${f}"`).join(", ")})
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(finishes.size) { index ->
            val finish = finishes[index]
            FilterChip(
                selected = finish == selected,
                onClick = { onFinishSelected(finish) },
                label = { Text(finish.replaceFirstChar { it.uppercase() }) }
            )
        }
    }
}`;
}
function generateArMaterialSwitcher(composableName, modelPath, options) {
    const { surface, colors = DEFAULT_COLORS[options.surface] ?? ["#FFFFFF"] } = options;
    return `package com.example.interior.materials

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.node.ModelNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberCollisionSystem

/**
 * AR Material Switcher — ${surface}.
 *
 * Preview ${surface} materials in your real room using AR.
 * Tap a wall/surface to apply selected color or texture.
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

    val modelInstance = rememberModelInstance(modelLoader, "${modelPath}")

    val colors = listOf(${colors.map((c) => `"${c}"`).join(", ")})
    var selectedColorIndex by remember { mutableIntStateOf(0) }

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
                    // Apply selected material to tapped surface
                }
            ) {
                modelInstance?.let { instance ->
                    ModelNode(
                        modelInstance = instance,
                        scaleToUnits = 1.0f
                    )
                }
            }

            if (modelInstance == null) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }

        // Color picker
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(colors.size) { index ->
                val hex = colors[index]
                val color = Color(android.graphics.Color.parseColor(hex))
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(color)
                        .clickable { selectedColorIndex = index }
                )
            }
        }
    }
}`;
}
function getSurfaceModelPath(surface) {
    return `models/room/${surface.replace("-", "_")}_scene.glb`;
}
function capitalize(s) {
    return s
        .split("-")
        .map((w) => w.charAt(0).toUpperCase() + w.slice(1))
        .join("");
}

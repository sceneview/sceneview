package io.github.sceneview.demo.demos

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.sceneview.SceneView
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.R
import io.github.sceneview.demo.LoadingScrim
import io.github.sceneview.demo.common.rememberModelDemoEnvironment
import io.github.sceneview.demo.rememberFirstFrameState
import io.github.sceneview.node.FogNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberView

/**
 * Demonstrates atmospheric fog applied to a 3D scene.
 *
 * Users can toggle fog on/off, adjust density via a slider, and pick from colour presets.
 * The fog is applied through [FogNode], which wraps Filament's per-View fog options.
 */
@Composable
fun FogDemo(onBack: () -> Unit) {
    data class FogPreset(val label: String, val color: Color)

    val presets = remember {
        listOf(
            FogPreset("Mist", Color(0xFFCCDDFF)),
            FogPreset("Warm Haze", Color(0xFFFFDDAA)),
            FogPreset("Eerie Green", Color(0xFFAAFFCC)),
            FogPreset("Deep Smoke", Color(0xFF888888))
        )
    }

    // Defaults captured once so the bottom-sheet "Reset" button (#1154 Stage 3)
    // can restore them without duplicating the literals.
    val defaultEnabled = true
    val defaultDensity = 0.15f
    val defaultPreset = presets[0]

    var fogEnabled by remember { mutableStateOf(defaultEnabled) }
    var fogDensity by remember { mutableFloatStateOf(defaultDensity) }
    var selectedPreset by remember { mutableStateOf(defaultPreset) }

    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)
    val view = rememberView(engine)
    val modelInstance = rememberModelInstance(modelLoader, "models/khronos_damaged_helmet.glb")

    // Camera orbits the helmet — the volumetric fog is world-space, so moving the
    // camera through the volume shows the depth falloff (fog thickens with distance)
    // in a way that a spinning model can't. Spin → model never leaves its cell, so
    // every frame shows roughly the same "amount" of fog between the eye and the
    // helmet surface, and the density slider looks less effective.
    val cameraManipulator = io.github.sceneview.demo.rememberHeroOrbitCameraManipulator(
        trigger = modelInstance != null,
        radius = 2.2f,
        yHeight = 0.3f,
        durationMillis = 20_000,
        staticYaw = 30f,
    )

    val firstFrame = rememberFirstFrameState()

    DemoScaffold(
        title = stringResource(R.string.demo_fog),
        onBack = onBack,
        firstFrameRendered = firstFrame.rendered,
        onResetSettings = {
            fogEnabled = defaultEnabled
            fogDensity = defaultDensity
            selectedPreset = defaultPreset
        },
        controls = {
            // Enable / disable toggle — toggleable on the whole row so tapping the
            // label flips the state, and UiAutomator finds a clickable ancestor.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = fogEnabled,
                        onValueChange = { fogEnabled = it },
                    ),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Fog Enabled", style = MaterialTheme.typography.bodyLarge)
                Switch(checked = fogEnabled, onCheckedChange = null)
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Density slider
            Text(
                text = "Density: ${"%.2f".format(fogDensity)}",
                style = MaterialTheme.typography.labelLarge
            )
            Slider(
                value = fogDensity,
                onValueChange = { fogDensity = it },
                valueRange = 0f..1f,
                enabled = fogEnabled
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Colour presets
            Text("Color Preset", style = MaterialTheme.typography.labelLarge)
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                presets.forEach { preset ->
                    FilterChip(
                        selected = selectedPreset == preset,
                        onClick = { selectedPreset = preset },
                        label = { Text(preset.label) },
                        enabled = fogEnabled
                    )
                }
            }
        }
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            SceneView(
                modifier = Modifier.fillMaxSize(),
                onFrame = firstFrame.onFrame,
                engine = engine,
                modelLoader = modelLoader,
                environmentLoader = environmentLoader,
                environment = rememberModelDemoEnvironment(environmentLoader),
                view = view,
                cameraManipulator = cameraManipulator,
            ) {
                FogNode(
                    view = view,
                    enabled = fogEnabled,
                    density = fogDensity,
                    color = selectedPreset.color,
                )
                modelInstance?.let { instance ->
                    ModelNode(
                        modelInstance = instance,
                        scaleToUnits = 0.5f,
                    )
                }
            }
            LoadingScrim(loading = modelInstance == null, label = "Loading helmet…")
        }
    }
}

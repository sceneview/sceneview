package io.github.sceneview.demo.demos

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.android.filament.View.AntiAliasing
import com.google.android.filament.View.Dithering
import io.github.sceneview.SceneView
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.R
import io.github.sceneview.demo.LoadingScrim
import io.github.sceneview.demo.rememberFirstFrameState
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Scale
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberView

/**
 * Demonstrates direct Filament [View] post-processing controls: SSAO, anti-aliasing, and dithering.
 *
 * The Filament [View] is created via [rememberView] and passed to [SceneView]. Toggle switches
 * modify the view's properties on every recomposition via [SideEffect]-style updates.
 *
 * The helmet is staged sitting **on a ground plane** rather than floating in the void: SSAO
 * darkens contact zones and crevices, so the soft shadow where the helmet meets the floor only
 * exists with SSAO on. Toggling the SSAO switch makes that contact shadow flatly appear and
 * disappear — the post-processing difference reads at a glance instead of being a subtle change
 * a user can easily miss (#1443).
 */
@Composable
fun PostProcessingDemo(onBack: () -> Unit) {
    // Initial state mirrors the SDK's library defaults from `SceneFactories.kt:93-112`
    // (`createView`): SSAO on, MSAA off, FXAA on, dithering on. Pre-#1076 this demo
    // shipped `ssaoEnabled = false` which silently disabled SSAO on first paint,
    // teaching users the wrong default. Now the toggles reflect what the library
    // actually does out of the box; flipping them shows the user the contrast
    // vs. the default.
    var ssaoEnabled by remember { mutableStateOf(true) }
    var msaaEnabled by remember { mutableStateOf(false) }
    var fxaaEnabled by remember { mutableStateOf(true) }
    var ditheringEnabled by remember { mutableStateOf(true) }

    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)
    val view = rememberView(engine)
    val modelInstance = rememberModelInstance(modelLoader, "models/khronos_damaged_helmet.glb")

    // A light, matte ground the helmet rests on. SSAO darkens the contact zone
    // between the model and this plane, so toggling SSAO makes the soft contact
    // shadow visibly appear/disappear — the whole point of the demo (#1443).
    val groundBitmap = remember { createMattGroundBitmap() }

    // Apply post-processing settings to the Filament View after composition lands —
    // a SideEffect runs on every successful recomposition so toggles actually take
    // effect on the next rendered frame. Writing to `view` directly inside the body
    // would work today but is fragile: Filament's options getters currently return
    // the same mutable struct instance, so any future change that returns a defensive
    // copy would silently drop SSAO/FXAA/dithering writes.
    SideEffect {
        view.ambientOcclusionOptions = view.ambientOcclusionOptions.apply {
            enabled = ssaoEnabled
        }
        view.multiSampleAntiAliasingOptions = view.multiSampleAntiAliasingOptions.apply {
            enabled = msaaEnabled
        }
        view.antiAliasing = if (fxaaEnabled) AntiAliasing.FXAA else AntiAliasing.NONE
        view.dithering = if (ditheringEnabled) Dithering.TEMPORAL else Dithering.NONE
    }

    // Camera orbits the helmet from a slightly raised angle so the ground plane
    // reads as a floor receding into the scene — that angle is what makes the
    // SSAO contact shadow under the helmet visible. SSAO / FXAA / dithering read
    // best on a static model where the user can catch aliasing at grazing angles
    // as the camera moves; a spinning helmet would sweep its surface through the
    // same screen pixels so edge aliasing is harder to compare between AA modes.
    val cameraManipulator = io.github.sceneview.demo.rememberHeroOrbitCameraManipulator(
        trigger = modelInstance != null,
        radius = 2.0f,
        yHeight = 0.7f,
        durationMillis = 20_000,
        staticYaw = 30f,
    )

    val firstFrame = rememberFirstFrameState()

    DemoScaffold(
        title = stringResource(R.string.demo_post_processing_title),
        onBack = onBack,
        firstFrameRendered = firstFrame.rendered,
        controls = {
            Text(
                "Toggle SSAO and watch the soft contact shadow where the helmet " +
                    "meets the floor appear and disappear.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text("Render Effects", style = MaterialTheme.typography.labelLarge)
            Spacer(modifier = Modifier.height(4.dp))
            ToggleRow("SSAO (Ambient Occlusion)", ssaoEnabled) { ssaoEnabled = it }
            ToggleRow("MSAA (4x Multi-Sample)", msaaEnabled) { msaaEnabled = it }
            ToggleRow("FXAA (Fast Approx. AA)", fxaaEnabled) { fxaaEnabled = it }
            ToggleRow("Temporal Dithering", ditheringEnabled) { ditheringEnabled = it }
        }
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            SceneView(
                modifier = Modifier.fillMaxSize(),
                onFrame = firstFrame.onFrame,
                engine = engine,
                modelLoader = modelLoader,
                materialLoader = materialLoader,
                environmentLoader = environmentLoader,
                view = view,
                cameraManipulator = cameraManipulator,
            ) {
                // Ground plane the helmet rests on. Laid flat (rotated -90° about
                // X) and pushed down to the base of the model so SSAO has a
                // contact surface to darken.
                ImageNode(
                    bitmap = groundBitmap,
                    position = Position(x = 0f, y = -0.27f, z = 0f),
                    rotation = Rotation(x = -90f),
                    scale = Scale(2.6f),
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

/**
 * A plain, light, matte ground bitmap. Kept deliberately flat and uniform so the
 * only thing the eye picks up near the helmet's base is the SSAO contact shadow —
 * a textured or patterned floor would compete with it.
 */
private fun createMattGroundBitmap(): Bitmap {
    val size = 256
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(0xFFB8BCC4.toInt())
    // A faint inset border so the plane reads as a finite surface, not an
    // infinite void-colored quad.
    val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFA0A4AC.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }
    val inset = 10f
    canvas.drawRect(inset, inset, size - inset, size - inset, borderPaint)
    return bitmap
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                onValueChange = onCheckedChange,
            ),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = null)
    }
}

package io.github.sceneview.demo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.google.android.filament.View
import io.github.sceneview.FrameRatePolicy
import io.github.sceneview.RenderQuality
import io.github.sceneview.SceneView
import io.github.sceneview.demo.theme.SceneViewDemoTheme
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.math.Direction
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMainLightNode
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberView

/**
 * Debug-only side-by-side of the render presets on a plain `SceneView`: one bundled model, the
 * library's own camera, environment and lights, nothing the demo screens add on top. Used to pick
 * the out-of-the-box look (`RenderQuality.Default`) against what it replaced.
 *
 * ```
 * adb shell am start -n io.github.sceneview.demo.qa/io.github.sceneview.demo.RenderPresetQaActivity \
 *     --es model models/khronos_damaged_helmet.glb --es preset before|default|cinematic|performance \
 *     [--ef units 1.0]
 * ```
 *
 * `before` is the default as it shipped until this preset was retuned: SSAO at Filament's LOW
 * quality with bilinear upsampling, hard PCF shadows, the main light pointing straight down.
 * `units` scales the model to that size; without it the model keeps its authored size, which is
 * what an app gets by default.
 */
class RenderPresetQaActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val model = intent.getStringExtra(EXTRA_MODEL) ?: "models/khronos_damaged_helmet.glb"
        val preset = intent.getStringExtra(EXTRA_PRESET) ?: "default"
        val units = intent.getFloatExtra(EXTRA_UNITS, 0f).takeIf { it > 0f }
        val before = preset == "before"
        val quality = when (preset) {
            "cinematic" -> RenderQuality.Cinematic
            "performance" -> RenderQuality.Performance
            else -> RenderQuality.Default
        }
        setContent {
            SceneViewDemoTheme {
                Box(Modifier.fillMaxSize()) {
                    val engine = rememberEngine()
                    val modelLoader = rememberModelLoader(engine)
                    val view = rememberView(engine)
                    val modelInstance = rememberModelInstance(modelLoader, model)
                    val mainLight = rememberMainLightNode(engine) {
                        if (before) lightDirection = Direction(0f, -1f, 0f)
                    }
                    SceneView(
                        modifier = Modifier.fillMaxSize(),
                        engine = engine,
                        modelLoader = modelLoader,
                        view = view,
                        mainLightNode = mainLight,
                        renderQuality = quality,
                        frameRatePolicy = FrameRatePolicy.Continuous(),
                        onFrame = { if (before) view.applyPreviousDefault() },
                    ) {
                        modelInstance?.let { ModelNode(modelInstance = it, scaleToUnits = units) }
                    }
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .safeDrawingPadding()
                            .padding(SceneViewTokens.Space.md),
                    ) {
                        Text(
                            text = preset,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(SceneViewTokens.Space.sm),
                        )
                    }
                }
            }
        }
    }

    /** The view half of the default as it shipped before the premium retune. */
    private fun View.applyPreviousDefault() {
        ambientOcclusionOptions = ambientOcclusionOptions.apply {
            quality = View.QualityLevel.LOW
            upsampling = View.QualityLevel.LOW
        }
        setShadowType(View.ShadowType.PCF)
    }

    private companion object {
        const val EXTRA_MODEL = "model"
        const val EXTRA_PRESET = "preset"
        const val EXTRA_UNITS = "units"
    }
}

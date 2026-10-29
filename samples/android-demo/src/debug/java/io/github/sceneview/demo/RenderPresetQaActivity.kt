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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.google.android.filament.ColorGrading
import com.google.android.filament.LightManager
import com.google.android.filament.ToneMapper
import com.google.android.filament.View
import io.github.sceneview.DEFAULT_MAIN_LIGHT_COLOR
import io.github.sceneview.DEFAULT_MAIN_LIGHT_COLOR_INTENSITY
import io.github.sceneview.DEFAULT_MAIN_LIGHT_DIRECTION
import io.github.sceneview.FrameRatePolicy
import io.github.sceneview.RenderQuality
import io.github.sceneview.SceneView
import io.github.sceneview.createEnvironment
import io.github.sceneview.demo.theme.SceneViewDemoTheme
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.demo.ui.viewer.ViewerBackdrop
import io.github.sceneview.math.Position
import io.github.sceneview.math.Size
import io.github.sceneview.node.LightNode
import io.github.sceneview.rememberCameraManipulator
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberEnvironment
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberNode
import io.github.sceneview.rememberView
import kotlin.math.cos
import kotlin.math.sin

/**
 * Debug-only comparison of the render presets, staged like a product shot so a reviewer can judge
 * the out-of-the-box look (`RenderQuality.Default`) against what it replaced.
 *
 * Every column gets the same stage: the library's own image-based light, a light studio backdrop
 * (`stage-studio` horizon token, painted through [ViewerBackdrop]), a matte floor that receives
 * the main light's shadow map, and a three-quarter high-angle camera. Only the preset changes.
 *
 * ```
 * adb shell am start -n io.github.sceneview.demo.qa/io.github.sceneview.demo.RenderPresetQaActivity \
 *     --es model models/khronos_damaged_helmet.glb \
 *     --es preset before|default|middle|cinematic|performance|agx \
 *     [--ef dist 2.6] [--ef ty 0.35] [--ef yaw -35] [--ef pitch 15] [--ef lx .. --ef ly .. --ef lz ..]
 * ```
 *
 * - `before`: the default as it shipped until this retune — SSAO at Filament's LOW quality with
 *   bilinear upsampling, hard PCF shadows, the main light pointing straight down.
 * - `middle`: a cheaper candidate — the new default with PCF instead of DPCF, plus screen-space
 *   contact shadows on the key light.
 * - `agx`: the new default under Filament's AgX tone mapper, for reference only (the backdrop is
 *   compensated for PBR Neutral, so its tone shifts in that column).
 */
class RenderPresetQaActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val model = intent.getStringExtra(EXTRA_MODEL) ?: "models/khronos_damaged_helmet.glb"
        val preset = intent.getStringExtra(EXTRA_PRESET) ?: "default"
        val label = intent.getStringExtra(EXTRA_LABEL)
        val distance = intent.getFloatExtra(EXTRA_DISTANCE, DEFAULT_DISTANCE)
        val targetY = intent.getFloatExtra(EXTRA_TARGET_Y, DEFAULT_TARGET_Y)
        val yaw = Math.toRadians(intent.getFloatExtra(EXTRA_YAW, DEFAULT_YAW_DEGREES).toDouble()).toFloat()
        val pitch = Math.toRadians(intent.getFloatExtra(EXTRA_PITCH, DEFAULT_PITCH_DEGREES).toDouble()).toFloat()
        // Optional key-light override, to compare candidate directions under one preset.
        val lightOverride = floatArrayOf(
            intent.getFloatExtra(EXTRA_LIGHT_X, Float.NaN),
            intent.getFloatExtra(EXTRA_LIGHT_Y, Float.NaN),
            intent.getFloatExtra(EXTRA_LIGHT_Z, Float.NaN),
        ).takeIf { it.none(Float::isNaN) }
        val quality = when (preset) {
            "cinematic" -> RenderQuality.Cinematic
            "performance" -> RenderQuality.Performance
            else -> RenderQuality.Default
        }
        val target = Position(y = targetY)
        val eye = Position(
            x = distance * cos(pitch) * sin(yaw),
            y = targetY + distance * sin(pitch),
            z = distance * cos(pitch) * cos(yaw),
        )
        setContent {
            SceneViewDemoTheme {
                Box(Modifier.fillMaxSize()) {
                    val engine = rememberEngine()
                    val modelLoader = rememberModelLoader(engine)
                    val materialLoader = rememberMaterialLoader(engine)
                    val environmentLoader = rememberEnvironmentLoader(engine)
                    val view = rememberView(engine)
                    val modelInstance = rememberModelInstance(modelLoader, model)
                    val backdrop = SceneViewTokens.Showroom.Light.horizon
                    val environment = rememberEnvironment(environmentLoader) {
                        // The library's default image-based light, in front of a studio wall.
                        createEnvironment(environmentLoader).copy(
                            skybox = ViewerBackdrop.create(engine, backdrop),
                        )
                    }
                    val floorMaterial = remember(materialLoader) {
                        materialLoader.createColorInstance(
                            SceneViewTokens.Showroom.Light.floor,
                            metallic = 0f,
                            roughness = FLOOR_ROUGHNESS,
                        )
                    }
                    val mainLight = rememberNode {
                        LightNode(engine, LightManager.Type.DIRECTIONAL) {
                            color(DEFAULT_MAIN_LIGHT_COLOR.r, DEFAULT_MAIN_LIGHT_COLOR.g, DEFAULT_MAIN_LIGHT_COLOR.b)
                            intensity(DEFAULT_MAIN_LIGHT_COLOR_INTENSITY)
                            if (lightOverride != null) {
                                direction(lightOverride[0], lightOverride[1], lightOverride[2])
                            } else if (preset == "before") {
                                direction(0f, -1f, 0f)
                            } else {
                                direction(
                                    DEFAULT_MAIN_LIGHT_DIRECTION.x,
                                    DEFAULT_MAIN_LIGHT_DIRECTION.y,
                                    DEFAULT_MAIN_LIGHT_DIRECTION.z,
                                )
                            }
                            castShadows(true)
                            if (preset == "middle") {
                                shadowOptions(
                                    LightManager.ShadowOptions().apply { screenSpaceContactShadows = true },
                                )
                            }
                        }
                    }
                    val cameraNode = rememberCameraNode(engine) {
                        position = eye
                        lookAt(target)
                    }
                    val agx = remember(engine) {
                        ColorGrading.Builder().toneMapper(ToneMapper.Agx()).build(engine)
                    }
                    SceneView(
                        modifier = Modifier.fillMaxSize(),
                        engine = engine,
                        modelLoader = modelLoader,
                        materialLoader = materialLoader,
                        environmentLoader = environmentLoader,
                        view = view,
                        environment = environment,
                        mainLightNode = mainLight,
                        cameraNode = cameraNode,
                        cameraManipulator = rememberCameraManipulator(
                            orbitHomePosition = eye,
                            targetPosition = target,
                        ),
                        renderQuality = quality,
                        autoCenterContent = false,
                        frameRatePolicy = FrameRatePolicy.Continuous(),
                        onFrame = {
                            when (preset) {
                                "before" -> view.applyPreviousDefault()
                                "middle" -> view.setShadowType(View.ShadowType.PCF)
                                "agx" -> view.colorGrading = agx
                            }
                        },
                    ) {
                        // A floor quad is built from x and z: Plane spans its size in all three axes.
                        PlaneNode(size = Size(FLOOR_SIZE, 0f, FLOOR_SIZE), materialInstance = floorMaterial)
                        modelInstance?.let {
                            // Bottom of the model on the floor.
                            ModelNode(
                                modelInstance = it,
                                scaleToUnits = MODEL_UNITS,
                                centerOrigin = Position(0f, -1f, 0f),
                                apply = { if (preset == "middle") setScreenSpaceContactShadows(true) },
                            )
                        }
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
                            text = label ?: LABELS[preset] ?: preset,
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
        // What the comparison sheet shows above each column.
        val LABELS = mapOf(
            "before" to "Previous default",
            "default" to "New Default",
            "middle" to "PCF + contact shadows",
            "cinematic" to "Cinematic",
            "performance" to "Performance",
            "agx" to "AgX (reference)",
        )
        const val EXTRA_MODEL = "model"
        const val EXTRA_PRESET = "preset"
        const val EXTRA_DISTANCE = "dist"
        const val EXTRA_TARGET_Y = "ty"
        const val DEFAULT_DISTANCE = 3.0f
        const val DEFAULT_TARGET_Y = 0.35f
        const val MODEL_UNITS = 1f
        const val FLOOR_SIZE = 20f
        const val FLOOR_ROUGHNESS = 0.9f

        // Three-quarter view from the front left, looking slightly down like a product
        // photographer; the key light comes from the right, so its shadow falls into view.
        const val EXTRA_LABEL = "label"
        const val EXTRA_LIGHT_X = "lx"
        const val EXTRA_LIGHT_Y = "ly"
        const val EXTRA_LIGHT_Z = "lz"
        const val EXTRA_YAW = "yaw"
        const val EXTRA_PITCH = "pitch"
        const val DEFAULT_YAW_DEGREES = -35f
        const val DEFAULT_PITCH_DEGREES = 15f
    }
}

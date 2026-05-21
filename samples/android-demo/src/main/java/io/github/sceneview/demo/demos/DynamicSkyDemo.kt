package io.github.sceneview.demo.demos

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.sceneview.SceneView
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.LoadingScrim
import io.github.sceneview.demo.R
import io.github.sceneview.demo.rememberFirstFrameState
import io.github.sceneview.math.Position
import io.github.sceneview.node.DynamicSkyNode
import io.github.sceneview.rememberCameraManipulator
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader

/**
 * Demonstrates [DynamicSkyNode] — a time-of-day sun that changes colour, intensity, and
 * direction as a slider moves from 0 h (midnight) to 24 h.
 */
@Composable
fun DynamicSkyDemo(onBack: () -> Unit) {
    var timeOfDay by remember { mutableFloatStateOf(12f) }
    var turbidity by remember { mutableFloatStateOf(2f) }

    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)
    val modelInstance = rememberModelInstance(modelLoader, "models/khronos_damaged_helmet.glb")
    // Pick an HDR environment that matches the current time of day. Without this
    // the SceneView default neutral_ibl gives a black-ish skybox that hides the
    // dynamic sun entirely (QA finding 2026-05-11 — "Dynamic Sky black at noon").
    // Three buckets is a coarse approximation, but it covers the three obvious
    // user expectations: night = stars / lights, dawn / dusk = sunset, daytime
    // = blue sky.
    val envAsset = when {
        timeOfDay < 6f || timeOfDay >= 19f -> "environments/rooftop_night_2k.hdr"
        timeOfDay < 9f || timeOfDay >= 17f -> "environments/sunset_2k.hdr"
        else                               -> "environments/outdoor_cloudy_2k.hdr"
    }
    val environment = io.github.sceneview.rememberEnvironment(
        environmentLoader = environmentLoader,
    ) {
        environmentLoader.createHDREnvironment(envAsset)!!
    }

    // Period label for the user — gives continuous visual feedback as the slider moves,
    // even when the HDR env is the same across multiple hours. QA finding 2026-05-11 :
    // "slider has zero visual effect" — because the HDR env only swaps at 3 buckets and
    // the sun direction change is hard to see against a static skybox. Showing the
    // period label means every slider movement updates SOMETHING the user can see.
    val periodLabel = when {
        timeOfDay < 5f          -> "🌙 Night"
        timeOfDay < 7f          -> "🌅 Dawn"
        timeOfDay < 10f         -> "🌄 Morning"
        timeOfDay < 14f         -> "☀️ Noon"
        timeOfDay < 17f         -> "🌤️ Afternoon"
        timeOfDay < 19f         -> "🌇 Sunset"
        timeOfDay < 21f         -> "🌆 Dusk"
        else                    -> "🌙 Night"
    }

    val firstFrame = rememberFirstFrameState()

    DemoScaffold(
        title = stringResource(R.string.demo_dynamic_sky),
        onBack = onBack,
        firstFrameRendered = firstFrame.rendered,
        controls = {
            Text(
                "Time of Day: %.1f h  ·  $periodLabel".format(timeOfDay),
                style = MaterialTheme.typography.labelLarge
            )
            Slider(
                value = timeOfDay,
                onValueChange = { timeOfDay = it },
                valueRange = 0f..24f
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Turbidity: %.1f".format(turbidity),
                style = MaterialTheme.typography.labelLarge
            )
            Slider(
                value = turbidity,
                onValueChange = { turbidity = it },
                valueRange = 1f..10f
            )
        }
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            SceneView(
                modifier = Modifier.fillMaxSize(),
                onFrame = firstFrame.onFrame,
                engine = engine,
                modelLoader = modelLoader,
                environmentLoader = environmentLoader,
                environment = environment,
                // Disable the constant 110 klx default main light so the DynamicSkyNode's SUN is
                // the only directional contribution. The HDR IBL ambient fill keeps the helmet
                // visible at night when the sun is below the horizon, and the matching skybox
                // gives the user a clear visual feedback that time-of-day actually changed.
                mainLightNode = null,
                cameraManipulator = rememberCameraManipulator()
            ) {
                DynamicSkyNode(
                    timeOfDay = timeOfDay,
                    turbidity = turbidity,
                    // 500 klx (5x the default) so the sun visibly dominates even with the
                    // default IBL ambient — otherwise the neutral IBL's constant ~30 klx
                    // contribution masks the time-of-day changes on the metallic helmet
                    // (PBR reflections = mostly IBL). Bright sun = visible difference.
                    sunIntensity = 500_000f,
                )

                modelInstance?.let { instance ->
                    ModelNode(
                        modelInstance = instance,
                        scaleToUnits = 0.5f,
                        position = Position(y = 0f)
                    )
                }
            }
            // Mirror every other helmet-loading demo: hold a scrim until the
            // model instance is ready so the helmet's async pop-in is hidden
            // behind a labelled placeholder instead of appearing on a bare sky.
            LoadingScrim(loading = modelInstance == null, label = "Loading helmet…")
        }
    }
}

package io.github.sceneview.demo.auto

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.filament.Colors
import io.github.sceneview.ExperimentalSceneViewApi
import io.github.sceneview.RenderQuality
import io.github.sceneview.SceneView
import io.github.sceneview.environment.rememberHDREnvironment
import io.github.sceneview.math.Direction
import io.github.sceneview.math.toColor
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberEnvironment
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberMainLightNode
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberRenderInvalidator
import io.github.sceneview.sample.SceneviewTheme
import kotlin.math.exp
import kotlin.math.max

/**
 * Night Garage — SceneView as an Android Auto **parked app**.
 *
 * A plain activity: on a phone running Android 15 or later, Android Auto shows it on the car's
 * screen while the car is parked (see the manifest for the two declarations that make it one).
 * Nothing here is car-specific code — the same activity runs on the phone — which is the point:
 * a SceneView scene needs no template, no host and no Car App Library to reach a head unit.
 *
 * One scene: a car on a turntable in a dark garage. Drag orbits, pinch zooms, and three large
 * controls step through the cars, their finishes and the lighting.
 */
class GarageActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // A showroom is the whole screen. On a head unit Android Auto owns the system chrome;
        // on a phone the bars come back with a swipe.
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        val store = GarageStore(applicationContext)
        setContent {
            // The theme follows the system. The garage's own chrome is glass over media — the
            // theme-independent `DESIGN.md` tokens — so it reads the same in light and dark.
            SceneviewTheme(dynamicColor = false) {
                GarageScreen(store)
            }
        }
    }
}

@OptIn(ExperimentalSceneViewApi::class)
@Composable
private fun GarageScreen(store: GarageStore) {
    var selection by remember { mutableStateOf(store.load()) }
    val car = GarageCatalog.cars[selection.car]
    val lighting = GarageCatalog.lightings[selection.lighting]

    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)
    val cameraNode = rememberCameraNode(engine)
    val invalidator = rememberRenderInvalidator()

    // Every car is loaded up front (22 MB of glTF) and stays in the scene: a switch is then a
    // visibility flip, not a load. `rememberModelInstance` keeps Filament on the main thread.
    val instances = GarageCatalog.cars.map { entry ->
        key(entry.assetPath) { rememberModelInstance(modelLoader, entry.assetPath) }
    }

    // Reflections come from the HDR, with no skybox: the garage stays dark and the car carries
    // the light. A switch keeps the previous environment until the next one is decoded.
    val fallbackEnvironment = rememberEnvironment(environmentLoader)
    val hdrEnvironment = rememberHDREnvironment(environmentLoader, lighting.hdrPath, createSkybox = false)
    val mainLight = rememberMainLightNode(engine) {
        intensity = lighting.keyIntensity
        color = Colors.cct(lighting.keyKelvin).toColor()
        lightDirection = KEY_LIGHT_DIRECTION
    }

    // The reveal. Frames are counted once everything is in memory: for the first few every car
    // is drawn — under the cover — so each one's shaders and textures are warm before it is ever
    // picked; then the cover lifts on the selected car alone.
    val loaded = hdrEnvironment != null && instances.all { it != null }
    var presentedFrames by remember { mutableIntStateOf(0) }
    val warmed = presentedFrames >= WARM_FRAMES
    val revealed = presentedFrames >= REVEAL_FRAMES
    val coverAlpha by animateFloatAsState(
        targetValue = if (revealed) 0f else 1f,
        animationSpec = AutoTokens.handover,
        label = "cover",
    )

    // The finish is a write to raw Filament material state, which reports nothing to the
    // on-demand renderer — ask for the frame. Re-applied when the car comes back, too.
    val instance = instances[selection.car]
    LaunchedEffect(instance, selection.car, selection.paint) {
        val paint = car.paints.getOrNull(selection.paint) ?: return@LaunchedEffect
        instance?.applyPaint(car, paint)
        invalidator.requestRender()
    }

    // Turntable and camera, one loop. A finger on the stage stops the turntable — the driver is
    // looking at something — and it eases back into its spin a moment after the last touch.
    val orbit = remember { OrbitCamera() }
    var turntableYaw by remember { mutableFloatStateOf(0f) }
    var touching by remember { mutableStateOf(false) }
    var releasedAt by remember { mutableLongStateOf(0L) }
    LaunchedEffect(cameraNode) {
        // Lift the car into the band between the title and the controls.
        cameraNode.setShift(0.0, STAGE_SHIFT)
        orbit.applyTo(cameraNode)
        var last = 0L
        var spin = 1f
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0f else ((now - last) / NANOS_PER_SECOND).coerceAtMost(MAX_FRAME_STEP_S)
                last = now
                val held = touching || System.nanoTime() - releasedAt < SPIN_RESUME_NANOS
                spin += ((if (held) 0f else 1f) - spin) * (1f - exp(-SPIN_EASE_RATE * dt))
                turntableYaw = (turntableYaw + SPIN_DEGREES_PER_S * spin * dt) % FULL_TURN
                if (!orbit.settled) {
                    orbit.step(dt)
                    orbit.applyTo(cameraNode)
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AutoTokens.Stage.background)
            // The framing was tuned on a landscape head unit; a narrower screen backs away so
            // the whole car stays in frame (a portrait head unit is 1042 x 1080).
            .onSizeChanged { size ->
                if (size.height > 0) {
                    orbit.fit = max(1f, REFERENCE_ASPECT / (size.width.toFloat() / size.height))
                }
            }
    ) {
        SceneView(
            modifier = Modifier.fillMaxSize(),
            engine = engine,
            modelLoader = modelLoader,
            environmentLoader = environmentLoader,
            environment = hdrEnvironment ?: fallbackEnvironment,
            mainLightNode = mainLight,
            cameraNode = cameraNode,
            // [OrbitCamera] owns the camera: eased, clamped above the floor, aspect-aware.
            cameraManipulator = null,
            // Positions are authored: the podium is the origin and the cars are parked on it.
            autoCenterContent = false,
            renderQuality = RenderQuality.Cinematic,
            renderInvalidator = invalidator,
            onFrame = { if (loaded && presentedFrames < REVEAL_FRAMES) presentedFrames++ },
        ) {
            GarageFloor()
            Turntable(yaw = { turntableYaw }) {
                GarageCatalog.cars.forEachIndexed { index, entry ->
                    key(entry.assetPath) {
                        instances[index]?.let { parked ->
                            ParkedCar(
                                instance = parked,
                                visible = !warmed || index == selection.car,
                                shadow = warmed && index == selection.car,
                            )
                        }
                    }
                }
            }
        }

        // Gestures, above the scene and under the chrome. Drag turns the camera around the car
        // the way the finger pushes it; pinch moves in and out.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        touching = true
                        do {
                            val event = awaitPointerEvent()
                        } while (event.changes.any { it.pressed })
                        touching = false
                        releasedAt = System.nanoTime()
                    }
                }
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        orbit.orbitBy(
                            dYaw = -pan.x / density * ORBIT_DEGREES_PER_DP,
                            dPitch = pan.y / density * ORBIT_DEGREES_PER_DP,
                        )
                        if (zoom != 1f) orbit.zoomBy(1f / zoom)
                    }
                }
        )

        ChromeScrims()

        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(AutoTokens.Space.lg)
        ) {
            TitleBlock(car = car, modifier = Modifier.align(Alignment.TopStart))
            ControlRow(
                selection = selection,
                onSelection = { next ->
                    selection = next
                    store.save(next)
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
            )
        }

        if (coverAlpha > 0f) LoadingCover(alpha = coverAlpha)
    }
}

/** A key light from the front-left, high: it rakes the bonnet and drops the shadow behind. */
private val KEY_LIGHT_DIRECTION = Direction(x = -0.45f, y = -1f, z = -0.55f)

private const val NANOS_PER_SECOND = 1e9f
private const val FULL_TURN = 360f

/** Longest step the loop integrates: a frame that long is a pause, not motion. */
private const val MAX_FRAME_STEP_S = 0.1f

/** One turn in 45 s: slow enough to read the body lines, fast enough to be alive. */
private const val SPIN_DEGREES_PER_S = 8f
private const val SPIN_EASE_RATE = 2.5f
private const val SPIN_RESUME_NANOS = 2_500_000_000L

/** A swipe across a 800 dp screen turns the camera a little over half a turn. */
private const val ORBIT_DEGREES_PER_DP = 0.25f

/** Vertical lens shift, in the camera's normalised units. */
private const val STAGE_SHIFT = 0.05

/** Aspect ratio the home framing fills: 800 x 480, the smallest landscape head unit. */
private const val REFERENCE_ASPECT = 1.6f

/** Frames every car is drawn for under the cover, then frames of the final scene before it lifts. */
private const val WARM_FRAMES = 4
private const val REVEAL_FRAMES = 8

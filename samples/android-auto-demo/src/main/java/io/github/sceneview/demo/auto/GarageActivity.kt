package io.github.sceneview.demo.auto

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Density
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
 * SceneView Drive — SceneView as an Android Auto **parked app**.
 *
 * A plain activity: on a phone running Android 15 or later, Android Auto shows it on the car's
 * screen while the car is parked (see the manifest for the two declarations that make it one).
 * Nothing here is car-specific code — the same activity runs on the phone — which is the point:
 * a SceneView scene needs no template, no host and no Car App Library to reach a head unit.
 *
 * One scene, two ways to be in it. The **showroom**: a car on a turntable in a dark garage —
 * drag orbits, pinch zooms, three large controls step through the cars, their finishes and the
 * lighting. And **Drive**: the same car, off the podium and on the garage floor, steered with
 * the left thumb and driven with the right, a chase camera behind it.
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
    // Showroom or road. `driving` is what the scene shows; `driveRequested` is what was asked
    // for, and the curtain closes between the two so the car and the camera swap places unseen.
    var driveRequested by rememberSaveable { mutableStateOf(false) }
    var driving by remember { mutableStateOf(false) }
    val curtain = remember { Animatable(0f) }
    val pad = remember { DrivePad() }
    LaunchedEffect(driveRequested) {
        if (driveRequested == driving) return@LaunchedEffect
        curtain.animateTo(1f, AutoTokens.fade)
        pad.release()
        driving = driveRequested
        // Two frames of the new scene under the curtain before it opens.
        repeat(2) { withFrameNanos { } }
        curtain.animateTo(0f, AutoTokens.fade)
    }
    BackHandler(enabled = driveRequested) { driveRequested = false }

    // At night a car on the road lights its own way: the key light steps back so the beams are
    // what the driver sees the floor by.
    val headlights = driving && lighting.headlights
    val keyIntensity = lighting.keyIntensity * if (headlights) GarageStage.HEADLIGHT_KEY_SHARE else 1f
    val mainLight = rememberMainLightNode(engine) {
        intensity = keyIntensity
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
        instance?.applyPaint(paint)
        invalidator.requestRender()
    }

    // One loop for everything that moves. In the showroom, the turntable and the orbit camera:
    // a finger on the stage stops the turntable — the driver is looking at something — and it
    // eases back into its spin a moment after the last touch. On the road, the car and the
    // camera chasing it.
    val orbit = remember { OrbitCamera() }
    val pose = remember { MountPose() }
    val drive = remember { DriveModel() }
    val isDriving by rememberUpdatedState(driving)
    val drivenCar by rememberUpdatedState(car)
    var touching by remember { mutableStateOf(false) }
    var releasedAt by remember { mutableLongStateOf(0L) }
    LaunchedEffect(cameraNode) {
        // Lift the car into the band between the title and the controls.
        cameraNode.setShift(0.0, STAGE_SHIFT)
        orbit.applyTo(cameraNode)
        var last = 0L
        var spin = 0f
        var turntableYaw = 0f
        var chase: ChaseCamera? = null
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0f else ((now - last) / NANOS_PER_SECOND).coerceAtMost(MAX_FRAME_STEP_S)
                last = now
                if (isDriving) {
                    val camera = chase ?: ChaseCamera(drivenCar.bodyLength).also {
                        drive.reset()
                        it.snapTo(drive)
                        chase = it
                    }
                    drive.step(dt, pad.input)
                    pose.onRoad(drive)
                    camera.step(dt, drive, cameraNode, orbit.fit, GarageStage.FLOOR_Y)
                } else {
                    if (chase != null) {
                        // Back from the road: the car returns to the podium the way it left it.
                        chase = null
                        spin = 0f
                        orbit.invalidate()
                    }
                    // Still until the cover lifts: the reveal opens on the home three-quarter view.
                    val covered = presentedFrames < REVEAL_FRAMES
                    val held = covered || touching ||
                        System.nanoTime() - releasedAt < SPIN_RESUME_NANOS
                    spin += ((if (held) 0f else 1f) - spin) * (1f - exp(-SPIN_EASE_RATE * dt))
                    turntableYaw = (turntableYaw + SPIN_DEGREES_PER_S * spin * dt) % FULL_TURN
                    pose.onPodium(turntableYaw)
                    if (!orbit.settled) {
                        orbit.step(dt)
                        orbit.applyTo(cameraNode)
                    }
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
            Podium()
            CarMount(pose) {
                GarageCatalog.cars.forEachIndexed { index, entry ->
                    key(entry.assetPath) {
                        instances[index]?.let { parked ->
                            val onShow = warmed && index == selection.car
                            GarageCar(
                                car = entry,
                                instance = parked,
                                visible = !warmed || index == selection.car,
                                shadow = onShow,
                                onRoad = driving,
                                headlights = onShow && headlights,
                            )
                        }
                    }
                }
            }
        }

        // Gestures, above the scene and under the chrome. Drag turns the camera around the car
        // the way the finger pushes it; pinch moves in and out. On the road the camera follows
        // the car and the stage takes no gesture.
        if (!driving) {
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
        }

        ChromeScrims()

        // The chrome is laid out for the smallest head unit and magnified on a bigger one: at
        // 1920 x 1080 the same dp count is read from further away.
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val base = LocalDensity.current
            val scale = AutoTokens.Car.chromeScale(maxWidth, maxHeight)
            CompositionLocalProvider(
                LocalDensity provides Density(base.density * scale, base.fontScale),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .padding(AutoTokens.Space.lg)
                ) {
                    TitleBlock(
                        eyebrow = stringResource(if (driving) R.string.drive_title else R.string.garage_title),
                        car = car,
                        modifier = Modifier.align(Alignment.TopStart),
                    )
                    if (driving) {
                        ActionControl(
                            text = stringResource(R.string.drive_exit),
                            onClick = { driveRequested = false },
                            modifier = Modifier.align(Alignment.TopEnd),
                            forward = false,
                        )
                        DrivePads(pad)
                    } else {
                        ControlRow(
                            selection = selection,
                            onSelection = { next ->
                                selection = next
                                store.save(next)
                            },
                            onDrive = { driveRequested = true },
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth(),
                        )
                    }
                }
                // Inside the magnified chrome: the cover's title is read from the same seat.
                if (curtain.value > 0f) StageCurtain(alpha = curtain.value)
                if (coverAlpha > 0f) LoadingCover(alpha = coverAlpha)
            }
        }
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

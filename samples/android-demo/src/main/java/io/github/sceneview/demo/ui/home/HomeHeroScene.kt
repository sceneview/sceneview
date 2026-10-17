package io.github.sceneview.demo.ui.home

import io.github.sceneview.demo.common.rememberFilmicView
import android.app.ActivityManager
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.android.filament.LightManager
import com.google.android.filament.MaterialInstance
import com.google.android.filament.View
import io.github.sceneview.FrameRatePolicy
import io.github.sceneview.RenderQuality
import io.github.sceneview.SceneView
import io.github.sceneview.SurfaceType
import io.github.sceneview.demo.BuildConfig
import io.github.sceneview.demo.DemoSettings
import io.github.sceneview.demo.R
import io.github.sceneview.demo.StartupMarker
import io.github.sceneview.demo.common.qaStateOverridesAllowed
import io.github.sceneview.demo.telemetry.CrashKey
import io.github.sceneview.demo.telemetry.Telemetry
import io.github.sceneview.demo.theme.LocalMotionEnabled
import io.github.sceneview.environment.rememberKTXEnvironment
import io.github.sceneview.math.Direction
import io.github.sceneview.math.Position
import io.github.sceneview.math.colorOf
import io.github.sceneview.model.ModelInstance
import io.github.sceneview.model.renderableInstances
import io.github.sceneview.model.renderableManager
import io.github.sceneview.node.LightNode as LightNodeImpl
import io.github.sceneview.node.ModelNode as ModelNodeImpl
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberEnvironment
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberRenderInvalidator
import io.github.sceneview.utils.OpenGL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The night circuit: one GLB — the Khronos Car Concept on a wet start grid, with its stands,
 * pits, gantry, skyline, rain and headlight cones — built by the website's
 * `tools/scenes/circuit/` and shared with it.
 */
internal const val HOME_HERO_MODEL: String = "models/night_circuit.glb"

/** Image-based light rendered from the same set, so the paint and the puddles reflect it. */
private const val HERO_IBL = "environments/circuit_ibl.ktx"

/**
 * The night circuit behind the home screen — the scene the website opens on, rendered and
 * animated by the engine itself — and the reason scrolling away and back never reloads it
 * (#3949).
 *
 * **What it is.** A race car waits on a wet start grid at night. The venue powers up group by
 * group — garages, windows, skyline, stands, pit neon, the SCENEVIEW screens, the floods —
 * and the car wakes with two indicator blinks and its headlights. Then the start procedure:
 * five red columns light one a second, go out together, the headlights flash twice and the
 * brakes glow while the camera glides over the tail. Rain falls through the beams the whole
 * time, the tower beacon blinks, the asphalt mirrors all of it. The camera makes one slow lap
 * of the car every [NightFilm.LOOP_SECONDS]; scrolling the home screen turns it further round
 * and pushes it in; tilting the phone steers it a few degrees. All of it is Filament through
 * SceneView: one `ModelNode`, six `LightNode`s, material parameters and view options written
 * per frame ([nightCueLevels], [nightCameraPose]). No overlay, no video.
 *
 * **Why it survives the scroll.** [HomeScreen] composes this once, as a layer *under* the
 * grid, for as long as the screen lives. The grid's hero item is transparent copy over it.
 * Neither `LazyVerticalGrid` nor the featured `HorizontalPager` can dispose the engine, the
 * TextureView, the model or the clock, because none of it is theirs. When the band leaves
 * the viewport the stage's own [Lifecycle] drops to CREATED and SceneView stops submitting
 * frames — the last one stays in the TextureView, the [HeroClock] stops — and coming back
 * raises it to RESUMED: the same frame, then the next one.
 *
 * **Cold start.** Nothing here composes before Compose has presented the app's first frame
 * (`withFrameNanos` gate). `StartupMarker` `first_model_frame` / `model_textured_frame` are
 * logged from presented frames, as `tools/measure-demo-cold-start.sh` expects. The film's
 * clock starts on the textured frame, so the venue powers up on a scene that is all there.
 *
 * **Tiers.** [HeroTier.forDevice] picks the top rung, the bottom one on a low-RAM device,
 * or the rung this install already settled on; [HeroFrameBudget] steps down a rung within
 * seconds whenever the frames come in under 30 fps — reflections, then the shadow, then
 * the venue's point lights and bloom, two at a time under 10 fps — and never climbs back. A drop is remembered, so the
 * next launch starts where this one settled. Reduced motion (animator scale 0, QA mode) holds the opening shot,
 * every lamp lit, under [FrameRatePolicy.OnDemand].
 *
 * **Where it cannot run.** On the legacy SwiftShader GLES library a GPU-less virtual device
 * renders with, the app aborted inside Filament about a second after launch (#4411). So the
 * GL implementation is read first ([HeroSurface.forRenderer]), and there the stage shows
 * [HomeHeroStill] — the same view, standing still — and no engine is created.
 *
 * **Leaving before it has loaded.** The stage only starts, and only *issues* a load, while
 * the home screen is the resumed destination, so a tap on the hero's Open during a cold start
 * never leaves a second engine uploading the circuit through the viewer's own start (the
 * Home → Models stall). What has already landed stays; a load still in flight is cancelled
 * and simply starts again on resume.
 *
 * @param active The band is at least partly on screen and the screen is not searching.
 *               False parks the render loop and the clock; the last frame stays.
 * @param scroll How far the featured band has scrolled out, 0 → 1, read per frame.
 * @param contentPadding The parts of the stage under the header and the band's copy: the
 *                       car is framed in what is left.
 */
@Composable
internal fun HomeHeroScene(
    active: Boolean,
    modifier: Modifier = Modifier,
    scroll: () -> Float = { 0f },
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    // Null until Compose has presented the app's first frame *and* the GL implementation
    // has been read — off the main thread, once per process.
    var surface by remember { mutableStateOf<HeroSurface?>(null) }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        surface = withContext(Dispatchers.Default) { heroSurface }
    }
    // `--ez qa_mode true --es qa_state hero_still`: the only way to look at the still on a
    // device that can run the stage — no device this project tests on renders with that library.
    val pinnedStill = DemoSettings.qaDemoState == HERO_STILL_QA_STATE && qaStateOverridesAllowed()
    when (if (pinnedStill) HeroSurface.Still else surface) {
        HeroSurface.Live -> {
            val hostResumed = rememberHostResumed()
            // Latched: once the stage exists it stays for the screen's life (#3949) —
            // tearing it down on a pause would reload everything on the way back.
            var started by remember { mutableStateOf(false) }
            if (hostResumed) started = true
            if (started) {
                HomeHeroStage(
                    active = active,
                    loadsAllowed = hostResumed,
                    scroll = scroll,
                    contentPadding = contentPadding,
                    modifier = modifier,
                )
            }
        }
        HeroSurface.Still -> HomeHeroStill(modifier)
        null -> Unit
    }
}

/**
 * What this device's hero stage shows, read once per process: [OpenGL.queryRenderer] makes a
 * throwaway GL context, so the first read belongs off the main thread. The answer also goes
 * to the crash reports — a Filament abort leaves no readable stack, and until #4411 nothing
 * in a report said what the device rendered with.
 */
private val heroSurface: HeroSurface by lazy {
    val renderer = runCatching { OpenGL.queryRenderer() }.getOrNull()
    val surface = HeroSurface.forRenderer(renderer)
    if (BuildConfig.DEBUG) Log.d(HERO_TAG, "hero surface=$surface on $renderer")
    with(Telemetry.analytics) {
        setCrashKey(CrashKey.GlRenderer, renderer?.renderer ?: NO_GL)
        setCrashKey(CrashKey.GlVersion, renderer?.version ?: NO_GL)
        setCrashKey(CrashKey.Abi, Build.SUPPORTED_ABIS.joinToString(","))
        setCrashKey(CrashKey.HeroSurface, surface.name.lowercase())
    }
    surface
}

private const val HERO_TAG = "HomeHero"
private const val NO_GL = "none"

/** The `qa_state` id that pins the home stage to [HomeHeroStill] ([DemoSettings.qaDemoState]). */
internal const val HERO_STILL_QA_STATE = "hero_still"

/**
 * The stage's picture, for a device that cannot run it ([HeroSurface.Still]): one frame of
 * the night circuit — the car on the wet grid under the gantry, lamps lit — as a bundled
 * image. No engine, no surface, nothing that can abort; the home screen reads the same,
 * standing still.
 *
 * The picture is a Cinematic frame of the flight, captured without the page over it and cut
 * below the status bar. It is a little wider than a phone stage, so there it loses a strip on
 * each side; a wider stage (a tablet's) crops it top and bottom, around the band that holds
 * the car ([HERO_STILL_FOCUS_Y]) rather than around its centre.
 */
@Composable
internal fun HomeHeroStill(modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.home_hero_still),
        contentDescription = null,
        modifier = modifier,
        alignment = HeroStillAlignment,
        contentScale = ContentScale.Crop,
    )
}

/**
 * Where a crop of `home_hero_still` holds on, down the picture: the car sits around 0.46 of
 * its height, under the pit building and its lamps — pinned here, a tablet stage keeps the
 * car and the lit pit wall over it.
 */
private const val HERO_STILL_FOCUS_Y = 0.42f

/** A bias of `2f − 1` pins the picture's fraction `f` to the same fraction of the stage. */
private val HeroStillAlignment = BiasAlignment(
    horizontalBias = 0f,
    verticalBias = 2f * HERO_STILL_FOCUS_Y - 1f,
)

/**
 * Art direction of the night — world units and linear light, not UI tokens. Every number
 * is the website's (`circuit.js`), so the two surfaces light the same set the same way.
 */
private object NightSet {
    /** The grandstand floods: the key, from the right of the grid, the only shadow caster. */
    val keyColor = colorOf(r = 1f, g = 0.95f, b = 0.88f)
    val keyDirection = Direction(-0.62f, -0.7f, -0.36f)
    const val KEY_LUX = 52_000f

    /** The image-based light, scaled with the floods: a dark venue is lit by the city alone. */
    const val IBL_INTENSITY = 34_000f
    const val IBL_FLOOR = 0.35f

    /** The car's two headlight spots, in the car's frame (nose towards +Z). */
    val headlightColor = colorOf(r = 0.72f, g = 0.85f, b = 1f)
    const val HEADLIGHT_LUMENS = 1e7f
    const val HEADLIGHT_X = 0.72f
    const val HEADLIGHT_Y = 0.66f
    const val HEADLIGHT_Z = 2.35f
    const val HEADLIGHT_TOE = 0.06f
    const val HEADLIGHT_DIP = -0.16f
    const val HEADLIGHT_FALLOFF = 36f
    const val HEADLIGHT_INNER_CONE = 0.12f
    const val HEADLIGHT_OUTER_CONE = 0.62f

    /** The tail: a small red pool under the diffuser, six times brighter on the brakes. */
    val tailColor = colorOf(r = 1f, g = 0.04f, b = 0.02f)
    val tailPosition = Position(0f, 0.45f, -3.1f)
    const val TAIL_LUMENS = 6e5f
    const val TAIL_FALLOFF = 7f

    /** The start gantry's red, over the grid. */
    val startColor = colorOf(r = 1f, g = 0.06f, b = 0.03f)
    val startPosition = Position(0f, 6.2f, 13.6f)
    const val START_LUMENS = 1.3e7f
    const val START_FALLOFF = 26f

    /** The open garage's warm spill, left of the grid. */
    val garageColor = colorOf(r = 1f, g = 0.6f, b = 0.3f)
    val garagePosition = Position(-17f, 3.2f, -4f)
    const val GARAGE_LUMENS = 3.2e7f
    const val GARAGE_FALLOFF = 42f

    /** Linear fog colour: the night's blue haze, the headlight cones' medium. */
    val fogColor = floatArrayOf(0.05f, 0.075f, 0.13f)

    /** The `Beam` material's colour; its opacity is what the cue sheet drives. */
    val beamColor = floatArrayOf(0.72f, 0.85f, 1f)
    const val BEAM_OPACITY = 0.07f

    /** The sky dome is 700 m out; the camera must see it. */
    const val CAMERA_FAR = 1500f

    /**
     * The rain falls all around the camera: a drop a few centimetres from the lens fills the
     * frame as a white bar. Nothing else on the set comes nearer than about a metre, even
     * from the lowest shot (eye 0.25 m up, the grid a metre ahead at the bottom of the frame).
     */
    const val CAMERA_NEAR = 0.6f
}

@Composable
private fun HomeHeroStage(
    active: Boolean,
    /** False while the screen is not the resumed destination: nothing new is loaded. */
    loadsAllowed: Boolean,
    scroll: () -> Float,
    contentPadding: PaddingValues,
    modifier: Modifier,
) {
    val context = LocalContext.current
    var tier by remember(context) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        mutableStateOf(
            HeroTier.forDevice(am.isLowRamDevice, settled = settledHeroTier(context)),
        )
    }
    val motionEnabled = LocalMotionEnabled.current
    val moving = active && motionEnabled
    val clock = remember { HeroClock() }
    val tilt = remember { HeroTilt() }
    val budget = remember { HeroFrameBudget() }
    // Seeded with the film's first frame, the venue still dark: a frame drawn before the loop
    // first runs must not show the set at full power.
    val levels = remember { nightCueLevels(film = 0f, motion = motionEnabled) }
    val scrollFollow = remember { HeroScrollFollow() }
    val stageLifecycle = rememberHeroLifecycle(active, clock, scrollFollow)

    val engine = rememberEngine()
    val view = rememberFilmicView(engine)
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)
    val fallbackEnvironment = rememberEnvironment(environmentLoader, isOpaque = false)
    // Each load below is composed only while loads are allowed or once it has landed:
    // leaving composition cancels a load still in flight before it reaches the engine.
    var iblLanded by remember { mutableStateOf(false) }
    val nightEnvironment = if (loadsAllowed || iblLanded) {
        rememberKTXEnvironment(environmentLoader, HERO_IBL)?.also { iblLanded = true }
    } else {
        null
    }
    val environment = nightEnvironment ?: fallbackEnvironment
    // Same for the image-based light: it lands at the floods' level, not at full strength.
    // An effect, not composition: it runs once the environment has landed, before its frame.
    LaunchedEffect(nightEnvironment) {
        nightEnvironment?.indirectLight?.intensity = NightSet.IBL_INTENSITY *
            (NightSet.IBL_FLOOR + (1f - NightSet.IBL_FLOOR) * levels.flood)
    }
    val cameraNode = rememberCameraNode(engine) {
        near = NightSet.CAMERA_NEAR
        far = NightSet.CAMERA_FAR
        val pose = nightCameraPose(film = 0f)
        focalLength = pose.focalLengthMillimetres
        lookAt(pose)
    }
    val renderInvalidator = rememberRenderInvalidator()

    var modelLanded by remember { mutableStateOf(false) }
    val modelInstance = if (loadsAllowed || modelLanded) {
        rememberModelInstance(modelLoader, HOME_HERO_MODEL)?.also { modelLanded = true }
    } else {
        null
    }
    val lamps = remember(modelInstance) { modelInstance?.let { NightLamps(it) } }

    // What the frame loop drives. Plain holders, not state: a per-frame write must not
    // recompose anything.
    val modelNode = remember { arrayOfNulls<ModelNodeImpl>(1) }
    val lights = remember { NightLights() }
    val filmStart = remember { arrayOfNulls<Double>(1) }
    val lastFocal = remember { doubleArrayOf(0.0) }
    val lastIbl = remember { floatArrayOf(-1f) }

    // Device tilt steers the camera a few degrees while the film runs; the listener leaves
    // with it. Not on the bottom rung, which saves everything it can.
    DisposableEffect(context, moving, tier) {
        val manager = if (moving && tier != HeroTier.Light) {
            context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        } else {
            null
        }
        val gravity = manager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                tilt.feed(gravityX = event.values[0], gravityZ = event.values[2])
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        if (gravity != null) {
            manager?.registerListener(listener, gravity, SensorManager.SENSOR_DELAY_UI)
        }
        onDispose {
            manager?.unregisterListener(listener)
            tilt.reset()
        }
    }
    LaunchedEffect(moving) {
        clock.pause()
        scrollFollow.pause()
        budget.reset()
        if (moving) renderInvalidator.requestRender()
    }

    Box(modifier.clearAndSetSemantics { }) {
        SceneView(
            modifier = Modifier.fillMaxSize(),
            surfaceType = SurfaceType.TextureSurface,
            isOpaque = false,
            engine = engine,
            view = view,
            modelLoader = modelLoader,
            materialLoader = materialLoader,
            environmentLoader = environmentLoader,
            environment = environment,
            cameraNode = cameraNode,
            mainLightNode = null,
            fillLightNode = null,
            cameraManipulator = null,
            onGestureListener = null,
            autoCenterContent = false,
            lifecycle = stageLifecycle,
            renderQuality = if (tier.performancePreset) RenderQuality.Performance else RenderQuality.Default,
            frameRatePolicy = if (moving) {
                FrameRatePolicy.Continuous(maxFps = HERO_MAX_FPS)
            } else {
                FrameRatePolicy.OnDemand(maxFps = HERO_MAX_FPS)
            },
            renderInvalidator = renderInvalidator,
            contentPadding = contentPadding,
            onFrame = { nanos ->
                val node = modelNode[0]
                var textured = false
                if (node != null) {
                    StartupMarker.mark("first_model_frame")
                    if (!modelLoader.isLoading) {
                        StartupMarker.mark("model_textured_frame")
                        textured = true
                        if (filmStart[0] == null) filmStart[0] = clock.seconds
                    }
                }
                val before = clock.seconds
                val seconds = clock.frame(nanos, moving)
                tilt.update((seconds - before).toFloat())
                // The film starts on the first textured frame: the venue powers up on a set
                // that is all there.
                val film = (seconds - (filmStart[0] ?: seconds)).toFloat()

                // The camera eases after the scroll rather than jumping with it; QA mode and
                // reduced motion take the scroll as it is, so captures stay deterministic.
                val followed = scrollFollow.follow(
                    target = scroll(),
                    nanos = nanos,
                    snap = DemoSettings.qaMode || !motionEnabled,
                )
                val pose = nightCameraPose(
                    film = film,
                    scroll = followed,
                    aspect = cameraNode.getViewPortAspect().toFloat(),
                    tiltX = tilt.x,
                    tiltY = tilt.y,
                    motion = motionEnabled,
                )
                val focal = pose.focalLengthMillimetres
                if (kotlin.math.abs(focal - lastFocal[0]) > 1e-3) {
                    lastFocal[0] = focal
                    cameraNode.focalLength = focal
                }
                cameraNode.lookAt(pose)

                nightCueLevels(film, motionEnabled, into = levels, headOn = nightHeadOn(pose))
                lamps?.apply(levels)
                lamps?.rain(if (motionEnabled) film else 0f)
                lights.apply(levels)
                val ibl = NightSet.IBL_INTENSITY *
                    (NightSet.IBL_FLOOR + (1f - NightSet.IBL_FLOOR) * levels.flood)
                if (nightEnvironment != null && ibl != lastIbl[0]) {
                    lastIbl[0] = ibl
                    nightEnvironment.indirectLight?.intensity = ibl
                }

                // Too slow for this rung: one down, judged afresh after its own compiles.
                if (textured && moving && budget.frame(nanos)) {
                    val step = if (budget.farBelow) tier.lower?.lower ?: tier.lower else tier.lower
                    step?.let { lower ->
                        if (BuildConfig.DEBUG) Log.d(HERO_TAG, "hero tier $tier -> $lower")
                        tier = lower
                        rememberSettledHeroTier(context, lower)
                    }
                }
            },
        ) {
            modelInstance?.let { instance ->
                ModelNode(
                    modelInstance = instance,
                    autoAnimate = false,
                    apply = {
                        modelNode[0] = this
                        configureNightSet(instance)
                        // The GLB is authored lit: dim it before its first frame, not after.
                        lamps?.apply(levels)
                    },
                )
            }
            // The floods: the key, and the only shadow. Its shadow map covers the grid
            // around the car, not the whole venue.
            LightNode(
                type = LightManager.Type.DIRECTIONAL,
                direction = NightSet.keyDirection,
                color = NightSet.keyColor,
                apply = {
                    intensity(0f)
                    castShadows(true)
                    shadowOptions(
                        LightManager.ShadowOptions().apply {
                            mapSize = 1024
                            shadowFar = 28f
                            shadowFarHint = 28f
                        },
                    )
                },
                nodeApply = { lights.key = this },
            )
            for (side in HEADLIGHT_SIDES) {
                LightNode(
                    type = LightManager.Type.SPOT,
                    direction = Direction(
                        side * NightSet.HEADLIGHT_TOE,
                        NightSet.HEADLIGHT_DIP,
                        1f,
                    ).normalized(),
                    position = Position(
                        side * NightSet.HEADLIGHT_X,
                        NightSet.HEADLIGHT_Y,
                        NightSet.HEADLIGHT_Z,
                    ),
                    color = NightSet.headlightColor,
                    apply = {
                        intensity(0f)
                        falloff(NightSet.HEADLIGHT_FALLOFF)
                        spotLightCone(NightSet.HEADLIGHT_INNER_CONE, NightSet.HEADLIGHT_OUTER_CONE)
                        castShadows(false)
                    },
                    nodeApply = { lights.headlights += this },
                )
            }
            LightNode(
                type = LightManager.Type.POINT,
                position = NightSet.tailPosition,
                color = NightSet.tailColor,
                apply = {
                    intensity(NightSet.TAIL_LUMENS)
                    falloff(NightSet.TAIL_FALLOFF)
                },
                nodeApply = { lights.tail = this },
            )
            if (tier.venueLights) {
                LightNode(
                    type = LightManager.Type.POINT,
                    position = NightSet.startPosition,
                    color = NightSet.startColor,
                    apply = {
                        intensity(NightSet.START_LUMENS)
                        falloff(NightSet.START_FALLOFF)
                    },
                    nodeApply = { lights.start = this },
                )
                LightNode(
                    type = LightManager.Type.POINT,
                    position = NightSet.garagePosition,
                    color = NightSet.garageColor,
                    apply = {
                        intensity(0f)
                        falloff(NightSet.GARAGE_FALLOFF)
                    },
                    nodeApply = { lights.garage = this },
                )
            } else {
                lights.start = null
                lights.garage = null
            }
        }
        // Raw Filament writes on the View, declared after the SceneView so they run after
        // its own `applyRenderQuality` effect and are never undone by it (#1078). Each
        // write asks for a frame: a parked loop would otherwise keep the old look.
        LaunchedEffect(view, tier, modelInstance) {
            applyNightLook(view, tier)
            lights.key?.isShadowCaster = tier.shadows
            modelInstance?.let { showStandingWater(it, visible = tier.reflections) }
            renderInvalidator.requestRender()
        }
    }
}

/**
 * The rung this install's GPU last settled on, if any. A device that had to step down starts
 * there next time instead of replaying seconds of a rung it cannot draw. Keyed by the app's
 * version: a new build, with its own shaders and set, is judged afresh.
 */
private fun settledHeroTier(context: Context): HeroTier? {
    val name = heroPrefs(context).getString(settledTierKey(), null) ?: return null
    return HeroTier.entries.firstOrNull { it.name == name }
}

private fun rememberSettledHeroTier(context: Context, tier: HeroTier) {
    heroPrefs(context).edit().putString(settledTierKey(), tier.name).apply()
}

private fun heroPrefs(context: Context) =
    context.getSharedPreferences("home_hero", Context.MODE_PRIVATE)

private fun settledTierKey() = "tier@${BuildConfig.VERSION_CODE}"

/** Up to 60 presented frames a second: the rain and the flashes need every one. */
private const val HERO_MAX_FPS = 60

/** Left, then right headlight: the sign of the car's X axis. */
private val HEADLIGHT_SIDES = floatArrayOf(-1f, 1f)

private fun Direction.normalized(): Direction {
    val length = kotlin.math.sqrt(x * x + y * y + z * z)
    return Direction(x / length, y / length, z / length)
}

private fun io.github.sceneview.node.CameraNode.lookAt(pose: NightCameraPose) {
    lookAt(
        eye = Position(pose.eyeX, pose.eyeY, pose.eyeZ),
        center = Position(pose.targetX, pose.targetY, pose.targetZ),
        up = Direction(0f, 1f, 0f),
    )
}

/**
 * The night's lens: the same bloom, vignette and haze as the website, and the wet asphalt's
 * screen-space reflections on the rungs that keep them. Ambient occlusion stays off — at
 * night the floods' shadow carries the contact.
 */
private fun applyNightLook(view: View, tier: HeroTier) {
    view.ambientOcclusionOptions = view.ambientOcclusionOptions.also { it.enabled = false }
    view.bloomOptions = view.bloomOptions.also { bloom ->
        bloom.enabled = tier.bloom
        bloom.strength = 0.32f
        bloom.resolution = 512
        bloom.levels = 7
        bloom.threshold = true
        bloom.lensFlare = false
    }
    view.vignetteOptions = view.vignetteOptions.also { vignette ->
        vignette.enabled = true
        vignette.midPoint = 0.62f
        vignette.roundness = 0.5f
        vignette.feather = 0.8f
    }
    view.fogOptions = view.fogOptions.also { fog ->
        fog.enabled = true
        fog.color[0] = NightSet.fogColor[0]
        fog.color[1] = NightSet.fogColor[1]
        fog.color[2] = NightSet.fogColor[2]
        fog.density = 0.016f
        fog.distance = 14f
        fog.height = 0f
        fog.heightFalloff = 0.035f
        fog.maximumOpacity = 0.82f
        // The sky dome, 700 m out, stays sharp.
        fog.cutOffDistance = 620f
        fog.fogColorFromIbl = false
    }
    view.screenSpaceReflectionsOptions = view.screenSpaceReflectionsOptions.also { ssr ->
        ssr.enabled = tier.reflections
        ssr.thickness = 0.5f
        ssr.bias = 0.05f
        ssr.maxDistance = 9f
        ssr.stride = 1f
    }
    view.dynamicResolutionOptions = view.dynamicResolutionOptions.also { dr ->
        dr.enabled = tier.dynamicResolution
        dr.homogeneousScaling = true
    }
    view.antiAliasing = View.AntiAliasing.FXAA
    view.setShadowingEnabled(tier.shadows)
}

/** The six lights of the set, as the frame loop writes them. */
private class NightLights {
    var key: LightNodeImpl? = null
    val headlights = ArrayList<LightNodeImpl>(2)
    var tail: LightNodeImpl? = null
    var start: LightNodeImpl? = null
    var garage: LightNodeImpl? = null

    private val last = FloatArray(5) { -1f }

    fun apply(levels: NightCueLevels) {
        write(0, NightSet.KEY_LUX * levels.flood) { key?.intensity = it }
        write(1, NightSet.HEADLIGHT_LUMENS * levels.headlights) { value ->
            for (light in headlights) light.intensity = value
        }
        write(2, NightSet.TAIL_LUMENS * levels.brake) { tail?.intensity = it }
        // The venue's two are dropped and re-created with a rung: always rewrite them.
        start?.intensity = NightSet.START_LUMENS * levels.startLight
        garage?.intensity = NightSet.GARAGE_LUMENS * levels.garage
    }

    private inline fun write(slot: Int, value: Float, set: (Float) -> Unit) {
        if (value != last[slot]) {
            last[slot] = value
            set(value)
        }
    }
}

/**
 * The circuit's lamps: its emissive materials, by the names the GLB gives them, each scaled
 * from the strength it was authored with. Bound once per loaded model; a frame writes a
 * parameter only when its level moved.
 */
private class NightLamps(private val instance: ModelInstance) {
    private class Lamp(val materials: List<MaterialInstance>, val base: Float) {
        var last = -1f

        fun set(level: Float) {
            val value = base * level
            if (value == last) return
            last = value
            for (material in materials) material.setParameter(EMISSIVE_STRENGTH, value)
        }
    }

    private val byName: Map<String, List<MaterialInstance>> = instance.primitiveMaterials()
        .map { it.second }
        .distinct()
        .groupBy { it.name.orEmpty() }

    private fun lamp(name: String, base: Float): Lamp = Lamp(
        byName[name].orEmpty().filter { it.material.hasParameter(EMISSIVE_STRENGTH) },
        base,
    )

    private val headlight = lamp("Headlight", 10f)
    private val signal = lamp("Signallight", 2f)
    private val brake = lamp("Brakelight", 2f)
    private val board = lamp("Light board", 2.6f)
    private val banner = lamp("Light banner", 3.2f)
    private val led = lamp("Light led", 5f)
    private val ledWarm = lamp("Light ledWarm", 4f)
    private val garage = lamp("Light garage", 0.85f)
    private val window = lamp("Light window", 1.5f)
    private val seats = lamp("Light seats", 0.3f)
    private val flood = lamp("Light flood", 26f)
    private val beacon = lamp("Light beacon", 12f)
    private val city = lamp("Light city", 6f)
    private val cityCool = lamp("Light cityCool", 6f)
    private val startReds = List(NightCues.COLUMNS) { lamp("Light startRed$it", 22f) }
    private val beams = byName["Beam"].orEmpty().filter { it.material.hasParameter(BASE_COLOR) }
    private var lastBeam = -1f

    private val rainDuration: Float = instance.animator
        .takeIf { it.animationCount > 0 }
        ?.getAnimationDuration(0)
        ?: 0f

    init {
        if (BuildConfig.DEBUG) {
            Log.d(HERO_TAG, "night lamps: ${byName.keys.sorted()} rain=${rainDuration}s")
        }
    }

    fun apply(levels: NightCueLevels) {
        headlight.set(levels.headlightLamps)
        signal.set(levels.signals)
        brake.set(levels.brakeLamps)
        board.set(levels.screens)
        banner.set(levels.screens)
        led.set(levels.led)
        ledWarm.set(levels.ledWarm)
        garage.set(levels.garage)
        window.set(levels.windows)
        seats.set(levels.seats)
        flood.set(levels.flood)
        beacon.set(levels.beacon)
        city.set(levels.city)
        cityCool.set(levels.city)
        for (i in startReds.indices) startReds[i].set(levels.startColumns[i])
        val opacity = NightSet.BEAM_OPACITY * levels.beams
        if (opacity != lastBeam) {
            lastBeam = opacity
            for (material in beams) {
                material.setParameter(
                    BASE_COLOR,
                    NightSet.beamColor[0],
                    NightSet.beamColor[1],
                    NightSet.beamColor[2],
                    opacity,
                )
            }
        }
    }

    /** The rain, played by the film's clock rather than by the node's own animator. */
    fun rain(film: Float) {
        if (rainDuration <= 0f) return
        instance.animator.applyAnimation(0, film % rainDuration)
    }

    private companion object {
        const val EMISSIVE_STRENGTH = "emissiveStrength"
        const val BASE_COLOR = "baseColorFactor"
    }
}

/** Every primitive of [this] model: its renderable, and the material it draws with. */
private fun ModelInstance.primitiveMaterials(): List<Pair<Int, MaterialInstance>> {
    val renderables = renderableManager
    return renderableInstances.flatMap { renderable ->
        List(renderables.getPrimitiveCount(renderable)) { primitive ->
            renderable to renderables.getMaterialInstanceAt(renderable, primitive)
        }
    }
}

/** Materials that are light, not matter: the haze, the rain and the sky dome. */
private val SHADOWLESS_MATERIALS = setOf("Beam", "Rain", "Sky")

/** The set's own materials that are not `Set …` or `Light …`: the ground, the water, the sky, the cones. */
private val SET_MATERIALS = setOf("Wet asphalt", STANDING_WATER, "Sky", "Beam", "Contact shadow")

private const val STANDING_WATER = "Standing water"

/** Filament's default render channel: the set, the headlight cones and the contact shadow. */
private const val SET_CHANNEL = 2

/** Drawn after [SET_CHANNEL]: the car, then the rain over it. */
private const val CAR_CHANNEL = 3

private fun isSetMaterial(name: String?): Boolean =
    name != null && (name in SET_MATERIALS || name.startsWith("Set ") || name.startsWith("Light "))

/**
 * Shadows and draw order of the set.
 *
 * Everything on the set receives the floods' shadow; everything solid casts it. The cones,
 * the rain and the sky must not throw a shadow on the grid.
 *
 * The headlight cones are blended and write no depth, so wherever the camera looks at the
 * car's nose through a cone — every front three-quarter shot — they washed a pale wedge over
 * the paint and the car read as half transparent. The car and the rain are drawn in a later
 * channel than the set: the cones still glow over the asphalt and in the haze, and the car's
 * body covers them, the way a beam starting at the lamp never veils the car that throws it.
 */
private fun configureNightSet(instance: ModelInstance) {
    val renderables = instance.renderableManager
    val primitives = instance.primitiveMaterials()
    val shadowless = primitives
        .filter { (_, material) -> material.name in SHADOWLESS_MATERIALS }
        .mapTo(HashSet()) { it.first }
    val set = primitives
        .filter { (_, material) -> isSetMaterial(material.name) }
        .mapTo(HashSet()) { it.first }
    for (renderable in instance.renderableInstances) {
        val matter = renderable !in shadowless
        renderables.setCastShadows(renderable, matter)
        renderables.setReceiveShadows(renderable, matter)
        renderables.setChannel(renderable, if (renderable in set) SET_CHANNEL else CAR_CHANNEL)
    }
    primitives.map { it.second }.distinct()
        .filter { it.name == TRIM_PAINT && it.material.hasParameter(BASE_COLOR) }
        .forEach { it.setParameter(BASE_COLOR, TRIM_F0, TRIM_F0, TRIM_F0, 1f) }
}

/**
 * The car's black lower trim: side skirts, splitter and diffuser.
 *
 * It is a metal whose base colour is its reflectance at normal incidence, 0.038. Filament
 * lifts the grazing reflectance of any surface brighter than 0.02 to a full mirror, so the
 * skirts, seen edge-on from a camera at the car's height, mirrored the bright lower half of
 * the circuit's environment map instead of the dark wet grid an inch below them. Between the
 * wheels that pale strip read as daylight under the car, and the car floated. At 0.006 the
 * grazing mirror falls under a third and the trim stays the black it is.
 */
private const val TRIM_PAINT = "Paint 2 Carmine"
private const val BASE_COLOR = "baseColorFactor"
private const val TRIM_F0 = 0.006f

/**
 * The standing water, only where screen-space reflections run. Without them a puddle mirrors
 * the environment map alone — a black sky, which never holds the car nor the floods — and
 * each one read as a dark shadow lying metres away from the car on the side shots. The wet
 * asphalt keeps its own gloss and the floods' streaks on every rung.
 */
private fun showStandingWater(instance: ModelInstance, visible: Boolean) {
    val renderables = instance.renderableManager
    for ((renderable, material) in instance.primitiveMaterials()) {
        if (material.name == STANDING_WATER) {
            renderables.setLayerMask(renderable, ALL_LAYERS, if (visible) ALL_LAYERS else 0)
        }
    }
}

private const val ALL_LAYERS = 0xff

/** Whether the screen hosting the stage is the resumed destination, as state. */
@Composable
private fun rememberHostResumed(): Boolean {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val state by lifecycle.currentStateFlow.collectAsState()
    return state.isAtLeast(Lifecycle.State.RESUMED)
}

private class HeroLifecycleOwner : LifecycleOwner {
    val registry = LifecycleRegistry(this).apply { currentState = Lifecycle.State.CREATED }
    override val lifecycle: Lifecycle get() = registry
}

/**
 * The stage's own lifecycle: RESUMED only while [active] *and* the host is resumed.
 *
 * SceneView stops submitting frames below RESUMED and never detaches the surface, so
 * the last frame stays on screen — which is exactly the state the band must come back
 * in (#3949). The clock and the scroll follow pause on the way down so the next frame gets
 * no delta.
 */
@Composable
private fun rememberHeroLifecycle(
    active: Boolean,
    clock: HeroClock,
    scrollFollow: HeroScrollFollow,
): Lifecycle {
    val parent = LocalLifecycleOwner.current.lifecycle
    val owner = remember { HeroLifecycleOwner() }
    DisposableEffect(parent, owner, active) {
        fun sync() {
            val resumed = active && parent.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if (!resumed) {
                clock.pause()
                scrollFollow.pause()
            }
            owner.registry.currentState =
                if (resumed) Lifecycle.State.RESUMED else Lifecycle.State.CREATED
        }
        val observer = LifecycleEventObserver { _, _ -> sync() }
        parent.addObserver(observer)
        sync()
        onDispose { parent.removeObserver(observer) }
    }
    DisposableEffect(owner) {
        onDispose { owner.registry.currentState = Lifecycle.State.DESTROYED }
    }
    return owner.lifecycle
}

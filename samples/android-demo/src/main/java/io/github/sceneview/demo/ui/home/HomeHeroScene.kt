package io.github.sceneview.demo.ui.home

import android.app.ActivityManager
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.android.filament.Material
import com.google.android.filament.RenderableManager
import com.google.android.filament.View
import dev.romainguy.kotlin.math.normalize
import io.github.sceneview.FrameRatePolicy
import io.github.sceneview.RenderQuality
import io.github.sceneview.SceneView
import io.github.sceneview.SurfaceType
import io.github.sceneview.demo.StartupMarker
import io.github.sceneview.demo.theme.LocalMotionEnabled
import io.github.sceneview.environment.rememberHDREnvironment
import io.github.sceneview.geometries.Geometry
import io.github.sceneview.math.Color
import io.github.sceneview.math.Direction
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Scale
import io.github.sceneview.math.colorOf
import io.github.sceneview.node.CameraNode
import io.github.sceneview.node.MeshNode as MeshNodeImpl
import io.github.sceneview.node.ModelNode as ModelNodeImpl
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberEnvironment
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberMainLightNode
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberRenderInvalidator
import io.github.sceneview.rememberView
import io.github.sceneview.safeDestroyGeometry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.sin

/** The subject of the flight — the licensed, bundled flagship model, no download on home. */
const val HOME_HERO_MODEL: String = "models/khronos_damaged_helmet.glb"

private const val HERO_HDR = "environments/sunset_2k.hdr"
private const val HERO_TERRAIN_MATERIAL = "materials/hero_terrain.filamat"

/**
 * The dusk flight behind the home screen (#3948) — and the reason scrolling away and
 * back no longer reloads it (#3949).
 *
 * **What it is.** A low-poly valley slides under the camera at dusk: a periodic
 * flat-shaded heightfield ([HomeHeroTerrain]) lit by one warm sun low on the horizon,
 * an emissive sun disc that bloom and the lens flare bleed from, height fog in the
 * horizon's colour, the sunset HDR as image-based light, TAA, and the Damaged Helmet
 * riding front-right of the camera, turning slowly. The camera flies a lazy S-curve and
 * banks into it; the device's tilt steers the gaze a few degrees. All of it is
 * Filament through SceneView — one `MeshNode`, one `SphereNode`, one `ModelNode`.
 *
 * **Why it survives the scroll.** [HomeScreen] composes this once, as a layer *under*
 * the grid, for as long as the screen lives. The grid's hero item is transparent copy
 * over it. Neither `LazyVerticalGrid` nor the featured `HorizontalPager` can dispose
 * the engine, the TextureView, the model or the clock, because none of it is theirs.
 * When the band leaves the viewport the stage's own [Lifecycle] drops to CREATED and
 * SceneView stops submitting frames — the last one stays in the TextureView, the
 * [HeroClock] stops — and coming back raises it to RESUMED: the same frame, then the
 * next one. No still, no loader, no fade.
 *
 * **Cold start.** Nothing here composes before Compose has presented the app's first
 * frame (`withFrameNanos` gate). Terrain generation runs on `Dispatchers.Default`; the
 * only main-thread Filament work after the gate is buffer upload and material creation.
 * `StartupMarker` `first_model_frame` / `model_textured_frame` are logged from presented
 * frames, as `tools/measure-demo-cold-start.sh` expects.
 *
 * **Tiers.** `isLowRamDevice` gets the Performance preset, a quarter of the triangles,
 * no HDR decode, no bloom, fog or TAA — the same flight, matte. Reduced motion (animator
 * scale 0, QA mode) holds the opening frame under [FrameRatePolicy.OnDemand].
 *
 * @param active The band is at least partly on screen and the screen is not searching.
 *               False parks the render loop and the clock; the last frame stays.
 */
@Composable
internal fun HomeHeroScene(
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    var firstFrameDrawn by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        firstFrameDrawn = true
    }
    if (firstFrameDrawn) {
        HomeHeroStage(active = active, modifier = modifier)
    }
}

/**
 * Art direction of the flight — world units and linear light, not UI tokens. The sky
 * gradient Compose paints behind the transparent surface lives in [HomeScreen]
 * (`HeroSky`); [fogColor] is its horizon, so the far ridges dissolve into it.
 */
private object DuskFlight {
    /**
     * Where the sun disc sits — far down the valley, a little left of the corridor, just
     * above the far ridges, past the fog cut-off so it stays sharp for the flare.
     */
    val sunPosition = Position(-10f, 10.5f, -74f)
    const val SUN_RADIUS = 2.6f

    /**
     * The key light rakes in from the left and above, not straight out of the disc: lit
     * from the disc the whole valley would be back-lit into one silhouette. Nobody can
     * read a 30° discrepancy between a glow on the horizon and the side the facets catch
     * the light on; everybody reads relief.
     */
    val sunDirection: Direction = normalize(Direction(0.55f, -0.35f, 0.76f))
    val sunColor: Color = colorOf(r = 1f, g = 0.62f, b = 0.38f)
    const val SUN_INTENSITY_LUX = 95_000f

    /** Linear radiance the disc emits — far above 1.0 so bloom has something to bleed. */
    val sunEmissive = floatArrayOf(30f, 11f, 3.5f)

    /** Linear sRGB of the horizon: `HeroSky`'s ember stop, 0xFFE2734F. */
    val fogColor = floatArrayOf(0.75f, 0.17f, 0.075f)

    const val HELMET_UNITS = 0.8f

    /** How far below the valley the terrain starts, rising into place on its first frame. */
    const val TERRAIN_RISE_UNITS = 2.5f
}

@Composable
private fun HomeHeroStage(
    active: Boolean,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val tier = remember(context) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        HeroTier.forDevice(am.isLowRamDevice)
    }
    val motionEnabled = LocalMotionEnabled.current
    val moving = active && motionEnabled
    val clock = remember { HeroClock() }
    val tilt = remember { HeroTilt() }
    val stageLifecycle = rememberHeroLifecycle(active, clock)

    val engine = rememberEngine()
    val view = rememberView(engine)
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)
    val fallbackEnvironment = rememberEnvironment(environmentLoader, isOpaque = false)
    // The HDR is decoded and prefiltered off the main thread and lands when it lands; the
    // flight starts under the plain environment. Skipped entirely on the light tier.
    val hdrEnvironment = if (tier.cinematic) {
        rememberHDREnvironment(environmentLoader, HERO_HDR, createSkybox = false)
    } else {
        null
    }
    val cameraNode = rememberCameraNode(engine) {
        lookAt(heroFlightPose(0.0, tier.terrain.period))
    }
    val sun = rememberMainLightNode(engine) {
        lightDirection = DuskFlight.sunDirection
        color = DuskFlight.sunColor
        intensity = DuskFlight.SUN_INTENSITY_LUX
        // A sun this low would stretch every shadow across the whole strip; the facets
        // carry the relief on their own.
        isShadowCaster = false
    }
    val renderInvalidator = rememberRenderInvalidator()

    // The terrain: CPU work off the main thread, Filament buffers on it.
    val terrainVertices by produceState<Pair<List<Geometry.Vertex>, List<Int>>?>(null, tier) {
        value = withContext(Dispatchers.Default) {
            val mesh = buildHeroTerrain(tier.terrain)
            val vertices = ArrayList<Geometry.Vertex>(mesh.vertexCount)
            for (i in 0 until mesh.vertexCount) {
                vertices += Geometry.Vertex(
                    position = Position(
                        mesh.positions[i * 3],
                        mesh.positions[i * 3 + 1],
                        mesh.positions[i * 3 + 2],
                    ),
                    normal = Direction(
                        mesh.normals[i * 3],
                        mesh.normals[i * 3 + 1],
                        mesh.normals[i * 3 + 2],
                    ),
                    color = Color(
                        mesh.colors[i * 4],
                        mesh.colors[i * 4 + 1],
                        mesh.colors[i * 4 + 2],
                        mesh.colors[i * 4 + 3],
                    ),
                )
            }
            vertices to mesh.indices.toList()
        }
    }
    val terrainGeometry = remember(engine, terrainVertices) {
        terrainVertices?.let { (vertices, indices) ->
            Geometry.Builder(RenderableManager.PrimitiveType.TRIANGLES)
                .vertices(vertices)
                .indices(indices)
                .build(engine)
        }
    }
    DisposableEffect(engine, terrainGeometry) {
        onDispose { terrainGeometry?.let { engine.safeDestroyGeometry(it) } }
    }
    // One compiled material, two instances: the matte ground and the glowing disc. The
    // loader owns both and destroys them with the screen.
    val terrainMaterial by produceState<Material?>(null, materialLoader) {
        value = materialLoader.loadMaterial(HERO_TERRAIN_MATERIAL)
    }
    val terrainInstance = remember(materialLoader, terrainMaterial) {
        terrainMaterial?.let { material ->
            materialLoader.createInstance(material).apply {
                setParameter("color", 1f, 1f, 1f, 1f)
                setParameter("roughness", 0.92f)
                setParameter("emissive", 0f, 0f, 0f)
            }
        }
    }
    val sunInstance = remember(materialLoader, terrainMaterial) {
        terrainMaterial?.let { material ->
            materialLoader.createInstance(material).apply {
                setParameter("color", 1f, 0.55f, 0.25f, 1f)
                setParameter("roughness", 1f)
                setParameter(
                    "emissive",
                    DuskFlight.sunEmissive[0],
                    DuskFlight.sunEmissive[1],
                    DuskFlight.sunEmissive[2],
                )
            }
        }
    }

    val modelInstance = rememberModelInstance(modelLoader, HOME_HERO_MODEL)
    // Nodes the frame loop drives. Plain holders, not state: a per-frame write must not
    // recompose anything.
    val terrainNode = remember { arrayOfNulls<MeshNodeImpl>(1) }
    val helmetNode = remember { arrayOfNulls<ModelNodeImpl>(1) }
    val helmetBaseScale = remember { floatArrayOf(1f) }
    val entranceStart = remember { arrayOfNulls<Double>(1) }
    val terrainStart = remember { arrayOfNulls<Double>(1) }

    // Device tilt steers the gaze while the flight is live; the listener leaves with it.
    DisposableEffect(context, moving, tier) {
        val manager = if (moving && tier.cinematic) {
            context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        } else {
            null
        }
        val gravity = manager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                tilt.feed(event.values[0], event.values[1], event.values[2])
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
            environment = hdrEnvironment ?: fallbackEnvironment,
            cameraNode = cameraNode,
            mainLightNode = sun,
            fillLightNode = null,
            cameraManipulator = null,
            onGestureListener = null,
            autoCenterContent = false,
            lifecycle = stageLifecycle,
            renderQuality = if (tier.cinematic) RenderQuality.Default else RenderQuality.Performance,
            frameRatePolicy = if (moving) {
                FrameRatePolicy.Continuous(maxFps = HERO_MAX_FPS)
            } else {
                FrameRatePolicy.OnDemand(maxFps = HERO_MAX_FPS)
            },
            renderInvalidator = renderInvalidator,
            onFrame = { nanos ->
                val helmet = helmetNode[0]
                if (helmet != null) {
                    StartupMarker.mark("first_model_frame")
                    if (!modelLoader.isLoading) {
                        StartupMarker.mark("model_textured_frame")
                        if (entranceStart[0] == null) entranceStart[0] = clock.seconds
                    }
                }
                if (terrainNode[0] != null && terrainStart[0] == null) terrainStart[0] = clock.seconds
                val before = clock.seconds
                val seconds = clock.frame(nanos, moving)
                tilt.update((seconds - before).toFloat())
                val pose = heroFlightPose(
                    seconds = seconds,
                    period = tier.terrain.period,
                    tiltX = tilt.x,
                    tiltY = tilt.y,
                    entranceStart = entranceStart[0],
                    terrainStart = terrainStart[0],
                    motion = motionEnabled,
                )
                terrainNode[0]?.position = Position(
                    y = -DuskFlight.TERRAIN_RISE_UNITS * (1f - pose.terrainRise),
                    z = pose.terrainOffsetZ,
                )
                cameraNode.lookAt(pose)
                helmet?.apply {
                    position = Position(pose.helmetX, pose.helmetY, pose.helmetZ)
                    rotation = Rotation(x = -6f, y = pose.helmetYawDegrees)
                    scale = Scale(helmetBaseScale[0] * pose.helmetEntrance.coerceAtLeast(0.001f))
                }
            },
        ) {
            if (terrainGeometry != null && terrainInstance != null) {
                MeshNode(
                    primitiveType = RenderableManager.PrimitiveType.TRIANGLES,
                    vertexBuffer = terrainGeometry.vertexBuffer,
                    indexBuffer = terrainGeometry.indexBuffer,
                    boundingBox = terrainGeometry.boundingBox,
                    materialInstance = terrainInstance,
                    apply = {
                        terrainNode[0] = this
                        isShadowCaster = false
                        isShadowReceiver = false
                    },
                )
            }
            if (sunInstance != null) {
                SphereNode(
                    radius = DuskFlight.SUN_RADIUS,
                    stacks = 12,
                    slices = 24,
                    materialInstance = sunInstance,
                    position = DuskFlight.sunPosition,
                    apply = {
                        isShadowCaster = false
                        isShadowReceiver = false
                    },
                )
            }
            modelInstance?.let { instance ->
                ModelNode(
                    modelInstance = instance,
                    autoAnimate = false,
                    scaleToUnits = DuskFlight.HELMET_UNITS,
                    centerOrigin = Position(0f),
                    apply = {
                        helmetNode[0] = this
                        helmetBaseScale[0] = scale.x
                        // Invisible until its first textured frame: the entrance scales it in.
                        scale = Scale(scale.x * 0.001f)
                        isShadowCaster = false
                    },
                )
            }
        }
        // Raw Filament writes on the View, declared after the SceneView so they run after
        // its own `applyRenderQuality` effect and are never undone by it (#1078). Each
        // write asks for a frame: a parked loop would otherwise keep the old look.
        LaunchedEffect(view, tier) {
            view.fogOptions = view.fogOptions.also { fog ->
                fog.enabled = tier.cinematic
                fog.color[0] = DuskFlight.fogColor[0]
                fog.color[1] = DuskFlight.fogColor[1]
                fog.color[2] = DuskFlight.fogColor[2]
                fog.density = 0.045f
                fog.height = -1f
                fog.heightFalloff = 0.14f
                fog.distance = 6f
                // Ridges dissolve into the horizon; the disc, past this, stays sharp.
                fog.cutOffDistance = 80f
                fog.maximumOpacity = 0.9f
                fog.fogColorFromIbl = false
                fog.inScatteringStart = 20f
                fog.inScatteringSize = 14f
            }
            if (tier.cinematic) {
                view.bloomOptions = view.bloomOptions.also { bloom ->
                    bloom.enabled = true
                    bloom.strength = 0.3f
                    bloom.lensFlare = true
                    bloom.starburst = true
                    bloom.ghostCount = 3
                    bloom.ghostThreshold = 12f
                    bloom.haloThreshold = 12f
                    bloom.chromaticAberration = 0.004f
                }
                view.temporalAntiAliasingOptions = view.temporalAntiAliasingOptions.also { taa ->
                    taa.enabled = true
                }
                view.antiAliasing = View.AntiAliasing.NONE
            }
            renderInvalidator.requestRender()
        }
    }
}

/** 30 presented frames per second — the flight paces like film and the panel idles. */
private const val HERO_MAX_FPS = 30

private fun CameraNode.lookAt(pose: HeroFlightPose) {
    val roll = Math.toRadians(pose.rollDegrees.toDouble())
    lookAt(
        eye = Position(pose.eyeX, pose.eyeY, pose.eyeZ),
        center = Position(pose.targetX, pose.targetY, pose.targetZ),
        up = Direction(sin(roll).toFloat(), cos(roll).toFloat(), 0f),
    )
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
 * in (#3949). The clock pauses on the way down so the next frame gets no delta.
 */
@Composable
private fun rememberHeroLifecycle(active: Boolean, clock: HeroClock): Lifecycle {
    val parent = LocalLifecycleOwner.current.lifecycle
    val owner = remember { HeroLifecycleOwner() }
    DisposableEffect(parent, owner, active) {
        fun sync() {
            val resumed = active && parent.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if (!resumed) clock.pause()
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

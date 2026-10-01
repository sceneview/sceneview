package io.github.sceneview.demo.demos

import io.github.sceneview.demo.telemetry.LocalSampleId
import io.github.sceneview.demo.telemetry.logSampleInteraction
import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cyclone
import androidx.compose.material.icons.filled.Flare
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.android.filament.Engine
import com.google.android.filament.IndexBuffer
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.Texture
import com.google.android.filament.TextureSampler
import com.google.android.filament.VertexBuffer
import dev.romainguy.kotlin.math.Quaternion
import io.github.sceneview.FrameRatePolicy
import io.github.sceneview.SceneView
import io.github.sceneview.demo.DemoPreviewPlaceholder
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.DemoSettings
import io.github.sceneview.demo.DockItem
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.internal.CosmosFlight
import io.github.sceneview.demo.demos.internal.CosmosFocus
import io.github.sceneview.demo.demos.internal.CosmosFraming
import io.github.sceneview.demo.demos.internal.CosmosMeshes
import io.github.sceneview.demo.demos.internal.CosmosRig
import io.github.sceneview.demo.demos.internal.CosmosScene
import io.github.sceneview.demo.demos.internal.CosmosSpacetime
import io.github.sceneview.demo.demos.internal.CosmosSystem
import io.github.sceneview.demo.demos.internal.CosmosVoyage
import io.github.sceneview.demo.demos.internal.CosmosVoyageCamera
import io.github.sceneview.demo.demos.internal.GlowMesh
import io.github.sceneview.demo.demos.internal.RIBBON_STRIDE
import io.github.sceneview.demo.demos.internal.SpacetimeField
import io.github.sceneview.demo.demos.internal.SpacetimeTransition
import io.github.sceneview.demo.demos.internal.VoyageExit
import io.github.sceneview.demo.demos.internal.VoyageState
import io.github.sceneview.demo.demos.internal.orbitPose
import io.github.sceneview.demo.initialDemoMode
import io.github.sceneview.demo.rememberFirstFrameState
import io.github.sceneview.demo.theme.LocalMotionEnabled
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.demo.ui.ConnectedChoiceRow
import io.github.sceneview.math.Direction
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Scale
import io.github.sceneview.node.MeshNode as MeshNodeImpl
import io.github.sceneview.node.Node as NodeImpl
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberOnGestureListener
import io.github.sceneview.rememberRenderInvalidator
import io.github.sceneview.rememberView
import io.github.sceneview.safeDestroyIndexBuffer
import io.github.sceneview.safeDestroyTexture
import io.github.sceneview.safeDestroyVertexBuffer
import io.github.sceneview.sample.ui.LabeledSlider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import com.google.android.filament.Box as FilamentBox

/** One detonation of the burst, start to afterglow, in seconds. */
private const val BURST_PERIOD_SECONDS = 5.5f

/** How long a scene takes to fade in after a switch, in seconds. */
private const val REVEAL_SECONDS = 0.9f

private const val DEFAULT_BLOOM = 0.45f

/**
 * How long the glow takes to come up once the loading cover hands over, in seconds (#4160).
 * Bloom and the dust lanes only join after the first frame is on screen — their programs are
 * the costliest to link — so they ramp in over this, eased out, and the galaxy ignites instead
 * of popping brighter half a second after the cover lifts.
 */
private const val IGNITE_SECONDS = 0.7f

/** A frame gap longer than this is a hitch, not time the glow spent ramping. */
private const val IGNITE_HITCH_SECONDS = 0.1f
private const val ONE_FRAME_SECONDS = 1f / 60f

/**
 * **Cosmos** — four procedural, real-time space scenes lit by nothing but their own light and
 * a bloom pass: a four-arm spiral galaxy, a plasma star, a particle-track burst and a vortex flow
 * field. Nothing is a video or a texture; every point and stroke is geometry built on the CPU
 * once ([CosmosMeshes]) and animated in the shader.
 *
 * ### The recipe it teaches
 *
 * Glow is **additive, unlit, HDR geometry plus bloom**:
 *
 * - Radiance above 1.0 is what the bloom pass bleeds from, so every colour here is written in
 *   linear HDR (a star core at 3–6, a dim dust grain at 0.05) and the tone mapper rolls it off.
 * - `blending: add` in the material makes overlapping light pile up — 60 000 galaxy stars stack
 *   into a white-hot bulge with no sorting at all.
 * - `PrimitiveType.POINTS` and `LINES` draw at one device pixel on mobile, so points are
 *   camera-facing quads and strokes are camera-facing ribbons, both expanded in the vertex
 *   shader (`cosmos_sprite.mat`, `cosmos_ribbon.mat`) from one static buffer.
 * - Additive light cannot darken, so the galaxy's dust lanes are a second, alpha-blended
 *   material (`cosmos_dust.mat`) drawn last with a renderable priority of 7.
 * - The star is a `SphereNode` with a noise material (`cosmos_plasma.mat`): domain-warped fbm,
 *   ridged filaments and a Fresnel limb.
 * - A ringed world orbits it ([CosmosSystem]), lit by the star as a point at the origin inside its
 *   own unlit material (`cosmos_planet.mat`): a soft terminator, an atmosphere rim that scatters
 *   forward when the star is behind it, and the rings' shadow found by one ray-plane test. The
 *   rings (`cosmos_ring.mat`) are translucent and take the planet's shadow back. Tap it and the
 *   camera flies there, eased (`ease-expressive`), round the star rather than through it.
 *
 * Animation is uniforms only — time, the burst's head and fade, the galaxy's spin — so a frame
 * costs a handful of `setParameter` calls and no buffer upload.
 *
 * ### The voyage
 *
 * On opening, the camera goes on a **voyage** ([CosmosVoyage]): one continuous take per scene,
 * written as a table of keyframes — eye, target, focal length, roll, look-ahead — and joined by
 * jumps (light streaks, a surging wide lens) or fades. [CosmosVoyageCamera] turns the table into
 * a pose each frame with a Catmull-Rom spline, a look-ahead aim and a little handheld sway. Any
 * touch in the scene hands the camera back, eased; after [VoyageState.IDLE_RESUME_SECONDS]
 * untouched the voyage jumps on to the next scene. A scene picked in the dock is shown still for
 * [VoyageState.AUTO_START_SECONDS], then the voyage takes off from it. Only the dock's accent or
 * the Voyage toggle stops it for good; either starts it again.
 */
@Suppress("LongMethod", "CyclomaticComplexMethod")
@Composable
fun CosmosDemo(onBack: () -> Unit) {
    if (LocalInspectionMode.current) {
        DemoPreviewPlaceholder(title = "Cosmos", onBack = onBack)
        return
    }

    // The Star scene's view: Starlight (false) or Spacetime (true), switched by the pill over the
    // dock. `?tab=spacetime` opens straight on it, the voyage stopped.
    var spacetime by remember { mutableStateOf(initialDemoMode(listOf(false, true), false)) }
    var scene by remember { mutableStateOf(if (spacetime) CosmosScene.Star else CosmosScene.Galaxy) }
    // The voyage is on unless the user stopped it; `voyage` holds where it is in the render loop.
    var voyageOn by remember { mutableStateOf(!DemoSettings.qaMode && !spacetime) }
    val voyage = remember { VoyageState(playing = !DemoSettings.qaMode && !spacetime) }
    val voyageCamera = remember { CosmosVoyageCamera() }
    // The caption of the shot on screen while the voyage plays, null while the user has the camera.
    var voyageCaption by remember { mutableStateOf<String?>(null) }
    var animating by remember { mutableStateOf(true) }
    var bloom by remember { mutableFloatStateOf(DEFAULT_BLOOM) }
    val motionEnabled = LocalMotionEnabled.current

    val engine = rememberEngine()
    val materialLoader = rememberMaterialLoader(engine)
    val view = rememberView(engine)
    val renderInvalidator = rememberRenderInvalidator()
    val cameraNode = rememberCameraNode(engine) {
        position = Position(0f, 0f, 6f)
        lookAt(Position(0f, 0f, 0f))
        // Room for the star field shell (radius ~44) behind a camera up to ~8 units out.
        far = 200f
        near = 0.05f
    }

    val spriteMaterial by produceState<Material?>(null, materialLoader) {
        value = materialLoader.loadMaterial("materials/cosmos_sprite.filamat")
    }
    val ribbonMaterial by produceState<Material?>(null, materialLoader) {
        value = materialLoader.loadMaterial("materials/cosmos_ribbon.filamat")
    }
    val plasmaMaterial by produceState<Material?>(null, materialLoader) {
        value = materialLoader.loadMaterial("materials/cosmos_plasma.filamat")
    }
    val dustMaterial by produceState<Material?>(null, materialLoader) {
        value = materialLoader.loadMaterial("materials/cosmos_dust.filamat")
    }
    val sprites = remember(materialLoader, spriteMaterial) {
        spriteMaterial?.let { material ->
            SpriteInstances(List(SPRITE_SLOTS) { materialLoader.createInstance(material) })
        }
    }
    val ribbons = remember(materialLoader, ribbonMaterial) {
        ribbonMaterial?.let { material ->
            RibbonInstances(List(RIBBON_SLOTS) { materialLoader.createInstance(material) })
        }
    }
    val plasma = remember(materialLoader, plasmaMaterial) {
        plasmaMaterial?.let { material ->
            PlasmaInstances(
                star = materialLoader.createInstance(material).apply { setPlasma(STAR_PLASMA) },
                nucleus = materialLoader.createInstance(material).apply { setPlasma(NUCLEUS_PLASMA) },
            )
        }
    }
    val planetMaterial by produceState<Material?>(null, materialLoader) {
        value = materialLoader.loadMaterial("materials/cosmos_planet.filamat")
    }
    val ringMaterial by produceState<Material?>(null, materialLoader) {
        value = materialLoader.loadMaterial("materials/cosmos_ring.filamat")
    }
    val world = remember(materialLoader, planetMaterial, ringMaterial) {
        val planetBase = planetMaterial ?: return@remember null
        val ringBase = ringMaterial ?: return@remember null
        WorldInstances(materialLoader.createInstance(planetBase), materialLoader.createInstance(ringBase))
    }
    val dust = remember(materialLoader, dustMaterial) {
        dustMaterial?.let { material -> materialLoader.createInstance(material).apply { setParameter("opacity", 0f) } }
    }
    val sheetMaterial by produceState<Material?>(null, materialLoader) {
        value = materialLoader.loadMaterial("materials/cosmos_spacetime.filamat")
    }
    // Spacetime's sheet and the six worlds that come to rest on it beside the ringed one.
    val fabric = remember(materialLoader, sheetMaterial, planetMaterial) {
        val sheetBase = sheetMaterial ?: return@remember null
        val planetBase = planetMaterial ?: return@remember null
        FabricInstances(
            sheet = materialLoader.createInstance(sheetBase),
            worlds = List(FABRIC_WORLDS.size) { materialLoader.createInstance(planetBase) },
        )
    }

    // GPU meshes, built per scene on first use. The arrays are computed off the main thread;
    // the Filament buffers are created on it. Declared before the SceneView so they are
    // destroyed after the nodes that draw them.
    val meshes = remember { mutableStateMapOf<CosmosScene, SceneMeshes>() }
    // The galaxy comes in layers, each a sparser sample of the whole: the first is on screen
    // within a second of opening, the rest densify it while it fades in.
    val galaxyLayers = remember { mutableStateListOf<GpuMesh>() }
    val openedAt = remember { SystemClock.elapsedRealtime() }
    val stars by produceState<GpuMesh?>(null, engine) {
        val cpu = withContext(Dispatchers.Default) { CosmosMeshes.starField().staged() }
        value = cpu.upload(engine)
    }
    val warpStreaks by produceState<GpuMesh?>(null, engine) {
        val cpu = withContext(Dispatchers.Default) { CosmosVoyage.warpStreaks().staged() }
        value = cpu.upload(engine)
    }
    LaunchedEffect(engine, scene) {
        // The selected scene first, then the others in the background so a switch is instant.
        // The first galaxy layer goes ahead of everything: it is what a user sees first.
        if (galaxyLayers.isEmpty()) {
            val first = withContext(Dispatchers.Default) { CosmosMeshes.galaxyLayer(0).staged() }
            galaxyLayers += first.upload(engine)
        }
        val order = listOf(scene) + CosmosScene.entries.filter { it != scene }
        for (target in order) {
            if (!meshes.containsKey(target)) {
                val staged = withContext(Dispatchers.Default) { buildScene(target) }
                meshes[target] = staged.mapValues { (_, mesh) -> mesh.upload(engine) }.let(::SceneMeshes)
            }
            if (target == CosmosScene.Galaxy && galaxyLayers.size < CosmosMeshes.GALAXY_LAYERS) {
                while (galaxyLayers.size < CosmosMeshes.GALAXY_LAYERS) {
                    val layer = galaxyLayers.size
                    val staged = withContext(Dispatchers.Default) { CosmosMeshes.galaxyLayer(layer).staged() }
                    galaxyLayers += staged.upload(engine)
                }
                Log.i(TAG, "galaxy complete ${SystemClock.elapsedRealtime() - openedAt} ms after opening")
            }
        }
    }
    DisposableEffect(engine) {
        onDispose {
            meshes.values.forEach { scene -> scene.parts.values.forEach { it.destroy(engine) } }
            meshes.clear()
            galaxyLayers.forEach { it.destroy(engine) }
            galaxyLayers.clear()
        }
    }
    DisposableEffect(engine, stars) {
        val mesh = stars
        onDispose { mesh?.destroy(engine) }
    }
    DisposableEffect(engine, warpStreaks) {
        val mesh = warpStreaks
        onDispose { mesh?.destroy(engine) }
    }
    // The sheet's grid (40 k vertices) and its horizon map (the key light's visibility over the
    // star's hollow, 512² texels) are built the first time the Star scene is on screen, off the
    // main thread, and kept: the star and the light never move in Spacetime.
    val sheetWanted = scene == CosmosScene.Star
    val sheetMesh by produceState<GpuSheet?>(null, engine, sheetWanted) {
        if (value == null && sheetWanted) {
            val started = SystemClock.elapsedRealtime()
            val staged = withContext(Dispatchers.Default) { CosmosSpacetime.grid().stagedSheet() }
            value = staged.uploadSheet(engine)
            Log.i(TAG, "spacetime sheet built in ${SystemClock.elapsedRealtime() - started} ms")
        }
    }
    DisposableEffect(engine, sheetMesh) {
        val sheet = sheetMesh
        onDispose { sheet?.destroy(engine) }
    }
    // The sheet's material samples the horizon map. The binding runs when the composition that
    // first holds both is applied, before the next frame renders the sheet, and once per pair.
    DisposableEffect(sheetMesh, fabric) {
        val sheet = sheetMesh
        if (sheet != null && fabric != null) fabric.sheet.bindHorizon(sheet.horizon)
        onDispose { }
    }

    val clock = remember { CosmosClock() }
    // The Star scene's camera: what it looks at, and the eased flight between two looks.
    var focus by remember { mutableStateOf(CosmosFocus.System) }
    // One rig per render loop: the orbit and camera maths reuse its buffers, frame after frame.
    val rig = remember { CosmosRig() }
    val flight = remember { CosmosFlight(rig) }
    val planetNode = remember { arrayOfNulls<NodeImpl>(1) }
    val streakNode = remember { arrayOfNulls<NodeImpl>(1) }
    val freePose = remember { FloatArray(CosmosSystem.POSE_FLOATS) }
    val orbitScratch = remember { FloatArray(ORBIT_SCRATCH_FLOATS) }
    val dragDegreesPerPx = with(LocalDensity.current) { DRAG_DEGREES_PER_DP / 1.dp.toPx() }
    val orbitNode = remember { arrayOfNulls<NodeImpl>(1) }
    val minTapRadiusPx = with(LocalDensity.current) { SceneViewTokens.Space.xl.toPx() }
    LaunchedEffect(scene) {
        focus = CosmosFocus.System
        flight.reset()
    }
    val ignition = remember { CosmosIgnition() }
    val galaxyNode = remember { arrayOfNulls<NodeImpl>(1) }
    val starNode = remember { arrayOfNulls<NodeImpl>(1) }
    val firstFrame = rememberFirstFrameState(engine)
    val galaxyShown = remember { booleanArrayOf(false) }
    // Spacetime: where the entry sequence is, the sheet at this frame, the camera's turn (yaw,
    // elevation) and the nodes the sequence shows and hides.
    val transition = remember { SpacetimeTransition() }
    val field = remember { SpacetimeField() }
    val spacetimeView = remember { floatArrayOf(0f, CosmosSpacetime.ELEVATION_DEGREES) }
    val spacetimePose = remember { FloatArray(CosmosSystem.POSE_FLOATS) }
    val basePose = remember { FloatArray(CosmosSystem.POSE_FLOATS) }
    val fabricFrame = remember { FabricFrame() }
    val sheetNode = remember { arrayOfNulls<NodeImpl>(1) }
    val fabricNodes = remember { arrayOfNulls<NodeImpl>(FABRIC_WORLDS.size) }
    val haloNode = remember { arrayOfNulls<NodeImpl>(1) }
    val prominenceNode = remember { arrayOfNulls<NodeImpl>(1) }
    val starFieldNode = remember { arrayOfNulls<NodeImpl>(1) }
    val appliedBloom = remember { floatArrayOf(-1f) }

    val telemetrySampleId = LocalSampleId.current
    val showSpacetime: (Boolean) -> Unit = { on ->
        if (on != spacetime) {
            spacetime = on
            // The camera's turn is kept on the way back, so the return flight leaves from the
            // pose on screen; it is reset once the sequence is back at Starlight (onFrame).
            if (on) {
                // Spacetime holds the camera still: the voyage stops (a pick would restart it),
                // the free camera lets go of any drag or close-up, and it all eases from the
                // pose on screen while the sheet comes in.
                voyageOn = false
                voyage.stop(flight)
                voyage.resetDrag()
                flight.start()
                focus = CosmosFocus.System
                logSampleInteraction(telemetrySampleId, "spacetime")
            }
        }
    }
    val spacetimeLegend = stringResource(R.string.demo_cosmos_spacetime_legend)
    // Opened on Spacetime (`?tab=spacetime`), the cover and "Scene ready" also wait for the
    // sheet, built off the main thread: lifted earlier they would show an empty frame.
    val openedOnSpacetime = remember { spacetime }
    firstFrame.holdUntil(landed = !openedOnSpacetime || (sheetMesh != null && fabric != null))
    val coverLifted = remember(firstFrame, fabric) {
        derivedStateOf { firstFrame.rendered.value && (!openedOnSpacetime || (sheetMesh != null && fabric != null)) }
    }
    DemoScaffold(
        title = stringResource(R.string.demo_cosmos_title),
        onBack = onBack,
        firstFrameRendered = coverLifted,
        loadingLabel = stringResource(R.string.demo_cosmos_loading),
        peekHeader = when {
            voyageCaption != null -> voyageCaption
            scene == CosmosScene.Star && spacetime -> spacetimeLegend
            scene == CosmosScene.Star -> focus.caption
            else -> scene.caption
        },
        onResetSettings = {
            showSpacetime(false)
            voyageOn = true
            voyage.resumeNow()
            animating = true
            bloom = DEFAULT_BLOOM
        },
        dock = CosmosScene.entries.map { target ->
            sceneDockItem(target, scene) {
                // Star again while in Spacetime: already there, the voyage stays stopped.
                if (it == CosmosScene.Star && spacetime) return@sceneDockItem
                // Any other scene leaves Spacetime.
                showSpacetime(false)
                // The scene picked is shown still; a few seconds of calm and the voyage goes on.
                voyage.pick(flight)
                scene = it
                // `galaxy` | `star` | `burst` | `flow` — the taxonomy shared with iOS.
                logSampleInteraction(telemetrySampleId, it.name.lowercase())
            }
        },
        dockAccent = if (voyageOn && voyageCaption != null) {
            DockItem(Icons.Filled.Pause, "Stop the voyage", {
                voyageOn = false
                voyage.stop(flight)
            })
        } else {
            DockItem(Icons.Filled.RocketLaunch, "Start the voyage", {
                // The voyage goes back to Starlight before it moves on.
                showSpacetime(false)
                voyageOn = true
                animating = true
                voyage.resumeNow()
            })
        },
        // The Star scene's two views, over the dock whenever the star is on screen — the
        // voyage's Star shot included. The scaffold measures the band; nothing here places it.
        bottomOverlay = if (scene == CosmosScene.Star) {
            { SpacetimePill(spacetime, showSpacetime) }
        } else {
            null
        },
        controls = {
            LabeledSlider(
                label = "Bloom",
                value = bloom,
                onValueChange = { bloom = it },
                valueRange = 0f..1f,
                decimals = 2,
            )
            Spacer(modifier = Modifier.height(SceneViewTokens.Space.md))
            ToggleRow("Voyage", voyageOn) { on ->
                if (on) showSpacetime(false)
                voyageOn = on
                if (on) voyage.resumeNow() else voyage.stop(flight)
            }
            Spacer(modifier = Modifier.height(SceneViewTokens.Space.xs))
            ToggleRow("Animate", animating) { animating = it }
        },
    ) {
        SceneView(
            modifier = Modifier.fillMaxSize(),
            engine = engine,
            view = view,
            materialLoader = materialLoader,
            cameraNode = cameraNode,
            cameraManipulator = null,
            autoCenterContent = false,
            frameRatePolicy = FrameRatePolicy.Continuous(),
            renderInvalidator = renderInvalidator,
            onGestureListener = rememberOnGestureListener(
                // In Spacetime the voyage is stopped and stays so: a touch hands nothing back.
                onDown = { _, _ -> if (!spacetime) voyage.takeOver(flight) },
                onScroll = { _, _, _, distance ->
                    if (spacetime && scene == CosmosScene.Star) {
                        // A drag turns the camera round the sheet, kept above it: the sheet has
                        // one face.
                        spacetimeView[0] += distance.x * dragDegreesPerPx
                        spacetimeView[1] = (spacetimeView[1] - distance.y * dragDegreesPerPx).coerceIn(
                            CosmosSpacetime.MIN_ELEVATION_DEGREES,
                            CosmosSpacetime.MAX_ELEVATION_DEGREES,
                        )
                    } else {
                        voyage.takeOver(flight)
                        voyage.drag(scene, distance.x * dragDegreesPerPx, -distance.y * dragDegreesPerPx)
                    }
                },
                onSingleTapConfirmed = { event, _ ->
                // Tap-to-frame is Starlight's: in Spacetime, and on the way in or out, a tap
                // does nothing.
                if (scene == CosmosScene.Star && !spacetime && !transition.active) {
                    val viewport = view.viewport
                    val hit = CosmosSystem.hit(
                        pose = flight.lastPose,
                        time = flight.lastTime,
                        width = viewport.width.toFloat(),
                        height = viewport.height.toFloat(),
                        x = event.x,
                        y = event.y,
                        minRadiusPx = minTapRadiusPx,
                    )
                    // A second tap on what is already in focus, or a tap on empty space, pulls back.
                    val next = if (hit == null || hit == focus) CosmosFocus.System else hit
                    if (next != focus) {
                        flight.start()
                        focus = next
                        voyage.resetDrag()
                    }
                }
            },
            ),
            onFrame = { nanos ->
                firstFrame.onFrame(nanos)
                val frozen = DemoSettings.qaMode || !motionEnabled
                val current = scene
                // The clock waits for the loading cover to lift: the voyage's opening shot would
                // otherwise play out under it.
                clock.advance(nanos, current, running = animating && !frozen && firstFrame.rendered.value)
                val inSpacetime = spacetime && current == CosmosScene.Star
                val time = when {
                    !frozen -> clock.sceneTime
                    inSpacetime -> CosmosSpacetime.QA_TIME
                    else -> QA_TIME[current.ordinal]
                }
                // The opening scene is not revealed: the loading cover already fades it in, and a
                // reveal started under the cover finished after it, as a pop (#4160).
                val sceneReveal = if (frozen || !clock.switched) {
                    1f
                } else {
                    CosmosFraming.reveal(clock.sceneTime, REVEAL_SECONDS)
                }
                val viewport = view.viewport
                val aspect = if (viewport.height > 0) viewport.width.toFloat() / viewport.height else 0.5f
                val step = voyage.step(nanos, counted = firstFrame.rendered.value)
                // The voyage runs on the scene's clock, so it pauses with Animate and reduced motion
                // and counts its calm again from when they are back.
                if (!voyageOn || !animating || frozen) {
                    voyage.hold(flight)
                } else if (voyage.dueToResume()) {
                    voyage.resumeNow()
                }
                val voyagePose: FloatArray
                val voyageFocal: Float
                val caption: String?
                // Where a jump away from the free camera lands, on the frame it does.
                var landing: CosmosScene? = null
                if (voyage.playing) {
                    val shot = CosmosVoyage.shotOf(current)
                    val ending = clock.sceneTime > shot.seconds
                    if (ending) {
                        Log.i(TAG, voyage.shotPacing(current))
                        voyage.arrivedByWarp = shot.exit == VoyageExit.Warp
                        scene = CosmosVoyage.next(current)
                    }
                    voyageCamera.evaluate(shot, clock.sceneTime, time, aspect, voyage.arrivedByWarp)
                    voyagePose = voyageCamera.pose
                    voyageFocal = voyageCamera.focal
                    voyage.show(voyageCamera.fade, voyageCamera.streaks)
                    // The caption changes with the scene, not a frame after it.
                    caption = if (ending) {
                        CosmosVoyage.openingCaption(CosmosVoyage.next(current))
                    } else {
                        voyageCamera.caption
                    }
                } else {
                    val framing = if (current == CosmosScene.Star) {
                        rig.pose(focus, time, aspect)
                    } else {
                        CosmosFraming.pose(current, time, aspect)
                    }
                    framing.copyInto(freePose)
                    // The flow field has an edge: it is never turned, whatever drag is left over.
                    if (current != CosmosScene.Flow) orbitPose(freePose, voyage.yaw, voyage.pitch, orbitScratch)
                    // QA captures after a tap must show where the flight lands, not a frame of it.
                    val instant = !motionEnabled || DemoSettings.qaMode
                    val free = flight.advance(nanos, freePose, instant)
                    val freeFocal = flight.focal(CosmosVoyageCamera.DEFAULT_FOCAL)
                    if (voyage.leaving >= 0f) {
                        // Jumping away from the free camera, on to the voyage's next scene.
                        voyage.leaving += step / CosmosVoyageCamera.WARP_OUT_SECONDS
                        voyageCamera.warpAway(free, freeFocal, voyage.leaving)
                        voyagePose = voyageCamera.pose
                        voyageFocal = voyageCamera.focal
                        voyage.show(voyageCamera.fade, voyageCamera.streaks)
                        if (voyage.leaving >= 1f) {
                            // A scene picked in the dock gets its own shot before the voyage moves on.
                            landing = if (voyage.inPlace) current else CosmosVoyage.next(current)
                            if (landing == current) clock.restart() else scene = landing
                            voyage.arrive()
                        }
                    } else {
                        voyagePose = free
                        voyageFocal = freeFocal
                        voyage.settle(step)
                    }
                    caption = landing?.let(CosmosVoyage::openingCaption)
                }
                if (voyageCaption != caption) voyageCaption = caption
                // Spacetime: the sequence runs once the sheet is built and the cover has lifted,
                // at once for QA and reduced motion; leaving the Star scene drops it.
                transition.enter(inSpacetime && sheetMesh != null && fabric != null)
                if (current != CosmosScene.Star || frozen || firstFrame.rendered.value) {
                    transition.advance(nanos, instant = frozen || current != CosmosScene.Star)
                }
                val sequence = transition.clock
                if (sequence <= 0f && !inSpacetime) {
                    spacetimeView[0] = 0f
                    spacetimeView[1] = CosmosSpacetime.ELEVATION_DEGREES
                }
                val flown = CosmosSpacetime.flight(sequence)
                val pose: FloatArray
                val focal: Float
                if (sequence > 0f) {
                    // The camera flies from wherever Starlight has it to Spacetime's still pose.
                    voyagePose.copyInto(basePose)
                    CosmosSpacetime.pose(aspect, spacetimeView[0], spacetimeView[1], spacetimePose)
                    pose = rig.blend(basePose, spacetimePose, flown)
                    focal = voyageFocal + (CosmosVoyageCamera.DEFAULT_FOCAL - voyageFocal) * flown
                } else {
                    pose = voyagePose
                    focal = voyageFocal
                }
                val exposure = if (current == CosmosScene.Galaxy) {
                    CosmosVoyage.galaxyExposure(sqrt(pose[0] * pose[0] + pose[1] * pose[1] + pose[2] * pose[2]))
                } else {
                    1f
                }
                val reveal = sceneReveal * voyage.fade * exposure
                flight.record(pose, time, focal)
                cameraNode.lookAt(
                    eye = Position(pose[0], pose[1], pose[2]),
                    center = Position(pose[3], pose[4], pose[5]),
                    up = Direction(pose[6], pose[7], pose[8]),
                )
                if (focal != voyage.appliedFocal) {
                    voyage.appliedFocal = focal
                    cameraNode.focalLength = focal.toDouble()
                }
                streakNode[0]?.let { node ->
                    val visible = voyage.streaks > 0f
                    if (node.isVisible != visible) node.isVisible = visible
                    if (visible) {
                        // The streaks ride with the camera: its eye, its view axis.
                        node.position = Position(pose[0], pose[1], pose[2])
                        node.lookAt(
                            targetWorldPosition = Position(pose[3], pose[4], pose[5]),
                            upDirection = Direction(pose[6], pose[7], pose[8]),
                            smooth = false,
                        )
                        ribbons?.warp?.setParameter("time", voyage.warpClock)
                        ribbons?.warp?.setParameter("intensity", voyage.streaks)
                    }
                }
                if (!galaxyShown[0] && current == CosmosScene.Galaxy && galaxyLayers.isNotEmpty()) {
                    galaxyShown[0] = true
                    Log.i(TAG, "first galaxy frame ${SystemClock.elapsedRealtime() - openedAt} ms after opening")
                }
                galaxyNode[0]?.rotation = Rotation(y = -time * GALAXY_SPIN_DEGREES_PER_SECOND)
                starNode[0]?.rotation = Rotation(y = time * STAR_SPIN_DEGREES_PER_SECOND, x = 12f)
                // In Spacetime the star shrinks to its place among the worlds, its glow goes out
                // before the sheet reaches it, and so does the sky.
                val starScale = 1f + (CosmosSpacetime.STAR_SCALE - 1f) * flown
                starNode[0]?.scale = Scale((1f + 0.012f * sin(time * 2.1f)) * starScale)
                val glow = CosmosSpacetime.glow(sequence)
                val sky = CosmosSpacetime.starField(sequence)
                sprites?.update(current, time, reveal, glow, sky)
                ribbons?.update(current, time, reveal * glow)
                plasma?.update(current, time, reveal)
                starFieldNode[0]?.let { if (it.isVisible != sky > 0f) it.isVisible = sky > 0f }
                // The halo is drawn over the sheet while it fades, never cut by it.
                if (fabricFrame.haloCulled == sequence > 0f) {
                    fabricFrame.haloCulled = sequence <= 0f
                    sprites?.halo?.setDepthCulling(fabricFrame.haloCulled)
                }
                if (current == CosmosScene.Star) {
                    // The Star scene's own nodes: outside it these slots hold destroyed nodes.
                    haloNode[0]?.let { if (it.isVisible != glow > 0f) it.isVisible = glow > 0f }
                    prominenceNode[0]?.let { if (it.isVisible != glow > 0f) it.isVisible = glow > 0f }
                    orbitNode[0]?.let { if (it.isVisible != glow > 0f) it.isVisible = glow > 0f }
                    orbitNode[0]?.quaternion = rig.trailRotation(time, aspect).toQuaternion()
                    val at = rig.planetPosition(time, aspect)
                    val spin = rig.planetRotation(time, aspect)
                    if (sequence > 0f && fabric != null) {
                        // The sheet at this moment, and the worlds on it.
                        fabricFrame.place(field, fabric, time, sequence, at, spin, reveal)
                        planetNode[0]?.apply {
                            val p = fabricFrame.ringed
                            position = Position(p[0], p[1], p[2])
                            quaternion = fabricFrame.ringedSpin.toQuaternion()
                        }
                        world?.light(fabricFrame.ringedSun)
                        fabricNodes.forEachIndexed { slot, node ->
                            node ?: return@forEachIndexed
                            val body = FABRIC_WORLDS[slot]
                            val arrived = CosmosSpacetime.arrival(body, sequence)
                            if (node.isVisible != arrived > 0f) node.isVisible = arrived > 0f
                            if (arrived > 0f) {
                                node.position = Position(
                                    fabricFrame.positions[body * 3],
                                    fabricFrame.positions[body * 3 + 1],
                                    fabricFrame.positions[body * 3 + 2],
                                )
                                node.scale = Scale(arrived)
                            }
                        }
                    } else {
                        planetNode[0]?.apply {
                            position = Position(at[0], at[1], at[2])
                            quaternion = spin.toQuaternion()
                        }
                        if (fabricFrame.lit) {
                            fabricFrame.lit = false
                            world?.light(null)
                            fabricNodes.forEach { node -> if (node?.isVisible == true) node.isVisible = false }
                        }
                    }
                    sheetNode[0]?.let { node ->
                        val visible = sequence > CosmosSpacetime.SHEET_IN_START
                        if (node.isVisible != visible) node.isVisible = visible
                    }
                    world?.update(time, reveal)
                    // The trail fades out as the camera closes on the planet: from the follow view
                    // the arc behind it runs past the lens.
                    val trail = rig.trailVisibility(pose[0], pose[1], pose[2], time, aspect)
                    ribbons?.trail?.setParameter("intensity", reveal * trail * glow)
                }
                ignition.advance(nanos, firstFrame.rendered.value, instant = frozen)
                // Spacetime takes the bloom down to a trace on the way in; flight 0 leaves it as set.
                val strength = CosmosSpacetime.bloom(bloom, sequence) * ignition.level
                if (strength != appliedBloom[0]) {
                    appliedBloom[0] = strength
                    view.bloomOptions = view.bloomOptions.also { it.strength = strength }
                }
                if (current == CosmosScene.Galaxy) dust?.setParameter("opacity", reveal * ignition.level)
            },
        ) {
            val sceneMeshes = meshes[scene]
            stars?.let { mesh -> sprites?.let { GlowMeshNode(mesh, it.starField) { starFieldNode[0] = this } } }
            val streaks = warpStreaks
            if (streaks != null && ribbons != null) {
                // The jump's light streaks ride with the camera, hidden outside a jump. The node
                // is the one shown and hidden: a hidden parent hides its mesh.
                Node(apply = {
                    streakNode[0] = this
                    isVisible = false
                }) {
                    GlowMeshNode(streaks, ribbons.warp)
                }
            }
            val materialsReady = sprites != null && ribbons != null && plasma != null && dust != null
            if (scene == CosmosScene.Galaxy && sprites != null) {
                Node(apply = { galaxyNode[0] = this }) {
                    galaxyLayers.forEach { layer -> key(layer) { GlowMeshNode(layer, sprites.galaxy) } }
                    val lanes = sceneMeshes?.parts?.get(CosmosPart.Dust)
                    // The dust joins once the stars are up: its program is one less to link before
                    // the first frame, and it fades in with the rest.
                    if (lanes != null && dust != null && firstFrame.rendered.value) {
                        // Dust absorbs light, so it is alpha-blended and drawn after the stars.
                        MeshNode(
                            primitiveType = RenderableManager.PrimitiveType.TRIANGLES,
                            vertexBuffer = lanes.vertexBuffer,
                            indexBuffer = lanes.indexBuffer,
                            boundingBox = lanes.box,
                            materialInstance = dust,
                            apply = {
                                configureGlow()
                                setPriority(DUST_PRIORITY)
                            },
                        )
                    }
                }
            }
            if (sceneMeshes != null && materialsReady) {
                when (scene) {
                    CosmosScene.Galaxy -> Unit
                    CosmosScene.Star -> {
                        Node(apply = { starNode[0] = this }) {
                            SphereNode(
                                radius = 1f,
                                stacks = 64,
                                slices = 96,
                                materialInstance = plasma.star,
                                apply = {
                                    isShadowCaster = false
                                    isShadowReceiver = false
                                },
                            )
                            GlowMeshNode(sceneMeshes.parts.getValue(CosmosPart.Strokes), ribbons.prominences) {
                                prominenceNode[0] = this
                            }
                        }
                        GlowMeshNode(sceneMeshes.parts.getValue(CosmosPart.Main), sprites.halo) { haloNode[0] = this }
                        if (world != null) {
                            // The trail turns with the planet: a static arc under a rotating node.
                            Node(apply = { orbitNode[0] = this }) {
                                GlowMeshNode(sceneMeshes.parts.getValue(CosmosPart.Trail), ribbons.trail)
                            }
                            SphereNode(
                                radius = CosmosSystem.PLANET_RADIUS,
                                stacks = 48,
                                slices = 64,
                                materialInstance = world.planet,
                                apply = {
                                    planetNode[0] = this
                                    isShadowCaster = false
                                    isShadowReceiver = false
                                },
                            ) {
                                GlowMeshNode(sceneMeshes.parts.getValue(CosmosPart.Ring), world.ring)
                            }
                        }
                        val sheet = sheetMesh
                        if (sheet != null && fabric != null) {
                            // Spacetime's sheet, opaque and drawn under everything else; hidden
                            // until the entry sequence brings it in.
                            MeshNode(
                                primitiveType = RenderableManager.PrimitiveType.TRIANGLES,
                                vertexBuffer = sheet.mesh.vertexBuffer,
                                indexBuffer = sheet.mesh.indexBuffer,
                                boundingBox = sheet.mesh.box,
                                materialInstance = fabric.sheet,
                                apply = {
                                    sheetNode[0] = this
                                    configureGlow()
                                    isVisible = false
                                },
                            )
                            FABRIC_WORLDS.forEachIndexed { slot, body ->
                                SphereNode(
                                    radius = CosmosSpacetime.RADIUS[body],
                                    stacks = 32,
                                    slices = 48,
                                    materialInstance = fabric.worlds[slot],
                                    apply = {
                                        fabricNodes[slot] = this
                                        isShadowCaster = false
                                        isShadowReceiver = false
                                        isVisible = false
                                    },
                                )
                            }
                        }
                    }
                    CosmosScene.Burst -> {
                        GlowMeshNode(sceneMeshes.parts.getValue(CosmosPart.Strokes), ribbons.burst)
                        GlowMeshNode(sceneMeshes.parts.getValue(CosmosPart.Dust), sprites.sparks)
                        GlowMeshNode(sceneMeshes.parts.getValue(CosmosPart.Main), sprites.flash)
                    }
                    CosmosScene.Flow -> {
                        GlowMeshNode(sceneMeshes.parts.getValue(CosmosPart.Main), sprites.backdrop)
                        GlowMeshNode(sceneMeshes.parts.getValue(CosmosPart.Strokes), ribbons.flow)
                        GlowMeshNode(sceneMeshes.parts.getValue(CosmosPart.Dust), sprites.dust)
                        CosmosMeshes.flowVortices.take(NUCLEI).forEachIndexed { index, vortex ->
                            SphereNode(
                                radius = NUCLEUS_RADII[index],
                                stacks = 24,
                                slices = 32,
                                materialInstance = plasma.nucleus,
                                position = Position(
                                    vortex.x,
                                    vortex.y,
                                    CosmosMeshes.flowDepth(vortex.x, vortex.y) + NUCLEUS_RADII[index] * 0.4f,
                                ),
                                apply = {
                                    isShadowCaster = false
                                    isShadowReceiver = false
                                },
                            )
                        }
                    }
                }
            }
        }
        // Raw View writes, declared after the SceneView so they land after its own
        // render-quality effect (#1078). Nothing here casts or receives a shadow and nothing
        // is lit, so SSAO and shadows are pure cost; bloom is the whole look.
        // Bloom waits for the first frame on screen. Its programs are the costliest to link, and
        // on a software GL (the emulator) linking them held the loading cover for seconds; this
        // way the galaxy shows first. The glow then rises from zero over IGNITE_SECONDS (driven
        // per frame in onFrame) — switched on at full strength it read as a pop (#4160).
        val onScreen = firstFrame.rendered.value
        LaunchedEffect(onScreen) {
            if (onScreen) Log.i(TAG, "on screen ${SystemClock.elapsedRealtime() - openedAt} ms after opening")
        }
        LaunchedEffect(view, bloom, onScreen) {
            view.bloomOptions = view.bloomOptions.also { options ->
                options.enabled = bloom > 0f && onScreen
                options.strength = bloom * ignition.level
                // The render loop sets the strength again on its next frame (Spacetime lowers it).
                appliedBloom[0] = -1f
                options.levels = 7
                options.resolution = 512
                options.threshold = true
                options.lensFlare = false
            }
            view.ambientOcclusionOptions = view.ambientOcclusionOptions.also { it.enabled = false }
            view.setShadowingEnabled(false)
            renderInvalidator.requestRender()
        }
    }
}

@Composable
private fun ToggleRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    // Toggleable on the whole row, so tapping the label flips it and UiAutomator finds it.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = value, onValueChange = onChange),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = value, onCheckedChange = null)
    }
}

private fun sceneDockItem(target: CosmosScene, current: CosmosScene, select: (CosmosScene) -> Unit) = DockItem(
    icon = when (target) {
        CosmosScene.Galaxy -> Icons.Filled.Cyclone
        CosmosScene.Star -> Icons.Filled.WbSunny
        CosmosScene.Burst -> Icons.Filled.Flare
        CosmosScene.Flow -> Icons.Filled.Waves
    },
    label = target.label,
    onClick = { select(target) },
    selected = current == target,
)

@Composable
private fun io.github.sceneview.SceneScope.GlowMeshNode(
    mesh: GpuMesh,
    material: MaterialInstance,
    apply: MeshNodeImpl.() -> Unit = {},
) {
    MeshNode(
        primitiveType = RenderableManager.PrimitiveType.TRIANGLES,
        vertexBuffer = mesh.vertexBuffer,
        indexBuffer = mesh.indexBuffer,
        boundingBox = mesh.box,
        materialInstance = material,
        apply = {
            configureGlow()
            apply()
        },
    )
}

/**
 * The Star scene's two views, Starlight and Spacetime: a segmented pill over the dock, in the
 * fixed over-media palette of `DESIGN.md` (`mode-pill-*`) — opaque, so it reads on the black sky
 * and on the lit sheet alike, in light and dark theme.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SpacetimePill(spacetime: Boolean, onSelect: (Boolean) -> Unit) {
    val pill = SceneViewTokens.ModePill
    val starlight = stringResource(R.string.demo_cosmos_starlight)
    val spacetimeLabel = stringResource(R.string.demo_cosmos_spacetime)
    Surface(
        shape = CircleShape,
        color = pill.container,
        contentColor = pill.onContainer,
        border = BorderStroke(pill.outlineWidth, pill.outline),
    ) {
        ConnectedChoiceRow(
            options = listOf(false, true),
            selected = spacetime,
            onSelect = onSelect,
            label = { if (it) spacetimeLabel else starlight },
            modifier = Modifier.padding(horizontal = SceneViewTokens.Space.xs),
            optionTestTag = { if (it) "cosmos_spacetime" else "cosmos_starlight" },
            colors = ToggleButtonDefaults.colors(
                containerColor = pill.container,
                contentColor = pill.onContainer,
                checkedContainerColor = pill.selectedContainer,
                checkedContentColor = pill.onSelected,
            ),
            fillWidth = false,
        )
    }
}

private fun MeshNodeImpl.configureGlow() {
    isShadowCaster = false
    isShadowReceiver = false
}

/** Frozen per-scene times for QA captures and reduced motion: each at its most telling moment. */
private val QA_TIME = floatArrayOf(6f, 3f, 1.9f, 4f)

/** How far a drag turns the free camera, in degrees per dp. */
private const val DRAG_DEGREES_PER_DP = 0.3f

private const val ORBIT_SCRATCH_FLOATS = 15

private const val TAG = "CosmosDemo"

/** Renderable priority of the dust lanes: last, over the additive stars they darken. */
private const val DUST_PRIORITY = 7

private const val GALAXY_SPIN_DEGREES_PER_SECOND = 3.5f
private const val STAR_SPIN_DEGREES_PER_SECOND = 4f
private const val NUCLEI = 4
private val NUCLEUS_RADII = floatArrayOf(0.1f, 0.075f, 0.06f, 0.08f)

/** Which mesh of a scene a buffer pair is. */
private enum class CosmosPart { Main, Strokes, Dust, Ring, Trail }

private class SceneMeshes(val parts: Map<CosmosPart, GpuMesh>)

private fun buildScene(scene: CosmosScene): Map<CosmosPart, StagedMesh> = when (scene) {
    // The stars come in layers (see galaxyLayers); only the dust lanes are a scene part.
    CosmosScene.Galaxy -> mapOf(CosmosPart.Dust to CosmosMeshes.galaxyDust().staged())
    CosmosScene.Star -> mapOf(
        CosmosPart.Main to CosmosMeshes.starHalo().staged(),
        CosmosPart.Strokes to CosmosMeshes.prominences().staged(),
        CosmosPart.Ring to CosmosSystem.ring().staged(),
        CosmosPart.Trail to CosmosSystem.orbitTrail().staged(),
    )
    CosmosScene.Burst -> mapOf(
        CosmosPart.Main to CosmosMeshes.burstCore().staged(),
        CosmosPart.Strokes to CosmosMeshes.burst().staged(),
        CosmosPart.Dust to CosmosMeshes.burstSparks().staged(),
    )
    CosmosScene.Flow -> mapOf(
        CosmosPart.Main to CosmosMeshes.flowBackdrop().staged(),
        CosmosPart.Strokes to CosmosMeshes.flowField().staged(),
        CosmosPart.Dust to CosmosMeshes.flowDust().staged(),
    )
}

/** A mesh copied into direct buffers, ready for Filament — built off the main thread. */
private class StagedMesh(val mesh: GlowMesh, val vertices: ByteBuffer, val indices: ByteBuffer)

private fun GlowMesh.staged(): StagedMesh {
    val v = ByteBuffer.allocateDirect(vertices.size * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
    v.asFloatBuffer().put(vertices)
    val i = ByteBuffer.allocateDirect(indices.size * Int.SIZE_BYTES).order(ByteOrder.nativeOrder())
    i.asIntBuffer().put(indices)
    return StagedMesh(this, v, i)
}

private class GpuMesh(val vertexBuffer: VertexBuffer, val indexBuffer: IndexBuffer, val box: FilamentBox) {
    fun destroy(engine: Engine) {
        engine.safeDestroyVertexBuffer(vertexBuffer)
        engine.safeDestroyIndexBuffer(indexBuffer)
    }
}

/** Main thread only: Filament buffer creation is a JNI call into the engine. */
private fun StagedMesh.upload(engine: Engine): GpuMesh {
    val strideBytes = mesh.stride * Float.SIZE_BYTES
    val builder = VertexBuffer.Builder()
        .bufferCount(1)
        .vertexCount(mesh.vertexCount)
        .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, strideBytes)
        .attribute(VertexBuffer.VertexAttribute.COLOR, 0, VertexBuffer.AttributeType.FLOAT4, 12, strideBytes)
        .attribute(VertexBuffer.VertexAttribute.CUSTOM0, 0, VertexBuffer.AttributeType.FLOAT4, 28, strideBytes)
    if (mesh.stride == RIBBON_STRIDE) {
        builder.attribute(VertexBuffer.VertexAttribute.CUSTOM1, 0, VertexBuffer.AttributeType.FLOAT4, 44, strideBytes)
    }
    val vertexBuffer = builder.build(engine)
    vertexBuffer.setBufferAt(engine, 0, vertices)
    val indexBuffer = IndexBuffer.Builder()
        .indexCount(mesh.indices.size)
        .bufferType(IndexBuffer.Builder.IndexType.UINT)
        .build(engine)
    indexBuffer.setBuffer(engine, indices)
    val min = mesh.boundsMin
    val max = mesh.boundsMax
    val box = FilamentBox(
        (min[0] + max[0]) / 2f, (min[1] + max[1]) / 2f, (min[2] + max[2]) / 2f,
        (max[0] - min[0]) / 2f, (max[1] - min[1]) / 2f, (max[2] - min[2]) / 2f,
    )
    return GpuMesh(vertexBuffer, indexBuffer, box)
}

/** Seconds since the current scene came on screen, advanced only while animating. */
private class CosmosClock {
    var sceneTime = 0f
        private set
    /** Whether the user (or the tour) has left the opening scene at least once. */
    var switched = false
        private set
    private var lastNanos = 0L
    private var scene: CosmosScene? = null

    fun advance(nanos: Long, current: CosmosScene, running: Boolean) {
        if (scene != current) {
            if (scene != null) switched = true
            scene = current
            sceneTime = 0f
        } else if (running && lastNanos != 0L) {
            // Clamp a long hitch (backgrounding, a GC) so the scene does not jump ahead.
            sceneTime += ((nanos - lastNanos) / 1e9f).coerceIn(0f, 0.1f)
        }
        lastNanos = nanos
    }

    /** The scene on screen starts over: the voyage plays its shot again from the top. */
    fun restart() {
        sceneTime = 0f
    }
}

/**
 * The glow's level, 0 → 1 over [IGNITE_SECONDS] from the first frame on screen, eased out
 * (cubic). Advanced by frame time, and a frame gap longer than [IGNITE_HITCH_SECONDS] counts
 * as a single frame: enabling bloom stalls the next frame while its programs link (~0.35 s on the
 * emulator), and that stall must not be spent from the ramp, or the glow lands half-lit.
 */
private class CosmosIgnition {
    var level = 0f
        private set
    private var progress = 0f
    private var lastNanos = 0L

    /** Advances the ramp; true while [level] changed on this frame, including the last step. */
    fun advance(nanos: Long, onScreen: Boolean, instant: Boolean): Boolean {
        if (progress >= 1f || !onScreen) return false
        progress = when {
            instant -> 1f
            lastNanos == 0L -> 0f
            else -> {
                // A hitch counts as one 60 Hz frame, so a device that is slow every frame still
                // gets to full glow.
                val gap = (nanos - lastNanos) / 1e9f
                val dt = if (gap in 0f..IGNITE_HITCH_SECONDS) gap else ONE_FRAME_SECONDS
                (progress + dt / IGNITE_SECONDS).coerceAtMost(1f)
            }
        }
        lastNanos = nanos
        val remaining = 1f - progress
        level = 1f - remaining * remaining * remaining
        return true
    }
}

private const val SPRITE_SLOTS = 8
private const val RIBBON_SLOTS = 5

private class SpriteInstances(all: List<MaterialInstance>) {
    val starField = all[0]
    val galaxy = all[1]
    val halo = all[2]
    val sparks = all[3]
    val flash = all[4]
    val dust = all[5]
    val backdrop = all[6]

    init {
        all.forEach { instance ->
            instance.setParameter("time", 0f)
            instance.setParameter("intensity", 1f)
            instance.setParameter("sizeScale", 1f)
            instance.setParameter("twinkle", 0f)
            instance.setParameter("minPixels", 1.1f)
        }
        starField.setParameter("twinkle", 0.3f)
        starField.setParameter("minPixels", 1.4f)
        galaxy.setParameter("twinkle", 0.12f)
        galaxy.setParameter("minPixels", 1.0f)
        sparks.setParameter("twinkle", 0.6f)
        sparks.setParameter("minPixels", 1.6f)
        dust.setParameter("twinkle", 0.7f)
        dust.setParameter("minPixels", 1.3f)
    }

    /** [glow] fades the star's halo and [sky] the star field: both 1 but on the way to Spacetime. */
    fun update(scene: CosmosScene, time: Float, reveal: Float, glow: Float = 1f, sky: Float = 1f) {
        starField.setParameter("time", time)
        starField.setParameter("intensity", reveal * sky * if (scene == CosmosScene.Flow) 0.35f else 1f)
        when (scene) {
            CosmosScene.Galaxy -> {
                galaxy.setParameter("time", time)
                galaxy.setParameter("intensity", reveal)
            }
            CosmosScene.Star -> {
                halo.setParameter("intensity", reveal * glow * (1f + 0.12f * sin(time * 2.1f)))
            }
            CosmosScene.Burst -> {
                val envelope = CosmosMeshes.burstEnvelope((time / BURST_PERIOD_SECONDS) % 1f)
                sparks.setParameter("time", time)
                sparks.setParameter("intensity", reveal * envelope[1] * envelope[0].coerceAtMost(1f))
                sparks.setParameter("sizeScale", 0.4f + 0.6f * envelope[0].coerceAtMost(1f))
                flash.setParameter("intensity", reveal * envelope[2])
            }
            CosmosScene.Flow -> {
                dust.setParameter("time", time)
                dust.setParameter("intensity", reveal)
                backdrop.setParameter("intensity", reveal)
            }
        }
    }
}

private class RibbonInstances(all: List<MaterialInstance>) {
    val burst = all[0]
    val flow = all[1]
    val prominences = all[2]
    val trail = all[3]
    val warp = all[4]

    init {
        all.forEach { instance ->
            instance.setParameter("time", 0f)
            instance.setParameter("intensity", 1f)
            instance.setParameter("widthScale", 1f)
            instance.setParameter("minPixels", 1.0f)
            instance.setParameter("head", 10f)
            instance.setParameter("headGlow", 0f)
            instance.setParameter("base", 1f)
            instance.setParameter("dashAmp", 0f)
            instance.setParameter("dashFreq", 0f)
            instance.setParameter("dashSpeed", 0f)
            instance.setParameter("fade", 1f)
            instance.setParameter("tailTaper", 0f)
        }
        burst.setParameter("headGlow", 3f)
        burst.setParameter("base", 0.75f)
        burst.setParameter("dashAmp", 0.5f)
        burst.setParameter("dashFreq", TWO_PI * 3f)
        burst.setParameter("dashSpeed", 7f)
        burst.setParameter("tailTaper", 0.35f)
        flow.setParameter("base", 0.5f)
        flow.setParameter("dashAmp", 1.3f)
        flow.setParameter("dashFreq", TWO_PI * 5f)
        flow.setParameter("dashSpeed", 2.5f)
        prominences.setParameter("base", 0.6f)
        prominences.setParameter("dashAmp", 1.6f)
        prominences.setParameter("dashFreq", TWO_PI * 3.5f)
        prominences.setParameter("dashSpeed", 1.6f)
        prominences.setParameter("tailTaper", 0.2f)
        // The orbit trail's fade is baked into its colours: a plain, steady stroke.
        trail.setParameter("minPixels", 1.2f)
        // The jump's streaks: faint bodies under fast pulses running at the lens.
        warp.setParameter("base", 0.25f)
        warp.setParameter("dashAmp", 2.6f)
        warp.setParameter("dashFreq", TWO_PI * 0.18f)
        warp.setParameter("dashSpeed", 22f)
        warp.setParameter("minPixels", 1.1f)
        warp.setParameter("intensity", 0f)
    }

    fun update(scene: CosmosScene, time: Float, reveal: Float) {
        when (scene) {
            CosmosScene.Burst -> {
                val envelope = CosmosMeshes.burstEnvelope((time / BURST_PERIOD_SECONDS) % 1f)
                burst.setParameter("time", time)
                burst.setParameter("head", envelope[0])
                burst.setParameter("fade", envelope[1])
                burst.setParameter("intensity", reveal)
            }
            CosmosScene.Flow -> {
                flow.setParameter("time", time)
                flow.setParameter("intensity", reveal)
            }
            CosmosScene.Star -> {
                prominences.setParameter("time", time)
                prominences.setParameter("intensity", reveal)
            }
            CosmosScene.Galaxy -> Unit
        }
    }
}

/** Surface colours of a plasma instance: deep, hot, rim (linear rgb), rim power, scale, flow. */
private class PlasmaLook(
    val deep: FloatArray,
    val hot: FloatArray,
    val rim: FloatArray,
    val rimPower: Float,
    val noiseScale: Float,
    val flow: Float,
)

private val STAR_PLASMA = PlasmaLook(
    deep = floatArrayOf(0.09f, 0.42f, 0.95f),
    hot = floatArrayOf(0.26f, 0.8f, 1.7f),
    rim = floatArrayOf(0.3f, 0.9f, 2.2f),
    rimPower = 3f,
    noiseScale = 9f,
    flow = 0.22f,
)

private val NUCLEUS_PLASMA = PlasmaLook(
    deep = floatArrayOf(0.004f, 0.008f, 0.02f),
    hot = floatArrayOf(0.03f, 0.07f, 0.16f),
    rim = floatArrayOf(0.25f, 0.6f, 1.6f),
    rimPower = 3f,
    noiseScale = 4f,
    flow = 0.2f,
)

private fun MaterialInstance.setPlasma(look: PlasmaLook, gain: Float = 1f) {
    setParameter("time", 0f)
    setParameter("deepColor", look.deep[0] * gain, look.deep[1] * gain, look.deep[2] * gain)
    setParameter("hotColor", look.hot[0] * gain, look.hot[1] * gain, look.hot[2] * gain)
    setParameter("rimColor", look.rim[0] * gain, look.rim[1] * gain, look.rim[2] * gain)
    setParameter("rimPower", look.rimPower)
    setParameter("noiseScale", look.noiseScale)
    setParameter("flow", look.flow)
}

private class PlasmaInstances(val star: MaterialInstance, val nucleus: MaterialInstance) {
    fun update(scene: CosmosScene, time: Float, reveal: Float) {
        when (scene) {
            CosmosScene.Star -> {
                star.setPlasma(STAR_PLASMA, gain = reveal * (1f + 0.08f * sin(time * 2.1f)))
                star.setParameter("time", time)
            }
            CosmosScene.Flow -> nucleus.setParameter("time", time)
            else -> Unit
        }
    }
}

private const val TWO_PI = (2.0 * PI).toFloat()

private fun FloatArray.toQuaternion() = Quaternion(this[0], this[1], this[2], this[3])

/** The ringed world's two surfaces: the planet and its rings, both lit by the star at the origin. */
private class WorldInstances(val planet: MaterialInstance, val ring: MaterialInstance) {
    init {
        planet.setParameter("time", 0f)
        planet.setParameter("sunPosition", 0f, 0f, 0f)
        planet.setParameter("sunColor", SUN_COLOR[0], SUN_COLOR[1], SUN_COLOR[2])
        planet.setParameter("bandLight", 0.92f, 0.76f, 0.54f)
        planet.setParameter("bandDark", 0.52f, 0.32f, 0.18f)
        planet.setParameter("atmosphere", 0.22f, 0.5f, 1.0f)
        planet.setParameter("ringInner", CosmosSystem.RING_INNER)
        planet.setParameter("ringOuter", CosmosSystem.RING_OUTER)
        planet.setParameter("intensity", 1f)
        ring.setParameter("sunPosition", 0f, 0f, 0f)
        ring.setParameter("sunColor", SUN_COLOR[0], SUN_COLOR[1], SUN_COLOR[2])
        ring.setParameter("ringColor", 0.8f, 0.66f, 0.48f)
        ring.setParameter("planetRadius", CosmosSystem.PLANET_RADIUS)
        ring.setParameter("ringInner", CosmosSystem.RING_INNER)
        ring.setParameter("ringOuter", CosmosSystem.RING_OUTER)
        ring.setParameter("intensity", 1f)
    }

    fun update(time: Float, reveal: Float) {
        planet.setParameter("time", time)
        planet.setParameter("intensity", reveal)
        ring.setParameter("intensity", reveal)
    }

    /** Lights the world from [sun] (Spacetime's key light, far off), or from the star when null. */
    fun light(sun: FloatArray?) {
        val x = sun?.get(0) ?: 0f
        val y = sun?.get(1) ?: 0f
        val z = sun?.get(2) ?: 0f
        planet.setParameter("sunPosition", x, y, z)
        ring.setParameter("sunPosition", x, y, z)
    }
}

/** The blue star's light as it reaches the planet, linear. */
private val SUN_COLOR = floatArrayOf(0.95f, 1.15f, 1.55f)

/** The worlds Spacetime adds round the star, in [CosmosSpacetime]'s body order. */
private val FABRIC_WORLDS = intArrayOf(
    CosmosSpacetime.EMBER,
    CosmosSpacetime.AZURE,
    CosmosSpacetime.OCHRE,
    CosmosSpacetime.ICE,
    CosmosSpacetime.MOON_O,
    CosmosSpacetime.MOON_I,
)

/** Pale band, dark belt and atmosphere rim of each of [FABRIC_WORLDS], linear rgb. */
private val FABRIC_LOOKS = arrayOf(
    floatArrayOf(0.85f, 0.42f, 0.22f, 0.45f, 0.16f, 0.08f, 0.5f, 0.2f, 0.08f),
    floatArrayOf(0.35f, 0.6f, 0.95f, 0.12f, 0.28f, 0.6f, 0.2f, 0.45f, 1.0f),
    floatArrayOf(0.85f, 0.65f, 0.35f, 0.5f, 0.33f, 0.14f, 0.4f, 0.3f, 0.15f),
    floatArrayOf(0.8f, 0.9f, 0.95f, 0.5f, 0.65f, 0.75f, 0.3f, 0.5f, 0.7f),
    floatArrayOf(0.6f, 0.58f, 0.55f, 0.35f, 0.33f, 0.31f, 0.05f, 0.05f, 0.05f),
    floatArrayOf(0.6f, 0.58f, 0.55f, 0.35f, 0.33f, 0.31f, 0.05f, 0.05f, 0.05f),
)

/**
 * A ring-shadow band no ringless world has: `cosmos_planet.mat` divides by outer − inner, so the
 * band is put far outside the sphere rather than zeroed.
 */
private const val NO_RING_INNER = 10f
private const val NO_RING_OUTER = 11f

/** Spacetime's materials: the sheet and one planet instance per world of [FABRIC_WORLDS]. */
private class FabricInstances(val sheet: MaterialInstance, val worlds: List<MaterialInstance>) {
    init {
        val base = CosmosSpacetime.sheetBaseLinear()
        val light = CosmosSpacetime.LIGHT
        sheet.setParameter("lightDir", light[0], light[1], light[2])
        sheet.setParameter("baseColor", base[0], base[1], base[2])
        sheet.setParameter("intensity", 0f)
        worlds.forEachIndexed { slot, world ->
            val look = FABRIC_LOOKS[slot]
            world.setParameter("time", 0f)
            world.setParameter("sunPosition", 0f, 0f, 0f)
            world.setParameter("sunColor", SUN_COLOR[0], SUN_COLOR[1], SUN_COLOR[2])
            world.setParameter("bandLight", look[0], look[1], look[2])
            world.setParameter("bandDark", look[3], look[4], look[5])
            world.setParameter("atmosphere", look[6], look[7], look[8])
            world.setParameter("ringInner", NO_RING_INNER)
            world.setParameter("ringOuter", NO_RING_OUTER)
            world.setParameter("intensity", 0f)
        }
    }
}

/**
 * One Spacetime frame, without allocating: lays the sheet out, rests every world on it, and
 * writes the sheet's uniforms and the worlds' light. The render loop holds one.
 */
private class FabricFrame {
    /** World positions (x, y, z) per body, in [CosmosSpacetime]'s order. */
    val positions = FloatArray(CosmosSpacetime.BODY_COUNT * 3)

    /** The ringed world's centre, orientation and "sun" for this frame. */
    val ringed = FloatArray(3)
    val ringedSpin = FloatArray(4)
    val ringedSun = FloatArray(3)

    /** Whether the halo is depth-culled (Starlight) or not (on the way to Spacetime). */
    var haloCulled = true

    /** Whether the worlds are lit by Spacetime's key light. */
    var lit = false

    private val spheres = FloatArray(CosmosSpacetime.BODY_COUNT * 4)
    private val flatSpin = FloatArray(4)
    private val sun = FloatArray(3)

    @Suppress("LongParameterList")
    fun place(
        field: SpacetimeField,
        fabric: FabricInstances,
        time: Float,
        sequence: Float,
        orbit: FloatArray,
        orbitSpin: FloatArray,
        reveal: Float,
    ) {
        lit = true
        val flown = CosmosSpacetime.flight(sequence)
        val well = CosmosSpacetime.wellDepth(sequence)
        val lift = CosmosSpacetime.LIFT * (1f - flown)
        field.prepare(time, well)
        val light = CosmosSpacetime.LIGHT
        for (body in 0 until CosmosSpacetime.BODY_COUNT) {
            val y = if (body == CosmosSpacetime.STAR) 0f else field.rest(body) - lift
            positions[body * 3] = field.x(body)
            positions[body * 3 + 1] = y + CosmosSpacetime.fallHeight(body, sequence)
            positions[body * 3 + 2] = field.z(body)
        }
        // The ringed world leaves its tipped orbit for its rest on the sheet as the plane lays down.
        val r = CosmosSpacetime.RINGED * 3
        for (i in 0..2) ringed[i] = orbit[i] + (positions[r + i] - orbit[i]) * flown
        // field.rest(RINGED) above left the ring plane's normal in field.ringNormal.
        CosmosSpacetime.ringedRotation(field.ringNormal, time, flatSpin)
        CosmosSpacetime.slerp(orbitSpin, flatSpin, flown, ringedSpin)
        // Its light turns from the star at the origin to the key light: a point far off along
        // the blend of the two directions.
        val d = sqrt(ringed[0] * ringed[0] + ringed[1] * ringed[1] + ringed[2] * ringed[2]).coerceAtLeast(1e-4f)
        for (i in 0..2) sun[i] = -ringed[i] / d + (light[i] + ringed[i] / d) * flown
        val m = sqrt(sun[0] * sun[0] + sun[1] * sun[1] + sun[2] * sun[2]).coerceAtLeast(1e-4f)
        for (i in 0..2) ringedSun[i] = ringed[i] + CosmosSpacetime.SUN_DISTANCE * sun[i] / m
        for (i in 0..2) positions[r + i] = ringed[i]
        // Shadow casters: every world but the star, shrunk by its arrival.
        for (body in 0 until CosmosSpacetime.BODY_COUNT) {
            val arrived = if (body == CosmosSpacetime.STAR) 0f else CosmosSpacetime.arrival(body, sequence)
            spheres[body * 4] = positions[body * 3]
            spheres[body * 4 + 1] = positions[body * 3 + 1]
            spheres[body * 4 + 2] = positions[body * 3 + 2]
            spheres[body * 4 + 3] = CosmosSpacetime.RADIUS[body] * arrived
        }
        val sheet = fabric.sheet
        sheet.setParameter("bodies", MaterialInstance.FloatElement.FLOAT4, field.bodies, 0, CosmosSpacetime.BODY_COUNT)
        sheet.setParameter("spheres", MaterialInstance.FloatElement.FLOAT4, spheres, 0, CosmosSpacetime.BODY_COUNT)
        sheet.setParameter("ringCenter", ringed[0], ringed[1], ringed[2], CosmosSystem.RING_INNER)
        val n = field.ringNormal
        sheet.setParameter("ringNormal", n[0], n[1], n[2], CosmosSystem.RING_OUTER)
        sheet.setParameter("offset", field.offset - lift)
        sheet.setParameter("well", well)
        sheet.setParameter("intensity", CosmosSpacetime.sheetIntensity(sequence) * reveal)
        // The other worlds are lit by the key light alone.
        for (slot in fabric.worlds.indices) {
            val world = fabric.worlds[slot]
            val body = FABRIC_WORLDS[slot]
            val o = body * 3
            world.setParameter(
                "sunPosition",
                positions[o] + CosmosSpacetime.SUN_DISTANCE * light[0],
                positions[o + 1] + CosmosSpacetime.SUN_DISTANCE * light[1],
                positions[o + 2] + CosmosSpacetime.SUN_DISTANCE * light[2],
            )
            world.setParameter("time", time)
            world.setParameter("intensity", CosmosSpacetime.arrival(body, sequence) * reveal)
        }
    }
}

/**
 * The sheet's grid and horizon map copied into direct buffers, ready for Filament — built off
 * the main thread.
 */
private class StagedSheet(val vertices: ByteBuffer, val indices: ByteBuffer, val horizon: ByteBuffer)

private suspend fun CosmosSpacetime.Grid.stagedSheet(): StagedSheet = coroutineScope {
    val v = ByteBuffer.allocateDirect(vertices.size * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
    v.asFloatBuffer().put(vertices)
    val i = ByteBuffer.allocateDirect(indices.size * Short.SIZE_BYTES).order(ByteOrder.nativeOrder())
    i.asShortBuffer().put(indices)
    // The horizon map is the long part (a march per texel): one band of rows per core.
    val n = CosmosSpacetime.HORIZON_MAP_SIZE
    val map = ByteArray(n * n)
    val bands = Runtime.getRuntime().availableProcessors().coerceIn(1, MAX_BAKE_BANDS)
    (0 until bands).map { band ->
        async { CosmosSpacetime.horizonRows(map, band * n / bands, (band + 1) * n / bands) }
    }.awaitAll()
    val h = ByteBuffer.allocateDirect(map.size).order(ByteOrder.nativeOrder())
    h.put(map).flip()
    StagedSheet(v, i, h)
}

/** At most this many cores bake the horizon map at once. */
private const val MAX_BAKE_BANDS = 8

/** Spacetime's sheet on the GPU: its grid and the horizon map its material samples. */
private class GpuSheet(val mesh: GpuMesh, val horizon: Texture) {
    fun destroy(engine: Engine) {
        mesh.destroy(engine)
        engine.safeDestroyTexture(horizon)
    }
}

/**
 * Binds the horizon map: bilinear, clamped (past the map's edge the light is clear, as on its
 * border texels). The sampler is made here, not in a top-level val: it is a JNI call, and this
 * file's statics load before Filament's native library.
 */
private fun MaterialInstance.bindHorizon(horizon: Texture) = setParameter(
    "horizon",
    horizon,
    TextureSampler(
        TextureSampler.MinFilter.LINEAR,
        TextureSampler.MagFilter.LINEAR,
        TextureSampler.WrapMode.CLAMP_TO_EDGE,
    ),
)

/**
 * Main thread only. Position (x, 0, z) per vertex, 16-bit indices (39 520 vertices); the
 * horizon map as one R8 texture. The box is static: y spans every well depth and lift the entry
 * passes through.
 */
private fun StagedSheet.uploadSheet(engine: Engine): GpuSheet {
    val strideBytes = CosmosSpacetime.GRID_STRIDE * Float.SIZE_BYTES
    val vertexBuffer = VertexBuffer.Builder()
        .bufferCount(1)
        .vertexCount(CosmosSpacetime.GRID_VERTICES)
        .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, strideBytes)
        .build(engine)
    vertexBuffer.setBufferAt(engine, 0, vertices)
    val indexBuffer = IndexBuffer.Builder()
        .indexCount(CosmosSpacetime.GRID_TRIANGLES * 3)
        .bufferType(IndexBuffer.Builder.IndexType.USHORT)
        .build(engine)
    indexBuffer.setBuffer(engine, indices)
    val r = CosmosSpacetime.SHEET_RADIUS
    val box = FilamentBox(
        0f, (CosmosSpacetime.BOX_MIN_Y + CosmosSpacetime.BOX_MAX_Y) / 2f, 0f,
        r, (CosmosSpacetime.BOX_MAX_Y - CosmosSpacetime.BOX_MIN_Y) / 2f, r,
    )
    val n = CosmosSpacetime.HORIZON_MAP_SIZE
    val texture = Texture.Builder()
        .width(n)
        .height(n)
        .levels(1)
        .sampler(Texture.Sampler.SAMPLER_2D)
        .format(Texture.InternalFormat.R8)
        .build(engine)
    texture.setImage(engine, 0, Texture.PixelBufferDescriptor(horizon, Texture.Format.R, Texture.Type.UBYTE, 1))
    return GpuSheet(GpuMesh(vertexBuffer, indexBuffer, box), texture)
}

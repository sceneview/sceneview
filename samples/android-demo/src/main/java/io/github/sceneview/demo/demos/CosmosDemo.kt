package io.github.sceneview.demo.demos

import io.github.sceneview.demo.telemetry.LocalSampleId
import io.github.sceneview.demo.telemetry.logSampleInteraction
import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cyclone
import androidx.compose.material.icons.filled.Flare
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import io.github.sceneview.demo.demos.internal.CosmosSystem
import io.github.sceneview.demo.demos.internal.CosmosVoyage
import io.github.sceneview.demo.demos.internal.CosmosVoyageCamera
import io.github.sceneview.demo.demos.internal.GlowMesh
import io.github.sceneview.demo.demos.internal.RIBBON_STRIDE
import io.github.sceneview.demo.demos.internal.VoyageExit
import io.github.sceneview.demo.demos.internal.VoyageState
import io.github.sceneview.demo.demos.internal.orbitPose
import io.github.sceneview.demo.rememberFirstFrameState
import io.github.sceneview.demo.theme.LocalMotionEnabled
import io.github.sceneview.demo.theme.SceneViewTokens
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
import io.github.sceneview.safeDestroyVertexBuffer
import io.github.sceneview.sample.ui.LabeledSlider
import kotlinx.coroutines.Dispatchers
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

    var scene by remember { mutableStateOf(CosmosScene.Galaxy) }
    // The voyage is on unless the user stopped it; `voyage` holds where it is in the render loop.
    var voyageOn by remember { mutableStateOf(!DemoSettings.qaMode) }
    val voyage = remember { VoyageState(playing = !DemoSettings.qaMode) }
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

    val telemetrySampleId = LocalSampleId.current
    DemoScaffold(
        title = stringResource(R.string.demo_cosmos_title),
        onBack = onBack,
        firstFrameRendered = firstFrame.rendered,
        loadingLabel = stringResource(R.string.demo_cosmos_loading),
        peekHeader = voyageCaption ?: if (scene == CosmosScene.Star) focus.caption else scene.caption,
        onResetSettings = {
            voyageOn = true
            voyage.resumeNow()
            animating = true
            bloom = DEFAULT_BLOOM
        },
        dock = CosmosScene.entries.map { target ->
            sceneDockItem(target, scene) {
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
                voyageOn = true
                animating = true
                voyage.resumeNow()
            })
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
                onDown = { _, _ -> voyage.takeOver(flight) },
                onScroll = { _, _, _, distance ->
                    voyage.takeOver(flight)
                    voyage.drag(scene, distance.x * dragDegreesPerPx, -distance.y * dragDegreesPerPx)
                },
                onSingleTapConfirmed = { event, _ ->
                if (scene == CosmosScene.Star) {
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
                val time = if (frozen) QA_TIME[current.ordinal] else clock.sceneTime
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
                val pose: FloatArray
                val focal: Float
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
                    pose = voyageCamera.pose
                    focal = voyageCamera.focal
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
                        pose = voyageCamera.pose
                        focal = voyageCamera.focal
                        voyage.show(voyageCamera.fade, voyageCamera.streaks)
                        if (voyage.leaving >= 1f) {
                            // A scene picked in the dock gets its own shot before the voyage moves on.
                            landing = if (voyage.inPlace) current else CosmosVoyage.next(current)
                            if (landing == current) clock.restart() else scene = landing
                            voyage.arrive()
                        }
                    } else {
                        pose = free
                        focal = freeFocal
                        voyage.settle(step)
                    }
                    caption = landing?.let(CosmosVoyage::openingCaption)
                }
                if (voyageCaption != caption) voyageCaption = caption
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
                starNode[0]?.scale = Scale(1f + 0.012f * sin(time * 2.1f))
                sprites?.update(current, time, reveal)
                ribbons?.update(current, time, reveal)
                plasma?.update(current, time, reveal)
                if (current == CosmosScene.Star) {
                    orbitNode[0]?.quaternion = rig.trailRotation(time, aspect).toQuaternion()
                    planetNode[0]?.apply {
                        val at = rig.planetPosition(time, aspect)
                        position = Position(at[0], at[1], at[2])
                        quaternion = rig.planetRotation(time, aspect).toQuaternion()
                    }
                    world?.update(time, reveal)
                    // The trail fades out as the camera closes on the planet: from the follow view
                    // the arc behind it runs past the lens.
                    val trail = rig.trailVisibility(pose[0], pose[1], pose[2], time, aspect)
                    ribbons?.trail?.setParameter("intensity", reveal * trail)
                }
                if (ignition.advance(nanos, firstFrame.rendered.value, instant = frozen)) {
                    view.bloomOptions = view.bloomOptions.also { it.strength = bloom * ignition.level }
                }
                if (current == CosmosScene.Galaxy) dust?.setParameter("opacity", reveal * ignition.level)
            },
        ) {
            val sceneMeshes = meshes[scene]
            stars?.let { mesh -> sprites?.let { GlowMeshNode(mesh, it.starField) } }
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
                            GlowMeshNode(sceneMeshes.parts.getValue(CosmosPart.Strokes), ribbons.prominences)
                        }
                        GlowMeshNode(sceneMeshes.parts.getValue(CosmosPart.Main), sprites.halo)
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
private fun io.github.sceneview.SceneScope.GlowMeshNode(mesh: GpuMesh, material: MaterialInstance) {
    MeshNode(
        primitiveType = RenderableManager.PrimitiveType.TRIANGLES,
        vertexBuffer = mesh.vertexBuffer,
        indexBuffer = mesh.indexBuffer,
        boundingBox = mesh.box,
        materialInstance = material,
        apply = { configureGlow() },
    )
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

    fun update(scene: CosmosScene, time: Float, reveal: Float) {
        starField.setParameter("time", time)
        starField.setParameter("intensity", reveal * if (scene == CosmosScene.Flow) 0.35f else 1f)
        when (scene) {
            CosmosScene.Galaxy -> {
                galaxy.setParameter("time", time)
                galaxy.setParameter("intensity", reveal)
            }
            CosmosScene.Star -> {
                halo.setParameter("intensity", reveal * (1f + 0.12f * sin(time * 2.1f)))
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
}

/** The blue star's light as it reaches the planet, linear. */
private val SUN_COLOR = floatArrayOf(0.95f, 1.15f, 1.55f)

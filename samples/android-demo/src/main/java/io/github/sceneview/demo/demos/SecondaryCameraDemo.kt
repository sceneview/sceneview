package io.github.sceneview.demo.demos

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import io.github.sceneview.demo.common.DemoStatusBanner
import io.github.sceneview.demo.common.DemoStatusTone
import io.github.sceneview.demo.theme.SceneViewTokens
import android.util.Log
import androidx.annotation.StringRes
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.google.android.filament.MaterialInstance
import com.google.android.filament.View
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Ray
import dev.romainguy.kotlin.math.dot
import dev.romainguy.kotlin.math.length
import dev.romainguy.kotlin.math.normalize
import io.github.sceneview.SceneScope
import io.github.sceneview.SceneView
import io.github.sceneview.SurfaceType
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.R
import io.github.sceneview.demo.common.StageSkyFog
import io.github.sceneview.demo.common.rememberModelDemoEnvironment
import io.github.sceneview.demo.common.rememberStageSkybox
import io.github.sceneview.demo.common.themedStageSky
import io.github.sceneview.demo.rememberFirstFrameState
import io.github.sceneview.demo.rememberFitOrbitRadius
import io.github.sceneview.rememberCameraManipulator
import io.github.sceneview.loaders.ModelLoader
import io.github.sceneview.material.setColor
import io.github.sceneview.math.Direction
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Size
import io.github.sceneview.model.ModelInstance
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberOnGestureListener
import io.github.sceneview.rememberRenderInvalidator
import io.github.sceneview.rememberView
import io.github.sceneview.utils.screenToRay
import io.github.sceneview.utils.readBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private const val HELMET_ASSET = "models/khronos_damaged_helmet.glb"

/** The helmet's largest side, in metres; it stands on the floor. */
private const val HELMET_SIZE = 0.5f

/** How far from the centre a tap can walk the helmet: both cameras keep the whole stage in frame. */
private const val STAGE_HALF_EXTENT = 0.35f

/** A tap whose ray passes this close to the helmet's centre turns it rather than moving it. */
private const val HELMET_PICK_RADIUS = 0.2f

/** How long an edit takes to play out, in both views at once. */
private const val EDIT_GLIDE_MILLIS = 450

/** The point both cameras aim at: the stage's centre, a little above the floor. */
private val STAGE_CENTRE = Position(0f, 0.2f, 0f)

private const val TAG = "SecondaryCameraDemo"

/** Look-down of the main camera's home shot. */
private const val MAIN_ELEVATION_DEGREES = 24f

// Orbit preset tuning. The camera circles the stage at a slight elevation; one
// full sweep takes ORBIT_PERIOD_NANOS.
private const val ORBIT_RADIUS = 1.45f
private const val ORBIT_HEIGHT = 0.75f
private const val ORBIT_PERIOD_NANOS = 12_000_000_000L

/** Floor and grid: 25 cm squares, 4 either side — a 2 m square around the stage. */
private const val FLOOR_SIZE = 90f
private const val GRID_SPACING = 0.25f
private const val GRID_HALF_LINES = 4
private const val GRID_LINE_WIDTH = 0.008f
private const val GRID_LINE_HEIGHT = 0.002f

/**
 * Secondary camera (picture-in-picture) demo.
 *
 * Two [SceneView]s share the same engine/loaders and render the helmet
 * simultaneously:
 *  - the main view uses the default orbital camera (user-interactive),
 *  - the small PiP overlay binds a dedicated [rememberCameraNode] and is
 *    repositioned by [LaunchedEffect] when the user picks a chip.
 *
 * The chips drive the PiP camera in two distinct ways, both proving the
 * per-instance `cameraNode` binding is genuinely independent of the main view:
 *
 *  - **Fixed angles** (Top / Side / Front / Corner) park the PiP at a static
 *    eye position, so the inset shows the helmet from an angle the user has
 *    not orbited the main view to.
 *  - **Orbit** runs an [LaunchedEffect] frame loop that continuously sweeps
 *    `pipCameraNode.position` around the model. The PiP keeps flying around
 *    the helmet on its own *while the user drags the main view* — the most
 *    direct demonstration of why you would reach for a second `cameraNode`:
 *    one scene, two cameras moving fully independently.
 *
 * Key correctness invariants — both ship-blockers if missed:
 *
 *  1. Each view gets its OWN [ModelInstance]. [ModelNode]'s wrapper entity is
 *     `modelInstance.root`, so a single instance attached to two scenes would
 *     be destroyed twice on dispose (SIGABRT) and its child light / camera
 *     nodes reparented to whichever ModelNode was built last. We get two
 *     distinct instances from one `createInstancedModel(count = 2)` call —
 *     the GLB is parsed ONCE and the two instances share the asset's mesh /
 *     material / texture GPU resources, while each carries its own root
 *     entity hierarchy, so the two ModelNodes destroy distinct roots and
 *     never double-free. (See [rememberInstancedHelmet].)
 *
 *  2. The PiP SceneView passes `cameraManipulator = null`. Without it, the
 *     SceneView frame loop (`SceneView.kt`) writes
 *     `cameraNode.transform = manipulator.getTransform()` every frame,
 *     clobbering whatever the LaunchedEffect just set on `pipCameraNode`.
 *     The chips would fire and the PiP would visually freeze at the
 *     manipulator's home position.
 *
 * Both views draw one shared scene state (#4083): the helmet's position on the
 * floor and its heading. A tap in either view — on the floor to walk the helmet
 * there, on the helmet to turn it — writes that state, so an edit made through
 * one camera is visibly applied in the other. Each tap is resolved against the
 * tapped view's own camera (`View.screenToRay`), which is the other half of the
 * lesson: picking works per view, whichever camera the user is looking through.
 *
 * Both views also share one environment (studio IBL + themed stage sky), so it is
 * built once rather than once per SceneView; the stage fog is per view.
 *
 * The PiP uses [SurfaceType.TextureSurface] so it composites correctly over
 * the main [SurfaceType.Surface] view. It rides [DemoScaffold]'s `topOverlay`
 * slot, aligned to the start edge, so the scaffold owns its gutter and inset
 * and it stays clear of the top-end asset-source chip and the bottom-end
 * settings FAB. `cameraPreset` is [rememberSaveable] so configuration
 * changes (rotation, dark-mode toggle) preserve the user's selection.
 */
@Composable
fun SecondaryCameraDemo(onBack: () -> Unit) {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)

    // Both views stand the helmet on the same floor under a themed stage sky (#4083): the
    // neutral grey void this replaced gave neither camera a ground to read the other's edit
    // against. The skybox and IBL are shared; the fog is per view, because it lives on the view.
    val mainView = rememberView(engine)
    val pipView = rememberView(engine)
    val renderInvalidator = rememberRenderInvalidator()
    val pipRenderInvalidator = rememberRenderInvalidator()
    val sky = themedStageSky()
    val stageSkybox = rememberStageSkybox(engine, sky, renderInvalidator::requestRender)
    StageSkyFog(mainView, sky, renderInvalidator::requestRender)
    StageSkyFog(pipView, sky, pipRenderInvalidator::requestRender)
    val firstFrame = rememberFirstFrameState(engine)
    val baseEnvironment = rememberModelDemoEnvironment(environmentLoader, firstFrame)
    val environment = remember(baseEnvironment, stageSkybox) {
        baseEnvironment.copy(skybox = stageSkybox)
    }
    val gridColor = sky.grid
    val floorMaterial = remember(materialLoader) {
        materialLoader.createColorInstance(sky.floor, metallic = 0f, roughness = 0.7f)
    }
    val gridMaterial = remember(materialLoader) {
        materialLoader.createColorInstance(gridColor, metallic = 0f, roughness = 0.8f)
    }
    LaunchedEffect(floorMaterial, gridMaterial, sky.floor, gridColor) {
        floorMaterial.setColor(sky.floor)
        gridMaterial.setColor(gridColor)
        renderInvalidator.requestRender()
        pipRenderInvalidator.requestRender()
    }

    // One GLB parse, two resource-sharing instances — see invariant #1.
    val helmet = rememberInstancedHelmet(modelLoader, count = 2)
    val instances = helmet?.getOrNull().orEmpty()
    val mainInstance = instances.getOrNull(0)
    val pipInstance = instances.getOrNull(1)

    var cameraPreset by rememberSaveable { mutableStateOf(CameraPreset.CORNER) }

    // The one scene state both views draw (#4083): where the helmet stands and which way it
    // faces. A tap in either view writes it and both views render it, so an edit made through
    // one camera shows up in the other at once — one scene, two cameras, not two copies.
    var helmetX by rememberSaveable { mutableFloatStateOf(0f) }
    var helmetZ by rememberSaveable { mutableFloatStateOf(0f) }
    var helmetYaw by rememberSaveable { mutableFloatStateOf(0f) }
    var lastEdit by rememberSaveable { mutableStateOf<EditSource?>(null) }
    val animatedX by animateFloatAsState(helmetX, tween(EDIT_GLIDE_MILLIS), label = "helmetX")
    val animatedZ by animateFloatAsState(helmetZ, tween(EDIT_GLIDE_MILLIS), label = "helmetZ")
    val animatedYaw by animateFloatAsState(helmetYaw, tween(EDIT_GLIDE_MILLIS), label = "helmetYaw")
    val helmetPosition = Position(animatedX, 0f, animatedZ)
    val helmetRotation = Rotation(y = animatedYaw)

    // A tap on the helmet turns it a quarter turn; a tap on the floor walks it there, kept on
    // the stage so neither camera loses it. Resolved against the tapped view's own camera.
    fun edit(view: View, xPx: Float, yPx: Float, source: EditSource) {
        val ray = view.screenToRay(xPx, yPx) ?: return
        val centre = Float3(helmetX, HELMET_SIZE / 2f, helmetZ)
        if (rayPassesWithin(ray, centre, HELMET_PICK_RADIUS)) {
            helmetYaw += 90f
        } else {
            val floorHit = rayHitsFloor(ray) ?: return
            helmetX = floorHit.x.coerceIn(-STAGE_HALF_EXTENT, STAGE_HALF_EXTENT)
            helmetZ = floorHit.z.coerceIn(-STAGE_HALF_EXTENT, STAGE_HALF_EXTENT)
        }
        lastEdit = source
    }

    val pipCameraNode = rememberCameraNode(engine)
    // Positions the PiP camera. A fixed preset (Top / Side / Front / Corner)
    // parks the camera once; the Orbit preset runs a frame loop that keeps
    // sweeping the camera around the stage — independently of the user's
    // main-view orbit, which is the whole point of a dedicated `cameraNode`.
    LaunchedEffect(cameraPreset) {
        cameraPreset.eye?.let { eye ->
            pipCameraNode.position = eye
            pipCameraNode.lookAt(STAGE_CENTRE)
            pipRenderInvalidator.requestRender()
            return@LaunchedEffect
        }
        // Orbit: one full sweep every ORBIT_PERIOD_NANOS, driven by the display
        // clock so the speed is frame-rate independent (matches OrbitalARDemo).
        var startNanos = 0L
        while (true) {
            withFrameNanos { nanos ->
                if (startNanos == 0L) startNanos = nanos
                val phase = (nanos - startNanos) % ORBIT_PERIOD_NANOS
                val angle = phase.toFloat() / ORBIT_PERIOD_NANOS * (2f * PI.toFloat())
                pipCameraNode.position = Position(
                    x = sin(angle) * ORBIT_RADIUS,
                    y = ORBIT_HEIGHT,
                    z = cos(angle) * ORBIT_RADIUS,
                )
                pipCameraNode.lookAt(STAGE_CENTRE)
                pipRenderInvalidator.requestRender()
            }
        }
    }

    // "Scene ready" is two views of one helmet (#4459): both instances and their textures, and
    // the inset having presented a frame that carries its own.
    var insetShowsHelmet by remember { mutableStateOf(false) }
    firstFrame.holdUntilModels(modelLoader, instancesLoaded = instances.size == 2)
    firstFrame.holdUntil(landed = insetShowsHelmet, what = "inset view")
    LaunchedEffect(helmet, firstFrame) {
        if (helmet?.isFailure == true) firstFrame.reportContentFailed()
    }

    DemoScaffold(
        title = stringResource(R.string.demo_secondary_camera_title),
        onBack = onBack,
        firstFrameRendered = firstFrame.rendered,
        sceneReady = firstFrame.sceneReady,
        contentIssue = firstFrame.contentIssue,
        bottomOverlay = {
            // No helmet, nothing to move: the stage already says the load failed.
            if (helmet?.isFailure != true) {
                DemoStatusBanner(
                    text = when (lastEdit) {
                        EditSource.MAIN -> stringResource(R.string.demo_secondary_camera_status_main_edit)
                        EditSource.PIP -> stringResource(R.string.demo_secondary_camera_status_pip_edit)
                        null -> stringResource(R.string.demo_secondary_camera_status_prompt)
                    },
                    tone = DemoStatusTone.Guidance,
                )
            }
        },
        controls = {
            Text(
                stringResource(R.string.demo_secondary_camera_explainer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Mark the section label as a heading so TalkBack users can
            // navigate to it and understand the chip row that follows.
            Text(
                stringResource(R.string.demo_secondary_camera_chip_section),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.semantics { heading() }
            )
            val selectedStateDescription =
                stringResource(R.string.demo_secondary_camera_chip_selected)
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.sm)
            ) {
                CameraPreset.entries.forEach { preset ->
                    val selected = cameraPreset == preset
                    FilterChip(
                        selected = selected,
                        onClick = { cameraPreset = preset },
                        label = { Text(stringResource(preset.labelRes)) },
                        // FilterChip exposes a selected toggle to TalkBack, but
                        // the default state announcement ("on"/"off") is vague
                        // for a camera-angle picker — spell out "selected".
                        modifier = Modifier.semantics {
                            if (selected) stateDescription = selectedStateDescription
                        }
                    )
                }
            }
            OutlinedButton(
                onClick = {
                    helmetX = 0f
                    helmetZ = 0f
                    helmetYaw = 0f
                    lastEdit = null
                },
                enabled = lastEdit != null,
            ) { Text(stringResource(R.string.demo_secondary_camera_reset)) }
        },
        topOverlay = {
            val pipDescription = stringResource(
                R.string.demo_secondary_camera_pip_cd,
                stringResource(cameraPreset.labelRes)
            )
            Box(
                modifier = Modifier
                    .align(Alignment.Start)
                    .padding(horizontal = SceneViewTokens.Space.md)
                    .size(SceneViewTokens.Space.x4l * 2, SceneViewTokens.Space.x3l * 2)
                    .clip(RoundedCornerShape(SceneViewTokens.Radius.sm))
                    .border(
                        SceneViewTokens.Layout.selectedOutlineWidth,
                        MaterialTheme.colorScheme.outline,
                        RoundedCornerShape(SceneViewTokens.Radius.sm)
                    )
                    // The PiP renders into a TextureView that TalkBack cannot
                    // introspect — describe it explicitly, and mark it a polite
                    // live region so the angle change is announced when the user
                    // picks a different chip.
                    .semantics {
                        contentDescription = pipDescription
                        liveRegion = LiveRegionMode.Polite
                    }
            ) {
                SceneView(
                    modifier = Modifier.fillMaxSize(),
                    surfaceType = SurfaceType.TextureSurface,
                    engine = engine,
                    view = pipView,
                    modelLoader = modelLoader,
                    materialLoader = materialLoader,
                    environmentLoader = environmentLoader,
                    environment = environment,
                    renderInvalidator = pipRenderInvalidator,
                    onFrame = {
                        if (!insetShowsHelmet && pipInstance != null && !modelLoader.isLoading) {
                            insetShowsHelmet = true
                            // The main view may have parked: it has to see the answer.
                            renderInvalidator.requestRender()
                        }
                    },
                    cameraNode = pipCameraNode,
                    cameraManipulator = null,
                    // The helmet moves; re-centring on the scene's bounds would move the stage.
                    autoCenterContent = false,
                    onGestureListener = rememberOnGestureListener(
                        onSingleTapConfirmed = { event, _ ->
                            edit(pipView, event.x, event.y, EditSource.PIP)
                        },
                    ),
                ) {
                    StageFloor(floorMaterial, gridMaterial)
                    pipInstance?.let { instance ->
                        ModelNode(
                            modelInstance = instance,
                            scaleToUnits = HELMET_SIZE,
                            centerOrigin = Position(y = -1f),
                            position = helmetPosition,
                            rotation = helmetRotation,
                        )
                    }
                }
                Text(
                    stringResource(R.string.demo_secondary_camera_pip_label, stringResource(cameraPreset.labelRes)),
                    modifier = Modifier.align(Alignment.BottomStart)
                        .background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.small)
                        .padding(SceneViewTokens.Space.sm),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                stringResource(R.string.demo_secondary_camera_main),
                modifier = Modifier.align(Alignment.End).padding(horizontal = SceneViewTokens.Space.md)
                    .background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.small)
                    .padding(SceneViewTokens.Space.sm),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    ) {
        // #3426 — the main view is fitted to its subject. Since #4083 the subject is the stage
        // the helmet can walk on, not the helmet alone, so a move to the stage's edge stays in
        // frame; a slight look-down shows the floor the edit lands on.
        val mainRadius = rememberFitOrbitRadius(
            extentX = STAGE_HALF_EXTENT * 2f + HELMET_SIZE,
            extentY = HELMET_SIZE,
            extentZ = STAGE_HALF_EXTENT * 2f + HELMET_SIZE,
            elevationDegrees = MAIN_ELEVATION_DEGREES,
        )
        val mainPitch = Math.toRadians(MAIN_ELEVATION_DEGREES.toDouble())
        SceneView(
            modifier = Modifier.fillMaxSize(),
            engine = engine,
            view = mainView,
            modelLoader = modelLoader,
            materialLoader = materialLoader,
            environmentLoader = environmentLoader,
            environment = environment,
            renderInvalidator = renderInvalidator,
            onFrame = firstFrame.onFrame,
            // The helmet moves; re-centring on the scene's bounds would move the stage with it.
            autoCenterContent = false,
            cameraManipulator = rememberCameraManipulator(
                orbitHomePosition = Position(
                    x = STAGE_CENTRE.x,
                    y = STAGE_CENTRE.y + mainRadius * sin(mainPitch).toFloat(),
                    z = STAGE_CENTRE.z + mainRadius * cos(mainPitch).toFloat(),
                ),
                targetPosition = STAGE_CENTRE,
            ),
            onGestureListener = rememberOnGestureListener(
                onSingleTapConfirmed = { event, _ ->
                    edit(mainView, event.x, event.y, EditSource.MAIN)
                },
            ),
        ) {
            StageFloor(floorMaterial, gridMaterial)
            mainInstance?.let { instance ->
                ModelNode(
                    modelInstance = instance,
                    scaleToUnits = HELMET_SIZE,
                    centerOrigin = Position(y = -1f),
                    position = helmetPosition,
                    rotation = helmetRotation,
                )
            }
        }
    }
}

/** Which camera the last edit was made through. */
private enum class EditSource { MAIN, PIP }

/**
 * The floor both views share: a plane that runs out into the stage sky's fog, and a grid over
 * the stage the helmet can walk on, so a move reads as a distance in either camera.
 */
@Composable
private fun SceneScope.StageFloor(
    floorMaterial: MaterialInstance,
    gridMaterial: MaterialInstance,
) {
    PlaneNode(
        size = Size(x = FLOOR_SIZE, y = 0f, z = FLOOR_SIZE),
        normal = Direction(y = 1f),
        materialInstance = floorMaterial,
    )
    val span = GRID_SPACING * GRID_HALF_LINES * 2f
    repeat(GRID_HALF_LINES * 2 + 1) { i ->
        val offset = (i - GRID_HALF_LINES) * GRID_SPACING
        CubeNode(
            size = Size(x = GRID_LINE_WIDTH, y = GRID_LINE_HEIGHT, z = span),
            position = Position(x = offset, y = GRID_LINE_HEIGHT / 2f),
            materialInstance = gridMaterial,
        )
        CubeNode(
            size = Size(x = span, y = GRID_LINE_HEIGHT, z = GRID_LINE_WIDTH),
            position = Position(y = GRID_LINE_HEIGHT / 2f, z = offset),
            materialInstance = gridMaterial,
        )
    }
}

/** Where [ray] meets the floor (`y = 0`), or `null` if it points at the sky. */
private fun rayHitsFloor(ray: Ray): Float3? {
    if (ray.direction.y >= -1e-4f) return null
    val t = -ray.origin.y / ray.direction.y
    return ray.origin + ray.direction * t
}

/** Whether [ray] passes within [radius] of [point], in front of its origin. */
private fun rayPassesWithin(ray: Ray, point: Float3, radius: Float): Boolean {
    val direction = normalize(ray.direction)
    val toPoint = point - ray.origin
    val along = dot(toPoint, direction)
    if (along <= 0f) return false
    val closest = ray.origin + direction * along
    return length(point - closest) <= radius
}

/**
 * Loads the helmet GLB once and returns [count] resource-sharing
 * [ModelInstance]s via `ModelLoader.createInstancedModel`.
 *
 * Mirrors the threading contract of `rememberModelInstance`: the asset bytes
 * are read on [Dispatchers.IO], then `createInstancedModel` (a `@MainThread`
 * Filament call) runs back on the composition's main dispatcher inside
 * [produceState]. Returns `null` while loading, and a failure — not an empty list — when the
 * asset cannot be read or parsed, so the screen can say so instead of showing an empty stage
 * (#4459).
 */
@Composable
private fun rememberInstancedHelmet(
    modelLoader: ModelLoader,
    count: Int,
): Result<List<ModelInstance>>? {
    val context = LocalContext.current
    return produceState<Result<List<ModelInstance>>?>(null, modelLoader, count) {
        val buffer = withContext(Dispatchers.IO) {
            runCatching { context.assets.readBuffer(HELMET_ASSET) }
        }
        value = buffer.mapCatching { bytes ->
            modelLoader.createInstancedModel(bytes, count).also { created ->
                check(created.size == count) { "expected $count instances, got ${created.size}" }
            }
        }.onFailure { error ->
            if (error is kotlinx.coroutines.CancellationException) throw error
            Log.w(TAG, "Failed to load $HELMET_ASSET", error)
        }
    }.value
}

/**
 * A PiP camera framing.
 *
 * [eye] is the static eye position for a fixed angle. It is `null` for [ORBIT],
 * which has no fixed position — the [LaunchedEffect] animates it every frame.
 */
private enum class CameraPreset(@StringRes val labelRes: Int, val eye: Position?) {
    // Y=0.85 with X=0.01 to avoid a gimbal singularity in lookAt's up-vector
    // resolution when the camera sits exactly above the origin.
    TOP(R.string.demo_secondary_camera_chip_top, Position(0.01f, 1.9f, 0f)),
    SIDE(R.string.demo_secondary_camera_chip_side, Position(1.5f, 0.35f, 0f)),
    FRONT(R.string.demo_secondary_camera_chip_front, Position(0f, 0.35f, 1.5f)),
    CORNER(R.string.demo_secondary_camera_chip_corner, Position(1.05f, 0.85f, 1.05f)),

    // No fixed eye — the demo's LaunchedEffect sweeps the PiP camera around the
    // model on its own, regardless of how the user orbits the main view.
    ORBIT(R.string.demo_secondary_camera_chip_orbit, null),
}

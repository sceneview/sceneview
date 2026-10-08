package io.github.sceneview.demo.demos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.google.android.filament.LightManager
import io.github.sceneview.SceneView
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.R
import io.github.sceneview.demo.common.StageSkyFog
import io.github.sceneview.demo.common.rememberStageSkybox
import io.github.sceneview.demo.common.themedStageSky
import io.github.sceneview.demo.demoSceneFrame
import io.github.sceneview.demo.fitOrbitRadius
import io.github.sceneview.demo.pendulumCeiling
import io.github.sceneview.demo.pendulumSubject
import io.github.sceneview.demo.pendulumSwingExtent
import io.github.sceneview.demo.rememberFirstFrameState
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.demo.ui.GlassActionPill
import io.github.sceneview.environment.rememberHDREnvironment
import io.github.sceneview.gesture.CameraGestureDetector
import io.github.sceneview.math.Direction
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Scale
import io.github.sceneview.math.Size
import io.github.sceneview.material.setColor
import io.github.sceneview.node.CubeNode as CubeNodeImpl
import io.github.sceneview.node.SphereNode as SphereNodeImpl
import io.github.sceneview.node.TubeNode as TubeNodeImpl
import io.github.sceneview.physics.DoublePendulum
import io.github.sceneview.physics.DoublePendulumLink
import io.github.sceneview.physics.DoublePendulumState
import io.github.sceneview.physics.HALF_PI
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberEnvironment
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberRenderInvalidator
import io.github.sceneview.rememberView
import io.github.sceneview.sample.rememberMaterialInstance
import io.github.sceneview.sample.ui.LabeledSlider
import java.util.Locale
import kotlin.math.atan2

/**
 * **Orbital Pendulum** — a chaotic two-link mechanism rendered in SceneView's
 * own visual language: two slender brand-blue arms tipped with glossy weighted
 * bobs, swinging from a single brushed hinge on a stand, over a gridded floor
 * that fades into the themed stage sky — fixed references that make every orbit
 * read as the camera moving (#4083).
 *
 * The genuine double-pendulum physics is shared, platform-independent code —
 * the [DoublePendulum] simulation in `sceneview-core` (the equations of motion
 * originate from the cross-platform port tracked in
 * [#1221](https://github.com/sceneview/sceneview/issues/1221)). What this demo
 * owns is the *staging*: a ball-and-rod construction (point masses drawn where
 * the model actually puts them — at each link's tip), a SceneView brand-token
 * palette (primary blue → gradient violet), an off-axis key/rim light rig, and
 * a camera that auto-frames the **entire reachable swing envelope** rather than
 * a fixed crop.
 *
 * ## How the simulation drives the render
 *
 * Each arm is a thin [CubeNodeImpl] of unit height; a glossy [SphereNodeImpl]
 * bob marks the point mass at each link tip. A [withFrameNanos] loop advances
 * [DoublePendulum.step] every frame and rewrites every node's
 * `position` / `rotation` / `scale` from the simulation's joint positions — no
 * per-frame mesh regeneration, just transform updates.
 *
 * ## Camera framing
 *
 * The reachable tip can sit anywhere within `length1 + length2` of the pivot,
 * so the swing envelope is a disc of that radius centred on the pivot. The
 * camera targets the **centre of that disc** and backs off by a distance
 * proportional to the envelope radius (with headroom), so the full chaotic
 * swing stays comfortably inside the viewport at every arm-length setting —
 * fixing the "ça cible pas bien" framing flagged in
 * [#1481](https://github.com/sceneview/sceneview/issues/1481).
 */
@Composable
fun DoublePendulumDemo(onBack: () -> Unit) {
    // --- Tunable simulation parameters (exposed as sliders) ---
    // Distinct, asymmetric default proportions: a long lead arm and a shorter,
    // heavier trailing arm — an original ratio, not a mirrored pair.
    var length1 by remember { mutableFloatStateOf(PENDULUM_DEFAULT_LENGTH_1) }
    var length2 by remember { mutableFloatStateOf(PENDULUM_DEFAULT_LENGTH_2) }
    var gravity by remember { mutableFloatStateOf(PENDULUM_DEFAULT_GRAVITY) }

    // Generation key — bumping it re-seeds the simulation (Release button).
    var generation by remember { mutableStateOf(0) }

    // Leave a fading trace of the tip's path: the chaos is the path, not the instant.
    var showTrail by remember { mutableStateOf(true) }

    // World-space hinge, on top of a stand whose base sits on the floor (y = 0). High enough
    // that the longest possible swing (both arms hanging straight down) still clears the base.
    val pivot = remember { Position(0f, PIVOT_HEIGHT, 0f) }

    // The mutable simulation state. Re-seeded whenever a slider or Release
    // changes the parameters: both arms start cocked to one side so the very
    // first frame already shows dramatic, asymmetric motion.
    var state by remember(length1, length2, gravity, generation) {
        mutableStateOf(
            DoublePendulumState(
                link1 = DoublePendulumLink(
                    length = length1, mass = PENDULUM_JOINT_MASS, angle = PENDULUM_RELEASE_ANGLE_1,
                ),
                link2 = DoublePendulumLink(
                    length = length2, mass = PENDULUM_TIP_MASS, angle = PENDULUM_RELEASE_ANGLE_2,
                ),
                pivot = pivot,
                gravity = gravity,
                damping = PENDULUM_DAMPING,
            )
        )
    }

    val engine = rememberEngine()
    val view = rememberView(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)
    val renderInvalidator = rememberRenderInvalidator()

    // Warm studio HDR for IBL. The backdrop is the themed stage sky (#4083): a floor that
    // dissolves into a horizon glow, like the Camera & Gestures stage. The flat grey backdrop
    // this replaced (#3826) left nothing in frame that did not move with the camera, so an
    // orbit read as the pendulum spinning in a void rather than the camera moving around it.
    val hdrEnvironment = rememberHDREnvironment(
        environmentLoader,
        "environments/studio_warm_2k.hdr",
        createSkybox = false,
    )
    val fallbackEnvironment = rememberEnvironment(environmentLoader)
    val sky = themedStageSky()
    val stageSkybox = rememberStageSkybox(engine, sky, renderInvalidator::requestRender)
    StageSkyFog(view, sky, renderInvalidator::requestRender)
    val lightEnvironment = hdrEnvironment ?: fallbackEnvironment
    val activeEnvironment = remember(lightEnvironment, stageSkybox) {
        lightEnvironment.copy(skybox = stageSkybox)
    }

    // Floor and its measuring grid, recoloured in place on a light/dark flip — the activity
    // handles `uiMode` itself, so the materials outlive the toggle.
    val gridColor = sky.grid
    val floorMaterial = remember(materialLoader) {
        materialLoader.createColorInstance(sky.floor, metallic = 0f, roughness = 0.62f)
    }
    val gridMaterial = remember(materialLoader) {
        materialLoader.createColorInstance(gridColor, metallic = 0f, roughness = 0.8f)
    }
    LaunchedEffect(floorMaterial, gridMaterial, sky.floor, gridColor) {
        floorMaterial.setColor(sky.floor)
        gridMaterial.setColor(gridColor)
        renderInvalidator.requestRender()
    }

    val cameraNode = rememberCameraNode(engine)
    val firstFrame = rememberFirstFrameState(engine)
    // "Scene ready" waits for the HDR: the fallback-lit frames are not the demo's picture (#4174).
    firstFrame.holdUntil(landed = hdrEnvironment != null)

    DemoScaffold(
        title = stringResource(R.string.demo_double_pendulum_title),
        onBack = onBack,
        firstFrameRendered = firstFrame.rendered,
        sceneReady = firstFrame.sceneReady,
        // Release is the one thing to do here, so it lives on the scene; the
        // sheet keeps the parameters, which apply live as they are dragged.
        bottomOverlayReservesScene = true,
        bottomOverlay = {
            GlassActionPill(
                icon = Icons.Outlined.RestartAlt,
                label = stringResource(R.string.demo_double_pendulum_release),
                onClick = { generation++ },
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        },
        controls = {
            LabeledSlider(
                label = stringResource(R.string.demo_double_pendulum_lead_arm),
                value = length1,
                onValueChange = { length1 = it },
                valueRange = PENDULUM_LENGTH_1_RANGE,
                valueText = "${"%.2f".format(Locale.US, length1)} m",
            )

            LabeledSlider(
                label = stringResource(R.string.demo_double_pendulum_trailing_arm),
                value = length2,
                onValueChange = { length2 = it },
                valueRange = PENDULUM_LENGTH_2_RANGE,
                valueText = "${"%.2f".format(Locale.US, length2)} m",
            )

            LabeledSlider(
                label = stringResource(R.string.demo_double_pendulum_gravity),
                value = gravity,
                onValueChange = { gravity = it },
                valueRange = PENDULUM_GRAVITY_RANGE,
                valueText = "${"%.1f".format(Locale.US, gravity)} m/s²",
            )

            // Toggleable on the whole row so the label is part of the target — the same
            // contract every other demo switch uses.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(value = showTrail, onValueChange = { showTrail = it }),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.demo_double_pendulum_trail),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        stringResource(R.string.demo_double_pendulum_trail_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = showTrail, onCheckedChange = null)
            }

            Spacer(modifier = Modifier.height(SceneViewTokens.Space.sm))
            Text(
                stringResource(R.string.demo_double_pendulum_explainer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            // The scene may run under the glass (a phone held sideways has no room to
            // inset it, #4310): what covers it goes to the SDK as `contentPadding`, so
            // the hinge is centred in the free area and the swing is drawn there, not
            // behind the Release pill or the settings sheet (#4326).
            val frame = demoSceneFrame()
            // Fitted for the free area at rest. The sheet then only narrows the band the
            // lens projects into — the same picture, smaller, follows it up — so opening
            // Settings neither rebuilds the orbit nor resets the angle the user set.
            // Rebuilt when an arm length or the resting area changes, so a slider drag
            // reframes live instead of leaving the swing cropped.
            val shot = remember(length1, length2, frame.restingAspect, frame.compactHeight) {
                pendulumHomeShot(length1, length2, frame.restingAspect, strip = frame.compactHeight)
            }
            val cameraManipulator = remember(shot) {
                CameraGestureDetector.DefaultCameraManipulator(
                    eyePosition = shot.eye,
                    targetPosition = shot.target,
                )
            }
            SceneView(
                modifier = Modifier.fillMaxSize(),
                contentPadding = frame.contentPadding,
                onFrame = firstFrame.onFrame,
                engine = engine,
                view = view,
                materialLoader = materialLoader,
                environmentLoader = environmentLoader,
                environment = activeEnvironment,
                renderInvalidator = renderInvalidator,
                cameraNode = cameraNode,
                // The rig is authored around the hinge; re-centring on the moving
                // arms' bounds is what pushed the pendulum off-centre at open.
                autoCenterContent = false,
                cameraManipulator = cameraManipulator,
            ) {
                // Off-axis key light — warm-leaning, rakes across the bobs so the
                // glossy spheres catch a moving highlight as they swing. It also
                // casts the swing's shadow onto the floor: the one cue that ties
                // the arms, high in the air, to the ground under them.
                LightNode(
                    type = LightManager.Type.DIRECTIONAL,
                    direction = Direction(-0.35f, -0.85f, -0.4f),
                    apply = {
                        intensity(9_500f)
                        castShadows(true)
                    },
                )
                // Cool rim light from behind-right separates the arms from the
                // stage sky and adds depth to the staging.
                LightNode(
                    type = LightManager.Type.DIRECTIONAL,
                    direction = Direction(0.6f, 0.25f, 0.5f),
                    apply = { intensity(3_200f) },
                )

                // --- SceneView brand-token palette --------------------------
                // Lead arm + bob: primary blue (#005bc1). Trailing arm + bob:
                // brand-gradient violet (#6446cd). Hinge: brushed neutral.
                val leadArmMaterial = rememberMaterialInstance(
                    materialLoader,
                    Color(0xFF005BC1),
                    metallic = 0.55f,
                    roughness = 0.35f,
                )
                val leadBobMaterial = rememberMaterialInstance(
                    materialLoader,
                    Color(0xFF3D7BD6),
                    metallic = 0.7f,
                    roughness = 0.18f,
                )
                val trailArmMaterial = rememberMaterialInstance(
                    materialLoader,
                    Color(0xFF6446CD),
                    metallic = 0.55f,
                    roughness = 0.35f,
                )
                val trailBobMaterial = rememberMaterialInstance(
                    materialLoader,
                    Color(0xFF8A6FE0),
                    metallic = 0.7f,
                    roughness = 0.18f,
                )
                val hingeMaterial = rememberMaterialInstance(
                    materialLoader,
                    Color(0xFFB8C0CC),
                    metallic = 0.9f,
                    roughness = 0.3f,
                )

                // Each arm is a thin unit-tall box; the frame loop below rewrites
                // its transform. Initial Y-size of 1 m means a Y-scale equal to the
                // arm length renders the correct rendered length.
                var leadArmRef by remember { mutableStateOf<CubeNodeImpl?>(null) }
                var trailArmRef by remember { mutableStateOf<CubeNodeImpl?>(null) }
                // Glossy bobs mark the point masses — drawn where the physics model
                // actually concentrates mass: at each link's tip.
                var jointBobRef by remember { mutableStateOf<SphereNodeImpl?>(null) }
                var tipBobRef by remember { mutableStateOf<SphereNodeImpl?>(null) }
                var trailRef by remember { mutableStateOf<TubeNodeImpl?>(null) }

                // --- The stage: floor, measuring grid, stand -------------------
                // Everything here is still, so every camera move shows against it.
                PlaneNode(
                    size = Size(x = FLOOR_SIZE, y = 0f, z = FLOOR_SIZE),
                    normal = Direction(y = 1f),
                    materialInstance = floorMaterial,
                )
                // A metre-scale grid centred under the hinge: its perspective lines
                // swing with an orbit, and it gives the swing a scale to read against.
                val gridLines = GRID_HALF_LINES * 2 + 1
                val gridSpan = GRID_SPACING * GRID_HALF_LINES * 2f
                repeat(gridLines) { i ->
                    val offset = (i - GRID_HALF_LINES) * GRID_SPACING
                    CubeNode(
                        size = Size(x = GRID_LINE_WIDTH, y = GRID_LINE_HEIGHT, z = gridSpan),
                        position = Position(x = offset, y = GRID_LINE_HEIGHT / 2f),
                        materialInstance = gridMaterial,
                    )
                    CubeNode(
                        size = Size(x = gridSpan, y = GRID_LINE_HEIGHT, z = GRID_LINE_WIDTH),
                        position = Position(y = GRID_LINE_HEIGHT / 2f, z = offset),
                        materialInstance = gridMaterial,
                    )
                }
                // The stand: a base plate, a post behind the swing plane and an axle
                // out to the hinge — the arms hang from something, not from the air.
                CubeNode(
                    size = Size(x = STAND_BASE_WIDTH, y = STAND_BASE_HEIGHT, z = STAND_BASE_DEPTH),
                    position = Position(y = STAND_BASE_HEIGHT / 2f, z = STAND_POST_Z),
                    materialInstance = hingeMaterial,
                    apply = { isShadowCaster = true },
                )
                CubeNode(
                    size = Size(x = STAND_POST_SIZE, y = PIVOT_HEIGHT, z = STAND_POST_SIZE),
                    position = Position(y = PIVOT_HEIGHT / 2f, z = STAND_POST_Z),
                    materialInstance = hingeMaterial,
                    apply = { isShadowCaster = true },
                )
                CubeNode(
                    size = Size(x = STAND_AXLE_SIZE, y = STAND_AXLE_SIZE, z = -STAND_POST_Z),
                    position = Position(y = PIVOT_HEIGHT, z = STAND_POST_Z / 2f),
                    materialInstance = hingeMaterial,
                    apply = { isShadowCaster = true },
                )

                CubeNode(
                    size = Size(x = 0.032f, y = 1f, z = 0.032f),
                    materialInstance = leadArmMaterial,
                    apply = {
                        isShadowCaster = true
                        leadArmRef = this
                    },
                )
                CubeNode(
                    size = Size(x = 0.046f, y = 1f, z = 0.046f),
                    materialInstance = trailArmMaterial,
                    apply = {
                        isShadowCaster = true
                        trailArmRef = this
                    },
                )
                // Joint bob — the lead link's point mass (also the trailing hinge).
                SphereNode(
                    radius = JOINT_BOB_RADIUS,
                    materialInstance = leadBobMaterial,
                    apply = {
                        isShadowCaster = true
                        jointBobRef = this
                    },
                )
                // Tip bob — the trailing link's point mass; heavier, so larger.
                SphereNode(
                    radius = TIP_BOB_RADIUS,
                    materialInstance = trailBobMaterial,
                    apply = {
                        isShadowCaster = true
                        tipBobRef = this
                    },
                )
                // Fixed hinge marker at the pivot — a small brushed sphere.
                SphereNode(
                    radius = 0.05f,
                    materialInstance = hingeMaterial,
                    position = pivot,
                    apply = { isShadowCaster = true },
                )
                // The tip's recent path. Seeded as a short stub at the release point
                // and rewritten in place every frame by the loop below — same point
                // count every time, so the upload reuses its index buffer.
                // The seed hangs off the hinge (never read from `state`, which changes every
                // frame and would recompose the scene with it); the loop replaces it on its
                // first frame.
                if (showTrail) {
                    val seed = remember { trailSeed(pivot) }
                    TubeNode(
                        points = seed,
                        radius = TRAIL_RADIUS,
                        radialSegments = TRAIL_RADIAL_SEGMENTS,
                        materialInstance = trailBobMaterial,
                        apply = { trailRef = this },
                    )
                    // The node is destroyed when the switch turns it off. This clears the
                    // ref in the same composition pass, and the loop re-reads `trailRef`
                    // every frame, so it never writes into a destroyed vertex buffer.
                    DisposableEffect(Unit) { onDispose { trailRef = null } }
                }

                // Per-frame physics loop. Keyed on the node refs, the parameters and
                // generation so a Release or a slider change restarts it — and the
                // trail with it, instead of drawing a jump from the old swing.
                // The trail is deliberately not a key: toggling it must not restart the
                // swing. The loop keeps the tip's recent path either way and writes it
                // into whichever tube exists on that frame.
                LaunchedEffect(
                    leadArmRef, trailArmRef, jointBobRef, tipBobRef,
                    generation, length1, length2, gravity,
                ) {
                    val arm1 = leadArmRef ?: return@LaunchedEffect
                    val arm2 = trailArmRef ?: return@LaunchedEffect
                    val bobJoint = jointBobRef ?: return@LaunchedEffect
                    val bobTip = tipBobRef ?: return@LaunchedEffect
                    val trailPoints = ArrayDeque(trailSeed(state.tip))
                    trailRef?.updateGeometry(points = trailPoints.toList())
                    var lastNanos = withFrameNanos { it }
                    while (true) {
                        val now = withFrameNanos { it }
                        val dt = ((now - lastNanos) / 1_000_000_000.0).toFloat()
                        lastNanos = now

                        state = DoublePendulum.step(state, dt)

                        applyArmTransform(arm1, state.pivot, state.joint)
                        applyArmTransform(arm2, state.joint, state.tip)
                        bobJoint.position = state.joint
                        bobTip.position = state.tip
                        trailPoints.removeFirst()
                        trailPoints.addLast(state.tip)
                        trailRef?.updateGeometry(points = trailPoints.toList())
                    }
                }
            }
        }
    }
}

/** Where the home shot stands and what it looks at. */
internal class PendulumHomeShot(val eye: Position, val target: Position)

/**
 * The home shot for arms of [length1] and [length2] in a free area of [freeAspect] (width over
 * height), a slight look-down that puts the floor grid under the stand in the lower frame.
 *
 * Upright the width limits. The tip's centre can reach anywhere within `length1 + length2` of the
 * hinge and the bob drawn around it a radius further: the camera looks at the hinge from straight
 * in front and backs off until that disc fills the free area, so the hinge sits dead centre of it
 * and no swing leaves it.
 *
 * In a [strip] — a phone held sideways, where the free area is a band above the Release pill —
 * the height limits, and it is spent on what is drawn: from the floor, or the stand's foot ends
 * behind the pill, up to the highest a pendulum let go at rest can climb ([pendulumCeiling]).
 */
internal fun pendulumHomeShot(
    length1: Float,
    length2: Float,
    freeAspect: Float,
    strip: Boolean,
): PendulumHomeShot {
    val swingExtent = pendulumSwingExtent(length1, length2, TIP_BOB_RADIUS)
    val ceiling = pendulumCeiling(
        length1 = length1,
        length2 = length2,
        mass1 = PENDULUM_JOINT_MASS,
        mass2 = PENDULUM_TIP_MASS,
        releaseAngle1 = PENDULUM_RELEASE_ANGLE_1,
        releaseAngle2 = PENDULUM_RELEASE_ANGLE_2,
        jointBobRadius = JOINT_BOB_RADIUS,
        tipBobRadius = TIP_BOB_RADIUS,
    )
    val subject = pendulumSubject(PIVOT_HEIGHT, swingExtent, ceiling, strip)
    val distance = fitOrbitRadius(
        extentX = swingExtent,
        extentY = subject.extentY,
        extentZ = TIP_BOB_RADIUS * 2f,
        aspect = freeAspect,
        elevationDegrees = PENDULUM_ELEVATION_DEGREES,
        fill = if (strip) PENDULUM_STRIP_FRAME_FILL else PENDULUM_FRAME_FILL,
        azimuthInvariant = false,
    )
    val pitch = Math.toRadians(PENDULUM_ELEVATION_DEGREES.toDouble())
    val target = Position(0f, subject.centerY, 0f)
    return PendulumHomeShot(
        eye = Position(
            target.x,
            target.y + distance * kotlin.math.sin(pitch).toFloat(),
            target.z + distance * kotlin.math.cos(pitch).toFloat(),
        ),
        target = target,
    )
}

/**
 * The release: both arms cocked to one side, past the horizontal, at rest. Angles from the
 * downward vertical. The first frame already shows asymmetric motion, and the height the bobs
 * start from is all the energy the swing will ever have — the home shot is fitted for it.
 */
internal const val PENDULUM_RELEASE_ANGLE_1 = HALF_PI * 1.15f
internal const val PENDULUM_RELEASE_ANGLE_2 = HALF_PI * 1.7f

/** The point masses: the tip is the heavier one, and is drawn larger. */
internal const val PENDULUM_JOINT_MASS = 1f
internal const val PENDULUM_TIP_MASS = 1.6f

/** Share of the angular velocity lost per second. */
internal const val PENDULUM_DAMPING = 0.035f

/** What the demo opens with: the two arm lengths in metres, gravity in m/s². */
internal const val PENDULUM_DEFAULT_LENGTH_1 = 0.52f
internal const val PENDULUM_DEFAULT_LENGTH_2 = 0.34f
internal const val PENDULUM_DEFAULT_GRAVITY = 11.2f

/** What the Settings sliders can set them to. */
internal val PENDULUM_LENGTH_1_RANGE = 0.3f..0.65f
internal val PENDULUM_LENGTH_2_RANGE = 0.2f..0.5f
internal val PENDULUM_GRAVITY_RANGE = 1.6f..20f

/** Radius of the joint bob, the lead arm's point mass. */
internal const val JOINT_BOB_RADIUS = 0.062f

/** Radius of the tip bob, included in the full swing envelope. */
internal const val TIP_BOB_RADIUS = 0.085f

/** Share of the free area the swing envelope's bounding square fills. */
private const val PENDULUM_FRAME_FILL = 0.96f

/**
 * The same in a strip, where the fit runs down to the stand's foot: a square has spare corners
 * around the disc inside it, the foot and the shadow it casts forward have none.
 */
internal const val PENDULUM_STRIP_FRAME_FILL = 0.88f

/** Look-down of the home shot: enough to put the floor grid under the stand in frame. */
internal const val PENDULUM_ELEVATION_DEGREES = 10f

/**
 * Height of the hinge above the floor. The longest reachable hang (0.65 + 0.5 m arms, plus the
 * tip bob) still clears the stand's base plate.
 */
internal const val PIVOT_HEIGHT = 1.4f

/** Side of the square floor: far past where the stage sky's fog has swallowed it. */
private const val FLOOR_SIZE = 90f

/** Grid: lines every 25 cm, 6 either side of the stand — a 3 m square of measured floor. */
private const val GRID_SPACING = 0.25f
private const val GRID_HALF_LINES = 6
private const val GRID_LINE_WIDTH = 0.008f
private const val GRID_LINE_HEIGHT = 0.002f

/** The stand, behind the swing plane (the bobs reach 8.5 cm either side of it). */
internal const val STAND_POST_Z = -0.18f
internal const val STAND_POST_SIZE = 0.05f
internal const val STAND_AXLE_SIZE = 0.03f
internal const val STAND_BASE_WIDTH = 0.42f
internal const val STAND_BASE_DEPTH = 0.3f
internal const val STAND_BASE_HEIGHT = 0.03f

/** Trail: the tip's last ~0.8 s at 60 fps, drawn as a thin tube. */
private const val TRAIL_POINTS = 48
private const val TRAIL_RADIUS = 0.007f
private const val TRAIL_RADIAL_SEGMENTS = 6

/**
 * A degenerate trail parked at [at]: [TRAIL_POINTS] points a tenth of a millimetre apart, so no
 * segment has zero length (a tube's cross-section is oriented by its segment directions).
 */
private fun trailSeed(at: Position): List<Position> =
    List(TRAIL_POINTS) { i -> Position(at.x, at.y + i * 1e-4f, at.z) }

/**
 * Position, orient and stretch a unit-tall arm box so its two ends sit at
 * [from] and [to] in world space.
 *
 * The box mesh is 1 m tall along local +Y; we scale Y by the segment length,
 * place the box centre at the segment midpoint, and rotate about Z so local
 * +Y points from [from] toward [to]. (The pendulum swings in the XY plane, so
 * Z rotation alone fully orients the arm.)
 */
private fun applyArmTransform(node: CubeNodeImpl, from: Position, to: Position) {
    val dx = to.x - from.x
    val dy = to.y - from.y
    val length = kotlin.math.sqrt(dx * dx + dy * dy)
    // Angle of the segment from the +Y axis (Filament Z-rotation, degrees).
    // atan2(dx, dy) gives 0° when the arm points straight up (+Y); negate so
    // an arm hanging down-and-to-the-right rotates the expected way.
    val angleDeg = Math.toDegrees(atan2(dx.toDouble(), dy.toDouble())).toFloat()
    node.position = Position(
        x = (from.x + to.x) * 0.5f,
        y = (from.y + to.y) * 0.5f,
        z = from.z,
    )
    node.rotation = Rotation(x = 0f, y = 0f, z = -angleDeg)
    node.scale = Scale(x = 1f, y = length.coerceAtLeast(0.001f), z = 1f)
}

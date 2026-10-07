package io.github.sceneview.demo.demos

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import com.google.android.filament.Engine
import com.google.ar.core.Pose
import dev.romainguy.kotlin.math.Quaternion
import io.github.sceneview.SceneView
import io.github.sceneview.SurfaceType
import io.github.sceneview.ar.collaborative.CollaborativeSession
import io.github.sceneview.ar.collaborative.LoopbackCollaborativeTransport
import io.github.sceneview.ar.collaborative.rememberCollaborativeSession
import io.github.sceneview.demo.DemoPreviewPlaceholder
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.DockItem
import io.github.sceneview.demo.FirstFrameState
import io.github.sceneview.demo.LocalDemoSceneCover
import io.github.sceneview.demo.LocalDemoSceneRestingCover
import io.github.sceneview.demo.LocalDemoSheetCover
import io.github.sceneview.demo.R
import io.github.sceneview.demo.SceneViewColors
import io.github.sceneview.demo.common.StageSkyFog
import io.github.sceneview.demo.common.rememberModelDemoEnvironment
import io.github.sceneview.demo.common.rememberStageSkybox
import io.github.sceneview.demo.common.themedStageSky
import io.github.sceneview.demo.rememberFirstFrameState
import io.github.sceneview.demo.theme.LocalStageChrome
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.demo.ui.GlassPill
import io.github.sceneview.environment.Environment
import io.github.sceneview.loaders.EnvironmentLoader
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.loaders.ModelLoader
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Scale
import io.github.sceneview.math.Size
import io.github.sceneview.rememberCameraManipulator
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberOnGestureListener
import io.github.sceneview.rememberRenderInvalidator
import io.github.sceneview.rememberView
import io.github.sceneview.sample.rememberMaterialInstance
import io.github.sceneview.utils.screenToRay
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.isActive

/**
 * Two independently orbitable views of one shared space, backed by two real sessions.
 * A single LoopbackHub joins Alice and Bob; rememberCollaborativeSession constructs each
 * CollaborativeSession and owns start/stop in a DisposableEffect, including transport cleanup.
 * Each view renders only its own session's received state. Replace loopback with a network
 * transport and establish a shared spatial anchor to extend this example to two devices.
 */
@Composable
fun ARCollaborativeDemo(onBack: () -> Unit) {
    val title = stringResource(R.string.demo_ar_collaborative_title)
    if (LocalInspectionMode.current) {
        DemoPreviewPlaceholder(title = title, onBack = onBack)
        return
    }
    val hub = remember { LoopbackCollaborativeTransport.LoopbackHub() }
    val aliceTransport = remember(hub) { hub.join("alice") }
    val bobTransport = remember(hub) { hub.join("bob") }
    val aliceName = stringResource(R.string.demo_ar_collaborative_alice)
    val bobName = stringResource(R.string.demo_ar_collaborative_bob)
    val alice = rememberCollaborativeSession(aliceTransport, displayName = aliceName)
    val bob = rememberCollaborativeSession(bobTransport, displayName = bobName)
    val slots = remember(alice, bob) { IntArray(2) }
    var shape by remember { mutableStateOf("cube") }
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materials = rememberMaterialLoader(engine)
    val environments = rememberEnvironmentLoader(engine)
    val firstFrames = listOf(rememberFirstFrameState(engine), rememberFirstFrameState(engine))
    val environment = rememberModelDemoEnvironment(environments, firstFrames[0])
    val bothRendered = remember { derivedStateOf { firstFrames.all { it.rendered.value } } }
    val bothReady = remember {
        derivedStateOf { firstFrames.all { it.rendered.value && it.sceneReady.value } }
    }
    DemoScaffold(
        title = title, onBack = onBack, themedStage = true,
        firstFrameRendered = bothRendered, sceneReady = bothReady,
        peekHeader = if (alice.placedNodes.isEmpty() && bob.placedNodes.isEmpty()) {
            stringResource(R.string.demo_ar_collaborative_hint)
        } else null,
        dock = listOf(
            DockItem(Icons.Default.ViewInAr, stringResource(R.string.demo_ar_collaborative_cube),
                onClick = { shape = "cube" }, selected = shape == "cube"),
            DockItem(Icons.Default.Circle, stringResource(R.string.demo_ar_collaborative_sphere),
                onClick = { shape = "sphere" }, selected = shape == "sphere"),
            DockItem(Icons.Default.Storage, stringResource(R.string.demo_ar_collaborative_cylinder),
                onClick = { shape = "cylinder" }, selected = shape == "cylinder"),
        ),
        controls = {
            Text(stringResource(R.string.demo_ar_collaborative_local), style = SceneViewTokens.Type.body)
            Spacer(Modifier.height(SceneViewTokens.Space.sm))
            Text(stringResource(R.string.demo_ar_collaborative_devices), style = SceneViewTokens.Type.body)
        },
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth > maxHeight
            // What the scaffold's chrome covers of the slot: identity row and status bar on top;
            // hint, mode pill, dock and navigation bar at the bottom, or the settings sheet.
            val top = LocalDemoSceneCover.current.calculateTopPadding()
            // Eased: the hint leaving the band does not make the view above it jump.
            val band by animateDpAsState(
                LocalDemoSceneRestingCover.current.calculateBottomPadding(),
                SceneViewTokens.Motion.fade(), label = "band",
            )
            val sheet by animateDpAsState(
                LocalDemoSheetCover.current, SceneViewTokens.Motion.fade(), label = "sheet",
            )
            val bottom = max(band, sheet)
            val pane: @Composable (Int, Modifier) -> Unit = { index, modifier ->
                SharedSpacePane(
                    modifier = modifier,
                    engine = engine,
                    modelLoader = modelLoader,
                    materials = materials,
                    environments = environments,
                    baseEnvironment = environment,
                    session = if (index == 0) alice else bob,
                    peerId = if (index == 0) "alice" else "bob",
                    name = if (index == 0) aliceName else bobName,
                    index = index,
                    wide = wide,
                    firstFrame = firstFrames[index],
                    chromePadding = PaddingValues(
                        top = if (wide || index == 0) top else 0.dp,
                        bottom = if (wide || index == 1) bottom else 0.dp,
                    ),
                ) { point ->
                    val session = if (index == 0) alice else bob
                    val peerId = if (index == 0) "alice" else "bob"
                    // No AR anchor: the stage origin IS shared-anchor space in this demo.
                    // Per-peer keys avoid concurrent writes to a key without a logical clock.
                    session.placeNode(
                        nodeKey = "$peerId-${slots[index]}", modelKey = shape,
                        translation = floatArrayOf(point.x, OBJECT_SIZE / 2f, point.z),
                        quaternion = floatArrayOf(0f, 0f, 0f, 1f),
                    )
                    slots[index] = (slots[index] + 1) % OBJECTS_PER_PEER
                }
            }
            val seam = Modifier.background(MaterialTheme.colorScheme.outlineVariant)
            if (wide) {
                Row(Modifier.fillMaxSize()) {
                    pane(0, Modifier.weight(1f).fillMaxHeight())
                    Spacer(seam.width(SceneViewTokens.Layout.hairlineWidth).fillMaxHeight())
                    pane(1, Modifier.weight(1f).fillMaxHeight())
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    pane(0, Modifier.weight(1f).fillMaxWidth())
                    Spacer(seam.height(SceneViewTokens.Layout.hairlineWidth).fillMaxWidth())
                    pane(1, Modifier.weight(1f).fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun SharedSpacePane(
    modifier: Modifier,
    engine: Engine,
    modelLoader: ModelLoader,
    materials: MaterialLoader,
    environments: EnvironmentLoader,
    baseEnvironment: Environment,
    session: CollaborativeSession,
    peerId: String,
    name: String,
    index: Int,
    wide: Boolean,
    firstFrame: FirstFrameState,
    chromePadding: PaddingValues,
    onPlace: (Position) -> Unit,
) {
    val view = rememberView(engine)
    val invalidator = rememberRenderInvalidator()
    val sky = themedStageSky()
    val skybox = rememberStageSkybox(engine, sky, invalidator::requestRender)
    StageSkyFog(view, sky, invalidator::requestRender)
    val environment = remember(baseEnvironment, skybox) { baseEnvironment.copy(skybox = skybox) }
    val floor = rememberMaterialInstance(materials, sky.floor, metallic = 0f, roughness = 0.7f)
    val aliceMaterial = rememberMaterialInstance(materials, SceneViewColors.Ramp4[0])
    val bobMaterial = rememberMaterialInstance(materials, SceneViewColors.Ramp4[1])
    // A low orbit and a wide lens: the viewer across the floor is inside the frame from the start.
    // Side by side the chrome leaves each view a strip: a lower orbit and a longer lens fill it.
    val home = remember(index, wide) {
        val side = if (index == 0) 1f else -1f
        Position(1.45f * side, if (wide) STRIP_ORBIT_HEIGHT else ORBIT_HEIGHT, 1.9f * side)
    }
    val focalLength = if (wide) STRIP_FOCAL_LENGTH_MM else WIDE_FOCAL_LENGTH_MM
    val camera = rememberCameraNode(engine) {
        position = home
        lookAt(Position(0f))
        this.focalLength = focalLength
    }
    // A rotation keeps both sessions and this camera: aim it again for the new frame.
    LaunchedEffect(camera, home, focalLength) {
        camera.position = home
        camera.lookAt(Position(0f))
        camera.focalLength = focalLength
    }
    // broadcastLocalPose has NO throttle. Sample cached camera values on the main thread;
    // polling also sends the final pose after a short drag, even if rendering has parked.
    LaunchedEffect(session, camera) {
        var lastPosition: Position? = null
        var lastQuaternion: Quaternion? = null
        while (isActive) {
            delay(1_000L / CollaborativeSession.DEFAULT_POSE_RATE_HZ)
            val p = camera.worldPosition
            val q = camera.worldQuaternion
            if (p != lastPosition || q != lastQuaternion) {
                session.broadcastLocalPose(Pose(
                    floatArrayOf(p.x, p.y, p.z), floatArrayOf(q.x, q.y, q.z, q.w),
                ))
                lastPosition = Position(p.x, p.y, p.z)
                lastQuaternion = Quaternion(q.x, q.y, q.z, q.w)
            }
        }
    }
    Box(modifier) {
        SceneView(
            modifier = Modifier.fillMaxSize(), surfaceType = SurfaceType.TextureSurface,
            engine = engine, modelLoader = modelLoader, materialLoader = materials,
            environmentLoader = environments, environment = environment,
            view = view, renderInvalidator = invalidator, cameraNode = camera,
            cameraManipulator = rememberCameraManipulator(home, Position(0f)),
            autoCenterContent = false, contentPadding = chromePadding,
            onFrame = firstFrame.onFrame,
            onGestureListener = rememberOnGestureListener(onSingleTapConfirmed = { event, _ ->
                // The tapped view supplies its own ray; intersect the round floor at y = 0.
                val ray = view.screenToRay(event.x, event.y)
                if (ray != null && ray.direction.y < -1e-4f) {
                    val distance = -ray.origin.y / ray.direction.y
                    val point = ray.origin + ray.direction * distance
                    if (distance >= 0f && point.x * point.x + point.z * point.z <= FLOOR_RADIUS * FLOOR_RADIUS) {
                        onPlace(point)
                    }
                }
            }),
        ) {
            CylinderNode(radius = FLOOR_RADIUS, height = 0.08f, sideCount = FLOOR_SIDES,
                position = Position(y = -0.04f), materialInstance = floor)
            // Owner ids arrive from a peer: an unknown one draws nothing.
            val materialOf = { id: String ->
                when (id) {
                    "alice" -> aliceMaterial
                    "bob" -> bobMaterial
                    else -> null
                }
            }
            session.placedNodes.forEach { placed ->
                val material = materialOf(placed.ownerPeerId)
                if (material != null) key(placed.nodeKey, placed.modelKey) {
                    val t = placed.translation
                    val q = placed.quaternion
                    val s = placed.scale
                    Node(position = Position(t[0], t[1], t[2]),
                        rotation = Quaternion(q[0], q[1], q[2], q[3]).toEulerAngles(),
                        scale = Scale(s[0], s[1], s[2])) {
                        // Peer-supplied model keys are an allow-list, never asset paths.
                        when (placed.modelKey) {
                            "cube" -> CubeNode(size = Size(OBJECT_SIZE), materialInstance = material)
                            "sphere" -> SphereNode(radius = OBJECT_SIZE / 2f, materialInstance = material)
                            "cylinder" -> CylinderNode(radius = OBJECT_SIZE / 2f,
                                height = OBJECT_SIZE, materialInstance = material)
                            else -> Unit
                        }
                    }
                }
            }
            session.participants.forEach { participant ->
                val t = participant.translation
                val q = participant.quaternion
                val material = materialOf(participant.id)
                if (t != null && q != null && material != null) key(participant.id) {
                    Node(position = Position(t[0], t[1], t[2]),
                        rotation = Quaternion(q[0], q[1], q[2], q[3]).toEulerAngles()) {
                        // Camera forward is -Z; a cone's apex is +Y. Rotate it into -Z.
                        ConeNode(radius = MARKER_RADIUS, height = MARKER_HEIGHT,
                            rotation = Rotation(x = -90f), materialInstance = material)
                    }
                }
            }
        }
        PeerTag(name, index, session, peerId,
            Modifier.align(Alignment.TopStart)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                .padding(top = chromePadding.calculateTopPadding())
                .padding(SceneViewTokens.Space.md))
    }
}

@Composable
private fun PeerTag(
    name: String, index: Int, session: CollaborativeSession, peerId: String, modifier: Modifier,
) {
    val pulse = remember(session) { Animatable(0f) }
    LaunchedEffect(session, peerId) {
        // The peer's camera pose arrives ten times a second: pulse on its objects only.
        snapshotFlow { session.placedNodes.filter { it.ownerPeerId != peerId } }
            .drop(1).collectLatest {
                pulse.snapTo(1f)
                pulse.animateTo(0f, SceneViewTokens.Motion.fade())
            }
    }
    GlassPill(modifier.border(SceneViewTokens.Layout.selectedOutlineWidth,
        LocalStageChrome.current.accent.copy(alpha = pulse.value),
        RoundedCornerShape(SceneViewTokens.Radius.full))) {
        Box(Modifier.size(SceneViewTokens.Space.sm).background(SceneViewColors.Ramp4[index], CircleShape))
        Spacer(Modifier.width(SceneViewTokens.Space.sm))
        Text(name, style = SceneViewTokens.Type.caption, color = LocalStageChrome.current.onGlass)
    }
}

private const val OBJECTS_PER_PEER = 4
private const val OBJECT_SIZE = 0.28f
private const val FLOOR_RADIUS = 1.2f
private const val FLOOR_SIDES = 72
private const val MARKER_RADIUS = 0.2f
private const val MARKER_HEIGHT = 0.5f
private const val ORBIT_HEIGHT = 1f
private const val STRIP_ORBIT_HEIGHT = 0.6f
private const val WIDE_FOCAL_LENGTH_MM = 20.0
private const val STRIP_FOCAL_LENGTH_MM = 45.0

package io.github.sceneview.demo.demos

import android.view.MotionEvent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.github.sceneview.SceneView
import io.github.sceneview.components.PRIORITY_DEFAULT
import io.github.sceneview.components.PRIORITY_LAST
import io.github.sceneview.demo.DemoPreviewPlaceholder
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.DemoSettings
import io.github.sceneview.demo.DockItem
import io.github.sceneview.demo.R
import io.github.sceneview.demo.SceneViewColors
import io.github.sceneview.demo.common.StageSkyFog
import io.github.sceneview.demo.common.rememberModelDemoEnvironment
import io.github.sceneview.demo.common.rememberStageSkybox
import io.github.sceneview.demo.common.themedStageSky
import io.github.sceneview.demo.demos.internal.CalloutLayout
import io.github.sceneview.demo.demos.internal.RocketPart
import io.github.sceneview.demo.rememberFirstFrameState
import io.github.sceneview.demo.demoSceneFrame
import io.github.sceneview.demo.theme.SceneViewDemoTheme
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Scale
import io.github.sceneview.math.Size
import io.github.sceneview.node.ViewNode
import io.github.sceneview.rememberCameraManipulator
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberCollisionSystem
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberOnGestureListener
import io.github.sceneview.rememberRenderInvalidator
import io.github.sceneview.rememberView
import io.github.sceneview.rememberViewNodeManager
import io.github.sceneview.sample.LifecycleAwareLaunchedEffect
import io.github.sceneview.sample.rememberMaterialInstance

/**
 * Inspect: tap a rocket part, then restyle it with live Compose swatches on an attached quad.
 * Procedural parts are deliberate: ModelNode has one model-wide collider and HitResult carries
 * no primitive index, so the public API cannot pick individual glTF parts today.
 *
 * ViewNode requires its WindowManager on SceneView; its separate composition inherits no locals,
 * so re-apply the theme inside. A manager sizes every child to its largest: keep a fixed card box.
 * The card is lit and bloom is disabled to avoid the halo of an unlit white card. Rendering last
 * does not change picking order: the raw touch callback prioritises the visible on-top card.
 */
@Composable
fun TwoDInThreeDInspectDemo(onBack: () -> Unit) {
    val title = stringResource(R.string.demo_two_d_in_three_d_title)
    if (LocalInspectionMode.current) {
        DemoPreviewPlaceholder(title = title, onBack = onBack)
        return
    }
    val layout = CalloutLayout
    var selected by remember { mutableStateOf<RocketPart?>(null) }
    var hasSelected by remember { mutableStateOf(false) }
    val originals = remember { RocketPart.entries.associateWith { it.originalMaterial } }
    var choices by remember { mutableStateOf(originals) }
    var alwaysOnTop by remember { mutableStateOf(true) }
    var spinning by remember { mutableStateOf(false) }
    var yaw by remember { mutableFloatStateOf(0f) }
    var anchor by remember { mutableStateOf(Position()) }
    var pulseRequest by remember { mutableIntStateOf(0) }
    val pulse = remember { Animatable(1f) }
    LaunchedEffect(pulseRequest) {
        pulse.snapTo(1f)
        if (pulseRequest > 0) {
            pulse.animateTo(layout.PULSE_SCALE, tween(SceneViewTokens.Duration.shortMillis))
            pulse.animateTo(1f, SceneViewTokens.Motion.spring())
        }
    }
    LifecycleAwareLaunchedEffect(spinning, DemoSettings.qaMode) {
        if (!spinning || DemoSettings.qaMode) return@LifecycleAwareLaunchedEffect
        var previous = 0L
        while (true) withFrameNanos { now ->
            if (previous != 0L) yaw = layout.nextTurntableYaw(yaw, now - previous)
            previous = now
        }
    }
    val engine = rememberEngine()
    val view = rememberView(engine)
    val materials = rememberMaterialLoader(engine)
    val environments = rememberEnvironmentLoader(engine)
    val camera = rememberCameraNode(engine)
    val collisions = rememberCollisionSystem(view)
    val manager = rememberViewNodeManager()
    val invalidator = rememberRenderInvalidator()
    val firstFrame = rememberFirstFrameState(engine)
    val sky = themedStageSky()
    val skybox = rememberStageSkybox(engine, sky, invalidator::requestRender)
    StageSkyFog(view, sky, invalidator::requestRender)
    LaunchedEffect(view) {
        view.bloomOptions = view.bloomOptions.apply { enabled = false }
        invalidator.requestRender()
    }
    val studio = rememberModelDemoEnvironment(environments, firstFrame)
    val environment = remember(studio, skybox) { studio.copy(skybox = skybox) }
    val floor = rememberMaterialInstance(materials, sky.floor, 0f, 0.62f)
    val swatchColors = listOf(SceneViewColors.SurfaceLight, SceneViewColors.Primary,
        SceneViewColors.TintLight, SceneViewColors.Highlight)
    val finishes = listOf(
        rememberMaterialInstance(materials, swatchColors[0], 0f, 0.85f, 0.25f),
        rememberMaterialInstance(materials, swatchColors[1], 0f, 0.12f, 0.65f),
        rememberMaterialInstance(materials, swatchColors[2], 1f, 0.42f, 0.5f),
        rememberMaterialInstance(materials, swatchColors[3], 1f, 0.18f, 0.5f),
    )
    var eye by remember { mutableStateOf(Position()) }
    var cardNode by remember { mutableStateOf<ViewNode?>(null) }
    val touchCapture = remember { arrayOfNulls<ViewNode>(1) }
    LaunchedEffect(cardNode, alwaysOnTop) {
        cardNode?.let {
            it.materialInstance.setDepthCulling(!alwaysOnTop)
            it.setPriority(if (alwaysOnTop) PRIORITY_LAST else PRIORITY_DEFAULT)
            it.requestRender()
        }
    }
    LaunchedEffect(cardNode, selected) { cardNode?.isHittable = selected != null }
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val cardWidth = SceneViewTokens.Layout.touchTarget * 4 + SceneViewTokens.Space.sm * 3 +
        SceneViewTokens.Space.md * 2
    val cardHeight = SceneViewTokens.Space.x4l + SceneViewTokens.Space.md
    val cardScale = with(LocalDensity.current) {
        layout.CARD_WIDTH_METERS * layout.VIEW_PIXELS_PER_UNIT / cardWidth.toPx()
    }
    val names = mapOf(RocketPart.Nose to R.string.demo_two_d_in_three_d_nose,
        RocketPart.Body to R.string.demo_two_d_in_three_d_body,
        RocketPart.Window to R.string.demo_two_d_in_three_d_window,
        RocketPart.Fins to R.string.demo_two_d_in_three_d_fins,
        RocketPart.Engine to R.string.demo_two_d_in_three_d_engine)
    val finishNames = listOf(R.string.demo_two_d_in_three_d_matte, R.string.demo_two_d_in_three_d_gloss,
        R.string.demo_two_d_in_three_d_metal, R.string.demo_two_d_in_three_d_gold)

    DemoScaffold(
        title = title, onBack = onBack, themedStage = true,
        firstFrameRendered = firstFrame.rendered, sceneReady = firstFrame.sceneReady,
        peekHeader = if (hasSelected) null else stringResource(R.string.demo_two_d_in_three_d_inspect_hint),
        dock = listOf(
            DockItem(Icons.Filled.RotateRight, stringResource(R.string.demo_two_d_in_three_d_spin),
                { spinning = !spinning }, selected = spinning, enabled = !DemoSettings.qaMode),
            DockItem(Icons.Filled.Refresh, stringResource(R.string.demo_two_d_in_three_d_reset),
                { choices = originals }, enabled = choices != originals),
        ),
        controls = {
            Text(stringResource(R.string.demo_two_d_in_three_d_inspect_explainer),
                style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.fillMaxWidth().padding(top = SceneViewTokens.Space.md)
                .toggleable(alwaysOnTop, onValueChange = { alwaysOnTop = it }),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.demo_two_d_in_three_d_always_on_top))
                Switch(alwaysOnTop, onCheckedChange = null)
            }
        },
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val sceneFrame = demoSceneFrame()
            val home = remember(sceneFrame.restingAspect, DemoSettings.cameraDistance) {
                val distance = DemoSettings.cameraDistance ?: layout.cameraDistance(
                    layout.INSPECT_EXTENT, sceneFrame.restingAspect,
                )
                layout.cameraHome(distance) + layout.INSPECT_TARGET
            }
            SceneView(
                contentPadding = sceneFrame.contentPadding,
                modifier = Modifier.fillMaxSize(), engine = engine, view = view,
                materialLoader = materials, environmentLoader = environments, environment = environment,
                cameraNode = camera, collisionSystem = collisions, viewNodeWindowManager = manager,
                renderInvalidator = invalidator, autoCenterContent = false,
                cameraManipulator = rememberCameraManipulator(home, layout.INSPECT_TARGET),
                onFrame = { nanos ->
                    firstFrame.onFrame(nanos)
                    if (layout.movedPerceptibly(eye, camera.worldPosition)) eye = camera.worldPosition
                },
                onTouchEvent = { event, nearest ->
                    // Depth priority is visual only. Pick the on-top card before geometry behind it,
                    // then keep its whole stream, including padding and an UP outside its collider.
                    val cardHit = if (alwaysOnTop && selected != null) {
                        collisions.hitTest(event).firstOrNull { it.node === cardNode }
                    } else nearest?.takeIf { it.node === cardNode }
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                        touchCapture[0]?.onCapturedTouchEvent(event)
                        touchCapture[0] = cardHit?.node as? ViewNode
                    }
                    val captured = touchCapture[0]
                    if (captured != null) {
                        if (cardHit != null) captured.onTouchEvent(event, cardHit)
                        else captured.onCapturedTouchEvent(event)
                        if (event.actionMasked == MotionEvent.ACTION_UP ||
                            event.actionMasked == MotionEvent.ACTION_CANCEL) touchCapture[0] = null
                        true
                    } else false
                },
                onGestureListener = rememberOnGestureListener(onSingleTapConfirmed = { _, node ->
                    if (node !is ViewNode) {
                        val part = RocketPart.entries.firstOrNull { it.name == node?.name }
                        selected = part
                        if (part != null) {
                            hasSelected = true
                            anchor = layout.cardAnchor(part, eye, yaw)
                            pulseRequest++
                        }
                    }
                }),
            ) {
                PlaneNode(size = Size(layout.FLOOR_SIZE, layout.FLOOR_SIZE, 0f), rotation = Rotation(x = -90f),
                    materialInstance = floor, apply = { isHittable = false })
                Node(rotation = Rotation(y = yaw)) {
                    RocketPart.entries.forEach { part -> key(part) {
                        val material = finishes[choices.getValue(part)]
                        val scale = Scale(if (part == selected) pulse.value else 1f)
                        val position = layout.partPosition(part)
                        when (part) {
                            RocketPart.Nose -> ConeNode(radius = layout.BODY_RADIUS, height = layout.NOSE_HEIGHT,
                                position = position, scale = scale, materialInstance = material,
                                apply = { name = part.name })
                            RocketPart.Body -> CylinderNode(radius = layout.BODY_RADIUS, height = layout.BODY_HEIGHT,
                                position = position, scale = scale, materialInstance = material,
                                apply = { name = part.name })
                            RocketPart.Window -> SphereNode(radius = layout.WINDOW_RADIUS, position = position,
                                scale = scale * layout.WINDOW_SCALE, materialInstance = material,
                                apply = { name = part.name })
                            RocketPart.Fins -> layout.FIN_YAWS.forEach { finYaw -> key(finYaw) {
                                CubeNode(size = layout.FIN_SIZE, position = layout.finPosition(finYaw),
                                    rotation = Rotation(y = finYaw), scale = scale, materialInstance = material,
                                    apply = { name = part.name })
                            } }
                            RocketPart.Engine -> CylinderNode(radius = layout.ENGINE_RADIUS, height = layout.ENGINE_HEIGHT,
                                position = position, scale = scale, materialInstance = material,
                                apply = { name = part.name })
                        }
                    } }
                    // Keep the same quad alive while selection moves; an invisible card cannot pick.
                    ViewNode(windowManager = manager, unlit = false, position = anchor,
                        rotation = Rotation(y = layout.billboardYawDegrees(layout.rotateY(anchor, yaw), eye, yaw)),
                        scale = Scale(cardScale), isVisible = selected != null,
                        apply = { cardNode = this; isHittable = false; isTouchForwardingEnabled = true },
                    ) {
                        SceneViewDemoTheme(darkTheme = dark) {
                            Card(Modifier.size(cardWidth, cardHeight), shape = MaterialTheme.shapes.large) {
                                Column(Modifier.fillMaxSize().padding(SceneViewTokens.Space.md),
                                    verticalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.sm)) {
                                    Text(selected?.let { stringResource(names.getValue(it)) }.orEmpty(),
                                        style = MaterialTheme.typography.titleMedium)
                                    Row(horizontalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.sm)) {
                                        finishNames.forEachIndexed { index, labelRes ->
                                            val label = stringResource(labelRes)
                                            val chosen = selected?.let { choices[it] == index } == true
                                            Surface(selected = chosen, onClick = {
                                                selected?.let { choices = choices + (it to index) }
                                            }, modifier = Modifier.size(SceneViewTokens.Layout.touchTarget)
                                                .semantics { contentDescription = label },
                                                shape = CircleShape, color = swatchColors[index],
                                                border = BorderStroke(SceneViewTokens.Space.xs / 2,
                                                    if (chosen) MaterialTheme.colorScheme.onSurface
                                                    else MaterialTheme.colorScheme.outlineVariant)) {
                                                Box(contentAlignment = Alignment.Center) {
                                                    if (chosen) Icon(Icons.Filled.Check, null, tint =
                                                        if (swatchColors[index].luminance() > 0.5f)
                                                            SceneViewColors.SurfaceDim else SceneViewColors.SurfaceLight)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

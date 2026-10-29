package io.github.sceneview.demo.demos

import android.view.MotionEvent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.graphics.toArgb
import io.github.sceneview.demo.demos.internal.StreamPhase
import io.github.sceneview.demo.demos.internal.fitInside
import io.github.sceneview.demo.demos.internal.videoTransport
import io.github.sceneview.demo.theme.themedStageChrome
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.unit.LayoutDirection
import io.github.sceneview.SceneView
import io.github.sceneview.components.PRIORITY_DEFAULT
import io.github.sceneview.components.PRIORITY_LAST
import io.github.sceneview.createDefaultCameraManipulator
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
import io.github.sceneview.demo.isDemoCompactHeight
import io.github.sceneview.demo.rememberFirstFrameState
import io.github.sceneview.demo.demoSceneFrame
import io.github.sceneview.demo.theme.SceneViewDemoTheme
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Scale
import io.github.sceneview.math.Size
import io.github.sceneview.node.ViewNode
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
 * 2D in 3D, one showcase (#4624). It used to be two tabs, Inspect and Media, and the second one
 * went unnoticed three times out of four: everything now stands in one scene. A procedural rocket
 * in front — tap a part, then restyle it from live Compose UI on an attached [ViewNode] card —
 * and behind it a wall: a framed drawable ([ImageNode]), a muted streamed video ([VideoNode]),
 * a floating badge ([BillboardNode]), each surface named by a [TextNode].
 *
 * Procedural parts are deliberate: ModelNode has one model-wide collider and HitResult carries
 * no primitive index, so the public API cannot pick individual glTF parts today.
 *
 * ViewNode requires its WindowManager on SceneView; its separate composition inherits no locals,
 * so re-apply the theme inside. A manager sizes every child to its largest: keep a fixed card box.
 * The card is unlit so its swatches show their authored token colours (#4595 follow-up): a lit
 * card takes the scene light and displayed amber as ochre. Bloom stays disabled so the unlit
 * white card has no halo. Rendering last does not change picking order: the raw touch callback
 * prioritises the visible on-top card.
 *
 * TextNode and BillboardNode face the camera only with a cameraPositionProvider. Clearing it leaves
 * a node as it was last turned, so those nodes are keyed on Face camera: rebuilt, they hang at
 * their resting yaw again. VideoNode(player) needs a prepared MediaPlayer:
 * `rememberMediaPlayer` prepares the URL off the main thread ([rememberStreamedVideo]), and until
 * it has, or when it cannot, the screen says so on a still. It never plays a stand-in.
 * QA seeks the attached video to one second while paused; a decoder that cannot finish that seek
 * falls back to muted playback after a bounded wait, so the rest of the scene remains usable.
 */
@Composable
fun TwoDInThreeDDemo(onBack: () -> Unit) {
    val title = stringResource(R.string.demo_two_d_in_three_d_title)
    if (LocalInspectionMode.current) {
        DemoPreviewPlaceholder(title = title, onBack = onBack)
        return
    }
    val layout = CalloutLayout
    val qa = DemoSettings.qaMode
    // A phone held sideways: the strip above the controls is too thin to give a line to a hint,
    // and the wall stands either side of the rocket there.
    val strip = isDemoCompactHeight()
    val showcase = layout.showcase(strip)
    val target = layout.showcaseTarget(strip)
    var selected by remember { mutableStateOf<RocketPart?>(null) }
    var hasSelected by remember { mutableStateOf(false) }
    val originals = remember { RocketPart.entries.associateWith { it.originalMaterial } }
    var choices by remember { mutableStateOf(originals) }
    var alwaysOnTop by remember { mutableStateOf(true) }
    var faceCamera by remember { mutableStateOf(true) }
    var spinning by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(!qa) }
    var yaw by remember { mutableFloatStateOf(0f) }
    var anchor by remember { mutableStateOf(Position()) }
    // Where the card was turned when its part was picked: what it keeps with Face camera off.
    var resting by remember { mutableStateOf(Rotation()) }
    var pulseRequest by remember { mutableIntStateOf(0) }
    val pulse = remember { Animatable(1f) }
    LaunchedEffect(pulseRequest) {
        pulse.snapTo(1f)
        if (pulseRequest > 0) {
            pulse.animateTo(layout.PULSE_SCALE, tween(SceneViewTokens.Duration.shortMillis))
            pulse.animateTo(1f, SceneViewTokens.Motion.spring())
        }
    }
    LifecycleAwareLaunchedEffect(spinning, qa) {
        if (!spinning || qa) return@LifecycleAwareLaunchedEffect
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
    val chrome = themedStageChrome()
    val skybox = rememberStageSkybox(engine, sky, invalidator::requestRender)
    StageSkyFog(view, sky, invalidator::requestRender)
    LaunchedEffect(view) {
        view.bloomOptions = view.bloomOptions.apply { enabled = false }
        invalidator.requestRender()
    }
    val studio = rememberModelDemoEnvironment(environments, firstFrame)
    val environment = remember(studio, skybox) { studio.copy(skybox = skybox) }
    val floor = rememberMaterialInstance(materials, sky.floor, 0f, 0.62f)
    val frame = rememberMaterialInstance(materials, SceneViewColors.SurfaceDim, 0.2f, 0.45f)
    val swatchColors = listOf(SceneViewColors.SurfaceLight, SceneViewColors.Primary,
        SceneViewColors.TintLight, SceneViewColors.Highlight)
    val finishes = listOf(
        rememberMaterialInstance(materials, swatchColors[0], 0f, 0.85f, 0.25f),
        rememberMaterialInstance(materials, swatchColors[1], 0f, 0.12f, 0.65f),
        rememberMaterialInstance(materials, swatchColors[2], 1f, 0.42f, 0.5f),
        rememberMaterialInstance(materials, swatchColors[3], 1f, 0.18f, 0.5f),
    )

    // ── The wall: picture, video, badge and their captions ──────────────────────────────────
    var eye by remember { mutableStateOf(Position()) }
    val badge = rememberGallerySprite()
    val screenAspect = layout.SCREEN_SIZE.x / layout.SCREEN_SIZE.y
    val loadingStill = rememberVideoStatusStill(
        stringResource(R.string.demo_two_d_in_three_d_video_loading), null, screenAspect)
    val unavailableStill = rememberVideoStatusStill(
        stringResource(R.string.demo_two_d_in_three_d_video_unavailable),
        stringResource(R.string.demo_two_d_in_three_d_video_unavailable_detail), screenAspect)
    val captions = listOf(R.string.demo_two_d_in_three_d_image_node, R.string.demo_two_d_in_three_d_video_node)
    // TextNode's bitmap is 512 x 128 px: at 2.25 x the large headline, the longest caption fills
    // about three quarters of it in monospace, readable from where the camera stands.
    val fontSize = MaterialTheme.typography.headlineLarge.fontSize.value * 2.25f
    val video = rememberStreamedVideo(MEDIA_VIDEO_URL)
    val player = video.player
    val videoFailed = video.phase == StreamPhase.Failed
    var attached by remember { mutableStateOf(false) }
    var seekFinished by remember { mutableStateOf(!qa) }
    var seekFallback by remember { mutableStateOf(false) }
    // A video paused before it ever played has no picture yet: one seek gives it its first frame.
    val pictured = remember { booleanArrayOf(false) }
    LaunchedEffect(player, attached) {
        val seekable = attached && qa && video.phase == StreamPhase.Ready
        if (player != null && seekable) {
            player.setOnSeekCompleteListener { seekFinished = true; invalidator.requestRender() }
            // A seek that throws has nothing to wait for: the fallback plays at once, so the
            // scene is never announced ready over a screen that has no picture coming.
            val refused = runCatching { player.seekTo(QA_FRAME_MILLIS) }.isFailure
            if (!refused) delay(SEEK_TIMEOUT_MILLIS)
            val failed = video.phase == StreamPhase.Failed
            if ((refused || !seekFinished) && !failed) seekFallback = true
            if (seekFallback && !failed) playing = true
            player.setOnSeekCompleteListener(null)
        }
    }
    LifecycleAwareLaunchedEffect(player, attached, playing, videoFailed) {
        if (player == null || !attached || videoFailed) return@LifecycleAwareLaunchedEffect
        try {
            runCatching {
                if (playing) player.start()
                else if (player.isPlaying) player.pause()
                else if (!qa && !pictured[0]) player.seekTo(0)
                pictured[0] = true
            }.onFailure { video.fail() }
            invalidator.requestRender()
            awaitCancellation()
        } finally {
            runCatching { if (player.isPlaying) player.pause() }
        }
    }
    // The scene never waits for the network: the screen shows where the stream stands. QA does
    // wait, for its paused seek; a stream that fails, a preparation or a decoder that times out
    // must never leave the loading cover up.
    val rendered = remember(firstFrame, video) { derivedStateOf {
        firstFrame.rendered.value &&
            (!qa || video.phase == StreamPhase.Failed || seekFinished || seekFallback)
    } }
    val ready = remember(firstFrame, rendered) { derivedStateOf {
        rendered.value && firstFrame.sceneReady.value
    } }
    // `playing` is the request; a failed stream leaves it true with nothing playing on screen.
    val transport = videoTransport(playing, videoFailed, qa, seekFallback)

    // ── The card ────────────────────────────────────────────────────────────────────────────
    var cardNode by remember { mutableStateOf<ViewNode?>(null) }
    // What the card faces, read on every frame; the node skips the frames where nothing moved.
    // Not the eye itself — `{ camera.worldPosition }` — which turns a card this far off the
    // axis into a parallelogram. A point as far from the card as the eye is from the orbit
    // target, the same way: the card stays parallel to the screen, a rectangle.
    val cameraPosition = remember(camera, target) { {
        (cardNode?.worldPosition ?: target) + (camera.worldPosition - target)
    } }
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
    // Two rows of two full-size touch targets under the part's name: narrow enough to stand
    // beside the rocket on a portrait phone.
    val density = LocalDensity.current
    val cardPadding = SceneViewTokens.Space.md - SceneViewTokens.Space.xs
    val cardWidth = SceneViewTokens.Layout.touchTarget * 2 + SceneViewTokens.Space.sm + cardPadding * 2
    val cardHeight = with(density) { MaterialTheme.typography.titleMedium.lineHeight.toDp() } +
        SceneViewTokens.Layout.touchTarget * 2 + SceneViewTokens.Space.sm * 2 + cardPadding * 2
    val names = mapOf(RocketPart.Nose to R.string.demo_two_d_in_three_d_nose,
        RocketPart.Body to R.string.demo_two_d_in_three_d_body,
        RocketPart.Window to R.string.demo_two_d_in_three_d_window,
        RocketPart.Fins to R.string.demo_two_d_in_three_d_fins,
        RocketPart.Engine to R.string.demo_two_d_in_three_d_engine)
    val finishNames = listOf(R.string.demo_two_d_in_three_d_matte, R.string.demo_two_d_in_three_d_gloss,
        R.string.demo_two_d_in_three_d_metal, R.string.demo_two_d_in_three_d_gold)

    DemoScaffold(
        title = title, onBack = onBack, themedStage = true,
        firstFrameRendered = rendered, sceneReady = ready,
        // A phone held sideways leaves a strip above the controls: no line of it goes to a hint.
        peekHeader = if (hasSelected || strip) null
            else stringResource(R.string.demo_two_d_in_three_d_inspect_hint),
        dock = listOf(
            DockItem(if (transport.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                stringResource(if (transport.playing) R.string.demo_two_d_in_three_d_pause
                    else R.string.demo_two_d_in_three_d_play),
                { playing = !playing }, enabled = transport.enabled, selected = transport.playing),
            DockItem(Icons.Filled.RotateRight, stringResource(R.string.demo_two_d_in_three_d_spin),
                { spinning = !spinning }, selected = spinning, enabled = !qa),
            DockItem(Icons.Filled.Refresh, stringResource(R.string.demo_two_d_in_three_d_reset),
                { choices = originals }, enabled = choices != originals),
        ),
        controls = {
            Text(stringResource(R.string.demo_two_d_in_three_d_explainer),
                style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.fillMaxWidth().padding(top = SceneViewTokens.Space.md)
                .toggleable(faceCamera, onValueChange = { faceCamera = it }),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.demo_two_d_in_three_d_face_camera))
                Switch(faceCamera, onCheckedChange = null)
            }
            Row(Modifier.fillMaxWidth().padding(top = SceneViewTokens.Space.sm)
                .toggleable(alwaysOnTop, onValueChange = { alwaysOnTop = it }),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.demo_two_d_in_three_d_always_on_top))
                Switch(alwaysOnTop, onCheckedChange = null)
            }
            // CC BY asks for the title, the author and the licence wherever the work is shown.
            Text(stringResource(R.string.demo_two_d_in_three_d_video_credit),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = SceneViewTokens.Space.sm))
        },
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // The hint leaving frees a band under the scene. Keep the frame made with it: a new
            // home rebuilds the manipulator, so the tap that picks a part would undo the user's
            // orbit, and a new padding would slide the rocket from under the finger.
            val liveFrame = demoSceneFrame()
            val heldFrame = remember(maxWidth, maxHeight) { arrayOf(liveFrame) }
            if (!hasSelected) heldFrame[0] = liveFrame
            val sceneFrame = heldFrame[0]
            val aspect = sceneFrame.restingAspect
            val freeWidth = maxWidth - sceneFrame.contentPadding.calculateLeftPadding(LayoutDirection.Ltr) -
                sceneFrame.contentPadding.calculateRightPadding(LayoutDirection.Ltr)
            // Bounded once, here: the eye and the card's scale then share the distance really applied.
            // The whole showcase fits, and the card still finds room beside the rocket.
            val distance = remember(aspect, freeWidth, strip, DemoSettings.cameraDistance) {
                layout.homeDistance(DemoSettings.cameraDistance ?: maxOf(
                    layout.inspectDistance(aspect, freeWidth.value, cardWidth.value, SceneViewTokens.Space.md.value),
                    layout.cameraDistance(layout.showcaseExtent(strip), aspect)))
            }
            val home = remember(distance, target) { layout.cameraHome(distance) + target }
            // One dp of card is one dp of screen at the home distance, in either orientation.
            val metersPerDp = layout.metersPerDp(distance, freeWidth.value / aspect)
            val cardMeters by rememberUpdatedState(
                Size(cardWidth.value * metersPerDp, cardHeight.value * metersPerDp, 0f))
            val cardScale = metersPerDp * layout.VIEW_PIXELS_PER_UNIT / density.density
            // Keyed on the home: the eye-position `rememberCameraManipulator` builds once and keeps
            // the frame of the first composition, made before the sheet has reported its height.
            val manipulator = remember(home) {
                createDefaultCameraManipulator(eyePosition = home, targetPosition = target)
            }
            SceneView(
                contentPadding = sceneFrame.contentPadding,
                modifier = Modifier.fillMaxSize(), engine = engine, view = view,
                materialLoader = materials, environmentLoader = environments, environment = environment,
                cameraNode = camera, collisionSystem = collisions, viewNodeWindowManager = manager,
                renderInvalidator = invalidator, autoCenterContent = false,
                cameraManipulator = manipulator,
                onFrame = { nanos ->
                    firstFrame.onFrame(nanos)
                    val current = camera.worldPosition
                    if (layout.movedPerceptibly(eye, current)) eye = current
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
                            val eyeNow = camera.worldPosition
                            anchor = layout.cardAnchor(part, eyeNow, yaw, cardMeters)
                            resting = Rotation(x = layout.billboardPitchDegrees(target, eyeNow),
                                y = layout.billboardYawDegrees(Position(), eyeNow, yaw))
                            pulseRequest++
                        }
                    }
                }),
            ) {
                PlaneNode(size = Size(layout.FLOOR_SIZE, layout.FLOOR_SIZE, 0f), rotation = Rotation(x = -90f),
                    materialInstance = floor, apply = { isHittable = false })
                // The wall. Fixed surfaces: orbit and they go edge-on, as a picture on a wall does.
                showcase.take(2).forEachIndexed { index, exhibit -> key(index) {
                    Node(position = exhibit.position, rotation = Rotation(y = exhibit.yaw),
                        apply = { isHittable = false }) {
                        CubeNode(size = layout.frameSize(exhibit.size), materialInstance = frame,
                            apply = { isHittable = false })
                        if (index == 0) {
                            ImageNode(imageResId = R.drawable.preview_geometry_light,
                                size = exhibit.size, position = layout.CONTENT_OFFSET,
                                apply = { isHittable = false })
                        } else if (player != null && video.phase == StreamPhase.Ready) {
                            // Construction-only size: the picture fits the screen, never stretched.
                            VideoNode(player = player, position = layout.CONTENT_OFFSET,
                                size = fitInside(exhibit.size, player.videoWidth, player.videoHeight),
                                apply = { attached = true; isHittable = false })
                        } else {
                            // Not there yet, or not coming: the screen says which.
                            ImageNode(bitmap = if (videoFailed) unavailableStill else loadingStill,
                                size = exhibit.size, position = layout.CONTENT_OFFSET,
                                apply = { isHittable = false })
                        }
                    }
                } }
                // Only these nodes are recreated: a cleared provider would leave them as last turned.
                key(faceCamera) {
                    val provider: (() -> Position)? = if (faceCamera) ({ eye }) else null
                    val badgeSpot = showcase[2]
                    BillboardNode(bitmap = badge, widthMeters = badgeSpot.size.x, heightMeters = badgeSpot.size.y,
                        position = badgeSpot.position, cameraPositionProvider = provider,
                        apply = { isHittable = false })
                    showcase.take(2).forEachIndexed { index, item -> key(index) {
                        TextNode(text = stringResource(captions[index]), fontSize = fontSize,
                            textColor = chrome.onCard.toArgb(), backgroundColor = chrome.card.toArgb(),
                            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD),
                            widthMeters = layout.SHOWCASE_CAPTION_SIZE.x,
                            heightMeters = layout.SHOWCASE_CAPTION_SIZE.y,
                            position = item.caption, cameraPositionProvider = provider,
                            apply = { rotation = Rotation(y = item.yaw); isHittable = false })
                    } }
                }
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
                                Node(rotation = Rotation(y = finYaw)) {
                                    CubeNode(size = layout.FIN_SIZE, position = layout.FIN_CENTER,
                                        rotation = Rotation(x = layout.FIN_TILT_DEGREES), scale = scale,
                                        materialInstance = material, apply = { name = part.name })
                                }
                            } }
                            RocketPart.Engine -> ConeNode(radius = layout.ENGINE_RADIUS, height = layout.ENGINE_HEIGHT,
                                position = position, scale = scale, materialInstance = material,
                                apply = { name = part.name })
                        }
                    } }
                    // Keep the same quad alive while selection moves; an invisible card cannot pick.
                    // Face camera on: `cameraPositionProvider` keeps the card square to the screen —
                    // orbit, or spin the rocket under it, and it follows. Off: `rotation` applies again,
                    // the angle it had when its part was picked, and the card turns away with the rocket.
                    ViewNode(windowManager = manager, unlit = true, position = anchor,
                        rotation = resting, scale = Scale(cardScale), isVisible = selected != null,
                        cameraPositionProvider = if (faceCamera) cameraPosition else null,
                        apply = { cardNode = this; isHittable = false; isTouchForwardingEnabled = true },
                    ) {
                        SceneViewDemoTheme(darkTheme = dark) {
                            Card(Modifier.size(cardWidth, cardHeight), shape = MaterialTheme.shapes.large) {
                                Column(Modifier.fillMaxSize().padding(cardPadding),
                                    verticalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.sm)) {
                                    Text(selected?.let { stringResource(names.getValue(it)) }.orEmpty(),
                                        style = MaterialTheme.typography.titleMedium, maxLines = 1)
                                    finishNames.indices.chunked(2).forEach { row ->
                                        Row(horizontalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.sm)) {
                                            row.forEach { index ->
                                                val label = stringResource(finishNames[index])
                                                val chosen = selected?.let { choices[it] == index } == true
                                                // `outline`, not its variant: the matte swatch is the
                                                // card's own white and must still read as a button.
                                                Surface(selected = chosen, onClick = {
                                                    selected?.let { choices = choices + (it to index) }
                                                }, modifier = Modifier.size(SceneViewTokens.Layout.touchTarget)
                                                    .semantics { contentDescription = label },
                                                    shape = CircleShape, color = swatchColors[index],
                                                    border = BorderStroke(SceneViewTokens.Space.xs / 2,
                                                        if (chosen) MaterialTheme.colorScheme.onSurface
                                                        else MaterialTheme.colorScheme.outline)) {
                                                    Box(contentAlignment = Alignment.Center) {
                                                        if (chosen) Icon(Icons.Filled.Check, null, tint =
                                                            if (swatchColors[index].luminance() > 0.5f)
                                                                SceneViewColors.SurfaceDim
                                                            else SceneViewColors.SurfaceLight)
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
}

/** A remembered Canvas sprite made entirely from the existing palette and sizing tokens. */
@Composable
private fun rememberGallerySprite(): Bitmap {
    val density = LocalDensity.current
    val size = with(density) { SceneViewTokens.Space.x4l.roundToPx() }
    val radius = with(density) { SceneViewTokens.Space.x3l.toPx() / 2f }
    val inset = with(density) { SceneViewTokens.Space.lg.toPx() }
    return remember(size, radius, inset) {
        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            val centerX = size / 2f
            val centerY = size / 2f
            paint.color = SceneViewColors.Primary.toArgb()
            canvas.drawCircle(centerX, centerY, radius, paint)
            canvas.rotate(45f, centerX, centerY)
            paint.color = SceneViewColors.Highlight.toArgb()
            canvas.drawRect(centerX - inset / 2, centerY - inset / 2,
                centerX + inset / 2, centerY + inset / 2, paint)
        }
    }
}

private const val QA_FRAME_MILLIS = 1_000
private const val SEEK_TIMEOUT_MILLIS = 3_000L

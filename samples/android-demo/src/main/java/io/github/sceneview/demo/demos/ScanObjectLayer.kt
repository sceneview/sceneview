package io.github.sceneview.demo.demos

import android.os.SystemClock
import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import com.google.android.filament.Colors
import com.google.android.filament.Engine
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import io.github.sceneview.demo.LocalDemoSceneCover
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.internal.ArDebugFraming
import io.github.sceneview.demo.demos.internal.ArDebugOrbitCamera
import io.github.sceneview.demo.demos.internal.ArDebugSession
import io.github.sceneview.demo.demos.internal.ArDebugStyle
import io.github.sceneview.demo.demos.internal.DebugGroup
import io.github.sceneview.demo.demos.internal.DebugMesh
import io.github.sceneview.demo.demos.internal.DenseSurfels
import io.github.sceneview.demo.demos.internal.OrbitPose
import io.github.sceneview.demo.demos.internal.ScanBox
import io.github.sceneview.demo.demos.internal.ScanCard
import io.github.sceneview.demo.demos.internal.ScanChrome
import io.github.sceneview.demo.demos.internal.ScanObjectMeshes
import io.github.sceneview.demo.demos.internal.ScanObjects
import io.github.sceneview.demo.demos.internal.ScanPick
import io.github.sceneview.demo.demos.internal.ScanReveal
import io.github.sceneview.demo.demos.internal.scanCamera
import io.github.sceneview.demo.theme.DebugPalette
import io.github.sceneview.demo.theme.LocalStageChrome
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.demo.theme.SceneViewTokens.Space
import io.github.sceneview.demo.ui.overMediaEdge
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.math.Position
import io.github.sceneview.math.colorOf
import io.github.sceneview.math.toLinearSpace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/*
 * Tap an object in a scan's replay (#4485): the points it is made of light up, a box is drawn
 * round them, and its three dimensions are written over it. A tap elsewhere picks another
 * object; a tap on the floor, on a wall or in the void clears it.
 *
 * The geometry is [ScanObjects] — plain Kotlin on the points the scan stored. This file is only
 * what the replay draws with the answer.
 */

private const val TAG = "ScanObject"

/**
 * The object picked in one replay, kept by the replay's screen: the view that draws it comes and
 * goes — the Camera view takes its place, the theme changes — and finds it again (#4505).
 */
internal class ScanSelection {
    var found: ScanPick.Found? = null
}

/**
 * The object picked in one replay: three layers of the replay's scene — the tint over the
 * object's surfels, and its box drawn twice, solid where it is in view and faint where the room
 * hides it — and what [ScanObjectOverlay] writes over them.
 *
 * Everything here runs on the main thread except the two pieces of geometry, which run on
 * [Dispatchers.Default]: sorting the cloud, once, as soon as the replay opens, and each tap's
 * region.
 */
internal class ScanObjectPick(
    private val replay: RerunReplayMedia,
    private val dense: ReplayDenseLayer,
    private val session: ArDebugSession,
    private val orbit: ArDebugOrbitCamera,
    private val selection: ScanSelection,
    engine: Engine,
    private val materialLoader: MaterialLoader,
    palette: DebugPalette,
    private val scope: CoroutineScope,
) {
    // The selection wears the view's "present" colour, the live frustum's, in the two strengths
    // the palette already has: full for the box in view, the keyframes' faint one for the tint
    // and for the edges behind the room.
    private val tintMaterial = materialLoader.selectionMaterial(palette.keyframe, palette.frustumGlow).apply {
        // The tint lies on the very quads it lights: drawn a hair nearer, it never fights them.
        setPolygonOffset(TINT_OFFSET, TINT_OFFSET)
    }
    private val hiddenMaterial = materialLoader.selectionMaterial(palette.keyframe, 1f).apply {
        setDepthCulling(false)
    }
    private val edgeMaterial = materialLoader.selectionMaterial(palette.frustum, palette.frustumGlow)

    private val tintNode = DebugLayerNode(engine, tintMaterial, TINT_PRIORITY)
    private val hiddenNode = DebugLayerNode(engine, hiddenMaterial, HIDDEN_PRIORITY)
    private val edgeNode = DebugLayerNode(engine, edgeMaterial, EDGE_PRIORITY)

    /** Children of the replay's layer node, destroyed with it; [destroy] comes after. */
    val nodes: List<DebugLayerNode> = listOf(tintNode, hiddenNode, edgeNode)

    private val objects: Deferred<ScanObjects?> = scope.async(Dispatchers.Default) {
        runCatching { ScanObjects.of(dense.cloud, dense.voxelM) }
            .onFailure { Log.w(TAG, "The cloud could not be sorted: ${it.message}") }
            .getOrNull()
    }

    /** [ScanObjects.pick] keeps its scratch buffers: one tap at a time. */
    private val picking = Mutex()
    private var taps = 0
    private var hintJob: Job? = null

    private var found: ScanPick.Found? = null
    private var cornerPositions: List<Position> = emptyList()

    /** When the camera is slid to bring the box and its label out from under the chrome. */
    private val reveal = ScanReveal()

    /** What the replay draws over this view, as [ScanObjectOverlay] lays it out. */
    var chrome: ScanChrome? = null

    /** How tall the dimensions' label is, in pixels, once it has been laid out. */
    var labelHeight = 0

    private var edgesFor: ArDebugStyle? = null
    private val edgeMesh = DebugMesh()

    /** `true` while the picked object is drawn — and its label written, once it has a place. */
    var drawn: Boolean by mutableStateOf(false)
        private set

    /** The picked object's box, `null` with nothing picked. */
    var box: ScanBox? by mutableStateOf(null)
        private set

    /**
     * The highest point the box is drawn at in the view, in pixels, over the middle of its top;
     * unspecified while it is not drawn.
     */
    var anchor: Offset by mutableStateOf(Offset.Unspecified)
        private set

    /** The lowest point it is drawn at, under [anchor]: where the label goes with no room above. */
    var foot: Offset by mutableStateOf(Offset.Unspecified)
        private set

    /** Where a tap found something too large to be one object, until the hint has been read. */
    var tooLargeAt: Offset by mutableStateOf(Offset.Unspecified)
        private set

    init {
        nodes.forEach { it.isVisible = false }
        // Back from the Camera view: the object picked before it is still the one picked, and the
        // camera is where it was left — nothing to bring out.
        selection.found?.takeIf { it.surfels.isNotEmpty() && it.surfels.last() < dense.cloud.count }?.let {
            show(it, Offset.Unspecified)
            reveal.settled()
        }
    }

    /**
     * A finger down on the view: the turntable stops under it, so what a tap is aimed at is still
     * under the finger when the tap is confirmed, a double-tap's delay later.
     */
    fun down() = orbit.fingerDown()

    /** A single tap at ([x], [y]) of the view, in pixels: picks what is drawn under it. */
    fun tap(x: Float, y: Float) {
        orbit.hold()
        val tap = ++taps
        hintJob?.cancel()
        tooLargeAt = Offset.Unspecified
        val shown = shownSurfels()
        if (shown == 0) {
            show(ScanPick.Empty, Offset(x, y))
            return
        }
        val camera = orbit.scanCamera()
        val ray = camera.ray(x, y)
        scope.launch {
            val started = SystemClock.elapsedRealtimeNanos()
            val pick = withContext(Dispatchers.Default) {
                picking.withLock { objects.await()?.pick(camera.eye, ray, shown) }
            } ?: ScanPick.Empty
            // A later tap answers for this one.
            if (tap != taps) return@launch
            val millis = (SystemClock.elapsedRealtimeNanos() - started) / NANOS_PER_MILLI
            Log.d(TAG, "tap answered in $millis ms: ${describe(pick)}")
            show(pick, Offset(x, y))
        }
    }

    private fun show(pick: ScanPick, at: Offset) {
        found = pick as? ScanPick.Found
        selection.found = found
        if (found == null) reveal.settled() else reveal.picked()
        box = found?.box
        // The room does not turn away under a box being read, not even after a recenter.
        orbit.steady = found != null
        cornerPositions = found?.box?.corners().orEmpty().map { Position(it.x, it.y, it.z) }
        edgesFor = null
        tintNode.upload(found?.let { ScanObjectMeshes.surfels(dense.mesh, it.surfels) })
        if (pick == ScanPick.TapCloser) {
            tooLargeAt = at
            hintJob = scope.launch {
                delay(HINT_MILLIS)
                tooLargeAt = Offset.Unspecified
            }
        }
    }

    /**
     * Once a rendered frame: shows the object while every point of it is drawn — not with the
     * points hidden, nor scrubbed back to before the scan had found it —, keeps the box's lines
     * [style]'s constant width on screen, and follows its top for the label.
     */
    fun sync(style: ArDebugStyle) {
        val found = found
        // Said again here: the pick this one replaced let go of the turntable as it left.
        orbit.steady = found != null
        val visible = found != null && found.surfels.isNotEmpty() && found.surfels.last() < shownSurfels()
        if (visible != drawn) {
            drawn = visible
            nodes.forEach { it.isVisible = visible }
        }
        if (found == null) reveal.settled()
        if (found == null || !visible) {
            anchor = Offset.Unspecified
            foot = Offset.Unspecified
            return
        }
        if (style != edgesFor) {
            edgesFor = style
            ScanObjectMeshes.edges(found.box, style.axisRadius, edgeMesh)
            hiddenNode.upload(edgeMesh)
            edgeNode.upload(edgeMesh)
        }
        // The box and its label, out from under the cards: after the tap, and again each time a
        // finger has turned the room and let go of it.
        if (reveal.due(labelMeasured = labelHeight > 0, moving = orbit.moving)) orbit.glideTo(framed(orbit.pose))
        if (reveal.pending) {
            // Not written yet: the label is born where that slide ends, it does not jump there.
            anchor = Offset.Unspecified
            foot = Offset.Unspecified
        } else {
            follow(found.box)
        }
    }

    /**
     * [pose] moved as little as it takes for the picked box and its label to be clear of the
     * replay's cards ([ArDebugFraming.reveal]); [pose] itself with nothing picked.
     */
    fun framed(pose: OrbitPose): OrbitPose {
        val chrome = chrome ?: return pose
        if (found == null) return pose
        val free = chrome.free(orbit.viewportWidth, orbit.viewportHeight, labelHeight)
        return ArDebugFraming.reveal(pose, cornerPositions, orbit.verticalFovDegrees, orbit.aspect, free, orbit.lift)
    }

    /**
     * Where [box] is drawn: [anchor] over its highest corner, [foot] under its lowest — this frame,
     * or, while the camera slides to bring it out, where the slide ends: the label waits there.
     */
    private fun follow(box: ScanBox) {
        val span = ScanObjectMeshes.drawnSpan(box, orbit.scanCamera(orbit.heading))
        anchor = span?.let { (x, high, _) -> Offset(x, high) } ?: Offset.Unspecified
        foot = span?.let { (x, _, low) -> Offset(x, low) } ?: Offset.Unspecified
    }

    /** The surfels the dense layer is drawing now, the cloud's first ones; none with Points off. */
    private fun shownSurfels(): Int =
        if (session.isVisible(DebugGroup.Points)) {
            minOf(replay.pointCountAt(session.time), dense.cloud.count, DenseSurfels.MAX_SURFELS)
        } else {
            0
        }

    /** The material instances, once the nodes are gone; the [selection] stays with the replay. */
    fun destroy() {
        orbit.steady = false
        hintJob?.cancel()
        objects.cancel()
        listOf(tintMaterial, hiddenMaterial, edgeMaterial).forEach { materialLoader.destroyMaterialInstance(it) }
    }

    private fun describe(pick: ScanPick) = when (pick) {
        ScanPick.Empty -> "nothing"
        ScanPick.TapCloser -> "too large for one object"
        is ScanPick.Found -> "an object of ${pick.surfels.size} points"
    }

    private companion object {
        /** Over the dense cloud (3) and the room's measure (4); the box over its own tint. */
        const val TINT_PRIORITY = 5
        const val HIDDEN_PRIORITY = 6
        const val EDGE_PRIORITY = 6

        /** In `glPolygonOffset` units, towards the eye: Filament flips the sign for its reversed depth. */
        const val TINT_OFFSET = -1f
        const val HINT_MILLIS = 2600L
        const val NANOS_PER_MILLI = 1_000_000L
    }
}

/** An unlit layer in [color], [glow] times brighter in linear light so the dark stage's bloom lifts it. */
private fun MaterialLoader.selectionMaterial(color: Color, glow: Float): MaterialInstance =
    createUnlitColorInstance(color).apply {
        // The opaque unlit material culls back faces; the tubes are seen from every side.
        if (color.alpha >= 1f) setCullingMode(Material.CullingMode.NONE)
        if (glow != 1f) {
            val linear = colorOf(color).toLinearSpace()
            setParameter("color", Colors.RgbaType.LINEAR, linear.x * glow, linear.y * glow, linear.z * glow, linear.w)
        }
    }

/**
 * The picker of [replay]'s dense cloud, `null` for a replay without one, one still recording, or
 * none at all. Called before the `SceneView` it draws in, so its materials outlive its nodes.
 * [selection] is where the picked object is kept while this view is away.
 */
@Composable
internal fun rememberScanObjectPick(
    replay: RerunReplayMedia?,
    session: ArDebugSession,
    orbit: ArDebugOrbitCamera,
    engine: Engine,
    materialLoader: MaterialLoader,
    selection: ScanSelection? = null,
): ScanObjectPick? {
    val palette = LocalStageChrome.current.debug
    val scope = rememberCoroutineScope()
    val kept = selection ?: remember(replay) { ScanSelection() }
    val pick = remember(engine, materialLoader, replay, session, orbit, palette, kept) {
        val dense = replay?.dense?.takeUnless { replay.growing }
        if (replay != null && dense != null) {
            ScanObjectPick(replay, dense, session, orbit, kept, engine, materialLoader, palette, scope)
        } else {
            null
        }
    }
    DisposableEffect(pick) { onDispose { pick?.destroy() } }
    return pick
}

/**
 * What [pick] writes over the replay: the picked object's three dimensions over its box — under
 * it where the replay's cards leave no room —, or, where a tap found something no single box
 * holds, a word saying so. It takes no touch — the view under it keeps every gesture.
 *
 * [cornerCard] is the card the replay hangs in the top end corner of this view, as far as it
 * reaches in from that corner under the other cards — nothing is written under it either.
 */
@Composable
internal fun ScanObjectOverlay(pick: ScanObjectPick?, modifier: Modifier = Modifier, cornerCard: DpSize = DpSize.Zero) {
    if (pick == null) return
    val cover = LocalDemoSceneCover.current
    val density = LocalDensity.current
    val atLeft = LocalLayoutDirection.current == LayoutDirection.Rtl
    val chrome = remember(cover, density, cornerCard, atLeft) {
        with(density) {
            ScanChrome(
                top = cover.calculateTopPadding().roundToPx(),
                bottom = cover.calculateBottomPadding().roundToPx(),
                card = ScanCard(cornerCard.width.roundToPx(), cornerCard.height.roundToPx(), atLeft)
                    .takeIf { cornerCard != DpSize.Zero },
                margin = Space.md.roundToPx(),
                gap = Space.sm.roundToPx(),
            )
        }
    }
    SideEffect { pick.chrome = chrome }
    val box = pick.box
    // The last box and the last place stay through the fade out.
    var lastBox by remember(pick) { mutableStateOf(box) }
    if (box != null) lastBox = box
    val lastAnchor = remember(pick) { LastOffset() }
    val lastFoot = remember(pick) { LastOffset() }
    // Measured as soon as the object is drawn, placed only once the pick says where: the camera
    // may first slide to bring the box out, and the label is written where that ends.
    Anchored(
        above = { lastAnchor.of(pick.anchor, live = pick.drawn) },
        below = { lastFoot.of(pick.foot, live = pick.drawn) },
        chrome = chrome,
        modifier = modifier,
        onHeight = { pick.labelHeight = it },
    ) {
        AnimatedVisibility(
            visible = box != null && pick.drawn,
            enter = fadeIn(SceneViewTokens.Motion.fade()),
            exit = fadeOut(SceneViewTokens.Motion.fade()),
        ) {
            lastBox?.let { ObjectSizeLabel(it) }
        }
    }
    val lastHint = remember(pick) { LastOffset() }
    val hintAt = { lastHint.of(pick.tooLargeAt) }
    Anchored(above = hintAt, below = hintAt, chrome = chrome, modifier = modifier) {
        AnimatedVisibility(
            visible = pick.tooLargeAt.isSpecified,
            enter = fadeIn(SceneViewTokens.Motion.fade()),
            exit = fadeOut(SceneViewTokens.Motion.fade()),
        ) {
            ObjectLabel(
                title = stringResource(R.string.demo_ar_rerun_object_too_large),
                caption = stringResource(R.string.demo_ar_rerun_object_too_large_caption),
                description = null,
                modifier = Modifier.testTag("scan-object-too-large"),
            )
        }
    }
}

/**
 * The last specified offset it was given, so a label fading out stays where it was. While what it
 * names is [live], an unspecified offset is one too: a label still waiting for its place has none.
 */
private class LastOffset {
    private var last = Offset.Unspecified

    fun of(offset: Offset, live: Boolean = false): Offset {
        if (live || offset.isSpecified) last = offset
        return last
    }
}

@Composable
private fun ObjectSizeLabel(box: ScanBox) {
    val (length, depth, height) = box.sizes()
    ObjectLabel(
        title = stringResource(R.string.demo_ar_rerun_object_size, length, depth, height),
        caption = stringResource(R.string.demo_ar_rerun_object_size_caption),
        description = stringResource(R.string.demo_ar_rerun_object_size_description, length, depth, height),
        modifier = Modifier.testTag("scan-object-size"),
    )
}

/** A card of the stage's chrome: [title] over a quieter [caption]. */
@Composable
private fun ObjectLabel(title: String, caption: String, description: String?, modifier: Modifier = Modifier) {
    val chrome = LocalStageChrome.current
    val shape = RoundedCornerShape(SceneViewTokens.Radius.sm)
    Column(
        modifier = modifier
            .overMediaEdge(shape, chrome.edgeRing, chrome.edgeHalo)
            .background(chrome.card, shape)
            .semantics(mergeDescendants = true) {
                liveRegion = LiveRegionMode.Polite
                if (description != null) contentDescription = description
            }
            .padding(horizontal = Space.md, vertical = Space.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = SceneViewTokens.Type.card.copy(color = chrome.onCard, fontFeatureSettings = "tnum"))
        Text(caption, style = SceneViewTokens.Type.caption.copy(color = chrome.onCardMuted))
    }
}

/**
 * Lays [content] out centred over [above], a point of this layout in pixels — or under [below]
 * where [chrome], what the replay draws over the scene, leaves no room over it —, always in what
 * that chrome leaves free ([ScanChrome.labelAt]). The two points are read while placing, so a
 * label following a moving camera is only moved — never recomposed, never measured again.
 * [onHeight] is told how tall the content is once it has one.
 */
@Composable
private fun Anchored(
    above: () -> Offset,
    below: () -> Offset,
    chrome: ScanChrome,
    modifier: Modifier = Modifier,
    onHeight: (Int) -> Unit = {},
    content: @Composable () -> Unit,
) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val placeable = measurables.firstOrNull()?.measure(constraints.copy(minWidth = 0, minHeight = 0))
        if (placeable != null && placeable.height > 0) onHeight(placeable.height)
        layout(constraints.maxWidth, constraints.maxHeight) {
            val over = above()
            val under = below()
            if (placeable != null && over.isSpecified && under.isSpecified) {
                val (x, y) = chrome.labelAt(
                    over.x, over.y, under.y,
                    placeable.width, placeable.height, constraints.maxWidth, constraints.maxHeight,
                )
                placeable.place(x, y)
            }
        }
    }
}

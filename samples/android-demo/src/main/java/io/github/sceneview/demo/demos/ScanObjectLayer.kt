package io.github.sceneview.demo.demos

import android.os.SystemClock
import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
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
import io.github.sceneview.demo.demos.internal.LabelRect
import io.github.sceneview.demo.demos.internal.OrbitPose
import io.github.sceneview.demo.demos.internal.ReplayTap
import io.github.sceneview.demo.demos.internal.ReplayTapAnswer
import io.github.sceneview.demo.demos.internal.ScanBox
import io.github.sceneview.demo.demos.internal.ScanCard
import io.github.sceneview.demo.demos.internal.ScanChrome
import io.github.sceneview.demo.demos.internal.ScanLabels
import io.github.sceneview.demo.demos.internal.ScanObjectMeshes
import io.github.sceneview.demo.demos.internal.ScanObjects
import io.github.sceneview.demo.demos.internal.ScanPick
import io.github.sceneview.demo.demos.internal.ScanReveal
import io.github.sceneview.demo.demos.internal.ScanTaps
import io.github.sceneview.demo.demos.internal.scanCamera
import io.github.sceneview.demo.demos.internal.semantic.RecognizedItem
import io.github.sceneview.demo.demos.internal.semantic.SceneBoxMeshes
import io.github.sceneview.demo.demos.internal.semantic.SceneDetector
import io.github.sceneview.demo.demos.internal.semantic.SceneModel
import io.github.sceneview.demo.demos.internal.semantic.SceneModelState
import io.github.sceneview.demo.demos.internal.semantic.SceneRecognition
import io.github.sceneview.demo.demos.internal.semantic.SceneRecognizer
import io.github.sceneview.demo.demos.internal.semantic.allowSceneDetectorDownload
import io.github.sceneview.demo.demos.internal.semantic.loadSceneDetector
import io.github.sceneview.demo.demos.internal.semantic.nameRes
import io.github.sceneview.demo.demos.internal.semantic.recognitionLogLine
import io.github.sceneview.demo.demos.internal.semantic.recognizeInPasses
import io.github.sceneview.demo.demos.internal.semantic.sceneImageOf
import io.github.sceneview.demo.hdpack.hdPackSize
import io.github.sceneview.demo.theme.DebugPalette
import io.github.sceneview.demo.theme.LocalStageChrome
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.demo.theme.SceneViewTokens.Space
import io.github.sceneview.demo.ui.NarrationText
import io.github.sceneview.demo.ui.overMediaEdge
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.math.Position
import io.github.sceneview.math.colorOf
import io.github.sceneview.math.toLinearSpace
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt
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

    /** What the scan was recognised to hold, once it has been ([SceneRecognizer]): done once a replay. */
    var recognized: List<RecognizedItem>? = null
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
    parent: CoroutineScope,
    /** The user's go for the detector's download on a metered network ([SceneModelState.Metered]). */
    private val allowDownload: () -> Unit = {},
    /**
     * The 2D detector the scan's parts are recognised with, `null` without one. It may first have
     * to be downloaded, off the main thread: it says where it is ([SceneModelState]) as it goes.
     */
    private val loadDetector: suspend (onState: (SceneModelState) -> Unit) -> SceneDetector? = { null },
) {
    /**
     * This picker's own coroutines, under the composition's [parent] scope: [destroy] cancels them,
     * and a tap answered after it is dropped instead of uploaded into destroyed nodes (#4696).
     */
    private val taps = ScanTaps(parent)
    private val scope = taps.scope

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

    /** The recognised parts' boxes wear the quiet outline of what is not the selection. */
    private val recognizedMaterial = materialLoader.selectionMaterial(palette.otherOutline, 1f)

    private val tintNode = DebugLayerNode(engine, tintMaterial, TINT_PRIORITY)
    private val hiddenNode = DebugLayerNode(engine, hiddenMaterial, HIDDEN_PRIORITY)
    private val edgeNode = DebugLayerNode(engine, edgeMaterial, EDGE_PRIORITY)

    private val recognizedNode = DebugLayerNode(engine, recognizedMaterial, EDGE_PRIORITY)

    /** The three layers of the picked object, shown and hidden together. */
    private val pickNodes = listOf(tintNode, hiddenNode, edgeNode)

    /** Children of the replay's layer node, destroyed with it; [destroy] comes after. */
    val nodes: List<DebugLayerNode> = pickNodes + recognizedNode

    private val objects: Deferred<ScanObjects?> = scope.async(Dispatchers.Default) {
        runCatching { ScanObjects.of(dense.cloud, dense.voxelM) }
            .onFailure { Log.w(TAG, "The cloud could not be sorted: ${it.message}") }
            .getOrNull()
    }

    /** [ScanObjects.pick] keeps its scratch buffers: one tap at a time. */
    private val picking = Mutex()
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
     * What the label writes on [box] ([ReplayTap]): its sizes, the name recognition gave it, and
     * whether the scan saw it from one side only; `null` with nothing picked.
     */
    var answer: ReplayTapAnswer? by mutableStateOf(null)
        private set
    private var namingJob: Job? = null

    /**
     * The highest point the box is drawn at in the view, in pixels, over the middle of its top;
     * unspecified while it is not drawn.
     */
    var anchor: Offset by mutableStateOf(Offset.Unspecified)
        private set

    /** The lowest point it is drawn at, under [anchor]: where the label goes with no room above. */
    var foot: Offset by mutableStateOf(Offset.Unspecified)
        private set

    /** The parts of the room recognised in the scan, surest first; none until recognised. */
    var recognized: List<RecognizedItem> by mutableStateOf(emptyList())
        private set

    /**
     * Over the middle of each of [recognized]'s boxes' top, in pixels, in the same order;
     * unspecified for one not drawn now.
     */
    var recognizedAnchors: List<Offset> by mutableStateOf(emptyList())
        private set

    private val recognizedMesh = DebugMesh()
    private var recognizedFor: Triple<List<RecognizedItem>, Int, ArDebugStyle>? = null

    /** Where a tap found something too large to be one object, until the hint has been read. */
    var tooLargeAt: Offset by mutableStateOf(Offset.Unspecified)
        private set

    /**
     * Where the furniture detector is while this replay waits for it — downloading, or off for want
     * of it until the notice has been read —; `null` once it is ready, or with nothing to say.
     */
    var detectorState: SceneModelState? by mutableStateOf(null)
        private set
    private var noticeJob: Job? = null

    init {
        nodes.forEach { it.isVisible = false }
        // Back from the Camera view: the object picked before it is still the one picked, and the
        // camera is where it was left — nothing to bring out.
        selection.found?.takeIf { it.surfels.isNotEmpty() && it.surfels.last() < dense.cloud.count }?.let {
            show(it, Offset.Unspecified)
            reveal.settled()
        }
        val known = selection.recognized ?: when {
            QaRecognizedLabels.active() -> QaRecognizedLabels.items(dense.cloud).also { selection.recognized = it }
            QaReplayTapNames.active() -> QaReplayTapNames.items(dense.cloud).also { selection.recognized = it }
            else -> null
        }
        if (known != null) recognized = known else recognize()
        rename()
    }

    /**
     * Recognises the scan's parts, once, in two passes ([recognizeInPasses]): the openings in the
     * walls at once, then everything, the furniture included, once the detector is there — it may
     * first have to be downloaded, its progress in [detectorState]. The keyframes are read here,
     * from the trace, on the main thread that owns it; the photos, the detector and the lifting run
     * on [Dispatchers.Default], each lifting under the taps' own [picking] lock. Kept in [selection]
     * once the last pass is done: a view left before then recognises again when it is back.
     */
    private fun recognize() {
        val keyframes = SceneRecognizer.keyframes(replay.trace, replay.manifest.lens)
        scope.launch {
            val sorted = objects.await() ?: return@launch
            var last: SceneRecognition? = null
            recognizeInPasses(
                loadDetector = {
                    try {
                        loadDetector(::detectorIs)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "No scene detector: ${e.message}")
                        detectorIs(SceneModelState.Unusable)
                        null
                    }
                },
                recognize = { recognizer ->
                    withContext(Dispatchers.Default) {
                        try {
                            recognizer.recognize(
                                keyframes, dense.cloud, sorted,
                                image = { path -> sceneImageOf { replay.decode(path, it) } },
                                pickLimit = minOf(dense.cloud.count, DenseSurfels.MAX_SURFELS),
                                picking = picking,
                            )
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w(TAG, "The scan could not be recognised: ${e.message}")
                            null
                        }
                    }
                },
                onPass = { recognition ->
                    // `adb logcat -s ScanObject`. The first pass reads no photo by design — the
                    // openings come from the dense cloud —, so it says what the walls gave instead.
                    Log.d(TAG, recognitionLogLine(recognition))
                    last = recognition
                    recognized = recognition.items
                    rename()
                },
            )
            last?.let { selection.recognized = it.items }
        }
    }

    /** The user's tap on the detector's "Download" card: on a metered network, the download starts. */
    fun download() = allowDownload()

    /** Shows where the detector is: its progress while it downloads, a notice for a while when it is off. */
    private fun detectorIs(state: SceneModelState) {
        noticeJob?.cancel()
        detectorState = state.takeUnless { it == SceneModelState.Ready }
        if (state == SceneModelState.Offline || state == SceneModelState.Unavailable || state == SceneModelState.Unusable) {
            noticeJob = scope.launch {
                delay(NOTICE_MILLIS)
                detectorState = null
            }
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
        hintJob?.cancel()
        tooLargeAt = Offset.Unspecified
        val shown = shownSurfels()
        if (shown == 0) {
            taps.answered()
            show(ScanPick.Empty, Offset(x, y))
            return
        }
        val camera = orbit.scanCamera()
        val ray = camera.ray(x, y)
        val started = SystemClock.elapsedRealtimeNanos()
        // Named from what was recognised when the finger came down; a pass ending since names it again.
        val known = recognized
        taps.tap(
            work = {
                withContext(Dispatchers.Default) {
                    val pick = picking.withLock { objects.await()?.pick(camera.eye, ray, shown) } ?: ScanPick.Empty
                    val picked = SystemClock.elapsedRealtimeNanos()
                    Tapped(pick, ReplayTap.answer(dense.cloud, pick, known), SystemClock.elapsedRealtimeNanos() - picked)
                }
            },
            show = { tapped ->
                val millis = (SystemClock.elapsedRealtimeNanos() - started) / NANOS_PER_MILLI
                val named = tapped.answer?.let { a ->
                    ", ${a.label?.wireName ?: "unnamed"}${if (a.oneSided) ", seen from one side" else ""}," +
                        " named and measured in ${tapped.answerNanos / NANOS_PER_MICRO} µs"
                }.orEmpty()
                Log.d(TAG, "tap answered in $millis ms: ${describe(tapped.pick)}$named")
                show(tapped.pick, Offset(x, y), tapped.answer)
                // A pass that ended while the tap was worked on renamed the box picked before it:
                // this one is named again from what that pass knows.
                if (recognized !== known) rename()
            },
        )
    }

    /** What a tap's work hands back to the main thread: the pick, what its label says, and how long that took. */
    private class Tapped(val pick: ScanPick, val answer: ReplayTapAnswer?, val answerNanos: Long)

    /**
     * Names and measures the picked object again off the main thread — back from the Camera view,
     * or once a recognition pass knows more of the room —; dropped if another tap has picked since.
     */
    private fun rename() {
        val found = found ?: return
        val known = recognized
        namingJob?.cancel()
        namingJob = scope.launch {
            val named = withContext(Dispatchers.Default) { ReplayTap.answer(dense.cloud, found, known) }
            if (this@ScanObjectPick.found === found) answer = named
        }
    }

    private fun show(
        pick: ScanPick,
        at: Offset,
        named: ReplayTapAnswer? = (pick as? ScanPick.Found)?.let { ReplayTapAnswer(it.box, null, oneSided = false) },
    ) {
        found = pick as? ScanPick.Found
        selection.found = found
        if (found == null) reveal.settled() else reveal.picked()
        box = found?.box
        namingJob?.cancel()
        answer = named?.takeIf { found != null }
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
        val visible = found != null && found.surfels.isNotEmpty() && found.surfels.last() < shownSurfels()
        // Said again here: the pick this one replaced let go of the turntable as it left. And held
        // only while the box is drawn: with Points off, or scrubbed back to before the scan had
        // found the object, nothing is on screen to be read, and the room turns again (#4696).
        orbit.steady = visible
        if (visible != drawn) {
            drawn = visible
            pickNodes.forEach { it.isVisible = visible }
        }
        syncRecognized(style)
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
        val span = ScanObjectMeshes.drawnSpan(box, orbit.scanCamera(orbit.restingPose))
        anchor = span?.let { (x, high, _) -> Offset(x, high) } ?: Offset.Unspecified
        foot = span?.let { (x, _, low) -> Offset(x, low) } ?: Offset.Unspecified
    }

    /**
     * The recognised boxes drawn now — those every point of which is — at [style]'s width, and
     * where their labels go: none while a picked object's label is written.
     */
    private fun syncRecognized(style: ArDebugStyle) {
        val items = recognized
        if (items.isEmpty()) return
        val shown = shownSurfels()
        val all = minOf(dense.cloud.count, DenseSurfels.MAX_SURFELS)
        val now = Triple(items, shown, style)
        val drawnItems = items.map { SceneBoxMeshes.isShown(it, shown, all) }
        if (now != recognizedFor) {
            recognizedFor = now
            val boxes = items.filterIndexed { index, _ -> drawnItems[index] }.map { it.box }
            recognizedNode.upload(SceneBoxMeshes.edges(boxes, style.axisRadius, recognizedMesh))
            recognizedNode.isVisible = boxes.isNotEmpty()
        }
        val camera = orbit.scanCamera(orbit.restingPose)
        recognizedAnchors = items.mapIndexed { index, item ->
            val span = if (drawnItems[index] && !drawn) ScanObjectMeshes.drawnSpan(item.box, camera) else null
            span?.let { (x, high, _) -> Offset(x, high) } ?: Offset.Unspecified
        }
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
        // The pick under way, the cloud's sort and the hint: none of them shows anything any more.
        taps.cancel()
        listOf(tintMaterial, hiddenMaterial, edgeMaterial, recognizedMaterial).forEach {
            materialLoader.destroyMaterialInstance(it)
        }
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

        /** Long enough to read why recognition is off, two lines of it. */
        const val NOTICE_MILLIS = 4500L
        const val NANOS_PER_MILLI = 1_000_000L
        const val NANOS_PER_MICRO = 1_000L
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
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val kept = selection ?: remember(replay) { ScanSelection() }
    val pick = remember(engine, materialLoader, replay, session, orbit, palette, kept) {
        val dense = replay?.dense?.takeUnless { replay.growing }
        if (replay != null && dense != null) {
            ScanObjectPick(
                replay, dense, session, orbit, kept, engine, materialLoader, palette, scope,
                allowDownload = { allowSceneDetectorDownload(context) },
            ) { onState ->
                loadSceneDetector(context, onState)
            }
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
 * holds, a word saying so; and at its foot, while the furniture detector downloads or when it is
 * off, where it is. It takes no touch but the detector card's "Download" button, on a metered
 * network — the view under it keeps every other gesture.
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
    val answer = pick.answer
    // The last label and the last place stay through the fade out.
    var lastAnswer by remember(pick) { mutableStateOf(answer) }
    if (answer != null) lastAnswer = answer
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
            visible = answer != null && pick.drawn,
            enter = fadeIn(SceneViewTokens.Motion.fade()),
            exit = fadeOut(SceneViewTokens.Motion.fade()),
        ) {
            lastAnswer?.let { ReplayTapLabel(it) }
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
    // The recognised parts of the room, each named over its box, none over another; hidden under
    // a picked one's label.
    RecognizedLabels(pick, chrome, modifier)
    DetectorStatus(pick.detectorState, modifier, onDownload = pick::download)
}

/**
 * Where the furniture detector is, over the foot of the view: how much of it has downloaded — the
 * first replay that needs it fetches it —, on a metered network a button to download it
 * ([onDownload]), or why recognition is off. The replay plays on under it.
 */
@Composable
private fun DetectorStatus(state: SceneModelState?, modifier: Modifier, onDownload: () -> Unit) {
    val cover = LocalDemoSceneCover.current
    // The last state stays through the fade out.
    var last by remember { mutableStateOf(state) }
    if (state != null) last = state
    Box(modifier) {
        AnimatedVisibility(
            visible = state != null,
            enter = fadeIn(SceneViewTokens.Motion.fade()),
            exit = fadeOut(SceneViewTokens.Motion.fade()),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(start = Space.md, end = Space.md, bottom = cover.calculateBottomPadding() + Space.md),
        ) {
            when (val shown = last) {
                is SceneModelState.Downloading -> {
                    val size = hdPackSize(LocalContext.current, SceneModel.BYTES)
                    ObjectLabel(
                        title = stringResource(R.string.demo_ar_rerun_detector_downloading, size),
                        caption = stringResource(
                            R.string.demo_ar_rerun_detector_downloading_caption,
                            (shown.fraction * PERCENT).roundToInt(),
                        ),
                        description = stringResource(R.string.demo_ar_rerun_detector_downloading_description, size),
                        modifier = Modifier.testTag("scan-detector-downloading"),
                        progress = shown.fraction,
                    )
                }
                SceneModelState.Offline, SceneModelState.Unavailable, SceneModelState.Unusable -> ObjectLabel(
                    title = stringResource(R.string.demo_ar_rerun_detector_off),
                    caption = stringResource(
                        when (shown) {
                            SceneModelState.Offline -> R.string.demo_ar_rerun_detector_offline_caption
                            SceneModelState.Unavailable -> R.string.demo_ar_rerun_detector_unavailable_caption
                            else -> R.string.demo_ar_rerun_detector_unusable_caption
                        },
                    ),
                    description = null,
                    modifier = Modifier.testTag("scan-detector-off"),
                )
                SceneModelState.Metered -> {
                    val chrome = LocalStageChrome.current
                    ObjectLabel(
                        title = stringResource(R.string.demo_ar_rerun_detector_metered),
                        caption = stringResource(R.string.demo_ar_rerun_detector_metered_caption),
                        description = null,
                        modifier = Modifier.testTag("scan-detector-metered"),
                    ) {
                        Button(
                            onClick = onDownload,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = chrome.accent,
                                contentColor = chrome.onAccent,
                            ),
                            modifier = Modifier.padding(top = Space.sm).testTag("scan-detector-download"),
                        ) {
                            Text(
                                stringResource(
                                    R.string.demo_ar_rerun_detector_download,
                                    hdPackSize(LocalContext.current, SceneModel.BYTES),
                                ),
                            )
                        }
                    }
                }
                SceneModelState.Ready, null -> Unit
            }
        }
    }
}

/**
 * The labels of [pick]'s recognised parts, laid out together so that none covers another
 * ([ScanLabels.declutter]): each over its box where it is free, else slid to the nearest free
 * place with a leader line down to its box, or left out when no place is near — the surest
 * keep theirs first. The boxes' points are read while placing, so the labels follow the camera
 * without being recomposed; the leader lines are drawn behind them.
 */
@Composable
private fun RecognizedLabels(pick: ScanObjectPick, chrome: ScanChrome, modifier: Modifier) {
    val items = pick.recognized
    if (items.isEmpty()) return
    val stage = LocalStageChrome.current
    val density = LocalDensity.current
    val line = with(density) { SceneViewTokens.Layout.hairlineWidth.toPx() }
    val dot = with(density) { (Space.xs / 2).toPx() }
    val maxShift = with(density) { (Space.x4l + Space.x2l).roundToPx() }
    var leaders by remember(pick) { mutableStateOf(emptyList<Pair<Offset, Offset>>()) }
    Layout(
        content = { items.forEach { RecognizedLabel(it) } },
        modifier = modifier.drawBehind {
            leaders.forEach { (from, to) ->
                drawLine(stage.onCardMuted, from, to, strokeWidth = line)
                drawCircle(stage.onCardMuted, radius = dot, center = from)
            }
        },
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val placeables = measurables.map { it.measure(loose) }
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        layout(width, height) {
            val anchors = pick.recognizedAnchors
            val wanted = placeables.mapIndexed { index, placeable ->
                val at = anchors.getOrElse(index) { Offset.Unspecified }
                if (!at.isSpecified || placeable.height <= 0) {
                    null
                } else {
                    val (x, y) = chrome.labelAt(at.x, at.y, at.y, placeable.width, placeable.height, width, height)
                    LabelRect(x, y, placeable.width, placeable.height)
                }
            }
            val placed = ScanLabels.declutter(
                wanted,
                area = chrome.labelArea(width, height),
                gap = chrome.gap,
                maxShift = maxShift,
                obstacles = listOfNotNull(chrome.cardRect(width)),
            )
            val lines = ArrayList<Pair<Offset, Offset>>()
            placed.forEachIndexed { index, rect ->
                if (rect == null) return@forEachIndexed
                placeables[index].place(rect.left, rect.top)
                if (rect != wanted[index]) {
                    val at = anchors[index]
                    ScanLabels.leaderEnd(at.x, at.y, rect)?.let { (x, y) -> lines += at to Offset(x, y) }
                }
            }
            if (lines != leaders) leaders = lines
        }
    }
}

/** A recognised part of the room: what it is and how sure the scan is, over its box's three dimensions. */
@Composable
private fun RecognizedLabel(item: RecognizedItem) {
    val (length, depth, height) = ReplayTap.sizes(item.box, LocalLocale.current.platformLocale)
    val name = stringResource(item.label.nameRes())
    val sure = (item.confidence * PERCENT).roundToInt()
    val chrome = LocalStageChrome.current
    val shape = RoundedCornerShape(SceneViewTokens.Radius.sm)
    val description = stringResource(R.string.demo_ar_rerun_recognized_description, name, sure)
    Column(
        modifier = Modifier
            .testTag("scan-recognized-${item.label.wireName}")
            .overMediaEdge(shape, chrome.edgeRing, chrome.edgeHalo)
            .background(chrome.card, shape)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(horizontal = Space.sm, vertical = Space.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(name, style = SceneViewTokens.Type.caption.copy(color = chrome.onCard))
            Text(
                stringResource(R.string.demo_ar_rerun_recognized_confidence, sure),
                style = SceneViewTokens.Type.caption.copy(color = chrome.onCardMuted, fontFeatureSettings = "tnum"),
                modifier = Modifier.padding(start = Space.xs),
            )
        }
        Text(
            stringResource(R.string.demo_replay_tap_sizes, length, depth, height),
            style = SceneViewTokens.Type.caption.copy(color = chrome.onCardMuted, fontFeatureSettings = "tnum"),
        )
    }
}

private const val PERCENT = 100f

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

/**
 * A card of the stage's chrome: [title] over a quieter [caption], and [action] under them when it
 * has one. With a [progress], the title narrates a step under way, and the card reads as a
 * progress bar instead of announcing each change.
 */
@Composable
private fun ObjectLabel(
    title: String,
    caption: String,
    description: String?,
    modifier: Modifier = Modifier,
    progress: Float? = null,
    action: (@Composable () -> Unit)? = null,
) {
    val chrome = LocalStageChrome.current
    val shape = RoundedCornerShape(SceneViewTokens.Radius.sm)
    Column(
        modifier = modifier
            .overMediaEdge(shape, chrome.edgeRing, chrome.edgeHalo)
            .background(chrome.card, shape)
            .semantics(mergeDescendants = true) {
                if (progress == null) {
                    liveRegion = LiveRegionMode.Polite
                } else {
                    progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f)
                }
                if (description != null) contentDescription = description
            }
            .padding(horizontal = Space.md, vertical = Space.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val titleStyle = SceneViewTokens.Type.card.copy(color = chrome.onCard, fontFeatureSettings = "tnum")
        if (progress == null) Text(title, style = titleStyle) else NarrationText(title, style = titleStyle)
        Text(caption, style = SceneViewTokens.Type.caption.copy(color = chrome.onCardMuted))
        action?.invoke()
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

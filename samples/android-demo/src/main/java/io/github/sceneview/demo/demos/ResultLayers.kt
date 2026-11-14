package io.github.sceneview.demo.demos

import android.util.Log
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.withFrameMillis
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.google.android.filament.Engine
import io.github.sceneview.SceneScope
import io.github.sceneview.core.splat.SplatCloud
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.internal.DenseCloud
import io.github.sceneview.demo.demos.internal.DenseFusion
import io.github.sceneview.demo.demos.internal.DepthBackProjection
import io.github.sceneview.demo.demos.internal.model.SplatFaces
import io.github.sceneview.demo.demos.internal.model.SurfelSplats
import io.github.sceneview.demo.demos.internal.model.TexturedSyntheticRoom
import io.github.sceneview.demo.theme.LocalStageChrome
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.demo.theme.SceneViewTokens.Space
import io.github.sceneview.demo.ui.ConnectedChoiceRow
import io.github.sceneview.demo.ui.overMediaEdge
import io.github.sceneview.math.Position
import io.github.sceneview.model.ModelInstance
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext

/*
 * The scan result's layers: Photo — the scan's surfels drawn as flat gaussian
 * splats ([SurfelSplats]), soft and continuous, with no meshing holes — Model, the textured mesh,
 * and Plan, the same model seen from above. Photo is a preview and says so: every measure stays
 * on the model (the grid's dimensions are read off its walls, never off the splats).
 *
 * Offered on every scan that has surfels to draw it from ([photoOffered]): it was a debug-and-QA
 * preview, which no one saw in the store app. Wired into `RerunModelUi.kt` by four
 * calls: [rememberResultLayers], [ResultLayerPicker] in the bottom band, [ResultPhotoLayer] in
 * the scene and [ResultLayerVeil] over it.
 */

/** One position of the result's layer switch. */
internal enum class ResultLayer(val key: String) {
    Photo("photo"),
    Model("model"),
    Plan("plan"),
}

/**
 * The result's layer state: what the user picked ([photoPicked]), the splats once built
 * ([splats]), and what the scene draws ([photoShown]), which follows the pick across a short dip
 * of the stage colour ([veil]) so the switch is never a cut.
 */
@Stable
internal class ResultLayers(val offered: Boolean, photoFirst: Boolean = false) {
    /**
     * Photo picked over Model (the Plan position is the screen's own `plan`). Picked from the
     * start on a result that opens on its Photo layer ([photoFirstCloud]).
     */
    var photoPicked by mutableStateOf(photoFirst)

    /**
     * Whether the layer shown is one the user chose on the switch. Until then a result opened on
     * its Photo layer hands over to its model once that is finished ([handsOverToModel]).
     */
    var userPicked by mutableStateOf(false)

    /**
     * The share of the splats drawn, 0–1, in the order the scan found them ([SurfelSplats] keeps
     * the map's insertion order): a result that opens on its Photo layer grows on the stage the
     * way it was scanned ([grow]), instead of standing there whole on the first frame.
     */
    var grown by mutableFloatStateOf(if (photoFirst) 0f else 1f)

    /** The [grow] that owns [grown]: a newer one takes it over. */
    internal var growRun = 0
        internal set

    /** The splats of the scan's surfels; `null` until built, or when it has none. */
    var splats by mutableStateOf<SplatCloud?>(null)
        internal set

    /** [splats] by the way they face, so the room opens as the model does ([SplatFaces]). */
    var faces by mutableStateOf<SplatFaces?>(null)
        internal set

    /** The [faces] the eye sees, as [SplatFaces.visible] last read them; all until the first frame. */
    var seen by mutableIntStateOf(SplatFaces.ALL)
        private set

    /**
     * Reads the faces seen from the eye at [eye], each frame; the scene redraws only when they
     * change, and they change only once the eye is clearly past a wall's plane
     * ([SplatFaces.HYSTERESIS_M]).
     */
    fun look(eye: Position) {
        val faces = faces ?: return
        val mask = faces.visible(eye.x, eye.y, eye.z, seen)
        if (mask != seen) seen = mask
    }

    /** The last filters read off [faces], by face mask, newest last: an orbit back reuses them. */
    private val shown = LinkedHashMap<Int, BooleanArray?>()

    /**
     * Which of [faces]' splats are in the faces of [mask] ([SplatFaces.shown]): read once
     * (O(splats)), then kept for the next time the eye comes back to them. Called off the main
     * thread, one call at a time.
     */
    @Synchronized
    internal fun shownOf(faces: SplatFaces, mask: Int): BooleanArray? {
        if (shown.containsKey(mask)) return shown.remove(mask).also { shown[mask] = it }
        val filter = faces.shown(mask)
        shown[mask] = filter
        while (shown.size > SHOWN_FILTERS) shown.remove(shown.keys.first())
        return filter
    }

    /** Whether the building found nothing to draw: Photo then stays on the model. */
    var unavailable by mutableStateOf(false)
        internal set

    /** Whether the scene draws the splats in the model's place. */
    var photoShown by mutableStateOf(false)
        internal set

    /** The stage colour over the scene, 0–1, while the layers swap. */
    val veil = Animatable(0f)

    /** What the switch shows as selected. */
    fun selected(plan: Boolean): ResultLayer = when {
        plan -> ResultLayer.Plan
        photoPicked -> ResultLayer.Photo
        else -> ResultLayer.Model
    }
}

/**
 * The layers of the result of [source], kept while [key] (what it is framed by) stays. The
 * splats are built off the main thread once the model is [settled], never beside its build —
 * except on a result that opens on them ([photoFirst]): they are what is seen while the model is
 * built, and are built first — or handed over ready-made ([prebuilt]).
 *
 * [surfels] are the scan's own, read with its recording: the splats come from them when there is
 * no [source], as when the model is read back from the scan's cache rather than built again
 * (#4896). The splats need the surfels only, never the reconstruction.
 */
@Composable
internal fun rememberResultLayers(
    source: RerunModelSource?,
    surfels: DenseCloud?,
    key: Any?,
    settled: Boolean,
    photoFirst: Boolean = false,
    prebuilt: SplatFaces? = null,
): ResultLayers {
    val offered = photoOffered(source, surfels)
    val layers = remember(source, surfels, key, offered) {
        ResultLayers(offered, photoFirst).also { layers ->
            // Reuse any splats the caller prepared before opening the result.
            prebuilt?.takeIf { it.all.count > 0 }?.let {
                layers.faces = it
                layers.splats = it.all
                if (photoFirst) {
                    // A scan handed over from its live model is already whole. Keep it visible
                    // instead of replaying its growth from an empty stage after Done.
                    layers.photoShown = true
                    layers.grown = 1f
                }
            }
        }
    }
    val build = settled || photoFirst
    LaunchedEffect(layers, build) {
        if (!layers.offered || !build || layers.splats != null || layers.unavailable) return@LaunchedEffect
        val started = System.nanoTime()
        val built = withContext(Dispatchers.Default) {
            runCatching { photoCloudOf(source, surfels)?.let { SplatFaces.of(SurfelSplats.ofPlace(it)) } }
                .onFailure { if (it is CancellationException) throw it }
                .onFailure { Log.w(LAYERS_TAG, "photo layer not built", it) }
                .getOrNull()
        }
        Log.i(LAYERS_TAG, "photo layer: ${built?.all?.count ?: 0} splats in ${(System.nanoTime() - started) / NS_PER_MS} ms")
        if (built != null && built.all.count > 0) {
            layers.faces = built
            layers.splats = built.all
        } else {
            layers.unavailable = true
        }
    }
    // The swap: the stage colour comes up, the layers trade places under it, it goes again. Two
    // frames under the veil let the splats' first back-to-front sort land before they are seen.
    // Opacity only, so it stays under reduced motion.
    LaunchedEffect(layers) { layers.swapUnderVeil() }
    return layers
}

/**
 * Runs the swaps of [ResultLayers]: on each change of layer the veil comes up, the layers trade
 * places under it, and it goes again. A change that reverses one mid-rise cancels it with the veil
 * part way up; the veil then goes again from there, whether or not the layers traded places.
 */
internal suspend fun ResultLayers.swapUnderVeil() {
    snapshotFlow { photoPicked && splats != null }.collectLatest { photo ->
        if (photo && !photoShown && grown < 1f) {
            // A result that opens on its Photo layer: nothing is drawn yet for a veil to hide.
            // The layer grows from nothing ([grow]).
            photoShown = true
        } else if (photo != photoShown) {
            veil.animateTo(VEIL_PEAK, tween(SceneViewTokens.Duration.handoverMillis, easing = FastOutSlowInEasing))
            photoShown = photo
            repeat(SORT_FRAMES) { withFrameNanos { } }
        }
        if (veil.value > 0f) {
            veil.animateTo(0f, tween(SceneViewTokens.Duration.mediumMillis, easing = SceneViewTokens.Ease.expressive))
        }
    }
}

/**
 * Grows the Photo layer from nothing to the whole scan in [GROW_MS], fast at first so the first
 * second already shows most of the place; whole at once without [animate] (reduced motion).
 */
internal suspend fun ResultLayers.grow(animate: Boolean) {
    if (!animate) {
        grown = 1f
        return
    }
    // One growth at a time: a second one (the film's, asked while the opening one runs) takes
    // over, and the first stops writing rather than pull the layer back and forth.
    val run = ++growRun
    grown = 0f
    val start = withFrameMillis { it }
    while (grown < 1f && run == growRun) {
        val share = grownShare(withFrameMillis { (it - start) / GROW_MS })
        if (run == growRun) grown = share
    }
}

/** The share of the scan drawn [t] of the way through [grow], 0–1: an ease-out, never past the whole. */
internal fun grownShare(t: Float): Float {
    val left = 1f - t.coerceIn(0f, 1f)
    return 1f - left * left * left
}

/**
 * The splats drawn while the layer is [share] grown: the first [share] of the [count], in their
 * own order, among those [faces] lets through (`null`: all of them). [faces] itself once whole.
 */
internal fun grownFilter(faces: BooleanArray?, count: Int, share: Float): BooleanArray? {
    if (share >= 1f) return faces
    val upTo = (count * share.coerceAtLeast(0f)).toInt()
    return BooleanArray(count) { it < upTo && (faces == null || faces[it]) }
}

/**
 * Whether a result opened on its Photo layer now shows its model instead: once the model is
 * finished and ready to be drawn ([modelReady]), unless the user chose a layer meanwhile or a
 * film is being taken of the one shown ([busy]). The Photo layer is what is seen while the model
 * is built; the model, sharper, is what the result rests on.
 */
internal fun ResultLayers.handsOverToModel(modelReady: Boolean, busy: Boolean): Boolean =
    photoPicked && !userPicked && modelReady && !busy

/** Whether there is anything to draw the Photo layer from: a model's [source] or the scan's [surfels]. */
internal fun photoOffered(source: RerunModelSource?, surfels: DenseCloud?): Boolean =
    source != null || (surfels?.count ?: 0) > 0

/**
 * The surfels a result opens on at once, as its Photo layer, while its model is built behind them:
 * a scan's own, with the normals its frame is read from ([ScanResultFraming.of]). `null` for a
 * result that opens on its model — the QA room, whose surfels are ray-cast first, or a scan with
 * none. A scan was a black stage for as long as its model took, 45 s for a garden on a Pixel 4a.
 */
internal fun photoFirstCloud(source: RerunModelSource?, surfels: DenseCloud?): DenseCloud? =
    if (source is RerunModelSource.Synthetic) null else photoCloudOf(source, surfels)?.takeIf { it.normals != null }

/**
 * The surfels the Photo layer is drawn from: the scan's, or the QA room's, textured as its model
 * is; the scan's own [surfels] when its model was read back with no [source] to build it from.
 */
internal fun photoCloudOf(source: RerunModelSource?, surfels: DenseCloud?): DenseCloud? = when (source) {
    is RerunModelSource.Live -> source.cloud ?: surfels
    is RerunModelSource.Surfels -> source.cloud
    is RerunModelSource.Synthetic -> DenseFusion().apply {
        TexturedSyntheticRoom.frames().forEach { add(DepthBackProjection.project(it)) }
    }.cloud(minViews = DenseFusion.MIN_VIEWS)
    null -> surfels
}?.takeIf { it.count > 0 }

/**
 * Photo | Model | Plan, the over-media mode pill (`mode-pill-*`, `DESIGN.md`) at the foot of the
 * bottom band, with the line that says Photo is a preview above it. Laid out from the start and
 * only faded in once the model is seen ([ready]), so it never moves the model's framing.
 * [onPlan] moves the camera between the 3D view and the plan, as the dock does.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ResultLayerPicker(
    layers: ResultLayers,
    plan: Boolean,
    ready: Boolean,
    onPlan: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    seen: Boolean = ready,
) {
    if (!layers.offered) return
    // The dock's Plan leaves Photo too: the plan is read on the model.
    LaunchedEffect(plan) { if (plan) layers.photoPicked = false }
    // A scan with nothing to splat goes back to the model rather than stay on a Photo it lacks.
    LaunchedEffect(layers.unavailable) { if (layers.unavailable) layers.photoPicked = false }
    // [seen] before it is [ready], it is there but dimmed and deaf: what the result will offer once
    // its model is built, in the place it will be offered — no hole between the line above and the bar.
    val alpha by animateFloatAsState(
        targetValue = if (ready) 1f else if (seen) NOT_READY_ALPHA else 0f,
        animationSpec = tween(SceneViewTokens.Duration.fadeMillis),
        label = "layers",
    )
    val chrome = LocalStageChrome.current
    val pill = SceneViewTokens.ModePill
    val note = when {
        !ready || !layers.photoPicked || plan -> null
        layers.unavailable -> null
        layers.splats == null -> stringResource(R.string.scan_result_photo_preparing)
        else -> stringResource(R.string.scan_result_photo_preview)
    }
    val noteAlpha by animateFloatAsState(if (note != null) 1f else 0f, tween(SceneViewTokens.Duration.fadeMillis), label = "note")
    // The last note stays written while it fades out.
    var lastNote by remember { mutableStateOf("") }
    if (note != null) lastNote = note
    Column(
        modifier = modifier
            .alpha(alpha)
            .then(if (ready) Modifier else Modifier.clearAndSetSemantics { })
            .testTag(RESULT_LAYERS_TAG),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val shape = RoundedCornerShape(SceneViewTokens.Radius.full)
        // Space kept for the note at all times: it comes and goes without moving the pill.
        Text(
            text = lastNote.ifEmpty { stringResource(R.string.scan_result_photo_preview) },
            style = SceneViewTokens.Type.caption,
            color = chrome.onCard,
            modifier = Modifier
                .alpha(noteAlpha)
                .padding(bottom = Space.sm)
                .clip(shape)
                .background(chrome.card, shape)
                .overMediaEdge(shape, chrome.edgeRing, chrome.edgeHalo)
                .padding(horizontal = Space.md, vertical = Space.xs)
                .then(if (note != null) Modifier else Modifier.clearAndSetSemantics { }),
        )
        Surface(
            shape = CircleShape,
            color = pill.container,
            contentColor = pill.onContainer,
            border = BorderStroke(pill.outlineWidth, pill.outline),
        ) {
            ConnectedChoiceRow(
                options = ResultLayer.entries,
                selected = layers.selected(plan),
                onSelect = { picked ->
                    if (!ready) return@ConnectedChoiceRow
                    layers.userPicked = true
                    // A scan with nothing to splat stays on the model.
                    layers.photoPicked = picked == ResultLayer.Photo && !layers.unavailable
                    val toPlan = picked == ResultLayer.Plan
                    if (toPlan != plan) onPlan(toPlan)
                },
                label = { stringResource(it.labelRes) },
                modifier = Modifier.padding(horizontal = Space.xs),
                optionTestTag = { "result_layer_${it.key}" },
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
}

private val ResultLayer.labelRes: Int
    get() = when (this) {
        ResultLayer.Photo -> R.string.scan_result_layer_photo
        ResultLayer.Model -> R.string.scan_result_layer_model
        ResultLayer.Plan -> R.string.scan_result_layer_plan
    }

/**
 * The Photo layer in the scene: the splats while [ResultLayers.photoShown], and the model
 * [instance] hidden under them meanwhile (its renderables' layer mask), shown again when Photo
 * goes — if it is still [live], its owner's word. The splats sort back to front against the
 * scene's camera on their own (`SplatNode`).
 */
@Composable
internal fun SceneScope.ResultPhotoLayer(layers: ResultLayers, instance: ModelInstance?, live: () -> Boolean = { true }) {
    val faces = layers.faces?.takeIf { layers.photoShown }
    val seen = layers.seen
    // The faces turned away from the eye left out, as the model's single-sided surfaces are; a new
    // set only when the eye crosses a wall's plane. One node draws the whole cloud and the set is a
    // filter its next sort applies, off the main thread (#4896): a crossing re-packs no texture and
    // builds no node, and the splats drawn so far stay until the filter is ready.
    var shownSplats by remember(faces) { mutableStateOf<BooleanArray?>(null) }
    var filtered by remember(faces) { mutableStateOf(false) }
    // While the layer grows ([ResultLayers.grow]) the filter also holds back the splats not yet
    // reached, a few steps a second: each step is one more sort, never a new node.
    val step = (layers.grown * GROW_STEPS).toInt().coerceIn(0, GROW_STEPS)
    LaunchedEffect(faces, seen, step) {
        val from = faces ?: return@LaunchedEffect
        val started = System.nanoTime()
        val next = withContext(Dispatchers.Default) {
            grownFilter(layers.shownOf(from, seen), from.all.count, step.toFloat() / GROW_STEPS)
        }
        if (!filtered || next !== shownSplats) {
            if (step == GROW_STEPS) {
                Log.i(LAYERS_TAG, "photo faces ${seen.toString(2)}: ${next?.count { it } ?: from.all.count} splats shown in ${(System.nanoTime() - started) / NS_PER_MS} ms")
            }
            shownSplats = next
            filtered = true
        }
    }
    // Built once the first filter is read, so the faces turned away are never drawn, even under the veil.
    faces?.takeIf { filtered }?.let { from ->
        val building = System.nanoTime()
        SplatNode(
            splatCloud = from.all,
            shownSplats = shownSplats,
            apply = { Log.i(LAYERS_TAG, "photo node: ${from.all.count} splats built in ${(System.nanoTime() - building) / NS_PER_MS} ms") },
        )
    }
    val photo = faces != null
    // Keyed on the instance too: a finished model that takes a raw surface's place is hidden as well.
    // Only an instance still [live] is shown again: its owner is forgotten in the screen's own
    // composition, before this one, both when a finished model replaces a raw surface and when the
    // screen is left, and reading the entities of a destroyed instance is a native crash.
    DisposableEffect(instance, photo) {
        if (photo) instance?.let { modelShown(engine, it, false) }
        onDispose { if (photo && live()) instance?.let { modelShown(engine, it, true) } }
    }
}

/** Shows or hides every renderable of [instance], as `SplatNode` hides a batch (layer mask). */
private fun modelShown(engine: Engine, instance: ModelInstance, shown: Boolean) {
    val manager = engine.renderableManager
    instance.entities.forEach { entity ->
        if (manager.hasComponent(entity)) {
            manager.setLayerMask(manager.getInstance(entity), ALL_LAYERS, if (shown) DEFAULT_LAYER else 0)
        }
    }
}

/** The stage colour [ground] over the scene while the layers swap; nothing the rest of the time. */
@Composable
internal fun ResultLayerVeil(layers: ResultLayers, ground: Color, modifier: Modifier = Modifier) {
    val veil = layers.veil.value
    if (veil > 0f) Box(modifier.alpha(veil).background(ground))
}

/** The [ResultLayerPicker] while its model is not there yet: Material's disabled content. */
private const val NOT_READY_ALPHA = 0.38f

/** Test tag of the [ResultLayerPicker]. */
internal const val RESULT_LAYERS_TAG = "result-layers"

/** How far the stage colour comes up at the swap: the room stays faintly seen through it. */
internal const val VEIL_PEAK = 0.35f

/** Face filters [ResultLayers.shownOf] keeps: the one drawn and the one an orbit back returns to. */
private const val SHOWN_FILTERS = 2

/** How long a result's Photo layer takes to grow from nothing to the whole scan ([grow]). */
internal const val GROW_MS = 2_400f

/** The steps the growth is drawn in: one filter and one sort each, 25 a second. */
internal const val GROW_STEPS = 60

/** Frames (~0.1 s) the swap waits under the veil for the splats' first sort. */
private const val SORT_FRAMES = 6

private const val ALL_LAYERS = 0xff

/** Filament's default layer: the one a renderable is built on. */
private const val DEFAULT_LAYER = 0x01

private const val NS_PER_MS = 1_000_000L
private const val LAYERS_TAG = "ResultLayers"

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
import androidx.compose.runtime.mutableStateOf
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
import io.github.sceneview.demo.BuildConfig
import io.github.sceneview.demo.DemoSettings
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
 * The scan result's layers (QA preview): Photo — the scan's surfels drawn as flat gaussian
 * splats ([SurfelSplats]), soft and continuous, with no meshing holes — Model, the textured mesh,
 * and Plan, the same model seen from above. Photo is a preview and says so: every measure stays
 * on the model (the grid's dimensions are read off its walls, never off the splats).
 *
 * Offered only in QA mode ([DemoSettings.qaMode]) for now. Wired into `RerunModelUi.kt` by four
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
    /** Photo picked over Model (the Plan position is the screen's own `plan`). */
    var photoPicked by mutableStateOf(photoFirst)

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
 * The layers of the result of [source], kept while [key] (the model it is framed by) stays. The
 * splats are built off the main thread once the model is [settled], never beside its build.
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
    /**
     * A room's result (Live Room): the layers are always offered and open on Photo, the scan as
     * it was taken, never worse than it; the screen moves to Model when its finished model
     * passed the quality gate.
     */
    photoFirst: Boolean = false,
    /** Whether the layers are offered whatever the QA mode: a room's result, on its shell or not. */
    offer: Boolean = photoFirst,
): ResultLayers {
    // Debug builds only until it is measured on a device: QA mode can be switched on in the store app.
    val offered = (offer || BuildConfig.DEBUG && DemoSettings.qaMode) && photoOffered(source, surfels)
    val layers = remember(source, surfels, key, offered) { ResultLayers(offered, photoFirst = photoFirst && offered) }
    LaunchedEffect(layers, settled) {
        if (!layers.offered || !settled || layers.splats != null || layers.unavailable) return@LaunchedEffect
        val started = System.nanoTime()
        val built = withContext(Dispatchers.Default) {
            runCatching { photoCloudOf(source, surfels)?.let { SplatFaces.of(SurfelSplats.of(it)) } }
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
        if (photo != photoShown) {
            veil.animateTo(VEIL_PEAK, tween(SceneViewTokens.Duration.handoverMillis, easing = FastOutSlowInEasing))
            photoShown = photo
            repeat(SORT_FRAMES) { withFrameNanos { } }
        }
        if (veil.value > 0f) {
            veil.animateTo(0f, tween(SceneViewTokens.Duration.mediumMillis, easing = SceneViewTokens.Ease.expressive))
        }
    }
}

/** Whether there is anything to draw the Photo layer from: a model's [source] or the scan's [surfels]. */
internal fun photoOffered(source: RerunModelSource?, surfels: DenseCloud?): Boolean =
    source != null || (surfels?.count ?: 0) > 0

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
    /** The model first, Photo second: a room's shell, which the result opens on. */
    modelFirst: Boolean = false,
) {
    if (!layers.offered) return
    // The dock's Plan leaves Photo too: the plan is read on the model.
    LaunchedEffect(plan) { if (plan) layers.photoPicked = false }
    // A scan with nothing to splat goes back to the model rather than stay on a Photo it lacks.
    LaunchedEffect(layers.unavailable) { if (layers.unavailable) layers.photoPicked = false }
    val alpha by animateFloatAsState(if (ready) 1f else 0f, tween(SceneViewTokens.Duration.fadeMillis), label = "layers")
    val chrome = LocalStageChrome.current
    val pill = SceneViewTokens.ModePill
    val note = when {
        !layers.photoPicked || plan -> null
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
                options = if (modelFirst) MODEL_FIRST else ResultLayer.entries,
                selected = layers.selected(plan),
                onSelect = { picked ->
                    if (!ready) return@ConnectedChoiceRow
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
 * goes. The splats sort back to front against the scene's camera on their own (`SplatNode`).
 */
@Composable
internal fun SceneScope.ResultPhotoLayer(layers: ResultLayers, instance: ModelInstance?) {
    val faces = layers.faces?.takeIf { layers.photoShown }
    val seen = layers.seen
    // The faces turned away from the eye left out, as the model's single-sided surfaces are; a new
    // set only when the eye crosses a wall's plane. One node draws the whole cloud and the set is a
    // filter its next sort applies, off the main thread (#4896): a crossing re-packs no texture and
    // builds no node, and the splats drawn so far stay until the filter is ready.
    var shownSplats by remember(faces) { mutableStateOf<BooleanArray?>(null) }
    var filtered by remember(faces) { mutableStateOf(false) }
    LaunchedEffect(faces, seen) {
        val from = faces ?: return@LaunchedEffect
        val started = System.nanoTime()
        val next = withContext(Dispatchers.Default) { layers.shownOf(from, seen) }
        if (!filtered || next !== shownSplats) {
            Log.i(LAYERS_TAG, "photo faces ${seen.toString(2)}: ${next?.count { it } ?: from.all.count} splats shown in ${(System.nanoTime() - started) / NS_PER_MS} ms")
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
    DisposableEffect(instance, photo) {
        if (photo) instance?.let { modelShown(engine, it, false) }
        onDispose { if (photo) instance?.let { modelShown(engine, it, true) } }
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

/** The switch's order when the result opens on the model: Model, Photo, Plan. */
private val MODEL_FIRST = listOf(ResultLayer.Model, ResultLayer.Photo, ResultLayer.Plan)

/** Test tag of the [ResultLayerPicker]. */
internal const val RESULT_LAYERS_TAG = "result-layers"

/** How far the stage colour comes up at the swap: the room stays faintly seen through it. */
internal const val VEIL_PEAK = 0.9f

/** Face filters [ResultLayers.shownOf] keeps: the one drawn and the one an orbit back returns to. */
private const val SHOWN_FILTERS = 2

/** Frames (~0.1 s) the swap waits under the veil for the splats' first sort. */
private const val SORT_FRAMES = 6

private const val ALL_LAYERS = 0xff

/** Filament's default layer: the one a renderable is built on. */
private const val DEFAULT_LAYER = 0x01

private const val NS_PER_MS = 1_000_000L
private const val LAYERS_TAG = "ResultLayers"

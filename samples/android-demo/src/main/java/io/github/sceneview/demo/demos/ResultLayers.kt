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
import io.github.sceneview.demo.DemoSettings
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.internal.DenseCloud
import io.github.sceneview.demo.demos.internal.DenseFusion
import io.github.sceneview.demo.demos.internal.DepthBackProjection
import io.github.sceneview.demo.demos.internal.model.SurfelSplats
import io.github.sceneview.demo.demos.internal.model.TexturedSyntheticRoom
import io.github.sceneview.demo.theme.LocalStageChrome
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.demo.theme.SceneViewTokens.Space
import io.github.sceneview.demo.ui.ConnectedChoiceRow
import io.github.sceneview.demo.ui.overMediaEdge
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
internal class ResultLayers(val offered: Boolean) {
    /** Photo picked over Model (the Plan position is the screen's own `plan`). */
    var photoPicked by mutableStateOf(false)

    /** The splats of the scan's surfels; `null` until built, or when it has none. */
    var splats by mutableStateOf<SplatCloud?>(null)
        internal set

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
 */
@Composable
internal fun rememberResultLayers(source: RerunModelSource?, key: Any?, settled: Boolean): ResultLayers {
    val offered = DemoSettings.qaMode && source != null
    val layers = remember(source, key, offered) { ResultLayers(offered) }
    LaunchedEffect(layers, settled) {
        if (!layers.offered || !settled || layers.splats != null || layers.unavailable) return@LaunchedEffect
        val started = System.nanoTime()
        val built = withContext(Dispatchers.Default) {
            runCatching { photoCloudOf(source)?.let { SurfelSplats.of(it) } }
                .onFailure { if (it is CancellationException) throw it }
                .onFailure { Log.w(LAYERS_TAG, "photo layer not built", it) }
                .getOrNull()
        }
        Log.i(LAYERS_TAG, "photo layer: ${built?.count ?: 0} splats in ${(System.nanoTime() - started) / NS_PER_MS} ms")
        if (built != null && built.count > 0) layers.splats = built else layers.unavailable = true
    }
    // The swap: the stage colour comes up, the layers trade places under it, it goes again. Two
    // frames under the veil let the splats' first back-to-front sort land before they are seen.
    // Opacity only, so it stays under reduced motion.
    LaunchedEffect(layers) {
        snapshotFlow { layers.photoPicked && layers.splats != null }.collectLatest { photo ->
            if (photo == layers.photoShown) return@collectLatest
            layers.veil.animateTo(VEIL_PEAK, tween(SceneViewTokens.Duration.handoverMillis, easing = FastOutSlowInEasing))
            layers.photoShown = photo
            repeat(SORT_FRAMES) { withFrameNanos { } }
            layers.veil.animateTo(0f, tween(SceneViewTokens.Duration.mediumMillis, easing = SceneViewTokens.Ease.expressive))
        }
    }
    return layers
}

/** The surfels the Photo layer is drawn from: the scan's, or the QA room's, textured as its model is. */
private fun photoCloudOf(source: RerunModelSource?): DenseCloud? = when (source) {
    is RerunModelSource.Live -> source.cloud
    is RerunModelSource.Surfels -> source.cloud
    is RerunModelSource.Synthetic -> DenseFusion().apply {
        TexturedSyntheticRoom.frames().forEach { add(DepthBackProjection.project(it)) }
    }.cloud(minViews = DenseFusion.MIN_VIEWS)
    null -> null
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
                options = ResultLayer.entries,
                selected = layers.selected(plan),
                onSelect = { picked ->
                    if (!ready) return@ConnectedChoiceRow
                    layers.photoPicked = picked == ResultLayer.Photo
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
    val splats = layers.splats?.takeIf { layers.photoShown }
    splats?.let { SplatNode(splatCloud = it) }
    val photo = splats != null
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

/** Test tag of the [ResultLayerPicker]. */
internal const val RESULT_LAYERS_TAG = "result-layers"

/** How far the stage colour comes up at the swap: the room stays faintly seen through it. */
private const val VEIL_PEAK = 0.9f

/** Frames (~0.1 s) the swap waits under the veil for the splats' first sort. */
private const val SORT_FRAMES = 6

private const val ALL_LAYERS = 0xff

/** Filament's default layer: the one a renderable is built on. */
private const val DEFAULT_LAYER = 0x01

private const val NS_PER_MS = 1_000_000L
private const val LAYERS_TAG = "ResultLayers"
